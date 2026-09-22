/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PackageLib {
    private PackageLib() {}

    public static void open(LuaState state, LuaTable globals) {
        LuaTable pkg = new LuaTable();
        fillInto(pkg, state, globals);
        globals.rawset(LuaString.interned("package"), pkg);
    }

    public static void fillInto(LuaTable pkg, LuaState state, LuaTable globals) {
        LuaTable loaded = new LuaTable();
        LuaTable preload = new LuaTable();

        pkg.rawset(LuaString.interned("loaded"), loaded);
        pkg.rawset(LuaString.interned("preload"), preload);
        pkg.rawset(LuaString.interned("path"), LuaString.interned("./?.lua;./?/init.lua;tests/lua-5.4.9-tests/?.lua"));
        pkg.rawset(LuaString.interned("cpath"), LuaString.interned("./?.so;./loadall.so"));
        pkg.rawset(LuaString.interned("config"), LuaString.valueOf("/\n;\n?\n!\n-\n"));

        // Register the standard libraries in package.loaded exactly as PUC
        // does at open time. Seed from the state's recorded stdlib tables,
        // NOT from the current globals: `all.lua` nils the globals and then
        // requires them back, which must still resolve (PUC keeps them in
        // package.loaded). Only the PUC standard names are registered; the
        // Luava-only `java`/`luajava` tables are intentionally excluded.
        loaded.rawset(LuaString.interned("_G"), globals);
        loaded.rawset(LuaString.interned("package"), pkg);
        for (String name : new String[]{"math", "string", "table", "coroutine", "utf8", "os", "io", "debug"}) {
            LuaTable lib = state.standardLibs().get(name);
            if (lib != null) {
                loaded.rawset(LuaString.valueOf(name), lib);
            }
        }

        LuaFunction searchpathFn = LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'package.searchpath'");
            String name = args[0].toLuaString();
            String path = args[1].toLuaString();
            String dirSep = "/";
            String sep = (args.length > 2 && !args[2].isNil()) ? args[2].toLuaString() : ".";
            String rep = (args.length > 3 && !args[3].isNil()) ? args[3].toLuaString() : dirSep;

            if (!sep.isEmpty()) {
                name = name.replace(sep, rep);
            }

            // PUC replaces '?' in the whole path first, then builds the error
            // from every path segment (including empty ones produced by ";;")
            // via pusherrornotfound: separators become "'\n\tno file '".
            // Search skips empty segments (getnextfilename) but the message
            // still lists them.
            String[] segments = path.split(";", -1);
            for (String template : segments) {
                if (template.isEmpty()) continue;
                String candidate = template.replace("?", name);
                try {
                    Path p = Path.of(candidate);
                    if (Files.exists(p) && !Files.isDirectory(p)) {
                        return LuaString.valueOf(candidate);
                    }
                } catch (Exception ignored) {
                }
            }
            StringBuilder tried = new StringBuilder();
            for (int i = 0; i < segments.length; i++) {
                if (i > 0) tried.append("\n\t");
                tried.append("no file '").append(segments[i].replace("?", name)).append("'");
            }
            return Varargs.of(LuaNil.NIL, LuaString.valueOf(tried.toString()));
        });
        pkg.rawset(LuaString.interned("searchpath"), searchpathFn);

        // searcher 1: preload
        LuaFunction searcherPreload = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue preloadVal = pkg.rawget(LuaString.interned("preload"));
            if (!preloadVal.isTable()) {
                throw new LuaException("'package.preload' must be a table");
            }
            LuaTable preloadTable = (LuaTable) preloadVal;
            LuaValue loader = preloadTable.rawget(modName);
            if (loader.isNil()) {
                return LuaString.valueOf("no field package.preload['" + modName.toLuaString() + "']");
            }
            return Varargs.of(loader, LuaString.interned(":preload:"));
        });

        // searcher 2: Lua files via package.path
        LuaFunction searcherLua = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue pathVal = pkg.rawget(LuaString.interned("path"));
            if (!pathVal.isString()) {
                throw new LuaException("'package.path' must be a string");
            }
            String nameStr = modName.toLuaString();
            LuaValue searchResult = searchpathFn.call(modName, pathVal);
            if (searchResult instanceof Varargs v && v.arg(1).isNil()) {
                return v.arg(2);
            }
            if (searchResult.isNil()) {
                return LuaNil.NIL;
            }
            String filename = searchResult.toLuaString();
            try {
                // Lua source is a byte stream; read with ISO-8859-1 so each
                // byte maps to one char (UTF-8 would collapse multi-byte
                // sequences and corrupt non-ASCII literals).
                String source = new String(Files.readAllBytes(Path.of(filename)),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                LuaFunction chunk = state.compile(source, "@" + filename, globals);
                return Varargs.of(chunk, LuaString.valueOf(filename));
            } catch (LuaException e) {
                throw new LuaException("error loading module '" + nameStr + "' from file '" + filename + "':\n\t" + e.getMessage());
            } catch (IOException e) {
                throw new LuaException("error loading module '" + nameStr + "' from file '" + filename + "':\n\t" + e.getMessage());
            }
        });

        // searcher 3: C files via package.cpath
        LuaFunction searcherC = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue cpathVal = pkg.rawget(LuaString.interned("cpath"));
            if (!cpathVal.isString()) {
                throw new LuaException("'package.cpath' must be a string");
            }
            LuaValue searchResult = searchpathFn.call(modName, cpathVal);
            if (searchResult instanceof Varargs v && v.arg(1).isNil()) {
                return v.arg(2);
            }
            return LuaNil.NIL;
        });

        // searcher 4: all-in-one loader for C submodules
        LuaFunction searcherCroot = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            String nameStr = modName.toLuaString();
            int dot = nameStr.indexOf('.');
            if (dot < 0) {
                return LuaNil.NIL;
            }
            String root = nameStr.substring(0, dot);
            LuaValue cpathVal = pkg.rawget(LuaString.interned("cpath"));
            if (!cpathVal.isString()) {
                throw new LuaException("'package.cpath' must be a string");
            }
            LuaValue searchResult = searchpathFn.call(LuaString.valueOf(root), cpathVal);
            if (searchResult instanceof Varargs v && v.arg(1).isNil()) {
                return v.arg(2);
            }
            return LuaNil.NIL;
        });

        // searcher 5: virtual resources (classpath/JAR/in-memory). Only active
        // when the host registered a LuaResourceLoader; otherwise this is a
        // no-op that adds no error text, so PUC search semantics are
        // unchanged. It is appended after the standard searchers so their
        // indices (and error-message ordering) stay PUC-compatible.
        LuaFunction searcherResource = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            org.luava.runtime.standard.LuaResourceLoader loader = state.getResourceLoader();
            if (loader == null) {
                return LuaNil.NIL;
            }
            String nameStr = modName.toLuaString();
            String resPath = state.getResourcePath();
            if (resPath == null || resPath.isEmpty()) {
                return LuaNil.NIL;
            }
            String dotted = nameStr.replace(".", "/");
            for (String template : resPath.split(";", -1)) {
                if (template.isEmpty()) {
                    continue;
                }
                String candidate = template.replace("?", dotted);
                String logical = candidate.startsWith("classpath:") ? candidate.substring("classpath:".length()) : candidate;
                String source = loader.read(logical);
                if (source == null) {
                    continue;
                }
                LuaFunction chunk = state.compile(source, "@" + candidate, globals);
                return Varargs.of(chunk, LuaString.valueOf(candidate));
            }
            return LuaNil.NIL;
        });

        LuaTable searchers = new LuaTable();
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(1), searcherPreload);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(2), searcherLua);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(3), searcherC);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(4), searcherCroot);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(5), searcherResource);
        pkg.rawset(LuaString.interned("searchers"), searchers);

        pkg.rawset(LuaString.interned("loadlib"), LuaFunction.of(args -> {
            return Varargs.of(LuaNil.NIL, LuaString.interned("dynamic libraries not supported"), LuaString.interned("absent"));
        }));

        // require(modname)
        globals.rawset(LuaString.interned("require"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isInteger() && !args[0].isFloat())) {
                throw new LuaException("bad argument #1 to 'require' (string expected)");
            }
            String nameStr = args[0].toLuaString();
            LuaString modName = LuaString.valueOf(nameStr);

            // 1. Check package.loaded
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            LuaTable loadedTable = (loadedVal instanceof LuaTable t) ? t : loaded;
            LuaValue cached = loadedTable.rawget(modName);
            if (!cached.isNil() && cached.toBoolean()) {
                return cached;
            }

            // 2. Guide by package.searchers
            LuaValue searchersVal = pkg.rawget(LuaString.interned("searchers"));
            if (!searchersVal.isTable()) {
                throw new LuaException("'package.searchers' must be a table");
            }
            LuaTable searchersTable = (LuaTable) searchersVal;
            StringBuilder msg = new StringBuilder();
            LuaValue loader = LuaNil.NIL;
            LuaValue loaderData = LuaNil.NIL;

            for (long i = 1; ; i++) {
                // PUC findloader prefixes every searcher's contribution with
                // "\n\t"; a searcher that returns no error message drops the
                // prefix again. The searchers themselves return bare messages
                // ("no file '...'", "no field package.preload[...]").
                LuaValue searcher = searchersTable.get(org.luava.runtime.LuaInteger.valueOf(i));
                if (searcher.isNil()) {
                    break;
                }
                LuaValue res = searcher.call(modName);
                if (res instanceof Varargs v) {
                    LuaValue first = v.arg(1);
                    if (first.isFunction() || first instanceof org.luava.runtime.LuaUserdata) {
                        loader = first;
                        loaderData = v.arg(2);
                        break;
                    } else if (first.isString()) {
                        msg.append("\n\t").append(first.toLuaString());
                    }
                } else if (res.isFunction() || res instanceof org.luava.runtime.LuaUserdata) {
                    loader = res;
                    break;
                } else if (res.isString()) {
                    msg.append("\n\t").append(res.toLuaString());
                }
            }

            if (loader.isNil()) {
                throw new LuaException("module '" + nameStr + "' not found:" + msg.toString());
            }
            LuaValue result = loader.call(modName, loaderData);
            LuaValue toCache;
            if (!result.isNil()) {
                toCache = result;
            } else {
                LuaValue current = loadedTable.rawget(modName);
                if (current.isNil()) {
                    toCache = org.luava.runtime.LuaBoolean.TRUE;
                } else {
                    toCache = current;
                }
            }
            loadedTable.rawset(modName, toCache);
            return Varargs.of(toCache, loaderData);
        }));
    }
}

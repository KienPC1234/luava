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
        LuaTable loaded = new LuaTable();
        LuaTable preload = new LuaTable();

        pkg.rawset(LuaString.valueOf("loaded"), loaded);
        pkg.rawset(LuaString.valueOf("preload"), preload);
        pkg.rawset(LuaString.valueOf("path"), LuaString.valueOf("./?.lua;./?/init.lua;tests/lua-5.4.9-tests/?.lua"));
        pkg.rawset(LuaString.valueOf("cpath"), LuaString.valueOf("./?.so;./loadall.so"));
        pkg.rawset(LuaString.valueOf("config"), LuaString.valueOf("/\n;\n?\n!\n-\n"));

        // Register default modules in package.loaded
        loaded.rawset(LuaString.valueOf("_G"), globals);
        loaded.rawset(LuaString.valueOf("package"), pkg);
        if (!globals.rawget(LuaString.valueOf("math")).isNil()) loaded.rawset(LuaString.valueOf("math"), globals.rawget(LuaString.valueOf("math")));
        if (!globals.rawget(LuaString.valueOf("string")).isNil()) loaded.rawset(LuaString.valueOf("string"), globals.rawget(LuaString.valueOf("string")));
        if (!globals.rawget(LuaString.valueOf("table")).isNil()) loaded.rawset(LuaString.valueOf("table"), globals.rawget(LuaString.valueOf("table")));
        if (!globals.rawget(LuaString.valueOf("coroutine")).isNil()) loaded.rawset(LuaString.valueOf("coroutine"), globals.rawget(LuaString.valueOf("coroutine")));
        if (!globals.rawget(LuaString.valueOf("utf8")).isNil()) loaded.rawset(LuaString.valueOf("utf8"), globals.rawget(LuaString.valueOf("utf8")));
        if (!globals.rawget(LuaString.valueOf("os")).isNil()) loaded.rawset(LuaString.valueOf("os"), globals.rawget(LuaString.valueOf("os")));
        if (!globals.rawget(LuaString.valueOf("io")).isNil()) loaded.rawset(LuaString.valueOf("io"), globals.rawget(LuaString.valueOf("io")));
        if (!globals.rawget(LuaString.valueOf("debug")).isNil()) loaded.rawset(LuaString.valueOf("debug"), globals.rawget(LuaString.valueOf("debug")));

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

            StringBuilder tried = new StringBuilder();
            for (String template : path.split(";")) {
                if (template.isEmpty()) continue;
                String candidate = template.replace("?", name);
                try {
                    Path p = Path.of(candidate);
                    if (Files.exists(p) && !Files.isDirectory(p)) {
                        return LuaString.valueOf(candidate);
                    }
                } catch (Exception ignored) {
                }
                tried.append("\n\tno file '").append(candidate).append("'");
            }
            return Varargs.of(LuaNil.NIL, LuaString.valueOf(tried.toString()));
        });
        pkg.rawset(LuaString.valueOf("searchpath"), searchpathFn);

        // searcher 1: preload
        LuaFunction searcherPreload = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue preloadVal = pkg.rawget(LuaString.valueOf("preload"));
            if (!preloadVal.isTable()) {
                throw new LuaException("'package.preload' must be a table");
            }
            LuaTable preloadTable = (LuaTable) preloadVal;
            LuaValue loader = preloadTable.rawget(modName);
            if (loader.isNil()) {
                return LuaString.valueOf("\n\tno field package.preload['" + modName.toLuaString() + "']");
            }
            return Varargs.of(loader, LuaString.valueOf(":preload:"));
        });

        // searcher 2: Lua files via package.path
        LuaFunction searcherLua = LuaFunction.of(args -> {
            LuaValue modName = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue pathVal = pkg.rawget(LuaString.valueOf("path"));
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
                String source = Files.readString(Path.of(filename));
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
            LuaValue cpathVal = pkg.rawget(LuaString.valueOf("cpath"));
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
            LuaValue cpathVal = pkg.rawget(LuaString.valueOf("cpath"));
            if (!cpathVal.isString()) {
                throw new LuaException("'package.cpath' must be a string");
            }
            LuaValue searchResult = searchpathFn.call(LuaString.valueOf(root), cpathVal);
            if (searchResult instanceof Varargs v && v.arg(1).isNil()) {
                return v.arg(2);
            }
            return LuaNil.NIL;
        });

        LuaTable searchers = new LuaTable();
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(1), searcherPreload);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(2), searcherLua);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(3), searcherC);
        searchers.rawset(org.luava.runtime.LuaInteger.valueOf(4), searcherCroot);
        pkg.rawset(LuaString.valueOf("searchers"), searchers);

        pkg.rawset(LuaString.valueOf("loadlib"), LuaFunction.of(args -> {
            return Varargs.of(LuaNil.NIL, LuaString.valueOf("dynamic libraries not supported"), LuaString.valueOf("absent"));
        }));

        globals.rawset(LuaString.valueOf("package"), pkg);

        // require(modname)
        globals.rawset(LuaString.valueOf("require"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isInteger() && !args[0].isFloat())) {
                throw new LuaException("bad argument #1 to 'require' (string expected)");
            }
            String nameStr = args[0].toLuaString();
            LuaString modName = LuaString.valueOf(nameStr);

            // 1. Check package.loaded
            LuaValue loadedVal = pkg.rawget(LuaString.valueOf("loaded"));
            LuaTable loadedTable = (loadedVal instanceof LuaTable t) ? t : loaded;
            LuaValue cached = loadedTable.rawget(modName);
            if (!cached.isNil() && cached.toBoolean()) {
                return cached;
            }

            // 2. Guide by package.searchers
            LuaValue searchersVal = pkg.rawget(LuaString.valueOf("searchers"));
            if (!searchersVal.isTable()) {
                throw new LuaException("'package.searchers' must be a table");
            }
            LuaTable searchersTable = (LuaTable) searchersVal;
            StringBuilder msg = new StringBuilder();
            LuaValue loader = LuaNil.NIL;
            LuaValue loaderData = LuaNil.NIL;

            for (long i = 1; ; i++) {
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
                        msg.append(first.toLuaString());
                    }
                } else if (res.isFunction() || res instanceof org.luava.runtime.LuaUserdata) {
                    loader = res;
                    break;
                } else if (res.isString()) {
                    msg.append(res.toLuaString());
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

package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;

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

        // Register default modules in package.loaded
        loaded.rawset(LuaString.valueOf("_G"), globals);
        loaded.rawset(LuaString.valueOf("package"), pkg);
        if (!globals.rawget(LuaString.valueOf("math")).isNil()) loaded.rawset(LuaString.valueOf("math"), globals.rawget(LuaString.valueOf("math")));
        if (!globals.rawget(LuaString.valueOf("string")).isNil()) loaded.rawset(LuaString.valueOf("string"), globals.rawget(LuaString.valueOf("string")));
        if (!globals.rawget(LuaString.valueOf("table")).isNil()) loaded.rawset(LuaString.valueOf("table"), globals.rawget(LuaString.valueOf("table")));
        if (!globals.rawget(LuaString.valueOf("coroutine")).isNil()) loaded.rawset(LuaString.valueOf("coroutine"), globals.rawget(LuaString.valueOf("coroutine")));
        if (!globals.rawget(LuaString.valueOf("utf8")).isNil()) loaded.rawset(LuaString.valueOf("utf8"), globals.rawget(LuaString.valueOf("utf8")));

        globals.rawset(LuaString.valueOf("package"), pkg);

        // require(modname)
        globals.rawset(LuaString.valueOf("require"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'require' (string expected)");
            }
            LuaString modName = (LuaString) args[0];
            String nameStr = modName.toLuaString();

            // 1. Check package.loaded
            LuaValue cached = loaded.rawget(modName);
            if (!cached.isNil()) {
                return cached;
            }

            // 2. Check package.preload
            LuaValue loader = preload.rawget(modName);
            if (!loader.isNil() && loader.isFunction()) {
                LuaValue result = loader.call(modName);
                LuaValue toCache = (!result.isNil()) ? result : LuaValue.valueOf(true);
                loaded.rawset(modName, toCache);
                return toCache;
            }

            // 3. Search package.path
            String pathTemplate = pkg.rawget(LuaString.valueOf("path")).toLuaString();
            String[] templates = pathTemplate.split(";");
            for (String template : templates) {
                String candidate = template.trim().replace("?", nameStr);
                Path filePath = Path.of(candidate);
                if (Files.exists(filePath) && !Files.isDirectory(filePath)) {
                    try {
                        String source = Files.readString(filePath);
                        LuaFunction chunk = state.compile(source);
                        LuaValue result = chunk.call(modName, LuaString.valueOf(candidate));
                        LuaValue toCache = (!result.isNil()) ? result : LuaValue.valueOf(true);
                        loaded.rawset(modName, toCache);
                        return toCache;
                    } catch (IOException e) {
                        throw new LuaException("error loading module '" + nameStr + "' from file '" + candidate + "': " + e.getMessage());
                    }
                }
            }

            throw new LuaException("module '" + nameStr + "' not found in package.path");
        }));
    }
}

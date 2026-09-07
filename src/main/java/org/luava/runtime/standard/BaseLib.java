package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaseLib {
    private BaseLib() {}

    public static void open(LuaState state, LuaTable globals) {
        globals.rawset(LuaString.valueOf("_G"), globals);
        globals.rawset(LuaString.valueOf("_VERSION"), LuaString.valueOf("Lua 5.4"));

        globals.rawset(LuaString.valueOf("print"), LuaFunction.of(args -> {
            for (int i = 0; i < args.length; i++) {
                if (i > 0) System.out.print("\t");
                System.out.print(args[i].toLuaString());
            }
            System.out.println();
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("type"), LuaFunction.of(args -> {
            LuaValue v = args.length > 0 ? args[0] : LuaNil.NIL;
            return LuaString.valueOf(v.typeName());
        }));

        globals.rawset(LuaString.valueOf("tostring"), LuaFunction.of(args -> {
            LuaValue v = args.length > 0 ? args[0] : LuaNil.NIL;
            return LuaString.valueOf(v.toLuaString());
        }));

        globals.rawset(LuaString.valueOf("tonumber"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaValue v = args[0];
            if (v.isInteger()) return v;
            if (v.isFloat()) return v;
            if (v.isString()) {
                String s = v.toLuaString().trim();
                int base = (args.length > 1 && args[1].isInteger()) ? (int) args[1].toLong() : 10;
                try {
                    if (base == 10) {
                        if (s.contains(".") || s.contains("e") || s.contains("E")) {
                            return LuaFloat.valueOf(Double.parseDouble(s));
                        }
                        return LuaInteger.valueOf(Long.parseLong(s));
                    } else {
                        return LuaInteger.valueOf(Long.parseLong(s, base));
                    }
                } catch (NumberFormatException e) {
                    return LuaNil.NIL;
                }
            }
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("assert"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].toBoolean()) {
                String msg = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "assertion failed!";
                throw new LuaException(msg);
            }
            return Varargs.of(args);
        }));

        globals.rawset(LuaString.valueOf("error"), LuaFunction.of(args -> {
            LuaValue msg = args.length > 0 ? args[0] : LuaNil.NIL;
            throw new LuaException(msg);
        }));

        globals.rawset(LuaString.valueOf("pcall"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isFunction()) {
                throw new LuaException("bad argument #1 to 'pcall' (function expected)");
            }
            LuaFunction fn = (LuaFunction) args[0];
            LuaValue[] fnArgs = new LuaValue[args.length - 1];
            System.arraycopy(args, 1, fnArgs, 0, fnArgs.length);
            try {
                LuaValue result = fn.invoke(fnArgs);
                if (result instanceof Varargs va) {
                    LuaValue[] arr = va.toArray();
                    LuaValue[] out = new LuaValue[arr.length + 1];
                    out[0] = LuaBoolean.TRUE;
                    System.arraycopy(arr, 0, out, 1, arr.length);
                    return Varargs.of(out);
                }
                return Varargs.of(LuaBoolean.TRUE, result);
            } catch (LuaException le) {
                return Varargs.of(LuaBoolean.FALSE, le.getErrorObject());
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf(msg));
            }
        }));

        globals.rawset(LuaString.valueOf("xpcall"), LuaFunction.of(args -> {
            if (args.length < 2 || !args[0].isFunction() || !args[1].isFunction()) {
                throw new LuaException("bad arguments to 'xpcall' (functions expected)");
            }
            LuaFunction fn = (LuaFunction) args[0];
            LuaFunction msgh = (LuaFunction) args[1];
            LuaValue[] fnArgs = new LuaValue[args.length - 2];
            System.arraycopy(args, 2, fnArgs, 0, fnArgs.length);
            try {
                LuaValue result = fn.invoke(fnArgs);
                if (result instanceof Varargs va) {
                    LuaValue[] arr = va.toArray();
                    LuaValue[] out = new LuaValue[arr.length + 1];
                    out[0] = LuaBoolean.TRUE;
                    System.arraycopy(arr, 0, out, 1, arr.length);
                    return Varargs.of(out);
                }
                return Varargs.of(LuaBoolean.TRUE, result);
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                LuaValue handlerRes = msgh.call(LuaString.valueOf(msg));
                return Varargs.of(LuaBoolean.FALSE, handlerRes);
            }
        }));

        globals.rawset(LuaString.valueOf("select"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'select'");
            LuaValue selector = args[0];
            if (selector.isString() && "#".equals(selector.toLuaString())) {
                return LuaInteger.valueOf(args.length - 1);
            }
            long idx = selector.toLong();
            if (idx == 0 || idx < -args.length + 1) {
                throw new LuaException("bad index to 'select'");
            }
            int start = idx > 0 ? (int) idx : (int) (args.length + idx);
            if (start > args.length - 1) {
                return Varargs.EMPTY;
            }
            int count = args.length - start;
            LuaValue[] sub = new LuaValue[count];
            System.arraycopy(args, start, sub, 0, count);
            return Varargs.of(sub);
        }));

        globals.rawset(LuaString.valueOf("setmetatable"), LuaFunction.of(args -> {
            if (args.length < 2 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'setmetatable' (table expected)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaValue mt = args[1];
            if (mt.isNil()) {
                t.setMetatable(null);
            } else if (mt.isTable()) {
                t.setMetatable((LuaTable) mt);
            } else {
                throw new LuaException("bad argument #2 to 'setmetatable' (nil or table expected)");
            }
            return t;
        }));

        globals.rawset(LuaString.valueOf("getmetatable"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaTable mt = args[0].getMetatable();
            return mt != null ? mt : LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("rawget"), LuaFunction.of(args -> {
            if (args.length < 2 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'rawget' (table expected)");
            }
            return ((LuaTable) args[0]).rawget(args[1]);
        }));

        globals.rawset(LuaString.valueOf("rawset"), LuaFunction.of(args -> {
            if (args.length < 3 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'rawset' (table expected)");
            }
            ((LuaTable) args[0]).rawset(args[1], args[2]);
            return args[0];
        }));

        globals.rawset(LuaString.valueOf("rawequal"), LuaFunction.of(args -> {
            if (args.length < 2) return LuaBoolean.FALSE;
            return LuaBoolean.valueOf(args[0].equals(args[1]));
        }));

        globals.rawset(LuaString.valueOf("rawlen"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'rawlen'");
            if (args[0].isTable()) {
                return LuaInteger.valueOf(((LuaTable) args[0]).rawlen());
            }
            if (args[0].isString()) {
                return args[0].len();
            }
            throw new LuaException("table or string expected in 'rawlen'");
        }));

        globals.rawset(LuaString.valueOf("pairs"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'pairs' (table expected)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaFunction nextFunc = (LuaFunction) globals.rawget(LuaString.valueOf("next"));
            return Varargs.of(nextFunc, t, LuaNil.NIL);
        }));

        globals.rawset(LuaString.valueOf("next"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'next' (table expected)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaValue currentKey = args.length > 1 ? args[1] : LuaNil.NIL;
            java.util.List<LuaValue> keys = t.keys();
            if (keys.isEmpty()) return LuaNil.NIL;

            if (currentKey.isNil()) {
                LuaValue firstKey = keys.get(0);
                return Varargs.of(firstKey, t.rawget(firstKey));
            }
            int idx = keys.indexOf(currentKey);
            if (idx >= 0 && idx + 1 < keys.size()) {
                LuaValue nextKey = keys.get(idx + 1);
                return Varargs.of(nextKey, t.rawget(nextKey));
            }
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("ipairs"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'ipairs' (table expected)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaFunction iter = LuaFunction.of(iterArgs -> {
                LuaTable table = (LuaTable) iterArgs[0];
                long i = iterArgs[1].toLong() + 1;
                LuaValue val = table.rawget(LuaInteger.valueOf(i));
                if (val.isNil()) return LuaNil.NIL;
                return Varargs.of(LuaInteger.valueOf(i), val);
            });
            return Varargs.of(iter, t, LuaInteger.valueOf(0));
        }));

        globals.rawset(LuaString.valueOf("load"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("string expected in 'load'"));
            }
            String code = args[0].toLuaString();
            try {
                LuaFunction fn = state.compile(code);
                return fn;
            } catch (Throwable t) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(t.getMessage()));
            }
        }));

        globals.rawset(LuaString.valueOf("dofile"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("filename expected in 'dofile'");
            }
            String filename = args[0].toLuaString();
            try {
                String content = Files.readString(Path.of(filename));
                return state.eval(content);
            } catch (IOException e) {
                throw new LuaException("cannot open file " + filename + ": " + e.getMessage());
            }
        }));
    }
}

package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;
import org.luava.runtime.concurrency.LuaCoroutine;

public final class CoroutineLib {
    private CoroutineLib() {}

    public static void open(LuaTable globals) {
        LuaTable coro = new LuaTable();

        coro.rawset(LuaString.valueOf("create"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isFunction()) {
                throw new LuaException("bad argument #1 to 'coroutine.create' (function expected)");
            }
            return new LuaCoroutine((LuaFunction) args[0]);
        }));

        coro.rawset(LuaString.valueOf("resume"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
                throw new LuaException("bad argument #1 to 'coroutine.resume' (thread expected)");
            }
            LuaValue[] resumeArgs = new LuaValue[args.length - 1];
            System.arraycopy(args, 1, resumeArgs, 0, resumeArgs.length);
            LuaValue[] results = co.resume(resumeArgs);
            return Varargs.of(results);
        }));

        coro.rawset(LuaString.valueOf("yield"), LuaFunction.of(args -> {
            LuaValue[] yielded = LuaCoroutine.yield(args);
            return Varargs.of(yielded);
        }));

        coro.rawset(LuaString.valueOf("status"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
                throw new LuaException("bad argument #1 to 'coroutine.status' (thread expected)");
            }
            return LuaString.valueOf(co.getStatus().label());
        }));

        coro.rawset(LuaString.valueOf("isyieldable"), LuaFunction.of(args -> {
            return LuaBoolean.valueOf(LuaCoroutine.isYieldable());
        }));

        coro.rawset(LuaString.valueOf("running"), LuaFunction.of(args -> {
            LuaCoroutine running = LuaCoroutine.running();
            if (running != null) {
                return Varargs.of(running, LuaBoolean.FALSE);
            }
            return Varargs.of(LuaNil.NIL, LuaBoolean.TRUE);
        }));

        coro.rawset(LuaString.valueOf("wrap"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isFunction()) {
                throw new LuaException("bad argument #1 to 'coroutine.wrap' (function expected)");
            }
            LuaCoroutine co = new LuaCoroutine((LuaFunction) args[0]);
            return LuaFunction.of(wrapArgs -> {
                LuaValue[] results = co.resume(wrapArgs);
                if (!results[0].toBoolean()) {
                    throw new LuaException(results.length > 1 ? results[1] : LuaString.valueOf("error in coroutine"));
                }
                LuaValue[] out = new LuaValue[results.length - 1];
                System.arraycopy(results, 1, out, 0, out.length);
                return Varargs.of(out);
            });
        }));

        globals.rawset(LuaString.valueOf("coroutine"), coro);
    }
}

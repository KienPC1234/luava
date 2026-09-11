/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
        open(globals, null);
    }

    public static void open(LuaTable globals, LuaCoroutine mainThread) {
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
            LuaValue[] resumeArgs;
            int argCount = args.length - 1;
            if (argCount <= 0) {
                resumeArgs = LuaCoroutine.EMPTY_VALUES;
            } else {
                resumeArgs = new LuaValue[argCount];
                System.arraycopy(args, 1, resumeArgs, 0, argCount);
            }
            LuaValue[] results = co.resume(resumeArgs);
            if (results.length == 1) {
                return results[0];
            }
            return Varargs.of(results);
        }));

        coro.rawset(LuaString.valueOf("yield"), LuaFunction.of(args -> {
            LuaValue[] yielded = LuaCoroutine.yield(args != null && args.length > 0 ? args : LuaCoroutine.EMPTY_VALUES);
            if (yielded == null || yielded.length == 0) return LuaNil.NIL;
            if (yielded.length == 1) return yielded[0];
            return Varargs.of(yielded);
        }));

        coro.rawset(LuaString.valueOf("status"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
                throw new LuaException("bad argument #1 to 'coroutine.status' (thread expected)");
            }
            return LuaString.valueOf(co.getStatus().label());
        }));

        coro.rawset(LuaString.valueOf("close"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
                throw new LuaException("bad argument #1 to 'coroutine.close' (thread expected)");
            }
            LuaValue[] results = co.close();
            return Varargs.of(results);
        }));

        coro.rawset(LuaString.valueOf("isyieldable"), LuaFunction.of(args -> {
            if (args.length > 0 && !args[0].isNil()) {
                if (!(args[0] instanceof LuaCoroutine co)) {
                    throw new LuaException("bad argument #1 to 'coroutine.isyieldable' (thread expected)");
                }
                if (co == mainThread || co.isMainThread()) {
                    return LuaBoolean.FALSE;
                }
                return LuaBoolean.valueOf(co.isYieldableInstance());
            }
            LuaCoroutine running = LuaCoroutine.running();
            if (running == null || running == mainThread || running.isMainThread()) {
                return LuaBoolean.FALSE;
            }
            return LuaBoolean.valueOf(running.isYieldableInstance());
        }));

        coro.rawset(LuaString.valueOf("running"), LuaFunction.of(args -> {
            LuaCoroutine running = LuaCoroutine.running();
            if (running != null) {
                boolean isMain = (running == mainThread || running.isMainThread());
                return Varargs.of(running, LuaBoolean.valueOf(isMain));
            }
            return Varargs.of(mainThread != null ? mainThread : LuaNil.NIL, LuaBoolean.TRUE);
        }));

        coro.rawset(LuaString.valueOf("wrap"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isFunction()) {
                throw new LuaException("bad argument #1 to 'coroutine.wrap' (function expected)");
            }
            LuaCoroutine co = new LuaCoroutine((LuaFunction) args[0]);
            return LuaFunction.of(wrapArgs -> {
                LuaValue[] results = co.resume(wrapArgs);
                if (!results[0].toBoolean()) {
                    if (co.getStatus() == LuaCoroutine.Status.SUSPENDED) {
                        try {
                            co.close();
                        } catch (Exception ignored) {}
                    }
                    throw new LuaException(results.length > 1 ? results[1] : LuaString.valueOf("error in coroutine"));
                }
                int outLen = results.length - 1;
                if (outLen <= 0) return Varargs.EMPTY;
                if (outLen == 1) return results[1];
                LuaValue[] out = new LuaValue[outLen];
                System.arraycopy(results, 1, out, 0, outLen);
                return Varargs.of(out);
            });
        }));

        globals.rawset(LuaString.valueOf("coroutine"), coro);
    }
}

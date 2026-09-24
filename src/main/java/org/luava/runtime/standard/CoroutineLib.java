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

    /**
     * Shared, stateless coroutine builtins. The VM recognizes these by
     * identity to run them framelessly (no argument boxing, no CallStack
     * frame, no name resolution): yield/resume sit on the hot path of every
     * coroutine switch, where the generic external-call machinery costs more
     * than the context switch itself. They are state-independent because a
     * coroutine is always passed as an argument and the "main thread" test is
     * exactly {@link LuaCoroutine#isMainThread()}.
     */
    public static final LuaFunction CREATE = LuaFunction.of(CoroutineLib::createImpl);
    public static final LuaFunction RESUME = LuaFunction.of(CoroutineLib::resumeImpl);
    public static final LuaFunction YIELD = LuaFunction.of(CoroutineLib::yieldImpl);
    public static final LuaFunction STATUS = LuaFunction.of(CoroutineLib::statusImpl);
    public static final LuaFunction CLOSE = LuaFunction.of(CoroutineLib::closeImpl);
    public static final LuaFunction WRAP = LuaFunction.of(CoroutineLib::wrapImpl);

    static LuaValue createImpl(LuaValue[] args) {
        if (args.length == 0 || !args[0].isFunction()) {
            throw LuaValue.argError(1, "coroutine.create", "function expected, got "
                    + (args.length == 0 ? "no value" : args[0].typeName()));
        }
        return new LuaCoroutine((LuaFunction) args[0]);
    }

    static LuaValue resumeImpl(LuaValue[] args) {
        if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
            throw LuaValue.argError(1, "coroutine.resume", "thread expected, got "
                    + (args.length == 0 ? "no value" : args[0].typeName()));
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
    }

    static LuaValue yieldImpl(LuaValue[] args) {
        LuaValue[] yielded = LuaCoroutine.yield(args != null && args.length > 0 ? args : LuaCoroutine.EMPTY_VALUES);
        if (yielded == null || yielded.length == 0) return LuaNil.NIL;
        if (yielded.length == 1) return yielded[0];
        return Varargs.of(yielded);
    }

    static LuaValue statusImpl(LuaValue[] args) {
        if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
            throw LuaValue.argError(1, "coroutine.status", "thread expected, got "
                    + (args.length == 0 ? "no value" : args[0].typeName()));
        }
        return LuaString.valueOf(co.getStatus().label());
    }

    static LuaValue closeImpl(LuaValue[] args) {
        if (args.length == 0 || !(args[0] instanceof LuaCoroutine co)) {
            throw LuaValue.argError(1, "coroutine.close", "thread expected, got "
                    + (args.length == 0 ? "no value" : args[0].typeName()));
        }
        return Varargs.of(co.close());
    }

    static LuaValue wrapImpl(LuaValue[] args) {
        if (args.length == 0 || !args[0].isFunction()) {
            throw LuaValue.argError(1, "coroutine.wrap", "function expected, got "
                    + (args.length == 0 ? "no value" : args[0].typeName()));
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
                throw new LuaException(results.length > 1 ? results[1] : LuaString.interned("error in coroutine"));
            }
            int outLen = results.length - 1;
            if (outLen <= 0) return Varargs.EMPTY;
            if (outLen == 1) return results[1];
            LuaValue[] out = new LuaValue[outLen];
            System.arraycopy(results, 1, out, 0, outLen);
            return Varargs.of(out);
        });
    }

    public static void open(LuaTable globals) {
        open(globals, null);
    }

    public static void open(LuaTable globals, LuaCoroutine mainThread) {
        LuaTable coro = new LuaTable();
        fillInto(coro, globals, mainThread);
        globals.rawset(LuaString.interned("coroutine"), coro);
    }

    public static void fillInto(LuaTable coro, LuaTable globals, LuaCoroutine mainThread) {
        coro.rawset(LuaString.interned("create"), CREATE);
        coro.rawset(LuaString.interned("resume"), RESUME);
        coro.rawset(LuaString.interned("yield"), YIELD);
        coro.rawset(LuaString.interned("status"), STATUS);
        coro.rawset(LuaString.interned("close"), CLOSE);
        coro.rawset(LuaString.interned("wrap"), WRAP);
        // `running`/`isyieldable` are state-bound: on the host-call path no
        // coroutine is current, and they must fall back to this state's main
        // thread. Everything else is state-independent (the thread is an
        // argument) and shared for identity-based VM inlining.
        coro.rawset(LuaString.interned("isyieldable"), LuaFunction.of(args -> {
            // `lua_isnone(L,1) ? L : getco(L)`: only an absent argument means
            // "current thread"; an explicit nil is a bad thread argument.
            if (args.length > 0) {
                if (!(args[0] instanceof LuaCoroutine co)) {
                    throw LuaValue.argError(1, "coroutine.isyieldable",
                            "thread expected, got " + args[0].typeName());
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
        coro.rawset(LuaString.interned("running"), LuaFunction.of(args -> {
            LuaCoroutine running = LuaCoroutine.running();
            if (running != null) {
                boolean isMain = (running == mainThread || running.isMainThread());
                return Varargs.of(running, LuaBoolean.valueOf(isMain));
            }
            return Varargs.of(mainThread != null ? mainThread : LuaNil.NIL, LuaBoolean.TRUE);
        }));
    }
}

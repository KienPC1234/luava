/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

import org.luava.runtime.LuaValue;
import org.luava.runtime.concurrency.LuaCoroutine;
import org.luava.runtime.eval.CallStack;
import org.luava.runtime.eval.Upvalue;

/**
 * Loop-carried state of {@link BytecodeVM#runLoop}.
 *
 * <p>The dispatch loop was a single ~9.8 KB method, which HotSpot refuses
 * to JIT-compile (over the 8 KB {@code HugeMethodLimit}), so the
 * interpreter ran uncompiled forever. Moving this state into one holder
 * lets big opcode handlers ({@code OP_CALL}, {@code OP_TAILCALL},
 * {@code OP_RETURN}) move to {@code static} helpers while the loop itself
 * stays small enough for C2/OSR. The object is allocated once per
 * {@code execute()} and never escapes, so C2 can scalar-replace it.
 */
public final class VmContext {
    // Integer loop state
    public int pc;
    public int base;
    public int top;
    public int callDepth;
    public int oldpc;
    public boolean varargPrepRan;
    /** Scratch in/out slot for tiny helpers (e.g. callable resolution). */
    public int scratch0;

    // Frame / teardown state
    public int savedStackTop;
    public int initialDepth;
    public Throwable thrown;

    // Current function view (re-pointed on every call/return)
    public LuaClosure closure;
    public LuaProto proto;
    public int[] code;
    public LuaValue[] k;
    public Upvalue[] upvals;
    public LuaValue[] varargs;
    public CallInfo[] callStack;

    // Value stacks (re-fetched after growth)
    public long[] pStack;
    public byte[] tStack;
    public LuaValue[] oStack;

    /**
     * Hoisted thread state, constant for a whole {@code runLoop} invocation
     * (resume continues the same thread; nested coroutines get their own
     * loop). Saves several {@code ThreadLocal} lookups per call/return.
     * {@code co} may be null on bare threads (same null-guard semantics as
     * the static {@code CallStack} methods); {@code callState} never is.
     */
    public LuaCoroutine co;
    public CallStack.CallStackState callState;

    /**
     * Effective thread for stack operations: {@code co} when running inside a
     * coroutine, otherwise the state's main thread (mirrors
     * {@code LuaState.getCurrentThread()} without the ThreadLocal lookup).
     * Hoisted once per {@code execute()}; the VM uses it for all hot-path
     * stack reads/growth so a Lua-to-Lua call never re-resolves the thread.
     */
    public LuaCoroutine thread;

    /**
     * Direct-mapped memo for {@code getobjname} (pure in proto/pc/reg).
     * Name resolution runs once per call site per execute instead of a
     * bytecode-archaeology scan per call. Entries are ctx-local, so no
     * cross-thread contention; the stored array is shared and callers must
     * only read it. Sized so method-heavy code with many call sites does not
     * thrash (a 4-entry table did, re-scanning on nearly every call).
     */
    public static final int NAME_CACHE_SIZE = 256;
    public final LuaProto[] ncProto = new LuaProto[NAME_CACHE_SIZE];
    public final int[] ncPc = new int[NAME_CACHE_SIZE];
    public final int[] ncReg = new int[NAME_CACHE_SIZE];
    public final String[][] ncInfo = new String[NAME_CACHE_SIZE][];
    public final boolean[] ncFilled = new boolean[NAME_CACHE_SIZE];
}

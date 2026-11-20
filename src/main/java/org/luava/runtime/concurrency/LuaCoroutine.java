/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.concurrency;

import org.luava.binding.JavaAccessPolicy;
import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaType;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;
import org.luava.runtime.eval.CallStack;

import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

public final class LuaCoroutine extends LuaValue {
    public enum Status {
        SUSPENDED("suspended"),
        RUNNING("running"),
        NORMAL("normal"),
        DEAD("dead");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final LuaValue[] EMPTY_VALUES = new LuaValue[0];
    private static final LuaValue[] RESUME_DEAD_ERROR = new LuaValue[]{LuaBoolean.FALSE, LuaString.valueOf("cannot resume dead coroutine")};
    private static final LuaValue[] RESUME_NON_SUSPENDED_ERROR = new LuaValue[]{LuaBoolean.FALSE, LuaString.valueOf("cannot resume non-suspended coroutine")};
    private static final LuaValue[] RESUME_OVERFLOW_ERROR = new LuaValue[]{LuaBoolean.FALSE, LuaString.valueOf("C stack overflow")};
    private static final LuaValue[] RESUME_SUCCESS_EMPTY = new LuaValue[]{LuaBoolean.TRUE};

    private static final ThreadLocal<LuaCoroutine> CURRENT_COROUTINE = new ThreadLocal<>();

    /**
     * Handoff rendezvous sequence numbers. A plain status-flag handshake
     * (set status, unpark, spin/park while status unchanged) has a
     * lost-wakeup race: the resumer can observe SUSPENDED and return before
     * the yielding side reaches its wait, then re-resume and be told
     * "cannot resume non-suspended coroutine". The old code masked it only
     * because an unconditional park is slow enough that the counterpart was
     * always already blocked. Monotonic per-side counters make the handoff
     * exact: the resumer waits for a <em>new</em> yield/death event
     * ({@link #yieldSeq}) and the yielder waits for a <em>new</em> resume
     * ({@link #resumeSeq}), with the expected value snapshotted before the
     * event is published.
     */
    private volatile long yieldSeq = 0;
    private volatile long resumeSeq = 0;
    private volatile boolean resumerParked = false;
    private volatile boolean workerParked = false;

    /**
     * Adaptive spin budget (pure pause iterations before falling back to bounded
     * nanoTime spin + LockSupport.park). Avoids nanoTime overhead during fast
     * cooperative yields while bounding spin on genuinely blocked waits.
     */
    private int resumerSpinBudget = 1024;
    private int workerSpinBudget = 1024;
    private static final long HANDOFF_SPIN_NANOS = 10_000L;

    /**
     * Per-coroutine "a hook is active" flag for the VM hot path. Hooks in
     * Lua are per-thread: {@code debug.sethook(co, ...)} only arms {@code co}.
     * This used to be a process-global static counter, which leaked — a hook
     * set on a coroutine that was never resumed/closed (or on a main thread
     * whose state was abandoned) left the global true forever, permanently
     * disabling JIT for every later {@code LuaState}. Reading a boolean on
     * the already-hoisted running coroutine costs the same as the old global
     * read, and is correct per Lua semantics.
     */
    public volatile boolean hooksActive = false;

    /** Disarm this coroutine's hook (idempotent; safe to call on death/close). */
    private void disarmHook() {
        hooksActive = false;
    }

    /**
     * Mirror of the VM program counter, written once per instruction by
     * {@code BytecodeVM.execute} (plain field: same-thread for running reads,
     * park/unpark happens-before edge for suspended reads). Debug readers
     * (getinfo/traceback/getlocal) sync the top frame from this on demand
     * instead of the loop syncing every frame eagerly.
     */
    public int vmPcMirror = -1;

    /** Refresh the top frame's pc/line from {@link #vmPcMirror} (Lua frames only). */
    public void syncTopFrameFromMirror() {
        int mpc = vmPcMirror;
        if (mpc < 0) return;
        CallStack.Frame f = getCallStackState().getFrame(0);
        if (f == null || !(f.function instanceof org.luava.runtime.bytecode.LuaClosure cl)) return;
        f.pc = mpc;
        if (cl.proto != null && cl.proto.lineInfo != null && mpc < cl.proto.lineInfo.length) {
            f.line = cl.proto.lineInfo[mpc];
        }
    }

    private final LuaFunction entryFunction;
    private final LuaState ownerState;
    private final java.util.concurrent.atomic.AtomicBoolean deadAccounted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread resumerThread;
    private volatile Thread virtualThread;
    private volatile LuaValue[] handoffArgs;
    private volatile LuaValue handoffSingleArg;
    private volatile int handoffArgCount;
    private volatile LuaValue[] handoffResult;
    private volatile LuaValue handoffSingleResult;
    private volatile int handoffResultCount;
    // Caller ThreadLocal scope transferred across resume/close under the coroutine lock.
    private LuaState handoffActiveState;
    private Long handoffMaxAlloc;
    private JavaAccessPolicy handoffPolicy;
    private LuaState handoffBasicState;
    private Thread handoffRootThread;
    // Worker-confined mirror of installed ThreadLocals on virtualThread.
    private LuaState installedActiveState;
    private Long installedMaxAlloc;
    private JavaAccessPolicy installedPolicy;
    private LuaState installedBasicState;
    private Thread installedRootThread;
    private volatile Status status = Status.SUSPENDED;
    private volatile Throwable error;
    private static final int MAX_NESTED_COROUTINES = 200;
    private int nestedDepth = 0;
    private volatile int nonYieldableCount = 0;

    private final CallStack.CallStackState callStackState = new CallStack.CallStackState();

    // Per-thread execution stacks for BytecodeVM (Zero-Allocation hot-paths & stack isolation)
    private long[] primitiveStack = new long[256];
    private byte[] typeStack = new byte[256];
    private LuaValue[] objectStack = new LuaValue[256];
    private org.luava.runtime.eval.Upvalue openUpvaluesHead = null;
    private org.luava.runtime.LuaState.TbcEntry tbcHead = null;
    private int stackTop = 0;

    public int getStackTop() {
        return stackTop;
    }

    public void setStackTop(int top) {
        this.stackTop = Math.max(0, top);
    }

    public long[] getPrimitiveStack() {
        return primitiveStack;
    }

    public byte[] getTypeStack() {
        return typeStack;
    }

    public LuaValue[] getObjectStack() {
        return objectStack;
    }

    public void ensureStackCapacity(int needed) {
        if (needed > primitiveStack.length) {
            int newCap = Math.max(primitiveStack.length * 2, needed + 256);
            long[] newP = new long[newCap];
            byte[] newT = new byte[newCap];
            LuaValue[] newO = new LuaValue[newCap];
            System.arraycopy(primitiveStack, 0, newP, 0, primitiveStack.length);
            System.arraycopy(typeStack, 0, newT, 0, typeStack.length);
            System.arraycopy(objectStack, 0, newO, 0, objectStack.length);
            primitiveStack = newP;
            typeStack = newT;
            objectStack = newO;
        }
    }

    public org.luava.runtime.eval.Upvalue getOpenUpvaluesHead() {
        return openUpvaluesHead;
    }

    public void setOpenUpvaluesHead(org.luava.runtime.eval.Upvalue openUpvaluesHead) {
        this.openUpvaluesHead = openUpvaluesHead;
    }

    public org.luava.runtime.LuaState.TbcEntry getTbcHead() {
        return tbcHead;
    }

    /** Closes every pending to-be-closed variable on this thread (os.exit true). */
    public void closeAllTbc() {
        org.luava.runtime.LuaState.TbcEntry entry = tbcHead;
        tbcHead = null;
        while (entry != null) {
            org.luava.runtime.LuaState.TbcEntry next = entry.next;
            org.luava.runtime.LuaValue val = entry.value;
            org.luava.runtime.LuaTable mt = val.getMetatable();
            org.luava.runtime.LuaValue closeMth = mt != null
                    ? mt.rawget(org.luava.runtime.LuaValue.Meta.CLOSE) : org.luava.runtime.LuaNil.NIL;
            try {
                if (!closeMth.isNil()) {
                    org.luava.runtime.eval.CallStack.setNextCall("close", "metamethod", false, true);
                    closeMth.call(val, org.luava.runtime.LuaNil.NIL);
                }
            } catch (Throwable ignored) {
            }
            entry = next;
        }
    }

    public void setTbcHead(org.luava.runtime.LuaState.TbcEntry tbcHead) {
        this.tbcHead = tbcHead;
    }

    public CallStack.CallStackState getCallStackState() {
        return callStackState;
    }

    public LuaFunction getEntryFunction() {
        return entryFunction;
    }

    public LuaValue[] getHandoffArgs() {
        return handoffArgs;
    }

    public LuaValue[] getHandoffResult() {
        return handoffResult;
    }

    public static final class HookConfig {
        public LuaValue hook = LuaNil.NIL;
        public String mask = "";
        public int count = 0;
        public boolean hookCall = false;
        public boolean hookReturn = false;
        public boolean hookLine = false;
        public int countSoFar = 0;
        public int lastLine = -1;
        public boolean inHook = false;
    }

    private final HookConfig hookConfig = new HookConfig();

    public HookConfig getHookConfig() {
        return hookConfig;
    }

    public void setLastLine(int lastLine) {
        hookConfig.lastLine = lastLine;
    }

    public void resetLastLine() {
        hookConfig.lastLine = -1;
    }

    public void setHook(LuaValue hook, String mask, int count) {
        hookConfig.hook = (hook != null && !hook.isNil()) ? hook : LuaNil.NIL;
        hookConfig.mask = mask != null ? mask : "";
        hookConfig.count = Math.max(0, count);
        hookConfig.hookCall = hookConfig.mask.contains("c");
        hookConfig.hookReturn = hookConfig.mask.contains("r");
        hookConfig.hookLine = hookConfig.mask.contains("l");
        hookConfig.countSoFar = 0;
        hookConfig.lastLine = -1;
        boolean active = !hookConfig.hook.isNil()
                && (hookConfig.hookCall || hookConfig.hookReturn || hookConfig.hookLine || hookConfig.count > 0);
        // lua_sethook (lstate.c): "if (func == NULL || mask == 0) turn off
        // hooks". A function with a mask that selects no event (e.g. "3") and
        // no count is therefore equivalent to clearing the hook, and
        // debug.gethook() must report nil afterwards.
        if (!active) {
            hookConfig.hook = LuaNil.NIL;
            hookConfig.mask = "";
            hookConfig.count = 0;
        }
        hooksActive = active;

        CallStack.CallStackState state = getCallStackState();
        if (state != null && state.top > 1) {
            CallStack.Frame caller = state.stack[state.top - 2];
            if (caller != null && caller.line > 0) {
                caller.lastLine = caller.line;
            }
        }
    }

    public void clearHook() {
        disarmHook();
        hookConfig.hook = LuaNil.NIL;
        hookConfig.mask = "";
        hookConfig.count = 0;
        hookConfig.hookCall = false;
        hookConfig.hookReturn = false;
        hookConfig.hookLine = false;
        hookConfig.countSoFar = 0;
        hookConfig.lastLine = -1;
    }

    public boolean hasHook() {
        return !hookConfig.hook.isNil();
    }

    public void fireCallHook() {
        if (!hookConfig.hookCall || hookConfig.hook.isNil() || hookConfig.inHook) return;
        invokeHook("call", -1);
    }

    public void fireTailCallHook() {
        if (!hookConfig.hookCall || hookConfig.hook.isNil() || hookConfig.inHook) return;
        invokeHook("tail call", -1);
    }

    public void fireReturnHook() {
        if (!hookConfig.hookReturn || hookConfig.hook.isNil() || hookConfig.inHook) return;
        invokeHook("return", -1);
    }

    public void fireLineAndCountHook(int line) {
        fireLineAndCountHook(line, null);
    }

    public void fireCountHook() {
        if (hookConfig.count <= 0 || hookConfig.hook.isNil() || hookConfig.inHook) return;
        hookConfig.countSoFar++;
        if (hookConfig.countSoFar >= hookConfig.count) {
            hookConfig.countSoFar = 0;
            invokeHook("count", -1);
        }
    }

    public void fireLineAndCountHook(int line, CallStack.Frame frame) {
        if (hookConfig.hook.isNil() || hookConfig.inHook) {
            if (line > 0 && frame != null) {
                frame.lastLine = line;
            }
            return;
        }
        int prevLine = (frame != null) ? frame.lastLine : hookConfig.lastLine;
        if (line <= 0 || line == prevLine) {
            return;
        }

        if (frame != null) {
            frame.lastLine = line;
        }
        hookConfig.lastLine = line;

        if (hookConfig.hookLine) {
            int hookLine = (frame != null && frame.function != null && frame.function.isStripped()) ? -1 : line;
            try {
                invokeHook("line", hookLine);
            } catch (LuaException le) {
                if (le.getMessage() != null && le.getMessage().contains("wrong trace!!")) {
                    throw new LuaException("wrong trace at hook line " + line + ": " + le.getMessage());
                }
                throw le;
            }
        }
    }

    /**
     * Directly fires the line hook for BytecodeVM execution.
     * Lua 5.4 semantics (ldebug.c: npci {@code <=} oldpc || changedline) are
     * evaluated in the BytecodeVM loop.
     */
    public void fireLineHookDirect(int line, CallStack.Frame frame) {
        if (hookConfig.hook.isNil() || hookConfig.inHook || !hookConfig.hookLine) {
            if (line > 0 && frame != null) {
                frame.lastLine = line;
            }
            return;
        }
        if (frame != null) {
            frame.lastLine = line;
        }
        hookConfig.lastLine = line;
        int hookLine = (frame != null && frame.function != null && frame.function.isStripped()) ? -1 : line;
        try {
            invokeHook("line", hookLine);
        } catch (LuaException le) {
            if (le.getMessage() != null && le.getMessage().contains("wrong trace!!")) {
                throw new LuaException("wrong trace at hook line " + line + ": " + le.getMessage());
            }
            throw le;
        }
    }

    private void invokeHook(String event, int line) {
        boolean prevInHook = hookConfig.inHook;
        hookConfig.inHook = true;
        try {
            org.luava.runtime.eval.CallStack.setNextCall("?", "hook", false, false);
            if (line > 0) {
                hookConfig.hook.call(LuaString.valueOf(event), LuaInteger.valueOf(line));
            } else {
                hookConfig.hook.call(LuaString.valueOf(event));
            }
        } finally {
            hookConfig.inHook = prevInHook;
        }
    }

    public void enterNonYieldable() {
        nonYieldableCount++;
    }

    public void exitNonYieldable() {
        nonYieldableCount--;
    }

    private final boolean isMainThread;

    public LuaCoroutine(LuaFunction function) {
        this(function, false);
    }

    public LuaCoroutine(LuaFunction function, boolean isMainThread) {
        this.entryFunction = function;
        this.isMainThread = isMainThread;
        LuaState active = isMainThread ? null : LuaState.activeState();
        this.ownerState = active;
        if (active != null) {
            active.onCoroutineCreated();
            int cap = active.getMaxCallDepth();
            if (cap > 0) {
                this.callStackState.frameLimit = Math.min(cap, org.luava.runtime.eval.CallStack.MAX_CALL_DEPTH);
            }
        }
    }

    private void markDead() {
        status = Status.DEAD;
        if (ownerState != null && deadAccounted.compareAndSet(false, true)) {
            ownerState.onCoroutineDead();
        }
    }

    public static LuaCoroutine createMainThread() {
        LuaCoroutine main = new LuaCoroutine(null, true);
        main.status = Status.RUNNING;
        return main;
    }

    public boolean isMainThread() {
        return isMainThread;
    }

    public static LuaCoroutine running() {
        return CURRENT_COROUTINE.get();
    }

    public static void setCurrent(LuaCoroutine coro) {
        if (coro != null) {
            CURRENT_COROUTINE.set(coro);
        } else {
            CURRENT_COROUTINE.remove();
        }
    }

    public boolean isYieldableInstance() {
        if (isMainThread) return false;
        return nonYieldableCount == 0;
    }

    public static boolean isYieldable() {
        LuaCoroutine cur = CURRENT_COROUTINE.get();
        return cur != null && !cur.isMainThread && cur.nonYieldableCount == 0;
    }

    public Status getStatus() {
        return status;
    }

    public static final class CoroutineCloseSignal extends Error {
        public CoroutineCloseSignal() {
            super(null, null, false, false);
        }
    }

    /** Internal unwind signal when a stackless coroutine needs a VirtualThread. */
    public static final class PromoteSignal extends Error {
        public static final PromoteSignal INSTANCE = new PromoteSignal();

        private PromoteSignal() {
            super(null, null, false, false);
        }
    }

    public org.luava.runtime.bytecode.VmContext activeStacklessCtx;
    private org.luava.runtime.bytecode.VmContext stacklessCtx;
    private LuaState stacklessState;
    public int yieldFuncIdx;
    public int yieldNResults;

    public static void promoteIfStackless() {
        LuaCoroutine cur = CURRENT_COROUTINE.get();
        if (cur != null && cur.activeStacklessCtx != null) {
            throw PromoteSignal.INSTANCE;
        }
    }

    public boolean isStacklessSuspended() {
        return virtualThread == null && stacklessCtx != null;
    }

    public boolean isClosing() {
        return isClosing;
    }

    private volatile boolean isClosing = false;
    private volatile org.luava.runtime.LuaExit exitSignal = null;

    private void captureCallerContext() {
        handoffActiveState = LuaState.activeState();
        handoffMaxAlloc = LuaState.activeMaxAlloc();
        handoffPolicy = JavaAccessPolicy.activeOrNull();
        handoffBasicState = LuaValue.activeBasicState();
        handoffRootThread = LuaState.activeRootThread();
    }

    private void syncWorkerContext() {
        if (handoffActiveState != installedActiveState) {
            LuaState.swapActiveState(handoffActiveState);
            installedActiveState = handoffActiveState;
        }
        if (!Objects.equals(handoffMaxAlloc, installedMaxAlloc)) {
            LuaState.swapActiveMaxAlloc(handoffMaxAlloc);
            installedMaxAlloc = handoffMaxAlloc;
        }
        if (handoffPolicy != installedPolicy) {
            JavaAccessPolicy.setActive(handoffPolicy);
            installedPolicy = handoffPolicy;
        }
        if (handoffBasicState != installedBasicState) {
            if (handoffBasicState == null) {
                LuaValue.popBasicState(null);
            } else {
                LuaValue.pushBasicState(handoffBasicState);
            }
            installedBasicState = handoffBasicState;
        }
        if (handoffRootThread != installedRootThread) {
            LuaState.swapActiveRootThread(handoffRootThread);
            installedRootThread = handoffRootThread;
        }
    }

    private void clearWorkerContext() {
        if (installedActiveState != null) {
            LuaState.swapActiveState(null);
            installedActiveState = null;
        }
        if (installedMaxAlloc != null) {
            LuaState.swapActiveMaxAlloc(null);
            installedMaxAlloc = null;
        }
        if (installedPolicy != null) {
            JavaAccessPolicy.restoreActive(null);
            installedPolicy = null;
        }
        if (installedBasicState != null) {
            LuaValue.popBasicState(null);
            installedBasicState = null;
        }
        if (installedRootThread != null) {
            LuaState.swapActiveRootThread(null);
            installedRootThread = null;
        }
    }

    private LuaValue[] materializeHandoffArgs() {
        int c = handoffArgCount;
        if (c == 0) {
            return EMPTY_VALUES;
        }
        if (c == 1) {
            return new LuaValue[]{handoffSingleArg};
        }
        return handoffArgs != null ? handoffArgs : EMPTY_VALUES;
    }

    private Thread startCoroutineThread(java.util.function.Supplier<LuaValue> body) {
        return Thread.ofVirtual().name("lua-coroutine-" + System.identityHashCode(this)).start(() -> {
            CURRENT_COROUTINE.set(this);
            try {
                syncWorkerContext();
                LuaValue result = body.get();
                completeNormally(result);
            } catch (CoroutineCloseSignal ccs) {
                handoffResult = null;
                handoffSingleResult = null;
                handoffResultCount = 0;
                markDead();
                callStackState.clearSavedErrorStack();
            } catch (org.luava.runtime.LuaExit ex) {
                markDead();
                callStackState.clearSavedErrorStack();
                exitSignal = ex;
                yieldSeq++;
                if (resumerParked) {
                    LockSupport.unpark(resumerThread);
                }
                throw ex;
            } catch (Throwable t) {
                recordFailure(t);
            } finally {
                disarmHook();
                clearWorkerContext();
                CURRENT_COROUTINE.remove();
                yieldSeq++;
                if (resumerParked) {
                    LockSupport.unpark(resumerThread);
                }
            }
        });
    }

    public void completeNormally(LuaValue result) {
        if (result instanceof Varargs va) {
            LuaValue[] vals = va.getValuesUnsafe();
            handoffResult = vals;
            handoffResultCount = vals.length;
            handoffSingleResult = vals.length == 1 ? vals[0] : null;
        } else {
            handoffSingleResult = result;
            handoffResult = null;
            handoffResultCount = 1;
        }
        markDead();
        callStackState.clearSavedErrorStack();
    }

    public void completeNormallyArray(LuaValue[] vals) {
        if (vals == null || vals.length == 0) {
            handoffResult = null;
            handoffSingleResult = null;
            handoffResultCount = 0;
        } else if (vals.length == 1) {
            handoffResult = null;
            handoffSingleResult = vals[0];
            handoffResultCount = 1;
        } else {
            handoffResult = vals;
            handoffSingleResult = null;
            handoffResultCount = vals.length;
        }
        markDead();
        callStackState.clearSavedErrorStack();
    }

    public void suspendStackless(org.luava.runtime.bytecode.VmContext ctx, LuaState state,
            int funcIdx, int nResults, int yieldCount, LuaValue singleYield, LuaValue[] multiYield) {
        this.stacklessCtx = ctx;
        this.stacklessState = state;
        this.activeStacklessCtx = null;
        this.yieldFuncIdx = funcIdx;
        this.yieldNResults = nResults;
        this.handoffResultCount = yieldCount;
        this.handoffSingleResult = singleYield;
        this.handoffResult = multiYield;
        this.status = Status.SUSPENDED;
    }

    public void clearStacklessOnExit() {
        this.activeStacklessCtx = null;
        this.stacklessCtx = null;
        this.stacklessState = null;
        disarmHook();
    }

    public void setExitSignal(org.luava.runtime.LuaExit ex) {
        markDead();
        callStackState.clearSavedErrorStack();
        clearStacklessOnExit();
        exitSignal = ex;
    }

    public void recordFailure(Throwable t) {
        error = t;
        if (callStackState.deathTop > 0 && callStackState.deathStack != null) {
            org.luava.runtime.eval.CallStack.Frame[] ds = callStackState.deathStack;
            int dn = callStackState.deathTop;
            for (int i = 0, j = dn - 1; i < j; i++, j--) {
                org.luava.runtime.eval.CallStack.Frame tmp = ds[i];
                ds[i] = ds[j];
                ds[j] = tmp;
            }
            callStackState.errorStack = ds;
            callStackState.errorTop = dn;
            callStackState.deathStack = null;
            callStackState.deathTop = 0;
        } else {
            callStackState.snapshotErrorStack();
        }
        callStackState.preserveForDeath = false;
        LuaValue errVal;
        if (t instanceof LuaException le && le.getErrorObject() != null) {
            errVal = le.getErrorObject();
        } else {
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            errVal = LuaString.valueOf(msg);
        }
        handoffResult = new LuaValue[]{errVal};
        handoffSingleResult = errVal;
        handoffResultCount = 1;
        markDead();
        clearStacklessOnExit();
    }

    public void graduateToVirtualThread(LuaState state, org.luava.runtime.bytecode.VmContext ctx) {
        long seenYield;
        synchronized (this) {
            this.resumerThread = Thread.currentThread();
            captureCallerContext();
            this.stacklessCtx = null;
            this.stacklessState = null;
            this.activeStacklessCtx = null;
            seenYield = yieldSeq;
            resumeSeq++;
            this.virtualThread = startCoroutineThread(() -> {
                LuaValue[] res = org.luava.runtime.bytecode.BytecodeVM.continueStacklessInVirtualThread(state, ctx);
                return (res.length == 0) ? Varargs.EMPTY : ((res.length == 1) ? res[0] : Varargs.of(res));
            });
        }
        awaitYield(seenYield);
        if (exitSignal != null) {
            throw exitSignal;
        }
    }

    private LuaValue[] resumeCore(int argCount, LuaValue singleArg, LuaValue[] multiArgs) {
        LuaCoroutine callerCoro = CURRENT_COROUTINE.get();
        Thread callerThread = Thread.currentThread();
        boolean runStackless = false;
        LuaState slState = null;
        org.luava.runtime.bytecode.VmContext slCtx = null;
        org.luava.runtime.bytecode.LuaClosure entryClosure = null;

        long seenYield = 0;
        synchronized (this) {
            if (status == Status.DEAD) {
                return RESUME_DEAD_ERROR;
            }
            if (status == Status.RUNNING || status == Status.NORMAL) {
                return RESUME_NON_SUSPENDED_ERROR;
            }

            int depth = (callerCoro != null ? callerCoro.nestedDepth + 1 : 1);
            if (depth >= MAX_NESTED_COROUTINES) {
                return RESUME_OVERFLOW_ERROR;
            }
            this.nestedDepth = depth;

            if (callerCoro != null) {
                callerCoro.status = Status.NORMAL;
            }

            error = null;
            callStackState.clearSavedErrorStack();
            callStackState.preserveForDeath = false;
            callStackState.deathStack = null;
            callStackState.deathTop = 0;

            if (virtualThread == null && !hooksActive) {
                if (stacklessCtx != null) {
                    runStackless = true;
                    slCtx = stacklessCtx;
                    slState = stacklessState;
                    status = Status.RUNNING;
                } else if (entryFunction instanceof org.luava.runtime.bytecode.LuaClosure lc) {
                    LuaState st = lc.getState();
                    if (st == null) {
                        st = LuaState.activeState();
                    }
                    if (st != null) {
                        runStackless = true;
                        slState = st;
                        entryClosure = lc;
                        status = Status.RUNNING;
                    }
                }
            }

            if (!runStackless) {
                resumerThread = callerThread;
                handoffArgCount = argCount;
                handoffSingleArg = singleArg;
                handoffArgs = multiArgs;
                captureCallerContext();
                status = Status.RUNNING;
                seenYield = yieldSeq;
                resumeSeq++;

                if (virtualThread == null) {
                    virtualThread = startCoroutineThread(() -> entryFunction != null
                            ? entryFunction.invoke(materializeHandoffArgs())
                            : LuaNil.NIL);
                } else if (workerParked) {
                    LockSupport.unpark(virtualThread);
                }
            }
        }

        if (runStackless) {
            try {
                org.luava.runtime.bytecode.BytecodeVM.resumeStackless(
                        slState, this, slCtx, entryClosure, argCount, singleArg, multiArgs);
            } finally {
                if (callerCoro != null) {
                    callerCoro.status = Status.RUNNING;
                }
            }
            if (exitSignal != null) {
                throw exitSignal;
            }
            return null;
        }

        awaitYield(seenYield);

        if (callerCoro != null) {
            callerCoro.status = Status.RUNNING;
        }

        if (exitSignal != null) {
            throw exitSignal;
        }

        return null;
    }

    public LuaValue[] resume(LuaValue... args) {
        LuaValue[] resumeArgs = (args != null && args.length > 0 ? args : EMPTY_VALUES);
        if (resumeArgs.length == 1 && resumeArgs[0] instanceof Varargs va) {
            resumeArgs = va.getValuesUnsafe();
        }
        int argCount = resumeArgs.length;
        LuaValue singleArg = (argCount == 1) ? resumeArgs[0] : null;
        LuaValue[] fail = resumeCore(argCount, singleArg, resumeArgs);
        if (fail != null) {
            return fail;
        }

        if (error != null) {
            LuaValue errVal = (handoffResult != null && handoffResult.length > 0) ? handoffResult[0] : LuaString.valueOf("error in coroutine");
            return new LuaValue[]{LuaBoolean.FALSE, errVal};
        }

        int len = handoffResultCount;
        if (len <= 0) {
            return RESUME_SUCCESS_EMPTY;
        }
        if (len == 1) {
            return new LuaValue[]{LuaBoolean.TRUE, handoffSingleResult};
        }
        LuaValue[] res = handoffResult;
        LuaValue[] returnVals = new LuaValue[len + 1];
        returnVals[0] = LuaBoolean.TRUE;
        System.arraycopy(res, 0, returnVals, 1, len);
        return returnVals;
    }

    /**
     * Frameless VM fast lane for {@code coroutine.resume(co, ...)}: writes
     * results straight into the caller's register window without allocating a
     * wrapper array on 0- or 1-value switches. Returns the new VM top.
     */
    public int resumeInline(long[] p, byte[] t, LuaValue[] o, int funcIdx, int nResults,
            int argCount, LuaValue singleArg, LuaValue[] multiArgs) {
        LuaValue[] fail = resumeCore(argCount, singleArg, multiArgs);
        if (fail != null) {
            return writeResumeResults(p, t, o, funcIdx, nResults, fail, fail.length);
        }
        if (error != null) {
            LuaValue errVal = (handoffResult != null && handoffResult.length > 0)
                    ? handoffResult[0] : LuaString.valueOf("error in coroutine");
            return writeTwoResumeResults(p, t, o, funcIdx, nResults, LuaBoolean.FALSE, errVal);
        }
        int len = handoffResultCount;
        if (len <= 0) {
            return writeOneResumeResult(p, t, o, funcIdx, nResults, LuaBoolean.TRUE);
        }
        if (len == 1) {
            return writeTwoResumeResults(p, t, o, funcIdx, nResults, LuaBoolean.TRUE, handoffSingleResult);
        }
        LuaValue[] res = handoffResult;
        int total = len + 1;
        if (nResults >= 0) {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx, LuaBoolean.TRUE);
            for (int i = 1; i < nResults; i++) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i,
                        (i - 1) < len ? res[i - 1] : LuaNil.NIL);
            }
            return funcIdx + nResults;
        }
        org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx, LuaBoolean.TRUE);
        for (int i = 0; i < len; i++) {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + 1 + i, res[i]);
        }
        return funcIdx + total;
    }

    /**
     * Frameless VM fast lane for {@code coroutine.wrap(fn)} wrapper calls.
     * Writes yielded/returned values starting at {@code funcIdx} (without the
     * leading boolean) and returns {@code -1} when non-suspended so the generic
     * wrapper handles status/error diagnostics identically.
     */
    public int resumeWrapInline(long[] p, byte[] t, LuaValue[] o, int funcIdx, int nResults,
            int argCount, LuaValue singleArg, LuaValue[] multiArgs) {
        if (status != Status.SUSPENDED) {
            return -1;
        }
        LuaValue[] fail = resumeCore(argCount, singleArg, multiArgs);
        if (fail != null) {
            if (status == Status.SUSPENDED) {
                try {
                    close();
                } catch (Exception ignored) {}
            }
            throw new LuaException(fail.length > 1 ? fail[1] : LuaString.interned("error in coroutine"));
        }
        if (error != null) {
            if (status == Status.SUSPENDED) {
                try {
                    close();
                } catch (Exception ignored) {}
            }
            LuaValue errVal = (handoffResult != null && handoffResult.length > 0)
                    ? handoffResult[0] : LuaString.interned("error in coroutine");
            throw new LuaException(errVal);
        }
        int len = handoffResultCount;
        if (len <= 0) {
            if (nResults > 0) {
                for (int i = 0; i < nResults; i++) {
                    org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i, LuaNil.NIL);
                }
                return funcIdx + nResults;
            }
            return funcIdx;
        }
        if (len == 1) {
            return writeOneResumeResult(p, t, o, funcIdx, nResults, handoffSingleResult);
        }
        LuaValue[] res = handoffResult;
        return writeResumeResults(p, t, o, funcIdx, nResults, res, len);
    }

    private static int writeOneResumeResult(long[] p, byte[] t, LuaValue[] o,
            int funcIdx, int nResults, LuaValue v0) {
        if (nResults != 0) {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx, v0);
            for (int i = 1; i < nResults; i++) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i, LuaNil.NIL);
            }
        }
        return nResults >= 0 ? funcIdx + nResults : funcIdx + 1;
    }

    private static int writeTwoResumeResults(long[] p, byte[] t, LuaValue[] o,
            int funcIdx, int nResults, LuaValue v0, LuaValue v1) {
        if (nResults != 0) {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx, v0);
            if (nResults != 1) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + 1, v1);
                for (int i = 2; i < nResults; i++) {
                    org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i, LuaNil.NIL);
                }
            }
        }
        return nResults >= 0 ? funcIdx + nResults : funcIdx + 2;
    }

    private static int writeResumeResults(long[] p, byte[] t, LuaValue[] o,
            int funcIdx, int nResults, LuaValue[] vals, int len) {
        if (nResults >= 0) {
            for (int i = 0; i < nResults; i++) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i,
                        i < len ? vals[i] : LuaNil.NIL);
            }
            return funcIdx + nResults;
        }
        for (int i = 0; i < len; i++) {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i, vals[i]);
        }
        return funcIdx + len;
    }

    public static LuaValue[] yield(LuaValue... args) {
        LuaCoroutine current = yieldCheck();
        if (args == null || args.length == 0) {
            current.handoffResultCount = 0;
            current.handoffSingleResult = null;
            current.handoffResult = null;
        } else if (args.length == 1) {
            current.handoffResultCount = 1;
            current.handoffSingleResult = args[0];
            current.handoffResult = null;
        } else {
            current.handoffResultCount = args.length;
            current.handoffSingleResult = null;
            current.handoffResult = args;
        }
        current.performYieldHandoff();
        return current.materializeHandoffArgs();
    }

    /** Frameless VM fast lane for {@code coroutine.yield(...)}. */
    public static int yieldInline(long[] p, byte[] t, LuaValue[] o,
            int funcIdx, int nActualArgs, int nResults) {
        LuaCoroutine current = yieldCheck();
        if (nActualArgs == 0) {
            current.handoffResultCount = 0;
            current.handoffSingleResult = null;
            current.handoffResult = null;
        } else if (nActualArgs == 1) {
            current.handoffResultCount = 1;
            current.handoffSingleResult = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 1);
            current.handoffResult = null;
        } else {
            LuaValue[] out = new LuaValue[nActualArgs];
            for (int i = 0; i < nActualArgs; i++) {
                out[i] = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 1 + i);
            }
            current.handoffResultCount = nActualArgs;
            current.handoffSingleResult = null;
            current.handoffResult = out;
        }
        current.performYieldHandoff();
        int c = current.handoffArgCount;
        if (c == 0) {
            if (nResults > 0) {
                for (int i = 0; i < nResults; i++) {
                    org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, funcIdx + i, LuaNil.NIL);
                }
                return funcIdx + nResults;
            }
            return funcIdx;
        }
        if (c == 1) {
            return writeOneResumeResult(p, t, o, funcIdx, nResults, current.handoffSingleArg);
        }
        LuaValue[] in = current.handoffArgs != null ? current.handoffArgs : EMPTY_VALUES;
        return writeResumeResults(p, t, o, funcIdx, nResults, in, c);
    }

    private static LuaCoroutine yieldCheck() {
        LuaCoroutine current = CURRENT_COROUTINE.get();
        if (current == null || current.isMainThread) {
            throw LuaException.undecorated("attempt to yield from outside a coroutine");
        }
        if (current.nonYieldableCount > 0 || current.isClosing) {
            throw LuaException.undecorated("attempt to yield across a C-call boundary");
        }
        return current;
    }

    private void performYieldHandoff() {
        status = Status.SUSPENDED;
        long seenResume = resumeSeq;
        yieldSeq++;
        if (resumerParked) {
            LockSupport.unpark(resumerThread);
        }

        awaitResume(seenResume);
        syncWorkerContext();

        if (isClosing) {
            throw new CoroutineCloseSignal();
        }
    }

    private void awaitYield(long seen) {
        int budget = resumerSpinBudget;
        for (int i = 0; i < budget; i++) {
            if (yieldSeq != seen) {
                if (budget < 4096) resumerSpinBudget = budget + 64;
                return;
            }
            Thread.onSpinWait();
        }
        long deadline = System.nanoTime() + HANDOFF_SPIN_NANOS;
        while (yieldSeq == seen) {
            if (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            } else {
                resumerSpinBudget = 64;
                resumerParked = true;
                if (yieldSeq == seen) {
                    LockSupport.park();
                }
                resumerParked = false;
                if (yieldSeq == seen) {
                    org.luava.runtime.LuaState.checkGuard();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new LuaException("execution interrupted");
                    }
                }
            }
        }
    }

    private void awaitResume(long seen) {
        int budget = workerSpinBudget;
        for (int i = 0; i < budget; i++) {
            if (resumeSeq != seen) {
                if (budget < 4096) workerSpinBudget = budget + 64;
                return;
            }
            Thread.onSpinWait();
        }
        long deadline = System.nanoTime() + HANDOFF_SPIN_NANOS;
        while (resumeSeq == seen) {
            if (System.nanoTime() < deadline) {
                Thread.onSpinWait();
            } else {
                workerSpinBudget = 64;
                workerParked = true;
                if (resumeSeq == seen) {
                    LockSupport.park();
                }
                workerParked = false;
                if (resumeSeq == seen) {
                    org.luava.runtime.LuaState.checkGuard();
                    if (Thread.currentThread().isInterrupted()) {
                        throw new LuaException("execution interrupted");
                    }
                }
            }
        }
    }

    public LuaValue[] close() {
        LuaCoroutine callerCoro = CURRENT_COROUTINE.get();
        long seenYield;
        synchronized (this) {
            if (status == Status.RUNNING || status == Status.NORMAL) {
                throw new LuaException("cannot close a " + status.label() + " coroutine");
            }
            if (status == Status.DEAD) {
                if (error != null) {
                    LuaValue errVal = (handoffResult != null && handoffResult.length > 0) ? handoffResult[0] : LuaString.valueOf("error in coroutine");
                    error = null;
                    handoffResult = null;
                    return new LuaValue[]{LuaValue.valueOf(false), errVal};
                }
                return new LuaValue[]{LuaValue.valueOf(true)};
            }

            int depth = (callerCoro != null ? callerCoro.nestedDepth + 1 : 1);
            if (depth >= MAX_NESTED_COROUTINES) {
                return new LuaValue[]{LuaBoolean.FALSE, LuaString.valueOf("C stack overflow")};
            }
            this.nestedDepth = depth;

            if (virtualThread == null) {
                if (stacklessCtx != null && tbcHead != null) {
                    if (callerCoro != null) {
                        callerCoro.status = Status.NORMAL;
                    }
                    org.luava.runtime.bytecode.VmContext closeCtx = stacklessCtx;
                    LuaState closeState = stacklessState;
                    stacklessCtx = null;
                    stacklessState = null;
                    activeStacklessCtx = null;
                    this.isClosing = true;
                    this.resumerThread = Thread.currentThread();
                    captureCallerContext();
                    this.status = Status.RUNNING;
                    seenYield = yieldSeq;
                    resumeSeq++;
                    this.virtualThread = startCoroutineThread(() -> {
                        LuaValue[] res = org.luava.runtime.bytecode.BytecodeVM
                                .continueStacklessInVirtualThread(closeState, closeCtx);
                        return (res.length == 0) ? Varargs.EMPTY
                                : ((res.length == 1) ? res[0] : Varargs.of(res));
                    });
                } else {
                    if (stacklessCtx != null) {
                        org.luava.runtime.bytecode.BytecodeVM.closeStacklessClean(stacklessState, this, stacklessCtx);
                    }
                    markDead();
                    disarmHook();
                    return new LuaValue[]{LuaValue.valueOf(true)};
                }
            } else {
                if (callerCoro != null) {
                    callerCoro.status = Status.NORMAL;
                }

                this.isClosing = true;
                this.resumerThread = Thread.currentThread();
                captureCallerContext();
                this.status = Status.RUNNING;
                seenYield = yieldSeq;
                resumeSeq++;
                if (workerParked) {
                    LockSupport.unpark(virtualThread);
                }
            }
        }

        awaitYield(seenYield);

        if (callerCoro != null) {
            callerCoro.status = Status.RUNNING;
        }

        if (error != null) {
            LuaValue errVal = (handoffResult != null && handoffResult.length > 0) ? handoffResult[0] : LuaString.valueOf("error in coroutine");
            error = null;
            handoffResult = null;
            return new LuaValue[]{LuaValue.valueOf(false), errVal};
        }
        return new LuaValue[]{LuaValue.valueOf(true)};
    }

    @Override
    public LuaType type() {
        return LuaType.THREAD;
    }

    @Override
    public boolean isThread() {
        return true;
    }

    @Override
    public String toLuaString() {
        return "thread: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

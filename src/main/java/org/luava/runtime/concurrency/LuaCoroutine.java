package org.luava.runtime.concurrency;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaType;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;
import org.luava.runtime.eval.CallStack;

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
     * Global hook-armed flag for the VM hot path. True when at least one
     * coroutine has an active hook. The interpreter loop checks this single
     * predictable field instead of ThreadLocal + config per instruction.
     * Biased to stay true (only perf cost); never false while a hook exists.
     */
    public static volatile boolean HOOKS_ARMED = false;
    private static int hookArmedCount = 0;
    private boolean hookArmedCounted = false;

    private static void updateHookArmed(boolean wasActive, boolean nowActive) {
        if (wasActive == nowActive) return;
        synchronized (LuaCoroutine.class) {
            if (nowActive) {
                hookArmedCount++;
            } else if (hookArmedCount > 0) {
                hookArmedCount--;
            }
            HOOKS_ARMED = hookArmedCount > 0;
        }
    }

    /** Disarm hook counting (idempotent; safe to call on death/close). */
    private void disarmHookCounted() {
        if (hookArmedCounted) {
            hookArmedCounted = false;
            synchronized (LuaCoroutine.class) {
                if (hookArmedCount > 0) hookArmedCount--;
                HOOKS_ARMED = hookArmedCount > 0;
            }
        }
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
    private volatile Thread resumerThread;
    private volatile Thread virtualThread;
    private volatile LuaValue[] handoffArgs;
    private volatile LuaValue[] handoffResult;
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
        boolean wasActive = hookArmedCounted;
        hookConfig.hook = (hook != null && !hook.isNil()) ? hook : LuaNil.NIL;
        hookConfig.mask = mask != null ? mask : "";
        hookConfig.count = Math.max(0, count);
        hookConfig.hookCall = hookConfig.mask.contains("c");
        hookConfig.hookReturn = hookConfig.mask.contains("r");
        hookConfig.hookLine = hookConfig.mask.contains("l");
        hookConfig.countSoFar = 0;
        hookConfig.lastLine = -1;
        boolean nowActive = !hookConfig.hook.isNil()
                && (hookConfig.hookCall || hookConfig.hookReturn || hookConfig.hookLine || hookConfig.count > 0);
        hookArmedCounted = nowActive;
        updateHookArmed(wasActive, nowActive);

        CallStack.CallStackState state = getCallStackState();
        if (state != null && state.top > 1) {
            CallStack.Frame caller = state.stack[state.top - 2];
            if (caller != null && caller.line > 0) {
                caller.lastLine = caller.line;
            }
        }
    }

    public void clearHook() {
        disarmHookCounted();
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
     * Lua 5.4 semantics (ldebug.c: npci <= oldpc || changedline) are evaluated in BytecodeVM loop.
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

    private volatile boolean isClosing = false;

    public LuaValue[] resume(LuaValue... args) {
        LuaCoroutine callerCoro = CURRENT_COROUTINE.get();
        Thread callerThread = Thread.currentThread();
        LuaValue[] resumeArgs = (args != null && args.length > 0 ? args : EMPTY_VALUES);
        if (resumeArgs.length == 1 && resumeArgs[0] instanceof Varargs va) {
            resumeArgs = va.getValuesUnsafe();
        }

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

            resumerThread = callerThread;
            handoffArgs = resumeArgs;
            status = Status.RUNNING;
            callStackState.clearSavedErrorStack();
            callStackState.preserveForDeath = false;
            callStackState.deathStack = null;
            callStackState.deathTop = 0;
            error = null;

            if (virtualThread == null) {
                virtualThread = Thread.ofVirtual().name("lua-coroutine-" + System.identityHashCode(this)).start(() -> {
                    CURRENT_COROUTINE.set(this);
                    try {
                        LuaValue result = entryFunction != null ? entryFunction.invoke(handoffArgs) : LuaNil.NIL;
                        if (result instanceof Varargs va) {
                            handoffResult = va.getValuesUnsafe();
                        } else {
                            handoffResult = new LuaValue[]{result};
                        }
                        status = Status.DEAD;
                        callStackState.clearSavedErrorStack();
                    } catch (CoroutineCloseSignal ccs) {
                        status = Status.DEAD;
                        callStackState.clearSavedErrorStack();
                    } catch (Throwable t) {
                        error = t;
                        // Transfer death frames saved by reference during fatal
                        // unwind (no copy). Pops save innermost-first, but stack
                        // layout is outermost-first, so reverse (pointer swaps).
                        // Falls back to snapshot if none.
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
                        status = Status.DEAD;
                    } finally {
                        disarmHookCounted();
                        CURRENT_COROUTINE.remove();
                        LockSupport.unpark(resumerThread);
                    }
                });
            } else {
                LockSupport.unpark(virtualThread);
            }
        }

        while (status == Status.RUNNING || status == Status.NORMAL) {
            LockSupport.park();
        }

        if (callerCoro != null) {
            callerCoro.status = Status.RUNNING;
        }

        if (error != null) {
            LuaValue errVal = (handoffResult != null && handoffResult.length > 0) ? handoffResult[0] : LuaString.valueOf("error in coroutine");
            return new LuaValue[]{LuaBoolean.FALSE, errVal};
        }

        if (handoffResult == null || handoffResult.length == 0) {
            return RESUME_SUCCESS_EMPTY;
        }
        int len = handoffResult.length;
        if (len == 1) {
            return new LuaValue[]{LuaBoolean.TRUE, handoffResult[0]};
        }
        LuaValue[] returnVals = new LuaValue[len + 1];
        returnVals[0] = LuaBoolean.TRUE;
        System.arraycopy(handoffResult, 0, returnVals, 1, len);
        return returnVals;
    }

    public static LuaValue[] yield(LuaValue... args) {
        LuaCoroutine current = CURRENT_COROUTINE.get();
        if (current == null || current.isMainThread) {
            throw new LuaException("attempt to yield from outside a coroutine");
        }
        if (current.nonYieldableCount > 0 || current.isClosing) {
            throw new LuaException("attempt to yield across a C-call boundary");
        }
        current.handoffResult = (args != null && args.length > 0 ? args : EMPTY_VALUES);
        current.status = Status.SUSPENDED;

        LockSupport.unpark(current.resumerThread);

        while (current.status == Status.SUSPENDED) {
            LockSupport.park();
        }

        if (current.isClosing) {
            throw new CoroutineCloseSignal();
        }

        return current.handoffArgs != null ? current.handoffArgs : EMPTY_VALUES;
    }

    public LuaValue[] close() {
        LuaCoroutine callerCoro = CURRENT_COROUTINE.get();
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
                status = Status.DEAD;
                disarmHookCounted();
                return new LuaValue[]{LuaValue.valueOf(true)};
            }

            if (callerCoro != null) {
                callerCoro.status = Status.NORMAL;
            }

            this.isClosing = true;
            this.resumerThread = Thread.currentThread();
            this.status = Status.RUNNING;
            LockSupport.unpark(virtualThread);
        }

        while (status == Status.RUNNING || status == Status.NORMAL) {
            LockSupport.park();
        }

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

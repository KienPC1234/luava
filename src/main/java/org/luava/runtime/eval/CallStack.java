package org.luava.runtime.eval;

import org.luava.runtime.LuaFunction;
import org.luava.runtime.concurrency.LuaCoroutine;

public final class CallStack {
    public static final class Frame {
        public LuaFunction function;
        public String name;
        public String namewhat;
        public int line;
        public int lastLine = -1;
        public Environment env;
        public boolean isMethod;
        public boolean isMetamethod;
        public final java.util.List<org.luava.runtime.LuaValue> temps = new java.util.ArrayList<>();
        public org.luava.runtime.LuaValue[] cArgs;
        public org.luava.runtime.LuaValue[] retValues;
        public int ftransfer = 0;
        public int ntransfer = 0;
        public boolean isTailCall = false;
        public int baseIndex = -1;
        public int funcIndex = -1;
        public int pc = -1;
        public org.luava.runtime.LuaState state;
        public org.luava.runtime.LuaValue[] varargs;

        public void pushTemp(org.luava.runtime.LuaValue v) {
            temps.add(v);
        }

        public org.luava.runtime.LuaValue popTemp() {
            if (temps.isEmpty()) return org.luava.runtime.LuaNil.NIL;
            return temps.remove(temps.size() - 1);
        }

        public Frame copy() {
            Frame f = new Frame(this.function, this.name, this.namewhat, this.line, this.isMethod, this.isMetamethod);
            f.lastLine = this.lastLine;
            f.env = this.env;
            f.isTailCall = this.isTailCall;
            f.baseIndex = this.baseIndex;
            f.funcIndex = this.funcIndex;
            f.pc = this.pc;
            f.state = this.state;
            f.varargs = this.varargs;
            f.temps.addAll(this.temps);
            if (this.cArgs != null) {
                f.cArgs = this.cArgs.clone();
            }
            if (this.retValues != null) {
                f.retValues = this.retValues.clone();
            }
            f.ftransfer = this.ftransfer;
            f.ntransfer = this.ntransfer;
            return f;
        }

        public Frame(LuaFunction function, String name, String namewhat, int line, boolean isMethod, boolean isMetamethod) {
            this.function = function;
            this.name = name;
            this.namewhat = namewhat;
            this.line = line;
            this.isMethod = isMethod;
            this.isMetamethod = isMetamethod;
        }

        public Frame(LuaFunction function, String name, int line, boolean isMethod, boolean isMetamethod) {
            this(function, name, isMetamethod ? "metamethod" : (isMethod ? "method" : null), line, isMethod, isMetamethod);
        }

        public Frame(LuaFunction function, String name, int line, boolean isMethod) {
            this(function, name, line, isMethod, false);
        }
    }

    public static final int MAX_CALL_DEPTH = 200;
    public static final int EXTRA_STACK_SLOTS = 50;

    public static final class ProtectedFrame {
        public final org.luava.runtime.LuaValue handler; // null for pcall, non-null for xpcall
        public final int stackDepth;
        public boolean handling;

        public ProtectedFrame(org.luava.runtime.LuaValue handler, int stackDepth) {
            this.handler = handler;
            this.stackDepth = stackDepth;
            this.handling = false;
        }
    }

    public static final class CallStackState {
        public Frame[] stack = new Frame[256];
        public int top = 0;
        public Frame[] errorStack = null;
        public int errorTop = 0;
        // When true, pop() is skipped to preserve death frames for
        // debug.traceback on a coroutine killed by an unprotected error.
        // Set at fatal-error time (cheap flag, no copying); the coroutine
        // dies so skipped pops never leak (no further pushes on dead state).
        public boolean preserveForDeath = false;
        // Death frames saved by reference during fatal unwind (no copy, no wipe).
        // Transferred to errorStack when the coroutine dies.
        public Frame[] deathStack = null;
        public int deathTop = 0;
        public boolean nextMethod = false;
        public String nextName = null;
        public String nextNamewhat = null;
        public boolean nextMetamethod = false;
        public int nextFtransfer = 0;
        public int nextNtransfer = 0;
        public org.luava.runtime.LuaValue[] nextCArgs = null;
        public Environment nextEnv = null;
        public int nextBaseIndex = -1;
        public int nextFuncIndex = -1;
        public int nextPc = -1;
        public org.luava.runtime.LuaState nextVmState = null;
        public org.luava.runtime.LuaValue[] nextVarargs = null;
        public int closingCount = 0;
        public final java.util.List<ProtectedFrame> protectedFrames = new java.util.ArrayList<>();

        public void snapshotErrorStack() {
            if (errorStack != null) return;
            errorTop = top;
            errorStack = new Frame[Math.max(top, 16)];
            for (int i = 0; i < top; i++) {
                Frame f = stack[i];
                errorStack[i] = (f != null) ? f.copy() : null;
            }
        }

        public void clearSavedErrorStack() {
            errorStack = null;
            errorTop = 0;
        }

        public int depth() {
            if (errorStack != null) {
                return errorTop;
            }
            return top;
        }

        public Frame getFrame(int level) {
            Frame[] s = (errorStack != null) ? errorStack : stack;
            int t = (errorStack != null) ? errorTop : top;
            int idx = t - 1 - level;
            if (idx >= 0 && idx < t) {
                return s[idx];
            }
            return null;
        }

        public int debugTop() {
            return (errorStack != null) ? errorTop : top;
        }

        public boolean debugHasErrorStack() {
            return errorStack != null;
        }
    }

    public static void recordErrorSnapshotIfUnprotected() {
        CallStackState state = currentState();
        if (state.errorStack == null && state.protectedFrames.isEmpty()) {
            state.snapshotErrorStack();
        }
    }

    private static final ThreadLocal<CallStackState> DEFAULT_STATE = ThreadLocal.withInitial(CallStackState::new);

    private CallStack() {}

    public static CallStackState currentState() {
        LuaCoroutine cur = LuaCoroutine.running();
        return cur != null ? cur.getCallStackState() : DEFAULT_STATE.get();
    }

    public static void setNextCallMethod(boolean isMethod) {
        currentState().nextMethod = isMethod;
    }

    public static void setNextCall(String name, String namewhat, boolean isMethod, boolean isMetamethod) {
        CallStackState state = currentState();
        state.nextName = name;
        state.nextNamewhat = namewhat;
        state.nextMethod = isMethod;
        state.nextMetamethod = isMetamethod;
    }

    public static void setNextCall(String name, boolean isMetamethod) {
        setNextCall(name, isMetamethod ? "metamethod" : null, false, isMetamethod);
    }

    /**
     * Clear a stale next-call name (e.g. set for a call target that pushes no
     * frame, like a Java generic-for iterator). Only clears when the pending
     * value still equals {@code expected}, so nested legitimate values survive.
     */
    public static void clearNextCallIf(String expected) {
        CallStackState state = currentState();
        if (expected != null ? expected.equals(state.nextName) : state.nextName == null) {
            state.nextName = null;
            state.nextNamewhat = null;
            state.nextMetamethod = false;
            state.nextMethod = false;
        }
    }

    public static void setNextTransfer(int ftransfer, int ntransfer, org.luava.runtime.LuaValue[] cArgs, Environment env) {
        CallStackState state = currentState();
        state.nextFtransfer = ftransfer;
        state.nextNtransfer = ntransfer;
        state.nextCArgs = cArgs;
        state.nextEnv = env;
    }

    public static void setNextTransfer(int ftransfer, int ntransfer, org.luava.runtime.LuaValue[] cArgs) {
        setNextTransfer(ftransfer, ntransfer, cArgs, null);
    }

    public static void setNextVmFrame(org.luava.runtime.LuaState vmState, int baseIndex, int funcIndex, org.luava.runtime.LuaValue[] varargs, int pc) {
        CallStackState state = currentState();
        state.nextVmState = vmState;
        state.nextBaseIndex = baseIndex;
        state.nextFuncIndex = funcIndex;
        state.nextVarargs = varargs;
        state.nextPc = pc;
    }

    public static void pushProtectedFrame(org.luava.runtime.LuaValue handler) {
        CallStackState state = currentState();
        state.protectedFrames.add(new ProtectedFrame(handler, state.depth()));
    }

    public static void popProtectedFrame() {
        CallStackState state = currentState();
        java.util.List<ProtectedFrame> list = state.protectedFrames;
        if (!list.isEmpty()) {
            list.remove(list.size() - 1);
        }
        if (list.isEmpty()) {
            state.clearSavedErrorStack();
        }
    }

    public static void pushErrorHandler(org.luava.runtime.LuaValue handler) {
        pushProtectedFrame(handler);
    }

    public static void popErrorHandler() {
        popProtectedFrame();
    }

    public static boolean hasErrorHandler() {
        java.util.List<ProtectedFrame> list = currentState().protectedFrames;
        if (list.isEmpty()) return false;
        ProtectedFrame top = list.get(list.size() - 1);
        return top.handler != null;
    }

    public static boolean isHandlingError() {
        java.util.List<ProtectedFrame> list = currentState().protectedFrames;
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).handling) return true;
        }
        return false;
    }

    public static boolean canHandleError() {
        java.util.List<ProtectedFrame> list = currentState().protectedFrames;
        for (int i = list.size() - 1; i >= 0; i--) {
            ProtectedFrame pf = list.get(i);
            if (pf.handling) return false;
            if (pf.handler != null) return true;
            return false;
        }
        return false;
    }

    public static org.luava.runtime.LuaValue runErrorHandler(org.luava.runtime.LuaValue errObj) {
        java.util.List<ProtectedFrame> list = currentState().protectedFrames;
        if (list.isEmpty()) return errObj;
        ProtectedFrame top = list.get(list.size() - 1);
        if (top.handler == null) return errObj;
        if (top.handling) {
            return org.luava.runtime.LuaString.valueOf("error in error handling");
        }
        top.handling = true;
        // Push a frame for the handler so that debug.traceback's default level=1
        // correctly skips the handler itself (level 0) and starts at the error site.
        boolean pushedHandlerFrame = false;
        if (top.handler instanceof org.luava.runtime.LuaFunction fn) {
            push(fn, fn.getName() != null ? fn.getName() : "?", "C", -1, false, false);
            pushedHandlerFrame = true;
        }
        try {
            return top.handler.call(errObj);
        } catch (Throwable t) {
            return org.luava.runtime.LuaString.valueOf("error in error handling");
        } finally {
            if (pushedHandlerFrame) {
                pop();
            }
            top.handling = false;
        }
    }

    public static void push(LuaFunction fn, String name, String namewhat, int line, boolean isMethod, boolean isMetamethod) {
        push(fn, name, namewhat, line, isMethod, isMetamethod, false);
    }

    public static void push(LuaFunction fn, String name, String namewhat, int line, boolean isMethod, boolean isMetamethod, boolean isTailCall) {
        CallStackState state = currentState();
        int top = state.top;
        if (isHandlingError()) {
            if (top >= MAX_CALL_DEPTH + EXTRA_STACK_SLOTS) {
                org.luava.runtime.LuaException le = new org.luava.runtime.LuaException("error in error handling");
                le.setDecorated(true);
                throw le;
            }
        } else {
            if (top >= MAX_CALL_DEPTH) {
                throw new org.luava.runtime.LuaException("stack overflow");
            }
        }

        if (top >= state.stack.length) {
            Frame[] newStack = new Frame[state.stack.length * 2];
            System.arraycopy(state.stack, 0, newStack, 0, state.stack.length);
            state.stack = newStack;
        }
        Frame frame = state.stack[top];
        String resolvedName = name != null ? name : (fn != null ? fn.getName() : null);
        if (frame == null) {
            frame = new Frame(fn, resolvedName, namewhat, line, isMethod, isMetamethod);
            frame.lastLine = -1;
            state.stack[top] = frame;
        } else {
            frame.function = fn;
            frame.name = resolvedName;
            frame.namewhat = namewhat;
            frame.line = line;
            frame.lastLine = -1;
            frame.isMethod = isMethod;
            frame.isMetamethod = isMetamethod;
            frame.temps.clear();
            frame.cArgs = null;
            frame.retValues = null;
            frame.ftransfer = 0;
            frame.ntransfer = 0;
            frame.env = null;
            frame.baseIndex = -1;
            frame.funcIndex = -1;
            frame.pc = -1;
            frame.state = null;
            frame.varargs = null;
        }
        frame.isTailCall = isTailCall;
        frame.ftransfer = state.nextFtransfer;
        frame.ntransfer = state.nextNtransfer;
        frame.cArgs = state.nextCArgs;
        if (state.nextEnv != null) {
            frame.env = state.nextEnv;
        }
        frame.baseIndex = state.nextBaseIndex;
        frame.funcIndex = state.nextFuncIndex;
        frame.pc = state.nextPc;
        frame.state = state.nextVmState;
        frame.varargs = state.nextVarargs;
        state.nextBaseIndex = -1;
        state.nextFuncIndex = -1;
        state.nextPc = -1;
        state.nextVmState = null;
        state.nextVarargs = null;
        state.nextFtransfer = 0;
        state.nextNtransfer = 0;
        state.nextCArgs = null;
        state.nextEnv = null;
        state.nextName = null;
        state.nextNamewhat = null;
        state.nextMetamethod = false;
        state.nextMethod = false;
        state.top = top + 1;
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            cur.setLastLine(-1);
            if (isTailCall) {
                cur.fireTailCallHook();
            } else {
                cur.fireCallHook();
            }
        }
    }

    public static void push(LuaFunction fn, String name, int line) {
        pushWithState(fn, name, line, false);
    }

    public static void pushTail(LuaFunction fn, String name, int line) {
        pushWithState(fn, name, line, true);
    }

    private static void pushWithState(LuaFunction fn, String name, int line, boolean isTailCall) {
        CallStackState state = currentState();
        String overrideName = state.nextName;
        String namewhat = state.nextNamewhat;
        boolean isMeta = state.nextMetamethod;
        boolean m = state.nextMethod;
        state.nextName = null;
        state.nextNamewhat = null;
        state.nextMetamethod = false;
        state.nextMethod = false;

        String finalName = (overrideName != null) ? overrideName : name;
        push(fn, finalName, namewhat, line, m, isMeta, isTailCall);
    }

    public static void replaceTailCall(LuaFunction fn, String name, int line) {
        replaceTailCall(fn, name, null, line, false, false);
    }

    public static void replaceTailCall(LuaFunction fn, String name, String namewhat, int line, boolean isMethod, boolean isMetamethod) {
        CallStackState state = currentState();
        if (state.top > 0) {
            String overrideName = state.nextName;
            String overrideNamewhat = state.nextNamewhat;
            boolean isMeta = isMetamethod || state.nextMetamethod;
            boolean m = isMethod || state.nextMethod;
            state.nextName = null;
            state.nextNamewhat = null;
            state.nextMetamethod = false;
            state.nextMethod = false;

            String finalName = (overrideName != null) ? overrideName : (name != null ? name : (fn != null ? fn.getName() : null));
            String finalNamewhat = (overrideNamewhat != null) ? overrideNamewhat : namewhat;

            Frame frame = state.stack[state.top - 1];
            frame.function = fn;
            frame.name = finalName;
            frame.namewhat = finalNamewhat;
            frame.line = line;
            frame.lastLine = -1;
            frame.isMethod = m;
            frame.isMetamethod = isMeta;
            frame.isTailCall = true;
            frame.temps.clear();
            frame.cArgs = state.nextCArgs;
            frame.retValues = null;
            frame.ftransfer = state.nextFtransfer;
            frame.ntransfer = state.nextNtransfer;
            if (state.nextEnv != null) {
                frame.env = state.nextEnv;
            }
            frame.baseIndex = state.nextBaseIndex;
            frame.funcIndex = state.nextFuncIndex;
            frame.pc = state.nextPc;
            frame.state = state.nextVmState;
            frame.varargs = state.nextVarargs;
            state.nextBaseIndex = -1;
            state.nextFuncIndex = -1;
            state.nextPc = -1;
            state.nextVmState = null;
            state.nextVarargs = null;
            state.nextFtransfer = 0;
            state.nextNtransfer = 0;
            state.nextCArgs = null;
            state.nextEnv = null;

            LuaCoroutine cur = LuaCoroutine.running();
            if (cur != null) {
                cur.setLastLine(-1);
                cur.fireTailCallHook();
            }
        }
    }

    public static void fireCountHook() {
        if (Thread.currentThread().isInterrupted()) {
            throw new org.luava.runtime.LuaException("interrupted");
        }
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            cur.fireCountHook();
        }
    }

    public static void setLine(int line) {
        CallStackState state = currentState();
        Frame topFrame = null;
        if (state.top > 0) {
            topFrame = state.stack[state.top - 1];
            topFrame.line = line;
        }
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            cur.fireLineAndCountHook(line, topFrame);
        }
    }

    public static void resetLastLine() {
        CallStackState state = currentState();
        if (state.top > 0) {
            state.stack[state.top - 1].lastLine = -1;
        }
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            cur.resetLastLine();
        }
    }

    public static Frame topFrame() {
        CallStackState state = currentState();
        return (state != null && state.top > 0) ? state.stack[state.top - 1] : null;
    }

    public static void pop() {
        CallStackState state = currentState();
        if (state.top > 0) {
            LuaCoroutine cur = LuaCoroutine.running();
            if (cur != null) {
                cur.fireReturnHook();
            }
            state.top--;
            Frame topFrame = state.stack[state.top];
            if (topFrame != null) {
                if (state.preserveForDeath) {
                    // Fatal unwind: save reference (no wipe, no copy) for
                    // dead-coroutine traceback. Top still decrements so
                    // unwind loops terminate.
                    if (state.deathStack == null) {
                        state.deathStack = new Frame[Math.max(state.top + 1, 16)];
                    } else if (state.deathTop >= state.deathStack.length) {
                        Frame[] bigger = new Frame[state.deathStack.length * 2];
                        System.arraycopy(state.deathStack, 0, bigger, 0, state.deathTop);
                        state.deathStack = bigger;
                    }
                    state.deathStack[state.deathTop++] = topFrame;
                } else {
                    topFrame.function = null;
                    topFrame.name = null;
                    topFrame.namewhat = null;
                    topFrame.lastLine = -1;
                    topFrame.env = null;
                    topFrame.temps.clear();
                    topFrame.cArgs = null;
                    topFrame.retValues = null;
                    topFrame.ftransfer = 0;
                    topFrame.ntransfer = 0;
                    topFrame.isTailCall = false;
                    topFrame.baseIndex = -1;
                    topFrame.funcIndex = -1;
                    topFrame.pc = -1;
                    topFrame.state = null;
                    topFrame.varargs = null;
                }
            }
            if (cur != null) {
                if (state.top > 0) {
                    cur.setLastLine(state.stack[state.top - 1].lastLine);
                } else {
                    cur.setLastLine(-1);
                }
            }
        }
    }

    public static void popTailCall() {
        CallStackState state = currentState();
        if (state.top > 0) {
            state.top--;
            Frame topFrame = state.stack[state.top];
            if (topFrame != null) {
                topFrame.function = null;
                topFrame.name = null;
                topFrame.namewhat = null;
                topFrame.lastLine = -1;
                topFrame.env = null;
                topFrame.temps.clear();
                topFrame.cArgs = null;
                topFrame.retValues = null;
                topFrame.ftransfer = 0;
                topFrame.ntransfer = 0;
                topFrame.isTailCall = false;
                topFrame.baseIndex = -1;
                topFrame.funcIndex = -1;
                topFrame.pc = -1;
                topFrame.state = null;
                topFrame.varargs = null;
            }
            LuaCoroutine cur = LuaCoroutine.running();
            if (cur != null) {
                if (state.top > 0) {
                    cur.setLastLine(state.stack[state.top - 1].lastLine);
                } else {
                    cur.setLastLine(-1);
                }
            }
        }
    }

    public static Frame getFrame(int level) {
        return currentState().getFrame(level);
    }

    public static Frame getFrame(LuaCoroutine co, int level) {
        if (co != null) {
            return co.getCallStackState().getFrame(level);
        }
        return getFrame(level);
    }

    public static int depth() {
        return currentState().depth();
    }

    public static int depth(LuaCoroutine co) {
        if (co != null) {
            return co.getCallStackState().depth();
        }
        return depth();
    }
}

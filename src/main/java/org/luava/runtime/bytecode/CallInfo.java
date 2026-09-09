package org.luava.runtime.bytecode;

public final class CallInfo {
    public LuaClosure closure;
    public int funcIndex;
    public int baseIndex;
    public int topIndex;
    public int savedPc;
    public int expectedResults;
    public boolean isTailCall;

    public CallInfo() {}

    public void init(LuaClosure closure, int funcIndex, int baseIndex, int topIndex, int savedPc, int expectedResults) {
        this.closure = closure;
        this.funcIndex = funcIndex;
        this.baseIndex = baseIndex;
        this.topIndex = topIndex;
        this.savedPc = savedPc;
        this.expectedResults = expectedResults;
        this.isTailCall = false;
    }

    public void clear() {
        this.closure = null;
        this.funcIndex = 0;
        this.baseIndex = 0;
        this.topIndex = 0;
        this.savedPc = 0;
        this.expectedResults = 0;
        this.isTailCall = false;
    }
}

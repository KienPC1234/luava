/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

public final class CallInfo {
    public LuaClosure closure;
    public int funcIndex;
    public int baseIndex;
    public int topIndex;
    public int savedPc;
    public int expectedResults;
    public boolean isTailCall;
    public org.luava.runtime.LuaValue[] varargs;
    public int oldpc = -1;
    public boolean varargPrepRan = false;

    public CallInfo() {}

    public void init(LuaClosure closure, int funcIndex, int baseIndex, int topIndex, int savedPc, int expectedResults) {
        this.closure = closure;
        this.funcIndex = funcIndex;
        this.baseIndex = baseIndex;
        this.topIndex = topIndex;
        this.savedPc = savedPc;
        this.expectedResults = expectedResults;
        this.isTailCall = false;
        this.varargs = null;
        this.oldpc = -1;
        this.varargPrepRan = false;
    }

    public void clear() {
        this.closure = null;
        this.funcIndex = 0;
        this.baseIndex = 0;
        this.topIndex = 0;
        this.savedPc = 0;
        this.expectedResults = 0;
        this.isTailCall = false;
        this.varargs = null;
        this.oldpc = -1;
        this.varargPrepRan = false;
    }
}

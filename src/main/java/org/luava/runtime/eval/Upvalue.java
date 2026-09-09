package org.luava.runtime.eval;

import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaValue;

public final class Upvalue {
    private final String name;
    private Environment.VariableSlot slot;

    private LuaValue[] stack;
    private org.luava.runtime.LuaState state;
    private int stackIndex = -1;
    private LuaValue closedValue = LuaNil.NIL;
    private boolean isOpenOnStack = false;

    public Upvalue(String name, Environment.VariableSlot slot) {
        this.name = name != null ? name : "?";
        this.slot = slot != null ? slot : new Environment.VariableSlot(LuaNil.NIL, false, false);
    }

    public Upvalue(String name, LuaValue[] stack, int stackIndex) {
        this.name = name != null ? name : "?";
        this.stack = stack;
        this.stackIndex = stackIndex;
        this.isOpenOnStack = true;
    }

    public Upvalue(String name, org.luava.runtime.LuaState state, int stackIndex) {
        this.name = name != null ? name : "?";
        this.state = state;
        this.stackIndex = stackIndex;
        this.isOpenOnStack = true;
    }

    public Upvalue(String name, LuaValue initialValue) {
        this.name = name != null ? name : "?";
        this.closedValue = initialValue != null ? initialValue : LuaNil.NIL;
        this.isOpenOnStack = false;
    }

    public String getName() {
        return name;
    }

    public Environment.VariableSlot getSlot() {
        return slot;
    }

    public void setSlot(Environment.VariableSlot slot) {
        this.slot = slot;
    }

    public boolean isOpenOnStack() {
        return isOpenOnStack;
    }

    public int getStackIndex() {
        return stackIndex;
    }

    public void close() {
        if (isOpenOnStack && state != null && stackIndex >= 0) {
            this.closedValue = state.getStackValue(stackIndex);
            this.state = null;
            this.isOpenOnStack = false;
        } else if (isOpenOnStack && stack != null && stackIndex >= 0) {
            this.closedValue = stack[stackIndex] != null ? stack[stackIndex] : LuaNil.NIL;
            this.stack = null;
            this.isOpenOnStack = false;
        } else if (slot != null) {
            this.closedValue = slot.get();
            this.slot = null;
        }
    }

    public LuaValue getValue() {
        if (isOpenOnStack && state != null && stackIndex >= 0) {
            return state.getStackValue(stackIndex);
        }
        if (isOpenOnStack && stack != null && stackIndex >= 0) {
            LuaValue v = stack[stackIndex];
            return v != null ? v : LuaNil.NIL;
        }
        if (slot != null) {
            return slot.get();
        }
        return closedValue != null ? closedValue : LuaNil.NIL;
    }

    public void setValue(LuaValue val) {
        LuaValue v = val != null ? val : LuaNil.NIL;
        if (isOpenOnStack && state != null && stackIndex >= 0) {
            state.setStackValue(stackIndex, v);
            return;
        }
        if (isOpenOnStack && stack != null && stackIndex >= 0) {
            stack[stackIndex] = v;
            return;
        }
        if (slot != null) {
            slot.set(v);
            return;
        }
        this.closedValue = v;
    }
}

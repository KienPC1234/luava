package org.luava.runtime.eval;

import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaValue;
import org.luava.runtime.LuaState;
import org.luava.runtime.bytecode.BytecodeVM;

public final class Upvalue {
    private final String name;
    private Environment.VariableSlot slot;

    // Bytecode VM execution binding
    private LuaValue[] stack;
    private LuaState state;
    private int stackIndex = -1;
    private boolean isOpenOnStack = false;

    // Unboxed storage for closed primitive upvalues (Zero-Allocation on close)
    private long rawValue;
    private byte typeTag = BytecodeVM.TYPE_NIL;
    private LuaValue objectValue;
    private LuaValue closedValue = null;

    // Intrusive singly-linked list pointer (managed by LuaState in descending order of stackIndex)
    public Upvalue nextOpen;

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

    public Upvalue(String name, LuaState state, int stackIndex) {
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

    public long getRawValue() {
        return rawValue;
    }

    public byte getTypeTag() {
        return typeTag;
    }

    public LuaValue getObjectValue() {
        return objectValue;
    }

    public void close() {
        if (isOpenOnStack && state != null && stackIndex >= 0) {
            this.typeTag = state.getTypeStack()[stackIndex];
            this.rawValue = state.getPrimitiveStack()[stackIndex];
            this.objectValue = state.getObjectStack()[stackIndex];
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
        if (typeTag != BytecodeVM.TYPE_NIL || objectValue != null) {
            return BytecodeVM.getLuaValueFromRaw(rawValue, typeTag, objectValue);
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
        this.objectValue = v;
        this.typeTag = BytecodeVM.TYPE_OBJECT;
    }
}

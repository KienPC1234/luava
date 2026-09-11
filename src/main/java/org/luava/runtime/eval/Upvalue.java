/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
    private org.luava.runtime.concurrency.LuaCoroutine thread;
    private int stackIndex = -1;
    private boolean isOpenOnStack = false;

    // Unboxed storage for closed primitive upvalues (Zero-Allocation on close)
    private long rawValue;
    private byte typeTag = BytecodeVM.TYPE_NIL;
    private LuaValue objectValue;
    private LuaValue closedValue = null;

    // Unique identity for debug.upvalueid
    private Object id;

    // Join delegate (debug.upvaluejoin): if non-null, this upvalue is an alias
    // that shares live storage with the target (C: f1.upvals[n1] = f2.upvals[n2]).
    // The alias keeps its own name (for AST name resolution) and id is synced
    // to the target's id at join time.
    private Upvalue joinDelegate;

    /**
     * Create an alias upvalue for {@code debug.upvaluejoin}: shares live storage
     * with {@code target} (reads/writes delegate to it) but keeps {@code name}
     * and syncs identity to target. The caller must REPLACE (not mutate) the
     * original upvalue in the function's list, as the original may be shared
     * with other closures (same variable = same upvalue object).
     */
    public static Upvalue joinedAlias(String name, Upvalue target) {
        // Flatten chains (join of join follows C pointer assignment).
        Upvalue root = target;
        while (root.joinDelegate != null) {
            root = root.joinDelegate;
        }
        Upvalue alias = new Upvalue(name, LuaNil.NIL);
        alias.joinDelegate = root;
        alias.id = root.getId();
        // Share slot for AST path (AST reads/writes via slot directly).
        // VM path uses getValue/setValue which delegate above.
        alias.slot = root.slot;
        return alias;
    }

    // Intrusive singly-linked list pointer (managed by LuaState in descending order of stackIndex)
    public Upvalue nextOpen;

    public Object getId() {
        if (id == null) {
            id = (slot != null) ? slot : new Object();
        }
        return id;
    }

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
        this(name, state != null ? state.getCurrentThread() : null, stackIndex);
        this.state = state;
    }

    public Upvalue(String name, org.luava.runtime.concurrency.LuaCoroutine thread, int stackIndex) {
        this.name = name != null ? name : "?";
        this.thread = thread;
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
        if (isOpenOnStack && thread != null && stackIndex >= 0) {
            this.typeTag = thread.getTypeStack()[stackIndex];
            this.rawValue = thread.getPrimitiveStack()[stackIndex];
            this.objectValue = thread.getObjectStack()[stackIndex];
            this.thread = null;
            this.state = null;
            this.isOpenOnStack = false;
        } else if (isOpenOnStack && state != null && stackIndex >= 0) {
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
        if (joinDelegate != null) {
            return joinDelegate.getValue();
        }
        if (isOpenOnStack && thread != null && stackIndex >= 0) {
            return BytecodeVM.getLuaValue(thread.getPrimitiveStack(), thread.getTypeStack(), thread.getObjectStack(), stackIndex);
        }
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
        if (joinDelegate != null) {
            joinDelegate.setValue(val);
            return;
        }
        LuaValue v = val != null ? val : LuaNil.NIL;
        if (isOpenOnStack && thread != null && stackIndex >= 0) {
            BytecodeVM.setLuaValue(thread.getPrimitiveStack(), thread.getTypeStack(), thread.getObjectStack(), stackIndex, v);
            return;
        }
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

    /**
     * Make this upvalue share the live storage of {@code other} while keeping
     * its own name. This mirrors Lua C's lua_upvaluejoin, where both upvalues
     * point to the same UpVal*. The name must be preserved because the AST
     * interpreter resolves upvalues by name during closure invocation.
     */
    public void joinWith(Upvalue other) {
        if (other == null) return;
        this.id = other.getId();
        if (other.slot != null) {
            this.slot = other.slot;
            this.stack = null;
            this.state = null;
            this.thread = null;
            this.stackIndex = -1;
            this.isOpenOnStack = false;
            this.rawValue = 0;
            this.typeTag = BytecodeVM.TYPE_NIL;
            this.objectValue = null;
            this.closedValue = null;
        } else if (other.isOpenOnStack) {
            this.slot = null;
            this.stack = other.stack;
            this.state = other.state;
            this.thread = other.thread;
            this.stackIndex = other.stackIndex;
            this.isOpenOnStack = true;
            this.rawValue = 0;
            this.typeTag = BytecodeVM.TYPE_NIL;
            this.objectValue = null;
            this.closedValue = null;
        } else if (other.typeTag != BytecodeVM.TYPE_NIL || other.objectValue != null) {
            this.slot = null;
            this.stack = null;
            this.state = null;
            this.thread = null;
            this.stackIndex = -1;
            this.isOpenOnStack = false;
            this.rawValue = other.rawValue;
            this.typeTag = other.typeTag;
            this.objectValue = other.objectValue;
            this.closedValue = null;
        } else {
            this.slot = null;
            this.stack = null;
            this.state = null;
            this.thread = null;
            this.stackIndex = -1;
            this.isOpenOnStack = false;
            this.rawValue = 0;
            this.typeTag = BytecodeVM.TYPE_NIL;
            this.objectValue = null;
            this.closedValue = other.closedValue;
        }
    }
}

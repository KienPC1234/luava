package org.luava.runtime.eval;

import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaValue;

public final class Upvalue {
    private final String name;
    private Environment.VariableSlot slot;

    public Upvalue(String name, Environment.VariableSlot slot) {
        this.name = name;
        this.slot = slot != null ? slot : new Environment.VariableSlot(LuaNil.NIL, false, false);
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

    public LuaValue getValue() {
        return slot != null ? slot.get() : LuaNil.NIL;
    }

    public void setValue(LuaValue val) {
        if (slot != null) {
            slot.set(val);
        }
    }
}

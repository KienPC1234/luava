package org.luava.runtime.eval;

import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Environment {
    public static final class VariableSlot {
        private LuaValue value;
        private final boolean isClose;
        private final boolean isConst;

        public VariableSlot(LuaValue value, boolean isClose, boolean isConst) {
            this.value = value != null ? value : LuaNil.NIL;
            this.isClose = isClose;
            this.isConst = isConst;
        }

        public LuaValue get() {
            return value;
        }

        public void set(LuaValue val) {
            this.value = val != null ? val : LuaNil.NIL;
        }

        public boolean isClose() {
            return isClose;
        }

        public boolean isConst() {
            return isConst;
        }
    }

    private final Environment parent;
    private final Map<String, VariableSlot> locals = new HashMap<>();
    private final List<VariableSlot> toCloseSlots = new ArrayList<>();
    private LuaTable globals;

    public Environment(Environment parent, LuaTable globals) {
        this.parent = parent;
        this.globals = globals != null ? globals : (parent != null ? parent.globals : new LuaTable());
    }

    public LuaTable getGlobals() {
        return globals;
    }

    public void setGlobals(LuaTable globals) {
        this.globals = globals;
    }

    public void defineLocal(String name, LuaValue value, boolean isClose, boolean isConst) {
        VariableSlot slot = new VariableSlot(value, isClose, isConst);
        locals.put(name, slot);
        if (isClose) {
            toCloseSlots.add(slot);
        }
    }

    public LuaValue get(String name) {
        VariableSlot slot = locals.get(name);
        if (slot != null) {
            return slot.get();
        }
        if (parent != null) {
            return parent.get(name);
        }
        return globals.get(LuaString.valueOf(name));
    }

    public void set(String name, LuaValue value) {
        VariableSlot slot = findSlot(name);
        if (slot != null) {
            slot.set(value);
            return;
        }
        globals.set(LuaString.valueOf(name), value);
    }

    private VariableSlot findSlot(String name) {
        VariableSlot slot = locals.get(name);
        if (slot != null) {
            return slot;
        }
        if (parent != null) {
            return parent.findSlot(name);
        }
        return null;
    }

    public void closeToCloseVariables() {
        // Lua 5.4: Close in reverse order of declaration
        for (int i = toCloseSlots.size() - 1; i >= 0; i--) {
            VariableSlot slot = toCloseSlots.get(i);
            LuaValue val = slot.get();
            if (val != null && !val.isNil()) {
                LuaTable mt = val.getMetatable();
                if (mt != null) {
                    LuaValue closeHandler = mt.rawget(LuaString.valueOf("__close"));
                    if (!closeHandler.isNil()) {
                        closeHandler.call(val);
                    }
                }
            }
        }
        toCloseSlots.clear();
    }
}

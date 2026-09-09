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
            if (isConst) {
                throw new org.luava.runtime.LuaException("attempt to assign to const variable");
            }
            this.value = val != null ? val : LuaNil.NIL;
        }

        public boolean isClose() {
            return isClose;
        }

        public boolean isConst() {
            return isConst;
        }
    }

    public static final class NamedSlot {
        public final String name;
        public final VariableSlot slot;

        public NamedSlot(String name, VariableSlot slot) {
            this.name = name;
            this.slot = slot;
        }
    }

    private final Environment parent;
    private final Map<String, VariableSlot> locals = new HashMap<>();
    private final List<NamedSlot> orderedLocals = new ArrayList<>();
    private LuaValue[] varargsArray = null;
    private List<VariableSlot> toCloseSlots = null;
    private LuaTable globals;
    private final boolean isFunctionBoundary;

    public void setVarargsArray(LuaValue[] varargsArray) {
        this.varargsArray = varargsArray;
    }

    public LuaValue[] getVarargsArray() {
        if (varargsArray != null) return varargsArray;
        if (parent != null && !isFunctionBoundary) return parent.getVarargsArray();
        return null;
    }

    public List<NamedSlot> collectVisibleLocals() {
        List<Environment> chain = new ArrayList<>();
        Environment cur = this;
        while (cur != null) {
            chain.add(cur);
            if (cur.isFunctionBoundary) break;
            cur = cur.parent;
        }
        List<NamedSlot> result = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            result.addAll(chain.get(i).orderedLocals);
        }
        return result;
    }

    public Environment(Environment parent, LuaTable globals) {
        this(parent, globals, false);
    }

    public Environment(Environment parent, LuaTable globals, boolean isFunctionBoundary) {
        this.parent = parent;
        this.globals = globals != null ? globals : (parent != null ? parent.globals : new LuaTable());
        this.isFunctionBoundary = isFunctionBoundary;
    }

    public boolean isFunctionBoundary() {
        return isFunctionBoundary;
    }

    public LuaTable getGlobals() {
        return globals;
    }

    public void setGlobals(LuaTable globals) {
        this.globals = globals;
    }

    public boolean hasAnyLocalSlot() {
        if (!locals.isEmpty()) return true;
        if (parent != null) return parent.hasAnyLocalSlot();
        return false;
    }

    public boolean hasToCloseInFunction() {
        Environment cur = this;
        while (cur != null) {
            if (cur.toCloseSlots != null && !cur.toCloseSlots.isEmpty()) {
                return true;
            }
            if (cur.isFunctionBoundary) {
                break;
            }
            cur = cur.parent;
        }
        return false;
    }

    public void defineLocal(String name, LuaValue value, boolean isClose, boolean isConst) {
        if (isClose && value != null && !value.isNil() && !value.equals(org.luava.runtime.LuaBoolean.FALSE)) {
            LuaTable mt = value.getMetatable();
            if (mt == null || mt.rawget(LuaString.valueOf("__close")).isNil()) {
                throw new org.luava.runtime.LuaException("variable '" + name + "' got a non-closable value");
            }
        }
        VariableSlot slot = new VariableSlot(value, isClose, isConst);
        locals.put(name, slot);
        if (!"...".equals(name)) {
            orderedLocals.add(new NamedSlot(name, slot));
        }
        if (isClose) {
            if (toCloseSlots == null) {
                toCloseSlots = new ArrayList<>(2);
            }
            toCloseSlots.add(slot);
        }
    }

    public LuaValue get(String name) {
        VariableSlot slot = findSlot(name);
        if (slot != null) {
            return slot.get();
        }
        if ("_ENV".equals(name)) {
            return globals;
        }
        LuaValue envVal = get("_ENV");
        if (envVal != null && !envVal.isNil()) {
            return envVal.get(LuaString.valueOf(name));
        }
        return globals.get(LuaString.valueOf(name));
    }

    public void set(String name, LuaValue value) {
        VariableSlot slot = findSlot(name);
        if (slot != null) {
            slot.set(value);
            return;
        }
        if ("_ENV".equals(name)) {
            if (value instanceof LuaTable t) {
                globals = t;
            }
            return;
        }
        LuaValue envVal = get("_ENV");
        if (envVal != null && !envVal.isNil()) {
            envVal.set(LuaString.valueOf(name), value);
            return;
        }
        globals.set(LuaString.valueOf(name), value);
    }

    public Environment getParent() {
        return parent;
    }

    public java.util.Collection<VariableSlot> getSlots() {
        return locals.values();
    }

    public void defineSlot(String name, VariableSlot slot) {
        locals.put(name, slot);
    }

    public VariableSlot findSlot(String name) {
        VariableSlot slot = locals.get(name);
        if (slot != null) {
            return slot;
        }
        if (parent != null) {
            return parent.findSlot(name);
        }
        return null;
    }

    public boolean isUpvalue(String name) {
        // If defined in the current function scope (before hitting any function boundary), it's a local.
        // If defined in an outer function scope (across a function boundary), it's an upvalue.
        Environment cur = this;
        while (cur != null) {
            if (cur.locals.containsKey(name)) {
                return false;
            }
            if (cur.isFunctionBoundary) {
                // If it wasn't found in current function, search outer scopes
                Environment outer = cur.parent;
                while (outer != null) {
                    if (outer.locals.containsKey(name)) {
                        return true;
                    }
                    outer = outer.parent;
                }
                return false;
            }
            cur = cur.parent;
        }
        return false;
    }

    public void closeToCloseVariables() {
        closeToCloseVariables(LuaNil.NIL);
    }

    public void closeToCloseVariables(LuaValue errorObj) {
        if (toCloseSlots == null || toCloseSlots.isEmpty()) {
            return;
        }
        LuaValue err = (errorObj != null) ? errorObj : LuaNil.NIL;
        Throwable lastError = null;
        // Lua 5.4: Close in reverse order of declaration
        for (int i = toCloseSlots.size() - 1; i >= 0; i--) {
            VariableSlot slot = toCloseSlots.get(i);
            LuaValue val = slot.get();
            if (val != null && !val.isNil() && !val.equals(org.luava.runtime.LuaBoolean.FALSE)) {
                LuaTable mt = val.getMetatable();
                LuaValue closeHandler = (mt != null) ? mt.rawget(LuaString.valueOf("__close")) : LuaNil.NIL;
                try {
                    if (closeHandler.isNil()) {
                        throw new org.luava.runtime.LuaException("attempt to call a nil value (metamethod 'close')");
                    }
                    org.luava.runtime.eval.CallStack.setNextCall("close", "metamethod", false, true);
                    closeHandler.call(val, err);
                } catch (org.luava.runtime.eval.LuaUnwindException ue) {
                    err = ue.getResult();
                    lastError = ue;
                } catch (org.luava.runtime.LuaException le) {
                    err = le.getErrorObject();
                    lastError = le;
                } catch (Throwable t) {
                    lastError = t;
                    err = LuaString.valueOf(t.getMessage() != null ? t.getMessage() : t.toString());
                }
            }
        }
        toCloseSlots.clear();
        if (lastError != null) {
            if (lastError instanceof org.luava.runtime.eval.LuaUnwindException ue) throw ue;
            if (lastError instanceof RuntimeException re) throw re;
            if (lastError instanceof Error er) throw er;
            throw new org.luava.runtime.LuaException(lastError.getMessage());
        }
    }
}

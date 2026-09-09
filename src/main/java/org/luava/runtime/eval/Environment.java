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
    private String name0, name1, name2, name3;
    private VariableSlot slot0, slot1, slot2, slot3;
    private int localCount = 0;
    private Map<String, VariableSlot> overflowLocals = null;
    private List<NamedSlot> overflowOrderedLocals = null;
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
            Environment env = chain.get(i);
            if (env.localCount > 0) {
                if (!"...".equals(env.name0)) result.add(new NamedSlot(env.name0, env.slot0));
                if (env.localCount > 1 && !"...".equals(env.name1)) result.add(new NamedSlot(env.name1, env.slot1));
                if (env.localCount > 2 && !"...".equals(env.name2)) result.add(new NamedSlot(env.name2, env.slot2));
                if (env.localCount > 3 && !"...".equals(env.name3)) result.add(new NamedSlot(env.name3, env.slot3));
            }
            if (env.overflowOrderedLocals != null) {
                result.addAll(env.overflowOrderedLocals);
            }
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
        if (localCount > 0) return true;
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
        defineSlot(name, slot);
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

    public void forEachSlot(java.util.function.Consumer<VariableSlot> action) {
        if (localCount > 0) {
            action.accept(slot0);
            if (localCount > 1) {
                action.accept(slot1);
                if (localCount > 2) {
                    action.accept(slot2);
                    if (localCount > 3) {
                        action.accept(slot3);
                    }
                }
            }
        }
        if (overflowLocals != null) {
            overflowLocals.values().forEach(action);
        }
    }

    public java.util.Collection<VariableSlot> getSlots() {
        if (overflowLocals != null) {
            List<VariableSlot> list = new ArrayList<>(localCount + overflowLocals.size());
            if (localCount > 0) list.add(slot0);
            if (localCount > 1) list.add(slot1);
            if (localCount > 2) list.add(slot2);
            if (localCount > 3) list.add(slot3);
            list.addAll(overflowLocals.values());
            return list;
        }
        if (localCount == 0) return java.util.Collections.emptyList();
        VariableSlot[] arr = new VariableSlot[localCount];
        if (localCount > 0) arr[0] = slot0;
        if (localCount > 1) arr[1] = slot1;
        if (localCount > 2) arr[2] = slot2;
        if (localCount > 3) arr[3] = slot3;
        return java.util.Arrays.asList(arr);
    }

    public void defineSlot(String name, VariableSlot slot) {
        if (localCount < 4) {
            if (localCount == 0) {
                name0 = name; slot0 = slot; localCount = 1;
            } else if (localCount == 1) {
                name1 = name; slot1 = slot; localCount = 2;
            } else if (localCount == 2) {
                name2 = name; slot2 = slot; localCount = 3;
            } else {
                name3 = name; slot3 = slot; localCount = 4;
            }
        } else {
            if (overflowLocals == null) {
                overflowLocals = new HashMap<>(4);
                overflowOrderedLocals = new ArrayList<>(4);
            }
            overflowLocals.put(name, slot);
            if (!"...".equals(name)) {
                overflowOrderedLocals.add(new NamedSlot(name, slot));
            }
        }
    }

    public VariableSlot findSlot(String name) {
        if (overflowLocals != null) {
            VariableSlot s = overflowLocals.get(name);
            if (s != null) return s;
        }
        if (localCount > 0) {
            if (localCount == 4 && name.equals(name3)) return slot3;
            if (localCount >= 3 && name.equals(name2)) return slot2;
            if (localCount >= 2 && name.equals(name1)) return slot1;
            if (localCount >= 1 && name.equals(name0)) return slot0;
        }
        if (parent != null) {
            return parent.findSlot(name);
        }
        return null;
    }

    public boolean hasLocal(String name) {
        if (overflowLocals != null && overflowLocals.containsKey(name)) return true;
        if (localCount > 0) {
            if (name.equals(name0)) return true;
            if (localCount > 1 && name.equals(name1)) return true;
            if (localCount > 2 && name.equals(name2)) return true;
            if (localCount > 3 && name.equals(name3)) return true;
        }
        return false;
    }

    public boolean isUpvalue(String name) {
        // If defined in the current function scope (before hitting any function boundary), it's a local.
        // If defined in an outer function scope (across a function boundary), it's an upvalue.
        Environment cur = this;
        while (cur != null) {
            if (cur.hasLocal(name)) {
                return false;
            }
            if (cur.isFunctionBoundary) {
                // If it wasn't found in current function, search outer scopes
                Environment outer = cur.parent;
                while (outer != null) {
                    if (outer.hasLocal(name)) {
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

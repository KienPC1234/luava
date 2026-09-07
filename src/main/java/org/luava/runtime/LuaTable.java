package org.luava.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LuaTable extends LuaValue {
    private final Map<LuaValue, LuaValue> hashPart = new HashMap<>();
    private final ArrayList<LuaValue> arrayPart = new ArrayList<>();
    private LuaTable metatable;

    public LuaTable() {}

    public LuaTable(int arrayCapacity, int hashCapacity) {
        if (arrayCapacity > 0) {
            arrayPart.ensureCapacity(arrayCapacity);
        }
    }

    @Override
    public LuaType type() {
        return LuaType.TABLE;
    }

    @Override
    public boolean isTable() {
        return true;
    }

    @Override
    public LuaTable getMetatable() {
        return metatable;
    }

    @Override
    public void setMetatable(LuaTable mt) {
        this.metatable = mt;
    }

    public LuaValue rawget(LuaValue key) {
        if (key == null || key.isNil()) {
            return LuaNil.NIL;
        }
        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx >= 1 && idx <= arrayPart.size()) {
                LuaValue val = arrayPart.get((int) (idx - 1));
                return val != null ? val : LuaNil.NIL;
            }
        }
        LuaValue val = hashPart.get(key);
        return val != null ? val : LuaNil.NIL;
    }

    public void rawset(LuaValue key, LuaValue value) {
        if (key == null || key.isNil()) {
            throw new LuaException("table index is nil");
        }
        if (key.isFloat() && Double.isNaN(key.toDouble())) {
            throw new LuaException("table index is NaN");
        }
        LuaValue toSet = (value == null || value.isNil()) ? LuaNil.NIL : value;

        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx == arrayPart.size() + 1 && !toSet.isNil()) {
                arrayPart.add(toSet);
                return;
            } else if (idx >= 1 && idx <= arrayPart.size()) {
                if (toSet.isNil() && idx == arrayPart.size()) {
                    arrayPart.remove(arrayPart.size() - 1);
                    // Trim trailing nils
                    while (!arrayPart.isEmpty() && arrayPart.get(arrayPart.size() - 1).isNil()) {
                        arrayPart.remove(arrayPart.size() - 1);
                    }
                } else {
                    arrayPart.set((int) (idx - 1), toSet);
                }
                return;
            }
        }

        if (toSet.isNil()) {
            hashPart.remove(key);
        } else {
            hashPart.put(key, toSet);
        }
    }

    @Override
    public LuaValue get(LuaValue key) {
        LuaValue val = rawget(key);
        if (!val.isNil()) {
            return val;
        }
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__index"));
            if (!handler.isNil()) {
                if (handler.isFunction()) {
                    return handler.call(this, key);
                } else {
                    return handler.get(key);
                }
            }
        }
        return LuaNil.NIL;
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        LuaValue existing = rawget(key);
        if (!existing.isNil() || metatable == null) {
            rawset(key, value);
            return;
        }
        LuaValue handler = metatable.rawget(LuaString.valueOf("__newindex"));
        if (!handler.isNil()) {
            if (handler.isFunction()) {
                handler.call(this, key, value);
                return;
            } else {
                handler.set(key, value);
                return;
            }
        }
        rawset(key, value);
    }

    @Override
    public LuaValue len() {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__len"));
            if (!handler.isNil()) {
                return handler.call(this);
            }
        }
        return LuaInteger.valueOf(rawlen());
    }

    public int rawlen() {
        // Find boundary according to Lua 5.4 definition
        int n = arrayPart.size();
        while (n > 0 && arrayPart.get(n - 1).isNil()) {
            n--;
        }
        if (n > 0) {
            return n;
        }
        // If array part is empty, check consecutive integer keys in hash part
        int i = 1;
        while (!rawget(LuaInteger.valueOf(i)).isNil()) {
            i++;
        }
        return i - 1;
    }

    public List<LuaValue> keys() {
        List<LuaValue> list = new ArrayList<>();
        for (int i = 0; i < arrayPart.size(); i++) {
            LuaValue v = arrayPart.get(i);
            if (v != null && !v.isNil()) {
                list.add(LuaInteger.valueOf(i + 1));
            }
        }
        list.addAll(hashPart.keySet());
        return list;
    }

    @Override
    public String toLuaString() {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__tostring"));
            if (!handler.isNil()) {
                return handler.call(this).toLuaString();
            }
        }
        return "table: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

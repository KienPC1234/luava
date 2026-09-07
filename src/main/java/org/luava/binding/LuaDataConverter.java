package org.luava.binding;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LuaDataConverter {
    private LuaDataConverter() {}

    public static LuaValue toLua(Object obj) {
        if (obj == null) return LuaNil.NIL;
        if (obj instanceof LuaValue lv) return lv;
        if (obj instanceof Boolean b) return LuaBoolean.valueOf(b);
        if (obj instanceof Integer i) return LuaInteger.valueOf(i);
        if (obj instanceof Long l) return LuaInteger.valueOf(l);
        if (obj instanceof Short s) return LuaInteger.valueOf(s);
        if (obj instanceof Byte b) return LuaInteger.valueOf(b);
        if (obj instanceof Float f) return LuaFloat.valueOf(f.doubleValue());
        if (obj instanceof Double d) return LuaFloat.valueOf(d);
        if (obj instanceof CharSequence s) return LuaString.valueOf(s.toString());

        if (obj instanceof List<?> list) {
            LuaTable table = new LuaTable();
            for (int i = 0; i < list.size(); i++) {
                table.rawset(LuaInteger.valueOf(i + 1), toLua(list.get(i)));
            }
            return table;
        }

        if (obj instanceof Map<?, ?> map) {
            LuaTable table = new LuaTable();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                table.rawset(toLua(entry.getKey()), toLua(entry.getValue()));
            }
            return table;
        }

        if (obj.getClass().isArray()) {
            LuaTable table = new LuaTable();
            int length = Array.getLength(obj);
            for (int i = 0; i < length; i++) {
                table.rawset(LuaInteger.valueOf(i + 1), toLua(Array.get(obj, i)));
            }
            return table;
        }

        return new LuaUserdata(obj);
    }

    @SuppressWarnings("unchecked")
    public static <T> T toJava(LuaValue val, Class<T> targetType) {
        if (val == null || val.isNil()) {
            if (targetType.isPrimitive()) {
                if (targetType == boolean.class) return (T) Boolean.FALSE;
                if (targetType == int.class) return (T) Integer.valueOf(0);
                if (targetType == long.class) return (T) Long.valueOf(0L);
                if (targetType == double.class) return (T) Double.valueOf(0.0);
                if (targetType == float.class) return (T) Float.valueOf(0.0f);
            }
            return null;
        }

        if (targetType != Object.class && targetType.isAssignableFrom(val.getClass())) {
            return (T) val;
        }

        if (targetType == String.class) {
            return (T) val.toLuaString();
        }

        if (targetType == boolean.class || targetType == Boolean.class) {
            return (T) Boolean.valueOf(val.toBoolean());
        }

        if (targetType == int.class || targetType == Integer.class) {
            return (T) Integer.valueOf((int) val.toLong());
        }

        if (targetType == long.class || targetType == Long.class) {
            return (T) Long.valueOf(val.toLong());
        }

        if (targetType == double.class || targetType == Double.class) {
            return (T) Double.valueOf(val.toDouble());
        }

        if (targetType == float.class || targetType == Float.class) {
            return (T) Float.valueOf((float) val.toDouble());
        }

        if (targetType == List.class && val.isTable()) {
            LuaTable table = (LuaTable) val;
            int len = table.rawlen();
            List<Object> list = new ArrayList<>(len);
            for (int i = 1; i <= len; i++) {
                list.add(toJava(table.rawget(LuaInteger.valueOf(i)), Object.class));
            }
            return (T) list;
        }

        if (targetType == Map.class && val.isTable()) {
            LuaTable table = (LuaTable) val;
            Map<Object, Object> map = new HashMap<>();
            for (LuaValue k : table.keys()) {
                map.put(toJava(k, Object.class), toJava(table.rawget(k), Object.class));
            }
            return (T) map;
        }

        if (val.isUserdata()) {
            Object instance = ((LuaUserdata) val).getJavaInstance();
            if (targetType.isInstance(instance)) {
                return (T) instance;
            }
        }

        if (targetType == Object.class) {
            if (val.isBoolean()) return (T) Boolean.valueOf(val.toBoolean());
            if (val.isInteger()) return (T) Long.valueOf(val.toLong());
            if (val.isFloat()) return (T) Double.valueOf(val.toDouble());
            if (val.isString()) return (T) val.toLuaString();
            if (val.isTable()) return (T) toJava(val, Map.class);
            if (val.isUserdata()) return (T) ((LuaUserdata) val).getJavaInstance();
            return (T) val;
        }

        throw new LuaException("Cannot convert Lua value of type " + val.typeName() + " to Java type " + targetType.getName());
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
        if (obj instanceof Character c) return LuaString.valueOf(String.valueOf(c));
        // Only immutable String converts to a Lua string. Mutable
        // CharSequences (StringBuilder, buffers, ...) stay live userdata so
        // object identity and chaining survive the bridge.
        if (obj instanceof String s) return LuaString.valueOf(s);

        if (obj instanceof Enum<?> e) {
            return LuaString.valueOf(e.name());
        }

        if (obj instanceof Optional<?> opt) {
            return opt.map(LuaDataConverter::toLua).orElse(LuaNil.NIL);
        }

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

        if (obj instanceof Set<?> set) {
            LuaTable table = new LuaTable();
            int i = 1;
            for (Object e : set) {
                table.rawset(LuaInteger.valueOf(i++), toLua(e));
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

    public static LuaValue toLuaLive(Object obj) {
        if (obj == null) return LuaNil.NIL;
        if (obj instanceof LuaValue lv) return lv;
        if (obj instanceof Boolean b) return LuaBoolean.valueOf(b);
        if (obj instanceof Integer i) return LuaInteger.valueOf(i);
        if (obj instanceof Long l) return LuaInteger.valueOf(l);
        if (obj instanceof Short s) return LuaInteger.valueOf(s);
        if (obj instanceof Byte b) return LuaInteger.valueOf(b);
        if (obj instanceof Float f) return LuaFloat.valueOf(f.doubleValue());
        if (obj instanceof Double d) return LuaFloat.valueOf(d);
        if (obj instanceof Character c) return LuaString.valueOf(String.valueOf(c));
        if (obj instanceof String s) return LuaString.valueOf(s);
        if (obj instanceof Enum<?> e) return LuaString.valueOf(e.name());

        // Collections, Arrays, and arbitrary Java objects wrapped as live LuaUserdata
        return wrapLive(obj);
    }

    /**
     * Wraps a fresh Java object as live userdata, attaching the shared
     * length metatable for sized types. Use at construction/call sites that
     * produce live objects (as opposed to {@link #toLua}, which snapshots
     * collections into tables).
     */
    public static LuaUserdata wrapLive(Object obj) {
        LuaUserdata ud = new LuaUserdata(obj);
        if (obj instanceof List<?> || obj instanceof Map<?, ?> || obj instanceof Set<?> || obj.getClass().isArray()) {
            ud.setMetatable(liveCollectionMetatable());
        }
        return ud;
    }

    private static volatile LuaTable liveCollectionMetatable;

    /**
     * Shared metatable giving live Java collections/arrays the Lua length
     * operator ({@code #}). Host code may replace it per-object via
     * {@code setmetatable}; this default only applies at wrap time.
     */
    private static LuaTable liveCollectionMetatable() {
        LuaTable mt = liveCollectionMetatable;
        if (mt == null) {
            synchronized (LuaDataConverter.class) {
                mt = liveCollectionMetatable;
                if (mt == null) {
                    mt = new LuaTable();
                    mt.rawset(LuaString.valueOf("__len"), LuaFunction.of(args -> {
                        Object inst = (args.length > 0 && args[0].isUserdata())
                                ? ((LuaUserdata) args[0]).getJavaInstance() : null;
                        if (inst instanceof List<?> l) return LuaInteger.valueOf(l.size());
                        if (inst instanceof Map<?, ?> m) return LuaInteger.valueOf(m.size());
                        if (inst instanceof Set<?> s) return LuaInteger.valueOf(s.size());
                        if (inst != null && inst.getClass().isArray()) {
                            return LuaInteger.valueOf(Array.getLength(inst));
                        }
                        throw new LuaException("attempt to get length of a non-sized userdata value");
                    }));
                    liveCollectionMetatable = mt;
                }
            }
        }
        return mt;
    }

    @SuppressWarnings("unchecked")
    public static <T> T toJava(LuaValue val, Class<T> targetType) {
        if (val == null || val.isNil()) {
            if (targetType.isPrimitive()) {
                if (targetType == boolean.class) return (T) Boolean.FALSE;
                if (targetType == byte.class) return (T) Byte.valueOf((byte) 0);
                if (targetType == short.class) return (T) Short.valueOf((short) 0);
                if (targetType == int.class) return (T) Integer.valueOf(0);
                if (targetType == long.class) return (T) Long.valueOf(0L);
                if (targetType == double.class) return (T) Double.valueOf(0.0);
                if (targetType == float.class) return (T) Float.valueOf(0.0f);
                if (targetType == char.class) return (T) Character.valueOf('\0');
            }
            if (targetType == Optional.class) {
                return (T) Optional.empty();
            }
            return null;
        }

        if (targetType != Object.class && targetType.isAssignableFrom(val.getClass())) {
            return (T) val;
        }

        if (targetType == Optional.class) {
            return (T) Optional.ofNullable(toJava(val, Object.class));
        }

        // String / CharSequence
        if (targetType == String.class || targetType == CharSequence.class) {
            return (T) val.toLuaString();
        }

        // Boolean
        if (targetType == boolean.class || targetType == Boolean.class) {
            return (T) Boolean.valueOf(val.toBoolean());
        }

        // Character
        if (targetType == char.class || targetType == Character.class) {
            if (val.isString()) {
                String s = val.toLuaString();
                return (T) Character.valueOf(!s.isEmpty() ? s.charAt(0) : '\0');
            }
            if (val.isInteger()) {
                return (T) Character.valueOf((char) val.toLong());
            }
        }

        // Integers and primitive number coercion
        if (targetType == byte.class || targetType == Byte.class) {
            long l = val.isInteger() ? val.toLong() : (val.isFloat() ? (long) val.toDouble() : 0);
            return (T) Byte.valueOf((byte) l);
        }

        if (targetType == short.class || targetType == Short.class) {
            long l = val.isInteger() ? val.toLong() : (val.isFloat() ? (long) val.toDouble() : 0);
            return (T) Short.valueOf((short) l);
        }

        if (targetType == int.class || targetType == Integer.class) {
            long l = val.isInteger() ? val.toLong() : (val.isFloat() ? (long) val.toDouble() : 0);
            return (T) Integer.valueOf((int) l);
        }

        if (targetType == long.class || targetType == Long.class) {
            long l = val.isInteger() ? val.toLong() : (val.isFloat() ? (long) val.toDouble() : 0);
            return (T) Long.valueOf(l);
        }

        // Floating point numbers
        if (targetType == float.class || targetType == Float.class) {
            double d = val.isFloat() ? val.toDouble() : (val.isInteger() ? (double) val.toLong() : 0.0);
            return (T) Float.valueOf((float) d);
        }

        if (targetType == double.class || targetType == Double.class) {
            double d = val.isFloat() ? val.toDouble() : (val.isInteger() ? (double) val.toLong() : 0.0);
            return (T) Double.valueOf(d);
        }

        // Enums
        if (targetType.isEnum()) {
            if (val.isString()) {
                return (T) Enum.valueOf((Class<Enum>) targetType, val.toLuaString());
            }
            if (val.isInteger()) {
                Object[] constants = targetType.getEnumConstants();
                int ordinal = (int) val.toLong();
                if (ordinal >= 0 && ordinal < constants.length) {
                    return (T) constants[ordinal];
                }
            }
        }

        // Userdata unwrap
        if (val.isUserdata()) {
            Object instance = ((LuaUserdata) val).getJavaInstance();
            if (instance != null && targetType.isInstance(instance)) {
                return (T) instance;
            }
        }

        // SAM / FunctionalInterface adaptation
        if (val.isFunction() && targetType.isInterface()) {
            Method sam = findSingleAbstractMethod(targetType);
            if (sam != null) {
                return createSamProxy(targetType, (LuaFunction) val, sam);
            }
        }

        // Arrays
        if (targetType.isArray()) {
            Class<?> compType = targetType.getComponentType();
            if (val.isUserdata()) {
                Object inst = ((LuaUserdata) val).getJavaInstance();
                if (inst != null && inst.getClass().isArray()) {
                    return (T) inst;
                }
            }
            if (val.isTable()) {
                LuaTable table = (LuaTable) val;
                int len = table.rawlen();
                Object array = Array.newInstance(compType, len);
                for (int i = 1; i <= len; i++) {
                    Array.set(array, i - 1, toJava(table.rawget(LuaInteger.valueOf(i)), compType));
                }
                return (T) array;
            }
        }

        // List
        if (targetType == List.class) {
            if (val.isUserdata()) {
                Object inst = ((LuaUserdata) val).getJavaInstance();
                if (inst instanceof List<?> list) return (T) list;
            }
            if (val.isTable()) {
                LuaTable table = (LuaTable) val;
                int len = table.rawlen();
                List<Object> list = new ArrayList<>(len);
                for (int i = 1; i <= len; i++) {
                    list.add(toJava(table.rawget(LuaInteger.valueOf(i)), Object.class));
                }
                return (T) list;
            }
        }

        // Set
        if (targetType == Set.class) {
            if (val.isUserdata()) {
                Object inst = ((LuaUserdata) val).getJavaInstance();
                if (inst instanceof Set<?> set) return (T) set;
            }
            if (val.isTable()) {
                LuaTable table = (LuaTable) val;
                int len = table.rawlen();
                Set<Object> set = new HashSet<>(len);
                for (int i = 1; i <= len; i++) {
                    set.add(toJava(table.rawget(LuaInteger.valueOf(i)), Object.class));
                }
                return (T) set;
            }
        }

        // Map
        if (targetType == Map.class) {
            if (val.isUserdata()) {
                Object inst = ((LuaUserdata) val).getJavaInstance();
                if (inst instanceof Map<?, ?> map) return (T) map;
            }
            if (val.isTable()) {
                LuaTable table = (LuaTable) val;
                Map<Object, Object> map = new HashMap<>();
                for (LuaValue k : table.keys()) {
                    map.put(toJava(k, Object.class), toJava(table.rawget(k), Object.class));
                }
                return (T) map;
            }
        }

        // Fallback for Object.class
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

    public static Method findSingleAbstractMethod(Class<?> iface) {
        if (!iface.isInterface()) return null;
        Method candidate = null;
        for (Method m : iface.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                if (isObjectMethod(m)) continue;
                if (candidate != null) {
                    return null; // More than one abstract method
                }
                candidate = m;
            }
        }
        return candidate;
    }

    private static boolean isObjectMethod(Method m) {
        try {
            Object.class.getMethod(m.getName(), m.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T createSamProxy(Class<T> iface, LuaFunction fn, Method samMethod) {
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (proxy, method, args) -> {
            if (method.getName().equals("equals")) {
                return args != null && args.length == 1 && args[0] == proxy;
            }
            if (method.getName().equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (method.getName().equals("toString")) {
                return "LuaSAMProxy:" + iface.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
            }
            if (method.getName().equals(samMethod.getName())) {
                int count = (args != null ? args.length : 0);
                LuaValue[] luaArgs = new LuaValue[count];
                for (int i = 0; i < count; i++) {
                    luaArgs[i] = toLua(args[i]);
                }
                LuaValue res = fn.call(luaArgs);
                if (samMethod.getReturnType() == void.class || samMethod.getReturnType() == Void.class) {
                    return null;
                }
                return toJava(res, samMethod.getReturnType());
            }
            throw new UnsupportedOperationException("Method " + method.getName() + " is not supported on SAM proxy");
        });
    }
}

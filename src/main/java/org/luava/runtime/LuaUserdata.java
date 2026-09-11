/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.luava.binding.LuaDataConverter;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaUserdata extends LuaValue {
    private static final Map<Class<?>, List<Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, List<Constructor<?>>> CTOR_CACHE = new ConcurrentHashMap<>();

    private final Object instance;
    private final boolean isLight;
    private LuaTable metatable;
    private final LuaValue[] userValues;

    public LuaUserdata(Object instance) {
        this(instance, false, 1);
    }

    public LuaUserdata(Object instance, boolean isLight) {
        this(instance, isLight, isLight ? 0 : 1);
    }

    public LuaUserdata(Object instance, int nuvalue) {
        this(instance, false, nuvalue);
    }

    public LuaUserdata(Object instance, boolean isLight, int nuvalue) {
        this.instance = instance;
        this.isLight = isLight;
        if (isLight) {
            this.userValues = null;
        } else {
            this.userValues = new LuaValue[Math.max(0, nuvalue)];
            Arrays.fill(this.userValues, LuaNil.NIL);
        }
    }

    public int getNuvalue() {
        return userValues != null ? userValues.length : 0;
    }

    public LuaValue getUserValue(int n) {
        if (isLight || userValues == null || n <= 0 || n > userValues.length) {
            return null;
        }
        return userValues[n - 1];
    }

    public boolean setUserValue(int n, LuaValue val) {
        if (isLight || userValues == null || n <= 0 || n > userValues.length) {
            return false;
        }
        userValues[n - 1] = (val != null) ? val : LuaNil.NIL;
        return true;
    }

    public Object getJavaInstance() {
        return instance;
    }

    public Object getUserdata() {
        return instance;
    }

    @Override
    public LuaType type() {
        return LuaType.USERDATA;
    }

    @Override
    public boolean isUserdata() {
        return !isLight;
    }

    @Override
    public boolean isLightUserdata() {
        return isLight;
    }

    @Override
    public String typeName() {
        if (isLight) {
            return "light userdata";
        }
        return super.typeName();
    }

    @Override
    public LuaTable getMetatable() {
        return metatable;
    }

    @Override
    public void setMetatable(LuaTable mt) {
        this.metatable = mt;
    }

    @Override
    public LuaValue get(LuaValue key) {
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

        if (instance == null) {
            return LuaNil.NIL;
        }

        // 1. Array indexing
        if (instance.getClass().isArray() && key.isInteger()) {
            int idx = (int) key.toLong() - 1; // 1-based
            int len = Array.getLength(instance);
            if (idx >= 0 && idx < len) {
                return LuaDataConverter.toLua(Array.get(instance, idx));
            }
            return LuaNil.NIL;
        }

        // 2. List indexing
        if (instance instanceof List<?> list && key.isInteger()) {
            int idx = (int) key.toLong() - 1;
            if (idx >= 0 && idx < list.size()) {
                return LuaDataConverter.toLua(list.get(idx));
            }
            return LuaNil.NIL;
        }

        // 3. Map indexing
        if (instance instanceof Map<?, ?> map && !key.isNil()) {
            Object javaKey = LuaDataConverter.toJava(key, Object.class);
            if (map.containsKey(javaKey)) {
                return LuaDataConverter.toLua(map.get(javaKey));
            }
        }

        // 4. String member access (methods, fields, properties)
        if (key.isString()) {
            String name = key.toLuaString();

            // Static Class context
            if (instance instanceof Class<?> clazz) {
                org.luava.binding.JavaAccessPolicy.active().check(clazz.getName());
                if ("class".equals(name)) return this;
                if ("new".equals(name)) {
                    return createConstructorFunction(clazz);
                }

                // Static field
                Field f = findField(clazz, name, true);
                if (f != null) {
                    try {
                        return LuaDataConverter.toLua(f.get(null));
                    } catch (IllegalAccessException e) {
                        throw new LuaException("Access error for static field " + name + ": " + e.getMessage());
                    }
                }

                // Static methods
                List<Method> methods = findMethods(clazz, name, true);
                if (!methods.isEmpty()) {
                    return createMethodInvoker(null, name, methods);
                }
            } else {
                Class<?> clazz = instance.getClass();

                // Instance field
                Field f = findField(clazz, name, false);
                if (f != null) {
                    try {
                        return LuaDataConverter.toLua(f.get(instance));
                    } catch (IllegalAccessException e) {
                        throw new LuaException("Access error for field " + name + ": " + e.getMessage());
                    }
                }

                // Instance methods
                List<Method> methods = findMethods(clazz, name, false);
                if (!methods.isEmpty()) {
                    return createMethodInvoker(instance, name, methods);
                }

                // Built-in collection lengths (fallback if no method/field)
                if (instance.getClass().isArray() && "length".equals(name)) {
                    return LuaInteger.valueOf(Array.getLength(instance));
                }
                if (instance instanceof Collection<?> col && ("length".equals(name) || "size".equals(name))) {
                    return LuaInteger.valueOf(col.size());
                }
                if (instance instanceof Map<?, ?> map && "size".equals(name)) {
                    return LuaInteger.valueOf(map.size());
                }
            }
        }

        return LuaNil.NIL;
    }

    @Override
    public LuaValue call(LuaValue... args) {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__call"));
            if (!handler.isNil()) {
                LuaValue[] callArgs = new LuaValue[args.length + 1];
                callArgs[0] = this;
                System.arraycopy(args, 0, callArgs, 1, args.length);
                return handler.call(callArgs);
            }
        }

        if (instance != null) {
            // Check if instance implements a Single Abstract Method (SAM) interface
            Class<?> clazz = instance.getClass();
            Method sam = null;
            for (Class<?> iface : clazz.getInterfaces()) {
                Method ifaceSam = LuaDataConverter.findSingleAbstractMethod(iface);
                if (ifaceSam != null) {
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(ifaceSam.getName()) && !m.isBridge()) {
                            sam = m;
                            break;
                        }
                    }
                    if (sam == null) sam = ifaceSam;
                    break;
                }
            }
            if (sam == null) {
                sam = LuaDataConverter.findSingleAbstractMethod(clazz);
            }

            if (sam != null) {
                try {
                    sam.setAccessible(true);
                    Object[] javaArgs = convertArgs(sam.getParameterTypes(), sam.isVarArgs(), args);
                    Object res;
                    try {
                        res = sam.invoke(instance, javaArgs);
                    } catch (IllegalArgumentException iae) {
                        for (int i = 0; i < javaArgs.length; i++) {
                            if (javaArgs[i] instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                                javaArgs[i] = l.intValue();
                            }
                        }
                        res = sam.invoke(instance, javaArgs);
                    }
                    if (sam.getReturnType() == void.class || sam.getReturnType() == Void.class) {
                        return LuaNil.NIL;
                    }
                    return LuaDataConverter.toLua(res);
                } catch (Throwable t) {
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    throw new LuaException("Error invoking Java functional interface: " + cause.getMessage());
                }
            }
        }

        return super.call(args);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        if (metatable != null) {
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
        }

        if (instance == null) {
            throw new LuaException("Attempt to index a nil Java userdata");
        }

        // 1. Array element set
        if (instance.getClass().isArray() && key.isInteger()) {
            int idx = (int) key.toLong() - 1;
            int len = Array.getLength(instance);
            if (idx < 0 || idx >= len) {
                throw new LuaException("Array index out of bounds: " + (idx + 1) + " (length: " + len + ")");
            }
            Class<?> compType = instance.getClass().getComponentType();
            Array.set(instance, idx, LuaDataConverter.toJava(value, compType));
            return;
        }

        // 2. List element set
        if (instance instanceof List list && key.isInteger()) {
            int idx = (int) key.toLong() - 1;
            if (idx < 0 || idx >= list.size()) {
                throw new LuaException("List index out of bounds: " + (idx + 1) + " (size: " + list.size() + ")");
            }
            list.set(idx, LuaDataConverter.toJava(value, Object.class));
            return;
        }

        // 3. Map element set
        if (instance instanceof Map map && !key.isNil()) {
            Object javaKey = LuaDataConverter.toJava(key, Object.class);
            Object javaVal = LuaDataConverter.toJava(value, Object.class);
            map.put(javaKey, javaVal);
            return;
        }

        // 4. Field set
        if (key.isString()) {
            String name = key.toLuaString();
            if (instance instanceof Class<?> clazz) {
                Field f = findField(clazz, name, true);
                if (f != null) {
                    if (Modifier.isFinal(f.getModifiers())) {
                        throw new LuaException("Cannot assign to static final field: " + name);
                    }
                    try {
                        f.set(null, LuaDataConverter.toJava(value, f.getType()));
                        return;
                    } catch (IllegalAccessException e) {
                        throw new LuaException("Cannot write static field " + name + ": " + e.getMessage());
                    }
                }
            } else {
                Class<?> clazz = instance.getClass();
                Field f = findField(clazz, name, false);
                if (f != null) {
                    if (Modifier.isFinal(f.getModifiers())) {
                        throw new LuaException("Cannot assign to final field: " + name);
                    }
                    try {
                        f.set(instance, LuaDataConverter.toJava(value, f.getType()));
                        return;
                    } catch (IllegalAccessException e) {
                        throw new LuaException("Cannot write field " + name + ": " + e.getMessage());
                    }
                }
            }
        }

        throw new LuaException("Cannot set field '" + key.toLuaString() + "' on Java object " + instance);
    }

    private static LuaFunction createConstructorFunction(Class<?> clazz) {
        return LuaFunction.of(args -> {
            List<Constructor<?>> ctors = CTOR_CACHE.computeIfAbsent(clazz, c -> Arrays.asList(c.getConstructors()));
            Constructor<?> bestMatch = null;
            int bestScore = -1;

            for (Constructor<?> ctor : ctors) {
                int score = scoreParameters(ctor.getParameterTypes(), ctor.isVarArgs(), args);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = ctor;
                }
            }

            if (bestMatch == null) {
                throw new LuaException("No matching constructor found for class " + clazz.getName() + " with " + args.length + " arguments");
            }

            try {
                Object[] javaArgs = convertArgs(bestMatch.getParameterTypes(), bestMatch.isVarArgs(), args);
                Object obj = bestMatch.newInstance(javaArgs);
                return LuaDataConverter.wrapLive(obj);
            } catch (Throwable t) {
                throw new LuaException("Error invoking constructor for " + clazz.getName() + ": " + t.getMessage());
            }
        });
    }

    private static LuaFunction createMethodInvoker(Object target, String methodName, List<Method> candidates) {
        return LuaFunction.of(args -> {
            Method bestMatch = null;
            int bestScore = -1;

            for (Method m : candidates) {
                int score = scoreParameters(m.getParameterTypes(), m.isVarArgs(), args);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = m;
                }
            }

            if (bestMatch == null) {
                throw new LuaException("No matching method for '" + methodName + "' with " + args.length + " arguments on " +
                    (target != null ? target.getClass().getName() : "static class"));
            }

            try {
                Object[] javaArgs = convertArgs(bestMatch.getParameterTypes(), bestMatch.isVarArgs(), args);
                Object result = bestMatch.invoke(target, javaArgs);
                if (bestMatch.getReturnType() == void.class || bestMatch.getReturnType() == Void.class) {
                    return LuaNil.NIL;
                }
                return LuaDataConverter.toLua(result);
            } catch (Throwable t) {
                Throwable cause = t.getCause() != null ? t.getCause() : t;
                throw new LuaException("Error invoking Java method " + methodName + ": " + cause.getMessage());
            }
        });
    }

    private static int scoreParameters(Class<?>[] paramTypes, boolean isVarArgs, LuaValue[] args) {
        if (!isVarArgs) {
            if (paramTypes.length != args.length) return -1;
            int total = 0;
            for (int i = 0; i < paramTypes.length; i++) {
                int s = scoreArg(paramTypes[i], args[i]);
                if (s < 0) return -1;
                total += s;
            }
            return total;
        } else {
            int fixedCount = paramTypes.length - 1;
            if (args.length < fixedCount) return -1;
            int total = 0;
            for (int i = 0; i < fixedCount; i++) {
                int s = scoreArg(paramTypes[i], args[i]);
                if (s < 0) return -1;
                total += s;
            }
            Class<?> varArgComponent = paramTypes[fixedCount].getComponentType();
            for (int i = fixedCount; i < args.length; i++) {
                int s = scoreArg(varArgComponent, args[i]);
                if (s < 0) return -1;
                total += s;
            }
            return total;
        }
    }

    private static int scoreArg(Class<?> target, LuaValue val) {
        if (val == null || val.isNil()) {
            return target.isPrimitive() ? 1 : 5;
        }

        // Exact class matches
        if (target == Object.class) return 2;
        if (target.isInstance(val)) return 10;

        // Numbers
        if (val.isInteger()) {
            if (target == long.class || target == Long.class) return 10;
            if (target == int.class || target == Integer.class) return 9;
            if (target == short.class || target == Short.class || target == byte.class || target == Byte.class) return 8;
            if (target == double.class || target == Double.class || target == float.class || target == Float.class) return 7;
        }
        if (val.isFloat()) {
            if (target == double.class || target == Double.class) return 10;
            if (target == float.class || target == Float.class) return 9;
            if (target == long.class || target == int.class) return 5;
        }

        // Booleans
        if (val.isBoolean() && (target == boolean.class || target == Boolean.class)) return 10;

        // Strings
        if (val.isString() && (target == String.class || target == CharSequence.class)) return 10;
        if (val.isString() && target.isEnum()) return 8;

        // SAM Interfaces
        if (val.isFunction() && target.isInterface()) {
            if (LuaDataConverter.findSingleAbstractMethod(target) != null) return 9;
        }

        // Tables to Collections / Arrays
        if (val.isTable()) {
            if (target.isArray()) return 8;
            if (target == List.class || target == Collection.class) return 8;
            if (target == Map.class) return 8;
        }

        // Userdata unwrap
        if (val.isUserdata()) {
            Object inst = ((LuaUserdata) val).getJavaInstance();
            if (inst != null && target.isInstance(inst)) return 10;
        }

        return -1; // Incompatible
    }

    private static Object[] convertArgs(Class<?>[] paramTypes, boolean isVarArgs, LuaValue[] args) {
        if (!isVarArgs) {
            Object[] res = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                res[i] = LuaDataConverter.toJava(args[i], paramTypes[i]);
            }
            return res;
        } else {
            int fixedCount = paramTypes.length - 1;
            Object[] res = new Object[paramTypes.length];
            for (int i = 0; i < fixedCount; i++) {
                res[i] = LuaDataConverter.toJava(args[i], paramTypes[i]);
            }
            int varArgCount = args.length - fixedCount;
            Class<?> compType = paramTypes[fixedCount].getComponentType();
            Object varArgArray = Array.newInstance(compType, varArgCount);
            for (int i = 0; i < varArgCount; i++) {
                Array.set(varArgArray, i, LuaDataConverter.toJava(args[fixedCount + i], compType));
            }
            res[fixedCount] = varArgArray;
            return res;
        }
    }

    private static Field findField(Class<?> clazz, String name, boolean isStatic) {
        List<Field> fields = FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Field f : c.getFields()) {
                if (Modifier.isPublic(f.getModifiers())) {
                    f.setAccessible(true);
                    list.add(f);
                }
            }
            return list;
        });
        for (Field f : fields) {
            if (f.getName().equals(name)
                    && Modifier.isStatic(f.getModifiers()) == isStatic
                    && org.luava.binding.JavaAccessPolicy.active().isAllowed(f.getDeclaringClass().getName())) {
                return f;
            }
        }
        return null;
    }

    private static List<Method> findMethods(Class<?> clazz, String name, boolean isStatic) {
        List<Method> methods = METHOD_CACHE.computeIfAbsent(clazz, c -> {
            List<Method> list = new ArrayList<>();
            for (Method m : c.getMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    m.setAccessible(true);
                    list.add(m);
                }
            }
            return list;
        });
        List<Method> result = new ArrayList<>();
        for (Method m : methods) {
            if (m.getName().equals(name)
                    && Modifier.isStatic(m.getModifiers()) == isStatic
                    && org.luava.binding.JavaAccessPolicy.active().isAllowed(m.getDeclaringClass().getName())) {
                result.add(m);
            }
        }
        return result;
    }

    @Override
    public String toLuaString() {
        if (instance == null) return "userdata: null";
        return instance.toString();
    }

    @Override
    public boolean luaEquals(LuaValue other) {
        if (other instanceof LuaUserdata u) {
            if (instance == null && u.instance == null) return true;
            if (instance != null) return instance.equals(u.instance);
        }
        return super.luaEquals(other);
    }
}

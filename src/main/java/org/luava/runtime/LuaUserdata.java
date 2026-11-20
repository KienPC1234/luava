/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.JavaCapability;
import org.luava.binding.LuaDataConverter;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaUserdata extends LuaValue {
    private static final class ClassMembers {
        final Map<String, Field> instanceFields;
        final Map<String, Field> staticFields;
        final Map<String, List<Method>> instanceMethods;
        final Map<String, List<Method>> staticMethods;
        final boolean hasInstanceFields;
        final boolean hasStaticFields;

        ClassMembers(Class<?> c) {
            Map<String, Field> instF = new HashMap<>();
            Map<String, Field> statF = new HashMap<>();
            for (Field f : c.getFields()) {
                if (Modifier.isPublic(f.getModifiers())) {
                    if (!Modifier.isPublic(f.getDeclaringClass().getModifiers())) {
                        f.trySetAccessible();
                    }
                    if (Modifier.isStatic(f.getModifiers())) {
                        statF.putIfAbsent(f.getName(), f);
                    } else {
                        instF.putIfAbsent(f.getName(), f);
                    }
                }
            }
            Set<Method> seen = new LinkedHashSet<>();
            for (Method m : c.getMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    seen.add(resolveAccessibleMethod(m));
                }
            }
            Map<String, List<Method>> instM = new HashMap<>();
            Map<String, List<Method>> statM = new HashMap<>();
            for (Method m : seen) {
                Map<String, List<Method>> target = Modifier.isStatic(m.getModifiers()) ? statM : instM;
                target.computeIfAbsent(m.getName(), k -> new ArrayList<>(2)).add(m);
            }
            instM.replaceAll((k, v) -> List.copyOf(v));
            statM.replaceAll((k, v) -> List.copyOf(v));
            this.instanceFields = instF.isEmpty() ? Map.of() : Map.copyOf(instF);
            this.staticFields = statF.isEmpty() ? Map.of() : Map.copyOf(statF);
            this.instanceMethods = instM.isEmpty() ? Map.of() : Map.copyOf(instM);
            this.staticMethods = statM.isEmpty() ? Map.of() : Map.copyOf(statM);
            this.hasInstanceFields = !this.instanceFields.isEmpty();
            this.hasStaticFields = !this.staticFields.isEmpty();
        }
    }

    private static final ClassValue<ClassMembers> MEMBERS_CACHE = new ClassValue<>() {
        @Override
        protected ClassMembers computeValue(Class<?> c) {
            return new ClassMembers(c);
        }
    };
    private static final ClassValue<List<Constructor<?>>> CTOR_CACHE = new ClassValue<>() {
        @Override
        protected List<Constructor<?>> computeValue(Class<?> c) {
            return List.of(c.getConstructors());
        }
    };

    private record CachedInvoker(org.luava.binding.JavaAccessPolicy policy, LuaFunction fn) {}

    private final Object instance;
    private final boolean isLight;
    private LuaTable metatable;
    private final LuaValue[] userValues;
    /**
     * Per-userdata memo for resolved method invokers, keyed by method name and
     * tagged with the {@code JavaAccessPolicy} active at resolution time so a
     * dynamic policy change invalidates the cached invoker safely.
     */
    private ConcurrentHashMap<String, CachedInvoker> methodCache;
    /** Cached single-abstract-method lookup for the SAM call lane. */
    private volatile boolean samResolved;
    private volatile Method samMethod;

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

    public static LuaUserdata capability(Object target, String... allowedMethods) {
        return new LuaUserdata(new JavaCapability(target, allowedMethods), false, 0);
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
            LuaValue handler = metatable.rawget(LuaValue.Meta.INDEX);
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

        // 0. Capability wrapper: strict method-name allowlist only
        if (instance instanceof JavaCapability cap) {
            if (!key.isString()) {
                return LuaNil.NIL;
            }
            String name = key.toLuaString();
            if (!cap.allowsMethod(name)) {
                return LuaNil.NIL;
            }
            Object target = cap.target();
            Class<?> clazz = target.getClass();
            JavaAccessPolicy policy = JavaAccessPolicy.active();
            if (policy.isDeniedHierarchy(clazz)) {
                return LuaNil.NIL;
            }
            ClassMembers members = MEMBERS_CACHE.get(clazz);
            ConcurrentHashMap<String, CachedInvoker> cache = !isLight ? methodCache : null;
            if (cache != null) {
                CachedInvoker existing = cache.get(name);
                if (existing != null && existing.policy == policy) {
                    return existing.fn;
                }
            }
            List<Method> methods = filterByCapabilityPolicy(clazz, members.instanceMethods.get(name), policy);
            if (!methods.isEmpty()) {
                LuaFunction fn = cachedInvoker(name, clazz, target, methods, policy, true);
                if (fn != null) return fn;
                return createMethodInvoker(clazz, target, name, methods, true);
            }
            return LuaNil.NIL;
        }

        // 1. Array indexing
        if (instance.getClass().isArray() && key.isInteger()) {
            long rawIdx = key.toLong();
            int len = Array.getLength(instance);
            if (rawIdx >= 1 && rawIdx <= len) {
                return LuaDataConverter.toLuaLive(Array.get(instance, (int) (rawIdx - 1)));
            }
            return LuaNil.NIL;
        }

        // 2. List indexing
        if (instance instanceof List<?> list && key.isInteger()) {
            long rawIdx = key.toLong();
            if (rawIdx >= 1 && rawIdx <= list.size()) {
                return LuaDataConverter.toLuaLive(list.get((int) (rawIdx - 1)));
            }
            return LuaNil.NIL;
        }

        // 3. Map indexing
        if (instance instanceof Map<?, ?> map && !key.isNil()) {
            Object javaKey = LuaDataConverter.toJava(key, Object.class);
            if (map.containsKey(javaKey)) {
                return LuaDataConverter.toLuaLive(map.get(javaKey));
            }
        }

        // 4. String member access (methods, fields, properties)
        if (key.isString()) {
            String name = key.toLuaString();

            // Static Class context
            if (instance instanceof Class<?> clazz) {
                if ("class".equals(name)) return this;
                if ("new".equals(name)) {
                    return createConstructorFunction(clazz);
                }

                JavaAccessPolicy policy = JavaAccessPolicy.active();
                if (policy.allowsMember(clazz, null, name)) {
                    ClassMembers members = MEMBERS_CACHE.get(clazz);
                    if (members.hasStaticFields) {
                        Field f = members.staticFields.get(name);
                        if (f != null && policy.allowsMember(clazz, f.getDeclaringClass(), name)) {
                            try {
                                return LuaDataConverter.toLuaLive(f.get(null));
                            } catch (IllegalAccessException e) {
                                throw new LuaException("Access error for static field " + name + ": " + e.getMessage());
                            }
                        }
                    }

                    ConcurrentHashMap<String, CachedInvoker> cache = !isLight ? methodCache : null;
                    if (cache != null) {
                        CachedInvoker existing = cache.get(name);
                        if (existing != null && existing.policy == policy) {
                            return existing.fn;
                        }
                    }

                    List<Method> methods = filterByPolicy(clazz, name, members.staticMethods.get(name), policy);
                    if (!methods.isEmpty()) {
                        LuaFunction fn = cachedInvoker(name, clazz, null, methods, policy);
                        if (fn != null) return fn;
                        return createMethodInvoker(clazz, null, name, methods);
                    }
                }
            } else {
                Class<?> clazz = instance.getClass();
                JavaAccessPolicy policy = JavaAccessPolicy.active();
                if (policy.allowsMember(clazz, null, name)) {
                    ClassMembers members = MEMBERS_CACHE.get(clazz);
                    if (members.hasInstanceFields) {
                        Field f = members.instanceFields.get(name);
                        if (f != null && policy.allowsMember(clazz, f.getDeclaringClass(), name)) {
                            try {
                                return LuaDataConverter.toLuaLive(f.get(instance));
                            } catch (IllegalAccessException e) {
                                throw new LuaException("Access error for field " + name + ": " + e.getMessage());
                            }
                        }
                    }

                    ConcurrentHashMap<String, CachedInvoker> cache = !isLight ? methodCache : null;
                    if (cache != null) {
                        CachedInvoker existing = cache.get(name);
                        if (existing != null && existing.policy == policy) {
                            return existing.fn;
                        }
                    }

                    List<Method> methods = filterByPolicy(clazz, name, members.instanceMethods.get(name), policy);
                    if (!methods.isEmpty()) {
                        LuaFunction fn = cachedInvoker(name, clazz, instance, methods, policy);
                        if (fn != null) return fn;
                        return createMethodInvoker(clazz, instance, name, methods);
                    }
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
            LuaValue handler = metatable.rawget(LuaValue.Meta.CALL);
            if (!handler.isNil()) {
                LuaValue[] callArgs = new LuaValue[args.length + 1];
                callArgs[0] = this;
                System.arraycopy(args, 0, callArgs, 1, args.length);
                return handler.call(callArgs);
            }
        }

        if (instance != null && !(instance instanceof JavaCapability)) {
            Method sam = resolveSam();
            if (sam != null) {
                JavaAccessPolicy.active().checkMember(instance.getClass(), sam.getDeclaringClass(), sam.getName());
                try {
                    Object[] javaArgs = convertArgs(sam.getParameterTypes(), sam.isVarArgs(), args);
                    Object res = invokeSam(sam, instance, javaArgs);
                    if (sam.getReturnType() == void.class || sam.getReturnType() == Void.class) {
                        return LuaNil.NIL;
                    }
                    return LuaDataConverter.toLua(res);
                } catch (LuaException le) {
                    throw le;
                } catch (Throwable t) {
                    throw LuaFunction.hostError("Error invoking Java functional interface", t);
                }
            }
        }

        return super.call(args);
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaValue.Meta.NEWINDEX);
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
        if (instance instanceof JavaCapability) {
            throw new LuaException("access denied: capability userdata is read-only");
        }

        // 1. Array element set
        if (instance.getClass().isArray() && key.isInteger()) {
            long rawIdx = key.toLong();
            int len = Array.getLength(instance);
            if (rawIdx < 1 || rawIdx > len) {
                throw new LuaException("Array index out of bounds: " + rawIdx + " (length: " + len + ")");
            }
            Class<?> compType = instance.getClass().getComponentType();
            Array.set(instance, (int) (rawIdx - 1), LuaDataConverter.toJava(value, compType));
            return;
        }

        // 2. List element set
        if (instance instanceof List list && key.isInteger()) {
            long rawIdx = key.toLong();
            if (rawIdx < 1 || rawIdx > list.size()) {
                throw new LuaException("List index out of bounds: " + rawIdx + " (size: " + list.size() + ")");
            }
            list.set((int) (rawIdx - 1), LuaDataConverter.toJava(value, Object.class));
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

    /**
     * Resolves (once per userdata) the single abstract method implemented by
     * the wrapped instance, so {@code userdata(args)} can forward to a SAM
     * interface without rescanning interfaces on every call.
     */
    private Method resolveSam() {
        if (samResolved) {
            return samMethod;
        }
        synchronized (this) {
            if (samResolved) {
                return samMethod;
            }
            Class<?> clazz = instance.getClass();
            Method sam = null;
            for (Class<?> iface : clazz.getInterfaces()) {
                Method ifaceSam = LuaDataConverter.findSingleAbstractMethod(iface);
                if (ifaceSam != null) {
                    if (Modifier.isPublic(iface.getModifiers())) {
                        sam = ifaceSam;
                        break;
                    }
                    for (Method m : clazz.getMethods()) {
                        if (m.getName().equals(ifaceSam.getName()) && !m.isBridge()) {
                            sam = resolveAccessibleMethod(m);
                            break;
                        }
                    }
                    if (sam == null) sam = resolveAccessibleMethod(ifaceSam);
                    break;
                }
            }
            if (sam == null) {
                sam = resolveAccessibleMethod(LuaDataConverter.findSingleAbstractMethod(clazz));
            }
            samMethod = sam;
            samResolved = true;
            return sam;
        }
    }

    /**
     * Returns an accessible {@link Method} for {@code m}. When {@code m}'s
     * declaring class is non-public (e.g. {@code List.of()}'s internal
     * implementation class), finds the overridden public method on a public
     * superclass or interface so JPMS does not fail with
     * {@code InaccessibleObjectException}.
     */
    public static Method resolveAccessibleMethod(Method m) {
        if (m == null) {
            return null;
        }
        Class<?> declaring = m.getDeclaringClass();
        if (Modifier.isPublic(declaring.getModifiers())) {
            return m;
        }
        Method pub = findPublicOverriddenMethod(declaring, m.getName(), m.getParameterTypes());
        if (pub != null) {
            return pub;
        }
        m.trySetAccessible();
        return m;
    }

    private static Method findPublicOverriddenMethod(Class<?> clazz, String name, Class<?>[] paramTypes) {
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            if (cur != clazz && Modifier.isPublic(cur.getModifiers())) {
                try {
                    return cur.getMethod(name, paramTypes);
                } catch (NoSuchMethodException ignored) {
                }
            }
            Method fromIface = findPublicInterfaceMethod(cur.getInterfaces(), name, paramTypes);
            if (fromIface != null) {
                return fromIface;
            }
        }
        return null;
    }

    private static Method findPublicInterfaceMethod(Class<?>[] ifaces, String name, Class<?>[] paramTypes) {
        for (Class<?> iface : ifaces) {
            if (Modifier.isPublic(iface.getModifiers())) {
                try {
                    return iface.getMethod(name, paramTypes);
                } catch (NoSuchMethodException ignored) {
                }
            }
            Method nested = findPublicInterfaceMethod(iface.getInterfaces(), name, paramTypes);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static LuaFunction createConstructorFunction(Class<?> clazz) {
        return LuaFunction.of(args -> {
            JavaAccessPolicy.active().checkMember(clazz, clazz, "<init>");
            List<Constructor<?>> ctors = CTOR_CACHE.get(clazz);
            Constructor<?> bestMatch = (Constructor<?>) org.luava.binding.OverloadResolver.resolve(ctors, args);

            if (bestMatch == null) {
                throw new LuaException("No matching constructor found for class " + clazz.getName() + " with " + args.length + " arguments");
            }

            try {
                Object[] javaArgs = convertArgs(bestMatch.getParameterTypes(), bestMatch.isVarArgs(), args);
                Object obj = bestMatch.newInstance(javaArgs);
                return LuaDataConverter.wrapLive(obj);
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking constructor for " + clazz.getName(), t);
            }
        });
    }

    /**
     * Returns the per-userdata memoized invoker for {@code name}, creating it
     * on first use when the active policy matches. The bound target is always
     * this userdata's own instance (or null for static members).
     */
    private LuaFunction cachedInvoker(String name, Class<?> ownerClass, Object target, List<Method> candidates,
            JavaAccessPolicy policy) {
        return cachedInvoker(name, ownerClass, target, candidates, policy, false);
    }

    private LuaFunction cachedInvoker(String name, Class<?> ownerClass, Object target, List<Method> candidates,
            JavaAccessPolicy policy, boolean capabilityGrant) {
        if (isLight) {
            return null;
        }
        ConcurrentHashMap<String, CachedInvoker> cache = methodCache;
        if (cache == null) {
            synchronized (this) {
                cache = methodCache;
                if (cache == null) {
                    cache = new ConcurrentHashMap<>();
                    methodCache = cache;
                }
            }
        }
        CachedInvoker existing = cache.get(name);
        if (existing != null && existing.policy == policy) {
            return existing.fn;
        }
        LuaFunction created = createMethodInvoker(ownerClass, target, name, candidates, capabilityGrant);
        CachedInvoker entry = new CachedInvoker(policy, created);
        if (existing != null) {
            cache.put(name, entry);
            return created;
        }
        CachedInvoker prev = cache.putIfAbsent(name, entry);
        return (prev != null && prev.policy == policy) ? prev.fn : created;
    }

    private static LuaFunction createMethodInvoker(Class<?> ownerClass, Object target, String methodName,
            List<Method> candidates) {
        return createMethodInvoker(ownerClass, target, methodName, candidates, false);
    }

    private static void checkMethodInvocationPolicy(Class<?> ownerClass, Class<?> declaringClass,
            String methodName, boolean capabilityGrant) {
        JavaAccessPolicy policy = JavaAccessPolicy.active();
        if (capabilityGrant) {
            if (policy.isDeniedHierarchy(ownerClass)
                    || (declaringClass != null && policy.isDeniedHierarchy(declaringClass))) {
                throw new LuaException("access denied: " + ownerClass.getName()
                        + "." + methodName + " is blocked by the Java access policy (" + policy.label() + ")");
            }
            return;
        }
        policy.checkMember(ownerClass, declaringClass, methodName);
    }

    private static LuaFunction createMethodInvoker(Class<?> ownerClass, Object target, String methodName,
            List<Method> candidates, boolean capabilityGrant) {
        if (candidates.size() == 1) {
            Method single = candidates.get(0);
            Class<?> declaringClass = single.getDeclaringClass();
            LuaDataConverter.MethodMeta meta = LuaDataConverter.methodMeta(single);
            if (!meta.isVarArgs && meta.handle != null) {
                MethodHandle bound = (!meta.isStatic && target != null) ? meta.handle.bindTo(target) : meta.handle;
                Class<?>[] p = meta.paramTypes;
                if (p.length == 0) {
                    MethodHandle mh = bound.asType(MethodType.genericMethodType(0));
                    return createArity0Invoker(ownerClass, declaringClass, target, methodName, meta.returnType, mh, capabilityGrant);
                }
                if (p.length == 1) {
                    if (p[0] == long.class && meta.returnType == long.class) {
                        MethodHandle exact = bound.asType(MethodType.methodType(long.class, long.class));
                        return createLongToLongInvoker(ownerClass, declaringClass, target, methodName, exact, capabilityGrant);
                    }
                    MethodHandle mh = bound.asType(MethodType.genericMethodType(1));
                    return createArity1Invoker(ownerClass, declaringClass, target, methodName, p[0], meta.returnType, mh, capabilityGrant);
                }
                if (p.length == 2) {
                    MethodHandle mh = bound.asType(MethodType.genericMethodType(2));
                    return createArity2Invoker(ownerClass, declaringClass, target, methodName, p[0], p[1], meta.returnType, mh, capabilityGrant);
                }
                if (p.length == 3) {
                    MethodHandle mh = bound.asType(MethodType.genericMethodType(3));
                    return createArity3Invoker(ownerClass, declaringClass, target, methodName, p[0], p[1], p[2], meta.returnType, mh, capabilityGrant);
                }
                if (p.length == 4) {
                    MethodHandle mh = bound.asType(MethodType.genericMethodType(4));
                    return createArity4Invoker(ownerClass, declaringClass, target, methodName, p, meta.returnType, mh, capabilityGrant);
                }
            }
        }
        return createMultiInvoker(ownerClass, target, methodName, candidates, capabilityGrant);
    }

    private static boolean isSelf(LuaValue arg, Object target) {
        if (target == null || !(arg instanceof LuaUserdata self)) {
            return false;
        }
        return self.instance == target
                || (self.instance instanceof JavaCapability jc && jc.target() == target);
    }

    private static int resolveOffset(LuaValue[] args, int arity, Object target, String methodName) {
        if (args.length == arity) {
            return 0;
        }
        if (args.length == arity + 1 && isSelf(args[0], target)) {
            return 1;
        }
        throw noMatchingMethod(methodName, args.length, target);
    }

    private static LuaException noMatchingMethod(String methodName, int argc, Object target) {
        return new LuaException("No matching method for '" + methodName + "' with " + argc + " arguments on "
                + (target != null ? target.getClass().getName() : "static class"));
    }

    private static LuaValue convertReturn(Class<?> returnType, Object result) {
        if (returnType == void.class || returnType == Void.class) {
            return LuaNil.NIL;
        }
        return LuaDataConverter.toLua(result);
    }

    private static LuaFunction createLongToLongInvoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, MethodHandle exact, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            int off = resolveOffset(args, 1, target, methodName);
            LuaValue a0 = args[off];
            try {
                if (a0 instanceof LuaInteger li) {
                    return LuaInteger.valueOf((long) exact.invokeExact(li.toLong()));
                }
                if (org.luava.binding.OverloadResolver.scoreArg(long.class, a0)
                        == org.luava.binding.OverloadResolver.INCOMPATIBLE) {
                    throw noMatchingMethod(methodName, args.length, target);
                }
                long v = LuaDataConverter.toJava(a0, long.class);
                return LuaInteger.valueOf((long) exact.invokeExact(v));
            } catch (LuaException le) {
                throw le;
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createArity0Invoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, Class<?> returnType, MethodHandle mh, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            resolveOffset(args, 0, target, methodName);
            try {
                return convertReturn(returnType, mh.invokeExact());
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createArity1Invoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, Class<?> p0, Class<?> returnType, MethodHandle mh, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            int off = resolveOffset(args, 1, target, methodName);
            LuaValue a0 = args[off];
            if (org.luava.binding.OverloadResolver.scoreArg(p0, a0)
                    == org.luava.binding.OverloadResolver.INCOMPATIBLE) {
                throw noMatchingMethod(methodName, args.length, target);
            }
            try {
                Object j0 = LuaDataConverter.toJava(a0, p0);
                return convertReturn(returnType, mh.invokeExact(j0));
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createArity2Invoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, Class<?> p0, Class<?> p1, Class<?> returnType, MethodHandle mh, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            int off = resolveOffset(args, 2, target, methodName);
            LuaValue a0 = args[off];
            LuaValue a1 = args[off + 1];
            if (org.luava.binding.OverloadResolver.scoreArg(p0, a0) == org.luava.binding.OverloadResolver.INCOMPATIBLE
                    || org.luava.binding.OverloadResolver.scoreArg(p1, a1) == org.luava.binding.OverloadResolver.INCOMPATIBLE) {
                throw noMatchingMethod(methodName, args.length, target);
            }
            try {
                Object j0 = LuaDataConverter.toJava(a0, p0);
                Object j1 = LuaDataConverter.toJava(a1, p1);
                return convertReturn(returnType, mh.invokeExact(j0, j1));
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createArity3Invoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, Class<?> p0, Class<?> p1, Class<?> p2, Class<?> returnType, MethodHandle mh, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            int off = resolveOffset(args, 3, target, methodName);
            LuaValue a0 = args[off];
            LuaValue a1 = args[off + 1];
            LuaValue a2 = args[off + 2];
            if (org.luava.binding.OverloadResolver.scoreArg(p0, a0) == org.luava.binding.OverloadResolver.INCOMPATIBLE
                    || org.luava.binding.OverloadResolver.scoreArg(p1, a1) == org.luava.binding.OverloadResolver.INCOMPATIBLE
                    || org.luava.binding.OverloadResolver.scoreArg(p2, a2) == org.luava.binding.OverloadResolver.INCOMPATIBLE) {
                throw noMatchingMethod(methodName, args.length, target);
            }
            try {
                Object j0 = LuaDataConverter.toJava(a0, p0);
                Object j1 = LuaDataConverter.toJava(a1, p1);
                Object j2 = LuaDataConverter.toJava(a2, p2);
                return convertReturn(returnType, mh.invokeExact(j0, j1, j2));
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createArity4Invoker(Class<?> ownerClass, Class<?> declaringClass,
            Object target, String methodName, Class<?>[] p, Class<?> returnType, MethodHandle mh, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, declaringClass, methodName, capabilityGrant);
            int off = resolveOffset(args, 4, target, methodName);
            for (int i = 0; i < 4; i++) {
                if (org.luava.binding.OverloadResolver.scoreArg(p[i], args[off + i])
                        == org.luava.binding.OverloadResolver.INCOMPATIBLE) {
                    throw noMatchingMethod(methodName, args.length, target);
                }
            }
            try {
                Object j0 = LuaDataConverter.toJava(args[off], p[0]);
                Object j1 = LuaDataConverter.toJava(args[off + 1], p[1]);
                Object j2 = LuaDataConverter.toJava(args[off + 2], p[2]);
                Object j3 = LuaDataConverter.toJava(args[off + 3], p[3]);
                return convertReturn(returnType, mh.invokeExact(j0, j1, j2, j3));
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    private static LuaFunction createMultiInvoker(Class<?> ownerClass, Object target, String methodName,
            List<Method> candidates, boolean capabilityGrant) {
        return LuaFunction.of(args -> {
            checkMethodInvocationPolicy(ownerClass, null, methodName, capabilityGrant);
            Method bestMatch = (Method) org.luava.binding.OverloadResolver.resolve(candidates, args);
            LuaValue[] callArgs = args;

            if (bestMatch == null) {
                LuaValue[] withoutSelf = dropLeadingSelf(args, target);
                if (withoutSelf != null) {
                    bestMatch = (Method) org.luava.binding.OverloadResolver.resolve(candidates, withoutSelf);
                    callArgs = withoutSelf;
                }
            }

            if (bestMatch == null) {
                throw noMatchingMethod(methodName, args.length, target);
            }
            checkMethodInvocationPolicy(ownerClass, bestMatch.getDeclaringClass(), methodName, capabilityGrant);

            try {
                LuaDataConverter.MethodMeta meta = LuaDataConverter.methodMeta(bestMatch);
                Object[] javaArgs = convertArgs(meta.paramTypes, meta.isVarArgs, callArgs);
                Object result = invokeResolved(meta, target, javaArgs);
                return convertReturn(meta.returnType, result);
            } catch (Throwable t) {
                throw LuaFunction.hostError("Error invoking Java method " + methodName, t);
            }
        });
    }

    /**
     * Returns {@code args} without its first element when that element is the
     * receiver this invoker is bound to, or null when it is not. Identity of
     * the wrapped Java object is the test, not the userdata wrapper, so
     * re-wrapping the same object still matches.
     */
    private static LuaValue[] dropLeadingSelf(LuaValue[] args, Object target) {
        if (target == null || args.length == 0 || !isSelf(args[0], target)) {
            return null;
        }
        return Arrays.copyOfRange(args, 1, args.length);
    }

    /**
     * Memoized, unreflected invocation for a resolved method. The
     * {@link java.lang.invoke.MethodHandle} is cached by
     * {@link LuaDataConverter#samHandle(Method)} (per {@code Method}, so per
     * overload), replacing the repeated {@code Method.invoke} reflection the
     * interop layer used to pay on every call.
     *
     * <p>Arguments are already converted to the exact declared parameter types
     * by {@link #convertArgs}, so no widening retry is needed. That matters
     * for correctness: unlike {@code Method.invoke}, which wraps any exception
     * thrown by the target in {@code InvocationTargetException},
     * {@code MethodHandle.invokeWithArguments} propagates the target's own
     * {@code IllegalArgumentException}/{@code ClassCastException} unchanged,
     * so catching them here to "retry" would re-invoke a method that threw and
     * double-apply its side effects.
     */
    private static Object invokeResolved(LuaDataConverter.MethodMeta meta, Object target, Object[] javaArgs) throws Throwable {
        MethodHandle mh = meta.handle;
        if (mh == null) {
            return meta.accessibleMethod.invoke(target, javaArgs);
        }
        return mh.invokeWithArguments(withReceiver(meta.isStatic, target, javaArgs));
    }

    /**
     * Invokes a Java object that implements a SAM interface (a lambda or
     * anonymous class) from Lua. Such a method's erased parameter type is
     * {@code Object}, so a Lua integer becomes a {@code Long} while the typed
     * lambda body may expect an {@code Integer}; a {@code Method.invoke} with
     * the wrong box throws a pre-invocation {@code IllegalArgumentException}.
     * Narrowing the integers and retrying once fixes that, and is safe
     * <em>only</em> because {@code Method.invoke} wraps any exception the
     * target itself throws in {@code InvocationTargetException} (so a bare
     * {@code IllegalArgumentException} is a type mismatch, never a target
     * failure already applied). {@code MethodHandle} does not wrap, so it
     * cannot be used for this retry.
     */
    private static Object invokeSam(Method method, Object target, Object[] javaArgs) throws Throwable {
        method = resolveAccessibleMethod(method);
        if (!Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            method.trySetAccessible();
        }
        try {
            return method.invoke(target, javaArgs);
        } catch (IllegalArgumentException iae) {
            boolean narrowed = false;
            for (int i = 0; i < javaArgs.length; i++) {
                if (javaArgs[i] instanceof Long l && l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    javaArgs[i] = l.intValue();
                    narrowed = true;
                }
            }
            if (!narrowed) {
                throw iae;
            }
            return method.invoke(target, javaArgs);
        }
    }

    /**
     * An unreflected instance-method handle takes the receiver as its leading
     * argument, so prepend it. Static methods take the arguments unchanged.
     */
    private static Object[] withReceiver(boolean isStatic, Object target, Object[] javaArgs) {
        if (isStatic) {
            return javaArgs;
        }
        Object[] effective = new Object[javaArgs.length + 1];
        effective[0] = target;
        System.arraycopy(javaArgs, 0, effective, 1, javaArgs.length);
        return effective;
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
        JavaAccessPolicy policy = JavaAccessPolicy.active();
        if (!policy.allowsMember(clazz, null, name)) {
            return null;
        }
        ClassMembers members = MEMBERS_CACHE.get(clazz);
        Field f = isStatic ? members.staticFields.get(name) : members.instanceFields.get(name);
        if (f != null && policy.allowsMember(clazz, f.getDeclaringClass(), name)) {
            return f;
        }
        return null;
    }

    private static List<Method> filterByPolicy(Class<?> ownerClass, String methodName,
            List<Method> methods, JavaAccessPolicy policy) {
        if (methods == null || methods.isEmpty()) {
            return List.of();
        }
        int n = methods.size();
        for (int i = 0; i < n; i++) {
            if (!policy.allowsMember(ownerClass, methods.get(i).getDeclaringClass(), methodName)) {
                List<Method> filtered = new ArrayList<>(n);
                for (int j = 0; j < n; j++) {
                    Method m = methods.get(j);
                    if (policy.allowsMember(ownerClass, m.getDeclaringClass(), methodName)) {
                        filtered.add(m);
                    }
                }
                return filtered;
            }
        }
        return methods;
    }

    private static List<Method> filterByCapabilityPolicy(Class<?> ownerClass,
            List<Method> methods, JavaAccessPolicy policy) {
        if (methods == null || methods.isEmpty() || policy.isDeniedHierarchy(ownerClass)) {
            return List.of();
        }
        int n = methods.size();
        for (int i = 0; i < n; i++) {
            if (policy.isDeniedHierarchy(methods.get(i).getDeclaringClass())) {
                List<Method> filtered = new ArrayList<>(n);
                for (int j = 0; j < n; j++) {
                    Method m = methods.get(j);
                    if (!policy.isDeniedHierarchy(m.getDeclaringClass())) {
                        filtered.add(m);
                    }
                }
                return filtered;
            }
        }
        return methods;
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

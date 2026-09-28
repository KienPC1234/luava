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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaDataConverter {
    private LuaDataConverter() {}

    /** Cached SAM lookup: interface -> the unique abstract method (or NONE). */
    private static final Map<Class<?>, Method> SAM_METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NO_SAM = ConcurrentHashMap.newKeySet();
    /** Cached unreflected SAM handles, keyed by the abstract method. */
    private static final Map<Method, MethodHandle> SAM_HANDLE_CACHE = new ConcurrentHashMap<>();

    /**
     * Converts a host Java {@code String} (Unicode text) into a Lua string.
     *
     * <p>Lua strings are byte sequences, represented internally as one char
     * per byte (ISO-8859-1). Host text is therefore UTF-8 encoded first;
     * otherwise {@code #s}, {@code string.byte}, {@code string.sub} and
     * {@code utf8.*} would treat one multi-byte character as one byte and
     * silently corrupt every non-ASCII value crossing the bridge.
     */
    public static LuaString toLuaString(String s) {
        if (s == null) return LuaString.EMPTY;
        return LuaString.valueOf(new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }

    /**
     * Converts a Lua string (one char per byte) back into a host Java
     * {@code String}. Valid UTF-8 is decoded so a value set from a host
     * String round-trips exactly; binary strings that are not valid UTF-8
     * fall back to a lossless byte-per-char mapping instead of being
     * corrupted with replacement characters.
     */
    public static String toJavaString(String byteChars) {
        if (byteChars == null) return null;
        boolean ascii = true;
        for (int i = 0; i < byteChars.length(); i++) {
            char c = byteChars.charAt(i);
            if (c > 0xFF) return byteChars; // already real chars, not bytes
            if (c >= 0x80) ascii = false;
        }
        if (ascii) return byteChars;
        byte[] bytes = byteChars.getBytes(StandardCharsets.ISO_8859_1);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            return byteChars; // binary data: preserve bytes one-to-one
        }
    }

    /**
     * Snapshot conversion: a Java {@code List}/{@code Map}/{@code Set}/array
     * becomes a fresh {@link LuaTable} (a copy), so Java cannot observe
     * writes made in Lua. This is the documented {@code state.set} behaviour.
     *
     * <p>Anything the host already exposed live (via {@code setLive}) flows
     * through {@link #toLuaLive} instead, so nested collections stay live and
     * mutations are visible in Java.
     */
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
        if (obj instanceof Character c) return toLuaString(String.valueOf(c));
        // Only immutable String converts to a Lua string. Mutable
        // CharSequences (StringBuilder, buffers, ...) stay live userdata so
        // object identity and chaining survive the bridge.
        if (obj instanceof String s) return toLuaString(s);

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

    /**
     * Live conversion: a Java {@code List}/{@code Map}/{@code Set}/array
     * becomes a {@link LuaUserdata} that reads and writes the original
     * object, so mutations made in Lua are visible in Java. This is the
     * {@code state.setLive} contract, and it is also what every value
     * <em>stored in</em> a live object uses — public fields, {@code List}
     * elements, {@code Map} values and array elements — so liveness does not
     * stop at the first level.
     *
     * <p>Method and SAM <em>results</em> deliberately keep the snapshot
     * behaviour of {@link #toLua}: a returned collection is a value the host
     * computed, and the documented contract is that it materialises as a Lua
     * table.
     */
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
        if (obj instanceof Character c) return toLuaString(String.valueOf(c));
        if (obj instanceof String s) return toLuaString(s);
        if (obj instanceof Enum<?> e) return toLuaString(e.name());

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
                    mt.rawset(LuaValue.Meta.LEN, LuaFunction.ofGuarded(args -> {
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
            return (T) toJavaString(val.toLuaString());
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

        // Integers and primitive number coercion. Lua semantics: numbers and
        // numeric strings convert, everything else is an error (never a
        // silent 0).
        if (targetType == byte.class || targetType == Byte.class) {
            return (T) Byte.valueOf((byte) toIntegralChecked(val, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte"));
        }

        if (targetType == short.class || targetType == Short.class) {
            return (T) Short.valueOf((short) toIntegralChecked(val, Short.MIN_VALUE, Short.MAX_VALUE, "short"));
        }

        if (targetType == int.class || targetType == Integer.class) {
            return (T) Integer.valueOf((int) toIntegralChecked(val, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"));
        }

        if (targetType == long.class || targetType == Long.class) {
            return (T) Long.valueOf(toLongChecked(val));
        }

        // Floating point numbers
        if (targetType == float.class || targetType == Float.class) {
            return (T) Float.valueOf((float) toDoubleChecked(val));
        }

        if (targetType == double.class || targetType == Double.class) {
            return (T) Double.valueOf(toDoubleChecked(val));
        }

        // Arbitrary-precision and erased numeric targets. Lua numbers reach
        // Java as long/double, so an exact-width target is built from that.
        if (targetType == BigInteger.class) {
            if (val.isInteger()) {
                return (T) BigInteger.valueOf(val.toLong());
            }
            if (val.isFloat()) {
                return (T) BigDecimal.valueOf(val.toDouble()).toBigInteger();
            }
            return (T) new BigInteger(val.toLuaString());
        }

        if (targetType == BigDecimal.class) {
            if (val.isInteger()) {
                return (T) BigDecimal.valueOf(val.toLong());
            }
            if (val.isFloat()) {
                return (T) BigDecimal.valueOf(val.toDouble());
            }
            return (T) new BigDecimal(val.toLuaString());
        }

        if (targetType == Number.class) {
            if (val.isInteger()) {
                return (T) Long.valueOf(val.toLong());
            }
            if (val.isFloat()) {
                return (T) Double.valueOf(val.toDouble());
            }
            return (T) Long.valueOf(toLongChecked(val));
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
                int len = requireSequence(table, targetType);
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
                int len = requireSequence(table, targetType);
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
                int len = requireSequence(table, targetType);
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
            if (val.isString()) return (T) toJavaString(val.toLuaString());
            if (val.isTable()) return (T) toJava(val, Map.class);
            if (val.isUserdata()) return (T) ((LuaUserdata) val).getJavaInstance();
            return (T) val;
        }

        throw new LuaException("Cannot convert Lua value of type " + val.typeName() + " to Java type " + targetType.getName());
    }

    /**
     * Coerces a Lua value to a Java integer, following Lua's rules: integer
     * and float values convert (floats truncate toward zero), numeric strings
     * convert, anything else raises a Lua error rather than becoming 0.
     */
    /**
     * Returns the length of a Lua table being converted to a Java sequence
     * ({@code List}, {@code Set} or array), or throws when the table is not a
     * sequence.
     *
     * <p>Only tables whose non-nil keys are exactly {@code 1..n} convert. A
     * sparse or mixed-key table ({@code {[1]='a',[3]='c'}}, {@code {1,2,x=5}},
     * {@code {1,nil,3}}) has no faithful Java sequence form, and
     * {@code #rawlen} would silently emit a shorter collection with elements
     * dropped: the host would receive a plausible but wrong value. Asking for a
     * {@code Map} instead preserves every entry.
     *
     * <p>Both directions of a hole are checked: a key past {@code #rawlen}
     * (a sparse tail) and a nil slot inside {@code 1..#rawlen} (a hole in the
     * middle, which would become a Java {@code null} element).
     */
    private static int requireSequence(LuaTable table, Class<?> targetType) {
        int len = table.rawlen();
        for (LuaValue key : table.keys()) {
            if (key.isInteger()) {
                long i = key.toLong();
                if (i >= 1 && i <= len) {
                    continue;
                }
            }
            throw new LuaException("cannot convert a Lua table with non-sequence key "
                    + key.toLuaString() + " to " + targetType.getSimpleName()
                    + " (it would drop elements; use Map to keep every entry)");
        }
        for (int i = 1; i <= len; i++) {
            if (table.rawget(LuaInteger.valueOf(i)).isNil()) {
                throw new LuaException("cannot convert a Lua table with a nil element at index "
                        + i + " to " + targetType.getSimpleName()
                        + " (it would become a null entry; use Map to keep every entry)");
            }
        }
        return len;
    }

    /**
     * Coerces a Lua number to a Java {@code long}, rejecting values that do
     * not fit or are not finite. Java's {@code (long)} cast silently saturates
     * (NaN becomes 0, ±infinity becomes ±Long.MAX) and Lua values arrive as
     * doubles up to 2^63, so a plain cast would hand the host a plausible but
     * wrong number.
     */
    static long toLongChecked(LuaValue val) {
        if (val.isInteger()) return val.toLong();
        if (val.isFloat()) {
            double d = val.toDouble();
            // (double) Long.MAX_VALUE == 2^63 exactly, so the upper bound is
            // exclusive while the lower bound is inclusive (-2^63 is a long).
            if (d != d || d < -0x1p63 || d >= 0x1p63) {
                throw new LuaException("number " + val.toLuaString() + " is out of long range");
            }
            return (long) d;
        }
        if (val.isString()) return val.toLong(); // throws on non-numeric strings
        throw new LuaException("number expected, got " + val.typeName());
    }

    /**
     * Coerces a Lua number to an integral Java type in {@code [min, max]},
     * truncating toward zero (Java's cast semantics) but raising instead of
     * wrapping when the value does not fit. Fractional values are truncated,
     * which is the documented behaviour; out-of-range values are not a
     * truncation but a wraparound, and those are refused.
     */
    static long toIntegralChecked(LuaValue val, long min, long max, String typeName) {
        if (val.isFloat()) {
            double d = val.toDouble();
            if (d != d || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY
                    || d < min || d > max) {
                throw new LuaException("number " + val.toLuaString()
                        + " is out of range for " + typeName);
            }
            return (long) d;
        }
        long v = val.isString() ? val.toLong() : toLongChecked(val);
        if (v < min || v > max) {
            throw new LuaException("number " + val.toLuaString()
                    + " is out of range for " + typeName);
        }
        return v;
    }

    /**
     * Coerces a Lua value to a Java double following Lua's rules; non-numeric
     * values raise a Lua error instead of silently becoming 0.
     */
    static double toDoubleChecked(LuaValue val) {
        if (val.isFloat()) return val.toDouble();
        if (val.isInteger()) return (double) val.toLong();
        if (val.isString()) return val.toDouble(); // throws on non-numeric strings
        throw new LuaException("number expected, got " + val.typeName());
    }

    public static Method findSingleAbstractMethod(Class<?> iface) {
        if (!iface.isInterface()) return null;
        if (NO_SAM.contains(iface)) return null;
        Method cached = SAM_METHOD_CACHE.get(iface);
        if (cached != null) return cached;
        Method candidate = null;
        for (Method m : iface.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) {
                if (isObjectMethod(m)) continue;
                if (candidate != null) {
                    // More than one abstract method: remember the negative
                    // result so repeated scoreArg/conversion probes do not
                    // rescan the interface on every call.
                    NO_SAM.add(iface);
                    return null;
                }
                candidate = m;
            }
        }
        if (candidate == null) {
            NO_SAM.add(iface);
            return null;
        }
        SAM_METHOD_CACHE.put(iface, candidate);
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
        return (T) Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                new SamInvocationHandler(iface, fn, samMethod));
    }

    /**
     * Invocation handler for a Lua-function SAM proxy. The abstract method
     * is pre-resolved (never re-derived per invocation); only {@code equals},
     * {@code hashCode}, {@code toString} and the single abstract method are
     * ever accepted. Each call converts the Java arguments through the same
     * {@link #toLua}/{@link #toJava} bridge the rest of the interop layer
     * uses, so semantics are identical to {@code JavaInteropLib} proxies.
     */
    private static final class SamInvocationHandler implements java.lang.reflect.InvocationHandler {
        private final Class<?> iface;
        private final LuaFunction fn;
        private final Method samMethod;
        private final String samName;
        private final Class<?> returnType;

        SamInvocationHandler(Class<?> iface, LuaFunction fn, Method samMethod) {
            this.iface = iface;
            this.fn = fn;
            this.samMethod = samMethod;
            this.samName = samMethod.getName();
            this.returnType = samMethod.getReturnType();
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && args[0] == proxy;
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "LuaSAMProxy:" + iface.getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(proxy));
            }
            if (!name.equals(samName) || method.getParameterCount() != samMethod.getParameterCount()) {
                throw new UnsupportedOperationException("Method " + name + " is not supported on SAM proxy");
            }
            int count = (args != null ? args.length : 0);
            LuaValue[] luaArgs = new LuaValue[count];
            for (int i = 0; i < count; i++) {
                luaArgs[i] = toLua(args[i]);
            }
            LuaValue res = fn.call(luaArgs);
            if (returnType == void.class || returnType == Void.class) {
                return null;
            }
            return toJava(res, returnType);
        }
    }

    /**
     * Returns a cached, unreflected {@link MethodHandle} for a SAM method.
     * Prefer this over {@code Method.invoke} on any hot Java-interop path so
     * the reflection cost is paid once per method and C2 can optimize the
     * call site. Returns {@code null} when the method cannot be unreflected.
     */
    public static MethodHandle samHandle(Method method) {
        MethodHandle h = SAM_HANDLE_CACHE.get(method);
        if (h != null) {
            return h;
        }
        try {
            method.setAccessible(true);
            MethodHandle mh = MethodHandles.lookup().unreflect(method);
            // Callers already pack varargs into the trailing array via
            // convertArgs, so the handle must be fixed-arity. A varargs
            // collector would re-spread that array as extra arguments and
            // mis-unbox it (e.g. int[] into Number), which is exactly the bug
            // that made Java varargs methods uncallable from Lua.
            if (mh.isVarargsCollector()) {
                mh = mh.asFixedArity();
            }
            MethodHandle prev = SAM_HANDLE_CACHE.putIfAbsent(method, mh);
            return prev != null ? prev : mh;
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}

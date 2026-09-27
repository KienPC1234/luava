/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.reflect.Executable;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared Java overload selection for the dynamic ({@link org.luava.runtime.LuaUserdata})
 * and annotation-bound ({@link ModuleBinder}) interop lanes.
 *
 * <p>Selection is a two-stage process that mirrors the useful parts of JLS
 * &sect;15.12 without pretending Lua values have static Java types:
 *
 * <ol>
 *   <li><b>Score.</b> Every candidate gets a per-argument score from
 *       {@link #scoreArg}; the total is the sum minus a fixed varargs
 *       penalty. Higher is a closer conversion. A candidate that cannot
 *       accept an argument at all scores {@code -1} and is inapplicable.
 *       Scores encode numeric range (a Lua integer that fits {@code int} is
 *       closer to {@code int} than to {@code long}, but only {@code long} can
 *       take a value that does not fit {@code int}), inheritance/interface
 *       distance, boxing vs primitive widening, SAM adaptation, and null
 *       specificity.</li>
 *   <li><b>Specificity.</b> If several candidates share the top score,
 *       {@link #mostSpecific} keeps only those whose parameter types are
 *       subtypes of every other tied candidate's (JLS &sect;15.12.2.5). If no
 *       single candidate dominates, the call is ambiguous and a
 *       {@link LuaException} is raised rather than silently keeping the
 *       first-declared method.</li>
 * </ol>
 *
 * <p>Ambiguity is deliberate: {@code foo(CharSequence)} and
 * {@code foo(Serializable)} are equally applicable to a Lua string and neither
 * parameter is a subtype of the other, exactly as in Java. Silently picking
 * the first would make overload resolution depend on {@code getMethods()}
 * ordering, which the JVM does not specify.
 */
public final class OverloadResolver {
    private OverloadResolver() {}

    /** Best possible score: an exact type match. */
    private static final int S_EXACT = 100;
    /** {@code Object} accepts anything but is the weakest useful target. */
    private static final int S_OBJECT = 20;
    /** Any reference type is equally applicable to {@code nil}; specificity decides. */
    private static final int S_NULL_REF = 50;
    /** A primitive target under {@code nil} becomes the zero value (Lua-like). */
    private static final int S_NULL_PRIM = 5;
    /** Fixed cost for reaching a varargs array, applied once per candidate. */
    private static final int S_VARARGS_PENALTY = 25;
    /**
     * Sentinel for "candidate cannot accept these arguments". Distinct from a
     * low but valid score, because the varargs penalty can push a valid total
     * below zero (e.g. {@code foo(Object...)} with one nil argument).
     */
    private static final int INCOMPATIBLE = Integer.MIN_VALUE;

    /** Cache of {@code target -> actual -> inheritance distance}. */
    private static final Map<Class<?>, Map<Class<?>, Integer>> DISTANCE_CACHE = new ConcurrentHashMap<>();

    /**
     * Picks the best candidate for {@code args}, or {@code null} when none is
     * applicable. Throws {@link LuaException} when two or more tied candidates
     * are mutually incomparable.
     */
    public static Executable resolve(List<? extends Executable> candidates, LuaValue[] args) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int bestScore = INCOMPATIBLE;
        List<Executable> scores = new ArrayList<>(candidates.size());
        int[] totals = new int[candidates.size()];
        int i = 0;
        for (Executable e : candidates) {
            int s = score(e, args);
            totals[i++] = s;
            scores.add(e);
            if (s > bestScore) {
                bestScore = s;
            }
        }
        if (bestScore == INCOMPATIBLE) {
            return null;
        }

        List<Executable> tied = new ArrayList<>(2);
        for (int k = 0; k < scores.size(); k++) {
            if (totals[k] == bestScore) {
                tied.add(scores.get(k));
            }
        }
        dedupe(tied);
        if (tied.size() == 1) {
            return tied.get(0);
        }
        Executable specific = mostSpecific(tied);
        if (specific != null) {
            return specific;
        }
        throw new LuaException(ambiguityMessage(tied, args.length));
    }

    /** Total candidate score, or {@link #INCOMPATIBLE} when any argument fails. */
    private static int score(Executable e, LuaValue[] args) {
        Class<?>[] paramTypes = e.getParameterTypes();
        boolean varArgs = e.isVarArgs();
        if (!varArgs) {
            if (paramTypes.length != args.length) {
                return INCOMPATIBLE;
            }
            int total = 0;
            for (int i = 0; i < paramTypes.length; i++) {
                int s = scoreArg(paramTypes[i], args[i]);
                if (s == INCOMPATIBLE) {
                    return INCOMPATIBLE;
                }
                total += s;
            }
            return total;
        }
        int fixed = paramTypes.length - 1;
        if (args.length < fixed) {
            return INCOMPATIBLE;
        }
        int total = 0;
        for (int i = 0; i < fixed; i++) {
            int s = scoreArg(paramTypes[i], args[i]);
            if (s == INCOMPATIBLE) {
                return INCOMPATIBLE;
            }
            total += s;
        }
        Class<?> component = paramTypes[fixed].getComponentType();
        for (int i = fixed; i < args.length; i++) {
            int s = scoreArg(component, args[i]);
            if (s == INCOMPATIBLE) {
                return INCOMPATIBLE;
            }
            total += s;
        }
        return total - S_VARARGS_PENALTY;
    }

    /**
     * Score one Lua value against one declared parameter type. Package-private
     * so tests and diagnostics can reuse it; the contract is
     * {@link #INCOMPATIBLE} for a value the target cannot take at all, and a
     * positive closeness score otherwise (higher is closer).
     */
    static int scoreArg(Class<?> target, LuaValue val) {
        if (val == null || val.isNil()) {
            return target.isPrimitive() ? S_NULL_PRIM : S_NULL_REF;
        }

        if (target == Object.class) {
            return S_OBJECT;
        }

        // Exact runtime-type matches first. This also covers a Number target
        // when the LuaValue were a Java-backed wrapper; Lua numbers themselves
        // are not Java instances, so the numeric scores follow.
        if (target.isInstance(val)) {
            return fromDistance(target, val.getClass());
        }

        if (val.isInteger()) {
            return scoreInteger(target, val.toLong());
        }
        if (val.isFloat()) {
            return scoreFloat(target, val.toDouble());
        }

        if (val.isBoolean()) {
            if (target == boolean.class) {
                return S_EXACT;
            }
            if (target == Boolean.class) {
                return S_EXACT - 1;
            }
            return INCOMPATIBLE;
        }

        if (val.isString()) {
            if (target == String.class) {
                return S_EXACT;
            }
            if (target.isEnum()) {
                return 80;
            }
            if (target == char.class) {
                return 70;
            }
            if (target == Character.class) {
                return 69;
            }
            // CharSequence, Comparable, Serializable, ... Accept via the
            // String implementation hierarchy and score by distance.
            if (target.isAssignableFrom(String.class)) {
                return fromDistance(target, String.class);
            }
            return INCOMPATIBLE;
        }

        if (val.isFunction()) {
            if (target.isInterface() && LuaDataConverter.findSingleAbstractMethod(target) != null) {
                return 94;
            }
            return INCOMPATIBLE;
        }

        if (val.isTable()) {
            if (target.isArray() || target == List.class || target == Collection.class
                    || target == Set.class || target == Map.class) {
                return 80;
            }
            return INCOMPATIBLE;
        }

        if (val.isUserdata()) {
            Object inst = ((LuaUserdata) val).getJavaInstance();
            if (inst != null && target.isInstance(inst)) {
                return fromDistance(target, inst.getClass());
            }
        }

        return INCOMPATIBLE;
    }

    /**
     * Numeric score for an integral Lua value.
     *
     * <p>Tiers follow Java's invocation phases, but keyed on the value's
     * range rather than a static type:
     * <ol>
     *   <li>exact: {@code int}/{@code Integer} when the value fits (a Lua
     *       integer in {@code int} range is the closest thing to an exact
     *       match), otherwise {@code long};</li>
     *   <li>widening, in Java's most-specific order: {@code long}, then
     *       {@code float}, then {@code double};</li>
     *   <li>boxing of those;</li>
     *   <li>narrowing to {@code short}/{@code byte}/{@code char}, which Java
     *       would reject outright and Lua accepts explicitly, so they are
     *       applicable but rank last.</li>
     * </ol>
     * {@code BigInteger} sits with the boxing tier (exact, reference form) and
     * {@code Number} just below it.
     */
    private static int scoreInteger(Class<?> target, long v) {
        if (target == int.class) {
            return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? S_EXACT : INCOMPATIBLE;
        }
        if (target == Integer.class) {
            return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? 98 : INCOMPATIBLE;
        }
        if (target == long.class) {
            return 92;
        }
        if (target == Long.class) {
            return 90;
        }
        if (target == float.class) {
            return 84;
        }
        if (target == Float.class) {
            return 82;
        }
        if (target == double.class) {
            return 80;
        }
        if (target == Double.class) {
            return 78;
        }
        if (target == BigInteger.class) {
            return 88;
        }
        if (target == BigDecimal.class) {
            return 76;
        }
        if (target == Number.class) {
            return 55;
        }
        if (target == short.class) {
            return (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) ? 45 : INCOMPATIBLE;
        }
        if (target == Short.class) {
            return (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) ? 43 : INCOMPATIBLE;
        }
        if (target == byte.class) {
            return (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) ? 40 : INCOMPATIBLE;
        }
        if (target == Byte.class) {
            return (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) ? 38 : INCOMPATIBLE;
        }
        if (target == char.class) {
            return (v >= 0 && v <= 0xFFFF) ? 35 : INCOMPATIBLE;
        }
        if (target == Character.class) {
            return (v >= 0 && v <= 0xFFFF) ? 33 : INCOMPATIBLE;
        }
        return INCOMPATIBLE;
    }

    private static int scoreFloat(Class<?> target, double v) {
        if (target == double.class) {
            return S_EXACT;
        }
        if (target == Double.class) {
            return 98;
        }
        if (target == float.class) {
            return 90;
        }
        if (target == Float.class) {
            return 88;
        }
        if (target == BigDecimal.class) {
            return 85;
        }
        if (target == Number.class) {
            return 55;
        }
        // Java would not implicitly narrow a double to an integral type; Lua
        // historically allowed it via truncation, so keep it applicable but
        // clearly unattractive against any floating-point overload. The value
        // must still fit the target, otherwise conversion would silently wrap
        // (e.g. 2^31 into an int), and an integer value of the same magnitude
        // is already rejected by scoreInteger -- the two paths must agree.
        if (target == byte.class || target == Byte.class) {
            return fitsIntegral(v, Byte.MIN_VALUE, Byte.MAX_VALUE) ? 30 : INCOMPATIBLE;
        }
        if (target == short.class || target == Short.class) {
            return fitsIntegral(v, Short.MIN_VALUE, Short.MAX_VALUE) ? 30 : INCOMPATIBLE;
        }
        if (target == int.class || target == Integer.class) {
            return fitsIntegral(v, Integer.MIN_VALUE, Integer.MAX_VALUE) ? 30 : INCOMPATIBLE;
        }
        if (target == long.class || target == Long.class) {
            return (v == v && v >= -0x1p63 && v < 0x1p63) ? 30 : INCOMPATIBLE;
        }
        return INCOMPATIBLE;
    }

    /** True when {@code v} is finite and lies within {@code [min, max]}. */
    private static boolean fitsIntegral(double v, long min, long max) {
        return v == v && v != Double.POSITIVE_INFINITY && v != Double.NEGATIVE_INFINITY
                && v >= min && v <= max;
    }

    /** {@code 100 - distance} for an assignable target, never below {@code 1}. */
    private static int fromDistance(Class<?> target, Class<?> actual) {
        int d = distance(target, actual);
        if (d < 0) {
            return 85;
        }
        return Math.max(1, S_EXACT - d);
    }

    /**
     * Number of superclass/interface edges from {@code actual} up to
     * {@code target}, or {@code -1} when {@code actual} is not a subtype.
     * Cached because overload scoring runs per call on the uncached lanes.
     */
    static int distance(Class<?> target, Class<?> actual) {
        if (target == actual) {
            return 0;
        }
        if (!target.isAssignableFrom(actual)) {
            return -1;
        }
        Map<Class<?>, Integer> perTarget = DISTANCE_CACHE.computeIfAbsent(target, k -> new ConcurrentHashMap<>());
        Integer cached = perTarget.get(actual);
        if (cached != null) {
            return cached;
        }
        int computed = computeDistance(target, actual);
        perTarget.put(actual, computed);
        return computed;
    }

    private static int computeDistance(Class<?> target, Class<?> actual) {
        int depth = 0;
        List<Class<?>> level = new ArrayList<>();
        level.add(actual);
        while (!level.isEmpty()) {
            depth++;
            List<Class<?>> next = new ArrayList<>();
            for (Class<?> c : level) {
                Class<?> sup = c.getSuperclass();
                if (sup == target) {
                    return depth;
                }
                if (sup != null) {
                    next.add(sup);
                }
                for (Class<?> itf : c.getInterfaces()) {
                    if (itf == target) {
                        return depth;
                    }
                    next.add(itf);
                }
            }
            level = next;
        }
        return -1;
    }

    /** Removes duplicate signatures so bridge/covariant methods do not fake a tie. */
    private static void dedupe(List<Executable> tied) {
        for (int i = 0; i < tied.size(); i++) {
            Executable a = tied.get(i);
            for (int j = tied.size() - 1; j > i; j--) {
                Executable b = tied.get(j);
                if (sameSignature(a, b)) {
                    tied.remove(j);
                }
            }
        }
    }

    private static boolean sameSignature(Executable a, Executable b) {
        return a.getName().equals(b.getName())
                && a.isVarArgs() == b.isVarArgs()
                && Arrays.equals(a.getParameterTypes(), b.getParameterTypes());
    }

    /** Returns the single candidate that dominates every other tied one, else null. */
    private static Executable mostSpecific(List<Executable> tied) {
        for (Executable candidate : tied) {
            boolean dominatesAll = true;
            for (Executable other : tied) {
                if (candidate == other) {
                    continue;
                }
                if (!moreSpecific(candidate, other)) {
                    dominatesAll = false;
                    break;
                }
            }
            if (dominatesAll) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean moreSpecific(Executable a, Executable b) {
        Class<?>[] ap = a.getParameterTypes();
        Class<?>[] bp = b.getParameterTypes();
        if (ap.length != bp.length) {
            return false;
        }
        for (int i = 0; i < ap.length; i++) {
            if (!isSubtype(ap[i], bp[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSubtype(Class<?> a, Class<?> b) {
        if (a == b) {
            return true;
        }
        if (b.isAssignableFrom(a)) {
            return true;
        }
        return primitiveWidening(a, b);
    }

    private static boolean primitiveWidening(Class<?> from, Class<?> to) {
        if (!from.isPrimitive() || !to.isPrimitive()) {
            return false;
        }
        if (from == byte.class) {
            return to == short.class || to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == short.class) {
            return to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == char.class) {
            return to == int.class || to == long.class || to == float.class || to == double.class;
        }
        if (from == int.class) {
            return to == long.class || to == float.class || to == double.class;
        }
        if (from == long.class) {
            return to == float.class || to == double.class;
        }
        if (from == float.class) {
            return to == double.class;
        }
        return false;
    }

    private static String ambiguityMessage(List<Executable> tied, int argc) {
        StringBuilder sb = new StringBuilder("ambiguous call: ")
                .append(argc)
                .append(" argument(s) match ");
        for (int i = 0; i < tied.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tied.get(i).getName()).append(describe(tied.get(i)));
        }
        return sb.toString();
    }

    private static String describe(Executable e) {
        return "(" + Arrays.toString(e.getParameterTypes()) + ")";
    }
}

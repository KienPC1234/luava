/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class TableLib {
    private TableLib() {}

    private static long luaLen(LuaValue val) {
        LuaValue l = val.len();
        if (!l.isInteger()) {
            throw new LuaException("object length is not an integer");
        }
        return l.toLong();
    }

    /**
     * {@code luaL_checkinteger} for the table library: coerces a numeric
     * string (lua_tointegerx) and reports the argument index and PUC function
     * name. {@code func} is the unqualified name (e.g. "concat", "unpack").
     */
    static long checkInteger(LuaValue[] args, int idx, String func) {
        if (idx >= args.length || args[idx].isNil()) {
            throw LuaValue.argError(idx + 1, func, "number expected, got " + (idx >= args.length ? "no value" : "nil"));
        }
        LuaValue v = args[idx];
        LuaInteger i = v.toLuaIntegerCoercingStrings();
        if (i != null) return i.toLong();
        throw LuaValue.argError(idx + 1, func, v.integerConversionError());
    }

    public static void open(LuaTable globals) {
        LuaTable tableMod = new LuaTable();
        fillInto(tableMod, globals);
        globals.rawset(LuaString.interned("table"), tableMod);
    }

    public static void fillInto(LuaTable tableMod, LuaTable globals) {

        tableMod.rawset(LuaString.interned("insert"), LuaFunction.of(args -> {
            // PUC order: aux_getn validates argument #1 (table expected) BEFORE
            // the arity switch, so table.insert() blames argument #1 with
            // "got no value" and only 4+ args report the arity error.
            if (args.length == 0 || !args[0].isTable()) {
                throw LuaValue.argError(1, "insert", "table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || args.length > 3) {
                throw new LuaException("wrong number of arguments to 'insert'");
            }
            LuaTable t = (LuaTable) args[0];
            long len = luaLen(t);
            long pos;
            LuaValue val;
            if (args.length == 2) {
                pos = len + 1;
                val = args[1];
            } else {
                pos = checkInteger(args, 1, "insert");
                if (pos < 1 || pos > len + 1) {
                    throw LuaValue.argError(2, "insert", "position out of bounds");
                }
                val = args[2];
                for (long i = len + 1; i > pos; i--) {
                    t.set(LuaInteger.valueOf(i), t.get(LuaInteger.valueOf(i - 1)));
                }
            }
            t.set(LuaInteger.valueOf(pos), val);
            // PUC's table.insert returns zero values.
            return org.luava.runtime.Varargs.EMPTY;
        }));

        tableMod.rawset(LuaString.interned("remove"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw LuaValue.argError(1, "remove", "table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            LuaTable t = (LuaTable) args[0];
            long len = luaLen(t);
            long pos = (args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "remove") : len;
            if (pos != len && (pos < 1 || pos > len + 1)) {
                throw LuaValue.argError(2, "remove", "position out of bounds");
            }
            LuaValue removed = t.get(LuaInteger.valueOf(pos));
            for (; pos < len; pos++) {
                t.set(LuaInteger.valueOf(pos), t.get(LuaInteger.valueOf(pos + 1)));
            }
            t.set(LuaInteger.valueOf(pos), LuaNil.NIL);
            return removed;
        }));

        tableMod.rawset(LuaString.interned("concat"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw LuaValue.argError(1, "concat", "table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            LuaTable t = (LuaTable) args[0];
            String sep = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "";
            long i = (args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "concat") : 1;
            long last = (args.length > 3 && !args[3].isNil()) ? checkInteger(args, 3, "concat") : luaLen(t);

            // Plain-table fast lane: no metatable means get() == rawget, so
            // read the array part directly (no per-element key boxing, no
            // hash probe) with a presized buffer. Falls through to the
            // generic path for metatables or exotic ranges.
            if (t.getMetatable() == null && i >= 1 && last >= i - 1 && last <= Integer.MAX_VALUE) {
                long count = last - i + 1;
                int cap = count > (1 << 20) / 8 ? (1 << 20) : (int) (count * 8);
                StringBuilder sb = new StringBuilder(Math.max(16, cap));
                for (long idx = i; idx <= last; idx++) {
                    LuaValue val = t.rawgetInt(idx);
                    if (!val.isString() && !val.isNumber()) {
                        throw new LuaException("invalid value (" + val.typeName() + ") at index " + idx + " in table for 'concat'");
                    }
                    if (idx > i) sb.append(sep);
                    sb.append(val.toLuaString());
                }
                return LuaString.valueOf(sb.toString());
            }

            StringBuilder sb = new StringBuilder();
            for (; i < last; i++) {
                LuaValue val = t.get(LuaInteger.valueOf(i));
                if (!val.isString() && !val.isNumber()) {
                    throw new LuaException("invalid value (" + val.typeName() + ") at index " + i + " in table for 'concat'");
                }
                sb.append(val.toLuaString()).append(sep);
            }
            if (i == last) {
                LuaValue val = t.get(LuaInteger.valueOf(i));
                if (!val.isString() && !val.isNumber()) {
                    throw new LuaException("invalid value (" + val.typeName() + ") at index " + i + " in table for 'concat'");
                }
                sb.append(val.toLuaString());
            }
            return LuaString.valueOf(sb.toString());
        }));

        tableMod.rawset(LuaString.interned("unpack"), LuaFunction.of(args -> {
            // PUC's tunpack uses luaL_len(L,1), so any value with a length
            // works: a string unpacks its bytes, and a non-length value raises
            // "attempt to get length of a X value" rather than a type error.
            if (args.length == 0) {
                throw LuaValue.argError(1, "unpack", "table expected, got no value");
            }
            LuaValue t = args[0];
            long start = (args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "unpack") : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "unpack") : luaLen(t);

            if (start > end) return Varargs.EMPTY;
            long count;
            try {
                count = Math.addExact(Math.subtractExact(end, start), 1);
            } catch (ArithmeticException ex) {
                throw new LuaException("too many results to unpack");
            }
            if (count >= 1000000 || count <= 0) {
                throw new LuaException("too many results to unpack");
            }
            int icount = (int) count;
            LuaValue[] result = new LuaValue[icount];
            for (int i = 0; i < icount; i++) {
                result[i] = t.get(LuaInteger.valueOf(start + i));
            }
            return Varargs.of(result);
        }));

        tableMod.rawset(LuaString.interned("pack"), LuaFunction.of(args -> {
            LuaTable t = new LuaTable();
            for (int i = 0; i < args.length; i++) {
                t.rawset(LuaInteger.valueOf(i + 1), args[i]);
            }
            t.rawset(LuaString.interned("n"), LuaInteger.valueOf(args.length));
            return t;
        }));

        tableMod.rawset(LuaString.interned("move"), LuaFunction.of(args -> {
            // PUC's tmove checks arguments #2/#3/#4 (integers) before the
            // tables, so table.move() blames argument #2, not #1.
            long f = checkInteger(args, 1, "move");
            long e = checkInteger(args, 2, "move");
            long t = checkInteger(args, 3, "move");
            if (args.length < 1 || !args[0].isTable()) {
                throw LuaValue.argError(1, "move", "table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            LuaTable a1 = (LuaTable) args[0];
            LuaTable a2;
            if (args.length > 4 && !args[4].isNil()) {
                if (!args[4].isTable()) {
                    throw LuaValue.argError(5, "move", "table expected, got " + args[4].typeName());
                }
                a2 = (LuaTable) args[4];
            } else {
                a2 = a1;
            }

            if (e >= f) {
                if (f <= 0 && e >= Long.MAX_VALUE + f) {
                    throw LuaValue.argError(3, "move", "too many elements to move");
                }
                long n = e - f + 1;
                if (t > Long.MAX_VALUE - n + 1) {
                    throw LuaValue.argError(4, "move", "destination wrap around");
                }
                if (t > f && t <= e && a1 == a2) {
                    for (long i = n - 1; i >= 0; i--) {
                        if ((i & 0xFF) == 0) org.luava.runtime.LuaState.checkGuard();
                        a2.set(LuaInteger.valueOf(t + i), a1.get(LuaInteger.valueOf(f + i)));
                    }
                } else {
                    for (long i = 0; i < n; i++) {
                        if ((i & 0xFF) == 0) org.luava.runtime.LuaState.checkGuard();
                        a2.set(LuaInteger.valueOf(t + i), a1.get(LuaInteger.valueOf(f + i)));
                    }
                }
            }
            return a2;
        }));

        tableMod.rawset(LuaString.interned("sort"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw LuaValue.argError(1, "sort", "table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            LuaTable t = (LuaTable) args[0];
            if (args.length > 1 && !args[1].isNil() && !args[1].isFunction()) {
                throw LuaValue.argError(2, "sort", "function expected, got " + args[1].typeName());
            }
            LuaFunction comp = (args.length > 1 && !args[1].isNil()) ? (LuaFunction) args[1] : null;
            long lenLong = luaLen(t);
            if (lenLong <= 1) {
                // PUC's table.sort returns zero values.
                return org.luava.runtime.Varargs.EMPTY;
            }
            if (lenLong > Integer.MAX_VALUE - 2) {
                throw LuaValue.argError(1, "sort", "array too big");
            }
            int len = (int) lenLong;
            List<LuaValue> items = new ArrayList<>(len);
            for (int i = 1; i <= len; i++) {
                items.add(t.get(LuaInteger.valueOf(i)));
            }

            org.luava.runtime.concurrency.LuaCoroutine curCoro = org.luava.runtime.concurrency.LuaCoroutine.running();
            if (curCoro != null) curCoro.enterNonYieldable();
            try {
                // Faithful port of C ltablib.c auxsort/partition: one comparator
                // call per test, same invalid-order detection, same pivot logic.
                auxsort(items, 1, len, comp, new int[]{0});
            } finally {
                if (curCoro != null) curCoro.exitNonYieldable();
            }

            for (int i = 0; i < len; i++) {
                t.set(LuaInteger.valueOf(i + 1), items.get(i));
            }
            // PUC's table.sort returns zero values.
            return org.luava.runtime.Varargs.EMPTY;
        }));
    }

    // ---- Quicksort ported from PUC-Rio ltablib.c (1-based indices) ----

    private static final int SORT_RANLIMIT = 100;

    private static boolean sortComp(LuaValue a, LuaValue b, LuaFunction comp) {
        if (comp == null) return a.luaLessThan(b);
        // PUC invokes the comparator through lua_call; LuaFunction.call pushes
        // the nameless C frame that luaL_argerror needs (a comparator passed as
        // table.sort itself then resolves to 'table.sort', not the enclosing
        // call-site name 'sort').
        return comp.call(a, b).toBoolean();
    }

    private static void swapItems(java.util.List<LuaValue> a, int i, int j) {
        LuaValue tmp = a.get(i - 1);
        a.set(i - 1, a.get(j - 1));
        a.set(j - 1, tmp);
    }

    private static int partition(java.util.List<LuaValue> a, int lo, int up, LuaFunction comp) {
        LuaValue pivot = a.get(up - 2); // a[up-1] after the pivot swap
        int i = lo;
        int j = up - 1;
        for (;;) {
            while (sortComp(a.get(++i - 1), pivot, comp)) {
                if (i == up - 1) {
                    throw new LuaException("invalid order function for sorting");
                }
            }
            while (sortComp(pivot, a.get(--j - 1), comp)) {
                if (j < i) {
                    throw new LuaException("invalid order function for sorting");
                }
            }
            if (j < i) {
                a.set(up - 2, a.get(i - 1));
                a.set(i - 1, pivot);
                return i;
            }
            swapItems(a, i, j);
        }
    }

    private static int choosePivot(int lo, int up, int rnd) {
        int r4 = (up - lo) / 4;
        return (int) (Integer.toUnsignedLong(rnd) % (r4 * 2) + (lo + r4));
    }

    private static int randomizePivot() {
        long n = System.nanoTime() + 0x9E3779B97F4A7C15L * System.currentTimeMillis();
        return (int) (n ^ (n >>> 32));
    }

    private static void auxsort(java.util.List<LuaValue> a, int lo, int up, LuaFunction comp, int[] rnd) {
        while (lo < up) {
            if (sortComp(a.get(up - 1), a.get(lo - 1), comp)) {
                swapItems(a, lo, up);
            }
            if (up - lo == 1) return;
            int p;
            if (up - lo < SORT_RANLIMIT || rnd[0] == 0) {
                p = (lo + up) / 2;
            } else {
                p = choosePivot(lo, up, rnd[0]);
            }
            if (sortComp(a.get(p - 1), a.get(lo - 1), comp)) {
                swapItems(a, p, lo);
            } else if (sortComp(a.get(up - 1), a.get(p - 1), comp)) {
                swapItems(a, p, up);
            }
            if (up - lo == 2) return;
            LuaValue pivot = a.get(p - 1);
            a.set(p - 1, a.get(up - 2));
            a.set(up - 2, pivot);
            p = partition(a, lo, up, comp);
            int n;
            if (p - lo < up - p) {
                auxsort(a, lo, p - 1, comp, rnd);
                n = p - lo;
                lo = p + 1;
            } else {
                auxsort(a, p + 1, up, comp, rnd);
                n = up - p;
                up = p - 1;
            }
            if ((up - lo) / 128 > n) {
                rnd[0] = randomizePivot();
            }
        }
    }
}

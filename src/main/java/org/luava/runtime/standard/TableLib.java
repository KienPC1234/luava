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

    public static void open(LuaTable globals) {
        LuaTable tableMod = new LuaTable();

        tableMod.rawset(LuaString.valueOf("insert"), LuaFunction.of(args -> {
            if (args.length < 2 || args.length > 3) {
                throw new LuaException("wrong number of arguments to 'table.insert'");
            }
            if (!args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.insert' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable t = (LuaTable) args[0];
            long len = luaLen(t);
            long pos;
            LuaValue val;
            if (args.length == 2) {
                pos = len + 1;
                val = args[1];
            } else {
                pos = args[1].toLong();
                if (pos < 1 || pos > len + 1) {
                    throw new LuaException("bad argument #2 to 'table.insert' (position out of bounds)");
                }
                val = args[2];
                for (long i = len + 1; i > pos; i--) {
                    t.set(LuaInteger.valueOf(i), t.get(LuaInteger.valueOf(i - 1)));
                }
            }
            t.set(LuaInteger.valueOf(pos), val);
            return LuaNil.NIL;
        }));

        tableMod.rawset(LuaString.valueOf("remove"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.remove' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable t = (LuaTable) args[0];
            long len = luaLen(t);
            long pos = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : len;
            if (pos != len && (pos < 1 || pos > len + 1)) {
                throw new LuaException("bad argument #2 to 'table.remove' (position out of bounds)");
            }
            LuaValue removed = t.get(LuaInteger.valueOf(pos));
            for (; pos < len; pos++) {
                t.set(LuaInteger.valueOf(pos), t.get(LuaInteger.valueOf(pos + 1)));
            }
            t.set(LuaInteger.valueOf(pos), LuaNil.NIL);
            return removed;
        }));

        tableMod.rawset(LuaString.valueOf("concat"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.concat' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable t = (LuaTable) args[0];
            String sep = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "";
            long i = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : 1;
            long last = (args.length > 3 && !args[3].isNil()) ? args[3].toLong() : luaLen(t);

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

        tableMod.rawset(LuaString.valueOf("unpack"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.unpack' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable t = (LuaTable) args[0];
            long start = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : luaLen(t);

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

        tableMod.rawset(LuaString.valueOf("pack"), LuaFunction.of(args -> {
            LuaTable t = new LuaTable();
            for (int i = 0; i < args.length; i++) {
                t.rawset(LuaInteger.valueOf(i + 1), args[i]);
            }
            t.rawset(LuaString.valueOf("n"), LuaInteger.valueOf(args.length));
            return t;
        }));

        tableMod.rawset(LuaString.valueOf("move"), LuaFunction.of(args -> {
            if (args.length < 4 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.move' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable a1 = (LuaTable) args[0];
            long f = args[1].toLong();
            long e = args[2].toLong();
            long t = args[3].toLong();
            LuaTable a2;
            if (args.length > 4 && !args[4].isNil()) {
                if (!args[4].isTable()) {
                    throw new LuaException("bad argument #5 to 'table.move' (table expected, got " + args[4].typeName() + ")");
                }
                a2 = (LuaTable) args[4];
            } else {
                a2 = a1;
            }

            if (e >= f) {
                if (f <= 0 && e >= Long.MAX_VALUE + f) {
                    throw new LuaException("bad argument #3 to 'table.move' (too many elements to move)");
                }
                long n = e - f + 1;
                if (t > Long.MAX_VALUE - n + 1) {
                    throw new LuaException("bad argument #4 to 'table.move' (destination wrap around)");
                }
                if (t > f && t <= e && a1 == a2) {
                    for (long i = n - 1; i >= 0; i--) {
                        a2.set(LuaInteger.valueOf(t + i), a1.get(LuaInteger.valueOf(f + i)));
                    }
                } else {
                    for (long i = 0; i < n; i++) {
                        a2.set(LuaInteger.valueOf(t + i), a1.get(LuaInteger.valueOf(f + i)));
                    }
                }
            }
            return a2;
        }));

        tableMod.rawset(LuaString.valueOf("sort"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'table.sort' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            LuaTable t = (LuaTable) args[0];
            if (args.length > 1 && !args[1].isNil() && !args[1].isFunction()) {
                throw new LuaException("bad argument #2 to 'table.sort' (function expected, got "
                        + args[1].typeName() + ")");
            }
            LuaFunction comp = (args.length > 1 && !args[1].isNil()) ? (LuaFunction) args[1] : null;
            long lenLong = luaLen(t);
            if (lenLong <= 1) {
                return LuaNil.NIL;
            }
            if (lenLong > Integer.MAX_VALUE - 2) {
                throw new LuaException("bad argument #1 to 'table.sort' (array too big)");
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
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("table"), tableMod);
    }

    // ---- Quicksort ported from PUC-Rio ltablib.c (1-based indices) ----

    private static final int SORT_RANLIMIT = 100;

    private static boolean sortComp(LuaValue a, LuaValue b, LuaFunction comp) {
        if (comp == null) return a.luaLessThan(b);
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

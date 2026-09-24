/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LuaTable extends LuaValue {
    private final Map<LuaValue, LuaValue> hashPart = new LinkedHashMap<>();
    private final ArrayList<LuaValue> arrayPart = new ArrayList<>();
    private LuaTable metatable;
    // Cold-start hook: installed only on standard-library placeholder tables
    // (null for every ordinary table). Plain tables pay one predictable
    // null-check; the class stays final so JIT devirtualization is intact.
    //
    // Plain (non-volatile) by design: every other field here (arrayPart,
    // hashPart, metatable, modCount) is already plain, so the table is
    // thread-confined like the rest of the engine — the volatile bought
    // nothing. The synchronized fill-once below preserves exactly-once
    // filling; fillers are idempotent stdlib initializers, so even a stale
    // re-entry is harmless.
    private Runnable lazyFiller;

    public void setLazyFiller(Runnable filler) {
        this.lazyFiller = filler;
    }

    void ensureFilled() {
        if (lazyFiller != null) {
            ensureFilledSlow();
        }
    }

    private void ensureFilledSlow() {
        Runnable f = lazyFiller;
        if (f != null) {
            synchronized (this) {
                f = lazyFiller;
                if (f != null) {
                    lazyFiller = null;
                    f.run();
                }
            }
        }
    }

    public LuaTable() {
        org.luava.runtime.eval.GCManager.onAlloc();
    }

    public LuaTable(int arrayCapacity, int hashCapacity) {
        if (arrayCapacity > 0) {
            arrayPart.ensureCapacity(arrayCapacity);
        }
        org.luava.runtime.eval.GCManager.onAlloc();
    }

    @Override
    public LuaType type() {
        return LuaType.TABLE;
    }

    @Override
    public boolean isTable() {
        return true;
    }

    private boolean weakKeys = false;
    private boolean weakValues = false;
    private int weakQueryCount = 0;
    /**
     * Read-version counter bumped by every mutation that can change read
     * results ({@code set}/{@code rawset}/{@code rawsetInt}/
     * {@code setMetatable}). Powers the VM's {@code OP_GETTABUP} site cache:
     * {@link #readVersion()} returns -1 while a metatable could affect reads
     * (uncacheable), otherwise the counter, so any store through any path
     * invalidates cached lookups.
     */
    private long modCount = 0;

    /** VM site-cache guard; public for the bytecode package. */
    public long readVersion() {
        return metatable == null ? modCount : -1L;
    }
    private transient Iterator<Map.Entry<LuaValue, LuaValue>> nextIterator = null;
    private transient LuaValue lastReturnedKey = null;

    private static final class WeakVal extends LuaValue {
        final java.lang.ref.WeakReference<LuaValue> ref;

        WeakVal(LuaValue val) {
            this.ref = new java.lang.ref.WeakReference<>(val);
        }

        LuaValue get() {
            return ref.get();
        }

        void clear() {
            ref.clear();
        }

        @Override
        public LuaType type() {
            LuaValue v = ref.get();
            return v != null ? v.type() : LuaType.NIL;
        }

        @Override
        public String toLuaString() {
            LuaValue v = ref.get();
            return v != null ? v.toLuaString() : "nil";
        }
    }

    public static final class WeakKey extends LuaValue {
        final java.lang.ref.WeakReference<LuaValue> ref;
        final int hash;

        WeakKey(LuaValue key) {
            this.ref = new java.lang.ref.WeakReference<>(key);
            this.hash = key.hashCode();
        }

        LuaValue get() {
            return ref.get();
        }

        void clear() {
            ref.clear();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            LuaValue actual = ref.get();
            if (actual == null) return false;
            if (obj instanceof WeakKey wk) {
                LuaValue other = wk.ref.get();
                return other != null && actual.equals(other);
            }
            return actual.equals(obj);
        }

        @Override
        public LuaType type() {
            LuaValue actual = ref.get();
            return actual != null ? actual.type() : LuaType.NIL;
        }

        @Override
        public String toLuaString() {
            LuaValue actual = ref.get();
            return actual != null ? actual.toLuaString() : "nil";
        }
    }

    public boolean isWeakKeys() {
        return weakKeys;
    }

    public boolean isWeakValues() {
        return weakValues;
    }

    public static boolean isCollectable(LuaValue val) {
        return val != null && (val.isTable() || val.isFunction() || val.isUserdata() || val instanceof org.luava.runtime.concurrency.LuaCoroutine);
    }

    public void updateWeakMode() {
        if (metatable != null) {
            LuaValue mode = metatable.rawget(LuaValue.Meta.MODE);
            if (mode.isString()) {
                String s = mode.toLuaString();
                weakKeys = s.contains("k");
                weakValues = s.contains("v");
                if (weakKeys || weakValues) {
                    org.luava.runtime.eval.GCManager.registerWeakTable(this);
                    convertToWeak();
                }
                return;
            }
        }
        weakKeys = false;
        weakValues = false;
    }

    private void convertToWeak() {
        if (weakValues) {
            for (int i = 0; i < arrayPart.size(); i++) {
                LuaValue v = arrayPart.get(i);
                if (v != null && isCollectable(v) && !(v instanceof WeakVal)) {
                    arrayPart.set(i, new WeakVal(v));
                }
            }
            for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
                LuaValue v = entry.getValue();
                if (v != null && isCollectable(v) && !(v instanceof WeakVal)) {
                    entry.setValue(new WeakVal(v));
                }
            }
        }
        if (weakKeys) {
            Map<LuaValue, LuaValue> newHash = new LinkedHashMap<>();
            for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
                LuaValue k = entry.getKey();
                if (k != null && isCollectable(k) && !(k instanceof WeakKey)) {
                    newHash.put(new WeakKey(k), entry.getValue());
                } else {
                    newHash.put(k, entry.getValue());
                }
            }
            hashPart.clear();
            hashPart.putAll(newHash);
        }
    }

    public void cleanupWeak() {
        cleanupWeakValues(null);
        cleanupWeakKeys(null);
    }

    public void cleanupWeakValues(java.util.function.Predicate<LuaValue> isAlive) {
        if (!weakValues) return;
        for (int i = 0; i < arrayPart.size(); i++) {
            LuaValue v = arrayPart.get(i);
            if (v instanceof WeakVal wv) {
                LuaValue actual = wv.get();
                if (actual == null || (isAlive != null && isCollectable(actual) && !isAlive.test(actual))) {
                    wv.clear();
                    arrayPart.set(i, LuaNil.NIL);
                }
            }
        }
        hashPart.entrySet().removeIf(entry -> {
            LuaValue v = entry.getValue();
            if (v instanceof WeakVal wv) {
                LuaValue actual = wv.get();
                if (actual == null || (isAlive != null && isCollectable(actual) && !isAlive.test(actual))) {
                    wv.clear();
                    return true;
                }
            }
            return false;
        });
    }

    public void cleanupWeakKeys(java.util.function.Predicate<LuaValue> isAlive) {
        if (!weakKeys) return;
        hashPart.entrySet().removeIf(entry -> {
            LuaValue k = entry.getKey();
            if (k instanceof WeakKey wk) {
                LuaValue actual = wk.get();
                if (actual == null || (isAlive != null && isCollectable(actual) && !isAlive.test(actual))) {
                    wk.clear();
                    return true;
                }
            }
            return false;
        });
    }

    public void forEachKey(java.util.function.Consumer<LuaValue> consumer) {
        for (int i = 0; i < arrayPart.size(); i++) {
            consumer.accept(LuaInteger.valueOf(i + 1));
        }
        for (LuaValue k : hashPart.keySet()) {
            if (k instanceof WeakKey wk) k = wk.get();
            if (k != null && !k.isNil()) {
                consumer.accept(k);
            }
        }
    }

    @Override
    public LuaTable getMetatable() {
        return metatable;
    }

    @Override
    public void setMetatable(LuaTable mt) {
        this.metatable = mt;
        modCount++;
        updateWeakMode();
    }

    private static LuaValue normalizeKey(LuaValue key) {
        if (key != null && key.isFloat()) {
            double d = key.toDouble();
            if (!Double.isInfinite(d) && !Double.isNaN(d) && d >= (double) Long.MIN_VALUE && d < 9223372036854775808.0) {
                long l = (long) d;
                if ((double) l == d) {
                    return LuaInteger.valueOf(l);
                }
            }
        }
        return key;
    }

    /**
     * Integer-keyed fast lanes for the VM ({@code OP_GETI} / {@code OP_SETI}).
     * Identical semantics to {@code rawget(LuaInteger)}/{@code rawset} for
     * plain tables, but without boxing the index. Callers must only use
     * these when the table has no metatable (weak modes always imply one).
     */
    public LuaValue rawgetInt(long idx) {
        if (lazyFiller != null) {
            ensureFilledSlow();
        }
        if (idx >= 1 && idx <= arrayPart.size()) {
            LuaValue val = arrayPart.get((int) (idx - 1));
            if (val instanceof WeakVal wv) {
                LuaValue actual = wv.get();
                if (actual == null) {
                    arrayPart.set((int) (idx - 1), LuaNil.NIL);
                    return LuaNil.NIL;
                }
                return actual;
            }
            if (val != null && !val.isNil()) {
                return val;
            }
        }
        return rawget(LuaInteger.valueOf(idx));
    }

    public void rawsetInt(long idx, LuaValue value) {
        if (lazyFiller != null) {
            ensureFilledSlow();
        }
        // No version bump: integer writes never change string-key reads (the
        // only shape the VM site-caches), and only drop integer shadows from
        // the hash part. The rawset() fallthrough below bumps as usual.
        // Reference-compare nil first: the virtual isNil() is pure overhead
        // for the 99.99% of values that are neither null nor exotic-nil.
        LuaValue toSet = (value == null || value == LuaNil.NIL || value.isNil()) ? LuaNil.NIL : value;
        if (idx == arrayPart.size() + 1 && toSet != LuaNil.NIL) {
            arrayPart.add(toSet);
            if (!hashPart.isEmpty()) hashPart.remove(LuaInteger.valueOf(idx));
            return;
        } else if (idx >= 1 && idx <= arrayPart.size()) {
            if (!hashPart.isEmpty()) hashPart.remove(LuaInteger.valueOf(idx));
            arrayPart.set((int) (idx - 1), toSet);
            return;
        }
        rawset(LuaInteger.valueOf(idx), value);
    }

    public LuaValue rawget(LuaValue key) {
        ensureFilled();
        // String-key fast lane: the dominant field/method access shape
        // (t.x, t["k"]). Strings are never float-normalized, never nil and
        // never integer-keyed, and are never wrapped as WeakKey, so the
        // normalizeKey + isNil + isInteger virtual chain is pure overhead.
        // WeakVal unwrap is preserved (weak-value tables still store values
        // wrapped).
        if (key instanceof LuaString) {
            LuaValue val = hashPart.get(key);
            if (val instanceof WeakVal wv) {
                LuaValue actual = wv.get();
                if (actual == null) {
                    hashPart.remove(key);
                    return LuaNil.NIL;
                }
                return actual;
            }
            return val != null ? val : LuaNil.NIL;
        }
        key = normalizeKey(key);
        if (key == null || key.isNil()) {
            return LuaNil.NIL;
        }
        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx >= 1 && idx <= arrayPart.size()) {
                LuaValue val = arrayPart.get((int) (idx - 1));
                if (val instanceof WeakVal wv) {
                    LuaValue actual = wv.get();
                    if (actual == null) {
                        arrayPart.set((int) (idx - 1), LuaNil.NIL);
                        return LuaNil.NIL;
                    }
                    return actual;
                }
                if (val != null && !val.isNil()) {
                    return val;
                }
            }
        }
        LuaValue val = hashPart.get(key);
        if (val instanceof WeakVal wv) {
            LuaValue actual = wv.get();
            if (actual == null) {
                hashPart.remove(key);
                return LuaNil.NIL;
            }
            return actual;
        }
        return val != null ? val : LuaNil.NIL;
    }

    public void rawset(LuaValue key, LuaValue value) {
        ensureFilled();
        modCount++;
        lastReturnedKey = null;
        nextIterator = null;
        // String-key fast lane: the dominant field/element write shape
        // (t.x = v, t["k"] = v). Strings are never nil, never float-
        // normalized, never integer-keyed and never wrapped as WeakKey, so
        // the normalizeKey + isNil + isInteger(isFloat) virtual chain in the
        // generic path is pure overhead. Mirrors rawget's lane. Weak tables
        // keep the generic path (values must be wrapped).
        if (key instanceof LuaString && !weakKeys && !weakValues) {
            if (value == null || value == LuaNil.NIL || value.isNil()) {
                if (hashPart.containsKey(key)) {
                    hashPart.put(key, LuaNil.NIL);
                }
            } else {
                hashPart.put(key, value);
            }
            return;
        }
        key = normalizeKey(key);
        if (key == null || key.isNil()) {
            throw new LuaException("table index is nil");
        }
        if (key.isFloat() && Double.isNaN(key.toDouble())) {
            throw new LuaException("table index is NaN");
        }
        LuaValue toSet = (value == null || value.isNil()) ? LuaNil.NIL : value;
        LuaValue toStore = toSet;
        if (weakValues && isCollectable(toSet)) {
            toStore = new WeakVal(toSet);
        }

        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx == arrayPart.size() + 1 && !toSet.isNil()) {
                arrayPart.add(toStore);
                if (!hashPart.isEmpty()) hashPart.remove(key);
                return;
            } else if (idx >= 1 && idx <= arrayPart.size()) {
                if (!hashPart.isEmpty()) hashPart.remove(key);
                arrayPart.set((int) (idx - 1), toStore);
                return;
            } else if (!toSet.isNil()) {
                // Gap insert (e.g. filling from 2, or reverse fill): grow
                // the array part when dense enough, then retry above.
                maybeRehashForInt();
                int size = arrayPart.size();
                if (idx == (long) size + 1) {
                    arrayPart.add(toStore);
                    if (!hashPart.isEmpty()) hashPart.remove(key);
                    return;
                } else if (idx >= 1 && idx <= size) {
                    if (!hashPart.isEmpty()) hashPart.remove(key);
                    arrayPart.set((int) (idx - 1), toStore);
                    return;
                }
            }
        }

        if (toSet.isNil()) {
            if (hashPart.containsKey(key)) {
                hashPart.put(key, LuaNil.NIL);
            }
        } else if (!weakKeys && !weakValues) {
            // Hot path (plain tables): single lookup. Values are never
            // null, so put() alone both inserts and overwrites; the old
            // containsKey()+prune() pair was pure overhead here
            // (pruneDeadKeys is a no-op without weak modes).
            hashPart.put(key, toStore);
        } else {
            if (!hashPart.containsKey(key)) {
                pruneDeadKeys();
            }
            LuaValue keyToStore = (weakKeys && isCollectable(key)) ? new WeakKey(key) : key;
            hashPart.put(keyToStore, toStore);
        }
    }

    /**
     * Array-part growth trigger for out-of-range integer inserts.
     * Gated by hash occupancy so sparse keys (t[1000000] = x) stay in the
     * hash part instead of forcing a huge array; skipped for weak tables.
     */
    private int rehashThreshold = 64;

    private void maybeRehashForInt() {
        if (!weakKeys && !weakValues && hashPart.size() >= rehashThreshold) {
            rehash();
        }
    }

    /** Maximum array-part size (2^24 slots = 128 MB of refs); larger integer
     * keys stay in the hash part. Bounds memory on hostile sparse inserts. */
    private static final long MAX_ARRAY_SIZE = 1L << 24;

    /** Smallest i with {@code key <= 2^i} (key >= 1). */
    private static int arrayBucket(long key) {
        return 32 - Integer.numberOfLeadingZeros((int) Math.min(key - 1, 0x7FFFFFFFL));
    }

    /**
     * Lua-style rehash: size the array part to the largest power of two
     * that dense integer keys fill beyond half, then migrate those keys
     * out of the hash part. Sparse leftovers stay hashed. Never shrinks.
     * Values and iteration completeness are preserved; only the internal
     * placement changes (pairs order is unspecified, as in C Lua).
     */
    private void rehash() {
        int[] nums = new int[25]; // nums[i] = # int keys in (2^(i-1), 2^i]
        for (int i = 0; i < arrayPart.size(); i++) {
            LuaValue v = arrayPart.get(i);
            if (v instanceof WeakVal wv) v = wv.get();
            if (v != null && !v.isNil()) {
                int b = arrayBucket(i + 1L);
                if (b < nums.length) nums[b]++;
            }
        }
        for (LuaValue k : hashPart.keySet()) {
            if (k != null && k.isInteger()) {
                long idx = k.toLong();
                if (idx >= 1 && idx <= MAX_ARRAY_SIZE) {
                    int b = arrayBucket(idx);
                    if (b < nums.length) nums[b]++;
                }
            }
        }
        // Optimal size: largest 2^i with more than half its slots used.
        int cumulative = 0;
        int optimal = 0;
        for (int i = 0; i < nums.length; i++) {
            cumulative += nums[i];
            long half = (i == 0) ? 0 : (1L << (i - 1));
            if (nums[i] > 0 && cumulative > half) {
                optimal = 1 << i;
            }
        }
        if (optimal <= arrayPart.size()) {
            // No progress (all sparse): back the threshold off so a sparse
            // workload does not rehash on every insert (O(n^2)).
            rehashThreshold = Math.max(rehashThreshold * 2, hashPart.size() + 1);
            return;
        }
        arrayPart.ensureCapacity(optimal);
        while (arrayPart.size() < optimal) {
            arrayPart.add(LuaNil.NIL);
        }
        java.util.Iterator<Map.Entry<LuaValue, LuaValue>> it = hashPart.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<LuaValue, LuaValue> e = it.next();
            LuaValue k = e.getKey();
            if (k != null && k.isInteger()) {
                long idx = k.toLong();
                if (idx >= 1 && idx <= optimal) {
                    LuaValue v = e.getValue();
                    // Array-first reads make hash shadows invisible; drop
                    // nil ones, overwrite with live ones (same visible value).
                    arrayPart.set((int) idx - 1, (v == null || v.isNil()) ? LuaNil.NIL : v);
                    it.remove();
                }
            }
        }
    }

    private void pruneDeadKeys() {
        if (!weakKeys && !weakValues) return;
        hashPart.entrySet().removeIf(entry -> {
            LuaValue v = entry.getValue();
            if (v == null || v.isNil() || (v instanceof WeakVal wv && wv.get() == null)) return true;
            LuaValue k = entry.getKey();
            if (k instanceof WeakKey wk && wk.get() == null) return true;
            return false;
        });
    }

    @Override
    public LuaValue get(LuaValue key) {
        ensureFilled();
        LuaValue t = this;
        for (int loop = 0; loop < 2000; loop++) {
            if (t instanceof LuaTable tbl) {
                LuaValue val = tbl.rawget(key);
                if (!val.isNil()) {
                    return val;
                }
                LuaTable mt = tbl.getMetatable();
                if (mt == null) return LuaNil.NIL;
                LuaValue handler = mt.rawget(LuaValue.Meta.INDEX);
                if (handler.isNil()) return LuaNil.NIL;
                if (handler.isFunction()) {
                    org.luava.runtime.eval.CallStack.setNextCall("index", true);
                    return handler.call(tbl, key);
                }
                t = handler;
            } else {
                return t.get(key);
            }
        }
        throw new LuaException("'__index' chain too long; possible loop");
    }

    @Override
    public void set(LuaValue key, LuaValue value) {
        ensureFilled();
        // Fast path: no metatable means no __newindex chain; the rawget
        // probe below would be pure overhead (a second hash lookup on top
        // of rawset's own). Semantics identical: the loop's first iteration
        // with mt == null does exactly rawset(key, value).
        if (getMetatable() == null) {
            rawset(key, value);
            return;
        }
        LuaValue t = this;
        for (int loop = 0; loop < 2000; loop++) {
            if (t instanceof LuaTable tbl) {
                LuaValue existing = tbl.rawget(key);
                if (!existing.isNil() || tbl.getMetatable() == null) {
                    tbl.rawset(key, value);
                    return;
                }
                LuaTable mt = tbl.getMetatable();
                LuaValue handler = mt.rawget(LuaValue.Meta.NEWINDEX);
                if (handler.isNil()) {
                    tbl.rawset(key, value);
                    return;
                }
                if (handler.isFunction()) {
                    org.luava.runtime.eval.CallStack.setNextCall("newindex", true);
                    handler.call(tbl, key, value);
                    return;
                }
                t = handler;
            } else {
                t.set(key, value);
                return;
            }
        }
        throw new LuaException("'__newindex' chain too long; possible loop");
    }

    @Override
    public LuaValue len() {
        ensureFilled();
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaValue.Meta.LEN);
            if (!handler.isNil()) {
                org.luava.runtime.eval.CallStack.setNextCall("len", true);
                return handler.call(this, this);
            }
        }
        return LuaInteger.valueOf(rawlen());
    }

    public int rawlen() {
        ensureFilled();
        // Mirror C luaH_getn/unbound_search: trim trailing nils, then if
        // t[n+1] is present (possibly in hashPart) search upward for a border.
        int n = arrayPart.size();
        while (n > 0 && arrayPart.get(n - 1).isNil()) {
            n--;
        }
        if (n > 0 && rawget(LuaInteger.valueOf((long) n + 1)).isNil()) {
            return n;
        }
        return unboundSearch(n);
    }

    private int unboundSearch(long j) {
        long i = j; // i is zero or a present index
        j++;
        while (!rawget(LuaInteger.valueOf(j)).isNil()) {
            i = j;
            if (j > 0x7FFFFFFFL / 2) { // overflow guard: linear fallback
                i = 1;
                while (!rawget(LuaInteger.valueOf(i)).isNil()) i++;
                return (int) Math.min(i - 1, Integer.MAX_VALUE);
            }
            j *= 2;
        }
        while (j - i > 1) {
            long m = (i + j) / 2;
            if (rawget(LuaInteger.valueOf(m)).isNil()) j = m;
            else i = m;
        }
        return (int) Math.min(i, Integer.MAX_VALUE);
    }

    public Varargs next(LuaValue currentKey) {
        ensureFilled();
        currentKey = normalizeKey(currentKey);

        // 1. Starting traversal (currentKey is nil)
        // 1. Initial call
        if (currentKey == null || currentKey.isNil()) {
            pruneDeadKeys();
            hashPart.entrySet().removeIf(entry -> {
                LuaValue v = entry.getValue();
                return v == null || v.isNil();
            });
            for (int i = 0; i < arrayPart.size(); i++) {
                LuaValue val = arrayPart.get(i);
                if (val instanceof WeakVal wv) val = wv.get();
                if (val != null && !val.isNil()) {
                    return Varargs.of(LuaInteger.valueOf(i + 1), val);
                }
            }
            Iterator<Map.Entry<LuaValue, LuaValue>> iter = hashPart.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<LuaValue, LuaValue> entry = iter.next();
                LuaValue k = entry.getKey();
                if (k instanceof WeakKey wk) k = wk.get();
                if (k == null) continue;
                LuaValue val = entry.getValue();
                if (val instanceof WeakVal wv) val = wv.get();
                if (val != null && !val.isNil()) {
                    lastReturnedKey = k;
                    nextIterator = iter;
                    return Varargs.of(k, val);
                }
            }
            lastReturnedKey = null;
            nextIterator = null;
            return Varargs.of(LuaNil.NIL);
        }

        // 2. Traversal within arrayPart
        if (currentKey.isInteger()) {
            long k = currentKey.toLong();
            if (k >= 1 && k <= arrayPart.size()) {
                for (int i = (int) k; i < arrayPart.size(); i++) {
                    LuaValue val = arrayPart.get(i);
                    if (val instanceof WeakVal wv) val = wv.get();
                    if (val != null && !val.isNil()) {
                        return Varargs.of(LuaInteger.valueOf(i + 1), val);
                    }
                }
                Iterator<Map.Entry<LuaValue, LuaValue>> iter = hashPart.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<LuaValue, LuaValue> entry = iter.next();
                    LuaValue hk = entry.getKey();
                    if (hk instanceof WeakKey wk) hk = wk.get();
                    if (hk == null) continue;
                    LuaValue val = entry.getValue();
                    if (val instanceof WeakVal wv) val = wv.get();
                    if (val != null && !val.isNil()) {
                        lastReturnedKey = hk;
                        nextIterator = iter;
                        return Varargs.of(hk, val);
                    }
                }
                lastReturnedKey = null;
                nextIterator = null;
                return Varargs.of(LuaNil.NIL);
            }
        }

        // 3. Traversal within hashPart
        if (lastReturnedKey != null && currentKey.equals(lastReturnedKey) && nextIterator != null) {
            while (nextIterator.hasNext()) {
                Map.Entry<LuaValue, LuaValue> entry = nextIterator.next();
                LuaValue k = entry.getKey();
                if (k instanceof WeakKey wk) k = wk.get();
                if (k == null) continue;
                LuaValue val = entry.getValue();
                if (val instanceof WeakVal wv) val = wv.get();
                if (val != null && !val.isNil()) {
                    lastReturnedKey = k;
                    return Varargs.of(k, val);
                }
            }
            lastReturnedKey = null;
            nextIterator = null;
            return Varargs.of(LuaNil.NIL);
        }

        pruneDeadKeys();
        Iterator<Map.Entry<LuaValue, LuaValue>> iter = hashPart.entrySet().iterator();
        boolean found = false;
        while (iter.hasNext()) {
            Map.Entry<LuaValue, LuaValue> entry = iter.next();
            LuaValue k = entry.getKey();
            if (k instanceof WeakKey wk) k = wk.get();
            if (k == null) continue;
            if (found) {
                LuaValue val = entry.getValue();
                if (val instanceof WeakVal wv) val = wv.get();
                if (val != null && !val.isNil()) {
                    lastReturnedKey = k;
                    nextIterator = iter;
                    return Varargs.of(k, val);
                }
            } else if (k.equals(currentKey)) {
                found = true;
            }
        }
        lastReturnedKey = null;
        nextIterator = null;
        if (!found) {
            throw new LuaException("invalid key to 'next'");
        }
        return Varargs.of(LuaNil.NIL);
    }

    public List<LuaValue> keys() {
        List<LuaValue> list = new ArrayList<>();
        for (int i = 0; i < arrayPart.size(); i++) {
            LuaValue v = arrayPart.get(i);
            if (v != null && !v.isNil()) {
                list.add(LuaInteger.valueOf(i + 1));
            }
        }
        for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
            LuaValue k = entry.getKey();
            LuaValue v = entry.getValue();
            if (v != null && !v.isNil() && !(v instanceof WeakVal wv && wv.get() == null)) {
                if (!k.isInteger() || k.toLong() < 1 || k.toLong() > arrayPart.size()) {
                    list.add(k);
                }
            }
        }
        return list;
    }

    @Override
    public String toLuaString() {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaValue.Meta.TOSTRING);
            if (!handler.isNil()) {
                LuaValue res = handler.call(this);
                // luaL_tolstring accepts a numeric result too (lua_isstring).
                if (!res.isString() && !res.isNumber()) {
                    throw new LuaException("'__tostring' must return a string");
                }
                return res.toLuaString();
            }
            LuaValue nameVal = metatable.rawget(LuaValue.Meta.NAME);
            if (nameVal != null && nameVal.isString()) {
                return nameVal.toLuaString() + ": 0x" + Integer.toHexString(System.identityHashCode(this));
            }
        }
        return "table: 0x" + Integer.toHexString(System.identityHashCode(this));
    }

    public void forEach(java.util.function.BiConsumer<LuaValue, LuaValue> consumer) {
        for (int i = 0; i < arrayPart.size(); i++) {
            LuaValue v = arrayPart.get(i);
            if (v instanceof WeakVal wv) v = wv.get();
            if (v != null && !v.isNil()) {
                consumer.accept(LuaInteger.valueOf(i + 1), v);
            }
        }
        for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
            LuaValue k = entry.getKey();
            if (k instanceof WeakKey wk) k = wk.get();
            if (k == null || k.isNil()) continue;
            LuaValue v = entry.getValue();
            if (v instanceof WeakVal wv) v = wv.get();
            if (v != null && !v.isNil()) {
                consumer.accept(k, v);
            }
        }
    }
}

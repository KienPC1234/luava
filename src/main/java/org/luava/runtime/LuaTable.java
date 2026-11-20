/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.luava.runtime.bytecode.BytecodeVM;

public final class LuaTable extends LuaValue {
    // Unboxed array storage: arrT holds BytecodeVM.TYPE_* (TYPE_NIL == 0, so
    // fresh byte[] slots are nil without an init loop); arrO is allocated
    // lazily only when a non-primitive object is written to the array.
    private long[] arrP;
    private byte[] arrT;
    private LuaValue[] arrO;
    private int arrLen;

    // Lazy hash storage: stays null for pure arrays and empty tables.
    private HashMap<LuaValue, LuaValue> hashPart;
    private long hashKeyBloom;
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

    boolean isLazyPending() {
        return lazyFiller != null;
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

    /**
     * PUC {@code luaH_resize} for a table constructor: the array part is
     * pre-sized to {@code arrayCapacity} nil slots. Pre-sizing to the
     * constructor's list-part length is observable: it fixes {@code rawlen}
     * ({@code rawlen({1, 2, nil, 4})} is 4, not 2), the {@code #} border, and
     * therefore where {@code table.insert} puts a new element.
     */
    public LuaTable(int arrayCapacity, int hashCapacity) {
        if (arrayCapacity > 0) {
            this.arrP = new long[arrayCapacity];
            this.arrT = new byte[arrayCapacity];
            this.arrLen = arrayCapacity;
        }
        if (hashCapacity > 0) {
            this.hashPart = new HashMap<>(hashCapacity <= 4 ? 8 : Math.max(16, (hashCapacity * 4) / 3 + 1));
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

    /**
     * Cached first {@code __index} hop for metatable-backed reads, so a
     * {@code self.x} / {@code obj:m()} miss costs one hash probe instead of
     * two (the field, then the metatable's {@code __index} slot).
     *
     * <p>Guarded by the metatable's identity and {@link #readVersion()}, so
     * {@code Mt.__index = other} or any string-keyed write to {@code Mt}
     * misses. Integer-keyed writes do not bump {@code modCount}, which is
     * sound here because {@code __index} is always a string key. A metatable
     * that itself has a metatable reports -1 and is never cached.
     *
     * <p>Invalidated on this table's own {@code modCount++}, so
     * {@code setmetatable} cannot leave a stale hop behind. The interpreter,
     * the JIT and every library read go through {@link #get}, so one cache
     * covers all three.
     *
     * <p>The triple lives in one immutable object rather than three fields:
     * the table is documented thread-confined, but {@code lazyFiller} is
     * filled under {@code synchronized (this)}, and unlike a redundant (and
     * idempotent) re-fill, observing a mismatched pair would hand back the
     * wrong {@code __index} instead of merely recomputing it.
     */
    private record IndexHop(LuaTable mt, long mtVersion, LuaValue handler) {}

    private transient IndexHop indexHop;

    /** VM site-cache guard; public for the bytecode package. */
    public long readVersion() {
        return metatable == null ? modCount : -1L;
    }

    /**
     * The metatable's {@code __index}, memoized per (metatable, its version).
     * Semantically identical to {@code mt.rawget(Meta.INDEX)} on a miss.
     */
    private LuaValue cachedIndexHandler(LuaTable mt) {
        long mtVersion = mt.readVersion();
        IndexHop hop = indexHop;
        if (hop != null && hop.mt == mt && hop.mtVersion == mtVersion) {
            return hop.handler;
        }
        LuaValue handler = mt.rawget(LuaValue.Meta.INDEX);
        if (mtVersion != -1L) {
            indexHop = new IndexHop(mt, mtVersion, handler);
        }
        return handler;
    }
    private transient Iterator<Map.Entry<LuaValue, LuaValue>> nextIterator = null;
    private transient LuaValue lastReturnedKey = null;

    private void ensureArrayCapacity(int minCap) {
        if (arrT == null) {
            int cap = Math.max(4, minCap);
            arrP = new long[cap];
            arrT = new byte[cap];
            return;
        }
        int cur = arrT.length;
        if (minCap <= cur) {
            return;
        }
        int doubled = (cur <= (Integer.MAX_VALUE >> 1)) ? Math.max(8, cur << 1) : (Integer.MAX_VALUE - 8);
        int newCap = Math.max(minCap, doubled);
        arrP = Arrays.copyOf(arrP, newCap);
        arrT = Arrays.copyOf(arrT, newCap);
        if (arrO != null) {
            arrO = Arrays.copyOf(arrO, newCap);
        }
    }

    private void ensureArrO() {
        if (arrO == null) {
            arrO = new LuaValue[arrT.length];
        }
    }

    private void writeArraySlotFromValue(int idx0, LuaValue val) {
        if (val == null || val == LuaNil.NIL || val.isNil()) {
            arrT[idx0] = BytecodeVM.TYPE_NIL;
            arrP[idx0] = 0L;
            if (arrO != null) arrO[idx0] = null;
        } else if (val instanceof LuaInteger li) {
            arrT[idx0] = BytecodeVM.TYPE_INT;
            arrP[idx0] = li.toLong();
            if (arrO != null) arrO[idx0] = null;
        } else if (val instanceof LuaFloat lf) {
            arrT[idx0] = BytecodeVM.TYPE_FLOAT;
            arrP[idx0] = Double.doubleToRawLongBits(lf.toDouble());
            if (arrO != null) arrO[idx0] = null;
        } else if (val instanceof LuaBoolean lb) {
            arrT[idx0] = BytecodeVM.TYPE_BOOLEAN;
            arrP[idx0] = lb.toBoolean() ? 1L : 0L;
            if (arrO != null) arrO[idx0] = null;
        } else {
            ensureArrO();
            arrT[idx0] = BytecodeVM.TYPE_OBJECT;
            arrP[idx0] = 0L;
            arrO[idx0] = (weakValues && isCollectable(val) && !(val instanceof WeakVal)) ? new WeakVal(val) : val;
        }
    }

    private void writeArraySlotFromStack(int idx0, byte tag, long pVal, LuaValue oVal) {
        if (tag == BytecodeVM.TYPE_NIL) {
            arrT[idx0] = BytecodeVM.TYPE_NIL;
            arrP[idx0] = 0L;
            if (arrO != null) arrO[idx0] = null;
        } else if (tag != BytecodeVM.TYPE_OBJECT) {
            arrT[idx0] = tag;
            arrP[idx0] = pVal;
            if (arrO != null) arrO[idx0] = null;
        } else if (oVal == null || oVal == LuaNil.NIL || oVal.isNil()) {
            arrT[idx0] = BytecodeVM.TYPE_NIL;
            arrP[idx0] = 0L;
            if (arrO != null) arrO[idx0] = null;
        } else {
            ensureArrO();
            arrT[idx0] = BytecodeVM.TYPE_OBJECT;
            arrP[idx0] = 0L;
            arrO[idx0] = (weakValues && isCollectable(oVal) && !(oVal instanceof WeakVal)) ? new WeakVal(oVal) : oVal;
        }
    }

    private LuaValue readArraySlot(int idx0) {
        byte tag = arrT[idx0];
        return switch (tag) {
            case BytecodeVM.TYPE_NIL -> LuaNil.NIL;
            case BytecodeVM.TYPE_BOOLEAN -> LuaBoolean.valueOf(arrP[idx0] != 0L);
            case BytecodeVM.TYPE_INT -> LuaInteger.valueOf(arrP[idx0]);
            case BytecodeVM.TYPE_FLOAT -> LuaFloat.valueOf(Double.longBitsToDouble(arrP[idx0]));
            default -> {
                LuaValue val = arrO != null ? arrO[idx0] : null;
                if (val instanceof WeakVal wv) {
                    LuaValue actual = wv.get();
                    if (actual == null) {
                        arrT[idx0] = BytecodeVM.TYPE_NIL;
                        arrP[idx0] = 0L;
                        arrO[idx0] = null;
                        yield LuaNil.NIL;
                    }
                    yield actual;
                }
                yield val != null ? val : LuaNil.NIL;
            }
        };
    }

    private int hashEntryCount() {
        return hashPart != null ? hashPart.size() : 0;
    }

    private void markBloom(LuaValue key) {
        hashKeyBloom |= (1L << (key.hashCode() & 63));
    }

    public boolean hasMaybeStringKey(LuaString ks) {
        return hashPart != null && (hashKeyBloom & (1L << (ks.fastHash() & 63))) != 0L;
    }

    private HashMap<LuaValue, LuaValue> ensureHashPart() {
        if (hashPart == null) {
            hashPart = new HashMap<>();
        }
        return hashPart;
    }

    private void removeIntShadowFromHash(long idx) {
        if (hashPart != null && !hashPart.isEmpty()) {
            hashPart.remove(LuaInteger.valueOf(idx));
        }
    }

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
            for (int i = 0; i < arrLen; i++) {
                if (arrT[i] == BytecodeVM.TYPE_OBJECT && arrO != null) {
                    LuaValue v = arrO[i];
                    if (v != null && isCollectable(v) && !(v instanceof WeakVal)) {
                        arrO[i] = new WeakVal(v);
                    }
                }
            }
            if (hashPart != null) {
                for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
                    LuaValue v = entry.getValue();
                    if (v != null && isCollectable(v) && !(v instanceof WeakVal)) {
                        entry.setValue(new WeakVal(v));
                    }
                }
            }
        }
        if (weakKeys && hashPart != null) {
            HashMap<LuaValue, LuaValue> newHash = new HashMap<>(Math.max(16, hashPart.size() * 2));
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
        if (arrO != null) {
            for (int i = 0; i < arrLen; i++) {
                if (arrT[i] == BytecodeVM.TYPE_OBJECT) {
                    LuaValue v = arrO[i];
                    if (v instanceof WeakVal wv) {
                        LuaValue actual = wv.get();
                        if (actual == null || (isAlive != null && isCollectable(actual) && !isAlive.test(actual))) {
                            wv.clear();
                            arrT[i] = BytecodeVM.TYPE_NIL;
                            arrP[i] = 0L;
                            arrO[i] = null;
                        }
                    }
                }
            }
        }
        if (hashPart != null) {
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
    }

    public void cleanupWeakKeys(java.util.function.Predicate<LuaValue> isAlive) {
        if (!weakKeys || hashPart == null) return;
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
        for (int i = 0; i < arrLen; i++) {
            consumer.accept(LuaInteger.valueOf(i + 1));
        }
        if (hashPart != null) {
            for (LuaValue k : hashPart.keySet()) {
                if (k instanceof WeakKey wk) k = wk.get();
                if (k != null && !k.isNil()) {
                    consumer.accept(k);
                }
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
        // The cached __index hop belonged to the previous metatable.
        indexHop = null;
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
        if (idx >= 1 && idx <= arrLen) {
            LuaValue val = readArraySlot( (int) (idx - 1));
            if (val != null && !val.isNil()) {
                return val;
            }
        }
        if (hashEntryCount() == 0) {
            return LuaNil.NIL;
        }
        return rawget(LuaInteger.valueOf(idx));
    }

    public void rawgetIntToStack(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if (lazyFiller == null && idx >= 1 && idx <= arrLen) {
            int i = (int) (idx - 1);
            byte tag = arrT[i];
            if (!weakValues || tag != BytecodeVM.TYPE_OBJECT) {
                tStack[dst] = tag;
                pStack[dst] = arrP[i];
                oStack[dst] = (tag == BytecodeVM.TYPE_OBJECT && arrO != null) ? arrO[i] : null;
                return;
            }
        }
        rawgetIntToStackSlow(idx, pStack, tStack, oStack, dst);
    }

    private void rawgetIntToStackSlow(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if (lazyFiller != null) {
            ensureFilledSlow();
        }
        if (idx >= 1 && idx <= arrLen) {
            BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, readArraySlot((int) (idx - 1)));
            return;
        }
        if (hashEntryCount() == 0) {
            tStack[dst] = BytecodeVM.TYPE_NIL;
            pStack[dst] = 0L;
            oStack[dst] = null;
            return;
        }
        BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, rawget(LuaInteger.valueOf(idx)));
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
        if (idx == arrLen + 1 && toSet != LuaNil.NIL) {
            ensureArrayCapacity(arrLen + 1);
            writeArraySlotFromValue(arrLen++, toSet);
            if (hashEntryCount() > 0) removeIntShadowFromHash(idx);
            return;
        } else if (idx >= 1 && idx <= arrLen) {
            if (hashEntryCount() > 0) removeIntShadowFromHash(idx);
            writeArraySlotFromValue((int) (idx - 1), toSet);
            return;
        }
        rawset(LuaInteger.valueOf(idx), value);
    }

    public void rawsetIntFromStack(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        if (lazyFiller == null && hashPart == null) {
            byte tag = tStack[src];
            if (tag != BytecodeVM.TYPE_OBJECT) {
                if (idx >= 1 && idx <= arrLen) {
                    int i = (int) (idx - 1);
                    arrT[i] = tag;
                    arrP[i] = pStack[src];
                    if (arrO != null) arrO[i] = null;
                    return;
                }
                if (idx == arrLen + 1 && tag != BytecodeVM.TYPE_NIL && arrT != null && arrLen < arrT.length) {
                    int i = arrLen++;
                    arrT[i] = tag;
                    arrP[i] = pStack[src];
                    if (arrO != null) arrO[i] = null;
                    return;
                }
            }
        }
        rawsetIntFromStackSlow(idx, pStack, tStack, oStack, src);
    }

    private void rawsetIntFromStackSlow(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        if (lazyFiller != null) {
            ensureFilledSlow();
        }
        byte tag = tStack[src];
        long pVal = pStack[src];
        LuaValue oVal = oStack[src];
        boolean isNil = (tag == BytecodeVM.TYPE_NIL)
                || (tag == BytecodeVM.TYPE_OBJECT && (oVal == null || oVal == LuaNil.NIL || oVal.isNil()));
        if (idx == arrLen + 1 && !isNil) {
            ensureArrayCapacity(arrLen + 1);
            writeArraySlotFromStack(arrLen++, tag, pVal, oVal);
            if (hashPart != null && !hashPart.isEmpty()) hashPart.remove(LuaInteger.valueOf(idx));
            return;
        } else if (idx >= 1 && idx <= arrLen) {
            if (hashPart != null && !hashPart.isEmpty()) hashPart.remove(LuaInteger.valueOf(idx));
            writeArraySlotFromStack((int) (idx - 1), tag, pVal, oVal);
            return;
        }
        rawset(LuaInteger.valueOf(idx), BytecodeVM.getLuaValueFromRaw(pVal, tag, oVal));
    }

    public boolean tryRawgetToStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if (lazyFiller == null && !weakValues && key instanceof LuaString ks) {
            HashMap<LuaValue, LuaValue> map = this.hashPart;
            if (map == null || (hashKeyBloom & (1L << (ks.fastHash() & 63))) == 0L) {
                return false;
            }
            LuaValue val = map.get(ks);
            if (val == null || val == LuaNil.NIL) {
                return false;
            }
            BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, val);
            return true;
        }
        return tryRawgetToStackSlow(key, pStack, tStack, oStack, dst);
    }

    private boolean tryRawgetToStackSlow(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        ensureFilled();
        if (key instanceof LuaString) {
            if (hashPart == null) {
                return false;
            }
            LuaValue val = hashPart.get(key);
            if (val instanceof WeakVal wv) {
                LuaValue actual = wv.get();
                if (actual == null) {
                    hashPart.remove(key);
                    return false;
                }
                val = actual;
            }
            if (val == null || val == LuaNil.NIL || val.isNil()) {
                return false;
            }
            BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, val);
            return true;
        }
        LuaValue val = rawget(key);
        if (val == null || val == LuaNil.NIL || val.isNil()) {
            return false;
        }
        BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, val);
        return true;
    }

    public LuaValue rawget(LuaValue key) {
        if (lazyFiller == null && !weakValues && key instanceof LuaString ks) {
            HashMap<LuaValue, LuaValue> map = this.hashPart;
            if (map == null || (hashKeyBloom & (1L << (ks.fastHash() & 63))) == 0L) {
                return LuaNil.NIL;
            }
            LuaValue val = map.get(ks);
            return val != null ? val : LuaNil.NIL;
        }
        return rawgetSlow(key);
    }

    private LuaValue rawgetSlow(LuaValue key) {
        ensureFilled();
        if (key instanceof LuaString) {
            if (hashPart == null) {
                return LuaNil.NIL;
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
        key = normalizeKey(key);
        if (key == null || key.isNil()) {
            return LuaNil.NIL;
        }
        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx >= 1 && idx <= arrLen) {
                LuaValue val = readArraySlot((int) (idx - 1));
                if (val != null && !val.isNil()) {
                    return val;
                }
            }
        }
        if (hashPart == null) {
            return LuaNil.NIL;
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
        if (key instanceof LuaString ks && !weakKeys && !weakValues) {
            if (value == null || value == LuaNil.NIL || value.isNil()) {
                if (hashPart != null && hashPart.containsKey(ks)) {
                    hashPart.put(ks, LuaNil.NIL);
                }
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                ensureHashPart().put(ks, value);
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
            if (idx == arrLen + 1 && !toSet.isNil()) {
                ensureArrayCapacity(arrLen + 1);
                writeArraySlotFromValue(arrLen++, toSet);
                if (hashPart != null && !hashPart.isEmpty()) hashPart.remove(key);
                return;
            } else if (idx >= 1 && idx <= arrLen) {
                if (hashPart != null && !hashPart.isEmpty()) hashPart.remove(key);
                writeArraySlotFromValue((int) (idx - 1), toSet);
                return;
            } else if (!toSet.isNil() && storeIntGap(idx, key, toSet)) {
                return;
            }
        }

        if (toSet.isNil()) {
            if (hashPart != null && hashPart.containsKey(key)) {
                hashPart.put(key, LuaNil.NIL);
            }
        } else if (!weakKeys && !weakValues) {
            markBloom(key);
            ensureHashPart().put(key, toStore);
        } else {
            HashMap<LuaValue, LuaValue> map = ensureHashPart();
            if (!map.containsKey(key)) {
                pruneDeadKeys();
            }
            LuaValue keyToStore = (weakKeys && isCollectable(key)) ? new WeakKey(key) : key;
            markBloom(keyToStore);
            map.put(keyToStore, toStore);
        }
    }

    public void rawsetFromStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        ensureFilled();
        if (key instanceof LuaString ks && !weakKeys && !weakValues) {
            modCount++;
            lastReturnedKey = null;
            nextIterator = null;
            LuaValue val = BytecodeVM.getLuaValue(pStack, tStack, oStack, src);
            if (val.isNil()) {
                if (hashPart != null && hashPart.containsKey(ks)) {
                    hashPart.put(ks, LuaNil.NIL);
                }
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                ensureHashPart().put(ks, val);
            }
            return;
        }
        rawset(key, BytecodeVM.getLuaValue(pStack, tStack, oStack, src));
    }

    public boolean trySetFieldFromStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        if (lazyFiller == null && metatable == null && !weakKeys && !weakValues && key instanceof LuaString ks) {
            HashMap<LuaValue, LuaValue> map = this.hashPart;
            if (map == null) {
                map = new HashMap<>(4);
                this.hashPart = map;
            }
            modCount++;
            lastReturnedKey = null;
            nextIterator = null;
            LuaValue val = BytecodeVM.getLuaValue(pStack, tStack, oStack, src);
            if (val == LuaNil.NIL) {
                if (map.containsKey(ks)) map.put(ks, LuaNil.NIL);
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                map.put(ks, val);
            }
            return true;
        }
        return trySetFieldFromStackSlow(key, pStack, tStack, oStack, src);
    }

    private boolean trySetFieldFromStackSlow(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        ensureFilled();
        LuaValue existing = rawget(key);
        if (existing == null || existing.isNil()) {
            if (metatable != null && !metatable.rawget(LuaValue.Meta.NEWINDEX).isNil()) {
                return false;
            }
        }
        rawset(key, BytecodeVM.getLuaValue(pStack, tStack, oStack, src));
        return true;
    }

    public boolean trySetField(LuaValue key, LuaValue val) {
        if (lazyFiller == null && metatable == null && !weakKeys && !weakValues && key instanceof LuaString ks) {
            HashMap<LuaValue, LuaValue> map = this.hashPart;
            if (map == null) {
                map = new HashMap<>(4);
                this.hashPart = map;
            }
            modCount++;
            lastReturnedKey = null;
            nextIterator = null;
            if (val == null || val == LuaNil.NIL || val.isNil()) {
                if (map.containsKey(ks)) map.put(ks, LuaNil.NIL);
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                map.put(ks, val);
            }
            return true;
        }
        return trySetFieldSlow(key, val);
    }

    private boolean trySetFieldSlow(LuaValue key, LuaValue val) {
        ensureFilled();
        LuaValue existing = rawget(key);
        if (existing == null || existing.isNil()) {
            if (metatable != null && !metatable.rawget(LuaValue.Meta.NEWINDEX).isNil()) {
                return false;
            }
        }
        rawset(key, val);
        return true;
    }

    /**
     * Array-part growth trigger for out-of-range integer inserts.
     * Gated by hash occupancy so sparse keys (t[1000000] = x) stay in the
     * hash part instead of forcing a huge array; skipped for weak tables.
     */
    private int rehashThreshold = 64;

    private boolean storeIntGap(long idx, LuaValue key, LuaValue toStore) {
        maybeRehashForInt(idx);
        if (idx >= 1 && idx <= arrLen) {
            if (hashPart != null && !hashPart.isEmpty()) hashPart.remove(key);
            writeArraySlotFromValue((int) (idx - 1), toStore);
            return true;
        }
        return false;
    }

    private void maybeRehashForInt(long idx) {
        if (weakKeys || weakValues) {
            return;
        }
        long near = (long) arrLen * 2L + 2L;
        if (idx <= near || hashEntryCount() >= rehashThreshold) {
            rehash(idx);
        }
    }

    /** Maximum array-part size (2^24 slots); larger integer
     * keys stay in the hash part. Bounds memory on hostile sparse inserts. */
    private static final long MAX_ARRAY_SIZE = 1L << 24;

    /**
     * Density bucket for an array index: {@code ceil(log2(key))}, matching PUC's
     * {@code nums[luaO_ceillog2(arrayindex(k))]}. Bucket {@code i} therefore
     * holds the keys in {@code (2^(i-1), 2^i]}.
     */
    private static int arrayBucket(long key) {
        if (key <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros((int) Math.min(key - 1, 0x7FFFFFFFL));
    }

    /**
     * Lua-style rehash: size the array part to the largest power of two
     * that dense integer keys fill beyond half, then migrate those keys
     * out of the hash part. Sparse leftovers stay hashed. Never shrinks.
     */
    private void rehash(long pendingKey) {
        int[] nums = new int[32];
        int na = 0;
        for (int i = 0; i < arrLen; i++) {
            if (arrT[i] != BytecodeVM.TYPE_NIL) {
                LuaValue v = weakValues ? readArraySlot(i) : null;
                if (!weakValues || (v != null && !v.isNil())) {
                    int b = arrayBucket(i + 1L);
                    if (b < nums.length) nums[b]++;
                    na++;
                }
            }
        }
        if (hashPart != null) {
            for (LuaValue k : hashPart.keySet()) {
                if (k != null && k.isInteger()) {
                    long idx = k.toLong();
                    if (idx >= 1 && idx <= MAX_ARRAY_SIZE) {
                        int b = arrayBucket(idx);
                        if (b < nums.length) nums[b]++;
                        na++;
                    }
                }
            }
        }
        if (pendingKey >= 1 && pendingKey <= MAX_ARRAY_SIZE) {
            int b = arrayBucket(pendingKey);
            if (b < nums.length) nums[b]++;
            na++;
        }
        int optimal = 0;
        int a = 0;
        for (int i = 0, twotoi = 1;
             i < nums.length && twotoi > 0 && na > (twotoi / 2);
             i++, twotoi *= 2) {
            a += nums[i];
            if (a > twotoi / 2) {
                optimal = twotoi;
            }
        }
        if (optimal <= arrLen) {
            rehashThreshold = Math.max(rehashThreshold * 2, hashEntryCount() + 1);
            return;
        }
        ensureArrayCapacity(optimal);
        if (arrLen < optimal) {
            Arrays.fill(arrT, arrLen, optimal, BytecodeVM.TYPE_NIL);
            arrLen = optimal;
        }
        if (hashPart != null) {
            java.util.Iterator<Map.Entry<LuaValue, LuaValue>> it = hashPart.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<LuaValue, LuaValue> e = it.next();
                LuaValue k = e.getKey();
                if (k != null && k.isInteger()) {
                    long idx = k.toLong();
                    if (idx >= 1 && idx <= optimal) {
                        writeArraySlotFromValue((int) idx - 1, e.getValue());
                        it.remove();
                    }
                }
            }
        }
    }

    private void pruneDeadKeys() {
        if ((!weakKeys && !weakValues) || hashPart == null) return;
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
                LuaValue handler = tbl.cachedIndexHandler(mt);
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

    private boolean isArraySlotNil(int idx0) {
        byte tag = arrT[idx0];
        if (tag == BytecodeVM.TYPE_NIL) {
            return true;
        }
        if (weakValues && tag == BytecodeVM.TYPE_OBJECT && arrO != null && arrO[idx0] instanceof WeakVal wv) {
            LuaValue actual = wv.get();
            if (actual == null) {
                arrT[idx0] = BytecodeVM.TYPE_NIL;
                arrP[idx0] = 0L;
                arrO[idx0] = null;
                return true;
            }
        }
        return false;
    }

    private boolean isIntNil(long idx) {
        if (idx >= 1 && idx <= arrLen) {
            return isArraySlotNil((int) (idx - 1));
        }
        if (hashPart == null || hashPart.isEmpty()) {
            return true;
        }
        return rawget(LuaInteger.valueOf(idx)).isNil();
    }

    public int rawlen() {
        ensureFilled();
        // Mirror C luaH_getn/unbound_search: trim trailing nils, then if
        // t[n+1] is present (possibly in hash) search upward for a border.
        int n = arrLen;
        while (n > 0 && isArraySlotNil(n - 1)) {
            n--;
        }
        if (hashPart == null || hashPart.isEmpty()) {
            return n;
        }
        if (n > 0 && isIntNil((long) n + 1L)) {
            return n;
        }
        return unboundSearch(n);
    }

    private int unboundSearch(long j) {
        long i = j; // i is zero or a present index
        j++;
        while (!isIntNil(j)) {
            i = j;
            if (j > 0x7FFFFFFFL / 2) { // overflow guard: linear fallback
                i = 1;
                while (!isIntNil(i)) i++;
                return (int) Math.min(i - 1, Integer.MAX_VALUE);
            }
            j *= 2;
        }
        while (j - i > 1) {
            long m = (i + j) / 2;
            if (isIntNil(m)) j = m;
            else i = m;
        }
        return (int) Math.min(i, Integer.MAX_VALUE);
    }

    public Varargs next(LuaValue currentKey) {
        ensureFilled();
        currentKey = normalizeKey(currentKey);

        // 1. Starting traversal (currentKey is nil)
        if (currentKey == null || currentKey.isNil()) {
            if (hashPart != null) {
                pruneDeadKeys();
                hashPart.entrySet().removeIf(entry -> {
                    LuaValue v = entry.getValue();
                    return v == null || v.isNil();
                });
            }
            for (int i = 0; i < arrLen; i++) {
                if (arrT[i] != BytecodeVM.TYPE_NIL) {
                    LuaValue val = readArraySlot(i);
                    if (val != null && !val.isNil()) {
                        return Varargs.of(LuaInteger.valueOf(i + 1), val);
                    }
                }
            }
            if (hashPart != null) {
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
            }
            lastReturnedKey = null;
            nextIterator = null;
            return Varargs.of(LuaNil.NIL);
        }

        // 2. Traversal within array part
        if (currentKey.isInteger()) {
            long k = currentKey.toLong();
            if (k >= 1 && k <= arrLen) {
                for (int i = (int) k; i < arrLen; i++) {
                    if (arrT[i] != BytecodeVM.TYPE_NIL) {
                        LuaValue val = readArraySlot(i);
                        if (val != null && !val.isNil()) {
                            return Varargs.of(LuaInteger.valueOf(i + 1), val);
                        }
                    }
                }
                if (hashPart != null) {
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
                }
                lastReturnedKey = null;
                nextIterator = null;
                return Varargs.of(LuaNil.NIL);
            }
        }

        // 3. Traversal within hash part
        if (hashPart == null) {
            lastReturnedKey = null;
            nextIterator = null;
            throw new LuaException("invalid key to 'next'");
        }

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
        for (int i = 0; i < arrLen; i++) {
            if (arrT[i] != BytecodeVM.TYPE_NIL) {
                LuaValue v = readArraySlot(i);
                if (v != null && !v.isNil()) {
                    list.add(LuaInteger.valueOf(i + 1));
                }
            }
        }
        if (hashPart != null) {
            for (Map.Entry<LuaValue, LuaValue> entry : hashPart.entrySet()) {
                LuaValue k = entry.getKey();
                LuaValue v = entry.getValue();
                if (v != null && !v.isNil() && !(v instanceof WeakVal wv && wv.get() == null)) {
                    if (!k.isInteger() || k.toLong() < 1 || k.toLong() > arrLen) {
                        list.add(k);
                    }
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
                org.luava.runtime.concurrency.LuaCoroutine.promoteIfStackless();
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

    /** Rough retained heap byte estimate used by {@code LuaState.maxMemoryBytes(long)}. */
    public long estimatedMemoryBytes() {
        long h = (hashPart != null) ? (long) hashPart.size() * 48L : 0L;
        return 64L + (long) arrLen * 16L + h;
    }

    public void forEach(java.util.function.BiConsumer<LuaValue, LuaValue> consumer) {
        for (int i = 0; i < arrLen; i++) {
            if (arrT[i] != BytecodeVM.TYPE_NIL) {
                LuaValue v = readArraySlot(i);
                if (v != null && !v.isNil()) {
                    consumer.accept(LuaInteger.valueOf(i + 1), v);
                }
            }
        }
        if (hashPart != null) {
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

    /**
     * Plain-table zero-StringBuilder concatenation over integer keys
     * {@code [start..last]}. Reads unboxed array slots and {@link LuaString}
     * backing bytes directly into one growing {@code byte[]} buffer.
     */
    public LuaString concatPlainBytes(byte[] sep, int sepOff, int sepLen,
            long start, long last, long allocCap) {
        if (last < start) {
            return LuaString.EMPTY;
        }
        long count = last - start + 1;
        long hint = Math.min(allocCap, Math.max(64L, Math.min(1L << 20, count * 8L)));
        byte[] buf = new byte[(int) hint];
        int pos = 0;

        for (long idx = start; idx <= last; idx++) {
            if ((idx & 0xFF) == 0) {
                org.luava.runtime.LuaState.checkGuard();
            }
            if (idx > start && sepLen > 0) {
                buf = ensureConcatCap(buf, pos, sepLen, allocCap);
                System.arraycopy(sep, sepOff, buf, pos, sepLen);
                pos += sepLen;
            }
            LuaString elem;
            if (idx <= arrLen && !weakValues) {
                int slot = (int) idx - 1;
                byte tag = arrT[slot];
                if (tag == BytecodeVM.TYPE_OBJECT) {
                    LuaValue v = arrO[slot];
                    elem = (v instanceof LuaString ls) ? ls : concatValueToString(v, idx);
                } else if (tag == BytecodeVM.TYPE_INT) {
                    elem = LuaString.fromLong(arrP[slot]);
                } else if (tag == BytecodeVM.TYPE_FLOAT) {
                    elem = LuaString.valueOf(LuaFloat.valueOf(Double.longBitsToDouble(arrP[slot])).toLuaString());
                } else {
                    String tn = (tag == BytecodeVM.TYPE_BOOLEAN) ? "boolean" : "nil";
                    throw new LuaException("invalid value (" + tn + ") at index " + idx + " in table for 'concat'");
                }
            } else {
                elem = concatValueToString(rawgetInt(idx), idx);
            }
            int len = elem.rawLength();
            if (len > 0) {
                buf = ensureConcatCap(buf, pos, len, allocCap);
                System.arraycopy(elem.rawBytesUnsafe(), elem.rawOffset(), buf, pos, len);
                pos += len;
            }
        }
        return LuaString.fromOwnedRange(buf, pos);
    }

    private static LuaString concatValueToString(LuaValue val, long idx) {
        if (val instanceof LuaString ls) {
            return ls;
        }
        if (val instanceof LuaInteger li) {
            return LuaString.fromLong(li.toLong());
        }
        if (val != null && (val.isString() || val.isNumber())) {
            return LuaString.valueOf(val.toLuaString());
        }
        String tn = val != null ? val.typeName() : "nil";
        throw new LuaException("invalid value (" + tn + ") at index " + idx + " in table for 'concat'");
    }

    private static byte[] ensureConcatCap(byte[] buf, int pos, int add, long allocCap) {
        long needed = (long) pos + add;
        if (needed > allocCap || needed > Integer.MAX_VALUE - 8) {
            throw new LuaException("resulting string too large");
        }
        if (needed <= buf.length) {
            return buf;
        }
        long doubled = Math.max((long) buf.length * 2L, needed);
        int newCap = (int) Math.min(Math.min(allocCap, Integer.MAX_VALUE - 8), doubled);
        return Arrays.copyOf(buf, newCap);
    }
}

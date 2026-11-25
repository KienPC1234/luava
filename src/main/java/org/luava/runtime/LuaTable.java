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
    private static final byte FLAG_ALL_CANONICAL = 1;
    private static final byte FLAG_LAZY_PENDING = 2;
    private static final byte FLAG_WEAK_KEYS = 4;
    private static final byte FLAG_WEAK_VALUES = 8;
    private static final byte FLAG_MAY_HAVE_GC_OR_MODE = 16;

    // Unboxed array storage: arrT holds BytecodeVM.TYPE_* (TYPE_NIL == 0, so
    // fresh byte[] slots are nil without an init loop); arrO is allocated
    // lazily only when a non-primitive object is written to the array.
    private long[] arrP;
    private byte[] arrT;
    private LuaValue[] arrO;
    private int arrLen;
    private int modCount;

    // Inline unboxed storage for the first 2 non-weak string-keyed fields
    // directly on LuaTable (zero auxiliary object allocation for 0..2 field
    // records such as {x = x, y = y}). Keys 3..4 spill to OverflowFields in
    // hashOrOverflow, and 5+ keys or non-string/weak entries spill to HashMap.
    private long hashKeyBloom;
    private long p0;
    private long p1;
    private byte flags = FLAG_ALL_CANONICAL;
    private byte inlineSize;
    private byte t0;
    private byte t1;
    private LuaString k0;
    private LuaString k1;
    private LuaValue o0;
    private LuaValue o1;
    private LuaTable metatable;
    private Object hashOrOverflow;
    private TableAux aux;

    private static final class OverflowFields {
        LuaString k2;
        LuaString k3;
        long p2;
        long p3;
        byte t2;
        byte t3;
        LuaValue o2;
        LuaValue o3;
        int size;
    }

    private record IndexHop(LuaTable mt, long mtVersion, LuaValue handler) {}

    private static final class TableAux {
        Runnable lazyFiller;
        IndexHop indexHop;
        Iterator<Map.Entry<LuaValue, LuaValue>> nextIterator;
        LuaValue lastReturnedKey;
        int rehashThreshold = 64;
    }

    private TableAux ensureAux() {
        TableAux a = this.aux;
        if (a == null) {
            a = new TableAux();
            this.aux = a;
        }
        return a;
    }

    private void bumpModCount() {
        int next = this.modCount + 1;
        this.modCount = (next >= 0) ? next : 1;
    }

    private void clearTraversalCache() {
        TableAux a = this.aux;
        if (a != null) {
            a.lastReturnedKey = null;
            a.nextIterator = null;
        }
    }

    public void setLazyFiller(Runnable filler) {
        if (filler != null) {
            ensureAux().lazyFiller = filler;
            this.flags |= FLAG_LAZY_PENDING;
        } else {
            if (this.aux != null) {
                this.aux.lazyFiller = null;
            }
            this.flags &= ~FLAG_LAZY_PENDING;
        }
    }

    boolean isLazyPending() {
        return (this.flags & FLAG_LAZY_PENDING) != 0;
    }

    void ensureFilled() {
        if ((this.flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
    }

    private void ensureFilledSlow() {
        TableAux a = this.aux;
        if (a != null && a.lazyFiller != null) {
            synchronized (this) {
                Runnable f = a.lazyFiller;
                if (f != null) {
                    a.lazyFiller = null;
                    this.flags &= ~FLAG_LAZY_PENDING;
                    f.run();
                } else {
                    this.flags &= ~FLAG_LAZY_PENDING;
                }
            }
        } else {
            this.flags &= ~FLAG_LAZY_PENDING;
        }
    }

    public LuaTable() {
        org.luava.runtime.eval.GCManager.onAlloc();
    }

    private LuaTable(LuaState ownerState) {
        if (ownerState != null) {
            ownerState.gcState.onAlloc(100);
        } else {
            org.luava.runtime.eval.GCManager.onAlloc(100);
        }
    }

    /** Fast factory for empty or small-record (`arrayCapacity == 0 && hashCapacity <= 4`) constructors. */
    public static LuaTable allocSmall(org.luava.runtime.bytecode.LuaClosure cl) {
        return new LuaTable(cl != null ? cl.getState() : null);
    }

    /** State-aware empty/small table factory avoiding ThreadLocal lookup in BytecodeVM. */
    public static LuaTable allocState(LuaState ownerState) {
        return new LuaTable(ownerState);
    }

    /** State-aware constructor avoiding ThreadLocal lookup during VM/JIT `OP_NEWTABLE`. */
    public static LuaTable allocClosure(
            org.luava.runtime.bytecode.LuaClosure cl, int arrayCapacity, int hashCapacity) {
        return new LuaTable(arrayCapacity, hashCapacity, cl != null ? cl.getState() : null);
    }

    /**
     * PUC {@code luaH_resize} for a table constructor: the array part is
     * pre-sized to {@code arrayCapacity} nil slots. Pre-sizing to the
     * constructor's list-part length is observable: it fixes {@code rawlen}
     * ({@code rawlen({1, 2, nil, 4})} is 4, not 2), the {@code #} border, and
     * therefore where {@code table.insert} puts a new element.
     */
    public LuaTable(int arrayCapacity, int hashCapacity) {
        this(arrayCapacity, hashCapacity, null);
    }

    public LuaTable(int arrayCapacity, int hashCapacity, LuaState ownerState) {
        if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
            long preBytes = (arrayCapacity > 0 ? (long) arrayCapacity * 9L : 0L)
                    + (hashCapacity > 0 ? (long) hashCapacity * 64L : 0L);
            if (preBytes > 0) {
                if (ownerState != null) {
                    ownerState.gcState.preAllocCheck(preBytes);
                } else {
                    org.luava.runtime.eval.GCManager.preAllocCheck(preBytes);
                }
            }
        }
        if (arrayCapacity > 0) {
            this.arrP = new long[arrayCapacity];
            this.arrT = new byte[arrayCapacity];
            this.arrLen = arrayCapacity;
        }
        if (hashCapacity > 4) {
            this.hashOrOverflow = new HashMap<LuaValue, LuaValue>(Math.max(16, (hashCapacity * 4) / 3 + 1));
        }
        if (ownerState != null) {
            ownerState.gcState.onAlloc(100);
        } else {
            org.luava.runtime.eval.GCManager.onAlloc(100);
        }
    }

    @Override
    public LuaType type() {
        return LuaType.TABLE;
    }

    @Override
    public boolean isTable() {
        return true;
    }

    /** VM site-cache guard; public for the bytecode package. */
    public long readVersion() {
        return metatable == null ? modCount : -1L;
    }

    public int currentModCount() {
        return modCount;
    }

    public boolean fastVersionMatch(int expectedModCount) {
        return metatable == null
                && (flags & (FLAG_LAZY_PENDING | FLAG_WEAK_VALUES)) == 0
                && modCount == expectedModCount;
    }

    /**
     * Pre-allocates backing array storage for an unguarded sequential 1-based
     * fill loop without altering {@link #arrLen} (so Lua length/iteration
     * semantics remain identical at every step).
     */
    public void reserveSequentialCapacity(long curIdx, long rem, long step) {
        if (step == 1L
                && curIdx >= 1L
                && rem >= 16L
                && curIdx == (long) this.arrLen + 1L
                && (this.flags & (FLAG_LAZY_PENDING | FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0
                && !org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
            long end = curIdx + rem;
            if (end > curIdx && end <= 2_097_152L) {
                int targetCap = (int) end;
                if (arrT == null) {
                    arrP = new long[targetCap];
                    arrT = new byte[targetCap];
                } else if (targetCap > arrT.length) {
                    arrP = Arrays.copyOf(arrP, targetCap);
                    arrT = Arrays.copyOf(arrT, targetCap);
                    if (arrO != null) {
                        arrO = Arrays.copyOf(arrO, targetCap);
                    }
                }
            }
        }
    }

    public int plainIntArrayLen() {
        return ((flags & (FLAG_LAZY_PENDING | FLAG_WEAK_VALUES)) == 0) ? arrLen : 0;
    }

    public byte[] rawArrT() {
        return arrT;
    }

    public long[] rawArrP() {
        return arrP;
    }

    /**
     * Fast metatable assignment when this table has no existing metatable and
     * {@code mt} is known to carry neither {@code __gc} nor {@code __mode}.
     */
    public boolean trySetPlainMetatable(LuaTable mt) {
        if (this.metatable == null
                && (this.flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0
                && (mt.flags & (FLAG_LAZY_PENDING | FLAG_MAY_HAVE_GC_OR_MODE)) == 0) {
            this.metatable = mt;
            bumpModCount();
            return true;
        }
        return false;
    }

    private LuaValue cachedIndexHandler(LuaTable mt) {
        long mtVersion = mt.readVersion();
        TableAux a = this.aux;
        IndexHop hop = (a != null) ? a.indexHop : null;
        if (hop != null && hop.mt == mt && hop.mtVersion == mtVersion) {
            return hop.handler;
        }
        LuaValue handler = mt.rawget(LuaValue.Meta.INDEX);
        if (mtVersion != -1L) {
            ensureAux().indexHop = new IndexHop(mt, mtVersion, handler);
        }
        return handler;
    }

    @SuppressWarnings("unchecked")
    private HashMap<LuaValue, LuaValue> hashMap() {
        Object h = this.hashOrOverflow;
        return (h instanceof HashMap<?, ?>) ? (HashMap<LuaValue, LuaValue>) h : null;
    }

    private void ensureArrayCapacity(int minCap) {
        if (arrT == null) {
            int cap = Math.max(16, minCap);
            if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
                org.luava.runtime.eval.GCManager.preAllocCheck((long) cap * 9L);
            }
            arrP = new long[cap];
            arrT = new byte[cap];
            return;
        }
        int cur = arrT.length;
        if (minCap <= cur) {
            return;
        }
        int doubled = (cur <= (Integer.MAX_VALUE >> 1)) ? Math.max(16, cur << 1) : (Integer.MAX_VALUE - 8);
        int newCap = Math.max(minCap, doubled);
        if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
            long elemBytes = (arrO != null) ? 16L : 9L;
            org.luava.runtime.eval.GCManager.preAllocCheck((long) (newCap - cur) * elemBytes);
        }
        arrP = Arrays.copyOf(arrP, newCap);
        arrT = Arrays.copyOf(arrT, newCap);
        if (arrO != null) {
            arrO = Arrays.copyOf(arrO, newCap);
        }
    }

    private void ensureArrO() {
        if (arrO == null) {
            if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
                org.luava.runtime.eval.GCManager.preAllocCheck((long) arrT.length * 8L);
            }
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
            arrO[idx0] = (isWeakValues() && isCollectable(val) && !(val instanceof WeakVal)) ? new WeakVal(val) : val;
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
            arrO[idx0] = (isWeakValues() && isCollectable(oVal) && !(oVal instanceof WeakVal))
                    ? new WeakVal(oVal) : oVal;
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

    private int totalInlineSlots() {
        int n = inlineSize;
        if (n == 0) return 0;
        if (hashOrOverflow instanceof OverflowFields ov) {
            return n + ov.size;
        }
        return n;
    }

    private int hashEntryCount() {
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map != null) {
            return map.size();
        }
        return totalInlineSlots();
    }

    private void markBloom(LuaValue key) {
        hashKeyBloom |= (1L << (key.hashCode() & 63));
    }

    private void noteStringKeyMeta(LuaString ks) {
        if (!ks.isCanonical()) {
            flags &= ~FLAG_ALL_CANONICAL;
            int len = ks.rawLength();
            if ((len == 4 && ks.equals(LuaValue.Meta.GC))
                    || (len == 6 && ks.equals(LuaValue.Meta.MODE))) {
                flags |= FLAG_MAY_HAVE_GC_OR_MODE;
            }
        } else if (ks == LuaValue.Meta.GC || ks == LuaValue.Meta.MODE) {
            flags |= FLAG_MAY_HAVE_GC_OR_MODE;
        }
    }

    public boolean hasMaybeStringKey(LuaString ks) {
        if ((flags & FLAG_LAZY_PENDING) == 0 && hashOrOverflow == null
                && (flags & FLAG_ALL_CANONICAL) != 0 && ks.isCanonical()) {
            return (k0 == ks && t0 != BytecodeVM.TYPE_NIL) || (k1 == ks && t1 != BytecodeVM.TYPE_NIL);
        }
        return (inlineSize != 0 || hashOrOverflow != null || (flags & FLAG_LAZY_PENDING) != 0)
                && ((flags & FLAG_LAZY_PENDING) != 0 || (hashKeyBloom & (1L << (ks.fastHash() & 63))) != 0L);
    }

    public boolean hasNonNilStringKey(LuaString ks) {
        if ((flags & (FLAG_LAZY_PENDING | FLAG_WEAK_VALUES)) == 0) {
            if (k0 == ks) return t0 != BytecodeVM.TYPE_NIL;
            if (k1 == ks) return t1 != BytecodeVM.TYPE_NIL;
            if (hashOrOverflow == null && (flags & FLAG_ALL_CANONICAL) != 0 && ks.isCanonical()) {
                return false;
            }
            if ((hashKeyBloom & (1L << (ks.fastHash() & 63))) == 0L) {
                return false;
            }
        }
        return !rawget(ks).isNil();
    }

    private HashMap<LuaValue, LuaValue> ensureHashPart() {
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map != null) {
            return map;
        }
        spillInlineToHash();
        map = hashMap();
        if (map == null) {
            map = new HashMap<>();
            this.hashOrOverflow = map;
        }
        return map;
    }

    private int findInlineSlot(LuaString ks) {
        int n = inlineSize;
        if (n == 0) return -1;
        if (k0 == ks) return 0;
        if (n >= 2 && k1 == ks) return 1;
        OverflowFields ov = (hashOrOverflow instanceof OverflowFields o) ? o : null;
        if (ov != null) {
            if (ov.size >= 1 && ov.k2 == ks) return 2;
            if (ov.size >= 2 && ov.k3 == ks) return 3;
        }
        if (ks.isCanonical() && (flags & FLAG_ALL_CANONICAL) != 0) {
            return -1;
        }
        if (k0.equals(ks)) return 0;
        if (n >= 2 && k1.equals(ks)) return 1;
        if (ov != null) {
            if (ov.size >= 1 && ov.k2.equals(ks)) return 2;
            if (ov.size >= 2 && ov.k3.equals(ks)) return 3;
        }
        return -1;
    }

    private void writeInlineRaw(int slot, byte tag, long prim, LuaValue obj) {
        LuaValue cleanObj = (tag == BytecodeVM.TYPE_OBJECT) ? obj : null;
        if (tag == BytecodeVM.TYPE_OBJECT && (obj == null || obj == LuaNil.NIL || obj.isNil())) {
            tag = BytecodeVM.TYPE_NIL;
            prim = 0L;
            cleanObj = null;
        } else if (tag != BytecodeVM.TYPE_OBJECT) {
            cleanObj = null;
        }
        switch (slot) {
            case 0 -> { t0 = tag; p0 = prim; o0 = cleanObj; }
            case 1 -> { t1 = tag; p1 = prim; o1 = cleanObj; }
            case 2 -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                ov.t2 = tag; ov.p2 = prim; ov.o2 = cleanObj;
            }
            default -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                ov.t3 = tag; ov.p3 = prim; ov.o3 = cleanObj;
            }
        }
    }

    private LuaString inlineKeyAt(int slot) {
        return switch (slot) {
            case 0 -> k0;
            case 1 -> k1;
            case 2 -> ((OverflowFields) hashOrOverflow).k2;
            default -> ((OverflowFields) hashOrOverflow).k3;
        };
    }

    private LuaValue inlineValAt(int slot) {
        return switch (slot) {
            case 0 -> BytecodeVM.getLuaValueFromRaw(p0, t0, o0);
            case 1 -> BytecodeVM.getLuaValueFromRaw(p1, t1, o1);
            case 2 -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                yield BytecodeVM.getLuaValueFromRaw(ov.p2, ov.t2, ov.o2);
            }
            default -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                yield BytecodeVM.getLuaValueFromRaw(ov.p3, ov.t3, ov.o3);
            }
        };
    }

    private boolean inlineGetToStack(LuaString ks, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        int slot = findInlineSlot(ks);
        if (slot < 0) {
            return false;
        }
        byte tag;
        long prim;
        LuaValue obj;
        switch (slot) {
            case 0 -> { tag = t0; prim = p0; obj = o0; }
            case 1 -> { tag = t1; prim = p1; obj = o1; }
            case 2 -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                tag = ov.t2; prim = ov.p2; obj = ov.o2;
            }
            default -> {
                OverflowFields ov = (OverflowFields) hashOrOverflow;
                tag = ov.t3; prim = ov.p3; obj = ov.o3;
            }
        }
        if (tag == BytecodeVM.TYPE_NIL) {
            return false;
        }
        tStack[dst] = tag;
        pStack[dst] = prim;
        oStack[dst] = obj;
        return true;
    }

    private void spillInlineToHash() {
        if (hashOrOverflow instanceof HashMap<?, ?>) {
            return;
        }
        int n = inlineSize;
        OverflowFields ov = (hashOrOverflow instanceof OverflowFields o) ? o : null;
        if (n == 0 && ov == null) {
            return;
        }
        HashMap<LuaValue, LuaValue> map = new HashMap<>(8);
        if (n >= 1 && t0 != BytecodeVM.TYPE_NIL) {
            map.put(k0, BytecodeVM.getLuaValueFromRaw(p0, t0, o0));
        }
        if (n >= 2 && t1 != BytecodeVM.TYPE_NIL) {
            map.put(k1, BytecodeVM.getLuaValueFromRaw(p1, t1, o1));
        }
        if (ov != null) {
            if (ov.size >= 1 && ov.t2 != BytecodeVM.TYPE_NIL) {
                map.put(ov.k2, BytecodeVM.getLuaValueFromRaw(ov.p2, ov.t2, ov.o2));
            }
            if (ov.size >= 2 && ov.t3 != BytecodeVM.TYPE_NIL) {
                map.put(ov.k3, BytecodeVM.getLuaValueFromRaw(ov.p3, ov.t3, ov.o3));
            }
        }
        inlineSize = 0;
        t0 = 0;
        t1 = 0;
        p0 = 0L;
        p1 = 0L;
        k0 = null;
        k1 = null;
        o0 = null;
        o1 = null;
        this.hashOrOverflow = map;
    }

    private void putInlineRaw(LuaString ks, byte tag, long prim, LuaValue obj) {
        noteStringKeyMeta(ks);
        boolean isNil = (tag == BytecodeVM.TYPE_NIL)
                || (tag == BytecodeVM.TYPE_OBJECT && (obj == null || obj == LuaNil.NIL || obj.isNil()));
        int slot = findInlineSlot(ks);
        if (slot >= 0) {
            writeInlineRaw(slot, tag, prim, obj);
            return;
        }
        if (isNil) {
            return;
        }
        if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP) {
            org.luava.runtime.eval.GCManager.preAllocCheck(64L);
        }
        int sz = inlineSize;
        if (sz == 0) {
            k0 = ks;
            inlineSize = 1;
            writeInlineRaw(0, tag, prim, obj);
            hashKeyBloom |= (1L << (ks.fastHash() & 63));
            return;
        }
        if (sz == 1) {
            k1 = ks;
            inlineSize = 2;
            writeInlineRaw(1, tag, prim, obj);
            hashKeyBloom |= (1L << (ks.fastHash() & 63));
            return;
        }
        OverflowFields ov = (hashOrOverflow instanceof OverflowFields o) ? o : null;
        if (ov == null) {
            ov = new OverflowFields();
            ov.k2 = ks;
            ov.size = 1;
            this.hashOrOverflow = ov;
            writeInlineRaw(2, tag, prim, obj);
            hashKeyBloom |= (1L << (ks.fastHash() & 63));
            return;
        }
        if (ov.size == 1) {
            ov.k3 = ks;
            ov.size = 2;
            writeInlineRaw(3, tag, prim, obj);
            hashKeyBloom |= (1L << (ks.fastHash() & 63));
            return;
        }
        spillInlineToHash();
        hashKeyBloom |= (1L << (ks.fastHash() & 63));
        putHashEntry(hashMap(), ks, BytecodeVM.getLuaValueFromRaw(prim, tag, obj));
    }

    private void putInlineValue(LuaString ks, LuaValue value) {
        if (value == null || value == LuaNil.NIL || value.isNil()) {
            int slot = findInlineSlot(ks);
            if (slot >= 0) {
                writeInlineRaw(slot, BytecodeVM.TYPE_NIL, 0L, null);
            }
            return;
        }
        if (value instanceof LuaInteger li) {
            putInlineRaw(ks, BytecodeVM.TYPE_INT, li.toLong(), null);
        } else if (value instanceof LuaFloat lf) {
            putInlineRaw(ks, BytecodeVM.TYPE_FLOAT, Double.doubleToRawLongBits(lf.toDouble()), null);
        } else if (value instanceof LuaBoolean lb) {
            putInlineRaw(ks, BytecodeVM.TYPE_BOOLEAN, lb.toBoolean() ? 1L : 0L, null);
        } else {
            putInlineRaw(ks, BytecodeVM.TYPE_OBJECT, 0L, value);
        }
    }

    /**
     * Specialized constructor-field store for JIT-verified fresh plain tables
     * where {@code ks} is a compile-time canonical string other than
     * {@code __gc}/{@code __mode}.
     */
    public void putFreshCanonicalFromStack(
            LuaString ks, long bloomBit, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        byte tag = tStack[src];
        if (tag == BytecodeVM.TYPE_NIL) {
            if (k0 == ks) { t0 = BytecodeVM.TYPE_NIL; p0 = 0L; o0 = null; bumpModCount(); }
            else if (k1 == ks) { t1 = BytecodeVM.TYPE_NIL; p1 = 0L; o1 = null; bumpModCount(); }
            else if (hashOrOverflow != null) { bumpModCount(); putInlineValue(ks, LuaNil.NIL); }
            return;
        }
        LuaValue obj = null;
        long prim = 0L;
        if (tag == BytecodeVM.TYPE_OBJECT) {
            obj = oStack[src];
            if (obj == null || obj == LuaNil.NIL || obj.isNil()) {
                if (k0 == ks) { t0 = BytecodeVM.TYPE_NIL; p0 = 0L; o0 = null; bumpModCount(); }
                else if (k1 == ks) { t1 = BytecodeVM.TYPE_NIL; p1 = 0L; o1 = null; bumpModCount(); }
                else if (hashOrOverflow != null) { bumpModCount(); putInlineValue(ks, LuaNil.NIL); }
                return;
            }
        } else {
            prim = pStack[src];
        }
        int sz = inlineSize;
        if (sz == 0 && hashOrOverflow == null) {
            k0 = ks;
            t0 = tag;
            p0 = prim;
            o0 = obj;
            inlineSize = 1;
            hashKeyBloom = bloomBit;
            modCount = 1;
            return;
        }
        if (sz == 1 && k0 != ks && hashOrOverflow == null) {
            k1 = ks;
            t1 = tag;
            p1 = prim;
            o1 = obj;
            inlineSize = 2;
            hashKeyBloom |= bloomBit;
            bumpModCount();
            return;
        }
        bumpModCount();
        putInlineRaw(ks, tag, prim, obj);
    }

    /** Constant-value counterpart to {@link #putFreshCanonicalFromStack}. */
    public void putFreshCanonicalConst(LuaString ks, long bloomBit, LuaValue val) {
        if (val == null || val == LuaNil.NIL || val.isNil()) {
            if (k0 == ks) { t0 = BytecodeVM.TYPE_NIL; p0 = 0L; o0 = null; bumpModCount(); }
            else if (k1 == ks) { t1 = BytecodeVM.TYPE_NIL; p1 = 0L; o1 = null; bumpModCount(); }
            else if (hashOrOverflow != null) { bumpModCount(); putInlineValue(ks, LuaNil.NIL); }
            return;
        }
        byte tag;
        long prim = 0L;
        LuaValue obj = null;
        if (val instanceof LuaInteger li) {
            tag = BytecodeVM.TYPE_INT;
            prim = li.toLong();
        } else if (val instanceof LuaFloat lf) {
            tag = BytecodeVM.TYPE_FLOAT;
            prim = Double.doubleToRawLongBits(lf.toDouble());
        } else if (val instanceof LuaBoolean lb) {
            tag = BytecodeVM.TYPE_BOOLEAN;
            prim = lb.toBoolean() ? 1L : 0L;
        } else {
            tag = BytecodeVM.TYPE_OBJECT;
            obj = val;
        }
        int sz = inlineSize;
        if (sz == 0 && hashOrOverflow == null) {
            k0 = ks;
            t0 = tag;
            p0 = prim;
            o0 = obj;
            inlineSize = 1;
            hashKeyBloom = bloomBit;
            modCount = 1;
            return;
        }
        if (sz == 1 && k0 != ks && hashOrOverflow == null) {
            k1 = ks;
            t1 = tag;
            p1 = prim;
            o1 = obj;
            inlineSize = 2;
            hashKeyBloom |= bloomBit;
            bumpModCount();
            return;
        }
        bumpModCount();
        putInlineRaw(ks, tag, prim, obj);
    }

    private void removeIntShadowFromHash(long idx) {
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map != null && !map.isEmpty()) {
            map.remove(LuaInteger.valueOf(idx));
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
        return (flags & FLAG_WEAK_KEYS) != 0;
    }

    public boolean isWeakValues() {
        return (flags & FLAG_WEAK_VALUES) != 0;
    }

    public static boolean isCollectable(LuaValue val) {
        return val != null && (val.isTable() || val.isFunction() || val.isUserdata()
                || val instanceof org.luava.runtime.concurrency.LuaCoroutine);
    }

    public void updateWeakMode() {
        if (metatable != null) {
            LuaValue mode = metatable.rawget(LuaValue.Meta.MODE);
            if (mode.isString()) {
                String s = mode.toLuaString();
                boolean wk = s.contains("k");
                boolean wv = s.contains("v");
                flags = (byte) ((flags & ~(FLAG_WEAK_KEYS | FLAG_WEAK_VALUES))
                        | (wk ? FLAG_WEAK_KEYS : 0)
                        | (wv ? FLAG_WEAK_VALUES : 0));
                if (wk || wv) {
                    org.luava.runtime.eval.GCManager.registerWeakTable(this);
                    convertToWeak();
                }
                return;
            }
        }
        flags &= ~(FLAG_WEAK_KEYS | FLAG_WEAK_VALUES);
    }

    private void convertToWeak() {
        spillInlineToHash();
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (isWeakValues()) {
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
        if (isWeakKeys() && hashPart != null) {
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
        if (!isWeakValues()) return;
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (!isWeakKeys() || hashPart == null) return;
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (hashPart == null) {
            int n = totalInlineSlots();
            for (int i = 0; i < n; i++) {
                consumer.accept(inlineKeyAt(i));
            }
        } else {
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
        bumpModCount();
        if (this.aux != null) {
            this.aux.indexHop = null;
        }
        if (mt == null) {
            flags &= ~(FLAG_WEAK_KEYS | FLAG_WEAK_VALUES);
            return;
        }
        if ((mt.flags & (FLAG_LAZY_PENDING | FLAG_MAY_HAVE_GC_OR_MODE)) == 0) {
            flags &= ~(FLAG_WEAK_KEYS | FLAG_WEAK_VALUES);
            return;
        }
        updateWeakMode();
    }

    private static LuaValue normalizeKey(LuaValue key) {
        if (key != null && key.isFloat()) {
            double d = key.toDouble();
            if (!Double.isInfinite(d) && !Double.isNaN(d)
                    && d >= (double) Long.MIN_VALUE && d < 9223372036854775808.0) {
                long l = (long) d;
                if ((double) l == d) {
                    return LuaInteger.valueOf(l);
                }
            }
        }
        return key;
    }

    public LuaValue rawgetInt(long idx) {
        if ((flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
        if (idx >= 1 && idx <= arrLen) {
            LuaValue val = readArraySlot((int) (idx - 1));
            if (val != null && !val.isNil()) {
                return val;
            }
        }
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map == null || map.isEmpty()) {
            return LuaNil.NIL;
        }
        return rawget(LuaInteger.valueOf(idx));
    }

    public void rawgetIntToStack(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if ((flags & FLAG_LAZY_PENDING) == 0 && idx >= 1 && idx <= arrLen) {
            int i = (int) (idx - 1);
            byte tag = arrT[i];
            if ((flags & FLAG_WEAK_VALUES) == 0 || tag != BytecodeVM.TYPE_OBJECT) {
                tStack[dst] = tag;
                pStack[dst] = arrP[i];
                oStack[dst] = (tag == BytecodeVM.TYPE_OBJECT && arrO != null) ? arrO[i] : null;
                return;
            }
        }
        rawgetIntToStackSlow(idx, pStack, tStack, oStack, dst);
    }

    private void rawgetIntToStackSlow(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if ((flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
        if (idx >= 1 && idx <= arrLen) {
            BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, readArraySlot((int) (idx - 1)));
            return;
        }
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map == null || map.isEmpty()) {
            tStack[dst] = BytecodeVM.TYPE_NIL;
            pStack[dst] = 0L;
            oStack[dst] = null;
            return;
        }
        BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, rawget(LuaInteger.valueOf(idx)));
    }

    public void rawsetInt(long idx, LuaValue value) {
        if ((flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
        LuaValue toSet = (value == null || value == LuaNil.NIL || value.isNil()) ? LuaNil.NIL : value;
        if (idx == arrLen + 1 && toSet != LuaNil.NIL) {
            ensureArrayCapacity(arrLen + 1);
            writeArraySlotFromValue(arrLen++, toSet);
            removeIntShadowFromHash(idx);
            return;
        } else if (idx >= 1 && idx <= arrLen) {
            removeIntShadowFromHash(idx);
            writeArraySlotFromValue((int) (idx - 1), toSet);
            return;
        }
        rawset(LuaInteger.valueOf(idx), value);
    }

    public void rawsetIntFromStack(long idx, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        if ((flags & FLAG_LAZY_PENDING) == 0 && !(hashOrOverflow instanceof HashMap<?, ?>)) {
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
        if ((flags & FLAG_LAZY_PENDING) != 0) {
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
            removeIntShadowFromHash(idx);
            return;
        } else if (idx >= 1 && idx <= arrLen) {
            removeIntShadowFromHash(idx);
            writeArraySlotFromStack((int) (idx - 1), tag, pVal, oVal);
            return;
        }
        rawset(LuaInteger.valueOf(idx), BytecodeVM.getLuaValueFromRaw(pVal, tag, oVal));
    }

    public void rawsetIntLong(long idx, long val) {
        if ((flags & FLAG_LAZY_PENDING) == 0 && !(hashOrOverflow instanceof HashMap<?, ?>) && arrO == null) {
            int len = this.arrLen;
            if (idx >= 1 && idx <= len) {
                int i = (int) (idx - 1);
                arrT[i] = BytecodeVM.TYPE_INT;
                arrP[i] = val;
                return;
            }
            byte[] at = this.arrT;
            if (idx == (long) len + 1L && at != null && len < at.length) {
                this.arrLen = len + 1;
                at[len] = BytecodeVM.TYPE_INT;
                arrP[len] = val;
                return;
            }
        }
        rawsetIntLongSlow(idx, val);
    }

    private void rawsetIntLongSlow(long idx, long val) {
        if ((flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
        if (idx == (long) arrLen + 1L) {
            ensureArrayCapacity(arrLen + 1);
            int i = arrLen++;
            arrT[i] = BytecodeVM.TYPE_INT;
            arrP[i] = val;
            if (arrO != null) arrO[i] = null;
            removeIntShadowFromHash(idx);
            return;
        }
        if (idx >= 1 && idx <= arrLen) {
            removeIntShadowFromHash(idx);
            int i = (int) (idx - 1);
            arrT[i] = BytecodeVM.TYPE_INT;
            arrP[i] = val;
            if (arrO != null) arrO[i] = null;
            return;
        }
        rawset(LuaInteger.valueOf(idx), LuaInteger.valueOf(val));
    }

    public boolean tryRawgetStringToStack(
            LuaString ks, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if ((flags & (FLAG_LAZY_PENDING | FLAG_WEAK_VALUES)) == 0) {
            if (k0 == ks) {
                byte tag = t0;
                if (tag == BytecodeVM.TYPE_NIL) return false;
                tStack[dst] = tag;
                pStack[dst] = p0;
                oStack[dst] = o0;
                return true;
            }
            if (k1 == ks) {
                byte tag = t1;
                if (tag == BytecodeVM.TYPE_NIL) return false;
                tStack[dst] = tag;
                pStack[dst] = p1;
                oStack[dst] = o1;
                return true;
            }
            if (hashOrOverflow == null && (flags & FLAG_ALL_CANONICAL) != 0 && ks.isCanonical()) {
                return false;
            }
        }
        return tryRawgetStringToStackSlow(ks, pStack, tStack, oStack, dst);
    }

    private boolean tryRawgetStringToStackSlow(
            LuaString ks, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if ((flags & FLAG_LAZY_PENDING) != 0) {
            ensureFilledSlow();
        }
        if ((flags & FLAG_WEAK_VALUES) == 0 && (hashKeyBloom & (1L << (ks.fastHash() & 63))) == 0L) {
            return false;
        }
        HashMap<LuaValue, LuaValue> map = hashMap();
        if (map == null) {
            return inlineGetToStack(ks, pStack, tStack, oStack, dst);
        }
        LuaValue val = map.get(ks);
        if (val instanceof WeakVal wv) {
            LuaValue actual = wv.get();
            if (actual == null) {
                map.remove(ks);
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

    public boolean tryRawgetToStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        if (key instanceof LuaString ks) {
            return tryRawgetStringToStack(ks, pStack, tStack, oStack, dst);
        }
        return tryRawgetToStackNonString(key, pStack, tStack, oStack, dst);
    }

    private boolean tryRawgetToStackNonString(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int dst) {
        ensureFilled();
        LuaValue val = rawget(key);
        if (val == null || val == LuaNil.NIL || val.isNil()) {
            return false;
        }
        BytecodeVM.setLuaValue(pStack, tStack, oStack, dst, val);
        return true;
    }

    public LuaValue rawget(LuaValue key) {
        if ((flags & (FLAG_LAZY_PENDING | FLAG_WEAK_VALUES)) == 0 && key instanceof LuaString ks) {
            if (k0 == ks) {
                return t0 != BytecodeVM.TYPE_NIL ? BytecodeVM.getLuaValueFromRaw(p0, t0, o0) : LuaNil.NIL;
            }
            if (k1 == ks) {
                return t1 != BytecodeVM.TYPE_NIL ? BytecodeVM.getLuaValueFromRaw(p1, t1, o1) : LuaNil.NIL;
            }
            if (hashOrOverflow == null && (flags & FLAG_ALL_CANONICAL) != 0 && ks.isCanonical()) {
                return LuaNil.NIL;
            }
            if ((hashKeyBloom & (1L << (ks.fastHash() & 63))) == 0L) {
                return LuaNil.NIL;
            }
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map == null) {
                int slot = findInlineSlot(ks);
                return slot >= 0 ? inlineValAt(slot) : LuaNil.NIL;
            }
            LuaValue val = map.get(ks);
            return val != null ? val : LuaNil.NIL;
        }
        return rawgetSlow(key);
    }

    private LuaValue rawgetSlow(LuaValue key) {
        ensureFilled();
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (key instanceof LuaString ks) {
            if (hashPart == null) {
                int slot = findInlineSlot(ks);
                return slot >= 0 ? inlineValAt(slot) : LuaNil.NIL;
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

    private static void putHashEntry(HashMap<LuaValue, LuaValue> map, LuaValue key, LuaValue val) {
        if (org.luava.runtime.eval.GCManager.ANY_MEMORY_CAP && !map.containsKey(key)) {
            org.luava.runtime.eval.GCManager.preAllocCheck(64L);
        }
        map.put(key, val);
    }

    public void rawset(LuaValue key, LuaValue value) {
        ensureFilled();
        bumpModCount();
        clearTraversalCache();
        if (key instanceof LuaString ks && (flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0) {
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map == null) {
                putInlineValue(ks, value);
                return;
            }
            noteStringKeyMeta(ks);
            if (value == null || value == LuaNil.NIL || value.isNil()) {
                if (map.containsKey(ks)) {
                    map.put(ks, LuaNil.NIL);
                }
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                putHashEntry(map, ks, value);
            }
            return;
        }
        if (key instanceof LuaString ks) {
            noteStringKeyMeta(ks);
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
        if (isWeakValues() && isCollectable(toSet)) {
            toStore = new WeakVal(toSet);
        }

        if (key.isInteger()) {
            long idx = key.toLong();
            if (idx == arrLen + 1 && !toSet.isNil()) {
                ensureArrayCapacity(arrLen + 1);
                writeArraySlotFromValue(arrLen++, toSet);
                HashMap<LuaValue, LuaValue> map = hashMap();
                if (map != null && !map.isEmpty()) map.remove(key);
                return;
            } else if (idx >= 1 && idx <= arrLen) {
                HashMap<LuaValue, LuaValue> map = hashMap();
                if (map != null && !map.isEmpty()) map.remove(key);
                writeArraySlotFromValue((int) (idx - 1), toSet);
                return;
            } else if (!toSet.isNil() && storeIntGap(idx, key, toSet)) {
                return;
            }
        }

        HashMap<LuaValue, LuaValue> map = hashMap();
        if (toSet.isNil()) {
            if (map != null && map.containsKey(key)) {
                map.put(key, LuaNil.NIL);
            }
        } else if ((flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0) {
            markBloom(key);
            putHashEntry(ensureHashPart(), key, toStore);
        } else {
            map = ensureHashPart();
            if (!map.containsKey(key)) {
                pruneDeadKeys();
            }
            LuaValue keyToStore = (isWeakKeys() && isCollectable(key)) ? new WeakKey(key) : key;
            markBloom(keyToStore);
            putHashEntry(map, keyToStore, toStore);
        }
    }

    public void rawsetFromStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        ensureFilled();
        if (key instanceof LuaString ks && (flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0) {
            bumpModCount();
            clearTraversalCache();
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map == null) {
                putInlineRaw(ks, tStack[src], pStack[src], oStack[src]);
                return;
            }
            noteStringKeyMeta(ks);
            LuaValue val = BytecodeVM.getLuaValue(pStack, tStack, oStack, src);
            if (val.isNil()) {
                if (map.containsKey(ks)) {
                    map.put(ks, LuaNil.NIL);
                }
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                putHashEntry(map, ks, val);
            }
            return;
        }
        rawset(key, BytecodeVM.getLuaValue(pStack, tStack, oStack, src));
    }

    public boolean trySetFieldFromStack(LuaValue key, long[] pStack, byte[] tStack, LuaValue[] oStack, int src) {
        if (metatable == null && (flags & (FLAG_LAZY_PENDING | FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0
                && key instanceof LuaString ks) {
            bumpModCount();
            clearTraversalCache();
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map == null) {
                putInlineRaw(ks, tStack[src], pStack[src], oStack[src]);
                return true;
            }
            noteStringKeyMeta(ks);
            LuaValue val = BytecodeVM.getLuaValue(pStack, tStack, oStack, src);
            if (val == LuaNil.NIL) {
                if (map.containsKey(ks)) map.put(ks, LuaNil.NIL);
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                putHashEntry(map, ks, val);
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
        if (metatable == null && (flags & (FLAG_LAZY_PENDING | FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0
                && key instanceof LuaString ks) {
            bumpModCount();
            clearTraversalCache();
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map == null) {
                putInlineValue(ks, val);
                return true;
            }
            noteStringKeyMeta(ks);
            if (val == null || val == LuaNil.NIL || val.isNil()) {
                if (map.containsKey(ks)) map.put(ks, LuaNil.NIL);
            } else {
                hashKeyBloom |= (1L << (ks.fastHash() & 63));
                putHashEntry(map, ks, val);
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

    private boolean storeIntGap(long idx, LuaValue key, LuaValue toStore) {
        maybeRehashForInt(idx);
        if (idx >= 1 && idx <= arrLen) {
            HashMap<LuaValue, LuaValue> map = hashMap();
            if (map != null && !map.isEmpty()) map.remove(key);
            writeArraySlotFromValue((int) (idx - 1), toStore);
            return true;
        }
        return false;
    }

    private void maybeRehashForInt(long idx) {
        if ((flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) != 0) {
            return;
        }
        long near = (long) arrLen * 2L + 2L;
        int rehashThresh = (aux != null) ? aux.rehashThreshold : 64;
        if (idx <= near || hashEntryCount() >= rehashThresh) {
            rehash(idx);
        }
    }

    private static final long MAX_ARRAY_SIZE = 1L << 24;

    private static int arrayBucket(long key) {
        if (key <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros((int) Math.min(key - 1, 0x7FFFFFFFL));
    }

    private void rehash(long pendingKey) {
        int[] nums = new int[32];
        int na = 0;
        boolean wv = isWeakValues();
        for (int i = 0; i < arrLen; i++) {
            if (arrT[i] != BytecodeVM.TYPE_NIL) {
                LuaValue v = wv ? readArraySlot(i) : null;
                if (!wv || (v != null && !v.isNil())) {
                    int b = arrayBucket(i + 1L);
                    if (b < nums.length) nums[b]++;
                    na++;
                }
            }
        }
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
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
            TableAux ta = ensureAux();
            ta.rehashThreshold = Math.max(ta.rehashThreshold * 2, hashEntryCount() + 1);
            return;
        }
        ensureArrayCapacity(optimal);
        if (arrLen < optimal) {
            Arrays.fill(arrT, arrLen, optimal, BytecodeVM.TYPE_NIL);
            arrLen = optimal;
        }
        if (hashPart != null) {
            Iterator<Map.Entry<LuaValue, LuaValue>> it = hashPart.entrySet().iterator();
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if ((flags & (FLAG_WEAK_KEYS | FLAG_WEAK_VALUES)) == 0 || hashPart == null) return;
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
                    return firstValue(handler.call(tbl, key));
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
                return firstValue(handler.call(this, this));
            }
        }
        return LuaInteger.valueOf(rawlen());
    }

    private boolean isArraySlotNil(int idx0) {
        byte tag = arrT[idx0];
        if (tag == BytecodeVM.TYPE_NIL) {
            return true;
        }
        if (isWeakValues() && tag == BytecodeVM.TYPE_OBJECT && arrO != null && arrO[idx0] instanceof WeakVal wv) {
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (hashPart == null || hashPart.isEmpty()) {
            return true;
        }
        return rawget(LuaInteger.valueOf(idx)).isNil();
    }

    public int rawlen() {
        ensureFilled();
        int n = arrLen;
        while (n > 0 && isArraySlotNil(n - 1)) {
            n--;
        }
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (hashPart == null || hashPart.isEmpty()) {
            return n;
        }
        if (n > 0 && isIntNil((long) n + 1L)) {
            return n;
        }
        return unboundSearch(n);
    }

    private int unboundSearch(long j) {
        long i = j;
        j++;
        while (!isIntNil(j)) {
            i = j;
            if (j > 0x7FFFFFFFL / 2) {
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();

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
            if (hashPart == null) {
                int n = totalInlineSlots();
                for (int i = 0; i < n; i++) {
                    LuaValue val = inlineValAt(i);
                    if (!val.isNil()) {
                        clearTraversalCache();
                        return Varargs.of(inlineKeyAt(i), val);
                    }
                }
            } else {
                Iterator<Map.Entry<LuaValue, LuaValue>> iter = hashPart.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<LuaValue, LuaValue> entry = iter.next();
                    LuaValue k = entry.getKey();
                    if (k instanceof WeakKey wk) k = wk.get();
                    if (k == null) continue;
                    LuaValue val = entry.getValue();
                    if (val instanceof WeakVal wv) val = wv.get();
                    if (val != null && !val.isNil()) {
                        TableAux ta = ensureAux();
                        ta.lastReturnedKey = k;
                        ta.nextIterator = iter;
                        return Varargs.of(k, val);
                    }
                }
            }
            clearTraversalCache();
            return Varargs.of(LuaNil.NIL);
        }

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
                if (hashPart == null) {
                    int n = totalInlineSlots();
                    for (int i = 0; i < n; i++) {
                        LuaValue val = inlineValAt(i);
                        if (!val.isNil()) {
                            clearTraversalCache();
                            return Varargs.of(inlineKeyAt(i), val);
                        }
                    }
                } else {
                    Iterator<Map.Entry<LuaValue, LuaValue>> iter = hashPart.entrySet().iterator();
                    while (iter.hasNext()) {
                        Map.Entry<LuaValue, LuaValue> entry = iter.next();
                        LuaValue hk = entry.getKey();
                        if (hk instanceof WeakKey wk) hk = wk.get();
                        if (hk == null) continue;
                        LuaValue val = entry.getValue();
                        if (val instanceof WeakVal wv) val = wv.get();
                        if (val != null && !val.isNil()) {
                            TableAux ta = ensureAux();
                            ta.lastReturnedKey = hk;
                            ta.nextIterator = iter;
                            return Varargs.of(hk, val);
                        }
                    }
                }
                clearTraversalCache();
                return Varargs.of(LuaNil.NIL);
            }
        }

        if (hashPart == null) {
            int n = totalInlineSlots();
            if (n > 0) {
                int foundIdx = -1;
                if (currentKey instanceof LuaString ks) {
                    foundIdx = findInlineSlot(ks);
                }
                if (foundIdx < 0) {
                    throw new LuaException("invalid key to 'next'");
                }
                for (int i = foundIdx + 1; i < n; i++) {
                    LuaValue val = inlineValAt(i);
                    if (!val.isNil()) {
                        return Varargs.of(inlineKeyAt(i), val);
                    }
                }
                return Varargs.of(LuaNil.NIL);
            }
            clearTraversalCache();
            throw new LuaException("invalid key to 'next'");
        }

        TableAux ta = this.aux;
        if (ta != null && ta.lastReturnedKey != null && currentKey.equals(ta.lastReturnedKey) && ta.nextIterator != null) {
            while (ta.nextIterator.hasNext()) {
                Map.Entry<LuaValue, LuaValue> entry = ta.nextIterator.next();
                LuaValue k = entry.getKey();
                if (k instanceof WeakKey wk) k = wk.get();
                if (k == null) continue;
                LuaValue val = entry.getValue();
                if (val instanceof WeakVal wv) val = wv.get();
                if (val != null && !val.isNil()) {
                    ta.lastReturnedKey = k;
                    return Varargs.of(k, val);
                }
            }
            ta.lastReturnedKey = null;
            ta.nextIterator = null;
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
                    TableAux auxOut = ensureAux();
                    auxOut.lastReturnedKey = k;
                    auxOut.nextIterator = iter;
                    return Varargs.of(k, val);
                }
            } else if (k.equals(currentKey)) {
                found = true;
            }
        }
        clearTraversalCache();
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (hashPart == null) {
            int n = totalInlineSlots();
            for (int i = 0; i < n; i++) {
                LuaValue v = inlineValAt(i);
                if (!v.isNil()) {
                    list.add(inlineKeyAt(i));
                }
            }
        } else {
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
                LuaValue res = firstValue(handler.call(this));
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
        long arrBytes = (arrT != null) ? (long) arrT.length * (arrO != null ? 16L : 9L) : 0L;
        long h = (hashOrOverflow instanceof HashMap<?, ?> map)
                ? (long) map.size() * 64L
                : (hashOrOverflow instanceof OverflowFields ? 64L : 0L);
        return 64L + arrBytes + h;
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
        HashMap<LuaValue, LuaValue> hashPart = hashMap();
        if (hashPart == null) {
            int n = totalInlineSlots();
            for (int i = 0; i < n; i++) {
                LuaValue v = inlineValAt(i);
                if (!v.isNil()) {
                    consumer.accept(inlineKeyAt(i), v);
                }
            }
        } else {
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

    public LuaString concatPlainBytes(byte[] sep, int sepOff, int sepLen,
            long start, long last, long allocCap) {
        if (last < start) {
            return LuaString.EMPTY;
        }
        long count = last - start + 1;
        long hint = Math.min(allocCap, Math.max(64L, Math.min(1L << 20, count * 8L)));
        byte[] buf = new byte[(int) hint];
        int pos = 0;
        boolean wv = isWeakValues();

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
            if (idx <= arrLen && !wv) {
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

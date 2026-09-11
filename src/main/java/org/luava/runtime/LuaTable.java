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
            LuaValue mode = metatable.rawget(LuaString.valueOf("__mode"));
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
        LuaValue toSet = (value == null || value.isNil()) ? LuaNil.NIL : value;
        if (idx == arrayPart.size() + 1 && !toSet.isNil()) {
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
        lastReturnedKey = null;
        nextIterator = null;
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
            }
        }

        if (toSet.isNil()) {
            if (hashPart.containsKey(key)) {
                hashPart.put(key, LuaNil.NIL);
            }
        } else {
            if (!hashPart.containsKey(key)) {
                pruneDeadKeys();
            }
            LuaValue keyToStore = (weakKeys && isCollectable(key)) ? new WeakKey(key) : key;
            hashPart.put(keyToStore, toStore);
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
        LuaValue t = this;
        for (int loop = 0; loop < 2000; loop++) {
            if (t instanceof LuaTable tbl) {
                LuaValue val = tbl.rawget(key);
                if (!val.isNil()) {
                    return val;
                }
                LuaTable mt = tbl.getMetatable();
                if (mt == null) return LuaNil.NIL;
                LuaValue handler = mt.rawget(LuaString.valueOf("__index"));
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
        LuaValue t = this;
        for (int loop = 0; loop < 2000; loop++) {
            if (t instanceof LuaTable tbl) {
                LuaValue existing = tbl.rawget(key);
                if (!existing.isNil() || tbl.getMetatable() == null) {
                    tbl.rawset(key, value);
                    return;
                }
                LuaTable mt = tbl.getMetatable();
                LuaValue handler = mt.rawget(LuaString.valueOf("__newindex"));
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
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__len"));
            if (!handler.isNil()) {
                org.luava.runtime.eval.CallStack.setNextCall("len", true);
                return handler.call(this, this);
            }
        }
        return LuaInteger.valueOf(rawlen());
    }

    public int rawlen() {
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
            LuaValue handler = metatable.rawget(LuaString.valueOf("__tostring"));
            if (!handler.isNil()) {
                LuaValue res = handler.call(this);
                if (!res.isString()) {
                    throw new LuaException("'__tostring' must return a string");
                }
                return res.toLuaString();
            }
            LuaValue nameVal = metatable.rawget(LuaString.valueOf("__name"));
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

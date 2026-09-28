/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.eval;

import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * An identity set for the collector's mark phase: open addressed on
 * {@link System#identityHashCode(Object)}, with the hash cached per entry.
 *
 * <p>Replaces {@code Collections.newSetFromMap(new IdentityHashMap<>())}, which
 * the mark phase used for its live and visited sets. Profiling the collector
 * showed {@code IdentityHashMap.resize}/{@code hash}/{@code put} accounting for
 * about 20% of an allocation-heavy benchmark, i.e. a fifth of the whole task
 * was spent inside the collector's own bookkeeping. The two causes are visible
 * in {@code IdentityHashMap}'s design: it grows by rehashing, and it recomputes
 * {@code System.identityHashCode} for every key on every rehash, so a large
 * live set is re-marked several times per cycle.
 *
 * <p>This set grows by doubling and reinserts from the cached hashes, and it
 * compares by identity (never by {@code equals}, never by hash alone), so an
 * identity-hash collision cannot merge two distinct objects - a mistake that
 * would either leak a dead object or collect a live one.
 *
 * <p>Capacity is retained across collections via {@link #reset()}: the live
 * set of a state is roughly stable between cycles, so the second collection
 * onward allocates nothing at all.
 */
final class IdentitySet<T> extends AbstractSet<T> {

    private static final int MIN_CAPACITY = 16;
    /** Grow at 70% load: linear probing degrades sharply past that. */
    private static final double LOAD_FACTOR = 0.7d;

    private Object[] slots;
    private int[] hashes;
    private int size;
    private int threshold;

    IdentitySet() {
        this(MIN_CAPACITY);
    }

    IdentitySet(int expected) {
        int cap = MIN_CAPACITY;
        while (cap < (int) Math.max(MIN_CAPACITY, expected / LOAD_FACTOR)) {
            cap <<= 1;
        }
        allocate(cap);
    }

    private void allocate(int cap) {
        slots = new Object[cap];
        hashes = new int[cap];
        size = 0;
        threshold = (int) (cap * LOAD_FACTOR);
    }

    /**
     * Empties the set but keeps its backing arrays, so a repeat cycle of the
     * same size allocates nothing. Shrinks only if the set had grown far past
     * the current live set, to bound the retained footprint.
     */
    void reset() {
        if (slots.length > (1 << 20) && size < (slots.length >> 3)) {
            allocate(MIN_CAPACITY);
            return;
        }
        java.util.Arrays.fill(slots, null);
        size = 0;
    }

    @Override
    public boolean add(T o) {
        if (o == null) {
            throw new NullPointerException("identity set does not accept null");
        }
        int hash = System.identityHashCode(o);
        Object[] s = slots;
        int cap = s.length;
        int mask = cap - 1;
        int i = (hash ^ (hash >>> 16)) & mask;
        Object cur;
        while ((cur = s[i]) != null) {
            if (cur == o) {
                return false;
            }
            i = (i + 1) & mask;
        }
        s[i] = o;
        hashes[i] = hash;
        size++;
        if (size >= threshold) {
            grow();
        }
        return true;
    }

    @Override
    public boolean contains(Object o) {
        if (o == null) {
            return false;
        }
        Object[] s = slots;
        int mask = s.length - 1;
        int hash = System.identityHashCode(o);
        int i = (hash ^ (hash >>> 16)) & mask;
        Object cur;
        while ((cur = s[i]) != null) {
            if (cur == o) {
                return true;
            }
            i = (i + 1) & mask;
        }
        return false;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size == 0;
    }

    @Override
    public void clear() {
        reset();
    }

    @Override
    public boolean addAll(Collection<? extends T> c) {
        boolean changed = false;
        for (T o : c) {
            changed |= add(o);
        }
        return changed;
    }

    private void grow() {
        Object[] old = slots;
        int[] oldHashes = hashes;
        int cap = old.length << 1;
        allocate(cap);
        Object[] s = slots;
        int mask = cap - 1;
        for (int k = 0; k < old.length; k++) {
            Object o = old[k];
            if (o != null) {
                int hash = oldHashes[k];
                int i = (hash ^ (hash >>> 16)) & mask;
                while (s[i] != null) {
                    i = (i + 1) & mask;
                }
                s[i] = o;
                hashes[i] = hash;
                size++;
            }
        }
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private int idx;
            private int remaining = size;

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            @Override
            @SuppressWarnings("unchecked")
            public T next() {
                if (remaining <= 0) {
                    throw new NoSuchElementException();
                }
                Object[] s = slots;
                while (idx < s.length) {
                    Object o = s[idx++];
                    if (o != null) {
                        remaining--;
                        return (T) o;
                    }
                }
                throw new NoSuchElementException();
            }
        };
    }

    /**
     * Reuses this set's storage for {@code other}, so a second mark phase
     * (after finalizer resurrection) does not reallocate. Elements already
     * present are kept, which is exactly the {@code liveAll.addAll(liveNormal)}
     * copy this replaces.
     */
    void copyInto(IdentitySet<T> other) {
        Object[] s = slots;
        for (int k = 0; k < s.length; k++) {
            @SuppressWarnings("unchecked")
            T o = (T) s[k];
            if (o != null) {
                other.add(o);
            }
        }
    }
}

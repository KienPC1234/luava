/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.eval;

import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class GCManager {
    public static final class FinalizerEntry {
        public final LuaValue target;
        public final LuaValue gcHandler;
        public final long ownerId;

        public FinalizerEntry(LuaValue target, LuaValue gcHandler) {
            this(target, gcHandler, GLOBAL_OWNER_ID);
        }

        public FinalizerEntry(LuaValue target, LuaValue gcHandler, long ownerId) {
            this.target = target;
            this.gcHandler = gcHandler;
            this.ownerId = ownerId;
        }
    }

    private static final class WeakTableEntry {
        final java.lang.ref.WeakReference<LuaTable> table;
        final long ownerId;

        WeakTableEntry(LuaTable table, long ownerId) {
            this.table = new java.lang.ref.WeakReference<>(table);
            this.ownerId = ownerId;
        }
    }

    private static final long GLOBAL_OWNER_ID = 0L;

    private static final List<FinalizerEntry> FINALIZERS = new ArrayList<>();
    private static final List<WeakTableEntry> WEAK_TABLES = new ArrayList<>();
    private static final List<StringRef> LARGE_STRINGS = new ArrayList<>();
    private static final AtomicLong uncollectedBytes = new AtomicLong();
    private static volatile boolean runningFinalizer = false;
    private static volatile boolean gcRunning = true;
    private static boolean collecting = false;
    private static volatile long gcThreshold = 256L * 1024;

    private static final class StringRef {
        final java.lang.ref.WeakReference<LuaString> ref;
        final long bytes;
        StringRef(LuaString s) {
            this.ref = new java.lang.ref.WeakReference<>(s);
            this.bytes = (long) s.value().length() + 64;
        }
    }

    private static volatile LuaString allocatingString = null;

    public static synchronized void onAllocLargeString(LuaString s) {
        if (s == null) return;
        allocatingString = s;
        LARGE_STRINGS.add(new StringRef(s));
    }

    public static synchronized long getLargeStringsBytes() {
        long total = 0;
        for (int i = LARGE_STRINGS.size() - 1; i >= 0; i--) {
            StringRef sr = LARGE_STRINGS.get(i);
            if (sr.ref.get() == null) {
                LARGE_STRINGS.remove(i);
            } else {
                total += sr.bytes;
            }
        }
        return total;
    }

    public static synchronized double getMemoryKb() {
        long base = 45 * 1024;
        long total = base + uncollectedBytes.get() + getLargeStringsBytes();
        return (double) total / 1024.0;
    }

    private GCManager() {}

    public static boolean isRunning() {
        return gcRunning;
    }

    /**
     * True while a finalizer (or a collection it triggered) is on the stack.
     * PUC makes {@code collectgarbage} fail inside a finalizer to keep the
     * collector non-reentrant; {@code gc.lua} asserts exactly that.
     */
    public static boolean inFinalizer() {
        return runningFinalizer;
    }

    public static void stop() {
        gcRunning = false;
    }

    public static void restart() {
        gcRunning = true;
    }

    private static long currentGcOwnerId() {
        LuaState active = LuaValue.activeBasicState();
        return (active != null) ? active.gcOwnerId() : GLOBAL_OWNER_ID;
    }

    /**
     * Clears all JVM-wide collector bookkeeping. This is a test-harness
     * operation. It must not be called while unrelated live states exist,
     * because unlike per-state collection it discards every state's pending
     * finalizers, weak-table registrations, and roots.
     */
    public static synchronized void reset() {
        WEAK_TABLES.clear();
        LARGE_STRINGS.clear();
        FINALIZERS.clear();
        ROOT_PROVIDERS.clear();
        STATES.clear();
        uncollectedBytes.set(0);
        gcRunning = true;
        runningFinalizer = false;
        collecting = false;
        gcThreshold = 256L * 1024;
    }

    public static synchronized void register(LuaValue target, LuaValue gcHandler) {
        if (target == null || gcHandler == null || gcHandler.isNil()) return;
        FINALIZERS.add(new FinalizerEntry(target, gcHandler, currentGcOwnerId()));
    }

    public static synchronized void registerWeakTable(LuaTable table) {
        if (table == null) return;
        WEAK_TABLES.add(new WeakTableEntry(table, currentGcOwnerId()));
    }

    public static void onAlloc() {
        onAlloc(100);
    }

    public static void onAlloc(long bytes) {
        try {
            // Allocation happens on hot paths (especially table construction),
            // so avoid taking the collector monitor for every object. Only the
            // thread that crosses the threshold inspects the work lists and can
            // trigger a collection.
            long total = uncollectedBytes.addAndGet(bytes);
            if (!gcRunning || total < gcThreshold) {
                return;
            }
            if (!uncollectedBytes.compareAndSet(total, 0)) {
                return;
            }
            // Scope automatic collection to the allocating state. Running
            // every state's finalizers / cleaning every state's weak tables
            // from here corrupts concurrent states (one suite's GC step
            // mutating another's live table). Host registrations with no
            // owning state stay global.
            LuaState active = LuaValue.activeBasicState();
            if (active != null) {
                collect(active);
            } else {
                collect();
            }
        } finally {
            allocatingString = null;
        }
    }

    public static synchronized long getUncollectedBytes() {
        return uncollectedBytes.get();
    }

    public static synchronized boolean collect() {
        return collectInternal(null, false);
    }

    /**
     * Runs a collection for one Lua state. Reachability is still checked
     * across the shared allocator, but finalizers are only run for entries
     * owned by the requesting state (as well as host registrations made
     * outside Lua execution, which have no owning state).
     */
    public static synchronized boolean collect(LuaState owner) {
        if (owner == null) {
            return collectInternal(null, false);
        }
        return collectInternal(owner, true);
    }

    private static boolean collectInternal(LuaState owner, boolean ownedFinalizersOnly) {
        if (runningFinalizer || collecting) return false;
        collecting = true;
        try {
            uncollectedBytes.set(0);

        // 1. Mark phase from normal roots (excluding dead objects with finalizers)
        Set<LuaValue> liveNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> visitedNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<LuaTable> ephemeronsNormal = new ArrayList<>();
        ArrayDeque<LuaValue> worklist = new ArrayDeque<>();

        markFromRoots(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        drainWorklist(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        convergeEphemerons(liveNormal, visitedNormal, ephemeronsNormal);

        // 2. Clear weak VALUES from weak tables before separating objects to
        // be finalized. An owned collection only touches that state's weak
        // tables: cleaning another live state's table here would mutate its
        // data mid-execution.
        for (int i = WEAK_TABLES.size() - 1; i >= 0; i--) {
            WeakTableEntry entry = WEAK_TABLES.get(i);
            LuaTable tbl = entry.table.get();
            if (tbl == null) {
                WEAK_TABLES.remove(i);
            } else if (ownedFinalizersOnly && owner != null
                    && entry.ownerId != GLOBAL_OWNER_ID
                    && entry.ownerId != owner.gcOwnerId()) {
                continue;
            } else {
                tbl.cleanupWeakValues(liveNormal::contains);
            }
        }

        // 3. Separate dead objects with finalizers. An explicit request from
        // one state must not run finalizers owned by another live state.
        List<FinalizerEntry> toRun = new ArrayList<>();
        for (int i = FINALIZERS.size() - 1; i >= 0; i--) {
            FinalizerEntry entry = FINALIZERS.get(i);
            if (liveNormal.contains(entry.target)) {
                continue;
            }
            if (ownedFinalizersOnly && owner != null
                    && entry.ownerId != GLOBAL_OWNER_ID
                    && entry.ownerId != owner.gcOwnerId()) {
                continue;
            }
            FINALIZERS.remove(i);
            toRun.add(entry);
        }

        // 4. Resurrect objects in toRun and mark all reachable from them
        Set<LuaValue> liveAll = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        liveAll.addAll(liveNormal);
        Set<Object> visitedAll = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        visitedAll.addAll(visitedNormal);
        List<LuaTable> ephemeronsAll = new ArrayList<>(ephemeronsNormal);

        for (FinalizerEntry entry : toRun) {
            if (entry.target != null && LuaTable.isCollectable(entry.target)) worklist.add(entry.target);
            if (entry.gcHandler != null && LuaTable.isCollectable(entry.gcHandler)) worklist.add(entry.gcHandler);
        }
        drainWorklist(worklist, liveAll, visitedAll, ephemeronsAll);
        convergeEphemerons(liveAll, visitedAll, ephemeronsAll);

        // 5. Clear weak KEYS from weak tables after resurrection
        for (int i = WEAK_TABLES.size() - 1; i >= 0; i--) {
            LuaTable tbl = WEAK_TABLES.get(i).table.get();
            if (tbl == null) {
                WEAK_TABLES.remove(i);
            } else {
                tbl.cleanupWeakKeys(liveAll::contains);
            }
        }

        for (int i = LARGE_STRINGS.size() - 1; i >= 0; i--) {
            StringRef sr = LARGE_STRINGS.get(i);
            LuaString str = sr.ref.get();
            if (str == null || (str != allocatingString && !liveAll.contains(str))) {
                LARGE_STRINGS.remove(i);
            }
        }

        // 6. Run finalizers in toRun. When this collection was requested by a
        // particular state, execute its finalizers under that state's basic
        // metatable scope so __gc handlers observe the same string/number
        // metatables as ordinary code in that state.
        if (!toRun.isEmpty()) {
            runningFinalizer = true;
            LuaState basicScope = (ownedFinalizersOnly && owner != null) ? owner : null;
            LuaState previousBasicScope = (basicScope != null) ? LuaValue.pushBasicState(basicScope) : null;
            try {
                for (FinalizerEntry entry : toRun) {
                    try {
                        LuaValue handler = null;
                        if (entry.target.getMetatable() != null) {
                            handler = entry.target.getMetatable().rawget(org.luava.runtime.LuaValue.Meta.GC);
                        }
                        if (handler == null || handler.isNil()) {
                            handler = entry.gcHandler;
                        }
                        if (handler != null && (handler.isFunction() || (handler.getMetatable() != null && !handler.getMetatable().rawget(org.luava.runtime.LuaValue.Meta.CALL).isNil()))) {
                            CallStack.setNextCall("__gc", "metamethod", false, true);
                            handler.call(entry.target);
                        }
                    } catch (Throwable t) {
                        // In Lua 5.4, errors in finalizers during GC do not abort the collector
                    }
                }
            } finally {
                if (basicScope != null) {
                    LuaValue.popBasicState(previousBasicScope);
                }
                runningFinalizer = false;
            }
        }

        // Dynamic GC threshold (Lua 5.4 pause semantics): scale the allocation
        // threshold with the live heap so "repeat until GC" loops exit fast
        // on small heaps while allocation-heavy workloads avoid O(N^2).
        long liveBytes = (long) liveAll.size() * 256L;
        gcThreshold = Math.max(256L * 1024, Math.min(16L * 1024 * 1024, liveBytes * 2));

        return true;
        } finally {
            collecting = false;
        }
    }

    public static synchronized boolean step(long stepSizeKb) {
        long pending = uncollectedBytes.get();
        if (pending <= 0) {
            return collect();
        }
        long stepBytes = (stepSizeKb <= 0) ? 1024 : stepSizeKb * 1024;
        if (stepBytes >= pending) {
            uncollectedBytes.set(0);
            return collect();
        } else {
            uncollectedBytes.addAndGet(-stepBytes);
            return false;
        }
    }

    public static synchronized void checkAndRunDeadFinalizers() {
        collect();
    }

    public interface RootProvider {
        void provideRoots(java.util.function.Consumer<LuaValue> consumer);
    }

    private static final List<RootProvider> ROOT_PROVIDERS = new ArrayList<>();
    private static final List<java.lang.ref.WeakReference<LuaState>> STATES = new ArrayList<>();

    public static synchronized void addRootProvider(RootProvider provider) {
        if (provider != null) {
            ROOT_PROVIDERS.add(provider);
        }
    }

    public static synchronized void registerState(LuaState state) {
        if (state != null) {
            STATES.removeIf(ref -> ref.get() == null);
            STATES.add(new java.lang.ref.WeakReference<>(state));
            gcRunning = true;
        }
    }

    public static synchronized boolean isReachable(LuaValue target) {
        if (target == null) return false;
        Set<LuaValue> liveNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> visitedNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<LuaTable> ephemeronsNormal = new ArrayList<>();
        ArrayDeque<LuaValue> worklist = new ArrayDeque<>();
        markFromRoots(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        drainWorklist(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        convergeEphemerons(liveNormal, visitedNormal, ephemeronsNormal);
        return liveNormal.contains(target);
    }

    private static boolean isTracked(LuaValue v) {
        return v != null && (LuaTable.isCollectable(v) || v instanceof LuaString);
    }

    private static void markFromRoots(ArrayDeque<LuaValue> worklist, Set<LuaValue> liveSet, Set<Object> visited, List<LuaTable> ephemerons) {
        for (int i = STATES.size() - 1; i >= 0; i--) {
            LuaState state = STATES.get(i).get();
            if (state == null) {
                STATES.remove(i);
            } else {
                if (state.getRegistry() != null) worklist.add(state.getRegistry());
                if (state.getGlobals() != null) worklist.add(state.getGlobals());
                LuaValue[] oStack = state.getObjectStack();
                if (oStack != null) {
                    int top = state.getStackTop();
                    int max = Math.min(top, oStack.length);
                    for (int s = 0; s < max; s++) {
                        LuaValue v = oStack[s];
                        if (isTracked(v)) worklist.add(v);
                    }
                }
            }
        }

        for (int i = 0; i < ROOT_PROVIDERS.size(); i++) {
            RootProvider rp = ROOT_PROVIDERS.get(i);
            if (rp != null) {
                rp.provideRoots(val -> {
                    if (isTracked(val)) worklist.add(val);
                });
            }
        }

        int depth = CallStack.depth();
        for (int i = 0; i < depth; i++) {
            CallStack.Frame frame = CallStack.getFrame(i);
            if (frame != null) {
                if (frame.function != null) {
                    worklist.add(frame.function);
                }
                if (frame.env != null) {
                    markEnvWorklist(frame.env, worklist, visited);
                }
                for (int t = 0; t < frame.temps.size(); t++) {
                    LuaValue temp = frame.temps.get(t);
                    if (isTracked(temp)) worklist.add(temp);
                }
                if (frame.cArgs != null) {
                    for (LuaValue arg : frame.cArgs) {
                        if (isTracked(arg)) worklist.add(arg);
                    }
                }
            }
        }
    }

    private static void markEnvWorklist(Environment env, ArrayDeque<LuaValue> worklist, Set<Object> visited) {
        Environment cur = env;
        while (cur != null) {
            if (!visited.add(cur)) break;
            if (cur.getGlobals() != null) {
                worklist.add(cur.getGlobals());
            }
            cur.forEachSlot(slot -> {
                LuaValue v = slot.get();
                if (isTracked(v)) {
                    worklist.add(v);
                }
            });
            cur = cur.getParent();
        }
    }

    private static void drainWorklist(ArrayDeque<LuaValue> worklist, Set<LuaValue> liveSet, Set<Object> visited, List<LuaTable> ephemerons) {
        while (!worklist.isEmpty()) {
            LuaValue val = worklist.poll();
            if (val == null || val.isNil()) continue;
            if (val.isBoolean() || val.isNumber()) continue;
            if (val instanceof LuaString) {
                liveSet.add(val);
                continue;
            }
            if (!LuaTable.isCollectable(val)) continue;
            if (!visited.add(val)) continue;
            liveSet.add(val);

            if (val instanceof LuaTable tbl) {
                if (tbl.getMetatable() != null) {
                    worklist.add(tbl.getMetatable());
                }
                if (tbl.isWeakKeys() && tbl.isWeakValues()) {
                    tbl.forEach((k, v) -> {
                        if (!LuaTable.isCollectable(k)) {
                            if (isTracked(k)) worklist.add(k);
                            if (isTracked(v) && !LuaTable.isCollectable(v)) worklist.add(v);
                        }
                    });
                    continue;
                }
                if (tbl.isWeakValues()) {
                    tbl.forEach((k, v) -> {
                        if (isTracked(k)) worklist.add(k);
                        if (isTracked(v) && !LuaTable.isCollectable(v)) worklist.add(v);
                    });
                    continue;
                }
                if (tbl.isWeakKeys()) {
                    if (!ephemerons.contains(tbl)) {
                        ephemerons.add(tbl);
                    }
                    tbl.forEach((k, v) -> {
                        if (!LuaTable.isCollectable(k)) {
                            if (isTracked(k)) worklist.add(k);
                            if (isTracked(v)) worklist.add(v);
                        } else if (liveSet.contains(k)) {
                            if (isTracked(v)) worklist.add(v);
                        }
                    });
                    continue;
                }
                tbl.forEach((k, v) -> {
                    if (isTracked(k)) worklist.add(k);
                    if (isTracked(v)) worklist.add(v);
                });
                continue;
            }

            if (val instanceof org.luava.runtime.LuaFunction fn) {
                for (org.luava.runtime.eval.Upvalue uv : fn.getUpvalues()) {
                    if (uv != null && isTracked(uv.getValue())) {
                        worklist.add(uv.getValue());
                    }
                }
                continue;
            }

            if (val instanceof org.luava.runtime.concurrency.LuaCoroutine co) {
                if (co.getEntryFunction() != null) worklist.add(co.getEntryFunction());
                CallStack.CallStackState state = co.getCallStackState();
                if (state != null) {
                    int d = state.depth();
                    for (int i = 0; i < d; i++) {
                        CallStack.Frame frame = state.getFrame(i);
                        if (frame != null) {
                            if (frame.function != null) worklist.add(frame.function);
                            if (frame.env != null) markEnvWorklist(frame.env, worklist, visited);
                            for (int t = 0; t < frame.temps.size(); t++) {
                                LuaValue temp = frame.temps.get(t);
                                if (isTracked(temp)) worklist.add(temp);
                            }
                            if (frame.cArgs != null) {
                                for (LuaValue arg : frame.cArgs) {
                                    if (isTracked(arg)) worklist.add(arg);
                                }
                            }
                        }
                    }
                }
                LuaValue[] args = co.getHandoffArgs();
                if (args != null) {
                    for (LuaValue a : args) if (isTracked(a)) worklist.add(a);
                }
                LuaValue[] res = co.getHandoffResult();
                if (res != null) {
                    for (LuaValue r : res) if (isTracked(r)) worklist.add(r);
                }
                continue;
            }

            if (val instanceof org.luava.runtime.LuaUserdata ud) {
                if (ud.getMetatable() != null) {
                    worklist.add(ud.getMetatable());
                }
                for (int i = 1; i <= ud.getNuvalue(); i++) {
                    LuaValue uv = ud.getUserValue(i);
                    if (isTracked(uv)) worklist.add(uv);
                }
                continue;
            }
        }
    }

    private static void convergeEphemerons(Set<LuaValue> liveSet, Set<Object> visited, List<LuaTable> ephemerons) {
        ArrayDeque<LuaValue> worklist = new ArrayDeque<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int idx = 0; idx < ephemerons.size(); idx++) {
                LuaTable tbl = ephemerons.get(idx);
                int beforeSize = liveSet.size();
                tbl.forEach((k, v) -> {
                    if ((!LuaTable.isCollectable(k) || liveSet.contains(k)) && isTracked(v) && !liveSet.contains(v)) {
                        worklist.add(v);
                    }
                });
                if (!worklist.isEmpty()) {
                    drainWorklist(worklist, liveSet, visited, ephemerons);
                }
                if (liveSet.size() > beforeSize) {
                    changed = true;
                }
            }
        }
    }
}

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
import org.luava.runtime.concurrency.LuaCoroutine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Per-state tracing collector and finalizer/weak-table manager with a global
 * fallback for objects constructed outside any active {@link LuaState}.
 *
 * <p>Keeping allocation debt, weak tables, finalizers, and mark scratch sets
 * per-{@link LuaState} eliminates cross-core cache-line contention on a shared
 * counter and removes JVM-wide collector locking during multi-tenant execution.
 */
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

    private static final class StringRef {
        final java.lang.ref.WeakReference<LuaString> ref;
        final long bytes;

        StringRef(LuaString s) {
            this.ref = new java.lang.ref.WeakReference<>(s);
            this.bytes = (long) s.rawLength() + 64;
        }
    }

    public interface RootProvider {
        void provideRoots(java.util.function.Consumer<LuaValue> consumer);
    }

    private static final long GLOBAL_OWNER_ID = 0L;
    /** PUC {@code GCSTEPSIZE}: the debt retired by a {@code data == 0} step. */
    private static final long SMALL_STEP_BYTES = 13L * 1024;
    private static final long BASE_MEMORY_BYTES = 45L * 1024;

    private static final Object REGISTRY_LOCK = new Object();
    private static final List<RootProvider> ROOT_PROVIDERS = new ArrayList<>();
    private static volatile boolean hasRootProviders = false;
    private static final List<java.lang.ref.WeakReference<LuaState>> STATES = new ArrayList<>();
    private static final StateGC GLOBAL_GC = new StateGC(null);
    private static final LuaString MEMORY_LIMIT_ERROR = LuaString.interned("memory limit exceeded");
    /** True once any state installed {@code maxMemoryBytes > 0}; keeps uncapped hot paths free of ThreadLocal reads. */
    public static volatile boolean ANY_MEMORY_CAP = false;

    private GCManager() {}

    /**
     * Per-state collector instance owned by a single {@link LuaState} (or the
     * shared fallback when {@code owner == null}).
     */
    public static final class StateGC {
        private final LuaState owner;
        private final List<FinalizerEntry> finalizers = new ArrayList<>();
        private final List<WeakTableEntry> weakTables = new ArrayList<>();
        private final List<StringRef> largeStrings = new ArrayList<>();
        private final Set<LuaString> allocatingLargeStrings =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final IdentitySet<LuaValue> markLive = new IdentitySet<>();
        private final IdentitySet<Object> markVisited = new IdentitySet<>();
        private final IdentitySet<LuaValue> markLiveAll = new IdentitySet<>();
        private final IdentitySet<Object> markVisitedAll = new IdentitySet<>();
        private final ArrayDeque<LuaValue> worklistScratch = new ArrayDeque<>();

        private long uncollectedBytes = 0L;
        private volatile boolean runningFinalizer = false;
        private volatile boolean gcRunning = true;
        private boolean collecting = false;
        private volatile long gcThreshold = 256L * 1024;
        private volatile long maxMemoryBytes = 0L;
        private long liveRetainedBytes = 0L;

        public StateGC(LuaState owner) {
            this.owner = owner;
        }

        public void setMaxMemoryBytes(long bytes) {
            this.maxMemoryBytes = bytes;
            if (bytes > 0) {
                ANY_MEMORY_CAP = true;
                long capThresh = Math.max(1024L, bytes / 4);
                if (gcThreshold > capThresh) {
                    gcThreshold = capThresh;
                }
            }
        }

        public long getMaxMemoryBytes() {
            return maxMemoryBytes;
        }

        public long getEstimatedLiveBytes() {
            return liveRetainedBytes;
        }

        /** Thread-confined hot-path allocation accounting for an active state. */
        public void onAlloc(long bytes) {
            long memCap = maxMemoryBytes;
            if (memCap > 0 && bytes > memCap) {
                throw new org.luava.runtime.LuaException(MEMORY_LIMIT_ERROR);
            }
            long total = uncollectedBytes + bytes;
            uncollectedBytes = total;
            if (total < gcThreshold && !(memCap > 0 && liveRetainedBytes + total > memCap)) {
                return;
            }
            if (!gcRunning && memCap <= 0) {
                return;
            }
            collectOnThreshold(bytes);
        }

        void preAllocCheck(long pendingBytes) {
            long memCap = maxMemoryBytes;
            if (memCap <= 0 || pendingBytes <= 0) {
                return;
            }
            if (pendingBytes > memCap) {
                throw new org.luava.runtime.LuaException(MEMORY_LIMIT_ERROR);
            }
            long total = uncollectedBytes + pendingBytes;
            if (total >= gcThreshold || liveRetainedBytes + total > memCap) {
                uncollectedBytes = total;
                collectOnThreshold(pendingBytes);
            } else {
                uncollectedBytes = total;
            }
        }

        synchronized void onAllocSynchronized(long bytes) {
            onAlloc(bytes);
        }

        private synchronized void collectOnThreshold(long pendingAllocBytes) {
            long memCap = maxMemoryBytes;
            if (memCap > 0 && pendingAllocBytes > memCap) {
                throw new org.luava.runtime.LuaException(MEMORY_LIMIT_ERROR);
            }
            if ((!gcRunning && memCap <= 0)
                    || (uncollectedBytes < gcThreshold && !(memCap > 0 && liveRetainedBytes + uncollectedBytes > memCap))) {
                return;
            }
            if (collecting) {
                return;
            }
            if (memCap <= 0 && weakTables.isEmpty() && finalizers.isEmpty() && largeStrings.isEmpty()) {
                uncollectedBytes = 0;
                return;
            }
            uncollectedBytes = 0;
            collectInternal();
            if (memCap > 0 && (liveRetainedBytes > memCap || pendingAllocBytes > (memCap - liveRetainedBytes))) {
                throw new org.luava.runtime.LuaException(MEMORY_LIMIT_ERROR);
            }
        }

        public synchronized void beginAllocLargeString(LuaString s) {
            if (s == null) return;
            allocatingLargeStrings.add(s);
            largeStrings.add(new StringRef(s));
        }

        public synchronized void endAllocLargeString(LuaString s) {
            if (s == null) return;
            allocatingLargeStrings.remove(s);
        }

        public synchronized long getLargeStringsBytes() {
            long total = 0;
            for (int i = largeStrings.size() - 1; i >= 0; i--) {
                StringRef sr = largeStrings.get(i);
                if (sr.ref.get() == null) {
                    largeStrings.remove(i);
                } else {
                    total += sr.bytes;
                }
            }
            return total;
        }

        public synchronized long getUncollectedBytes() {
            return uncollectedBytes;
        }

        public synchronized double getMemoryKb() {
            long total = BASE_MEMORY_BYTES + uncollectedBytes + getLargeStringsBytes();
            return (double) total / 1024.0;
        }

        public synchronized void register(LuaValue target, LuaValue gcHandler) {
            if (target == null || gcHandler == null || gcHandler.isNil()) return;
            long id = (owner != null) ? owner.gcOwnerId() : GLOBAL_OWNER_ID;
            finalizers.add(new FinalizerEntry(target, gcHandler, id));
        }

        public synchronized void registerWeakTable(LuaTable table) {
            if (table == null) return;
            long id = (owner != null) ? owner.gcOwnerId() : GLOBAL_OWNER_ID;
            weakTables.add(new WeakTableEntry(table, id));
        }

        public synchronized boolean collect() {
            return collectInternal();
        }

        public synchronized boolean step(long stepSizeKb) {
            if (stepSizeKb > 0) {
                uncollectedBytes += stepSizeKb * 1024;
                if (uncollectedBytes < gcThreshold) {
                    return false;
                }
                uncollectedBytes = 0;
                return collectInternal();
            }
            long pending = uncollectedBytes;
            if (pending <= 0) {
                return false;
            }
            if (pending <= SMALL_STEP_BYTES) {
                uncollectedBytes = 0;
                return collectInternal();
            }
            uncollectedBytes -= SMALL_STEP_BYTES;
            return false;
        }

        public synchronized void reset() {
            weakTables.clear();
            largeStrings.clear();
            allocatingLargeStrings.clear();
            finalizers.clear();
            uncollectedBytes = 0;
            gcRunning = true;
            runningFinalizer = false;
            collecting = false;
            liveRetainedBytes = 0L;
            long baseThresh = 256L * 1024;
            gcThreshold = (maxMemoryBytes > 0) ? Math.min(baseThresh, Math.max(1024L, maxMemoryBytes / 4)) : baseThresh;
        }

        public synchronized void closeAndDrainFinalizers() {
            weakTables.clear();
            largeStrings.clear();
            allocatingLargeStrings.clear();
            if (finalizers.isEmpty()) {
                return;
            }
            List<FinalizerEntry> toRun = new ArrayList<>(finalizers.size());
            for (int i = finalizers.size() - 1; i >= 0; i--) {
                toRun.add(finalizers.get(i));
            }
            finalizers.clear();
            boolean prevRunningFinalizer = runningFinalizer;
            runningFinalizer = true;
            LuaState prevBasic = (owner != null) ? LuaValue.pushBasicState(owner) : null;
            try {
                runFinalizers(toRun);
            } finally {
                if (owner != null) {
                    LuaValue.popBasicState(prevBasic);
                }
                runningFinalizer = prevRunningFinalizer;
            }
        }

        private boolean collectInternal() {
            if (runningFinalizer || collecting) return false;
            collecting = true;
            try {
                uncollectedBytes = 0;

                IdentitySet<LuaValue> liveNormal = markLive;
                IdentitySet<Object> visitedNormal = markVisited;
                liveNormal.reset();
                visitedNormal.reset();
                List<LuaTable> ephemeronsNormal = new ArrayList<>();
                ArrayDeque<LuaValue> worklist = worklistScratch;
                worklist.clear();

                markFromRoots(owner, worklist, visitedNormal, ephemeronsNormal);
                drainWorklist(worklist, liveNormal, visitedNormal, ephemeronsNormal);
                convergeEphemerons(liveNormal, visitedNormal, ephemeronsNormal);

                for (int i = weakTables.size() - 1; i >= 0; i--) {
                    WeakTableEntry entry = weakTables.get(i);
                    LuaTable tbl = entry.table.get();
                    if (tbl == null) {
                        weakTables.remove(i);
                    } else {
                        tbl.cleanupWeakValues(liveNormal::contains);
                    }
                }

                List<FinalizerEntry> toRun = new ArrayList<>();
                for (int i = finalizers.size() - 1; i >= 0; i--) {
                    FinalizerEntry entry = finalizers.get(i);
                    if (liveNormal.contains(entry.target)) {
                        continue;
                    }
                    finalizers.remove(i);
                    toRun.add(entry);
                }

                IdentitySet<LuaValue> liveAll = markLiveAll;
                IdentitySet<Object> visitedAll = markVisitedAll;
                liveAll.reset();
                visitedAll.reset();
                liveNormal.copyInto(liveAll);
                visitedNormal.copyInto(visitedAll);
                List<LuaTable> ephemeronsAll = new ArrayList<>(ephemeronsNormal);

                for (FinalizerEntry entry : toRun) {
                    if (entry.target != null && LuaTable.isCollectable(entry.target)) worklist.add(entry.target);
                    if (entry.gcHandler != null && LuaTable.isCollectable(entry.gcHandler)) worklist.add(entry.gcHandler);
                }
                drainWorklist(worklist, liveAll, visitedAll, ephemeronsAll);
                convergeEphemerons(liveAll, visitedAll, ephemeronsAll);

                for (int i = weakTables.size() - 1; i >= 0; i--) {
                    LuaTable tbl = weakTables.get(i).table.get();
                    if (tbl == null) {
                        weakTables.remove(i);
                    } else {
                        tbl.cleanupWeakKeys(liveAll::contains);
                    }
                }

                for (int i = largeStrings.size() - 1; i >= 0; i--) {
                    StringRef sr = largeStrings.get(i);
                    LuaString str = sr.ref.get();
                    if (str == null || (!allocatingLargeStrings.contains(str) && !liveAll.contains(str))) {
                        largeStrings.remove(i);
                    }
                }

                if (!toRun.isEmpty()) {
                    runningFinalizer = true;
                    LuaState previousBasicScope = (owner != null) ? LuaValue.pushBasicState(owner) : null;
                    try {
                        runFinalizers(toRun);
                    } finally {
                        if (owner != null) {
                            LuaValue.popBasicState(previousBasicScope);
                        }
                        runningFinalizer = false;
                    }
                }

                long liveBytes = (long) liveAll.size() * 256L;
                long baseThresh = Math.max(256L * 1024, Math.min(16L * 1024 * 1024, liveBytes * 2));
                long memCap = maxMemoryBytes;
                if (memCap > 0) {
                    long retained = 0L;
                    for (LuaValue v : liveAll) {
                        if (v instanceof LuaTable tbl) {
                            retained += tbl.estimatedMemoryBytes();
                        } else if (v instanceof LuaString str) {
                            retained += 32L + str.rawLength();
                        } else {
                            retained += 32L;
                        }
                    }
                    liveRetainedBytes = retained;
                    long headroom = Math.max(1024L, (memCap - retained) / 4);
                    gcThreshold = Math.min(baseThresh, headroom);
                } else {
                    liveRetainedBytes = 0L;
                    gcThreshold = baseThresh;
                }
                return true;
            } finally {
                markLive.reset();
                markVisited.reset();
                markLiveAll.reset();
                markVisitedAll.reset();
                worklistScratch.clear();
                collecting = false;
            }
        }
    }

    private static StateGC currentCollector() {
        LuaState active = LuaValue.activeBasicState();
        return (active != null) ? active.gcState : GLOBAL_GC;
    }

    public static void beginAllocLargeString(LuaString s) {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            active.gcState.beginAllocLargeString(s);
        } else {
            GLOBAL_GC.beginAllocLargeString(s);
        }
    }

    public static void endAllocLargeString(LuaString s) {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            active.gcState.endAllocLargeString(s);
        } else {
            GLOBAL_GC.endAllocLargeString(s);
        }
    }

    public static long getLargeStringsBytes() {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            return active.gcState.getLargeStringsBytes();
        }
        long total = GLOBAL_GC.getLargeStringsBytes();
        for (LuaState s : snapshotLiveStates()) {
            total += s.gcState.getLargeStringsBytes();
        }
        return total;
    }

    public static double getMemoryKb() {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            return active.gcState.getMemoryKb();
        }
        long total = BASE_MEMORY_BYTES + GLOBAL_GC.getUncollectedBytes() + GLOBAL_GC.getLargeStringsBytes();
        for (LuaState s : snapshotLiveStates()) {
            total += s.gcState.getUncollectedBytes() + s.gcState.getLargeStringsBytes();
        }
        return (double) total / 1024.0;
    }

    public static double getMemoryKb(LuaState state) {
        return (state != null) ? state.gcState.getMemoryKb() : getMemoryKb();
    }

    public static boolean isRunning() {
        return currentCollector().gcRunning;
    }

    /**
     * True while a finalizer (or a collection it triggered) is on the stack.
     * PUC makes {@code collectgarbage} fail inside a finalizer to keep the
     * collector non-reentrant; {@code gc.lua} asserts exactly that.
     */
    public static boolean inFinalizer() {
        return currentCollector().runningFinalizer;
    }

    public static void stop() {
        currentCollector().gcRunning = false;
    }

    public static void restart() {
        currentCollector().gcRunning = true;
    }

    /**
     * Clears all JVM-wide collector bookkeeping. Test-harness operation.
     */
    public static void reset() {
        List<LuaState> states;
        synchronized (REGISTRY_LOCK) {
            states = snapshotLiveStatesLocked();
            STATES.clear();
            ROOT_PROVIDERS.clear();
            hasRootProviders = false;
        }
        GLOBAL_GC.reset();
        for (LuaState s : states) {
            s.gcState.reset();
        }
    }

    public static void register(LuaValue target, LuaValue gcHandler) {
        currentCollector().register(target, gcHandler);
    }

    public static void registerWeakTable(LuaTable table) {
        currentCollector().registerWeakTable(table);
    }

    public static void onAlloc() {
        onAlloc(100);
    }

    public static void onAlloc(long bytes) {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            active.gcState.onAlloc(bytes);
        } else {
            GLOBAL_GC.onAllocSynchronized(bytes);
        }
    }

    /**
     * Checks and accounts for a large backing allocation before allocating its
     * storage when any state enforces {@code maxMemoryBytes}.
     */
    public static void preAllocCheck(long pendingBytes) {
        if (!ANY_MEMORY_CAP || pendingBytes <= 0) {
            return;
        }
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            active.gcState.preAllocCheck(pendingBytes);
        }
    }

    public static long getUncollectedBytes() {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            return active.gcState.getUncollectedBytes();
        }
        long total = GLOBAL_GC.getUncollectedBytes();
        for (LuaState s : snapshotLiveStates()) {
            total += s.gcState.getUncollectedBytes();
        }
        return total;
    }

    public static long getUncollectedBytes(LuaState state) {
        return (state != null) ? state.gcState.getUncollectedBytes() : getUncollectedBytes();
    }

    public static boolean collect() {
        LuaState active = LuaValue.activeBasicState();
        if (active != null) {
            return active.gcState.collect();
        }
        boolean ran = GLOBAL_GC.collect();
        for (LuaState s : snapshotLiveStates()) {
            ran |= s.gcState.collect();
        }
        return ran;
    }

    /**
     * Runs a collection for one Lua state without locking unrelated states.
     */
    public static boolean collect(LuaState owner) {
        if (owner == null) {
            return collect();
        }
        return owner.gcState.collect();
    }

    /**
     * PUC {@code lua_gc(LUA_GCSTEP, data)} scoped to the active state.
     */
    public static boolean step(long stepSizeKb) {
        return currentCollector().step(stepSizeKb);
    }

    public static void checkAndRunDeadFinalizers() {
        collect();
    }

    public static void addRootProvider(RootProvider provider) {
        if (provider != null) {
            synchronized (REGISTRY_LOCK) {
                ROOT_PROVIDERS.add(provider);
                hasRootProviders = true;
            }
        }
    }

    public static void registerState(LuaState state) {
        if (state != null) {
            synchronized (REGISTRY_LOCK) {
                STATES.removeIf(ref -> ref.get() == null);
                STATES.add(new java.lang.ref.WeakReference<>(state));
            }
            state.gcState.gcRunning = true;
        }
    }

    /**
     * Drops bookkeeping references to {@code state} and drains/runs finalizers
     * owned by {@code state} without taking any lock on unrelated states.
     */
    public static void unregisterState(LuaState state) {
        if (state == null) {
            return;
        }
        synchronized (REGISTRY_LOCK) {
            STATES.removeIf(ref -> ref.get() == null || ref.get() == state);
        }
        state.gcState.closeAndDrainFinalizers();
    }

    public static boolean isReachable(LuaValue target) {
        if (target == null) return false;
        LuaState active = LuaValue.activeBasicState();
        IdentitySet<LuaValue> liveNormal = new IdentitySet<>();
        IdentitySet<Object> visitedNormal = new IdentitySet<>();
        List<LuaTable> ephemeronsNormal = new ArrayList<>();
        ArrayDeque<LuaValue> worklist = new ArrayDeque<>();
        markFromRoots(active, worklist, visitedNormal, ephemeronsNormal);
        drainWorklist(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        convergeEphemerons(liveNormal, visitedNormal, ephemeronsNormal);
        return liveNormal.contains(target);
    }

    private static List<LuaState> snapshotLiveStates() {
        synchronized (REGISTRY_LOCK) {
            return snapshotLiveStatesLocked();
        }
    }

    private static List<LuaState> snapshotLiveStatesLocked() {
        List<LuaState> out = new ArrayList<>(STATES.size());
        for (int i = STATES.size() - 1; i >= 0; i--) {
            LuaState s = STATES.get(i).get();
            if (s == null) {
                STATES.remove(i);
            } else {
                out.add(s);
            }
        }
        return out;
    }

    private static List<RootProvider> snapshotRootProviders() {
        synchronized (REGISTRY_LOCK) {
            return new ArrayList<>(ROOT_PROVIDERS);
        }
    }

    /** Executes finalizer entries safely, swallowing errors per Lua 5.4 GC semantics. */
    private static void runFinalizers(List<FinalizerEntry> toRun) {
        for (FinalizerEntry entry : toRun) {
            try {
                LuaValue handler = null;
                if (entry.target.getMetatable() != null) {
                    handler = entry.target.getMetatable().rawget(LuaValue.Meta.GC);
                }
                if (handler == null || handler.isNil()) {
                    handler = entry.gcHandler;
                }
                if (handler != null && (handler.isFunction()
                        || (handler.getMetatable() != null && !handler.getMetatable().rawget(LuaValue.Meta.CALL).isNil()))) {
                    CallStack.setNextCall("__gc", "metamethod", false, true);
                    handler.call(entry.target);
                }
            } catch (Throwable t) {
                // In Lua 5.4, errors in finalizers during GC/close do not abort remaining finalizers.
            }
        }
    }

    private static boolean isTracked(LuaValue v) {
        return v != null && (LuaTable.isCollectable(v) || v instanceof LuaString);
    }

    private static void addStateRoots(LuaState state, ArrayDeque<LuaValue> worklist) {
        if (state.getRegistry() != null) worklist.add(state.getRegistry());
        if (state.getGlobals() != null) worklist.add(state.getGlobals());
        addThreadStackRoots(state.getMainThread(), worklist);
        LuaCoroutine current = LuaCoroutine.running();
        if (current != null && current != state.getMainThread()) {
            addThreadStackRoots(current, worklist);
        }
    }

    private static void addThreadStackRoots(LuaCoroutine thread, ArrayDeque<LuaValue> worklist) {
        if (thread == null) return;
        LuaValue[] oStack = thread.getObjectStack();
        if (oStack != null) {
            int top = thread.getStackTop();
            int max = Math.min(top, oStack.length);
            for (int s = 0; s < max; s++) {
                LuaValue v = oStack[s];
                if (isTracked(v)) worklist.add(v);
            }
        }
    }

    private static void markFromRoots(LuaState scopedOwner, ArrayDeque<LuaValue> worklist,
            Set<Object> visited, List<LuaTable> ephemerons) {
        if (scopedOwner != null) {
            addStateRoots(scopedOwner, worklist);
        } else {
            for (LuaState s : snapshotLiveStates()) {
                addStateRoots(s, worklist);
            }
        }

        if (hasRootProviders) {
            for (RootProvider rp : snapshotRootProviders()) {
                if (rp != null) {
                    rp.provideRoots(val -> {
                        if (isTracked(val)) worklist.add(val);
                    });
                }
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

            if (val instanceof LuaCoroutine co) {
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

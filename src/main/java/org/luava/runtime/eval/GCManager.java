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

public final class GCManager {
    public static final class FinalizerEntry {
        public final LuaValue target;
        public final LuaValue gcHandler;

        public FinalizerEntry(LuaValue target, LuaValue gcHandler) {
            this.target = target;
            this.gcHandler = gcHandler;
        }
    }

    private static final List<FinalizerEntry> FINALIZERS = new ArrayList<>();
    private static final List<java.lang.ref.WeakReference<LuaTable>> WEAK_TABLES = new ArrayList<>();
    private static final List<StringRef> LARGE_STRINGS = new ArrayList<>();
    private static int allocCount = 0;
    private static long uncollectedBytes = 0;
    private static boolean runningFinalizer = false;
    private static volatile boolean gcRunning = true;

    private static final class StringRef {
        final java.lang.ref.WeakReference<LuaString> ref;
        final long bytes;
        StringRef(LuaString s) {
            this.ref = new java.lang.ref.WeakReference<>(s);
            this.bytes = (long) s.value().length() + 64;
        }
    }

    public static synchronized void onAllocLargeString(LuaString s) {
        if (s == null) return;
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
        long total = base + uncollectedBytes + getLargeStringsBytes();
        return (double) total / 1024.0;
    }

    private GCManager() {}

    public static boolean isRunning() {
        return gcRunning;
    }

    public static void stop() {
        gcRunning = false;
    }

    public static void restart() {
        gcRunning = true;
    }

    public static synchronized void reset() {
        WEAK_TABLES.clear();
        LARGE_STRINGS.clear();
        FINALIZERS.clear();
        ROOT_PROVIDERS.clear();
        STATES.clear();
        uncollectedBytes = 0;
        allocCount = 0;
        gcRunning = true;
        runningFinalizer = false;
    }

    public static synchronized void register(LuaValue target, LuaValue gcHandler) {
        if (target == null || gcHandler == null || gcHandler.isNil()) return;
        FINALIZERS.add(new FinalizerEntry(target, gcHandler));
    }

    public static synchronized void registerWeakTable(LuaTable table) {
        if (table == null) return;
        WEAK_TABLES.add(new java.lang.ref.WeakReference<>(table));
    }

    public static synchronized void onAlloc() {
        onAlloc(100);
    }

    public static synchronized void onAlloc(long bytes) {
        uncollectedBytes += bytes;
        if (!gcRunning) return;
        allocCount++;
        if (allocCount >= 100) {
            allocCount = 0;
            if ((!FINALIZERS.isEmpty() || !WEAK_TABLES.isEmpty()) && !runningFinalizer) {
                collect();
            }
        }
    }

    public static synchronized long getUncollectedBytes() {
        return uncollectedBytes;
    }

    public static synchronized boolean collect() {
        if (runningFinalizer) return false;
        uncollectedBytes = 0;

        // 1. Mark phase from normal roots (excluding dead objects with finalizers)
        Set<LuaValue> liveNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> visitedNormal = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        List<LuaTable> ephemeronsNormal = new ArrayList<>();
        ArrayDeque<LuaValue> worklist = new ArrayDeque<>();

        markFromRoots(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        drainWorklist(worklist, liveNormal, visitedNormal, ephemeronsNormal);
        convergeEphemerons(liveNormal, visitedNormal, ephemeronsNormal);

        // 2. Clear weak VALUES from weak tables before separating objects to be finalized
        for (int i = WEAK_TABLES.size() - 1; i >= 0; i--) {
            LuaTable tbl = WEAK_TABLES.get(i).get();
            if (tbl == null) {
                WEAK_TABLES.remove(i);
            } else {
                tbl.cleanupWeakValues(liveNormal::contains);
            }
        }

        // 3. Separate dead objects with finalizers
        List<FinalizerEntry> toRun = new ArrayList<>();
        for (int i = FINALIZERS.size() - 1; i >= 0; i--) {
            FinalizerEntry entry = FINALIZERS.get(i);
            if (!liveNormal.contains(entry.target)) {
                FINALIZERS.remove(i);
                toRun.add(entry);
            }
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
            LuaTable tbl = WEAK_TABLES.get(i).get();
            if (tbl == null) {
                WEAK_TABLES.remove(i);
            } else {
                tbl.cleanupWeakKeys(liveAll::contains);
            }
        }

        for (int i = LARGE_STRINGS.size() - 1; i >= 0; i--) {
            StringRef sr = LARGE_STRINGS.get(i);
            LuaString str = sr.ref.get();
            if (str == null || !liveAll.contains(str)) {
                LARGE_STRINGS.remove(i);
            }
        }

        // 6. Run finalizers in toRun
        if (!toRun.isEmpty()) {
            runningFinalizer = true;
            try {
                for (FinalizerEntry entry : toRun) {
                    try {
                        LuaValue handler = null;
                        if (entry.target.getMetatable() != null) {
                            handler = entry.target.getMetatable().rawget(LuaString.valueOf("__gc"));
                        }
                        if (handler == null || handler.isNil()) {
                            handler = entry.gcHandler;
                        }
                        if (handler != null && (handler.isFunction() || (handler.getMetatable() != null && !handler.getMetatable().rawget(LuaString.valueOf("__call")).isNil()))) {
                            CallStack.setNextCall("__gc", "metamethod", false, true);
                            handler.call(entry.target);
                        }
                    } catch (Throwable t) {
                        // In Lua 5.4, errors in finalizers during GC do not abort the collector
                    }
                }
            } finally {
                runningFinalizer = false;
            }
        }

        // Clear dead large strings
        for (int i = LARGE_STRINGS.size() - 1; i >= 0; i--) {
            StringRef sr = LARGE_STRINGS.get(i);
            LuaString ls = sr.ref.get();
            if (ls == null || !liveNormal.contains(ls)) {
                LARGE_STRINGS.remove(i);
            }
        }

        return true;
    }

    public static synchronized boolean step(long stepSizeKb) {
        if (uncollectedBytes <= 0) {
            return collect();
        }
        long stepBytes = (stepSizeKb <= 0) ? 1024 : stepSizeKb * 1024;
        if (stepBytes >= uncollectedBytes) {
            uncollectedBytes = 0;
            return collect();
        } else {
            uncollectedBytes -= stepBytes;
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

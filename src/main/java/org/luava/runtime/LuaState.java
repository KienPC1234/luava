/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.JavaFunctionBuilder;
import org.luava.binding.LuaDataConverter;
import org.luava.binding.ModuleBinder;
import org.luava.emmydoc.EmmyDocGenerator;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.eval.Environment;
import org.luava.runtime.standard.BaseLib;
import org.luava.runtime.standard.CoroutineLib;
import org.luava.runtime.standard.MathLib;
import org.luava.runtime.standard.PackageLib;
import org.luava.runtime.standard.StringLib;
import org.luava.runtime.standard.TableLib;
import org.luava.runtime.standard.Utf8Lib;
import org.luava.runtime.standard.OsLib;
import org.luava.runtime.standard.IoLib;
import org.luava.runtime.standard.DebugLib;
import org.luava.runtime.concurrency.LuaCoroutine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Lua universe: globals, registry, main thread, allocator caps, security
 * policy, and value-type metatables.
 *
 * <p>One state supports many coroutines and nested calls, but concurrent
 * top-level {@code eval} calls on the same state from different host threads
 * are not supported. Use one {@code LuaState} per host thread/tenant for
 * concurrent servers; states do not share globals, security policy, value
 * metatables, or explicit finalizer ownership.
 */
public final class LuaState implements AutoCloseable {
    private static final AtomicLong NEXT_GC_OWNER_ID = new AtomicLong(1);
    private final long gcOwnerId = NEXT_GC_OWNER_ID.getAndIncrement();
    /**
     * Additive host-observability counters (W4 embedding/ops). Incremented
     * with behavior-neutral one-liners on existing paths; they never alter
     * control flow. Read via {@link #stats()}.
     */
    private final AtomicLong statsCalls = new AtomicLong();
    private final AtomicLong statsInstructions = new AtomicLong();
    public volatile boolean hasActiveHooks = false;
    private final java.util.concurrent.atomic.AtomicInteger activeHookCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public void updateHookPresence(boolean wasActive, boolean nowActive) {
        if (wasActive == nowActive) {
            return;
        }
        if (nowActive) {
            activeHookCount.incrementAndGet();
            hasActiveHooks = true;
        } else if (activeHookCount.decrementAndGet() <= 0) {
            activeHookCount.set(0);
            hasActiveHooks = false;
        }
    }
    /** Set by {@link #close()}; further entries fail fast via {@link #ensureOpen()}. */
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile boolean closed;
    /** Per-state garbage collector bookkeeping (zero cross-state lock/counter contention). */
    public final org.luava.runtime.eval.GCManager.StateGC gcState =
            new org.luava.runtime.eval.GCManager.StateGC(this);
    /** Per-state JIT snapshot buffer avoiding {@code ThreadLocal} lookups on windowed calls. */
    public final org.luava.runtime.jit.JitRuntime.SnapshotStack jitSnapshotStack =
            new org.luava.runtime.jit.JitRuntime.SnapshotStack();
    /** Tracks the root host thread currently executing on this state to detect unsupported concurrent use. */
    private final AtomicReference<Thread> executingRootThread = new AtomicReference<>();
    private int executingDepth = 0;
    public enum AsyncRejectionPolicy {
        /** Fail immediately with {@link LuaException} when pending tasks reach {@code maxPendingAsyncTasks}. */
        REJECT,
        /** Block caller threads until a pending async task completes (or state closes). */
        CALLER_BLOCKS
    }

    private final Object asyncLock = new Object();
    private CompletableFuture<Void> asyncTail = CompletableFuture.completedFuture(null);
    private final java.util.concurrent.atomic.AtomicInteger pendingAsyncTasks =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile int maxPendingAsyncTasks = ExecutionPolicy.DEFAULT_MAX_PENDING_ASYNC_TASKS;
    private volatile AsyncRejectionPolicy asyncRejectionPolicy = AsyncRejectionPolicy.REJECT;
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean executedOrLoaded = false;
    private volatile boolean sandboxed = false;
    private volatile boolean tainted = false;
    private volatile boolean poolIdle = false;
    private final java.util.concurrent.atomic.AtomicLong leaseGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final AtomicReference<LuaStatePool> owningPool = new AtomicReference<>();
    private final LuaTable globals = new LuaTable();
    private final LuaTable registry = new LuaTable();
    private final LuaCoroutine mainThread = LuaCoroutine.createMainThread();
    private volatile long maxMemoryBytes = 0L;
    private volatile int maxCoroutines = 0;
    private volatile int maxCallDepth = 0;
    public volatile boolean cancelled = false;
    private final java.util.concurrent.atomic.AtomicInteger liveCoroutines = new java.util.concurrent.atomic.AtomicInteger();
    private final EnumMap<LuaType, LuaTable> basicMetatables = new EnumMap<>(LuaType.class);
    private final Environment rootEnvironment;
    private final List<ModuleBinder.ModuleInfo> registeredModules = new ArrayList<>();
    /**
     * Standard-library tables by module name, recorded when the state is
     * built. {@code package.loaded} is seeded from this map, not from the
     * current globals: PUC registers every opened stdlib in
     * {@code package.loaded} at startup, so `debug = nil; require"debug"`
     * must still resolve. Reading the globals at package-fill time would miss
     * any library the script had already nil'd (the real `all.lua` setup).
     */
    private final Map<String, LuaTable> standardLibs = new java.util.LinkedHashMap<>();
    private LuaValue savedLoadfile;
    private LuaValue savedDofile;

    /** Composite key for {@link #protoCache} avoiding full-source string concatenation. */
    private static final class ProtoCacheKey {
        final String chunkName;
        final String source;
        final int hash;

        ProtoCacheKey(String chunkName, String source) {
            this.chunkName = chunkName;
            this.source = source;
            this.hash = 31 * chunkName.hashCode() + source.hashCode();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ProtoCacheKey other
                    && hash == other.hash
                    && chunkName.equals(other.chunkName)
                    && source.equals(other.source);
        }
    }

    /**
     * Bounded cache of compiled {@code LuaProto} trees keyed by
     * {@code (chunkName, source)}. A server that evaluates the same
     * script on every request otherwise rebuilds a fresh proto each time, so
     * its hot functions can never reach the JIT tier-up threshold (the
     * documented "eval again per request never tiers up" limitation).
     * Reusing the proto tree lets hotness accumulate across evaluations.
     *
     * <p>Per-state (no cross-tenant sharing) and LRU-bounded so script data
     * cannot grow it without limit. Protos are immutable after compilation
     * except for JIT state, which is exactly what must persist.
     */
    private final Map<ProtoCacheKey, org.luava.runtime.bytecode.LuaProto> protoCache =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ProtoCacheKey, org.luava.runtime.bytecode.LuaProto> eldest) {
                    return size() > PROTO_CACHE_MAX;
                }
            };

    private static final int PROTO_CACHE_MAX = 256;

    /**
     * Cooperative VM guard, checked once per instruction while non-null.
     * Set via {@link #instructionLimit(long)} / {@link #timeout(Duration)}
     * to stop runaway scripts ({@code while true do end}) from hanging the
     * host. Null on the default (unguarded) fast path.
     */
    public interface LoopGuard {
        void tick();

        /** Batched safepoint tick used by JIT kernels to account for {@code steps} VM ops. */
        default void tickBatch(int steps) {
            for (int i = 0; i < steps; i++) {
                tick();
            }
        }
    }

    /**
     * Hard instruction/time budget. Unlike a one-shot hook, once tripped it
     * stays tripped for the rest of the top-level run, so {@code pcall} can
     * catch the error but the very next instruction throws again — a script
     * cannot swallow the guard and continue. The budget is re-armed at each
     * top-level {@link #eval} entry (see {@link #armGuard()}).
     */
    public static final class Guard implements LoopGuard {
        private final long maxInstr;
        private final long timeoutNanos;
        private long remaining;
        private long deadline;
        private int countdown;
        private volatile boolean tripped;
        private volatile boolean cancelRequested;
        private volatile boolean explicitCancel;
        private volatile boolean timeoutTrip;

        Guard(long maxInstr, long timeoutNanos) {
            this.maxInstr = maxInstr;
            this.timeoutNanos = timeoutNanos;
        }

        void arm() {
            this.remaining = maxInstr;
            this.deadline = timeoutNanos > 0 ? System.nanoTime() + timeoutNanos : 0;
            this.countdown = 4096;
            // Preserve an explicit cancellation: a re-arm by a nested/racing
            // eval must not revive a chunk its caller already abandoned.
            if (!cancelRequested) {
                this.tripped = false;
                this.timeoutTrip = false;
                this.explicitCancel = false;
            }
        }

        /** Forces the next {@link #tick()} to fail. Used to cancel a runaway
         * eval whose caller already gave up, so no worker keeps spinning. */
        void cancel() {
            this.timeoutTrip = true;
            this.cancelRequested = true;
            this.tripped = true;
        }

        void cancelExplicit() {
            this.explicitCancel = true;
            this.timeoutTrip = false;
            this.cancelRequested = true;
            this.tripped = true;
        }

        void clearCancel() {
            this.explicitCancel = false;
            this.cancelRequested = false;
            this.timeoutTrip = false;
            this.tripped = false;
        }

        boolean isCancelled() {
            return cancelRequested;
        }

        @Override
        public void tick() {
            if (tripped) {
                throw trip();
            }
            if (maxInstr > 0 && --remaining < 0) {
                tripped = true;
                timeoutTrip = false;
                throw trip();
            }
            if (timeoutNanos > 0 && --countdown <= 0) {
                countdown = 4096;
                if (System.nanoTime() - deadline >= 0) {
                    tripped = true;
                    timeoutTrip = true;
                    throw trip();
                }
            }
        }

        @Override
        public void tickBatch(int steps) {
            if (tripped) {
                throw trip();
            }
            if (maxInstr > 0) {
                remaining -= steps;
                if (remaining < 0) {
                    tripped = true;
                    timeoutTrip = false;
                    throw trip();
                }
            }
            if (timeoutNanos > 0) {
                countdown -= steps;
                if (countdown <= 0) {
                    countdown = 4096;
                    if (System.nanoTime() - deadline >= 0) {
                        tripped = true;
                        timeoutTrip = true;
                        throw trip();
                    }
                }
            }
        }

        /** True when instruction budget requires per-instruction interpreter granularity. */
        public boolean hasSmallInstrBudget() {
            return maxInstr > 0 && maxInstr < 8192;
        }

        private LuaException trip() {
            if (explicitCancel) {
                return new LuaException("execution cancelled");
            }
            return timeoutTrip
                    ? new GuardTimeout("execution timed out")
                    : new LuaException("instruction limit exceeded");
        }
    }

    /**
     * A {@link Guard} budget exhaustion caused by wall-clock timeout (rather
     * than an instruction limit). Lets {@link #evalWithTimeout} translate the
     * worker's failure into the declared {@link TimeoutException} even when
     * the self-terminating guard wins the race against the waiting caller.
     */
    static final class GuardTimeout extends LuaException {
        GuardTimeout(String message) {
            super(message);
        }
    }

    public volatile LoopGuard loopGuard;

    /** Active state for stdlib guard checks (set around a top-level run). */
    private static final ThreadLocal<LuaState> ACTIVE_STATE = new ThreadLocal<>();
    /** Logical root host thread for the active execution (propagated across coroutines for reentrancy checks). */
    private static final ThreadLocal<Thread> ACTIVE_ROOT_THREAD = new ThreadLocal<>();

    public static LuaState activeState() {
        return ACTIVE_STATE.get();
    }

    public static LuaState swapActiveState(LuaState state) {
        LuaState prev = ACTIVE_STATE.get();
        if (state == null) {
            ACTIVE_STATE.remove();
        } else {
            ACTIVE_STATE.set(state);
        }
        return prev;
    }

    public static void restoreActiveState(LuaState prev) {
        swapActiveState(prev);
    }

    public static Long activeMaxAlloc() {
        return ACTIVE_MAX_ALLOC.get();
    }

    public static Long swapActiveMaxAlloc(Long cap) {
        Long prev = ACTIVE_MAX_ALLOC.get();
        if (cap == null || cap <= 0) {
            ACTIVE_MAX_ALLOC.remove();
        } else {
            ACTIVE_MAX_ALLOC.set(cap);
        }
        return prev;
    }

    public static void restoreActiveMaxAlloc(Long prev) {
        swapActiveMaxAlloc(prev);
    }

    public static Thread activeRootThread() {
        Thread t = ACTIVE_ROOT_THREAD.get();
        return t != null ? t : Thread.currentThread();
    }

    public static Thread activeRootThreadOrNull() {
        return ACTIVE_ROOT_THREAD.get();
    }

    public static Thread swapActiveRootThread(Thread t) {
        Thread prev = ACTIVE_ROOT_THREAD.get();
        if (t == null) {
            ACTIVE_ROOT_THREAD.remove();
        } else {
            ACTIVE_ROOT_THREAD.set(t);
        }
        return prev;
    }

    public static void restoreActiveRootThread(Thread prev) {
        swapActiveRootThread(prev);
    }

    /**
     * Guard check for pure-Java stdlib loops (table.move, pattern matching,
     * string.rep, ...) that would otherwise never return to the VM dispatch
     * loop. Cheap no-op when no guard is active.
     */
    public static void checkGuard() {
        LuaState s = ACTIVE_STATE.get();
        if (s != null) {
            if (s.cancelled) {
                throw new LuaException("execution cancelled");
            }
            LoopGuard g = s.loopGuard;
            if (g != null) g.tick();
        }
    }

    public static void checkGuard(int steps) {
        LuaState s = ACTIVE_STATE.get();
        if (s != null) {
            if (s.cancelled) {
                throw new LuaException("execution cancelled");
            }
            LoopGuard g = s.loopGuard;
            if (g != null) g.tickBatch(steps);
        }
    }

    /**
     * Bounds retained heap memory for this state (best-effort estimate over live
     * Lua tables, strings, closures, and coroutines checked synchronously at GC
     * points; 0 = unlimited).
     */
    public LuaState maxMemoryBytes(long bytes) {
        this.maxMemoryBytes = bytes;
        this.gcState.setMaxMemoryBytes(bytes);
        return this;
    }

    public long getMaxMemoryBytes() {
        return maxMemoryBytes;
    }

    /**
     * Limits the number of simultaneously non-dead coroutines created inside this
     * state (0 = unlimited). Sequential coroutines that complete or close free
     * their slots immediately.
     */
    public LuaState maxCoroutines(int limit) {
        this.maxCoroutines = limit;
        return this;
    }

    public int getMaxCoroutines() {
        return maxCoroutines;
    }

    public int getLiveCoroutineCount() {
        return liveCoroutines.get();
    }

    public void onCoroutineCreated() {
        int cap = maxCoroutines;
        if (cap > 0) {
            while (true) {
                int cur = liveCoroutines.get();
                if (cur >= cap) {
                    throw new LuaException("coroutine limit exceeded");
                }
                if (liveCoroutines.compareAndSet(cur, cur + 1)) {
                    return;
                }
            }
        }
        liveCoroutines.incrementAndGet();
    }

    public void onCoroutineDead() {
        int prev;
        do {
            prev = liveCoroutines.get();
        } while (prev > 0 && !liveCoroutines.compareAndSet(prev, prev - 1));
    }

    /**
     * Sets a custom Lua call stack frame limit for this state (0 = default
     * {@link org.luava.runtime.eval.CallStack#MAX_CALL_DEPTH}).
     */
    public LuaState maxCallDepth(int depth) {
        this.maxCallDepth = depth;
        return this;
    }

    public int getMaxCallDepth() {
        return maxCallDepth;
    }

    /**
     * Arms a lightweight cooperative cancellation guard on this state even when
     * no {@code timeout} or {@code instructionLimit} is configured, allowing
     * {@link #cancel()} from another thread to abort JIT-compiled loops too.
     */
    public LuaState cancellable() {
        if (this.loopGuard == null) {
            this.loopGuard = new Guard(0, 0);
        }
        return this;
    }

    /**
     * Cooperatively cancels execution on this state. Safe to invoke from another
     * host thread while a script, pattern match, or coroutine is running.
     */
    public LuaState cancel() {
        this.cancelled = true;
        if (loopGuard instanceof Guard g) {
            g.cancelExplicit();
        } else {
            Guard cancelGuard = new Guard(0, 0);
            cancelGuard.cancelExplicit();
            this.loopGuard = cancelGuard;
        }
        Thread t = executingRootThread.get();
        if (t != null) {
            java.util.concurrent.locks.LockSupport.unpark(t);
        }
        return this;
    }

    public boolean isCancelled() {
        if (cancelled) return true;
        return loopGuard instanceof Guard g && g.isCancelled();
    }

    /** Clears an explicit {@link #cancel()} flag so this state can execute again. */
    public LuaState resetCancellation() {
        this.cancelled = false;
        if (loopGuard instanceof Guard g) {
            g.clearCancel();
        }
        return this;
    }

    /**
     * Maximum number of bytes a single stdlib allocation may request
     * (string.rep, string.pack, table.concat, ...). 0 = use the Lua default
     * (Integer.MAX_VALUE - 8). Servers should set a small value so untrusted
     * scripts cannot OOM the host.
     */
    private static final ThreadLocal<Long> ACTIVE_MAX_ALLOC = new ThreadLocal<>();

    /**
     * True once any state asked for an allocation cap. Keeps the concat hot
     * path free of a ThreadLocal lookup in the default (uncapped) case.
     */
    private static volatile boolean ANY_MAX_ALLOC = false;

    public LuaState maxAllocationBytes(long bytes) {
        this.maxAllocBytes = bytes;
        if (bytes > 0) {
            ANY_MAX_ALLOC = true;
        }
        return this;
    }

    private long maxAllocBytes = 0;

    private JavaAccessPolicy javaAccessPolicy = JavaAccessPolicy.DEFAULT;

    /**
     * Optional host-provided resource loader for {@code require}. When set,
     * Lua modules are resolved through it (classpath, JAR, in-memory, ...)
     * after the normal filesystem search. Null by default.
     */
    private volatile org.luava.runtime.standard.LuaResourceLoader resourceLoader;
    /**
     * Search template used for resource-loaded modules, mirroring
     * {@code package.path} syntax (e.g. {@code scripts/?.lua}). A segment may
     * be prefixed with {@code classpath:}, which is stripped before the
     * loader sees it, so {@code classpath:scripts/?.lua} also works.
     */
    private volatile String resourcePath = "?.lua;?/init.lua";

    /**
     * Replaces the Java interop host-access policy. Use
     * {@link JavaAccessPolicy#UNRESTRICTED} only for fully trusted scripts;
     * the default blocks process execution, reflection, filesystem and
     * network classes.
     */
    /**
     * Registers a virtual resource loader for {@code require}, enabling Lua
     * modules packaged inside a JAR or any non-filesystem store. The loader
     * is consulted after the filesystem searchers; returning null falls
     * through. Use {@link org.luava.runtime.standard.LuaResourceLoader#classpath()}
     * for the common "scripts live in my JAR" case.
     */
    public LuaState resourceLoader(org.luava.runtime.standard.LuaResourceLoader loader) {
        this.resourceLoader = loader;
        return this;
    }

    /** Returns the active resource loader, or null when none is registered. */
    public org.luava.runtime.standard.LuaResourceLoader getResourceLoader() {
        return resourceLoader;
    }

    /**
     * Overrides the {@code require} search path for resource-loaded modules.
     * Syntax mirrors {@code package.path}; each segment must carry the
     * {@code classpath:} prefix that {@link #getResourceLoader()} strips
     * before resolving. Defaults to {@code classpath:?.lua;classpath:?/init.lua}.
     */
    public LuaState resourcePath(String path) {
        this.resourcePath = path;
        return this;
    }

    public String getResourcePath() {
        return resourcePath;
    }

    public LuaState javaPolicy(JavaAccessPolicy policy) {
        JavaAccessPolicy next = (policy != null) ? policy : JavaAccessPolicy.DEFAULT;
        if (sandboxed && executedOrLoaded && next == JavaAccessPolicy.UNRESTRICTED) {
            throw new LuaException("cannot widen sandbox policy after code has been loaded or executed");
        }
        this.javaAccessPolicy = next;
        return this;
    }

    /** Allows additional Java class/package patterns through the policy. */
    public LuaState javaAllow(String... patterns) {
        if (sandboxed && executedOrLoaded) {
            throw new LuaException("cannot widen sandbox Java allowlist after code has been loaded or executed");
        }
        this.javaAccessPolicy = javaAccessPolicy.withAllow(patterns);
        return this;
    }

    /** Denies additional Java class/package patterns through the policy. */
    public LuaState javaDeny(String... patterns) {
        this.javaAccessPolicy = javaAccessPolicy.withDeny(patterns);
        return this;
    }

    public JavaAccessPolicy getJavaAccessPolicy() {
        return javaAccessPolicy;
    }

    /** Configures maximum queued/in-flight {@link #evalAsync} tasks (0 = unlimited). */
    public LuaState maxPendingAsyncTasks(int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("maxPendingAsyncTasks must not be negative");
        }
        this.maxPendingAsyncTasks = limit;
        return this;
    }

    public int getMaxPendingAsyncTasks() {
        return maxPendingAsyncTasks;
    }

    public int getPendingAsyncTaskCount() {
        return pendingAsyncTasks.get();
    }

    /** Whether async tasks are currently queued or running via {@link #evalAsync}. */
    public boolean hasPendingAsyncTasks() {
        return pendingAsyncTasks.get() > 0;
    }

    /** Whether a host entry is currently executing on this state. */
    public boolean isExecuting() {
        return executingRootThread.get() != null;
    }

    long nextPoolLeaseGeneration() {
        poolIdle = false;
        return leaseGeneration.incrementAndGet();
    }

    void markPoolIdle(long expectedGen) {
        if (leaseGeneration.get() == expectedGen && !closed) {
            poolIdle = true;
        }
    }

    void markPoolRetired() {
        poolIdle = false;
        leaseGeneration.incrementAndGet();
    }

    long currentLeaseGeneration() {
        return leaseGeneration.get();
    }

    boolean isPoolIdle() {
        return poolIdle;
    }

    boolean tryClaimPool(LuaStatePool pool) {
        LuaStatePool current = owningPool.get();
        if (current == pool) {
            return true;
        }
        return current == null && owningPool.compareAndSet(null, pool);
    }

    void releasePoolOwnership(LuaStatePool pool) {
        owningPool.compareAndSet(pool, null);
    }

    /** Configures behavior when {@link #evalAsync} reaches {@link #getMaxPendingAsyncTasks()}. */
    public LuaState asyncRejectionPolicy(AsyncRejectionPolicy policy) {
        this.asyncRejectionPolicy = (policy != null) ? policy : AsyncRejectionPolicy.REJECT;
        return this;
    }

    public AsyncRejectionPolicy getAsyncRejectionPolicy() {
        return asyncRejectionPolicy;
    }

    /** Whether this state has been marked untrusted/unhealthy by a limit trip or internal failure. */
    public boolean isTainted() {
        return closed || tainted || cancelled;
    }

    public boolean isSandboxed() {
        return sandboxed;
    }

    /** Exposes a Java object wrapped by a strict method-name capability allowlist. */
    public void setCapability(String name, Object target, String... allowedMethods) {
        globals.rawset(LuaString.valueOf(name), LuaUserdata.capability(target, allowedMethods));
    }

    /** Atomically applies an immutable {@link ExecutionPolicy} to this state. */
    public LuaState applyPolicy(ExecutionPolicy policy) {
        ensureOpen();
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        if (policy.isSandbox() && !sandboxed) {
            sandbox();
        }
        if (policy.javaAccessPolicy() != null) {
            javaPolicy(policy.javaAccessPolicy());
        }
        for (String d : policy.denyLibraries()) {
            deny(d);
        }
        for (String a : policy.allowLibraries()) {
            allow(a);
        }
        long instr = policy.instructionLimit();
        Duration t = policy.timeout();
        long timeoutNanos = (t != null && !t.isZero() && !t.isNegative()) ? t.toNanos() : 0L;
        if (instr > 0 || timeoutNanos > 0 || policy.isCancellable()) {
            Guard guard = new Guard(Math.max(0L, instr), timeoutNanos);
            guard.arm();
            this.loopGuard = guard;
        } else {
            this.loopGuard = null;
        }
        maxAllocationBytes(policy.maxAllocationBytes());
        maxMemoryBytes(policy.maxMemoryBytes());
        maxCoroutines(policy.maxCoroutines());
        maxCallDepth(policy.maxCallDepth());
        maxPendingAsyncTasks(policy.maxPendingAsyncTasks());
        asyncRejectionPolicy(policy.asyncRejectionPolicy());
        jitEnabled(policy.jitEnabled());
        return this;
    }

    /** Effective per-allocation cap for the currently running chunk. */
    public static long allocationLimit() {
        if (!ANY_MAX_ALLOC) return Integer.MAX_VALUE - 8;
        Long l = ACTIVE_MAX_ALLOC.get();
        if (l != null && l > 0) return l;
        return Integer.MAX_VALUE - 8;
    }

    private void armGuard() {
        LoopGuard g = loopGuard;
        if (g instanceof Guard hard) {
            hard.arm();
        }
    }

    public LuaState() {
        // Force-initialize the core value classes here, at a shallow call
        // depth, before any script runs. Class initialization can allocate
        // (static caches) and thus can throw StackOverflowError if it first
        // happens at the recursion limit — after which the JVM permanently
        // poisons the class ("Could not initialize class ..."). The PUC
        // suite deliberately overflows the C stack while running error
        // handlers, so this must be settled up front.
        initValueClasses();
        registry.rawset(LuaInteger.valueOf(1), mainThread);
        registry.rawset(LuaInteger.valueOf(2), globals);
        this.rootEnvironment = new Environment(null, globals);
        org.luava.runtime.eval.GCManager.registerState(this);
        LuaState prevBasic = LuaValue.pushBasicState(this);
        try {
            openStandardLibraries();
        } finally {
            LuaValue.popBasicState(prevBasic);
        }
        savedLoadfile = globals.rawget(LuaString.interned("loadfile"));
        savedDofile = globals.rawget(LuaString.interned("dofile"));
    }

    /** Touches each core value class so its {@code <clinit>} runs early. */
    private static void initValueClasses() {
        LuaValue[] probe = {
            LuaNil.NIL, LuaBoolean.TRUE, LuaBoolean.FALSE,
            LuaInteger.valueOf(0), LuaFloat.valueOf(0.0),
            LuaString.EMPTY, Varargs.EMPTY,
        };
        if (probe.length == 0) {
            throw new IllegalStateException();
        }
    }

    private void openStandardLibraries() {
        BaseLib.open(this, globals);
        lazyLib("math", t -> MathLib.fillInto(t, globals));
        LuaTable stringLib = lazyLib("string", t -> StringLib.fillInto(t, globals));
        installStringMetatable(stringLib);
        lazyLib("table", t -> TableLib.fillInto(t, globals));
        lazyLib("coroutine", t -> CoroutineLib.fillInto(t, globals, mainThread));
        lazyLib("utf8", t -> Utf8Lib.fillInto(t, globals));
        lazyLib("os", t -> OsLib.fillInto(t, globals));
        lazyLib("io", t -> IoLib.fillInto(t, this, globals));
        lazyLib("debug", t -> DebugLib.fillInto(t, this, globals));
        LuaTable pkgLib = lazyLib("package", t -> PackageLib.fillInto(t, this, globals));
        LuaTable javaLib = lazyLib("java", t -> org.luava.binding.JavaInteropLib.fillInto(t, globals));
        globals.rawset(LuaString.interned("luajava"), javaLib);
        // Bare-global require: stub fills package lib on first call, then
        // delegates to the real implementation (which overwrites this stub).
        globals.rawset(LuaString.interned("require"), LuaFunction.of(args -> {
            pkgLib.ensureFilled();
            LuaValue real = globals.rawget(LuaString.interned("require"));
            return ((LuaFunction) real).call(args);
        }));
        LuaTable argTable = new LuaTable();
        // No stand-alone program exists for an embedded engine; use a stable
        // name instead of pointing at the reference C binary (that would make
        // CLI-oriented suite files test PUC Lua, not Luava).
        String progName = System.getProperty("lua.prog", "luava");
        argTable.rawset(LuaInteger.valueOf(0), LuaString.valueOf(progName));
        globals.rawset(LuaString.interned("arg"), argTable);
    }

    private LuaTable lazyLib(String name, java.util.function.Consumer<LuaTable> filler) {
        LuaTable[] holder = new LuaTable[1];
        LuaTable table = new LuaTable();
        table.setLazyFiller(() -> filler.accept(holder[0]));
        holder[0] = table;
        globals.rawset(LuaString.valueOf(name), table);
        standardLibs.put(name, table);
        return table;
    }

    /** Standard-library tables by module name (for {@code package.loaded}). */
    public Map<String, LuaTable> standardLibs() {
        return standardLibs;
    }

    /**
     * Disables the dangerous standard libraries in one call: {@code os},
     * {@code io}, {@code package} (+ {@code require}/{@code dofile}/
     * {@code loadfile}) and the Java interop table. Use this before running
     * untrusted scripts to prevent filesystem/process/reflection abuse.
     *
     * @return this state (fluent one-liner: {@code new LuaState().sandbox()})
     */
    public LuaState sandbox() {
        if (executedOrLoaded && !sandboxed) {
            throw new LuaException(
                    "cannot convert a LuaState to sandbox after code has been loaded or executed; call sandbox() on a fresh state");
        }
        sandboxed = true;
        javaAccessPolicy = JavaAccessPolicy.STRICT;
        return deny("os", "io", "package", "require", "dofile", "loadfile", "java", "luajava", "debug");
    }

    /** Removes the named globals (library tables or functions) and unregisters them from {@code require}. */
    public LuaState deny(String... names) {
        LuaTable loadedTable = null;
        LuaValue pkgVal = globals.rawget(LuaString.interned("package"));
        if (pkgVal instanceof LuaTable pkg && !pkg.isLazyPending()) {
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            if (loadedVal instanceof LuaTable t) {
                loadedTable = t;
            }
        }
        for (String name : names) {
            LuaString key = LuaString.valueOf(name);
            globals.rawset(key, LuaNil.NIL);
            standardLibs.remove(name);
            if (loadedTable != null) {
                loadedTable.rawset(key, LuaNil.NIL);
            }
        }
        return this;
    }

    /**
     * Re-enables a standard library by (lazily) reinstalling it. Supported
     * names: math, string, table, coroutine, utf8, os, io, debug, package,
     * java. {@code require}/{@code dofile}/{@code loadfile} are restored with
     * the package library.
     */
    public LuaState allow(String name) {
        if (sandboxed && executedOrLoaded && isSensitiveLibrary(name)) {
            throw new LuaException("cannot re-enable library '" + name
                    + "' on an active sandbox after code has been loaded or executed");
        }
        switch (name) {
            case "math" -> lazyLib("math", t -> MathLib.fillInto(t, globals));
            case "string" -> {
                LuaTable stringLib = lazyLib("string", t -> StringLib.fillInto(t, globals));
                installStringMetatable(stringLib);
            }
            case "table" -> lazyLib("table", t -> TableLib.fillInto(t, globals));
            case "coroutine" -> lazyLib("coroutine", t -> CoroutineLib.fillInto(t, globals, mainThread));
            case "utf8" -> lazyLib("utf8", t -> Utf8Lib.fillInto(t, globals));
            case "os" -> lazyLib("os", t -> OsLib.fillInto(t, globals));
            case "io" -> lazyLib("io", t -> IoLib.fillInto(t, this, globals));
            case "debug" -> lazyLib("debug", t -> DebugLib.fillInto(t, this, globals));
            case "java" -> {
                LuaTable javaLib = lazyLib("java", t -> org.luava.binding.JavaInteropLib.fillInto(t, globals));
                globals.rawset(LuaString.interned("luajava"), javaLib);
            }
            case "package" -> installPackage();
            default -> throw new LuaException("unknown library: " + name);
        }
        return this;
    }

    private static boolean isSensitiveLibrary(String name) {
        return "os".equals(name) || "io".equals(name) || "package".equals(name)
                || "java".equals(name) || "debug".equals(name);
    }

    private void installPackage() {        LuaTable pkgLib = lazyLib("package", t -> PackageLib.fillInto(t, this, globals));
        globals.rawset(LuaString.interned("require"), LuaFunction.of(args -> {
            pkgLib.ensureFilled();
            LuaValue real = globals.rawget(LuaString.interned("require"));
            return ((LuaFunction) real).call(args);
        }));
        if (savedLoadfile != null) {
            globals.rawset(LuaString.interned("loadfile"), savedLoadfile);
        }
        if (savedDofile != null) {
            globals.rawset(LuaString.interned("dofile"), savedDofile);
        }
    }

    public LuaTable getRegistry() {
        return registry;
    }

    public long gcOwnerId() {
        return gcOwnerId;
    }

    EnumMap<LuaType, LuaTable> basicMetatables() {
        return basicMetatables;
    }

    /**
     * This state's basic metatable for {@code type} (null when unset).
     * Hot-path alternative to {@code LuaValue.getBasicMetatable}: the VM
     * already holds the executing state, so this skips the ThreadLocal
     * lookup. Only valid while this state is the active basic scope, which
     * always holds inside its own {@code execute()}.
     */
    public LuaTable basicMetatable(LuaType type) {
        return basicMetatables.get(type);
    }

    private void installStringMetatable(LuaTable stringLib) {
        LuaTable stringMetatable = StringLib.newStringMetatable(stringLib);
        basicMetatables.put(LuaType.STRING, stringMetatable);
        synchronized (LuaValue.class) {
            if (LuaValue.getBasicMetatable(LuaType.STRING) == null) {
                LuaValue.setBasicMetatable(LuaType.STRING, stringMetatable);
            }
        }
    }

    public LuaTable getGlobals() {
        return globals;
    }

    public LuaCoroutine getMainThread() {
        return mainThread;
    }

    public LuaValue get(String name) {
        return globals.get(LuaString.valueOf(name));
    }

    public <T> T get(String name, Class<T> type) {
        LuaValue val = get(name);
        return LuaDataConverter.toJava(val, type);
    }

    public void set(String name, Object value) {
        globals.set(LuaString.valueOf(name), LuaDataConverter.toLua(value));
    }

    public void setLive(String name, Object value) {
        globals.set(LuaString.valueOf(name), LuaDataConverter.toLuaLive(value));
    }

    public void registerClass(Class<?> clazz) {
        registerClass(clazz.getSimpleName(), clazz);
    }

    public void registerClass(String alias, Class<?> clazz) {
        globals.rawset(LuaString.valueOf(alias), new LuaUserdata(clazz));
    }

    public void registerFunction(String name, LuaInvokable invokable) {
        globals.rawset(LuaString.valueOf(name), LuaFunction.ofGuarded(invokable));
    }

    public void registerFunction(String name, Supplier<?> supplier) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromSupplier(supplier));
    }

    public <T> void registerFunction(String name, Class<T> type, Consumer<T> consumer) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromConsumer(type, consumer));
    }

    public <T, U> void registerFunction(String name, Class<T> type1, Class<U> type2, BiConsumer<T, U> consumer) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromBiConsumer(type1, type2, consumer));
    }

    public <T, R> void registerFunction(String name, Class<T> argType, Function<T, R> function) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromFunction(argType, function));
    }

    public <T, U, R> void registerFunction(String name, Class<T> argType1, Class<U> argType2, BiFunction<T, U, R> function) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromBiFunction(argType1, argType2, function));
    }

    public <T, U, V, R> void registerFunction(
        String name,
        Class<T> argType1,
        Class<U> argType2,
        Class<V> argType3,
        JavaFunctionBuilder.TriFunction<T, U, V, R> function
    ) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromTriFunction(argType1, argType2, argType3, function));
    }

    public void registerModule(Object target) {
        ModuleBinder.ModuleBindingResult result = ModuleBinder.bind(target);
        registeredModules.add(result.info());
        String modName = result.info().name();
        globals.rawset(LuaString.valueOf(modName), result.table());

        LuaValue pkgVal = globals.rawget(LuaString.interned("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                loaded.rawset(LuaString.valueOf(modName), result.table());
            }
        }
    }

    /**
     * Registers a module built by hand from a {@link LuaTable}. Unlike
     * {@link #registerModule(Object)} the table shape is arbitrary, so no
     * typed {@code ModuleInfo} is recorded and this module is intentionally
     * absent from {@link #generateEmmyDocs()}/{@link #exportEmmyDocs(Path)};
     * use the annotation path for documented modules.
     */
    public void registerModule(String moduleName, Consumer<LuaTable> configurator) {
        LuaTable table = new LuaTable();
        configurator.accept(table);
        globals.rawset(LuaString.valueOf(moduleName), table);

        LuaValue pkgVal = globals.rawget(LuaString.interned("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                loaded.rawset(LuaString.valueOf(moduleName), table);
            }
        }
    }

    public LuaValue call(String functionName, Object... args) {
        return runScoped(() -> {
            statsCalls.incrementAndGet();
            LuaValue fn = get(functionName);
            if (!fn.isFunction()) {
                throw new LuaException("global '" + functionName + "' is not a function");
            }
            int argc = (args != null) ? args.length : 0;
            LuaValue[] luaArgs = new LuaValue[argc];
            for (int i = 0; i < argc; i++) {
                luaArgs[i] = LuaDataConverter.toLua(args[i]);
            }
            return fn.call(luaArgs);
        });
    }

    /**
     * Executes {@code body} inside this state's complete execution scope:
     * single-flight/reentrancy check, {@code mainThread} binding, guard arming,
     * active state, allocation cap, Java access policy, basic-metatable scope,
     * and host-boundary exception translation.
     */
    private <T> T runScoped(java.util.function.Supplier<T> body) {
        ensureOpen();
        Thread root = activeRootThread();
        boolean isTopLevel;
        if (executingRootThread.get() == root) {
            isTopLevel = false;
            executingDepth++;
        } else {
            if (!executingRootThread.compareAndSet(null, root)) {
                throw new LuaException("concurrent execution on the same LuaState is not supported");
            }
            isTopLevel = true;
            executingDepth = 1;
        }

        LuaCoroutine prevCoro = LuaCoroutine.running();
        LuaState prevActive = ACTIVE_STATE.get();
        Long prevMax = ACTIVE_MAX_ALLOC.get();
        JavaAccessPolicy prevPolicy = JavaAccessPolicy.setActive(javaAccessPolicy);
        LuaState prevBasic = LuaValue.pushBasicState(this);
        Thread prevRoot = ACTIVE_ROOT_THREAD.get();

        if (prevCoro == null) {
            LuaCoroutine.setCurrent(mainThread);
        }
        ACTIVE_STATE.set(this);
        ACTIVE_ROOT_THREAD.set(root);
        if (cancelled) {
            if (prevRoot == null) ACTIVE_ROOT_THREAD.remove(); else ACTIVE_ROOT_THREAD.set(prevRoot);
            if (prevActive == null) ACTIVE_STATE.remove(); else ACTIVE_STATE.set(prevActive);
            if (prevCoro == null) LuaCoroutine.setCurrent(null);
            LuaValue.popBasicState(prevBasic);
            JavaAccessPolicy.restoreActive(prevPolicy);
            executingDepth--;
            if (isTopLevel) {
                executingRootThread.set(null);
            }
            throw new LuaException("execution cancelled");
        }
        org.luava.runtime.eval.CallStack.CallStackState mainStack = mainThread.getCallStackState();
        int prevFrameLimit = mainStack.frameLimit;
        mainStack.frameLimit = (maxCallDepth > 0)
                ? Math.min(maxCallDepth, org.luava.runtime.eval.CallStack.MAX_CALL_DEPTH)
                : org.luava.runtime.eval.CallStack.MAX_CALL_DEPTH;
        if (maxAllocBytes > 0) {
            ACTIVE_MAX_ALLOC.set(maxAllocBytes);
        } else {
            ACTIVE_MAX_ALLOC.remove();
        }
        if (isTopLevel) {
            executedOrLoaded = true;
            armGuard();
        }

        try {
            return guardHostBoundary(body);
        } finally {
            mainStack.frameLimit = prevFrameLimit;
            LuaValue.popBasicState(prevBasic);
            JavaAccessPolicy.restoreActive(prevPolicy);
            if (prevMax == null) {
                ACTIVE_MAX_ALLOC.remove();
            } else {
                ACTIVE_MAX_ALLOC.set(prevMax);
            }
            if (prevRoot == null) {
                ACTIVE_ROOT_THREAD.remove();
            } else {
                ACTIVE_ROOT_THREAD.set(prevRoot);
            }
            if (prevActive == null) {
                ACTIVE_STATE.remove();
            } else {
                ACTIVE_STATE.set(prevActive);
            }
            if (prevCoro == null) {
                LuaCoroutine.setCurrent(null);
            }
            executingDepth--;
            if (isTopLevel) {
                executingRootThread.set(null);
            }
        }
    }

    /**
     * Runs a host-initiated entry point, converting any unchecked Java
     * throwable that is not a Lua control-flow signal into a {@link
     * LuaException}. This is the embedding contract from AGENTS.md §IV.2:
     * an internal {@code NullPointerException}/{@code IndexOutOfBoundsException}
     * must never leak to the host application. Control-flow signals
     * ({@link LuaException} itself, {@link LuaExit},
     * {@link org.luava.runtime.eval.LuaUnwindException},
     * {@link org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal})
     * pass through unchanged so {@code os.exit}, coroutine close and error
     * unwinding keep their semantics.
     */
    private <T> T guardHostBoundary(java.util.function.Supplier<T> body) {
        try {
            return body.get();
        } catch (GuardTimeout gt) {
            this.tainted = true;
            throw gt;
        } catch (LuaException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("instruction limit")
                    || msg.contains("memory limit")
                    || msg.contains("execution cancelled"))) {
                this.tainted = true;
            }
            throw e;
        } catch (LuaExit
                | org.luava.runtime.eval.LuaUnwindException
                | org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal e) {
            throw e;
        } catch (StackOverflowError e) {
            this.tainted = true;
            throw new LuaException("stack overflow");
        } catch (OutOfMemoryError oom) {
            this.tainted = true;
            // PUC reports a failed allocation as the Lua-level error "not
            // enough memory" (LUA_ERRMEM), catchable by pcall. Build it from
            // the pre-allocated message so this boundary need not allocate
            // under pressure (a fresh message could re-OOM and leak the raw
            // OutOfMemoryError to the host).
            throw new LuaException(LuaString.MEMORY_ERROR);
        } catch (Throwable t) {
            this.tainted = true;
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            throw new LuaException("internal error: " + t.getClass().getSimpleName() + ": " + msg);
        }
    }

    /**
     * Immutable pre-compiled Lua chunk shareable across independent {@link LuaState}
     * instances while preserving compiled bytecode and JIT tier-up state.
     */
    public static final class CompiledChunk implements AutoCloseable {
        final org.luava.runtime.bytecode.LuaProto proto;

        CompiledChunk(org.luava.runtime.bytecode.LuaProto proto) {
            this.proto = proto;
        }

        @Override
        public void close() {
            org.luava.runtime.jit.JitCompiler.invalidateTree(proto);
        }
    }

    /** Parses and compiles a Lua source chunk independently of any state instance. */
    public static CompiledChunk compileChunk(String luaSource, String chunkName) {
        if (luaSource == null) {
            throw new LuaException("chunk source must not be null");
        }
        String effectiveName = chunkName != null ? chunkName : "chunk";
        try {
            boolean isFile = effectiveName.startsWith("@");
            Lexer lexer = new Lexer(luaSource, isFile);
            List<Token> tokens = lexer.scanTokens();
            Parser parser = new Parser(tokens);
            Statements.BlockStmt block = parser.parse();
            org.luava.runtime.bytecode.LuaProto proto =
                    org.luava.runtime.bytecode.BytecodeCompiler.compile(block, effectiveName);
            proto.rawSource = luaSource;
            return new CompiledChunk(proto);
        } catch (org.luava.frontend.parser.ParseException pe) {
            throw new LuaException(pe.format(chunkName != null ? chunkName : luaSource));
        }
    }

    public LuaFunction load(CompiledChunk chunk) {
        return load(chunk, globals);
    }

    public LuaFunction load(CompiledChunk chunk, LuaValue env) {
        ensureOpen();
        executedOrLoaded = true;
        if (chunk == null) {
            throw new LuaException("compiled chunk must not be null");
        }
        LuaValue chunkEnvVal = (env != null) ? env : globals;
        LuaTable chunkGlobals = (chunkEnvVal instanceof LuaTable t) ? t : globals;
        org.luava.runtime.eval.Upvalue envUpval =
                new org.luava.runtime.eval.Upvalue("_ENV", chunkEnvVal);
        return new org.luava.runtime.bytecode.LuaClosure(
                chunk.proto, new org.luava.runtime.eval.Upvalue[]{envUpval}, chunkGlobals, this);
    }

    public LuaValue eval(CompiledChunk chunk) {
        return runScoped(() -> {
            statsCalls.incrementAndGet();
            return load(chunk).call();
        });
    }

    public LuaValue eval(String luaSource) {
        return eval(luaSource, "chunk");
    }

    public LuaValue eval(String luaSource, String chunkName) {
        return runScoped(() -> {
            statsCalls.incrementAndGet();
            LuaFunction chunk = compile(luaSource, chunkName, globals);
            if (chunk instanceof org.luava.runtime.bytecode.LuaClosure lc) {
                lc.setState(this);
            }
            return chunk.call();
        });
    }

    public LuaValue eval(String luaSource, Map<String, Object> context) {
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                set(entry.getKey(), entry.getValue());
            }
        }
        return eval(luaSource);
    }

    public LuaValue evalWithTimeout(String luaSource, Duration timeout) throws TimeoutException {
        ensureOpen();
        // A private hard guard makes the worker self-terminate on timeout
        // instead of leaking a virtual thread that spins forever. The guard
        // is installed before the worker starts and removed once the worker
        // has unwound, so the state is reusable afterwards.
        Guard guard = new Guard(0, timeout.toNanos());
        guard.arm();
        LoopGuard prevGuard = loopGuard;
        loopGuard = guard;
        CompletableFuture<LuaValue> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("lua-timeout-task").start(() -> {
            try {
                LuaValue result = eval(luaSource);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            guard.cancel();
            try {
                worker.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new LuaException("Execution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // The self-terminating guard uses the same deadline as the
            // waiting caller, so it can win the race and fail the worker
            // with a timeout error before future.get() raises its own.
            // Surface that uniformly as the declared TimeoutException.
            if (cause instanceof GuardTimeout) {
                throw new TimeoutException(cause.getMessage());
            }
            if (cause instanceof RuntimeException re) throw re;
            throw new LuaException("Error during execution: " + cause.getMessage());
        } catch (TimeoutException e) {
            this.tainted = true;
            guard.cancel();
            future.cancel(false);
            try {
                worker.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } finally {
            loopGuard = prevGuard;
        }
    }

    /**
     * Stops execution after at most {@code maxInstructions} VM instructions
     * with a Lua error {@code "instruction limit exceeded"}. Cooperative and
     * cheap (one field check per instruction). Pass {@code 0} to disable.
     */
    public LuaState instructionLimit(long maxInstructions) {
        long timeoutNanos = (loopGuard instanceof Guard g) ? g.timeoutNanos : 0;
        if (maxInstructions <= 0) {
            if (timeoutNanos > 0) {
                Guard guard = new Guard(0, timeoutNanos);
                guard.arm();
                loopGuard = guard;
            } else {
                loopGuard = null;
            }
            return this;
        }
        Guard guard = new Guard(maxInstructions, timeoutNanos);
        guard.arm();
        loopGuard = guard;
        return this;
    }

    /**
     * Stops execution after {@code timeout} of wall-clock time with a Lua
     * error {@code "execution timed out"}. Checked periodically (not every
     * instruction) to keep the hot path cheap. Pass {@code null} to disable.
     */
    public LuaState timeout(Duration timeout) {
        long maxInstr = (loopGuard instanceof Guard g) ? g.maxInstr : 0;
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            if (maxInstr > 0) {
                Guard guard = new Guard(maxInstr, 0);
                guard.arm();
                loopGuard = guard;
            } else {
                loopGuard = null;
            }
            return this;
        }
        Guard guard = new Guard(maxInstr, timeout.toNanos());
        guard.arm();
        loopGuard = guard;
        return this;
    }

    /** Clears any instruction/timeout guard installed by this state. */
    public LuaState clearGuard() {
        loopGuard = null;
        return this;
    }

    public String generateEmmyDocs() {
        StringBuilder sb = new StringBuilder();
        for (ModuleBinder.ModuleInfo info : registeredModules) {
            sb.append(EmmyDocGenerator.generate(info)).append("\n\n");
        }
        return sb.toString();
    }

    public void exportEmmyDocs(Path directory) throws IOException {
        for (ModuleBinder.ModuleInfo info : registeredModules) {
            Path targetFile = directory.resolve(info.name() + ".lua");
            EmmyDocGenerator.exportToFile(targetFile, info);
        }
    }

    public LuaFunction compile(String luaSource) {
        return compile(luaSource, "=(load)", globals);
    }

    public Environment getRootEnvironment() {
        return rootEnvironment;
    }

    public LuaFunction compile(String luaSource, String chunkName, LuaValue env) {
        ensureOpen();
        executedOrLoaded = true;
        LuaValue chunkEnvVal = (env != null) ? env : globals;
        LuaTable chunkGlobals = (chunkEnvVal instanceof LuaTable t) ? t : globals;

        if (luaSource.startsWith("\u001b")) {
            LuaFunction fn = org.luava.runtime.standard.ChunkSerializer.undump(
                luaSource.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                chunkName, chunkEnvVal, globals, rootEnvironment
            );
            // undump may build a closure without a state; bind this state so
            // the loopGuard/instructionLimit applies (otherwise invoke() would
            // lazily create a fresh, unguarded LuaState).
            if (fn instanceof org.luava.runtime.bytecode.LuaClosure lc) {
                lc.setState(this);
            }
            return fn;
        }

        if (!USE_BYTECODE_VM) {
            throw new LuaException("AST interpreter retired; bytecode VM is the only execution path");
        }
        // Reuse the compiled proto tree for a repeated chunk. The proto is
        // immutable after compilation (only JIT tier-up state mutates), and
        // each call still gets a fresh closure with its own _ENV upvalue, so
        // sharing is semantically transparent. This is what lets a server
        // that eval's the same script per request accumulate JIT hotness
        // instead of rebuilding a cold proto every time.
        ProtoCacheKey key = new ProtoCacheKey(chunkName != null ? chunkName : "chunk", luaSource);
        org.luava.runtime.bytecode.LuaProto proto;
        synchronized (protoCache) {
            proto = protoCache.get(key);
        }
        if (proto == null) {
            proto = compileChunk(luaSource, chunkName).proto;
            synchronized (protoCache) {
                protoCache.put(key, proto);
            }
            statsInstructions.addAndGet(treeCodeSize(proto));
        }
        org.luava.runtime.eval.Upvalue envUpval = new org.luava.runtime.eval.Upvalue("_ENV", chunkEnvVal);
        return new org.luava.runtime.bytecode.LuaClosure(proto, new org.luava.runtime.eval.Upvalue[]{envUpval}, chunkGlobals, this);
    }

    // AST interpreter retired: the register-based bytecode VM is the only
    // execution path (verified 31/31 official suite + 32/32 VM files).
    public static boolean USE_BYTECODE_VM = true;

    /**
     * Hybrid tiered JIT master switch (plan.md). Default ON: hot kernels
     * tier up automatically while the interpreter stays the fallback for
     * anything unsupported (identical semantics, verified 30/30 + fuzz both
     * modes). Opt out with {@code -Dluava.jit=false}; force synchronous
     * tier-up for deterministic measurement with
     * {@code -Dluava.jit.sync=true}.
     *
     * <p>This is the process-wide default; per-state control is
     * {@link #jitEnabled(Boolean)} / {@link #isJitEnabled()}. A state's own
     * setting wins over this field.
     */
    public static volatile boolean ENABLE_JIT = !"false".equalsIgnoreCase(System.getProperty("luava.jit", "true"));

    /**
     * Interpreter calls of one proto before a JIT compile is attempted.
     * Overridable via {@code -Dluava.jit.hotThreshold=N} for testing the
     * compiled subset at the most aggressive tier-up (N=1 forces every call
     * site through the JIT on its first invocation).
     */
    public static final int JIT_HOT_THRESHOLD =
            Integer.getInteger("luava.jit.hotThreshold", 50);
    /**
     * How many structural deopts (impure-proto CALL/TAILCALL, expected every
     * run) a kernel tolerates before it is disarmed. Impure calling protos
     * are only compiled when a hot loop precedes the call, where the compiled
     * prefix easily pays for one exception per invocation; a proto whose
     * compiled prefix never pays (a trivial loop before the call, called
     * millions of times) is disabled here instead of penalized forever.
     */
    public static final int JIT_STRUCTURAL_DEOPT_BUDGET = 4096;

    /**
     * Loop back-edges of one proto before a JIT compile is attempted. Much
     * larger than {@link #JIT_HOT_THRESHOLD}: a single loop iteration is far
     * cheaper than a call, and this keeps short/once-around loops from
     * triggering a compile for no benefit. Used for protos that are hot
     * because of a loop (top-level chunks, {@code while}/{@code repeat}, or a
     * function called only once).
     *
     * <p>Every backward edge counts: numeric {@code for} also requests at
     * {@code FORPREP} from the known trip count (zero per-iteration cost),
     * while {@code while}/{@code repeat} and backward {@code goto} count at
     * the jump itself through {@code ctx.jitBackEdgeCountdown}. Generic-for
     * counts too but is excluded from on-stack replacement.
     */
    public static final int JIT_LOOP_THRESHOLD =
            Integer.getInteger("luava.jit.loopThreshold", 8192);

    /**
     * On-stack replacement for hot loops (plan.md). When a proto has a
     * compiled kernel, a loop that crosses {@link #JIT_LOOP_THRESHOLD}
     * back-edges jumps straight into the kernel at the loop header instead of
     * waiting for the next call, so a chunk invoked once with a huge loop
     * still gets compiled work. Opt out with {@code -Dluava.jit.osr=false}
     * (the kernel then only runs from a subsequent call/entry).
     */
    public static final boolean ENABLE_OSR =
            !"false".equalsIgnoreCase(System.getProperty("luava.jit.osr", "true"));

    /**
     * Per-state JIT override. {@code null} means "follow the global
     * {@link #ENABLE_JIT}"; true/false pins this state only, so a server can
     * keep JIT on for trusted tenants and off for others in the same JVM.
     */
    private volatile Boolean jitEnabledOverride;

    /**
     * Enables or disables tiered JIT for this state only, independent of
     * {@link #ENABLE_JIT} and other states. Thread-safe; may be flipped at
     * runtime. Pass {@code null} to fall back to the global default.
     */
    public LuaState jitEnabled(Boolean enabled) {
        this.jitEnabledOverride = enabled;
        return this;
    }

    /** Whether this state currently uses the JIT (its override or the global default). */
    public boolean isJitEnabled() {
        if (maxCallDepth > 0) {
            return false;
        }
        Boolean o = jitEnabledOverride;
        return o != null ? o : ENABLE_JIT;
    }

    public LuaCoroutine getCurrentThread() {
        LuaCoroutine cur = LuaCoroutine.running();
        return (cur != null) ? cur : mainThread;
    }

    public long[] getPrimitiveStack() {
        return getCurrentThread().getPrimitiveStack();
    }

    public byte[] getTypeStack() {
        return getCurrentThread().getTypeStack();
    }

    public LuaValue[] getObjectStack() {
        return getCurrentThread().getObjectStack();
    }

    public int getStackTop() {
        return getCurrentThread().getStackTop();
    }

    public void setStackTop(int top) {
        getCurrentThread().setStackTop(top);
    }

    public void ensureStackCapacity(int needed) {
        getCurrentThread().ensureStackCapacity(needed);
    }

    public LuaValue[] ensureStack(int needed) {
        ensureStackCapacity(needed);
        return getCurrentThread().getObjectStack();
    }

    public LuaValue getStackValue(int index) {
        LuaCoroutine th = getCurrentThread();
        return org.luava.runtime.bytecode.BytecodeVM.getLuaValue(th.getPrimitiveStack(), th.getTypeStack(), th.getObjectStack(), index);
    }

    public void setStackValue(int index, LuaValue val) {
        LuaCoroutine th = getCurrentThread();
        org.luava.runtime.bytecode.BytecodeVM.setLuaValue(th.getPrimitiveStack(), th.getTypeStack(), th.getObjectStack(), index, val);
    }

    public org.luava.runtime.eval.Upvalue findOrCreateOpenUpvalue(int stackIndex, String name) {
        return findOrCreateOpenUpvalue(getCurrentThread(), stackIndex, name);
    }

    /**
     * Hot-path overload: the VM already holds its thread ({@code ctx.thread},
     * identical to {@code getCurrentThread()} for the whole execute), so
     * pass it explicitly instead of paying a ThreadLocal lookup per call.
     */
    public org.luava.runtime.eval.Upvalue findOrCreateOpenUpvalue(LuaCoroutine thread, int stackIndex, String name) {
        org.luava.runtime.eval.Upvalue prev = null;
        org.luava.runtime.eval.Upvalue curr = thread.getOpenUpvaluesHead();
        while (curr != null && curr.getStackIndex() >= stackIndex) {
            if (curr.getStackIndex() == stackIndex) {
                return curr;
            }
            prev = curr;
            curr = curr.nextOpen;
        }

        org.luava.runtime.eval.Upvalue newUv = new org.luava.runtime.eval.Upvalue(name, thread, stackIndex);
        newUv.nextOpen = curr;
        if (prev == null) {
            thread.setOpenUpvaluesHead(newUv);
        } else {
            prev.nextOpen = newUv;
        }
        return newUv;
    }

    public void closeUpvalues(int fromIndex) {
        closeUpvalues(getCurrentThread(), fromIndex);
    }

    /** Hot-path overload: explicit thread, no ThreadLocal lookup (see above). */
    public void closeUpvalues(LuaCoroutine thread, int fromIndex) {
        while (thread.getOpenUpvaluesHead() != null && thread.getOpenUpvaluesHead().getStackIndex() >= fromIndex) {
            org.luava.runtime.eval.Upvalue uv = thread.getOpenUpvaluesHead();
            thread.setOpenUpvaluesHead(uv.nextOpen);
            uv.nextOpen = null;
            uv.close();
        }
    }

    /**
     * Runs {@code os.exit(_, true)} close semantics: closes pending
     * to-be-closed variables of the running thread, then runs finalizers.
     * Called from {@code os.exit} before unwinding with {@link LuaExit}.
     */
    public static void runExitFinalizers() {
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            try {
                cur.closeAllTbc();
            } catch (Throwable ignored) {
            }
        }
        try {
            org.luava.runtime.eval.GCManager.collect();
        } catch (Throwable ignored) {
        }
    }

    public static final class TbcEntry {        public final int stackIndex;
        public final LuaValue value;
        public final String varName;
        public TbcEntry next;

        public TbcEntry(int stackIndex, LuaValue value, String varName, TbcEntry next) {
            this.stackIndex = stackIndex;
            this.value = value;
            this.varName = varName;
            this.next = next;
        }
    }

    public void pushTbc(int stackIndex, LuaValue val, String varName) {
        if (val == null || val.isNil() || val.equals(LuaBoolean.FALSE)) {
            return;
        }
        LuaTable mt = val.getMetatable();
        if (mt == null || mt.rawget(LuaValue.Meta.CLOSE).isNil()) {
            throw new LuaException("variable '" + (varName != null ? varName : "?") + "' got a non-closable value");
        }
        org.luava.runtime.concurrency.LuaCoroutine.promoteIfStackless();
        LuaCoroutine thread = getCurrentThread();
        thread.setTbcHead(new TbcEntry(stackIndex, val, varName, thread.getTbcHead()));
    }

    public void closeTbc(int fromIndex, LuaValue errorObj) {
        closeTbc(getCurrentThread(), fromIndex, errorObj);
    }

    /**
     * ThreadLocal-free {@link #closeTbc(int, LuaValue)} for VM callers that
     * already hoisted the running thread (avoids a {@code ThreadLocalMap}
     * lookup on every return path).
     */
    public void closeTbc(LuaCoroutine thread, int fromIndex, LuaValue errorObj) {
        Throwable lastError = null;
        while (thread.getTbcHead() != null && thread.getTbcHead().stackIndex >= fromIndex) {
            TbcEntry entry = thread.getTbcHead();
            thread.setTbcHead(entry.next);
            LuaValue val = entry.value;
            LuaTable mt = val.getMetatable();
            LuaValue closeMth = mt != null ? mt.rawget(LuaValue.Meta.CLOSE) : LuaNil.NIL;
            try {
                if (closeMth.isNil()) {
                    throw new LuaException("attempt to call a nil value (metamethod 'close')");
                }
                org.luava.runtime.eval.CallStack.setNextCall("close", "metamethod", false, true);
                closeMth.call(val, errorObj != null ? errorObj : LuaNil.NIL);
            } catch (org.luava.runtime.eval.LuaUnwindException ue) {
                lastError = ue;
                errorObj = ue.getOriginalError();
            } catch (LuaException le) {
                lastError = le;
                errorObj = le.getErrorObject();
            } catch (Throwable t) {
                lastError = t;
                errorObj = LuaString.valueOf(t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }
        if (lastError != null) {
            if (lastError instanceof RuntimeException re) throw re;
            throw new LuaException(lastError.getMessage());
        }
    }

    // ---------------------------------------------------------------
    // W4 embedding/ops surface (purely additive): observability, async
    // entry, and lifecycle. None of the pre-existing paths above were
    // reordered or re-semanticized; counters are side-effect-free
    // increments and guards only fire on the new closed state.
    // ---------------------------------------------------------------

    /** Throws when this state was {@link #close() closed} or returned idle to a {@link LuaStatePool}. */
    private void ensureOpen() {
        if (closed) {
            throw new LuaException("LuaState is closed");
        }
        if (poolIdle) {
            throw new LuaException("pooled LuaState is idle and cannot be used outside an active lease");
        }
    }

    /** Whether {@link #close()} has been called on this state. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Returns an immutable observability snapshot for this state. Never
     * touches the VM or JIT control paths: JIT gauges are scanned from this
     * state's own proto cache, allocation debt is read from the GC manager.
     */
    public Stats stats() {
        long jitHits = 0;
        long deopts = 0;
        synchronized (protoCache) {
            for (org.luava.runtime.bytecode.LuaProto proto : protoCache.values()) {
                long[] scanned = scanJitGauges(proto);
                jitHits += scanned[0];
                deopts += scanned[1];
            }
        }
        return new Stats(
                statsCalls.get(),
                jitHits,
                deopts,
                gcState.getUncollectedBytes(),
                statsInstructions.get());
    }

    /**
     * Walks one cached proto tree, returning {@code [compiledKernels,
     * deopts]}. A kernel disarmed back to the interpreter (both {@code jitCode}
     * and {@code guardedJitCode == null}) contributes nothing, so {@code jitHits}
     * is a live gauge.
     */
    private static long[] scanJitGauges(org.luava.runtime.bytecode.LuaProto proto) {
        long hits = 0;
        long deopts = 0;
        org.luava.runtime.jit.JitCode jc = proto.jitCode;
        org.luava.runtime.jit.JitCode gjc = proto.guardedJitCode;
        if (jc != null || gjc != null) {
            hits = 1;
            if (jc != null) {
                deopts += (long) jc.deopts + jc.structuralDeopts;
            }
            if (gjc != null) {
                deopts += (long) gjc.deopts + gjc.structuralDeopts;
            }
        }
        for (org.luava.runtime.bytecode.LuaProto child : proto.protos) {
            long[] sub = scanJitGauges(child);
            hits += sub[0];
            deopts += sub[1];
        }
        return new long[]{hits, deopts};
    }

    /** Sums {@code code.length} over a proto tree (compiled-load pressure). */
    private static long treeCodeSize(org.luava.runtime.bytecode.LuaProto proto) {
        long size = proto.code.length;
        for (org.luava.runtime.bytecode.LuaProto child : proto.protos) {
            size += treeCodeSize(child);
        }
        return size;
    }

    /**
     * Evaluates {@code luaSource} asynchronously, completing with the chunk
     * result (a multi-value chunk completes with its {@link Varargs}). The
     * blocking {@link #eval} semantics are unchanged; this is a thin host
     * convenience. Failures complete exceptionally with the same
     * {@link LuaException} the blocking call would throw.
     */
    public CompletableFuture<LuaValue> evalAsync(String luaSource) {
        return evalAsync(luaSource, "chunk");
    }

    /**
     * Named-chunk variant of {@link #evalAsync(String)}; {@code chunkName}
     * shows up in error messages and tracebacks exactly as in
     * {@link #eval(String, String)}.
     */
    public CompletableFuture<LuaValue> evalAsync(String luaSource, String chunkName) {
        ensureOpen();
        int cap = maxPendingAsyncTasks;
        AsyncRejectionPolicy policy = asyncRejectionPolicy;
        CompletableFuture<LuaValue> result = new CompletableFuture<>();
        synchronized (asyncLock) {
            if (cap > 0) {
                if (policy == AsyncRejectionPolicy.CALLER_BLOCKS) {
                    boolean interrupted = false;
                    while (pendingAsyncTasks.get() >= cap && !closed) {
                        try {
                            asyncLock.wait();
                        } catch (InterruptedException ie) {
                            interrupted = true;
                            break;
                        }
                    }
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                        throw new LuaException("async task submission interrupted");
                    }
                    ensureOpen();
                } else if (pendingAsyncTasks.get() >= cap) {
                    throw new LuaException("async task queue is full (limit: " + cap + ")");
                }
            }
            pendingAsyncTasks.incrementAndGet();
            CompletableFuture<Void> prev = asyncTail;
            CompletableFuture<Void>[] holder = new CompletableFuture[1];
            CompletableFuture<Void> next = prev.whenCompleteAsync((ignored, err) -> {
                LuaValue value = null;
                Throwable failure = null;
                try {
                    if (closed || poolIdle) {
                        failure = new LuaException("LuaState is closed");
                    } else if (isCancelled()) {
                        failure = new LuaException("execution cancelled");
                    } else {
                        value = eval(luaSource, chunkName);
                    }
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    pendingAsyncTasks.decrementAndGet();
                    synchronized (asyncLock) {
                        if (asyncTail == holder[0]) {
                            asyncTail = CompletableFuture.completedFuture(null);
                        }
                        asyncLock.notifyAll();
                    }
                }
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(value);
                }
            }, ASYNC_EXECUTOR);
            holder[0] = next;
            asyncTail = next;
        }
        return result;
    }

    /**
     * Releases this state: cancels active runaway guards, invalidates cached
     * JIT kernels, closes idle top-level {@code <close>} variables, and runs/drops
     * state-owned GC finalizers and weak-table entries. Idempotent; further
     * {@code eval}/{@code call}/{@code compile} entries fail fast with a
     * {@link LuaException}.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        closed = true;
        synchronized (asyncLock) {
            asyncLock.notifyAll();
        }
        Thread execThread = executingRootThread.get();
        if (execThread != null) {
            cancelled = true;
            if (loopGuard instanceof Guard g) {
                g.cancel();
            }
            java.util.concurrent.locks.LockSupport.unpark(execThread);
        }
        List<org.luava.runtime.bytecode.LuaProto> protos;
        synchronized (protoCache) {
            protos = new ArrayList<>(protoCache.values());
            protoCache.clear();
        }
        for (org.luava.runtime.bytecode.LuaProto proto : protos) {
            org.luava.runtime.jit.JitCompiler.invalidateTree(proto);
        }
        if (execThread == null) {
            LuaState prevBasic = LuaValue.pushBasicState(this);
            try {
                mainThread.closeAllTbc();
            } finally {
                LuaValue.popBasicState(prevBasic);
            }
        }
        org.luava.runtime.eval.GCManager.unregisterState(this);
        cancelled = true;
        if (loopGuard instanceof Guard g) {
            g.cancel();
        }
    }
}

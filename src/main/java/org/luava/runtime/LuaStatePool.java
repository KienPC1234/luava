/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bounded pool of {@link LuaState} instances with strict total-capacity
 * admission control, ownership/lease verification, execution-policy retention,
 * and configurable state isolation.
 */
public final class LuaStatePool implements AutoCloseable {

    public enum IsolationMode {
        /**
         * Reuse healthy states across leases while preserving globals and
         * re-enforcing the pool's {@link ExecutionPolicy} before each checkout.
         * Intended only for trusted or same-tenant workloads sharing state.
         */
        STATEFUL,
        /**
         * Guarantees strict cross-tenant isolation: used states are never
         * returned to subsequent tenants (retired on release), preventing any
         * globals, registry, metatable, upvalue, or finalizer leakage.
         */
        ISOLATED
    }

    private static final int DEFAULT_SNAPSHOT_DEPTH = 64;

    private final ArrayBlockingQueue<LuaState> idle;
    private final Semaphore permits;
    private final int maxIdle;
    private final int maxTotal;
    private final Duration acquireTimeout;
    private final IsolationMode isolationMode;
    private final ExecutionPolicy policy;
    private final Supplier<LuaState> factory;
    private final int maxSnapshotDepth;

    private final Object leaseLock = new Object();
    private final IdentityHashMap<LuaState, Lease> activeLeases = new IdentityHashMap<>();
    private final IdentityHashMap<LuaState, Boolean> managedStates = new IdentityHashMap<>();
    private int initializingCount;
    private volatile boolean closed;

    /**
     * Scoped lease guaranteeing single release back to its owning pool.
     * Callers should execute work through the {@code Lease} (or within its
     * {@code try}-with-resources scope) rather than retaining {@link #state()}
     * references past {@link #close()}.
     */
    public final class Lease implements AutoCloseable {
        private final LuaState state;
        private final long generation;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Lease(LuaState state, long generation) {
            this.state = state;
            this.generation = generation;
        }

        /**
         * Returns the leased {@link LuaState} while this lease is active.
         * Retaining this reference past {@link #close()} violates the scoped
         * contract; pooled states returned to {@code idle} reject calls made
         * outside an active lease, and states with in-flight async work are
         * retired on release.
         */
        public LuaState state() {
            if (released.get() || state.currentLeaseGeneration() != generation) {
                throw new LuaException("lease has already been released");
            }
            return state;
        }

        public LuaValue eval(LuaState.CompiledChunk chunk) {
            return state().eval(chunk);
        }

        public LuaValue eval(String luaSource) {
            return state().eval(luaSource);
        }

        public LuaValue eval(String luaSource, String chunkName) {
            return state().eval(luaSource, chunkName);
        }

        public LuaValue evalSnapshot(LuaState.CompiledChunk chunk) {
            return snapshotValue(eval(chunk), maxSnapshotDepth);
        }

        public <T> T withState(Function<LuaState, T> fn) {
            if (fn == null) {
                throw new IllegalArgumentException("fn must not be null");
            }
            return fn.apply(state());
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                releaseInternal(state, this);
            }
        }
    }

    private LuaStatePool(Builder builder) {
        this.maxIdle = builder.maxIdle;
        this.maxTotal = builder.maxTotal;
        this.acquireTimeout = builder.acquireTimeout;
        this.isolationMode = builder.isolationMode;
        this.policy = builder.policy;
        this.factory = builder.factory;
        this.maxSnapshotDepth = builder.maxSnapshotDepth;
        this.idle = new ArrayBlockingQueue<>(maxIdle);
        this.permits = new Semaphore(maxTotal, true);
    }

    /** Backwards-compatible bounded stateful pool ({@code maxTotal == maxIdle}). */
    public LuaStatePool(int maxIdle, Supplier<LuaState> factory) {
        this(builder().maxIdle(maxIdle).maxTotal(maxIdle).factory(factory).isolationMode(IsolationMode.STATEFUL));
    }

    /** Backwards-compatible bounded stateful pool ({@code maxTotal == maxIdle}). */
    public LuaStatePool(int maxIdle) {
        this(maxIdle, LuaState::new);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Creates a strict cross-tenant isolated pool governed by {@code policy}. */
    public static LuaStatePool isolated(int maxTotal, ExecutionPolicy policy) {
        return builder()
                .isolationMode(IsolationMode.ISOLATED)
                .maxTotal(maxTotal)
                .maxIdle(maxTotal)
                .policy(policy)
                .build();
    }

    /** Creates a stateful same-tenant pool governed by {@code policy}. */
    public static LuaStatePool stateful(int maxTotal, ExecutionPolicy policy) {
        return builder()
                .isolationMode(IsolationMode.STATEFUL)
                .maxTotal(maxTotal)
                .maxIdle(maxTotal)
                .policy(policy)
                .build();
    }

    /** Borrows a state wrapped in an idempotent {@link Lease}. */
    public Lease acquireLease() {
        if (closed) {
            throw new LuaException("pool is closed");
        }
        acquirePermit();
        LuaState state = null;
        boolean closeOnRollback = true;
        boolean handoffSucceeded = false;
        boolean creationReserved = false;
        try {
            if (closed) {
                throw new LuaException("pool is closed");
            }
            boolean fromIdle = false;
            state = pollHealthyIdle();
            if (state != null) {
                fromIdle = true;
            } else {
                synchronized (leaseLock) {
                    if (closed) {
                        throw new LuaException("pool is closed");
                    }
                    initializingCount++;
                    creationReserved = true;
                }
                state = createFreshState();
            }
            long gen;
            synchronized (leaseLock) {
                if (creationReserved) {
                    initializingCount--;
                    creationReserved = false;
                }
                if (!fromIdle && (managedStates.containsKey(state) || !state.tryClaimPool(this))) {
                    closeOnRollback = false;
                    throw new LuaException("pool factory returned a LuaState already managed by another pool or lease");
                }
                if (activeLeases.containsKey(state)) {
                    closeOnRollback = false;
                    throw new LuaException("LuaState is already actively leased");
                }
                if (closed) {
                    throw new LuaException("pool is closed");
                }
                managedStates.put(state, Boolean.TRUE);
                gen = state.nextPoolLeaseGeneration();
            }
            if (policy != null) {
                state.applyPolicy(policy);
            }
            Lease lease = new Lease(state, gen);
            synchronized (leaseLock) {
                if (closed) {
                    throw new LuaException("pool is closed");
                }
                activeLeases.put(state, lease);
            }
            handoffSucceeded = true;
            return lease;
        } finally {
            if (creationReserved) {
                synchronized (leaseLock) {
                    initializingCount--;
                }
            }
            if (!handoffSucceeded) {
                if (state != null && closeOnRollback) {
                    retireState(state);
                }
                permits.release();
            }
        }
    }

    public LuaState acquire() {
        return acquireLease().state();
    }

    /**
     * Returns {@code state} to this pool. Rejects foreign states and duplicate
     * releases rather than corrupting pool accounting or handing the same
     * state to two callers.
     */
    public void release(LuaState state) {
        if (state == null) {
            return;
        }
        Lease lease;
        synchronized (leaseLock) {
            lease = activeLeases.get(state);
        }
        if (lease == null || !lease.released.compareAndSet(false, true)) {
            throw new LuaException("state does not belong to this pool or was already released");
        }
        releaseInternal(state, lease);
    }

    private void releaseInternal(LuaState state, Lease expectedLease) {
        try {
            synchronized (leaseLock) {
                Lease removed = activeLeases.get(state);
                if (removed == expectedLease) {
                    activeLeases.remove(state);
                }
            }
            if (isolationMode == IsolationMode.ISOLATED
                    || closed
                    || state.isTainted()
                    || state.hasPendingAsyncTasks()
                    || state.isExecuting()) {
                retireState(state);
                return;
            }
            if (policy != null) {
                try {
                    policy.applyTo(state);
                } catch (Throwable t) {
                    retireState(state);
                    return;
                }
            }
            if (closed || state.isTainted() || state.hasPendingAsyncTasks() || state.isExecuting()) {
                retireState(state);
                return;
            }
            state.markPoolIdle(expectedLease.generation);
            if (!idle.offer(state)) {
                retireState(state);
                return;
            }
            if (closed && idle.remove(state)) {
                retireState(state);
            }
        } finally {
            permits.release();
        }
    }

    /**
     * Evaluates {@code chunk} inside a scoped lease. Isolated pools return a
     * detached snapshot so mutable state-owned tables/userdata cannot outlive
     * the lease; use {@link #execute} or {@link #acquireLease()} to inspect
     * live results inside the lease lifetime.
     */
    public LuaValue eval(LuaState.CompiledChunk chunk) {
        try (Lease lease = acquireLease()) {
            LuaValue res = lease.eval(chunk);
            if (isolationMode == IsolationMode.ISOLATED) {
                return snapshotValue(res, maxSnapshotDepth);
            }
            return res;
        }
    }

    /** Evaluates {@code chunk} and always detaches the result before releasing the lease. */
    public LuaValue evalSnapshot(LuaState.CompiledChunk chunk) {
        try (Lease lease = acquireLease()) {
            return lease.evalSnapshot(chunk);
        }
    }

    /** Evaluates {@code chunk} and maps its result while the lease is still active. */
    public <T> T execute(LuaState.CompiledChunk chunk, Function<LuaValue, T> mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        try (Lease lease = acquireLease()) {
            return mapper.apply(lease.eval(chunk));
        }
    }

    /** Pre-creates up to {@code count} idle states respecting both {@code maxIdle} and {@code maxTotal}. */
    public int warmIdle(int count) {
        int created = 0;
        int target = Math.min(Math.max(0, count), maxIdle);
        while (!closed && created < target && idle.size() < target) {
            if (!permits.tryAcquire()) {
                break;
            }
            LuaState state = null;
            boolean closeOnRollback = true;
            boolean slotReserved = false;
            try {
                synchronized (leaseLock) {
                    if (closed
                            || idle.size() >= target
                            || managedStates.size() + initializingCount >= maxTotal) {
                        break;
                    }
                    initializingCount++;
                    slotReserved = true;
                }
                state = createFreshState();
                long gen;
                synchronized (leaseLock) {
                    initializingCount--;
                    slotReserved = false;
                    if (closed || managedStates.size() >= maxTotal) {
                        break;
                    }
                    if (managedStates.containsKey(state) || !state.tryClaimPool(this)) {
                        closeOnRollback = false;
                        throw new LuaException("pool factory returned a LuaState already managed by another pool or lease");
                    }
                    managedStates.put(state, Boolean.TRUE);
                    gen = state.nextPoolLeaseGeneration();
                }
                if (policy != null) {
                    state.applyPolicy(policy);
                }
                state.markPoolIdle(gen);
                if (idle.offer(state)) {
                    state = null;
                    created++;
                } else {
                    break;
                }
            } finally {
                if (slotReserved) {
                    synchronized (leaseLock) {
                        initializingCount--;
                    }
                }
                if (state != null && closeOnRollback) {
                    retireState(state);
                }
                permits.release();
            }
        }
        return created;
    }

    public int activeCount() {
        synchronized (leaseLock) {
            return activeLeases.size();
        }
    }

    /** Total resident states currently managed by this pool (active plus idle). */
    public int totalCount() {
        synchronized (leaseLock) {
            return managedStates.size();
        }
    }

    public int idleCount() {
        return idle.size();
    }

    public int maxIdle() {
        return maxIdle;
    }

    public int maxTotal() {
        return maxTotal;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    public IsolationMode isolationMode() {
        return isolationMode;
    }

    public ExecutionPolicy policy() {
        return policy;
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        LuaState state;
        while ((state = idle.poll()) != null) {
            retireState(state);
        }
    }

    private void acquirePermit() {
        try {
            if (acquireTimeout == null) {
                permits.acquire();
            } else if (!permits.tryAcquire(acquireTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new LuaException("timed out waiting for LuaState from pool");
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LuaException("interrupted waiting for LuaState from pool");
        }
    }

    private LuaState pollHealthyIdle() {
        LuaState candidate;
        while ((candidate = idle.poll()) != null) {
            if (!candidate.isTainted() && !candidate.hasPendingAsyncTasks() && !candidate.isExecuting()) {
                return candidate;
            }
            retireState(candidate);
        }
        return null;
    }

    private LuaState createFreshState() {
        LuaState created = factory.get();
        if (created == null) {
            throw new LuaException("pool factory returned null LuaState");
        }
        if (created.isClosed()) {
            throw new LuaException("pool factory returned closed LuaState");
        }
        return created;
    }

    private void retireState(LuaState state) {
        synchronized (leaseLock) {
            managedStates.remove(state);
        }
        state.markPoolRetired();
        state.releasePoolOwnership(this);
        closeQuietly(state);
    }

    private static void closeQuietly(LuaState state) {
        try {
            state.close();
        } catch (Throwable ignored) {
        }
    }

    /** Produces a detached deep copy of plain Lua values; rejects live state handles. */
    public static LuaValue snapshotValue(LuaValue value, int maxDepth) {
        return snapshotRecursive(value, new IdentityHashMap<>(), 0, maxDepth);
    }

    private static LuaValue snapshotRecursive(
            LuaValue value, IdentityHashMap<LuaTable, LuaTable> visited, int depth, int maxDepth) {
        if (value == null || value.isNil()
                || value instanceof LuaBoolean
                || value instanceof LuaInteger
                || value instanceof LuaFloat
                || value instanceof LuaString) {
            return value != null ? value : LuaNil.NIL;
        }
        if (depth >= maxDepth) {
            throw new LuaException("result snapshot exceeded max nesting depth (" + maxDepth + ")");
        }
        if (value instanceof Varargs va) {
            int n = va.count();
            LuaValue[] items = new LuaValue[n];
            for (int i = 0; i < n; i++) {
                items[i] = snapshotRecursive(va.arg(i + 1), visited, depth + 1, maxDepth);
            }
            return Varargs.of(items);
        }
        if (value instanceof LuaTable tbl) {
            LuaTable existing = visited.get(tbl);
            if (existing != null) {
                return existing;
            }
            LuaTable copy = new LuaTable();
            visited.put(tbl, copy);
            tbl.forEach((k, v) -> {
                LuaValue kCopy = snapshotRecursive(k, visited, depth + 1, maxDepth);
                LuaValue vCopy = snapshotRecursive(v, visited, depth + 1, maxDepth);
                copy.rawset(kCopy, vCopy);
            });
            return copy;
        }
        throw new LuaException("cannot return state-bound " + value.typeName()
                + " outside pool lease; use acquireLease() or execute()");
    }

    public static final class Builder {
        private int maxIdle = 8;
        private int maxTotal = 8;
        private Duration acquireTimeout;
        private IsolationMode isolationMode = IsolationMode.ISOLATED;
        private ExecutionPolicy policy;
        private Supplier<LuaState> factory = LuaState::new;
        private int maxSnapshotDepth = DEFAULT_SNAPSHOT_DEPTH;

        private Builder() {}

        public Builder maxIdle(int maxIdle) {
            if (maxIdle <= 0) {
                throw new IllegalArgumentException("maxIdle must be positive");
            }
            this.maxIdle = maxIdle;
            return this;
        }

        public Builder maxTotal(int maxTotal) {
            if (maxTotal <= 0) {
                throw new IllegalArgumentException("maxTotal must be positive");
            }
            this.maxTotal = maxTotal;
            return this;
        }

        public Builder acquireTimeout(Duration timeout) {
            if (timeout != null && timeout.isNegative()) {
                throw new IllegalArgumentException("acquireTimeout must not be negative");
            }
            this.acquireTimeout = timeout;
            return this;
        }

        public Builder isolationMode(IsolationMode mode) {
            if (mode == null) {
                throw new IllegalArgumentException("isolationMode must not be null");
            }
            this.isolationMode = mode;
            return this;
        }

        public Builder policy(ExecutionPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder factory(Supplier<LuaState> factory) {
            if (factory == null) {
                throw new IllegalArgumentException("factory must not be null");
            }
            this.factory = factory;
            return this;
        }

        public Builder maxSnapshotDepth(int depth) {
            if (depth <= 0) {
                throw new IllegalArgumentException("maxSnapshotDepth must be positive");
            }
            this.maxSnapshotDepth = depth;
            return this;
        }

        public LuaStatePool build() {
            if (maxIdle > maxTotal) {
                maxIdle = maxTotal;
            }
            return new LuaStatePool(this);
        }
    }
}

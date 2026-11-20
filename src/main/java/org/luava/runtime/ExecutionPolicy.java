/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.luava.binding.JavaAccessPolicy;

import java.time.Duration;
import java.util.List;

/**
 * Immutable execution and resource policy that can be attached to a
 * {@link LuaState} or enforced across every acquisition of a
 * {@link LuaStatePool}.
 */
public final class ExecutionPolicy {
    public static final int DEFAULT_MAX_PENDING_ASYNC_TASKS = 1024;

    private final boolean sandbox;
    private final JavaAccessPolicy javaAccessPolicy;
    private final List<String> allowLibraries;
    private final List<String> denyLibraries;
    private final long instructionLimit;
    private final Duration timeout;
    private final long maxAllocationBytes;
    private final long maxMemoryBytes;
    private final int maxCoroutines;
    private final int maxCallDepth;
    private final int maxPendingAsyncTasks;
    private final LuaState.AsyncRejectionPolicy asyncRejectionPolicy;
    private final Boolean jitEnabled;
    private final boolean cancellable;

    private ExecutionPolicy(Builder builder) {
        this.sandbox = builder.sandbox;
        this.javaAccessPolicy = builder.javaAccessPolicy;
        this.allowLibraries = builder.allowLibraries != null ? List.copyOf(builder.allowLibraries) : List.of();
        this.denyLibraries = builder.denyLibraries != null ? List.copyOf(builder.denyLibraries) : List.of();
        this.instructionLimit = builder.instructionLimit;
        this.timeout = builder.timeout;
        this.maxAllocationBytes = builder.maxAllocationBytes;
        this.maxMemoryBytes = builder.maxMemoryBytes;
        this.maxCoroutines = builder.maxCoroutines;
        this.maxCallDepth = builder.maxCallDepth;
        this.maxPendingAsyncTasks = builder.maxPendingAsyncTasks;
        this.asyncRejectionPolicy = builder.asyncRejectionPolicy != null
                ? builder.asyncRejectionPolicy
                : LuaState.AsyncRejectionPolicy.REJECT;
        this.jitEnabled = builder.jitEnabled;
        this.cancellable = builder.cancellable;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Conservative default policy suitable for untrusted multi-tenant chunks. */
    public static ExecutionPolicy sandboxed(long instructionLimit, Duration timeout) {
        return builder()
                .sandbox(true)
                .instructionLimit(instructionLimit)
                .timeout(timeout)
                .cancellable(true)
                .build();
    }

    public boolean isSandbox() {
        return sandbox;
    }

    public JavaAccessPolicy javaAccessPolicy() {
        return javaAccessPolicy;
    }

    public List<String> allowLibraries() {
        return allowLibraries;
    }

    public List<String> denyLibraries() {
        return denyLibraries;
    }

    public long instructionLimit() {
        return instructionLimit;
    }

    public Duration timeout() {
        return timeout;
    }

    public long maxAllocationBytes() {
        return maxAllocationBytes;
    }

    public long maxMemoryBytes() {
        return maxMemoryBytes;
    }

    public int maxCoroutines() {
        return maxCoroutines;
    }

    public int maxCallDepth() {
        return maxCallDepth;
    }

    public int maxPendingAsyncTasks() {
        return maxPendingAsyncTasks;
    }

    public LuaState.AsyncRejectionPolicy asyncRejectionPolicy() {
        return asyncRejectionPolicy;
    }

    public Boolean jitEnabled() {
        return jitEnabled;
    }

    public boolean isCancellable() {
        return cancellable;
    }

    /** Applies all configured controls atomically to {@code state}. */
    public void applyTo(LuaState state) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        state.applyPolicy(this);
    }

    public static final class Builder {
        private boolean sandbox;
        private JavaAccessPolicy javaAccessPolicy;
        private List<String> allowLibraries;
        private List<String> denyLibraries;
        private long instructionLimit;
        private Duration timeout;
        private long maxAllocationBytes;
        private long maxMemoryBytes;
        private int maxCoroutines;
        private int maxCallDepth;
        private int maxPendingAsyncTasks = DEFAULT_MAX_PENDING_ASYNC_TASKS;
        private LuaState.AsyncRejectionPolicy asyncRejectionPolicy = LuaState.AsyncRejectionPolicy.REJECT;
        private Boolean jitEnabled;
        private boolean cancellable;

        private Builder() {}

        public Builder sandbox(boolean sandbox) {
            this.sandbox = sandbox;
            return this;
        }

        public Builder javaAccessPolicy(JavaAccessPolicy policy) {
            this.javaAccessPolicy = policy;
            return this;
        }

        public Builder allowLibraries(String... libs) {
            this.allowLibraries = (libs != null) ? List.of(libs) : List.of();
            return this;
        }

        public Builder denyLibraries(String... libs) {
            this.denyLibraries = (libs != null) ? List.of(libs) : List.of();
            return this;
        }

        public Builder instructionLimit(long instructionLimit) {
            this.instructionLimit = instructionLimit;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder maxAllocationBytes(long maxAllocationBytes) {
            this.maxAllocationBytes = maxAllocationBytes;
            return this;
        }

        public Builder maxMemoryBytes(long maxMemoryBytes) {
            this.maxMemoryBytes = maxMemoryBytes;
            return this;
        }

        public Builder maxCoroutines(int maxCoroutines) {
            this.maxCoroutines = maxCoroutines;
            return this;
        }

        public Builder maxCallDepth(int maxCallDepth) {
            this.maxCallDepth = maxCallDepth;
            return this;
        }

        public Builder maxPendingAsyncTasks(int maxPendingAsyncTasks) {
            this.maxPendingAsyncTasks = maxPendingAsyncTasks;
            return this;
        }

        public Builder asyncRejectionPolicy(LuaState.AsyncRejectionPolicy policy) {
            this.asyncRejectionPolicy = policy;
            return this;
        }

        public Builder jitEnabled(Boolean jitEnabled) {
            this.jitEnabled = jitEnabled;
            return this;
        }

        public Builder cancellable(boolean cancellable) {
            this.cancellable = cancellable;
            return this;
        }

        public ExecutionPolicy build() {
            return new ExecutionPolicy(this);
        }
    }
}

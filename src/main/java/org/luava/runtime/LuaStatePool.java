/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Supplier;

/**
 * Bounded pool of reusable {@link LuaState} instances for high-throughput
 * host embedding. Avoids repeated stdlib initialization while ensuring each
 * execution borrows a non-closed state.
 */
public final class LuaStatePool implements AutoCloseable {
    private final ArrayBlockingQueue<LuaState> idle;
    private final Supplier<LuaState> factory;
    private volatile boolean closed;

    public LuaStatePool(int maxIdle, Supplier<LuaState> factory) {
        if (maxIdle <= 0) {
            throw new IllegalArgumentException("maxIdle must be positive");
        }
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.idle = new ArrayBlockingQueue<>(maxIdle);
        this.factory = factory;
    }

    public LuaStatePool(int maxIdle) {
        this(maxIdle, LuaState::new);
    }

    public LuaState acquire() {
        if (closed) {
            throw new LuaException("pool is closed");
        }
        LuaState state;
        while ((state = idle.poll()) != null) {
            if (!state.isClosed()) {
                return state;
            }
        }
        return factory.get();
    }

    public void release(LuaState state) {
        if (state == null) {
            return;
        }
        if (closed || state.isClosed()) {
            state.close();
            return;
        }
        state.clearGuard();
        if (!idle.offer(state)) {
            state.close();
        }
    }

    public LuaValue eval(LuaState.CompiledChunk chunk) {
        LuaState state = acquire();
        try {
            return state.eval(chunk);
        } finally {
            release(state);
        }
    }

    @Override
    public void close() {
        closed = true;
        LuaState state;
        while ((state = idle.poll()) != null) {
            state.close();
        }
    }
}

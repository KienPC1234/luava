/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

/**
 * A point-in-time observability snapshot for one {@link LuaState}, returned by
 * {@link LuaState#stats()}. All fields are cumulative counters (monotonic
 * except {@code allocBytes}, which is a gauge); the snapshot itself is
 * immutable and detached from the state.
 *
 * <ul>
 *   <li>{@code calls} — top-level host entries on this state
 *       ({@code eval} + {@code call}). Nested Lua-to-Lua calls are not
 *       counted; per-proto interpreter hotness lives on
 *       {@code LuaProto.hotCount}.</li>
 *   <li>{@code jitHits} — cached protos of this state that currently hold a
 *       compiled JIT kernel ({@code jitCode != null}). A gauge, not a
 *       cumulative entry count: kernels can be disarmed back to the
 *       interpreter.</li>
 *   <li>{@code deoptCount} — summed {@code deopts + structuralDeopts} over
 *       this state's cached compiled protos.</li>
 *   <li>{@code allocBytes} — JVM-wide uncollected allocation debt reported by
 *       the GC manager (gauge; shared across states by design).</li>
 *   <li>{@code instructions} — total bytecode instruction words compiled for
 *       this state (sum of {@code code.length} over each freshly compiled
 *       proto tree, including nested protos). Measures load/compile pressure,
 *       not dynamic executed instructions.</li>
 * </ul>
 */
public final class Stats {
    public final long calls;
    public final long jitHits;
    public final long deoptCount;
    public final long allocBytes;
    public final long instructions;

    public Stats(long calls, long jitHits, long deoptCount, long allocBytes, long instructions) {
        this.calls = calls;
        this.jitHits = jitHits;
        this.deoptCount = deoptCount;
        this.allocBytes = allocBytes;
        this.instructions = instructions;
    }

    @Override
    public String toString() {
        return "Stats[calls=" + calls
                + ", jitHits=" + jitHits
                + ", deoptCount=" + deoptCount
                + ", allocBytes=" + allocBytes
                + ", instructions=" + instructions + "]";
    }
}

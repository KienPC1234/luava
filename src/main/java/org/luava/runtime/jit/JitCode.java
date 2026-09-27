/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import java.lang.invoke.MethodHandle;
import org.luava.runtime.bytecode.LuaProto;

/**
 * A compiled Lua proto: one JVM method per Lua function.
 *
 * <p>The handle has the exact type
 * {@code (LuaClosure, Object[], long[], byte[], LuaValue[], int)long}:
 * current closure, upvalue array, primitive/type/object stacks and the
 * callee register-window base. It returns the single integer result.
 */
public final class JitCode {
    public final LuaProto proto;
    public final MethodHandle handle;
    /** Object-returning variant (null when the proto returns an integer). */
    public final MethodHandle objHandle;
    /**
     * On-stack replacement entry
     * {@code (LuaClosure, Object[], long[], byte[], LuaValue[], int base, int pc)long}
     * that branches into the kernel body at a loop-back-edge label. Null when
     * the proto has no in-subset loop header, or when OSR is disabled.
     */
    public final MethodHandle osrHandle;
    /**
     * Per-pc flags of the loop headers at which {@link #osrHandle} may be
     * entered; null when there is no OSR entry. The interpreter must check
     * this before calling, because {@code execOsr} treats an unflagged pc as
     * the ordinary pc-0 entry.
     */
    public final boolean[] osrTargets;
    public final int maxStack;
    public final int numParams;
    /** True when the proto has no observable side effects (pure integer
     * kernel): restart-from-entry deopt is safe and other JIT code may
     * call it directly. */
    public final boolean pure;
    /** True when every reachable single-value return yields an integer. */
    public final boolean returnsInt;
    /**
     * True when the proto yields no value at all (every return is a bare
     * {@code return}/fall-off). The kernel returns a sentinel {@code long};
     * callers materialize zero results (or nil-fill an expected count).
     */
    public final boolean returnsVoid;
    public int deopts;
    /**
     * Structural deopts (impure-proto CALL/TAILCALL) are expected every run,
     * so they get their own, much larger budget: a proto that runs a hot loop
     * before its trailing call keeps benefiting, but one whose compiled
     * prefix never pays for the per-call exception is disarmed eventually.
     */
    public int structuralDeopts;

    public JitCode(LuaProto proto, MethodHandle handle, boolean pure) {
        this(proto, handle, null, null, null, pure, true, false);
    }

    public JitCode(LuaProto proto, MethodHandle handle, MethodHandle objHandle, boolean pure,
            boolean returnsInt) {
        this(proto, handle, objHandle, null, null, pure, returnsInt, false);
    }

    public JitCode(LuaProto proto, MethodHandle handle, MethodHandle objHandle, MethodHandle osrHandle,
            boolean[] osrTargets, boolean pure, boolean returnsInt, boolean returnsVoid) {
        this.proto = proto;
        this.handle = handle;
        this.objHandle = objHandle;
        this.osrHandle = osrHandle;
        this.osrTargets = osrTargets;
        this.maxStack = proto.maxStackSize;
        this.numParams = proto.numParams;
        this.pure = pure;
        this.returnsInt = returnsInt;
        this.returnsVoid = returnsVoid;
        this.deopts = 0;
        this.structuralDeopts = 0;
    }
}

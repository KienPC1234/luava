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
    public final int maxStack;
    public final int numParams;
    /** True when the proto has no observable side effects (pure integer
     * kernel): restart-from-entry deopt is safe and other JIT code may
     * call it directly. */
    public final boolean pure;
    /** True when every reachable single-value return yields an integer. */
    public final boolean returnsInt;
    public int deopts;

    public JitCode(LuaProto proto, MethodHandle handle, boolean pure) {
        this(proto, handle, null, pure, true);
    }

    public JitCode(LuaProto proto, MethodHandle handle, MethodHandle objHandle, boolean pure,
            boolean returnsInt) {
        this.proto = proto;
        this.handle = handle;
        this.objHandle = objHandle;
        this.maxStack = proto.maxStackSize;
        this.numParams = proto.numParams;
        this.pure = pure;
        this.returnsInt = returnsInt;
        this.deopts = 0;
    }
}

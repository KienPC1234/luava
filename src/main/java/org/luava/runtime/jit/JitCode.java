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
    public final int maxStack;
    public final int numParams;
    public int deopts;

    public JitCode(LuaProto proto, MethodHandle handle) {
        this.proto = proto;
        this.handle = handle;
        this.maxStack = proto.maxStackSize;
        this.numParams = proto.numParams;
        this.deopts = 0;
    }
}

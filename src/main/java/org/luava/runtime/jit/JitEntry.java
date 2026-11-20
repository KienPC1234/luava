/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.LuaClosure;

/** Direct virtual-dispatch entry implemented by JIT-compiled classes. */
public interface JitEntry {
    long callInt(LuaClosure closure, Object[] upvalues, long[] p, byte[] t, LuaValue[] o, int base)
            throws Throwable;

    LuaValue callObj(LuaClosure closure, Object[] upvalues, long[] p, byte[] t, LuaValue[] o, int base)
            throws Throwable;

    default void callInto(
            LuaClosure closure, Object[] upvalues, long[] p, byte[] t, LuaValue[] o, int base, int dst)
            throws Throwable {
        LuaValue r = callObj(closure, upvalues, p, t, o, base);
        org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst, r);
    }
}

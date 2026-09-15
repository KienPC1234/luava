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

/**
 * Cold helpers invoked from JIT-compiled code. Anything non-trivial
 * (generic calls, deopt construction) lives here so generated methods stay
 * small enough for C2 to inline.
 */
public final class JitRuntime {
    private JitRuntime() {}

    /**
     * Invokes an already-compiled callee on the shared register window.
     * The callee is pure (checked by the caller), so a guard failure inside
     * simply deopts the whole outer call for interpreter restart.
     */
    public static long invoke(JitCode jc, LuaClosure callee, Object[] up, long[] p, byte[] t, LuaValue[] o,
            int base) throws Throwable {
        return (long) jc.handle.invokeExact(callee, up, p, t, o, base);
    }

    /** Fills registers [from, to) with nil (missing-call-argument semantics). */
    public static void nilFill(long[] p, byte[] t, LuaValue[] o, int from, int to) {
        for (int i = from; i < to; i++) {
            p[i] = 0;
            t[i] = 0;
            o[i] = null;
        }
    }
}

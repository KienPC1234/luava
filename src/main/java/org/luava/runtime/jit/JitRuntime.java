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

    /** Object-returning variant for factories and escaping values. */
    public static LuaValue invokeObj(JitCode jc, LuaClosure callee, Object[] up, long[] p, byte[] t, LuaValue[] o,
            int base) throws Throwable {
        return (LuaValue) jc.objHandle.invokeExact(callee, up, p, t, o, base);
    }

    /**
     * Lua {@code <<} with the 5.4 negative-count rule (luaV_shiftl): a count
     * of |n| >= 64 yields 0, a negative count shifts the other way. Mirrors
     * {@code LuaValue.shl}.
     */
    public static long shiftLeft(long value, long shift) {
        if (shift >= 64 || shift <= -64) return 0;
        if (shift < 0) return value >>> -shift;
        return value << shift;
    }

    /** Lua {@code >>}; see {@link #shiftLeft}. Mirrors {@code LuaValue.shr}. */
    public static long shiftRight(long value, long shift) {
        if (shift >= 64 || shift <= -64) return 0;
        if (shift < 0) return value << -shift;
        return value >>> shift;
    }

    /** Fills registers [from, to) with nil (missing-call-argument semantics). */
    public static void nilFill(long[] p, byte[] t, LuaValue[] o, int from, int to) {
        for (int i = from; i < to; i++) {
            p[i] = 0;
            t[i] = 0;
            o[i] = null;
        }
    }

    /**
     * Concatenates registers {@code [from, to)} into one string, mirroring
     * the interpreter's {@code OP_CONCAT}. Returns {@code null} when any
     * operand is not a plain string/number (a metatable {@code __concat}
     * could run arbitrary code), signalling the generated code to deopt.
     */
    public static org.luava.runtime.LuaValue concatRange(long[] p, byte[] t, LuaValue[] o,
            int from, int to) {
        org.luava.runtime.LuaValue res =
                org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, from);
        if (!isConcatable(res)) {
            return null;
        }
        for (int i = from + 1; i < to; i++) {
            org.luava.runtime.LuaValue v =
                    org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, i);
            if (!isConcatable(v)) {
                return null;
            }
            res = res.concat(v);
        }
        return res;
    }

    private static boolean isConcatable(org.luava.runtime.LuaValue v) {
        return v.isString() || v.isNumber();
    }

    /**
     * Mirrors the interpreter's fixed-count {@code OP_SETLIST}: bulk-appends
     * registers into a table. Metatables bypass raw writes exactly like the
     * interpreter (plain tables take the raw path, anything else the
     * {@code set} path that may raise).
     */
    public static void setList(long[] p, byte[] t, LuaValue[] o, int funcIdx, int n, int last) {
        org.luava.runtime.LuaValue tbl =
                org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx);
        if (tbl instanceof org.luava.runtime.LuaTable lt) {
            for (int i = 1; i <= n; i++) {
                lt.rawset(org.luava.runtime.LuaInteger.valueOf(last + i),
                        org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + i));
            }
        } else {
            for (int i = 1; i <= n; i++) {
                tbl.set(org.luava.runtime.LuaInteger.valueOf(last + i),
                        org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + i));
            }
        }
    }
}

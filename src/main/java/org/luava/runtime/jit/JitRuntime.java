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

    // ------------------------------------------------------------------
    // Register-window rollback (plan.md §5.A.2)
    //
    // A compiled callee runs straight on the shared register stack, so it
    // overwrites the caller's argument registers (its own parameter window
    // aliases them). On a nested deopt the interpreter resumes the caller at
    // the call pc and re-reads those registers, so the callee's prefix writes
    // must be rolled back. Only the argument span [from, from+max(nArgs,
    // numParams)) can overlap caller state: callee locals live above it and
    // the function/result register below it. A compiled TAILCALL shifts the
    // arguments onto the caller's own frame, so its snapshot covers the whole
    // pre-shift caller window. The snapshot lives in a per-thread
    // grow-on-demand raw-triple stack; nesting is safe because a callee's
    // snapshot sits above every live outer snapshot.
    // ------------------------------------------------------------------

    private static final class SnapshotStack {
        long[] p = new long[128];
        byte[] t = new byte[128];
        LuaValue[] o = new LuaValue[128];
        int top;

        void ensure(int extra) {
            if (top + extra > p.length) {
                int n = Math.max(p.length * 2, top + extra);
                p = java.util.Arrays.copyOf(p, n);
                t = java.util.Arrays.copyOf(t, n);
                o = java.util.Arrays.copyOf(o, n);
            }
        }
    }

    private static final ThreadLocal<SnapshotStack> SNAPSHOTS =
            ThreadLocal.withInitial(SnapshotStack::new);

    private static int clampSpan(long[] p, int from, int n) {
        int c = Math.min(n, p.length - from);
        return c < 0 ? 0 : c;
    }

    /** Pushes {@code n} raw triples at {@code from}; returns a cursor. */
    private static int pushWindow(long[] p, byte[] t, LuaValue[] o, int from, int n) {
        int count = clampSpan(p, from, n);
        SnapshotStack s = SNAPSHOTS.get();
        s.ensure(count);
        int at = s.top;
        for (int i = 0; i < count; i++) {
            s.p[at + i] = p[from + i];
            s.t[at + i] = t[from + i];
            s.o[at + i] = o[from + i];
        }
        s.top = at + count;
        return at;
    }

    private static void restoreWindow(int cursor, long[] p, byte[] t, LuaValue[] o, int from, int n) {
        int count = clampSpan(p, from, n);
        SnapshotStack s = SNAPSHOTS.get();
        for (int i = 0; i < count; i++) {
            p[from + i] = s.p[cursor + i];
            t[from + i] = s.t[cursor + i];
            o[from + i] = s.o[cursor + i];
        }
    }

    private static void popWindow(int cursor) {
        SNAPSHOTS.get().top = cursor;
    }

    /** Argument span a compiled call can clobber; see the section comment. */
    private static int callWindow(JitCode jc, int nArgs) {
        return Math.max(nArgs, jc.numParams);
    }

    /**
     * Requests compilation of a callee discovered at a compiled call site.
     * Without this, a callee reached only from already-compiled code (e.g. a
     * method called in a hot loop whose enclosing chunk already tiered up)
     * never gets its interpreter-side hot count bumped, so it stays
     * interpreted forever. Idempotent; the call site still deopts this time
     * and enters the compiled callee once it is ready.
     */
    public static void requestCallee(org.luava.runtime.bytecode.LuaProto proto) {
        try {
            org.luava.runtime.jit.JitCompiler.requestCompile(proto);
        } catch (Throwable t) {
            proto.jitDisabled = true;
        }
    }

    /**
     * The entire general (non-self, non-fused) compiled call, kept out of
     * generated code so a call site is one {@code INVOKESTATIC}: generated
     * code cannot branch cheaply, and inlining the callee guard, arity
     * nil-fill, capacity guard and both return protocols bloated every
     * calling kernel enough to lose C2 optimization of its hot loop.
     *
     * <p>Checks the callee is a pure compiled closure, requests compilation
     * if not yet done, nil-fills missing parameters, snapshots the argument
     * window, invokes under the callee's return protocol, and writes the
     * result triple to {@code argBase-1}. A failure the compiled code cannot
     * complete atomically throws a structural {@link DeoptSignal}; a nested
     * deopt restores the window before propagating.
     */
    public static void callFast(LuaValue callee, long[] p, byte[] t, LuaValue[] o,
            int argBase, int nArgs, int resumePc, boolean resultUsed) throws Throwable {
        if (!(callee instanceof LuaClosure closure)) {
            throw new DeoptSignal(resumePc, true);
        }
        org.luava.runtime.bytecode.LuaProto proto = closure.proto;
        JitCode jc = proto.jitCode;
        if (jc == null) {
            requestCallee(proto);
            throw new DeoptSignal(resumePc, true);
        }
        if (!jc.pure) {
            throw new DeoptSignal(resumePc, true);
        }
        if (resultUsed && jc.returnsVoid) {
            throw new DeoptSignal(resumePc, true);
        }
        int numParams = proto.numParams;
        if (nArgs < numParams) {
            nilFill(p, t, o, argBase + nArgs, argBase + numParams);
        }
        if (argBase + proto.maxStackSize > p.length) {
            throw new DeoptSignal(resumePc, false);
        }
        int win = callWindow(jc, nArgs);
        int cursor = pushWindow(p, t, o, argBase, win);
        int dst = argBase - 1;
        try {
            if (jc.returnsInt) {
                long r = (long) jc.handle.invokeExact(closure, (Object[]) closure.upvals, p, t, o, argBase);
                p[dst] = r;
                t[dst] = org.luava.runtime.bytecode.BytecodeVM.TYPE_INT;
                o[dst] = null;
            } else {
                LuaValue r = (LuaValue) jc.objHandle.invokeExact(closure, (Object[]) closure.upvals,
                        p, t, o, argBase);
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst, r);
            }
        } catch (DeoptSignal d) {
            restoreWindow(cursor, p, t, o, argBase, win);
            throw d;
        } finally {
            popWindow(cursor);
        }
    }

    /**
     * Compiled tail call with rollback. The generated code shifts the
     * arguments down onto the caller's own frame before invoking, so the
     * snapshot must cover the caller's whole pre-shift window and be taken
     * before the shift. On a nested deopt the interpreter re-executes the
     * tail call from intact registers.
     */
    public static long invokeTailSnap(JitCode jc, LuaClosure callee, long[] p, byte[] t, LuaValue[] o,
            int base, int a, int nArgs, int callerMaxStack) throws Throwable {
        int win = Math.max(callerMaxStack, a + 1 + nArgs);
        int cursor = pushWindow(p, t, o, base, win);
        try {
            shiftTailArgs(p, t, o, base, a, nArgs, jc.numParams);
            return (long) jc.handle.invokeExact(callee, (Object[]) callee.upvals, p, t, o, base);
        } catch (DeoptSignal d) {
            restoreWindow(cursor, p, t, o, base, win);
            throw d;
        } finally {
            popWindow(cursor);
        }
    }

    /** Object-returning variant of {@link #invokeTailSnap}. */
    public static LuaValue invokeTailObjSnap(JitCode jc, LuaClosure callee, long[] p, byte[] t, LuaValue[] o,
            int base, int a, int nArgs, int callerMaxStack) throws Throwable {
        int win = Math.max(callerMaxStack, a + 1 + nArgs);
        int cursor = pushWindow(p, t, o, base, win);
        try {
            shiftTailArgs(p, t, o, base, a, nArgs, jc.numParams);
            return (LuaValue) jc.objHandle.invokeExact(callee, (Object[]) callee.upvals, p, t, o, base);
        } catch (DeoptSignal d) {
            restoreWindow(cursor, p, t, o, base, win);
            throw d;
        } finally {
            popWindow(cursor);
        }
    }

    /**
     * Moves {@code nArgs} argument triples from {@code base+a+1..} down to
     * {@code base..} and nil-fills missing parameters, mirroring the
     * interpreter's tail-call frame reuse.
     */
    private static void shiftTailArgs(long[] p, byte[] t, LuaValue[] o, int base, int a, int n, int numParams) {
        for (int i = 0; i < n; i++) {
            int src = base + a + 1 + i;
            int dst = base + i;
            p[dst] = p[src];
            t[dst] = t[src];
            o[dst] = o[src];
        }
        for (int i = n; i < numParams; i++) {
            int dst = base + i;
            p[dst] = 0;
            t[dst] = 0;
            o[dst] = null;
        }
    }

    /**
     * Stores an already-floored double into register {@code idx} with PUC
     * {@code math.floor} subtype semantics: an integral value that fits a Lua
     * integer becomes an unboxed {@code TYPE_INT}, anything else (inf, nan,
     * or out of range) a raw-bit {@code TYPE_FLOAT}. The caller has already
     * computed {@code Math.floor}, so an in-range result is integral and the
     * {@code (long)} cast is exact.
     */
    public static void storeFloored(double d, long[] p, byte[] t, LuaValue[] o, int idx) {
        if (d >= -9223372036854775808.0 && d < 9223372036854775808.0) {
            p[idx] = (long) d;
            t[idx] = org.luava.runtime.bytecode.BytecodeVM.TYPE_INT;
        } else {
            p[idx] = Double.doubleToRawLongBits(d);
            t[idx] = org.luava.runtime.bytecode.BytecodeVM.TYPE_FLOAT;
        }
        o[idx] = null;
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
     * {@code OP_CLOSE A}: closes open upvalues at/above absolute register
     * {@code fromIdx} and any pending to-be-closed variables on the current
     * thread. Mirrors the interpreter's {@code doClose(..., false)}.
     */
    public static void closeAt(org.luava.runtime.bytecode.LuaClosure self, int fromIdx) {
        org.luava.runtime.LuaState state = self.getState();
        org.luava.runtime.concurrency.LuaCoroutine thread = state.getCurrentThread();
        state.closeUpvalues(thread, fromIdx);
        state.closeTbc(thread, fromIdx, null);
    }

    /**
     * Builds the child closure for {@code OP_CLOSURE}, mirroring the
     * interpreter's {@code executeClosure}: stack upvalues become open
     * upvalues on the running thread, parent upvalues are shared by
     * reference, and the stripped flag propagates. The child proto is loaded
     * from {@code self.proto} (the JIT body and its closure always share one
     * proto), so the whole closure captures the caller's register window.
     */
    public static org.luava.runtime.bytecode.LuaClosure buildClosure(
            org.luava.runtime.bytecode.LuaClosure self, int childIdx,
            long[] p, byte[] t, LuaValue[] o, int base) {
        org.luava.runtime.bytecode.LuaProto childProto = self.proto.protos[childIdx];
        org.luava.runtime.LuaState state = self.getState();
        org.luava.runtime.concurrency.LuaCoroutine thread = state.getCurrentThread();
        org.luava.runtime.eval.Upvalue[] ups =
                new org.luava.runtime.eval.Upvalue[childProto.upvalues.length];
        for (int i = 0; i < ups.length; i++) {
            org.luava.runtime.bytecode.UpvalueDesc desc = childProto.upvalues[i];
            if (desc.inStack) {
                ups[i] = state.findOrCreateOpenUpvalue(thread, base + desc.index, desc.name);
            } else {
                ups[i] = self.upvals[desc.index];
            }
        }
        org.luava.runtime.bytecode.LuaClosure child =
                new org.luava.runtime.bytecode.LuaClosure(childProto, ups, self.env, state);
        if (self.isStripped()) {
            child.setStripped(true);
        }
        return child;
    }

    /**
     * Resolves the method for {@code OP_SELF} on a plain table: returns
     * {@code rawget(key)} when the object at {@code objIdx} is a
     * {@code LuaTable} and the raw hit is non-nil. Returns {@code null}
     * otherwise (non-table, or a nil raw hit that a metatable {@code __index}
     * might still resolve) so the generated code deopts to the interpreter.
     * Mirrors the interpreter's {@code executeSelfCached} table fast lane.
     */
    public static LuaValue selfMethod(org.luava.runtime.bytecode.LuaClosure self,
            long[] p, byte[] t, LuaValue[] o, int objIdx, LuaValue key) {
        if (t[objIdx] != org.luava.runtime.bytecode.BytecodeVM.TYPE_OBJECT) {
            return null;
        }
        LuaValue obj = o[objIdx];
        if (obj instanceof org.luava.runtime.LuaTable tbl) {
            LuaValue m = tbl.rawget(key);
            return m.isNil() ? null : m;
        }
        // `str:method()` resolves through the string type metatable (PUC's
        // luaT_gettmbyobj), which is shared and read-only in Lua. Mirroring
        // that lookup here lets `str:gmatch(...)`, `str:sub(...)` and friends
        // run in compiled code; a miss still deopts to the interpreter.
        if (obj instanceof org.luava.runtime.LuaString && key instanceof org.luava.runtime.LuaString) {
            org.luava.runtime.LuaTable mt = self.getState() != null
                    ? self.getState().basicMetatable(org.luava.runtime.LuaType.STRING)
                    : org.luava.runtime.LuaValue.getBasicMetatable(org.luava.runtime.LuaType.STRING);
            if (mt == null) {
                return null;
            }
            LuaValue index = mt.rawget(org.luava.runtime.LuaValue.Meta.INDEX);
            if (!(index instanceof org.luava.runtime.LuaTable idx)) {
                return null;
            }
            LuaValue m = idx.rawget(key);
            return m.isNil() ? null : m;
        }
        return null;
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
     * Inline {@code string.gmatch(s, p)} iterator setup for the JIT: calls
     * the shared {@code StringLib.GMATCH} builtin on registers
     * {@code [argBase, argBase+nArgs)} and returns its result triple. The
     * generated code has already identity-guarded the function register
     * against {@code StringLib.GMATCH}.
     */
    public static org.luava.runtime.Varargs gmatchSetup(long[] p, byte[] t, LuaValue[] o,
            int argBase, int nArgs) {
        LuaValue[] args = new LuaValue[nArgs];
        for (int i = 0; i < nArgs; i++) {
            args[i] = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, argBase + i);
        }
        LuaValue res = org.luava.runtime.standard.StringLib.GMATCH.call(args);
        return res instanceof org.luava.runtime.Varargs va ? va
                : org.luava.runtime.Varargs.of(res);
    }

    /**
     * Writes the iterator-setup results ({@code func, state, control}) into
     * {@code R[dst..dst+nRes-1]}, nil-filling any register beyond the values
     * the factory returned. The generic-for compiler reserves four registers
     * ({@code R[A..A+3]}), so the 4th (the to-be-closed slot) is nil for the
     * built-in factories, exactly as PUC's {@code luaD_poscall} would fill.
     */
    public static void unpackTForSetup(long[] p, byte[] t, LuaValue[] o,
            org.luava.runtime.Varargs triple, int dst, int nRes) {
        LuaValue[] vals = triple != null ? triple.getValuesUnsafe() : null;
        for (int i = 0; i < nRes; i++) {
            org.luava.runtime.LuaValue v = (vals != null && i < vals.length && vals[i] != null)
                    ? vals[i] : org.luava.runtime.LuaNil.NIL;
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst + i, v);
        }
    }

    /**
     * {@code OP_TFORPREP}: registers the loop's 4th slot as a to-be-closed
     * variable, exactly like the interpreter. Runs once per loop entry, so a
     * helper call is negligible. {@code idx} is the absolute R[A+3] index.
     */
    public static void tforPrep(org.luava.runtime.bytecode.LuaClosure self,
            long[] p, byte[] t, LuaValue[] o, int idx) {
        org.luava.runtime.LuaState state = self.getState();
        LuaValue val = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, idx);
        state.pushTbc(idx, val, "(for state)");
    }

    /**
     * {@code OP_TFORCALL} fast lane for the built-in generic-for iterators.
     * Runs the step and writes the {@code c} result registers
     * {@code R[A+4..]}; throws a structural {@link DeoptSignal} (before any
     * side effect) when the iterator is not one the compiled body can
     * complete atomically, so the interpreter re-executes exactly once.
     */
    public static void tforCall(long[] p, byte[] t, LuaValue[] o, int funcIdx, int c, int pc) {
        LuaValue f = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx);
        int nVars = Math.max(1, c);
        int dst = funcIdx + 4;
        if (f == org.luava.runtime.standard.BaseLib.NEXT) {
            LuaValue tv = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 1);
            if (!(tv instanceof org.luava.runtime.LuaTable tbl)) {
                throw new DeoptSignal(pc, true);
            }
            LuaValue ctrl = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 2);
            storeResults(p, t, o, dst, nVars, tbl.next(ctrl));
            return;
        }
        if (f == org.luava.runtime.standard.BaseLib.IPAIRSAUX) {
            LuaValue tv = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 1);
            LuaValue ctrl = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, funcIdx + 2);
            if (!(tv instanceof org.luava.runtime.LuaTable tbl) || !ctrl.isNumber()) {
                throw new DeoptSignal(pc, true);
            }
            // PUC ipairs reads through the normal index path, so an
            // __index metamethod must run interpreted (it may yield).
            org.luava.runtime.LuaTable mt = tbl.getMetatable();
            if (mt != null && !mt.rawget(org.luava.runtime.LuaValue.Meta.INDEX).isNil()) {
                throw new DeoptSignal(pc, true);
            }
            long i = ctrl.toLong() + 1;
            LuaValue val = tbl.rawget(org.luava.runtime.LuaInteger.valueOf(i));
            if (val.isNil()) {
                storeResults(p, t, o, dst, nVars, null);
            } else {
                storeResults(p, t, o, dst, nVars,
                        org.luava.runtime.Varargs.of(org.luava.runtime.LuaInteger.valueOf(i), val));
            }
            return;
        }
        if (f instanceof org.luava.runtime.standard.LuaPattern.GmatchIterator gi) {
            storeResults(p, t, o, dst, nVars, gi.next());
            return;
        }
        // A custom iterator closure or any unrecognized callable: deopt
        // before writing anything so the interpreter re-executes exactly once.
        throw new DeoptSignal(pc, true);
    }

    /** Writes {@code n} result registers from a call result (null = nil). */
    private static void storeResults(long[] p, byte[] t, LuaValue[] o, int dst, int n,
            org.luava.runtime.LuaValue res) {
        if (res instanceof org.luava.runtime.Varargs va) {
            LuaValue[] vals = va.getValuesUnsafe();
            for (int i = 0; i < n; i++) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst + i,
                        i < vals.length && vals[i] != null ? vals[i] : org.luava.runtime.LuaNil.NIL);
            }
        } else {
            org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst,
                    res != null ? res : org.luava.runtime.LuaNil.NIL);
            for (int i = 1; i < n; i++) {
                org.luava.runtime.bytecode.BytecodeVM.setLuaValue(p, t, o, dst + i,
                        org.luava.runtime.LuaNil.NIL);
            }
        }
    }

    /**
     * Inline {@code setmetatable(t, mt)} for the JIT intrinsic. Returns the
     * mutated table, or {@code null} to deopt when the shape is not one the
     * compiled lane completes atomically: a non-table first argument (the
     * interpreter must raise the exact bad-argument error) or a non-table,
     * non-nil second argument. A {@code __gc}/{@code __mode} metatable is also
     * rejected: it registers JVM-wide, non-idempotent bookkeeping that a
     * restart-from-entry would double-apply. The plain method-table case (the
     * OOP constructor idiom) has no such effect and stays inline.
     */
    public static LuaValue setmetatableInline(long[] p, byte[] t, LuaValue[] o, int tblIdx, int mtIdx) {
        if (t[tblIdx] != org.luava.runtime.bytecode.BytecodeVM.TYPE_OBJECT
                || !(o[tblIdx] instanceof org.luava.runtime.LuaTable table)) {
            return null;
        }
        LuaValue mt = org.luava.runtime.bytecode.BytecodeVM.getLuaValue(p, t, o, mtIdx);
        if (!mt.isNil() && !(mt instanceof org.luava.runtime.LuaTable)) {
            return null;
        }
        if (mt instanceof org.luava.runtime.LuaTable mtTable
                && (!mtTable.rawget(org.luava.runtime.LuaValue.Meta.GC).isNil()
                        || !mtTable.rawget(org.luava.runtime.LuaValue.Meta.MODE).isNil())) {
            return null;
        }
        return org.luava.runtime.standard.BaseLib.setmetatableCore(table, mt);
    }

    /**
     * Inline {@code tostring(x)} for the JIT. Returns the rendered
     * {@link LuaValue}, or {@code null} when the result is not safe to
     * compute outside the interpreter: a metatable on the value (its
     * {@code __tostring} would run), a metatable on the number type (its
     * format override), or any object other than a plain string. The caller
     * deopts on {@code null}.
     */
    public static org.luava.runtime.LuaValue tostringInline(
            org.luava.runtime.bytecode.LuaClosure self, long[] p, byte[] t, LuaValue[] o, int argIdx) {
        org.luava.runtime.LuaState state = self.getState();
        byte tag = t[argIdx];
        if (tag == org.luava.runtime.bytecode.BytecodeVM.TYPE_INT) {
            if (state.basicMetatable(org.luava.runtime.LuaType.NUMBER) != null) {
                return null;
            }
            return org.luava.runtime.LuaString.valueOf(Long.toString(p[argIdx]));
        }
        if (tag == org.luava.runtime.bytecode.BytecodeVM.TYPE_FLOAT) {
            if (state.basicMetatable(org.luava.runtime.LuaType.NUMBER) != null) {
                return null;
            }
            return org.luava.runtime.LuaString.valueOf(
                    org.luava.runtime.LuaFloat.valueOf(Double.longBitsToDouble(p[argIdx])).toLuaString());
        }
        if (tag == org.luava.runtime.bytecode.BytecodeVM.TYPE_BOOLEAN) {
            return org.luava.runtime.LuaString.valueOf(p[argIdx] != 0 ? "true" : "false");
        }
        if (tag == org.luava.runtime.bytecode.BytecodeVM.TYPE_NIL) {
            return org.luava.runtime.LuaString.valueOf("nil");
        }
        if (tag == org.luava.runtime.bytecode.BytecodeVM.TYPE_OBJECT) {
            LuaValue v = o[argIdx];
            if (v instanceof org.luava.runtime.LuaString s && s.getMetatable() == null) {
                return s;
            }
        }
        return null;
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

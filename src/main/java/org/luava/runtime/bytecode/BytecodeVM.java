/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

import org.luava.runtime.*;
import org.luava.runtime.concurrency.LuaCoroutine;
import org.luava.runtime.eval.CallStack;
import org.luava.runtime.eval.Upvalue;
import org.luava.runtime.jit.DeoptSignal;
import org.luava.runtime.jit.JitCode;
import org.luava.runtime.jit.JitCompiler;

public final class BytecodeVM {
    public static final byte TYPE_NIL = 0;
    public static final byte TYPE_BOOLEAN = 1;
    public static final byte TYPE_INT = 2;
    public static final byte TYPE_FLOAT = 3;
    public static final byte TYPE_OBJECT = 4;

    public static boolean isNumber(byte t) {
        return t == TYPE_INT || t == TYPE_FLOAT;
    }

    private static final double MAXINTFITSF = 9007199254740992.0; // 2^53

    private static boolean intFitsFloat(long i) {
        return i >= -9007199254740992L && i <= 9007199254740992L;
    }

    private static boolean ltIntFloat(long i, double f) {
        if (Double.isNaN(f)) return false;
        if (intFitsFloat(i)) return (double) i < f;
        long fi = (long) Math.ceil(f);
        if (fi == Long.MIN_VALUE && f < (double) Long.MIN_VALUE) return false;
        if (fi == Long.MAX_VALUE && f >= 9223372036854775808.0) return true;
        return i < fi;
    }

    private static boolean leIntFloat(long i, double f) {
        if (Double.isNaN(f)) return false;
        if (intFitsFloat(i)) return (double) i <= f;
        long fi = (long) Math.floor(f);
        if (fi == Long.MIN_VALUE && f < (double) Long.MIN_VALUE) return false;
        if (fi == Long.MAX_VALUE && f >= 9223372036854775808.0) return true;
        return i <= fi;
    }

    private static boolean ltFloatInt(double f, long i) {
        if (Double.isNaN(f)) return false;
        if (intFitsFloat(i)) return f < (double) i;
        long fi = (long) Math.floor(f);
        if (fi == Long.MIN_VALUE && f < (double) Long.MIN_VALUE) return true;
        if (fi == Long.MAX_VALUE && f >= 9223372036854775808.0) return false;
        return fi < i;
    }

    private static boolean leFloatInt(double f, long i) {
        if (Double.isNaN(f)) return false;
        if (intFitsFloat(i)) return f <= (double) i;
        long fi = (long) Math.ceil(f);
        if (fi == Long.MIN_VALUE && f < (double) Long.MIN_VALUE) return true;
        if (fi == Long.MAX_VALUE && f >= 9223372036854775808.0) return false;
        return fi <= i;
    }

    private static void clearDeadTemp(long[] pStack, byte[] tStack, LuaValue[] oStack,
                                      LuaProto proto, int base, int reg, int pc) {
        int idx = base + reg;
        if (tStack[idx] != TYPE_OBJECT || oStack[idx] == null) return;
        if (proto != null && proto.findLocalVarName(reg, pc) != null) return;
        tStack[idx] = TYPE_NIL;
        pStack[idx] = 0;
        oStack[idx] = null;
    }

    public static LuaValue getLuaValue(long[] pStack, byte[] tStack, LuaValue[] oStack, int idx) {
        switch (tStack[idx]) {
            case TYPE_NIL:
                return LuaNil.NIL;
            case TYPE_BOOLEAN:
                return pStack[idx] != 0 ? LuaBoolean.TRUE : LuaBoolean.FALSE;
            case TYPE_INT:
                return LuaInteger.valueOf(pStack[idx]);
            case TYPE_FLOAT:
                return LuaFloat.valueOf(Double.longBitsToDouble(pStack[idx]));
            default:
                return oStack[idx] != null ? oStack[idx] : LuaNil.NIL;
        }
    }

    public static LuaValue getLuaValueFromRaw(long raw, byte tag, LuaValue obj) {
        switch (tag) {
            case TYPE_NIL:
                return LuaNil.NIL;
            case TYPE_BOOLEAN:
                return raw != 0 ? LuaBoolean.TRUE : LuaBoolean.FALSE;
            case TYPE_INT:
                return LuaInteger.valueOf(raw);
            case TYPE_FLOAT:
                return LuaFloat.valueOf(Double.longBitsToDouble(raw));
            default:
                return obj != null ? obj : LuaNil.NIL;
        }
    }

    public static void setLuaValue(long[] pStack, byte[] tStack, LuaValue[] oStack, int idx, LuaValue val) {
        // Reference-compare the nil singleton before the virtual isNil()
        // so the two hottest cases (nil and primitives) avoid a virtual
        // dispatch. Non-primitive subclasses that override isNil (Varargs)
        // still route through the final isNil() check below.
        if (val == null || val == LuaNil.NIL) {
            tStack[idx] = TYPE_NIL;
            pStack[idx] = 0;
            oStack[idx] = null;
        } else if (val instanceof LuaInteger li) {
            tStack[idx] = TYPE_INT;
            pStack[idx] = li.toLong();
            oStack[idx] = null;
        } else if (val instanceof LuaFloat lf) {
            tStack[idx] = TYPE_FLOAT;
            pStack[idx] = Double.doubleToRawLongBits(lf.toDouble());
            oStack[idx] = null;
        } else if (val instanceof LuaBoolean lb) {
            tStack[idx] = TYPE_BOOLEAN;
            pStack[idx] = lb.toBoolean() ? 1L : 0L;
            oStack[idx] = null;
        } else if (val.isNil()) {
            tStack[idx] = TYPE_NIL;
            pStack[idx] = 0;
            oStack[idx] = null;
        } else {
            tStack[idx] = TYPE_OBJECT;
            pStack[idx] = 0;
            oStack[idx] = val;
        }
    }

    public static void copyReg(long[] pStack, byte[] tStack, LuaValue[] oStack, int dst, int src) {
        pStack[dst] = pStack[src];
        tStack[dst] = tStack[src];
        oStack[dst] = oStack[src];
    }

    public static boolean isTruthy(long[] pStack, byte[] tStack, int idx) {
        byte t = tStack[idx];
        if (t == TYPE_NIL) return false;
        if (t == TYPE_BOOLEAN) return pStack[idx] != 0;
        return true;
    }

    public static LuaValue[] execute(LuaState state, LuaClosure initialClosure, LuaValue[] initialArgs) {
        VmContext ctx = new VmContext();
        LuaCoroutine coInit = LuaCoroutine.running();
        ctx.co = coInit;
        ctx.thread = coInit != null ? coInit : state.getMainThread();
        ctx.callState = coInit != null ? coInit.getCallStackState() : CallStack.currentState();
        ctx.thread.ensureStackCapacity(256);
        ctx.pStack = ctx.thread.getPrimitiveStack();
        ctx.tStack = ctx.thread.getTypeStack();
        ctx.oStack = ctx.thread.getObjectStack();

        ctx.callStack = new CallInfo[256];
        for (int i = 0; i < ctx.callStack.length; i++) ctx.callStack[i] = new CallInfo();
        ctx.callDepth = 0;

        ctx.savedStackTop = ctx.thread.getStackTop();
        ctx.base = ctx.savedStackTop;
        ctx.top = ctx.base;
        ctx.pc = 0;

        ctx.closure = initialClosure;
        ctx.proto = ctx.closure.proto;
        ctx.code = ctx.proto.code;
        ctx.k = ctx.proto.constants;
        ctx.upvals = ctx.closure.upvals;

        ctx.thread.ensureStackCapacity(ctx.base + ctx.proto.maxStackSize + 64);
        ctx.thread.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
        ctx.pStack = ctx.thread.getPrimitiveStack();
        ctx.tStack = ctx.thread.getTypeStack();
        ctx.oStack = ctx.thread.getObjectStack();

        int nArgs = initialArgs != null ? initialArgs.length : 0;
        for (int i = 0; i < ctx.proto.numParams; i++) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + i, (i < nArgs) ? initialArgs[i] : LuaNil.NIL);
        }
        ctx.top = ctx.base + ctx.proto.numParams;

        ctx.varargs = null;
        if (ctx.proto.isVararg && nArgs > ctx.proto.numParams) {
            int nv = nArgs - ctx.proto.numParams;
            ctx.varargs = new LuaValue[nv];
            for (int i = 0; i < nv; i++) {
                ctx.varargs[i] = initialArgs[ctx.proto.numParams + i];
            }
        }

        ctx.initialDepth = CallStack.depth();
        CallStack.setNextVmFrame(ctx.callState, state, ctx.base, ctx.base - 1, ctx.varargs, ctx.pc);
        CallStack.push(initialClosure, initialClosure.getName(), initialClosure.getLineDefined());
        ctx.oldpc = -1;
        ctx.varargPrepRan = false;
        ctx.thrown = null;
        // A previous run of this (cached) proto may have tiered it up. Run
        // the compiled kernel directly instead of dispatching every
        // instruction; a deopt resumes the interpreter at the faulting pc
        // with all committed state intact.
        LuaValue[] jitResult = runTopLevelJit(state, ctx, initialClosure);
        if (jitResult != null) {
            return jitResult;
        }
        // Hoisted: coroutine is constant for the whole execute() invocation
        // (resume continues the same thread/CURRENT; nested coroutines get
        // their own execute()). Saves a ThreadLocal lookup per instruction.
        return runLoop(state, ctx);
    }

    /**
     * Attempts to run the top-level chunk through its compiled kernel.
     * Returns the boxed result array (possibly empty for a void chunk) when
     * handled, or {@code null} when the interpreter must run (cold,
     * ineligible, guarded, or a deopt, which resumes at the faulting pc).
     * A chunk that returns many values deopts inside the kernel, so the
     * multret layout is never reimplemented here.
     */
    private static LuaValue[] runTopLevelJit(LuaState state, VmContext ctx, LuaClosure closure) {
        if (!state.isJitEnabled() || (ctx.co != null && ctx.co.hooksActive) || state.loopGuard != null) {
            return null;
        }
        JitCode jc = closure.proto.jitCode;
        // Impure kernels are allowed: their every call deopts before entering
        // a callee, and a deopt resumes the top-level interpreter at the
        // faulting pc with committed state intact.
        if (jc == null || (!jc.returnsInt && jc.objHandle == null)) {
            return null;
        }
        try {
            if (jc.returnsVoid) {
                // The handle returns a long sentinel; the cast pins the exact
                // MethodType so invokeExact matches (a bare statement call
                // would infer a void type). The value is discarded.
                long ignored = (long) jc.handle.invokeExact(closure, (Object[]) closure.upvals,
                        ctx.pStack, ctx.tStack, ctx.oStack, ctx.base);
                finishFrame(state, ctx);
                return new LuaValue[0];
            }
            if (jc.returnsInt) {
                long r = (long) jc.handle.invokeExact(closure, (Object[]) closure.upvals,
                        ctx.pStack, ctx.tStack, ctx.oStack, ctx.base);
                ctx.pStack[ctx.base] = r;
                ctx.tStack[ctx.base] = TYPE_INT;
                ctx.oStack[ctx.base] = null;
            } else {
                LuaValue r = (LuaValue) jc.objHandle.invokeExact(closure, (Object[]) closure.upvals,
                        ctx.pStack, ctx.tStack, ctx.oStack, ctx.base);
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, r);
            }
            ctx.top = ctx.base + 1;
            LuaValue[] ret = boxTopLevelResults(ctx, ctx.base + 0, 1);
            finishFrame(state, ctx);
            return ret;
        } catch (DeoptSignal d) {
            // A structural deopt (impure-proto call) is expected every run, so
            // it gets a far larger budget; genuine guard failures disarm fast.
            if (d.structural ? ++jc.structuralDeopts > LuaState.JIT_STRUCTURAL_DEOPT_BUDGET
                    : ++jc.deopts > 8) {
                closure.proto.jitCode = null;
                closure.proto.jitDisabled = true;
            }
            ctx.pc = d.pc;
            return null;
        } catch (StackOverflowError soe) {
            closure.proto.jitCode = null;
            closure.proto.jitDisabled = true;
            ctx.pc = 0;
            return null;
        } catch (RuntimeException | Error t) {
            closure.proto.jitCode = null;
            closure.proto.jitDisabled = true;
            throw t;
        } catch (Throwable t) {
            closure.proto.jitCode = null;
            closure.proto.jitDisabled = true;
            return null;
        }
    }

    /**
     * Requests a compile for the current proto after a numeric {@code for}
     * loop whose trip count reached {@link LuaState#JIT_LOOP_THRESHOLD}. This
     * runs once per loop (at {@code FORPREP}), never per iteration, so a hot
     * function called only once still tiers up with zero dispatch-loop
     * overhead. Only called when the count is already known to be large, so
     * short loops never trigger it.
     */
    private static void requestLoopCompile(LuaState state, VmContext ctx) {
        if (!state.isJitEnabled()) {
            return;
        }
        LuaProto proto = ctx.proto;
        if (proto.loopCompileRequested || proto.jitCode != null || proto.jitDisabled) {
            return;
        }
        proto.loopCompileRequested = true;
        if (ctx.thread.hooksActive || state.loopGuard != null) {
            return;
        }
        try {
            JitCompiler.requestCompile(proto);
        } catch (Throwable t) {
            proto.jitDisabled = true;
        }
    }

    private static LuaValue[] runLoop(LuaState state, VmContext ctx) {
        LuaCoroutine co0 = ctx.co;
        // Cooperative runaway-script guard, constant for this execute() call.
        LuaState.LoopGuard guard = state.loopGuard;
        try {
            while (true) {
                int instPc = ctx.pc;
                // Lazy frame sync: mirror pc for on-demand debug readers
                // (getinfo/traceback/getlocal sync the top frame from this).
                // Replaces per-instruction topFrame() + lineInfo lookup.
                if (co0 != null) {
                    co0.vmPcMirror = instPc;
                } else {
                    mirrorSlow(ctx, instPc);
                }

                if (guard != null) {
                    guard.tick();
                }

                int inst = ctx.code[ctx.pc++];
                int op = (inst >>> Instruction.POS_OP) & Instruction.MASK_OP;
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;

                // Fast path: single predictable per-coroutine boolean instead
                // of ThreadLocal + config lookups per instruction. Hooks are
                // per-thread in Lua, so this is also semantically correct.
                if (co0 != null && co0.hooksActive) {
                    pollHooks(co0, ctx, instPc, op);
                }
                ctx.oldpc = instPc;

            switch (op) {
                case OpCode.OP_MOVE -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    copyReg(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.base + b);
                }
                case OpCode.OP_LOADI -> {
                    int sbx = ((inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx) - Instruction.OFFSET_sBx;
                    ctx.pStack[ctx.base + a] = sbx;
                    ctx.tStack[ctx.base + a] = TYPE_INT;
                    ctx.oStack[ctx.base + a] = null;
                }
                case OpCode.OP_LOADF -> {
                    int sbx = ((inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx) - Instruction.OFFSET_sBx;
                    ctx.pStack[ctx.base + a] = Double.doubleToRawLongBits(sbx);
                    ctx.tStack[ctx.base + a] = TYPE_FLOAT;
                    ctx.oStack[ctx.base + a] = null;
                }
                case OpCode.OP_LOADK -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.k[bx]);
                }
                case OpCode.OP_LOADKX -> {
                    int nextInst = ctx.code[ctx.pc++];
                    int ax = (nextInst >>> Instruction.POS_Ax) & Instruction.MASK_Ax;
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.k[ax]);
                }
                case OpCode.OP_LOADFALSE -> {
                    ctx.pStack[ctx.base + a] = 0;
                    ctx.tStack[ctx.base + a] = TYPE_BOOLEAN;
                    ctx.oStack[ctx.base + a] = null;
                }
                case OpCode.OP_LFALSESKIP -> {
                    ctx.pStack[ctx.base + a] = 0;
                    ctx.tStack[ctx.base + a] = TYPE_BOOLEAN;
                    ctx.oStack[ctx.base + a] = null;
                    ctx.pc++;
                }
                case OpCode.OP_LOADTRUE -> {
                    ctx.pStack[ctx.base + a] = 1;
                    ctx.tStack[ctx.base + a] = TYPE_BOOLEAN;
                    ctx.oStack[ctx.base + a] = null;
                }
                case OpCode.OP_LOADNIL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    for (int j = 0; j <= b; j++) {
                        ctx.tStack[ctx.base + a + j] = TYPE_NIL;
                        ctx.pStack[ctx.base + a + j] = 0;
                        ctx.oStack[ctx.base + a + j] = null;
                    }
                }
                case OpCode.OP_CLEANUP -> {
                    // Compiler-generated dead-slot clearing (loop entries).
                    // Same effect as LOADNIL but never fires hooks, so
                    // instruction-counting hooks observe C-like counts.
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    for (int j = 0; j <= b; j++) {
                        ctx.tStack[ctx.base + a + j] = TYPE_NIL;
                        ctx.pStack[ctx.base + a + j] = 0;
                        ctx.oStack[ctx.base + a + j] = null;
                    }
                }
                case OpCode.OP_GETUPVAL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.upvals[b].getValue());
                }
                case OpCode.OP_SETUPVAL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    ctx.upvals[b].setValue(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a));
                }
                case OpCode.OP_GETTABUP -> executeGetTabUpCached(ctx, ctx.pc, a, inst);
                case OpCode.OP_GETTABLE -> executeGetTable(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_GETI -> executeGetI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_GETFIELD -> executeGetField(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETTABUP -> executeSetTabUp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.upvals, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETTABLE -> executeSetTable(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETI -> executeSetI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETFIELD -> executeSetField(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_NEWTABLE -> ctx.pc = executeNewTable(ctx.code, ctx.pc, ctx.tStack, ctx.oStack, ctx.base, a);
                case OpCode.OP_SELF -> executeSelfCached(ctx, state, ctx.pc, a, inst);
                case OpCode.OP_ADD -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = ctx.base + b;
                    int regC = ctx.base + c;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT && ctx.tStack[regC] == TYPE_INT) {
                        ctx.pStack[regA] = ctx.pStack[regB] + ctx.pStack[regC];
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else if (isNumber(ctx.tStack[regB]) && isNumber(ctx.tStack[regC])) {
                        double db = ctx.tStack[regB] == TYPE_INT ? ctx.pStack[regB] : Double.longBitsToDouble(ctx.pStack[regB]);
                        double dc = ctx.tStack[regC] == TYPE_INT ? ctx.pStack[regC] : Double.longBitsToDouble(ctx.pStack[regC]);
                        ctx.pStack[regA] = Double.doubleToRawLongBits(db + dc);
                        ctx.tStack[regA] = TYPE_FLOAT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else {
                        executeSlowAdd(ctx.pStack, ctx.tStack, ctx.oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_ADDI -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int sc = Instruction.getsC(inst);
                    int regB = ctx.base + b;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT) {
                        ctx.pStack[regA] = ctx.pStack[regB] + sc;
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBINI) ctx.pc++;
                    } else if (ctx.tStack[regB] == TYPE_FLOAT) {
                        double db = Double.longBitsToDouble(ctx.pStack[regB]);
                        ctx.pStack[regA] = Double.doubleToRawLongBits(db + sc);
                        ctx.tStack[regA] = TYPE_FLOAT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBINI) ctx.pc++;
                    } else {
                        executeSlowAddI(ctx.pStack, ctx.tStack, ctx.oStack, regA, regB, sc);
                    }
                }
                case OpCode.OP_ADDK -> executeSlowAddK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SUB -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = ctx.base + b;
                    int regC = ctx.base + c;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT && ctx.tStack[regC] == TYPE_INT) {
                        ctx.pStack[regA] = ctx.pStack[regB] - ctx.pStack[regC];
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else if (isNumber(ctx.tStack[regB]) && isNumber(ctx.tStack[regC])) {
                        double db = ctx.tStack[regB] == TYPE_INT ? ctx.pStack[regB] : Double.longBitsToDouble(ctx.pStack[regB]);
                        double dc = ctx.tStack[regC] == TYPE_INT ? ctx.pStack[regC] : Double.longBitsToDouble(ctx.pStack[regC]);
                        ctx.pStack[regA] = Double.doubleToRawLongBits(db - dc);
                        ctx.tStack[regA] = TYPE_FLOAT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else {
                        executeSlowSub(ctx.pStack, ctx.tStack, ctx.oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_SUBK -> executeSlowSubK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_MUL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = ctx.base + b;
                    int regC = ctx.base + c;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT && ctx.tStack[regC] == TYPE_INT) {
                        ctx.pStack[regA] = ctx.pStack[regB] * ctx.pStack[regC];
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else if (isNumber(ctx.tStack[regB]) && isNumber(ctx.tStack[regC])) {
                        double db = ctx.tStack[regB] == TYPE_INT ? ctx.pStack[regB] : Double.longBitsToDouble(ctx.pStack[regB]);
                        double dc = ctx.tStack[regC] == TYPE_INT ? ctx.pStack[regC] : Double.longBitsToDouble(ctx.pStack[regC]);
                        ctx.pStack[regA] = Double.doubleToRawLongBits(db * dc);
                        ctx.tStack[regA] = TYPE_FLOAT;
                        ctx.oStack[regA] = null;
                        if (ctx.pc < ctx.code.length && ((ctx.code[ctx.pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) ctx.pc++;
                    } else {
                        executeSlowMul(ctx.pStack, ctx.tStack, ctx.oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_MULK -> executeSlowMulK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_DIV -> executeSlowDiv(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_DIVK -> executeSlowDivK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_IDIV -> executeSlowIDiv(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_IDIVK -> executeSlowIDivK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_MOD -> executeSlowMod(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_MODK -> executeSlowModK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_POW -> executeSlowPow(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_POWK -> executeSlowPowK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_BAND -> executeSlowBand(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_BANDK -> executeSlowBandK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_BOR -> executeSlowBor(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_BORK -> executeSlowBorK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_BXOR -> executeSlowBxor(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_BXORK -> executeSlowBxorK(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SHL -> executeSlowShl(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_SHLI -> executeSlowShlI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_SHR -> executeSlowShr(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_SHRI -> executeSlowShrI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_UNM -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int regB = ctx.base + b;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT) {
                        ctx.pStack[regA] = -ctx.pStack[regB];
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                    } else if (ctx.tStack[regB] == TYPE_FLOAT) {
                        ctx.pStack[regA] = Double.doubleToRawLongBits(-Double.longBitsToDouble(ctx.pStack[regB]));
                        ctx.tStack[regA] = TYPE_FLOAT;
                        ctx.oStack[regA] = null;
                    } else {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA, getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regB).unm());
                    }
                }
                case OpCode.OP_BNOT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int regB = ctx.base + b;
                    int regA = ctx.base + a;
                    if (ctx.tStack[regB] == TYPE_INT) {
                        ctx.pStack[regA] = ~ctx.pStack[regB];
                        ctx.tStack[regA] = TYPE_INT;
                        ctx.oStack[regA] = null;
                    } else {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA, getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regB).bnot());
                    }
                }
                case OpCode.OP_NOT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    boolean truthy = isTruthy(ctx.pStack, ctx.tStack, ctx.base + b);
                    ctx.pStack[ctx.base + a] = truthy ? 0L : 1L;
                    ctx.tStack[ctx.base + a] = TYPE_BOOLEAN;
                    ctx.oStack[ctx.base + a] = null;
                }
                case OpCode.OP_LEN -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + b).len());
                }
                case OpCode.OP_CONCAT -> executeConcat(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_CLOSE -> doClose(state, ctx, a, false);
                case OpCode.OP_TBC -> doClose(state, ctx, a, true);
                case OpCode.OP_JMP -> {
                    int sj = ((inst >>> Instruction.POS_sJ) & Instruction.MASK_sJ) - Instruction.OFFSET_sJ;
                    ctx.pc += sj;
                }
                case OpCode.OP_EQ -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    int regB = ctx.base + b;
                    byte ta = ctx.tStack[regA];
                    byte tb = ctx.tStack[regB];
                    boolean cond;
                    if (ta == tb && ta == TYPE_INT) {
                        cond = (ctx.pStack[regA] == ctx.pStack[regB]);
                    } else if (ta == tb && ta == TYPE_BOOLEAN) {
                        cond = (ctx.pStack[regA] == ctx.pStack[regB]);
                    } else if (ta == TYPE_NIL && tb == TYPE_NIL) {
                        cond = true;
                    } else {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaEquals(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regB));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_EQK -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a).luaEquals(ctx.k[b]);
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_EQI -> {
                    int sb = Instruction.getsB(inst);
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    boolean cond = (ctx.tStack[regA] == TYPE_INT && ctx.pStack[regA] == sb);
                    if (!cond && ctx.tStack[regA] != TYPE_INT) {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaEquals(LuaInteger.valueOf(sb));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_LT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    int regB = ctx.base + b;
                    byte ta = ctx.tStack[regA];
                    byte tb = ctx.tStack[regB];
                    boolean cond;
                    if (ta == TYPE_INT && tb == TYPE_INT) {
                        cond = ctx.pStack[regA] < ctx.pStack[regB];
                    } else if (ta == TYPE_INT && tb == TYPE_FLOAT) {
                        cond = ltIntFloat(ctx.pStack[regA], Double.longBitsToDouble(ctx.pStack[regB]));
                    } else if (ta == TYPE_FLOAT && tb == TYPE_INT) {
                        cond = ltFloatInt(Double.longBitsToDouble(ctx.pStack[regA]), ctx.pStack[regB]);
                    } else if (ta == TYPE_FLOAT && tb == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) < Double.longBitsToDouble(ctx.pStack[regB]);
                    } else {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaLessThan(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regB));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_LE -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    int regB = ctx.base + b;
                    byte ta = ctx.tStack[regA];
                    byte tb = ctx.tStack[regB];
                    boolean cond;
                    if (ta == TYPE_INT && tb == TYPE_INT) {
                        cond = ctx.pStack[regA] <= ctx.pStack[regB];
                    } else if (ta == TYPE_INT && tb == TYPE_FLOAT) {
                        cond = leIntFloat(ctx.pStack[regA], Double.longBitsToDouble(ctx.pStack[regB]));
                    } else if (ta == TYPE_FLOAT && tb == TYPE_INT) {
                        cond = leFloatInt(Double.longBitsToDouble(ctx.pStack[regA]), ctx.pStack[regB]);
                    } else if (ta == TYPE_FLOAT && tb == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) <= Double.longBitsToDouble(ctx.pStack[regB]);
                    } else {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaLessOrEqual(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regB));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_LTI -> {
                    // PUC lvm.c op_orderI: compare R[A] with the signed
                    // immediate sB against 0 (or, for GTI/GEI, 1) using the
                    // numeric ordering macros. Integer and float lanes are
                    // unboxed; other types fall back to the metamethod via
                    // the slow path.
                    int sb = Instruction.getsB(inst);
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    byte ta = ctx.tStack[regA];
                    boolean cond;
                    if (ta == TYPE_INT) {
                        cond = ctx.pStack[regA] < sb;
                    } else if (ta == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) < sb;
                    } else {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaLessThan(LuaInteger.valueOf(sb));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_LEI -> {
                    int sb = Instruction.getsB(inst);
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    byte ta = ctx.tStack[regA];
                    boolean cond;
                    if (ta == TYPE_INT) {
                        cond = ctx.pStack[regA] <= sb;
                    } else if (ta == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) <= sb;
                    } else {
                        cond = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA).luaLessOrEqual(LuaInteger.valueOf(sb));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_GTI -> {
                    int sb = Instruction.getsB(inst);
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    byte ta = ctx.tStack[regA];
                    boolean cond;
                    if (ta == TYPE_INT) {
                        cond = ctx.pStack[regA] > sb;
                    } else if (ta == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) > sb;
                    } else {
                        // PUC op_orderI(GTI, inv=1, TM_LT): metamethod sees the
                        // immediate on the left, i.e. `im < R[A]`.
                        cond = LuaInteger.valueOf(sb).luaLessThan(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_GEI -> {
                    int sb = Instruction.getsB(inst);
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = ctx.base + a;
                    byte ta = ctx.tStack[regA];
                    boolean cond;
                    if (ta == TYPE_INT) {
                        cond = ctx.pStack[regA] >= sb;
                    } else if (ta == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(ctx.pStack[regA]) >= sb;
                    } else {
                        // PUC op_orderI(GEI, inv=1, TM_LE): `im <= R[A]`.
                        cond = LuaInteger.valueOf(sb).luaLessOrEqual(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, regA));
                    }
                    if (cond != (flagK == 1)) ctx.pc++;
                }
                case OpCode.OP_TEST -> {
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean truthy = isTruthy(ctx.pStack, ctx.tStack, ctx.base + a);
                    if (truthy != (flagK == 1)) ctx.pc++;
                    clearDeadTemp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.proto, ctx.base, a, ctx.pc - 1);
                }
                case OpCode.OP_TESTSET -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean truthy = isTruthy(ctx.pStack, ctx.tStack, ctx.base + b);
                    if (truthy != (flagK == 1)) {
                        ctx.pc++;
                    } else {
                        copyReg(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.base + b);
                    }
                    clearDeadTemp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.proto, ctx.base, b, ctx.pc - 1);
                }
                case OpCode.OP_CALL -> executeCallOp(state, ctx, a, inst);
                case OpCode.OP_TAILCALL -> {
                    LuaValue[] tailResult = doTailCall(state, ctx, a, inst);
                    if (tailResult != null) {
                        return tailResult;
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    if (ctx.thread.getOpenUpvaluesHead() != null) state.closeUpvalues(ctx.thread, ctx.base);
                    if (ctx.thread.getTbcHead() != null) state.closeTbc(ctx.thread, ctx.base, null);
                    LuaValue[] r0;
                    if (ctx.thread.hooksActive) {
                        LuaValue[] retVals0 = new LuaValue[0];
                        stampReturnFrame(ctx, instPc, retVals0, 1, 0);
                        r0 = returnToCaller(state, ctx, retVals0);
                    } else {
                        r0 = returnToCallerRaw(state, ctx, ctx.base + a, 0);
                    }
                    if (r0 != null) {
                        return r0;
                    }
                }
                case OpCode.OP_RETURN1 -> {
                    if (ctx.thread.getOpenUpvaluesHead() != null) state.closeUpvalues(ctx.thread, ctx.base);
                    if (ctx.thread.getTbcHead() != null) state.closeTbc(ctx.thread, ctx.base, null);
                    LuaValue[] r1;
                    if (ctx.thread.hooksActive) {
                        LuaValue ret = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a);
                        LuaValue[] retVals1 = new LuaValue[]{ret};
                        // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                        stampReturnFrame(ctx, instPc, retVals1, a + 1, 1);
                        r1 = returnToCaller(state, ctx, retVals1);
                    } else {
                        r1 = returnToCallerRaw(state, ctx, ctx.base + a, 1);
                    }
                    if (r1 != null) {
                        return r1;
                    }
                }
                case OpCode.OP_RETURN -> {
                    if (ctx.thread.getOpenUpvaluesHead() != null) state.closeUpvalues(ctx.thread, ctx.base);
                    if (ctx.thread.getTbcHead() != null) state.closeTbc(ctx.thread, ctx.base, null);
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int nReturns = b > 0 ? b - 1 : (ctx.top - (ctx.base + a));
                    LuaValue[] rN;
                    if (ctx.thread.hooksActive) {
                        LuaValue[] retVals = new LuaValue[nReturns];
                        for (int i = 0; i < nReturns; i++) {
                            retVals[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + i);
                        }
                        // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                        stampReturnFrame(ctx, instPc, retVals, a + 1, nReturns);
                        rN = returnToCaller(state, ctx, retVals);
                    } else {
                        rN = returnToCallerRaw(state, ctx, ctx.base + a, nReturns);
                    }
                    if (rN != null) {
                        return rN;
                    }
                }
                case OpCode.OP_FORPREP -> doForPrep(state, ctx, a, inst);
                case OpCode.OP_FORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    int regInit = ctx.base + a;
                    if (ctx.tStack[regInit + 1] == TYPE_INT
                            && Long.compareUnsigned(ctx.pStack[regInit + 1], 0) > 0) {
                        ctx.pStack[regInit + 1]--;
                        long next = ctx.pStack[regInit] + ctx.pStack[regInit + 2];
                        ctx.pStack[regInit] = next;
                        ctx.pStack[regInit + 3] = next;
                        ctx.pc -= bx;
                    } else if (ctx.tStack[regInit + 1] == TYPE_FLOAT) {
                        double step = Double.longBitsToDouble(ctx.pStack[regInit + 2]);
                        double limit = Double.longBitsToDouble(ctx.pStack[regInit + 1]);
                        double idx = Double.longBitsToDouble(ctx.pStack[regInit]);
                        idx += step;
                        if (step > 0 ? idx <= limit : limit <= idx) {
                            ctx.pStack[regInit] = Double.doubleToRawLongBits(idx);
                            ctx.pStack[regInit + 3] = Double.doubleToRawLongBits(idx);
                            ctx.pc -= bx;
                        }
                    }
                }
                case OpCode.OP_TFORPREP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    LuaValue val = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 3);
                    state.pushTbc(ctx.base + a + 3, val, "(for state)");
                    ctx.pc += bx;
                }
                case OpCode.OP_TFORCALL -> {
                    // Stamp the generic-for frame (iterator runs arbitrary
                    // code that may read this frame via traceback/getinfo).
                    CallStack.Frame tforCaller = CallStack.topFrame(ctx.callState);
                    if (tforCaller != null) {
                        tforCaller.pc = instPc;
                        if (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) {
                            tforCaller.line = ctx.proto.lineInfo[instPc];
                        }
                    }
                    executeTForCall(ctx, inst, a);
                }
                case OpCode.OP_TFORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    if (ctx.tStack[ctx.base + a + 4] != TYPE_NIL) {
                        copyReg(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 2, ctx.base + a + 4);
                        ctx.pc -= bx;
                    }
                }
                case OpCode.OP_SETLIST -> ctx.pc = executeSetList(ctx.code, ctx.pc, inst, ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, ctx.top);
                case OpCode.OP_CLOSURE -> executeClosure(state, ctx.thread, ctx.proto, ctx.closure, ctx.upvals, ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_VARARG -> doVararg(ctx, a, inst);
                case OpCode.OP_VARARGPREP -> {
                    // Pre-aligned in call setup
                }
                case OpCode.OP_EXTRAARG -> {}
                default -> throw new LuaException("unimplemented opcode: " + OpCode.getOpName(op));
            }
        }
        } catch (org.luava.runtime.eval.LuaUnwindException ue) {
            ctx.thrown = ue;
            throw ue;
        } catch (LuaException le) {
            int faultPc = (ctx.pc > 0) ? ctx.pc - 1 : 0;
            // Lazy-sync top frame so tracebacks/getinfo see the fault site.
            CallStack.Frame faultFrame = CallStack.topFrame(ctx.callState);
            if (faultFrame != null) {
                faultFrame.pc = faultPc;
                if (ctx.proto.lineInfo != null && faultPc < ctx.proto.lineInfo.length) {
                    faultFrame.line = ctx.proto.lineInfo[faultPc];
                }
            }
            if (!le.isDecorated()) {
                decorateFault(le, ctx, faultPc);
            }
            if (CallStack.canHandleError()) {
                LuaValue res = CallStack.runErrorHandler(le.getErrorObject());
                ctx.thrown = le;
                throw new org.luava.runtime.eval.LuaUnwindException(res, le.getErrorObject());
            }
            ctx.thrown = le;
            throw le;
        } catch (Throwable t) {
            ctx.thrown = t;
            throw t;
        } finally {
            finishFrame(state, ctx);
        }
    }

    /**
     * Loop-exit teardown (cold paths + normal returns share it): pop stray
     * frames, close upvalues, map the in-flight error for {@code closeTbc},
     * and restore the stack top.
     */
    private static void finishFrame(LuaState state, VmContext ctx) {
        while (CallStack.depth() > ctx.initialDepth) {
            CallStack.pop(ctx.callState, ctx.co);
        }
        state.closeUpvalues(ctx.thread, ctx.savedStackTop);
        LuaValue errVal = null;
        if (ctx.thrown != null) {
            if (ctx.thrown instanceof org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal) {
                errVal = null;
            } else if (ctx.thrown instanceof org.luava.runtime.eval.LuaUnwindException ue) {
                errVal = ue.getOriginalError();
            } else if (ctx.thrown instanceof LuaException le && le.getErrorObject() != null) {
                errVal = le.getErrorObject();
            } else {
                String msg = ctx.thrown.getMessage() != null ? ctx.thrown.getMessage() : ctx.thrown.toString();
                errVal = LuaString.valueOf(msg);
            }
        }
        state.closeTbc(ctx.savedStackTop, errVal);
        ctx.thread.setStackTop(ctx.savedStackTop);
    }

    /**
     * Hook polling, extracted from the dispatch loop (runs only while a hook
     * is armed, so zero cost otherwise). Mutates {@code ctx.oldpc} /
     * {@code ctx.varargPrepRan} and stamps the top frame before firing, so
     * hook observers ({@code getlocal}, traceback) see the firing site.
     */
    private static void pollHooks(LuaCoroutine co, VmContext ctx, int instPc, int op) {
        // Hooks armed: compute line/frame lazily (once per
        // instruction only while a hook is actually installed).
        int curLine = (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[instPc] : -1;
        CallStack.Frame curFrame = CallStack.topFrame(ctx.callState);
        LuaCoroutine.HookConfig hc = co.getHookConfig();
        if (!hc.hook.isNil() && !hc.inHook) {
            // OP_CLEANUP is compiler-internal (dead-slot clearing):
            // it must not fire count hooks (instruction counts stay
            // C-like), but it fires line hooks normally (it carries
            // the loop's line, so first-event sequencing is intact).
            boolean isCleanup = (op == OpCode.OP_CLEANUP);
            if (hc.count > 0 && !isCleanup) {
                // Stamp the frame: hook observers (getlocal/traceback) read it.
                if (curFrame != null) {
                    curFrame.pc = instPc;
                    curFrame.line = curLine;
                }
                co.fireCountHook();
            }
            if (hc.hookLine) {
                // Lua 5.4 semantics (lvm.c: OP_VARARGPREP & ldebug.c: luaG_traceexec):
                // 1. OP_VARARGPREP is internal setup and never triggers the line hook.
                // 2. Setting oldpc to 1 in Lua's OP_VARARGPREP guarantees next opcode triggers line hook.
                // 3. Subsequent instructions trigger the hook on backward jumps (loops: instPc <= oldpc)
                //    or on entering a new line (curLine != oldLine).
                if (op != OpCode.OP_VARARGPREP) {
                    int oldLine = (ctx.proto.lineInfo != null && ctx.oldpc >= 0 && ctx.oldpc < ctx.proto.lineInfo.length)
                            ? ctx.proto.lineInfo[ctx.oldpc] : -1;
                    if (ctx.varargPrepRan || ctx.oldpc < 0 || instPc <= ctx.oldpc || curLine != oldLine) {
                        if (curLine > 0) {
                            // Stamp the frame: hook observers (getlocal/traceback) read it.
                            if (curFrame != null) {
                                curFrame.pc = instPc;
                                curFrame.line = curLine;
                            }
                            co.fireLineHookDirect(curLine, curFrame);
                        }
                        ctx.varargPrepRan = false;
                    }
                } else {
                    ctx.varargPrepRan = true;
                }
            }
        }
    }

    /**
     * Error-message decoration for the {@code catch} path (cold).
     * Attaches the bytecode descriptor and rewrites the message to the
     * {@code source:line: msg} form.
     */
    private static void decorateFault(LuaException le, VmContext ctx, int faultPc) {
        attachBytecodeDesc(le, ctx.proto, faultPc, ctx.pStack, ctx.tStack, ctx.oStack, ctx.base);
        int curLine = (ctx.proto.lineInfo != null && ctx.proto.lineInfo.length > 0 && faultPc < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[faultPc] : -1;
        String msg = le.getMessage();
        if (msg != null) {
            String source = ctx.proto.source;
            if (source == null || source.isEmpty() || "=?".equals(source) || curLine <= 0) {
                source = (source == null || "=?".equals(source)) ? "=?" : source;
                if ("=?".equals(source)) curLine = -1;
            }
            String formattedSource = org.luava.frontend.parser.ParseException.formatChunkName(source);
            le.setMessage(formattedSource + ":" + curLine + ": " + msg);
        }
        le.setDecorated(true);
    }

    /**
     * Shared {@code __call} / userdata resolution for {@code OP_CALL} and
     * {@code OP_TAILCALL} (cold unwrap path). Returns the callable and
     * publishes the adjusted argument count via {@code ctx.scratch0}.
     */
    private static LuaFunction resolveCallable(LuaState state, VmContext ctx, int funcIdx, int nArgs, int a) {
        LuaValue func = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx);
        while (!(func instanceof LuaFunction)) {
            LuaTable mt = func.getMetatable();
            LuaValue tm = mt != null ? mt.rawget(LuaValue.Meta.CALL) : null;
            if (tm != null && !tm.isNil()) {
                ctx.thread.ensureStackCapacity(funcIdx + nArgs + 3);
                ctx.pStack = ctx.thread.getPrimitiveStack();
                ctx.tStack = ctx.thread.getTypeStack();
                ctx.oStack = ctx.thread.getObjectStack();
                System.arraycopy(ctx.pStack, funcIdx, ctx.pStack, funcIdx + 1, nArgs + 1);
                System.arraycopy(ctx.tStack, funcIdx, ctx.tStack, funcIdx + 1, nArgs + 1);
                System.arraycopy(ctx.oStack, funcIdx, ctx.oStack, funcIdx + 1, nArgs + 1);
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, tm);
                nArgs++;
                func = tm;
            } else if (func instanceof LuaUserdata) {
                // Java userdata (incl. SAM functional interfaces):
                // adapt to the external-call path instead of throwing.
                func = new UserdataCallFunction((LuaUserdata) func);
            } else {
                String[] info = getobjname(ctx.proto, ctx.pc - 1, a);
                String extra = (info != null && info[0] != null) ? " (" + info[1] + " '" + info[0] + "')" : "";
                throw new LuaException("attempt to call a " + func.typeName() + " value" + extra);
            }
        }
        ctx.scratch0 = nArgs;
        return (LuaFunction) func;
    }

    /**
     * {@code OP_CALL} handler. Outlined from the dispatch loop so the loop
     * itself keeps C2 inline budget and this method gets a fresh one: the
     * per-call helpers ({@code resolveCallable}, {@code pushVmFrame}, ...)
     * are all under {@code FreqInlineSize} and can fuse into this unit,
     * while inside the 7 KB {@code runLoop} they starve (even 22-byte
     * callees are rejected there). Mutates {@code ctx} directly; void
     * because a Lua-to-Lua call never terminates the loop.
     *
     * <p>Note: {@code OP_RETURN} is deliberately <em>not</em> outlined the
     * same way: a 296-byte return helper gets re-inlined into
     * {@code runLoop} on loop-heavy compiles and wrecks hot-loop code
     * quality (arith_loop -40%, confirmed via {@code CompileCommand
     * dontinline} recovery), so the return bodies stay inline.
     */
    /**
     * Hybrid JIT fast lane (plan.md). Runs a compiled kernel
     * directly on the caller's register window instead of pushing an
     * interpreter frame.
     *
     * <p>Returns 1 when the call was fully handled (single int result at
     * {@code base+0} copied to the caller per {@code nResults}), 0 when the
     * JIT was not attempted (cold, unsuitable, entry guard), and 2 when the
     * compiled code deopted mid-flight. On 2, {@code ctx.jitResumePc} holds
     * the faulting pc and the caller resumes the interpreter there with all
     * committed state intact (no restart, so table/upvalue writes are safe).
     * Nested JIT deopts are converted to the outer call pc by generated
     * try/catch, and callees re-invoked from scratch are always pure.
     */
    private static int tryJitCall(LuaState state, VmContext ctx, LuaClosure child,
            int funcIdx, int nActualArgs, int nResults) {
        if (!state.isJitEnabled()) {
            return 0;
        }
        if (ctx.thread.hooksActive || state.loopGuard != null) {
            return 0;
        }
        LuaProto proto = child.proto;
        if (proto.mayYield) {
            return 0;
        }
        JitCode jc = proto.jitCode;
        if (jc == null) {
            if (proto.jitDisabled) {
                return 0;
            }
            int hot = proto.hotCount + 1;
            proto.hotCount = hot;
            if (hot < LuaState.JIT_HOT_THRESHOLD) {
                return 0;
            }
            // Tier-up is a request: background thread compiles (or
            // synchronously under luava.jit.sync); this call stays interpreted.
            try {
                org.luava.runtime.jit.JitCompiler.requestCompile(proto);
            } catch (Throwable t) {
                proto.jitDisabled = true;
            }
            return 0;
        }
        int base = funcIdx + 1;
        if (nActualArgs < proto.numParams) {
            return 0;
        }
        try {
            ctx.thread.ensureStackCapacity(base + proto.maxStackSize + 64);
            long[] pStack = ctx.thread.getPrimitiveStack();
            byte[] tStack = ctx.thread.getTypeStack();
            LuaValue[] oStack = ctx.thread.getObjectStack();
            if (!jc.returnsInt) {
                // Object-returning kernel (factories): box-free registers in,
                // one boxed value out.
                LuaValue r = (LuaValue) jc.objHandle.invokeExact(child, (Object[]) child.upvals, pStack,
                        tStack, oStack, base);
                ctx.pStack = pStack;
                ctx.tStack = tStack;
                ctx.oStack = oStack;
                ctx.top = base + proto.numParams;
                closeOnJitReturn(state, ctx, base);
                if (nResults == 1) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, r);
                } else if (nResults == 0) {
                    // Discarded.
                } else if (nResults < 0) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, r);
                    ctx.top = funcIdx + 1;
                } else {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, r);
                    for (int i = 1; i < nResults; i++) {
                        ctx.pStack[funcIdx + i] = 0;
                        ctx.tStack[funcIdx + i] = TYPE_NIL;
                        ctx.oStack[funcIdx + i] = null;
                    }
                }
                return 1;
            }
            if (jc.returnsVoid) {
                // Void kernel: no result. Materialize zero values, or nil-fill
                // however many the caller expected.
                long ignored = (long) jc.handle.invokeExact(child, (Object[]) child.upvals,
                        pStack, tStack, oStack, base);
                ctx.pStack = pStack;
                ctx.tStack = tStack;
                ctx.oStack = oStack;
                ctx.top = base + proto.numParams;
                closeOnJitReturn(state, ctx, base);
                if (nResults < 0) {
                    ctx.top = funcIdx;
                } else if (nResults > 1) {
                    for (int i = 0; i < nResults; i++) {
                        ctx.pStack[funcIdx + i] = 0;
                        ctx.tStack[funcIdx + i] = TYPE_NIL;
                        ctx.oStack[funcIdx + i] = null;
                    }
                }
                return 1;
            }
            // The compiled integer kernel yields exactly one integer result;
            // mirror the returnToCallerRaw layout framelessly.
            long r = (long) jc.handle.invokeExact(child, (Object[]) child.upvals, pStack, tStack, oStack, base);
            ctx.pStack = pStack;
            ctx.tStack = tStack;
            ctx.oStack = oStack;
            // Mirror the interpreter: after a fixed call its top is the
            // callee flavor (pure callees never move it).
            ctx.top = base + proto.numParams;
            closeOnJitReturn(state, ctx, base);
            if (nResults == 1) {
                ctx.pStack[funcIdx] = r;
                ctx.tStack[funcIdx] = TYPE_INT;
                ctx.oStack[funcIdx] = null;
            } else if (nResults == 0) {
                // Discarded.
            } else if (nResults < 0) {
                ctx.pStack[funcIdx] = r;
                ctx.tStack[funcIdx] = TYPE_INT;
                ctx.oStack[funcIdx] = null;
                ctx.top = funcIdx + 1;
            } else {
                ctx.pStack[funcIdx] = r;
                ctx.tStack[funcIdx] = TYPE_INT;
                ctx.oStack[funcIdx] = null;
                for (int i = 1; i < nResults; i++) {
                    ctx.pStack[funcIdx + i] = 0;
                    ctx.tStack[funcIdx + i] = TYPE_NIL;
                    ctx.oStack[funcIdx + i] = null;
                }
            }
            return 1;
        } catch (DeoptSignal d) {
            if (d.structural ? ++jc.structuralDeopts > LuaState.JIT_STRUCTURAL_DEOPT_BUDGET
                    : ++jc.deopts > 8) {
                proto.jitCode = null;
                proto.jitDisabled = true;
            }
            if (jitDebug()) {
                String extra = "";
                try {
                    org.luava.runtime.eval.Upvalue uv = child.upvals.length > 0 ? child.upvals[0] : null;
                    extra = " up0tag=" + (uv == null ? "n/a"
                            : (uv.isOpenOnStack() ? "open" : Byte.toString(uv.getTypeTag())));
                } catch (Throwable ignore) {
                }
                System.err.println("[jit] deopt pc=" + d.pc + " proto=" + proto.name + extra);
            }
            ctx.jitResumePc = d.pc;
            return 2;
        } catch (StackOverflowError soe) {
            // Deep JIT recursion: fall back so the interpreter raises the
            // proper Lua stack-overflow error.
            proto.jitCode = null;
            proto.jitDisabled = true;
            ctx.jitResumePc = 0;
            return 2;
        } catch (Throwable t) {
            if (jitDebug()) {
                System.err.println("[jit] FAILED proto=" + proto.name + " : " + t);
            }
            proto.jitCode = null;
            proto.jitDisabled = true;
            if (t instanceof LuaException || t instanceof RuntimeException || t instanceof Error) {
                throw sneakyThrow(t);
            }
            return 0;
        }
    }

    /** Rethrows any throwable without a checked-exception declaration. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Mirrors the interpreter's return path: closes open upvalues at or
     * above the callee base. Required because a caller's captured locals
     * may alias the callee register window; without this, slot reuse after
     * return would corrupt them. Runs once per top-level JIT call (inner
     * recursion never escapes to the interpreter mid-flight).
     */
    private static void closeOnJitReturn(LuaState state, VmContext ctx, int base) {
        if (ctx.thread.getOpenUpvaluesHead() != null) {
            state.closeUpvalues(ctx.thread, base);
        }
    }

    /**
     * JIT path for {@code OP_TAILCALL}. Returns 1 when the compiled kernel
     * produced the single result (now in {@code funcIdx}), 0 when the caller
     * must run the generic tail path, or 2 after a deopt with the argument
     * registers restored. The kernel is invoked on the callee register
     * window, so arguments must be snapshotted first: a deopt mid-kernel may
     * have overwritten them. Snapshotting only happens once the proto has a
     * compiled kernel (steady state), so cold calls pay nothing.
     */
    private static int tryJitTailCall(LuaState state, VmContext ctx, LuaClosure child,
            int funcIdx, int nActualArgs) {
        if (!state.isJitEnabled()) {
            return 0;
        }
        if (ctx.thread.hooksActive || state.loopGuard != null) {
            return 0;
        }
        LuaProto proto = child.proto;
        if (proto.mayYield) {
            return 0;
        }
        JitCode jc = proto.jitCode;
        if (jc == null) {
            if (proto.jitDisabled) {
                return 0;
            }
            int hot = proto.hotCount + 1;
            proto.hotCount = hot;
            if (hot < LuaState.JIT_HOT_THRESHOLD) {
                return 0;
            }
            try {
                org.luava.runtime.jit.JitCompiler.requestCompile(proto);
            } catch (Throwable t) {
                proto.jitDisabled = true;
            }
            return 0;
        }
        if (nActualArgs < proto.numParams) {
            return 0;
        }
        // Snapshot the caller-provided arguments (as LuaValue, exact for any
        // type) so a deopt can restore them before the generic tail path.
        LuaValue[] snapshot = new LuaValue[nActualArgs];
        for (int i = 0; i < nActualArgs; i++) {
            snapshot[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1 + i);
        }
        int r = tryJitCall(state, ctx, child, funcIdx, nActualArgs, 1);
        if (r == 1) {
            return 1;
        }
        if (r == 2) {
            // Deopt left the register window partially written; restore the
            // original arguments so the interpreter tail call is correct.
            for (int i = 0; i < nActualArgs; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1 + i, snapshot[i]);
            }
            return 2;
        }
        return 0;
    }

    private static boolean jitDebugLogged;
    private static boolean jitDebug() {
        if (!jitDebugLogged) {
            jitDebugLogged = true;
            if (Boolean.getBoolean("luava.jit.debug")) {
                System.err.println("[jit] ENABLE_JIT=" + LuaState.ENABLE_JIT);
            }
        }
        return Boolean.getBoolean("luava.jit.debug");
    }

    private static void executeCallOp(LuaState state, VmContext ctx, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;

        int funcIdx = ctx.base + a;
        int nResults = c - 1;
        // Fast lane: the register already holds a LuaFunction (the dominant
        // case: local/upvalue function calls). resolveCallable's boxing +
        // metamethod walk is pure overhead then; it only matters for tables
        // with __call or userdata. Same argument-count semantics.
        int nArgs;
        LuaFunction func;
        if (ctx.tStack[funcIdx] == TYPE_OBJECT && ctx.oStack[funcIdx] instanceof LuaFunction direct) {
            func = direct;
            nArgs = b > 0 ? b - 1 : (ctx.top - (funcIdx + 1));
        } else {
            func = resolveCallable(state, ctx, funcIdx, b > 0 ? b - 1 : (ctx.top - (funcIdx + 1)), a);
            nArgs = ctx.scratch0;
        }
        int nActualArgs = nArgs;

        // Well-known-builtin inline: tostring(x) with exactly one argument is
        // one of the hottest external calls (string building, concat loops).
        // The frameless fast path skips args boxing, debug-frame push/pop and
        // name resolution; it bails to the generic path whenever a
        // __tostring metamethod, hook, or exotic shape could observe a
        // difference.
        if ((ctx.co == null || !ctx.co.hooksActive)) {
            int inlined = -1;
            if (func == org.luava.runtime.standard.BaseLib.TOSTRING && nActualArgs == 1) {
                inlined = inlineTostring1(state, ctx, funcIdx, nResults);
            } else if (func == org.luava.runtime.standard.StringLib.GMATCH
                    && (nActualArgs == 2 || nActualArgs == 3)) {
                inlined = inlineGmatch(ctx, funcIdx, nActualArgs, nResults);
            } else if (inlineSqrtRaw(ctx, func, funcIdx, nActualArgs, funcIdx)) {
                for (int i = 1; i < nResults; i++) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + i, LuaNil.NIL);
                }
                inlined = funcIdx + 1;
            } else {
                LuaValue inlineRes = inlineBuiltinResult(ctx, func, funcIdx, nActualArgs);
                if (inlineRes != null) {
                    if (nResults != 0) {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, inlineRes);
                        for (int i = 1; i < nResults; i++) {
                            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + i, LuaNil.NIL);
                        }
                    }
                    inlined = funcIdx + 1;
                }
            }
            if (inlined >= 0) {
                if (nResults < 0) {
                    ctx.top = inlined;
                }
                ctx.pStack = ctx.thread.getPrimitiveStack();
                ctx.tStack = ctx.thread.getTypeStack();
                ctx.oStack = ctx.thread.getObjectStack();
                return;
            }
        }

        if (func instanceof LuaClosure childClosure) {
            // Trivial-factory inline (make_counter shape): frameless closure
            // construction, checked before the JIT tier (factories never JIT
            // anyway — OP_CLOSURE is outside the subset).
            if (callFactory(state, ctx, childClosure, funcIdx, nActualArgs, nResults)) {
                ctx.pStack = ctx.thread.getPrimitiveStack();
                ctx.tStack = ctx.thread.getTypeStack();
                ctx.oStack = ctx.thread.getObjectStack();
                return;
            }
            // Hybrid JIT fast lane (plan.md): a compiled kernel
            // runs framelessly on the register window. 1 = handled; 2 = run
            // the interpreter below but resume at the deopt pc instead of 0;
            // 0 = cold/unsuitable, run from scratch.
            int jit = tryJitCall(state, ctx, childClosure, funcIdx, nActualArgs, nResults);
            if (jit == 1) {
                return;
            }
            CallStack.Frame callerFrame = CallStack.topFrame(ctx.callState);
            if (callerFrame != null) {
                callerFrame.pc = ctx.pc - 1;
                if (ctx.proto.lineInfo != null && ctx.pc - 1 >= 0 && ctx.pc - 1 < ctx.proto.lineInfo.length) {
                    callerFrame.line = ctx.proto.lineInfo[ctx.pc - 1];
                }
            }
            if (ctx.callDepth >= ctx.callStack.length) {
                ctx.callStack = expandCallStack(ctx.callStack);
            }
            CallInfo ci = ctx.callStack[ctx.callDepth++];
            ci.init(ctx.closure, funcIdx, ctx.base, ctx.top, ctx.pc, nResults);
            ci.varargs = ctx.varargs;
            ci.oldpc = ctx.oldpc;
            ci.varargPrepRan = ctx.varargPrepRan;
            ctx.oldpc = -1;
            ctx.varargPrepRan = false;
            CallStack.CallStackState csState = ctx.callState;
            String callName = csState.nextName;
            String callNamewhat = csState.nextNamewhat;
            boolean isMeta = csState.nextMetamethod;
            boolean isMethod = csState.nextMethod;
            csState.nextName = null;
            csState.nextNamewhat = null;
            csState.nextMetamethod = false;
            csState.nextMethod = false;
            if (callName == null) {
                String[] info = callName(ctx, ctx.proto, ctx.pc - 1, a);
                if (info != null) {
                    callName = info[0];
                    callNamewhat = info[1];
                    if ("method".equals(callNamewhat)) isMethod = true;
                } else {
                    callName = childClosure.getName();
                    callNamewhat = "";
                }
            }

            ctx.base = funcIdx + 1;
            ctx.closure = childClosure;
            ctx.proto = ctx.closure.proto;
            ctx.code = ctx.proto.code;
            ctx.k = ctx.proto.constants;
            ctx.upvals = ctx.closure.upvals;
            ctx.pc = 0;

            ctx.thread.ensureStackCapacity(ctx.base + ctx.proto.maxStackSize + 64);
            ctx.thread.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
            ctx.pStack = ctx.thread.getPrimitiveStack();
            ctx.tStack = ctx.thread.getTypeStack();
            ctx.oStack = ctx.thread.getObjectStack();

            if (ctx.proto.isVararg && nActualArgs > ctx.proto.numParams) {
                int nv = nActualArgs - ctx.proto.numParams;
                ctx.varargs = new LuaValue[nv];
                for (int i = 0; i < nv; i++) {
                    ctx.varargs[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + ctx.proto.numParams + i);
                }
            } else {
                ctx.varargs = null;
            }

            for (int i = nActualArgs; i < ctx.proto.numParams; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + i, LuaNil.NIL);
            }
            ctx.top = ctx.base + ctx.proto.numParams;

            // Lua 5.4 semantics: call hook runs after stack frame and arguments are established
            CallStack.pushVmFrame(childClosure,
                    callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(),
                    isMethod, isMeta,
                    1, childClosure.proto.numParams,
                    state, ctx.base, funcIdx, ctx.varargs,
                    ctx.callState, ctx.co);
            if (jit == 2) {
                // JIT deopt: registers already hold the committed prefix;
                // continue the interpreter at the faulting instruction.
                ctx.pc = ctx.jitResumePc;
            }
        } else if (func instanceof LuaFunction fn) {
            int callLine = (ctx.proto.lineInfo != null && ctx.pc - 1 < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[ctx.pc - 1] : -1;
            int newTop = executeExternalCall(state, ctx, ctx.proto, ctx.pc, ctx.base, fn, funcIdx, nActualArgs, nResults, callLine);
            if (nResults < 0) {
                ctx.top = newTop;
            }
            ctx.pStack = ctx.thread.getPrimitiveStack();
            ctx.tStack = ctx.thread.getTypeStack();
            ctx.oStack = ctx.thread.getObjectStack();
        }
    }

    /**
     * {@code OP_TAILCALL} handler (largest single case, ~1.5 KB).
     * Mutates {@code ctx} directly; a non-null return value must be
     * returned from the dispatch loop immediately.
     */
    private static LuaValue[] doTailCall(LuaState state, VmContext ctx, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int funcIdx = ctx.base + a;
        // Fast lane mirroring OP_CALL: the register already holds a
        // LuaFunction (the dominant case), so resolveCallable's boxing +
        // metamethod walk is pure overhead.
        LuaFunction func;
        int nActualArgs;
        if (ctx.tStack[funcIdx] == TYPE_OBJECT && ctx.oStack[funcIdx] instanceof LuaFunction direct) {
            func = direct;
            nActualArgs = b > 0 ? b - 1 : (ctx.top - (funcIdx + 1));
        } else {
            func = resolveCallable(state, ctx, funcIdx, b > 0 ? b - 1 : (ctx.top - (funcIdx + 1)), a);
            nActualArgs = ctx.scratch0;
        }

        if (func instanceof LuaClosure childClosure) {
            // Frameless factory tailcall (e.g. `return Vec.new(x, y)` in a
            // constructor): build the closure and unwind, exactly like the
            // OP_CALL inline. Hooks/loop guards fall through unchanged.
            if (ctx.co == null || !ctx.co.hooksActive) {
                LuaClosure built = factoryBuild(state, ctx, childClosure, funcIdx, nActualArgs);
                if (built != null) {
                    // The tail call replaces this frame, so its locals (and any
                    // open upvalues into them) go away exactly as in the
                    // generic tail path.
                    state.closeUpvalues(ctx.thread, ctx.base);
                    return tailReturnInline(state, ctx, built);
                }
                // JIT tail call. Tier-up counting used to live only in
                // executeCallOp (OP_CALL), so a hot function reached via
                // `return f(...)` (TAILCALL) never compiled — the common
                // shape for a script's hot entry function. Count here too,
                // and run the compiled kernel framelessly when available.
                int jit = tryJitTailCall(state, ctx, childClosure, funcIdx, nActualArgs);
                if (jit == 1) {
                    LuaValue res = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx);
                    // The tail call replaces this frame, so its locals (and
                    // any open upvalues into them) go away exactly as in the
                    // generic tail path.
                    state.closeUpvalues(ctx.thread, ctx.base);
                    return tailReturnInline(state, ctx, res);
                }
                if (jit == 2) {
                    // Deopt: run the generic tail path from a clean state.
                    ctx.pStack = ctx.thread.getPrimitiveStack();
                    ctx.tStack = ctx.thread.getTypeStack();
                    ctx.oStack = ctx.thread.getObjectStack();
                }
            }
            state.closeUpvalues(ctx.thread, ctx.base);
            CallStack.CallStackState csState = ctx.callState;
            String callName = csState.nextName;
            String callNamewhat = csState.nextNamewhat;
            boolean isMeta = csState.nextMetamethod;
            boolean isMethod = csState.nextMethod;
            csState.nextName = null;
            csState.nextNamewhat = null;
            csState.nextMetamethod = false;
            csState.nextMethod = false;
            if (callName == null) {
                String[] info = callName(ctx, ctx.proto, ctx.pc - 1, a);
                if (info != null) {
                    callName = info[0];
                    callNamewhat = info[1];
                    if ("method".equals(callNamewhat)) isMethod = true;
                } else {
                    callName = childClosure.getName();
                    callNamewhat = "";
                }
            }
            int oldTop = ctx.top;
            System.arraycopy(ctx.pStack, funcIdx + 1, ctx.pStack, ctx.base, nActualArgs);
            System.arraycopy(ctx.tStack, funcIdx + 1, ctx.tStack, ctx.base, nActualArgs);
            System.arraycopy(ctx.oStack, funcIdx + 1, ctx.oStack, ctx.base, nActualArgs);
            if (oldTop > ctx.base + nActualArgs) {
                java.util.Arrays.fill(ctx.oStack, ctx.base + nActualArgs, oldTop, null);
            }
            ctx.closure = childClosure;
            ctx.proto = ctx.closure.proto;
            ctx.code = ctx.proto.code;
            ctx.k = ctx.proto.constants;
            ctx.upvals = ctx.closure.upvals;
            ctx.pc = 0;
            ctx.oldpc = -1;
            ctx.varargPrepRan = false;

            if (ctx.proto.isVararg && nActualArgs > ctx.proto.numParams) {
                int nv = nActualArgs - ctx.proto.numParams;
                ctx.varargs = new LuaValue[nv];
                for (int i = 0; i < nv; i++) {
                    ctx.varargs[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + ctx.proto.numParams + i);
                }
            } else {
                ctx.varargs = null;
            }

            for (int i = nActualArgs; i < ctx.proto.numParams; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + i, LuaNil.NIL);
            }
            ctx.top = ctx.base + ctx.proto.numParams;

            // Lua 5.4 semantics: tail call replaces frame without firing return hook, fires tailcall hook
            CallStack.setNextTransfer(ctx.callState, 1, childClosure.proto.numParams, null);
            CallStack.setNextVmFrame(ctx.callState, state, ctx.base, ctx.base - 1, ctx.varargs, 0);
            CallStack.replaceTailCall(childClosure, callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(), isMethod, isMeta, ctx.callState, ctx.co);
        } else if (func instanceof LuaFunction fn) {
            state.closeUpvalues(ctx.thread, ctx.base);
            state.closeTbc(ctx.thread, ctx.base, null);

            int callLine = (ctx.proto.lineInfo != null && ctx.pc - 1 < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[ctx.pc - 1] : -1;
            if (callLine > 0) CallStack.setLine(ctx.callState, ctx.co, callLine);
            CallStack.Frame callerFrameExt = CallStack.topFrame(ctx.callState);
            if (callerFrameExt != null) {
                callerFrameExt.pc = ctx.pc - 1;
                if (callLine > 0) callerFrameExt.line = callLine;
            }
            CallStack.CallStackState csState = ctx.callState;
            String resolvedName = csState.nextName;
            String namewhat = csState.nextNamewhat;
            boolean isMeta = csState.nextMetamethod;
            boolean isMethod = csState.nextMethod;
            csState.nextName = null;
            csState.nextNamewhat = null;
            csState.nextMetamethod = false;
            csState.nextMethod = false;
            if (resolvedName == null) {
                String[] info = callName(ctx, ctx.proto, ctx.pc - 1, a);
                if (info != null) {
                    resolvedName = info[0];
                    namewhat = info[1];
                    if ("method".equals(namewhat)) isMethod = true;
                } else {
                    resolvedName = fn.getName();
                    namewhat = "";
                }
            }
            // Frameless tailcall to a single-result builtin (setmetatable in
            // constructors, math.sqrt in numeric tails): skip args boxing,
            // frame replace and invoke; the shared return plumbing below
            // handles the single value identically.
            if (ctx.co == null || !ctx.co.hooksActive) {
                // Raw sqrt tailcall first: single float result straight into
                // the caller's register, no LuaFloat allocation (500k+ per
                // numeric OOP loop). Needs callDepth > 0 (top-level still
                // boxes via the helper below — cold path).
                if (fn == org.luava.runtime.standard.MathLib.SQRT && nActualArgs == 1
                        && ctx.callDepth > 0
                        && (ctx.tStack[funcIdx + 1] == TYPE_INT
                            || ctx.tStack[funcIdx + 1] == TYPE_FLOAT)) {
                    // Mirror the generic path's frame bookkeeping: it replaces
                    // the callee frame then pops it (net: callee frame gone).
                    // Skipping the pop leaks one debug frame per tailcall and
                    // overflows the stack on loops. Hooks are gated off above.
                    CallStack.pop(ctx.callState, ctx.co);
                    CallInfo ci = ctx.callStack[--ctx.callDepth];
                    int callerFunc = ci.funcIndex;
                    ctx.base = ci.baseIndex;
                    ctx.closure = ci.closure;
                    ctx.proto = ctx.closure.proto;
                    ctx.code = ctx.proto.code;
                    ctx.k = ctx.proto.constants;
                    ctx.upvals = ctx.closure.upvals;
                    ctx.pc = ci.savedPc;
                    ctx.varargs = ci.varargs;
                    ctx.oldpc = ci.oldpc;
                    ctx.varargPrepRan = ci.varargPrepRan;
                    ctx.thread.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : 1) + 32);
                    ctx.pStack = ctx.thread.getPrimitiveStack();
                    ctx.tStack = ctx.thread.getTypeStack();
                    ctx.oStack = ctx.thread.getObjectStack();
                    if (ci.expectedResults != 0) {
                        inlineSqrtRaw(ctx, fn, funcIdx, nActualArgs, callerFunc);
                        if (ci.expectedResults > 1) {
                            for (int i = 1; i < ci.expectedResults; i++) {
                                ctx.pStack[callerFunc + i] = 0;
                                ctx.tStack[callerFunc + i] = TYPE_NIL;
                                ctx.oStack[callerFunc + i] = null;
                            }
                        } else if (ci.expectedResults < 0) {
                            ctx.top = callerFunc + 1;
                        }
                    }
                    return null;
                }
                LuaValue inlineRes = inlineBuiltinResult(ctx, fn, funcIdx, nActualArgs);
                if (inlineRes != null) {
                    // (Same frame-bookkeeping note as above.)
                    CallStack.pop(ctx.callState, ctx.co);
                    LuaValue[] inlineRetVals = new LuaValue[]{inlineRes};
                    int nReturns = 1;
                    if (ctx.callDepth > 0) {
                        CallInfo ci = ctx.callStack[--ctx.callDepth];
                        int callerFunc = ci.funcIndex;
                        ctx.base = ci.baseIndex;
                        ctx.closure = ci.closure;
                        ctx.proto = ctx.closure.proto;
                        ctx.code = ctx.proto.code;
                        ctx.k = ctx.proto.constants;
                        ctx.upvals = ctx.closure.upvals;
                        ctx.pc = ci.savedPc;
                        ctx.varargs = ci.varargs;
                        ctx.oldpc = ci.oldpc;
                        ctx.varargPrepRan = ci.varargPrepRan;
                        ctx.thread.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : nReturns) + 32);
                        ctx.pStack = ctx.thread.getPrimitiveStack();
                        ctx.tStack = ctx.thread.getTypeStack();
                        ctx.oStack = ctx.thread.getObjectStack();
                        if (ci.expectedResults > 0) {
                            for (int i = 0; i < ci.expectedResults; i++) {
                                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, (i < nReturns) ? inlineRetVals[i] : LuaNil.NIL);
                            }
                        } else if (ci.expectedResults < 0) {
                            for (int i = 0; i < nReturns; i++) {
                                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, inlineRetVals[i]);
                            }
                            ctx.top = callerFunc + nReturns;
                        }
                    } else {
                        return inlineRetVals;
                    }
                    return null;
                }
            }
            LuaValue[] tailCArgs = getArgsForCall(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1, nActualArgs);
            CallStack.setNextTransfer(ctx.callState, 1, nActualArgs, tailCArgs);
            CallStack.setNextVmFrame(ctx.callState, state, ctx.base, ctx.base - 1, null, -1);
            // PUC does NOT reuse the caller's frame for a C tail call
            // (ldo.c luaD_pretailcall: only LUA_VLCL returns -1 to "startfunc";
            // C closures/functions go through precallC and the caller is then
            // finished by luaD_poscall). So the C function gets its own frame
            // on top of ours, and our frame must remain visible to debug
            // readers (debug.getlocal/getinfo, traceback) for the duration of
            // the C call. Replacing our frame (as before) made the caller's
            // locals invisible and corrupted frame levels.
            CallStack.push(fn, resolvedName, namewhat != null ? namewhat : "", callLine, isMethod, isMeta,
                    ctx.callState, ctx.co);
            int origTop = ctx.thread.getStackTop();
            ctx.thread.setStackTop(funcIdx + nActualArgs + 1);
            LuaValue res = null;
            try {
                res = fn.invoke(tailCArgs);
            } finally {
                ctx.thread.setStackTop(origTop);
                CallStack.Frame f = CallStack.topFrame(ctx.callState);
                if (f != null && res != null) {
                    LuaValue[] retVals = (res instanceof Varargs va) ? va.getValuesUnsafe() : new LuaValue[]{res};
                    f.retValues = retVals;
                    f.ftransfer = 1;
                    f.ntransfer = retVals.length;
                }
                // Pop the C frame, then our own frame: the C call returns to
                // us and we immediately return its results to our caller
                // (equivalent to PUC's precallC + luaD_poscall + ret).
                CallStack.pop(ctx.callState, ctx.co);
                CallStack.pop(ctx.callState, ctx.co);
            }
            LuaValue[] retVals = (res instanceof Varargs va) ? va.getValuesUnsafe() : (res != null ? new LuaValue[]{res} : new LuaValue[0]);
            int nReturns = retVals.length;
            if (ctx.callDepth > 0) {
                CallInfo ci = ctx.callStack[--ctx.callDepth];
                int callerFunc = ci.funcIndex;
                ctx.base = ci.baseIndex;
                ctx.closure = ci.closure;
                ctx.proto = ctx.closure.proto;
                ctx.code = ctx.proto.code;
                ctx.k = ctx.proto.constants;
                ctx.upvals = ctx.closure.upvals;
                ctx.pc = ci.savedPc;
                ctx.varargs = ci.varargs;
                ctx.oldpc = ci.oldpc;
                ctx.varargPrepRan = ci.varargPrepRan;
                ctx.thread.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : nReturns) + 32);
                ctx.pStack = ctx.thread.getPrimitiveStack();
                ctx.tStack = ctx.thread.getTypeStack();
                ctx.oStack = ctx.thread.getObjectStack();
                if (ci.expectedResults > 0) {
                    for (int i = 0; i < ci.expectedResults; i++) {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, (i < nReturns) ? retVals[i] : LuaNil.NIL);
                    }
                } else if (ci.expectedResults < 0) {
                    for (int i = 0; i < nReturns; i++) {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, retVals[i]);
                    }
                    ctx.top = callerFunc + nReturns;
                }
            } else {
                return retVals;
            }
        }
        return null;
    }

    /**
     * Lazy-sync: stamp the returning frame so locals scope and hook
     * observers see the return site.
     */
    private static void stampReturnFrame(VmContext ctx, int instPc, LuaValue[] retVals, int ftransfer, int ntransfer) {
        CallStack.Frame retFrame = CallStack.topFrame(ctx.callState);
        if (retFrame != null) {
            retFrame.pc = instPc;
            if (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) {
                retFrame.line = ctx.proto.lineInfo[instPc];
            }
            retFrame.retValues = retVals;
            retFrame.ftransfer = ftransfer;
            retFrame.ntransfer = ntransfer;
        }
    }

    /**
     * Shared caller-restore for the {@code OP_RETURN} family. Restores the
     * caller view into {@code ctx} and writes back results; a non-null
     * return value must be returned from the dispatch loop immediately.
     */
    private static LuaValue[] returnToCaller(LuaState state, VmContext ctx, LuaValue[] retVals) {
        if (ctx.callDepth > 0) {
            CallStack.pop(ctx.callState, ctx.co);
            CallInfo ci = ctx.callStack[--ctx.callDepth];
            int callerFunc = ci.funcIndex;
            ctx.base = ci.baseIndex;
            ctx.closure = ci.closure;
            ctx.proto = ctx.closure.proto;
            ctx.code = ctx.proto.code;
            ctx.k = ctx.proto.constants;
            ctx.upvals = ctx.closure.upvals;
            ctx.pc = ci.savedPc;
            ctx.varargs = ci.varargs;
            ctx.oldpc = ci.oldpc;
            ctx.varargPrepRan = ci.varargPrepRan;
            int nReturns = retVals.length;
            if (ci.expectedResults > 0) {
                for (int i = 0; i < ci.expectedResults; i++) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, (i < nReturns) ? retVals[i] : LuaNil.NIL);
                }
            } else if (ci.expectedResults < 0) {
                for (int i = 0; i < nReturns; i++) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, retVals[i]);
                }
                ctx.top = callerFunc + nReturns;
            }
            ctx.thread.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
            return null;
        }
        ctx.thread.setStackTop(ctx.savedStackTop);
        return retVals;
    }

    /**
     * Hook-free return path: copies raw register triples straight from the
     * callee's result registers into the caller's, without boxing values
     * into {@code LuaValue} ({@code getLuaValue}), allocating a result
     * array, stamping the frame, and unboxing again ({@code setLuaValue}).
     * Only valid while no hook is armed: the stamped {@code retValues} are
     * observable solely by return hooks (fired in {@code pop} before the
     * frame is discarded) and by debug readers of live frames, and a
     * normally-returned frame is popped immediately, so skipping the stamp
     * is unobservable. Callers must use {@link #returnToCaller} whenever the
     * running coroutine's {@code hooksActive} is true.
     */
    /**
     * Cold top-level boxing for {@link #returnToCallerRaw} (once per
     * execute): kept out of line so the hot restore stays under
     * {@code MaxInlineSize} and fuses into the dispatch loop.
     */
    private static LuaValue[] boxTopLevelResults(VmContext ctx, int srcIdx, int nReturns) {
        ctx.thread.setStackTop(ctx.savedStackTop);
        LuaValue[] retVals = new LuaValue[nReturns];
        for (int i = 0; i < nReturns; i++) {
            retVals[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, srcIdx + i);
        }
        return retVals;
    }

    /**
     * Cold open-result copy for {@link #returnToCallerRaw} (vararg-result
     * calls only): kept out of line for the same reason.
     */
    private static void copyOpenResults(VmContext ctx, int callerFunc, int srcIdx, int nReturns) {
        for (int i = 0; i < nReturns; i++) {
            ctx.pStack[callerFunc + i] = ctx.pStack[srcIdx + i];
            ctx.tStack[callerFunc + i] = ctx.tStack[srcIdx + i];
            ctx.oStack[callerFunc + i] = ctx.oStack[srcIdx + i];
        }
        ctx.top = callerFunc + nReturns;
    }

    /**
     * Cold nil-fill for {@link #returnToCallerRaw} (short-result calls
     * only): kept out of line for the same reason.
     */
    private static void fillNilResults(VmContext ctx, int callerFunc, int from, int expectedResults) {
        for (int i = from; i < expectedResults; i++) {
            ctx.tStack[callerFunc + i] = TYPE_NIL;
            ctx.pStack[callerFunc + i] = 0;
            ctx.oStack[callerFunc + i] = null;
        }
    }

    private static LuaValue[] returnToCallerRaw(LuaState state, VmContext ctx, int srcIdx, int nReturns) {
        if (ctx.callDepth > 0) {
            CallStack.pop(ctx.callState, ctx.co);
            CallInfo ci = ctx.callStack[--ctx.callDepth];
            int callerFunc = ci.funcIndex;
            ctx.base = ci.baseIndex;
            ctx.closure = ci.closure;
            ctx.proto = ctx.closure.proto;
            ctx.code = ctx.proto.code;
            ctx.k = ctx.proto.constants;
            ctx.upvals = ctx.closure.upvals;
            ctx.pc = ci.savedPc;
            ctx.varargs = ci.varargs;
            ctx.oldpc = ci.oldpc;
            ctx.varargPrepRan = ci.varargPrepRan;
            if (ci.expectedResults > 0) {
                int n = Math.min(ci.expectedResults, nReturns);
                // Direct triple copy: cheaper than System.arraycopy's fixed
                // overhead for the tiny (usually 0-2) result counts here,
                // and avoids all LuaValue boxing.
                for (int i = 0; i < n; i++) {
                    ctx.pStack[callerFunc + i] = ctx.pStack[srcIdx + i];
                    ctx.tStack[callerFunc + i] = ctx.tStack[srcIdx + i];
                    ctx.oStack[callerFunc + i] = ctx.oStack[srcIdx + i];
                }
                if (n < ci.expectedResults) {
                    fillNilResults(ctx, callerFunc, n, ci.expectedResults);
                }
            } else if (ci.expectedResults < 0) {
                copyOpenResults(ctx, callerFunc, srcIdx, nReturns);
            }
            ctx.thread.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
            return null;
        }
        // Top-level return must box for the host; rare (once per execute).
        return boxTopLevelResults(ctx, srcIdx, nReturns);
    }

    /**
     * {@code OP_FORPREP} handler (runs once per loop, so call overhead is
     * free). Initializes the numeric loop counter or skips the loop.
     */
    private static void doForPrep(LuaState state, VmContext ctx, int a, int inst) {
        int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
        int regInit = ctx.base + a;
        if (ctx.tStack[regInit] == TYPE_INT && ctx.tStack[regInit + 1] == TYPE_INT && ctx.tStack[regInit + 2] == TYPE_INT) {
            long init = ctx.pStack[regInit];
            long limit = ctx.pStack[regInit + 1];
            long step = ctx.pStack[regInit + 2];
            if (step == 0) throw new LuaException("'for' step is zero");
            ctx.pStack[regInit + 3] = init;
            ctx.tStack[regInit + 3] = TYPE_INT;
            ctx.oStack[regInit + 3] = null;
            boolean skip = step > 0 ? init > limit : init < limit;
            if (skip) {
                ctx.pc += bx;
            } else {
                long count = step > 0 ? Long.divideUnsigned(limit - init, step)
                        : Long.divideUnsigned(init - limit, -step);
                ctx.pStack[regInit + 1] = count;
                // Loop-driven tier-up: a long numeric loop reached from a
                // single call has no call hotness. This runs once per loop
                // entry, so the dispatch hot path pays nothing.
                if (count >= LuaState.JIT_LOOP_THRESHOLD) {
                    requestLoopCompile(state, ctx);
                }
            }
        } else {
            ctx.pc = executeForPrepSlow(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, bx, ctx.pc);
        }
    }

    /**
     * {@code OP_VARARG} handler (runs once per vararg call).
     * Copies varargs into registers; adjusts {@code top} for {@code C == 0}.
     */
    private static void doVararg(VmContext ctx, int a, int inst) {
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int vLen = ctx.varargs != null ? ctx.varargs.length : 0;
        if (c > 1) {
            for (int i = 0; i < c - 1; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + i, i < vLen ? ctx.varargs[i] : LuaNil.NIL);
            }
        } else if (c == 0) {
            for (int i = 0; i < vLen; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + i, ctx.varargs[i]);
            }
            ctx.top = ctx.base + a + vLen;
        }
    }

    /**
     * {@code OP_CLOSE} / {@code OP_TBC} handlers (tiny, cold).
     */
    private static void doClose(LuaState state, VmContext ctx, int a, boolean isTbc) {
        if (isTbc) {
            LuaValue val = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a);
            String varName = ctx.proto.findLocalVarName(a, ctx.pc - 1);
            state.pushTbc(ctx.base + a, val, varName);
        } else {
            state.closeUpvalues(ctx.thread, ctx.base + a);
            state.closeTbc(ctx.thread, ctx.base + a, null);
        }
    }

    /**
     * Bare-thread fallback for the pc mirror (almost never taken; the
     * coroutine is normally always set).
     */
    private static void mirrorSlow(VmContext ctx, int instPc) {
        int curLineSlow = (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[instPc] : -1;
        CallStack.Frame curFrameSlow = CallStack.topFrame(ctx.callState);
        if (curFrameSlow != null) {
            curFrameSlow.pc = instPc;
            curFrameSlow.line = curLineSlow;
        }
    }

    /**
     * Cached call-name resolution. {@code getobjname} is pure in
     * (proto, pc, reg) but scans bytecode per call; this memoizes it in a
     * tiny ctx-local direct-mapped cache. The returned array is shared and
     * must only be read. A cached miss (null) is remembered too.
     */
    private static String[] callName(VmContext ctx, LuaProto p, int lastpc, int reg) {
        int idx = (lastpc + reg * 33) & (VmContext.NAME_CACHE_SIZE - 1);
        if (ctx.ncFilled[idx] && ctx.ncProto[idx] == p && ctx.ncPc[idx] == lastpc && ctx.ncReg[idx] == reg) {
            return ctx.ncInfo[idx];
        }
        String[] info = getobjname(p, lastpc, reg);
        ctx.ncFilled[idx] = true;
        ctx.ncProto[idx] = p;
        ctx.ncPc[idx] = lastpc;
        ctx.ncReg[idx] = reg;
        ctx.ncInfo[idx] = info;
        return info;
    }

    private static CallInfo[] expandCallStack(CallInfo[] callStack) {
        CallInfo[] newStack = new CallInfo[callStack.length * 2];
        System.arraycopy(callStack, 0, newStack, 0, callStack.length);
        for (int i = callStack.length; i < newStack.length; i++) newStack[i] = new CallInfo();
        return newStack;
    }

    private static int executeNewTable(int[] code, int pc, byte[] tStack, LuaValue[] oStack, int base, int a) {
        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_EXTRAARG) {
            pc++;
        }
        int regA = base + a;
        tStack[regA] = TYPE_OBJECT;
        oStack[regA] = new LuaTable();
        return pc;
    }

    private static int executeSetList(int[] code, int pc, int inst, long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int top) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        int last = c;
        if (flagK == 1) {
            if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_EXTRAARG) {
                int extraInst = code[pc++];
                int ax = (extraInst >>> Instruction.POS_Ax) & Instruction.MASK_Ax;
                last += ax * (Instruction.MASK_C + 1);
            }
        }
        int n = b > 0 ? b : (top - (base + a) - 1);
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + a);
        int regBase = base + a;
        if (tbl instanceof LuaTable lt) {
            for (int i = 1; i <= n; i++) {
                LuaValue val = getLuaValue(pStack, tStack, oStack, regBase + i);
                lt.rawset(LuaInteger.valueOf(last + i), val);
            }
        } else {
            for (int i = 1; i <= n; i++) {
                LuaValue val = getLuaValue(pStack, tStack, oStack, regBase + i);
                tbl.set(LuaInteger.valueOf(last + i), val);
            }
        }
        return pc;
    }

    private static void executeClosure(LuaState state, LuaCoroutine thread, LuaProto proto, LuaClosure closure, Upvalue[] upvals,
                                       long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
        LuaProto childProto = proto.protos[bx];
        Upvalue[] childUpvals = new Upvalue[childProto.upvalues.length];
        for (int i = 0; i < childProto.upvalues.length; i++) {
            UpvalueDesc desc = childProto.upvalues[i];
            if (desc.inStack) {
                childUpvals[i] = state.findOrCreateOpenUpvalue(thread, base + desc.index, desc.name);
            } else {
                childUpvals[i] = upvals[desc.index];
            }
        }
        int regA = base + a;
        tStack[regA] = TYPE_OBJECT;
        LuaClosure child = new LuaClosure(childProto, childUpvals, closure.env, state);
        // Stripping applies to the whole proto tree (C undump): children
        // created at runtime inherit the flag so debug info stays masked.
        if (closure.isStripped()) {
            child.setStripped(true);
        }
        oStack[regA] = child;
    }

    private static void executeGetTabUp(long[] pStack, byte[] tStack, LuaValue[] oStack, Upvalue[] upvals, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue tbl = upvals[b].getValue();
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(k[c]));
    }

    /**
     * Frameless inline for the shared {@code tostring} builtin with exactly
     * one argument. Returns the new top, or -1 when the generic external-call
     * path must run instead (metatable with {@code __tostring}, non-primitive
     * argument, or multi-result shapes the inline does not cover).
     *
     * <p>Semantics mirror {@code BaseLib.tostringImpl}: primitives without a
     * basic metatable convert directly; anything else bails. The inline never
     * throws (all covered shapes are total), never grows the stack, and never
     * fires hooks (the caller checks {@code hooksActive}, exactly matching
     * the generic path's gating).
     */
    private static int inlineTostring1(LuaState state, VmContext ctx, int funcIdx, int nResults) {
        if (nResults > 1) {
            return -1;
        }
        int argIdx = funcIdx + 1;
        LuaValue result;
        switch (ctx.tStack[argIdx]) {
            case TYPE_INT -> {
                if (state.basicMetatable(LuaType.NUMBER) != null) {
                    return -1;
                }
                result = LuaString.valueOf(Long.toString(ctx.pStack[argIdx]));
            }
            case TYPE_FLOAT -> {
                if (state.basicMetatable(LuaType.NUMBER) != null) {
                    return -1;
                }
                result = LuaString.valueOf(
                        LuaFloat.valueOf(Double.longBitsToDouble(ctx.pStack[argIdx])).toLuaString());
            }
            case TYPE_BOOLEAN -> result = LuaString.valueOf(ctx.pStack[argIdx] != 0 ? "true" : "false");
            case TYPE_NIL -> result = LuaString.valueOf("nil");
            case TYPE_OBJECT -> {
                LuaValue v = ctx.oStack[argIdx];
                if (v instanceof LuaString s && s.getMetatable() == null) {
                    result = s;
                } else {
                    return -1;
                }
            }
            default -> {
                return -1;
            }
        }
        if (nResults != 0) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, result);
        }
        return funcIdx + 1;
    }

    /**
     * Analyzes whether {@code proto} is a trivial closure factory: leading
     * register/constant materialization ({@code MOVE}/{@code LOADK}/
     * {@code LOADNIL}/{@code CLEANUP}), exactly one {@code OP_CLOSURE},
     * and a single return of the created register (dead {@code CLOSE}/
     * {@code RETURN0} tails allowed). Returns the descriptor, or null for
     * any other shape. Runs once per proto (cached on it).
     */
    private static LuaProto.FactoryInfo analyzeFactory(LuaProto proto) {
        int[] code = proto.code;
        if (code == null || code.length == 0 || code.length > 32 || proto.isVararg) {
            return null;
        }
        int regs = Math.max(proto.maxStackSize, proto.numParams);
        if (regs <= 0 || regs > 64) {
            return null;
        }
        // Per-reg value source: 0 = caller arg (arg[i]), 1 = nil, 2 = const
        // (konst[i]), -1 = unknown. Params start as their caller arg.
        int[] kind = new int[regs];
        int[] arg = new int[regs];
        LuaValue[] konst = new LuaValue[regs];
        for (int i = 0; i < regs; i++) {
            if (i < proto.numParams) {
                kind[i] = 0;
                arg[i] = i;
            } else {
                kind[i] = -1;
            }
        }
        int closureReg = -1;
        int childIdx = -1;
        boolean returned = false;
        int[] capKind = null;
        int[] capArg = null;
        LuaValue[] capConst = null;
        int[] capUp = null;
        for (int pc = 0; pc < code.length; pc++) {
            int inst = code[pc];
            int op = Instruction.getOp(inst);
            int a = Instruction.getA(inst);
            switch (op) {
                case OpCode.OP_MOVE -> {
                    int b = Instruction.getB(inst);
                    if (a < 0 || a >= regs || b < 0 || b >= regs || returned) {
                        return null;
                    }
                    kind[a] = kind[b];
                    arg[a] = arg[b];
                    konst[a] = konst[b];
                }
                case OpCode.OP_LOADK -> {
                    int bx = Instruction.getBx(inst);
                    if (a < 0 || a >= regs || bx < 0 || bx >= proto.constants.length || returned) {
                        return null;
                    }
                    kind[a] = 2;
                    konst[a] = proto.constants[bx];
                }
                case OpCode.OP_LOADNIL, OpCode.OP_CLEANUP -> {
                    int b = Instruction.getB(inst);
                    if (a < 0 || a >= regs || returned) {
                        return null;
                    }
                    for (int j = 0; j <= b && a + j < regs; j++) {
                        kind[a + j] = 1;
                    }
                }
                case OpCode.OP_CLOSURE -> {
                    if (closureReg != -1 || returned) {
                        return null;
                    }
                    int bx = Instruction.getBx(inst);
                    if (a < 0 || a >= regs || bx < 0 || bx >= proto.protos.length) {
                        return null;
                    }
                    closureReg = a;
                    childIdx = bx;
                    UpvalueDesc[] descs = proto.protos[bx].upvalues;
                    capKind = new int[descs.length];
                    capArg = new int[descs.length];
                    capConst = new LuaValue[descs.length];
                    capUp = new int[descs.length];
                    // Snapshot sources at creation time (later moves must
                    // not affect the captured values).
                    for (int i = 0; i < descs.length; i++) {
                        UpvalueDesc d = descs[i];
                        if (!d.inStack) {
                            capKind[i] = 3;
                            capUp[i] = d.index;
                            continue;
                        }
                        if (d.index < 0 || d.index >= regs || kind[d.index] == -1) {
                            return null;
                        }
                        capKind[i] = kind[d.index];
                        capArg[i] = arg[d.index];
                        capConst[i] = konst[d.index];
                    }
                }
                case OpCode.OP_RETURN1 -> {
                    if (closureReg == -1 || returned || a != closureReg) {
                        return null;
                    }
                    returned = true;
                }
                case OpCode.OP_RETURN -> {
                    int b = Instruction.getB(inst);
                    if (closureReg == -1 || returned || b != 2 || a != closureReg) {
                        return null;
                    }
                    returned = true;
                }
                case OpCode.OP_CLOSE, OpCode.OP_RETURN0 -> {
                    // Only valid as dead tail after the return.
                    if (!returned) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        if (closureReg == -1 || !returned) {
            return null;
        }
        return new LuaProto.FactoryInfo(childIdx, capKind, capArg, capConst, capUp);
    }

    /**
     * Frameless trivial-factory call ({@code make_counter} shape): builds
     * the child closure directly from caller registers — no frame, no
     * {@code CallInfo}, no return plumbing. Captured values are closed
     * immediately with the exact representation a normal close produces
     * (primitive tags preserved, so the JIT int lane keeps working); no
     * other code can observe openness because the factory body provably
     * does nothing else. Returns false when the generic path must run
     * (not a factory, hooks, or loop guard active).
     */
    private static boolean callFactory(LuaState state, VmContext ctx, LuaClosure closure,
            int funcIdx, int nActualArgs, int nResults) {
        LuaClosure childClosure = factoryBuild(state, ctx, closure, funcIdx, nActualArgs);
        if (childClosure == null) {
            return false;
        }
        if (nResults != 0) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, childClosure);
            for (int i = 1; i < nResults; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + i, LuaNil.NIL);
            }
        }
        if (nResults < 0) {
            ctx.top = funcIdx + 1;
        }
        return true;
    }

    /**
     * Builds the child closure for a trivial factory, or null when the
     * generic path must run. Shared by {@code OP_CALL} and {@code OP_TAILCALL}
     * (constructors like {@code Vec.new} are usually tail calls).
     */
    private static LuaClosure factoryBuild(LuaState state, VmContext ctx, LuaClosure closure,
            int funcIdx, int nActualArgs) {
        LuaProto proto = closure.proto;
        LuaProto.FactoryInfo fi = proto.factoryInfo;
        if (!proto.factoryAnalyzed) {
            fi = analyzeFactory(proto);
            proto.factoryInfo = fi;
            proto.factoryAnalyzed = true;
        }
        if (fi == null || ctx.thread.hooksActive || state.loopGuard != null) {
            return null;
        }
        LuaProto child = proto.protos[fi.childIdx];
        Upvalue[] ups = new Upvalue[fi.capKind.length];
        Upvalue[] parentUps = closure.upvals;
        for (int i = 0; i < ups.length; i++) {
            // Parent-upvalue sharing indexes the FACTORY's own upvalue array
            // (closure.upvals), never the caller's (ctx.upvals): at f(10) the
            // caller is the chunk (whose upvalue 0 is _ENV), while f's
            // upvalue 0 is w. Mixing them aliases the wrong variable.
            if (fi.capKind[i] == 3 && (fi.capUp[i] < 0 || fi.capUp[i] >= parentUps.length)) {
                return null;
            }
            String name = child.upvalues[i].name;
            switch (fi.capKind[i]) {
                case 0 -> {
                    int ai = fi.capArg[i];
                    org.luava.runtime.eval.Upvalue uv =
                            new org.luava.runtime.eval.Upvalue(name, LuaNil.NIL);
                    if (ai < nActualArgs) {
                        int slot = funcIdx + 1 + ai;
                        // Unboxed int fast lane: no LuaInteger allocation,
                        // identical representation to a normal close.
                        if (ctx.tStack[slot] == TYPE_INT) {
                            uv.setClosedInt(ctx.pStack[slot]);
                        } else {
                            uv.setValue(getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, slot));
                        }
                    }
                    ups[i] = uv;
                }
                case 1 -> ups[i] = new org.luava.runtime.eval.Upvalue(name, LuaNil.NIL);
                case 2 -> {
                    org.luava.runtime.eval.Upvalue uv =
                            new org.luava.runtime.eval.Upvalue(name, LuaNil.NIL);
                    uv.setValue(fi.capConst[i]);
                    ups[i] = uv;
                }
                default -> ups[i] = parentUps[fi.capUp[i]];
            }
        }
        LuaClosure childClosure = new LuaClosure(child, ups, closure.env, state);
        if (closure.isStripped()) {
            childClosure.setStripped(true);
        }
        return childClosure;
    }

    /**
     * Unwinds a tail call that produced exactly one frameless value: pops the
     * callee debug frame, restores the caller's frame from its {@code CallInfo},
     * writes the value (per the caller's expected result count) and returns
     * the top-level result array when there is no caller. Mirrors the generic
     * tail path's net effect; hooks are gated by the callers.
     */
    private static LuaValue[] tailReturnInline(LuaState state, VmContext ctx, LuaValue value) {
        CallStack.pop(ctx.callState, ctx.co);
        if (ctx.callDepth <= 0) {
            return new LuaValue[] {value};
        }
        CallInfo ci = ctx.callStack[--ctx.callDepth];
        int callerFunc = ci.funcIndex;
        ctx.base = ci.baseIndex;
        ctx.closure = ci.closure;
        ctx.proto = ctx.closure.proto;
        ctx.code = ctx.proto.code;
        ctx.k = ctx.proto.constants;
        ctx.upvals = ctx.closure.upvals;
        ctx.pc = ci.savedPc;
        ctx.varargs = ci.varargs;
        ctx.oldpc = ci.oldpc;
        ctx.varargPrepRan = ci.varargPrepRan;
        ctx.thread.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : 1) + 32);
        ctx.pStack = ctx.thread.getPrimitiveStack();
        ctx.tStack = ctx.thread.getTypeStack();
        ctx.oStack = ctx.thread.getObjectStack();
        if (ci.expectedResults > 0) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc, value);
            for (int i = 1; i < ci.expectedResults; i++) {
                setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc + i, LuaNil.NIL);
            }
        } else if (ci.expectedResults < 0) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, callerFunc, value);
            ctx.top = callerFunc + 1;
        }
        return null;
    }

    /**
     * Frameless single-result builtins ({@code setmetatable/2} on a fresh
     * table, {@code math.sqrt/1} on a number). Returns the result, or null
     * when the generic path must run (bad argument types, protected
     * metatable, {@code __gc} handler, hooks, or any other exotic shape —
     * the generic path then produces the exact specified behavior/error).
     */
    private static LuaValue inlineBuiltinResult(VmContext ctx, LuaFunction fn, int funcIdx, int nArgs) {
        if (fn == org.luava.runtime.standard.BaseLib.SETMETATABLE && nArgs == 2) {
            LuaValue tVal = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1);
            LuaValue mtVal = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 2);
            if (!(tVal instanceof LuaTable tbl) || tbl.getMetatable() != null) {
                return null;
            }
            if (mtVal.isNil()) {
                tbl.setMetatable(null);
                return tbl;
            }
            if (mtVal instanceof LuaTable tableMt
                    && tableMt.rawget(LuaValue.Meta.GC).isNil()) {
                tbl.setMetatable(tableMt);
                return tbl;
            }
            return null;
        }
        if (fn == org.luava.runtime.standard.MathLib.SQRT && nArgs == 1) {
            int argIdx = funcIdx + 1;
            double d;
            if (ctx.tStack[argIdx] == TYPE_INT) {
                d = (double) ctx.pStack[argIdx];
            } else if (ctx.tStack[argIdx] == TYPE_FLOAT) {
                d = Double.longBitsToDouble(ctx.pStack[argIdx]);
            } else {
                return null;
            }
            return LuaFloat.valueOf(Math.sqrt(d));
        }
        return null;
    }

    /**
     * Raw-bits variant of the {@code math.sqrt/1} inline: stores the result
     * directly into the caller's register (no {@code LuaFloat} allocation —
     * 500k+ of them in numeric OOP loops). Returns false when the generic
     * path must run. The stored shape (TYPE_FLOAT + raw bits) is exactly
     * what the generic path's {@code setLuaValue} would write.
     */
    private static boolean inlineSqrtRaw(VmContext ctx, LuaFunction fn, int funcIdx, int nArgs, int destIdx) {
        if (fn != org.luava.runtime.standard.MathLib.SQRT || nArgs != 1) {
            return false;
        }
        int argIdx = funcIdx + 1;
        double d;
        if (ctx.tStack[argIdx] == TYPE_INT) {
            d = (double) ctx.pStack[argIdx];
        } else if (ctx.tStack[argIdx] == TYPE_FLOAT) {
            d = Double.longBitsToDouble(ctx.pStack[argIdx]);
        } else {
            return false;
        }
        ctx.pStack[destIdx] = Double.doubleToRawLongBits(Math.sqrt(d));
        ctx.tStack[destIdx] = TYPE_FLOAT;
        ctx.oStack[destIdx] = null;
        return true;
    }

    /**
     * Frameless inline for the shared {@code string.gmatch} builtin.
     * Same contract as {@link #inlineTostring1}: returns the new top, or -1
     * to run the generic path (bad argument types or exotic result shapes,
     * preserving the exact arg-error behavior).
     */
    private static int inlineGmatch(VmContext ctx, int funcIdx, int nArgs, int nResults) {
        if (nResults > 1) {
            return -1;
        }
        LuaValue s = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1);
        LuaValue p = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 2);
        if ((!s.isString() && !s.isNumber()) || (!p.isString() && !p.isNumber())) {
            return -1;
        }
        LuaValue init = LuaNil.NIL;
        if (nArgs > 2) {
            LuaValue initArg = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 3);
            // Only the total shapes inline; anything exotic keeps the generic
            // path (and its identical error) so tracebacks never lose a frame.
            if (!initArg.isNil() && !(initArg instanceof LuaInteger)) {
                return -1;
            }
            init = initArg;
        }
        LuaValue it = org.luava.runtime.standard.LuaPattern.gmatch(s, p, init);
        if (nResults != 0) {
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx, it);
        }
        return funcIdx + 1;
    }

    /**
     * {@code OP_GETTABUP} with a ctx-local site cache for the string-key
     * shape (global-variable reads). The guard is the table's
     * {@code readVersion()}: -1 while a metatable could affect reads (then
     * the entry is never stored), otherwise a counter bumped by every table
     * mutation on any path — so stale reads are impossible, including
     * {@code rawset(_G)}, host {@code setLive}, or swapped upvalues (table
     * identity is part of the guard).
     */
    private static void executeGetTabUpCached(VmContext ctx, int pc, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue key = ctx.k[c];
        LuaValue uv = ctx.upvals[b].getValue();
        if (key instanceof LuaString && uv instanceof LuaTable tbl) {
            int idx = (System.identityHashCode(tbl) ^ (pc * 33) ^ System.identityHashCode(key))
                    & (VmContext.GLOBAL_CACHE_SIZE - 1);
            if (ctx.gcProto[idx] == ctx.proto && ctx.gcPc[idx] == pc
                    && ctx.gcTable[idx] == tbl && ctx.gcKey[idx] == key) {
                long ver = tbl.readVersion();
                if (ver != -1L && ver == ctx.gcVersion[idx]) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.gcValue[idx]);
                    return;
                }
            }
            LuaValue val = tbl.get(key);
            long ver = tbl.readVersion();
            if (ver != -1L) {
                ctx.gcProto[idx] = ctx.proto;
                ctx.gcPc[idx] = pc;
                ctx.gcTable[idx] = tbl;
                ctx.gcKey[idx] = key;
                ctx.gcVersion[idx] = ver;
                ctx.gcValue[idx] = val;
            }
            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, val);
            return;
        }
        executeGetTabUp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.upvals, ctx.k, ctx.base, a, inst);
    }

    private static void executeGetTable(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int tb = base + b;
        int kb = base + c;
        // Fast lane: plain table + integer key. Skips key boxing and the
        // virtual get(); the result still unboxes into registers.
        if (tStack[tb] == TYPE_OBJECT && oStack[tb] instanceof LuaTable lt && lt.getMetatable() == null
                && tStack[kb] == TYPE_INT) {
            setLuaValue(pStack, tStack, oStack, base + a, lt.rawgetInt(pStack[kb]));
            return;
        }
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
        LuaValue key = getLuaValue(pStack, tStack, oStack, base + c);
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(key));
    }

    private static void executeGetI(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
        if (tbl instanceof LuaTable lt && lt.getMetatable() == null) {
            setLuaValue(pStack, tStack, oStack, base + a, lt.rawgetInt(c));
        } else {
            setLuaValue(pStack, tStack, oStack, base + a, tbl.get(LuaInteger.valueOf(c)));
        }
    }

    private static void executeGetField(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int tb = base + b;
        // Fast lane: the field exists directly on a table instance (the
        // dominant `self.x` shape). Skips the virtual get() loop and the
        // metatable walk; a miss falls through with identical semantics.
        if (tStack[tb] == TYPE_OBJECT && oStack[tb] instanceof LuaTable lt) {
            LuaValue val = lt.rawget(k[c]);
            if (val != LuaNil.NIL) {
                setLuaValue(pStack, tStack, oStack, base + a, val);
                return;
            }
        }
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, tb);
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(k[c]));
    }

    private static void executeSetTabUp(long[] pStack, byte[] tStack, LuaValue[] oStack, Upvalue[] upvals, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        LuaValue tbl = upvals[a].getValue();
        LuaValue key = k[b];
        LuaValue val = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
        tbl.set(key, val);
    }

    private static void executeSetTable(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        int tb = base + a;
        int kb = base + b;
        // Fast lane: plain table + integer key. Skips key boxing, the
        // virtual set() and the metatable probe; the value still boxes
        // (tables hold objects). Mirrors the OP_SETI lane.
        if (tStack[tb] == TYPE_OBJECT && oStack[tb] instanceof LuaTable lt && lt.getMetatable() == null
                && tStack[kb] == TYPE_INT) {
            LuaValue val = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
            lt.rawsetInt(pStack[kb], val);
            return;
        }
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + a);
        LuaValue key = getLuaValue(pStack, tStack, oStack, base + b);
        LuaValue val = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
        tbl.set(key, val);
    }

    private static void executeSetI(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + a);
        LuaValue val = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
        if (tbl instanceof LuaTable lt && lt.getMetatable() == null) {
            lt.rawsetInt(b, val);
        } else {
            tbl.set(LuaInteger.valueOf(b), val);
        }
    }

    private static void executeSetField(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + a);
        LuaValue val = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
        tbl.set(k[b], val);
    }

    private static void executeSelf(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
        LuaValue key = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
        setLuaValue(pStack, tStack, oStack, base + a + 1, tbl);
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(key));
    }

    /**
     * {@code OP_SELF} fast lane for {@code str:method} with a constant method
     * name (the dominant string-library call shape). String methods resolve
     * as metatable {@code __index} (the string library table) + raw field;
     * both hops are cached per (proto, pc, key) guarded by the library
     * table's identity and {@code readVersion()}, so {@code string.foo = ..}
     * reassignment or metatable swaps safely miss. Anything exotic (no
     * metatable, function {@code __index}, missing method) falls through to
     * the generic path with identical semantics.
     */
    private static void executeSelfCached(VmContext ctx, LuaState state, int pc, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
        int regB = ctx.base + b;
        LuaValue key = flagK == 1 ? ctx.k[c] : getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + c);
        if (flagK == 1 && key instanceof LuaString ks) {
            if (ctx.tStack[regB] == TYPE_OBJECT && ctx.oStack[regB] instanceof LuaTable tbl) {
                // `obj:method` on a table missing the field: resolve through
                // metatable __index once per (proto, pc, metatable, key),
                // guarded by the index table's readVersion(). OOP loops reuse
                // one metatable for millions of fresh instances, so this turns
                // 2-3 hash probes per method call into a handful of compares.
                LuaValue own = tbl.rawget(ks);
                if (!own.isNil()) {
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 1, ctx.oStack[regB]);
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, own);
                    return;
                }
                LuaTable mt = tbl.getMetatable();
                LuaValue handler = (mt != null) ? mt.rawget(LuaValue.Meta.INDEX) : null;
                if (handler instanceof LuaTable idx) {
                    int id = (System.identityHashCode(mt) ^ System.identityHashCode(idx) ^ (pc * 33)
                            ^ System.identityHashCode(ks)) & (VmContext.GLOBAL_CACHE_SIZE - 1);
                    if (ctx.gcProto[id] == ctx.proto && ctx.gcPc[id] == pc
                            && ctx.gcTable[id] == idx && ctx.gcKey[id] == ks) {
                        long ver = idx.readVersion();
                        if (ver != -1L && ver == ctx.gcVersion[id]) {
                            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 1, ctx.oStack[regB]);
                            setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.gcValue[id]);
                            return;
                        }
                    }
                    LuaValue m = idx.rawget(ks);
                    long ver = idx.readVersion();
                    if (ver != -1L && !m.isNil()) {
                        ctx.gcProto[id] = ctx.proto;
                        ctx.gcPc[id] = pc;
                        ctx.gcTable[id] = idx;
                        ctx.gcKey[id] = ks;
                        ctx.gcVersion[id] = ver;
                        ctx.gcValue[id] = m;
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 1, ctx.oStack[regB]);
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, m);
                        return;
                    }
                }
            }
            LuaTable mt = state.basicMetatable(LuaType.STRING);
            LuaValue handler = (mt != null) ? mt.rawget(LuaValue.Meta.INDEX) : null;
            if (handler instanceof LuaTable idx) {
                int id = (System.identityHashCode(idx) ^ (pc * 33) ^ System.identityHashCode(ks))
                        & (VmContext.GLOBAL_CACHE_SIZE - 1);
                if (ctx.gcProto[id] == ctx.proto && ctx.gcPc[id] == pc
                        && ctx.gcTable[id] == idx && ctx.gcKey[id] == ks) {
                    long ver = idx.readVersion();
                    if (ver != -1L && ver == ctx.gcVersion[id]) {
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 1, ctx.oStack[regB]);
                        setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, ctx.gcValue[id]);
                        return;
                    }
                }
                LuaValue m = idx.rawget(ks);
                long ver = idx.readVersion();
                if (ver != -1L && !m.isNil()) {
                    ctx.gcProto[id] = ctx.proto;
                    ctx.gcPc[id] = pc;
                    ctx.gcTable[id] = idx;
                    ctx.gcKey[id] = ks;
                    ctx.gcVersion[id] = ver;
                    ctx.gcValue[id] = m;
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 1, ctx.oStack[regB]);
                    setLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a, m);
                    return;
                }
            }
        }
        executeSelf(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
    }

    private static void executeSlowAdd(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int regC) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        LuaValue vc = getLuaValue(pStack, tStack, oStack, regC);
        setLuaValue(pStack, tStack, oStack, regA, vb.add(vc));
    }

    private static void executeSlowAddI(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int sc) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        setLuaValue(pStack, tStack, oStack, regA, vb.add(LuaInteger.valueOf(sc)));
    }

    private static void executeSlowAddK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int regB = base + b;
        int regA = base + a;
        LuaValue kc = k[c];
        if (tStack[regB] == TYPE_INT && kc instanceof LuaInteger ki) {
            pStack[regA] = pStack[regB] + ki.toLong();
            tStack[regA] = TYPE_INT;
            oStack[regA] = null;
            return;
        }
        if (isNumber(tStack[regB]) && kc.isNumber()) {
            double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
            pStack[regA] = Double.doubleToRawLongBits(db + kc.toDouble());
            tStack[regA] = TYPE_FLOAT;
            oStack[regA] = null;
            return;
        }
        setLuaValue(pStack, tStack, oStack, regA, getLuaValue(pStack, tStack, oStack, regB).add(kc));
    }

    private static void executeSlowSubK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int regB = base + b;
        int regA = base + a;
        LuaValue kc = k[c];
        if (tStack[regB] == TYPE_INT && kc instanceof LuaInteger ki) {
            pStack[regA] = pStack[regB] - ki.toLong();
            tStack[regA] = TYPE_INT;
            oStack[regA] = null;
            return;
        }
        if (isNumber(tStack[regB]) && kc.isNumber()) {
            double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
            pStack[regA] = Double.doubleToRawLongBits(db - kc.toDouble());
            tStack[regA] = TYPE_FLOAT;
            oStack[regA] = null;
            return;
        }
        setLuaValue(pStack, tStack, oStack, regA, getLuaValue(pStack, tStack, oStack, regB).sub(kc));
    }

    private static void executeSlowSub(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int regC) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        LuaValue vc = getLuaValue(pStack, tStack, oStack, regC);
        setLuaValue(pStack, tStack, oStack, regA, vb.sub(vc));
    }

    private static void executeSlowMul(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int regC) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        LuaValue vc = getLuaValue(pStack, tStack, oStack, regC);
        setLuaValue(pStack, tStack, oStack, regA, vb.mul(vc));
    }

    private static void executeSlowMulK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        int regB = base + b;
        int regA = base + a;
        // Unboxed fast lanes mirroring OP_MUL: the constant is a compile-time
        // literal (normally LuaInteger), so int*int must stay in the raw
        // register world instead of boxing through getLuaValue().mul().
        LuaValue kc = k[c];
        if (tStack[regB] == TYPE_INT && kc instanceof LuaInteger ki) {
            pStack[regA] = pStack[regB] * ki.toLong();
            tStack[regA] = TYPE_INT;
            oStack[regA] = null;
            return;
        }
        if (isNumber(tStack[regB]) && kc.isNumber()) {
            double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
            pStack[regA] = Double.doubleToRawLongBits(db * kc.toDouble());
            tStack[regA] = TYPE_FLOAT;
            oStack[regA] = null;
            return;
        }
        setLuaValue(pStack, tStack, oStack, regA, getLuaValue(pStack, tStack, oStack, regB).mul(kc));
    }

    private static void executeSlowDiv(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).div(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowDivK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).div(k[c]));
    }

    private static void executeSlowIDiv(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).idiv(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowIDivK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).idiv(k[c]));
    }

    private static void executeSlowMod(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).mod(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowModK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).mod(k[c]));
    }

    private static void executeSlowPow(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).pow(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowPowK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).pow(k[c]));
    }

    private static void executeSlowBand(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).band(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowBandK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).band(k[c]));
    }

    private static void executeSlowBor(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).bor(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowBorK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).bor(k[c]));
    }

    private static void executeSlowBxor(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).bxor(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowBxorK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).bxor(k[c]));
    }

    private static void executeSlowShl(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).shl(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowShlI(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int sc = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, LuaInteger.valueOf(sc).shl(getLuaValue(pStack, tStack, oStack, base + b)));
    }

    private static void executeSlowShr(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).shr(getLuaValue(pStack, tStack, oStack, base + c)));
    }

    private static void executeSlowShrI(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int sc = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).shr(LuaInteger.valueOf(sc)));
    }

    private static void executeConcat(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        LuaValue res = getLuaValue(pStack, tStack, oStack, base + a);
        for (int j = 1; j < b; j++) {
            res = res.concat(getLuaValue(pStack, tStack, oStack, base + a + j));
        }
        setLuaValue(pStack, tStack, oStack, base + a, res);
    }

    private static LuaValue[] getArgsForCall(long[] pStack, byte[] tStack, LuaValue[] oStack, int startIdx, int count) {
        if (count <= 0) return LuaCoroutine.EMPTY_VALUES;
        LuaValue[] args = new LuaValue[count];
        for (int i = 0; i < count; i++) {
            args[i] = getLuaValue(pStack, tStack, oStack, startIdx + i);
        }
        return args;
    }

    private static final boolean[] OP_SETS_A = new boolean[OpCode.NUM_OPCODES];
    static {
        int[] setsA = {
            OpCode.OP_MOVE, OpCode.OP_LOADI, OpCode.OP_LOADF, OpCode.OP_LOADK, OpCode.OP_LOADKX,
            OpCode.OP_LOADFALSE, OpCode.OP_LFALSESKIP, OpCode.OP_LOADTRUE, OpCode.OP_LOADNIL,
            OpCode.OP_GETUPVAL, OpCode.OP_GETTABUP, OpCode.OP_GETTABLE, OpCode.OP_GETI, OpCode.OP_GETFIELD,
            OpCode.OP_NEWTABLE, OpCode.OP_SELF, OpCode.OP_ADDI, OpCode.OP_ADDK, OpCode.OP_SUBK,
            OpCode.OP_MULK, OpCode.OP_MODK, OpCode.OP_POWK, OpCode.OP_DIVK, OpCode.OP_IDIVK,
            OpCode.OP_BANDK, OpCode.OP_BORK, OpCode.OP_BXORK, OpCode.OP_SHRI, OpCode.OP_SHLI,
            OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL, OpCode.OP_MOD, OpCode.OP_POW,
            OpCode.OP_DIV, OpCode.OP_IDIV, OpCode.OP_BAND, OpCode.OP_BOR, OpCode.OP_BXOR,
            OpCode.OP_SHL, OpCode.OP_SHR, OpCode.OP_UNM, OpCode.OP_BNOT, OpCode.OP_NOT,
            OpCode.OP_LEN, OpCode.OP_CONCAT, OpCode.OP_TESTSET, OpCode.OP_CALL, OpCode.OP_TAILCALL,
            OpCode.OP_FORLOOP, OpCode.OP_FORPREP, OpCode.OP_TFORLOOP, OpCode.OP_CLOSURE, OpCode.OP_VARARG,
            OpCode.OP_CLEANUP
        };
        for (int op : setsA) {
            OP_SETS_A[op] = true;
        }
    }

    private static boolean testAMode(int op) {
        return op >= 0 && op < OP_SETS_A.length && OP_SETS_A[op];
    }

    private static int findsetreg(LuaProto p, int lastpc, int reg) {
        int setreg = -1;
        int jmptarget = 0;
        int[] code = p.code;
        if (lastpc > 0 && lastpc < code.length) {
            int op = code[lastpc] & Instruction.MASK_OP;
            if (op == OpCode.OP_MMBIN || op == OpCode.OP_MMBINI || op == OpCode.OP_MMBINK) {
                lastpc--;
            }
        }
        for (int pc = 0; pc < lastpc; pc++) {
            int inst = code[pc];
            int op = inst & Instruction.MASK_OP;
            int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
            boolean change = false;
            switch (op) {
                case OpCode.OP_LOADNIL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    change = (a <= reg && reg <= a + b);
                }
                case OpCode.OP_TFORCALL -> {
                    change = (reg >= a + 2);
                }
                case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                    change = (reg >= a);
                }
                case OpCode.OP_JMP -> {
                    int b = Instruction.getsJ(inst);
                    int dest = pc + 1 + b;
                    if (dest <= lastpc && dest > jmptarget) {
                        jmptarget = dest;
                    }
                }
                default -> {
                    change = testAMode(op) && (reg == a);
                }
            }
            if (change) {
                setreg = (pc < jmptarget) ? -1 : pc;
            }
        }
        return setreg;
    }

    private static String kname(LuaProto p, int index) {
        String n = knameOrNull(p, index);
        return n != null ? n : "?";
    }

    /**
     * PUC {@code kname}: only a string constant has a name. A non-string
     * constant (number, boolean, ...) yields no symbolic name, so
     * {@code basicgetobjname} must report "unknown" rather than
     * {@code "constant '?'"} — the latter produced bogus descriptors like
     * {@code number (constant '?') has no integer representation}.
     */
    private static String knameOrNull(LuaProto p, int index) {
        if (p.constants != null && index >= 0 && index < p.constants.length) {
            LuaValue kv = p.constants[index];
            if (kv instanceof LuaString ls) {
                return ls.value();
            }
        }
        return null;
    }

    private static String basicgetobjname(LuaProto p, int[] ppc, int reg, String[] name) {
        int pc = ppc[0];
        String locName = p.findLocalVarName(reg, pc);
        if (locName != null) {
            name[0] = locName;
            return "local";
        }
        ppc[0] = pc = findsetreg(p, pc, reg);
        if (pc != -1) {
            int inst = p.code[pc];
            int op = inst & Instruction.MASK_OP;
            switch (op) {
                case OpCode.OP_MOVE -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
                    if (b < a) {
                        return basicgetobjname(p, ppc, b, name);
                    }
                }
                case OpCode.OP_GETUPVAL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    name[0] = (p.upvalues != null && b < p.upvalues.length && p.upvalues[b] != null) ? p.upvalues[b].name : "?";
                    return "upvalue";
                }
                case OpCode.OP_LOADK -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    String cName = knameOrNull(p, bx);
                    if (cName == null) {
                        return null;  // non-string constant: no symbolic name
                    }
                    name[0] = cName;
                    return "constant";
                }
                case OpCode.OP_LOADKX -> {
                    if (pc + 1 < p.code.length) {
                        int nextInst = p.code[pc + 1];
                        int ax = (nextInst >>> Instruction.POS_Ax) & Instruction.MASK_Ax;
                        String cName = knameOrNull(p, ax);
                        if (cName == null) {
                            return null;
                        }
                        name[0] = cName;
                        return "constant";
                    }
                }
                default -> {}
            }
        }
        return null;
    }

    private static void rname(LuaProto p, int pc, int c, String[] name) {
        int[] ppc = new int[]{pc};
        String what = basicgetobjname(p, ppc, c, name);
        if (what == null || !what.equals("constant")) {
            name[0] = "?";
        }
    }

    private static String isEnv(LuaProto p, int pc, int inst, boolean isup) {
        int t = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        String name;
        if (isup) {
            name = (p.upvalues != null && t < p.upvalues.length && p.upvalues[t] != null) ? p.upvalues[t].name : null;
        } else {
            String[] outName = new String[1];
            int[] ppc = new int[]{pc};
            String what = basicgetobjname(p, ppc, t, outName);
            name = ("local".equals(what) || "upvalue".equals(what)) ? outName[0] : null;
        }
        return "_ENV".equals(name) ? "global" : "field";
    }

    public static String[] getobjname(LuaProto p, int lastpc, int reg) {
        if (p == null || p.code == null) return null;
        String[] name = new String[]{ "?" };
        int[] ppc = new int[]{ lastpc };
        String kind = basicgetobjname(p, ppc, reg, name);
        if (kind != null) {
            return new String[]{ name[0], kind };
        }
        lastpc = ppc[0];
        if (lastpc != -1 && lastpc < p.code.length) {
            int inst = p.code[lastpc];
            int op = inst & Instruction.MASK_OP;
            switch (op) {
                case OpCode.OP_GETTABUP -> {
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    name[0] = kname(p, c);
                    return new String[]{ name[0], isEnv(p, lastpc, inst, true) };
                }
                case OpCode.OP_GETTABLE -> {
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    rname(p, lastpc, c, name);
                    return new String[]{ name[0], isEnv(p, lastpc, inst, false) };
                }
                case OpCode.OP_GETI -> {
                    return new String[]{ "integer index", "field" };
                }
                case OpCode.OP_GETFIELD -> {
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    name[0] = kname(p, c);
                    return new String[]{ name[0], isEnv(p, lastpc, inst, false) };
                }
                case OpCode.OP_SELF -> {
                    int kFlag = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    if (kFlag == 1) {
                        name[0] = kname(p, c);
                    } else {
                        rname(p, lastpc, c, name);
                    }
                    return new String[]{ name[0], "method" };
                }
                default -> {}
            }
        }
        return null;
    }

    /**
     * Adapter routing userdata calls (including Java SAM functional
     * interfaces) through the standard external-call path. LuaUserdata.call
     * already forwards SAM invocations; the adapter only supplies the
     * LuaFunction shape the VM dispatch expects.
     */
    private static final class UserdataCallFunction extends LuaFunction {
        private final LuaUserdata target;

        UserdataCallFunction(LuaUserdata target) {
            this.target = target;
            setName("userdata");
            setWhat("C");
        }

        @Override
        public LuaValue invoke(LuaValue... args) {
            return target.call(args);
        }
    }

    private static int executeExternalCall(LuaState state, VmContext ctx, LuaProto proto, int pc, int base, LuaFunction fn, int funcIdx, int nActualArgs, int nResults, int curLine) {        long[] pStack = ctx.thread.getPrimitiveStack();
        byte[] tStack = ctx.thread.getTypeStack();
        LuaValue[] oStack = ctx.thread.getObjectStack();
        LuaValue[] cArgs = getArgsForCall(pStack, tStack, oStack, funcIdx + 1, nActualArgs);
        if (curLine > 0) CallStack.setLine(ctx.callState, ctx.co, curLine);
        CallStack.CallStackState csState = ctx.callState;
        String resolvedName = csState.nextName;
        String namewhat = csState.nextNamewhat;
        boolean isMeta = csState.nextMetamethod;
        boolean isMethod = csState.nextMethod;
        csState.nextName = null;
        csState.nextNamewhat = null;
        csState.nextMetamethod = false;
        csState.nextMethod = false;
        if (resolvedName == null) {
            // Introspect bytecode opcode to determine accurate namewhat ('global', 'local', 'field', 'method').
            // Memoized per (proto, pc, reg): hot call sites (e.g. tostring in
            // a loop) otherwise re-scan bytecode on every single call.
            String[] info = callName(ctx, proto, pc - 1, funcIdx - base);
            if (info != null) {
                resolvedName = info[0];
                namewhat = info[1];
                if ("method".equals(namewhat)) isMethod = true;
            } else {
                resolvedName = fn.getName();
                namewhat = "";
            }
        }
        CallStack.Frame callerFrame = CallStack.topFrame(ctx.callState);
        if (callerFrame != null) {
            callerFrame.pc = pc - 1;
        }
        CallStack.setNextTransfer(ctx.callState, 1, nActualArgs, cArgs);
        CallStack.setNextVmFrame(ctx.callState, state, funcIdx + 1, funcIdx, null, -1);
        CallStack.push(fn, resolvedName, namewhat, curLine, isMethod, isMeta, ctx.callState, ctx.co);
        CallStack.Frame extFrame = CallStack.topFrame(ctx.callState);
        if (extFrame != null) {
            extFrame.cArgs = cArgs;
        }
        int savedStackTop = ctx.thread.getStackTop();
        // Lua 5.4: GC traverses stack up to top; restrict stack top to active arguments during external calls
        ctx.thread.setStackTop(funcIdx + nActualArgs + 1);
        LuaValue res = null;
        try {
            res = fn.invoke(cArgs);
        } finally {
            ctx.thread.setStackTop(savedStackTop);
            CallStack.Frame f = CallStack.topFrame(ctx.callState);
            if (f != null && res != null) {
                LuaValue[] retVals = (res instanceof Varargs va) ? va.getValuesUnsafe() : new LuaValue[]{res};
                f.retValues = retVals;
                f.ftransfer = 1;
                f.ntransfer = retVals.length;
            }
            CallStack.pop(ctx.callState, ctx.co);
        }
        ctx.thread.ensureStackCapacity(funcIdx + (nResults > 0 ? nResults : 16) + 32);
        pStack = ctx.thread.getPrimitiveStack();
        tStack = ctx.thread.getTypeStack();
        oStack = ctx.thread.getObjectStack();
        if (nResults > 0) {
            if (res instanceof Varargs va) {
                LuaValue[] vals = va.getValuesUnsafe();
                for (int i = 0; i < nResults; i++) {
                    setLuaValue(pStack, tStack, oStack, funcIdx + i, (i < vals.length) ? vals[i] : LuaNil.NIL);
                }
            } else {
                setLuaValue(pStack, tStack, oStack, funcIdx, res != null ? res : LuaNil.NIL);
                for (int i = 1; i < nResults; i++) {
                    setLuaValue(pStack, tStack, oStack, funcIdx + i, LuaNil.NIL);
                }
            }
            return funcIdx + nResults;
        } else if (nResults < 0) {
            if (res instanceof Varargs va) {
                LuaValue[] vals = va.getValuesUnsafe();
                state.ensureStackCapacity(funcIdx + vals.length + 32);
                pStack = ctx.thread.getPrimitiveStack();
                tStack = ctx.thread.getTypeStack();
                oStack = ctx.thread.getObjectStack();
                for (int i = 0; i < vals.length; i++) {
                    setLuaValue(pStack, tStack, oStack, funcIdx + i, vals[i]);
                }
                return funcIdx + vals.length;
            } else if (res != null) {
                setLuaValue(pStack, tStack, oStack, funcIdx, res);
                return funcIdx + 1;
            } else {
                return funcIdx;
            }
        }
        return funcIdx;
    }

    private static int executeForPrepSlow(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int bx, int pc) {
        int regInit = base + a;
        LuaValue init = getLuaValue(pStack, tStack, oStack, regInit);
        LuaValue limit = getLuaValue(pStack, tStack, oStack, regInit + 1);
        LuaValue step = getLuaValue(pStack, tStack, oStack, regInit + 2);

        Long iInit = toForInteger(init);
        Long iStep = toForInteger(step);

        // Lua 5.4: If both initial value and step are integers, the loop executes as an integer loop
        if (iInit != null && iStep != null) {
            long stepVal = iStep;
            if (stepVal == 0) throw new LuaException("'for' step is zero");
            long initVal = iInit;
            long limitVal;

            Long iLimit = toForInteger(limit);
            if (iLimit != null) {
                limitVal = iLimit;
            } else {
                Double dLimit = toForFloat(limit);
                if (dLimit == null) {
                    throw new LuaException("bad 'for' limit (number expected, got " + limit.typeName() + ")");
                }
                double d = dLimit;
                if (Double.isNaN(d)) {
                    // C lvm.c forlimit: NaN converts via tonumber, 0 < NaN is
                    // false so it takes the negative branch: skip for step > 0,
                    // MININTEGER bound for step < 0 (loop then runs).
                    if (stepVal > 0) return pc + bx;
                    limitVal = Long.MIN_VALUE;
                } else if (d >= 9223372036854775808.0) {
                    if (stepVal < 0) return pc + bx; // skip loop
                    limitVal = Long.MAX_VALUE;
                } else if (d < -9223372036854775808.0) {
                    if (stepVal > 0) return pc + bx; // skip loop
                    limitVal = Long.MIN_VALUE;
                } else {
                    // Lua 5.4 forlimit: floor for ascending step, ceil for descending step
                    limitVal = (long) (stepVal > 0 ? Math.floor(d) : Math.ceil(d));
                }
            }

            setLuaValue(pStack, tStack, oStack, regInit + 3, LuaInteger.valueOf(initVal));
            boolean skip = stepVal > 0 ? initVal > limitVal : initVal < limitVal;
            if (skip) {
                return pc + bx;
            } else {
                // C lvm.c: count computed in unsigned arithmetic to avoid overflow
                long count = stepVal > 0 ? Long.divideUnsigned(limitVal - initVal, stepVal)
                        : Long.divideUnsigned(initVal - limitVal, -stepVal);
                setLuaValue(pStack, tStack, oStack, regInit, LuaInteger.valueOf(initVal));
                setLuaValue(pStack, tStack, oStack, regInit + 1, LuaInteger.valueOf(count));
                setLuaValue(pStack, tStack, oStack, regInit + 2, LuaInteger.valueOf(stepVal));
                return pc;
            }
        }

        Double fInit = toForFloat(init);
        Double fLimit = toForFloat(limit);
        Double fStep = toForFloat(step);
        if (fLimit == null) {
            throw new LuaException("bad 'for' limit (number expected, got " + limit.typeName() + ")");
        }
        if (fStep == null) {
            throw new LuaException("bad 'for' step (number expected, got " + step.typeName() + ")");
        }
        if (fInit == null) {
            throw new LuaException("bad 'for' initial value (number expected, got " + init.typeName() + ")");
        }
        double s = fStep;
        if (s == 0.0) {
            throw new LuaException("'for' step is zero");
        }
        double ini = fInit;
        double lim = fLimit;
        boolean skip = s > 0 ? ini > lim : ini < lim;
        if (skip) {
            return pc + bx;
        } else {
            setLuaValue(pStack, tStack, oStack, regInit, LuaFloat.valueOf(ini));
            setLuaValue(pStack, tStack, oStack, regInit + 1, LuaFloat.valueOf(lim));
            setLuaValue(pStack, tStack, oStack, regInit + 2, LuaFloat.valueOf(s));
            setLuaValue(pStack, tStack, oStack, regInit + 3, LuaFloat.valueOf(ini));
            return pc;
        }
    }

    private static Long toForInteger(LuaValue v) {
        // C lvm.c forprep: integer loop only when init AND step are strictly
        // integers; strings (even "1") force the float path.
        if (v instanceof LuaInteger li) return li.toLong();
        return null;
    }

    private static Double toForFloat(LuaValue v) {
        if (v.isNumber()) return v.toDouble();
        if (v instanceof LuaString ls) {
            LuaValue num = ls.toLuaNumber();
            if (num != null) return num.toDouble();
        }
        return null;
    }

    private static void executeTForCall(VmContext ctx, int inst, int a) {
        long[] pStack = ctx.pStack;
        byte[] tStack = ctx.tStack;
        LuaValue[] oStack = ctx.oStack;
        int base = ctx.base;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue f = getLuaValue(pStack, tStack, oStack, base + a);
        // Fast lane: gmatch iterators ignore (state, control) and expose a
        // direct step. Skips two args boxing, the varargs array and double
        // virtual dispatch per iteration (500k+/s in gmatch loops).
        if (f instanceof org.luava.runtime.standard.LuaPattern.GmatchIterator gi) {
            LuaValue res = gi.next();
            int nVars = Math.max(1, c);
            if (res instanceof Varargs va) {
                LuaValue[] vals = va.getValuesUnsafe();
                for (int i = 0; i < nVars; i++) {
                    setLuaValue(pStack, tStack, oStack, base + a + 4 + i, (i < vals.length) ? vals[i] : LuaNil.NIL);
                }
            } else {
                setLuaValue(pStack, tStack, oStack, base + a + 4, res != null ? res : LuaNil.NIL);
                for (int i = 1; i < nVars; i++) {
                    setLuaValue(pStack, tStack, oStack, base + a + 4 + i, LuaNil.NIL);
                }
            }
            return;
        }
        LuaValue s = getLuaValue(pStack, tStack, oStack, base + a + 1);
        LuaValue var = getLuaValue(pStack, tStack, oStack, base + a + 2);
        // C Lua: iterator calls report name/namewhat "for iterator".
        // Lua closures consume this via the initial push; Java callables push
        // no frame, so clear afterwards (try-finally: also on throw) to avoid
        // leaking the name into unrelated later calls. Staged on the
        // ctx-local call state (no ThreadLocal) — this runs per iteration.
        org.luava.runtime.eval.CallStack.setNextCall(ctx.callState, "for iterator", "for iterator", false, false);
        LuaValue res;
        try {
            res = f.call(s, var);
        } finally {
            org.luava.runtime.eval.CallStack.clearNextCallIf(ctx.callState, "for iterator");
        }
        int nVars = Math.max(1, c);
        if (res instanceof Varargs va) {
            LuaValue[] vals = va.getValuesUnsafe();
            for (int i = 0; i < nVars; i++) {
                setLuaValue(pStack, tStack, oStack, base + a + 4 + i, (i < vals.length) ? vals[i] : LuaNil.NIL);
            }
        } else {
            setLuaValue(pStack, tStack, oStack, base + a + 4, res != null ? res : LuaNil.NIL);
            for (int i = 1; i < nVars; i++) {
                setLuaValue(pStack, tStack, oStack, base + a + 4 + i, LuaNil.NIL);
            }
        }
    }

    /**
     * Checks if a LuaValue has an exact integer representation under Lua 5.4 rules
     * (integers, or floats whose value fits into a 64-bit signed integer and is an exact integer).
     */
    private static boolean hasIntegerRepresentation(LuaValue v) {
        if (v instanceof LuaInteger) return true;
        if (v instanceof LuaFloat f) {
            double d = f.toDouble();
            return d >= -9223372036854775808.0 && d < 9223372036854775808.0 &&
                   Math.floor(d) == d && !Double.isInfinite(d) && !Double.isNaN(d);
        }
        return false;
    }

    /**
     * Lua 5.4 error decoration (ldebug.c: varinfo).
     * Enriches runtime error messages with operand descriptors from bytecode inspection:
     * e.g. "attempt to index a nil value (global 'x')",
     *      "number (field 'huge') has no integer representation".
     */
    private static void attachBytecodeDesc(LuaException le, LuaProto proto, int faultPc,
                                           long[] pStack, byte[] tStack, LuaValue[] oStack, int base) {
        if (le == null || le.isDecorated() || proto == null || proto.code == null || faultPc < 0 || faultPc >= proto.code.length) return;
        String msg = le.getMessage();
        if (msg == null || msg.contains("(")) return;
        if (!msg.endsWith("value") && !msg.contains("has no integer representation")) return;

        int inst = proto.code[faultPc];
        int op = inst & Instruction.MASK_OP;
        String desc = null;

        switch (op) {
            case OpCode.OP_GETTABUP -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                String upName = (proto.upvalues != null && b < proto.upvalues.length && proto.upvalues[b] != null) ? proto.upvalues[b].name : null;
                if ("_ENV".equals(upName)) {
                    desc = "(global '" + kname(proto, c) + "')";
                } else if (upName != null) {
                    desc = "(upvalue '" + upName + "')";
                }
            }
            case OpCode.OP_GETTABLE -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_GETI -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_GETFIELD -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_SETTABUP -> {
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String upName = (proto.upvalues != null && a < proto.upvalues.length && proto.upvalues[a] != null) ? proto.upvalues[a].name : null;
                if ("_ENV".equals(upName)) {
                    desc = "(global '" + kname(proto, b) + "')";
                } else if (upName != null) {
                    desc = "(upvalue '" + upName + "')";
                }
            }
            case OpCode.OP_SETTABLE, OpCode.OP_SETI, OpCode.OP_SETFIELD -> {
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
                String[] info = getobjname(proto, faultPc, a);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_SELF -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
                String[] info = getobjname(proto, faultPc, a);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL, OpCode.OP_MOD, OpCode.OP_POW,
                 OpCode.OP_DIV, OpCode.OP_IDIV, OpCode.OP_BAND, OpCode.OP_BOR, OpCode.OP_BXOR,
                 OpCode.OP_SHL, OpCode.OP_SHR -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                LuaValue valB = getLuaValue(pStack, tStack, oStack, base + b);
                LuaValue valC = getLuaValue(pStack, tStack, oStack, base + c);
                int culpritReg = b;
                if (msg.contains("has no integer representation")) {
                    if (hasIntegerRepresentation(valB)) {
                        culpritReg = c;
                    }
                } else if (valB.isNumber() || (valB instanceof LuaString ls && ls.isNumber())) {
                    culpritReg = c;
                }
                String[] info = getobjname(proto, faultPc, culpritReg);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_ADDK, OpCode.OP_SUBK, OpCode.OP_MULK, OpCode.OP_MODK, OpCode.OP_POWK,
                 OpCode.OP_DIVK, OpCode.OP_IDIVK -> {
                // K variants: B is a register, C indexes the constant table.
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                LuaValue valB = getLuaValue(pStack, tStack, oStack, base + b);
                LuaValue valC = (proto.constants != null && c < proto.constants.length) ? proto.constants[c] : LuaNil.NIL;
                int culpritReg = b;
                if (msg.contains("has no integer representation")) {
                    if (hasIntegerRepresentation(valB)) {
                        culpritReg = -1; // constant side
                    }
                } else if (valB.isNumber() || (valB instanceof LuaString ls && ls.isNumber())) {
                    culpritReg = -1; // constant side is the culprit
                }
                if (culpritReg >= 0) {
                    String[] info = getobjname(proto, faultPc, culpritReg);
                    if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
                }
            }
            case OpCode.OP_ADDI, OpCode.OP_SHLI, OpCode.OP_SHRI -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_UNM, OpCode.OP_BNOT, OpCode.OP_LEN -> {
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                String[] info = getobjname(proto, faultPc, b);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            case OpCode.OP_CONCAT -> {
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
                int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                int culpritReg = a;
                for (int i = 0; i < b; i++) {
                    LuaValue v = getLuaValue(pStack, tStack, oStack, base + a + i);
                    if (!v.isString() && !v.isNumber()) {
                        culpritReg = a + i;
                        break;
                    }
                }
                String[] info = getobjname(proto, faultPc, culpritReg);
                if (info != null && info[0] != null) desc = "(" + info[1] + " '" + info[0] + "')";
            }
            default -> {}
        }

        if (desc != null) {
            if (msg.startsWith("number has no integer representation")) {
                le.setMessage("number " + desc + " has no integer representation");
            } else {
                le.setMessage(msg + " " + desc);
            }
            le.setDecorated(true);
        }
    }

    private BytecodeVM() {}
}

package org.luava.runtime.bytecode;

import org.luava.runtime.*;
import org.luava.runtime.concurrency.LuaCoroutine;
import org.luava.runtime.eval.CallStack;
import org.luava.runtime.eval.Upvalue;

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
        if (val == null || val.isNil()) {
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
        state.ensureStackCapacity(256);
        ctx.pStack = state.getPrimitiveStack();
        ctx.tStack = state.getTypeStack();
        ctx.oStack = state.getObjectStack();

        ctx.callStack = new CallInfo[256];
        for (int i = 0; i < ctx.callStack.length; i++) ctx.callStack[i] = new CallInfo();
        ctx.callDepth = 0;

        ctx.savedStackTop = state.getStackTop();
        ctx.base = ctx.savedStackTop;
        ctx.top = ctx.base;
        ctx.pc = 0;

        ctx.closure = initialClosure;
        ctx.proto = ctx.closure.proto;
        ctx.code = ctx.proto.code;
        ctx.k = ctx.proto.constants;
        ctx.upvals = ctx.closure.upvals;

        state.ensureStackCapacity(ctx.base + ctx.proto.maxStackSize + 64);
        state.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
        ctx.pStack = state.getPrimitiveStack();
        ctx.tStack = state.getTypeStack();
        ctx.oStack = state.getObjectStack();

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
        CallStack.setNextVmFrame(state, ctx.base, ctx.base - 1, ctx.varargs, ctx.pc);
        CallStack.push(initialClosure, initialClosure.getName(), initialClosure.getLineDefined());
        ctx.oldpc = -1;
        ctx.varargPrepRan = false;
        ctx.thrown = null;
        // Hoisted: coroutine is constant for the whole execute() invocation
        // (resume continues the same thread/CURRENT; nested coroutines get
        // their own execute()). Saves a ThreadLocal lookup per instruction.
        return runLoop(state, ctx);
    }

    /**
     * The HotSpot-critical dispatch loop. Kept small enough for C2/OSR
     * (well under the 8 KB HugeMethodLimit); big opcode handlers live in
     * helpers that mutate {@code ctx} directly.
     */
    private static LuaValue[] runLoop(LuaState state, VmContext ctx) {
        LuaCoroutine co0 = LuaCoroutine.running();

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

                int inst = ctx.code[ctx.pc++];
                int op = (inst >>> Instruction.POS_OP) & Instruction.MASK_OP;
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;

                // Fast path: single predictable global check instead of
                // ThreadLocal + config lookups per instruction. HOOKS_ARMED is
                // biased to stay true (perf-only cost); hooks still verified
                // per-coroutine inside.
                if (LuaCoroutine.HOOKS_ARMED && co0 != null) {
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
                case OpCode.OP_GETTABUP -> executeGetTabUp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.upvals, ctx.k, ctx.base, a, inst);
                case OpCode.OP_GETTABLE -> executeGetTable(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_GETI -> executeGetI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                case OpCode.OP_GETFIELD -> executeGetField(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETTABUP -> executeSetTabUp(ctx.pStack, ctx.tStack, ctx.oStack, ctx.upvals, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETTABLE -> executeSetTable(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETI -> executeSetI(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_SETFIELD -> executeSetField(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
                case OpCode.OP_NEWTABLE -> ctx.pc = executeNewTable(ctx.code, ctx.pc, ctx.tStack, ctx.oStack, ctx.base, a);
                case OpCode.OP_SELF -> executeSelf(ctx.pStack, ctx.tStack, ctx.oStack, ctx.k, ctx.base, a, inst);
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
                    int sc = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
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
                    int sb = ((inst >>> Instruction.POS_B) & Instruction.MASK_B) - 128;
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
                case OpCode.OP_CALL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;

                    int funcIdx = ctx.base + a;
                    int nResults = c - 1;
                    LuaFunction func = resolveCallable(state, ctx, funcIdx, b > 0 ? b - 1 : (ctx.top - (funcIdx + 1)), a);
                    int nActualArgs = ctx.scratch0;

                    if (func instanceof LuaClosure childClosure) {
                        CallStack.Frame callerFrame = CallStack.topFrame();
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
                        CallStack.CallStackState csState = CallStack.currentState();
                        String callName = csState.nextName;
                        String callNamewhat = csState.nextNamewhat;
                        boolean isMeta = csState.nextMetamethod;
                        boolean isMethod = csState.nextMethod;
                        csState.nextName = null;
                        csState.nextNamewhat = null;
                        csState.nextMetamethod = false;
                        csState.nextMethod = false;
                        if (callName == null) {
                            String[] info = getobjname(ctx.proto, ctx.pc - 1, a);
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

                        state.ensureStackCapacity(ctx.base + ctx.proto.maxStackSize + 64);
                        state.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
                        ctx.pStack = state.getPrimitiveStack();
                        ctx.tStack = state.getTypeStack();
                        ctx.oStack = state.getObjectStack();

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
                        CallStack.setNextTransfer(1, childClosure.proto.numParams, null);
                        CallStack.setNextVmFrame(state, ctx.base, funcIdx, ctx.varargs, 0);
                        CallStack.push(childClosure, callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(), isMethod, isMeta);
                    } else if (func instanceof LuaFunction fn) {
                        int callLine = (ctx.proto.lineInfo != null && ctx.pc - 1 < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[ctx.pc - 1] : -1;
                        int newTop = executeExternalCall(state, ctx.proto, ctx.pc, ctx.base, fn, funcIdx, nActualArgs, nResults, callLine);
                        if (nResults < 0) {
                            ctx.top = newTop;
                        }
                        ctx.pStack = state.getPrimitiveStack();
                        ctx.tStack = state.getTypeStack();
                        ctx.oStack = state.getObjectStack();
                    }
                }
                case OpCode.OP_TAILCALL -> {
                    LuaValue[] tailResult = doTailCall(state, ctx, a, inst);
                    if (tailResult != null) {
                        return tailResult;
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    state.closeUpvalues(ctx.base);
                    state.closeTbc(ctx.base, null);
                    LuaValue[] retVals0 = new LuaValue[0];
                    stampReturnFrame(ctx, instPc, retVals0, 1, 0);
                    LuaValue[] r0 = returnToCaller(state, ctx, retVals0);
                    if (r0 != null) {
                        return r0;
                    }
                }
                case OpCode.OP_RETURN1 -> {
                    state.closeUpvalues(ctx.base);
                    state.closeTbc(ctx.base, null);
                    LuaValue ret = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a);
                    LuaValue[] retVals1 = new LuaValue[]{ret};
                    // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                    stampReturnFrame(ctx, instPc, retVals1, a + 1, 1);
                    LuaValue[] r1 = returnToCaller(state, ctx, retVals1);
                    if (r1 != null) {
                        return r1;
                    }
                }
                case OpCode.OP_RETURN -> {
                    state.closeUpvalues(ctx.base);
                    state.closeTbc(ctx.base, null);
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int nReturns = b > 0 ? b - 1 : (ctx.top - (ctx.base + a));
                    LuaValue[] retVals = new LuaValue[nReturns];
                    for (int i = 0; i < nReturns; i++) {
                        retVals[i] = getLuaValue(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + i);
                    }
                    // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                    stampReturnFrame(ctx, instPc, retVals, a + 1, nReturns);
                    LuaValue[] rN = returnToCaller(state, ctx, retVals);
                    if (rN != null) {
                        return rN;
                    }
                }
                case OpCode.OP_FORPREP -> doForPrep(ctx, a, inst);
                case OpCode.OP_FORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    int regInit = ctx.base + a;
                    if (ctx.tStack[regInit + 1] == TYPE_INT && ctx.pStack[regInit + 1] > 0) {
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
                    CallStack.Frame tforCaller = CallStack.topFrame();
                    if (tforCaller != null) {
                        tforCaller.pc = instPc;
                        if (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) {
                            tforCaller.line = ctx.proto.lineInfo[instPc];
                        }
                    }
                    executeTForCall(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
                }
                case OpCode.OP_TFORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    if (ctx.tStack[ctx.base + a + 4] != TYPE_NIL) {
                        copyReg(ctx.pStack, ctx.tStack, ctx.oStack, ctx.base + a + 2, ctx.base + a + 4);
                        ctx.pc -= bx;
                    }
                }
                case OpCode.OP_SETLIST -> ctx.pc = executeSetList(ctx.code, ctx.pc, inst, ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, ctx.top);
                case OpCode.OP_CLOSURE -> executeClosure(state, ctx.proto, ctx.closure, ctx.upvals, ctx.pStack, ctx.tStack, ctx.oStack, ctx.base, a, inst);
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
            CallStack.Frame faultFrame = CallStack.topFrame();
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
            CallStack.pop();
        }
        state.closeUpvalues(ctx.savedStackTop);
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
        state.setStackTop(ctx.savedStackTop);
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
        CallStack.Frame curFrame = CallStack.topFrame();
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
            LuaValue tm = mt != null ? mt.rawget(LuaString.valueOf("__call")) : null;
            if (tm != null && !tm.isNil()) {
                state.ensureStackCapacity(funcIdx + nArgs + 3);
                ctx.pStack = state.getPrimitiveStack();
                ctx.tStack = state.getTypeStack();
                ctx.oStack = state.getObjectStack();
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
     * {@code OP_TAILCALL} handler (largest single case, ~1.5 KB).
     * Mutates {@code ctx} directly; a non-null return value must be
     * returned from the dispatch loop immediately.
     */
    private static LuaValue[] doTailCall(LuaState state, VmContext ctx, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int funcIdx = ctx.base + a;
        LuaFunction func = resolveCallable(state, ctx, funcIdx, b > 0 ? b - 1 : (ctx.top - (funcIdx + 1)), a);
        int nActualArgs = ctx.scratch0;

        if (func instanceof LuaClosure childClosure) {
            state.closeUpvalues(ctx.base);
            CallStack.CallStackState csState = CallStack.currentState();
            String callName = csState.nextName;
            String callNamewhat = csState.nextNamewhat;
            boolean isMeta = csState.nextMetamethod;
            boolean isMethod = csState.nextMethod;
            csState.nextName = null;
            csState.nextNamewhat = null;
            csState.nextMetamethod = false;
            csState.nextMethod = false;
            if (callName == null) {
                String[] info = getobjname(ctx.proto, ctx.pc - 1, a);
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
            CallStack.setNextTransfer(1, childClosure.proto.numParams, null);
            CallStack.setNextVmFrame(state, ctx.base, ctx.base - 1, ctx.varargs, 0);
            CallStack.replaceTailCall(childClosure, callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(), isMethod, isMeta);
        } else if (func instanceof LuaFunction fn) {
            state.closeUpvalues(ctx.base);
            state.closeTbc(ctx.base, null);

            int callLine = (ctx.proto.lineInfo != null && ctx.pc - 1 < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[ctx.pc - 1] : -1;
            if (callLine > 0) CallStack.setLine(callLine);
            CallStack.Frame callerFrameExt = CallStack.topFrame();
            if (callerFrameExt != null) {
                callerFrameExt.pc = ctx.pc - 1;
                if (callLine > 0) callerFrameExt.line = callLine;
            }
            CallStack.CallStackState csState = CallStack.currentState();
            String resolvedName = csState.nextName;
            String namewhat = csState.nextNamewhat;
            boolean isMeta = csState.nextMetamethod;
            boolean isMethod = csState.nextMethod;
            csState.nextName = null;
            csState.nextNamewhat = null;
            csState.nextMetamethod = false;
            csState.nextMethod = false;
            if (resolvedName == null) {
                String[] info = getobjname(ctx.proto, ctx.pc - 1, a);
                if (info != null) {
                    resolvedName = info[0];
                    namewhat = info[1];
                    if ("method".equals(namewhat)) isMethod = true;
                } else {
                    resolvedName = fn.getName();
                    namewhat = "";
                }
            }
            LuaValue[] tailCArgs = getArgsForCall(ctx.pStack, ctx.tStack, ctx.oStack, funcIdx + 1, nActualArgs);
            CallStack.setNextTransfer(1, nActualArgs, tailCArgs);
            CallStack.setNextVmFrame(state, ctx.base, ctx.base - 1, null, -1);
            CallStack.replaceTailCall(fn, resolvedName, namewhat != null ? namewhat : "", callLine, isMethod, isMeta);
            int origTop = state.getStackTop();
            state.setStackTop(funcIdx + nActualArgs + 1);
            LuaValue res = null;
            try {
                res = fn.invoke(tailCArgs);
            } finally {
                state.setStackTop(origTop);
                CallStack.Frame f = CallStack.topFrame();
                if (f != null && res != null) {
                    LuaValue[] retVals = (res instanceof Varargs va) ? va.getValuesUnsafe() : new LuaValue[]{res};
                    f.retValues = retVals;
                    f.ftransfer = 1;
                    f.ntransfer = retVals.length;
                }
                CallStack.pop();
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
                state.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : nReturns) + 32);
                ctx.pStack = state.getPrimitiveStack();
                ctx.tStack = state.getTypeStack();
                ctx.oStack = state.getObjectStack();
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
        CallStack.Frame retFrame = CallStack.topFrame();
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
            CallStack.pop();
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
            state.setStackTop(ctx.base + ctx.proto.maxStackSize + 64);
            return null;
        }
        state.setStackTop(ctx.savedStackTop);
        return retVals;
    }

    /**
     * {@code OP_FORPREP} handler (runs once per loop, so call overhead is
     * free). Initializes the numeric loop counter or skips the loop.
     */
    private static void doForPrep(VmContext ctx, int a, int inst) {
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
            state.closeUpvalues(ctx.base + a);
            state.closeTbc(ctx.base + a, null);
        }
    }

    /**
     * Bare-thread fallback for the pc mirror (almost never taken; the
     * coroutine is normally always set).
     */
    private static void mirrorSlow(VmContext ctx, int instPc) {
        int curLineSlow = (ctx.proto.lineInfo != null && instPc < ctx.proto.lineInfo.length) ? ctx.proto.lineInfo[instPc] : -1;
        CallStack.Frame curFrameSlow = CallStack.topFrame();
        if (curFrameSlow != null) {
            curFrameSlow.pc = instPc;
            curFrameSlow.line = curLineSlow;
        }
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

    private static void executeClosure(LuaState state, LuaProto proto, LuaClosure closure, Upvalue[] upvals,
                                       long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
        LuaProto childProto = proto.protos[bx];
        Upvalue[] childUpvals = new Upvalue[childProto.upvalues.length];
        for (int i = 0; i < childProto.upvalues.length; i++) {
            UpvalueDesc desc = childProto.upvalues[i];
            if (desc.inStack) {
                childUpvals[i] = state.findOrCreateOpenUpvalue(base + desc.index, desc.name);
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

    private static void executeGetTable(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
        LuaValue key = getLuaValue(pStack, tStack, oStack, base + c);
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(key));
    }

    private static void executeGetI(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
        setLuaValue(pStack, tStack, oStack, base + a, tbl.get(LuaInteger.valueOf(c)));
    }

    private static void executeGetField(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue tbl = getLuaValue(pStack, tStack, oStack, base + b);
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
        tbl.set(LuaInteger.valueOf(b), val);
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
        LuaValue vb = getLuaValue(pStack, tStack, oStack, base + b);
        setLuaValue(pStack, tStack, oStack, base + a, vb.add(k[c]));
    }

    private static void executeSlowSub(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int regC) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        LuaValue vc = getLuaValue(pStack, tStack, oStack, regC);
        setLuaValue(pStack, tStack, oStack, regA, vb.sub(vc));
    }

    private static void executeSlowSubK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue vb = getLuaValue(pStack, tStack, oStack, base + b);
        setLuaValue(pStack, tStack, oStack, base + a, vb.sub(k[c]));
    }

    private static void executeSlowMul(long[] pStack, byte[] tStack, LuaValue[] oStack, int regA, int regB, int regC) {
        LuaValue vb = getLuaValue(pStack, tStack, oStack, regB);
        LuaValue vc = getLuaValue(pStack, tStack, oStack, regC);
        setLuaValue(pStack, tStack, oStack, regA, vb.mul(vc));
    }

    private static void executeSlowMulK(long[] pStack, byte[] tStack, LuaValue[] oStack, LuaValue[] k, int base, int a, int inst) {
        int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue vb = getLuaValue(pStack, tStack, oStack, base + b);
        setLuaValue(pStack, tStack, oStack, base + a, vb.mul(k[c]));
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
        if (p.constants != null && index >= 0 && index < p.constants.length) {
            LuaValue kv = p.constants[index];
            if (kv instanceof LuaString ls) {
                return ls.value();
            }
        }
        return "?";
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
                    name[0] = kname(p, bx);
                    return "constant";
                }
                case OpCode.OP_LOADKX -> {
                    if (pc + 1 < p.code.length) {
                        int nextInst = p.code[pc + 1];
                        int ax = (nextInst >>> Instruction.POS_Ax) & Instruction.MASK_Ax;
                        name[0] = kname(p, ax);
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

    private static int executeExternalCall(LuaState state, LuaProto proto, int pc, int base, LuaFunction fn, int funcIdx, int nActualArgs, int nResults, int curLine) {        long[] pStack = state.getPrimitiveStack();
        byte[] tStack = state.getTypeStack();
        LuaValue[] oStack = state.getObjectStack();
        LuaValue[] cArgs = getArgsForCall(pStack, tStack, oStack, funcIdx + 1, nActualArgs);
        if (curLine > 0) CallStack.setLine(curLine);
        CallStack.CallStackState csState = CallStack.currentState();
        String resolvedName = csState.nextName;
        String namewhat = csState.nextNamewhat;
        boolean isMeta = csState.nextMetamethod;
        boolean isMethod = csState.nextMethod;
        csState.nextName = null;
        csState.nextNamewhat = null;
        csState.nextMetamethod = false;
        csState.nextMethod = false;
        if (resolvedName == null) {
            // Introspect bytecode opcode to determine accurate namewhat ('global', 'local', 'field', 'method')
            String[] info = getobjname(proto, pc - 1, funcIdx - base);
            if (info != null) {
                resolvedName = info[0];
                namewhat = info[1];
                if ("method".equals(namewhat)) isMethod = true;
            } else {
                resolvedName = fn.getName();
                namewhat = "";
            }
        }
        CallStack.Frame callerFrame = CallStack.topFrame();
        if (callerFrame != null) {
            callerFrame.pc = pc - 1;
        }
        CallStack.setNextTransfer(1, nActualArgs, cArgs);
        CallStack.setNextVmFrame(state, funcIdx + 1, funcIdx, null, -1);
        CallStack.push(fn, resolvedName, namewhat, curLine, isMethod, isMeta);
        CallStack.Frame extFrame = CallStack.topFrame();
        if (extFrame != null) {
            extFrame.cArgs = cArgs;
        }
        int savedStackTop = state.getStackTop();
        // Lua 5.4: GC traverses stack up to top; restrict stack top to active arguments during external calls
        state.setStackTop(funcIdx + nActualArgs + 1);
        LuaValue res = null;
        try {
            res = fn.invoke(cArgs);
        } finally {
            state.setStackTop(savedStackTop);
            CallStack.Frame f = CallStack.topFrame();
            if (f != null && res != null) {
                LuaValue[] retVals = (res instanceof Varargs va) ? va.getValuesUnsafe() : new LuaValue[]{res};
                f.retValues = retVals;
                f.ftransfer = 1;
                f.ntransfer = retVals.length;
            }
            CallStack.pop();
        }
        state.ensureStackCapacity(funcIdx + (nResults > 0 ? nResults : 16) + 32);
        pStack = state.getPrimitiveStack();
        tStack = state.getTypeStack();
        oStack = state.getObjectStack();
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
                pStack = state.getPrimitiveStack();
                tStack = state.getTypeStack();
                oStack = state.getObjectStack();
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
                    throw new LuaException("bad 'for' limit (number expected, got NaN)");
                }
                if (d >= 9223372036854775808.0) {
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
        if (v instanceof LuaInteger li) return li.toLong();
        if (v instanceof LuaString ls) {
            LuaValue num = ls.toLuaNumber();
            if (num instanceof LuaInteger li) return li.toLong();
        }
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

    private static void executeTForCall(long[] pStack, byte[] tStack, LuaValue[] oStack, int base, int a, int inst) {
        int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
        LuaValue f = getLuaValue(pStack, tStack, oStack, base + a);
        LuaValue s = getLuaValue(pStack, tStack, oStack, base + a + 1);
        LuaValue var = getLuaValue(pStack, tStack, oStack, base + a + 2);
        // C Lua: iterator calls report name/namewhat "for iterator".
        // Lua closures consume this via the initial push; Java callables push
        // no frame, so clear afterwards (try-finally: also on throw) to avoid
        // leaking the name into unrelated later calls.
        org.luava.runtime.eval.CallStack.setNextCall("for iterator", "for iterator", false, false);
        LuaValue res;
        try {
            res = f.call(s, var);
        } finally {
            org.luava.runtime.eval.CallStack.clearNextCallIf("for iterator");
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

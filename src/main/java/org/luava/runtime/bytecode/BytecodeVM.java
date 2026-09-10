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
        state.ensureStackCapacity(256);
        long[] pStack = state.getPrimitiveStack();
        byte[] tStack = state.getTypeStack();
        LuaValue[] oStack = state.getObjectStack();

        CallInfo[] callStack = new CallInfo[256];
        for (int i = 0; i < callStack.length; i++) callStack[i] = new CallInfo();
        int callDepth = 0;

        int savedStackTop = state.getStackTop();
        int base = savedStackTop;
        int top = base;
        int pc = 0;

        LuaClosure closure = initialClosure;
        LuaProto proto = closure.proto;
        int[] code = proto.code;
        LuaValue[] k = proto.constants;
        Upvalue[] upvals = closure.upvals;

        state.ensureStackCapacity(base + proto.maxStackSize + 64);
        state.setStackTop(base + proto.maxStackSize + 64);
        pStack = state.getPrimitiveStack();
        tStack = state.getTypeStack();
        oStack = state.getObjectStack();

        int nArgs = initialArgs != null ? initialArgs.length : 0;
        for (int i = 0; i < proto.numParams; i++) {
            setLuaValue(pStack, tStack, oStack, base + i, (i < nArgs) ? initialArgs[i] : LuaNil.NIL);
        }
        top = base + proto.numParams;

        LuaValue[] varargs = null;
        if (proto.isVararg && nArgs > proto.numParams) {
            int nv = nArgs - proto.numParams;
            varargs = new LuaValue[nv];
            for (int i = 0; i < nv; i++) {
                varargs[i] = initialArgs[proto.numParams + i];
            }
        }

        int initialDepth = CallStack.depth();
        CallStack.setNextVmFrame(state, base, base - 1, varargs, pc);
        CallStack.push(initialClosure, initialClosure.getName(), initialClosure.getLineDefined());
        int oldpc = -1;
        boolean varargPrepRan = false;
        Throwable caughtException = null;

        try {
            while (true) {
                int instPc = pc;
                int curLine = (proto.lineInfo != null && instPc < proto.lineInfo.length) ? proto.lineInfo[instPc] : -1;
                CallStack.Frame curFrame = CallStack.topFrame();
                if (curFrame != null) {
                    curFrame.pc = instPc;
                    curFrame.line = curLine;
                }

                int inst = code[pc++];
                int op = (inst >>> Instruction.POS_OP) & Instruction.MASK_OP;
                int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;

                LuaCoroutine co = LuaCoroutine.running();
                if (co != null) {
                    LuaCoroutine.HookConfig hc = co.getHookConfig();
                    if (!hc.hook.isNil() && !hc.inHook) {
                        if (hc.count > 0) {
                            co.fireCountHook();
                        }
                        if (hc.hookLine) {
                            // Lua 5.4 semantics (lvm.c: OP_VARARGPREP & ldebug.c: luaG_traceexec):
                            // 1. OP_VARARGPREP is internal setup and never triggers the line hook.
                            // 2. Setting oldpc to 1 in Lua's OP_VARARGPREP guarantees next opcode triggers line hook.
                            // 3. Subsequent instructions trigger the hook on backward jumps (loops: instPc <= oldpc)
                            //    or on entering a new line (curLine != oldLine).
                            if (op != OpCode.OP_VARARGPREP) {
                                int oldLine = (proto.lineInfo != null && oldpc >= 0 && oldpc < proto.lineInfo.length)
                                        ? proto.lineInfo[oldpc] : -1;
                                if (varargPrepRan || oldpc < 0 || instPc <= oldpc || curLine != oldLine) {
                                    if (curLine > 0) {
                                        co.fireLineHookDirect(curLine, curFrame);
                                    }
                                    varargPrepRan = false;
                                }
                            } else {
                                varargPrepRan = true;
                            }
                        }
                    }
                }
                oldpc = instPc;

            switch (op) {
                case OpCode.OP_MOVE -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    copyReg(pStack, tStack, oStack, base + a, base + b);
                }
                case OpCode.OP_LOADI -> {
                    int sbx = ((inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx) - Instruction.OFFSET_sBx;
                    pStack[base + a] = sbx;
                    tStack[base + a] = TYPE_INT;
                    oStack[base + a] = null;
                }
                case OpCode.OP_LOADF -> {
                    int sbx = ((inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx) - Instruction.OFFSET_sBx;
                    pStack[base + a] = Double.doubleToRawLongBits(sbx);
                    tStack[base + a] = TYPE_FLOAT;
                    oStack[base + a] = null;
                }
                case OpCode.OP_LOADK -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    setLuaValue(pStack, tStack, oStack, base + a, k[bx]);
                }
                case OpCode.OP_LOADKX -> {
                    int nextInst = code[pc++];
                    int ax = (nextInst >>> Instruction.POS_Ax) & Instruction.MASK_Ax;
                    setLuaValue(pStack, tStack, oStack, base + a, k[ax]);
                }
                case OpCode.OP_LOADFALSE -> {
                    pStack[base + a] = 0;
                    tStack[base + a] = TYPE_BOOLEAN;
                    oStack[base + a] = null;
                }
                case OpCode.OP_LFALSESKIP -> {
                    pStack[base + a] = 0;
                    tStack[base + a] = TYPE_BOOLEAN;
                    oStack[base + a] = null;
                    pc++;
                }
                case OpCode.OP_LOADTRUE -> {
                    pStack[base + a] = 1;
                    tStack[base + a] = TYPE_BOOLEAN;
                    oStack[base + a] = null;
                }
                case OpCode.OP_LOADNIL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    for (int j = 0; j <= b; j++) {
                        tStack[base + a + j] = TYPE_NIL;
                        pStack[base + a + j] = 0;
                        oStack[base + a + j] = null;
                    }
                }
                case OpCode.OP_GETUPVAL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    setLuaValue(pStack, tStack, oStack, base + a, upvals[b].getValue());
                }
                case OpCode.OP_SETUPVAL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    upvals[b].setValue(getLuaValue(pStack, tStack, oStack, base + a));
                }
                case OpCode.OP_GETTABUP -> executeGetTabUp(pStack, tStack, oStack, upvals, k, base, a, inst);
                case OpCode.OP_GETTABLE -> executeGetTable(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_GETI -> executeGetI(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_GETFIELD -> executeGetField(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_SETTABUP -> executeSetTabUp(pStack, tStack, oStack, upvals, k, base, a, inst);
                case OpCode.OP_SETTABLE -> executeSetTable(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_SETI -> executeSetI(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_SETFIELD -> executeSetField(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_NEWTABLE -> pc = executeNewTable(code, pc, tStack, oStack, base, a);
                case OpCode.OP_SELF -> executeSelf(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_ADD -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = base + b;
                    int regC = base + c;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT && tStack[regC] == TYPE_INT) {
                        pStack[regA] = pStack[regB] + pStack[regC];
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else if (isNumber(tStack[regB]) && isNumber(tStack[regC])) {
                        double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
                        double dc = tStack[regC] == TYPE_INT ? pStack[regC] : Double.longBitsToDouble(pStack[regC]);
                        pStack[regA] = Double.doubleToRawLongBits(db + dc);
                        tStack[regA] = TYPE_FLOAT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else {
                        executeSlowAdd(pStack, tStack, oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_ADDI -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int sc = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = base + b;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT) {
                        pStack[regA] = pStack[regB] + sc;
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBINI) pc++;
                    } else if (tStack[regB] == TYPE_FLOAT) {
                        double db = Double.longBitsToDouble(pStack[regB]);
                        pStack[regA] = Double.doubleToRawLongBits(db + sc);
                        tStack[regA] = TYPE_FLOAT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBINI) pc++;
                    } else {
                        executeSlowAddI(pStack, tStack, oStack, regA, regB, sc);
                    }
                }
                case OpCode.OP_ADDK -> executeSlowAddK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_SUB -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = base + b;
                    int regC = base + c;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT && tStack[regC] == TYPE_INT) {
                        pStack[regA] = pStack[regB] - pStack[regC];
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else if (isNumber(tStack[regB]) && isNumber(tStack[regC])) {
                        double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
                        double dc = tStack[regC] == TYPE_INT ? pStack[regC] : Double.longBitsToDouble(pStack[regC]);
                        pStack[regA] = Double.doubleToRawLongBits(db - dc);
                        tStack[regA] = TYPE_FLOAT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else {
                        executeSlowSub(pStack, tStack, oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_SUBK -> executeSlowSubK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_MUL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int regB = base + b;
                    int regC = base + c;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT && tStack[regC] == TYPE_INT) {
                        pStack[regA] = pStack[regB] * pStack[regC];
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else if (isNumber(tStack[regB]) && isNumber(tStack[regC])) {
                        double db = tStack[regB] == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
                        double dc = tStack[regC] == TYPE_INT ? pStack[regC] : Double.longBitsToDouble(pStack[regC]);
                        pStack[regA] = Double.doubleToRawLongBits(db * dc);
                        tStack[regA] = TYPE_FLOAT;
                        oStack[regA] = null;
                        if (pc < code.length && ((code[pc] >>> Instruction.POS_OP) & Instruction.MASK_OP) == OpCode.OP_MMBIN) pc++;
                    } else {
                        executeSlowMul(pStack, tStack, oStack, regA, regB, regC);
                    }
                }
                case OpCode.OP_MULK -> executeSlowMulK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_DIV -> executeSlowDiv(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_DIVK -> executeSlowDivK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_IDIV -> executeSlowIDiv(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_IDIVK -> executeSlowIDivK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_MOD -> executeSlowMod(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_MODK -> executeSlowModK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_POW -> executeSlowPow(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_POWK -> executeSlowPowK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_BAND -> executeSlowBand(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_BANDK -> executeSlowBandK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_BOR -> executeSlowBor(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_BORK -> executeSlowBorK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_BXOR -> executeSlowBxor(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_BXORK -> executeSlowBxorK(pStack, tStack, oStack, k, base, a, inst);
                case OpCode.OP_SHL -> executeSlowShl(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_SHLI -> executeSlowShlI(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_SHR -> executeSlowShr(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_SHRI -> executeSlowShrI(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_UNM -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int regB = base + b;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT) {
                        pStack[regA] = -pStack[regB];
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                    } else if (tStack[regB] == TYPE_FLOAT) {
                        pStack[regA] = Double.doubleToRawLongBits(-Double.longBitsToDouble(pStack[regB]));
                        tStack[regA] = TYPE_FLOAT;
                        oStack[regA] = null;
                    } else {
                        setLuaValue(pStack, tStack, oStack, regA, getLuaValue(pStack, tStack, oStack, regB).unm());
                    }
                }
                case OpCode.OP_BNOT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int regB = base + b;
                    int regA = base + a;
                    if (tStack[regB] == TYPE_INT) {
                        pStack[regA] = ~pStack[regB];
                        tStack[regA] = TYPE_INT;
                        oStack[regA] = null;
                    } else {
                        setLuaValue(pStack, tStack, oStack, regA, getLuaValue(pStack, tStack, oStack, regB).bnot());
                    }
                }
                case OpCode.OP_NOT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    boolean truthy = isTruthy(pStack, tStack, base + b);
                    pStack[base + a] = truthy ? 0L : 1L;
                    tStack[base + a] = TYPE_BOOLEAN;
                    oStack[base + a] = null;
                }
                case OpCode.OP_LEN -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    setLuaValue(pStack, tStack, oStack, base + a, getLuaValue(pStack, tStack, oStack, base + b).len());
                }
                case OpCode.OP_CONCAT -> executeConcat(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_CLOSE -> {
                    state.closeUpvalues(base + a);
                    state.closeTbc(base + a, null);
                }
                case OpCode.OP_TBC -> {
                    LuaValue val = getLuaValue(pStack, tStack, oStack, base + a);
                    String varName = proto.findLocalVarName(a, pc - 1);
                    state.pushTbc(base + a, val, varName);
                }
                case OpCode.OP_JMP -> {
                    int sj = ((inst >>> Instruction.POS_sJ) & Instruction.MASK_sJ) - Instruction.OFFSET_sJ;
                    pc += sj;
                }
                case OpCode.OP_EQ -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = base + a;
                    int regB = base + b;
                    byte ta = tStack[regA];
                    byte tb = tStack[regB];
                    boolean cond;
                    if (ta == tb && ta == TYPE_INT) {
                        cond = (pStack[regA] == pStack[regB]);
                    } else if (ta == tb && ta == TYPE_BOOLEAN) {
                        cond = (pStack[regA] == pStack[regB]);
                    } else if (ta == TYPE_NIL && tb == TYPE_NIL) {
                        cond = true;
                    } else {
                        cond = getLuaValue(pStack, tStack, oStack, regA).luaEquals(getLuaValue(pStack, tStack, oStack, regB));
                    }
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_EQK -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean cond = getLuaValue(pStack, tStack, oStack, base + a).luaEquals(k[b]);
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_EQI -> {
                    int sb = ((inst >>> Instruction.POS_B) & Instruction.MASK_B) - 128;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = base + a;
                    boolean cond = (tStack[regA] == TYPE_INT && pStack[regA] == sb);
                    if (!cond && tStack[regA] != TYPE_INT) {
                        cond = getLuaValue(pStack, tStack, oStack, regA).luaEquals(LuaInteger.valueOf(sb));
                    }
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_LT -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = base + a;
                    int regB = base + b;
                    byte ta = tStack[regA];
                    byte tb = tStack[regB];
                    boolean cond;
                    if (ta == TYPE_INT && tb == TYPE_INT) {
                        cond = pStack[regA] < pStack[regB];
                    } else if (ta == TYPE_INT && tb == TYPE_FLOAT) {
                        cond = ltIntFloat(pStack[regA], Double.longBitsToDouble(pStack[regB]));
                    } else if (ta == TYPE_FLOAT && tb == TYPE_INT) {
                        cond = ltFloatInt(Double.longBitsToDouble(pStack[regA]), pStack[regB]);
                    } else if (ta == TYPE_FLOAT && tb == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(pStack[regA]) < Double.longBitsToDouble(pStack[regB]);
                    } else {
                        cond = getLuaValue(pStack, tStack, oStack, regA).luaLessThan(getLuaValue(pStack, tStack, oStack, regB));
                    }
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_LE -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    int regA = base + a;
                    int regB = base + b;
                    byte ta = tStack[regA];
                    byte tb = tStack[regB];
                    boolean cond;
                    if (ta == TYPE_INT && tb == TYPE_INT) {
                        cond = pStack[regA] <= pStack[regB];
                    } else if (ta == TYPE_INT && tb == TYPE_FLOAT) {
                        cond = leIntFloat(pStack[regA], Double.longBitsToDouble(pStack[regB]));
                    } else if (ta == TYPE_FLOAT && tb == TYPE_INT) {
                        cond = leFloatInt(Double.longBitsToDouble(pStack[regA]), pStack[regB]);
                    } else if (ta == TYPE_FLOAT && tb == TYPE_FLOAT) {
                        cond = Double.longBitsToDouble(pStack[regA]) <= Double.longBitsToDouble(pStack[regB]);
                    } else {
                        cond = getLuaValue(pStack, tStack, oStack, regA).luaLessOrEqual(getLuaValue(pStack, tStack, oStack, regB));
                    }
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_TEST -> {
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean truthy = isTruthy(pStack, tStack, base + a);
                    if (truthy != (flagK == 1)) pc++;
                    clearDeadTemp(pStack, tStack, oStack, proto, base, a, pc - 1);
                }
                case OpCode.OP_TESTSET -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean truthy = isTruthy(pStack, tStack, base + b);
                    if (truthy != (flagK == 1)) {
                        pc++;
                    } else {
                        copyReg(pStack, tStack, oStack, base + a, base + b);
                    }
                    clearDeadTemp(pStack, tStack, oStack, proto, base, b, pc - 1);
                }
                case OpCode.OP_CALL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;

                    int funcIdx = base + a;
                    LuaValue func = getLuaValue(pStack, tStack, oStack, funcIdx);
                    int nActualArgs = b > 0 ? b - 1 : (top - (funcIdx + 1));
                    int nResults = c - 1;

                    while (!(func instanceof LuaFunction)) {
                        LuaTable mt = func.getMetatable();
                        LuaValue tm = mt != null ? mt.rawget(LuaString.valueOf("__call")) : null;
                        if (tm != null && !tm.isNil()) {
                            state.ensureStackCapacity(funcIdx + nActualArgs + 3);
                            pStack = state.getPrimitiveStack();
                            tStack = state.getTypeStack();
                            oStack = state.getObjectStack();
                            System.arraycopy(pStack, funcIdx, pStack, funcIdx + 1, nActualArgs + 1);
                            System.arraycopy(tStack, funcIdx, tStack, funcIdx + 1, nActualArgs + 1);
                            System.arraycopy(oStack, funcIdx, oStack, funcIdx + 1, nActualArgs + 1);
                            setLuaValue(pStack, tStack, oStack, funcIdx, tm);
                            nActualArgs++;
                            func = tm;
                        } else {
                            String[] info = getobjname(proto, pc - 1, a);
                            String extra = (info != null && info[0] != null) ? " (" + info[1] + " '" + info[0] + "')" : "";
                            throw new LuaException("attempt to call a " + func.typeName() + " value" + extra);
                        }
                    }

                    if (func instanceof LuaClosure childClosure) {
                        CallStack.Frame callerFrame = CallStack.topFrame();
                        if (callerFrame != null) {
                            callerFrame.pc = pc - 1;
                        }
                        if (callDepth >= callStack.length) {
                            callStack = expandCallStack(callStack);
                        }
                        CallInfo ci = callStack[callDepth++];
                        ci.init(closure, funcIdx, base, top, pc, nResults);
                        ci.varargs = varargs;
                        ci.oldpc = oldpc;
                        ci.varargPrepRan = varargPrepRan;
                        oldpc = -1;
                        varargPrepRan = false;
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
                            String[] info = getobjname(proto, pc - 1, a);
                            if (info != null) {
                                callName = info[0];
                                callNamewhat = info[1];
                                if ("method".equals(callNamewhat)) isMethod = true;
                            } else {
                                callName = childClosure.getName();
                                callNamewhat = "";
                            }
                        }

                        base = funcIdx + 1;
                        closure = childClosure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = 0;

                        state.ensureStackCapacity(base + proto.maxStackSize + 64);
                        state.setStackTop(base + proto.maxStackSize + 64);
                        pStack = state.getPrimitiveStack();
                        tStack = state.getTypeStack();
                        oStack = state.getObjectStack();

                        if (proto.isVararg && nActualArgs > proto.numParams) {
                            int nv = nActualArgs - proto.numParams;
                            varargs = new LuaValue[nv];
                            for (int i = 0; i < nv; i++) {
                                varargs[i] = getLuaValue(pStack, tStack, oStack, base + proto.numParams + i);
                            }
                        } else {
                            varargs = null;
                        }

                        for (int i = nActualArgs; i < proto.numParams; i++) {
                            setLuaValue(pStack, tStack, oStack, base + i, LuaNil.NIL);
                        }
                        top = base + proto.numParams;

                        // Lua 5.4 semantics: call hook runs after stack frame and arguments are established
                        CallStack.setNextTransfer(1, childClosure.proto.numParams, null);
                        CallStack.setNextVmFrame(state, base, funcIdx, varargs, 0);
                        CallStack.push(childClosure, callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(), isMethod, isMeta);
                    } else if (func instanceof LuaFunction fn) {
                        int callLine = (proto.lineInfo != null && pc - 1 < proto.lineInfo.length) ? proto.lineInfo[pc - 1] : -1;
                        int newTop = executeExternalCall(state, proto, pc, base, fn, funcIdx, nActualArgs, nResults, callLine);
                        if (nResults < 0) {
                            top = newTop;
                        }
                        pStack = state.getPrimitiveStack();
                        tStack = state.getTypeStack();
                        oStack = state.getObjectStack();
                    }
                }
                case OpCode.OP_TAILCALL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int funcIdx = base + a;
                    LuaValue func = getLuaValue(pStack, tStack, oStack, funcIdx);
                    int nActualArgs = b > 0 ? b - 1 : (top - (funcIdx + 1));

                    while (!(func instanceof LuaFunction)) {
                        LuaTable mt = func.getMetatable();
                        LuaValue tm = mt != null ? mt.rawget(LuaString.valueOf("__call")) : null;
                        if (tm != null && !tm.isNil()) {
                            state.ensureStackCapacity(funcIdx + nActualArgs + 3);
                            pStack = state.getPrimitiveStack();
                            tStack = state.getTypeStack();
                            oStack = state.getObjectStack();
                            System.arraycopy(pStack, funcIdx, pStack, funcIdx + 1, nActualArgs + 1);
                            System.arraycopy(tStack, funcIdx, tStack, funcIdx + 1, nActualArgs + 1);
                            System.arraycopy(oStack, funcIdx, oStack, funcIdx + 1, nActualArgs + 1);
                            setLuaValue(pStack, tStack, oStack, funcIdx, tm);
                            nActualArgs++;
                            func = tm;
                        } else {
                            String[] info = getobjname(proto, pc - 1, a);
                            String extra = (info != null && info[0] != null) ? " (" + info[1] + " '" + info[0] + "')" : "";
                            throw new LuaException("attempt to call a " + func.typeName() + " value" + extra);
                        }
                    }

                    if (func instanceof LuaClosure childClosure) {
                        state.closeUpvalues(base);
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
                            String[] info = getobjname(proto, pc - 1, a);
                            if (info != null) {
                                callName = info[0];
                                callNamewhat = info[1];
                                if ("method".equals(callNamewhat)) isMethod = true;
                            } else {
                                callName = childClosure.getName();
                                callNamewhat = "";
                            }
                        }
                        int oldTop = top;
                        System.arraycopy(pStack, funcIdx + 1, pStack, base, nActualArgs);
                        System.arraycopy(tStack, funcIdx + 1, tStack, base, nActualArgs);
                        System.arraycopy(oStack, funcIdx + 1, oStack, base, nActualArgs);
                        if (oldTop > base + nActualArgs) {
                            java.util.Arrays.fill(oStack, base + nActualArgs, oldTop, null);
                        }
                        closure = childClosure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = 0;
                        oldpc = -1;
                        varargPrepRan = false;

                        if (proto.isVararg && nActualArgs > proto.numParams) {
                            int nv = nActualArgs - proto.numParams;
                            varargs = new LuaValue[nv];
                            for (int i = 0; i < nv; i++) {
                                varargs[i] = getLuaValue(pStack, tStack, oStack, base + proto.numParams + i);
                            }
                        } else {
                            varargs = null;
                        }

                        for (int i = nActualArgs; i < proto.numParams; i++) {
                            setLuaValue(pStack, tStack, oStack, base + i, LuaNil.NIL);
                        }
                        top = base + proto.numParams;

                        // Lua 5.4 semantics: tail call replaces frame without firing return hook, fires tailcall hook
                        CallStack.setNextTransfer(1, childClosure.proto.numParams, null);
                        CallStack.setNextVmFrame(state, base, base - 1, varargs, 0);
                        CallStack.replaceTailCall(childClosure, callName, callNamewhat != null ? callNamewhat : "", childClosure.getLineDefined(), isMethod, isMeta);
                    } else if (func instanceof LuaFunction fn) {
                        state.closeUpvalues(base);
                        state.closeTbc(base, null);

                        int callLine = (proto.lineInfo != null && pc - 1 < proto.lineInfo.length) ? proto.lineInfo[pc - 1] : -1;
                        if (callLine > 0) CallStack.setLine(callLine);
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
                            String[] info = getobjname(proto, pc - 1, a);
                            if (info != null) {
                                resolvedName = info[0];
                                namewhat = info[1];
                                if ("method".equals(namewhat)) isMethod = true;
                            } else {
                                resolvedName = fn.getName();
                                namewhat = "";
                            }
                        }
                        LuaValue[] tailCArgs = getArgsForCall(pStack, tStack, oStack, funcIdx + 1, nActualArgs);
                        CallStack.setNextTransfer(1, nActualArgs, tailCArgs);
                        CallStack.setNextVmFrame(state, base, base - 1, null, -1);
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
                        if (callDepth > 0) {
                            CallInfo ci = callStack[--callDepth];
                            int callerFunc = ci.funcIndex;
                            base = ci.baseIndex;
                            closure = ci.closure;
                            proto = closure.proto;
                            code = proto.code;
                            k = proto.constants;
                            upvals = closure.upvals;
                            pc = ci.savedPc;
                            varargs = ci.varargs;
                            oldpc = ci.oldpc;
                            varargPrepRan = ci.varargPrepRan;
                            state.ensureStackCapacity(callerFunc + (ci.expectedResults > 0 ? ci.expectedResults : nReturns) + 32);
                            pStack = state.getPrimitiveStack();
                            tStack = state.getTypeStack();
                            oStack = state.getObjectStack();
                            if (ci.expectedResults > 0) {
                                for (int i = 0; i < ci.expectedResults; i++) {
                                    setLuaValue(pStack, tStack, oStack, callerFunc + i, (i < nReturns) ? retVals[i] : LuaNil.NIL);
                                }
                            } else if (ci.expectedResults < 0) {
                                for (int i = 0; i < nReturns; i++) {
                                    setLuaValue(pStack, tStack, oStack, callerFunc + i, retVals[i]);
                                }
                                top = callerFunc + nReturns;
                            }
                        } else {
                            return retVals;
                        }
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    state.closeUpvalues(base);
                    state.closeTbc(base, null);
                    CallStack.Frame retFrame = CallStack.topFrame();
                    if (retFrame != null) {
                        retFrame.retValues = new LuaValue[0];
                        retFrame.ftransfer = 1;
                        retFrame.ntransfer = 0;
                    }
                    if (callDepth > 0) {
                        CallStack.pop();
                        CallInfo ci = callStack[--callDepth];
                        int callerFunc = ci.funcIndex;
                        base = ci.baseIndex;
                        closure = ci.closure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = ci.savedPc;
                        varargs = ci.varargs;
                        oldpc = ci.oldpc;
                        varargPrepRan = ci.varargPrepRan;
                        if (ci.expectedResults > 0) {
                            for (int i = 0; i < ci.expectedResults; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, LuaNil.NIL);
                            }
                        } else if (ci.expectedResults < 0) {
                            top = callerFunc;
                        }
                        state.setStackTop(base + proto.maxStackSize + 64);
                    } else {
                        state.setStackTop(savedStackTop);
                        return new LuaValue[0];
                    }
                }
                case OpCode.OP_RETURN1 -> {
                    state.closeUpvalues(base);
                    state.closeTbc(base, null);
                    LuaValue ret = getLuaValue(pStack, tStack, oStack, base + a);
                    CallStack.Frame retFrame = CallStack.topFrame();
                    if (retFrame != null) {
                        retFrame.retValues = new LuaValue[]{ret};
                        // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                        retFrame.ftransfer = a + 1;
                        retFrame.ntransfer = 1;
                    }
                    if (callDepth > 0) {
                        CallStack.pop();
                        CallInfo ci = callStack[--callDepth];
                        int callerFunc = ci.funcIndex;
                        base = ci.baseIndex;
                        closure = ci.closure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = ci.savedPc;
                        varargs = ci.varargs;
                        oldpc = ci.oldpc;
                        varargPrepRan = ci.varargPrepRan;
                        if (ci.expectedResults > 0) {
                            setLuaValue(pStack, tStack, oStack, callerFunc, ret);
                            for (int i = 1; i < ci.expectedResults; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, LuaNil.NIL);
                            }
                        } else if (ci.expectedResults < 0) {
                            setLuaValue(pStack, tStack, oStack, callerFunc, ret);
                            top = callerFunc + 1;
                        }
                        state.setStackTop(base + proto.maxStackSize + 64);
                    } else {
                        state.setStackTop(savedStackTop);
                        return new LuaValue[]{ret};
                    }
                }
                case OpCode.OP_RETURN -> {
                    state.closeUpvalues(base);
                    state.closeTbc(base, null);
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int nReturns = b > 0 ? b - 1 : (top - (base + a));
                    LuaValue[] retVals = new LuaValue[nReturns];
                    for (int i = 0; i < nReturns; i++) {
                        retVals[i] = getLuaValue(pStack, tStack, oStack, base + a + i);
                    }
                    CallStack.Frame retFrame = CallStack.topFrame();
                    if (retFrame != null) {
                        retFrame.retValues = retVals;
                        // Lua 5.4 semantics (ldo.c: rethook): ftransfer is offset of first result relative to func (1-based)
                        retFrame.ftransfer = a + 1;
                        retFrame.ntransfer = nReturns;
                    }
                    if (callDepth > 0) {
                        CallStack.pop();
                        CallInfo ci = callStack[--callDepth];
                        int callerFunc = ci.funcIndex;
                        base = ci.baseIndex;
                        closure = ci.closure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = ci.savedPc;
                        varargs = ci.varargs;
                        oldpc = ci.oldpc;
                        varargPrepRan = ci.varargPrepRan;
                        if (ci.expectedResults > 0) {
                            for (int i = 0; i < ci.expectedResults; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, (i < nReturns) ? retVals[i] : LuaNil.NIL);
                            }
                        } else if (ci.expectedResults < 0) {
                            for (int i = 0; i < nReturns; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, retVals[i]);
                            }
                            top = callerFunc + nReturns;
                        }
                        state.setStackTop(base + proto.maxStackSize + 64);
                    } else {
                        state.setStackTop(savedStackTop);
                        return retVals;
                    }
                }
                case OpCode.OP_FORPREP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    int regInit = base + a;
                    if (tStack[regInit] == TYPE_INT && tStack[regInit + 1] == TYPE_INT && tStack[regInit + 2] == TYPE_INT) {
                        long init = pStack[regInit];
                        long limit = pStack[regInit + 1];
                        long step = pStack[regInit + 2];
                        if (step == 0) throw new LuaException("'for' step is zero");
                        pStack[regInit + 3] = init;
                        tStack[regInit + 3] = TYPE_INT;
                        oStack[regInit + 3] = null;
                        boolean skip = step > 0 ? init > limit : init < limit;
                        if (skip) {
                            pc += bx;
                        } else {
                            long count = step > 0 ? Long.divideUnsigned(limit - init, step)
                                    : Long.divideUnsigned(init - limit, -step);
                            pStack[regInit + 1] = count;
                        }
                    } else {
                        pc = executeForPrepSlow(pStack, tStack, oStack, base, a, bx, pc);
                    }
                }
                case OpCode.OP_FORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    int regInit = base + a;
                    if (tStack[regInit + 1] == TYPE_INT && pStack[regInit + 1] > 0) {
                        pStack[regInit + 1]--;
                        long next = pStack[regInit] + pStack[regInit + 2];
                        pStack[regInit] = next;
                        pStack[regInit + 3] = next;
                        pc -= bx;
                    } else if (tStack[regInit + 1] == TYPE_FLOAT) {
                        double step = Double.longBitsToDouble(pStack[regInit + 2]);
                        double limit = Double.longBitsToDouble(pStack[regInit + 1]);
                        double idx = Double.longBitsToDouble(pStack[regInit]);
                        idx += step;
                        if (step > 0 ? idx <= limit : limit <= idx) {
                            pStack[regInit] = Double.doubleToRawLongBits(idx);
                            pStack[regInit + 3] = Double.doubleToRawLongBits(idx);
                            pc -= bx;
                        }
                    }
                }
                case OpCode.OP_TFORPREP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    LuaValue val = getLuaValue(pStack, tStack, oStack, base + a + 3);
                    state.pushTbc(base + a + 3, val, "(for state)");
                    pc += bx;
                }
                case OpCode.OP_TFORCALL -> executeTForCall(pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_TFORLOOP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
                    if (tStack[base + a + 4] != TYPE_NIL) {
                        copyReg(pStack, tStack, oStack, base + a + 2, base + a + 4);
                        pc -= bx;
                    }
                }
                case OpCode.OP_SETLIST -> pc = executeSetList(code, pc, inst, pStack, tStack, oStack, base, a, top);
                case OpCode.OP_CLOSURE -> executeClosure(state, proto, closure, upvals, pStack, tStack, oStack, base, a, inst);
                case OpCode.OP_VARARG -> {
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;
                    int vLen = varargs != null ? varargs.length : 0;
                    if (c > 1) {
                        for (int i = 0; i < c - 1; i++) {
                            setLuaValue(pStack, tStack, oStack, base + a + i, i < vLen ? varargs[i] : LuaNil.NIL);
                        }
                    } else if (c == 0) {
                        for (int i = 0; i < vLen; i++) {
                            setLuaValue(pStack, tStack, oStack, base + a + i, varargs[i]);
                        }
                        top = base + a + vLen;
                    }
                }
                case OpCode.OP_VARARGPREP -> {
                    // Pre-aligned in call setup
                }
                case OpCode.OP_EXTRAARG -> {}
                default -> throw new LuaException("unimplemented opcode: " + OpCode.getOpName(op));
            }
        }
        } catch (org.luava.runtime.eval.LuaUnwindException ue) {
            caughtException = ue;
            throw ue;
        } catch (LuaException le) {
            int faultPc = (pc > 0) ? pc - 1 : 0;
            if (!le.isDecorated()) {
                attachBytecodeDesc(le, proto, faultPc, pStack, tStack, oStack, base);
                int curLine = (proto.lineInfo != null && proto.lineInfo.length > 0 && faultPc < proto.lineInfo.length) ? proto.lineInfo[faultPc] : -1;
                String msg = le.getMessage();
                if (msg != null) {
                    String source = proto.source;
                    if (source == null || source.isEmpty() || "=?".equals(source) || curLine <= 0) {
                        source = (source == null || "=?".equals(source)) ? "=?" : source;
                        if ("=?".equals(source)) curLine = -1;
                    }
                    String formattedSource = org.luava.frontend.parser.ParseException.formatChunkName(source);
                    le.setMessage(formattedSource + ":" + curLine + ": " + msg);
                }
                le.setDecorated(true);
            }
            if (CallStack.canHandleError()) {
                LuaValue res = CallStack.runErrorHandler(le.getErrorObject());
                caughtException = le;
                throw new org.luava.runtime.eval.LuaUnwindException(res, le.getErrorObject());
            }
            caughtException = le;
            throw le;
        } catch (Throwable t) {
            caughtException = t;
            throw t;
        } finally {
            while (CallStack.depth() > initialDepth) {
                CallStack.pop();
            }
            state.closeUpvalues(savedStackTop);
            LuaValue errVal = null;
            if (caughtException != null) {
                if (caughtException instanceof org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal) {
                    errVal = null;
                } else if (caughtException instanceof org.luava.runtime.eval.LuaUnwindException ue) {
                    errVal = ue.getOriginalError();
                } else if (caughtException instanceof LuaException le && le.getErrorObject() != null) {
                    errVal = le.getErrorObject();
                } else {
                    String msg = caughtException.getMessage() != null ? caughtException.getMessage() : caughtException.toString();
                    errVal = LuaString.valueOf(msg);
                }
            }
            state.closeTbc(savedStackTop, errVal);
            state.setStackTop(savedStackTop);
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
        oStack[regA] = new LuaClosure(childProto, childUpvals, closure.env, state);
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
            OpCode.OP_FORLOOP, OpCode.OP_FORPREP, OpCode.OP_TFORLOOP, OpCode.OP_CLOSURE, OpCode.OP_VARARG
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

    private static int executeExternalCall(LuaState state, LuaProto proto, int pc, int base, LuaFunction fn, int funcIdx, int nActualArgs, int nResults, int curLine) {
        long[] pStack = state.getPrimitiveStack();
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
        LuaValue res = f.call(s, var);
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

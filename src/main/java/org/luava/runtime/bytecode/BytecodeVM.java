package org.luava.runtime.bytecode;

import org.luava.runtime.*;
import org.luava.runtime.concurrency.LuaCoroutine;
import org.luava.runtime.eval.Upvalue;

public final class BytecodeVM {
    public static final byte TYPE_NIL = 0;
    public static final byte TYPE_BOOLEAN = 1;
    public static final byte TYPE_INT = 2;
    public static final byte TYPE_FLOAT = 3;
    public static final byte TYPE_OBJECT = 4;

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

        int base = 0;
        int top = 0;
        int pc = 0;

        LuaClosure closure = initialClosure;
        LuaProto proto = closure.proto;
        int[] code = proto.code;
        LuaValue[] k = proto.constants;
        Upvalue[] upvals = closure.upvals;

        state.ensureStackCapacity(base + proto.maxStackSize + 32);
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

        while (true) {
            int inst = code[pc++];
            int op = (inst >>> Instruction.POS_OP) & Instruction.MASK_OP;
            int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;

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
                    } else if (tStack[regB] >= TYPE_INT && tStack[regC] >= TYPE_INT) {
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
                    } else if (tStack[regB] >= TYPE_INT && tStack[regC] >= TYPE_INT) {
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
                    } else if (tStack[regB] >= TYPE_INT && tStack[regC] >= TYPE_INT) {
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
                    String varName = (proto.locVars != null && a < proto.locVars.length) ? proto.locVars[a] : null;
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
                    } else if (ta >= TYPE_INT && tb >= TYPE_INT) {
                        double da = ta == TYPE_INT ? pStack[regA] : Double.longBitsToDouble(pStack[regA]);
                        double db = tb == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
                        cond = da < db;
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
                    } else if (ta >= TYPE_INT && tb >= TYPE_INT) {
                        double da = ta == TYPE_INT ? pStack[regA] : Double.longBitsToDouble(pStack[regA]);
                        double db = tb == TYPE_INT ? pStack[regB] : Double.longBitsToDouble(pStack[regB]);
                        cond = da <= db;
                    } else {
                        cond = getLuaValue(pStack, tStack, oStack, regA).luaLessOrEqual(getLuaValue(pStack, tStack, oStack, regB));
                    }
                    if (cond != (flagK == 1)) pc++;
                }
                case OpCode.OP_TEST -> {
                    int flagK = (inst >>> Instruction.POS_k) & Instruction.MASK_k;
                    boolean truthy = isTruthy(pStack, tStack, base + a);
                    if (truthy != (flagK == 1)) pc++;
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
                }
                case OpCode.OP_CALL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;

                    int funcIdx = base + a;
                    LuaValue func = getLuaValue(pStack, tStack, oStack, funcIdx);
                    int nActualArgs = b > 0 ? b - 1 : (top - (funcIdx + 1));
                    int nResults = c - 1;

                    if (func instanceof LuaClosure childClosure) {
                        if (callDepth >= callStack.length) {
                            callStack = expandCallStack(callStack);
                        }
                        CallInfo ci = callStack[callDepth++];
                        ci.init(closure, funcIdx, base, top, pc, nResults);
                        ci.varargs = varargs;

                        base = funcIdx + 1;
                        closure = childClosure;
                        proto = closure.proto;
                        code = proto.code;
                        k = proto.constants;
                        upvals = closure.upvals;
                        pc = 0;

                        state.ensureStackCapacity(base + proto.maxStackSize + 32);
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
                    } else if (func instanceof LuaFunction fn) {
                        int newTop = executeExternalCall(state, fn, funcIdx, nActualArgs, nResults);
                        if (nResults < 0) {
                            top = newTop;
                        }
                        pStack = state.getPrimitiveStack();
                        tStack = state.getTypeStack();
                        oStack = state.getObjectStack();
                    } else {
                        throw new LuaException("attempt to call a " + func.type().name().toLowerCase() + " value");
                    }
                }
                case OpCode.OP_TAILCALL -> {
                    int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
                    int funcIdx = base + a;
                    LuaValue func = getLuaValue(pStack, tStack, oStack, funcIdx);
                    int nActualArgs = b > 0 ? b - 1 : (top - (funcIdx + 1));

                    if (func instanceof LuaClosure childClosure) {
                        state.closeUpvalues(base);
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
                    } else if (func instanceof LuaFunction fn) {
                        state.closeUpvalues(base);
                        state.closeTbc(base, null);
                        LuaValue res = fn.invoke(getArgsForCall(pStack, tStack, oStack, funcIdx + 1, nActualArgs));
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
                        if (ci.expectedResults > 0) {
                            for (int i = 0; i < ci.expectedResults; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, LuaNil.NIL);
                            }
                        }
                    } else {
                        return new LuaValue[0];
                    }
                }
                case OpCode.OP_RETURN1 -> {
                    state.closeUpvalues(base);
                    state.closeTbc(base, null);
                    LuaValue ret = getLuaValue(pStack, tStack, oStack, base + a);
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
                        if (ci.expectedResults > 0) {
                            setLuaValue(pStack, tStack, oStack, callerFunc, ret);
                            for (int i = 1; i < ci.expectedResults; i++) {
                                setLuaValue(pStack, tStack, oStack, callerFunc + i, LuaNil.NIL);
                            }
                        } else if (ci.expectedResults < 0) {
                            setLuaValue(pStack, tStack, oStack, callerFunc, ret);
                            top = callerFunc + 1;
                        }
                    } else {
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
                            long count = step > 0 ? (limit - init) / step : (init - limit) / (-step);
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
                    }
                }
                case OpCode.OP_TFORPREP -> {
                    int bx = (inst >>> Instruction.POS_Bx) & Instruction.MASK_Bx;
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
        setLuaValue(pStack, tStack, oStack, base + a + 1, tbl);
        LuaValue key = flagK == 1 ? k[c] : getLuaValue(pStack, tStack, oStack, base + c);
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

    private static int executeExternalCall(LuaState state, LuaFunction fn, int funcIdx, int nActualArgs, int nResults) {
        long[] pStack = state.getPrimitiveStack();
        byte[] tStack = state.getTypeStack();
        LuaValue[] oStack = state.getObjectStack();
        LuaValue[] cArgs = getArgsForCall(pStack, tStack, oStack, funcIdx + 1, nActualArgs);
        LuaValue res = fn.invoke(cArgs);
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
            } else if (res != null && !res.isNil()) {
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

        if (init instanceof LuaInteger ii && limit instanceof LuaInteger il && step instanceof LuaInteger is) {
            long initVal = ii.toLong();
            long limitVal = il.toLong();
            long stepVal = is.toLong();
            if (stepVal == 0) throw new LuaException("'for' step is zero");
            setLuaValue(pStack, tStack, oStack, regInit + 3, init);
            boolean skip = stepVal > 0 ? initVal > limitVal : initVal < limitVal;
            if (skip) {
                return pc + bx;
            } else {
                long count = stepVal > 0 ? (limitVal - initVal) / stepVal : (initVal - limitVal) / (-stepVal);
                setLuaValue(pStack, tStack, oStack, regInit + 1, LuaInteger.valueOf(count));
                return pc;
            }
        }
        return pc;
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

    private BytecodeVM() {}
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.Instruction;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.bytecode.OpCode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Translates one integer-specialized Lua proto into a JVM static method.
 *
 * <p>Emitted method:
 * {@code static long exec(LuaClosure self, Object[] up, long[] p, byte[] t,
 * LuaValue[] o, int base)}.
 * Registers live in the shared triple stack at {@code base + reg}; results
 * flow as unboxed {@code long}. Every numeric read is guarded by a tag
 * check that throws {@link DeoptSignal} on mismatch, so the interpreter can
 * re-run the call. Only side-effect-free protos are accepted (no table /
 * global / upvalue writes, no closure creation), which makes deopt-restart
 * semantically safe. Self-recursive {@code CALL} becomes a direct
 * {@code INVOKESTATIC}.
 */
public final class LuaToJvmTranslator implements Opcodes {
    private static final String EXEC_NAME = "exec";
    private static final String EXEC_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J";
    private static final int TYPE_INT = 2;
    private static final int TYPE_OBJECT = 4;
    private static final int TYPE_FLOAT = 3;
    private static final int TYPE_NIL = 0;
    private static final int TYPE_BOOLEAN = 1;

    private LuaToJvmTranslator() {}

    private static final String EXEC_INNER_NAME = "execInner";
    private static final String EXEC_OBJ_NAME = "execObj";
    private static final String EXEC_OBJ_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;";

    /** Result of a successful translation. */
    public record Translation(String internalName, byte[] bytes) {}

    /** Descriptor of the prologue-free recursive entry (verified closures). */
    static String innerDesc(int fusedBCount) {
        StringBuilder sb = new StringBuilder(
                "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I");
        for (int i = 0; i < fusedBCount; i++) {
            sb.append("Lorg/luava/runtime/bytecode/LuaClosure;");
        }
        sb.append(")J");
        return sb.toString();
    }

    private static Label[] newLabels(int len) {
        Label[] labels = new Label[len];
        for (int i = 0; i < len; i++) {
            labels[i] = new Label();
        }
        return labels;
    }

    /** Returns null when the proto is outside the integer-subset. */
    public static Translation translate(LuaProto proto, String internalName) {
        Info info = analyze(proto);
        if (info == null) {
            return null;
        }
        boolean pure = info.pure();
        int len = proto.code.length;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, "java/lang/Object", null);

        // Locals: 0=self 1=up 2=p 3=t 4=o 5=base 6-7=long scratch 8=ref scratch
        // 9+ = hoisted fused-callee flags (int) and closures.
        // Fuse map: callPc -> upvalue index when the CALL's function register
        // is provably (straight-line, no intervening read) the value of one
        // GETUPVAL. The fused GETUPVAL emits no store; the CALL reads the
        // upvalue directly. Safe under deopt-restart (pure subset recomputes).
        int[] fusedUp = new int[len];
        for (int i = 0; i < len; i++) {
            fusedUp[i] = -1;
        }
        // Fusion hoists callee closures into locals 9+, which collide with
        // the table-write scratch (local 9) and is pointless for impure
        // protos: their every call deopts instead of entering a callee.
        if (pure) {
            for (int pc = 0; pc < len; pc++) {
                int inst = proto.code[pc];
                int op = Instruction.getOp(inst);
                if (op == OpCode.OP_CALL || op == OpCode.OP_TAILCALL) {
                    fusedUp[pc] = fusedUpvalue(proto.code, pc, Instruction.getA(inst));
                }
            }
        }
        boolean[] skipStore = new boolean[len];
        int[] fusedDef = new int[len];
        for (int pc = 0; pc < len; pc++) {
            fusedDef[pc] = -1;
            if (fusedUp[pc] >= 0) {
                int def = lastWrite(proto.code, pc, Instruction.getA(proto.code[pc]));
                if (def >= 0) {
                    fusedDef[pc] = def;
                    // Skipping the GETUPVAL store is only sound when resume
                    // recomputes it: pure protos resume fused calls at the
                    // def pc (prefix is side-effect free); impure leaves
                    // always materialize so any resume pc sees valid regs.
                    if (pure) {
                        skipStore[def] = true;
                    }
                }
            }
        }
        // Distinct fused upvalue indexes, each classified once at entry:
        // flag = whether upvals[b] currently holds exactly `self`. Upvalues
        // cannot change mid-flight (no hooks, no yield, no writes in the
        // pure subset), so this is sound; a mismatch only selects the slow
        // path, never deopts.
        int[] fusedBList = new int[len];
        int fusedBCount = 0;
        for (int pc = 0; pc < len; pc++) {
            if (fusedUp[pc] >= 0) {
                boolean seen = false;
                for (int i = 0; i < fusedBCount; i++) {
                    if (fusedBList[i] == fusedUp[pc]) {
                        seen = true;
                        break;
                    }
                }
                if (!seen) {
                    fusedBList[fusedBCount++] = fusedUp[pc];
                }
            }
        }
        MethodVisitor mv;
        if (info.returnsInt()) {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_NAME, EXEC_DESC, null, null);
        } else {
            mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_OBJ_NAME, EXEC_OBJ_DESC, null, null);
        }
        mv.visitCode();
        emitPrologue(mv, fusedBList, fusedBCount);
        emitBody(mv, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                fusedBCount, info.returnsInt(), info.returnsVoid(), pure, info.types());
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        if (info.returnsInt() && fusedBCount > 0) {
            // Prologue-free recursive entry. Sound only because every caller
            // passes down entry-verified closures for the same upvalue array
            // (self-recursion on one closure); anything else uses exec.
            MethodVisitor mi =
                    cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_INNER_NAME, innerDesc(fusedBCount), null, null);
            mi.visitCode();
            for (int i = 0; i < fusedBCount; i++) {
                mi.visitVarInsn(ALOAD, 6 + i);
                mi.visitVarInsn(ASTORE, 10 + 2 * i);
                mi.visitInsn(ICONST_1);
                mi.visitVarInsn(ISTORE, 9 + 2 * i);
            }
            emitBody(mi, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                    fusedBCount, true, info.returnsVoid(), pure, info.types());
            mi.visitMaxs(0, 0);
            mi.visitEnd();
        }
        cw.visitEnd();
        return new Translation(internalName, cw.toByteArray());
    }

    private static void emitPrologue(MethodVisitor mv, int[] fusedBList, int fusedBCount) {
        // Entry prologue: classify each distinct fused callee once per
        // invocation (identity against `self`, a single reference compare).
        for (int i = 0; i < fusedBCount; i++) {
            int b = fusedBList[i];
            int flagSlot = 9 + 2 * i;
            int cloSlot = 10 + 2 * i;
            mv.visitVarInsn(ALOAD, 1);
            ldcInt(mv, b);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                    "()Lorg/luava/runtime/LuaValue;", false);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ASTORE, 8);
            mv.visitVarInsn(ALOAD, 0);
            Label notSelf = new Label();
            Label nextB = new Label();
            mv.visitJumpInsn(IF_ACMPNE, notSelf);
            mv.visitInsn(ICONST_1);
            mv.visitVarInsn(ISTORE, flagSlot);
            mv.visitVarInsn(ALOAD, 8);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
            mv.visitVarInsn(ASTORE, cloSlot);
            mv.visitJumpInsn(GOTO, nextB);
            mv.visitLabel(notSelf);
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, flagSlot);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, cloSlot);
            mv.visitLabel(nextB);
        }
    }

    /**
     * Emits the per-instruction bodies. Shared verbatim by {@code exec}
     * (after the prologue) and {@code execInner} (after verified-closure
     * init): fused call sites target {@code execInner} because the hoisted
     * closures provably still hold.
     */
    private static void emitBody(MethodVisitor mv, String owner, LuaProto proto, Label[] labels,
            int[] fusedUp, int[] fusedDef, boolean[] skipStore, int[] fusedBList, int fusedBCount,
            boolean returnsInt, boolean returnsVoid, boolean pure, int[][] types) {
        int len = proto.code.length;
        for (int pc = 0; pc < len; pc++) {
            mv.visitLabel(labels[pc]);
            int inst = proto.code[pc];
            int op = Instruction.getOp(inst);
            int a = Instruction.getA(inst);
            int b = Instruction.getB(inst);
            int c = Instruction.getC(inst);
            switch (op) {
                case OpCode.OP_MOVE -> emitMove(mv, a, b);
                case OpCode.OP_LOADI -> {
                    int sbx = Instruction.getsBx(inst);
                    emitStoreIntConst(mv, a, sbx);
                }
                case OpCode.OP_LOADK -> {
                    LuaValue kv = proto.constants[Instruction.getBx(inst)];
                    if (kv instanceof LuaInteger li) {
                        emitStoreIntConst(mv, a, li.toLong());
                    } else if (kv instanceof org.luava.runtime.LuaFloat lf) {
                        mv.visitVarInsn(ALOAD, 2);
                        emitIndex(mv, a);
                        mv.visitLdcInsn(Double.doubleToRawLongBits(lf.toDouble()));
                        mv.visitInsn(LASTORE);
                        mv.visitVarInsn(ALOAD, 3);
                        emitIndex(mv, a);
                        ldcInt(mv, TYPE_FLOAT);
                        mv.visitInsn(BASTORE);
                        mv.visitVarInsn(ALOAD, 4);
                        emitIndex(mv, a);
                        mv.visitInsn(ACONST_NULL);
                        mv.visitInsn(AASTORE);
                    } else {
                        mv.visitVarInsn(ALOAD, 2);
                        emitIndex(mv, a);
                        mv.visitInsn(LCONST_0);
                        mv.visitInsn(LASTORE);
                        mv.visitVarInsn(ALOAD, 3);
                        emitIndex(mv, a);
                        ldcInt(mv, TYPE_OBJECT);
                        mv.visitInsn(BASTORE);
                        mv.visitVarInsn(ALOAD, 4);
                        emitIndex(mv, a);
                        emitLoadConst(mv, Instruction.getBx(inst));
                        mv.visitInsn(AASTORE);
                    }
                }
                case OpCode.OP_LOADF -> {
                    double dv = (double) Instruction.getsBx(inst);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitLdcInsn(Double.doubleToRawLongBits(dv));
                    mv.visitInsn(LASTORE);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_FLOAT);
                    mv.visitInsn(BASTORE);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(AASTORE);
                }
                case OpCode.OP_LOADNIL -> {
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a);
                    mv.visitInsn(IADD);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a + b + 1);
                    mv.visitInsn(IADD);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                            "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
                }
                case OpCode.OP_LOADTRUE, OpCode.OP_LOADFALSE -> {
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(op == OpCode.OP_LOADTRUE ? LCONST_1 : LCONST_0);
                    mv.visitInsn(LASTORE);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_BOOLEAN);
                    mv.visitInsn(BASTORE);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(AASTORE);
                }
                case OpCode.OP_CLEANUP -> {
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a);
                    mv.visitInsn(IADD);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a + b + 1);
                    mv.visitInsn(IADD);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                            "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
                }
                case OpCode.OP_GETTABUP -> emitGetTabUp(mv, a, b, c, pc);
                case OpCode.OP_GETTABLE -> emitGetTable(mv, a, b, c, pc);
                case OpCode.OP_GETI -> emitGetI(mv, a, b, c, pc);
                case OpCode.OP_GETFIELD -> emitGetField(mv, a, b, c, pc);
                case OpCode.OP_SETTABUP -> emitSetTabUp(mv, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETTABLE -> emitSetTable(mv, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETI -> emitSetI(mv, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETFIELD -> emitSetField(mv, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SELF -> emitSelf(mv, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETLIST -> {
                    int n = b;
                    int last = c;
                    if (Instruction.getk(inst) == 1
                            && pc + 1 < proto.code.length
                            && Instruction.getOp(proto.code[pc + 1]) == OpCode.OP_EXTRAARG) {
                        last += Instruction.getAx(proto.code[pc + 1]) * 256;
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a);
                    mv.visitInsn(IADD);
                    ldcInt(mv, n);
                    ldcInt(mv, last);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "setList",
                            "([J[B[Lorg/luava/runtime/LuaValue;III)V", false);
                }
                case OpCode.OP_CLOSE -> {
                    // Closes open upvalues at/above R[A] plus pending tbc
                    // variables. Both act on this frame's running thread.
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a);
                    mv.visitInsn(IADD);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "closeAt",
                            "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
                }
                case OpCode.OP_CLOSURE -> {
                    // Build the child closure over the caller's register
                    // window. This mutates the open-upvalue list, so it is
                    // classified impure; a later deopt resumes at-pc with the
                    // upvalue already committed (idempotent under
                    // findOrCreateOpenUpvalue).
                    mv.visitVarInsn(ALOAD, 0);
                    ldcInt(mv, Instruction.getBx(inst));
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ILOAD, 5);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "buildClosure",
                            "(Lorg/luava/runtime/bytecode/LuaClosure;I[J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/bytecode/LuaClosure;", false);
                    emitStoreValueReg(mv, a);
                }
                case OpCode.OP_NEWTABLE -> {
                    mv.visitTypeInsn(NEW, "org/luava/runtime/LuaTable");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/LuaTable", "<init>", "()V", false);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LASTORE);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_OBJECT);
                    mv.visitInsn(BASTORE);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitInsn(AASTORE);
                }
                case OpCode.OP_EXTRAARG -> {
                    // Size hints consumed by NEWTABLE; nothing to execute.
                }
                case OpCode.OP_GETUPVAL -> {
                    if (skipStore[pc]) {
                        break;
                    }
                    // Fast lane: closed integer upvalue, no boxing.
                    mv.visitVarInsn(ALOAD, 1);
                    ldcInt(mv, b);
                    mv.visitInsn(AALOAD);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "isClosedInt",
                            "()Z", false);
                    Label generic = new Label();
                    Label doneUv = new Label();
                    mv.visitJumpInsn(IFEQ, generic);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getClosedInt",
                            "()J", false);
                    mv.visitVarInsn(LSTORE, 6);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(LLOAD, 6);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                    mv.visitJumpInsn(GOTO, doneUv);
                    mv.visitLabel(generic);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                            "()Lorg/luava/runtime/LuaValue;", false);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "setLuaValue",
                            "([J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)V", false);
                    mv.visitLabel(doneUv);
                }
                case OpCode.OP_SETUPVAL -> {
                    // Leaf-only (eligibility): closed-integer upvalue write.
                    emitGuardInt(mv, a, pc);
                    mv.visitVarInsn(ALOAD, 1);
                    ldcInt(mv, b);
                    mv.visitInsn(AALOAD);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "isClosedInt",
                            "()Z", false);
                    Label uvOk = new Label();
                    mv.visitJumpInsn(IFNE, uvOk);
                    emitDeopt(mv, pc);
                    mv.visitLabel(uvOk);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LALOAD);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "setClosedInt",
                            "(J)V", false);
                }
                case OpCode.OP_ADD -> emitArithNum(mv, a, b, c, pc, LADD, DADD);
                case OpCode.OP_SUB -> emitArithNum(mv, a, b, c, pc, LSUB, DSUB);
                case OpCode.OP_MUL -> emitArithNum(mv, a, b, c, pc, LMUL, DMUL);
                case OpCode.OP_DIV -> emitDivNum(mv, a, b, c, pc);
                case OpCode.OP_POW -> emitPowNum(mv, a, b, c, pc);
                case OpCode.OP_IDIV -> emitIdivNum(mv, a, b, c, pc);
                case OpCode.OP_MOD -> emitModNum(mv, a, b, c, pc);
                case OpCode.OP_BAND -> emitBitwiseNum(mv, a, b, c, pc, LAND, "and");
                case OpCode.OP_BOR -> emitBitwiseNum(mv, a, b, c, pc, LOR, "or");
                case OpCode.OP_BXOR -> emitBitwiseNum(mv, a, b, c, pc, LXOR, "xor");
                case OpCode.OP_SHL -> emitShiftNum(mv, a, b, c, pc, true);
                case OpCode.OP_SHR -> emitShiftNum(mv, a, b, c, pc, false);
                case OpCode.OP_SHLI -> emitShiftImm(mv, a, b, Instruction.getsC(inst), pc, true);
                case OpCode.OP_SHRI -> emitShiftImm(mv, a, b, Instruction.getsC(inst), pc, false);
                // Integer-only constant forms. analyze() already rejected any
                // proto containing a K-form whose constant is not a LuaInteger
                // (and rejected DIVK/POWK outright, since `/` and `^` always
                // yield float). So kc is a compile-time integer operand here.
                case OpCode.OP_ADDK -> emitArithKNum(mv, proto, a, b, c, pc, LADD, DADD);
                case OpCode.OP_MULK -> emitArithKNum(mv, proto, a, b, c, pc, LMUL, DMUL);
                case OpCode.OP_DIVK -> emitDivK(mv, proto, a, b, c, pc);
                case OpCode.OP_POWK -> emitPowK(mv, proto, a, b, c, pc);
                case OpCode.OP_IDIVK -> emitIdivK(mv, proto, a, b, c, pc);
                case OpCode.OP_MODK -> emitModK(mv, proto, a, b, c, pc);
                case OpCode.OP_BANDK -> emitArithKNum(mv, proto, a, b, c, pc, LAND, -1);
                case OpCode.OP_BORK -> emitArithKNum(mv, proto, a, b, c, pc, LOR, -1);
                case OpCode.OP_BXORK -> emitArithKNum(mv, proto, a, b, c, pc, LXOR, -1);
                case OpCode.OP_UNM -> {
                    emitGuardInt(mv, b, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, b);
                    mv.visitInsn(LALOAD);
                    mv.visitInsn(LNEG);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
                case OpCode.OP_BNOT -> {
                    emitGuardInt(mv, b, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, b);
                    mv.visitInsn(LALOAD);
                    mv.visitLdcInsn(-1L);
                    mv.visitInsn(LXOR);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
                case OpCode.OP_NOT -> {
                    // Pure truthiness, no guard needed (mirrors isTruthy).
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, b);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "isTruthy",
                            "([J[BI)Z", false);
                    Label isTrue = new Label();
                    Label isDone = new Label();
                    mv.visitJumpInsn(IFNE, isTrue);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LCONST_1);
                    mv.visitInsn(LASTORE);
                    mv.visitJumpInsn(GOTO, isDone);
                    mv.visitLabel(isTrue);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LASTORE);
                    mv.visitLabel(isDone);
                    emitTagIntNull(mv, a);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_BOOLEAN);
                    mv.visitInsn(BASTORE);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(AASTORE);
                }
                case OpCode.OP_LEN -> {
                    // Plain tables and strings only; anything else (numbers,
                    // booleans, __len metamethods) deopts to the interpreter.
                    emitGuardObject(mv, b, pc);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, b);
                    mv.visitInsn(AALOAD);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
                    Label isTableLen = new Label();
                    Label isStringLen = new Label();
                    Label lenDone = new Label();
                    mv.visitJumpInsn(IFNE, isTableLen);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaString");
                    mv.visitJumpInsn(IFNE, isStringLen);
                    emitDeopt(mv, pc);
                    mv.visitLabel(isStringLen);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaString");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaString", "len",
                            "()Lorg/luava/runtime/LuaValue;", false);
                    mv.visitJumpInsn(GOTO, lenDone);
                    mv.visitLabel(isTableLen);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "getMetatable",
                            "()Lorg/luava/runtime/LuaTable;", false);
                    Label hasMeta = new Label();
                    mv.visitJumpInsn(IFNONNULL, hasMeta);
                    mv.visitInsn(POP);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "len",
                            "()Lorg/luava/runtime/LuaValue;", false);
                    mv.visitJumpInsn(GOTO, lenDone);
                    mv.visitLabel(hasMeta);
                    emitDeopt(mv, pc);
                    mv.visitLabel(lenDone);
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaInteger");
                    Label lenOk = new Label();
                    mv.visitJumpInsn(IFNE, lenOk);
                    emitDeopt(mv, pc);
                    mv.visitLabel(lenOk);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaInteger");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaInteger", "toLong", "()J", false);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
                case OpCode.OP_CONCAT -> {
                    // R[A] = R[A] .. ... .. R[A+B-1]. The helper returns null
                    // when an operand is not a plain string/number (a
                    // metatable __concat could run code), so we deopt then.
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a);
                    mv.visitInsn(IADD);
                    mv.visitVarInsn(ILOAD, 5);
                    ldcInt(mv, a + b);
                    mv.visitInsn(IADD);
                    mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "concatRange",
                            "([J[B[Lorg/luava/runtime/LuaValue;II)Lorg/luava/runtime/LuaValue;", false);
                    mv.visitInsn(DUP);
                    Label concatOk = new Label();
                    mv.visitJumpInsn(IFNONNULL, concatOk);
                    mv.visitInsn(POP);
                    emitDeopt(mv, pc);
                    mv.visitLabel(concatOk);
                    // Stack: [result]. Store it as an object register.
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitInsn(AASTORE);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LASTORE);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_OBJECT);
                    mv.visitInsn(BASTORE);
                }
                case OpCode.OP_ADDI -> {
                    int sc = Instruction.getsC(inst);
                    emitGuardInt(mv, b, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, b);
                    mv.visitInsn(LALOAD);
                    mv.visitLdcInsn((long) sc);
                    mv.visitInsn(LADD);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
                case OpCode.OP_SUBK -> emitArithKNum(mv, proto, a, b, c, pc, LSUB, DSUB);
                case OpCode.OP_LEI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLE, IFGT);
                case OpCode.OP_LTI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLT, IFGE);
                case OpCode.OP_GTI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGT, IFLE);
                case OpCode.OP_GEI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGE, IFLT);
                case OpCode.OP_EQI -> {
                    int sb = Instruction.getsB(inst);
                    int k = Instruction.getk(inst);
                    emitGuardInt(mv, a, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LALOAD);
                    mv.visitLdcInsn((long) sb);
                    mv.visitInsn(LCMP);
                    Label isEq = new Label();
                    mv.visitJumpInsn(IFEQ, isEq);
                    // not equal
                    if (k == 1) {
                        mv.visitJumpInsn(GOTO, labels[pc + 2]);
                    }
                    mv.visitJumpInsn(GOTO, labels[pc + 1]);
                    mv.visitLabel(isEq);
                    if (k == 1) {
                        mv.visitJumpInsn(GOTO, labels[pc + 1]);
                    } else {
                        mv.visitJumpInsn(GOTO, labels[pc + 2]);
                    }
                }
                case OpCode.OP_LT -> emitCmpRR(mv, a, b, Instruction.getk(inst), pc, labels, false);
                case OpCode.OP_LE -> emitCmpRR(mv, a, b, Instruction.getk(inst), pc, labels, true);
                case OpCode.OP_EQ -> emitEqRR(mv, proto, a, b, Instruction.getk(inst), pc, labels);
                case OpCode.OP_EQK -> emitEqK(mv, proto, a, b, Instruction.getk(inst), pc, labels);
                case OpCode.OP_TEST -> emitTest(mv, a, Instruction.getk(inst), pc, labels);
                case OpCode.OP_TESTSET -> emitTestSet(mv, a, b, Instruction.getk(inst), pc, labels);
                case OpCode.OP_LFALSESKIP -> {
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LASTORE);
                    mv.visitVarInsn(ALOAD, 3);
                    emitIndex(mv, a);
                    ldcInt(mv, TYPE_BOOLEAN);
                    mv.visitInsn(BASTORE);
                    mv.visitVarInsn(ALOAD, 4);
                    emitIndex(mv, a);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitInsn(AASTORE);
                    // Unconditional skip of the next instruction.
                    mv.visitJumpInsn(GOTO, labels[pc + 2]);
                }
                case OpCode.OP_JMP -> {
                    int sj = Instruction.getsJ(inst);
                    mv.visitJumpInsn(GOTO, labels[pc + 1 + sj]);
                }
                case OpCode.OP_FORPREP -> emitForPrep(mv, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_FORLOOP -> emitForLoop(mv, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_TFORPREP -> emitTForPrep(mv, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_TFORCALL -> emitTForCall(mv, a, c, pc);
                case OpCode.OP_TFORLOOP -> emitTForLoop(mv, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_CALL -> {
                    if (isFloorSqrtIntrinsic(proto, proto.code, pc)) {
                        emitFloorSqrtCall(mv, a, pc);
                        break;
                    }
                    if (isSqrtIntrinsic(proto, proto.code, pc)) {
                        emitSqrtCall(mv, a, pc);
                        break;
                    }
                    if (isFloorIntrinsic(proto, proto.code, pc)) {
                        emitFloorCall(mv, a, pc);
                        break;
                    }
                    if (isTForSetup(proto, proto.code, pc)) {
                        emitTForSetup(mv, a, b - 1, pc);
                        break;
                    }
                    if (isTostringIntrinsic(proto, proto.code, pc)) {
                        emitTostringCall(mv, a, pc);
                        break;
                    }
                    if (isIntrinsicCall(proto, proto.code, pc)) {
                        // The multi-result sqrt half consumed by the following
                        // fused floor(sqrt): emits nothing here.
                        break;
                    }
                    if (!pure) {
                        // Impure proto: never enter a callee from compiled
                        // code. A nested deopt would re-invoke a callee whose
                        // earlier writes are already committed, double-applying
                        // them. Deopt here instead: committed prefix state is
                        // intact and the interpreter re-executes this call.
                        // Structural: expected every invocation, so it must not
                        // count toward the deopt budget.
                        emitDeopt(mv, pc, true);
                        break;
                    }
                    int slot = -1;
                    if (fusedUp[pc] >= 0) {
                        for (int i = 0; i < fusedBCount; i++) {
                            if (fusedBList[i] == fusedUp[pc]) {
                                slot = i;
                                break;
                            }
                        }
                    }
                    // Fused calls resume at the skipped GETUPVAL so the
                    // interpreter recomputes the function register.
                    int resumePc = fusedDef[pc] >= 0 ? fusedDef[pc] : pc;
                    emitCall(mv, owner, proto, a, b - 1, resumePc, fusedUp[pc], slot, fusedBList,
                            fusedBCount, c - 1 != 0);
                }
                case OpCode.OP_TAILCALL -> {
                    if (isSqrtIntrinsic(proto, proto.code, pc)) {
                        emitSqrtTail(mv, a, pc);
                        break;
                    }
                    if (!pure) {
                        // Same rule as OP_CALL: an impure proto never enters a
                        // callee from compiled code. Structural deopt.
                        emitDeopt(mv, pc, true);
                        break;
                    }
                    int slot = -1;
                    if (fusedUp[pc] >= 0) {
                        for (int i = 0; i < fusedBCount; i++) {
                            if (fusedBList[i] == fusedUp[pc]) {
                                slot = i;
                                break;
                            }
                        }
                    }
                    int resumePc = fusedDef[pc] >= 0 ? fusedDef[pc] : pc;
                    emitTailCall(mv, owner, proto, a, b - 1, resumePc, fusedUp[pc], slot, fusedBList,
                            fusedBCount, labels);
                }
                case OpCode.OP_RETURN1 -> emitReturnOne(mv, a, pc, returnsInt);
                case OpCode.OP_RETURN -> {
                    // Single-value shape only; anything else deopts.
                    if (b != 2) {
                        emitDeopt(mv, pc);
                    } else {
                        emitReturnOne(mv, a, pc, returnsInt);
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    if (returnsVoid) {
                        // Void proto: return the sentinel; callers materialize
                        // zero results (or nil-fill an expected count).
                        mv.visitInsn(LCONST_0);
                        mv.visitInsn(LRETURN);
                    } else {
                        emitDeopt(mv, pc);
                    }
                }
                default -> emitDeopt(mv, pc);
            }
        }
        // Fallthrough safety: never normally reached.
        emitDeopt(mv, len - 1);
    }

    /** Shared single-value return for RETURN1 and RETURN B==2. */
    private static void emitReturnOne(MethodVisitor mv, int a, int pc, boolean returnsInt) {
        if (returnsInt) {
            emitGuardInt(mv, a, pc);
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            mv.visitInsn(LALOAD);
            mv.visitInsn(LRETURN);
        } else {
            // Object mode: box the full triple; any type is exact.
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM",
                    "getLuaValue",
                    "([J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
            mv.visitInsn(ARETURN);
        }
    }

    /**
     * Static analysis result: purity, the return-kind decision, and the
     * per-pc register type map (state before each instruction).
     *
     * @param returnsVoid the proto yields no value at all (all returns are
     *     bare {@code return}/fall-off); {@code returnsInt} is true as well so
     *     the kernel keeps the unboxed {@code long} ABI, and callers discard
     *     the sentinel.
     */
    public record Info(boolean pure, boolean returnsInt, boolean returnsVoid, int[][] types) {}

    // Register abstract types for return-kind inference.
    private static final int T_UNKNOWN = 0;
    private static final int T_INT = 1;
    private static final int T_OBJ = 2;
    /**
     * A number whose subtype (integer vs float) is not statically known.
     * Arithmetic on it is emitted as a runtime numeric dispatch: the
     * integer lane when both operands are ints, otherwise the float lane.
     */
    private static final int T_NUM = 3;

    /**
     * Analyzes a proto for the JIT subset. Returns null when any shape is
     * unsupported. Safety rules: a proto with calls must be pure (no
     * upvalue/table writes and no closure creation), because a nested deopt
     * re-invokes the callee from scratch; impure leaves resume at the
     * faulting pc with committed state intact.
     *
     * <p>Return-kind inference runs a forward type analysis
     * (UNKNOWN/INT/OBJ, conflict rejects). Parameters seed INT, which is
     * sound because every type-specific use in generated code is
     * runtime-guarded: a wrong guess only costs a deopt, never wrong
     * behavior. Object-returning protos must be call-free leaves (their
     * results cannot feed the integer call protocol).
     */
    public static Info analyze(LuaProto proto) {
        // A vararg proto is only rejected when it actually reads `...`: the
        // `VARARG`/`VARARGPREP` opcodes are outside the allow-list below, so
        // they reject the proto there. A vararg signature that never uses
        // `...` is indistinguishable from a fixed-arity one (extra args are
        // ignored, missing ones nil-filled) and is safe to compile.
        int[] code = proto.code;
        if (code.length == 0 || code.length > 200) {
            return null;
        }
        int regs = Math.max(proto.maxStackSize, proto.numParams);
        if (proto.maxStackSize > 64 || proto.numParams > 16) {
            return null;
        }
        // Purity is a whole-proto property (any write/closure/close). Compute
        // it first: an impure proto emits a deopt for every CALL/TAILCALL, so
        // its call shapes need no validation (see the call check below).
        boolean impure = false;
        for (int pc = 0; pc < code.length; pc++) {
            switch (Instruction.getOp(code[pc])) {
                case OpCode.OP_SETUPVAL,
                        OpCode.OP_SETTABUP,
                        OpCode.OP_SETTABLE,
                        OpCode.OP_SETI,
                        OpCode.OP_SETFIELD,
                        OpCode.OP_SETLIST,
                        OpCode.OP_CLOSURE,
                        OpCode.OP_CLOSE -> impure = true;
                default -> {}
            }
        }
        boolean hasCalls = false;
        int firstGeneralCallPc = -1;
        int firstLoopBackedgePc = -1;
        boolean hasReturn1 = false;
        boolean hasVoidReturn = false;
        boolean hasTailCall = false;
        boolean hasIntrinsicTail = false;
        for (int pc = 0; pc < code.length; pc++) {
            int inst = code[pc];
            switch (Instruction.getOp(inst)) {
                case OpCode.OP_MOVE,
                        OpCode.OP_LOADI,
                        OpCode.OP_LOADF,
                        OpCode.OP_LOADK,
                        OpCode.OP_LOADNIL,
                        OpCode.OP_LOADTRUE,
                        OpCode.OP_LOADFALSE,
                        OpCode.OP_CLEANUP,
                        OpCode.OP_GETUPVAL,
                        OpCode.OP_SETUPVAL,
                        OpCode.OP_GETTABUP,
                        OpCode.OP_GETTABLE,
                        OpCode.OP_GETI,
                        OpCode.OP_GETFIELD,
                        OpCode.OP_SELF,
                        OpCode.OP_SETTABUP,
                        OpCode.OP_SETTABLE,
                        OpCode.OP_SETI,
                        OpCode.OP_SETFIELD,
                        OpCode.OP_CLOSURE,
                        OpCode.OP_CLOSE,
                        OpCode.OP_NEWTABLE,
                        OpCode.OP_EXTRAARG,
                        OpCode.OP_UNM,
                        OpCode.OP_BNOT,
                        OpCode.OP_NOT,
                        OpCode.OP_LEN,
                        OpCode.OP_CONCAT,
                        OpCode.OP_SETLIST,
                        OpCode.OP_ADD,
                        OpCode.OP_SUB,
                        OpCode.OP_MUL,
                        OpCode.OP_DIV,
                        OpCode.OP_MOD,
                        OpCode.OP_POW,
                        OpCode.OP_IDIV,
                        OpCode.OP_BAND,
                        OpCode.OP_BOR,
                        OpCode.OP_BXOR,
                        OpCode.OP_SHL,
                        OpCode.OP_SHR,
                        OpCode.OP_SHLI,
                        OpCode.OP_SHRI,
                        OpCode.OP_ADDI,
                        OpCode.OP_ADDK,
                        OpCode.OP_SUBK,
                        OpCode.OP_MULK,
                        OpCode.OP_DIVK,
                        OpCode.OP_POWK,
                        OpCode.OP_IDIVK,
                        OpCode.OP_MODK,
                        OpCode.OP_BANDK,
                        OpCode.OP_BORK,
                        OpCode.OP_BXORK,
                        OpCode.OP_LEI,
                        OpCode.OP_LTI,
                        OpCode.OP_GTI,
                        OpCode.OP_GEI,
                        OpCode.OP_EQI,
                        OpCode.OP_EQ,
                        OpCode.OP_EQK,
                        OpCode.OP_LT,
                        OpCode.OP_LE,
                        OpCode.OP_TEST,
                        OpCode.OP_TESTSET,
                        OpCode.OP_LFALSESKIP,
                        OpCode.OP_JMP,
                        OpCode.OP_FORPREP,
                        OpCode.OP_FORLOOP,
                        OpCode.OP_TFORPREP,
                        OpCode.OP_TFORCALL,
                        OpCode.OP_TFORLOOP,
                        OpCode.OP_CALL,
                        OpCode.OP_TAILCALL,
                        OpCode.OP_RETURN,
                        OpCode.OP_RETURN1,
                        OpCode.OP_RETURN0 -> {}
                default -> {
                    return null;
                }
            }
            if (Instruction.getOp(inst) == OpCode.OP_FORLOOP
                    || Instruction.getOp(inst) == OpCode.OP_TFORLOOP
                    || (Instruction.getOp(inst) == OpCode.OP_JMP && Instruction.getsJ(inst) < 0)) {
                if (firstLoopBackedgePc < 0) {
                    firstLoopBackedgePc = pc;
                }
            }
            if (Instruction.getOp(inst) == OpCode.OP_CALL
                    || Instruction.getOp(inst) == OpCode.OP_TAILCALL) {
                // A recognized builtin intrinsic (`math.sqrt(x)`,
                // `math.floor(x)`, `math.floor(math.sqrt(x))`) is emitted
                // inline as a guarded Math call, so it is NOT a general call:
                // it cannot reach a Lua callee and cannot observe state.
                if (isIntrinsicCall(proto, code, pc)) {
                    if (Instruction.getOp(inst) == OpCode.OP_TAILCALL) {
                        hasIntrinsicTail = true;
                    }
                } else {
                    hasCalls = true;
                    if (firstGeneralCallPc < 0) {
                        firstGeneralCallPc = pc;
                    }
                    if (Instruction.getOp(inst) == OpCode.OP_TAILCALL) {
                        hasTailCall = true;
                    } else if (!impure) {
                        // Pure proto: the JIT actually enters the callee, so
                        // OP_CALL must yield exactly one value (C==2) or no
                        // value (C==1, a statement call), with at least the
                        // function register (B>=1). An impure proto deopts
                        // every call, so its shape is irrelevant.
                        int cc = Instruction.getC(inst);
                        if ((cc != 2 && cc != 1) || Instruction.getB(inst) < 1) {
                            return null;
                        }
                    }
                }
            }
            if (Instruction.getOp(inst) == OpCode.OP_RETURN0) {
                hasVoidReturn = true;
            }
            if (Instruction.getOp(inst) == OpCode.OP_RETURN1
                    || (Instruction.getOp(inst) == OpCode.OP_RETURN && Instruction.getB(inst) == 2)) {
                hasReturn1 = true;
            }
            // NOTE: OP_RETURN with B!=2 (multret) is not rejected here; the
            // emitter deopts on it. Return-kind classification is refined
            // after reachability so dead trailing returns do not skew it.
            if (Instruction.getOp(inst) == OpCode.OP_SETLIST && Instruction.getB(inst) == 0) {
                return null;
            }
            // Integer-constant arithmetic forms: the constant must be a
            // LuaInteger so the unboxed long world stays exact. DIVK/POWK are
            // always float-producing, so their constant may be any number.
            switch (Instruction.getOp(inst)) {
                case OpCode.OP_ADDK,
                        OpCode.OP_SUBK,
                        OpCode.OP_MULK,
                        OpCode.OP_IDIVK,
                        OpCode.OP_MODK,
                        OpCode.OP_BANDK,
                        OpCode.OP_BORK,
                        OpCode.OP_BXORK -> {
                    if (!(proto.constants[Instruction.getC(inst)] instanceof LuaInteger)) {
                        return null;
                    }
                }
                case OpCode.OP_DIVK, OpCode.OP_POWK -> {
                    LuaValue k = proto.constants[Instruction.getC(inst)];
                    if (!(k instanceof LuaInteger) && !(k instanceof org.luava.runtime.LuaFloat)) {
                        return null;
                    }
                }
                default -> {}
            }
        }
        // An impure proto may still be compiled, but every CALL/TAILCALL in
        // it deopts (see emitBody): entering a callee from compiled code
        // risks a nested deopt re-invoking an impure callee whose prefix
        // writes are already committed. The compiled prefix is side-effect
        // committed before the deopt, so resuming at the call pc is exact.
        //
        // Only compile an impure *calling* proto when a loop back-edge
        // precedes its first general call: then the hot loop runs compiled and
        // only the trailing call deopts each invocation. Otherwise every entry
        // would pay a structural-deopt exception for no compiled work.
        if (impure && hasCalls && (firstLoopBackedgePc < 0 || firstLoopBackedgePc > firstGeneralCallPc)) {
            return null;
        }
        // Reachable pcs (jumps + conditional skips).
        boolean[] reach = new boolean[code.length];
        reach[0] = true;
        boolean rchanged = true;
        while (rchanged) {
            rchanged = false;
            for (int pc = 0; pc < code.length; pc++) {
                if (!reach[pc]) {
                    continue;
                }
                for (int s : successors(code, pc)) {
                    if (s >= 0 && s < code.length && !reach[s]) {
                        reach[s] = true;
                        rchanged = true;
                    }
                }
            }
        }
        // Forward type inference over reachable code.
        int[][] in = new int[code.length][regs];
        for (int i = 0; i < proto.numParams && i < regs; i++) {
            in[0][i] = T_INT;
        }
        for (int iter = 0; iter < code.length * 2 + 4; iter++) {
            boolean changed = false;
            for (int pc = 0; pc < code.length; pc++) {
                if (!reach[pc]) {
                    continue;
                }
                int[] out = in[pc].clone();
                if (!transfer(proto, code, pc, in[pc], out, regs)) {
                    return null;
                }
                for (int s : successors(code, pc)) {
                    if (s < 0 || s >= code.length) {
                        continue;
                    }
                    for (int r = 0; r < regs; r++) {
                        if (out[r] != T_UNKNOWN) {
                            int merged = mergeTy(in[s][r], out[r]);
                            if (merged < 0) {
                                return null;
                            }
                            if (merged != in[s][r]) {
                                in[s][r] = merged;
                                changed = true;
                            }
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        // Return-kind classification over reachable code only (the compiler
        // always appends a dead RETURN0). A tail call always forwards a
        // value, so it disqualifies voidness.
        boolean reachVoid = false;
        boolean reachValue = false;
        for (int pc = 0; pc < code.length; pc++) {
            if (!reach[pc]) {
                continue;
            }
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_RETURN0) {
                reachVoid = true;
            } else if (op == OpCode.OP_RETURN1
                    || (op == OpCode.OP_RETURN && Instruction.getB(code[pc]) == 2)) {
                reachValue = true;
            }
        }
        boolean returnsVoid = reachVoid && !reachValue && !hasTailCall && !hasIntrinsicTail;
        if (!reachValue && !hasIntrinsicTail && !returnsVoid) {
            return null;
        }
        boolean seenObj = false;
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_RETURN1
                    || (op == OpCode.OP_RETURN && Instruction.getB(code[pc]) == 2)) {
                int rt = in[pc][Instruction.getA(code[pc])];
                if (rt == T_OBJ || rt == T_NUM) {
                    // T_OBJ is an object; T_NUM may be a float. Only a
                    // definitely-int return can use the unboxed long
                    // protocol (T_UNKNOWN keeps the old guarded-int path).
                    seenObj = true;
                }
            }
        }
        if (hasIntrinsicTail) {
            // A `math.sqrt` tail call yields a float; force the object
            // protocol so the boxed LuaFloat is returned exactly.
            seenObj = true;
        }
        boolean returnsInt = !seenObj;
        if (!returnsInt && hasCalls && !impure) {
            // Pure object-returning protos enter their callees under the
            // unboxed integer call protocol, which cannot carry an object
            // result, so reject. An impure proto deopts every call instead,
            // so its object result is only ever boxed at the call boundary.
            return null;
        }
        return new Info(!impure, returnsInt, returnsVoid, in);
    }

    /**
     * Recognizes the exact `math.sqrt(x)` builtin shape at a CALL/TAILCALL pc:
     * the function register must be loaded straight-line by
     * {@code GETFIELD "sqrt"} off a {@code GETTABUP "math"} result. The
     * translator still emits a runtime identity guard against the shared
     * {@code MathLib.SQRT} singleton, so reassigning {@code math.sqrt} or
     * shadowing the global simply deopts — the static shape only avoids
     * deopt storms on unrelated one-argument calls.
     */
    private static boolean isSqrtIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        int op = Instruction.getOp(inst);
        if (op != OpCode.OP_CALL && op != OpCode.OP_TAILCALL) {
            return false;
        }
        // Exactly one argument. A non-tail CALL must also expect one result.
        if (Instruction.getB(inst) != 2) {
            return false;
        }
        if (op == OpCode.OP_CALL && Instruction.getC(inst) != 2) {
            return false;
        }
        return "sqrt".equals(mathFieldAt(proto, code, pc, Instruction.getA(inst)));
    }

    /**
     * Recognizes the {@code math.floor(x)} one-result call shape (identity of
     * the function register is {@code MathLib.FLOOR} at runtime).
     */
    private static boolean isFloorIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        if (Instruction.getOp(inst) != OpCode.OP_CALL
                || Instruction.getB(inst) != 2
                || Instruction.getC(inst) != 2) {
            return false;
        }
        return "floor".equals(mathFieldAt(proto, code, pc, Instruction.getA(inst)));
    }

    /**
     * Recognizes the {@code math.floor(math.sqrt(x))} loop-bound idiom, whose
     * bytecode is a multi-result {@code sqrt} call immediately consumed by a
     * one-result {@code floor} call. {@code pc} is the floor call; the sqrt
     * call must be the directly preceding instruction with its result in
     * {@code R[A+1]} ({@code sqrt} yields exactly one value, so the floor
     * call's {@code B==0} argument window is exactly that value).
     */
    private static boolean isFloorSqrtIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        if (Instruction.getOp(inst) != OpCode.OP_CALL
                || Instruction.getB(inst) != 0
                || Instruction.getC(inst) != 2) {
            return false;
        }
        int af = Instruction.getA(inst);
        if (!"floor".equals(mathFieldAt(proto, code, pc, af))) {
            return false;
        }
        int prev = pc - 1;
        if (prev < 0) {
            return false;
        }
        int pinst = code[prev];
        if (Instruction.getOp(pinst) != OpCode.OP_CALL
                || Instruction.getB(pinst) != 2
                || Instruction.getC(pinst) != 0
                || Instruction.getA(pinst) != af + 1) {
            return false;
        }
        return "sqrt".equals(mathFieldAt(proto, code, prev, af + 1));
    }

    /**
     * Returns {@code "sqrt"}/{@code "floor"} when {@code R[reg]} is provably
     * loaded straight-line by {@code GETFIELD <name>} off a {@code GETTABUP
     * "math"} result, else {@code null}. The emitted code still identity-
     * guards the shared builtin singleton, so a reassigned or shadowed
     * {@code math} simply deopts.
     */
    private static String mathFieldAt(LuaProto proto, int[] code, int pc, int reg) {
        int def = lastWrite(code, pc, reg);
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_GETFIELD) {
            return null;
        }
        int c = Instruction.getC(code[def]);
        if (!(c >= 0 && c < proto.constants.length)
                || !(proto.constants[c] instanceof org.luava.runtime.LuaString ks)) {
            return null;
        }
        String name = ks.toLuaString();
        if (!"sqrt".equals(name) && !"floor".equals(name)) {
            return null;
        }
        int baseReg = Instruction.getB(code[def]);
        int baseDef = lastWrite(code, def, baseReg);
        if (baseDef < 0 || Instruction.getOp(code[baseDef]) != OpCode.OP_GETTABUP) {
            return null;
        }
        int bc = Instruction.getC(code[baseDef]);
        if (!(bc >= 0 && bc < proto.constants.length)
                || !(proto.constants[bc] instanceof org.luava.runtime.LuaString ms)
                || !"math".equals(ms.toLuaString())) {
            return null;
        }
        return name;
    }

    /**
     * True when the call at {@code pc} is handled by an inlined numeric
     * builtin rather than the general call protocol: {@code math.sqrt},
     * {@code math.floor}, the fused {@code math.floor(math.sqrt(x))}, or the
     * multi-result {@code sqrt} call that the following fused floor consumes.
     * Such calls cannot reach a Lua callee and cannot observe state, so they
     * are not general calls and do not reject or deopt a proto.
     */
    private static boolean isIntrinsicCall(LuaProto proto, int[] code, int pc) {
        if (isSqrtIntrinsic(proto, code, pc)
                || isFloorIntrinsic(proto, code, pc)
                || isFloorSqrtIntrinsic(proto, code, pc)
                || isTostringIntrinsic(proto, code, pc)) {
            return true;
        }
        if (isTForSetup(proto, code, pc)) {
            return true;
        }
        // The multi-result sqrt half of a fused floor(sqrt): it emits nothing
        // (the next instruction performs the whole computation).
        int next = pc + 1;
        return next < code.length && isFloorSqrtIntrinsic(proto, code, next)
                && Instruction.getOp(code[pc]) == OpCode.OP_CALL
                && Instruction.getC(code[pc]) == 0;
    }

    /**
     * Recognizes a one-argument, one-result {@code tostring(x)} call whose
     * callee register is loaded straight-line from {@code GETTABUP} on the
     * global table. The emitted code identity-guards the shared
     * {@code BaseLib.TOSTRING} singleton and deopts for any argument whose
     * rendering is not a pure primitive/string (a metatable {@code __tostring}
     * or a number-format override must run interpreted for exact semantics).
     */
    private static boolean isTostringIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        if (Instruction.getOp(inst) != OpCode.OP_CALL
                || Instruction.getB(inst) != 2
                || Instruction.getC(inst) != 2) {
            return false;
        }
        return isGlobalConst(proto, code, pc, Instruction.getA(inst), "tostring");
    }

    /**
     * True when register {@code reg} is loaded straight-line by
     * {@code GETTABUP <globalTable> "name"}: the PUC bytecode for reading a
     * global function. The {@code GETTABUP} {@code B} operand is the _ENV
     * upvalue, which is always 0 in a normal chunk.
     */
    private static boolean isGlobalConst(LuaProto proto, int[] code, int pc, int reg, String name) {
        int def = lastWrite(code, pc, reg);
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_GETTABUP) {
            return false;
        }
        int c = Instruction.getC(code[def]);
        return c >= 0 && c < proto.constants.length
                && proto.constants[c] instanceof org.luava.runtime.LuaString ks
                && name.equals(ks.toLuaString());
    }

    /**
     * True when the CALL at {@code pc} is the iterator-factory call that
     * immediately feeds a generic-for {@code TFORPREP}. Such a call is
     * emitted inline (guarded by identity against the shared {@code pairs} /
     * {@code ipairs} / {@code gmatch} builtins) rather than through the
     * general call protocol, so a generic-for loop can run entirely compiled.
     * An unrecognized factory deopts structurally before any side effect.
     */
    private static boolean isTForSetup(LuaProto proto, int[] code, int pc) {
        if (Instruction.getOp(code[pc]) != OpCode.OP_CALL) {
            return false;
        }
        int next = pc + 1;
        return next < code.length && Instruction.getOp(code[next]) == OpCode.OP_TFORPREP;
    }

    /** Successor pcs for control-flow (conditional compares skip one). */
    private static int[] successors(int[] code, int pc) {
        int op = Instruction.getOp(code[pc]);
        if (op == OpCode.OP_JMP) {
            return new int[] {pc + 1 + Instruction.getsJ(code[pc])};
        }
        // Numeric for: FORPREP may skip the whole loop, FORLOOP may jump back.
        if (op == OpCode.OP_FORPREP) {
            int bx = Instruction.getBx(code[pc]);
            return new int[] {pc + 1, pc + 1 + bx};
        }
        if (op == OpCode.OP_FORLOOP) {
            int bx = Instruction.getBx(code[pc]);
            return new int[] {pc + 1, pc + 1 - bx};
        }
        // Generic-for: TFORPREP skips to TFORCALL; TFORLOOP jumps back to the
        // body start when the control variable is non-nil. The body lies
        // between TFORPREP+1 and TFORCALL-1 (plus, for the back edge, between
        // TFORCALL+1 and TFORLOOP-1).
        if (op == OpCode.OP_TFORPREP) {
            int bx = Instruction.getBx(code[pc]);
            return new int[] {pc + 1, pc + 1 + bx};
        }
        if (op == OpCode.OP_TFORLOOP) {
            int bx = Instruction.getBx(code[pc]);
            return new int[] {pc + 1, pc + 1 - bx};
        }
        if (op == OpCode.OP_LFALSESKIP) {
            // Unconditional skip of the next instruction.
            return new int[] {pc + 2};
        }
        switch (op) {
            case OpCode.OP_LEI,
                    OpCode.OP_LTI,
                    OpCode.OP_GTI,
                    OpCode.OP_GEI,
                    OpCode.OP_EQI,
                    OpCode.OP_EQ,
                    OpCode.OP_EQK,
                    OpCode.OP_LT,
                    OpCode.OP_LE,
                    OpCode.OP_TEST,
                    OpCode.OP_TESTSET,
                    OpCode.OP_RETURN0,
                    OpCode.OP_RETURN1,
                    OpCode.OP_RETURN,
                    OpCode.OP_TAILCALL -> {}
            default -> {
                return new int[] {pc + 1};
            }
        }
        if (op == OpCode.OP_RETURN0
                || op == OpCode.OP_RETURN1
                || op == OpCode.OP_RETURN
                || op == OpCode.OP_TAILCALL) {
            return new int[0];
        }
        return new int[] {pc + 1, pc + 2};
    }

    /**
     * Applies one instruction's type transfer. Returns false when a static
     * type guarantees a runtime guard would always fail.
     */
    private static boolean transfer(LuaProto proto, int[] code, int pc, int[] in, int[] out, int regs) {
        int inst = code[pc];
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        int b = Instruction.getB(inst);
        int c = Instruction.getC(inst);
        switch (op) {
            case OpCode.OP_MOVE -> setTy(out, regs, a, in[b]);
            case OpCode.OP_LOADI -> setTy(out, regs, a, T_INT);
            case OpCode.OP_LOADF -> setTy(out, regs, a, T_NUM);
            case OpCode.OP_LOADNIL,
                    OpCode.OP_LOADTRUE,
                    OpCode.OP_LOADFALSE -> setTy(out, regs, a, T_OBJ);
            case OpCode.OP_CLEANUP -> {
                // OP_CLEANUP clears compiler-dead slots to nil at a loop
                // entry; the slots are immediately redefined (e.g. by the
                // following FORPREP). Widening them to T_OBJ falsely conflicts
                // with that numeric reuse at the loop merge, so mark them
                // unknown: conservative and never over-constraining.
                for (int j = 0; j <= b && a + j < regs; j++) {
                    setTy(out, regs, a + j, T_UNKNOWN);
                }
            }
            case OpCode.OP_LOADK -> {
                LuaValue kv = proto.constants[Instruction.getBx(inst)];
                setTy(out, regs, a, kv instanceof LuaInteger ? T_INT
                        : kv instanceof org.luava.runtime.LuaFloat ? T_NUM : T_OBJ);
            }
            case OpCode.OP_GETUPVAL, OpCode.OP_GETTABUP, OpCode.OP_GETTABLE, OpCode.OP_GETI,
                    OpCode.OP_GETFIELD -> setTy(out, regs, a, T_UNKNOWN);
            case OpCode.OP_SETUPVAL -> {
                return in[a] != T_OBJ;
            }
            case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL -> {
                if (in[b] == T_OBJ || in[c] == T_OBJ) {
                    return false;
                }
                // Mixed int/float sources yield a number whose subtype is
                // only known at runtime; T_NUM drives the numeric dispatch.
                setTy(out, regs, a, (in[b] == T_INT && in[c] == T_INT) ? T_INT : T_NUM);
            }
            case OpCode.OP_UNM -> {
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, in[b] == T_INT ? T_INT : T_NUM);
            }
            case OpCode.OP_BNOT -> {
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_ADDI -> {
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, in[b] == T_INT ? T_INT : T_NUM);
            }
            case OpCode.OP_ADDK,
                    OpCode.OP_SUBK,
                    OpCode.OP_MULK -> {
                // analyze() already guaranteed the constant is an integer.
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, in[b] == T_INT ? T_INT : T_NUM);
            }
            case OpCode.OP_IDIVK,
                    OpCode.OP_MODK,
                    OpCode.OP_BANDK,
                    OpCode.OP_BORK,
                    OpCode.OP_BXORK -> {
                // analyze() already guaranteed the constant is an integer.
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_DIV, OpCode.OP_POW, OpCode.OP_DIVK, OpCode.OP_POWK -> {
                // `/` and `^` always yield a float.
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_NUM);
            }
            case OpCode.OP_MOD, OpCode.OP_IDIV -> {
                if (in[b] == T_OBJ || in[c] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, (in[b] == T_INT && in[c] == T_INT) ? T_INT : T_NUM);
            }
            case OpCode.OP_BAND, OpCode.OP_BOR, OpCode.OP_BXOR,
                    OpCode.OP_SHL, OpCode.OP_SHR, OpCode.OP_SHLI, OpCode.OP_SHRI -> {
                // Bitwise ops are integer-only in this lane; a float operand
                // would raise in the interpreter, so guard-int is correct.
                if ((op == OpCode.OP_BAND || op == OpCode.OP_BOR || op == OpCode.OP_BXOR
                        || op == OpCode.OP_SHL || op == OpCode.OP_SHR)
                        && (in[b] == T_OBJ || in[c] == T_OBJ)) {
                    return false;
                }
                if ((op == OpCode.OP_SHLI || op == OpCode.OP_SHRI) && in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_LEI, OpCode.OP_LTI, OpCode.OP_GTI, OpCode.OP_GEI, OpCode.OP_EQI -> {
                return in[a] != T_OBJ;
            }
            case OpCode.OP_LT, OpCode.OP_LE -> {
                // Numeric comparison is guarded at runtime; a statically
                // object-typed operand can never pass (it would need the
                // metamethod path), so reject the proto instead of deopting.
                return in[a] != T_OBJ && in[b] != T_OBJ;
            }
            case OpCode.OP_EQ, OpCode.OP_EQK -> {
                // Equality is defined for every type (identity/metamethod),
                // but the compiled form only handles number/boolean/nil
                // operands and deopts otherwise, so it is never unsound.
            }
            case OpCode.OP_TEST -> {
                // Truthiness is defined for every type and emitted as a
                // runtime check, so no static restriction is needed.
            }
            case OpCode.OP_TESTSET -> {
                // Copies R[B] into R[A] on the fallthrough edge.
                setTy(out, regs, a, in[b]);
            }
            case OpCode.OP_LFALSESKIP -> setTy(out, regs, a, T_OBJ);
            case OpCode.OP_FORPREP -> {
                // Integer loop only. A statically object-typed init/limit/step
                // (e.g. a float loop) can never pass the runtime guard, so the
                // proto stays interpreted instead of deopting every call.
                if (in[a] == T_OBJ || in[a + 1] == T_OBJ || in[a + 2] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
                setTy(out, regs, a + 1, T_INT);
                setTy(out, regs, a + 2, T_INT);
                setTy(out, regs, a + 3, T_INT);
            }
            case OpCode.OP_FORLOOP -> {
                setTy(out, regs, a, T_INT);
                setTy(out, regs, a + 3, T_INT);
            }
            case OpCode.OP_TFORPREP -> {
                // Pushes a TBC marker; register values are unchanged.
            }
            case OpCode.OP_TFORCALL -> {
                // Iterator results are dynamic values (a `pairs` value is
                // commonly a number used in arithmetic). They must be
                // T_UNKNOWN, not T_OBJ: marking a numeric-but-dynamic slot
                // object would falsely conflict with a following ADD. Every
                // use is runtime-guarded, so unknown only costs a guard.
                int nVars = Math.max(1, c);
                for (int i = 0; i < nVars; i++) {
                    setTy(out, regs, a + 4 + i, T_UNKNOWN);
                }
                setTy(out, regs, a + 2, T_UNKNOWN);
            }
            case OpCode.OP_TFORLOOP -> {
                setTy(out, regs, a + 2, T_UNKNOWN);
            }
            case OpCode.OP_CLOSURE, OpCode.OP_NEWTABLE, OpCode.OP_CONCAT -> setTy(out, regs, a, T_OBJ);
            case OpCode.OP_CLOSE -> {
                // Closes upvalues at/above R[A]; the register value itself is
                // unchanged (the interpreter clears it to nil after close).
                for (int j = a; j < regs; j++) {
                    setTy(out, regs, j, T_UNKNOWN);
                }
            }
            case OpCode.OP_SELF -> {
                // R[A] = method, R[A+1] = receiver. Both are objects.
                setTy(out, regs, a, T_OBJ);
                setTy(out, regs, a + 1, T_OBJ);
            }
            case OpCode.OP_CALL -> {
                if (isTForSetup(proto, code, pc)) {
                    // Iterator-setup call: R[A..A+2] are objects and R[A+3]
                    // (the to-be-closed slot) is nil.
                    setTy(out, regs, a, T_OBJ);
                    setTy(out, regs, a + 1, T_OBJ);
                    setTy(out, regs, a + 2, T_OBJ);
                    setTy(out, regs, a + 3, T_OBJ);
                } else if (isTostringIntrinsic(proto, code, pc)) {
                    // tostring yields a string (object).
                    setTy(out, regs, a, T_OBJ);
                } else {
                    // A recognized sqrt intrinsic yields a float; any other
                    // call yields an integer under the JIT call protocol.
                    setTy(out, regs, a, isIntrinsicCall(proto, code, pc) ? T_NUM : T_INT);
                }
            }
            case OpCode.OP_TAILCALL -> {
                // No fallthrough register: the callee result becomes ours.
            }
            default -> {}
        }
        return true;
    }

    private static void setTy(int[] row, int regs, int r, int t) {
        if (r >= 0 && r < regs) {
            row[r] = t;
        }
    }

    /**
     * Joins two abstract types at a control-flow merge. {@code T_INT} and
     * {@code T_NUM} join to {@code T_NUM}: every numeric use already emits a
     * runtime int/float dispatch, so a wider static type only costs a guard,
     * never correctness. Only an object/unknown collision is a real conflict
     * (the object protocol differs). Returns {@code -1} on conflict.
     */
    private static int mergeTy(int a, int b) {
        if (a == b) return a;
        if (a == T_UNKNOWN) return b;
        if (b == T_UNKNOWN) return a;
        if ((a == T_INT || a == T_NUM) && (b == T_INT || b == T_NUM)) {
            return T_NUM;
        }
        return -1;
    }

    /** Strict integer-subset eligibility; anything else stays interpreted. */
    public static boolean eligible(LuaProto proto) {
        return analyze(proto) != null;
    }

    private static void emitMove(MethodVisitor mv, int a, int b) {
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitInsn(AASTORE);
    }

    private static void emitStoreIntConst(MethodVisitor mv, int a, long v) {
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitLdcInsn(v);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /**
     * Emits {@code R[A] = <double result>} storing raw IEEE-754 bits and
     * tagging the register {@code TYPE_FLOAT} (never {@code D2L}, which would
     * truncate). Expects a double on the JVM stack.
     */
    private static void emitStoreFloatFromStack(MethodVisitor mv, int a) {
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
    }

    /** {@code R[A] = R[B] / R[C]}; always float, Lua coerces ints to double. */
    private static void emitDivNum(MethodVisitor mv, int a, int b, int c, int pc) {
        emitGuardNumber(mv, b, pc);
        emitGuardNumber(mv, c, pc);
        emitLoadDouble(mv, b);
        emitLoadDouble(mv, c);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ R[C]}; always float, uses C99 pow semantics. */
    private static void emitPowNum(MethodVisitor mv, int a, int b, int c, int pc) {
        emitGuardNumber(mv, b, pc);
        emitGuardNumber(mv, c, pc);
        emitLoadDouble(mv, b);
        emitLoadDouble(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaNumPow", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] // R[C]}: int floor-div, or float floor for numbers. */
    private static void emitIdivNum(MethodVisitor mv, int a, int b, int c, int pc) {
        emitGuardNumber(mv, b, pc);
        emitGuardNumber(mv, c, pc);
        Label floatLane = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, c);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        // int lane: guard /0 (interpreter raises), then floorDiv.
        emitLoadP(mv, c);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label divOk = new Label();
        mv.visitJumpInsn(IFNE, divOk);
        emitDeopt(mv, pc);
        mv.visitLabel(divOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorDiv", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b);
        emitLoadDouble(mv, c);
        mv.visitInsn(DDIV);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    /** {@code R[A] = R[B] % R[C]}: int floor-mod, or Lua float mod. */
    private static void emitModNum(MethodVisitor mv, int a, int b, int c, int pc) {
        emitGuardNumber(mv, b, pc);
        emitGuardNumber(mv, c, pc);
        Label floatLane = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, c);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        // int lane: n%0 raises in the interpreter.
        emitLoadP(mv, c);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label modOk = new Label();
        mv.visitJumpInsn(IFNE, modOk);
        emitDeopt(mv, pc);
        mv.visitLabel(modOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b);
        emitLoadDouble(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaFloatMod", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    /**
     * Two-register integer bitwise op ({@code BAND/BOR/BXOR}). Guards both
     * operands as integers (a float would raise in the interpreter, so the
     * deopt produces the same error).
     */
    private static void emitBitwiseNum(MethodVisitor mv, int a, int b, int c, int pc,
            int jvmIntOp, String kind) {
        emitGuardInt(mv, b, pc);
        emitGuardInt(mv, c, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /**
     * Two-register shift ({@code SHL}/{@code SHR}) with Lua's negative-count
     * semantics: a negative shift reverses direction, |count| >= 64 yields 0.
     * Implemented as a helper call to keep the emitted method small.
     */
    private static void emitShiftNum(MethodVisitor mv, int a, int b, int c, int pc, boolean left) {
        emitGuardInt(mv, b, pc);
        emitGuardInt(mv, c, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        String name = left ? "shiftLeft" : "shiftRight";
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", name, "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /** Immediate shift with Lua's negative-count semantics (SHLI/SHRI). */
    private static void emitShiftImm(MethodVisitor mv, int a, int b, int sc, int pc, boolean left) {
        emitGuardInt(mv, b, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        mv.visitLdcInsn((long) sc);
        String name = left ? "shiftLeft" : "shiftRight";
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", name, "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /** {@code R[A] = R[B] / K[C]}; always float (K may be int or float). */
    private static void emitDivK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, b, pc);
        emitLoadDouble(mv, b);
        mv.visitLdcInsn(kv);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ K[C]}; always float with C99 pow semantics. */
    private static void emitPowK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, b, pc);
        emitLoadDouble(mv, b);
        mv.visitLdcInsn(kv);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaNumPow", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
    }

    /** Extracts an integer/float constant as a {@link Number}. */
    private static Number numericConstant(LuaProto proto, int idx) {
        LuaValue k = proto.constants[idx];
        if (k instanceof LuaInteger li) {
            return li.toLong();
        }
        return ((org.luava.runtime.LuaFloat) k).toDouble();
    }

    /**
     * {@code R[A] = R[B] <op> R[C]} (+, -, *). Emits a numeric dispatch:
     * integer lane when both registers currently hold integers, float lane
     * (tagged FLOAT) otherwise. A non-number operand deopts, matching the
     * interpreter's non-metamethod arithmetic. Lua widens int/float results
     * per the same rule (int op int stays int, anything else is float).
     */
    private static void emitArithNum(MethodVisitor mv, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp) {
        emitGuardNumber(mv, b, pc);
        emitGuardNumber(mv, c, pc);
        Label floatLane = new Label();
        Label done = new Label();
        // Both integer? go integer lane, else float lane.
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, c);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        // Integer lane.
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, c);
        mv.visitInsn(LALOAD);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        // Float lane.
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b);
        emitLoadDouble(mv, c);
        mv.visitInsn(jvmFloatOp);
        // Store the raw IEEE-754 bits, not a numeric D2L conversion: the
        // register stack holds raw bits and getLuaValueFromRaw decodes them
        // with longBitsToDouble. D2L would truncate to an integer value.
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
        mv.visitLabel(done);
    }

    /**
     * {@code R[A] = R[B] <op> K[C]} (+, -, * / bitwise) for an integer
     * constant K[C]. Numeric dispatch as in {@link #emitArithNum}; the
     * constant is a compile-time integer, so only R[B]'s runtime tag is
     * tested (a statically-int R[B] takes the int lane).
     */
    private static void emitArithKNum(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp) {
        LuaValue k = proto.constants[c];
        if (!(k instanceof LuaInteger)) {
            // analyze() rejects this today; keep a hard guard anyway.
            emitDeopt(mv, pc);
            return;
        }
        long kv = ((LuaInteger) k).toLong();
        emitGuardNumber(mv, b, pc);
        if (jvmFloatOp < 0) {
            // Bitwise K-forms are integer-only (interpreter yields an error
            // for floats, which deopts here).
            emitGuardInt(mv, b, pc);
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, b);
            mv.visitInsn(LALOAD);
            mv.visitLdcInsn(kv);
            mv.visitInsn(jvmIntOp);
            mv.visitInsn(LASTORE);
            emitTagIntNull(mv, a);
            return;
        }
        Label floatLane = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitLdcInsn(kv);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b);
        mv.visitLdcInsn((double) kv);
        mv.visitInsn(jvmFloatOp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
        mv.visitLabel(done);
    }

    /**
     * Emits the tail-call form of the {@code math.sqrt(x)} intrinsic. The
     * function register {@code a} must still be the shared
     * {@code MathLib.SQRT} singleton (identity guard): anything else deopts,
     * so a script that reassigns {@code math.sqrt} or shadows {@code math}
     * keeps exact semantics via the interpreter. The argument must be a
     * number; the result is a raw-bit float stored as our own object-mode
     * return, matching {@code LuaFloat.valueOf(Math.sqrt(d))} exactly
     * (including the positive-nan/negative-zero behavior of Math.sqrt).
     */
    private static void emitSqrtTail(MethodVisitor mv, int a, int pc) {
        emitSqrtGuardAndCompute(mv, a, pc);
        // Box as LuaFloat (our object-mode return type).
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaFloat", "valueOf",
                "(D)Lorg/luava/runtime/LuaFloat;", false);
        mv.visitInsn(ARETURN);
    }

    /**
     * Emits the {@code R[a] = math.sqrt(R[a+1])} one-result call form. The
     * numeric result is kept in the unboxed float lane (raw IEEE bits) so it
     * can feed further JIT arithmetic without boxing.
     */
    private static void emitSqrtCall(MethodVisitor mv, int a, int pc) {
        emitSqrtGuardAndCompute(mv, a, pc);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
    }

    /**
     * Shared sqrt intrinsic core: identity-guards {@code R[a]} against the
     * {@code MathLib.SQRT} singleton, checks the argument is a number and
     * leaves {@code Math.sqrt(arg)} as a JVM double on the stack.
     */
    private static void emitSqrtGuardAndCompute(MethodVisitor mv, int a, int pc) {
        emitBuiltinIdentityGuard(mv, a, "SQRT", pc);
        // Argument must be a number.
        emitGuardNumber(mv, a + 1, pc);
        emitLoadDouble(mv, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "sqrt", "(D)D", false);
    }

    /**
     * Emits {@code R[a] = tostring(R[a+1])} inline under an identity guard on
     * the shared {@code BaseLib.TOSTRING} singleton. The helper renders the
     * primitive/string cases exactly and returns {@code null} otherwise, so a
     * value with a {@code __tostring} metatable (or a number-format override)
     * deopts to the interpreter instead of producing a different string.
     */
    private static void emitTostringCall(MethodVisitor mv, int a, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "TOSTRING",
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, pc);
        mv.visitLabel(ok);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "tostringInline",
                "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
        mv.visitInsn(DUP);
        Label rendered = new Label();
        mv.visitJumpInsn(IFNONNULL, rendered);
        mv.visitInsn(POP);
        emitDeopt(mv, pc);
        mv.visitLabel(rendered);
        emitStoreValueReg(mv, a);
    }

    /** Deopts unless {@code R[reg]} is the {@code MathLib.<field>} singleton. */
    private static void emitBuiltinIdentityGuard(MethodVisitor mv, int reg, String field, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, reg);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/MathLib", field,
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, pc);
        mv.visitLabel(ok);
    }

    /**
     * Emits the generic-for iterator-factory call {@code R[a] = pairs(t)} /
     * {@code ipairs(t)} / {@code gmatch(s,p)} inline. The factory register is
     * identity-guarded against the shared builtin singleton; an unrecognized
     * function, or a {@code pairs} target with an {@code __pairs} metamethod,
     * deopts structurally (no side effect before the deopt). The helper fills
     * the three registers the loop reserved ({@code R[a], R[a+1], R[a+2]})
     * with the PUC iterator triple.
     */
    private static void emitTForSetup(MethodVisitor mv, int a, int nArgs, int pc) {
        // The resolved triple is kept on the JVM operand stack and merged at
        // `matched`: every path pushes exactly one Varargs, so ASM's frame
        // computation sees a single consistent stack shape. No scratch local
        // is used (locals 6..8 are shared and 9+ hold fused-callee state).
        Label isPairs = new Label();
        Label isIpairs = new Label();
        Label isGmatch = new Label();
        Label matched = new Label();
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "PAIRS",
                "Lorg/luava/runtime/LuaFunction;");
        mv.visitJumpInsn(IF_ACMPEQ, isPairs);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "IPAIRS",
                "Lorg/luava/runtime/LuaFunction;");
        mv.visitJumpInsn(IF_ACMPEQ, isIpairs);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/StringLib", "GMATCH",
                "Lorg/luava/runtime/LuaFunction;");
        mv.visitJumpInsn(IF_ACMPEQ, isGmatch);
        emitDeopt(mv, pc, true);
        mv.visitLabel(isPairs);
        emitGetValueReg(mv, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/standard/BaseLib", "pairsFast",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/Varargs;", false);
        // pairsFast returns null for an __pairs target: deopt to the handler.
        mv.visitInsn(DUP);
        Label pairsOk = new Label();
        mv.visitJumpInsn(IFNONNULL, pairsOk);
        mv.visitInsn(POP);
        emitDeopt(mv, pc);
        mv.visitLabel(pairsOk);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitJumpInsn(GOTO, matched);
        mv.visitLabel(isIpairs);
        emitGetValueReg(mv, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/standard/BaseLib", "ipairsFast",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/Varargs;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitJumpInsn(GOTO, matched);
        mv.visitLabel(isGmatch);
        // R[a+1]=string, R[a+2]=pattern, optional R[a+3]=init are exactly the
        // gmatch builtin arguments; delegate to it (it returns the iterator
        // closure) and let tforCall recognize that iterator.
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        ldcInt(mv, nArgs);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "gmatchSetup",
                "([J[B[Lorg/luava/runtime/LuaValue;II)Lorg/luava/runtime/Varargs;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitJumpInsn(GOTO, matched);
        mv.visitLabel(matched);
        // Unpack the triple into R[a..a+3], nil-filling missing slots. Local 8
        // is the shared ref scratch (dead at a TFORCALL), so it holds the
        // Varargs from whichever factory path ran.
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a);
        mv.visitInsn(IADD);
        ldcInt(mv, 4);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "unpackTForSetup",
                "([J[B[Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/Varargs;II)V", false);
    }

    /**
     * Emits {@code R[a] = math.floor(R[a+1])}. An integer argument is copied
     * unchanged (exact over the whole 64-bit range, which a round-trip through
     * {@code double} would not be); a float argument goes through
     * {@code Math.floor} and {@link JitRuntime#storeFloored} for PUC subtype
     * semantics.
     */
    private static void emitFloorCall(MethodVisitor mv, int a, int pc) {
        emitBuiltinIdentityGuard(mv, a, "FLOOR", pc);
        Label intArg = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a + 1);
        mv.visitInsn(BALOAD);
        mv.visitVarInsn(ISTORE, 7);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intArg);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        Label floatArg = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, floatArg);
        emitDeopt(mv, pc);
        mv.visitLabel(floatArg);
        emitLoadDouble(mv, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "storeFloored",
                "(D[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(intArg);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 1);
        mv.visitInsn(LALOAD);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        ldcInt(mv, TYPE_INT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
        mv.visitLabel(done);
    }

    /**
     * Emits the fused {@code R[af] = math.floor(math.sqrt(R[af+2]))} idiom
     * (the {@code for i = 2, math.floor(math.sqrt(N))} loop bound). Both
     * singletons are identity-guarded; the argument must be a number.
     */
    private static void emitFloorSqrtCall(MethodVisitor mv, int af, int pc) {
        emitBuiltinIdentityGuard(mv, af, "FLOOR", pc);
        emitBuiltinIdentityGuard(mv, af + 1, "SQRT", pc);
        emitGuardNumber(mv, af + 2, pc);
        emitLoadDouble(mv, af + 2);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "sqrt", "(D)D", false);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, af);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "storeFloored",
                "(D[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
    }

    /** Deopts unless register {@code reg} holds TYPE_INT or TYPE_FLOAT. */
    private static void emitGuardNumber(MethodVisitor mv, int reg, int pc) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        mv.visitInsn(BALOAD);
        mv.visitVarInsn(ISTORE, 7 + 0); // scratch int local 7 (long scratch is 6)
        Label isNum = new Label();
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, isNum);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPEQ, isNum);
        emitDeopt(mv, pc);
        mv.visitLabel(isNum);
    }

    /** Pushes register {@code reg} as a JVM double (int value or raw float). */
    private static void emitLoadDouble(MethodVisitor mv, int reg) {
        Label isFloat = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, isFloat);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, reg);
        mv.visitInsn(LALOAD);
        mv.visitInsn(L2D);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(isFloat);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, reg);
        mv.visitInsn(LALOAD);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitLabel(done);
    }

    /** {@code R[A] = R[B] // K[C]} with Lua floor-division semantics. */
    private static void emitIdivK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        emitGuardInt(mv, b, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitLdcInsn(kv);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorDiv", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /** {@code R[A] = R[B] % K[C]} with Lua floor-modulo semantics. */
    private static void emitModK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        emitGuardInt(mv, b, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitLdcInsn(kv);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /**
     * Integer numeric {@code for} setup, mirroring {@code doForPrep}'s fast
     * path: R[A]=init, R[A+1]=limit, R[A+2]=step, R[A+3]=control. All three
     * must be ints or the whole call deopts to the interpreter (which also
     * handles the float/coercion and step-zero cases with exact error
     * decoration). On the non-skip path R[A+1] is replaced by the unsigned
     * trip count, exactly like the interpreter's FORLOOP expects. Runs once
     * per loop, so the redundant reloads are free; no scratch locals are
     * used (the slots 6..8 are shared jit scratch and mixing types across
     * control-flow joins is a {@code VerifyError}).
     */
    private static void emitForPrep(MethodVisitor mv, int a, int bx, int pc, Label[] labels) {
        emitGuardInt(mv, a, pc);
        emitGuardInt(mv, a + 1, pc);
        emitGuardInt(mv, a + 2, pc);
        // step == 0 -> deopt (interpreter raises "'for' step is zero")
        emitLoadP(mv, a + 2);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label stepOk = new Label();
        mv.visitJumpInsn(IFNE, stepOk);
        emitDeopt(mv, pc);
        mv.visitLabel(stepOk);
        // control R[A+3] = init
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 3);
        emitLoadP(mv, a);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a + 3);
        // step sign
        emitLoadP(mv, a + 2);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label negStep = new Label();
        mv.visitJumpInsn(IFLE, negStep);
        // positive step: skip when init > limit
        emitLoadP(mv, a);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCMP);
        Label noSkipPos = new Label();
        mv.visitJumpInsn(IFLE, noSkipPos);
        mv.visitJumpInsn(GOTO, labels[pc + 1 + bx]);
        mv.visitLabel(noSkipPos);
        // count = divideUnsigned(limit - init, step)
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 1);
        emitLoadP(mv, a + 1);
        emitLoadP(mv, a);
        mv.visitInsn(LSUB);
        emitLoadP(mv, a + 2);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "divideUnsigned", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        Label endPrep = new Label();
        mv.visitJumpInsn(GOTO, endPrep);
        mv.visitLabel(negStep);
        // negative step: skip when init < limit
        emitLoadP(mv, a);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCMP);
        Label noSkipNeg = new Label();
        mv.visitJumpInsn(IFGE, noSkipNeg);
        mv.visitJumpInsn(GOTO, labels[pc + 1 + bx]);
        mv.visitLabel(noSkipNeg);
        // count = divideUnsigned(init - limit, -step)
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 1);
        emitLoadP(mv, a);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LSUB);
        emitLoadP(mv, a + 2);
        mv.visitInsn(LNEG);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "divideUnsigned", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        mv.visitLabel(endPrep);
    }

    /**
     * Integer numeric {@code for} step, mirroring {@code OP_FORLOOP}'s fast
     * path: R[A+1] is the unsigned remaining count; while it is non-zero,
     * decrement it, advance R[A] by R[A+2] and mirror the control into
     * R[A+3], then jump back. Registers are ints by construction (FORPREP
     * deopts otherwise), so no per-iteration type guard is needed.
     */
    private static void emitForLoop(MethodVisitor mv, int a, int bx, int pc, Label[] labels) {
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCONST_0);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false);
        Label exit = new Label();
        mv.visitJumpInsn(IFLE, exit);
        // R[A+1]--
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 1);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCONST_1);
        mv.visitInsn(LSUB);
        mv.visitInsn(LASTORE);
        // R[A+3] = R[A] + R[A+2]  (computed from the old R[A])
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 3);
        emitLoadP(mv, a);
        emitLoadP(mv, a + 2);
        mv.visitInsn(LADD);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a + 3);
        // R[A] = R[A+3]
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, a + 3);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, labels[pc + 1 - bx]);
        mv.visitLabel(exit);
    }

    /**
     * {@code OP_TFORPREP}: push the loop-state slot (R[A+3]) as a to-be-closed
     * variable, then continue. The helper mirrors the interpreter exactly; it
     * runs once per loop entry, so the call is free in steady state.
     */
    private static void emitTForPrep(MethodVisitor mv, int a, int bx, int pc, Label[] labels) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 3);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "tforPrep",
                "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        // PUC OP_TFORPREP is an unconditional jump to the matching TFORCALL,
        // so the body never runs before the first iterator step.
        mv.visitJumpInsn(GOTO, labels[pc + 1 + bx]);
    }

    /**
     * {@code OP_TFORCALL}: ask {@link JitRuntime#tforCall} to step the
     * built-in iterator ({@code next}/{@code ipairsaux}/{@code gmatch}) and
     * write the {@code c} result registers. A {@code false} return means the
     * iterator is not one the compiled body can complete atomically (a custom
     * closure, a metatable-sensitive access); deopt before any side effect so
     * the interpreter re-executes this TFORCALL exactly once.
     */
    private static void emitTForCall(MethodVisitor mv, int a, int c, int pc) {
        // The helper stores the results and throws DeoptSignal itself when the
        // iterator is one it cannot complete atomically. Branchless at the
        // call site, so ASM's frame computation sees a single stack shape.
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a);
        mv.visitInsn(IADD);
        ldcInt(mv, c);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "tforCall",
                "([J[B[Lorg/luava/runtime/LuaValue;III)V", false);
    }

    /**
     * {@code OP_TFORLOOP}: if R[A+4] is non-nil, copy it to the loop control
     * R[A+2] and jump back to the body. The value is a runtime object, so the
     * copy is a triple-stack move and truthiness is a tag check (nil only).
     */
    private static void emitTForLoop(MethodVisitor mv, int a, int bx, int pc, Label[] labels) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a + 4);
        mv.visitInsn(BALOAD);
        // NIL is 0; anything else is a live control value.
        Label isNil = new Label();
        mv.visitJumpInsn(IFEQ, isNil);
        emitMove(mv, a + 2, a + 4);
        mv.visitJumpInsn(GOTO, labels[pc + 1 - bx]);
        mv.visitLabel(isNil);
    }

    /** Pushes {@code p[base+reg]} (an unboxed long) onto the JVM stack. */
    private static void emitLoadP(MethodVisitor mv, int reg) {
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, reg);
        mv.visitInsn(LALOAD);
    }

    private static void emitCmpImm(
            MethodVisitor mv, int a, int sb, int k, int pc, Label[] labels, int jumpIfTrue, int jumpIfFalse) {
        emitGuardInt(mv, a, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitInsn(LALOAD);
        mv.visitLdcInsn((long) sb);
        mv.visitInsn(LCMP);
        Label condTrue = new Label();
        mv.visitJumpInsn(jumpIfTrue, condTrue);
        // Condition false: cond == false; skip-next iff false != (k==1).
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        }
        mv.visitLabel(condTrue);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        }
    }

    /**
     * Two-register numeric comparison ({@code LT}/{@code LE}), with a mixed
     * int/float runtime lane and a deopt for any non-number. Mirrors the
     * interpreter's {@code luaV_lessthan}/{@code luaV_lessequal} numeric
     * fast path; the mixed cases use {@code BytecodeVM}'s exact helpers so
     * NaN/±0 ordering matches bit-for-bit. On success jumps to
     * {@code pc+1}/{@code pc+2} per the {@code k} skip flag.
     */
    private static void emitCmpRR(MethodVisitor mv, int a, int b, int k, int pc, Label[] labels,
            boolean orEqual) {
        emitGuardNumber(mv, a, pc);
        emitGuardNumber(mv, b, pc);
        // condition = R[A] < R[B] (or <= when orEqual). DCMPG pushes +1 for
        // NaN so both IFLT and IFLE are false (matches Lua NaN ordering).
        int cmpOp = orEqual ? IFLE : IFLT;
        Label intLane = new Label();
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intLane);
        // Float lane: promote both to double.
        emitLoadDouble(mv, a);
        emitLoadDouble(mv, b);
        mv.visitInsn(DCMPG);
        mv.visitJumpInsn(cmpOp, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(intLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        Label intInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, intInt);
        // R[A] int, R[B] float.
        emitLoadP(mv, a);
        mv.visitInsn(L2D);
        emitLoadDouble(mv, b);
        mv.visitInsn(DCMPG);
        mv.visitJumpInsn(cmpOp, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(intInt);
        emitLoadP(mv, a);
        emitLoadP(mv, b);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(cmpOp, doneTrue);
        mv.visitLabel(doneFalse);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        }
        mv.visitLabel(doneTrue);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        }
    }

    /**
     * Two-register equality ({@code EQ}). Handles int/float/boolean/nil
     * directly; anything else (tables, strings with metamethods, functions)
     * deopts to the interpreter's full {@code luaEquals}. Note the mixed
     * int/float compare must treat {@code 1 == 1.0} as true, so a float lane
     * promotes both operands.
     */
    private static void emitEqRR(MethodVisitor mv, LuaProto proto, int a, int b, int k, int pc,
            Label[] labels) {
        Label boolLane = new Label();
        Label nilLane = new Label();
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        mv.visitInsn(BALOAD);
        mv.visitVarInsn(ISTORE, 7);
        // Numbers (int or float) compare by value with promotion so that
        // 1 == 1.0 holds. Any int/float mix with a non-number is unequal.
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        Label aIsInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, aIsInt);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        Label aIsFloat = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, aIsFloat);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_BOOLEAN);
        mv.visitJumpInsn(IF_ICMPEQ, boolLane);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_NIL);
        mv.visitJumpInsn(IF_ICMPEQ, nilLane);
        emitDeopt(mv, pc);
        // --- R[A] is INT ---
        mv.visitLabel(aIsInt);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        mv.visitVarInsn(ISTORE, 7);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        Label bInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, bInt);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        emitLoadDouble(mv, a);
        emitLoadDouble(mv, b);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(bInt);
        emitLoadP(mv, a);
        emitLoadP(mv, b);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        // --- R[A] is FLOAT: R[B] must be numeric; promote both ---
        mv.visitLabel(aIsFloat);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        mv.visitVarInsn(ISTORE, 7);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        Label bNum = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, bNum);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        mv.visitLabel(bNum);
        emitLoadDouble(mv, a);
        emitLoadDouble(mv, b);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        // --- R[A] is BOOLEAN: R[B] must be the same boolean ---
        mv.visitLabel(boolLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_BOOLEAN);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        emitLoadP(mv, a);
        emitLoadP(mv, b);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        // --- R[A] is NIL: equal only if R[B] is nil ---
        mv.visitLabel(nilLane);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_NIL);
        mv.visitJumpInsn(IF_ICMPEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(doneFalse);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        }
        mv.visitLabel(doneTrue);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        }
    }

    /**
     * {@code EQK}: R[A] == K[B]. Only number/boolean/nil constants are
     * compiled; string/table constants deopt to the interpreter (which may
     * invoke {@code __eq}), so semantics stay exact.
     */
    private static void emitEqK(MethodVisitor mv, LuaProto proto, int a, int b, int k, int pc,
            Label[] labels) {
        LuaValue kv = proto.constants[b];
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        if (kv instanceof LuaInteger ki) {
            long c = ki.toLong();
            // R[A] must be int or float; int compares directly, float promotes.
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_INT);
            Label asFloat = new Label();
            mv.visitJumpInsn(IF_ICMPNE, asFloat);
            emitLoadP(mv, a);
            mv.visitLdcInsn(c);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(asFloat);
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadDouble(mv, a);
            mv.visitLdcInsn((double) c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv instanceof org.luava.runtime.LuaFloat kf) {
            double c = kf.toDouble();
            // R[A] must be numeric; promote both.
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_INT);
            Label asFloat = new Label();
            mv.visitJumpInsn(IF_ICMPNE, asFloat);
            emitLoadP(mv, a);
            mv.visitInsn(L2D);
            mv.visitLdcInsn(c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(asFloat);
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadDouble(mv, a);
            mv.visitLdcInsn(c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv instanceof org.luava.runtime.LuaBoolean kb) {
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_BOOLEAN);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadP(mv, a);
            mv.visitLdcInsn((long) (kb.toBoolean() ? 1 : 0));
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv.isNil()) {
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_NIL);
            mv.visitJumpInsn(IF_ICMPEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else {
            // String/other constant: full equality may run metamethods.
            emitDeopt(mv, pc);
            return;
        }
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        }
        mv.visitLabel(doneTrue);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        }
    }

    /**
     * {@code TEST A k}: {@code if (truthy(R[A]) != (k==1)) pc++}. When the
     * condition holds, control falls through to {@code pc+1}; otherwise the
     * next instruction is skipped ({@code pc+2}).
     */
    private static void emitTest(MethodVisitor mv, int a, int k, int pc, Label[] labels) {
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "isTruthy",
                "([J[BI)Z", false);
        // isTruthy == (k==1) -> fall through;  else -> skip next.
        Label truthy = new Label();
        mv.visitJumpInsn(IFNE, truthy);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        }
        mv.visitLabel(truthy);
        if (k == 1) {
            mv.visitJumpInsn(GOTO, labels[pc + 1]);
        } else {
            mv.visitJumpInsn(GOTO, labels[pc + 2]);
        }
    }

    /**
     * {@code TESTSET A B k}: like TEST on R[B], but on the fallthrough edge
     * copies R[B] into R[A] (mirrors {@code OP_TESTSET}).
     */
    private static void emitTestSet(MethodVisitor mv, int a, int b, int k, int pc, Label[] labels) {
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "isTruthy",
                "([J[BI)Z", false);
        // The copy happens iff truthy == (k==1); otherwise skip the next
        // instruction. (Mirrors OP_TESTSET's `truthy != (k==1)` test.)
        Label skip = new Label();
        if (k == 1) {
            mv.visitJumpInsn(IFEQ, skip); // falsy -> no copy
        } else {
            mv.visitJumpInsn(IFNE, skip); // truthy -> no copy
        }
        copyRegTriple(mv, a, b);
        mv.visitJumpInsn(GOTO, labels[pc + 1]);
        mv.visitLabel(skip);
        mv.visitJumpInsn(GOTO, labels[pc + 2]);
    }

    /** Copies the triple-stack register {@code src} into {@code dst}. */
    private static void copyRegTriple(MethodVisitor mv, int dst, int src) {
        for (int local : new int[] {2, 3, 4}) {
            mv.visitVarInsn(ALOAD, local);
            emitIndex(mv, dst);
            mv.visitVarInsn(ALOAD, local);
            emitIndex(mv, src);
            if (local == 2) {
                mv.visitInsn(LALOAD);
                mv.visitInsn(LASTORE);
            } else if (local == 3) {
                mv.visitInsn(BALOAD);
                mv.visitInsn(BASTORE);
            } else {
                mv.visitInsn(AALOAD);
                mv.visitInsn(AASTORE);
            }
        }
    }

    /**
     * Call site with three tiers: hoisted self (classified at entry),
     * resolved self (same proto, direct INVOKESTATIC), or another pure JIT
     * proto via the {@link JitRuntime} helper. Anything else deopts to the
     * interpreter.
     */
    private static void emitCall(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount,
            boolean resultUsed) {
        Label done = new Label();
        if (hoistSlot >= 0) {
            // Fast tier: entry-classified self callee straight into the
            // prologue-free inner entry (upvalues provably unchanged).
            mv.visitVarInsn(ILOAD, 9 + 2 * hoistSlot);
            Label general = new Label();
            mv.visitJumpInsn(IFEQ, general);
            mv.visitVarInsn(ALOAD, 10 + 2 * hoistSlot);
            mv.visitVarInsn(ASTORE, 8);
            emitInnerInvoke(mv, owner, proto, a, nArgs, resumePc, fusedBList, fusedBCount);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(general);
        }
        // Resolve the callee closure (fused: straight from the upvalue).
        if (fusedB >= 0) {
            mv.visitVarInsn(ALOAD, 1);
            ldcInt(mv, fusedB);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                    "()Lorg/luava/runtime/LuaValue;", false);
        } else {
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitInsn(AALOAD);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/bytecode/LuaClosure");
        Label isClosure = new Label();
        mv.visitJumpInsn(IFNE, isClosure);
        // A builtin (Java-backed) callee is not a LuaClosure and never will
        // be, so this deopt is expected on every invocation; it must not
        // consume the small guard-failure budget and disarm the hot prefix.
        emitDeopt(mv, resumePc, true);
        mv.visitLabel(isClosure);
        // Same proto -> direct INVOKESTATIC; else a pure JIT proto -> helper.
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        Label selfProto = new Label();
        Label generalCall = new Label();
        mv.visitJumpInsn(IF_ACMPNE, generalCall);
        mv.visitLabel(selfProto);
        emitDirectInvoke(mv, owner, proto, a, nArgs, resumePc);
        mv.visitJumpInsn(GOTO, done);
        // General monomorphic call: callee must be compiled and pure, with
        // an exact argument-count match (missing args would read stale regs).
        mv.visitLabel(generalCall);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "jitCode",
                "Lorg/luava/runtime/jit/JitCode;");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 21);
        Label hasJit = new Label();
        mv.visitJumpInsn(IFNONNULL, hasJit);
        // Callee not (yet) compiled: expected, structural (see isClosure).
        emitDeopt(mv, resumePc, true);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "pure", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, resumePc, true);
        mv.visitLabel(isPure);
        // The integer call protocol carries an unboxed long result; an
        // object-returning callee cannot feed it.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsInt", "Z");
        Label returnsIntOk = new Label();
        mv.visitJumpInsn(IFNE, returnsIntOk);
        emitDeopt(mv, resumePc, true);
        mv.visitLabel(returnsIntOk);
        // A void callee returns a sentinel long that is indistinguishable from
        // the integer 0. When the call result is consumed (a value context) the
        // interpreter must run it; a discarded statement call is harmless and
        // stays compiled. Structural deopt: expected for this shape.
        if (resultUsed) {
            mv.visitVarInsn(ALOAD, 21);
            mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsVoid", "Z");
            Label notVoidCall = new Label();
            mv.visitJumpInsn(IFEQ, notVoidCall);
            emitDeopt(mv, resumePc, true);
            mv.visitLabel(notVoidCall);
        }
        ldcInt(mv, nArgs);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        Label arityOk = new Label();
        // Extra arguments are ignored (caller scratch); missing ones are
        // nil-filled exactly like the interpreter, so neither deopts.
        mv.visitJumpInsn(IF_ICMPGE, arityOk);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1 + nArgs);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        mv.visitLabel(arityOk);
        // Dynamic capacity guard with the callee's own maxStack.
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "maxStackSize", "I");
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOkGen = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOkGen);
        emitDeopt(mv, resumePc);
        mv.visitLabel(capOkGen);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "invoke",
                "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J",
                resumePc);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitLabel(done);
    }

    /**
     * Bulk-moves {@code count} triple-stack registers from
     * {@code base+src} to {@code base+dst} (overlap-safe, used by TAILCALL
     * argument shifting).
     */
    private static void arraycopyTriple(MethodVisitor mv, int src, int dst, int count) {
        for (int local : new int[] {2, 3, 4}) {
            mv.visitVarInsn(ALOAD, local);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, src);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ALOAD, local);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, dst);
            mv.visitInsn(IADD);
            ldcInt(mv, count);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "arraycopy",
                    "(Ljava/lang/Object;ILjava/lang/Object;II)V", false);
        }
    }

    /**
     * Tail call: shifts the arguments down to our own window, moves the
     * function to our result slot, invokes the callee with our base, and
     * returns its single result directly. The callee must be pure (same
     * rule as CALL); a nested deopt converts to this pc so the interpreter
     * re-executes the tail call with committed prefix state intact.
     */
    private static void emitTailCall(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount, Label[] labels) {
        if (hoistSlot >= 0) {
            // Self tail recursion on the same closure: reuse our own window
            // as a true loop (no Java stack growth), then re-enter at pc 0.
            // Sound: identical closure, identical upvalue array, and missing
            // params are nil-filled exactly like the interpreter.
            mv.visitVarInsn(ILOAD, 9 + 2 * hoistSlot);
            Label general = new Label();
            mv.visitJumpInsn(IFEQ, general);
            if (nArgs > 0) {
                arraycopyTriple(mv, a + 1, 0, nArgs);
            }
            if (nArgs < proto.numParams) {
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 4);
                mv.visitVarInsn(ILOAD, 5);
                ldcInt(mv, nArgs);
                mv.visitInsn(IADD);
                mv.visitVarInsn(ILOAD, 5);
                ldcInt(mv, proto.numParams);
                mv.visitInsn(IADD);
                mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                        "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
            }
            mv.visitJumpInsn(GOTO, labels[0]);
            mv.visitLabel(general);
        }
        // Resolve from the original slot (or straight from the upvalue).
        if (fusedB >= 0) {
            mv.visitVarInsn(ALOAD, 1);
            ldcInt(mv, fusedB);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                    "()Lorg/luava/runtime/LuaValue;", false);
        } else {
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitInsn(AALOAD);
        }
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/bytecode/LuaClosure");
        Label isClosure = new Label();
        mv.visitJumpInsn(IFNE, isClosure);
        emitDeopt(mv, resumePc);
        mv.visitLabel(isClosure);
        // Shift arguments down onto our own window now that the callee sits
        // safely in local 8; arraycopy is overlap-safe either way.
        if (nArgs > 0) {
            arraycopyTriple(mv, a + 1, 0, nArgs);
        }
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        Label selfProto = new Label();
        Label generalCall = new Label();
        mv.visitJumpInsn(IF_ACMPNE, generalCall);
        mv.visitLabel(selfProto);
        emitTailDirectInvoke(mv, owner, proto, nArgs, resumePc);
        mv.visitInsn(LRETURN);
        mv.visitLabel(generalCall);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "jitCode",
                "Lorg/luava/runtime/jit/JitCode;");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 21);
        Label hasJit = new Label();
        mv.visitJumpInsn(IFNONNULL, hasJit);
        emitDeopt(mv, resumePc);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "pure", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, resumePc);
        mv.visitLabel(isPure);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsInt", "Z");
        Label returnsIntOk = new Label();
        mv.visitJumpInsn(IFNE, returnsIntOk);
        emitDeopt(mv, resumePc);
        mv.visitLabel(returnsIntOk);
        // A void callee forwards zero results; the compiled tail path can
        // only return one long (the sentinel would be read as integer 0 by
        // the caller), so run it in the interpreter. Structural.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsVoid", "Z");
        Label notVoidTail = new Label();
        mv.visitJumpInsn(IFEQ, notVoidTail);
        emitDeopt(mv, resumePc, true);
        mv.visitLabel(notVoidTail);
        ldcInt(mv, nArgs);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        Label arityOk = new Label();
        // Tail callee reuses our window: extra args ignored, missing ones
        // nil-filled exactly like the interpreter.
        mv.visitJumpInsn(IF_ICMPGE, arityOk);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, nArgs);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        mv.visitLabel(arityOk);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "maxStackSize", "I");
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOkGen = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOkGen);
        emitDeopt(mv, resumePc);
        mv.visitLabel(capOkGen);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "invoke",
                "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J",
                resumePc);
        mv.visitInsn(LRETURN);
    }

    /** Tail-call direct self-invocation reusing our own window as base. */
    private static void emitTailDirectInvoke(MethodVisitor mv, String owner, LuaProto proto, int nArgs,
            int resumePc) {
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, proto.maxStackSize);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOk = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOk);
        emitDeopt(mv, resumePc);
        mv.visitLabel(capOk);
        if (nArgs < proto.numParams) {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, nArgs);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, proto.numParams);
            mv.visitInsn(IADD);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                    "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        }
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        emitGuardedInvoke(mv, owner, EXEC_NAME, EXEC_DESC, resumePc);
        mv.visitInsn(LRETURN);
    }

    /** Direct INVOKESTATIC into this proto's own exec (self-recursion). */
    private static void emitDirectInvoke(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc) {
        // Capacity guard for the callee window.
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1 + proto.maxStackSize);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOk = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOk);
        emitDeopt(mv, resumePc);
        mv.visitLabel(capOk);
        // Missing arguments are nil-filled exactly like the interpreter.
        if (nArgs < proto.numParams) {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1 + nArgs);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1 + proto.numParams);
            mv.visitInsn(IADD);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                    "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        }
        // Direct self-recursion: exec(callee, callee.upvals, p, t, o, base+a+1).
        // A nested deopt is converted to this call's resumePc: the caller resumes
        // here and re-invokes the (pure) callee from scratch.
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        emitGuardedInvoke(mv, owner, EXEC_NAME, EXEC_DESC, resumePc);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /**
     * Emits an INVOKESTATIC whose {@link DeoptSignal} is converted to the
     * enclosing call resumePc, so the interpreter resumes the current frame at
     * the call instead of unwinding with a foreign pc.
     */
    private static void emitGuardedInvoke(MethodVisitor mv, String owner, String name, String desc, int pc) {
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label handler = new Label();
        Label cont = new Label();
        mv.visitLabel(tryStart);
        mv.visitMethodInsn(INVOKESTATIC, owner, name, desc, false);
        mv.visitLabel(tryEnd);
        mv.visitJumpInsn(GOTO, cont);
        mv.visitLabel(handler);
        mv.visitInsn(POP);
        emitDeopt(mv, pc);
        mv.visitLabel(cont);
        mv.visitTryCatchBlock(tryStart, tryEnd, handler, "org/luava/runtime/jit/DeoptSignal");
    }

    /**
     * Prologue-free self-recursion into {@code execInner}. The caller passes
     * its entry-verified closures down, so the callee skips re-verification.
     * Sound because the whole chain shares one closure (callee == self) and
     * upvalues cannot change mid-flight.
     */
    private static void emitInnerInvoke(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc, int[] fusedBList, int fusedBCount) {
        // Capacity guard for the callee window.
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1 + proto.maxStackSize);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOk = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOk);
        emitDeopt(mv, resumePc);
        mv.visitLabel(capOk);
        // Missing arguments are nil-filled exactly like the interpreter.
        if (nArgs < proto.numParams) {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1 + nArgs);
            mv.visitInsn(IADD);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1 + proto.numParams);
            mv.visitInsn(IADD);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                    "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        }
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        for (int i = 0; i < fusedBCount; i++) {
            mv.visitVarInsn(ALOAD, 10 + 2 * i);
        }
        emitGuardedInvoke(mv, owner, EXEC_INNER_NAME, innerDesc(fusedBCount), resumePc);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /**
     * Pushes the assigned value as a LuaValue: a constant when {@code k==1},
     * otherwise the boxed register. Leaves it on the stack.
     */
    private static void emitBoxValue(MethodVisitor mv, LuaProto proto, int reg, int c, int k, int pc) {
        if (k == 1) {
            emitLoadConst(mv, c);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, reg);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "getLuaValue",
                    "([J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
        }
    }

    private static void emitSetTabUp(MethodVisitor mv, LuaProto proto, int a, int b, int c, int k, int pc) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        emitLoadConst(mv, b);
        emitBoxValue(mv, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "set",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitSetTable(MethodVisitor mv, LuaProto proto, int a, int b, int c, int k, int pc) {
        // Key dispatch: integer keys go through rawsetInt on the array part;
        // object (string) keys through rawset on a metatable-free table. A
        // metatable could run __newindex, so the plain-table guard deopts.
        // Scratch local 9 is free: a metatable-free table write never enters
        // a callee and fused callee locals exist only in pure protos.
        Label intKey = new Label();
        Label objKey = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, b);
        mv.visitInsn(BALOAD);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intKey);
        ldcInt(mv, TYPE_OBJECT);
        mv.visitJumpInsn(IF_ICMPEQ, objKey);
        emitDeopt(mv, pc);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitGuardObject(mv, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitVarInsn(LSTORE, 6);
        emitBoxValue(mv, proto, c, c, k, pc);
        mv.visitVarInsn(ASTORE, 9);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitVarInsn(LLOAD, 6);
        mv.visitVarInsn(ALOAD, 9);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                "(JLorg/luava/runtime/LuaValue;)V", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(objKey);
        emitGuardObject(mv, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 9);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitVarInsn(ALOAD, 9);
        emitBoxValue(mv, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawset",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
        mv.visitLabel(done);
    }

    private static void emitSetI(MethodVisitor mv, LuaProto proto, int a, int b, int c, int k, int pc) {
        emitGuardObject(mv, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        mv.visitInsn(POP);
        emitBoxValue(mv, proto, c, c, k, pc);
        mv.visitVarInsn(ASTORE, 9);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitLdcInsn((long) b);
        mv.visitVarInsn(ALOAD, 9);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                "(JLorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitSetField(MethodVisitor mv, LuaProto proto, int a, int b, int c, int k, int pc) {
        emitGuardObject(mv, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        emitLoadConst(mv, b);
        emitBoxValue(mv, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "set",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
    }
    private static void emitLoadConst(MethodVisitor mv, int c) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "constants",
                "[Lorg/luava/runtime/LuaValue;");
        ldcInt(mv, c);
        mv.visitInsn(AALOAD);
    }

    /**
     * Guards that the LuaValue in local 8 is a metatable-free LuaTable,
     * leaving the checked table on the stack. Anything else deopts (a
     * metamethod could observe or alter state).
     */
    private static void emitGuardPlainTable(MethodVisitor mv, int pc) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, pc);
        mv.visitLabel(isTable);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "getMetatable",
                "()Lorg/luava/runtime/LuaTable;", false);
        Label noMeta = new Label();
        mv.visitJumpInsn(IFNULL, noMeta);
        emitDeopt(mv, pc);
        mv.visitLabel(noMeta);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
    }

    private static void emitStoreValueReg(MethodVisitor mv, int a) {
        // Expects the LuaValue on the stack; stores via setLuaValue.
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "setLuaValue",
                "([J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)V", false);
    }

    /**
     * {@code R[A+1] = R[B]; R[A] = R[B][K[C] or R[C]]} (method lookup for
     * {@code obj:method()}). The raw-hit fast lane mirrors
     * {@code emitGetField}; a miss deopts so the interpreter can resolve
     * {@code __index}. On success the receiver lands in {@code A+1} and the
     * method in {@code A}, ready for the following CALL.
     */
    private static void emitSelf(MethodVisitor mv, LuaProto proto, int a, int b, int c, int k, int pc) {
        // Resolve R[B][key] with a raw-hit fast lane. Push the closure, the
        // three stacks, objIdx, then the key on top, so the helper's trailing
        // LuaValue parameter works without a scratch local; reading a register
        // after pushing arrays is stack-neutral.
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, b);
        mv.visitInsn(IADD);
        if (k == 1) {
            emitLoadConst(mv, c);
        } else {
            emitGuardObject(mv, c, pc);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitInsn(AALOAD);
        }
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "selfMethod",
                "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        mv.visitInsn(DUP);
        Label selfOk = new Label();
        mv.visitJumpInsn(IFNONNULL, selfOk);
        mv.visitInsn(POP);
        emitDeopt(mv, pc);
        mv.visitLabel(selfOk);
        // method -> R[A]
        emitStoreValueReg(mv, a);
        // receiver -> R[A+1]
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a + 1);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "setLuaValue",
                "([J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitGetTabUp(MethodVisitor mv, int a, int b, int c, int pc) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        // lt.get(k[c])
        emitLoadConst(mv, c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "get",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueReg(mv, a);
    }

    private static void emitGetTable(MethodVisitor mv, int a, int b, int c, int pc) {
        // Key dispatch: integer keys use the array-part fast lane; object
        // keys (typically strings, e.g. t["key_"..i]) use rawget on a plain
        // table. Any other key type deopts.
        Label intKey = new Label();
        Label objKey = new Label();
        Label done = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, c);
        mv.visitInsn(BALOAD);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intKey);
        ldcInt(mv, TYPE_OBJECT);
        mv.visitJumpInsn(IF_ICMPEQ, objKey);
        emitDeopt(mv, pc);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitGuardObject(mv, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        // lt.rawgetInt(p[base+c])
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, c);
        mv.visitInsn(LALOAD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetInt",
                "(J)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueReg(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(objKey);
        // A raw miss can still resolve via __index, so require non-nil.
        emitGuardObject(mv, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, c);
        mv.visitInsn(AALOAD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawget",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueRegDeoptOnNil(mv, a, pc);
        mv.visitLabel(done);
    }

    private static void emitGetI(MethodVisitor mv, int a, int b, int c, int pc) {
        emitGuardObject(mv, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        mv.visitLdcInsn((long) c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetInt",
                "(J)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueReg(mv, a);
    }

    private static void emitGetField(MethodVisitor mv, int a, int b, int c, int pc) {
        // Full `t[k]` semantics: rawget first, then metatable __index. A raw
        // hit is metatable-independent, so guard on "rawget non-nil" (deopt
        // otherwise) instead of "table has no metatable" — the latter deopted
        // every OOP instance (`self.x` on an object whose class table is the
        // metatable), which made methods like Vec:dot never JIT-run.
        emitGuardObject(mv, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc);
        emitLoadConst(mv, c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawget",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueRegDeoptOnNil(mv, a, pc);
    }

    /**
     * Consumes a reference on the stack and leaves it typed as
     * {@code LuaTable}, deopting when it is not a table (or is null).
     */
    private static void emitGuardTableTyped(MethodVisitor mv, int pc) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, pc);
        mv.visitLabel(isTable);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
    }

    /**
     * Stores the {@code LuaValue} on the stack into register {@code a}, but
     * deopts when it is nil: a nil rawget means the real {@code get} could
     * still find an {@code __index} result, which only the interpreter can
     * resolve (and it must restart this instruction with no committed state).
     */
    private static void emitStoreValueRegDeoptOnNil(MethodVisitor mv, int a, int pc) {
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaValue", "isNil", "()Z", false);
        Label notNil = new Label();
        mv.visitJumpInsn(IFEQ, notNil);
        emitDeopt(mv, pc);
        mv.visitLabel(notNil);
        emitStoreValueReg(mv, a);
    }

    private static void emitGuardObject(MethodVisitor mv, int reg, int pc) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_OBJECT);
        Label ok = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, ok);
        emitDeopt(mv, pc);
        mv.visitLabel(ok);
    }

    private static void emitGuardInt(MethodVisitor mv, int reg, int pc) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        Label ok = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, ok);
        emitDeopt(mv, pc);
        mv.visitLabel(ok);
    }

    private static void emitTagIntNull(MethodVisitor mv, int reg) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        ldcInt(mv, TYPE_INT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, reg);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
    }

    /** Pushes {@code BytecodeVM.getLuaValue(p, t, o, base+reg)} onto the stack. */
    private static void emitGetValueReg(MethodVisitor mv, int reg) {
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, reg);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "getLuaValue",
                "([J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
    }

    private static void emitIndex(MethodVisitor mv, int reg) {
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, reg);
        mv.visitInsn(IADD);
    }

    private static void emitDeopt(MethodVisitor mv, int pc) {
        emitDeopt(mv, pc, false);
    }

    /**
     * Emits a deopt. {@code structural} marks an unconditional deopt the
     * compiled body always takes for this proto (impure-proto CALL/TAILCALL),
     * which the deopt budget ignores so it never disarms the hot prefix.
     */
    private static void emitDeopt(MethodVisitor mv, int pc, boolean structural) {
        mv.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        mv.visitInsn(DUP);
        ldcInt(mv, pc);
        if (structural) {
            mv.visitInsn(ICONST_1);
            mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(IZ)V", false);
        } else {
            mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(I)V", false);
        }
        mv.visitInsn(ATHROW);
    }

    /**
     * Returns the upvalue index when the CALL's function register is fed
     * straight-line by one GETUPVAL with no intervening read, else -1.
     */
    private static int fusedUpvalue(int[] code, int callPc, int funcReg) {
        int def = lastWrite(code, callPc, funcReg);
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_GETUPVAL) {
            return -1;
        }
        // No branches may enter or leave the (def, callPc) span, and no
        // instruction inside may read funcReg or write it again.
        for (int i = def; i < callPc; i++) {
            int op = Instruction.getOp(code[i]);
            if (op == OpCode.OP_JMP || isBranch(code[i])) {
                return -1;
            }
        }
        for (int t = 0; t < code.length; t++) {
            int jt = jumpTarget(code[t], t);
            if (jt > def && jt < callPc) {
                return -1;
            }
        }
        for (int i = def + 1; i < callPc; i++) {
            if (readsReg(code[i], funcReg) || writesReg(code[i], funcReg)) {
                return -1;
            }
        }
        return Instruction.getB(code[def]);
    }

    private static int lastWrite(int[] code, int callPc, int reg) {
        for (int i = callPc - 1; i >= 0; i--) {
            // EXTRAARG words are data, never register writes.
            if (Instruction.getOp(code[i]) == OpCode.OP_EXTRAARG) {
                continue;
            }
            if (writesReg(code[i], reg)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isBranch(int inst) {
        switch (Instruction.getOp(inst)) {
            case OpCode.OP_EQ,
                    OpCode.OP_LT,
                    OpCode.OP_LE,
                    OpCode.OP_EQK,
                    OpCode.OP_EQI,
                    OpCode.OP_LTI,
                    OpCode.OP_LEI,
                    OpCode.OP_GTI,
                    OpCode.OP_GEI,
                    OpCode.OP_TEST,
                    OpCode.OP_TESTSET -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Jump destination of a JMP, else -1. */
    private static int jumpTarget(int inst, int pc) {
        if (Instruction.getOp(inst) == OpCode.OP_JMP) {
            return pc + 1 + Instruction.getsJ(inst);
        }
        return -1;
    }

    private static boolean readsReg(int inst, int reg) {
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        int b = Instruction.getB(inst);
        int c = Instruction.getC(inst);
        switch (op) {
            case OpCode.OP_MOVE -> {
                return b == reg;
            }
            case OpCode.OP_ADD, OpCode.OP_SUB -> {
                return b == reg || c == reg;
            }
            case OpCode.OP_ADDI, OpCode.OP_SUBK -> {
                return b == reg;
            }
            case OpCode.OP_FORPREP -> {
                return reg == a || reg == a + 1 || reg == a + 2;
            }
            case OpCode.OP_FORLOOP -> {
                return reg == a || reg == a + 1 || reg == a + 2;
            }
            case OpCode.OP_ADDK,
                    OpCode.OP_MULK,
                    OpCode.OP_IDIVK,
                    OpCode.OP_MODK,
                    OpCode.OP_BANDK,
                    OpCode.OP_BORK,
                    OpCode.OP_BXORK -> {
                return b == reg;
            }
            case OpCode.OP_UNM, OpCode.OP_BNOT, OpCode.OP_NOT, OpCode.OP_LEN -> {
                return b == reg;
            }
            case OpCode.OP_LOADNIL,
                    OpCode.OP_LOADTRUE,
                    OpCode.OP_LOADFALSE,
                    OpCode.OP_LOADF,
                    OpCode.OP_CLEANUP -> {
                return false;
            }
            case OpCode.OP_SETLIST -> {
                // Table plus fixed value registers R[a+1 .. a+b-1].
                for (int r = a; r < a + b; r++) {
                    if (r == reg) {
                        return true;
                    }
                }
                return false;
            }
            case OpCode.OP_LEI, OpCode.OP_LTI, OpCode.OP_GTI, OpCode.OP_GEI, OpCode.OP_EQI -> {
                return a == reg;
            }
            case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                // Function plus fixed args R[a+1 .. a+b-1].
                if (a == reg) {
                    return true;
                }
                for (int r = a + 1; r < a + b; r++) {
                    if (r == reg) {
                        return true;
                    }
                }
                return false;
            }
            case OpCode.OP_RETURN1 -> {
                return a == reg;
            }
            case OpCode.OP_RETURN -> {
                // Multret shape may read an open-ended range; block fusion.
                return true;
            }
            case OpCode.OP_GETUPVAL -> {
                return false;
            }
            case OpCode.OP_GETTABUP -> {
                return false;
            }
            case OpCode.OP_GETTABLE -> {
                return b == reg || c == reg;
            }
            case OpCode.OP_GETI, OpCode.OP_GETFIELD -> {
                return b == reg;
            }
            case OpCode.OP_SETUPVAL -> {
                return a == reg;
            }
            case OpCode.OP_SETTABUP -> {
                return Instruction.getk(inst) == 0 && Instruction.getC(inst) == reg;
            }
            case OpCode.OP_SETTABLE -> {
                return a == reg
                        || b == reg
                        || (Instruction.getk(inst) == 0 && Instruction.getC(inst) == reg);
            }
            case OpCode.OP_SETI, OpCode.OP_SETFIELD -> {
                return a == reg || (Instruction.getk(inst) == 0 && Instruction.getC(inst) == reg);
            }
            case OpCode.OP_NEWTABLE, OpCode.OP_EXTRAARG -> {
                return false;
            }
            default -> {
                return a == reg || b == reg || c == reg;
            }
        }
    }

    private static boolean writesReg(int inst, int reg) {
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        int b = Instruction.getB(inst);
        switch (op) {
            case OpCode.OP_LOADNIL, OpCode.OP_CLEANUP -> {
                return reg >= a && reg <= a + b;
            }
            case OpCode.OP_MOVE,
                    OpCode.OP_LOADI,
                    OpCode.OP_LOADF,
                    OpCode.OP_LOADK,
                    OpCode.OP_LOADTRUE,
                    OpCode.OP_LOADFALSE,
                    OpCode.OP_GETUPVAL,
                    OpCode.OP_GETTABUP,
                    OpCode.OP_GETTABLE,
                    OpCode.OP_GETI,
                    OpCode.OP_GETFIELD,
                    OpCode.OP_NEWTABLE,
                    OpCode.OP_ADD,
                    OpCode.OP_SUB,
                    OpCode.OP_ADDI,
                    OpCode.OP_SUBK,
                    OpCode.OP_ADDK,
                    OpCode.OP_MULK,
                    OpCode.OP_IDIVK,
                    OpCode.OP_MODK,
                    OpCode.OP_BANDK,
                    OpCode.OP_BORK,
                    OpCode.OP_BXORK,
                    OpCode.OP_UNM,
                    OpCode.OP_BNOT,
                    OpCode.OP_NOT,
                    OpCode.OP_LEN,
                    OpCode.OP_CALL,
                    OpCode.OP_TAILCALL -> {
                return a == reg;
            }
            case OpCode.OP_SETUPVAL,
                    OpCode.OP_SETTABUP,
                    OpCode.OP_SETTABLE,
                    OpCode.OP_SETI,
                    OpCode.OP_SETFIELD,
                    OpCode.OP_SETLIST,
                    OpCode.OP_EXTRAARG,
                    OpCode.OP_JMP,
                    OpCode.OP_RETURN1,
                    OpCode.OP_RETURN0 -> {
                return false;
            }
            case OpCode.OP_FORPREP -> {
                // Writes control (A+3), count (A+1) and A.
                return reg == a || reg == a + 1 || reg == a + 3;
            }
            case OpCode.OP_FORLOOP -> {
                // Writes control (A+3), remaining count (A+1) and A.
                return reg == a || reg == a + 1 || reg == a + 3;
            }
            default -> {
                return a == reg;
            }
        }
    }

    private static void ldcInt(MethodVisitor mv, int v) {
        mv.visitLdcInsn(v);
    }
}

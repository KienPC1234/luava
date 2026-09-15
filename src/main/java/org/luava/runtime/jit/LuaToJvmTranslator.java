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
        for (int pc = 0; pc < len; pc++) {
            int inst = proto.code[pc];
            int op = Instruction.getOp(inst);
            if (op == OpCode.OP_CALL || op == OpCode.OP_TAILCALL) {
                fusedUp[pc] = fusedUpvalue(proto.code, pc, Instruction.getA(inst));
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
                fusedBCount, info.returnsInt());
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
                    fusedBCount, true);
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
            boolean returnsInt) {
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
                case OpCode.OP_ADD -> emitArith(mv, a, b, c, pc, LADD);
                case OpCode.OP_SUB -> emitArith(mv, a, b, c, pc, LSUB);
                case OpCode.OP_MUL -> emitArith(mv, a, b, c, pc, LMUL);
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
                case OpCode.OP_SUBK -> {
                    long kv = ((LuaInteger) proto.constants[c]).toLong();
                    emitGuardInt(mv, b, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, b);
                    mv.visitInsn(LALOAD);
                    mv.visitLdcInsn(kv);
                    mv.visitInsn(LSUB);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
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
                case OpCode.OP_JMP -> {
                    int sj = Instruction.getsJ(inst);
                    mv.visitJumpInsn(GOTO, labels[pc + 1 + sj]);
                }
                case OpCode.OP_CALL -> {
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
                            fusedBCount);
                }
                case OpCode.OP_TAILCALL -> {
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
                case OpCode.OP_RETURN0 -> emitDeopt(mv, pc);
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

    /** Static analysis result: purity plus the return-type decision. */
    public record Info(boolean pure, boolean returnsInt) {}

    // Register abstract types for return-kind inference.
    private static final int T_UNKNOWN = 0;
    private static final int T_INT = 1;
    private static final int T_OBJ = 2;

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
        if (proto.isVararg) {
            return null;
        }
        int[] code = proto.code;
        if (code.length == 0 || code.length > 200) {
            return null;
        }
        int regs = Math.max(proto.maxStackSize, proto.numParams);
        if (proto.maxStackSize > 64 || proto.numParams > 16) {
            return null;
        }
        boolean hasCalls = false;
        boolean impure = false;
        boolean hasReturn1 = false;
        for (int inst : code) {
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
                        OpCode.OP_SETTABUP,
                        OpCode.OP_SETTABLE,
                        OpCode.OP_SETI,
                        OpCode.OP_SETFIELD,
                        OpCode.OP_NEWTABLE,
                        OpCode.OP_EXTRAARG,
                        OpCode.OP_UNM,
                        OpCode.OP_BNOT,
                        OpCode.OP_NOT,
                        OpCode.OP_LEN,
                        OpCode.OP_SETLIST,
                        OpCode.OP_ADD,
                        OpCode.OP_SUB,
                        OpCode.OP_MUL,
                        OpCode.OP_ADDI,
                        OpCode.OP_SUBK,
                        OpCode.OP_LEI,
                        OpCode.OP_LTI,
                        OpCode.OP_GTI,
                        OpCode.OP_GEI,
                        OpCode.OP_EQI,
                        OpCode.OP_JMP,
                        OpCode.OP_CALL,
                        OpCode.OP_TAILCALL,
                        OpCode.OP_RETURN,
                        OpCode.OP_RETURN1,
                        OpCode.OP_RETURN0 -> {}
                default -> {
                    return null;
                }
            }
            switch (Instruction.getOp(inst)) {
                case OpCode.OP_SETUPVAL,
                        OpCode.OP_SETTABUP,
                        OpCode.OP_SETTABLE,
                        OpCode.OP_SETI,
                        OpCode.OP_SETFIELD,
                        OpCode.OP_SETLIST -> impure = true;
                default -> {}
            }
            if (Instruction.getOp(inst) == OpCode.OP_CALL
                    || Instruction.getOp(inst) == OpCode.OP_TAILCALL) {
                hasCalls = true;
                int bb = Instruction.getB(inst);
                int cc = Instruction.getC(inst);
                // CALL always yields exactly one JIT value; TAILCALL forwards
                // it as our own single result regardless of C.
                if (bb < 1 || (Instruction.getOp(inst) == OpCode.OP_CALL && cc != 2)) {
                    return null;
                }
            }
            if (Instruction.getOp(inst) == OpCode.OP_RETURN1
                    || (Instruction.getOp(inst) == OpCode.OP_RETURN && Instruction.getB(inst) == 2)) {
                hasReturn1 = true;
            }
            if (Instruction.getOp(inst) == OpCode.OP_SETLIST && Instruction.getB(inst) == 0) {
                return null;
            }
        }
        if (!hasReturn1) {
            return null;
        }
        if (hasCalls && impure) {
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
                if (!transfer(proto, code[pc], in[pc], out, regs)) {
                    return null;
                }
                for (int s : successors(code, pc)) {
                    if (s < 0 || s >= code.length) {
                        continue;
                    }
                    for (int r = 0; r < regs; r++) {
                        if (out[r] != T_UNKNOWN) {
                            if (in[s][r] == T_UNKNOWN) {
                                in[s][r] = out[r];
                                changed = true;
                            } else if (in[s][r] != out[r]) {
                                return null;
                            }
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        boolean seenObj = false;
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_RETURN1
                    || (op == OpCode.OP_RETURN && Instruction.getB(code[pc]) == 2)) {
                if (in[pc][Instruction.getA(code[pc])] == T_OBJ) {
                    seenObj = true;
                }
            }
        }
        boolean returnsInt = !seenObj;
        if (!returnsInt && hasCalls) {
            return null;
        }
        return new Info(!impure, returnsInt);
    }

    /** Successor pcs for control-flow (conditional compares skip one). */
    private static int[] successors(int[] code, int pc) {
        int op = Instruction.getOp(code[pc]);
        if (op == OpCode.OP_JMP) {
            return new int[] {pc + 1 + Instruction.getsJ(code[pc])};
        }
        switch (op) {
            case OpCode.OP_LEI,
                    OpCode.OP_LTI,
                    OpCode.OP_GTI,
                    OpCode.OP_GEI,
                    OpCode.OP_EQI,
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
    private static boolean transfer(LuaProto proto, int inst, int[] in, int[] out, int regs) {
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        int b = Instruction.getB(inst);
        int c = Instruction.getC(inst);
        switch (op) {
            case OpCode.OP_MOVE -> setTy(out, regs, a, in[b]);
            case OpCode.OP_LOADI -> setTy(out, regs, a, T_INT);
            case OpCode.OP_LOADF,
                    OpCode.OP_LOADNIL,
                    OpCode.OP_LOADTRUE,
                    OpCode.OP_LOADFALSE -> setTy(out, regs, a, T_OBJ);
            case OpCode.OP_CLEANUP -> {
                for (int j = 0; j <= b && a + j < regs; j++) {
                    setTy(out, regs, a + j, T_OBJ);
                }
            }
            case OpCode.OP_LOADK -> {
                LuaValue kv = proto.constants[Instruction.getBx(inst)];
                setTy(out, regs, a, kv instanceof LuaInteger ? T_INT : T_OBJ);
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
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_UNM, OpCode.OP_BNOT -> {
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_ADDI, OpCode.OP_SUBK -> {
                if (in[b] == T_OBJ) {
                    return false;
                }
                setTy(out, regs, a, T_INT);
            }
            case OpCode.OP_LEI, OpCode.OP_LTI, OpCode.OP_GTI, OpCode.OP_GEI, OpCode.OP_EQI -> {
                return in[a] != T_OBJ;
            }
            case OpCode.OP_CLOSURE, OpCode.OP_NEWTABLE -> setTy(out, regs, a, T_OBJ);
            case OpCode.OP_CALL -> setTy(out, regs, a, T_INT);
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

    private static void emitArith(MethodVisitor mv, int a, int b, int c, int pc, int jvmOp) {
        emitGuardInt(mv, b, pc);
        emitGuardInt(mv, c, pc);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, c);
        mv.visitInsn(LALOAD);
        mv.visitInsn(jvmOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
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
     * Call site with three tiers: hoisted self (classified at entry),
     * resolved self (same proto, direct INVOKESTATIC), or another pure JIT
     * proto via the {@link JitRuntime} helper. Anything else deopts to the
     * interpreter.
     */
    private static void emitCall(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount) {
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
        emitDeopt(mv, resumePc);
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
        emitDeopt(mv, resumePc);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "pure", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, resumePc);
        mv.visitLabel(isPure);
        // The integer call protocol carries an unboxed long result; an
        // object-returning callee cannot feed it.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsInt", "Z");
        Label returnsIntOk = new Label();
        mv.visitJumpInsn(IFNE, returnsIntOk);
        emitDeopt(mv, resumePc);
        mv.visitLabel(returnsIntOk);
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
        // Fast lane mirrors the interpreter: plain table + integer key.
        // Scratch local 9 is free: table writes forbid calls (analyze),
        // hence no fused-callee or vc-param locals exist here.
        emitGuardInt(mv, b, pc);
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
        // Fast lane mirrors the interpreter: plain table + integer key.
        emitGuardInt(mv, c, pc);
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
        emitGuardObject(mv, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc);
        emitLoadConst(mv, c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "get",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
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

    private static void emitIndex(MethodVisitor mv, int reg) {
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, reg);
        mv.visitInsn(IADD);
    }

    private static void emitDeopt(MethodVisitor mv, int pc) {
        mv.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        mv.visitInsn(DUP);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(I)V", false);
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
            default -> {
                return a == reg;
            }
        }
    }

    private static void ldcInt(MethodVisitor mv, int v) {
        mv.visitLdcInsn(v);
    }
}

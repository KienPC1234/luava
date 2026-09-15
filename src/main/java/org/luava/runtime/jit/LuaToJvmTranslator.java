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

    private LuaToJvmTranslator() {}

    /** Result of a successful translation. */
    public record Translation(String internalName, byte[] bytes) {}

    /** Returns null when the proto is outside the integer-subset. */
    public static Translation translate(LuaProto proto, String internalName) {
        if (!eligible(proto)) {
            return null;
        }
        int len = proto.code.length;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_NAME, EXEC_DESC, null, null);
        mv.visitCode();

        Label[] labels = new Label[len];
        for (int i = 0; i < len; i++) {
            labels[i] = new Label();
        }

        // Locals: 0=self 1=up 2=p 3=t 4=o 5=base 6-7=long scratch 8=ref scratch
        // 9+ = hoisted fused-callee closures and their upvalue arrays.
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
            if (Instruction.getOp(inst) == OpCode.OP_CALL) {
                fusedUp[pc] = fusedUpvalue(proto.code, pc, Instruction.getA(inst));
            }
        }
        boolean[] skipStore = new boolean[len];
        for (int pc = 0; pc < len; pc++) {
            if (fusedUp[pc] >= 0) {
                int def = lastWrite(proto.code, pc, Instruction.getA(proto.code[pc]));
                if (def >= 0) {
                    skipStore[def] = true;
                }
            }
        }
        // Distinct fused upvalue indexes, each hoisted to two locals at entry:
        // the verified callee closure and its upvalue array.
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
        // Entry prologue: classify each distinct fused callee once per
        // invocation. Upvalues cannot change mid-flight (no hooks, no yield,
        // no writes in the pure subset), so recording "is this the same
        // proto as self" here is sound; the general path re-validates
        // anyway. A mismatch only selects the slow path, never deopts.
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
            mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/bytecode/LuaClosure");
            Label notSelf = new Label();
            Label nextB = new Label();
            mv.visitJumpInsn(IFEQ, notSelf);
            mv.visitVarInsn(ALOAD, 8);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
            mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                    "Lorg/luava/runtime/bytecode/LuaProto;");
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                    "Lorg/luava/runtime/bytecode/LuaProto;");
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
                    long kv = ((LuaInteger) proto.constants[Instruction.getBx(inst)]).toLong();
                    emitStoreIntConst(mv, a, kv);
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
                    emitCall(mv, internalName, proto, a, b - 1, pc, fusedUp[pc], slot);
                }
                case OpCode.OP_RETURN1 -> {
                    emitGuardInt(mv, a, pc);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LALOAD);
                    mv.visitInsn(LRETURN);
                }
                case OpCode.OP_RETURN0 -> emitDeopt(mv, pc);
                default -> emitDeopt(mv, pc);
            }
        }
        // Fallthrough safety: never normally reached.
        emitDeopt(mv, len - 1);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return new Translation(internalName, cw.toByteArray());
    }

    /** Static analysis result: eligibility plus the purity flag. */
    public record Info(boolean pure) {}

    /**
     * Analyzes a proto for the integer subset. Returns null when any
     * reachable shape is unsupported. Rules preserving deopt-restart
     * safety: a proto with calls must be pure (no upvalue writes), and an
     * impure leaf must take no parameters (frame setup would otherwise
     * clobber JIT state on resume-at-pc).
     */
    public static Info analyze(LuaProto proto) {
        if (proto.isVararg || proto.protos.length != 0) {
            return null;
        }
        int[] code = proto.code;
        if (code.length == 0 || code.length > 200) {
            return null;
        }
        if (proto.maxStackSize > 64 || proto.numParams > 16) {
            return null;
        }
        boolean hasCalls = false;
        boolean hasSetupVal = false;
        for (int inst : code) {
            switch (Instruction.getOp(inst)) {
                case OpCode.OP_MOVE,
                        OpCode.OP_LOADI,
                        OpCode.OP_GETUPVAL,
                        OpCode.OP_SETUPVAL,
                        OpCode.OP_ADD,
                        OpCode.OP_SUB,
                        OpCode.OP_ADDI,
                        OpCode.OP_SUBK,
                        OpCode.OP_LEI,
                        OpCode.OP_LTI,
                        OpCode.OP_GTI,
                        OpCode.OP_GEI,
                        OpCode.OP_EQI,
                        OpCode.OP_JMP,
                        OpCode.OP_CALL,
                        OpCode.OP_RETURN1,
                        OpCode.OP_RETURN0 -> {}
                case OpCode.OP_LOADK -> {
                    LuaValue kv = proto.constants[Instruction.getBx(inst)];
                    if (!(kv instanceof LuaInteger)) {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
            if (Instruction.getOp(inst) == OpCode.OP_SETUPVAL) {
                hasSetupVal = true;
            }
            if (Instruction.getOp(inst) == OpCode.OP_CALL) {
                hasCalls = true;
                int bb = Instruction.getB(inst);
                int cc = Instruction.getC(inst);
                if (bb < 1 || cc != 2) {
                    return null;
                }
            }
        }
        if (hasCalls && hasSetupVal) {
            return null;
        }
        if (hasSetupVal && proto.numParams != 0) {
            return null;
        }
        return new Info(!hasSetupVal);
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
    private static void emitCall(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs, int pc,
            int fusedB, int hoistSlot) {
        Label done = new Label();
        if (hoistSlot >= 0) {
            // Fast tier: entry-classified same-proto callee, no re-check.
            mv.visitVarInsn(ILOAD, 9 + 2 * hoistSlot);
            Label general = new Label();
            mv.visitJumpInsn(IFEQ, general);
            mv.visitVarInsn(ALOAD, 10 + 2 * hoistSlot);
            mv.visitVarInsn(ASTORE, 8);
            emitDirectInvoke(mv, owner, proto, a, nArgs, pc);
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
        emitDeopt(mv, pc);
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
        emitDirectInvoke(mv, owner, proto, a, nArgs, pc);
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
        emitDeopt(mv, pc);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "pure", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, pc);
        mv.visitLabel(isPure);
        ldcInt(mv, nArgs);
        mv.visitVarInsn(ALOAD, 20);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        Label arityOk = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, arityOk);
        emitDeopt(mv, pc);
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
        emitDeopt(mv, pc);
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
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "invoke",
                "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J",
                false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitLabel(done);
    }

    /** Direct INVOKESTATIC into this proto's own exec (self-recursion). */
    private static void emitDirectInvoke(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int pc) {
        // Capacity guard for the callee window.
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1 + proto.maxStackSize);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOk = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOk);
        emitDeopt(mv, pc);
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
        mv.visitMethodInsn(INVOKESTATIC, owner, EXEC_NAME, EXEC_DESC, false);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
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
            case OpCode.OP_LEI, OpCode.OP_LTI, OpCode.OP_GTI, OpCode.OP_GEI, OpCode.OP_EQI -> {
                return a == reg;
            }
            case OpCode.OP_CALL -> {
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
            case OpCode.OP_GETUPVAL -> {
                return false;
            }
            case OpCode.OP_SETUPVAL -> {
                return a == reg;
            }
            default -> {
                return a == reg || b == reg || c == reg;
            }
        }
    }

    private static boolean writesReg(int inst, int reg) {
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        switch (op) {
            case OpCode.OP_MOVE,
                    OpCode.OP_LOADI,
                    OpCode.OP_LOADK,
                    OpCode.OP_GETUPVAL,
                    OpCode.OP_ADD,
                    OpCode.OP_SUB,
                    OpCode.OP_ADDI,
                    OpCode.OP_SUBK,
                    OpCode.OP_CALL -> {
                return a == reg;
            }
            case OpCode.OP_SETUPVAL,
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

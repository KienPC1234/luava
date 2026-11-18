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
    private static final String CALL_SLOW_NAME = "callSlow";
    private static final String DEOPT_NAME = "deopt";
    private static final String DEOPT_DESC = "(I)Lorg/luava/runtime/jit/DeoptSignal;";
    private static final String DEOPT_S_NAME = "deoptS";
    private static final String DEOPT_S_DESC = "(I)Lorg/luava/runtime/jit/DeoptSignal;";
    private static final String GUARD_INT_NAME = "guardInt";
    private static final String GUARD_INT_DESC = "([BII)V";
    private static final String GUARD_NUM_NAME = "guardNumber";
    private static final String GUARD_NUM_DESC = "([BII)V";
    private static final String GUARD_OBJ_NAME = "guardObject";
    private static final String GUARD_OBJ_DESC = "([BII)V";
    private static final String SUB_K_NAME = "subKImm";
    private static final String SUB_K_DESC = "([J[B[Lorg/luava/runtime/LuaValue;IIJI)V";
    private static final String ADD_RR_NAME = "addIntRR";
    private static final String ADD_RR_DESC = "([J[B[Lorg/luava/runtime/LuaValue;IIII)V";
    private static final String LEI_IMM_NAME = "leiImm";
    private static final String LEI_IMM_DESC = "([B[JIII)Z";
    private static final String RET_INT_NAME = "retInt";
    private static final String RET_INT_DESC = "([J[BII)J";
    private static final String STORE_RAW_NAME = "storeRaw";
    private static final String STORE_RAW_DESC = "([J[B[Lorg/luava/runtime/LuaValue;IJ)V";
    private static final String CALL_SLOW_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;IIIIIZ)V";
    private static final String CALL_SLOW_GENERAL_NAME = "callSlowGeneral";
    private static final String CALL_SLOW_GENERAL_DESC =
            "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;IIIZ)V";
    private static final String CALL_DIRECT_NAME = "callDirect";
    private static final String CALL_DIRECT_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;III)J";
    private static final String EXEC_OBJ_NAME = "execObj";
    private static final String EXEC_OBJ_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;";
    private static final String EXEC_OSR_NAME = "execOsr";
    private static final String EXEC_OBJ_OSR_NAME = "execObjOsr";
    private static final String EXEC_OSR_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;II)J";
    private static final String EXEC_OBJ_OSR_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;II)Lorg/luava/runtime/LuaValue;";

    /**
     * Result of a successful translation.
     *
     * @param osrTargets per-pc flags marking the loop instructions at which
     *     on-stack replacement may enter {@code execOsr}; null when the proto
     *     has no in-subset loop or no OSR entry was emitted.
     */
    public record Translation(String internalName, byte[] bytes, boolean[] osrTargets) {}

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
        return translate(proto, internalName, false);
    }

    /**
     * @param wantOsr when true, also emits an {@code execOsr} entry that
     *     branches into the loop body at the instruction labels, enabling
     *     on-stack replacement for hot loops.
     */
    public static Translation translate(LuaProto proto, String internalName, boolean wantOsr) {
        Info info = analyze(proto);
        if (info == null) {
            return null;
        }
        boolean pure = info.pure();
        int len = proto.code.length;
        // Loop instructions at which OSR may enter: a jump back-edge or a
        // numeric/generic loop step. Any such pc is only reachable after FORPREP
        // or a prior body execution, so register state is initialized.
        boolean[] osrTargets = wantOsr ? loopHeadFlags(proto.code) : null;
        boolean anyOsr = false;
        if (osrTargets != null) {
            for (boolean t : osrTargets) {
                if (t) {
                    anyOsr = true;
                    break;
                }
            }
        }
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
        // Fusion hoists callee closures into locals 9+ and enables the
        // prologue-free self-recursion entry, which has no register-window
        // rollback. Only safe when the proto never writes its parameter
        // registers (otherwise a nested deopt could re-read a clobbered
        // operand); a parameter-writing proto routes self calls through the
        // snapshot-wrapped general helper. Pointless for impure protos.
        boolean selfSafe = !writesParams(proto, proto.code);
        if (pure && selfSafe) {
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
        boolean returnsInt = info.returnsInt();
        if (anyOsr) {
            // The body lives once, in execOsr, whose extra pc parameter drives
            // a jump table into the loop header. exec keeps its six-argument
            // ABI and forwards at pc 0, so existing callers and MethodHandles
            // are unchanged and the body is not duplicated.
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    returnsInt ? EXEC_NAME : EXEC_OBJ_NAME,
                    returnsInt ? EXEC_DESC : EXEC_OBJ_DESC, null, null);
            mv.visitCode();
            emitOsrForward(mv, internalName, returnsInt);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            emitOsrBody(cw, internalName, proto, osrTargets, info, fusedUp, fusedDef, skipStore,
                    fusedBList, fusedBCount, pure, selfSafe);
        } else {
            osrTargets = null;
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    returnsInt ? EXEC_NAME : EXEC_OBJ_NAME,
                    returnsInt ? EXEC_DESC : EXEC_OBJ_DESC, null, null);
            mv.visitCode();
            emitPrologue(mv, internalName, fusedBList, fusedBCount);
            emitBody(mv, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                    fusedBCount, returnsInt, info.returnsVoid(), pure, info.types(), selfSafe, false);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        if (returnsInt && fusedBCount > 0) {
            // Prologue-free recursive entry. Sound only because every caller
            // passes down entry-verified closures for the same upvalue array
            // (self-recursion on one closure); anything else uses exec.
            MethodVisitor mi =
                    cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_INNER_NAME, innerDesc(fusedBCount), null, null);
            mi.visitCode();
            // The fused closures are already verified by the caller, hence
            // flags set to 1 directly.
            for (int i = 0; i < fusedBCount; i++) {
                mi.visitVarInsn(ALOAD, 6 + i);
                mi.visitVarInsn(ASTORE, 10 + 2 * i);
                mi.visitInsn(ICONST_1);
                mi.visitVarInsn(ISTORE, 9 + 2 * i);
            }
            emitBody(mi, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                    fusedBCount, true, info.returnsVoid(), pure, info.types(), selfSafe, true);
            mi.visitMaxs(0, 0);
            mi.visitEnd();
        }
        boolean hasCallOp = false;
        for (int inst : proto.code) {
            if (Instruction.getOp(inst) == OpCode.OP_CALL) {
                hasCallOp = true;
                break;
            }
        }
        emitGuardHelpers(cw, internalName);
        if (returnsInt && fusedBCount > 0) {
            // Lane/return/store leaf helpers are only used by the prologue-free
            // recursive entry, whose inline budget binds; every other method
            // keeps the proven inline codegen so hot loops never depend on
            // cross-method inlining.
            emitLaneHelpers(cw, internalName);
        }
        if (hasCallOp) {
            emitCallSlowHelper(cw, internalName);
        }
        cw.visitEnd();
        return new Translation(internalName, cw.toByteArray(), osrTargets);
    }

    /**
     * Flags the loop headers at which OSR may enter: the backward target of a
     * {@code JMP} (while/repeat/goto), a {@code FORLOOP} (numeric-for step) or
     * a {@code TFORLOOP} (generic-for step). The interpreter invokes the OSR
     * entry after applying the back-edge, so {@code ctx.pc} is exactly this
     * target (the loop body start). {@code FORPREP}/{@code TFORPREP} are loop
     * setup and run before any body state exists, so their targets are not
     * counted here.
     *
     * <p>All three are int-safe only when they are not nested inside a
     * different for body: entering a nested header would skip the enclosing
     * {@code FORPREP} guard, and the kernel's numeric {@code FORLOOP} has no
     * per-iteration tag guard, so a float outer {@code for} could be
     * corrupted. A for's own header lies at depth 1; depth &gt; 1 means an
     * outer for encloses it. A pc that a backward {@code JMP} can also reach
     * is dropped because that JMP could target a float for. Only targets
     * inside {@code [0, len)} are flagged.
     */
    private static boolean[] loopHeadFlags(int[] code) {
        boolean[] flags = new boolean[code.length];
        boolean[] jmpTarget = new boolean[code.length];
        boolean[] loopHeader = new boolean[code.length];
        int[] forDepth = new int[code.length];
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_FORPREP) {
                int end = pc + 1 + Instruction.getBx(code[pc]);
                for (int t = pc + 1; t <= end && t < code.length; t++) {
                    forDepth[t]++;
                }
            } else if (op == OpCode.OP_TFORPREP) {
                int end = pc + 2 + Instruction.getBx(code[pc]);
                for (int t = pc + 1; t <= end && t < code.length; t++) {
                    forDepth[t]++;
                }
            }
        }
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_FORLOOP || op == OpCode.OP_TFORLOOP) {
                int h = pc + 1 - Instruction.getBx(code[pc]);
                if (h >= 0 && h < code.length) {
                    loopHeader[h] = true;
                }
            } else if (op == OpCode.OP_JMP && Instruction.getsJ(code[pc]) < 0) {
                int t = pc + 1 + Instruction.getsJ(code[pc]);
                if (t >= 0 && t < code.length) {
                    jmpTarget[t] = true;
                }
            }
        }
        for (int pc = 0; pc < code.length; pc++) {
            if (loopHeader[pc]) {
                flags[pc] = !jmpTarget[pc] && forDepth[pc] <= 1;
            } else if (jmpTarget[pc]) {
                // A while/repeat/goto header: safe only outside every
                // enclosing for, where the kernel can never run an outer
                // FORLOOP whose guard it skipped.
                flags[pc] = forDepth[pc] == 0;
            }
        }
        return flags;
    }

    /** {@code exec} forwarding to {@code execOsr(..., pc = 0)}. */
    private static void emitOsrForward(MethodVisitor mv, String owner, boolean returnsInt) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, owner, returnsInt ? EXEC_OSR_NAME : EXEC_OBJ_OSR_NAME,
                returnsInt ? EXEC_OSR_DESC : EXEC_OBJ_OSR_DESC, false);
        mv.visitInsn(returnsInt ? LRETURN : ARETURN);
    }

    /**
     * Emits the single body in {@code execOsr}, dispatching on the trailing
     * {@code pc} parameter (slot 6) through a jump table so a hot loop can
     * enter compilation on the stack instead of waiting for the next call.
     * The pc is consumed before the body runs, so the body's long scratch slot
     * 6 is free afterwards. The default branch restarts at pc 0 (the ordinary
     * function entry), which the interpreter never selects because it only
     * calls {@code execOsr} at a flagged loop pc.
     */
    private static void emitOsrBody(ClassWriter cw, String internalName, LuaProto proto,
            boolean[] osrTargets, Info info, int[] fusedUp, int[] fusedDef, boolean[] skipStore,
            int[] fusedBList, int fusedBCount, boolean pure, boolean selfSafe) {
        int len = proto.code.length;
        Label[] labels = newLabels(len);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                info.returnsInt() ? EXEC_OSR_NAME : EXEC_OBJ_OSR_NAME,
                info.returnsInt() ? EXEC_OSR_DESC : EXEC_OBJ_OSR_DESC, null, null);
        mv.visitCode();
        emitPrologue(mv, internalName, fusedBList, fusedBCount);
        java.util.ArrayList<Integer> targets = new java.util.ArrayList<>();
        for (int pc = 0; pc < osrTargets.length; pc++) {
            if (osrTargets[pc]) {
                targets.add(pc);
            }
        }
        int[] keys = new int[targets.size()];
        Label[] tramp = new Label[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            keys[i] = targets.get(i);
            tramp[i] = new Label();
        }
        // Entry trampolines normalize the long scratch slot 6 before joining
        // the body, so the verifier sees a long at every body label even though
        // pc arrived as int in slot 6. The default (unknown pc 0 from exec's
        // forwarder, or any non-target) enters the ordinary pc-0 body; the
        // interpreter only calls execOsr at a flagged loop pc.
        Label defaultTramp = new Label();
        mv.visitVarInsn(ILOAD, 6); // pc parameter (slot 6, int)
        mv.visitLookupSwitchInsn(defaultTramp, keys, tramp);
        mv.visitLabel(defaultTramp);
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitJumpInsn(GOTO, labels[0]);
        for (int i = 0; i < targets.size(); i++) {
            mv.visitLabel(tramp[i]);
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, 6);
            mv.visitJumpInsn(GOTO, labels[targets.get(i)]);
        }
        emitBody(mv, internalName, proto, labels, fusedUp, fusedDef, skipStore, fusedBList,
                fusedBCount, info.returnsInt(), info.returnsVoid(), pure, info.types(), selfSafe, false);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitPrologue(MethodVisitor mv, String owner, int[] fusedBList, int fusedBCount) {
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
            boolean returnsInt, boolean returnsVoid, boolean pure, int[][] types, boolean selfSafe,
            boolean forInner) {
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
                case OpCode.OP_GETTABUP -> emitGetTabUp(mv, owner, a, b, c, pc);
                case OpCode.OP_GETTABLE -> emitGetTable(mv, owner, a, b, c, pc);
                case OpCode.OP_GETI -> emitGetI(mv, owner, a, b, c, pc);
                case OpCode.OP_GETFIELD -> emitGetField(mv, owner, a, b, c, pc);
                case OpCode.OP_SETTABUP -> emitSetTabUp(mv, owner, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETTABLE -> emitSetTable(mv, owner, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETI -> emitSetI(mv, owner, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SETFIELD -> emitSetField(mv, owner, proto, a, b, c, Instruction.getk(inst), pc);
                case OpCode.OP_SELF -> emitSelf(mv, owner, proto, a, b, c, Instruction.getk(inst), pc);
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
                    // Same array pre-size the interpreter applies (B = the
                    // constructor's list-part length); a tier that disagreed
                    // with the interpreter on rawlen/# would be a real
                    // interpreter/JIT divergence.
                    mv.visitTypeInsn(NEW, "org/luava/runtime/LuaTable");
                    mv.visitInsn(DUP);
                    ldcInt(mv, Instruction.getB(inst));
                    ldcInt(mv, 0);
                    mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/LuaTable", "<init>", "(II)V", false);
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
                    emitGuardInt(mv, owner, a, pc);
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
                    emitDeopt(mv, owner, pc);
                    mv.visitLabel(uvOk);
                    mv.visitVarInsn(ALOAD, 8);
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    mv.visitInsn(LALOAD);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "setClosedInt",
                            "(J)V", false);
                }
                case OpCode.OP_ADD -> emitArithNum(mv, owner, a, b, c, pc, LADD, DADD, isInt(types, pc, b) && isInt(types, pc, c), forInner);
                case OpCode.OP_SUB -> emitArithNum(mv, owner, a, b, c, pc, LSUB, DSUB, isInt(types, pc, b) && isInt(types, pc, c), forInner);
                case OpCode.OP_MUL -> emitArithNum(mv, owner, a, b, c, pc, LMUL, DMUL, isInt(types, pc, b) && isInt(types, pc, c), forInner);
                case OpCode.OP_DIV -> emitDivNum(mv, owner, a, b, c, pc);
                case OpCode.OP_POW -> emitPowNum(mv, owner, a, b, c, pc);
                case OpCode.OP_IDIV -> emitIdivNum(mv, owner, a, b, c, pc, isInt(types, pc, b) && isInt(types, pc, c));
                case OpCode.OP_MOD -> emitModNum(mv, owner, a, b, c, pc, isInt(types, pc, b) && isInt(types, pc, c));
                case OpCode.OP_BAND -> emitBitwiseNum(mv, owner, a, b, c, pc, LAND, "and");
                case OpCode.OP_BOR -> emitBitwiseNum(mv, owner, a, b, c, pc, LOR, "or");
                case OpCode.OP_BXOR -> emitBitwiseNum(mv, owner, a, b, c, pc, LXOR, "xor");
                case OpCode.OP_SHL -> emitShiftNum(mv, owner, a, b, c, pc, true);
                case OpCode.OP_SHR -> emitShiftNum(mv, owner, a, b, c, pc, false);
                case OpCode.OP_SHLI -> emitShiftImm(mv, owner, a, b, Instruction.getsC(inst), pc, true);
                case OpCode.OP_SHRI -> emitShiftImm(mv, owner, a, b, Instruction.getsC(inst), pc, false);
                // Integer-only constant forms. analyze() already rejected any
                // proto containing a K-form whose constant is not a LuaInteger
                // (and rejected DIVK/POWK outright, since `/` and `^` always
                // yield float). So kc is a compile-time integer operand here.
                case OpCode.OP_ADDK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LADD, DADD, isInt(types, pc, b), forInner);
                case OpCode.OP_MULK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LMUL, DMUL, isInt(types, pc, b), forInner);
                case OpCode.OP_DIVK -> emitDivK(mv, owner, proto, a, b, c, pc);
                case OpCode.OP_POWK -> emitPowK(mv, owner, proto, a, b, c, pc);
                case OpCode.OP_IDIVK -> emitIdivK(mv, owner, proto, a, b, c, pc);
                case OpCode.OP_MODK -> emitModK(mv, owner, proto, a, b, c, pc);
                case OpCode.OP_BANDK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LAND, -1, isInt(types, pc, b), forInner);
                case OpCode.OP_BORK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LOR, -1, isInt(types, pc, b), forInner);
                case OpCode.OP_BXORK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LXOR, -1, isInt(types, pc, b), forInner);
                case OpCode.OP_UNM -> {
                    emitGuardInt(mv, owner, b, pc);
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
                    emitGuardInt(mv, owner, b, pc);
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
                    // isTruthy takes exactly (long[], byte[], int): the object
                    // array must NOT be pushed, or the reference is stranded on
                    // the operand stack and every control-flow join afterwards
                    // carries an extra slot (ASM Frame.merge then throws and
                    // JitCompiler silently disables the whole proto).
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
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
                    emitGuardObject(mv, owner, b, pc);
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
                    emitDeopt(mv, owner, pc);
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
                    emitDeopt(mv, owner, pc);
                    mv.visitLabel(lenDone);
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ASTORE, 8);
                    mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaInteger");
                    Label lenOk = new Label();
                    mv.visitJumpInsn(IFNE, lenOk);
                    emitDeopt(mv, owner, pc);
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
                    emitDeopt(mv, owner, pc);
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
                    emitGuardInt(mv, owner, b, pc);
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
                case OpCode.OP_SUBK -> emitArithKNum(mv, owner, proto, a, b, c, pc, LSUB, DSUB, isInt(types, pc, b), forInner);
                case OpCode.OP_LEI -> emitCmpImm(mv, owner, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLE, IFGT, isInt(types, pc, a), forInner);
                case OpCode.OP_LTI -> emitCmpImm(mv, owner, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLT, IFGE, isInt(types, pc, a), forInner);
                case OpCode.OP_GTI -> emitCmpImm(mv, owner, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGT, IFLE, isInt(types, pc, a), forInner);
                case OpCode.OP_GEI -> emitCmpImm(mv, owner, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGE, IFLT, isInt(types, pc, a), forInner);
                case OpCode.OP_EQI -> {
                    int sb = Instruction.getsB(inst);
                    int k = Instruction.getk(inst);
                    emitGuardInt(mv, owner, a, pc);
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
                case OpCode.OP_LT -> emitCmpRR(mv, owner, a, b, Instruction.getk(inst), pc, labels, false, isInt(types, pc, a) && isInt(types, pc, b));
                case OpCode.OP_LE -> emitCmpRR(mv, owner, a, b, Instruction.getk(inst), pc, labels, true, isInt(types, pc, a) && isInt(types, pc, b));
                case OpCode.OP_EQ -> emitEqRR(mv, owner, proto, a, b, Instruction.getk(inst), pc, labels);
                case OpCode.OP_EQK -> emitEqK(mv, owner, proto, a, b, Instruction.getk(inst), pc, labels,
                        isInt(types, pc, a));
                case OpCode.OP_TEST -> emitTest(mv, owner, a, Instruction.getk(inst), pc, labels);
                case OpCode.OP_TESTSET -> emitTestSet(mv, owner, a, b, Instruction.getk(inst), pc, labels);
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
                case OpCode.OP_FORPREP -> emitForPrep(mv, owner, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_FORLOOP -> emitForLoop(mv, owner, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_TFORPREP -> emitTForPrep(mv, owner, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_TFORCALL -> emitTForCall(mv, owner, a, c, pc);
                case OpCode.OP_TFORLOOP -> emitTForLoop(mv, owner, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_CALL -> {
                    if (isFloorSqrtIntrinsic(proto, proto.code, pc)) {
                        emitFloorSqrtCall(mv, owner, a, pc);
                        break;
                    }
                    if (isSqrtIntrinsic(proto, proto.code, pc)) {
                        emitSqrtCall(mv, owner, a, pc);
                        break;
                    }
                    if (isFloorIntrinsic(proto, proto.code, pc)) {
                        emitFloorCall(mv, owner, a, pc);
                        break;
                    }
                    if (isTForSetup(proto, proto.code, pc)) {
                        emitTForSetup(mv, owner, a, b - 1, pc);
                        break;
                    }
                    if (isTostringIntrinsic(proto, proto.code, pc)) {
                        emitTostringCall(mv, owner, a, pc);
                        break;
                    }
                    if (isSetmetatableIntrinsic(proto, proto.code, pc)) {
                        emitSetmetatableCall(mv, owner, a, pc);
                        break;
                    }
                    if (isIntrinsicCall(proto, proto.code, pc)) {
                        // The multi-result sqrt half consumed by the following
                        // fused floor(sqrt): emits nothing here.
                        break;
                    }
                    // The compiled call protocol yields exactly one value
                    // (C==2) or discards it (C==1). Any other result count
                    // (multret or multi-assignment) must run interpreted;
                    // deopt structurally so the surrounding hot loop still
                    // compiles. This is the common `local a,b = f()` shape.
                    if (c != 1 && c != 2) {
                        emitDeopt(mv, owner, pc, true);
                        break;
                    }
                    // Both pure and impure protos enter a compiled callee; the
                    // emitter guards on `jc.pure`, and every general call goes
                    // through the snapshot-wrapped protocol, so a nested deopt
                    // rolls the argument window back before the interpreter
                    // re-executes the call with the impure prefix committed.
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
                            fusedBCount, c - 1 != 0, returnsInt, selfSafe, forInner);
                }
                case OpCode.OP_TAILCALL -> {
                    if (isSqrtIntrinsic(proto, proto.code, pc)) {
                        emitSqrtTail(mv, owner, a, pc);
                        break;
                    }
                    if (isSetmetatableIntrinsic(proto, proto.code, pc)) {
                        // The constructor tail: mutate in place, then return
                        // the table as the object-mode result.
                        emitSetmetatableCall(mv, owner, a, pc);
                        mv.visitVarInsn(ALOAD, 4);
                        emitIndex(mv, a);
                        mv.visitInsn(AALOAD);
                        mv.visitInsn(ARETURN);
                        break;
                    }
                    if (!pure) {
                        // Same rule as OP_CALL: an impure proto never enters a
                        // callee from compiled code. Structural deopt.
                        emitDeopt(mv, owner, pc, true);
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
                            fusedBCount, labels, returnsInt, selfSafe);
                }
                case OpCode.OP_RETURN1 -> emitReturnOne(mv, owner, a, pc, returnsInt, forInner);
                case OpCode.OP_RETURN -> {
                    // Single-value shape only; anything else deopts.
                    if (b != 2) {
                        emitDeopt(mv, owner, pc);
                    } else {
                        emitReturnOne(mv, owner, a, pc, returnsInt, forInner);
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    if (returnsVoid) {
                        // Void proto: return the sentinel; callers materialize
                        // zero results (or nil-fill an expected count).
                        mv.visitInsn(LCONST_0);
                        mv.visitInsn(LRETURN);
                    } else {
                        emitDeopt(mv, owner, pc);
                    }
                }
                default -> emitDeopt(mv, owner, pc);
            }
        }
        // Fallthrough safety: never normally reached.
        emitDeopt(mv, owner, len - 1);
    }

    /** Shared single-value return for RETURN1 and RETURN B==2. */
    private static void emitReturnOne(MethodVisitor mv, String owner, int a, int pc, boolean returnsInt,
            boolean forInner) {
        if (returnsInt) {
            if (forInner) {
                // Guarded int return as one INVOKESTATIC to the generated leaf
                // helper, which returns the raw long for the unboxed ABI.
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                emitIndex(mv, a);
                ldcInt(mv, pc);
                mv.visitMethodInsn(INVOKESTATIC, owner, RET_INT_NAME, RET_INT_DESC, false);
                mv.visitInsn(LRETURN);
                return;
            }
            emitGuardInt(mv, owner, a, pc);
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
        // Purity is a whole-proto property. A write is pure when its target is
        // a table provably created by a NEWTABLE in this same straight-line
        // region and still unaliased: the write only affects a value this
        // invocation owns, so restart-from-entry discards it safely. This is
        // what makes the OOP constructor `setmetatable({...}, C)` a pure
        // object-returning leaf and lets its caller enter it. Writes to
        // upvalues/globals/parameters or to any table that could be observed
        // elsewhere stay impure; a `__gc`/`__mode` metatable deopts in
        // JitRuntime before any mutation.
        boolean impure = false;
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            switch (op) {
                case OpCode.OP_SETUPVAL,
                        OpCode.OP_SETTABUP,
                        OpCode.OP_CLOSURE,
                        OpCode.OP_CLOSE -> impure = true;
                case OpCode.OP_SETFIELD, OpCode.OP_SETI, OpCode.OP_SETTABLE, OpCode.OP_SETLIST -> {
                    if (!writesFreshTable(proto, code, pc, Instruction.getA(code[pc]))) {
                        impure = true;
                    }
                }
                case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                    if (isSetmetatableIntrinsic(proto, code, pc)
                            && !writesFreshTable(proto, code, pc, Instruction.getA(code[pc]) + 1)) {
                        impure = true;
                    }
                }
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
        // An impure proto may compile when it has a loop anywhere: its calls
        // enter only pure compiled callees through the snapshot-wrapped
        // protocol (which rolls the argument window back on a nested deopt),
        // and a builtin/impure callee deopts structurally at the call pc with
        // the impure prefix committed. Without a loop a straight-line prefix
        // that always reaches a call buys nothing.
        if (impure && hasCalls && firstLoopBackedgePc < 0) {
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
        // A pure proto whose only exit is a general tail call forwards the
        // callee's (unknown) result; classify it object-returning so the
        // dominant OOP method shape `return self:other(...)` compiles under
        // the object call protocol.
        boolean tailForwardOnly = !reachValue && !hasIntrinsicTail && !returnsVoid
                && hasTailCall && !impure;
        if (!reachValue && !hasIntrinsicTail && !returnsVoid && !tailForwardOnly) {
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
        if (tailForwardOnly) {
            // The tail call forwards an unknown result; carry it as a boxed
            // LuaValue under the object call protocol.
            seenObj = true;
        }
        boolean returnsInt = !seenObj;
        if (!returnsInt && hasCalls && !impure && !tailForwardOnly) {
            // Pure object-returning protos enter their callees under the
            // unboxed integer call protocol, which cannot carry an object
            // result, so reject. An impure proto deopts every call instead,
            // so its object result is only ever boxed at the call boundary.
            // A tail-forward-only proto is exempt: its tail call is emitted
            // via the object protocol (invokeTailObjSnap).
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
                || isTostringIntrinsic(proto, code, pc)
                || isSetmetatableIntrinsic(proto, code, pc)) {
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
     * Recognizes {@code setmetatable(t, mt)} whose function register is loaded
     * straight-line from the {@code _ENV} global. The constructor shape
     * {@code return setmetatable({...}, Class)} is the dominant OOP factory
     * idiom; inlining it removes the only call from the proto, so the factory
     * becomes an object-returning leaf and compiles. Both the one-result call
     * form and the tail-call form are recognized.
     */
    private static boolean isSetmetatableIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        int op = Instruction.getOp(inst);
        if (op == OpCode.OP_CALL) {
            if (Instruction.getB(inst) != 3 || Instruction.getC(inst) != 2) {
                return false;
            }
        } else if (op == OpCode.OP_TAILCALL) {
            if (Instruction.getB(inst) != 3) {
                return false;
            }
        } else {
            return false;
        }
        return isGlobalConst(proto, code, pc, Instruction.getA(inst), "setmetatable");
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
                } else if (isSetmetatableIntrinsic(proto, code, pc)) {
                    // setmetatable returns its (table) argument: an object.
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
     * True when {@code analyze}'s forward inference proved register {@code r}
     * holds a Lua integer at {@code pc}. The emitter may then drop the float
     * branch: every int-only lane still starts with a runtime
     * {@code guardInt}, so a wrong static guess (e.g. a parameter seeded
     * {@code T_INT} that arrives as a float) only costs a deopt, never a
     * wrong result.
     */
    private static boolean isInt(int[][] types, int pc, int r) {
        return types != null && pc >= 0 && pc < types.length && r >= 0 && r < types[pc].length
                && types[pc][r] == T_INT;
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
    private static void emitDivNum(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
        emitGuardNumber(mv, owner, b, pc);
        emitGuardNumber(mv, owner, c, pc);
        emitLoadDouble(mv, owner, b);
        emitLoadDouble(mv, owner, c);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ R[C]}; always float, uses C99 pow semantics. */
    private static void emitPowNum(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
        emitGuardNumber(mv, owner, b, pc);
        emitGuardNumber(mv, owner, c, pc);
        emitLoadDouble(mv, owner, b);
        emitLoadDouble(mv, owner, c);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaNumPow", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] // R[C]}: int floor-div, or float floor for numbers. */
    private static void emitIdivNum(MethodVisitor mv, String owner, int a, int b, int c, int pc, boolean intOnly) {
        if (intOnly) {
            emitGuardInt(mv, owner, b, pc);
            emitGuardInt(mv, owner, c, pc);
        } else {
            emitGuardNumber(mv, owner, b, pc);
            emitGuardNumber(mv, owner, c, pc);
        }
        Label floatLane = new Label();
        Label done = new Label();
        if (!intOnly) {
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
        }
        // int lane: guard /0 (interpreter raises), then floorDiv.
        emitLoadP(mv, c);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label divOk = new Label();
        mv.visitJumpInsn(IFNE, divOk);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(divOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorDiv", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        if (intOnly) {
            return;
        }
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, owner, b);
        emitLoadDouble(mv, owner, c);
        mv.visitInsn(DDIV);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    /** {@code R[A] = R[B] % R[C]}: int floor-mod, or Lua float mod. */
    private static void emitModNum(MethodVisitor mv, String owner, int a, int b, int c, int pc, boolean intOnly) {
        if (intOnly) {
            emitGuardInt(mv, owner, b, pc);
            emitGuardInt(mv, owner, c, pc);
        } else {
            emitGuardNumber(mv, owner, b, pc);
            emitGuardNumber(mv, owner, c, pc);
        }
        Label floatLane = new Label();
        Label done = new Label();
        if (!intOnly) {
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
        }
        // int lane: n%0 raises in the interpreter.
        emitLoadP(mv, c);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label modOk = new Label();
        mv.visitJumpInsn(IFNE, modOk);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(modOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b);
        emitLoadP(mv, c);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        if (intOnly) {
            return;
        }
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, owner, b);
        emitLoadDouble(mv, owner, c);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaFloatMod", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    /**
     * Two-register integer bitwise op ({@code BAND/BOR/BXOR}). Guards both
     * operands as integers (a float would raise in the interpreter, so the
     * deopt produces the same error).
     */
    private static void emitBitwiseNum(MethodVisitor mv, String owner, int a, int b, int c, int pc,
            int jvmIntOp, String kind) {
        emitGuardInt(mv, owner, b, pc);
        emitGuardInt(mv, owner, c, pc);
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
    private static void emitShiftNum(MethodVisitor mv, String owner, int a, int b, int c, int pc, boolean left) {
        emitGuardInt(mv, owner, b, pc);
        emitGuardInt(mv, owner, c, pc);
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
    private static void emitShiftImm(MethodVisitor mv, String owner, int a, int b, int sc, int pc, boolean left) {
        emitGuardInt(mv, owner, b, pc);
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
    private static void emitDivK(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int pc) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, owner, b, pc);
        emitLoadDouble(mv, owner, b);
        mv.visitLdcInsn(kv);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ K[C]}; always float with C99 pow semantics. */
    private static void emitPowK(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int pc) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, owner, b, pc);
        emitLoadDouble(mv, owner, b);
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
    private static void emitArithNum(MethodVisitor mv, String owner, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp, boolean intOnly, boolean forInner) {
        if (intOnly && jvmIntOp == LADD && forInner) {
            // Integer ADD lane as one INVOKESTATIC to the generated leaf
            // helper (C2 inlines it back for free); the float branch and the
            // inline tag checks are gone from the hot method.
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            emitIndex(mv, b);
            emitIndex(mv, c);
            ldcInt(mv, pc);
            mv.visitMethodInsn(INVOKESTATIC, owner, ADD_RR_NAME, ADD_RR_DESC, false);
            return;
        }
        if (intOnly) {
            emitGuardInt(mv, owner, b, pc);
            emitGuardInt(mv, owner, c, pc);
        } else {
            emitGuardNumber(mv, owner, b, pc);
            emitGuardNumber(mv, owner, c, pc);
        }
        Label floatLane = new Label();
        Label done = new Label();
        if (!intOnly) {
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
        }
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
        if (intOnly) {
            return;
        }
        mv.visitJumpInsn(GOTO, done);
        // Float lane.
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, owner, b);
        emitLoadDouble(mv, owner, c);
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
    private static void emitArithKNum(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp, boolean intOnly, boolean forInner) {
        LuaValue k = proto.constants[c];
        if (!(k instanceof LuaInteger)) {
            // analyze() rejects this today; keep a hard guard anyway.
            emitDeopt(mv, owner, pc);
            return;
        }
        long kv = ((LuaInteger) k).toLong();
        if (intOnly && jvmIntOp == LSUB && forInner) {
            // Integer SUBK lane as one INVOKESTATIC to the generated leaf
            // helper; the float branch and the inline tag check are gone.
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            emitIndex(mv, b);
            mv.visitLdcInsn(kv);
            ldcInt(mv, pc);
            mv.visitMethodInsn(INVOKESTATIC, owner, SUB_K_NAME, SUB_K_DESC, false);
            return;
        }
        if (intOnly) {
            emitGuardInt(mv, owner, b, pc);
        } else {
            emitGuardNumber(mv, owner, b, pc);
        }
        if (jvmFloatOp < 0) {
            // Bitwise K-forms are integer-only (interpreter yields an error
            // for floats, which deopts here).
            emitGuardInt(mv, owner, b, pc);
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
        if (!intOnly) {
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, b);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_INT);
            mv.visitJumpInsn(IF_ICMPNE, floatLane);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        mv.visitLdcInsn(kv);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        if (intOnly) {
            return;
        }
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, owner, b);
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
    private static void emitSqrtTail(MethodVisitor mv, String owner, int a, int pc) {
        emitSqrtGuardAndCompute(mv, owner, a, pc);
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
    private static void emitSqrtCall(MethodVisitor mv, String owner, int a, int pc) {
        emitSqrtGuardAndCompute(mv, owner, a, pc);
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
    private static void emitSqrtGuardAndCompute(MethodVisitor mv, String owner, int a, int pc) {
        emitBuiltinIdentityGuard(mv, owner, a, "SQRT", pc);
        // Argument must be a number.
        emitGuardNumber(mv, owner, a + 1, pc);
        emitLoadDouble(mv, owner, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "sqrt", "(D)D", false);
    }

    /**
     * Emits {@code R[a] = tostring(R[a+1])} inline under an identity guard on
     * the shared {@code BaseLib.TOSTRING} singleton. The helper renders the
     * primitive/string cases exactly and returns {@code null} otherwise, so a
     * value with a {@code __tostring} metatable (or a number-format override)
     * deopts to the interpreter instead of producing a different string.
     */
    private static void emitTostringCall(MethodVisitor mv, String owner, int a, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "TOSTRING",
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, owner, pc);
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
        emitDeopt(mv, owner, pc);
        mv.visitLabel(rendered);
        emitStoreValueReg(mv, a);
    }

    /**
     * Emits the {@code setmetatable(t, mt)} intrinsic for the one-result CALL
     * form: {@code R[a] = setmetatable(R[a+1], R[a+2])}. The function register
     * is identity-guarded against the shared {@code BaseLib.SETMETATABLE}
     * singleton; anything else (a reassigned or shadowed global) deopts. The
     * helper returns {@code null} for a non-table target, an invalid metatable
     * shape, or a {@code __gc}/{@code __mode} metatable, so the interpreter
     * raises the exact error / performs the registration exactly once.
     */
    private static void emitSetmetatableCall(MethodVisitor mv, String owner, int a, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "SETMETATABLE",
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(ok);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a + 2);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "setmetatableInline",
                "([J[B[Lorg/luava/runtime/LuaValue;II)Lorg/luava/runtime/LuaValue;", false);
        mv.visitInsn(DUP);
        Label done = new Label();
        mv.visitJumpInsn(IFNONNULL, done);
        mv.visitInsn(POP);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(done);
        emitStoreValueReg(mv, a);
    }

    /** Deopts unless {@code R[reg]} is the {@code MathLib.<field>} singleton. */
    private static void emitBuiltinIdentityGuard(MethodVisitor mv, String owner, int reg, String field, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, reg);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/MathLib", field,
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, owner, pc);
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
    private static void emitTForSetup(MethodVisitor mv, String owner, int a, int nArgs, int pc) {
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
        emitDeopt(mv, owner, pc, true);
        mv.visitLabel(isPairs);
        emitGetValueReg(mv, a + 1);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/standard/BaseLib", "pairsFast",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/Varargs;", false);
        // pairsFast returns null for an __pairs target: deopt to the handler.
        mv.visitInsn(DUP);
        Label pairsOk = new Label();
        mv.visitJumpInsn(IFNONNULL, pairsOk);
        mv.visitInsn(POP);
        emitDeopt(mv, owner, pc);
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
    private static void emitFloorCall(MethodVisitor mv, String owner, int a, int pc) {
        emitBuiltinIdentityGuard(mv, owner, a, "FLOOR", pc);
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
        emitDeopt(mv, owner, pc);
        mv.visitLabel(floatArg);
        emitLoadDouble(mv, owner, a + 1);
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
    private static void emitFloorSqrtCall(MethodVisitor mv, String owner, int af, int pc) {
        emitBuiltinIdentityGuard(mv, owner, af, "FLOOR", pc);
        emitBuiltinIdentityGuard(mv, owner, af + 1, "SQRT", pc);
        emitGuardNumber(mv, owner, af + 2, pc);
        emitLoadDouble(mv, owner, af + 2);
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
    private static void emitGuardNumber(MethodVisitor mv, String owner, int reg, int pc) {
        // Outlined: a single INVOKESTATIC instead of an inline tag check.
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESTATIC, owner, GUARD_NUM_NAME, GUARD_NUM_DESC, false);
    }

    /** Pushes register {@code reg} as a JVM double (int value or raw float). */
    private static void emitLoadDouble(MethodVisitor mv, String owner, int reg) {
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
    private static void emitIdivK(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int pc) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        emitGuardInt(mv, owner, b, pc);
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
    private static void emitModK(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int pc) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        emitGuardInt(mv, owner, b, pc);
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
    private static void emitForPrep(MethodVisitor mv, String owner, int a, int bx, int pc, Label[] labels) {
        emitGuardInt(mv, owner, a, pc);
        emitGuardInt(mv, owner, a + 1, pc);
        emitGuardInt(mv, owner, a + 2, pc);
        // step == 0 -> deopt (interpreter raises "'for' step is zero")
        emitLoadP(mv, a + 2);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label stepOk = new Label();
        mv.visitJumpInsn(IFNE, stepOk);
        emitDeopt(mv, owner, pc);
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
    private static void emitForLoop(MethodVisitor mv, String owner, int a, int bx, int pc, Label[] labels) {
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
    private static void emitTForPrep(MethodVisitor mv, String owner, int a, int bx, int pc, Label[] labels) {
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
    private static void emitTForCall(MethodVisitor mv, String owner, int a, int c, int pc) {
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
    private static void emitTForLoop(MethodVisitor mv, String owner, int a, int bx, int pc, Label[] labels) {
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

    /**
     * Compare {@code R[a]} against a small integer immediate. The register may
     * hold an integer or a float (e.g. {@code acc > 0} with a float
     * accumulator), so mirror {@link #emitCmpRR}: an int lane for
     * {@code TYPE_INT} and a double lane otherwise. The previous
     * {@code emitGuardInt} deopted on every float-vs-immediate comparison and,
     * after the small guard budget, disarmed the whole kernel — which is what
     * pushed the OOP hot loop back to the interpreter.
     */
    private static void emitCmpImm(
            MethodVisitor mv, String owner, int a, int sb, int k, int pc, Label[] labels, int jumpIfTrue, int jumpIfFalse,
            boolean intOnly, boolean forInner) {
        if (intOnly && jumpIfTrue == IFLE && jumpIfFalse == IFGT && forInner) {
            // Integer LEI lane as one INVOKESTATIC to the generated leaf
            // helper, which returns the condition; the float branch is gone.
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            ldcInt(mv, sb);
            ldcInt(mv, pc);
            mv.visitMethodInsn(INVOKESTATIC, owner, LEI_IMM_NAME, LEI_IMM_DESC, false);
            Label leiFalse = new Label();
            mv.visitJumpInsn(IFEQ, leiFalse);
            if (k == 1) {
                mv.visitJumpInsn(GOTO, labels[pc + 1]);
            } else {
                mv.visitJumpInsn(GOTO, labels[pc + 2]);
            }
            mv.visitLabel(leiFalse);
            if (k == 1) {
                mv.visitJumpInsn(GOTO, labels[pc + 2]);
            } else {
                mv.visitJumpInsn(GOTO, labels[pc + 1]);
            }
            return;
        }
        if (intOnly) {
            emitGuardInt(mv, owner, a, pc);
        } else {
            emitGuardNumber(mv, owner, a, pc);
        }
        Label intLane = new Label();
        Label condTrue = new Label();
        Label condFalse = new Label();
        if (!intOnly) {
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, a);
            mv.visitInsn(BALOAD);
            ldcInt(mv, TYPE_INT);
            mv.visitJumpInsn(IF_ICMPEQ, intLane);
            // Float lane: promote to double. Lua's comparisons treat NaN as false
            // for every predicate, so the DCMP variant must push a value that
            // fails the "true" branch: DCMPG pushes +1 for NaN (fails IFLE/IFLT),
            // DCMPL pushes -1 (fails IFGT/IFGE). Using one variant unconditionally
            // would make `nan > 0` or `nan <= 0` wrong.
            int dcmpl = (jumpIfTrue == IFGT || jumpIfTrue == IFGE) ? DCMPL : DCMPG;
            emitLoadDouble(mv, owner, a);
            mv.visitLdcInsn((double) sb);
            mv.visitInsn(dcmpl);
            mv.visitJumpInsn(jumpIfTrue, condTrue);
            mv.visitJumpInsn(GOTO, condFalse);
        }
        mv.visitLabel(intLane);
        emitLoadP(mv, a);
        mv.visitLdcInsn((long) sb);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(jumpIfTrue, condTrue);
        // Condition false: skip-next iff false != (k==1).
        mv.visitLabel(condFalse);
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
    private static void emitCmpRR(MethodVisitor mv, String owner, int a, int b, int k, int pc, Label[] labels,
            boolean orEqual, boolean intOnly) {
        int cmpOp = orEqual ? IFLE : IFLT;
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        if (intOnly) {
            emitGuardInt(mv, owner, a, pc);
            emitGuardInt(mv, owner, b, pc);
            emitLoadP(mv, a);
            emitLoadP(mv, b);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(cmpOp, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(doneTrue);
            if (k == 1) {
                mv.visitJumpInsn(GOTO, labels[pc + 1]);
            } else {
                mv.visitJumpInsn(GOTO, labels[pc + 2]);
            }
            mv.visitLabel(doneFalse);
            if (k == 1) {
                mv.visitJumpInsn(GOTO, labels[pc + 2]);
            } else {
                mv.visitJumpInsn(GOTO, labels[pc + 1]);
            }
            return;
        }
        emitGuardNumber(mv, owner, a, pc);
        emitGuardNumber(mv, owner, b, pc);
        // condition = R[A] < R[B] (or <= when orEqual). DCMPG pushes +1 for
        // NaN so both IFLT and IFLE are false (matches Lua NaN ordering).
        Label intLane = new Label();
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intLane);
        // Float lane: promote both to double.
        emitLoadDouble(mv, owner, a);
        emitLoadDouble(mv, owner, b);
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
        emitLoadDouble(mv, owner, b);
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
    private static void emitEqRR(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int k, int pc,
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
        emitDeopt(mv, owner, pc);
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
        emitLoadDouble(mv, owner, a);
        emitLoadDouble(mv, owner, b);
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
        emitLoadDouble(mv, owner, a);
        emitLoadDouble(mv, owner, b);
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
    private static void emitEqK(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int k, int pc,
            Label[] labels, boolean intOnly) {
        LuaValue kv = proto.constants[b];
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        if (kv instanceof LuaInteger ki) {
            long c = ki.toLong();
            if (intOnly) {
                // analyze() proved R[A] is an integer; a float can never
                // reach here without deopting at the guard first.
                emitGuardInt(mv, owner, a, pc);
                emitLoadP(mv, a);
                mv.visitLdcInsn(c);
                mv.visitInsn(LCMP);
                mv.visitJumpInsn(IFEQ, doneTrue);
                mv.visitJumpInsn(GOTO, doneFalse);
            } else {
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
                emitLoadDouble(mv, owner, a);
                mv.visitLdcInsn((double) c);
                mv.visitInsn(DCMPL);
                mv.visitJumpInsn(IFEQ, doneTrue);
                mv.visitJumpInsn(GOTO, doneFalse);
            }
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
            emitLoadDouble(mv, owner, a);
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
            emitDeopt(mv, owner, pc);
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
    private static void emitTest(MethodVisitor mv, String owner, int a, int k, int pc, Label[] labels) {
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
    private static void emitTestSet(MethodVisitor mv, String owner, int a, int b, int k, int pc, Label[] labels) {
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
     * Call site with three tiers: hoisted self (classified at entry) into the
     * prologue-free inner entry; fused slow path (cold: the fast tier handles
     * entry-verified self) via the generated {@code callSlow} helper; and the
     * general slow path for non-fused calls kept inline in the baseline shape
     * (its hot path must not pay an extra helper hop — C2 does not inline a
     * 100+ byte helper into a hot loop, measured +4% on the OOP task).
     */
    private static void emitCall(MethodVisitor mv, String owner, LuaProto proto, int a, int nArgs,
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount,
            boolean resultUsed, boolean callerReturnsInt, boolean selfSafe, boolean forInner) {
        Label done = new Label();
        // Only an int-returning, parameter-preserving proto has a prologue-free
        // self entry (it has no window rollback); an object-returning caller
        // routes every call through the general snapshot-wrapped helper.
        if (hoistSlot >= 0 && callerReturnsInt && selfSafe) {
            // Fast tier: entry-classified self callee straight into the
            // prologue-free inner entry (upvalues provably unchanged).
            mv.visitVarInsn(ILOAD, 9 + 2 * hoistSlot);
            Label general = new Label();
            mv.visitJumpInsn(IFEQ, general);
            mv.visitVarInsn(ALOAD, 10 + 2 * hoistSlot);
            mv.visitVarInsn(ASTORE, 8);
            emitInnerInvoke(mv, owner, proto, a, nArgs, resumePc, fusedBList, fusedBCount, forInner);
            mv.visitJumpInsn(GOTO, done);
            mv.visitLabel(general);
        }
        // Slow path. Fused slow tiers (cold: the fast tier handles
        // entry-verified self) live in the generated `callSlow` helper:
        // inlining them cost ~200 bytes per call site and kept C2 from
        // inlining the hot recursive entry. Non-fused slow tiers stay
        // inline in the baseline shape below.
        if (fusedB >= 0) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a);
            ldcInt(mv, nArgs);
            ldcInt(mv, resumePc);
            if (resultUsed) {
                mv.visitInsn(ICONST_1);
            } else {
                mv.visitInsn(ICONST_0);
            }
            ldcInt(mv, fusedB);
            mv.visitMethodInsn(INVOKESTATIC, owner, CALL_SLOW_NAME, CALL_SLOW_DESC, false);
        } else {
            // General slow path, baseline inline shape. The callee comes from
            // the register window (no fused upvalue); its hot path runs as
            // machine code exactly like before the outline.
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitInsn(AALOAD);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ASTORE, 8);
            mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/bytecode/LuaClosure");
            Label isClosure = new Label();
            mv.visitJumpInsn(IFNE, isClosure);
            // A builtin (Java-backed) callee is not a LuaClosure and never
            // will be, so this deopt is expected on every invocation; it must
            // not consume the small guard-failure budget and disarm the hot
            // prefix.
            emitDeopt(mv, owner, resumePc, true);
            mv.visitLabel(isClosure);
            // Same proto -> direct INVOKESTATIC; else the general leaf helper
            // (tiny, C2-inlined).
            Label generalCall = new Label();
            if (!callerReturnsInt || !selfSafe) {
                // Object-returning callers, and parameter-writing protos whose
                // direct self call has no window rollback, use the general
                // snapshot-wrapped path. The proto comparison is not emitted
                // here at all: it would push two refs that only IF_ACMPNE
                // consumes, and a GOTO past it would carry them onto the
                // operand stack, leaving the two edges into `generalCall`
                // with different stack depths. That inconsistency is invisible
                // to the emitter but makes ASM's COMPUTE_FRAMES throw
                // ArrayIndexOutOfBounds in Frame.merge, so every
                // object-returning proto containing a call silently failed to
                // compile (JitCompiler caught it and set jitDisabled).
                mv.visitJumpInsn(GOTO, generalCall);
            } else {
                mv.visitVarInsn(ALOAD, 8);
                mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
                mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                        "Lorg/luava/runtime/bytecode/LuaProto;");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                        "Lorg/luava/runtime/bytecode/LuaProto;");
                mv.visitJumpInsn(IF_ACMPNE, generalCall);
                emitDirectInvoke(mv, owner, proto, a, nArgs, resumePc);
                mv.visitJumpInsn(GOTO, done);
            }
            mv.visitLabel(generalCall);
            mv.visitVarInsn(ALOAD, 8);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1);
            mv.visitInsn(IADD);
            ldcInt(mv, nArgs);
            ldcInt(mv, resumePc);
            if (resultUsed) {
                mv.visitInsn(ICONST_1);
            } else {
                mv.visitInsn(ICONST_0);
            }
            mv.visitMethodInsn(INVOKESTATIC, owner, CALL_SLOW_GENERAL_NAME, CALL_SLOW_GENERAL_DESC, false);
        }
        mv.visitLabel(done);
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
        emitDeopt(mv, owner, resumePc);
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
        // Unboxed int store as one INVOKESTATIC to the generated leaf helper.
        mv.visitVarInsn(LSTORE, 6);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitMethodInsn(INVOKESTATIC, owner, STORE_RAW_NAME, STORE_RAW_DESC, false);
    }

    /**
     * Emits the tiny generated guard/throw helpers shared by every method of
     * the class. Each guard is a leaf (no calls, no handlers) so C2 inlines it
     * back for free; in bytecode it is a single {@code INVOKESTATIC}, which
     * keeps the hot method under the inline budget.
     */
    private static void emitGuardHelpers(ClassWriter cw, String owner) {
        MethodVisitor md = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, DEOPT_NAME, DEOPT_DESC, null, null);
        md.visitCode();
        md.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        md.visitInsn(DUP);
        md.visitVarInsn(ILOAD, 0);
        md.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(I)V", false);
        md.visitInsn(ARETURN);
        md.visitMaxs(0, 0);
        md.visitEnd();
        MethodVisitor ms = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, DEOPT_S_NAME, DEOPT_S_DESC, null, null);
        ms.visitCode();
        ms.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        ms.visitInsn(DUP);
        ms.visitVarInsn(ILOAD, 0);
        ms.visitInsn(ICONST_1);
        ms.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(IZ)V", false);
        ms.visitInsn(ARETURN);
        ms.visitMaxs(0, 0);
        ms.visitEnd();
        emitTagGuard(cw, owner, GUARD_INT_NAME, TYPE_INT);
        emitTagGuard(cw, owner, GUARD_NUM_NAME, -1);
        emitTagGuard(cw, owner, GUARD_OBJ_NAME, TYPE_OBJECT);
    }

    /**
     * Emits one tag-guard helper {@code (byte[] t, int idx, int pc)}: deopts
     * unless {@code t[idx]} equals {@code tag}, or — when {@code tag < 0} —
     * is an int/float number tag.
     */
    private static void emitTagGuard(ClassWriter cw, String owner, String name, int tag) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, name,
                tag == TYPE_INT || tag == TYPE_OBJECT ? GUARD_INT_DESC : GUARD_NUM_DESC, null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitInsn(BALOAD);
        if (tag >= 0) {
            ldcInt(mv, tag);
            Label ok = new Label();
            mv.visitJumpInsn(IF_ICMPEQ, ok);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(ok);
        } else {
            mv.visitVarInsn(ISTORE, 3);
            Label isNum = new Label();
            mv.visitVarInsn(ILOAD, 3);
            ldcInt(mv, TYPE_INT);
            mv.visitJumpInsn(IF_ICMPEQ, isNum);
            mv.visitVarInsn(ILOAD, 3);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitJumpInsn(IF_ICMPEQ, isNum);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
            mv.visitInsn(ATHROW);
            mv.visitLabel(isNum);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits the three integer-lane helpers. Each is a tiny leaf (no calls
     * except the cold deopt throw, no handlers), so C2 inlines it back into
     * the hot method for free; in bytecode the whole lane is one
     * {@code INVOKESTATIC}, which is what gets the recursive kernel under the
     * inline budget. Semantics mirror the inline int lanes exactly, including
     * the guard-then-deopt and the raw-bits store/tag protocol.
     */
    private static void emitLaneHelpers(ClassWriter cw, String owner) {
        // R[a] = R[b] - kv, both statically int.
        MethodVisitor sk = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, SUB_K_NAME, SUB_K_DESC, null, null);
        sk.visitCode();
        sk.visitVarInsn(ALOAD, 1);
        sk.visitVarInsn(ILOAD, 4);
        sk.visitInsn(BALOAD);
        ldcInt(sk, TYPE_INT);
        Label skOk = new Label();
        sk.visitJumpInsn(IF_ICMPEQ, skOk);
        sk.visitVarInsn(ILOAD, 7);
        sk.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        sk.visitInsn(ATHROW);
        sk.visitLabel(skOk);
        sk.visitVarInsn(ALOAD, 0);
        sk.visitVarInsn(ILOAD, 3);
        sk.visitVarInsn(ALOAD, 0);
        sk.visitVarInsn(ILOAD, 4);
        sk.visitInsn(LALOAD);
        sk.visitVarInsn(LLOAD, 5);
        sk.visitInsn(LSUB);
        sk.visitInsn(LASTORE);
        sk.visitVarInsn(ALOAD, 1);
        sk.visitVarInsn(ILOAD, 3);
        ldcInt(sk, TYPE_INT);
        sk.visitInsn(BASTORE);
        sk.visitVarInsn(ALOAD, 2);
        sk.visitVarInsn(ILOAD, 3);
        sk.visitInsn(ACONST_NULL);
        sk.visitInsn(AASTORE);
        sk.visitInsn(RETURN);
        sk.visitMaxs(0, 0);
        sk.visitEnd();
        // R[a] = R[b] + R[c], all statically int.
        MethodVisitor ad = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, ADD_RR_NAME, ADD_RR_DESC, null, null);
        ad.visitCode();
        ad.visitVarInsn(ALOAD, 1);
        ad.visitVarInsn(ILOAD, 4);
        ad.visitInsn(BALOAD);
        ldcInt(ad, TYPE_INT);
        Label adOkB = new Label();
        ad.visitJumpInsn(IF_ICMPEQ, adOkB);
        ad.visitVarInsn(ILOAD, 6);
        ad.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        ad.visitInsn(ATHROW);
        ad.visitLabel(adOkB);
        ad.visitVarInsn(ALOAD, 1);
        ad.visitVarInsn(ILOAD, 5);
        ad.visitInsn(BALOAD);
        ldcInt(ad, TYPE_INT);
        Label adOkC = new Label();
        ad.visitJumpInsn(IF_ICMPEQ, adOkC);
        ad.visitVarInsn(ILOAD, 6);
        ad.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        ad.visitInsn(ATHROW);
        ad.visitLabel(adOkC);
        ad.visitVarInsn(ALOAD, 0);
        ad.visitVarInsn(ILOAD, 3);
        ad.visitVarInsn(ALOAD, 0);
        ad.visitVarInsn(ILOAD, 4);
        ad.visitInsn(LALOAD);
        ad.visitVarInsn(ALOAD, 0);
        ad.visitVarInsn(ILOAD, 5);
        ad.visitInsn(LALOAD);
        ad.visitInsn(LADD);
        ad.visitInsn(LASTORE);
        ad.visitVarInsn(ALOAD, 1);
        ad.visitVarInsn(ILOAD, 3);
        ldcInt(ad, TYPE_INT);
        ad.visitInsn(BASTORE);
        ad.visitVarInsn(ALOAD, 2);
        ad.visitVarInsn(ILOAD, 3);
        ad.visitInsn(ACONST_NULL);
        ad.visitInsn(AASTORE);
        ad.visitInsn(RETURN);
        ad.visitMaxs(0, 0);
        ad.visitEnd();
        // (R[a] <= sb), R[a] statically int. Returns the condition.
        MethodVisitor lei = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, LEI_IMM_NAME, LEI_IMM_DESC, null, null);
        lei.visitCode();
        lei.visitVarInsn(ALOAD, 0);
        lei.visitVarInsn(ILOAD, 2);
        lei.visitInsn(BALOAD);
        ldcInt(lei, TYPE_INT);
        Label leiOk = new Label();
        lei.visitJumpInsn(IF_ICMPEQ, leiOk);
        lei.visitVarInsn(ILOAD, 4);
        lei.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        lei.visitInsn(ATHROW);
        lei.visitLabel(leiOk);
        lei.visitVarInsn(ALOAD, 1);
        lei.visitVarInsn(ILOAD, 2);
        lei.visitInsn(LALOAD);
        lei.visitVarInsn(ILOAD, 3);
        lei.visitInsn(I2L);
        lei.visitInsn(LCMP);
        Label leiTrue = new Label();
        lei.visitJumpInsn(IFLE, leiTrue);
        lei.visitInsn(ICONST_0);
        lei.visitInsn(IRETURN);
        lei.visitLabel(leiTrue);
        lei.visitInsn(ICONST_1);
        lei.visitInsn(IRETURN);
        lei.visitMaxs(0, 0);
        lei.visitEnd();
        // Guarded int return: the int protocol returns the raw long.
        MethodVisitor ri = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, RET_INT_NAME, RET_INT_DESC, null, null);
        ri.visitCode();
        ri.visitVarInsn(ALOAD, 1);
        ri.visitVarInsn(ILOAD, 2);
        ri.visitInsn(BALOAD);
        ldcInt(ri, TYPE_INT);
        Label riOk = new Label();
        ri.visitJumpInsn(IF_ICMPEQ, riOk);
        ri.visitVarInsn(ILOAD, 3);
        ri.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        ri.visitInsn(ATHROW);
        ri.visitLabel(riOk);
        ri.visitVarInsn(ALOAD, 0);
        ri.visitVarInsn(ILOAD, 2);
        ri.visitInsn(LALOAD);
        ri.visitInsn(LRETURN);
        ri.visitMaxs(0, 0);
        ri.visitEnd();
        // Unboxed int store: raw bits plus the INT tag and a null object.
        MethodVisitor sr = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, STORE_RAW_NAME, STORE_RAW_DESC, null, null);
        sr.visitCode();
        sr.visitVarInsn(ALOAD, 0);
        sr.visitVarInsn(ILOAD, 3);
        sr.visitVarInsn(LLOAD, 4);
        sr.visitInsn(LASTORE);
        sr.visitVarInsn(ALOAD, 1);
        sr.visitVarInsn(ILOAD, 3);
        ldcInt(sr, TYPE_INT);
        sr.visitInsn(BASTORE);
        sr.visitVarInsn(ALOAD, 2);
        sr.visitVarInsn(ILOAD, 3);
        sr.visitInsn(ACONST_NULL);
        sr.visitInsn(AASTORE);
        sr.visitInsn(RETURN);
        sr.visitMaxs(0, 0);
        sr.visitEnd();
    }

    /**
     * Emits the generated {@code callSlow} slow-path helper shared by every
     * call site of the class. Parameters mirror the kernel window plus the
     * per-site constants ({@code a}, {@code nArgs}, {@code resumePc},
     * {@code resultUsed}, {@code fusedB}, and whether the same-proto direct
     * lane applies). Any nested {@link DeoptSignal} converts to
     * {@code resumePc} exactly like the {@code emitGuardedInvoke} wrapper it
     * replaces, and a builtin callee deopts structurally.
     */
    private static void emitCallSlowHelper(ClassWriter cw, String owner) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, CALL_SLOW_NAME, CALL_SLOW_DESC,
                null, null);
        mv.visitCode();
        // Resolve the callee closure (fused: straight from the upvalue).
        mv.visitVarInsn(ILOAD, 10);
        Label useReg = new Label();
        mv.visitJumpInsn(IFLT, useReg);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 10);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        Label resolved = new Label();
        mv.visitJumpInsn(GOTO, resolved);
        mv.visitLabel(useReg);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IADD);
        mv.visitInsn(AALOAD);
        mv.visitLabel(resolved);
        mv.visitVarInsn(ASTORE, 12);
        mv.visitVarInsn(ALOAD, 12);
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/bytecode/LuaClosure");
        Label isClosure = new Label();
        mv.visitJumpInsn(IFNE, isClosure);
        mv.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ILOAD, 8);
        mv.visitInsn(ICONST_1);
        mv.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(IZ)V", false);
        mv.visitInsn(ATHROW);
        mv.visitLabel(isClosure);
        // Same proto -> direct INVOKESTATIC; else the tiny general leaf.
        Label generalCall = new Label();
        mv.visitVarInsn(ALOAD, 12);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitJumpInsn(IF_ACMPNE, generalCall);
        // Same-proto direct call goes to the `callDirect` leaf; anything
        // else delegates to the tiny general leaf.
        mv.visitVarInsn(ALOAD, 12);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IADD);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitVarInsn(ILOAD, 8);
        mv.visitMethodInsn(INVOKESTATIC, owner, CALL_DIRECT_NAME, CALL_DIRECT_DESC, false);
        mv.visitVarInsn(LSTORE, 13);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IADD);
        mv.visitVarInsn(LLOAD, 13);
        mv.visitMethodInsn(INVOKESTATIC, owner, STORE_RAW_NAME, STORE_RAW_DESC, false);
        mv.visitInsn(RETURN);
        // Different proto (or builtin-shaped): delegate to the tiny general
        // leaf, which C2 inlines at hot call sites.
        mv.visitLabel(generalCall);
        mv.visitVarInsn(ALOAD, 12);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        mv.visitVarInsn(ILOAD, 6);
        mv.visitInsn(IADD);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ILOAD, 7);
        mv.visitVarInsn(ILOAD, 8);
        mv.visitVarInsn(ILOAD, 9);
        mv.visitMethodInsn(INVOKESTATIC, owner, CALL_SLOW_GENERAL_NAME, CALL_SLOW_GENERAL_DESC, false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        // Tiny general leaf: the snapshot-wrapped call protocol with the
        // nested-deopt conversion. Small enough to inline everywhere.
        MethodVisitor mg = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, CALL_SLOW_GENERAL_NAME,
                CALL_SLOW_GENERAL_DESC, null, null);
        mg.visitCode();
        Label gTryStart = new Label();
        Label gTryEnd = new Label();
        Label gHandler = new Label();
        Label gCont = new Label();
        mg.visitLabel(gTryStart);
        mg.visitVarInsn(ALOAD, 0);
        mg.visitVarInsn(ALOAD, 1);
        mg.visitVarInsn(ALOAD, 2);
        mg.visitVarInsn(ALOAD, 3);
        mg.visitVarInsn(ILOAD, 4);
        mg.visitVarInsn(ILOAD, 5);
        mg.visitVarInsn(ILOAD, 6);
        mg.visitVarInsn(ILOAD, 7);
        mg.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "callFast",
                "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;IIIZ)V", false);
        mg.visitLabel(gTryEnd);
        mg.visitJumpInsn(GOTO, gCont);
        mg.visitLabel(gHandler);
        mg.visitInsn(POP);
        mg.visitTypeInsn(NEW, "org/luava/runtime/jit/DeoptSignal");
        mg.visitInsn(DUP);
        mg.visitVarInsn(ILOAD, 6);
        mg.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/DeoptSignal", "<init>", "(I)V", false);
        mg.visitInsn(ATHROW);
        mg.visitLabel(gCont);
        mg.visitTryCatchBlock(gTryStart, gTryEnd, gHandler, "org/luava/runtime/jit/DeoptSignal");
        mg.visitInsn(RETURN);
        mg.visitMaxs(0, 0);
        mg.visitEnd();
        // Same-proto direct call: capacity guard, arity fill, then the
        // callee's own entry with the nested-deopt conversion. A nested
        // deopt converts to this call's resumePc: the caller resumes here
        // and re-invokes the (pure) callee from scratch.
        MethodVisitor md = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, CALL_DIRECT_NAME, CALL_DIRECT_DESC,
                null, null);
        md.visitCode();
        // Parameters: 0=callee, 1=p, 2=t, 3=o, 4=newBase, 5=nArgs, 6=pc.
        md.visitVarInsn(ILOAD, 4);
        md.visitVarInsn(ALOAD, 0);
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "maxStackSize", "I");
        md.visitInsn(IADD);
        md.visitVarInsn(ALOAD, 1);
        md.visitInsn(ARRAYLENGTH);
        Label dCapOk = new Label();
        md.visitJumpInsn(IF_ICMPLT, dCapOk);
        md.visitVarInsn(ILOAD, 6);
        md.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        md.visitInsn(ATHROW);
        md.visitLabel(dCapOk);
        md.visitVarInsn(ILOAD, 5);
        md.visitVarInsn(ALOAD, 0);
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        Label dSkipNil = new Label();
        md.visitJumpInsn(IF_ICMPGE, dSkipNil);
        md.visitVarInsn(ALOAD, 1);
        md.visitVarInsn(ALOAD, 2);
        md.visitVarInsn(ALOAD, 3);
        md.visitVarInsn(ILOAD, 4);
        md.visitVarInsn(ILOAD, 5);
        md.visitInsn(IADD);
        md.visitVarInsn(ILOAD, 4);
        md.visitVarInsn(ALOAD, 0);
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "numParams", "I");
        md.visitInsn(IADD);
        md.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "nilFill",
                "([J[B[Lorg/luava/runtime/LuaValue;II)V", false);
        md.visitLabel(dSkipNil);
        Label dTryStart = new Label();
        Label dTryEnd = new Label();
        Label dHandler = new Label();
        Label dCont = new Label();
        md.visitLabel(dTryStart);
        md.visitVarInsn(ALOAD, 0);
        md.visitInsn(DUP);
        md.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "upvals",
                "[Lorg/luava/runtime/eval/Upvalue;");
        md.visitVarInsn(ALOAD, 1);
        md.visitVarInsn(ALOAD, 2);
        md.visitVarInsn(ALOAD, 3);
        md.visitVarInsn(ILOAD, 4);
        md.visitMethodInsn(INVOKESTATIC, owner, EXEC_NAME, EXEC_DESC, false);
        md.visitLabel(dTryEnd);
        md.visitJumpInsn(GOTO, dCont);
        md.visitLabel(dHandler);
        md.visitInsn(POP);
        md.visitVarInsn(ILOAD, 6);
        md.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        md.visitInsn(ATHROW);
        md.visitLabel(dCont);
        md.visitTryCatchBlock(dTryStart, dTryEnd, dHandler, "org/luava/runtime/jit/DeoptSignal");
        md.visitInsn(LRETURN);
        md.visitMaxs(0, 0);
        md.visitEnd();
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
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount, Label[] labels,
            boolean callerReturnsInt, boolean selfSafe) {
        if (hoistSlot >= 0 && selfSafe) {
            // Self tail recursion on the same closure: reuse our own window
            // as a true loop (no Java stack growth), then re-enter at pc 0.
            // Sound: identical closure, identical upvalue array, and missing
            // params are nil-filled exactly like the interpreter. Requires
            // selfSafe: the args are shifted onto our window before entering,
            // so a parameter-writing proto could clobber them before a nested
            // deopt.
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
        emitDeopt(mv, owner, resumePc);
        mv.visitLabel(isClosure);
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
        if (callerReturnsInt && selfSafe) {
            // Self tail recursion: shift args onto our own window, then jump
            // back into the kernel (no Java stack growth).
            if (nArgs > 0) {
                arraycopyTriple(mv, a + 1, 0, nArgs);
            }
            emitTailDirectInvoke(mv, owner, proto, nArgs, resumePc);
            mv.visitInsn(LRETURN);
        } else {
            // Object-returning or parameter-writing protos route their self
            // tail call through the snapshot-wrapped general helper.
            mv.visitJumpInsn(GOTO, generalCall);
        }
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
        // See emitCall: request compilation of a callee reached only from
        // compiled code, then deopt so the interpreter runs it once.
        mv.visitVarInsn(ALOAD, 20);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "requestCallee",
                "(Lorg/luava/runtime/bytecode/LuaProto;)V", false);
        emitDeopt(mv, owner, resumePc);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "pure", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, owner, resumePc);
        mv.visitLabel(isPure);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsInt", "Z");
        Label tailObj = new Label();
        if (callerReturnsInt) {
            Label returnsIntOk = new Label();
            mv.visitJumpInsn(IFNE, returnsIntOk);
            // The enclosing proto returns an integer; an object callee's
            // result cannot feed the long lane. Structural.
            emitDeopt(mv, owner, resumePc, true);
            mv.visitLabel(returnsIntOk);
        } else {
            mv.visitJumpInsn(IFEQ, tailObj);
            // The enclosing proto returns an object; an integer callee's long
            // result cannot feed the boxed lane. Structural.
            emitDeopt(mv, owner, resumePc, true);
            mv.visitLabel(tailObj);
        }
        // A void callee forwards zero results; the compiled tail path can
        // only return one value (int or object), so run it interpreted.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsVoid", "Z");
        Label notVoidTail = new Label();
        mv.visitJumpInsn(IFEQ, notVoidTail);
        emitDeopt(mv, owner, resumePc, true);
        mv.visitLabel(notVoidTail);
        // invokeTail(Snap)/invokeTailObjSnap snapshot the pre-shift caller
        // window, shift the arguments down to our base, nil-fill missing
        // params and invoke. On a nested deopt the window is restored so the
        // interpreter re-executes the tail call exactly.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/bytecode/LuaClosure");
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a);
        ldcInt(mv, nArgs);
        ldcInt(mv, proto.maxStackSize);
        if (callerReturnsInt) {
            emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "invokeTailSnap",
                    "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;IIII)J",
                    resumePc);
            mv.visitInsn(LRETURN);
        } else {
            emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "invokeTailObjSnap",
                    "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;IIII)Lorg/luava/runtime/LuaValue;",
                    resumePc);
            mv.visitInsn(ARETURN);
        }
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
        emitDeopt(mv, owner, resumePc);
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
        emitDeopt(mv, owner, pc);
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
            int resumePc, int[] fusedBList, int fusedBCount, boolean forInner) {
        // Capacity guard for the callee window.
        mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a + 1 + proto.maxStackSize);
            mv.visitInsn(IADD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitInsn(ARRAYLENGTH);
        Label capOk = new Label();
        mv.visitJumpInsn(IF_ICMPLT, capOk);
        emitDeopt(mv, owner, resumePc);
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
        if (forInner) {
            // Unboxed int store as one INVOKESTATIC to the generated leaf helper.
            mv.visitVarInsn(LSTORE, 6);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitVarInsn(LLOAD, 6);
            mv.visitMethodInsn(INVOKESTATIC, owner, STORE_RAW_NAME, STORE_RAW_DESC, false);
            return;
        }
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
    private static void emitBoxValue(MethodVisitor mv, String owner, LuaProto proto, int reg, int c, int k, int pc) {
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

    private static void emitSetTabUp(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int k, int pc) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        emitLoadConst(mv, b);
        emitBoxValue(mv, owner, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "set",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitSetTable(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int k, int pc) {
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
        emitDeopt(mv, owner, pc);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitGuardObject(mv, owner, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        // Receiver stays on the stack (locals 9+ are fused-callee slots in pure
        // protos and must not be scratch), so load the index and box the value
        // directly.
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, b);
        mv.visitInsn(LALOAD);
        emitBoxValue(mv, owner, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                "(JLorg/luava/runtime/LuaValue;)V", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(objKey);
        emitGuardObject(mv, owner, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        emitBoxValue(mv, owner, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawset",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
        mv.visitLabel(done);
    }

    private static void emitSetI(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int k, int pc) {
        emitGuardObject(mv, owner, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        // Receiver stays on the stack; the key and value follow, so no scratch
        // local is needed. Local 9 must stay free: a fused-callee proto uses it
        // as the int flag "upvalue b holds self", and reusing it here for a
        // LuaValue made the verifier see Top where an int was required.
        mv.visitLdcInsn((long) b);
        emitBoxValue(mv, owner, proto, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                "(JLorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitSetField(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int k, int pc) {
        emitGuardObject(mv, owner, a, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        emitLoadConst(mv, b);
        emitBoxValue(mv, owner, proto, c, c, k, pc);
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
    private static void emitGuardPlainTable(MethodVisitor mv, String owner, int pc) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(isTable);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "getMetatable",
                "()Lorg/luava/runtime/LuaTable;", false);
        Label noMeta = new Label();
        mv.visitJumpInsn(IFNULL, noMeta);
        emitDeopt(mv, owner, pc);
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
    private static void emitSelf(MethodVisitor mv, String owner, LuaProto proto, int a, int b, int c, int k, int pc) {
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
            emitGuardObject(mv, owner, c, pc);
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
        emitDeopt(mv, owner, pc);
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

    private static void emitGetTabUp(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        // lt.get(k[c])
        emitLoadConst(mv, c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "get",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueReg(mv, a);
    }

    private static void emitGetTable(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
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
        emitDeopt(mv, owner, pc);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitGuardObject(mv, owner, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
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
        emitGuardObject(mv, owner, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, owner, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, c);
        mv.visitInsn(AALOAD);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawget",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueRegDeoptOnNil(mv, owner, a, pc);
        mv.visitLabel(done);
    }

    private static void emitGetI(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
        emitGuardObject(mv, owner, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, owner, pc);
        mv.visitLdcInsn((long) c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetInt",
                "(J)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueReg(mv, a);
    }

    private static void emitGetField(MethodVisitor mv, String owner, int a, int b, int c, int pc) {
        // Full `t[k]` semantics: rawget first, then metatable __index. A raw
        // hit is metatable-independent, so guard on "rawget non-nil" (deopt
        // otherwise) instead of "table has no metatable" — the latter deopted
        // every OOP instance (`self.x` on an object whose class table is the
        // metatable), which made methods like Vec:dot never JIT-run.
        emitGuardObject(mv, owner, b, pc);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, owner, pc);
        emitLoadConst(mv, c);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawget",
                "(Lorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;", false);
        emitStoreValueRegDeoptOnNil(mv, owner, a, pc);
    }

    /**
     * Consumes a reference on the stack and leaves it typed as
     * {@code LuaTable}, deopting when it is not a table (or is null).
     */
    private static void emitGuardTableTyped(MethodVisitor mv, String owner, int pc) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, owner, pc);
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
    private static void emitStoreValueRegDeoptOnNil(MethodVisitor mv, String owner, int a, int pc) {
        mv.visitInsn(DUP);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaValue", "isNil", "()Z", false);
        Label notNil = new Label();
        mv.visitJumpInsn(IFEQ, notNil);
        emitDeopt(mv, owner, pc);
        mv.visitLabel(notNil);
        emitStoreValueReg(mv, a);
    }

    private static void emitGuardObject(MethodVisitor mv, String owner, int reg, int pc) {
        // Outlined: a single INVOKESTATIC instead of an inline tag check.
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESTATIC, owner, GUARD_OBJ_NAME, GUARD_OBJ_DESC, false);
    }

    private static void emitGuardInt(MethodVisitor mv, String owner, int reg, int pc) {
        // Outlined: a single INVOKESTATIC instead of an inline tag check.
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESTATIC, owner, GUARD_INT_NAME, GUARD_INT_DESC, false);
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

    private static void emitDeopt(MethodVisitor mv, String owner, int pc) {
        emitDeopt(mv, owner, pc, false);
    }

    /**
     * Emits a deopt. {@code structural} marks an unconditional deopt the
     * compiled body always takes for this proto (impure-proto CALL/TAILCALL),
     * which the deopt budget ignores so it never disarms the hot prefix.
     */
    private static void emitDeopt(MethodVisitor mv, String owner, int pc, boolean structural) {
        // Outlined into the generated `deopt`/`deoptS` helpers, which build
        // and return the signal; the site throws it, exactly like the old
        // inline NEW/DUP/INVOKESPECIAL/ATHROW, so every control-flow join
        // keeps the stack depth the verifier proved before.
        ldcInt(mv, pc);
        if (structural) {
            mv.visitMethodInsn(INVOKESTATIC, owner, DEOPT_S_NAME, DEOPT_S_DESC, false);
        } else {
            mv.visitMethodInsn(INVOKESTATIC, owner, DEOPT_NAME, DEOPT_DESC, false);
        }
        mv.visitInsn(ATHROW);
    }

    /**
     * True when register {@code reg} provably holds a table created by a
     * {@code NEWTABLE} in this straight-line region and has not escaped, so a
     * write through it is a purely local effect: restart-from-entry discards
     * the table and the write with it. This is what makes the OOP constructor
     * shape ({@code setmetatable({...}, C)}) a restart-safe pure leaf. Any
     * other read of the register (a MOVE, a call argument, an upvalue/global
     * store, a return) counts as an escape and rejects the region. No branch
     * may enter or leave the span, so the definition dominates the write on
     * every path.
     */
    private static boolean writesFreshTable(LuaProto proto, int[] code, int pc, int reg) {
        if (reg < 0 || reg >= Math.max(proto.maxStackSize, proto.numParams)) {
            return false;
        }
        int def = lastWrite(code, pc, reg);
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_NEWTABLE) {
            return false;
        }
        for (int i = def; i < pc; i++) {
            if (Instruction.getOp(code[i]) == OpCode.OP_JMP || isBranch(code[i])) {
                return false;
            }
        }
        for (int t = 0; t < code.length; t++) {
            int jt = jumpTarget(code[t], t);
            if (jt > def && jt < pc) {
                return false;
            }
        }
        for (int i = def + 1; i < pc; i++) {
            if (writesReg(code[i], reg)) {
                return false;
            }
            if (readsReg(code[i], reg) && !isLocalTableWrite(code[i], reg)) {
                return false;
            }
        }
        return true;
    }

    /** A table write whose target register is {@code reg} (a local mutation). */
    private static boolean isLocalTableWrite(int inst, int reg) {
        int op = Instruction.getOp(inst);
        return (op == OpCode.OP_SETFIELD || op == OpCode.OP_SETI
                || op == OpCode.OP_SETTABLE || op == OpCode.OP_SETLIST)
                && Instruction.getA(inst) == reg;
    }

    /**
     * True when the proto writes any of its parameter registers {@code [0,
     * numParams)}. A compiled callee's parameter window aliases the caller's
     * argument registers, so only such a callee can clobber a caller's
     * operands before a nested deopt. The prologue-free self-recursion and
     * direct self-call fast paths have no window snapshot, so they are used
     * only when this is false; a parameter-writing self-recursive proto routes
     * its calls through the snapshot-wrapped general helper instead.
     */
    private static boolean writesParams(LuaProto proto, int[] code) {
        int n = proto.numParams;
        if (n <= 0) {
            return false;
        }
        for (int pc = 0; pc < code.length; pc++) {
            int inst = code[pc];
            int op = Instruction.getOp(inst);
            // Skip instructions whose A operand is a read, not a write.
            // writesReg's default (A is the destination) is wrong for the
            // comparison/conditional family, which read A.
            switch (op) {
                case OpCode.OP_EXTRAARG, OpCode.OP_JMP, OpCode.OP_RETURN0,
                        OpCode.OP_EQ, OpCode.OP_EQK, OpCode.OP_EQI,
                        OpCode.OP_LT, OpCode.OP_LE,
                        OpCode.OP_LTI, OpCode.OP_LEI, OpCode.OP_GTI, OpCode.OP_GEI,
                        OpCode.OP_TEST, OpCode.OP_LFALSESKIP -> {
                    continue;
                }
                default -> {}
            }
            for (int r = 0; r < n; r++) {
                if (writesReg(inst, r)) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * Emits an {@code int} constant in the most compact form. ASM's
     * {@code visitLdcInsn(Integer)} always emits a 3-byte {@code ldc}; the
     * register indexes, small counts and deopt pcs in generated kernels are
     * almost always in the {@code iconst}/{@code bipush}/{@code sipush} range,
     * so using the compact forms shrinks every kernel and helps C2 inline it
     * (plan.md §5.C).
     */
    private static void ldcInt(MethodVisitor mv, int v) {
        switch (v) {
            case -1 -> mv.visitInsn(ICONST_M1);
            case 0 -> mv.visitInsn(ICONST_0);
            case 1 -> mv.visitInsn(ICONST_1);
            case 2 -> mv.visitInsn(ICONST_2);
            case 3 -> mv.visitInsn(ICONST_3);
            case 4 -> mv.visitInsn(ICONST_4);
            case 5 -> mv.visitInsn(ICONST_5);
            default -> {
                if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
                    mv.visitIntInsn(BIPUSH, v);
                } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
                    mv.visitIntInsn(SIPUSH, v);
                } else {
                    mv.visitLdcInsn(v);
                }
            }
        }
    }
}

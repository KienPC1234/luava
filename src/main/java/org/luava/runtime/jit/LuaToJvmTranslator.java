/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaString;
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
    private static final String EXEC_INTO_NAME = "execInto";
    private static final String EXEC_INTO_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;II)V";
    private static final String EXEC_OSR_NAME = "execOsr";
    private static final String EXEC_OBJ_OSR_NAME = "execObjOsr";
    private static final String EXEC_OSR_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;II)J";
    private static final String EXEC_OBJ_OSR_DESC =
            "(Lorg/luava/runtime/bytecode/LuaClosure;[Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;II)Lorg/luava/runtime/LuaValue;";
    private static final int SAFEPOINT_BATCH = 1024;

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
        return translate(proto, internalName, false, false);
    }

    /**
     * @param wantOsr when true, also emits an {@code execOsr} entry that
     *     jumps straight to loop back-edge targets; false leaves {@code exec}
     *     as the standalone body.
     */
    public static Translation translate(LuaProto proto, String internalName, boolean wantOsr) {
        return translate(proto, internalName, wantOsr, false);
    }

    /**
     * @param wantOsr when true, also emits {@code execOsr} which
     *     branches into the loop body at the instruction labels, enabling
     *     on-stack replacement for hot loops.
     * @param emitSafepoints when true, inserts batched guard safepoints on loop
     *     back-edges and call entries (zero overhead for unguarded variants).
     */
    public static Translation translate(LuaProto proto, String internalName, boolean wantOsr,
            boolean emitSafepoints) {
        AnalysisResult analysis = analyzeFull(proto);
        if (analysis == null) {
            return null;
        }
        return translate(proto, analysis, internalName, wantOsr, emitSafepoints, false);
    }

    static Translation translate(LuaProto proto, AnalysisResult analysis, String internalName,
            boolean wantOsr, boolean emitSafepoints) {
        return translate(proto, analysis, internalName, wantOsr, emitSafepoints, false);
    }

    static Translation translate(LuaProto proto, AnalysisResult analysis, String internalName,
            boolean wantOsr, boolean emitSafepoints, boolean useClassData) {
        if (analysis == null) {
            return null;
        }
        Info info = analysis.info();
        boolean[] jumpTargets = analysis.jumpTargets();
        boolean pure = info.pure();
        int len = proto.code.length;
        // Loop instructions at which OSR may enter: a jump back-edge or a
        // numeric/generic loop step. Any such pc is only reachable after FORPREP
        // or a prior body execution, so register state is initialized.
        boolean[] osrTargets = wantOsr ? loopHeadFlags(proto, proto.code) : null;
        boolean anyOsr = false;
        if (osrTargets != null) {
            for (boolean t : osrTargets) {
                if (t) {
                    anyOsr = true;
                    break;
                }
            }
        }
        boolean returnsInt = info.returnsInt();
        boolean hasExecInto = !returnsInt && !anyOsr;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, internalName, null, "java/lang/Object",
                new String[] {"org/luava/runtime/jit/JitEntry"});
        emitEntryBridges(cw, internalName, returnsInt, hasExecInto);
        boolean foldConsts = useClassData && proto.constants != null && proto.constants.length > 0
                && proto.constants.length <= 64;
        if (foldConsts) {
            for (int ci = 0; ci < proto.constants.length; ci++) {
                LuaValue kv = proto.constants[ci];
                if (kv instanceof LuaString) {
                    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "KS_" + ci,
                            "Lorg/luava/runtime/LuaString;", null, null).visitEnd();
                } else {
                    cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "KV_" + ci,
                            "Lorg/luava/runtime/LuaValue;", null, null).visitEnd();
                }
            }
        }
        int selfIcCount = 0;
        for (int pc = 0; pc < len; pc++) {
            int inst = proto.code[pc];
            if (Instruction.getOp(inst) == OpCode.OP_SELF && Instruction.getk(inst) == 1) {
                cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL, "SC_" + pc,
                        "Lorg/luava/runtime/jit/JitRuntime$SelfCache;", null, null).visitEnd();
                selfIcCount++;
            }
        }
        if (selfIcCount > 0 || foldConsts) {
            MethodVisitor cl = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
            cl.visitCode();
            if (foldConsts) {
                cl.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                        "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
                cl.visitLdcInsn("_");
                cl.visitLdcInsn(org.objectweb.asm.Type.getType("[Lorg/luava/runtime/LuaValue;"));
                cl.visitMethodInsn(INVOKESTATIC, "java/lang/invoke/MethodHandles", "classData",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;",
                        false);
                cl.visitTypeInsn(CHECKCAST, "[Lorg/luava/runtime/LuaValue;");
                cl.visitVarInsn(ASTORE, 0);
                for (int ci = 0; ci < proto.constants.length; ci++) {
                    cl.visitVarInsn(ALOAD, 0);
                    ldcInt(cl, ci);
                    cl.visitInsn(AALOAD);
                    if (proto.constants[ci] instanceof LuaString) {
                        cl.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaString");
                        cl.visitFieldInsn(PUTSTATIC, internalName, "KS_" + ci,
                                "Lorg/luava/runtime/LuaString;");
                    } else {
                        cl.visitFieldInsn(PUTSTATIC, internalName, "KV_" + ci,
                                "Lorg/luava/runtime/LuaValue;");
                    }
                }
            }
            for (int pc = 0; pc < len; pc++) {
                int inst = proto.code[pc];
                if (Instruction.getOp(inst) == OpCode.OP_SELF && Instruction.getk(inst) == 1) {
                    cl.visitTypeInsn(NEW, "org/luava/runtime/jit/JitRuntime$SelfCache");
                    cl.visitInsn(DUP);
                    cl.visitMethodInsn(INVOKESPECIAL, "org/luava/runtime/jit/JitRuntime$SelfCache",
                            "<init>", "()V", false);
                    cl.visitFieldInsn(PUTSTATIC, internalName, "SC_" + pc,
                            "Lorg/luava/runtime/jit/JitRuntime$SelfCache;");
                }
            }
            cl.visitInsn(RETURN);
            cl.visitMaxs(0, 0);
            cl.visitEnd();
        }

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
        boolean selfSafe = !analysis.writesParams();
        boolean[] skipStore = new boolean[len];
        int[] fusedDef = new int[len];
        for (int i = 0; i < len; i++) {
            fusedDef[i] = -1;
        }
        if (pure && selfSafe) {
            for (int pc = 0; pc < len; pc++) {
                int inst = proto.code[pc];
                int op = Instruction.getOp(inst);
                if (op == OpCode.OP_CALL || op == OpCode.OP_TAILCALL) {
                    int funcReg = Instruction.getA(inst);
                    int def = lastWrite(proto.code, pc, funcReg);
                    if (def >= 0 && isFusedUpvalueDef(proto.code, def, pc, funcReg, jumpTargets)) {
                        fusedUp[pc] = Instruction.getB(proto.code[def]);
                        fusedDef[pc] = def;
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
        int tickSlot = emitSafepoints ? Math.max(22, 11 + 2 * fusedBCount) : -1;
        int entryCost = (emitSafepoints && hasCallForSafepoint(proto)) ? Math.max(1, len) : 0;
        int loopSlotBase = Math.max(24, Math.max(tickSlot >= 0 ? tickSlot + 2 : 24, 11 + 2 * fusedBCount));
        LoopPlan loopPlan = computeLoopPlan(proto, info.types(), jumpTargets, loopSlotBase);
        CodegenEnv cgEnv = new CodegenEnv(internalName, foldConsts, jumpTargets, loopPlan);
        if (anyOsr) {
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    returnsInt ? EXEC_NAME : EXEC_OBJ_NAME,
                    returnsInt ? EXEC_DESC : EXEC_OBJ_DESC, null, null);
            mv.visitCode();
            emitOsrForward(mv, internalName, returnsInt);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            emitOsrBody(cw, internalName, proto, osrTargets, info, fusedUp, fusedDef, skipStore,
                    fusedBList, fusedBCount, pure, selfSafe, tickSlot, entryCost, cgEnv);
        } else {
            osrTargets = null;
            MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                    returnsInt ? EXEC_NAME : EXEC_OBJ_NAME,
                    returnsInt ? EXEC_DESC : EXEC_OBJ_DESC, null, null);
            mv.visitCode();
            emitPrologue(mv, fusedBList, fusedBCount, tickSlot, entryCost, loopPlan);
            emitBody(mv, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                    fusedBCount, returnsInt, info.returnsVoid(), pure, info.types(), selfSafe, tickSlot, -1,
                    cgEnv);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
            if (hasExecInto) {
                int retDstLocal = Math.max(tickSlot >= 0 ? tickSlot + 1 : 22, 11 + 2 * fusedBCount);
                MethodVisitor me = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_INTO_NAME,
                        EXEC_INTO_DESC, null, null);
                me.visitCode();
                me.visitVarInsn(ILOAD, 6);
                me.visitVarInsn(ISTORE, retDstLocal);
                me.visitInsn(LCONST_0);
                me.visitVarInsn(LSTORE, 6);
                emitPrologue(me, fusedBList, fusedBCount, tickSlot, entryCost, loopPlan);
                emitBody(me, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                        fusedBCount, false, info.returnsVoid(), pure, info.types(), selfSafe, tickSlot,
                        retDstLocal, cgEnv);
                me.visitMaxs(0, 0);
                me.visitEnd();
            }
        }
        if (returnsInt && fusedBCount > 0) {
            MethodVisitor mi =
                    cw.visitMethod(ACC_PUBLIC | ACC_STATIC, EXEC_INNER_NAME, innerDesc(fusedBCount), null, null);
            mi.visitCode();
            for (int i = 0; i < fusedBCount; i++) {
                mi.visitVarInsn(ALOAD, 6 + i);
                mi.visitVarInsn(ASTORE, 10 + 2 * i);
                mi.visitInsn(ICONST_1);
                mi.visitVarInsn(ISTORE, 9 + 2 * i);
            }
            if (tickSlot >= 0) {
                ldcInt(mi, SAFEPOINT_BATCH);
                mi.visitVarInsn(ISTORE, tickSlot);
                if (entryCost > 0) {
                    emitEntrySafepoint(mi, entryCost);
                }
            }
            emitLoopSlotsZeroInit(mi, loopPlan);
            emitBody(mi, internalName, proto, newLabels(len), fusedUp, fusedDef, skipStore, fusedBList,
                    fusedBCount, true, info.returnsVoid(), pure, info.types(), selfSafe, tickSlot, -1, cgEnv);
            mi.visitMaxs(0, 0);
            mi.visitEnd();
        }
        cw.visitEnd();
        return new Translation(internalName, cw.toByteArray(), osrTargets);
    }

    private static void emitEntryBridges(ClassWriter cw, String internalName, boolean returnsInt,
            boolean hasExecInto) {
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mInt = cw.visitMethod(ACC_PUBLIC, "callInt", EXEC_DESC, null, null);
        mInt.visitCode();
        if (returnsInt) {
            for (int i = 1; i <= 5; i++) {
                mInt.visitVarInsn(ALOAD, i);
            }
            mInt.visitVarInsn(ILOAD, 6);
            mInt.visitMethodInsn(INVOKESTATIC, internalName, EXEC_NAME, EXEC_DESC, false);
            mInt.visitInsn(LRETURN);
        } else {
            mInt.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
            mInt.visitInsn(DUP);
            mInt.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false);
            mInt.visitInsn(ATHROW);
        }
        mInt.visitMaxs(0, 0);
        mInt.visitEnd();

        MethodVisitor mObj = cw.visitMethod(ACC_PUBLIC, "callObj", EXEC_OBJ_DESC, null, null);
        mObj.visitCode();
        if (!returnsInt) {
            for (int i = 1; i <= 5; i++) {
                mObj.visitVarInsn(ALOAD, i);
            }
            mObj.visitVarInsn(ILOAD, 6);
            mObj.visitMethodInsn(INVOKESTATIC, internalName, EXEC_OBJ_NAME, EXEC_OBJ_DESC, false);
            mObj.visitInsn(ARETURN);
        } else {
            mObj.visitTypeInsn(NEW, "java/lang/UnsupportedOperationException");
            mObj.visitInsn(DUP);
            mObj.visitMethodInsn(INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "()V", false);
            mObj.visitInsn(ATHROW);
        }
        mObj.visitMaxs(0, 0);
        mObj.visitEnd();

        if (hasExecInto) {
            MethodVisitor mInto = cw.visitMethod(ACC_PUBLIC, "callInto", EXEC_INTO_DESC, null, null);
            mInto.visitCode();
            for (int i = 1; i <= 5; i++) {
                mInto.visitVarInsn(ALOAD, i);
            }
            mInto.visitVarInsn(ILOAD, 6);
            mInto.visitVarInsn(ILOAD, 7);
            mInto.visitMethodInsn(INVOKESTATIC, internalName, EXEC_INTO_NAME, EXEC_INTO_DESC, false);
            mInto.visitInsn(RETURN);
            mInto.visitMaxs(0, 0);
            mInto.visitEnd();
        }
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
    private static boolean[] loopHeadFlags(LuaProto proto, int[] code) {
        boolean[] flags = new boolean[code.length];
        boolean[] jmpTarget = new boolean[code.length];
        boolean[] loopHeader = new boolean[code.length];
        boolean[] deoptHeader = new boolean[code.length];
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
                    if (loopUnconditionallyDeopts(proto, code, h, pc)) {
                        deoptHeader[h] = true;
                    }
                }
            } else if (op == OpCode.OP_JMP && Instruction.getsJ(code[pc]) < 0) {
                int t = pc + 1 + Instruction.getsJ(code[pc]);
                if (t >= 0 && t < code.length) {
                    jmpTarget[t] = true;
                    if (loopUnconditionallyDeopts(proto, code, t, pc)) {
                        deoptHeader[t] = true;
                    }
                }
            }
        }
        for (int pc = 0; pc < code.length; pc++) {
            if (deoptHeader[pc]) {
                continue;
            }
            if (loopHeader[pc]) {
                flags[pc] = !jmpTarget[pc] && forDepth[pc] <= 1;
            } else if (jmpTarget[pc]) {
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
            int[] fusedBList, int fusedBCount, boolean pure, boolean selfSafe,
            int tickSlot, int entryCost, CodegenEnv cgEnv) {
        int len = proto.code.length;
        Label[] labels = newLabels(len);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC,
                info.returnsInt() ? EXEC_OSR_NAME : EXEC_OBJ_OSR_NAME,
                info.returnsInt() ? EXEC_OSR_DESC : EXEC_OBJ_OSR_DESC, null, null);
        mv.visitCode();
        emitPrologue(mv, fusedBList, fusedBCount, tickSlot, entryCost, cgEnv.loopPlan);
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
        Label defaultTramp = new Label();
        mv.visitVarInsn(ILOAD, 6);
        mv.visitLookupSwitchInsn(defaultTramp, keys, tramp);
        mv.visitLabel(defaultTramp);
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, 6);
        mv.visitJumpInsn(GOTO, labels[0]);
        for (int i = 0; i < targets.size(); i++) {
            int targetPc = targets.get(i);
            mv.visitLabel(tramp[i]);
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, 6);
            if (cgEnv.loopPlan != null && targetPc < cgEnv.loopPlan.byBodyStart.length) {
                LoopOpt lp = cgEnv.loopPlan.byBodyStart[targetPc];
                if (lp != null) {
                    emitLoopOsrInit(mv, lp);
                }
            }
            mv.visitJumpInsn(GOTO, labels[targetPc]);
        }
        emitBody(mv, internalName, proto, labels, fusedUp, fusedDef, skipStore, fusedBList,
                fusedBCount, info.returnsInt(), info.returnsVoid(), pure, info.types(), selfSafe, tickSlot, -1,
                cgEnv);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitPrologue(MethodVisitor mv, int[] fusedBList, int fusedBCount,
            int tickSlot, int entryCost, LoopPlan loopPlan) {
        if (tickSlot >= 0) {
            ldcInt(mv, SAFEPOINT_BATCH);
            mv.visitVarInsn(ISTORE, tickSlot);
            if (entryCost > 0) {
                emitEntrySafepoint(mv, entryCost);
            }
        }
        emitLoopSlotsZeroInit(mv, loopPlan);
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
    private static boolean hasCallForSafepoint(LuaProto proto) {
        int[] code = proto.code;
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            if (op == OpCode.OP_CALL || op == OpCode.OP_TAILCALL) {
                if (!isSqrtIntrinsic(proto, code, pc)
                        && !isFloorIntrinsic(proto, code, pc)
                        && !isFloorSqrtIntrinsic(proto, code, pc)
                        && !isTostringIntrinsic(proto, code, pc)
                        && !isSetmetatableIntrinsic(proto, code, pc)
                        && !isTForSetup(proto, code, pc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void emitEntrySafepoint(MethodVisitor mv, int entryCost) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/bytecode/LuaClosure", "getState",
                "()Lorg/luava/runtime/LuaState;", false);
        mv.visitInsn(DUP);
        Label nullState = new Label();
        Label done = new Label();
        mv.visitJumpInsn(IFNULL, nullState);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/LuaState", "loopGuard",
                "Lorg/luava/runtime/LuaState$LoopGuard;");
        mv.visitJumpInsn(IFNULL, done);
        mv.visitVarInsn(ALOAD, 0);
        ldcInt(mv, entryCost);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "safepoint",
                "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(nullState);
        mv.visitInsn(POP);
        mv.visitLabel(done);
    }

    private static void emitSafepointBackEdge(MethodVisitor mv, int tickSlot, int batchCost) {
        if (tickSlot < 0) {
            return;
        }
        mv.visitIincInsn(tickSlot, -1);
        mv.visitVarInsn(ILOAD, tickSlot);
        Label cont = new Label();
        mv.visitJumpInsn(IFGT, cont);
        ldcInt(mv, SAFEPOINT_BATCH);
        mv.visitVarInsn(ISTORE, tickSlot);
        mv.visitVarInsn(ALOAD, 0);
        ldcInt(mv, batchCost);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "safepoint",
                "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
        mv.visitLabel(cont);
    }

    private static void emitBody(MethodVisitor mv, String owner, LuaProto proto, Label[] labels,
            int[] fusedUp, int[] fusedDef, boolean[] skipStore, int[] fusedBList, int fusedBCount,
            boolean returnsInt, boolean returnsVoid, boolean pure, int[][] types, boolean selfSafe,
            int tickSlot, int retDstLocal, CodegenEnv cgEnv) {
        int len = proto.code.length;
        for (int pc = 0; pc < len; pc++) {
            mv.visitLabel(labels[pc]);
            LoopOpt activeLoop = (cgEnv.loopPlan != null && pc < cgEnv.loopPlan.activeAtPc.length)
                    ? cgEnv.loopPlan.activeAtPc[pc] : null;
            if (activeLoop != null && pc < activeLoop.loopPc - 1 && !cgEnv.jumpTargets[pc + 1]) {
                if (tryEmitFusedIntArithSetTable(mv, proto, pc, types, activeLoop)) {
                    pc++;
                    mv.visitLabel(labels[pc]);
                    continue;
                }
                if (tryEmitFusedGetTableAccArith(mv, proto, pc, types, activeLoop)) {
                    pc++;
                    mv.visitLabel(labels[pc]);
                    continue;
                }
            }
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
                    int bx = Instruction.getBx(inst);
                    LuaValue kv = proto.constants[bx];
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
                        emitLoadConst(mv, proto, cgEnv, bx);
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
                case OpCode.OP_GETTABUP -> emitGetTabUp(mv, proto, cgEnv, a, b, c, pc, activeLoop);
                case OpCode.OP_GETTABLE -> emitGetTable(mv, a, b, c, pc, types, activeLoop);
                case OpCode.OP_GETI -> emitGetI(mv, a, b, c, pc, activeLoop);
                case OpCode.OP_GETFIELD -> emitGetField(mv, proto, cgEnv, a, b, c, pc, activeLoop);
                case OpCode.OP_SETTABUP -> emitSetTabUp(mv, proto, cgEnv, a, b, c, Instruction.getk(inst), pc, activeLoop);
                case OpCode.OP_SETTABLE -> emitSetTable(mv, proto, cgEnv, a, b, c, Instruction.getk(inst), pc, types, activeLoop);
                case OpCode.OP_SETI -> emitSetI(mv, proto, cgEnv, a, b, c, Instruction.getk(inst), pc, types, activeLoop);
                case OpCode.OP_SETFIELD -> emitSetField(mv, proto, cgEnv, a, b, c, Instruction.getk(inst), pc, activeLoop);
                case OpCode.OP_SELF -> emitSelf(mv, owner, proto, cgEnv, a, b, c, Instruction.getk(inst), pc);
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
                    int arrCap = Instruction.getB(inst);
                    int hashCap = Instruction.getC(inst);
                    mv.visitVarInsn(ALOAD, 0);
                    if (arrCap == 0 && hashCap <= 4) {
                        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaTable", "allocSmall",
                                "(Lorg/luava/runtime/bytecode/LuaClosure;)Lorg/luava/runtime/LuaTable;", false);
                    } else {
                        ldcInt(mv, arrCap);
                        ldcInt(mv, hashCap);
                        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaTable", "allocClosure",
                                "(Lorg/luava/runtime/bytecode/LuaClosure;II)Lorg/luava/runtime/LuaTable;", false);
                    }
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
                case OpCode.OP_ADD -> emitArithNum(mv, a, b, c, pc, LADD, DADD, types, activeLoop);
                case OpCode.OP_SUB -> emitArithNum(mv, a, b, c, pc, LSUB, DSUB, types, activeLoop);
                case OpCode.OP_MUL -> emitArithNum(mv, a, b, c, pc, LMUL, DMUL, types, activeLoop);
                case OpCode.OP_DIV -> emitDivNum(mv, a, b, c, pc, activeLoop);
                case OpCode.OP_POW -> emitPowNum(mv, a, b, c, pc, activeLoop);
                case OpCode.OP_IDIV -> emitIdivNum(mv, a, b, c, pc, activeLoop);
                case OpCode.OP_MOD -> emitModNum(mv, a, b, c, pc, activeLoop);
                case OpCode.OP_BAND -> emitBitwiseNum(mv, a, b, c, pc, LAND, types, activeLoop);
                case OpCode.OP_BOR -> emitBitwiseNum(mv, a, b, c, pc, LOR, types, activeLoop);
                case OpCode.OP_BXOR -> emitBitwiseNum(mv, a, b, c, pc, LXOR, types, activeLoop);
                case OpCode.OP_SHL -> emitShiftNum(mv, a, b, c, pc, true, types, activeLoop);
                case OpCode.OP_SHR -> emitShiftNum(mv, a, b, c, pc, false, types, activeLoop);
                case OpCode.OP_SHLI -> emitShiftImm(mv, a, b, Instruction.getsC(inst), pc, true, types, activeLoop);
                case OpCode.OP_SHRI -> emitShiftImm(mv, a, b, Instruction.getsC(inst), pc, false, types, activeLoop);
                case OpCode.OP_ADDK -> emitArithKNum(mv, proto, a, b, c, pc, LADD, DADD, types, activeLoop);
                case OpCode.OP_MULK -> emitArithKNum(mv, proto, a, b, c, pc, LMUL, DMUL, types, activeLoop);
                case OpCode.OP_DIVK -> emitDivK(mv, proto, a, b, c, pc, activeLoop);
                case OpCode.OP_POWK -> emitPowK(mv, proto, a, b, c, pc, activeLoop);
                case OpCode.OP_IDIVK -> emitIdivK(mv, proto, a, b, c, pc, types, activeLoop);
                case OpCode.OP_MODK -> emitModK(mv, proto, a, b, c, pc, types, activeLoop);
                case OpCode.OP_BANDK -> emitArithKNum(mv, proto, a, b, c, pc, LAND, -1, types, activeLoop);
                case OpCode.OP_BORK -> emitArithKNum(mv, proto, a, b, c, pc, LOR, -1, types, activeLoop);
                case OpCode.OP_BXORK -> emitArithKNum(mv, proto, a, b, c, pc, LXOR, -1, types, activeLoop);
                case OpCode.OP_UNM -> {
                    if (!isStaticInt(types, pc, b)) {
                        emitGuardInt(mv, b, pc, activeLoop);
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    emitLoadP(mv, b, activeLoop);
                    mv.visitInsn(LNEG);
                    mv.visitInsn(LASTORE);
                    emitTagIntNull(mv, a);
                }
                case OpCode.OP_BNOT -> {
                    if (!isStaticInt(types, pc, b)) {
                        emitGuardInt(mv, b, pc, activeLoop);
                    }
                    mv.visitVarInsn(ALOAD, 2);
                    emitIndex(mv, a);
                    emitLoadP(mv, b, activeLoop);
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
                    if (activeLoop != null && a == activeLoop.accReg) {
                        emitAccAddI(mv, b, sc, pc, types, activeLoop);
                    } else {
                        if (!isStaticInt(types, pc, b)) {
                            emitGuardInt(mv, b, pc, activeLoop);
                        }
                        mv.visitVarInsn(ALOAD, 2);
                        emitIndex(mv, a);
                        emitLoadP(mv, b, activeLoop);
                        mv.visitLdcInsn((long) sc);
                        mv.visitInsn(LADD);
                        mv.visitInsn(LASTORE);
                        emitTagIntNull(mv, a);
                    }
                }
                case OpCode.OP_SUBK -> emitArithKNum(mv, proto, a, b, c, pc, LSUB, DSUB, types, activeLoop);
                case OpCode.OP_LEI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLE, IFGT, types, activeLoop);
                case OpCode.OP_LTI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFLT, IFGE, types, activeLoop);
                case OpCode.OP_GTI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGT, IFLE, types, activeLoop);
                case OpCode.OP_GEI -> emitCmpImm(mv, a, Instruction.getsB(inst), Instruction.getk(inst), pc, labels, IFGE, IFLT, types, activeLoop);
                case OpCode.OP_EQI -> {
                    int sb = Instruction.getsB(inst);
                    int k = Instruction.getk(inst);
                    if (!isStaticInt(types, pc, a)) {
                        emitGuardInt(mv, a, pc, activeLoop);
                    }
                    emitLoadP(mv, a, activeLoop);
                    mv.visitLdcInsn((long) sb);
                    mv.visitInsn(LCMP);
                    Label isEq = new Label();
                    mv.visitJumpInsn(IFEQ, isEq);
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
                case OpCode.OP_LT -> emitCmpRR(mv, a, b, Instruction.getk(inst), pc, labels, false, activeLoop);
                case OpCode.OP_LE -> emitCmpRR(mv, a, b, Instruction.getk(inst), pc, labels, true, activeLoop);
                case OpCode.OP_EQ -> emitEqRR(mv, proto, a, b, Instruction.getk(inst), pc, labels, activeLoop);
                case OpCode.OP_EQK -> emitEqK(mv, proto, a, b, Instruction.getk(inst), pc, labels, activeLoop);
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
                    mv.visitJumpInsn(GOTO, labels[pc + 2]);
                }
                case OpCode.OP_JMP -> {
                    int sj = Instruction.getsJ(inst);
                    if (sj < 0) {
                        emitSafepointBackEdge(mv, tickSlot, Math.max(1, -sj) * SAFEPOINT_BATCH);
                    }
                    mv.visitJumpInsn(GOTO, labels[pc + 1 + sj]);
                }
                case OpCode.OP_FORPREP -> {
                    LoopOpt prepLoop = (cgEnv.loopPlan != null && pc < cgEnv.loopPlan.byPrepPc.length)
                            ? cgEnv.loopPlan.byPrepPc[pc] : null;
                    emitForPrep(mv, a, Instruction.getBx(inst), pc, labels, prepLoop);
                }
                case OpCode.OP_FORLOOP -> {
                    LoopOpt endLoop = (cgEnv.loopPlan != null && pc < cgEnv.loopPlan.byLoopPc.length)
                            ? cgEnv.loopPlan.byLoopPc[pc] : null;
                    emitForLoop(mv, a, Instruction.getBx(inst), pc, labels, tickSlot, endLoop);
                }
                case OpCode.OP_TFORPREP -> emitTForPrep(mv, a, Instruction.getBx(inst), pc, labels);
                case OpCode.OP_TFORCALL -> emitTForCall(mv, a, c, pc);
                case OpCode.OP_TFORLOOP -> emitTForLoop(mv, a, Instruction.getBx(inst), pc, labels, tickSlot);
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
                    if (isSetmetatableIntrinsic(proto, proto.code, pc)) {
                        emitSetmetatableCall(mv, a, pc);
                        break;
                    }
                    if (isCoroutineResumeIntrinsic(proto, proto.code, pc)) {
                        emitCoroutineResumeCall(mv, a, b - 1, c - 1, pc);
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
                        emitDeopt(mv, pc, true);
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
                            fusedBCount, c - 1 != 0, returnsInt, selfSafe);
                }
                case OpCode.OP_TAILCALL -> {
                    if (isSqrtIntrinsic(proto, proto.code, pc)) {
                        emitSqrtTail(mv, a, pc, retDstLocal);
                        break;
                    }
                    if (isSetmetatableIntrinsic(proto, proto.code, pc)) {
                        if (retDstLocal >= 0) {
                            emitSetmetatableTailInto(mv, a, pc, retDstLocal);
                        } else {
                            emitSetmetatableCall(mv, a, pc);
                            mv.visitVarInsn(ALOAD, 4);
                            emitIndex(mv, a);
                            mv.visitInsn(AALOAD);
                            mv.visitInsn(ARETURN);
                        }
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
                            fusedBCount, labels, returnsInt, selfSafe, tickSlot, retDstLocal);
                }
                case OpCode.OP_RETURN1 -> emitReturnOne(
                        mv, a, pc, returnsInt, proto.protos.length > 0, retDstLocal, types);
                case OpCode.OP_RETURN -> {
                    // Single-value shape only; anything else deopts.
                    if (b != 2) {
                        emitDeopt(mv, pc);
                    } else {
                        emitReturnOne(mv, a, pc, returnsInt, proto.protos.length > 0, retDstLocal, types);
                    }
                }
                case OpCode.OP_RETURN0 -> {
                    if (returnsVoid) {
                        if (proto.protos.length > 0) {
                            mv.visitVarInsn(ALOAD, 0);
                            mv.visitVarInsn(ILOAD, 5);
                            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "closeAt",
                                    "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
                        }
                        if (retDstLocal >= 0) {
                            mv.visitVarInsn(ALOAD, 2);
                            mv.visitVarInsn(ILOAD, retDstLocal);
                            mv.visitInsn(LCONST_0);
                            mv.visitInsn(LASTORE);
                            mv.visitVarInsn(ALOAD, 3);
                            mv.visitVarInsn(ILOAD, retDstLocal);
                            ldcInt(mv, TYPE_NIL);
                            mv.visitInsn(BASTORE);
                            mv.visitVarInsn(ALOAD, 4);
                            mv.visitVarInsn(ILOAD, retDstLocal);
                            mv.visitInsn(ACONST_NULL);
                            mv.visitInsn(AASTORE);
                            mv.visitInsn(RETURN);
                        } else {
                            // Void proto: return the sentinel; callers materialize
                            // zero results (or nil-fill an expected count).
                            mv.visitInsn(LCONST_0);
                            mv.visitInsn(LRETURN);
                        }
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
    private static void emitReturnOne(MethodVisitor mv, int a, int pc, boolean returnsInt,
            boolean needsCloseOnReturn, int retDstLocal, int[][] types) {
        if (returnsInt) {
            emitGuardInt(mv, a, pc);
            if (needsCloseOnReturn) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ILOAD, 5);
                mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "closeAt",
                        "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
            }
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            mv.visitInsn(LALOAD);
            mv.visitInsn(LRETURN);
        } else if (retDstLocal >= 0) {
            if (needsCloseOnReturn) {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ILOAD, 5);
                mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "closeAt",
                        "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
            }
            int knownTy = (types != null && pc < types.length && a < types[pc].length) ? types[pc][a] : T_UNKNOWN;
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, retDstLocal);
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            mv.visitInsn(LALOAD);
            mv.visitInsn(LASTORE);

            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ILOAD, retDstLocal);
            if (knownTy == T_INT) {
                ldcInt(mv, TYPE_INT);
            } else {
                mv.visitVarInsn(ALOAD, 3);
                emitIndex(mv, a);
                mv.visitInsn(BALOAD);
            }
            mv.visitInsn(BASTORE);

            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, retDstLocal);
            if (knownTy == T_INT) {
                mv.visitInsn(ACONST_NULL);
            } else {
                mv.visitVarInsn(ALOAD, 4);
                emitIndex(mv, a);
                mv.visitInsn(AALOAD);
            }
            mv.visitInsn(AASTORE);
            mv.visitInsn(RETURN);
        } else {
            // Object mode: box the full triple; any type is exact.
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM",
                    "getLuaValue",
                    "([J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
            if (needsCloseOnReturn) {
                mv.visitVarInsn(ASTORE, 8);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ILOAD, 5);
                mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "closeAt",
                        "(Lorg/luava/runtime/bytecode/LuaClosure;I)V", false);
                mv.visitVarInsn(ALOAD, 8);
            }
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
    public record Info(boolean pure, boolean callSafe, boolean leafNoWindow,
            boolean returnsInt, boolean returnsVoid, int[][] types) {}

    /** Combined analysis bundle shared across eligibility and translation (J01/J02). */
    static final class AnalysisResult {
        private final Info info;
        private final boolean writesParams;
        private final boolean[] jumpTargets;

        AnalysisResult(Info info, boolean writesParams, boolean[] jumpTargets) {
            this.info = info;
            this.writesParams = writesParams;
            this.jumpTargets = jumpTargets;
        }

        Info info() {
            return info;
        }

        boolean writesParams() {
            return writesParams;
        }

        boolean[] jumpTargets() {
            return jumpTargets;
        }
    }

    public static Info analyze(LuaProto proto) {
        AnalysisResult res = analyzeFull(proto);
        return res != null ? res.info() : null;
    }

    static AnalysisResult analyzeFull(LuaProto proto) {
        if (proto.mayYield) {
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
        boolean[] jumpTargets = computeJumpTargets(code);
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
        int firstImpurePc = -1;
        int setupvalCount = 0;
        boolean hasClosureOrClose = false;
        boolean otherImpure = false;
        for (int pc = 0; pc < code.length; pc++) {
            int op = Instruction.getOp(code[pc]);
            switch (op) {
                case OpCode.OP_SETUPVAL -> {
                    impure = true;
                    if (firstImpurePc < 0) firstImpurePc = pc;
                    setupvalCount++;
                }
                case OpCode.OP_CLOSURE, OpCode.OP_CLOSE -> {
                    impure = true;
                    if (firstImpurePc < 0) firstImpurePc = pc;
                    hasClosureOrClose = true;
                }
                case OpCode.OP_SETTABUP -> {
                    impure = true;
                    otherImpure = true;
                }
                case OpCode.OP_SETFIELD, OpCode.OP_SETI, OpCode.OP_SETTABLE, OpCode.OP_SETLIST -> {
                    if (!writesFreshTable(proto, code, pc, Instruction.getA(code[pc]), jumpTargets)) {
                        impure = true;
                        otherImpure = true;
                    }
                }
                case OpCode.OP_CALL, OpCode.OP_TAILCALL -> {
                    if (isCoroutineResumeIntrinsic(proto, code, pc)) {
                        impure = true;
                        otherImpure = true;
                    } else if (isSetmetatableIntrinsic(proto, code, pc)
                            && !writesFreshTable(proto, code, pc, Instruction.getA(code[pc]) + 1, jumpTargets)) {
                        impure = true;
                        otherImpure = true;
                    }
                }
                default -> {}
            }
        }
        boolean hasCalls = false;
        int firstGeneralCallPc = -1;
        int firstLoopBackedgePc = -1;
        boolean hasViableLoop = false;
        boolean hasReturn1 = false;
        boolean hasVoidReturn = false;
        boolean hasTailCall = false;
        boolean hasIntrinsicTail = false;
        for (int pc = 0; pc < code.length; pc++) {
            int inst = code[pc];
            if ((Instruction.getOp(inst) == OpCode.OP_CALL
                    || Instruction.getOp(inst) == OpCode.OP_TAILCALL)
                    && "yield".equals(coroutineFieldAt(proto, code, pc, Instruction.getA(inst)))) {
                proto.mayYield = true;
                return null;
            }
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
                int h = (Instruction.getOp(inst) == OpCode.OP_JMP)
                        ? (pc + 1 + Instruction.getsJ(inst))
                        : (pc + 1 - Instruction.getBx(inst));
                if (h >= 0 && h < pc && !loopUnconditionallyDeopts(proto, code, h, pc)) {
                    hasViableLoop = true;
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
        if (hasCalls && firstLoopBackedgePc >= 0 && !hasViableLoop) {
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
        boolean restartSafeLeaf = false;
        if (impure && !otherImpure && !hasCalls && !hasTailCall && !hasIntrinsicTail
                && firstLoopBackedgePc < 0 && firstImpurePc >= 0
                && (setupvalCount == 0 || (setupvalCount == 1 && !hasClosureOrClose))) {
            boolean safe = true;
            for (int pc = firstImpurePc; pc < code.length; pc++) {
                if (!reach[pc]) {
                    continue;
                }
                if (pc == firstImpurePc && setupvalCount == 1) {
                    continue;
                }
                if (!isDeoptFreeAfterImpure(code[pc])) {
                    safe = false;
                    break;
                }
            }
            restartSafeLeaf = safe;
        }
        boolean callSafe = !impure || restartSafeLeaf;
        boolean writesParams = writesParams(proto, code);
        boolean leafNoWindow = callSafe && !writesParams
                && !hasCalls && !hasTailCall && !proto.isVararg;
        int[][] defTypes = computeDefiniteTypes(proto, code, reach, regs);
        Info info = new Info(!impure, callSafe, leafNoWindow, returnsInt, returnsVoid, defTypes);
        return new AnalysisResult(info, writesParams, jumpTargets);
    }

    // Register abstract types for return-kind inference.
    private static final int T_UNKNOWN = 0;
    private static final int T_INT = 1;
    private static final int T_OBJ = 2;
    private static final int T_NUM = 3;

    private static boolean[] computeJumpTargets(int[] code) {
        boolean[] targets = new boolean[code.length];
        for (int pc = 0; pc < code.length; pc++) {
            int jt = jumpTarget(code[pc], pc);
            if (jt >= 0 && jt < code.length) {
                targets[jt] = true;
            }
        }
        return targets;
    }

    /**
     * Sound forward dataflow pass computing registers that are guaranteed to
     * hold {@code TYPE_INT} regardless of caller argument types (unlike the
     * optimistic {@code returnsInt} pass, which seeds parameters as {@code T_INT}).
     */
    private static int[][] computeDefiniteTypes(LuaProto proto, int[] code, boolean[] reach, int regs) {
        int[][] defIn = new int[code.length][regs];
        boolean[] initialized = new boolean[code.length];
        initialized[0] = true;
        for (int iter = 0; iter < code.length * 2 + 4; iter++) {
            boolean changed = false;
            for (int pc = 0; pc < code.length; pc++) {
                if (!reach[pc] || !initialized[pc]) {
                    continue;
                }
                int[] out = defIn[pc].clone();
                int inst = code[pc];
                int op = Instruction.getOp(inst);
                int a = Instruction.getA(inst);
                int b = Instruction.getB(inst);
                int c = Instruction.getC(inst);
                switch (op) {
                    case OpCode.OP_MOVE, OpCode.OP_TESTSET -> setTy(out, regs, a, defIn[pc][b]);
                    case OpCode.OP_LOADI,
                            OpCode.OP_BNOT,
                            OpCode.OP_BAND,
                            OpCode.OP_BOR,
                            OpCode.OP_BXOR,
                            OpCode.OP_SHL,
                            OpCode.OP_SHR,
                            OpCode.OP_SHLI,
                            OpCode.OP_SHRI,
                            OpCode.OP_BANDK,
                            OpCode.OP_BORK,
                            OpCode.OP_BXORK -> setTy(out, regs, a, T_INT);
                    case OpCode.OP_LOADK -> {
                        LuaValue kv = proto.constants[Instruction.getBx(inst)];
                        setTy(out, regs, a, kv instanceof LuaInteger ? T_INT : T_UNKNOWN);
                    }
                    case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL, OpCode.OP_MOD, OpCode.OP_IDIV -> setTy(
                            out, regs, a, (defIn[pc][b] == T_INT && defIn[pc][c] == T_INT) ? T_INT : T_UNKNOWN);
                    case OpCode.OP_ADDI,
                            OpCode.OP_ADDK,
                            OpCode.OP_SUBK,
                            OpCode.OP_MULK,
                            OpCode.OP_IDIVK,
                            OpCode.OP_MODK,
                            OpCode.OP_UNM -> setTy(out, regs, a, defIn[pc][b] == T_INT ? T_INT : T_UNKNOWN);
                    case OpCode.OP_FORPREP -> {
                        setTy(out, regs, a, T_INT);
                        setTy(out, regs, a + 1, T_INT);
                        setTy(out, regs, a + 2, T_INT);
                        setTy(out, regs, a + 3, T_INT);
                    }
                    case OpCode.OP_FORLOOP -> {
                        setTy(out, regs, a, T_INT);
                        setTy(out, regs, a + 3, T_INT);
                    }
                    case OpCode.OP_LOADNIL, OpCode.OP_CLEANUP -> {
                        for (int j = 0; j <= b && a + j < regs; j++) {
                            setTy(out, regs, a + j, T_UNKNOWN);
                        }
                    }
                    case OpCode.OP_CLOSE -> {
                        for (int j = a; j < regs; j++) {
                            setTy(out, regs, j, T_UNKNOWN);
                        }
                    }
                    case OpCode.OP_SELF -> {
                        setTy(out, regs, a, T_UNKNOWN);
                        setTy(out, regs, a + 1, T_UNKNOWN);
                    }
                    case OpCode.OP_TFORCALL -> {
                        int nVars = Math.max(1, c);
                        for (int i = 0; i < nVars; i++) {
                            setTy(out, regs, a + 4 + i, T_UNKNOWN);
                        }
                        setTy(out, regs, a + 2, T_UNKNOWN);
                    }
                    case OpCode.OP_TFORLOOP -> setTy(out, regs, a + 2, T_UNKNOWN);
                    case OpCode.OP_CALL -> {
                        if (isTForSetup(proto, code, pc)) {
                            for (int i = 0; i < 4; i++) {
                                setTy(out, regs, a + i, T_UNKNOWN);
                            }
                        } else {
                            int nc = Instruction.getC(inst);
                            int limit = nc == 0 ? regs : Math.min(regs, a + Math.max(1, nc - 1));
                            for (int r = a; r < limit; r++) {
                                setTy(out, regs, r, T_UNKNOWN);
                            }
                        }
                    }
                    case OpCode.OP_LOADF,
                            OpCode.OP_LOADTRUE,
                            OpCode.OP_LOADFALSE,
                            OpCode.OP_LFALSESKIP,
                            OpCode.OP_GETUPVAL,
                            OpCode.OP_GETTABUP,
                            OpCode.OP_GETTABLE,
                            OpCode.OP_GETI,
                            OpCode.OP_GETFIELD,
                            OpCode.OP_DIV,
                            OpCode.OP_POW,
                            OpCode.OP_DIVK,
                            OpCode.OP_POWK,
                            OpCode.OP_NOT,
                            OpCode.OP_LEN,
                            OpCode.OP_CONCAT,
                            OpCode.OP_CLOSURE,
                            OpCode.OP_NEWTABLE,
                            OpCode.OP_VARARG -> setTy(out, regs, a, T_UNKNOWN);
                    default -> {}
                }
                for (int s : successors(code, pc)) {
                    if (s < 0 || s >= code.length) {
                        continue;
                    }
                    if (!initialized[s]) {
                        System.arraycopy(out, 0, defIn[s], 0, regs);
                        initialized[s] = true;
                        changed = true;
                    } else {
                        for (int r = 0; r < regs; r++) {
                            if (defIn[s][r] != out[r] && defIn[s][r] != T_UNKNOWN) {
                                defIn[s][r] = T_UNKNOWN;
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
        return defIn;
    }

    private static boolean isDeoptFreeAfterImpure(int inst) {
        return switch (Instruction.getOp(inst)) {
            case OpCode.OP_MOVE,
                    OpCode.OP_LOADI,
                    OpCode.OP_LOADF,
                    OpCode.OP_LOADK,
                    OpCode.OP_LOADNIL,
                    OpCode.OP_LOADTRUE,
                    OpCode.OP_LOADFALSE,
                    OpCode.OP_CLEANUP,
                    OpCode.OP_GETUPVAL,
                    OpCode.OP_CLOSURE,
                    OpCode.OP_CLOSE,
                    OpCode.OP_RETURN0,
                    OpCode.OP_RETURN1 -> true;
            case OpCode.OP_RETURN -> {
                int b = Instruction.getB(inst);
                yield b == 1 || b == 2;
            }
            default -> false;
        };
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

    private static String coroutineFieldAt(LuaProto proto, int[] code, int pc, int reg) {
        int def = lastWrite(code, pc, reg);
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_GETFIELD) {
            return null;
        }
        int c = Instruction.getC(code[def]);
        if (!(c >= 0 && c < proto.constants.length)
                || !(proto.constants[c] instanceof LuaString ks)) {
            return null;
        }
        int baseReg = Instruction.getB(code[def]);
        int baseDef = lastWrite(code, def, baseReg);
        if (baseDef < 0 || Instruction.getOp(code[baseDef]) != OpCode.OP_GETTABUP) {
            return null;
        }
        int bc = Instruction.getC(code[baseDef]);
        if (!(bc >= 0 && bc < proto.constants.length)
                || !(proto.constants[bc] instanceof LuaString ms)
                || !"coroutine".equals(ms.toLuaString())) {
            return null;
        }
        return ks.toLuaString();
    }

    private static boolean isCoroutineResumeIntrinsic(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        if (Instruction.getOp(inst) != OpCode.OP_CALL
                || Instruction.getB(inst) < 2
                || Instruction.getC(inst) < 1) {
            return false;
        }
        return "resume".equals(coroutineFieldAt(proto, code, pc, Instruction.getA(inst)));
    }

    private static boolean isAlwaysDeoptingCall(LuaProto proto, int[] code, int pc) {
        int inst = code[pc];
        int op = Instruction.getOp(inst);
        if (op != OpCode.OP_CALL && op != OpCode.OP_TAILCALL) {
            return false;
        }
        if (isIntrinsicCall(proto, code, pc)) {
            return false;
        }
        if (op == OpCode.OP_CALL) {
            int cc = Instruction.getC(inst);
            if (cc != 1 && cc != 2) {
                return true;
            }
        }
        return coroutineFieldAt(proto, code, pc, Instruction.getA(inst)) != null;
    }

    private static boolean loopUnconditionallyDeopts(LuaProto proto, int[] code, int h, int backPc) {
        for (int k = h; k < backPc; k++) {
            int inst = code[k];
            int op = Instruction.getOp(inst);
            if (isAlwaysDeoptingCall(proto, code, k)) {
                return true;
            }
            if (op == OpCode.OP_JMP || isBranch(inst)
                    || op == OpCode.OP_FORPREP || op == OpCode.OP_TFORPREP
                    || op == OpCode.OP_RETURN0 || op == OpCode.OP_RETURN1 || op == OpCode.OP_RETURN
                    || op == OpCode.OP_TAILCALL) {
                return false;
            }
        }
        return false;
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
                || isSetmetatableIntrinsic(proto, code, pc)
                || isCoroutineResumeIntrinsic(proto, code, pc)) {
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
                } else if (isCoroutineResumeIntrinsic(proto, code, pc)) {
                    int nRes = c - 1;
                    for (int i = 0; i < nRes; i++) {
                        setTy(out, regs, a + i, T_UNKNOWN);
                    }
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
    private static void emitDivNum(MethodVisitor mv, int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitGuardNumber(mv, b, pc, activeLoop);
        emitGuardNumber(mv, c, pc, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        emitLoadDouble(mv, c, activeLoop);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ R[C]}; always float, uses C99 pow semantics. */
    private static void emitPowNum(MethodVisitor mv, int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitGuardNumber(mv, b, pc, activeLoop);
        emitGuardNumber(mv, c, pc, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        emitLoadDouble(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaNumPow", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] // R[C]}: int floor-div, or float floor for numbers. */
    private static void emitIdivNum(MethodVisitor mv, int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitGuardNumber(mv, b, pc, activeLoop);
        emitGuardNumber(mv, c, pc, activeLoop);
        Label floatLane = new Label();
        Label done = new Label();
        emitLoadTag(mv, b, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        emitLoadTag(mv, c, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        emitLoadP(mv, c, activeLoop);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label divOk = new Label();
        mv.visitJumpInsn(IFNE, divOk);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(divOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        emitLoadP(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorDiv", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b, activeLoop);
        emitLoadDouble(mv, c, activeLoop);
        mv.visitInsn(DDIV);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floor", "(D)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    /** {@code R[A] = R[B] % R[C]}: int floor-mod, or Lua float mod. */
    private static void emitModNum(MethodVisitor mv, int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitGuardNumber(mv, b, pc, activeLoop);
        emitGuardNumber(mv, c, pc, activeLoop);
        Label floatLane = new Label();
        Label done = new Label();
        emitLoadTag(mv, b, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        emitLoadTag(mv, c, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        emitLoadP(mv, c, activeLoop);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label modOk = new Label();
        mv.visitJumpInsn(IFNE, modOk);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(modOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        emitLoadP(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        emitLoadDouble(mv, b, activeLoop);
        emitLoadDouble(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaValue", "luaFloatMod", "(DD)D", false);
        emitStoreFloatFromStack(mv, a);
        mv.visitLabel(done);
    }

    private static void emitBitwiseNum(MethodVisitor mv, int a, int b, int c, int pc,
            int jvmIntOp, int[][] types, LoopOpt activeLoop) {
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, activeLoop);
        }
        if (!isStaticInt(types, pc, c)) {
            emitGuardInt(mv, c, pc, activeLoop);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        emitLoadP(mv, c, activeLoop);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    private static void emitShiftNum(MethodVisitor mv, int a, int b, int c, int pc, boolean left,
            int[][] types, LoopOpt activeLoop) {
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, activeLoop);
        }
        if (!isStaticInt(types, pc, c)) {
            emitGuardInt(mv, c, pc, activeLoop);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        emitLoadP(mv, c, activeLoop);
        String name = left ? "shiftLeft" : "shiftRight";
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", name, "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    private static void emitShiftImm(MethodVisitor mv, int a, int b, int sc, int pc, boolean left,
            int[][] types, LoopOpt activeLoop) {
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, activeLoop);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        mv.visitLdcInsn((long) sc);
        String name = left ? "shiftLeft" : "shiftRight";
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", name, "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /** {@code R[A] = R[B] / K[C]}; always float (K may be int or float). */
    private static void emitDivK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc, LoopOpt activeLoop) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, b, pc, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        mv.visitLdcInsn(kv);
        mv.visitInsn(DDIV);
        emitStoreFloatFromStack(mv, a);
    }

    /** {@code R[A] = R[B] ^ K[C]}; always float with C99 pow semantics. */
    private static void emitPowK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc, LoopOpt activeLoop) {
        double kv = ((Number) numericConstant(proto, c)).doubleValue();
        emitGuardNumber(mv, b, pc, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
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

    private static boolean isStaticInt(int[][] types, int pc, int reg) {
        return types != null && pc < types.length && reg >= 0 && reg < types[pc].length
                && types[pc][reg] == T_INT;
    }

    /**
     * {@code R[A] = R[B] <op> R[C]} (+, -, *). Uses a static zero-guard integer
     * lane when both operands are proven {@code T_INT}, or a 2-load runtime
     * dispatch with direct pure-int and pure-float fast lanes otherwise.
     */
    private static void emitArithNum(MethodVisitor mv, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp, int[][] types, LoopOpt activeLoop) {
        if (activeLoop != null && a == activeLoop.accReg) {
            emitAccArithRR(mv, b, c, pc, jvmIntOp, jvmFloatOp, types, activeLoop);
            return;
        }
        if (isStaticInt(types, pc, b) && isStaticInt(types, pc, c)) {
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            emitLoadP(mv, b, activeLoop);
            emitLoadP(mv, c, activeLoop);
            mv.visitInsn(jvmIntOp);
            mv.visitInsn(LASTORE);
            emitTagIntNull(mv, a);
            return;
        }
        Label bNotInt = new Label();
        Label bFloat = new Label();
        Label mixedFromBInt = new Label();
        Label mixedFromBFloat = new Label();
        Label storeFloat = new Label();
        Label done = new Label();

        emitLoadTag(mv, b, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, bNotInt);
        mv.visitInsn(POP);

        // R[B] is TYPE_INT
        emitLoadTag(mv, c, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, mixedFromBInt);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        emitLoadP(mv, c, activeLoop);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);

        mv.visitLabel(mixedFromBInt);
        ldcInt(mv, TYPE_FLOAT);
        Label cOkFloat = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, cOkFloat);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(cOkFloat);
        emitLoadP(mv, b, activeLoop);
        mv.visitInsn(L2D);
        emitLoadP(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitInsn(jvmFloatOp);
        mv.visitJumpInsn(GOTO, storeFloat);

        // R[B] is not TYPE_INT: must be TYPE_FLOAT
        mv.visitLabel(bNotInt);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPEQ, bFloat);
        emitDeopt(mv, pc, false, activeLoop);

        mv.visitLabel(bFloat);
        emitLoadTag(mv, c, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, mixedFromBFloat);
        mv.visitInsn(POP);
        emitLoadP(mv, b, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        emitLoadP(mv, c, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitInsn(jvmFloatOp);
        mv.visitJumpInsn(GOTO, storeFloat);

        mv.visitLabel(mixedFromBFloat);
        ldcInt(mv, TYPE_INT);
        Label cOkInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, cOkInt);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(cOkInt);
        emitLoadP(mv, b, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        emitLoadP(mv, c, activeLoop);
        mv.visitInsn(L2D);
        mv.visitInsn(jvmFloatOp);

        mv.visitLabel(storeFloat);
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

    private static void emitArithKNum(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp, int[][] types, LoopOpt activeLoop) {
        LuaValue k = proto.constants[c];
        if (!(k instanceof LuaInteger)) {
            emitDeopt(mv, pc, false, activeLoop);
            return;
        }
        long kv = ((LuaInteger) k).toLong();
        if (activeLoop != null && a == activeLoop.accReg && jvmFloatOp >= 0) {
            emitAccArithK(mv, b, kv, pc, jvmIntOp, jvmFloatOp, types, activeLoop);
            return;
        }
        if (jvmFloatOp < 0 || isStaticInt(types, pc, b)) {
            if (!isStaticInt(types, pc, b)) {
                emitGuardInt(mv, b, pc, activeLoop);
            }
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, a);
            emitLoadP(mv, b, activeLoop);
            mv.visitLdcInsn(kv);
            mv.visitInsn(jvmIntOp);
            mv.visitInsn(LASTORE);
            emitTagIntNull(mv, a);
            return;
        }
        Label floatLane = new Label();
        Label done = new Label();
        emitLoadTag(mv, b, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitInsn(POP);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        mv.visitLdcInsn(kv);
        mv.visitInsn(jvmIntOp);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        ldcInt(mv, TYPE_FLOAT);
        Label okFloat = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, okFloat);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(okFloat);
        emitLoadP(mv, b, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
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
    private static void emitSqrtTail(MethodVisitor mv, int a, int pc, int retDstLocal) {
        emitSqrtGuardAndCompute(mv, a, pc);
        if (retDstLocal >= 0) {
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
            mv.visitVarInsn(LSTORE, 6);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ILOAD, retDstLocal);
            mv.visitVarInsn(LLOAD, 6);
            mv.visitInsn(LASTORE);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ILOAD, retDstLocal);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitInsn(BASTORE);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, retDstLocal);
            mv.visitInsn(ACONST_NULL);
            mv.visitInsn(AASTORE);
            mv.visitInsn(RETURN);
        } else {
            // Box as LuaFloat (our object-mode return type).
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaFloat", "valueOf",
                    "(D)Lorg/luava/runtime/LuaFloat;", false);
            mv.visitInsn(ARETURN);
        }
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

    /**
     * Emits the {@code setmetatable(t, mt)} intrinsic for the one-result CALL
     * form: {@code R[a] = setmetatable(R[a+1], R[a+2])}. The function register
     * is identity-guarded against the shared {@code BaseLib.SETMETATABLE}
     * singleton; anything else (a reassigned or shadowed global) deopts. The
     * helper returns {@code null} for a non-table target, an invalid metatable
     * shape, or a {@code __gc}/{@code __mode} metatable, so the interpreter
     * raises the exact error / performs the registration exactly once.
     */
    private static void emitSetmetatableCall(MethodVisitor mv, int a, int pc) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "SETMETATABLE",
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, pc);
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
        emitDeopt(mv, pc);
        mv.visitLabel(done);
        emitStoreValueReg(mv, a);
    }

    private static void emitSetmetatableTailInto(MethodVisitor mv, int a, int pc, int retDstLocal) {
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitFieldInsn(GETSTATIC, "org/luava/runtime/standard/BaseLib", "SETMETATABLE",
                "Lorg/luava/runtime/LuaFunction;");
        Label ok = new Label();
        mv.visitJumpInsn(IF_ACMPEQ, ok);
        emitDeopt(mv, pc);
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
        emitDeopt(mv, pc);
        mv.visitLabel(done);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ILOAD, retDstLocal);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ILOAD, retDstLocal);
        ldcInt(mv, TYPE_OBJECT);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, retDstLocal);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitInsn(AASTORE);
        mv.visitInsn(RETURN);
    }

    private static void emitCoroutineResumeCall(
            MethodVisitor mv, int a, int nActualArgs, int nResults, int pc) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, a);
        mv.visitInsn(IADD);
        ldcInt(mv, nActualArgs);
        ldcInt(mv, nResults);
        ldcInt(mv, pc);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "coroutineResumeInline",
                "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;IIII)V", false);
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
        emitGuardNumber(mv, reg, pc, null);
    }

    private static void emitGuardNumber(MethodVisitor mv, int reg, int pc, LoopOpt activeLoop) {
        if (activeLoop != null && (reg == activeLoop.indRegA || reg == activeLoop.indRegA + 3)) {
            return;
        }
        emitLoadTag(mv, reg, activeLoop);
        mv.visitVarInsn(ISTORE, 7);
        Label isNum = new Label();
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, isNum);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPEQ, isNum);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(isNum);
    }

    /** Pushes register {@code reg} as a JVM double (int value or raw float). */
    private static void emitLoadDouble(MethodVisitor mv, int reg) {
        emitLoadDouble(mv, reg, null);
    }

    private static void emitLoadDouble(MethodVisitor mv, int reg, LoopOpt activeLoop) {
        if (activeLoop != null && (reg == activeLoop.indRegA || reg == activeLoop.indRegA + 3)) {
            mv.visitVarInsn(LLOAD, activeLoop.slotI);
            mv.visitInsn(L2D);
            return;
        }
        Label isFloat = new Label();
        Label done = new Label();
        emitLoadTag(mv, reg, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, isFloat);
        emitLoadP(mv, reg, activeLoop);
        mv.visitInsn(L2D);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(isFloat);
        emitLoadP(mv, reg, activeLoop);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitLabel(done);
    }

    /** {@code R[A] = R[B] // K[C]} with Lua floor-division semantics. */
    private static void emitIdivK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc,
            int[][] types, LoopOpt activeLoop) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, activeLoop);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        mv.visitLdcInsn(kv);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorDiv", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    /** {@code R[A] = R[B] % K[C]} with Lua floor-modulo semantics. */
    private static void emitModK(MethodVisitor mv, LuaProto proto, int a, int b, int c, int pc,
            int[][] types, LoopOpt activeLoop) {
        long kv = ((LuaInteger) proto.constants[c]).toLong();
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, activeLoop);
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, b, activeLoop);
        mv.visitLdcInsn(kv);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "floorMod", "(JJ)J", false);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
    }

    private static void emitForPrep(MethodVisitor mv, int a, int bx, int pc, Label[] labels, LoopOpt prepLoop) {
        emitGuardInt(mv, a, pc);
        emitGuardInt(mv, a + 1, pc);
        emitGuardInt(mv, a + 2, pc);
        emitLoadP(mv, a + 2);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label stepOk = new Label();
        mv.visitJumpInsn(IFNE, stepOk);
        emitDeopt(mv, pc);
        mv.visitLabel(stepOk);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 3);
        emitLoadP(mv, a);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a + 3);
        emitLoadP(mv, a + 2);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LCMP);
        Label negStep = new Label();
        mv.visitJumpInsn(IFLE, negStep);
        emitLoadP(mv, a);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCMP);
        Label noSkipPos = new Label();
        mv.visitJumpInsn(IFLE, noSkipPos);
        mv.visitJumpInsn(GOTO, labels[pc + 1 + bx]);
        mv.visitLabel(noSkipPos);
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
        emitLoadP(mv, a);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCMP);
        Label noSkipNeg = new Label();
        mv.visitJumpInsn(IFGE, noSkipNeg);
        mv.visitJumpInsn(GOTO, labels[pc + 1 + bx]);
        mv.visitLabel(noSkipNeg);
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
        if (prepLoop != null) {
            emitTagIntNull(mv, a);
            emitLoopOsrInit(mv, prepLoop);
        }
    }

    private static void emitForLoop(
            MethodVisitor mv, int a, int bx, int pc, Label[] labels, int tickSlot, LoopOpt endLoop) {
        if (endLoop != null) {
            mv.visitVarInsn(LLOAD, endLoop.slotRem);
            mv.visitInsn(LCONST_0);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false);
            Label exit = new Label();
            mv.visitJumpInsn(IFLE, exit);
            mv.visitVarInsn(LLOAD, endLoop.slotRem);
            mv.visitInsn(LCONST_1);
            mv.visitInsn(LSUB);
            mv.visitVarInsn(LSTORE, endLoop.slotRem);
            mv.visitVarInsn(LLOAD, endLoop.slotI);
            mv.visitVarInsn(LLOAD, endLoop.slotStep);
            mv.visitInsn(LADD);
            mv.visitVarInsn(LSTORE, endLoop.slotI);
            emitSafepointBackEdge(mv, tickSlot, Math.max(1, bx) * SAFEPOINT_BATCH);
            mv.visitJumpInsn(GOTO, labels[pc + 1 - bx]);
            mv.visitLabel(exit);
            emitFlushLoopStateOnExit(mv, endLoop);
            return;
        }
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCONST_0);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "compareUnsigned", "(JJ)I", false);
        Label exit = new Label();
        mv.visitJumpInsn(IFLE, exit);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 1);
        emitLoadP(mv, a + 1);
        mv.visitInsn(LCONST_1);
        mv.visitInsn(LSUB);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a + 3);
        emitLoadP(mv, a);
        emitLoadP(mv, a + 2);
        mv.visitInsn(LADD);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a + 3);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, a);
        emitLoadP(mv, a + 3);
        mv.visitInsn(LASTORE);
        emitTagIntNull(mv, a);
        emitSafepointBackEdge(mv, tickSlot, Math.max(1, bx) * SAFEPOINT_BATCH);
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
    private static void emitTForLoop(MethodVisitor mv, int a, int bx, int pc, Label[] labels, int tickSlot) {
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, a + 4);
        mv.visitInsn(BALOAD);
        // NIL is 0; anything else is a live control value.
        Label isNil = new Label();
        mv.visitJumpInsn(IFEQ, isNil);
        emitMove(mv, a + 2, a + 4);
        emitSafepointBackEdge(mv, tickSlot, Math.max(1, bx) * SAFEPOINT_BATCH);
        mv.visitJumpInsn(GOTO, labels[pc + 1 - bx]);
        mv.visitLabel(isNil);
    }

    /** Pushes {@code p[base+reg]} (an unboxed long) onto the JVM stack. */
    private static void emitLoadP(MethodVisitor mv, int reg) {
        emitLoadP(mv, reg, null);
    }

    private static void emitLoadP(MethodVisitor mv, int reg, LoopOpt activeLoop) {
        if (activeLoop != null) {
            if (reg == activeLoop.indRegA || reg == activeLoop.indRegA + 3) {
                mv.visitVarInsn(LLOAD, activeLoop.slotI);
                return;
            }
            if (reg == activeLoop.accReg) {
                mv.visitVarInsn(LLOAD, activeLoop.slotAccBits);
                return;
            }
        }
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, reg);
        mv.visitInsn(LALOAD);
    }

    private static void emitLoadTag(MethodVisitor mv, int reg, LoopOpt activeLoop) {
        if (activeLoop != null) {
            if (reg == activeLoop.indRegA || reg == activeLoop.indRegA + 3) {
                ldcInt(mv, TYPE_INT);
                return;
            }
            if (reg == activeLoop.accReg) {
                mv.visitVarInsn(ILOAD, activeLoop.slotAccTag);
                return;
            }
        }
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, reg);
        mv.visitInsn(BALOAD);
    }

    private static void emitCmpImm(
            MethodVisitor mv, int a, int sb, int k, int pc, Label[] labels, int jumpIfTrue, int jumpIfFalse,
            int[][] types, LoopOpt activeLoop) {
        if (isStaticInt(types, pc, a)) {
            Label condTrue = new Label();
            emitLoadP(mv, a, activeLoop);
            mv.visitLdcInsn((long) sb);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(jumpIfTrue, condTrue);
            mv.visitJumpInsn(GOTO, labels[k == 1 ? pc + 2 : pc + 1]);
            mv.visitLabel(condTrue);
            mv.visitJumpInsn(GOTO, labels[k == 1 ? pc + 1 : pc + 2]);
            return;
        }
        emitGuardNumber(mv, a, pc, activeLoop);
        Label intLane = new Label();
        Label condTrue = new Label();
        Label condFalse = new Label();
        emitLoadTag(mv, a, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intLane);
        int dcmpl = (jumpIfTrue == IFGT || jumpIfTrue == IFGE) ? DCMPL : DCMPG;
        emitLoadDouble(mv, a, activeLoop);
        mv.visitLdcInsn((double) sb);
        mv.visitInsn(dcmpl);
        mv.visitJumpInsn(jumpIfTrue, condTrue);
        mv.visitJumpInsn(GOTO, condFalse);
        mv.visitLabel(intLane);
        emitLoadP(mv, a, activeLoop);
        mv.visitLdcInsn((long) sb);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(jumpIfTrue, condTrue);
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

    private static void emitCmpRR(MethodVisitor mv, int a, int b, int k, int pc, Label[] labels,
            boolean orEqual, LoopOpt activeLoop) {
        emitGuardNumber(mv, a, pc, activeLoop);
        emitGuardNumber(mv, b, pc, activeLoop);
        int cmpOp = orEqual ? IFLE : IFLT;
        Label intLane = new Label();
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        emitLoadTag(mv, a, activeLoop);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intLane);
        emitLoadDouble(mv, a, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        mv.visitInsn(DCMPG);
        mv.visitJumpInsn(cmpOp, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(intLane);
        emitLoadTag(mv, b, activeLoop);
        ldcInt(mv, TYPE_INT);
        Label intInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, intInt);
        emitLoadP(mv, a, activeLoop);
        mv.visitInsn(L2D);
        emitLoadDouble(mv, b, activeLoop);
        mv.visitInsn(DCMPG);
        mv.visitJumpInsn(cmpOp, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(intInt);
        emitLoadP(mv, a, activeLoop);
        emitLoadP(mv, b, activeLoop);
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

    private static void emitEqRR(MethodVisitor mv, LuaProto proto, int a, int b, int k, int pc,
            Label[] labels, LoopOpt activeLoop) {
        Label boolLane = new Label();
        Label nilLane = new Label();
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        emitLoadTag(mv, a, activeLoop);
        mv.visitVarInsn(ISTORE, 7);
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
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(aIsInt);
        emitLoadTag(mv, b, activeLoop);
        mv.visitVarInsn(ISTORE, 7);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        Label bInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, bInt);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        emitLoadDouble(mv, a, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(bInt);
        emitLoadP(mv, a, activeLoop);
        emitLoadP(mv, b, activeLoop);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(aIsFloat);
        emitLoadTag(mv, b, activeLoop);
        mv.visitVarInsn(ISTORE, 7);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_INT);
        Label bNum = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, bNum);
        mv.visitVarInsn(ILOAD, 7);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        mv.visitLabel(bNum);
        emitLoadDouble(mv, a, activeLoop);
        emitLoadDouble(mv, b, activeLoop);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(boolLane);
        emitLoadTag(mv, b, activeLoop);
        ldcInt(mv, TYPE_BOOLEAN);
        mv.visitJumpInsn(IF_ICMPNE, doneFalse);
        emitLoadP(mv, a, activeLoop);
        emitLoadP(mv, b, activeLoop);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFEQ, doneTrue);
        mv.visitJumpInsn(GOTO, doneFalse);
        mv.visitLabel(nilLane);
        emitLoadTag(mv, b, activeLoop);
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

    private static void emitEqK(MethodVisitor mv, LuaProto proto, int a, int b, int k, int pc,
            Label[] labels, LoopOpt activeLoop) {
        LuaValue kv = proto.constants[b];
        Label doneTrue = new Label();
        Label doneFalse = new Label();
        if (kv instanceof LuaInteger ki) {
            long c = ki.toLong();
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_INT);
            Label asFloat = new Label();
            mv.visitJumpInsn(IF_ICMPNE, asFloat);
            emitLoadP(mv, a, activeLoop);
            mv.visitLdcInsn(c);
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(asFloat);
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadDouble(mv, a, activeLoop);
            mv.visitLdcInsn((double) c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv instanceof org.luava.runtime.LuaFloat kf) {
            double c = kf.toDouble();
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_INT);
            Label asFloat = new Label();
            mv.visitJumpInsn(IF_ICMPNE, asFloat);
            emitLoadP(mv, a, activeLoop);
            mv.visitInsn(L2D);
            mv.visitLdcInsn(c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(asFloat);
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_FLOAT);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadDouble(mv, a, activeLoop);
            mv.visitLdcInsn(c);
            mv.visitInsn(DCMPL);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv instanceof org.luava.runtime.LuaBoolean kb) {
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_BOOLEAN);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            emitLoadP(mv, a, activeLoop);
            mv.visitLdcInsn((long) (kb.toBoolean() ? 1 : 0));
            mv.visitInsn(LCMP);
            mv.visitJumpInsn(IFEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv.isNil()) {
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_NIL);
            mv.visitJumpInsn(IF_ICMPEQ, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else if (kv instanceof LuaString ks) {
            emitLoadTag(mv, a, activeLoop);
            ldcInt(mv, TYPE_OBJECT);
            mv.visitJumpInsn(IF_ICMPNE, doneFalse);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitInsn(AALOAD);
            mv.visitInsn(DUP);
            mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaString");
            Label notStr = new Label();
            mv.visitJumpInsn(IFEQ, notStr);
            mv.visitLdcInsn(ks.value());
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/LuaString", "interned",
                    "(Ljava/lang/String;)Lorg/luava/runtime/LuaString;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaValue", "luaEquals",
                    "(Lorg/luava/runtime/LuaValue;)Z", false);
            mv.visitJumpInsn(IFNE, doneTrue);
            mv.visitJumpInsn(GOTO, doneFalse);
            mv.visitLabel(notStr);
            mv.visitInsn(POP);
            mv.visitJumpInsn(GOTO, doneFalse);
        } else {
            emitDeopt(mv, pc, false, activeLoop);
            return;
        }
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
            boolean resultUsed, boolean callerReturnsInt, boolean selfSafe) {
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
        Label selfProto = new Label();
        Label generalCall = new Label();
        if (!callerReturnsInt || !selfSafe) {
            // Object-returning callers, and parameter-writing protos whose
            // direct self call has no window rollback, use the general
            // snapshot-wrapped path. The proto comparison is not emitted here
            // at all: it would push two refs that only IF_ACMPNE consumes, and
            // a GOTO past it would carry them onto the operand stack, leaving
            // the two edges into `generalCall` with different stack depths.
            // That inconsistency is invisible to the emitter but makes ASM's
            // COMPUTE_FRAMES throw ArrayIndexOutOfBounds in Frame.merge, so
            // every object-returning proto containing a call silently failed
            // to compile (JitCompiler caught it and set jitDisabled).
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
            mv.visitLabel(selfProto);
            emitDirectInvoke(mv, owner, proto, a, nArgs, resumePc);
            mv.visitJumpInsn(GOTO, done);
        }
        // General monomorphic call: one runtime helper does the callee guard,
        // tier-up request, arity nil-fill, capacity guard, window snapshot and
        // return-protocol write. Emitting it inline doubled every caller's
        // kernel and cost C2 optimization of the hot loop (measured on
        // 04_string_concat); a single INVOKESTATIC keeps it small.
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
        emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "callFast",
                "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;IIIZ)V",
                resumePc);
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
            int resumePc, int fusedB, int hoistSlot, int[] fusedBList, int fusedBCount, Label[] labels,
            boolean callerReturnsInt, boolean selfSafe, int tickSlot, int retDstLocal) {
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
            emitSafepointBackEdge(mv, tickSlot, Math.max(1, resumePc + 1) * SAFEPOINT_BATCH);
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
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto",
                tickSlot >= 0 ? "guardedJitCode" : "jitCode",
                "Lorg/luava/runtime/jit/JitCode;");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 21);
        Label hasJit = new Label();
        mv.visitJumpInsn(IFNONNULL, hasJit);
        // See emitCall: request compilation of a callee reached only from
        // compiled code, then deopt so the interpreter runs it once.
        mv.visitVarInsn(ALOAD, 20);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime",
                tickSlot >= 0 ? "requestCalleeGuarded" : "requestCallee",
                "(Lorg/luava/runtime/bytecode/LuaProto;)V", false);
        emitDeopt(mv, resumePc);
        mv.visitLabel(hasJit);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "callSafe", "Z");
        Label isPure = new Label();
        mv.visitJumpInsn(IFNE, isPure);
        emitDeopt(mv, resumePc);
        mv.visitLabel(isPure);
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsInt", "Z");
        Label tailObj = new Label();
        if (callerReturnsInt) {
            Label returnsIntOk = new Label();
            mv.visitJumpInsn(IFNE, returnsIntOk);
            // The enclosing proto returns an integer; an object callee's
            // result cannot feed the long lane. Structural.
            emitDeopt(mv, resumePc, true);
            mv.visitLabel(returnsIntOk);
        } else if (retDstLocal < 0) {
            mv.visitJumpInsn(IFEQ, tailObj);
            // The enclosing proto returns an object; an integer callee's long
            // result cannot feed the boxed lane. Structural.
            emitDeopt(mv, resumePc, true);
            mv.visitLabel(tailObj);
        } else {
            mv.visitInsn(POP);
        }
        // A void callee forwards zero results; the compiled tail path can
        // only return one value (int or object), so run it interpreted.
        mv.visitVarInsn(ALOAD, 21);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/jit/JitCode", "returnsVoid", "Z");
        Label notVoidTail = new Label();
        mv.visitJumpInsn(IFEQ, notVoidTail);
        emitDeopt(mv, resumePc, true);
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
        } else if (retDstLocal >= 0) {
            mv.visitVarInsn(ILOAD, retDstLocal);
            emitGuardedInvoke(mv, "org/luava/runtime/jit/JitRuntime", "invokeTailInto",
                    "(Lorg/luava/runtime/jit/JitCode;Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;IIIII)V",
                    resumePc);
            mv.visitInsn(RETURN);
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

    private static void emitBoxValue(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv, int reg, int c, int k, int pc) {
        if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, reg);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "getLuaValue",
                    "([J[B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaValue;", false);
        }
    }

    private static void emitSetTabUp(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int k, int pc, LoopOpt activeLoop) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc, activeLoop);
        emitLoadConst(mv, proto, cgEnv, b);
        emitBoxValue(mv, proto, cgEnv, c, c, k, pc);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "set",
                "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitLoadPlainTableReg(MethodVisitor mv, int reg, int pc, LoopOpt activeLoop) {
        if (activeLoop != null && reg == activeLoop.tblReg) {
            mv.visitVarInsn(ALOAD, activeLoop.slotTbl);
            Label ok = new Label();
            mv.visitJumpInsn(IFNONNULL, ok);
            emitDeopt(mv, pc, false, activeLoop);
            mv.visitLabel(ok);
            mv.visitVarInsn(ALOAD, activeLoop.slotTbl);
            return;
        }
        emitGuardObject(mv, reg, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, reg);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardPlainTable(mv, pc, activeLoop);
    }

    private static void emitSetTable(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int k, int pc, int[][] types, LoopOpt activeLoop) {
        boolean keyStaticInt = isStaticInt(types, pc, b);
        boolean valStaticInt = k == 0 && isStaticInt(types, pc, c);
        if (keyStaticInt) {
            emitLoadPlainTableReg(mv, a, pc, activeLoop);
            emitLoadP(mv, b, activeLoop);
            if (valStaticInt) {
                emitLoadP(mv, c, activeLoop);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntLong",
                        "(JJ)V", false);
            } else if (k == 1) {
                emitLoadConst(mv, proto, cgEnv, c);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                        "(JLorg/luava/runtime/LuaValue;)V", false);
            } else {
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 4);
                emitIndex(mv, c);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntFromStack",
                        "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
            }
            return;
        }
        Label intKey = new Label();
        Label objKey = new Label();
        Label done = new Label();
        emitLoadTag(mv, b, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intKey);
        ldcInt(mv, TYPE_OBJECT);
        mv.visitJumpInsn(IF_ICMPEQ, objKey);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitLoadPlainTableReg(mv, a, pc, activeLoop);
        emitLoadP(mv, b, activeLoop);
        if (valStaticInt) {
            emitLoadP(mv, c, activeLoop);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntLong",
                    "(JJ)V", false);
        } else if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                    "(JLorg/luava/runtime/LuaValue;)V", false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntFromStack",
                    "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        }
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(objKey);
        emitLoadPlainTableReg(mv, a, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawset",
                    "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)V", false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetFromStack",
                    "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        }
        mv.visitLabel(done);
    }

    private static void emitSetI(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int k, int pc, int[][] types, LoopOpt activeLoop) {
        emitLoadPlainTableReg(mv, a, pc, activeLoop);
        mv.visitLdcInsn((long) b);
        if (k == 0 && isStaticInt(types, pc, c)) {
            emitLoadP(mv, c, activeLoop);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntLong",
                    "(JJ)V", false);
        } else if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetInt",
                    "(JLorg/luava/runtime/LuaValue;)V", false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntFromStack",
                    "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        }
    }

    private static void emitSetField(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int k, int pc, LoopOpt activeLoop) {
        if (activeLoop == null
                && b >= 0 && b < proto.constants.length
                && proto.constants[b] instanceof LuaString ks
                && ks.isCanonical()
                && ks != LuaValue.Meta.GC
                && ks != LuaValue.Meta.MODE
                && writesFreshTable(proto, proto.code, pc, a, cgEnv.jumpTargets)) {
            long bloomBit = 1L << (ks.fastHash() & 63);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitInsn(AALOAD);
            mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
            emitLoadStringConst(mv, cgEnv, b);
            mv.visitLdcInsn(bloomBit);
            if (k == 1) {
                emitLoadConst(mv, proto, cgEnv, c);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "putFreshCanonicalConst",
                        "(Lorg/luava/runtime/LuaString;JLorg/luava/runtime/LuaValue;)V", false);
            } else {
                mv.visitVarInsn(ALOAD, 2);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitVarInsn(ALOAD, 4);
                emitIndex(mv, c);
                mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "putFreshCanonicalFromStack",
                        "(Lorg/luava/runtime/LuaString;J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
            }
            return;
        }
        emitGuardObject(mv, a, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc, activeLoop);
        emitLoadConst(mv, proto, cgEnv, b);
        if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "trySetField",
                    "(Lorg/luava/runtime/LuaValue;Lorg/luava/runtime/LuaValue;)Z", false);
        } else {
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "trySetFieldFromStack",
                    "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        }
        Label ok = new Label();
        mv.visitJumpInsn(IFNE, ok);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(ok);
    }

    private static void emitLoadConst(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv, int c) {
        if (cgEnv != null && cgEnv.foldConsts && c >= 0 && c < proto.constants.length) {
            if (proto.constants[c] instanceof LuaString) {
                mv.visitFieldInsn(GETSTATIC, cgEnv.owner, "KS_" + c, "Lorg/luava/runtime/LuaString;");
            } else {
                mv.visitFieldInsn(GETSTATIC, cgEnv.owner, "KV_" + c, "Lorg/luava/runtime/LuaValue;");
            }
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "constants",
                "[Lorg/luava/runtime/LuaValue;");
        ldcInt(mv, c);
        mv.visitInsn(AALOAD);
    }

    private static void emitLoadStringConst(MethodVisitor mv, CodegenEnv cgEnv, int c) {
        if (cgEnv != null && cgEnv.foldConsts) {
            mv.visitFieldInsn(GETSTATIC, cgEnv.owner, "KS_" + c, "Lorg/luava/runtime/LuaString;");
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaClosure", "proto",
                "Lorg/luava/runtime/bytecode/LuaProto;");
        mv.visitFieldInsn(GETFIELD, "org/luava/runtime/bytecode/LuaProto", "constants",
                "[Lorg/luava/runtime/LuaValue;");
        ldcInt(mv, c);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaString");
    }

    private static void emitGuardPlainTable(MethodVisitor mv, int pc, LoopOpt activeLoop) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(isTable);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
        mv.visitInsn(DUP);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "getMetatable",
                "()Lorg/luava/runtime/LuaTable;", false);
        Label noMeta = new Label();
        mv.visitJumpInsn(IFNULL, noMeta);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(noMeta);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
    }

    private static void emitStoreValueReg(MethodVisitor mv, int a) {
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/bytecode/BytecodeVM", "setLuaValue",
                "([J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)V", false);
    }

    private static void emitSelf(MethodVisitor mv, String owner, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int k, int pc) {
        if (k == 1 && c >= 0 && c < proto.constants.length && proto.constants[c] instanceof LuaString) {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            mv.visitVarInsn(ILOAD, 5);
            ldcInt(mv, a);
            ldcInt(mv, b);
            emitLoadStringConst(mv, cgEnv, c);
            mv.visitFieldInsn(GETSTATIC, owner, "SC_" + pc,
                    "Lorg/luava/runtime/jit/JitRuntime$SelfCache;");
            ldcInt(mv, pc);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "selfDispatchCached",
                    "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;IIILorg/luava/runtime/LuaString;Lorg/luava/runtime/jit/JitRuntime$SelfCache;I)V",
                    false);
            return;
        }
        mv.visitVarInsn(ALOAD, 0);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        mv.visitVarInsn(ILOAD, 5);
        ldcInt(mv, b);
        mv.visitInsn(IADD);
        if (k == 1) {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitFieldInsn(GETSTATIC, owner, "SC_" + pc,
                    "Lorg/luava/runtime/jit/JitRuntime$SelfCache;");
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "selfMethodCached",
                    "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;Lorg/luava/runtime/jit/JitRuntime$SelfCache;)Lorg/luava/runtime/LuaValue;",
                    false);
        } else {
            emitGuardObject(mv, c, pc);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, c);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "selfMethod",
                    "(Lorg/luava/runtime/bytecode/LuaClosure;[J[B[Lorg/luava/runtime/LuaValue;ILorg/luava/runtime/LuaValue;)Lorg/luava/runtime/LuaValue;",
                    false);
        }
        mv.visitInsn(DUP);
        Label selfOk = new Label();
        mv.visitJumpInsn(IFNONNULL, selfOk);
        mv.visitInsn(POP);
        emitDeopt(mv, pc);
        mv.visitLabel(selfOk);
        mv.visitVarInsn(ASTORE, 8);
        copyRegTriple(mv, a + 1, b);
        mv.visitVarInsn(ALOAD, 8);
        emitStoreValueReg(mv, a);
    }

    private static void emitGetTabUp(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int pc, LoopOpt activeLoop) {
        mv.visitVarInsn(ALOAD, 1);
        ldcInt(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                "()Lorg/luava/runtime/LuaValue;", false);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc, activeLoop);
        if (c >= 0 && c < proto.constants.length && proto.constants[c] instanceof LuaString) {
            emitLoadStringConst(mv, cgEnv, c);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "tryRawgetStringToStack",
                    "(Lorg/luava/runtime/LuaString;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        } else {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "tryRawgetToStack",
                    "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        }
        Label ok = new Label();
        mv.visitJumpInsn(IFNE, ok);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(ok);
    }

    private static void emitGetTable(
            MethodVisitor mv, int a, int b, int c, int pc, int[][] types, LoopOpt activeLoop) {
        if (isStaticInt(types, pc, c)) {
            emitLoadPlainTableReg(mv, b, pc, activeLoop);
            emitLoadP(mv, c, activeLoop);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetIntToStack",
                    "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
            return;
        }
        Label intKey = new Label();
        Label objKey = new Label();
        Label done = new Label();
        emitLoadTag(mv, c, activeLoop);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, intKey);
        ldcInt(mv, TYPE_OBJECT);
        mv.visitJumpInsn(IF_ICMPEQ, objKey);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(intKey);
        mv.visitInsn(POP);
        emitLoadPlainTableReg(mv, b, pc, activeLoop);
        emitLoadP(mv, c, activeLoop);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetIntToStack",
                "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(objKey);
        emitGuardObject(mv, b, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, c);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "tryRawgetToStack",
                "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        Label okObj = new Label();
        mv.visitJumpInsn(IFNE, okObj);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(okObj);
        mv.visitLabel(done);
    }

    private static void emitGetI(MethodVisitor mv, int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitLoadPlainTableReg(mv, b, pc, activeLoop);
        mv.visitLdcInsn((long) c);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, a);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetIntToStack",
                "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
    }

    private static void emitGetField(MethodVisitor mv, LuaProto proto, CodegenEnv cgEnv,
            int a, int b, int c, int pc, LoopOpt activeLoop) {
        emitGuardObject(mv, b, pc, activeLoop);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, b);
        mv.visitInsn(AALOAD);
        mv.visitVarInsn(ASTORE, 8);
        mv.visitVarInsn(ALOAD, 8);
        emitGuardTableTyped(mv, pc, activeLoop);
        if (c >= 0 && c < proto.constants.length && proto.constants[c] instanceof LuaString) {
            emitLoadStringConst(mv, cgEnv, c);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "tryRawgetStringToStack",
                    "(Lorg/luava/runtime/LuaString;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        } else {
            emitLoadConst(mv, proto, cgEnv, c);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, a);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "tryRawgetToStack",
                    "(Lorg/luava/runtime/LuaValue;[J[B[Lorg/luava/runtime/LuaValue;I)Z", false);
        }
        Label ok = new Label();
        mv.visitJumpInsn(IFNE, ok);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(ok);
    }

    private static void emitGuardTableTyped(MethodVisitor mv, int pc, LoopOpt activeLoop) {
        mv.visitTypeInsn(INSTANCEOF, "org/luava/runtime/LuaTable");
        Label isTable = new Label();
        mv.visitJumpInsn(IFNE, isTable);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(isTable);
        mv.visitVarInsn(ALOAD, 8);
        mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/LuaTable");
    }

    private static void emitGuardObject(MethodVisitor mv, int reg, int pc) {
        emitGuardObject(mv, reg, pc, null);
    }

    private static void emitGuardObject(MethodVisitor mv, int reg, int pc, LoopOpt activeLoop) {
        emitLoadTag(mv, reg, activeLoop);
        ldcInt(mv, TYPE_OBJECT);
        Label ok = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, ok);
        emitDeopt(mv, pc, false, activeLoop);
        mv.visitLabel(ok);
    }

    private static void emitGuardInt(MethodVisitor mv, int reg, int pc) {
        emitGuardInt(mv, reg, pc, null);
    }

    private static void emitGuardInt(MethodVisitor mv, int reg, int pc, LoopOpt activeLoop) {
        if (activeLoop != null && (reg == activeLoop.indRegA || reg == activeLoop.indRegA + 3)) {
            return;
        }
        emitLoadTag(mv, reg, activeLoop);
        ldcInt(mv, TYPE_INT);
        Label ok = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, ok);
        emitDeopt(mv, pc, false, activeLoop);
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
        emitDeopt(mv, pc, false, null);
    }

    private static void emitDeopt(MethodVisitor mv, int pc, boolean structural) {
        emitDeopt(mv, pc, structural, null);
    }

    private static void emitDeopt(MethodVisitor mv, int pc, boolean structural, LoopOpt activeLoop) {
        if (activeLoop != null) {
            emitFlushLoopStateForDeopt(mv, activeLoop);
        }
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

    private record CodegenEnv(
            String owner,
            boolean foldConsts,
            boolean[] jumpTargets,
            LoopPlan loopPlan) {}

    private static final class LoopOpt {
        final int prepPc;
        final int bodyStart;
        final int loopPc;
        final int indRegA;
        final int tblReg;
        final int accReg;
        final boolean presizeSequentialTbl;
        final boolean[] captured;
        final long[] liveIn;
        final int slotI;
        final int slotRem;
        final int slotStep;
        final int slotTbl;
        final int slotAccTag;
        final int slotAccBits;
        final int slotIdx0;

        LoopOpt(int prepPc, int bodyStart, int loopPc, int indRegA, int tblReg, int accReg,
                boolean presizeSequentialTbl, boolean[] captured, long[] liveIn, int slotBase) {
            this.prepPc = prepPc;
            this.bodyStart = bodyStart;
            this.loopPc = loopPc;
            this.indRegA = indRegA;
            this.tblReg = tblReg;
            this.accReg = accReg;
            this.presizeSequentialTbl = presizeSequentialTbl;
            this.captured = captured;
            this.liveIn = liveIn;
            this.slotI = slotBase;
            this.slotRem = slotBase + 2;
            this.slotStep = slotBase + 4;
            this.slotTbl = slotBase + 6;
            this.slotAccTag = slotBase + 7;
            this.slotAccBits = slotBase + 8;
            this.slotIdx0 = slotBase + 10;
        }
    }

    private static final class LoopPlan {
        final int slotBase;
        final LoopOpt[] byPrepPc;
        final LoopOpt[] byBodyStart;
        final LoopOpt[] byLoopPc;
        final LoopOpt[] activeAtPc;

        LoopPlan(int slotBase, LoopOpt[] byPrepPc, LoopOpt[] byBodyStart,
                LoopOpt[] byLoopPc, LoopOpt[] activeAtPc) {
            this.slotBase = slotBase;
            this.byPrepPc = byPrepPc;
            this.byBodyStart = byBodyStart;
            this.byLoopPc = byLoopPc;
            this.activeAtPc = activeAtPc;
        }
    }

    private static void emitLoopSlotsZeroInit(MethodVisitor mv, LoopPlan plan) {
        if (plan == null) {
            return;
        }
        int s = plan.slotBase;
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, s);
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, s + 2);
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, s + 4);
        mv.visitInsn(ACONST_NULL);
        mv.visitVarInsn(ASTORE, s + 6);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, s + 7);
        mv.visitInsn(LCONST_0);
        mv.visitVarInsn(LSTORE, s + 8);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, s + 10);
    }

    private static void emitLoopOsrInit(MethodVisitor mv, LoopOpt lp) {
        emitLoadP(mv, lp.indRegA);
        mv.visitVarInsn(LSTORE, lp.slotI);
        emitLoadP(mv, lp.indRegA + 1);
        mv.visitVarInsn(LSTORE, lp.slotRem);
        emitLoadP(mv, lp.indRegA + 2);
        mv.visitVarInsn(LSTORE, lp.slotStep);
        if (lp.tblReg >= 0) {
            mv.visitVarInsn(ALOAD, 3);
            mv.visitVarInsn(ALOAD, 4);
            emitIndex(mv, lp.tblReg);
            mv.visitMethodInsn(INVOKESTATIC, "org/luava/runtime/jit/JitRuntime", "plainTableOrNull",
                    "([B[Lorg/luava/runtime/LuaValue;I)Lorg/luava/runtime/LuaTable;", false);
            mv.visitVarInsn(ASTORE, lp.slotTbl);
        } else {
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, lp.slotTbl);
        }
        if (lp.accReg >= 0) {
            mv.visitVarInsn(ALOAD, 3);
            emitIndex(mv, lp.accReg);
            mv.visitInsn(BALOAD);
            mv.visitVarInsn(ISTORE, lp.slotAccTag);
            emitLoadP(mv, lp.accReg);
            mv.visitVarInsn(LSTORE, lp.slotAccBits);
        } else {
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, lp.slotAccTag);
            mv.visitInsn(LCONST_0);
            mv.visitVarInsn(LSTORE, lp.slotAccBits);
        }
        if (lp.presizeSequentialTbl) {
            Label skipPresize = new Label();
            mv.visitVarInsn(ALOAD, lp.slotTbl);
            mv.visitJumpInsn(IFNULL, skipPresize);
            mv.visitVarInsn(ALOAD, lp.slotTbl);
            mv.visitVarInsn(LLOAD, lp.slotI);
            mv.visitVarInsn(LLOAD, lp.slotRem);
            mv.visitVarInsn(LLOAD, lp.slotStep);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "reserveSequentialCapacity",
                    "(JJJ)V", false);
            mv.visitLabel(skipPresize);
        }
    }

    private static void emitFlushLoopStateOnExit(MethodVisitor mv, LoopOpt lp) {
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA);
        mv.visitVarInsn(LLOAD, lp.slotI);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA + 1);
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA + 3);
        mv.visitVarInsn(LLOAD, lp.slotI);
        mv.visitInsn(LASTORE);
        emitFlushAccIfValid(mv, lp);
    }

    private static void emitFlushLoopStateForDeopt(MethodVisitor mv, LoopOpt lp) {
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA);
        mv.visitVarInsn(LLOAD, lp.slotI);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA + 1);
        mv.visitVarInsn(LLOAD, lp.slotRem);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.indRegA + 3);
        mv.visitVarInsn(LLOAD, lp.slotI);
        mv.visitInsn(LASTORE);
        emitFlushAccIfValid(mv, lp);
    }

    private static void emitFlushAccIfValid(MethodVisitor mv, LoopOpt lp) {
        if (lp.accReg < 0) {
            return;
        }
        Label skip = new Label();
        Label write = new Label();
        mv.visitVarInsn(ILOAD, lp.slotAccTag);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPEQ, write);
        mv.visitVarInsn(ILOAD, lp.slotAccTag);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, skip);
        mv.visitLabel(write);
        mv.visitVarInsn(ALOAD, 2);
        emitIndex(mv, lp.accReg);
        mv.visitVarInsn(LLOAD, lp.slotAccBits);
        mv.visitInsn(LASTORE);
        mv.visitVarInsn(ALOAD, 3);
        emitIndex(mv, lp.accReg);
        mv.visitVarInsn(ILOAD, lp.slotAccTag);
        mv.visitInsn(BASTORE);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, lp.accReg);
        mv.visitInsn(ACONST_NULL);
        mv.visitInsn(AASTORE);
        mv.visitLabel(skip);
    }

    private static void emitAccArithRR(MethodVisitor mv, int b, int c, int pc,
            int jvmIntOp, int jvmFloatOp, int[][] types, LoopOpt lp) {
        if (isStaticInt(types, pc, b) && isStaticInt(types, pc, c)) {
            emitLoadP(mv, b, lp);
            emitLoadP(mv, c, lp);
            mv.visitInsn(jvmIntOp);
            mv.visitVarInsn(LSTORE, lp.slotAccBits);
            ldcInt(mv, TYPE_INT);
            mv.visitVarInsn(ISTORE, lp.slotAccTag);
            return;
        }
        Label bNotInt = new Label();
        Label bFloat = new Label();
        Label mixedFromBInt = new Label();
        Label mixedFromBFloat = new Label();
        Label storeFloat = new Label();
        Label done = new Label();

        emitLoadTag(mv, b, lp);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, bNotInt);
        mv.visitInsn(POP);

        emitLoadTag(mv, c, lp);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, mixedFromBInt);
        mv.visitInsn(POP);
        emitLoadP(mv, b, lp);
        emitLoadP(mv, c, lp);
        mv.visitInsn(jvmIntOp);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        ldcInt(mv, TYPE_INT);
        mv.visitVarInsn(ISTORE, lp.slotAccTag);
        mv.visitJumpInsn(GOTO, done);

        mv.visitLabel(mixedFromBInt);
        ldcInt(mv, TYPE_FLOAT);
        Label cOkFloat = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, cOkFloat);
        emitDeopt(mv, pc, false, lp);
        mv.visitLabel(cOkFloat);
        emitLoadP(mv, b, lp);
        mv.visitInsn(L2D);
        emitLoadP(mv, c, lp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitInsn(jvmFloatOp);
        mv.visitJumpInsn(GOTO, storeFloat);

        mv.visitLabel(bNotInt);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPEQ, bFloat);
        emitDeopt(mv, pc, false, lp);

        mv.visitLabel(bFloat);
        emitLoadTag(mv, c, lp);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitJumpInsn(IF_ICMPNE, mixedFromBFloat);
        mv.visitInsn(POP);
        emitLoadP(mv, b, lp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        emitLoadP(mv, c, lp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitInsn(jvmFloatOp);
        mv.visitJumpInsn(GOTO, storeFloat);

        mv.visitLabel(mixedFromBFloat);
        ldcInt(mv, TYPE_INT);
        Label cOkInt = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, cOkInt);
        emitDeopt(mv, pc, false, lp);
        mv.visitLabel(cOkInt);
        emitLoadP(mv, b, lp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        emitLoadP(mv, c, lp);
        mv.visitInsn(L2D);
        mv.visitInsn(jvmFloatOp);

        mv.visitLabel(storeFloat);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitVarInsn(ISTORE, lp.slotAccTag);
        mv.visitLabel(done);
    }

    private static void emitAccArithK(MethodVisitor mv, int b, long kv, int pc,
            int jvmIntOp, int jvmFloatOp, int[][] types, LoopOpt lp) {
        if (isStaticInt(types, pc, b)) {
            emitLoadP(mv, b, lp);
            mv.visitLdcInsn(kv);
            mv.visitInsn(jvmIntOp);
            mv.visitVarInsn(LSTORE, lp.slotAccBits);
            ldcInt(mv, TYPE_INT);
            mv.visitVarInsn(ISTORE, lp.slotAccTag);
            return;
        }
        Label floatLane = new Label();
        Label done = new Label();
        emitLoadTag(mv, b, lp);
        mv.visitInsn(DUP);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, floatLane);
        mv.visitInsn(POP);
        emitLoadP(mv, b, lp);
        mv.visitLdcInsn(kv);
        mv.visitInsn(jvmIntOp);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        ldcInt(mv, TYPE_INT);
        mv.visitVarInsn(ISTORE, lp.slotAccTag);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(floatLane);
        ldcInt(mv, TYPE_FLOAT);
        Label okFloat = new Label();
        mv.visitJumpInsn(IF_ICMPEQ, okFloat);
        emitDeopt(mv, pc, false, lp);
        mv.visitLabel(okFloat);
        emitLoadP(mv, b, lp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "longBitsToDouble", "(J)D", false);
        mv.visitLdcInsn((double) kv);
        mv.visitInsn(jvmFloatOp);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "doubleToRawLongBits", "(D)J", false);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        ldcInt(mv, TYPE_FLOAT);
        mv.visitVarInsn(ISTORE, lp.slotAccTag);
        mv.visitLabel(done);
    }

    private static void emitAccAddI(
            MethodVisitor mv, int b, int sc, int pc, int[][] types, LoopOpt lp) {
        if (!isStaticInt(types, pc, b)) {
            emitGuardInt(mv, b, pc, lp);
        }
        emitLoadP(mv, b, lp);
        mv.visitLdcInsn((long) sc);
        mv.visitInsn(LADD);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        ldcInt(mv, TYPE_INT);
        mv.visitVarInsn(ISTORE, lp.slotAccTag);
    }

    private static boolean tryEmitFusedIntArithSetTable(
            MethodVisitor mv, LuaProto proto, int pc, int[][] types, LoopOpt lp) {
        int inst0 = proto.code[pc];
        int inst1 = proto.code[pc + 1];
        if (Instruction.getOp(inst1) != OpCode.OP_SETTABLE || Instruction.getk(inst1) != 0) {
            return false;
        }
        int tblReg = Instruction.getA(inst1);
        int keyReg = Instruction.getB(inst1);
        int valReg = Instruction.getC(inst1);
        int op0 = Instruction.getOp(inst0);
        int a0 = Instruction.getA(inst0);
        if (a0 != valReg || a0 == lp.indRegA || a0 == lp.indRegA + 3 || a0 == lp.accReg
                || a0 == tblReg || a0 == keyReg
                || (a0 < lp.captured.length && lp.captured[a0])
                || !isStaticInt(types, pc, keyReg)) {
            return false;
        }
        int b0 = Instruction.getB(inst0);
        int c0 = Instruction.getC(inst0);
        int jvmOp;
        long kConst = 0L;
        boolean hasK = false;
        switch (op0) {
            case OpCode.OP_ADD -> {
                if (!isStaticInt(types, pc, b0) || !isStaticInt(types, pc, c0)) return false;
                jvmOp = LADD;
            }
            case OpCode.OP_SUB -> {
                if (!isStaticInt(types, pc, b0) || !isStaticInt(types, pc, c0)) return false;
                jvmOp = LSUB;
            }
            case OpCode.OP_MUL -> {
                if (!isStaticInt(types, pc, b0) || !isStaticInt(types, pc, c0)) return false;
                jvmOp = LMUL;
            }
            case OpCode.OP_ADDI -> {
                if (!isStaticInt(types, pc, b0)) return false;
                jvmOp = LADD;
                kConst = Instruction.getsC(inst0);
                hasK = true;
            }
            case OpCode.OP_ADDK, OpCode.OP_SUBK, OpCode.OP_MULK -> {
                if (!isStaticInt(types, pc, b0) || !(proto.constants[c0] instanceof LuaInteger li)) return false;
                jvmOp = (op0 == OpCode.OP_ADDK) ? LADD : (op0 == OpCode.OP_SUBK ? LSUB : LMUL);
                kConst = li.toLong();
                hasK = true;
            }
            default -> {
                return false;
            }
        }
        if (!isTempDeadAt(lp, pc + 2, valReg)) {
            return false;
        }
        Label tblOk = new Label();
        if (tblReg == lp.tblReg) {
            mv.visitVarInsn(ALOAD, lp.slotTbl);
            mv.visitJumpInsn(IFNONNULL, tblOk);
            // Materialize valReg before deopting at SETTABLE (pc + 1) so interpreter sees exact R[valReg].
            mv.visitVarInsn(ALOAD, 2);
            emitIndex(mv, valReg);
            emitLoadP(mv, b0, lp);
            if (hasK) {
                mv.visitLdcInsn(kConst);
            } else {
                emitLoadP(mv, c0, lp);
            }
            mv.visitInsn(jvmOp);
            mv.visitInsn(LASTORE);
            emitTagIntNull(mv, valReg);
            emitDeopt(mv, pc + 1, false, lp);
            mv.visitLabel(tblOk);
            mv.visitVarInsn(ALOAD, lp.slotTbl);
        } else {
            return false;
        }
        emitLoadP(mv, keyReg, lp);
        emitLoadP(mv, b0, lp);
        if (hasK) {
            mv.visitLdcInsn(kConst);
        } else {
            emitLoadP(mv, c0, lp);
        }
        mv.visitInsn(jvmOp);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawsetIntLong",
                "(JJ)V", false);
        return true;
    }

    private static boolean tryEmitFusedGetTableAccArith(
            MethodVisitor mv, LuaProto proto, int pc, int[][] types, LoopOpt lp) {
        if (lp.accReg < 0 || lp.tblReg < 0) {
            return false;
        }
        int inst0 = proto.code[pc];
        int inst1 = proto.code[pc + 1];
        if (Instruction.getOp(inst0) != OpCode.OP_GETTABLE) {
            return false;
        }
        int tmpReg = Instruction.getA(inst0);
        int tblReg = Instruction.getB(inst0);
        int keyReg = Instruction.getC(inst0);
        if (tblReg != lp.tblReg || !isStaticInt(types, pc, keyReg)
                || tmpReg == lp.accReg || tmpReg == lp.indRegA || tmpReg == lp.indRegA + 3
                || (tmpReg < lp.captured.length && lp.captured[tmpReg])) {
            return false;
        }
        int op1 = Instruction.getOp(inst1);
        if (op1 != OpCode.OP_ADD && op1 != OpCode.OP_SUB && op1 != OpCode.OP_MUL) {
            return false;
        }
        int dst1 = Instruction.getA(inst1);
        int b1 = Instruction.getB(inst1);
        int c1 = Instruction.getC(inst1);
        if (dst1 != lp.accReg || !((b1 == lp.accReg && c1 == tmpReg) || (c1 == lp.accReg && b1 == tmpReg))) {
            return false;
        }
        if (!isTempDeadAt(lp, pc + 2, tmpReg)) {
            return false;
        }
        int jvmIntOp = (op1 == OpCode.OP_ADD) ? LADD : (op1 == OpCode.OP_SUB ? LSUB : LMUL);
        int jvmFloatOp = (op1 == OpCode.OP_ADD) ? DADD : (op1 == OpCode.OP_SUB ? DSUB : DMUL);

        Label tblOk = new Label();
        mv.visitVarInsn(ALOAD, lp.slotTbl);
        mv.visitJumpInsn(IFNONNULL, tblOk);
        emitDeopt(mv, pc, false, lp);
        mv.visitLabel(tblOk);

        emitLoadP(mv, keyReg, lp);
        mv.visitVarInsn(LSTORE, 6);

        Label slowPath = new Label();
        Label done = new Label();

        mv.visitVarInsn(ILOAD, lp.slotAccTag);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, slowPath);

        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(LCONST_1);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFLT, slowPath);

        mv.visitVarInsn(LLOAD, 6);
        mv.visitVarInsn(ALOAD, lp.slotTbl);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "plainIntArrayLen", "()I", false);
        mv.visitInsn(I2L);
        mv.visitInsn(LCMP);
        mv.visitJumpInsn(IFGT, slowPath);

        mv.visitVarInsn(LLOAD, 6);
        mv.visitInsn(L2I);
        mv.visitInsn(ICONST_1);
        mv.visitInsn(ISUB);
        mv.visitVarInsn(ISTORE, lp.slotIdx0);

        mv.visitVarInsn(ALOAD, lp.slotTbl);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawArrT", "()[B", false);
        mv.visitVarInsn(ILOAD, lp.slotIdx0);
        mv.visitInsn(BALOAD);
        ldcInt(mv, TYPE_INT);
        mv.visitJumpInsn(IF_ICMPNE, slowPath);

        if (b1 == lp.accReg) {
            mv.visitVarInsn(LLOAD, lp.slotAccBits);
            mv.visitVarInsn(ALOAD, lp.slotTbl);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawArrP", "()[J", false);
            mv.visitVarInsn(ILOAD, lp.slotIdx0);
            mv.visitInsn(LALOAD);
        } else {
            mv.visitVarInsn(ALOAD, lp.slotTbl);
            mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawArrP", "()[J", false);
            mv.visitVarInsn(ILOAD, lp.slotIdx0);
            mv.visitInsn(LALOAD);
            mv.visitVarInsn(LLOAD, lp.slotAccBits);
        }
        mv.visitInsn(jvmIntOp);
        mv.visitVarInsn(LSTORE, lp.slotAccBits);
        mv.visitJumpInsn(GOTO, done);

        mv.visitLabel(slowPath);
        mv.visitVarInsn(ALOAD, lp.slotTbl);
        mv.visitVarInsn(LLOAD, 6);
        mv.visitVarInsn(ALOAD, 2);
        mv.visitVarInsn(ALOAD, 3);
        mv.visitVarInsn(ALOAD, 4);
        emitIndex(mv, tmpReg);
        mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/LuaTable", "rawgetIntToStack",
                "(J[J[B[Lorg/luava/runtime/LuaValue;I)V", false);
        emitAccArithRR(mv, b1, c1, pc + 1, jvmIntOp, jvmFloatOp, types, lp);

        mv.visitLabel(done);
        return true;
    }

    private static boolean isTempDeadAt(LoopOpt lp, int nextPc, int tempReg) {
        return tempReg >= 0 && tempReg < 64
                && nextPc >= 0 && nextPc < lp.liveIn.length
                && (lp.liveIn[nextPc] & (1L << tempReg)) == 0L;
    }

    private static long regBit(int r) {
        return (r >= 0 && r < 64) ? (1L << r) : -1L;
    }

    private static long regRangeBits(int from, int count) {
        if (count <= 0) {
            return 0L;
        }
        if (from < 0 || from + count > 64) {
            return -1L;
        }
        long mask = 0L;
        for (int i = 0; i < count; i++) {
            mask |= (1L << (from + i));
        }
        return mask;
    }

    /**
     * Sound backward register-liveness pass over {@code proto.code} (64-register
     * bitmask; {@code analyzeFull} already bounds {@code maxStackSize <= 64}).
     * Any variable-width or conservative opcode marks all registers live.
     */
    private static long[] computeLiveIn(LuaProto proto) {
        int[] code = proto.code;
        int len = code.length;
        long[] use = new long[len];
        long[] def = new long[len];
        int[][] succs = new int[len][];
        for (int pc = 0; pc < len; pc++) {
            succs[pc] = successors(code, pc);
            int inst = code[pc];
            int op = Instruction.getOp(inst);
            int a = Instruction.getA(inst);
            int b = Instruction.getB(inst);
            int c = Instruction.getC(inst);
            switch (op) {
                case OpCode.OP_LOADI, OpCode.OP_LOADF, OpCode.OP_LOADK,
                        OpCode.OP_LOADFALSE, OpCode.OP_LFALSESKIP, OpCode.OP_LOADTRUE,
                        OpCode.OP_NEWTABLE, OpCode.OP_GETUPVAL, OpCode.OP_GETTABUP,
                        OpCode.OP_CLOSURE -> def[pc] = (a >= 0 && a < 64) ? (1L << a) : 0L;
                case OpCode.OP_LOADNIL, OpCode.OP_CLEANUP -> def[pc] =
                        (a >= 0 && a + b < 64) ? regRangeBits(a, b + 1) : 0L;
                case OpCode.OP_MOVE, OpCode.OP_UNM, OpCode.OP_BNOT, OpCode.OP_NOT, OpCode.OP_LEN,
                        OpCode.OP_ADDI, OpCode.OP_ADDK, OpCode.OP_SUBK, OpCode.OP_MULK,
                        OpCode.OP_DIVK, OpCode.OP_POWK, OpCode.OP_IDIVK, OpCode.OP_MODK,
                        OpCode.OP_BANDK, OpCode.OP_BORK, OpCode.OP_BXORK,
                        OpCode.OP_SHLI, OpCode.OP_SHRI,
                        OpCode.OP_GETI, OpCode.OP_GETFIELD -> {
                    use[pc] = regBit(b);
                    def[pc] = (a >= 0 && a < 64) ? (1L << a) : 0L;
                }
                case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL,
                        OpCode.OP_DIV, OpCode.OP_POW, OpCode.OP_IDIV, OpCode.OP_MOD,
                        OpCode.OP_BAND, OpCode.OP_BOR, OpCode.OP_BXOR,
                        OpCode.OP_SHL, OpCode.OP_SHR, OpCode.OP_GETTABLE -> {
                    use[pc] = regBit(b) | regBit(c);
                    def[pc] = (a >= 0 && a < 64) ? (1L << a) : 0L;
                }
                case OpCode.OP_SELF -> {
                    use[pc] = regBit(b) | (Instruction.getk(inst) == 0 ? regBit(c) : 0L);
                    def[pc] = (a >= 0 && a + 1 < 64) ? ((1L << a) | (1L << (a + 1))) : 0L;
                }
                case OpCode.OP_SETUPVAL -> use[pc] = regBit(a);
                case OpCode.OP_SETTABUP -> use[pc] = Instruction.getk(inst) == 0 ? regBit(c) : 0L;
                case OpCode.OP_SETTABLE -> use[pc] =
                        regBit(a) | regBit(b) | (Instruction.getk(inst) == 0 ? regBit(c) : 0L);
                case OpCode.OP_SETI, OpCode.OP_SETFIELD -> use[pc] =
                        regBit(a) | (Instruction.getk(inst) == 0 ? regBit(c) : 0L);
                case OpCode.OP_SETLIST -> use[pc] = b > 0 ? regRangeBits(a, b + 1) : -1L;
                case OpCode.OP_CONCAT -> {
                    use[pc] = regRangeBits(a, b);
                    def[pc] = (a >= 0 && a < 64) ? (1L << a) : 0L;
                }
                case OpCode.OP_EQ, OpCode.OP_LT, OpCode.OP_LE -> use[pc] = regBit(a) | regBit(b);
                case OpCode.OP_EQK, OpCode.OP_EQI,
                        OpCode.OP_LTI, OpCode.OP_LEI, OpCode.OP_GTI, OpCode.OP_GEI,
                        OpCode.OP_TEST -> use[pc] = regBit(a);
                case OpCode.OP_TESTSET -> use[pc] = regBit(b);
                case OpCode.OP_JMP, OpCode.OP_EXTRAARG, OpCode.OP_RETURN0 -> {}
                case OpCode.OP_RETURN1 -> use[pc] = regBit(a);
                case OpCode.OP_RETURN -> use[pc] = b > 0 ? regRangeBits(a, b - 1) : -1L;
                case OpCode.OP_FORPREP, OpCode.OP_FORLOOP -> use[pc] = regRangeBits(a, 3);
                case OpCode.OP_TFORPREP -> use[pc] = regBit(a + 3);
                case OpCode.OP_TFORCALL -> {
                    use[pc] = regRangeBits(a, 3);
                    int nVars = Math.max(1, c);
                    def[pc] = (a + 4 + nVars <= 64) ? (regRangeBits(a + 4, nVars) | regBit(a + 2)) : 0L;
                }
                case OpCode.OP_TFORLOOP -> use[pc] = regBit(a + 4);
                case OpCode.OP_CALL -> {
                    if (b == 0 || c == 0) {
                        use[pc] = -1L;
                    } else {
                        use[pc] = regRangeBits(a, b);
                        def[pc] = (a + c - 1 <= 64) ? regRangeBits(a, c - 1) : 0L;
                    }
                }
                case OpCode.OP_TAILCALL -> use[pc] = b > 0 ? regRangeBits(a, b) : -1L;
                default -> use[pc] = -1L;
            }
        }
        long[] liveIn = new long[len];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int pc = len - 1; pc >= 0; pc--) {
                long liveOut = 0L;
                for (int s : succs[pc]) {
                    if (s >= 0 && s < len) {
                        liveOut |= liveIn[s];
                    }
                }
                long nextIn = use[pc] | (liveOut & ~def[pc]);
                if (nextIn != liveIn[pc]) {
                    liveIn[pc] = nextIn;
                    changed = true;
                }
            }
        }
        return liveIn;
    }

    private static LoopPlan computeLoopPlan(
            LuaProto proto, int[][] types, boolean[] jumpTargets, int slotBase) {
        int[] code = proto.code;
        int len = code.length;
        if (len < 2 || slotBase < 0) {
            return null;
        }
        int regs = Math.max(proto.maxStackSize, proto.numParams) + 4;
        boolean[] captured = new boolean[regs];
        for (LuaProto child : proto.protos) {
            for (org.luava.runtime.bytecode.UpvalueDesc u : child.upvalues) {
                if (u.inStack && u.index >= 0 && u.index < regs) {
                    captured[u.index] = true;
                }
            }
        }
        long[] liveIn = null;
        LoopOpt[] byPrep = new LoopOpt[len];
        LoopOpt[] byBody = new LoopOpt[len];
        LoopOpt[] byLoop = new LoopOpt[len];
        LoopOpt[] active = new LoopOpt[len];
        boolean any = false;

        for (int prepPc = 0; prepPc < len; prepPc++) {
            int pInst = code[prepPc];
            if (Instruction.getOp(pInst) != OpCode.OP_FORPREP) {
                continue;
            }
            int a = Instruction.getA(pInst);
            int bx = Instruction.getBx(pInst);
            int bodyStart = prepPc + 1;
            int loopPc = prepPc + bx;
            if (loopPc <= bodyStart || loopPc >= len
                    || Instruction.getOp(code[loopPc]) != OpCode.OP_FORLOOP
                    || Instruction.getA(code[loopPc]) != a
                    || Instruction.getBx(code[loopPc]) != bx
                    || a + 3 >= regs
                    || captured[a] || captured[a + 1] || captured[a + 2] || captured[a + 3]) {
                continue;
            }
            boolean ok = true;
            for (int k = bodyStart; k < loopPc; k++) {
                int inst = code[k];
                int op = Instruction.getOp(inst);
                switch (op) {
                    case OpCode.OP_FORPREP, OpCode.OP_FORLOOP,
                            OpCode.OP_TFORPREP, OpCode.OP_TFORCALL, OpCode.OP_TFORLOOP,
                            OpCode.OP_CALL, OpCode.OP_TAILCALL, OpCode.OP_CLOSURE,
                            OpCode.OP_CLOSE, OpCode.OP_TBC,
                            OpCode.OP_RETURN, OpCode.OP_RETURN0, OpCode.OP_RETURN1,
                            OpCode.OP_VARARG -> ok = false;
                    case OpCode.OP_JMP -> {
                        int jt = k + 1 + Instruction.getsJ(inst);
                        if (jt < bodyStart || jt > loopPc) {
                            ok = false;
                        }
                    }
                    default -> {
                        if (isBranch(inst) && k + 2 > loopPc) {
                            ok = false;
                        }
                    }
                }
                if (!ok) break;
                if (writesRegForLoopCheck(inst, a) || writesRegForLoopCheck(inst, a + 1)
                        || writesRegForLoopCheck(inst, a + 2) || writesRegForLoopCheck(inst, a + 3)) {
                    ok = false;
                    break;
                }
                if ((readsReg(inst, a) || readsReg(inst, a + 3))
                        && !supportsPromotedLoopRead(inst, k, types, a, a + 3)) {
                    ok = false;
                    break;
                }
                if (readsReg(inst, a + 1) || readsReg(inst, a + 2)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;

            for (int k = 0; k < len; k++) {
                if (k >= prepPc && k <= loopPc) continue;
                int inst = code[k];
                int op = Instruction.getOp(inst);
                if (op == OpCode.OP_JMP) {
                    int jt = k + 1 + Instruction.getsJ(inst);
                    if (jt >= bodyStart && jt <= loopPc) {
                        ok = false;
                        break;
                    }
                } else if (isBranch(inst) && (k + 2 >= bodyStart && k + 2 <= loopPc)) {
                    ok = false;
                    break;
                }
            }
            if (!ok) continue;

            int tblReg = -1;
            for (int k = bodyStart; k < loopPc; k++) {
                int inst = code[k];
                int op = Instruction.getOp(inst);
                int cand = switch (op) {
                    case OpCode.OP_GETTABLE, OpCode.OP_GETI -> Instruction.getB(inst);
                    case OpCode.OP_SETTABLE, OpCode.OP_SETI -> Instruction.getA(inst);
                    default -> -1;
                };
                if (cand >= 0 && cand < regs && !captured[cand]
                        && cand != a && cand != a + 1 && cand != a + 2 && cand != a + 3) {
                    boolean mutated = false;
                    for (int m = bodyStart; m < loopPc; m++) {
                        if (writesRegForLoopCheck(code[m], cand)) {
                            mutated = true;
                            break;
                        }
                    }
                    if (!mutated) {
                        tblReg = cand;
                        break;
                    }
                }
            }

            int accReg = -1;
            for (int r = 0; r < regs; r++) {
                if (captured[r] || r == tblReg || (r >= a && r <= a + 3)) {
                    continue;
                }
                boolean written = false;
                boolean validAcc = true;
                for (int k = bodyStart; k < loopPc; k++) {
                    int inst = code[k];
                    int op = Instruction.getOp(inst);
                    if (writesRegForLoopCheck(inst, r)) {
                        written = true;
                        if (op != OpCode.OP_ADD && op != OpCode.OP_SUB && op != OpCode.OP_MUL
                                && op != OpCode.OP_ADDI && op != OpCode.OP_ADDK
                                && op != OpCode.OP_SUBK && op != OpCode.OP_MULK) {
                            validAcc = false;
                            break;
                        }
                    }
                    if (readsReg(inst, r) && !supportsPromotedAccRead(inst, r)) {
                        validAcc = false;
                        break;
                    }
                }
                if (written && validAcc) {
                    accReg = r;
                    break;
                }
            }

            boolean presizeSeq = false;
            if (tblReg >= 0) {
                for (int k = bodyStart; k < loopPc; k++) {
                    if (k > bodyStart && jumpTargets[k]) {
                        break;
                    }
                    int inst = code[k];
                    int op = Instruction.getOp(inst);
                    if (op == OpCode.OP_JMP || isBranch(inst)) {
                        break;
                    }
                    if (op == OpCode.OP_SETTABLE && Instruction.getA(inst) == tblReg) {
                        int keyR = Instruction.getB(inst);
                        if (keyR == a || keyR == a + 3) {
                            int valC = Instruction.getC(inst);
                            if (Instruction.getk(inst) == 1) {
                                if (valC >= 0 && valC < proto.constants.length
                                        && !proto.constants[valC].isNil()) {
                                    presizeSeq = true;
                                    break;
                                }
                            } else if (types != null && k < types.length
                                    && valC >= 0 && valC < types[k].length
                                    && (types[k][valC] == T_INT || types[k][valC] == T_NUM)) {
                                presizeSeq = true;
                                break;
                            }
                        }
                    }
                }
            }
            if (liveIn == null) {
                liveIn = computeLiveIn(proto);
            }
            LoopOpt lp = new LoopOpt(
                    prepPc, bodyStart, loopPc, a, tblReg, accReg, presizeSeq, captured, liveIn, slotBase);
            byPrep[prepPc] = lp;
            byBody[bodyStart] = lp;
            byLoop[loopPc] = lp;
            for (int k = bodyStart; k < loopPc; k++) {
                active[k] = lp;
            }
            any = true;
        }
        return any ? new LoopPlan(slotBase, byPrep, byBody, byLoop, active) : null;
    }

    private static boolean writesRegForLoopCheck(int inst, int reg) {
        int op = Instruction.getOp(inst);
        return switch (op) {
            case OpCode.OP_EXTRAARG, OpCode.OP_JMP,
                    OpCode.OP_EQ, OpCode.OP_EQK, OpCode.OP_EQI,
                    OpCode.OP_LT, OpCode.OP_LE,
                    OpCode.OP_LTI, OpCode.OP_LEI, OpCode.OP_GTI, OpCode.OP_GEI,
                    OpCode.OP_TEST, OpCode.OP_LFALSESKIP -> false;
            default -> writesReg(inst, reg);
        };
    }

    private static boolean supportsPromotedLoopRead(int inst, int pc, int[][] types, int ind0, int ind3) {
        int op = Instruction.getOp(inst);
        int a = Instruction.getA(inst);
        int b = Instruction.getB(inst);
        int c = Instruction.getC(inst);
        return switch (op) {
            case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL,
                    OpCode.OP_DIV, OpCode.OP_POW, OpCode.OP_IDIV, OpCode.OP_MOD,
                    OpCode.OP_BAND, OpCode.OP_BOR, OpCode.OP_BXOR,
                    OpCode.OP_SHL, OpCode.OP_SHR,
                    OpCode.OP_ADDI, OpCode.OP_ADDK, OpCode.OP_SUBK, OpCode.OP_MULK,
                    OpCode.OP_DIVK, OpCode.OP_POWK, OpCode.OP_IDIVK, OpCode.OP_MODK,
                    OpCode.OP_BANDK, OpCode.OP_BORK, OpCode.OP_BXORK,
                    OpCode.OP_SHLI, OpCode.OP_SHRI,
                    OpCode.OP_UNM, OpCode.OP_BNOT,
                    OpCode.OP_EQI, OpCode.OP_LTI, OpCode.OP_LEI, OpCode.OP_GTI, OpCode.OP_GEI,
                    OpCode.OP_LT, OpCode.OP_LE, OpCode.OP_EQ, OpCode.OP_EQK -> true;
            case OpCode.OP_GETTABLE -> (b != ind0 && b != ind3);
            case OpCode.OP_SETTABLE -> (a != ind0 && a != ind3)
                    && (Instruction.getk(inst) == 1 || (c != ind0 && c != ind3) || isStaticInt(types, pc, c));
            case OpCode.OP_SETI -> (a != ind0 && a != ind3)
                    && (Instruction.getk(inst) == 1 || (c != ind0 && c != ind3) || isStaticInt(types, pc, c));
            default -> false;
        };
    }

    private static boolean supportsPromotedAccRead(int inst, int accReg) {
        int op = Instruction.getOp(inst);
        return switch (op) {
            case OpCode.OP_ADD, OpCode.OP_SUB, OpCode.OP_MUL,
                    OpCode.OP_DIV, OpCode.OP_POW, OpCode.OP_IDIV, OpCode.OP_MOD,
                    OpCode.OP_ADDI, OpCode.OP_ADDK, OpCode.OP_SUBK, OpCode.OP_MULK,
                    OpCode.OP_DIVK, OpCode.OP_POWK,
                    OpCode.OP_EQI, OpCode.OP_LTI, OpCode.OP_LEI, OpCode.OP_GTI, OpCode.OP_GEI,
                    OpCode.OP_LT, OpCode.OP_LE, OpCode.OP_EQ, OpCode.OP_EQK -> true;
            default -> false;
        };
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
    private static boolean writesFreshTable(LuaProto proto, int[] code, int pc, int reg, boolean[] jumpTargets) {
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
        for (int t = def + 1; t < pc; t++) {
            if (jumpTargets[t]) {
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
     * Verifies straight-line upvalue load from {@code def} to {@code callPc}
     * without intervening branches, reads, or clobbering writes.
     */
    private static boolean isFusedUpvalueDef(int[] code, int def, int callPc, int funcReg, boolean[] jumpTargets) {
        if (def < 0 || Instruction.getOp(code[def]) != OpCode.OP_GETUPVAL) {
            return false;
        }
        for (int i = def; i < callPc; i++) {
            int op = Instruction.getOp(code[i]);
            if (op == OpCode.OP_JMP || isBranch(code[i])) {
                return false;
            }
        }
        for (int t = def + 1; t < callPc; t++) {
            if (jumpTargets[t]) {
                return false;
            }
        }
        for (int i = def + 1; i < callPc; i++) {
            if (readsReg(code[i], funcReg) || writesReg(code[i], funcReg)) {
                return false;
            }
        }
        return true;
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

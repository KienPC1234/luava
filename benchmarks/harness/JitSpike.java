import org.luava.runtime.*;
import org.luava.runtime.bytecode.*;
import org.luava.runtime.eval.Upvalue;
import org.objectweb.asm.*;
import java.lang.invoke.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Feasibility spike for a Lua-bytecode -> JVM-bytecode JIT.
 *
 * NOT a production JIT: it translates the integer-only opcode subset used by
 * the fib kernel (LEI/LTI/JMP/RETURN0/RETURN1/GETUPVAL/SUBK/ADD/ADDI/CALL)
 * into one JVM static method operating on Luava's raw register stacks, then
 * loads it as a hidden class. The point is to answer one question with a
 * number: if a translator (not hand-written code) emits direct static
 * recursive calls, how close to the machine ceiling does Luava get on fib?
 */
public class JitSpike {
    static LuaProto targetProto;
    static String clsName;

    public static void main(String[] args) throws Throwable {
        int n = 35;
        // ---- compile the Lua source and grab the fib proto (proto #1)
        LuaState st = new LuaState();
        String code = Files.readString(Path.of("/data/luava/benchmarks/lua/02_fibonacci.lua"));
        LuaClosure top = (LuaClosure) st.compile(code);
        targetProto = top.proto.protos[0];
        System.out.println("fib proto: params=" + targetProto.numParams + " maxStack=" + targetProto.maxStackSize
                + " len=" + targetProto.code.length);

        // ---- interpreter baseline (same chunk, warmed)
        long bi = benchInterp(st, code, n);
        System.out.println("interpreter fib(" + n + "): " + bi + " ms");

        // ---- translate + load via hidden class
        Hidden hid = build(targetProto);
        MethodHandle mh = MethodHandles.lookup().findStatic(hid.cls, "exec",
                MethodType.methodType(long.class, Object[].class, long[].class, byte[].class, LuaValue[].class, int.class));

        // closure carrying the self-reference as upvalue 0
        LuaClosure[] self = new LuaClosure[1];
        // build a closure whose upvals[0] points at itself
        Upvalue[] ups = new Upvalue[targetProto.upvalues.length];
        LuaClosure fibCl = makeSelfClosure(targetProto, ups, st);
        ups[0] = new Upvalue("fib", fibCl);

        long[] p0 = new long[512];
        p0[0] = n; // register R[base+0] holds the parameter n
        long r = (long) mh.invokeExact((Object[]) fibCl.upvals, p0, new byte[512], new LuaValue[512], 0);
        if (r != 9227465L) { System.out.println("JIT WRONG: " + r); return; }

        long bj = benchJit(mh, fibCl, n);
        System.out.println("jit fib(" + n + "): " + bj + " ms   (" + String.format("%.1f", (double) bi / bj) + "x vs interpreter)");
    }

    static long benchInterp(LuaState st, String code, int n) {
        for (int i = 0; i < 5; i++) st.eval(code);
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long t = System.nanoTime();
            st.eval(code);
            best = Math.min(best, (System.nanoTime() - t) / 1_000_000);
        }
        return best;
    }

    static long benchJit(MethodHandle mh, LuaClosure fib, int n) throws Throwable {
        Object[] up = fib.upvals;
        // warm
        for (int i = 0; i < 200; i++) {
            long[] pw = new long[512];
            pw[0] = n;
            long v = (long) mh.invokeExact(up, pw, new byte[512], new LuaValue[512], 0);
            if (v < 0) throw new Error();
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 5; i++) {
            long[] p = new long[512];
            byte[] t = new byte[512];
            LuaValue[] o = new LuaValue[512];
            p[0] = n;
            long s = System.nanoTime();
            long v = (long) mh.invokeExact(up, p, t, o, 0);
            best = Math.min(best, (System.nanoTime() - s) / 1_000_000);
            if (v != 9227465L) throw new Error("bad " + v);
        }
        return best;
    }

    // A closure whose upvals[] array is shared with the caller (ups).
    static LuaClosure makeSelfClosure(LuaProto proto, Upvalue[] ups, LuaState st) throws Exception {
        // LuaClosure(proto, upvals, env, state) copies the array by reference.
        return new LuaClosure(proto, ups, null, st);
    }

    record Hidden(Class<?> cls) {}

    // ---------------------------------------------------------------------
    // Translator: emits static long exec(Object[] upvals, long[] p, byte[] t,
    // LuaValue[] o, int base). Integer-only specialization; the benchmark
    // never hits a non-int operand.
    // ---------------------------------------------------------------------
    static Hidden build(LuaProto proto) throws Throwable {
        clsName = "FibJit";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V21, ACC_PUBLIC | ACC_FINAL, clsName, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, "exec",
                "([Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J", null, null);
        mv.visitCode();

        int[] code = proto.code;
        int len = code.length;
        Label[] labels = new Label[len];
        for (int i = 0; i < len; i++) labels[i] = new Label();

        // local layout:
        // 0 = upvals, 1 = p, 2 = t, 3 = o, 4 = base, 5 = result(long scratch)
        for (int pc = 0; pc < len; pc++) {
            mv.visitLabel(labels[pc]);
            int inst = code[pc];
            int op = (inst >>> Instruction.POS_OP) & Instruction.MASK_OP;
            int a = (inst >>> Instruction.POS_A) & Instruction.MASK_A;
            int b = (inst >>> Instruction.POS_B) & Instruction.MASK_B;
            int c = (inst >>> Instruction.POS_C) & Instruction.MASK_C;

            switch (op) {
                case OpCode.OP_GETUPVAL -> {
                    // o[base+a] = upvals[b].getValue()  (Upvalue is not a
                    // LuaValue, so unwrap; mirrors BytecodeVM's GETUPVAL)
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ILOAD, 4);
                    ldc(mv, a);
                    mv.visitInsn(IADD);
                    mv.visitVarInsn(ALOAD, 0);
                    ldc(mv, b);
                    mv.visitInsn(AALOAD);
                    mv.visitTypeInsn(CHECKCAST, "org/luava/runtime/eval/Upvalue");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "org/luava/runtime/eval/Upvalue", "getValue",
                            "()Lorg/luava/runtime/LuaValue;", false);
                    mv.visitInsn(AASTORE);
                }
                case OpCode.OP_SUBK -> {
                    // p[base+a] = p[base+b] - ((LuaInteger)k[c])
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, a); mv.visitInsn(IADD);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, b); mv.visitInsn(IADD);
                    mv.visitInsn(LALOAD);
                    // constant value baked in at translation time
                    long kv = ((LuaInteger) proto.constants[c]).toLong();
                    mv.visitLdcInsn(kv);
                    mv.visitInsn(LSUB);
                    mv.visitInsn(LASTORE);
                }
                case OpCode.OP_ADD -> {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, a); mv.visitInsn(IADD);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, b); mv.visitInsn(IADD);
                    mv.visitInsn(LALOAD);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, c); mv.visitInsn(IADD);
                    mv.visitInsn(LALOAD);
                    mv.visitInsn(LADD);
                    mv.visitInsn(LASTORE);
                }
                case OpCode.OP_LEI -> {
                    // if (p[base+a] <= sB) skip next instruction (JMP)
                    int sb = Instruction.getsB(inst);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, a); mv.visitInsn(IADD);
                    mv.visitInsn(LALOAD);
                    mv.visitLdcInsn((long) sb);
                    Label notTaken = new Label();
                    mv.visitInsn(LCMP);
                    mv.visitJumpInsn(IFGT, notTaken);
                    // cond held -> pc++ (skip JMP at pc+1)
                    Label after = new Label();
                    mv.visitJumpInsn(GOTO, labels[Math.min(pc + 2, len - 1)]);
                    mv.visitLabel(notTaken);
                    mv.visitJumpInsn(GOTO, labels[pc + 1]);
                    mv.visitLabel(after);
                }
                case OpCode.OP_JMP -> {
                    int sj = Instruction.getsJ(inst);
                    mv.visitJumpInsn(GOTO, labels[pc + 1 + sj]);
                }
                case OpCode.OP_CALL -> {
                    // func at o[base+a]; monomorphic self/call. Spike: assume
                    // the callee is the same proto (fib self-recursion) and
                    // emit a direct invokestatic, else fall back to the VM.
                    int newBase = a + 1; // callee base relative to caller base
                    // callee register window begins at base+a+1
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitVarInsn(ALOAD, 3);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, newBase); mv.visitInsn(IADD);
                    mv.visitMethodInsn(INVOKESTATIC, clsName, "exec",
                            "([Ljava/lang/Object;[J[B[Lorg/luava/runtime/LuaValue;I)J", false);
                    mv.visitVarInsn(LSTORE, 5);
                    // write result to R[base+a]
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, a); mv.visitInsn(IADD);
                    mv.visitVarInsn(LLOAD, 5);
                    mv.visitInsn(LASTORE);
                }
                case OpCode.OP_RETURN1 -> {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ILOAD, 4); ldc(mv, a); mv.visitInsn(IADD);
                    mv.visitInsn(LALOAD);
                    mv.visitInsn(LRETURN);
                }
                case OpCode.OP_RETURN0 -> {
                    mv.visitInsn(LCONST_0);
                    mv.visitInsn(LRETURN);
                }
                default -> throw new UnsupportedOperationException("op " + OpCode.getOpName(op));
            }
        }
        // fallthrough safety
        mv.visitInsn(LCONST_0);
        mv.visitInsn(LRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();

        byte[] bytes = cw.toByteArray();
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> cls = l.defineHiddenClass(bytes, true, MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
        return new Hidden(cls);
    }

    static void ldc(MethodVisitor mv, int v) { mv.visitLdcInsn(v); }
}

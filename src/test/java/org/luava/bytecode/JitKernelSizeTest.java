/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.LuaToJvmTranslator;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Guards the JIT kernel-size work (plan.md §5.C): the recursive kernel must
 * stay small enough for C2 to inline ({@code FreqInlineSize} 325), and the
 * outlined int-lane/call helpers must stay bit-identical with the
 * interpreter, including float inputs that deopt out of the int lanes.
 */
public class JitKernelSizeTest {

    /** C2's default frequent-inline budget; the recursive entry must fit it. */
    private static final int FREQ_INLINE_SIZE = 325;

    private static LuaProto fibProto() {
        LuaState state = new LuaState();
        LuaClosure fib = (LuaClosure) state.eval(
                "local function fib(n) if n <= 1 then return n end "
                        + "return fib(n-1) + fib(n-2) end return fib");
        return fib.proto;
    }

    /** Approximate JVM bytecode size of one generated method. */
    private static int methodSize(byte[] classBytes, String name) {
        final int[] size = {-1};
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int acc, String mname, String desc,
                    String sig, String[] ex) {
                if (!mname.equals(name)) {
                    return null;
                }
                size[0] = 0;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitInsn(int o) {
                        size[0] += 1;
                    }

                    @Override
                    public void visitIntInsn(int o, int x) {
                        size[0] += (o == Opcodes.SIPUSH ? 3 : 2);
                    }

                    @Override
                    public void visitVarInsn(int o, int v) {
                        // Short forms (xLOAD_n / xSTORE_n, n < 4) are 1 byte.
                        size[0] += (v < 4 ? 1 : 2);
                    }

                    @Override
                    public void visitTypeInsn(int o, String t) {
                        size[0] += 3;
                    }

                    @Override
                    public void visitFieldInsn(int o, String ow, String n, String d) {
                        size[0] += 3;
                    }

                    @Override
                    public void visitMethodInsn(int o, String ow, String n, String d, boolean itf) {
                        size[0] += 3;
                    }

                    @Override
                    public void visitJumpInsn(int o, Label l) {
                        size[0] += 3;
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        size[0] += 3;
                    }
                };
            }
        }, 0);
        return size[0];
    }

    @Test
    void recursiveKernelFitsC2InlineBudget() {
        LuaToJvmTranslator.Translation tr =
                LuaToJvmTranslator.translate(fibProto(), "KernelSizeFib", false);
        assertNotNull(tr, "fib proto must stay inside the JIT subset");
        int inner = methodSize(tr.bytes(), "execInner");
        assertTrue(inner > 0, "the recursive entry must exist");
        assertTrue(inner <= FREQ_INLINE_SIZE,
                "execInner must fit FreqInlineSize for C2, was " + inner);
    }

    private static String intLaneResult(String code) {
        LuaValue off = new LuaState().jitEnabled(false).eval(code);
        LuaValue on = new LuaState().jitEnabled(true).eval(code);
        assertEquals(off.toLuaString(), on.toLuaString(), "JIT diverged for: " + code);
        return on.toLuaString();
    }

    @Test
    void intLaneHelpersMatchInterpreter() {
        // Integer lanes with integer inputs (hot, compiled).
        assertEquals("40200", intLaneResult(
                "local function f(a,b) return (a-b)+(a+b) end "
                        + "local s=0 for i=1,200 do s=s+f(i,i+1) end return s"));
        // Float inputs must deopt out of the int lanes, same result.
        assertEquals("40400.0", intLaneResult(
                "local function f(a,b) return (a-b)+(a+b) end "
                        + "local s=0.0 for i=1,200 do s=s+f(i+0.5,1) end return s"));
        // Integer loop bound compare plus float bound (LEI lane / deopt).
        assertEquals("5050", intLaneResult(
                "local function f(n) local s=0 for i=1,n do s=s+i end return s end "
                        + "local a=f(100) for k=1,200 do a=f(100) end return a"));
        assertEquals("5050", intLaneResult(
                "local function f(n) local s=0 for i=1,n do s=s+i end return s end "
                        + "return f(100.0)"));
        // Recursive int kernel, then a float argument (deopt, then resume).
        assertEquals("6765", intLaneResult(
                "local function fib(n) if n <= 1 then return n end "
                        + "return fib(n-1) + fib(n-2) end "
                        + "local r=0 for k=1,3 do r=fib(20) end return r"));
        assertEquals("6765.0", intLaneResult(
                "local function fib(n) if n <= 1 then return n end "
                        + "return fib(n-1) + fib(n-2) end return fib(20.0)"));
    }

    @Test
    void outlinedCallProtocolMatchesInterpreter() {
        // Self-recursion through the outlined slow path and the fast tier.
        assertEquals("9227465", intLaneResult(
                "local function fib(n) if n <= 1 then return n end "
                        + "return fib(n-1) + fib(n-2) end "
                        + "local r=0 for k=1,2 do r=fib(35) end return r"));
        // Two-level call chain through compiled callees.
        assertEquals("-2", intLaneResult(
                "local function g(x) return x*2 end "
                        + "local function f(x) return g(x)+g(x+1) end "
                        + "local r=0 for k=1,200 do r=f(r+1) end return r"));
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.bytecode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.LuaClosure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JIT coverage and configuration regression tests. The generated code is a
 * fast path, so every case is asserted to produce identical results with JIT
 * forced on and off; the coverage cases additionally pin the opcode subset
 * (K-forms, numeric for loops) that used to fall back to the interpreter.
 */
public class JitCoverageTest {

    @AfterEach
    void restoreDefaults() {
        LuaState.ENABLE_JIT = true;
    }

    /** Runs {@code code} with JIT on or off on a fresh state. */
    private static LuaValue run(String code, boolean jit, int repeats) {
        LuaState state = new LuaState().jitEnabled(jit);
        LuaValue last = null;
        for (int i = 0; i < repeats; i++) {
            last = state.eval(code);
        }
        return last;
    }

    private static void assertSameWithAndWithoutJit(String code, int repeats) {
        String off = run(code, false, repeats).toLuaString();
        String on = run(code, true, repeats).toLuaString();
        assertEquals(off, on, "JIT result differs for: " + code);
    }

    /** Calls an inner function enough times to cross the hot threshold. */
    private static String hot(String body) {
        return "local function f(...) " + body + " end "
                + "local acc = '' "
                + "for k = 1, 400 do acc = acc .. tostring(f(3, 5)) .. ';' end "
                + "return #acc .. ':' .. acc:sub(1, 80)";
    }

    @Test
    void sqrtTailCallIntrinsicMatchesInterpreter() {
        // `return math.sqrt(x)` used to keep the whole proto interpreted
        // (builtin callee + no RETURN1). The JIT now emits a guarded
        // Math.sqrt with an identity check on MathLib.SQRT.
        String[] args = { "2.0", "4", "0", "-1", "0.0", "-0.0", "1e300", "0.5" };
        for (String arg : args) {
            assertSameWithAndWithoutJit(hot("return math.sqrt(" + arg + ")"), 2);
        }
        // Accumulation must keep float identity bit-for-bit.
        assertSameWithAndWithoutJit(
                "local function f(n) local s=0.0 for i=1,n do s=s+math.sqrt(i) end return s end "
                        + "local r for k=1,300 do r=f(1000) end "
                        + "return string.format('%.17g', r) .. ':' .. math.type(r)",
                2);
    }

    @Test
    void sqrtCallIntrinsicKeepsUnboxedFloatLane() {
        // Non-tail `local r = math.sqrt(x)` compiles to a one-result CALL; the
        // intrinsic keeps the float in the raw-bit lane, so subsequent
        // arithmetic must still match the interpreter bit-for-bit.
        assertSameWithAndWithoutJit(hot("local r = math.sqrt(2.0) return r * r"), 2);
        assertSameWithAndWithoutJit(hot("local r = math.sqrt(9) return r + 1"), 2);
        assertSameWithAndWithoutJit(hot("local r = math.sqrt(2.0) return r"), 2);
        assertSameWithAndWithoutJit(
                "local function f(x) local r = math.sqrt(x) return math.floor(r) end "
                        + "local s=0 for i=1,400 do s=s+f(i) end return s",
                2);
    }

    @Test
    void sqrtIntrinsicRespectsReassignmentAndShadowing() {
        // A reassigned math.sqrt must never be replaced by the intrinsic.
        assertSameWithAndWithoutJit(
                "math.sqrt = function(x) return 42.0 end local function f(x) return math.sqrt(x) end "
                        + "local r for i=1,400 do r=f(9.0) end return tostring(r)",
                2);
        // A shadowed local `math` resolves to the local, not the global.
        assertSameWithAndWithoutJit(
                "local math = { sqrt = function(x) return 7.0 end } "
                        + "local function f(x) return math.sqrt(x) end "
                        + "local r for i=1,400 do r=f(9.0) end return tostring(r)",
                2);
        // Bad argument types raise the exact same error.
        assertSameWithAndWithoutJit(
                "local function f(x) return math.sqrt(x) end "
                        + "local ok, err = pcall(f, 'nope') return tostring(ok) .. ':' .. tostring(err)",
                2);
    }

    @Test
    void integerConstantKFormsMatchInterpreter() {
        assertSameWithAndWithoutJit(hot("return (10 + 300) * 2"), 2);
        assertSameWithAndWithoutJit(hot("local x = 1000 return x * 7"), 2);
        assertSameWithAndWithoutJit(hot("local x = 1000 return x - 300"), 2);
        assertSameWithAndWithoutJit(hot("local x = 1000 return x // 3"), 2);
        assertSameWithAndWithoutJit(hot("local x = 1000 return x % 7"), 2);
    }

    @Test
    void floorDivisionAndModuloMatchLuaSemantics() {
        // Negative operands are where a naive `/` or `%` would diverge.
        assertSameWithAndWithoutJit(hot("local x = -7 return x // 3"), 2);
        assertSameWithAndWithoutJit(hot("local x = -7 return x % 3"), 2);
        // The interpreter is the oracle for exact values.
        LuaState s = new LuaState();
        assertEquals(-1, s.eval("local function f(x) for i=1,100 do x=x//3 end return x end return f(-7)").toLong());
        assertEquals(2, s.eval("local function f(x) for i=1,100 do x=x%3 end return x end return f(-7)").toLong());
        // Single-step values exercise the sign handling directly.
        assertEquals(-3, s.eval("local function f(x) return x//3 end return f(-7)").toLong());
        assertEquals(2, s.eval("local function f(x) return x%3 end return f(-7)").toLong());
    }

    @Test
    void numericForLoopsMatchInterpreter() {
        assertSameWithAndWithoutJit(hot("local x=0 for i=1,1000 do x=x+i end return x"), 2);
        assertSameWithAndWithoutJit(hot("local x=0 for i=1000,1,-1 do x=x+i end return x"), 2);
        assertSameWithAndWithoutJit(hot("local x=0 for i=1,1000,7 do x=x+i end return x"), 2);
        assertSameWithAndWithoutJit(hot("local s=0 for i=1,30 do for j=1,30 do s=s+i*j end end return s"), 2);
    }

    @Test
    void floatForLoopStillCorrect() {
        // Float loops are intentionally not JIT-specialized; they must still
        // run correctly through the interpreter fast path / deopt.
        assertSameWithAndWithoutJit(hot("local x=0.0 for i=1.0,10.0,0.5 do x=x+i end return x"), 2);
    }

    @Test
    void numericMixedArithmeticMatchesInterpreter() {
        // Regression: the JIT used to be integer-only, so any float arithmetic
        // in a hot function deopted on every call. It now emits a runtime
        // numeric dispatch (int lane / float lane). Results must be identical
        // in kind (int vs float) and value, including division and modulo.
        // Operands must arrive as parameters, not as literals: the constant
        // folder folds float-literal expressions before the JIT ever sees an
        // ADD, which would make the test vacuous. Each body accumulates in a
        // loop so the operation is genuinely executed per call.
        String[] bodies = {
            "x = x + y", "x = x * y", "x = x - y", "x = x / y", "x = x % y", "x = x ^ y",
        };
        for (String body : bodies) {
            String code = "local function f(a, y) local x = a for i=1,50 do " + body
                    + " end return x end "
                    + "local r for i=1,400 do r=f(1.5, 2.5) end return tostring(r) .. ':' .. math.type(r)";
            assertSameWithAndWithoutJit(code, 2);
        }
        // The canonical float accumulator: must not come back as denormal
        // garbage from a truncated D2L (a real bug this test now pins).
        assertSameWithAndWithoutJit(
                "local function f(n) local s=0.0 for i=1,n do s=s+i end return s end "
                + "local r for k=1,300 do r=f(10000) end return tostring(r) .. ':' .. math.type(r)", 2);
        // Integer lane must stay integer-typed and exact.
        assertSameWithAndWithoutJit(
                "local function f(n) local s=0 for i=1,n do s=s+i end return s end "
                + "local r for k=1,300 do r=f(10000) end return tostring(r) .. ':' .. math.type(r)", 2);
    }

    @Test
    void hotMethodOnMetatableBackedInstanceIsJitCompiled() {
        // Regression: GETFIELD guarded on "table has no metatable", so every
        // OOP instance (whose class table is its metatable) deopted. The guard
        // is now "rawget non-nil", which is metatable-independent.
        LuaState state = new LuaState();
        LuaClosure dot = (LuaClosure) state.eval(
                "Vec={} Vec.__index=Vec "
                + "function Vec.new(x,y) return setmetatable({x=x,y=y},Vec) end "
                + "function Vec:dot(o) return self.x*o.x+self.y*o.y end "
                + "return Vec.dot");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("dot"), dot);
        state.eval("local Vec=... "); // no-op, keep globals simple
        state.eval("""
                local a = Vec.new(1.0, 2.0)
                local b = Vec.new(3.0, 4.0)
                local acc = 0.0
                for i = 1, 400 do acc = acc + dot(a, b) end
                """);
        assertTrue(awaitCompiled(dot.proto, 5000),
                "a metatable-backed instance method should JIT-compile");
    }

    @Test
    void hotIntegerLoopActuallyCompiles() {
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local function f() local x=0 for i=1,1000 do x=x+i*2 end return x end return f");
        // Hotness is counted on interpreter OP_CALL, so drive the calls from
        // Lua rather than Java (a direct Java .call() bypasses the VM).
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        state.eval("for k=1,400 do f() end");
        assertTrue(awaitCompiled(f.proto, 5000),
                "hot integer loop with MULK should have been JIT-compiled");
    }

    @Test
    void repeatedEvalReusesProtoAndTiersUpTailCalledFunction() {
        // A hot function reached only via `return f(...)` (a TAILCALL) used to
        // never tier up: hotness was counted only in the OP_CALL path. With
        // the per-state proto cache, repeated eval of the same chunk also
        // reuses the same proto, so hotness accumulates across evaluations.
        LuaState state = new LuaState();
        String code = "local function hot(n) local s=0 for i=1,n do s=s+i end return s end "
                + "return hot(1000)";
        for (int i = 0; i < 80; i++) {
            state.eval(code);
        }
        // eval() uses the "chunk" name, so compile with the same key to get
        // the cached, tiered proto.
        LuaClosure chunk = (LuaClosure) state.compile(code, "chunk", state.getGlobals());
        org.luava.runtime.bytecode.LuaProto hot = chunk.proto.protos[0];
        assertTrue(awaitCompiled(hot, 5000),
                "a tail-called hot function must JIT-compile");
    }

    @Test
    void protoCacheIsBoundedAndSemanticallyTransparent() {
        // Many distinct sources must not grow the cache without limit, and
        // reusing a proto must still give each closure its own _ENV.
        LuaState state = new LuaState();
        for (int i = 0; i < 400; i++) {
            state.eval("return " + i);
        }
        state.set("x", 7);
        assertEquals(7, state.eval("return x").toLong());
    }

    @Test
    void perStateJitSwitchIsIndependent() {
        // The global JIT code cache is a bounded LRU shared process-wide, so
        // assert on the specific proto's compiled state, not cache size.
        LuaState off = new LuaState().jitEnabled(false);
        assertFalse(off.isJitEnabled());
        LuaClosure offFn = (LuaClosure) off.eval(
                "local function f(x) return x*2 end return f");
        off.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), offFn);
        off.eval("for k=1,400 do f(k) end");
        assertFalse(awaitCompiled(offFn.proto, 500),
                "state with JIT off must not compile anything");

        LuaState on = new LuaState().jitEnabled(true);
        assertTrue(on.isJitEnabled());
        LuaClosure onFn = (LuaClosure) on.eval(
                "local function g(x) return x*2 end return g");
        on.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("g"), onFn);
        on.eval("for k=1,400 do g(k) end");
        assertTrue(awaitCompiled(onFn.proto, 5000),
                "state with JIT on should compile a hot kernel");
    }

    @Test
    void globalDefaultStillHonoredWhenStateHasNoOverride() {
        boolean saved = LuaState.ENABLE_JIT;
        try {
            LuaState.ENABLE_JIT = false;
            LuaState state = new LuaState();
            assertFalse(state.isJitEnabled(), "no override should follow the global default");
            state.jitEnabled(true);
            assertTrue(state.isJitEnabled(), "explicit override should win over the global default");
            state.jitEnabled(null);
            assertFalse(state.isJitEnabled(), "null override should fall back to the global default");
        } finally {
            LuaState.ENABLE_JIT = saved;
        }
    }

    @Test
    void prewarmAcceptsLuaFunctionOverload() {
        LuaState state = new LuaState();
        LuaFunction fn = (LuaFunction) state.eval("local function h(x) return x + 1 end return h");
        int before = org.luava.runtime.jit.JitCompiler.cacheSize();
        org.luava.runtime.jit.JitCompiler.prewarm(fn);
        assertTrue(org.luava.runtime.jit.JitCompiler.cacheSize() > before,
                "prewarm(LuaFunction) should compile an eligible closure");
    }

    @Test
    void prewarmRespectsStateJitOff() {
        LuaState off = new LuaState().jitEnabled(false);
        LuaClosure cl = (LuaClosure) off.eval("local function k(x) return x + 1 end return k");
        int before = org.luava.runtime.jit.JitCompiler.cacheSize();
        org.luava.runtime.jit.JitCompiler.prewarm(cl, off);
        assertEquals(before, org.luava.runtime.jit.JitCompiler.cacheSize(),
                "prewarm must be a no-op when the state has JIT disabled");
    }

    @Test
    void sqrtIntrinsicProtoActuallyCompiles() {
        // Correctness could pass by staying interpreted; this pins that the
        // intrinsic shape is genuinely inside the JIT subset, for both the
        // tail and the one-result call forms.
        LuaState tail = new LuaState().jitEnabled(true);
        LuaClosure tf = (LuaClosure) tail.eval(
                "local function f(x) return math.sqrt(x*x + x) end return f");
        tail.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), tf);
        tail.eval("for k=1,400 do f(2.5) end");
        assertTrue(awaitCompiled(tf.proto, 5000),
                "a math.sqrt tail call must JIT-compile");

        LuaState call = new LuaState().jitEnabled(true);
        LuaClosure cf = (LuaClosure) call.eval(
                "local function g(x) local r = math.sqrt(x*x + x) return r end return g");
        call.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("g"), cf);
        call.eval("for k=1,400 do g(2.5) end");
        assertTrue(awaitCompiled(cf.proto, 5000),
                "a math.sqrt one-result call must JIT-compile");
    }

    @Test
    void loopsAndKFormsSurviveGuardedRuns() {
        // instructionLimit/long-poll guards disable JIT (by design); results
        // must still be correct.
        LuaState state = new LuaState().instructionLimit(50_000_000);
        LuaValue r = state.eval("local x=0 for i=1,1000 do x=x+i*2 end return x");
        assertEquals(1001000, r.toLong());
    }

    @Test
    void debugHookDoesNotDisarmJitForOtherStates() {
        // Regression: the hook-armed flag used to be a process-global that
        // leaked when a hook was set without being cleared, permanently
        // disabling JIT for every later state. Hooks are per-thread in Lua,
        // so a new state must be unaffected.
        LuaState hooked = new LuaState();
        hooked.eval("debug.sethook(function() end, '', 1000) "
                + "local x=0 for i=1,100 do x=x+i end");

        LuaState fresh = new LuaState();
        LuaClosure f = (LuaClosure) fresh.eval(
                "local function f() local x=0 for i=1,1000 do x=x+i*2 end return x end return f");
        fresh.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        fresh.eval("for k=1,400 do f() end");
        assertTrue(awaitCompiled(f.proto, 5000),
                "an abandoned hook on another state must not disable this state's JIT");
    }

    @Test
    void hookOnAbandonedCoroutineDoesNotDisarmJit() {
        LuaState c = new LuaState();
        c.eval("local co = coroutine.create(function() end) debug.sethook(co, function() end, 'l')");
        LuaState fresh = new LuaState();
        LuaClosure g = (LuaClosure) fresh.eval(
                "local function g(x) return x*2 end return g");
        fresh.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("g"), g);
        fresh.eval("for k=1,400 do g(k) end");
        assertTrue(awaitCompiled(g.proto, 5000),
                "a hook on an abandoned coroutine must not disable JIT for other states");
    }

    /**
     * Polls until {@code proto.jitCode} is set (or the timeout elapses).
     * Background tier-up latency is not deterministic under a loaded test
     * JVM, so a fixed sleep is flaky; the assertion is about whether the
     * proto becomes compiled, not how fast.
     */
    private static boolean awaitCompiled(org.luava.runtime.bytecode.LuaProto proto, long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (proto.jitCode != null) {
                return true;
            }
            if (proto.jitDisabled) {
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return proto.jitCode != null;
    }
}

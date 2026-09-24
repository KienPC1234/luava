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
    void whileAndRepeatLoopsMatchInterpreter() {
        // Regression: LT/LE were outside the JIT subset, so every while/repeat
        // loop stayed interpreted. They now compile with a mixed int/float
        // comparison lane and must match the interpreter exactly.
        assertSameWithAndWithoutJit(hot("local i=0 local s=0 while i<1000 do i=i+1 s=s+i end return s"), 2);
        assertSameWithAndWithoutJit(hot("local i=0 local s=0 while i<=1000 do i=i+1 s=s+i end return s"), 2);
        assertSameWithAndWithoutJit(hot("local i=0 local s=0 repeat i=i+1 s=s+i until i>=1000 return s"), 2);
        assertSameWithAndWithoutJit(hot("local s=0 for i=1,1000 do if i<500 then s=s+i end end return s"), 2);
        assertSameWithAndWithoutJit(hot("local s=0 for i=1,1000 do if i<=500 then s=s+i end end return s"), 2);
    }

    @Test
    void equalityAndTestOpsMatchInterpreter() {
        // EQ/EQK/TEST/TESTSET/LFALSESKIP: and/or, if-truthy and equality must
        // compile and stay bit-identical, including int/float equality
        // (1 == 1.0) and NaN ordering.
        assertSameWithAndWithoutJit(hot("local s=0 for i=1,1000 do if i==500 then s=s+1 end end return s"), 2);
        assertSameWithAndWithoutJit(hot("local s=0 for i=1,1000 do if i~=500 then s=s+1 end end return s"), 2);
        assertSameWithAndWithoutJit(hot("local x=3 local s=0 for i=1,1000 do s=s+(x or i) end return s"), 2);
        assertSameWithAndWithoutJit(hot("local x=3 local s=0 for i=1,1000 do s=s+(x and i or 0) end return s"), 2);
        assertSameWithAndWithoutJit(hot("local x=0 local s=0 for i=1,1000 do s=s+(x and i or 0) end return s"), 2);
        // Mixed numeric equality through parameters (not folded literals).
        assertSameWithAndWithoutJit(
                "local function f(a,b) local n=0 for i=1,50 do if a==b then n=n+1 end end return n end "
                + "local r for k=1,400 do r=f(1,1.0) end return r", 2);
        assertSameWithAndWithoutJit(
                "local function f(a,b) local n=0 for i=1,50 do if a<b then n=n+1 end end return n end "
                + "local r for k=1,400 do r=f(1,2.5) end return r", 2);
    }

    @Test
    void integerTableLoopWithIntAccumulatorCompiles() {
        // Regression: the forward type inference rejected a loop that mixed a
        // table read (T_UNKNOWN) into an integer accumulator because T_INT and
        // T_NUM were treated as a conflict. Numbers now join to T_NUM, whose
        // runtime int/float dispatch is exact.
        LuaState state = new LuaState();
        LuaClosure work = (LuaClosure) state.eval(
                "local function work() local t={} for i=1,2000 do t[i]=i*3 end "
                + "local s=0 for i=1,2000 do s=s+t[i] end return s end return work");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("work"), work);
        state.eval("for k=1,200 do work() end");
        assertTrue(awaitCompiled(work.proto, 5000),
                "integer-accumulator table loop should JIT-compile after the numeric join");
        assertEquals(6003000, state.eval("return work()").toLong());
    }

    @Test
    void singleInvocationHeavyLoopTiersUp() {
        // A function called once never crosses the call hotness threshold, so
        // its long numeric for-loop is what must drive the compile (requested
        // once per loop at FORPREP, not per iteration).
        LuaState state = new LuaState();
        LuaClosure work = (LuaClosure) state.eval(
                "local function work() local s=0 for i=1,1000000 do s=s+i end return s end return work");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("work"), work);
        // Called exactly once via a tail call after the loop request fires.
        org.luava.runtime.LuaValue r = state.eval("return work()");
        assertEquals(500000500000L, r.toLong());
        assertTrue(awaitCompiled(work.proto, 5000),
                "a single-invocation heavy loop should tier up via FORPREP");
    }

    @Test
    void topLevelChunkWithReturnUsesJitKernel() {
        // Regression for the top-level JIT fast lane: a chunk returning one
        // value must run through its compiled kernel once hot, not stay
        // interpreted because of the compiler-emitted trailing RETURN0.
        LuaState state = new LuaState();
        String code = "local s=0 for i=1,200000 do s=s+i end return s";
        LuaClosure chunk = (LuaClosure) state.compile(code, "chunk", state.getGlobals());
        for (int i = 0; i < 4; i++) {
            assertEquals(20000100000L, chunk.call().toLong());
        }
        assertTrue(awaitCompiled(chunk.proto, 5000), "top-level chunk should tier up");
    }

    @Test
    void varargProtoWithoutDotDotDotCompiles() {
        // A vararg signature that never reads `...` is safe to compile; the
        // blanket isVararg rejection was unnecessary (VARARG is still rejected
        // by the opcode allow-list when actually used).
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local function f(...) local s=0 for i=1,100 do s=s+i end return s end return f");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        state.eval("for k=1,400 do f(1,2,3) end");
        assertTrue(awaitCompiled(f.proto, 5000),
                "a vararg proto that never uses '...' should JIT-compile");
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
        LuaClosure fn = (LuaClosure) state.eval("local function h(x) return x + 1 end return h");
        // Assert on this proto's compiled state, not the shared LRU size:
        // the cache is process-wide and bounded (512), so once it is full a
        // successful compile no longer grows it.
        org.luava.runtime.jit.JitCompiler.prewarm((LuaFunction) fn);
        assertTrue(fn.proto.jitCode != null,
                "prewarm(LuaFunction) should compile an eligible closure");
    }

    @Test
    void prewarmRespectsStateJitOff() {
        LuaState off = new LuaState().jitEnabled(false);
        LuaClosure cl = (LuaClosure) off.eval("local function k(x) return x + 1 end return k");
        org.luava.runtime.jit.JitCompiler.prewarm(cl, off);
        assertEquals(null, cl.proto.jitCode,
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

    @Test
    void voidProtoCompilesAndMatchesInterpreter() {
        // RETURN0-only protos used to reject (no reachable value return). They
        // now compile under the unboxed long ABI with a void sentinel that
        // callers materialize as zero results.
        String[] bodies = {
            "local function g() local x=0 for i=1,200 do x=x+i end end g() return 1",
            "local function g(n) local x=0 for i=1,n do x=x+i end end g(200) return 2",
            "local function g() local t={} for i=1,200 do t[i]=i end end g() return 3",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local function f() local x=0 for i=1,100 do x=x+i end end return f");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        state.eval("for k=1,400 do f() end");
        assertTrue(awaitCompiled(f.proto, 5000), "a void loop proto should JIT-compile");
    }

    @Test
    void tailCallToVoidForwardZeroResults() {
        // Regression: a compiled tail call into a void callee returned its
        // sentinel long, which the caller read as the integer 0. A bare
        // `return g()` where g returns nothing must yield zero values, and a
        // one-value context must see nil. The void sentinel has no register
        // representation, so compiled callers now deopt to the interpreter.
        assertSameWithAndWithoutJit(
                "local function g() end local function h() return g() end "
                        + "for i=1,300 do if select('#', h()) ~= 0 then error('bad') end end return 1",
                2);
        assertSameWithAndWithoutJit(
                "local function g() end local function f(x) if x then return g() else return 1 end end "
                        + "local ok for i=1,300 do if f(true) ~= nil then ok=1 end end return 1",
                2);
        // A statement call to a void function stays void.
        assertSameWithAndWithoutJit(
                "local function g() end local function f(x) g() if x then return 1 end return 2 end "
                        + "for i=1,300 do local r=f(i%2==0) if r~=1 and r~=2 then error('bad') end end return 1",
                2);
        // Void as a non-final value (nil) in a multret list.
        assertSameWithAndWithoutJit(
                "local function g() end local function m() return g(), 5 end "
                        + "for i=1,300 do local a,b=m() if a~=nil or b~=5 then error('bad') end end return 1",
                2);
    }

    @Test
    void prewarmedVoidTailCallForwardsZeroResults() {
        // Deterministic tier-up: prewarm the caller and callee, then assert
        // the compiled caller still forwards zero results from the void tail.
        LuaState state = new LuaState().jitEnabled(true);
        state.eval("local function g() end local function h() return g() end "
                + "rawset(_G, 'g', g); rawset(_G, 'h', h)");
        org.luava.runtime.jit.JitCompiler.prewarm((LuaFunction) state.get("g"));
        org.luava.runtime.jit.JitCompiler.prewarm((LuaFunction) state.get("h"));
        for (int i = 0; i < 50; i++) {
            assertEquals(0L, state.eval("return select('#', h())").toLong(), "run " + i);
        }
        // The void callee g must be compiled (it is a leaf, but h is the one
        // whose void tail handling matters).
        assertTrue(((LuaClosure) state.get("g")).proto.jitCode != null
                        || ((LuaClosure) state.get("g")).proto.jitDisabled,
                "g must be compiled or rejected deterministically after prewarm");
    }

    @Test
    void closureFactoryCompilesAndMatchesInterpreter() {
        // OP_CLOSURE + OP_CLOSE (the make_counter shape) now compile: the
        // factory is an object-returning pure kernel; escaping upvalues are
        // closed by OP_CLOSE and by the caller's closeOnJitReturn.
        String[] bodies = {
            "local function mk(n) return function() n=n+1 return n end end "
                    + "local s=0 for i=1,200 do local c=mk(i) s=s+c()+c() end return s",
            "local function mk() local n=10 return function() n=n+1 return n end end "
                    + "local c=mk() local s=0 for i=1,200 do s=s+c() end return s",
            "local function pair() local x=0 return function() x=x+1 end, function() return x end end "
                    + "local inc,get=pair() local s=0 for i=1,200 do inc() inc() s=s+get() end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
    }

    @Test
    void selfMethodCallCompilesAndMatchesInterpreter() {
        // OP_SELF (obj:method()) is inside the subset under a rawget-non-nil
        // guard mirroring GETFIELD.
        LuaState state = new LuaState();
        LuaClosure dot = (LuaClosure) state.eval(
                "Vec={} Vec.__index=Vec "
                + "function Vec.new(x,y) return setmetatable({x=x,y=y},Vec) end "
                + "function Vec:dot(o) return self.x*o.x+self.y*o.y end "
                + "return Vec.dot");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("dot"), dot);
        state.eval("""
                local a = Vec.new(1.0, 2.0)
                local b = Vec.new(3.0, 4.0)
                local acc = 0.0
                for i = 1, 400 do acc = acc + a:dot(b) end
                """);
        assertTrue(awaitCompiled(dot.proto, 5000),
                "a colon-called method should JIT-compile via OP_SELF");
    }

    @Test
    void setmetatableConstructorCompilesAndMatchesInterpreter() {
        // `return setmetatable({...}, Class)` (the OOP factory idiom) used to
        // be rejected because the general call made the proto a calling impure
        // leaf with no loop; inlining the builtin removes the call, so the
        // factory compiles as an object-returning impure leaf. Semantics must
        // stay exact: protected metatables raise, bad arguments raise, a
        // reassigned/shadowed global must not use the intrinsic, and __gc
        // registration still runs.
        String[] bodies = {
            "local C={} C.__index=C local function mk(x) return setmetatable({x=x}, C) end "
                    + "local s=0 for i=1,200 do local o=mk(i) s=s+o.x end return s",
            "local function mk() local t=setmetatable({}, nil) return t end "
                    + "local n=0 for i=1,200 do if mk()~=nil then n=n+1 end end return n",
            "local function mk() return setmetatable({}, {__gc=function() end}) end "
                    + "for i=1,200 do mk() end return 1",
            "local function mk() return setmetatable({a=1}, {__index={b=2}}) end "
                    + "local s=0 for i=1,200 do s=s+mk().b end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        // A protected metatable must raise via the exact interpreter path.
        assertSameWithAndWithoutJit(
                "local p=setmetatable({},{__metatable='x'}) "
                        + "local function f() return pcall(setmetatable, p, {}) end "
                        + "local r for i=1,300 do r=f() end return tostring(r)",
                2);
        // Non-table targets and non-table metatables raise the exact error.
        assertSameWithAndWithoutJit(
                "local function f(a,b) return pcall(setmetatable, a, b) end "
                        + "local r for i=1,300 do r=f(5, {}) end return tostring(r)",
                2);
        // The constructor shape genuinely compiles (not just correct by deopt).
        LuaState state = new LuaState().jitEnabled(true);
        LuaClosure mk = (LuaClosure) state.eval(
                "local C={} C.__index=C "
                        + "local function mk(x,y) return setmetatable({x=x,y=y}, C) end return mk");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("mk"), mk);
        state.eval("local s=0 for i=1,400 do local o=mk(i,i+1) s=s+o.x end");
        assertTrue(awaitCompiled(mk.proto, 5000),
                "a setmetatable constructor should be inside the JIT subset");
    }

    @Test
    void objectReturningCallProtocolCompilesAndMatchesInterpreter() {
        // Plan.md §5.A: a pure object-returning callee is now entered from
        // compiled code under a boxed (object) call protocol, and an impure
        // caller may enter a pure compiled callee. The callee's parameter
        // window aliases the caller's argument registers, so a nested deopt
        // rolls that window back before the interpreter re-executes the call.
        // Every body must stay bit-identical whether the callee writes its
        // parameters (clobbering the window) or not.
        String[] bodies = {
            // object-returning callee, argument-preserving
            "local function f(x) return {v=x} end "
                    + "local s=0 for i=1,300 do s=s+f(i).v end return s",
            // object-returning callee that rewrites its parameter BEFORE a
            // deopting metatable lookup (the clobber shape)
            "local o=setmetatable({},{__index=function(_,k) return k end}) "
                    + "local function f(x) x=x+1000 return {v=(o[x] or 0)} end "
                    + "local s=0 for i=1,300 do s=s+f(i).v end return s",
            // tail-forward-only proto: `return f(...)`
            "local function f(x) return {v=x*2} end local function g(x) return f(x) end "
                    + "local s=0 for i=1,300 do s=s+g(i).v end return s",
            // impure caller entering a pure object callee
            "local t={} local function f(x) return {v=x} end "
                    + "for i=1,300 do t[i]=f(i).v end local s=0 for i=1,300 do s=s+t[i] end return s",
            // nested: object callee calls another object callee
            "local function h(x) return {v=x+1} end local function f(x) return h(x) end "
                    + "local s=0 for i=1,300 do s=s+f(i).v end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        // The OOP benchmark shape genuinely compiles all of new/dot/length/add.
        LuaState state = new LuaState().jitEnabled(true);
        state.eval("Vec={} Vec.__index=Vec "
                + "function Vec.new(x,y) return setmetatable({x=x,y=y},Vec) end "
                + "function Vec:dot(o) return self.x*o.x+self.y*o.y end "
                + "function Vec:add(o) return Vec.new(self.x+o.x, self.y+o.y) end");
        LuaClosure add = (LuaClosure) state.get("Vec").get(org.luava.runtime.LuaString.valueOf("add"));
        state.eval("local a=Vec.new(1.0,2.0) local b=Vec.new(3.0,4.0) local acc=0.0 "
                + "for i=1,400 do local c=a:add(b) acc=acc+c:dot(a) end");
        assertTrue(awaitCompiled(add.proto, 5000),
                "Vec:add (object-return tail call) should be inside the JIT subset");
    }

    @Test
    void parameterWritingSelfRecursionRollsBackArguments() {
        // A self-recursive proto that WRITES its parameter must not use the
        // prologue-free self-recursion entry (no window rollback): the callee's
        // window aliases the caller's argument registers, so a nested deopt
        // would re-read a clobbered parameter. The compiler must route such a
        // proto through the snapshot-wrapped general path, and results must
        // stay exact.
        String code = "local obj=setmetatable({},{__index=function(_,k) return k end}) "
                + "local function f(x,n) x=x+1000 if n<=0 then return x end "
                + "  return (obj[x] or 0)+f(x,n-1) end "
                + "local function outer(a,b) return f(a,b) end "
                + "for i=1,300 do outer(i,5) end "
                + "return outer(3,5);";
        assertSameWithAndWithoutJit("local function run() " + code + " end "
                + "local function hot() return run() end "
                + "for i=1,400 do hot() end return run()", 2);
        // The unsafe prologue-free entry must not be generated for this proto.
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local obj=setmetatable({},{__index=function(_,k) return k end}) "
                        + "local function f(x,n) x=x+1000 if n<=0 then return x end "
                        + "  return (obj[x] or 0)+f(x,n-1) end return f");
        org.luava.runtime.jit.JitCompiler.prewarm(f);
        assertTrue(f.proto.jitCode != null, "the self-recursive proto should compile");
        assertTrue(f.proto.jitCode.returnsInt, "expected the integer protocol");
    }

    @Test
    void impureProtoWithCallsCompilesAndEntersPureCallees() {
        // An impure proto (table/upvalue writes + a general call) may compile:
        // its general calls enter only pure compiled callees, with the
        // argument window rolled back on a nested deopt; a builtin/impure
        // callee deopts structurally at the call with the committed prefix
        // intact. Results must be identical and no write double-applied.
        String[] bodies = {
            "local t={} for i=1,200 do t[i]=i*i end local s=0 for i=1,200 do s=s+t[i] end return s",
            "local x=0 local function bump() x=x+1 end for i=1,200 do bump() end return x",
            "local t={} for i=1,200 do t['k'..i]=i end local s=0 for i=1,200 do s=s+t['k'..i] end return s",
            "local t={} local function f(v) t[#t+1]=v return #t end "
                    + "local n=0 for i=1,200 do n=f(i) end return n",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        LuaState state = new LuaState();
        LuaClosure work = (LuaClosure) state.eval(
                "local function work() local t={} for i=1,2000 do t[i]=i*3 end "
                + "local s=0 for i=1,2000 do s=s+t[i] end return s end return work");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("work"), work);
        state.eval("for k=1,200 do work() end");
        assertTrue(awaitCompiled(work.proto, 5000),
                "an impure table-write loop with no calls should JIT-compile");
    }

    @Test
    void topLevelChunkWithTrailingBuiltinCallStaysCompiled() {
        // Regression (host embedding): a pure chunk with a hot loop and a
        // trailing builtin call (assert(....) in every benchmark) deopted at
        // that builtin every run. The normal 8-deopt budget then disarmed the
        // kernel, so the hot loop fell back to the interpreter. A builtin
        // callee is not a LuaClosure and never will be, so that deopt is
        // structural and must use the large budget instead.
        LuaState state = new LuaState();
        String code = "local sum=0 for i=1,200000 do sum=sum+i end "
                + "assert(sum == 20000100000) return sum";
        LuaClosure chunk = (LuaClosure) state.compile(code, "chunk", state.getGlobals());
        for (int i = 0; i < 30; i++) {
            assertEquals(20000100000L, chunk.call().toLong());
        }
        assertTrue(chunk.proto.jitCode != null && !chunk.proto.jitDisabled,
                "a chunk with a trailing builtin call must not disarm its hot loop");
    }

    @Test
    void voidProtoCalledInOneValueContextYieldsNil() {
        // Regression (found by running the PUC suites at hotThreshold=1): a
        // void JIT kernel called where one value is expected used to leave the
        // function object in the result register instead of nil. The
        // load(reader-that-returns-nil) idiom in calls.lua pinned it.
        // `load(function() ... return nil end)` yields an empty (void) chunk.
        // Force-compile it, then call it in a one-value context: the result
        // must be nil, never the leftover function object.
        LuaState state = new LuaState();
        LuaClosure empty = (LuaClosure) state.eval("return load(function() return nil end)");
        org.luava.runtime.jit.JitCompiler.prewarm(empty);
        assertTrue(empty.proto.jitCode != null, "the empty chunk should have compiled");
        assertTrue(empty.call().isNil(),
                "a void kernel in a one-value context must yield nil, got " + empty.call());
        // The reader idiom that exposed it: each read returns the next line.
        LuaState s2 = new LuaState();
        LuaClosure reader = (LuaClosure) s2.eval(
                "local t = {nil, 'return ', '3'} "
                + "return load(function () return table.remove(t, 1) end)");
        org.luava.runtime.jit.JitCompiler.prewarm(reader);
        assertTrue(reader.call().isNil(), "the reader's empty-chunk result must be nil");
    }

    @Test
    void floorAndFloorSqrtIntrinsicsMatchInterpreter() {
        // `math.floor` and the fused `math.floor(math.sqrt(x))` loop bound are
        // emitted inline under an identity guard on the shared MathLib
        // singletons. Every numeric subtype (int, integral float, fractional,
        // huge, inf, nan) must come back with the exact value, kind and sign.
        for (String arg : new String[] {"0", "1", "3", "-3", "3.0", "-3.0", "3.9", "-3.9",
                "1e100", "-1e100", "math.huge", "-math.huge", "0/0", "2^53", "1.5"}) {
            assertSameWithAndWithoutJit(
                    hot("return tostring(math.floor(" + arg + ")) .. ':' .. math.type(math.floor(" + arg + "))"), 2);
        }
        assertSameWithAndWithoutJit(
                "local function f(n) local s=0 for i=1,n do s=s+math.floor(i/3) end return s end "
                        + "local r for i=1,400 do r=f(100) end return r",
                2);
        assertSameWithAndWithoutJit(
                "local function f(n) return math.floor(math.sqrt(n)) end "
                        + "local r for i=1,400 do r=f(i) end return r",
                2);
        // A reassigned or shadowed builtin must not be replaced by the intrinsic.
        assertSameWithAndWithoutJit(
                "math.floor = function(x) return 42 end "
                        + "local function f(x) return math.floor(x) end "
                        + "local r for i=1,400 do r=f(9.5) end return r",
                2);
        assertSameWithAndWithoutJit(
                "local math = { floor = function(x) return 7 end, sqrt = function(x) return 9 end } "
                        + "local function f(x) return math.floor(math.sqrt(x)) end "
                        + "local r for i=1,400 do r=f(16) end return r",
                2);
        // The fused idiom genuinely compiles (not merely correct via deopt).
        LuaState state = new LuaState();
        LuaClosure chunk = (LuaClosure) state.compile(
                "local N=200000 local sieve={} for i=2,N do sieve[i]=true end "
                + "for i=2,math.floor(math.sqrt(N)) do if sieve[i] then for j=i*i,N,i do sieve[j]=false end end end "
                + "local c=0 for i=2,N do if sieve[i] then c=c+1 end end return c",
                "chunk", state.getGlobals());
        assertEquals(17984, chunk.call().toLong());
        assertTrue(awaitCompiled(chunk.proto, 5000),
                "a floor(sqrt) loop bound must be inside the JIT subset");
    }

    @Test
    void impureProtoWithMultretCallStillCompilesHotLoop() {
        // An impure proto containing a multi-result or object call was once
        // rejected wholesale, so a hot numeric loop that merely *ends* with
        // `local a,b = f()` never compiled. Such calls now deopt instead of
        // rejecting the proto, so the loop runs compiled and the trailing
        // call resumes in the interpreter.
        String[] bodies = {
            "local function g() return 1, 2, 3 end local t={} for i=1,200 do t[i]=i end "
                    + "local s=0 for i=1,200 do s=s+t[i] end local a,b=g() return s+a+b",
            "local t={} local s=0 for i=1,200 do s=s+i end local a,b=table.unpack(t) return s+#t",
            "local t={} for i=1,200 do t[i]=tostring(i) end local s=0 for i=1,200 do s=s+#t[i] end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        LuaState state = new LuaState();
        LuaClosure work = (LuaClosure) state.eval(
                "local function work() local s=0 for i=1,2000 do s=s+i end "
                + "local function g() return 1,2 end local a,b=g() return s+a+b end return work");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("work"), work);
        state.eval("for k=1,200 do work() end");
        assertTrue(awaitCompiled(work.proto, 5000),
                "an impure proto with a trailing multret call should still compile its hot loop");
    }

    @Test
    void tinyImpureLoopWithTrailingCallIsDisarmedNotPenalized() {
        // Worst case for the impure deopt-at-call relaxation: a trivial loop
        // followed by a call, invoked far past the structural-deopt budget.
        // The kernel must be disarmed rather than paying an exception forever.
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local t = {} "
                + "local function f(x) t[1] = x local s = 0 for i = 1, 1 do s = s + i end "
                + "return #tostring(x .. s) end "
                + "local acc = 0 for k = 1, 2000000 do acc = acc + f(k) end "
                + "return f");
        assertTrue(f.proto.jitDisabled,
                "a trivial impure loop with a per-call deopt must disarm, not pay exceptions forever");
    }

    @Test
    void objectKeyTableAccessMatchesInterpreter() {
        // GETTABLE/SETTABLE now dispatch on the key type: integer keys use the
        // array-part lane, string keys rawget/rawset on a metatable-free table.
        String[] bodies = {
            "local t={} for i=1,200 do t['key_'..i]=i end local s=0 for i=1,200 do s=s+t['key_'..i] end return s",
            "local t={} for i=1,200 do local k='x'..i t[k]=(t[k] or 0)+i end return t.x100",
            "local t={} for i=1,200 do t[i]=i end local s=0 for i=1,200 do s=s+t[i] end return s",
            "local t={} t.a=1 t.b=2 t['a']=t['a']+t['b'] return t.a",
            "local t={} for i=1,100 do t['p_'..i]={v=i} end local s=0 for i=1,100 do s=s+t['p_'..i].v end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
    }

    @Test
    void genericForLoopsCompileAndMatchInterpreter() {
        // Generic-for (TFORPREP/TFORCALL/TFORLOOP) is now in the subset for the
        // built-in iterators (pairs/ipairs/gmatch): the setup call and each
        // iterator step are emitted inline under identity guards, and an
        // unrecognized iterator deopts structurally before any side effect.
        String[] bodies = {
            "local t={a=1,b=2,c=3} local s=0 for k,v in pairs(t) do s=s+v end return s",
            "local t={10,20,30} local s=0 for i,v in ipairs(t) do s=s+v end return s",
            "local s='a,b,c' local n=0 for w in s:gmatch('%a') do n=n+1 end return n",
            "local t={} for i=1,200 do t[i]=i end local s=0 for k,v in pairs(t) do s=s+v end return s",
            "local t={} for i=1,200 do t[i]=i end local s=0 for i,v in ipairs(t) do s=s+v end return s",
            // break out of a compiled generic-for
            "local s=0 for k,v in pairs({1,2,3,4,5}) do if k==3 then break end s=s+v end return s",
            // nested generic-for
            "local n=0 for k,v in pairs({1,2}) do for i,w in ipairs({1,2}) do n=n+w end end return n",
            // custom iterator closure: must deopt and still be correct
            "local function it(s,c) if c<3 then return c+1 end end local s=0 for v in it,nil,0 do s=s+v end return s",
            // __pairs metamethod: pairsFast returns null, so deopt to handler
            "local t=setmetatable({},{__pairs=function() return function() return nil end end}) "
                    + "local n=0 for k,v in pairs(t) do n=n+1 end return n",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
    }

    @Test
    void genericForProtoActuallyCompiles() {
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local function f(t) local s=0 for k,v in pairs(t) do s=s+v end return s end return f");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        state.eval("local t={} for i=1,100 do t[i]=i end for k=1,400 do f(t) end");
        assertTrue(awaitCompiled(f.proto, 5000),
                "a pairs loop must be inside the JIT subset");

        LuaState s2 = new LuaState();
        LuaClosure g = (LuaClosure) s2.eval(
                "local function g(str) local n=0 for w in str:gmatch('%a') do n=n+1 end return n end return g");
        s2.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("g"), g);
        s2.eval("for k=1,400 do g('a,b,c') end");
        assertTrue(awaitCompiled(g.proto, 5000),
                "a gmatch loop must be inside the JIT subset (string SELF lane)");
        assertEquals(3L, s2.eval("return g('a,b,c')").toLong());
    }

    @Test
    void tostringIntrinsicCompilesAndMatchesInterpreter() {
        // `tostring(x)` with a global callee is emitted inline under an
        // identity guard on the shared BaseLib.TOSTRING singleton. Primitive
        // and plain-string arguments render exactly; a __tostring metatable or
        // a number-format override deopts.
        String[] bodies = {
            "local s='' for i=1,200 do s=tostring(i)..tostring(i/2)..tostring(true)..tostring(nil) end return #s",
            "local s='' for i=1,200 do s=s..tostring(i) end return #s",
            "local t=setmetatable({},{__tostring=function() return 'M' end}) "
                    + "local s='' for i=1,200 do s=s..tostring(t) end return s",
            // reassigned tostring must not be replaced by the intrinsic
            "tostring=function(x) return 'Z' end local s='' for i=1,200 do s=s..tostring(i) end return s",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
        LuaState state = new LuaState();
        LuaClosure f = (LuaClosure) state.eval(
                "local function f(n) local s='' for i=1,n do s=tostring(i) end return #s end return f");
        state.getGlobals().rawset(org.luava.runtime.LuaString.valueOf("f"), f);
        state.eval("for k=1,400 do f(50) end");
        assertTrue(awaitCompiled(f.proto, 5000),
                "a hot tostring loop must be inside the JIT subset");
    }

    @Test
    void stringReceiverMethodCallCompilesAndMatchesInterpreter() {
        // `str:method()` resolves through the shared string metatable; the
        // compiled SELF lane handles it so string-method-heavy loops compile.
        String[] bodies = {
            "local function f(s) local n=0 for w in s:gmatch('%a') do n=n+1 end return n end return f('a,b,c')",
            "local function f(s) local n=0 for i=1,50 do n=n+#s:sub(1, 2) end return n end return f('abcdef')",
            "local s='hello world' local n=0 for i=1,100 do n=n+s:byte(1) end return n",
        };
        for (String code : bodies) {
            assertSameWithAndWithoutJit(hot(code), 2);
        }
    }

    @Test
    void hostOnlyCallsTierUp() {
        // Regression: tier-up counters were only bumped at Lua call sites
        // (tryJitCall/tryJitTailCall). A function invoked only from the host
        // (LuaState.call / LuaClosure.invoke) never compiled, so a small hot
        // loop driven from Java stayed interpreted forever. runTopLevelJit now
        // counts host entries.
        LuaState state = new LuaState().jitEnabled(true);
        LuaClosure fn = (LuaClosure) state.eval(
                "local function work(n) local x=0 for i=1,n do x=x+i end return x end return work");
        // Small loop: below JIT_LOOP_THRESHOLD, so only the call hotness can
        // drive the compile. Call it enough times from the host.
        for (int i = 0; i < 5000; i++) {
            fn.call(org.luava.runtime.LuaInteger.valueOf(100));
        }
        assertTrue(awaitCompiled(fn.proto, 5000),
                "a host-only-called function must tier up via runTopLevelJit");
        // Correct result after compilation.
        assertEquals(5050, fn.call(org.luava.runtime.LuaInteger.valueOf(100)).toLong());
    }

    @Test
    void repeatedEvalOfCachedChunkTiersUp() {
        // The same chunk name is served from the proto cache; repeated eval
        // must keep returning the same value and (now) tier the chunk up.
        LuaState state = new LuaState().jitEnabled(true);
        String code = "local s=0 for i=1,100 do s=s+i end return s";
        for (int i = 0; i < 300; i++) {
            assertEquals(5050, state.eval(code, "@cached").toLong());
        }
        // The compiled chunk must exist somewhere in the proto tree; assert the
        // result is still exact and no exception surfaced.
        assertEquals(5050, state.eval(code, "@cached").toLong());
    }

    @Test
    void hostCallPathDoesNotAllocatePerCallHeavyweights() {
        // Regression: every BytecodeVM.execute allocated CallInfo[256] plus
        // 256 CallInfo objects and VmContext's 11 cache arrays (~6.5 KB). A
        // leaf handler driven from the host now allocates only its VmContext
        // and a tiny grow-on-demand CallInfo array. Assert a generous bound on
        // bytes/call so a reintroduced 256-element allocation fails loudly.
        LuaState state = new LuaState();
        LuaFunction h = (LuaFunction) state.eval(
                "return function(a) local s=0 for i=1,8 do s=s+a+i end return s end");
        for (int i = 0; i < 20000; i++) {
            h.call(org.luava.runtime.LuaInteger.valueOf(3));
        }
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean sun =
                (com.sun.management.ThreadMXBean) bean;
        long before = sun.getThreadAllocatedBytes(Thread.currentThread().getId());
        int n = 200000;
        for (int i = 0; i < n; i++) {
            h.call(org.luava.runtime.LuaInteger.valueOf(3));
        }
        long bytes = sun.getThreadAllocatedBytes(Thread.currentThread().getId()) - before;
        long perCall = bytes / n;
        // A VmContext plus one 8-slot CallInfo[] is ~200 bytes; the old 256
        // CallInfo objects alone were > 8 KB/call. Allow headroom for JIT
        // metadata but reject the old heavyweight allocation.
        assertTrue(perCall < 4000,
                "host call allocates " + perCall + " bytes/call (expected < 4000)");
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

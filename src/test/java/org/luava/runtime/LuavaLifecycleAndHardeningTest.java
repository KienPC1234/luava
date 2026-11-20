/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luava.runtime.bytecode.Instruction;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.bytecode.OpCode;
import org.luava.runtime.bytecode.UpvalueDesc;
import org.luava.runtime.jit.JitCode;
import org.luava.runtime.jit.JitCodeCache;
import org.luava.runtime.jit.JitCompiler;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for lifecycle cleanup, async serialization, bounded string
 * formatting pointers, and JIT cache eviction.
 */
public class LuavaLifecycleAndHardeningTest {

    @Test
    void stringFormatPointerMatchesSpecWithoutGrowingInternPool() {
        LuaState s = new LuaState();
        LuaValue eq = s.eval("return string.format('%p', 'a'..'b') == string.format('%p', 'ab')");
        assertTrue(eq.toBoolean(), "equal short strings must report matching %p");

        LuaValue neq = s.eval("return string.format('%p', 'abc') ~= string.format('%p', 'def')");
        assertTrue(neq.toBoolean(), "distinct short strings should report different %p");

        LuaValue longDiff = s.eval("""
                local a = string.rep('x', 60)
                local b = ('x'):rep(30) .. ('x'):rep(30)
                return string.format('%p', a) ~= string.format('%p', b)
                """);
        assertTrue(longDiff.toBoolean(), "distinct long string instances must report different %p");

        String loopSrc = """
                for i = 1, 20000 do
                    local tmp = string.format('%p', 'dyn_' .. i)
                end
                """;
        // Warm up compile-time literals ('dyn_', 'format', '%p') once before sampling pool size.
        s.eval("local tmp = string.format('%p', 'dyn_' .. 0)");
        int poolBefore = LuaString.internPoolSize();
        s.eval(loopSrc);
        int poolAfter = LuaString.internPoolSize();
        assertEquals(poolBefore, poolAfter, "string.format('%p') must not insert runtime strings into INTERN_POOL");
    }

    @Test
    void closeRunsFinalizersClosesIoFilesAndInvalidatesJit(@TempDir Path dir) {
        LuaState s = new LuaState();
        AtomicInteger finalized = new AtomicInteger();
        s.registerFunction("markGc", () -> finalized.incrementAndGet());

        Path outFile = dir.resolve("unclosed.txt");
        s.set("targetPath", outFile.toString());
        s.eval("""
                local f = assert(io.open(targetPath, 'w'))
                f:write('hello')
                f:flush()
                keptFile = f
                keptTable = setmetatable({}, {__gc = function() markGc() end})
                function hotAdd(a, b) return a + b end
                """);
        LuaValue fn = s.get("hotAdd");
        boolean jitWasCompiled = false;
        if (fn instanceof LuaClosure lc) {
            JitCompiler.prewarm(lc, s);
            jitWasCompiled = lc.proto.jitCode != null;
        }

        LuaValue heldFile = s.get("keptFile");
        LuaTable ioLib = (LuaTable) s.get("io");
        LuaFunction ioType = (LuaFunction) ioLib.rawget(LuaString.valueOf("type"));
        assertEquals("file", ioType.call(heldFile).toLuaString());

        s.close();
        assertTrue(s.isClosed());
        assertEquals(1, finalized.get(), "close() must drain and run owned finalizers immediately");
        assertEquals("closed file", ioType.call(heldFile).toLuaString(),
                "close() must close unclosed IoLib FileHandle owned by the state");
        if (jitWasCompiled) {
            assertNull(((LuaClosure) fn).proto.jitCode, "close() must clear compiled JIT kernel");
        }

        // Idempotent close and fail-fast after close
        s.close();
        assertThrows(LuaException.class, () -> s.eval("return 1"));
        assertThrows(LuaException.class, () -> s.call("hotAdd", 1, 2));
        assertThrows(LuaException.class, () -> s.evalAsync("return 1"));
    }

    @Test
    void evalAsyncRunsOnVirtualThreadSerializesAndRejectsConcurrentDirectEval() throws Exception {
        LuaState s = new LuaState();
        AtomicBoolean ranVirtual = new AtomicBoolean(false);
        s.registerFunction("checkVirtual", () -> {
            ranVirtual.set(Thread.currentThread().isVirtual());
            return ranVirtual.get();
        });
        s.eval("count = 0");

        CompletableFuture<LuaValue> f1 = s.evalAsync("assert(checkVirtual()); count = count + 1; return count");
        CompletableFuture<LuaValue> f2 = s.evalAsync("error('boom')");
        CompletableFuture<LuaValue> f3 = s.evalAsync("count = count + 10; return count");

        assertEquals(1L, f1.get(5, TimeUnit.SECONDS).toLong());
        assertTrue(ranVirtual.get(), "evalAsync must execute on a virtual thread");
        assertThrows(Exception.class, () -> f2.get(5, TimeUnit.SECONDS));
        assertEquals(11L, f3.get(5, TimeUnit.SECONDS).toLong(),
                "queued evalAsync tasks must execute serially after an earlier failure");

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        s.registerFunction("blockHere", () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 1;
        });

        CompletableFuture<LuaValue> inFlight = s.evalAsync("return blockHere()");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        LuaException concurrent = assertThrows(LuaException.class, () -> s.eval("return 2"));
        assertTrue(concurrent.getMessage().contains("concurrent"),
                "expected concurrent-execution rejection, got: " + concurrent.getMessage());
        release.countDown();
        assertEquals(1L, inFlight.get(5, TimeUnit.SECONDS).toLong());
        s.close();
    }

    public static long dummyKernel() {
        return 0L;
    }

    @Test
    void jitCodeCacheSecondChancePreservesHotProtoOverColdEntries() throws Throwable {
        JitCodeCache cache = new JitCodeCache();
        var mh = MethodHandles.lookup().findStatic(
                LuavaLifecycleAndHardeningTest.class, "dummyKernel", MethodType.methodType(long.class));
        int[] code = {Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0)};

        LuaProto[] protos = new LuaProto[513];
        JitCode[] jcs = new JitCode[513];
        for (int i = 0; i < 513; i++) {
            protos[i] = new LuaProto(
                    "p" + i, 0, 0, 0, false, 2,
                    code, new LuaValue[0], new LuaProto[0], new UpvalueDesc[0], new int[1], new String[0]);
            jcs[i] = new JitCode(protos[i], mh, true);
            protos[i].jitCode = jcs[i];
        }

        // Fill cache to 512 entries
        for (int i = 0; i < 512; i++) {
            cache.put(protos[i], jcs[i]);
        }
        assertEquals(512, cache.size());

        // Mark p1..p511 as cold, keep p0 hot (recentlyUsed = true)
        for (int i = 1; i < 512; i++) {
            jcs[i].recentlyUsed = false;
        }
        jcs[0].recentlyUsed = true;

        // Inserting p512 must give p0 a second chance and evict cold p1 instead
        cache.put(protos[512], jcs[512]);
        assertEquals(512, cache.size());
        assertNotNull(protos[0].jitCode, "hot p0 must survive second-chance eviction");
        assertFalse(jcs[0].recentlyUsed, "second-chance sweep clears recentlyUsed bit");
        assertNull(protos[1].jitCode, "cold p1 should be evicted first");
        assertNotEquals(null, protos[512].jitCode);
    }

    @Test
    void stateGcAccountingAndStopStateStayIsolatedPerTenant() {
        LuaState s1 = new LuaState();
        LuaState s2 = new LuaState();
        try {
            s1.eval("collectgarbage('collect'); collectgarbage('stop')");
            assertFalse(s1.eval("return collectgarbage('isrunning')").toBoolean());
            assertTrue(s2.eval("return collectgarbage('isrunning')").toBoolean(),
                    "stopping GC in one LuaState must not stop GC in another");

            s2.eval("collectgarbage('collect')");
            long s2Before = s2.stats().allocBytes;
            s1.eval("local t = {} for i = 1, 500 do t[i] = {i} end");
            long s1After = s1.stats().allocBytes;
            long s2After = s2.stats().allocBytes;

            assertTrue(s1After > 10_000L, "s1 allocBytes should grow from its allocations: " + s1After);
            assertEquals(s2Before, s2After, "s1 allocations must not pollute s2 stats().allocBytes");
        } finally {
            s1.close();
            s2.close();
        }
    }

    @Test
    void compiledChunkAndLuaStatePoolIsolateStatesAndReuseProto() {
        LuaState.CompiledChunk chunk = LuaState.compileChunk(
                "local s = 0 for i = 1, 100 do s = s + i end; g_val = (g_val or 0) + 1; return s + g_val",
                "pooled.lua");
        try (LuaStatePool pool = new LuaStatePool(2)) {
            LuaValue r1 = pool.eval(chunk);
            assertEquals(5051L, r1.toLong());
            LuaValue r2 = pool.eval(chunk);
            assertEquals(5052L, r2.toLong());

            LuaState isolated = new LuaState();
            try {
                assertEquals(5051L, isolated.eval(chunk).toLong(),
                        "independent LuaState must not inherit globals from pooled state");
            } finally {
                isolated.close();
            }
        }
        chunk.close();
    }

    @Test
    void compiledChunkCloseInvalidatesJitTreeAndSupportsTryWithResources() {
        LuaProto captured;
        try (LuaState.CompiledChunk chunk = LuaState.compileChunk(
                "local function f(x) return x + 1 end; return f(41)", "auto_close.lua");
             LuaState s = new LuaState()) {
            LuaValue out = s.eval(chunk);
            assertEquals(42L, out.toLong());
            captured = chunk.proto;
            JitCompiler.prewarm(new LuaClosure(captured, new org.luava.runtime.eval.Upvalue[0], s.getGlobals(), s), s);
        }
        assertNull(captured.jitCode, "CompiledChunk.close() must invalidate compiled JIT kernels");
    }

    @Test
    void developerResourceControlsEnforceDepthCoroutinesMemoryAndCancelWithIsolation() throws Exception {
        // 1. maxCallDepth
        try (LuaState bounded = new LuaState().maxCallDepth(25);
             LuaState unbounded = new LuaState()) {
            bounded.eval("function rec(n) if n <= 0 then return 0 end return 1 + rec(n - 1) end");
            assertEquals(10L, bounded.eval("return rec(10)").toLong());
            LuaException depthEx = assertThrows(LuaException.class, () -> bounded.eval("return rec(40)"));
            assertTrue(depthEx.getMessage().contains("overflow"),
                    "expected stack overflow, got: " + depthEx.getMessage());
            // pcall catches cleanly
            assertFalse(bounded.eval("local ok = pcall(rec, 40); return ok").toBoolean());

            unbounded.eval("function rec(n) if n <= 0 then return 0 end return 1 + rec(n - 1) end");
            assertEquals(60L, unbounded.eval("return rec(60)").toLong(),
                    "maxCallDepth in one LuaState must not leak to another LuaState");
        }

        // 2. maxCoroutines: sequential reuse succeeds, live retention past cap fails
        try (LuaState coroLimited = new LuaState().maxCoroutines(4)) {
            LuaValue seq = coroLimited.eval("""
                    local sum = 0
                    for i = 1, 50 do
                        local f = coroutine.wrap(function(x) return x + 1 end)
                        sum = sum + f(i)
                    end
                    return sum
                    """);
            assertEquals(1325L, seq.toLong());
            assertEquals(0, coroLimited.getLiveCoroutineCount());

            LuaException coroEx = assertThrows(LuaException.class, () -> coroLimited.eval("""
                    local held = {}
                    for i = 1, 10 do
                        held[i] = coroutine.create(function() coroutine.yield(i) end)
                    end
                    """));
            assertTrue(coroEx.getMessage().contains("coroutine limit"),
                    "expected coroutine limit exceeded, got: " + coroEx.getMessage());
        }

        // 3. maxMemoryBytes: allows churn, stops retained table/string bomb even after collectgarbage('stop')
        try (LuaState memLimited = new LuaState().maxMemoryBytes(256 * 1024L)) {
            LuaValue churn = memLimited.eval("""
                    local acc = 0
                    for i = 1, 4000 do
                        local tmp = {i, i + 1}
                        acc = acc + tmp[1]
                    end
                    return acc
                    """);
            assertTrue(churn.toLong() > 0);

            LuaException memEx = assertThrows(LuaException.class, () -> memLimited.eval("""
                    collectgarbage('stop')
                    local keep = {}
                    for i = 1, 20000 do
                        keep[i] = string.rep('A', 64) .. tostring(i)
                    end
                    """));
            assertTrue(memEx.getMessage().contains("memory limit"),
                    "expected memory limit exceeded, got: " + memEx.getMessage());
        }

        // 4. cancellable() + cancel() + resetCancellation()
        LuaState cancellable = new LuaState().cancellable();
        CountDownLatch entered = new CountDownLatch(1);
        cancellable.registerFunction("signalStart", () -> {
            entered.countDown();
            return true;
        });
        CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(() -> {
            try {
                cancellable.eval("signalStart(); while true do end");
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        cancellable.cancel();
        Throwable thrown = outcome.get(5, TimeUnit.SECONDS);
        assertNotNull(thrown);
        assertTrue(thrown.getMessage().contains("cancelled"), "expected cancel error, got: " + thrown);
        assertTrue(cancellable.isCancelled());
        assertThrows(LuaException.class, () -> cancellable.eval("return 1"));
        cancellable.resetCancellation();
        assertFalse(cancellable.isCancelled());
        assertEquals(7L, cancellable.eval("return 3 + 4").toLong());
        cancellable.close();
    }
}

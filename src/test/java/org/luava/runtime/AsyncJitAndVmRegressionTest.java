/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.JitCompiler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for background async JIT tier-up/eviction/invalidation,
 * {@code SmallFields} edge cases, and stackless coroutine trampoline
 * transitions.
 */
public class AsyncJitAndVmRegressionTest {

    @Test
    void asyncJitConcurrentTierUpEvictionAndInvalidationStressTest() throws Exception {
        int threads = 4;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<LuaProto> trackedProtos = new ArrayList<>();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int workerId = t;
                futures.add(exec.submit(() -> {
                    for (int round = 0; round < 160; round++) {
                        try (LuaState state = new LuaState().sandbox()) {
                            String fnName = "f_" + workerId + "_" + round;
                            state.eval("function " + fnName + "(n) local s = 0 for i = 1, n do s = s + i end return s end");
                            LuaValue v = state.get(fnName);
                            if (v instanceof LuaClosure lc) {
                                synchronized (trackedProtos) {
                                    trackedProtos.add(lc.proto);
                                }
                            }
                            assertEquals(5050L, state.call(fnName, 100).toLong());
                            assertEquals(5050L, state.call(fnName, 100).toLong());
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
            for (LuaProto p : trackedProtos) {
                JitCompiler.invalidateTree(p);
            }
            JitCompiler.Telemetry telemetry = JitCompiler.telemetry();
            assertFalse(telemetry.globallyDisabled(), "async stress must never trip JIT kill-switch");
            assertEquals(0L, telemetry.vmErrorCount(), "async JIT compilation must produce zero VM errors");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void smallFieldsDeleteReaddMetatableNextAndSpillTest() {
        try (LuaState state = new LuaState()) {
            LuaValue out = state.eval("""
                    local t = { a = 1, b = 2.5, c = true, d = "hi" }
                    assert(t.a == 1 and t.b == 2.5 and t.c == true and t.d == "hi")

                    -- Delete and re-add middle slot in SmallFields
                    t.b = nil
                    assert(t.b == nil)
                    t.b = 99
                    assert(t.b == 99)

                    -- Non-canonical dynamic string keys must resolve same SmallFields slots
                    local ka = "a" .. ""
                    local kdyn = string.sub("xxbxx", 3, 3)
                    assert(t[ka] == 1)
                    assert(t[kdyn] == 99)
                    t[kdyn] = 100
                    assert(t.b == 100)

                    -- Delete one key then iterate with next()
                    t.c = nil
                    local seen = {}
                    for k, v in pairs(t) do
                        seen[k] = v
                    end
                    assert(seen.a == 1 and seen.b == 100 and seen.c == nil and seen.d == "hi")

                    -- Metatable behavior on SmallFields before and after spill
                    local fallback = { missing = 777 }
                    setmetatable(t, { __index = fallback })
                    assert(t.missing == 777)

                    -- Spill past 4 slots into HashMap while keeping existing values and metatable
                    t.c = false
                    t.e = "fifth"
                    t.f = 6
                    assert(t.a == 1 and t.b == 100 and t.c == false and t.d == "hi" and t.e == "fifth" and t.f == 6)
                    assert(t.missing == 777)
                    t.e = nil
                    local count = 0
                    for _ in pairs(t) do count = count + 1 end
                    return count
                    """);
            assertEquals(5L, out.toLong());
        }
    }

    @Test
    void coroutineTrampolinePrimitivePromoteCloseAndDepthTest() {
        try (LuaState state = new LuaState().maxCoroutines(8)) {
            LuaValue out = state.eval("""
                    -- 1. Pure Lua primitive single-value yield/resume loop via coroutine.wrap and create
                    local gen = coroutine.wrap(function(limit)
                        for i = 1, limit do
                            coroutine.yield(i * 2)
                        end
                        return 999
                    end)
                    local total = 0
                    for _ = 1, 5 do
                        total = total + gen(5)
                    end
                    assert(total == 30)
                    assert(gen() == 999)

                    -- 2. Float, boolean, nil yields through trampoline
                    local typesCo = coroutine.create(function()
                        coroutine.yield(1.5)
                        coroutine.yield(false)
                        coroutine.yield(nil)
                        return "done"
                    end)
                    local ok1, v1 = coroutine.resume(typesCo)
                    local ok2, v2 = coroutine.resume(typesCo)
                    local ok3, v3 = coroutine.resume(typesCo)
                    local ok4, v4 = coroutine.resume(typesCo)
                    assert(ok1 and v1 == 1.5)
                    assert(ok2 and v2 == false)
                    assert(ok3 and v3 == nil)
                    assert(ok4 and v4 == "done")
                    assert(coroutine.status(typesCo) == "dead")

                    -- 3. Promote to VirtualThread across pcall + to-be-closed variable
                    local closedLog = {}
                    local promoteCo = coroutine.create(function()
                        local x <close> = setmetatable({}, {
                            __close = function() closedLog[#closedLog + 1] = "closed" end
                        })
                        local ok, res = pcall(function()
                            local a = coroutine.yield(10)
                            return a + 5
                        end)
                        return ok and res or -1
                    end)
                    local pok1, pv1 = coroutine.resume(promoteCo)
                    assert(pok1 and pv1 == 10)
                    local pok2, pv2 = coroutine.resume(promoteCo, 32)
                    assert(pok2 and pv2 == 37)
                    assert(#closedLog == 1 and closedLog[1] == "closed")

                    -- 4. Close suspended trampoline coroutine and reclaim maxCoroutines slot
                    for i = 1, 20 do
                        local co = coroutine.create(function()
                            coroutine.yield(i)
                        end)
                        local cok, cv = coroutine.resume(co)
                        assert(cok and cv == i)
                        local closedOk = coroutine.close(co)
                        assert(closedOk and coroutine.status(co) == "dead")
                    end
                    return "ok"
                    """);
            assertEquals("ok", out.toLuaString());
        }

        // Verify coroutine limit still rejects simultaneous live coroutines
        try (LuaState limited = new LuaState().maxCoroutines(2)) {
            LuaException err = assertThrows(LuaException.class, () -> limited.eval("""
                    local a = coroutine.create(function() coroutine.yield(1) end)
                    local b = coroutine.create(function() coroutine.yield(2) end)
                    coroutine.resume(a)
                    coroutine.resume(b)
                    return coroutine.create(function() return 3 end)
                    """));
            assertTrue(err.getMessage().contains("coroutine"));
        }
    }
}

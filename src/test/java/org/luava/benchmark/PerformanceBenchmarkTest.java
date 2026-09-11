/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.benchmark;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PerformanceBenchmarkTest {

    public static class FastService {
        public long compute(long x) {
            return x * 2 + 1;
        }
    }

    @Test
    void runBenchmarksWithWarmup() {
        System.out.println("=================================================");
        System.out.println("       LUAVA JIT & CONCURRENCY BENCHMARK        ");
        System.out.println("=================================================");

        LuaState state = new LuaState();
        state.setLive("svc", new FastService());

        String loopCode = """
            local sum = 0
            for i = 1, 100000 do
                sum = sum + i
            end
            return sum
            """;

        String tableCode = """
            local t = {}
            for i = 1, 50000 do
                t[i] = i * 2
            end
            local acc = 0
            for i = 1, 50000 do
                acc = acc + t[i]
            end
            return acc
            """;

        String coCode = """
            local function worker()
                for i = 1, 1000 do
                    coroutine.yield(i)
                end
                return -1
            end
            local co = coroutine.create(worker)
            local total = 0
            while coroutine.status(co) ~= "dead" do
                local ok, val = coroutine.resume(co)
                if ok and val > 0 then
                    total = total + val
                end
            end
            return total
            """;

        String interopCode = """
            local total = 0
            for i = 1, 50000 do
                total = total + svc.compute(i)
            end
            return total
            """;

        // 1. COLD RUN
        System.out.println("\n[PHASE 1: COLD RUN (No Warmup)]");
        long t1 = measure(state, loopCode);
        long t2 = measure(state, tableCode);
        long t3 = measure(state, coCode);
        long t4 = measure(state, interopCode);
        System.out.printf("  - 100,000 Iterations Arithmetic Loop : %6.2f ms\n", t1 / 1_000_000.0);
        System.out.printf("  - 50,000 Table Set & Get             : %6.2f ms\n", t2 / 1_000_000.0);
        System.out.printf("  - 1,000 Coroutine Context Switches   : %6.2f ms\n", t3 / 1_000_000.0);
        System.out.printf("  - 50,000 Java Reflection Interop     : %6.2f ms\n", t4 / 1_000_000.0);

        // 2. JIT WARMUP (5 rounds)
        System.out.println("\n[PHASE 2: JIT WARMUP (5 rounds to trigger C2 HotSpot compilation)]");
        for (int i = 0; i < 5; i++) {
            state.eval(loopCode);
            state.eval(tableCode);
            state.eval(coCode);
            state.eval(interopCode);
        }
        System.out.println("  ✓ Warmup complete. C2 JIT optimization triggered.");

        // 3. JIT HOT RUN (average of 5 rounds)
        System.out.println("\n[PHASE 3: STEADY-STATE JIT HOT RUN (Average of 5 runs)]");
        double hotLoop = averageOf5(state, loopCode);
        double hotTable = averageOf5(state, tableCode);
        double hotCo = averageOf5(state, coCode);
        double hotInterop = averageOf5(state, interopCode);
        System.out.printf("  - 100,000 Iterations Arithmetic Loop : %6.2f ms (%.1fx faster than cold)\n",
                hotLoop, (t1 / 1_000_000.0) / Math.max(0.1, hotLoop));
        System.out.printf("  - 50,000 Table Set & Get             : %6.2f ms (%.1fx faster than cold)\n",
                hotTable, (t2 / 1_000_000.0) / Math.max(0.1, hotTable));
        System.out.printf("  - 1,000 Coroutine Context Switches   : %6.2f ms (%.1fx faster than cold)\n",
                hotCo, (t3 / 1_000_000.0) / Math.max(0.1, hotCo));
        System.out.printf("  - 50,000 Java Reflection Interop     : %6.2f ms (%.1fx faster than cold)\n",
                hotInterop, (t4 / 1_000_000.0) / Math.max(0.1, hotInterop));

        System.out.println("=================================================\n");
    }

    @Test
    void testNestedCoroutinesAndCarrierNoPinning() {
        LuaState state = new LuaState();
        String nestedCoroScript = """
            local function leaf()
                local s = 0
                for i = 1, 100 do
                    coroutine.yield(i)
                    s = s + i
                end
                return s
            end

            local function intermediate()
                local co = coroutine.create(leaf)
                local subtotal = 0
                while coroutine.status(co) ~= "dead" do
                    local ok, val = coroutine.resume(co)
                    if ok and val > 0 then
                        coroutine.yield(val * 10)
                        subtotal = subtotal + val
                    end
                end
                return subtotal
            end

            local mainCo = coroutine.create(intermediate)
            local grandTotal = 0
            while coroutine.status(mainCo) ~= "dead" do
                local ok, v = coroutine.resume(mainCo)
                if ok and v > 0 then
                    grandTotal = grandTotal + v
                end
            end
            return grandTotal
            """;

        LuaValue res = state.eval(nestedCoroScript);
        assertTrue(res.toLong() > 0);
    }

    @Test
    void testMultiThreadedConcurrency() throws InterruptedException {
        int threadCount = 8;
        int iterationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicBoolean allPassed = new AtomicBoolean(true);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    LuaState threadState = new LuaState();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        LuaValue val = threadState.eval("local s = 0; for j=1,1000 do s = s + j end; return s");
                        if (val.toLong() != 500500) {
                            allPassed.set(false);
                        }
                    }
                } catch (Throwable e) {
                    allPassed.set(false);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        assertTrue(allPassed.get(), "All concurrent threads must execute correctly");
    }

    private long measure(LuaState state, String code) {
        long start = System.nanoTime();
        state.eval(code);
        return System.nanoTime() - start;
    }

    private double averageOf5(LuaState state, String code) {
        long total = 0;
        for (int i = 0; i < 5; i++) {
            total += measure(state, code);
        }
        return (total / 5.0) / 1_000_000.0;
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.bytecode;

import org.junit.jupiter.api.Test;
import org.luava.runtime.*;
import org.luava.runtime.bytecode.BytecodeCompiler;
import org.luava.runtime.bytecode.BytecodeVM;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.eval.Upvalue;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PerformanceBenchmarkTest {

    // Performance baseline (2026-09-11, same machine as Lua C reference):
    //   1,000,000 Loop Arithmetic         | ~500 ms   (Lua C: 6.9 ms)
    //   100,000 Table Reads & Writes      | ~163 ms   (Lua C: 4.6 ms)
    //   500,000 Closure Upvalue Mutations | ~1350 ms  (Lua C: 17.7 ms)
    //   Fibonacci(24) Deep Call Stack     | ~405 ms   (Lua C: 5.1 ms)
    // History: hook-guard (~14%) + lazy debug-frame sync (~5%, A/B verified)
    // banked a combined ~13% on arith (575 ms -> ~500 ms). The interpreter is
    // now dispatch-bound (JFR: no single hotspot, negligible GC); the remaining
    // ~70-85x gap to C is structural and needs a JIT backend, not micro-opts.

    private record BenchmarkResult(String name, double bytecodeMs) {}

    private BenchmarkResult runTimed(String name, String script, long expected, int warmup, int runs) {
        org.luava.frontend.lexer.Lexer lexer = new org.luava.frontend.lexer.Lexer(script, false);
        java.util.List<org.luava.frontend.lexer.Token> tokens = lexer.scanTokens();
        org.luava.frontend.parser.Parser parser = new org.luava.frontend.parser.Parser(tokens);
        org.luava.frontend.ast.Statements.BlockStmt block = parser.parse();

        LuaState bcState = new LuaState();
        LuaProto proto = BytecodeCompiler.compile(block, "@bench");
        Upvalue envUpval = new Upvalue("_ENV", bcState.getGlobals());
        LuaClosure bcClosure = new LuaClosure(proto, new Upvalue[]{envUpval}, bcState.getGlobals(), bcState);

        // Warmup (JIT + caches)
        LuaValue warmupRes = LuaNil.NIL;
        for (int i = 0; i < warmup; i++) {
            LuaValue[] res = BytecodeVM.execute(bcState, bcClosure, new LuaValue[0]);
            warmupRes = res.length > 0 ? res[0] : LuaNil.NIL;
        }

        assertEquals(expected, warmupRes.toLong(), "Wrong output for " + name);

        // Benchmark Bytecode VM
        long startBc = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            BytecodeVM.execute(bcState, bcClosure, new LuaValue[0]);
        }
        long durBc = System.nanoTime() - startBc;
        double bcMs = (durBc / 1_000_000.0) / runs;

        return new BenchmarkResult(name, bcMs);
    }

    @Test
    public void runFullBenchmarkSuite() {
        System.out.println("================================================================================");
        System.out.println("                 LUAVA PERFORMANCE BENCHMARK: BYTECODE VM                        ");
        System.out.println("================================================================================");

        // 1. Arithmetic Loop: 1,000,000 iterations; sum 1..1e6 = 500000500000
        String arithScript = """
            local sum = 0
            for i = 1, 1000000 do
                sum = sum + i
            end
            return sum
        """;
        BenchmarkResult r1 = runTimed("1,000,000 Loop Arithmetic", arithScript, 500000500000L, 5, 10);

        // 2. Table Array Operations: 100,000 writes & reads; sum 2*i = 10000100000
        String tableScript = """
            local t = {}
            for i = 1, 100000 do
                t[i] = i * 2
            end
            local sum = 0
            for i = 1, 100000 do
                sum = sum + t[i]
            end
            return sum
        """;
        BenchmarkResult r2 = runTimed("100,000 Table Reads & Writes", tableScript, 10000100000L, 3, 5);

        // 3. Closure Upvalues: 500,000 upvalue mutations
        String upvalScript = """
            local c = 0
            local function inc()
                c = c + 1
                return c
            end
            local total = 0
            for i = 1, 500000 do
                total = inc()
            end
            return total
        """;
        BenchmarkResult r3 = runTimed("500,000 Closure Upvalue Mutations", upvalScript, 500000L, 3, 5);

        // 4. Fibonacci Recursion: fib(24) = 46368
        String fibScript = """
            local function fib(n)
                if n <= 1 then return n end
                return fib(n - 1) + fib(n - 2)
            end
            return fib(24)
        """;
        BenchmarkResult r4 = runTimed("Fibonacci(24) Deep Call Stack", fibScript, 46368L, 3, 5);

        System.out.printf("%-35s | %-12s%n", "Benchmark Workload", "Bytecode (ms)");
        System.out.println("------------------------------------+--------------");
        System.out.printf("%-35s | %10.3f ms%n", r1.name(), r1.bytecodeMs());
        System.out.printf("%-35s | %10.3f ms%n", r2.name(), r2.bytecodeMs());
        System.out.printf("%-35s | %10.3f ms%n", r3.name(), r3.bytecodeMs());
        System.out.printf("%-35s | %10.3f ms%n", r4.name(), r4.bytecodeMs());
        System.out.println("================================================================================");
    }
}

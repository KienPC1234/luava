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

    private record BenchmarkResult(String name, double astMs, double bytecodeMs, double speedup) {}

    private BenchmarkResult runComparison(String name, String script, int warmup, int runs) {
        LuaState astState = new LuaState();
        LuaFunction astFunc = astState.compile(script);

        org.luava.frontend.lexer.Lexer lexer = new org.luava.frontend.lexer.Lexer(script, false);
        java.util.List<org.luava.frontend.lexer.Token> tokens = lexer.scanTokens();
        org.luava.frontend.parser.Parser parser = new org.luava.frontend.parser.Parser(tokens);
        org.luava.frontend.ast.Statements.BlockStmt block = parser.parse();

        LuaState bcState = new LuaState();
        LuaProto proto = BytecodeCompiler.compile(block, "@bench");
        Upvalue envUpval = new Upvalue("_ENV", bcState.getGlobals());
        LuaClosure bcClosure = new LuaClosure(proto, new Upvalue[]{envUpval}, bcState.getGlobals(), bcState);

        // Warmup
        LuaValue astWarmup = null;
        for (int i = 0; i < warmup; i++) {
            astWarmup = astFunc.call();
        }
        LuaValue bcWarmup = null;
        for (int i = 0; i < warmup; i++) {
            LuaValue[] res = BytecodeVM.execute(bcState, bcClosure, new LuaValue[0]);
            bcWarmup = res.length > 0 ? res[0] : LuaNil.NIL;
        }

        assertEquals(astWarmup.toLong(), bcWarmup.toLong(), "Outputs must match identically for " + name);

        // Benchmark AST Interpreter
        long startAst = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            astFunc.call();
        }
        long durAst = System.nanoTime() - startAst;
        double astMs = (durAst / 1_000_000.0) / runs;

        // Benchmark Bytecode VM
        long startBc = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            BytecodeVM.execute(bcState, bcClosure, new LuaValue[0]);
        }
        long durBc = System.nanoTime() - startBc;
        double bcMs = (durBc / 1_000_000.0) / runs;

        double speedup = astMs / bcMs;
        return new BenchmarkResult(name, astMs, bcMs, speedup);
    }

    @Test
    public void runFullBenchmarkSuite() {
        System.out.println("================================================================================");
        System.out.println("                 LUAVA PERFORMANCE BENCHMARK: AST vs BYTECODE VM               ");
        System.out.println("================================================================================");

        // 1. Arithmetic Loop: 1,000,000 iterations
        String arithScript = """
            local sum = 0
            for i = 1, 1000000 do
                sum = sum + i
            end
            return sum
        """;
        BenchmarkResult r1 = runComparison("1,000,000 Loop Arithmetic", arithScript, 5, 10);

        // 2. Table Array Operations: 100,000 writes & reads
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
        BenchmarkResult r2 = runComparison("100,000 Table Reads & Writes", tableScript, 3, 5);

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
        BenchmarkResult r3 = runComparison("500,000 Closure Upvalue Mutations", upvalScript, 3, 5);

        // 4. Fibonacci Recursion: fib(24) = 46,368 recursive calls
        String fibScript = """
            local function fib(n)
                if n <= 1 then return n end
                return fib(n - 1) + fib(n - 2)
            end
            return fib(24)
        """;
        BenchmarkResult r4 = runComparison("Fibonacci(24) Deep Call Stack", fibScript, 3, 5);

        System.out.printf("%-35s | %-12s | %-12s | %-10s%n", "Benchmark Workload", "AST (ms)", "Bytecode (ms)", "Speedup");
        System.out.println("------------------------------------+--------------+--------------+-----------");
        System.out.printf("%-35s | %10.3f ms | %10.3f ms | %8.2fx%n", r1.name(), r1.astMs(), r1.bytecodeMs(), r1.speedup());
        System.out.printf("%-35s | %10.3f ms | %10.3f ms | %8.2fx%n", r2.name(), r2.astMs(), r2.bytecodeMs(), r2.speedup());
        System.out.printf("%-35s | %10.3f ms | %10.3f ms | %8.2fx%n", r3.name(), r3.astMs(), r3.bytecodeMs(), r3.speedup());
        System.out.printf("%-35s | %10.3f ms | %10.3f ms | %8.2fx%n", r4.name(), r4.astMs(), r4.bytecodeMs(), r4.speedup());
        System.out.println("================================================================================");
    }
}

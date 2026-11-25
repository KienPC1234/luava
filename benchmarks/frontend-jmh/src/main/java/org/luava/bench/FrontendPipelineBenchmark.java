package org.luava.bench;

import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaState.CompiledChunk;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.BytecodeCompiler;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.JitCode;
import org.luava.runtime.jit.JitCompiler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 800, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 800, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 2)
public class FrontendPipelineBenchmark {

    @State(Scope.Thread)
    public static class CompileState {
        @Param({
                "small_3kb",
                "medium_50kb",
                "large_600kb",
                "many_locals",
                "nested_closures",
                "table_constructors",
                "escaped_strings",
                "deep_exprs",
                "heavy_comments"
        })
        public String workload;

        String source;
        String chunkName;
        List<Token> tokens;
        Statements.BlockStmt ast;
        LuaState cachedState;

        @Setup(Level.Trial)
        public void setup() {
            cachedState = new LuaState();
            source = CorpusLoader.sourceFor(workload);
            chunkName = "@" + workload + ".lua";
            tokens = new Lexer(source, false).scanTokens();
            ast = new Parser(tokens).parse();
            cachedState.compile(source, chunkName, null);
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (cachedState != null) {
                cachedState.close();
            }
        }
    }

    @State(Scope.Thread)
    public static class JitCompileState {
        @Param({"jit_kernel", "small_3kb"})
        public String workload;

        Statements.BlockStmt ast;
        String chunkName;
        LuaProto[] protos;
        int idx;

        @Setup(Level.Iteration)
        public void setupIteration() {
            new LuaState().close();
            String src = CorpusLoader.sourceFor(workload);
            chunkName = "@" + workload + ".lua";
            List<Token> tokens = new Lexer(src, false).scanTokens();
            ast = new Parser(tokens).parse();
            LuaProto root = BytecodeCompiler.compile(ast, chunkName);
            // Collect child protos if root is just the wrapper chunk (e.g. jit_kernel has sum_step)
            if (root.protos != null && root.protos.length > 0) {
                protos = root.protos;
            } else {
                protos = new LuaProto[] {root};
            }
            idx = 0;
        }

        @TearDown(Level.Invocation)
        public void resetProto() {
            LuaProto p = protos[idx % protos.length];
            JitCompiler.invalidateTree(p);
            p.jitDisabled = false;
            p.guardedJitDisabled = false;
            idx++;
        }
    }

    @State(Scope.Thread)
    public static class ExecState {
        @Param({"jit_kernel", "exec_table_ops", "exec_oop_metatables"})
        public String workload;

        String source;
        String chunkName;
        LuaState state;
        CompiledChunk warmChunk;

        @Setup(Level.Trial)
        public void setup() {
            source = CorpusLoader.sourceFor(workload);
            chunkName = "@" + workload + ".lua";
            state = new LuaState();
            warmChunk = LuaState.compileChunk(source, chunkName);
            // Warm up compilation/JIT tier-up prior to warmExecute
            for (int i = 0; i < 5; i++) {
                state.eval(warmChunk);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (warmChunk != null) {
                warmChunk.close();
            }
            if (state != null) {
                state.close();
            }
        }
    }

    @Benchmark
    public List<Token> lexOnly(CompileState s) {
        return new Lexer(s.source, false).scanTokens();
    }

    @Benchmark
    public Statements.BlockStmt parseOnly(CompileState s) {
        return new Parser(s.tokens).parse();
    }

    @Benchmark
    public LuaProto bytecodeOnly(CompileState s) {
        return BytecodeCompiler.compile(s.ast, s.chunkName);
    }

    @Benchmark
    public LuaProto compileCold(CompileState s) {
        try (CompiledChunk chunk = LuaState.compileChunk(s.source, s.chunkName)) {
            return ((LuaClosure) s.cachedState.load(chunk)).proto;
        }
    }

    @Benchmark
    public Object compileCached(CompileState s) {
        return s.cachedState.compile(s.source, s.chunkName, null);
    }

    @Benchmark
    public JitCode jitCompile(JitCompileState s) {
        LuaProto target = s.protos[s.idx % s.protos.length];
        return JitCompiler.tryCompile(target, false);
    }

    @Benchmark
    public LuaValue coldExecute(ExecState s) {
        try (CompiledChunk chunk = LuaState.compileChunk(s.source, s.chunkName)) {
            return s.state.eval(chunk);
        }
    }

    @Benchmark
    public LuaValue warmExecute(ExecState s) {
        return s.state.eval(s.warmChunk);
    }
}

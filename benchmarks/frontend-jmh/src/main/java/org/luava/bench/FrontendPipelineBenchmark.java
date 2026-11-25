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
import org.luava.runtime.jit.LuaToJvmTranslator;
import org.openjdk.jmh.annotations.AuxCounters;
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
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class JitCounters {
        public long jitSucceeded;
        public long jitRejected;
    }

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
                "heavy_comments",
                "jit_kernel"
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
        @Param({"jit_kernel", "jit_oop_ctor"})
        public String workload;

        LuaProto[] protos;
        int idx;

        @Setup(Level.Iteration)
        public void setupIteration() {
            new LuaState().close();
            String src = CorpusLoader.sourceFor(workload);
            String chunkName = "@" + workload + ".lua";
            List<Token> tokens = new Lexer(src, false).scanTokens();
            Statements.BlockStmt ast = new Parser(tokens).parse();
            LuaProto root = BytecodeCompiler.compile(ast, chunkName);
            if (root.protos != null && root.protos.length > 0) {
                protos = root.protos;
            } else {
                protos = new LuaProto[] {root};
            }
            for (LuaProto p : protos) {
                if (!LuaToJvmTranslator.eligible(p)) {
                    throw new IllegalStateException("Workload " + workload + " proto " + p.name + " is not JIT-eligible");
                }
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

    /** Pure frontend compilation (Lexer -> Parser -> BytecodeCompiler) without LuaState.load/close. */
    @Benchmark
    public CompiledChunk frontendCompileOnly(CompileState s) {
        return LuaState.compileChunk(s.source, s.chunkName);
    }

    /** Full public compileChunk + closure load + chunk close lifecycle. */
    @Benchmark
    public LuaProto compileAndLoad(CompileState s) {
        try (CompiledChunk chunk = LuaState.compileChunk(s.source, s.chunkName)) {
            return ((LuaClosure) s.cachedState.load(chunk)).proto;
        }
    }

    @Benchmark
    public Object compileCached(CompileState s) {
        return s.cachedState.compile(s.source, s.chunkName, null);
    }

    @Benchmark
    public JitCode jitCompile(JitCompileState s, JitCounters counters) {
        LuaProto target = s.protos[s.idx % s.protos.length];
        JitCode code = JitCompiler.tryCompile(target, false);
        if (code != null) {
            counters.jitSucceeded++;
        } else {
            counters.jitRejected++;
            throw new IllegalStateException("Expected JIT compilation to succeed for " + s.workload);
        }
        return code;
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

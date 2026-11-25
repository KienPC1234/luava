package org.luava.bench;

import org.luava.bench.CorpusLoader.WorkloadMetrics;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaState.CompiledChunk;
import org.luava.runtime.bytecode.BytecodeCompiler;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.JitCompiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs Stage 0 JMH benchmarks (JIT ON and JIT OFF separately), records fork-local
 * metrics and {@code @AuxCounters} to {@code frontend_jmh_raw.csv}, and records
 * standalone coordinator probes to {@code independent_probes.csv}.
 */
public final class Stage0Runner {

    private Stage0Runner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Stage0Runner <outDir> <commitSha> [quick|full]");
            System.exit(2);
        }
        Path outDir = Path.of(args[0]);
        String commitSha = args[1];
        boolean quick = args.length > 2 && "quick".equalsIgnoreCase(args[2]);
        Files.createDirectories(outDir);

        new LuaState().close();

        Map<String, WorkloadMetrics> metricsMap = new LinkedHashMap<>();
        for (String name : CorpusLoader.allSources().keySet()) {
            metricsMap.put(name, CorpusLoader.measureMetrics(name));
        }
        writeCorpusMetricsCsv(outDir.resolve("corpus_metrics.csv"), commitSha, metricsMap);

        int forks = quick ? 1 : 2;
        int warmIters = quick ? 2 : 3;
        int measIters = quick ? 3 : 5;
        int iterMs = quick ? 400 : 800;

        Path rawCsv = outDir.resolve("frontend_jmh_raw.csv");
        Path probeCsv = outDir.resolve("independent_probes.csv");
        try (BufferedWriter jmhWriter = Files.newBufferedWriter(rawCsv, StandardCharsets.UTF_8);
             BufferedWriter probeWriter = Files.newBufferedWriter(probeCsv, StandardCharsets.UTF_8)) {
            jmhWriter.write("commit_sha,jit_mode,stage,workload,score_ms,error_ms,alloc_mb_per_op,tokens,ast_nodes,bytecode_insts,jit_succeeded_ops,jit_rejected_ops\n");
            probeWriter.write("commit_sha,jit_mode,stage,workload,peak_heap_mb,jit_compiled_count,jit_compile_ms\n");

            runSuite(jmhWriter, probeWriter, commitSha, "JIT_ON", true, forks, warmIters, measIters, iterMs, metricsMap,
                    "org.luava.bench.FrontendPipelineBenchmark.*");

            runSuite(jmhWriter, probeWriter, commitSha, "JIT_OFF", false, forks, warmIters, measIters, iterMs, metricsMap,
                    "org.luava.bench.FrontendPipelineBenchmark\\.(frontendCompileOnly|compileAndLoad|coldExecute|warmExecute)");
        }
    }

    private static void runSuite(
            BufferedWriter jmhWriter,
            BufferedWriter probeWriter,
            String commitSha,
            String jitMode,
            boolean jitEnabled,
            int forks,
            int warmIters,
            int measIters,
            int iterMs,
            Map<String, WorkloadMetrics> metricsMap,
            String includePattern) throws Exception {

        String[] jvmArgs = jitEnabled
                ? new String[] {"-Xms512m", "-Xmx2g", "-XX:+UseG1GC", "-Dluava.jit=true", "-Dluava.jit.sync=true"}
                : new String[] {"-Xms512m", "-Xmx2g", "-XX:+UseG1GC", "-Dluava.jit=false"};

        Options opt = new OptionsBuilder()
                .include(includePattern)
                .forks(forks)
                .warmupIterations(warmIters)
                .warmupTime(TimeValue.milliseconds(iterMs))
                .measurementIterations(measIters)
                .measurementTime(TimeValue.milliseconds(iterMs))
                .jvmArgs(jvmArgs)
                .addProfiler("gc")
                .shouldFailOnError(true)
                .build();

        Collection<RunResult> results = new Runner(opt).run();
        for (RunResult rr : results) {
            String fullName = rr.getParams().getBenchmark();
            String stage = fullName.substring(fullName.lastIndexOf('.') + 1);
            String workload = rr.getParams().getParam("workload");
            WorkloadMetrics wm = metricsMap.get(workload);

            double scoreMs = rr.getPrimaryResult().getScore();
            double errorMs = rr.getPrimaryResult().getScoreError();
            double allocMb = extractSecondary(rr.getSecondaryResults(), "gc.alloc.rate.norm") / (1024.0 * 1024.0);
            long jitSucceeded = Math.round(extractSecondary(rr.getSecondaryResults(), "jitSucceeded"));
            long jitRejected = Math.round(extractSecondary(rr.getSecondaryResults(), "jitRejected"));

            jmhWriter.write(String.format(
                    Locale.ROOT,
                    "%s,%s,%s,%s,%.6f,%.6f,%.6f,%d,%d,%d,%d,%d%n",
                    commitSha,
                    jitMode,
                    stage,
                    workload,
                    scoreMs,
                    errorMs,
                    allocMb < 0 ? -1.0 : allocMb,
                    wm != null ? wm.tokenCount() : 0,
                    wm != null ? wm.astNodeCount() : 0,
                    wm != null ? wm.bytecodeCount() : 0,
                    Math.max(0L, jitSucceeded),
                    Math.max(0L, jitRejected)));

            ProbeSnapshot snap = measureIndependentProbe(stage, workload, jitEnabled);
            probeWriter.write(String.format(
                    Locale.ROOT,
                    "%s,%s,%s,%s,%.2f,%d,%.4f%n",
                    commitSha,
                    jitMode,
                    stage,
                    workload,
                    snap.peakHeapMb(),
                    snap.jitCompiled(),
                    snap.jitCompileMs()));
        }
    }

    private static double extractSecondary(Map<String, Result> secondary, String suffix) {
        Result<?> res = secondary.get(suffix);
        if (res == null) {
            res = secondary.get("·" + suffix);
        }
        if (res == null) {
            for (Map.Entry<String, Result> e : secondary.entrySet()) {
                if (e.getKey().endsWith(suffix)) {
                    res = e.getValue();
                    break;
                }
            }
        }
        return res != null ? res.getScore() : 0.0;
    }

    private record ProbeSnapshot(double peakHeapMb, long jitCompiled, double jitCompileMs) {}

    private static ProbeSnapshot measureIndependentProbe(String stage, String workload, boolean jitEnabled) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
        long jitCountBefore = JitCompiler.telemetry().compiledCount();
        long jitNanosBefore = readJitCompileNanos();
        long fallbackJitNanos = 0L;
        String src = CorpusLoader.sourceFor(workload);
        String chunkName = "@" + workload + ".lua";

        if ("frontendCompileOnly".equals(stage) || "compileAndLoad".equals(stage)
                || "lexOnly".equals(stage) || "parseOnly".equals(stage) || "bytecodeOnly".equals(stage)) {
            List<Token> tokens = new Lexer(src, false).scanTokens();
            Statements.BlockStmt ast = new Parser(tokens).parse();
            BytecodeCompiler.compile(ast, chunkName);
        } else if ("jitCompile".equals(stage) && jitEnabled) {
            List<Token> tokens = new Lexer(src, false).scanTokens();
            Statements.BlockStmt ast = new Parser(tokens).parse();
            LuaProto root = BytecodeCompiler.compile(ast, chunkName);
            LuaProto[] targets = (root.protos != null && root.protos.length > 0) ? root.protos : new LuaProto[] {root};
            long t0 = System.nanoTime();
            for (LuaProto target : targets) {
                JitCompiler.tryCompile(target, false);
            }
            fallbackJitNanos = System.nanoTime() - t0;
            JitCompiler.invalidateTree(root);
        } else if ("coldExecute".equals(stage) || "warmExecute".equals(stage)) {
            try (LuaState st = new LuaState();
                 CompiledChunk chunk = LuaState.compileChunk(src, chunkName)) {
                st.jitEnabled(jitEnabled);
                st.eval(chunk);
            }
        } else if ("compileCached".equals(stage)) {
            try (LuaState st = new LuaState()) {
                st.compile(src, chunkName, null);
                st.compile(src, chunkName, null);
            }
        }

        long peakBytes = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peakBytes += pool.getPeakUsage().getUsed();
            }
        }
        long jitDelta = Math.max(0L, JitCompiler.telemetry().compiledCount() - jitCountBefore);
        long jitNanosAfter = readJitCompileNanos();
        double jitCompileMs = (jitNanosBefore >= 0L && jitNanosAfter >= 0L)
                ? Math.max(0L, jitNanosAfter - jitNanosBefore) / 1e6
                : (fallbackJitNanos / 1e6);
        return new ProbeSnapshot(peakBytes / (1024.0 * 1024.0), jitDelta, jitCompileMs);
    }

    private static long readJitCompileNanos() {
        try {
            return JitCompiler.totalCompileNanos();
        } catch (NoSuchMethodError e) {
            return -1L;
        }
    }

    private static void writeCorpusMetricsCsv(Path path, String commitSha, Map<String, WorkloadMetrics> metrics)
            throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("commit_sha,workload,source_bytes,tokens,ast_nodes,bytecode_insts,protos\n");
            for (WorkloadMetrics m : metrics.values()) {
                w.write(String.format(
                        Locale.ROOT,
                        "%s,%s,%d,%d,%d,%d,%d%n",
                        commitSha, m.name(), m.sourceBytes(), m.tokenCount(), m.astNodeCount(), m.bytecodeCount(), m.protoCount()));
            }
        }
    }
}

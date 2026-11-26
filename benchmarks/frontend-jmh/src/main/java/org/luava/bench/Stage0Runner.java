package org.luava.bench;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
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
import org.openjdk.jmh.util.Statistics;

/**
 * Runs Stage 0 JMH benchmarks (JIT ON and JIT OFF separately), records fork-local
 * statistics (mean, median, stdev, CI error, GC allocation, and {@code @AuxCounters})
 * to {@code frontend_jmh_raw.csv}, and records standalone coordinator probes to
 * {@code independent_probes.csv}.
 */
public final class Stage0Runner {

    private Stage0Runner() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: Stage0Runner <outDir> <commitSha> [quick|ab|full|probes]");
            System.exit(2);
        }
        Path outDir = Path.of(args[0]);
        String commitSha = args[1];
        String mode = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "full";
        boolean quick = "quick".equals(mode);
        boolean ab = "ab".equals(mode);
        boolean probesOnly = "probes".equals(mode);
        Files.createDirectories(outDir);

        Instant startedAt = Instant.now();
        new LuaState().close();

        Map<String, WorkloadMetrics> metricsMap = new LinkedHashMap<>();
        for (String name : CorpusLoader.workloadNames()) {
            metricsMap.put(name, CorpusLoader.measureMetrics(name));
        }
        writeCorpusMetricsCsv(outDir.resolve("corpus_metrics.csv"), commitSha, metricsMap);

        int forks = quick ? 1 : 2;
        int warmIters = (quick || ab) ? 2 : 3;
        int measIters = quick ? 3 : (ab ? 4 : 5);
        int iterMs = quick ? 400 : (ab ? 250 : 800);
        writeRetainedHeapProbeCsv(outDir.resolve("retained_heap_probe.csv"), commitSha);
        if (probesOnly) {
            Instant finishedProbes = Instant.now();
            writeMetadataJson(
                    outDir.resolve("metadata.json"),
                    commitSha,
                    mode,
                    forks,
                    warmIters,
                    measIters,
                    iterMs,
                    args,
                    startedAt,
                    finishedProbes);
            return;
        }

        Path rawCsv = outDir.resolve("frontend_jmh_raw.csv");
        Path probeCsv = outDir.resolve("independent_probes.csv");
        try (BufferedWriter jmhWriter = Files.newBufferedWriter(rawCsv, StandardCharsets.UTF_8);
             BufferedWriter probeWriter = Files.newBufferedWriter(probeCsv, StandardCharsets.UTF_8)) {
            jmhWriter.write("commit_sha,jit_mode,stage,workload,score_ms,p50_ms,stdev_ms,error_ms,alloc_mb_per_op,tokens,ast_nodes,bytecode_insts,jit_succeeded_ops,jit_rejected_ops\n");
            probeWriter.write("commit_sha,jit_mode,stage,workload,peak_heap_mb,jit_compiled_count,jit_rejected_count,jit_compile_ms\n");

            runSuite(jmhWriter, probeWriter, commitSha, "JIT_ON", true, forks, warmIters, measIters, iterMs, metricsMap,
                    "org.luava.bench.FrontendPipelineBenchmark.*");

            runSuite(jmhWriter, probeWriter, commitSha, "JIT_OFF", false, forks, warmIters, measIters, iterMs, metricsMap,
                    "org.luava.bench.FrontendPipelineBenchmark\\.(frontendCompileOnly|compileAndLoad|coldExecute|warmExecute)");
        }
        Instant finishedAt = Instant.now();
        writeMetadataJson(
                outDir.resolve("metadata.json"),
                commitSha,
                mode,
                forks,
                warmIters,
                measIters,
                iterMs,
                args,
                startedAt,
                finishedAt);
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

            Result<?> primary = rr.getPrimaryResult();
            Statistics stats = primary.getStatistics();
            double scoreMs = primary.getScore();
            double p50Ms = stats != null ? stats.getPercentile(50.0) : Double.NaN;
            double stdevMs = stats != null ? stats.getStandardDeviation() : Double.NaN;
            double errorMs = primary.getScoreError();

            double allocBytes = extractSecondary(rr.getSecondaryResults(), "gc.alloc.rate.norm");
            double allocMb = Double.isNaN(allocBytes) ? Double.NaN : (allocBytes / (1024.0 * 1024.0));
            double jitSucceededRaw = extractSecondary(rr.getSecondaryResults(), "jitSucceeded");
            double jitRejectedRaw = extractSecondary(rr.getSecondaryResults(), "jitRejected");

            jmhWriter.write(String.format(
                    Locale.ROOT,
                    "%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%d,%d,%s,%s%n",
                    commitSha,
                    jitMode,
                    stage,
                    workload,
                    fmtDouble(scoreMs, 6),
                    fmtDouble(p50Ms, 6),
                    fmtDouble(stdevMs, 6),
                    fmtDouble(errorMs, 6),
                    fmtDouble(allocMb, 6),
                    wm != null ? wm.tokenCount() : 0,
                    wm != null ? wm.astNodeCount() : 0,
                    wm != null ? wm.bytecodeCount() : 0,
                    fmtCount(jitSucceededRaw),
                    fmtCount(jitRejectedRaw)));

            ProbeSnapshot snap = measureIndependentProbe(stage, workload, jitEnabled);
            probeWriter.write(String.format(
                    Locale.ROOT,
                    "%s,%s,%s,%s,%.2f,%d,%d,%.4f%n",
                    commitSha,
                    jitMode,
                    stage,
                    workload,
                    snap.peakHeapMb(),
                    snap.jitCompiled(),
                    snap.jitRejected(),
                    snap.jitCompileMs()));
        }
    }

    private static String fmtDouble(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "NaN";
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", v);
    }

    private static String fmtCount(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "NaN";
        }
        return Long.toString(Math.max(0L, Math.round(v)));
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
        return res != null ? res.getScore() : Double.NaN;
    }

    private record ProbeSnapshot(double peakHeapMb, long jitCompiled, long jitRejected, double jitCompileMs) {}

    private static ProbeSnapshot measureIndependentProbe(String stage, String workload, boolean jitEnabled) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                pool.resetPeakUsage();
            }
        }
        long jitCountBefore = JitCompiler.telemetry().compiledCount();
        long jitRejectedBefore = readJitRejectedCount();
        long jitNanosBefore = readJitCompileNanos();
        long fallbackJitNanos = 0L;
        String src = CorpusLoader.sourceFor(workload);
        String chunkName = "@" + workload + ".lua";

        if ("frontendCompileOnly".equals(stage)) {
            LuaState.compileChunk(src, chunkName);
        } else if ("compileAndLoad".equals(stage)) {
            try (LuaState st = new LuaState();
                 CompiledChunk chunk = LuaState.compileChunk(src, chunkName)) {
                st.load(chunk);
            }
        } else if ("lexOnly".equals(stage) || "parseOnly".equals(stage) || "bytecodeOnly".equals(stage)) {
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
        long jitRejectedAfter = readJitRejectedCount();
        long jitRejectedDelta = (jitRejectedBefore >= 0L && jitRejectedAfter >= 0L)
                ? Math.max(0L, jitRejectedAfter - jitRejectedBefore)
                : 0L;
        long jitNanosAfter = readJitCompileNanos();
        double jitCompileMs = (jitNanosBefore >= 0L && jitNanosAfter >= 0L)
                ? Math.max(0L, jitNanosAfter - jitNanosBefore) / 1e6
                : (fallbackJitNanos / 1e6);
        return new ProbeSnapshot(peakBytes / (1024.0 * 1024.0), jitDelta, jitRejectedDelta, jitCompileMs);
    }

    private static final MethodHandle TOTAL_COMPILE_NANOS_MH = lookupOptionalLongGetter("totalCompileNanos");
    private static final MethodHandle REJECTED_COUNT_MH = lookupOptionalLongGetter("rejectedCount");

    private static MethodHandle lookupOptionalLongGetter(String name) {
        try {
            return MethodHandles.publicLookup().findStatic(
                    JitCompiler.class, name, MethodType.methodType(long.class));
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static long readJitCompileNanos() {
        if (TOTAL_COMPILE_NANOS_MH == null) {
            return -1L;
        }
        try {
            return (long) TOTAL_COMPILE_NANOS_MH.invokeExact();
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static long readJitRejectedCount() {
        if (REJECTED_COUNT_MH == null) {
            return -1L;
        }
        try {
            return (long) REJECTED_COUNT_MH.invokeExact();
        } catch (Throwable t) {
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

    private static void writeMetadataJson(
            Path path,
            String commitSha,
            String mode,
            int forks,
            int warmIters,
            int measIters,
            int iterMs,
            String[] mainArgs,
            Instant startedAt,
            Instant finishedAt)
            throws IOException {
        String cpuModel = detectCpuModel();
        String osInfo = System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
        String javaInfo = System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version");
        String shortSha = commitSha != null && commitSha.length() > 12 ? commitSha.substring(0, 7) : commitSha;
        String treeCleanProp = System.getProperty("luava.bench.treeClean", "unknown");
        String engineJarProp = System.getProperty("luava.bench.engineJar", "");
        String repoRootProp = System.getProperty("luava.bench.repoRoot", "");
        String engineJarSha256 = !engineJarProp.isBlank() ? sha256File(Path.of(engineJarProp)) : "unknown";
        Path jmhJarPath = detectOwnJarPath();
        String jmhJarSha256 = jmhJarPath != null ? sha256File(jmhJarPath) : "unknown";
        String srcTreeSha256 = !repoRootProp.isBlank()
                ? sha256SourceTree(Path.of(repoRootProp).resolve("src/main/java"))
                : "unknown";
        String jvmInputArgs = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments());
        String cmdLine = ("java " + jvmInputArgs + " org.luava.bench.Stage0Runner " + String.join(" ", mainArgs))
                .trim()
                .replaceAll("\\s+", " ");
        double elapsedSec = Duration.between(startedAt, finishedAt).toMillis() / 1000.0;

        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("{\n");
            w.write("  \"commit_sha\": \"" + escapeJson(commitSha) + "\",\n");
            w.write("  \"commit_short\": \"" + escapeJson(shortSha) + "\",\n");
            if ("true".equalsIgnoreCase(treeCleanProp) || "false".equalsIgnoreCase(treeCleanProp)) {
                w.write("  \"working_tree_clean\": " + Boolean.parseBoolean(treeCleanProp) + ",\n");
            } else {
                w.write("  \"working_tree_clean\": \"" + escapeJson(treeCleanProp) + "\",\n");
            }
            w.write("  \"src_main_tree_sha256\": \"" + escapeJson(srcTreeSha256) + "\",\n");
            w.write("  \"engine_jar_sha256\": \"" + escapeJson(engineJarSha256) + "\",\n");
            w.write("  \"jmh_jar_sha256\": \"" + escapeJson(jmhJarSha256) + "\",\n");
            w.write("  \"started_at_utc\": \"" + startedAt.toString() + "\",\n");
            w.write("  \"finished_at_utc\": \"" + finishedAt.toString() + "\",\n");
            w.write(String.format(Locale.ROOT, "  \"elapsed_seconds\": %.3f,%n", elapsedSec));
            w.write("  \"command_line\": \"" + escapeJson(cmdLine) + "\",\n");
            w.write("  \"mode\": \"" + escapeJson(mode) + "\",\n");
            w.write("  \"forks\": " + forks + ",\n");
            w.write("  \"warmup_iterations\": " + warmIters + ",\n");
            w.write("  \"measurement_iterations\": " + measIters + ",\n");
            w.write("  \"iteration_time_ms\": " + iterMs + ",\n");
            w.write("  \"jvm_args_jit_on\": \"-Xms512m -Xmx2g -XX:+UseG1GC -Dluava.jit=true -Dluava.jit.sync=true\",\n");
            w.write("  \"jvm_args_jit_off\": \"-Xms512m -Xmx2g -XX:+UseG1GC -Dluava.jit=false\",\n");
            w.write("  \"jdk\": \"" + escapeJson(javaInfo) + "\",\n");
            w.write("  \"os\": \"" + escapeJson(osInfo) + "\",\n");
            w.write("  \"cpu\": \"" + escapeJson(cpuModel) + "\",\n");
            w.write("  \"available_processors\": " + Runtime.getRuntime().availableProcessors() + "\n");
            w.write("}\n");
        }
    }

    private static Path detectOwnJarPath() {
        try {
            CodeSource cs = Stage0Runner.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                URI uri = cs.getLocation().toURI();
                Path p = Path.of(uri);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String sha256File(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return "unknown";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String sha256SourceTree(Path srcRoot) {
        if (srcRoot == null || !Files.isDirectory(srcRoot)) {
            return "unknown";
        }
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            List<Path> files = walk.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> srcRoot.relativize(p).toString().replace('\\', '/')))
                    .toList();
            for (Path f : files) {
                String rel = srcRoot.relativize(f).toString().replace('\\', '/');
                md.update(rel.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Files.readAllBytes(f));
                md.update((byte) 0);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String detectCpuModel() {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuinfo)) {
            try {
                for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        int colon = line.indexOf(':');
                        if (colon >= 0) {
                            return line.substring(colon + 1).trim();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return "unknown";
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static long settledUsedHeapBytes() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            try {
                Thread.sleep(25L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    private static void writeRetainedHeapProbeCsv(Path path, String commitSha) throws IOException {
        String largeTemplate = CorpusLoader.sourceFor("large_600kb");
        LuaState.compileChunk("return 1", "@warm.lua").close();
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write("commit_sha,scenario,retained_heap_mb,cached_entries,cached_bytes_mb\n");

            long base1 = settledUsedHeapBytes();
            CompiledChunk[] largeChunks = new CompiledChunk[16];
            for (int i = 0; i < largeChunks.length; i++) {
                String unsharedSrc = new String(largeTemplate + "\n-- instance " + i + "\n");
                largeChunks[i] = LuaState.compileChunk(unsharedSrc, "@large_" + i + ".lua");
            }
            long retainedLarge = Math.max(0L, settledUsedHeapBytes() - base1);
            w.write(String.format(Locale.ROOT, "%s,compiled_chunks_16x_large_600kb,%.2f,16,NaN%n",
                    commitSha, retainedLarge / (1024.0 * 1024.0)));
            if (largeChunks[0] == null) {
                throw new IllegalStateException();
            }
            for (CompiledChunk c : largeChunks) {
                c.close();
            }
            largeChunks = null;

            String commentPad = ("-- " + "0123456789abcdef".repeat(60) + "\n").repeat(1000);
            long base2 = settledUsedHeapBytes();
            CompiledChunk[] commentChunks = new CompiledChunk[32];
            for (int i = 0; i < commentChunks.length; i++) {
                String unsharedComments = new String(commentPad + "return " + i + "\n");
                commentChunks[i] = LuaState.compileChunk(unsharedComments, "@comments_" + i + ".lua");
            }
            long retainedComments = Math.max(0L, settledUsedHeapBytes() - base2);
            w.write(String.format(Locale.ROOT, "%s,compiled_chunks_32x_1mb_comments,%.2f,32,NaN%n",
                    commitSha, retainedComments / (1024.0 * 1024.0)));
            for (CompiledChunk c : commentChunks) {
                c.close();
            }
            commentChunks = null;

            long base3 = settledUsedHeapBytes();
            try (LuaState st = new LuaState()) {
                String bodyPad = "do local a = 1; local b = 2; local c = a + b end\n".repeat(180);
                for (int i = 0; i < 1500; i++) {
                    String script = bodyPad + "return " + i + "\n";
                    st.eval(script, "@churn_" + i + ".lua");
                }
                long retainedCache = Math.max(0L, settledUsedHeapBytes() - base3);
                long cachedCount = invokeOptionalStateGetter(st, "cachedProtoCount", false);
                long cachedBytes = invokeOptionalStateGetter(st, "cachedProtoBytes", true);
                w.write(String.format(
                        Locale.ROOT,
                        "%s,proto_cache_churn_1500x_8kb,%.2f,%s,%s%n",
                        commitSha,
                        retainedCache / (1024.0 * 1024.0),
                        cachedCount >= 0 ? Long.toString(cachedCount) : "NaN",
                        cachedBytes >= 0 ? String.format(Locale.ROOT, "%.2f", cachedBytes / (1024.0 * 1024.0)) : "NaN"));
            }
        }
    }

    private static long invokeOptionalStateGetter(LuaState st, String methodName, boolean returnsLong) {
        try {
            MethodType mt = MethodType.methodType(returnsLong ? long.class : int.class);
            MethodHandle mh = MethodHandles.publicLookup().findVirtual(LuaState.class, methodName, mt);
            return returnsLong ? (long) mh.invokeExact(st) : (int) mh.invokeExact(st);
        } catch (Throwable ignored) {
            return -1L;
        }
    }
}

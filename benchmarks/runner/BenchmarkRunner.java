/*
 * Luava Benchmark Runner
 * Measures: Cold (first-eval), Warm (after JVM reaches C2), Hot (avg of N hot runs)
 * Output: CSV to stdout for shell aggregation
 *
 * Usage:
 *   java -cp <classpath> BenchmarkRunner <lua_dir>
 *
 * Columns:
 *   task_id, task_name, cold_ms, warm_ms, hot_avg_ms, hot_min_ms, hot_max_ms
 */

import org.luava.runtime.LuaState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {

    // JVM warmup iterations before C2 JIT kicks in (empirically ~10k invocations)
    private static final int WARMUP_RUNS = 15;
    // Hot measurement iterations
    private static final int HOT_RUNS = 10;

    record TaskResult(
            String id,
            String name,
            double coldMs,
            double warmMs,
            double hotAvgMs,
            double hotMinMs,
            double hotMaxMs
    ) {}

    public static void main(String[] args) throws IOException {
        String luaDir = args.length > 0 ? args[0] : "benchmarks/lua";

        // Discover all .lua benchmark files, sorted by name
        List<Path> scripts = Files.list(Path.of(luaDir))
                .filter(p -> p.getFileName().toString().endsWith(".lua"))
                .sorted()
                .toList();

        System.err.println("[Luava BenchmarkRunner]");
        System.err.println("  JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.err.println("  Scripts: " + scripts.size());
        System.err.println("  Warmup runs: " + WARMUP_RUNS + ", Hot runs: " + HOT_RUNS);
        System.err.println();

        // CSV header to stdout
        System.out.println("task_id,task_name,cold_ms,warm_ms,hot_avg_ms,hot_min_ms,hot_max_ms");

        for (Path script : scripts) {
            String fileName = script.getFileName().toString();
            String taskId   = fileName.replaceFirst("_(.*)\\.lua$", "").replace(".lua", "");
            String taskName = fileName.replaceFirst("^\\d+_", "").replace(".lua", "").replace("_", " ");
            String code     = Files.readString(script);

            System.err.print("  Running [" + taskId + "] " + taskName + " ...");

            TaskResult result = runBenchmark(taskId, taskName, code);

            System.err.printf("  cold=%.2f warm=%.2f hot_avg=%.2f%n",
                    result.coldMs(), result.warmMs(), result.hotAvgMs());

            System.out.printf("%s,%s,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                    result.id(),
                    result.name(),
                    result.coldMs(),
                    result.warmMs(),
                    result.hotAvgMs(),
                    result.hotMinMs(),
                    result.hotMaxMs());
        }
    }

    private static TaskResult runBenchmark(String id, String name, String code) {
        // Each task gets its own independent LuaState to avoid cross-contamination
        LuaState state = new LuaState();

        // ---- COLD: very first execution (JVM still interpreting bytecode) ----
        double coldMs = measureMs(state, code);

        // ---- WARMUP: trigger C2 JIT without measuring ----
        for (int i = 0; i < WARMUP_RUNS; i++) {
            state.eval(code);
        }

        // ---- WARM: first post-warmup run (C2 compiled but cache cold) ----
        double warmMs = measureMs(state, code);

        // ---- HOT: steady-state average ----
        double[] hotTimes = new double[HOT_RUNS];
        for (int i = 0; i < HOT_RUNS; i++) {
            hotTimes[i] = measureMs(state, code);
        }

        double hotSum = 0, hotMin = Double.MAX_VALUE, hotMax = 0;
        for (double t : hotTimes) {
            hotSum += t;
            if (t < hotMin) hotMin = t;
            if (t > hotMax) hotMax = t;
        }

        return new TaskResult(id, name, coldMs, warmMs, hotSum / HOT_RUNS, hotMin, hotMax);
    }

    private static double measureMs(LuaState state, String code) {
        long t0 = System.nanoTime();
        state.eval(code);
        return (System.nanoTime() - t0) / 1_000_000.0;
    }
}


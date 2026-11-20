import com.sun.management.ThreadMXBean;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaState.CompiledChunk;
import org.luava.runtime.Stats;
import org.luava.runtime.jit.JitCompiler;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class Bench {
    public static void main(String[] a) throws Exception {
        String file = a[0];
        int warm = Integer.parseInt(a[1]);
        int iters = Integer.parseInt(a[2]);
        String mode = a.length > 3 ? a[3] : System.getenv().getOrDefault("BENCH_MODE", "exec");
        boolean guarded = "true".equalsIgnoreCase(System.getenv("BENCH_GUARDED"));

        String code = Files.readString(Path.of(file));
        String chunkName = "@" + Path.of(file).getFileName();
        LuaState st = new LuaState();
        if (guarded) {
            st.instructionLimit(1_000_000_000L);
        }
        CompiledChunk precompiled = "exec".equals(mode) ? LuaState.compileChunk(code, chunkName) : null;

        // Cold pass (first load/eval)
        long coldT0 = System.nanoTime();
        runOnce(st, precompiled, code, chunkName, mode);
        double coldMs = (System.nanoTime() - coldT0) / 1e6;

        for (int i = 0; i < Math.max(0, warm - 1); i++) {
            runOnce(st, precompiled, code, chunkName, mode);
        }

        ThreadMXBean tmxb = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().threadId();
        boolean trackAlloc = tmxb.isThreadAllocatedMemorySupported();

        double[] samples = new double[iters];
        double best = Double.MAX_VALUE;
        double sum = 0;
        long allocSum = 0;

        for (int i = 0; i < iters; i++) {
            long alloc0 = trackAlloc ? tmxb.getThreadAllocatedBytes(tid) : 0L;
            long t0 = System.nanoTime();
            runOnce(st, precompiled, code, chunkName, mode);
            double ms = (System.nanoTime() - t0) / 1e6;
            long alloc1 = trackAlloc ? tmxb.getThreadAllocatedBytes(tid) : 0L;
            samples[i] = ms;
            best = Math.min(best, ms);
            sum += ms;
            if (trackAlloc && alloc1 >= alloc0) {
                allocSum += (alloc1 - alloc0);
            }
        }

        Arrays.sort(samples);
        double median = (iters % 2 == 1)
                ? samples[iters / 2]
                : (samples[iters / 2 - 1] + samples[iters / 2]) / 2.0;
        double p25 = samples[Math.max(0, iters / 4)];
        double p75 = samples[Math.min(iters - 1, (iters * 3) / 4)];
        double allocKb = trackAlloc ? (allocSum / (double) iters) / 1024.0 : -1.0;

        Stats stats = st.stats();
        JitCompiler.Telemetry jitStats = JitCompiler.telemetry();
        System.out.printf(
                "%-28s best=%8.2f avg=%8.2f median=%8.2f iqr=%6.2f cold=%8.2f allocKB=%9.1f jitHits=%d jitCompiled=%d deopts=%d mode=%s ms%n",
                Path.of(file).getFileName(),
                best,
                sum / iters,
                median,
                p75 - p25,
                coldMs,
                allocKb,
                stats.jitHits,
                jitStats.compiledCount(),
                stats.deoptCount,
                mode);

        if (precompiled != null) {
            precompiled.close();
        }
        st.close();
    }

    private static void runOnce(LuaState st, CompiledChunk precompiled, String code, String chunkName, String mode) {
        if (precompiled != null) {
            st.eval(precompiled);
        } else if ("e2e".equals(mode)) {
            try (CompiledChunk c = LuaState.compileChunk(code, chunkName)) {
                st.eval(c);
            }
        } else {
            st.eval(code, chunkName);
        }
    }
}

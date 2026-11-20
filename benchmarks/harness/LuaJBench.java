import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class LuaJBench {
    public static void main(String[] a) throws Exception {
        String file = a[0];
        int warm = Integer.parseInt(a[1]);
        int iters = Integer.parseInt(a[2]);
        String mode = a.length > 3 ? a[3] : System.getenv().getOrDefault("BENCH_MODE", "exec");

        String code = Files.readString(Path.of(file));
        String chunkName = "@" + Path.of(file).getFileName();
        Globals globals = JsePlatform.standardGlobals();
        LuaValue precompiled = "exec".equals(mode) ? globals.load(code, chunkName) : null;

        long coldT0 = System.nanoTime();
        runOnce(globals, precompiled, code, chunkName);
        double coldMs = (System.nanoTime() - coldT0) / 1e6;

        for (int i = 0; i < Math.max(0, warm - 1); i++) {
            runOnce(globals, precompiled, code, chunkName);
        }

        double[] samples = new double[iters];
        double best = Double.MAX_VALUE;
        double sum = 0;
        for (int i = 0; i < iters; i++) {
            long t0 = System.nanoTime();
            runOnce(globals, precompiled, code, chunkName);
            double ms = (System.nanoTime() - t0) / 1e6;
            samples[i] = ms;
            best = Math.min(best, ms);
            sum += ms;
        }
        Arrays.sort(samples);
        double median = (iters % 2 == 1)
                ? samples[iters / 2]
                : (samples[iters / 2 - 1] + samples[iters / 2]) / 2.0;
        double p25 = samples[Math.max(0, iters / 4)];
        double p75 = samples[Math.min(iters - 1, (iters * 3) / 4)];
        System.out.printf("%-28s best=%8.2f avg=%8.2f median=%8.2f iqr=%6.2f cold=%8.2f mode=%s ms%n",
                Path.of(file).getFileName(), best, sum / iters, median, p75 - p25, coldMs, mode);
    }

    private static void runOnce(Globals globals, LuaValue precompiled, String code, String chunkName) {
        if (precompiled != null) {
            precompiled.call();
        } else {
            globals.load(code, chunkName).call();
        }
    }
}

import org.luaj.vm2.Globals;
import org.luaj.vm2.lib.jse.JsePlatform;
import java.nio.file.*;
public class LuaJBench {
    public static void main(String[] a) throws Exception {
        String file = a[0];
        int warm = Integer.parseInt(a[1]);
        int iters = Integer.parseInt(a[2]);
        Globals globals = JsePlatform.standardGlobals();
        for (int i = 0; i < warm; i++) globals.loadfile(file).call();
        double best = 1e18, sum = 0;
        for (int i = 0; i < iters; i++) {
            long t = System.nanoTime();
            globals.loadfile(file).call();
            double ms = (System.nanoTime() - t) / 1e6;
            best = Math.min(best, ms); sum += ms;
        }
        System.out.printf("%-28s best=%8.2f avg=%8.2f ms%n", Path.of(file).getFileName(), best, sum / iters);
    }
}

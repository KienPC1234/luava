import org.luava.runtime.LuaState;
import java.nio.file.*;
public class Bench {
    public static void main(String[] a) throws Exception {
        String code = Files.readString(Path.of(a[0]));
        int warm = Integer.parseInt(a[1]);
        int iters = Integer.parseInt(a[2]);
        LuaState st = new LuaState();
        for (int i=0;i<warm;i++) st.eval(code);
        double best=1e18, sum=0;
        for (int i=0;i<iters;i++){
            long t=System.nanoTime(); st.eval(code); double ms=(System.nanoTime()-t)/1e6;
            best=Math.min(best,ms); sum+=ms;
        }
        System.out.printf("%-28s best=%8.2f avg=%8.2f ms%n", Path.of(a[0]).getFileName(), best, sum/iters);
    }
}

package org.luava.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.luava.binding.annotation.LuaField;
import org.luava.binding.annotation.LuaMethod;
import org.luava.binding.annotation.LuaModule;
import org.luava.binding.annotation.LuaParam;
import org.luava.binding.annotation.LuaReturn;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luava-only advanced suite: deep Java interop (Lua functions as SAM
 * arguments, annotation modules, live proxies, java.* helpers), execution
 * timeouts, and EmmyDoc generation. LuaJ 5.2 has no equivalent for any of
 * these; every test executes real behavior on both sides of the bridge.
 */
public class LuavaAdvancedInteropTest {
    private LuaState state;

    @BeforeEach
    void setUp() {
        state = new LuaState();
    }

    public static class EventBus {
        public void onEvent(Consumer<String> handler) {
            handler.accept("hello");
        }

        public String map(Function<String, String> fn) {
            return fn.apply("abc");
        }
    }

    @Test
    void luaFunctionAsJavaConsumerArgument() {
        state.setLive("bus", new EventBus());
        state.eval("bus.onEvent(function(s) seen = s end)");
        assertEquals("hello", state.eval("return seen").toLuaString());
    }

    @Test
    void luaFunctionAsJavaMapperWithReturn() {
        state.setLive("bus", new EventBus());
        LuaValue res = state.eval("return bus.map(function(s) return s .. '!' end)");
        assertEquals("abc!", res.toLuaString());
    }

    @Test
    void luaComparatorSortsLiveJavaList() {
        List<Integer> xs = new ArrayList<>(List.of(3, 1, 2));
        state.setLive("xs", xs);
        state.eval("""
            local Cmp = java.proxy('java.util.Comparator', function(a, b)
              if a < b then return -1 elseif a > b then return 1 else return 0 end
            end)
            xs.sort(Cmp)
            """);
        assertEquals(List.of(1, 2, 3), xs);
    }

    @LuaModule(name = "Calc", description = "Test calculator")
    public static class CalcService {
        @LuaField(name = "VERSION", description = "Version", readOnly = true)
        public String version = "1.0";

        @LuaMethod(description = "Add two numbers")
        @LuaReturn(type = "integer", description = "Sum")
        public long add(
                @LuaParam(name = "a", type = "integer", description = "First") long a,
                @LuaParam(name = "b", type = "integer", description = "Second") long b) {
            return a + b;
        }
    }

    @Test
    void annotationModuleCallAndField() {
        state.registerModule(new CalcService());
        assertEquals(7L, state.eval("return Calc.add(3, 4)").toLong());
        assertEquals("1.0", state.eval("return Calc.VERSION").toLuaString());
    }

    @Test
    void emmyDocGenerationAndExport(@TempDir Path dir) throws Exception {
        state.registerModule(new CalcService());
        String docs = state.generateEmmyDocs();
        assertTrue(docs.contains("---@class Calc"));
        assertTrue(docs.contains("function Calc.add(a, b)"));
        assertTrue(docs.contains("---@param a integer"));
        assertTrue(docs.contains("---@return integer"));

        state.exportEmmyDocs(dir);
        String back = java.nio.file.Files.readString(dir.resolve("Calc.lua"));
        assertTrue(back.contains("---@class Calc"));
    }

    @Test
    void registerFunctionVariants() {
        state.registerFunction("answer", () -> 42);
        assertEquals(42L, state.eval("return answer()").toLong());

        state.registerFunction("add", Long.class, Long.class, (a, b) -> a + b);
        assertEquals(30L, state.eval("return add(10, 20)").toLong());

        AtomicReference<String> seen = new AtomicReference<>();
        state.registerFunction("capture", String.class, (Consumer<String>) seen::set);
        state.eval("capture('hi')");
        assertEquals("hi", seen.get());
    }

    @Test
    void staticMethodViaImportAndJavaNew() {
        assertEquals(7L, state.eval("""
            local Math = java.import("java.lang.Math")
            return Math.max(3, 7)
            """).toLong());
        assertEquals(5L, state.eval("""
            local Math = java.import("java.lang.Math")
            return Math.abs(-5)
            """).toLong());
        assertEquals(2L, state.eval("""
            local list = java.new("java.util.ArrayList")
            list.add("a")
            list.add("b")
            return list.size()
            """).toLong());
    }

    @Test
    void liveSetProxy() {
        Set<String> set = new HashSet<>();
        state.setLive("s", set);
        state.eval("s.add('x')");
        state.eval("s.add('y')");
        assertEquals(2, set.size());
        assertTrue(state.eval("return s.contains('x')").toBoolean());
        assertEquals(2L, state.eval("return s.size()").toLong());
    }

    @Test
    void javaArrayAndInstanceof() {
        LuaValue len = state.eval("""
            local arr = java.array("int", 3)
            arr[1] = 10
            arr[2] = 20
            arr[3] = 30
            total = arr[1] + arr[2] + arr[3]
            ok = java.instanceof(arr, "no.such.Class") == false and java.instanceof(arr, "java.lang.Object")
            return total
            """);
        assertEquals(60L, len.toLong());
        assertTrue(state.eval("return ok").toBoolean());
    }

    @Test
    void javaMethodReturningSetConvertsToTable() {
        state.setLive("prov", new SetProvider());
        LuaValue t = state.eval("return prov.tags()");
        assertTrue(t.isTable());
        assertEquals(2L, state.eval("local t = prov.tags() local n = 0 for _ in pairs(t) do n = n + 1 end return n").toLong());
    }

    public static class SetProvider {
        public Set<String> tags() {
            return new HashSet<>(List.of("a", "b"));
        }
    }

    @Test
    void liveCollectionsSupportLengthOperator() {
        List<String> list = new ArrayList<>(List.of("a", "b"));
        state.setLive("xs", list);
        assertEquals(2L, state.eval("return #xs").toLong());
        state.eval("xs.add('c')");
        assertEquals(3L, state.eval("return #xs").toLong());
        assertEquals(3, list.size());
    }

    @Test
    void mutableCharSequenceStaysLiveForChaining() {
        LuaValue res = state.eval("""
            local sb = java.new("java.lang.StringBuilder", "hi")
            local r1 = sb.append("!")
            same = (r1 == sb)
            return sb.append("?").toString()
            """);
        assertEquals("hi!?", res.toLuaString());
        assertTrue(state.eval("return same").toBoolean());
    }

    @Test
    void evalTimeoutFires() {
        assertEquals(6L, state.eval("return 1 + 2 + 3").toLong());
        assertThrows(TimeoutException.class, () -> state.evalWithTimeout(
                "local s = 0 for i = 1, 200000000 do s = s + i end return s",
                java.time.Duration.ofMillis(50)));
    }

    public static class Summer {
        public long sumList(List<Long> xs) {
            long s = 0;
            for (long x : xs) s += x;
            return s;
        }
    }

    @Test
    void luaTablePassedToJavaListParam() {
        state.setLive("summer", new Summer());
        assertEquals(6L, state.eval("return summer.sumList({1, 2, 3})").toLong());
    }
}

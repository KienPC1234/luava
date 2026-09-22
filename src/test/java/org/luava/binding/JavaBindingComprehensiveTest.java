/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaBindingComprehensiveTest {
    private LuaState state;

    @BeforeEach
    void setUp() {
        state = new LuaState();
    }

    public static class OverloadDemo {
        public String process(int x) {
            return "int:" + x;
        }

        public String process(String s) {
            return "string:" + s;
        }

        public String process(double d) {
            return "double:" + d;
        }

        public String process(int x, String s) {
            return "int_string:" + x + "_" + s;
        }
    }

    public enum Status {
        PENDING,
        RUNNING,
        COMPLETED
    }

    public static class ServiceWithEnum {
        public Status currentStatus = Status.PENDING;

        public void setStatus(Status s) {
            this.currentStatus = s;
        }

        public Status getStatus() {
            return currentStatus;
        }
    }

    @Test
    void testMethodOverloadResolution() {
        state.setLive("demo", new OverloadDemo());

        LuaValue res1 = state.eval("return demo.process(42)");
        assertEquals("int:42", res1.toLuaString());

        LuaValue res2 = state.eval("return demo.process('luava')");
        assertEquals("string:luava", res2.toLuaString());

        LuaValue res3 = state.eval("return demo.process(3.14)");
        assertEquals("double:3.14", res3.toLuaString());

        LuaValue res4 = state.eval("return demo.process(7, 'items')");
        assertEquals("int_string:7_items", res4.toLuaString());
    }

    @Test
    void testJavaImportAndConstructorInstantiation() {
        String code = """
            local ArrayList = java.import("java.util.ArrayList")
            local list = ArrayList.new()
            list.add("first")
            list.add("second")
            return list.size()
            """;
        LuaValue res = state.eval(code);
        assertEquals(2L, res.toLong());
    }

    @Test
    void testLiveListProxyMutations() {
        List<String> list = new ArrayList<>();
        list.add("original");
        state.setLive("list", list);

        state.eval("list.add('second')");
        state.eval("list[1] = 'modified'");

        assertEquals(2, list.size());
        assertEquals("modified", list.get(0));
        assertEquals("second", list.get(1));

        LuaValue readVal = state.eval("return list[2]");
        assertEquals("second", readVal.toLuaString());
    }

    @Test
    void testLiveMapProxyMutations() {
        Map<String, Object> map = new HashMap<>();
        map.put("initialKey", "initialVal");
        state.setLive("map", map);

        state.eval("map['newKey'] = 42");
        state.eval("map['another'] = 'hello'");

        assertEquals(42L, map.get("newKey"));
        assertEquals("hello", map.get("another"));

        LuaValue readVal = state.eval("return map.newKey");
        assertEquals(42L, readVal.toLong());
    }

    @Test
    void testLiveArrayAccess() {
        int[] numbers = new int[]{10, 20, 30};
        state.setLive("arr", numbers);

        LuaValue first = state.eval("return arr[1]");
        assertEquals(10L, first.toLong());

        state.eval("arr[2] = 999");
        assertEquals(999, numbers[1]);

        LuaValue len = state.eval("return arr.length");
        assertEquals(3L, len.toLong());
    }

    @Test
    void testSamFunctionalInterfaceConversion() {
        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<String> consumer = s -> {
            if ("triggered".equals(s)) called.set(true);
        };
        state.setLive("consumer", consumer);

        state.eval("consumer('triggered')");
        assertTrue(called.get());

        // Function<Integer, Integer> in Java called from Lua
        Function<Integer, Integer> doubler = x -> x * 2;
        state.setLive("doubler", doubler);
        LuaValue doubleRes = state.eval("return doubler(21)");
        assertEquals(42L, doubleRes.toLong());
    }

    @Test
    void testEnumConversion() {
        ServiceWithEnum service = new ServiceWithEnum();
        state.setLive("svc", service);

        state.eval("svc.setStatus('RUNNING')");
        assertEquals(Status.RUNNING, service.getStatus());

        LuaValue statusVal = state.eval("return svc.getStatus()");
        assertEquals("RUNNING", statusVal.toLuaString());
    }

    @Test
    void testShebangAndEmptyStatements() {
        String code = """
            #!/usr/bin/lua
            ;;;
            local a = 10;;;
            local b = 20;
            ;;
            return a + b;
            """;
        LuaValue res = state.eval(code);
        assertEquals(30L, res.toLong());
    }

    @Test
    void testHexFloatAndIntegerOverflowFallback() {
        LuaValue hexFloat1 = state.eval("return 0xF0.0");
        assertEquals(240.0, hexFloat1.toDouble(), 1e-9);

        LuaValue hexFloat2 = state.eval("return 0x1.fp3");
        assertEquals(15.5, hexFloat2.toDouble(), 1e-9);

        LuaValue overflowInt = state.eval("return 10000000000000000000000");
        assertTrue(overflowInt.isFloat());
        assertEquals(1e22, overflowInt.toDouble(), 1e15);
    }

    @Test
    void testStringMetatableMethodCalling() {
        LuaValue res = state.eval("return ('luava'):sub(1, 4)");
        assertEquals("luav", res.toLuaString());

        LuaValue lenRes = state.eval("return ('hello'):len()");
        assertEquals(5L, lenRes.toLong());
    }

    @Test
    void testTableFloatKeyEquivalenceAndMove() {
        // Lua 5.4: 1.0 and 1 must access identical table slot
        state.eval("t = {}; t[1] = 'val'");
        LuaValue readFloat = state.eval("return t[1.0]");
        assertEquals("val", readFloat.toLuaString());

        // table.move test
        String moveCode = """
            local a = {10, 20, 30}
            local b = {}
            table.move(a, 1, 3, 1, b)
            return b[1] + b[2] + b[3]
            """;
        LuaValue sumRes = state.eval(moveCode);
        assertEquals(60L, sumRes.toLong());
    }

    @Test
    void hostFunctionExceptionsAreWrappedNotLeaked() {
        state.registerFunction("boom", () -> {
            throw new IllegalStateException("host boom");
        });
        // The raw Java exception must never escape to the host or Lua; it is
        // wrapped in a LuaException (AGENTS.md IV.2).
        LuaException ex = assertThrows(LuaException.class, () -> state.eval("return boom()"));
        assertTrue(ex.getMessage().contains("host boom"));
        // pcall sees a normal Lua error, not a leaked Java throwable.
        LuaValue r = state.eval("local ok, err = pcall(boom); return tostring(ok)");
        assertEquals("false", r.toLuaString());
    }

    @Test
    void nonNumericValueIsRejectedNotCoercedToZero() {
        state.registerFunction("echo", Long.class, x -> x);
        assertEquals(123L, state.eval("return echo('123')").toLong());
        // Lua's luaL_checkinteger rejects these instead of returning 0.
        assertThrows(LuaException.class, () -> state.eval("return echo('abc')"));
        assertThrows(LuaException.class, () -> state.eval("return echo(true)"));
        assertThrows(LuaException.class, () -> state.eval("return echo({})"));
    }

    /**
     * {@code obj.method} is re-read on every loop iteration; the resolved
     * invoker must be memoized so the read returns the same function object
     * and does not rebuild the candidate set. Semantics (including overload
     * resolution) must be unchanged.
     */
    @Test
    void methodInvokerIsMemoizedPerUserdata() {
        state.setLive("demo", new OverloadDemo());
        LuaValue first = state.eval("return demo.process");
        LuaValue second = state.eval("return demo.process");
        assertTrue(first == second,
                "repeated property reads must return the cached method invoker");
        assertEquals("string:x", state.eval("return demo.process('x')").toLuaString());
        assertEquals("int:5", state.eval("return demo.process(5)").toLuaString());
    }

    /**
     * A Lua function passed where a Java SAM is expected must still adapt
     * after the MethodHandle-based, cached implementation.
     */
    @Test
    void samProxyUsesCachedMethodHandle() {
        AtomicBoolean called = new AtomicBoolean(false);
        Consumer<String> consumer = s -> called.set(true);
        state.setLive("consumer", consumer);
        state.eval("consumer('x')");
        assertTrue(called.get());
        // The adapter created from a Lua function must work in both directions.
        state.registerFunction("runTwice", Runnable.class, (Runnable r) -> { r.run(); r.run(); });
        state.eval("local n=0 runTwice(function() n=n+1 end) seen=n");
        assertEquals(2L, state.eval("return seen").toLong());
    }

    /**
     * A Java object implementing a SAM interface must be callable from Lua
     * through the cached handle, with primitive argument coercion preserved.
     */
    @Test
    void samObjectCallFromLuaNarrowsLongToInt() {
        state.setLive("op", (java.util.function.IntUnaryOperator) x -> x * 3);
        assertEquals(63L, state.eval("return op(21)").toLong());
    }

    /** A method whose body throws must be invoked exactly once. */
    public static class ThrowingSvc {
        public int calls = 0;

        public void boom(int x) {
            calls++;
            throw new IllegalArgumentException("target threw");
        }

        public void boomCast(Object x) {
            calls++;
            throw new ClassCastException("target cast");
        }
    }

    /**
     * Regression: {@code MethodHandle.invokeWithArguments} propagates the
     * target's own {@code IllegalArgumentException}/{@code ClassCastException}
     * unchanged (unlike {@code Method.invoke}, which wraps them in
     * {@code InvocationTargetException}), so the old "narrow longs and retry"
     * catch re-invoked a method that threw and double-applied its side effects.
     * The method body must run exactly once.
     */
    @Test
    void throwingJavaMethodIsInvokedExactlyOnce() {
        ThrowingSvc svc = new ThrowingSvc();
        state.setLive("svc", svc);
        assertThrows(LuaException.class, () -> state.eval("svc.boom(1)"));
        assertEquals(1, svc.calls, "IllegalArgumentException from the target must not retrigger a call");
        assertThrows(LuaException.class, () -> state.eval("svc.boomCast('x')"));
        assertEquals(2, svc.calls, "ClassCastException from the target must not retrigger a call");
    }
}

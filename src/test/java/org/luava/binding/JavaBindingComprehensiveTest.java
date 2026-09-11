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
}

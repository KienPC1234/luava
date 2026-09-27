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
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overload resolution specificity/ambiguity and LuaJ-compatible multi-interface
 * proxies. Both were the two places LuaJ was ahead of Luava before this work.
 */
public class InteropOverloadAndProxyTest {
    private LuaState state;

    @BeforeEach
    void setUp() {
        state = new LuaState();
    }

    public static class Range {
        public String r(int x) {
            return "int";
        }

        public String r(long x) {
            return "long";
        }
    }

    /** CharSequence and Serializable are unrelated, so a String is ambiguous. */
    public static class Ambiguous {
        public String amb(CharSequence x) {
            return "cs";
        }

        public String amb(Serializable x) {
            return "ser";
        }
    }

    public static class Specific {
        public String go(Object x) {
            return "object";
        }

        public String go(Number x) {
            return "number";
        }

        public String go(Integer x) {
            return "integer";
        }

        public String go(int x) {
            return "int";
        }
    }

    public static class Var {
        public String v(int x) {
            return "int";
        }

        public String v(int... xs) {
            return "varargs";
        }
    }

    public static class NullPick {
        public String n(Object x) {
            return "object";
        }

        public String n(String x) {
            return "string";
        }
    }

    public static class SamPick {
        public String s(Object x) {
            return "object";
        }

        public String s(Runnable x) {
            return "runnable";
        }
    }

    @Test
    void integerPrefersIntWhenItFits() {
        state.setLive("r", new Range());
        assertEquals("int", state.eval("return r.r(5)").toLuaString());
        assertEquals("int", state.eval("return r.r(2147483647)").toLuaString());
    }

    /** Only an {@code int} overload: a float must not wrap around to fit it. */
    public static class IntOnly {
        public String go(int x) {
            return "int:" + x;
        }
    }

    /**
     * A float argument must obey the same range rule as an integer one:
     * {@code 2^31} cannot be an {@code int}. It used to be accepted and
     * silently wrapped to {@code -2147483648}, while the integer {@code
     * 2147483648} was correctly rejected.
     */
    @Test
    void floatArgumentOutOfIntRangeIsRejectedNotWrapped() {
        state.setLive("io", new IntOnly());
        assertEquals("int:3", state.eval("return io.go(3)").toLuaString());
        assertEquals("int:3", state.eval("return io.go(3.0)").toLuaString());
        // Fractional values are truncated (documented), not rejected.
        assertEquals("int:3", state.eval("return io.go(3.9)").toLuaString());
        // Out-of-range values must raise, exactly like the integer literal
        // 2147483648, instead of wrapping to a negative number.
        LuaException viaFloat = assertThrows(LuaException.class,
                () -> state.eval("return io.go(2^31)"));
        LuaException viaInt = assertThrows(LuaException.class,
                () -> state.eval("return io.go(2147483648)"));
        assertTrue(viaFloat.getMessage().contains("No matching method"), viaFloat.getMessage());
        assertTrue(viaInt.getMessage().contains("No matching method"), viaInt.getMessage());
    }

    @Test
    void integerFallsBackToLongWhereItFits() {
        state.setLive("r", new Range());
        // 2^31 does not fit int, so only long is applicable.
        assertEquals("long", state.eval("return r.r(2147483648)").toLuaString());
        assertEquals("long", state.eval("return r.r(-2147483649)").toLuaString());
    }

    @Test
    void primitiveBeatsWrapper() {
        state.setLive("s", new Specific());
        assertEquals("int", state.eval("return s.go(7)").toLuaString());
    }

    @Test
    void unrelatedInterfacesAreAmbiguous() {
        state.setLive("a", new Ambiguous());
        LuaException e = assertThrows(LuaException.class, () -> state.eval("return a.amb('x')"));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
    }

    @Test
    void varargsLosesToExactArity() {
        state.setLive("v", new Var());
        assertEquals("int", state.eval("return v.v(3)").toLuaString());
        // Two arguments only fit the varargs overload.
        assertEquals("varargs", state.eval("return v.v(3, 4)").toLuaString());
    }

    @Test
    void nilPrefersReferenceOverObject() {
        state.setLive("n", new NullPick());
        assertEquals("string", state.eval("return n.n(nil)").toLuaString());
    }

    @Test
    void luaFunctionPrefersSamOverObject() {
        state.setLive("s", new SamPick());
        assertEquals("runnable", state.eval("return s.s(function() end)").toLuaString());
    }

    public interface Greeter {
        String greet(String who);
    }

    public interface Counter {
        int count();
    }

    @Test
    void multiInterfaceProxyFromLuaTable() {
        LuaValue proxy = state.eval("""
            return java.proxy('org.luava.binding.InteropOverloadAndProxyTest$Greeter',
                              'org.luava.binding.InteropOverloadAndProxyTest$Counter',
                              {
                                greet = function(self, who) return 'hi ' .. who end,
                                count = function(self) return 3 end
                              })
            """);
        Object raw = ((LuaUserdata) proxy).getJavaInstance();
        assertInstanceOf(Greeter.class, raw);
        assertInstanceOf(Counter.class, raw);
        assertEquals("hi lua", ((Greeter) raw).greet("lua"));
        assertEquals(3, ((Counter) raw).count());
    }

    @Test
    void createProxyAliasMatchesLuaj() {
        // LuaJ spells this luajava.createProxy(interface..., table).
        LuaValue proxy = state.eval("""
            local t = { run = function(self) ran = true end }
            return luajava.createProxy('java.lang.Runnable', t)
            """);
        Object raw = ((LuaUserdata) proxy).getJavaInstance();
        assertInstanceOf(Runnable.class, raw);
        ((Runnable) raw).run();
        assertEquals(true, state.eval("return ran").toBoolean());
    }

    @Test
    void multiInterfaceProxyRejectsFunctionHandler() {
        // A single Lua function cannot dispatch across several interfaces.
        assertThrows(LuaException.class, () -> state.eval("""
            return java.proxy('java.lang.Runnable',
                              'org.luava.binding.InteropOverloadAndProxyTest$Counter',
                              function() end)
            """));
    }

    @Test
    void proxyRejectsNonInterface() {
        assertThrows(LuaException.class, () -> state.eval("""
            return java.proxy('java.lang.String', { })
            """));
    }

    @Test
    void proxyTableReceivesSelfAsFirstArgument() {
        LuaValue proxy = state.eval("""
            local t = {}
            t.run = function(self) assert(self == t); self.ran = true end
            return java.proxy('java.lang.Runnable', t)
            """);
        Object raw = ((LuaUserdata) proxy).getJavaInstance();
        ((Runnable) raw).run();
    }

    @Test
    void liveProxyStillUsableAfterResolverChange() {
        LuaTable table = new LuaTable();
        table.rawset(org.luava.runtime.LuaString.valueOf("greet"),
                org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaString.valueOf("ok")));
        Object proxy = org.luava.runtime.interop.DynamicProxyBridge.createProxy(Greeter.class, table);
        assertEquals("ok", ((Greeter) proxy).greet("x"));
    }
}

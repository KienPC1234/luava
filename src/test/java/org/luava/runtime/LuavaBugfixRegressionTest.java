/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the "hard to use / bug" audit fixes. Each test
 * reproduces the original defect so a regression fails loudly.
 */
public class LuavaBugfixRegressionTest {

    @Test
    void hostUnicodeStringHasLuaByteLength() {
        LuaState s = new LuaState();
        s.set("name", "caf\u00e9"); // 4 Java chars, 5 UTF-8 bytes
        assertEquals(5, s.eval("return #name").toLong());
        assertEquals("99,97,102,195,169",
                s.eval("return table.concat({string.byte(name,1,-1)}, ',')").toLuaString());
        assertEquals(4, s.eval("return utf8.len(name)").toLong());
        assertEquals("caf\u00e9", s.get("name", String.class));
    }

    @Test
    void hostStringRoundTripsThroughLua() {
        LuaState s = new LuaState();
        s.set("greeting", "xin ch\u00e0o");
        // The Lua-side value is a byte string (5 ASCII + 2 UTF-8 bytes); the
        // host bridge decodes it back to the original text.
        assertEquals("xin ch\u00e0o!",
                org.luava.binding.LuaDataConverter.toJavaString(s.eval("return greeting .. '!'").toLuaString()));
        assertEquals("xin ch\u00e0o", s.get("greeting", String.class));
    }

    @Test
    void binaryDataShouldBePassedAsByteArrayNotString() {
        // Host Strings are text, so they are UTF-8 encoded on the way in.
        // A binary blob must be handed over as a byte[] (live userdata),
        // which preserves bytes exactly; this test pins down that the String
        // path is text and that byte[] is the binary escape hatch.
        LuaState s = new LuaState();
        byte[] blob = new byte[]{(byte) 0xC3, (byte) 0x28};
        s.set("blob", blob);
        assertEquals(2, s.eval("return #blob").toLong());
        s.set("text", "caf\u00e9");
        assertEquals(5, s.eval("return #text").toLong());
    }

    @Test
    void basicMetatablesAreIsolatedAcrossStates() {
        LuaState first = new LuaState();
        first.eval("debug.setmetatable('', {__index = function(_, key) return 'A:' .. key end})");
        first.eval("debug.setmetatable(1, {tag = 'A'})");
        LuaState second = new LuaState();

        assertEquals("A:foo", first.eval("return ('x').foo").toLuaString());
        assertEquals("A", first.eval("return debug.getmetatable(1).tag").toLuaString());
        assertTrue(second.eval("return ('x').foo == nil").toBoolean());
        assertEquals("X", second.eval("return ('x'):upper()").toLuaString());
        assertTrue(second.eval("return debug.getmetatable(1) == nil").toBoolean());
    }

    @Test
    void nestedStateEvalRestoresBasicMetatables() {
        LuaState first = new LuaState();
        first.eval("debug.setmetatable('', {__index = function(_, key) return 'A:' .. key end})");
        LuaState second = new LuaState();
        first.registerFunction("useSecond", (LuaInvokable) args -> second.eval(
                "debug.setmetatable('', {__index = function(_, key) return 'B:' .. key end}); "
                + "return ('x').foo"));
        assertEquals("B:foo", first.eval("return useSecond()").toLuaString());
        assertEquals("A:foo", first.eval("return ('x').foo").toLuaString());
        assertEquals("B:foo", second.eval("return ('x').foo").toLuaString());
    }

    @Test
    void explicitCollectionRunsOnlyRequestingStateFinalizers() {
        LuaState first = new LuaState();
        AtomicLong finalizations = new AtomicLong();
        first.registerFunction("fin", (LuaInvokable) args -> {
            finalizations.incrementAndGet();
            return LuaNil.NIL;
        });
        first.eval("t = setmetatable({}, {__gc = function(x) fin() end}); t = nil");

        LuaState second = new LuaState();
        second.eval("collectgarbage('collect')");
        assertEquals(0, finalizations.get());

        first.eval("collectgarbage('collect')");
        assertEquals(1, finalizations.get());
        assertTrue(first.eval("return collectgarbage('collect')").toBoolean());
        assertEquals(1, finalizations.get());
    }

    @Test
    void runtimeStringsDoNotGrowTheCanonicalPool() throws Exception {
        // Runtime string creation (valueOf) must never pool: script-generated
        // data would otherwise pin the canonical map forever. Only cold
        // interned() paths (compiler constants, metamethod/stdlib names) may.
        Field f = LuaString.class.getDeclaredField("INTERN_POOL");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> pool = (Map<Object, Object>) f.get(null);
        int before = pool.size();
        for (int i = 0; i < 20_000; i++) {
            LuaString.valueOf("runtime_key_" + i);
        }
        assertEquals(before, pool.size(),
                "valueOf must not add entries to the canonical pool");
    }

    @Test
    void canonicalKeysStayIdenticalAcrossLookups() {
        // Regression: a collectable intern pool let equal keys become
        // distinct objects, degrading every table lookup to String.equals.
        // Reserved names must stay reference-identical forever.
        LuaString first = LuaString.interned("__index");
        System.gc();
        LuaString second = LuaString.interned("__index");
        assertTrue(first == second, "interned keys must be canonical");
        assertTrue(first == LuaValue.Meta.INDEX, "Meta.INDEX must share the canonical key");
    }

    @Test
    void shortStringsAreStillInterned() {
        LuaState s = new LuaState();
        LuaValue r = s.eval("""
            local s1 = string.rep('a', 10)
            local s2 = string.rep('aa', 5)
            return string.format('%p', s1) == string.format('%p', s2)
            """);
        assertTrue(r.toBoolean());
    }

    @Test
    void maxAllocationBytesCapsConcatenation() {
        LuaState s = new LuaState().maxAllocationBytes(1024);
        LuaValue r = s.eval(
                "local ok, e = pcall(function() local x='a' for i=1,20 do x=x..x end end); "
                + "return tostring(ok) .. ':' .. tostring(e)");
        assertTrue(r.toLuaString().startsWith("false:"), r.toLuaString());
    }

    @Test
    void evalWithTimeoutDoesNotLeakRunawayWorker() throws Exception {
        LuaState s = new LuaState();
        AtomicLong ticks = new AtomicLong();
        s.registerFunction("tick", (LuaInvokable) args -> {
            ticks.incrementAndGet();
            return LuaNil.NIL;
        });
        assertThrows(TimeoutException.class,
                () -> s.evalWithTimeout("while true do tick() end", Duration.ofMillis(100)));
        long after = ticks.get();
        Thread.sleep(300);
        assertEquals(after, ticks.get(),
                "abandoned worker must stop, but kept running after timeout");
        // State must be reusable after a cancelled run.
        assertEquals(2, s.eval("return 1 + 1").toLong());
    }

    @Test
    void constantFoldingHandlesNegativeShift() {
        LuaState s = new LuaState();
        // Lua 5.4: a negative displacement shifts the other way.
        assertEquals(1, s.eval("return 2 << -1").toLong());
        assertEquals(4, s.eval("return 2 >> -1").toLong());
        assertEquals(0, s.eval("return 2 << 64").toLong());
        assertEquals(0, s.eval("return 2 >> 64").toLong());
    }

    @Test
    void constantFoldingMatchesRuntimeForIntegerExpressions() {
        LuaState s = new LuaState();
        LuaValue folded = s.eval("return 3 * 4 + (10 - 2) // 3");
        LuaValue runtime = s.eval("local a=3 local b=4 local c=10 local d=2 local e=3 return a*b + (c-d)//e");
        assertEquals(runtime.toLong(), folded.toLong());
    }

    @Test
    void constantFoldingDoesNotInternLongConcatLiterals() {
        // PUC Lua only interns short strings; folding a long concat into a
        // literal would wrongly share identity with an equal literal.
        LuaState s = new LuaState();
        LuaValue r = s.eval("""
            local sd = "0123456789" .. "0123456789012345678901234567890123456789"
            local lit = "012345678901234567890123456789012345678901234567890123456789"
            return string.format('%p', sd) ~= string.format('%p', lit)
            """);
        assertTrue(r.toBoolean());
    }

    @Test
    void xpcallMessageHandlerSurvivesStackOverflow() {
        // Regression: pcall/xpcall pushed the protected frame outside their
        // try, so a StackOverflowError at the depth limit leaked a handler
        // frame. That stale handler later made an unwind escape past top
        // level (calls.lua) or broke the xpcall result (errors.lua), but
        // only intermittently depending on JVM stack depth.
        LuaState s = new LuaState();
        LuaValue r = s.eval("""
            local function loop(x, y, z) return 1 + loop(x, y, z) end
            local ok, msg = xpcall(loop, function(m)
                assert(string.find(m, "stack overflow"))
                return 15
            end)
            return tostring(ok) .. ':' .. tostring(msg)
            """);
        assertEquals("false:15", r.toLuaString(), "message handler must run and its result survive");
        // The state must still handle ordinary errors afterwards.
        String after = s.eval("local ok,e = pcall(function() error('boom') end); "
                + "return tostring(ok) .. ':' .. tostring(e)").toLuaString();
        assertTrue(after.startsWith("false:") && after.endsWith("boom"), after);
    }

    @Test
    void cStackOverflowWhileHandlingOverflowDoesNotPoisonValueClasses() {
        // Regression: Varargs's <clinit> first ran at the recursion limit,
        // threw StackOverflowError, and the JVM then permanently poisoned
        // the class ("Could not initialize class ...") — so a later run in
        // the same JVM failed with NoClassDefFoundError instead of the Lua
        // error. The core value classes are now initialized at construction.
        String code = """
            local function loop ()
              assert(pcall(loop))
            end
            local err, msg = xpcall(loop, loop)
            assert(not err and string.find(msg, "error"))
            return "ok"
            """;
        for (int i = 0; i < 40; i++) {
            LuaState s = new LuaState();
            assertEquals("ok", s.eval(code).toLuaString(), "run " + i);
        }
    }

    @Test
    void collectgarbageReturnsIntegerAndIsNonReentrant() {
        // PUC returns lua_gc's integer result, not a boolean, and makes
        // collectgarbage fail inside a finalizer.
        LuaState s = new LuaState();
        assertEquals("integer", s.eval("return math.type(collectgarbage('collect'))").toLuaString());
        assertEquals("integer", s.eval("return math.type(collectgarbage('stop'))").toLuaString());
        s.eval("collectgarbage('restart')");
        // Inside a finalizer collectgarbage fails and returns nil.
        assertEquals("nil",
                s.eval("""
                    local res = true
                    setmetatable({}, {__gc = function() res = collectgarbage() end})
                    collectgarbage()
                    return tostring(res)
                    """).toLuaString());
    }

    @Test
    void compilerStackSizeCoversCallArgumentWindow() {
        // Regression: direct freereg writes could exceed the reported
        // maxStackSize, so the JIT capacity guard passed while the generated
        // code indexed past the shared stack array.
        LuaState s = new LuaState();
        org.luava.runtime.bytecode.LuaClosure c = (org.luava.runtime.bytecode.LuaClosure)
                s.eval("local function loop(x, y, z) return 1 + loop(x, y, z) end return loop");
        assertTrue(c.proto.maxStackSize >= 8,
                "maxStackSize must cover the argument registers (register 7), was " + c.proto.maxStackSize);
    }

    @Test
    void noUncheckedExceptionLeaksForCommonErrors() {
        LuaState s = new LuaState();
        String[] snippets = {
            "return nil + 1",
            "return {} < 1",
            "local x=5 return x.foo",
            "local x=5 return x()",
            "return string.char(3.5)",
        };
        for (String code : snippets) {
            Throwable t = assertThrows(Throwable.class, () -> s.eval(code), code);
            assertTrue(t instanceof LuaException,
                    "unchecked exception leaked for " + code + ": " + t.getClass());
        }
    }

    @Test
    void sandboxRemovesOsIoAndJava() {
        LuaState s = new LuaState().sandbox();
        assertTrue(s.get("os").isNil());
        assertTrue(s.get("io").isNil());
        assertTrue(s.get("java").isNil());
        assertTrue(s.eval("return java == nil").toBoolean());
    }

    @Test
    void formatIntegerRejectsFloatsAtOrAboveTwoToThe63() {
        // A naive `(long) d` saturates at Long.MAX_VALUE and `(double)
        // Long.MAX_VALUE` rounds back to 2^63, so the old round-trip check
        // accepted 2^63 and printed a bogus maxinteger. Lua 5.4 rejects any
        // float that does not fit a 64-bit integer.
        LuaState s = new LuaState();
        assertEquals("false,bad argument #2 to 'string.format' (number has no integer representation)",
                s.eval("local ok, e = pcall(string.format, '%d', 2^63) "
                        + "return tostring(ok) .. ',' .. e").toLuaString());
        assertEquals("false,bad argument #2 to 'string.format' (number has no integer representation)",
                s.eval("local ok, e = pcall(string.format, '%x', 2^63) "
                        + "return tostring(ok) .. ',' .. e").toLuaString());
        // 2^63-1 as a float is actually 2^63 (the nearest double), so it is
        // rejected too; the largest representable float below 2^63 works.
        assertEquals("-9223372036854775808",
                s.eval("return string.format('%d', -2^63)").toLuaString());
        assertEquals("9223372036854774784",
                s.eval("return string.format('%d', 9223372036854774784.0)").toLuaString());
    }

    @Test
    void exhaustedGmatchIteratorYieldsZeroValues() {
        // PUC's gmatch_aux returns 0 results when the scan is exhausted, not
        // one nil. Returning a single nil made `select('#', it())` report 1
        // and let a stale function escape through a multi-value position.
        LuaState s = new LuaState();
        assertEquals("0", s.eval(
                "local it = ('hello'):gmatch('l'); it(); it(); "
                        + "return tostring(select('#', it()))").toLuaString());
        assertEquals("0", s.eval(
                "local it = ('abc'):gmatch('z'); return tostring(select('#', it()))").toLuaString());
        assertEquals("1", s.eval(
                "local it = ('aaaa'):gmatch('a'); return tostring(select('#', it()))").toLuaString());
    }

    @Test
    void breakOutsideLoopIsASyntaxError() {
        // PUC's breakstat resolves `break` as a goto to an implicit label
        // created only inside a loop; outside one it is reported as a syntax
        // error at load time, never executing.
        LuaState s = new LuaState();
        for (String src : new String[]{"break", "do break end", "if true then break end"}) {
            // load returns (nil, errmsg); pcall wraps that as (true, nil, err).
            String r = s.eval("local ok, f, e = pcall(load, " + quote(src) + ") "
                    + "return tostring(ok) .. '|' .. tostring(f) .. '|' .. tostring(e)").toLuaString();
            assertTrue(r.startsWith("true|nil|") && r.contains("break outside loop at line 1"),
                    "expected syntax error for " + quote(src) + ", got: " + r);
        }
        // Inside a loop it still loads.
        assertEquals("true|function", s.eval(
                "local ok, f = pcall(load, 'while true do break end') "
                        + "return tostring(ok) .. '|' .. type(f)").toLuaString());
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    @Test
    void osDateUtcZoneNameIsGmt() {
        // `!%Z` formats with gmtime, whose %Z is the literal "GMT" on glibc
        // (and in the C locale), never the ZoneOffset id "Z". The local %Z
        // is environment-dependent, so only the UTC form is deterministic.
        LuaState s = new LuaState();
        assertEquals("GMT", s.eval("return os.date('!%Z', 0)").toLuaString());
    }

    @Test
    void pcallAndXpcallBadArgumentsRaiseLikePuc() {
        // luaL_checkany/luaL_checktype, which RAISE: an enclosing pcall sees
        // false, not a returned (false, msg) pair. They also require the
        // xpcall message handler to be a real function (nil or a callable
        // table is rejected).
        LuaState s = new LuaState();
        assertEquals("false", s.eval("return tostring(pcall(pcall))").toLuaString());
        assertEquals("false", s.eval("return tostring(pcall(xpcall))").toLuaString());
        assertEquals("false", s.eval("return tostring(pcall(xpcall, nil))").toLuaString());
        assertEquals("false", s.eval("return tostring(pcall(xpcall, function() end))").toLuaString());
        // A callable table as the *handler* is still not a function: rejected.
        assertEquals("false", s.eval(
                "return tostring(pcall(xpcall, function() return 1 end, "
                        + "setmetatable({}, {__call=function() return 'H' end})))")
                .toLuaString());
        // A proper handler still works.
        assertEquals("false,H:x", s.eval(
                "local ok, e = xpcall(function() error('x', 0) end, function(m) return 'H:'..m end) "
                        + "return tostring(ok) .. ',' .. e").toLuaString());
    }

    @Test
    void adversarialStdlibCallsNeverLeakJavaThrowables() {
        // Every stdlib call is wrapped in pcall. If a raw Java throwable
        // escapes, pcall cannot catch it and the eval would throw something
        // other than a string/table error object. This reproduces the
        // AGENTS.md §IV.2 rule that unchecked exceptions never leak.
        LuaState s = new LuaState();
        String script =
                "local weird = {true, false, 0, 1, -1, 0.5, 1e308, 1/0, 0/0, "
                + "math.maxinteger, math.mininteger, 'x', '', '\\0', {}, {1,2}} "
                + "local libs = {string, table, math, utf8, coroutine} "
                + "local bad = 0 "
                + "for _, lib in ipairs(libs) do "
                + "  for _, fn in pairs(lib) do "
                + "    if type(fn) == 'function' then "
                + "      for i = 1, #weird do "
                + "        for j = 1, #weird do "
                + "          local ok, e = pcall(fn, weird[i], weird[j]) "
                + "          if not ok and type(e) ~= 'string' and type(e) ~= 'table' "
                + "             and type(e) ~= 'nil' then bad = bad + 1 end "
                + "        end "
                + "      end "
                + "    end "
                + "  end "
                + "end "
                + "return bad";
        assertEquals(0, s.eval(script).toLong());
    }

    @Test
    void hostBoundaryWrapsInternalJavaErrors() {
        // A host function that throws an unchecked Java exception must be
        // surfaced as a LuaException, never a raw NullPointerException.
        LuaState s = new LuaState();
        s.registerFunction("boom", () -> {
            throw new IllegalStateException("kaboom");
        });
        Throwable t = assertThrows(Throwable.class, () -> s.eval("return boom()"));
        assertTrue(t instanceof LuaException, "leaked " + t.getClass().getName());
        // The control-flow signals still pass through untouched.
        assertThrows(LuaException.class, () -> s.eval("error('lua error')"));
    }
}

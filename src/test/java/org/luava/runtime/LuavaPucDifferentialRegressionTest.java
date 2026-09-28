/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;
import org.luava.binding.LuaDataConverter;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the defects a differential sweep against PUC-Rio
 * Lua 5.4.8 found: every expectation below is the reference implementation's
 * observed behaviour, not Luava's previous one.
 */
public class LuavaPucDifferentialRegressionTest {

    // ---- string.upper / string.lower must fold ASCII only ----------------

    @Test
    void caseMappingLeavesEveryByteAboveAsciiAlone() {
        LuaState s = new LuaState();
        // A Lua string is a byte string. Java's String.toLowerCase() applies
        // full Unicode case mapping to the Latin-1 range, so 0xDF upper-cased
        // to 'S' (0x53) and 0xC3 lower-cased to 0xE3, corrupting 63 of the 128
        // non-ASCII bytes.
        assertEquals("223", s.eval("return string.byte(string.char(0xDF):upper(), 1)").toLuaString());
        assertEquals("195", s.eval("return string.byte(string.char(0xC3):lower(), 1)").toLuaString());
        assertEquals("0", s.eval("""
                local n = 0
                for i = 128, 255 do
                  local c = string.char(i)
                  if c:lower():byte(1) ~= i or c:upper():byte(1) ~= i then n = n + 1 end
                end
                return n
                """).toLuaString());
        // ASCII folding itself is unchanged.
        assertEquals("abz 09", s.eval("return ('AbZ 09'):lower()").toLuaString());
        assertEquals("ABZ 09", s.eval("return ('AbZ 09'):upper()").toLuaString());
    }

    @Test
    void caseMappingPreservesUtf8Payloads() {
        LuaState s = new LuaState();
        // "E-acute e-acute" is C3 89 C3 A9. Build the bytes with string.char so
        // no Java unicode escape can rewrite the literal, and check that
        // lower() and upper() leave all four bytes alone.
        assertEquals("195,137,195,169",
                s.eval("return table.concat({string.byte(string.char(0xC3, 0x89, 0xC3, 0xA9), 1, -1)}, ',')").toLuaString());
        assertEquals("195,137,195,169",
                s.eval("""
                        local s = string.char(0xC3, 0x89, 0xC3, 0xA9)
                        return table.concat({string.byte(s:lower(), 1, -1)}, ',')
                        """).toLuaString());
        assertEquals("195,137,195,169",
                s.eval("""
                        local s = string.char(0xC3, 0x89, 0xC3, 0xA9)
                        return table.concat({string.byte(s:upper(), 1, -1)}, ',')
                        """).toLuaString());
        // The emoji must survive an upper/lower round trip byte for byte.
        assertEquals("true", s.eval("return utf8.char(0x1F600):upper() == utf8.char(0x1F600)").toLuaString());
    }

    // ---- a <= b falls back to the right operand's __lt -------------------

    @Test
    void lessOrEqualFallsBackToRightOperandLt() {
        LuaState s = new LuaState();
        // PUC ltm.c callorderTM: no __le anywhere means `a <= b` is
        // `not (b < a)`, using __lt found on either operand. Luava raised
        // "attempt to compare two table values" instead.
        assertEquals("false", s.eval("""
                local R = setmetatable({v = 0}, {__lt = function() return true end})
                local P = setmetatable({v = 1}, {})
                return tostring(P <= R)
                """).toLuaString());
        assertEquals("true", s.eval("""
                local R = setmetatable({v = 0}, {__lt = function(a, b) return a.v < b.v end})
                local P = setmetatable({v = 1}, {})
                return tostring(R <= P)
                """).toLuaString());
        // An explicit __le still wins, on either operand.
        assertEquals("true", s.eval("""
                local A = setmetatable({v = 1}, {__le = function() return true end})
                return tostring(A <= setmetatable({v = 2}, {}))
                """).toLuaString());
        // With neither metamethod the comparison still raises.
        assertTrue(raises(s, "return setmetatable({}, {}) <= setmetatable({}, {})",
                "attempt to compare two table values"));
    }

    // ---- table constructors pre-size their array part --------------------

    @Test
    void tableConstructorPresizesTheArrayPart() {
        LuaState s = new LuaState();
        // PUC luaH_resize pre-sizes the array part to the list-part length,
        // which rawlen and the # border expose.
        assertEquals(4, s.eval("return rawlen({1, 2, nil, 4})").toLong());
        assertEquals(2, s.eval("return #{nil, 2}").toLong());
        assertEquals(3, s.eval("return #{nil, nil, 3}").toLong());
        assertEquals(3, s.eval("return rawlen({1, 2, 3})").toLong());
        // A record part never extends the array part, so the natural growth of
        // key 1 is all that shows up.
        assertEquals(1, s.eval("return rawlen({[1] = 1, [5] = 5})").toLong());
        assertEquals(2, s.eval("return rawlen({1, 2, [10] = 9})").toLong());
        // # is a border and may differ, but a table with no hole is exact.
        assertEquals(4, s.eval("return #{1, 2, 3, 4}").toLong());
    }

    @Test
    void tableInsertLandsAfterAPreSizedNilHole() {
        LuaState s = new LuaState();
        // The pre-size is what makes table.insert put "X" after the hole: PUC
        // yields 1,2,nil,4,X, Luava used to yield 1,2,X,4,nil.
        assertEquals("4", s.eval("""
                local t = {1, 2, nil, 4}
                return tostring(#t)
                """).toLuaString());
        assertEquals("1,2,nil,4,X", s.eval("""
                local t = {1, 2, nil, 4}
                table.insert(t, "X")
                return table.concat({tostring(t[1]), tostring(t[2]), tostring(t[3]),
                                     tostring(t[4]), tostring(t[5])}, ",")
                """).toLuaString());
    }

    @Test
    void aTrailingMultivalueConstructorIsNotPresized() {
        LuaState s = new LuaState();
        // The result count of a trailing call/... is only known at run time, so
        // PUC leaves the array part to grow naturally.
        assertEquals(5, s.eval("""
                local function three() return 1, 2, 3 end
                return #{1, 2, three()}
                """).toLong());
        assertEquals(3, s.eval("""
                local function three() return 1, 2, 3 end
                return #{three()}
                """).toLong());
    }

    @Test
    void interpreterAndJitAgreeOnPresizedConstructors() {
        for (boolean jit : new boolean[] { false, true }) {
            LuaState s = new LuaState();
            s.jitEnabled(jit);
            assertEquals(4, s.eval("return rawlen({1, 2, nil, 4})").toLong());
            assertEquals(2, s.eval("return #{nil, 2}").toLong());
            assertEquals("1,2,nil,4,X", s.eval("""
                    local t = {1, 2, nil, 4}
                    table.insert(t, "X")
                    return table.concat({tostring(t[1]), tostring(t[2]), tostring(t[3]),
                                         tostring(t[4]), tostring(t[5])}, ",")
                    """).toLuaString());
        }
    }

    // ---- runtime errors raised from C helpers keep the Lua call site -----

    @Test
    void stringArithMetamethodErrorKeepsTheCallSite() {
        LuaState s = new LuaState();
        // The message is built by the string metatable, a Java-implemented
        // LuaFunction, so it used to be treated as a C-origin error and lose
        // the enclosing Lua frame's source:line.
        String err = luaError(s, "return 'a' + 1");
        assertTrue(err.contains("attempt to add a 'string' with a 'number'"), err);
        assertTrue(s.eval("""
                local ok, e = pcall(function() return 'a' + 1 end)
                return tostring(e):match('^.*:%d+: attempt to add') ~= nil
                """).toBoolean(), "the error must carry a source:line prefix");
    }

    @Test
    void closedFileErrorKeepsTheCallSite() {
        LuaState s = new LuaState();
        // luaL_argcheck inside io's f_read: PUC adds luaL_where(L, 1).
        String err = luaError(s, """
                local f = io.open(os.tmpname(), "w")
                f:close()
                return f:read()
                """);
        assertTrue(err.contains("attempt to use a closed file"), err);
    }

    @Test
    void luaLenFromTableLibraryStaysUndecorated() {
        LuaState s = new LuaState();
        // ... but PUC's table.unpack reaches the length through luaL_len, whose
        // luaG_typeerror is raised from a C frame and gets no location. This
        // distinguishes the two cases. (table.insert rejects a non-table with
        // its own luaL_argcheck first, so the length path is only reachable
        // through unpack.)
        assertEquals("attempt to get length of a number value",
                luaError(s, "return table.unpack(5)"));
    }

    @Test
    void sortComparisonErrorIsUndecoratedButComparatorErrorIsNot() {
        LuaState s = new LuaState();
        // PUC's comparison runs inside the C sort loop.
        assertEquals("attempt to compare string with number",
                luaError(s, "return table.sort({1, 'a'})"));
        // A Lua error raised by the comparator itself keeps its location.
        assertTrue(s.eval("""
                local ok, e = pcall(table.sort, {3, 1, 2}, function() error('boom') end)
                return tostring(e):match('^.*:%d+: boom$') ~= nil
                """).toBoolean());
    }

    @Test
    void yieldOutsideACoroutineIsUndecorated() {
        LuaState s = new LuaState();
        // PUC's lua_yield failure is a C-origin runerror.
        assertEquals("attempt to yield from outside a coroutine",
                luaError(s, "return coroutine.yield()"));
    }

    // ---- argument-error naming follows PUC's luaL_argerror ---------------

    @Test
    void argumentErrorNamesTheQualifiedGlobalWhenCalledFromC() {
        LuaState s = new LuaState();
        // Called from C there is no call site to inspect, so PUC's
        // pushglobalfuncname yields the qualified name. The math/os/io helpers
        // used to hard-code the bare builtin name.
        assertEquals("bad argument #1 to 'math.floor' (number expected, got string)",
                cError(s, "math.floor", "\"x\""));
        assertEquals("bad argument #1 to 'os.date' (invalid conversion specifier '%Q')",
                cError(s, "os.date", "\"%Q\""));
        assertEquals("bad argument #1 to 'io.open' (string expected, got no value)",
                cError(s, "io.open"));
    }

    @Test
    void argumentErrorNamesTheCallSiteFieldWhenCalledFromLua() {
        LuaState s = new LuaState();
        assertEquals("bad argument #1 to 'floor' (number expected, got string)",
                luaError(s, "return math.floor('x')"));
        assertEquals("bad argument #1 to 'insert' (table expected, got nil)",
                luaError(s, "return table.insert(nil, 1)"));
        // A local alias names the local, as before.
        assertEquals("bad argument #1 to 'f1' (table expected, got nil)",
                luaError(s, "local f1 = table.insert f1(nil, 1)"));
    }

    // ---- os.difftime / os.setlocale / collectgarbage ---------------------

    @Test
    void osDifftimeReportsTheOffendingArgument() {
        LuaState s = new LuaState();
        // The old message had no argument index and no type detail at all.
        assertEquals("bad argument #1 to 'os.difftime' (number expected, got string)",
                cError(s, "os.difftime", "\"x\""));
        assertEquals("bad argument #2 to 'os.difftime' (number expected, got string)",
                cError(s, "os.difftime", "10, \"x\""));
        assertEquals("bad argument #1 to 'os.difftime' (number expected, got no value)",
                cError(s, "os.difftime"));
        assertEquals(5.0, s.eval("return os.difftime(10, 5)").toDouble(), 1e-9);
    }

    @Test
    void osSetlocaleReportsAndAcceptsTheCLocaleFamily() {
        LuaState s = new LuaState();
        assertEquals("C", s.eval("return os.setlocale()").toLuaString());
        assertEquals("C", s.eval("return os.setlocale('C')").toLuaString());
        assertEquals("C", s.eval("return os.setlocale('POSIX')").toLuaString());
        // os.setlocale("") is a query and must not report the C locale.
        assertFalse("C".equals(s.eval("return os.setlocale('')").toLuaString()));
        // A locale the JVM cannot install reports PUC's nil.
        assertTrue(s.eval("return os.setlocale('en_US') == nil").toBoolean());
        assertEquals("bad argument #2 to 'os.setlocale' (invalid option 'bogus')",
                cError(s, "os.setlocale", "\"C\", \"bogus\""));
    }

    @Test
    void collectgarbageDefaultsToGenerationalAndCoercesNumbers() {
        LuaState s = new LuaState();
        // Lua 5.4's default mode is generational, and the setter reports the
        // mode that was in effect *before* the switch.
        assertEquals("generational", s.eval("return collectgarbage('incremental')").toLuaString());
        assertEquals("incremental", s.eval("return collectgarbage('generational')").toLuaString());
        assertEquals("generational", s.eval("return collectgarbage('incremental')").toLuaString());
        assertEquals("incremental", s.eval("return collectgarbage('generational')").toLuaString());
        // luaL_optstring coerces a number to its Lua string form.
        assertEquals("bad argument #1 to 'collectgarbage' (invalid option '5')",
                cError(s, "collectgarbage", "5"));
    }

    // ---- string.format conversion diagnostics ---------------------------

    @Test
    void stringFormatDistinguishesTheTwoConversionErrors() {
        LuaState s = new LuaState();
        // Unknown conversion character (str_format's default branch).
        assertEquals("invalid conversion '%z' to 'format'",
                luaError(s, "return string.format('%z', 1)"));
        // Known character with flags it does not accept (checkformat).
        assertEquals("invalid conversion specification: '%#d'",
                luaError(s, "return string.format('%#d', 1)"));
    }

    // ---- io: filename coercion and the strerror tail ---------------------

    @Test
    void ioFunctionsCoerceNumericFilenames() {
        LuaState s = new LuaState();
        // PUC's luaL_checklstring converts a number, so io.open(42) tries the
        // file named "42" rather than raising a type error.
        assertEquals("nil", s.eval("return (io.open(42))").toLuaString());
        assertEquals("cannot open file '42' (No such file or directory)",
                luaError(s, "return io.lines(42)"));
        assertEquals("cannot open file '1.5' (No such file or directory)",
                luaError(s, "return io.lines(1.5)"));
        assertEquals("bad argument #1 to 'io.open' (string expected, got no value)",
                cError(s, "io.open"));
    }

    @Test
    void cannotOpenMessageDoesNotRepeatTheFilename() {
        LuaState s = new LuaState();
        // PUC's fileerror uses strerror only; the name is already in the
        // message, so io.open's "name: reason" tuple prefix must be stripped.
        assertEquals("cannot open file '/no/such/dir/x' (No such file or directory)",
                luaError(s, "return io.lines('/no/such/dir/x')"));
        // The failure tuple itself keeps PUC's glibc-style "name: reason".
        assertEquals("/no/such/dir/x: No such file or directory", s.eval("""
                local _, msg = io.open('/no/such/dir/x')
                return msg
                """).toLuaString());
    }

    // ---- interop: a pre-sized hole must not become a Java null ----------

    @Test
    void aNilElementIsRejectedRatherThanConvertedToNull() {
        LuaState s = new LuaState();
        // With the array part pre-sized, {1, nil, 3} has rawlen 3, so a
        // key-range check alone would accept it and hand the host a List with
        // a null element.
        LuaTable t = (LuaTable) s.eval("return {1, nil, 3}");
        assertEquals(3, t.rawlen());
        assertThrows(LuaException.class, () -> LuaDataConverter.toJava(t, List.class));
        // Every entry is still reachable as a Map.
        @SuppressWarnings("unchecked")
        Map<Object, Object> asMap = (Map<Object, Object>) LuaDataConverter.toJava(t, Map.class);
        assertEquals("1", String.valueOf(asMap.get(1L)));
        assertEquals("3", String.valueOf(asMap.get(3L)));
        // A dense table still converts.
        LuaTable dense = (LuaTable) s.eval("return {1, 2, 3}");
        assertEquals(List.of(1L, 2L, 3L), LuaDataConverter.toJava(dense, List.class));
    }

    // ---- helpers --------------------------------------------------------

    /** Runs {@code expr} under pcall inside a Lua function and returns the
     * error text with any {@code source:line:} prefix stripped. */
    private static String luaError(LuaState s, String expr) {
        return stripLocation(s.eval(
                "local ok, e = pcall(function() " + expr + " end) return tostring(e)").toLuaString());
    }

    /** Calls {@code f} from C (through pcall, which has no Lua call site) and
     * returns the error text with the location stripped. */
    private static String cError(LuaState s, String f, String... args) {
        String argList = String.join(", ", args);
        String call = argList.isEmpty() ? f : f + ", " + argList;
        return stripLocation(s.eval(
                "local ok, e = pcall(" + call + ") return tostring(e)").toLuaString());
    }

    private static boolean raises(LuaState s, String expr, String fragment) {
        return luaError(s, expr).contains(fragment);
    }

    private static String stripLocation(String message) {
        return message.replaceFirst("^.*?:\\d+: ", "");
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Byte-for-byte conformance checks against stock PUC Lua 5.4. Each case here
 * was a real divergence found by differential testing (same script run under
 * a reference C Lua and Luava); the expected strings are copied from that
 * reference output.
 */
public class Lua54ConformanceTest {

    private static String eval(LuaState s, String code) {
        LuaValue v = s.eval(code);
        return v.toLuaString();
    }

    @Test
    void rawequalComparesNumbersAcrossSubtypes() {
        LuaState s = new LuaState();
        assertEquals("true", eval(s, "return tostring(rawequal(1, 1.0))"));
        assertEquals("true", eval(s, "return tostring(rawequal(1, 1))"));
        assertEquals("false", eval(s, "return tostring(rawequal(1, 2))"));
        assertEquals("true", eval(s, "return tostring(rawequal('a', 'a'))"));
    }

    @Test
    void mathModfReturnsIntegerPartWhenItFits() {
        LuaState s = new LuaState();
        assertEquals("integer", eval(s, "return math.type((math.modf(3.7)))"));
        assertEquals("-3", eval(s, "return tostring((math.modf(-3.7)))"));
        assertEquals("3", eval(s, "return tostring((math.modf(3)))"));
    }

    @Test
    void hexFloatFormatMatchesC() {
        LuaState s = new LuaState();
        assertEquals("0x1p+0", eval(s, "return string.format('%a', 1.0)"));
        assertEquals("0x1.cp+1", eval(s, "return string.format('%a', 3.5)"));
        assertEquals("0X1P+0", eval(s, "return string.format('%A', 1.0)"));
        assertEquals("0x0p+0", eval(s, "return string.format('%a', 0.0)"));
        assertEquals("-0x0p+0", eval(s, "return string.format('%a', -0.0)"));
        // precision rounding (half-to-even, carry into the leading digit)
        assertEquals("0x2.0p+7", eval(s, "return string.format('%.1a', 255.0)"));
        assertEquals("0x1.99ap-4", eval(s, "return string.format('%.3a', 0.1)"));
        assertEquals("0x1.p+0", eval(s, "return string.format('%#a', 1.0)"));
        assertEquals("0x1.cp+1", eval(s, "return string.format('%q', 3.5)"));
    }

    @Test
    void floatFormatsUseExactBinaryValue() {
        LuaState s = new LuaState();
        // Java's %f loses precision on huge magnitudes; C prints the exact
        // decimal expansion.
        assertEquals("1000000000000000019884624838656.000000",
                eval(s, "return string.format('%f', 1e30)"));
        assertEquals("2.67", eval(s, "return string.format('%.2f', 2.675)"));
        assertEquals("2e+00", eval(s, "return string.format('%.0e', 2.5)"));
        assertEquals("4e+00", eval(s, "return string.format('%.0e', 3.5)"));
        assertEquals("4.940656e-324", eval(s, "return string.format('%e', 5e-324)"));
        assertEquals("3.00000", eval(s, "return string.format('%#g', 3.0)"));
        assertEquals("4.94066e-324", eval(s, "return string.format('%g', 5e-324)"));
    }

    @Test
    void ioWriteFormatsFloatsLikePercentG() {
        LuaState s = new LuaState();
        // io.write uses LUA_NUMBER_FMT (%.14g): 1.0 prints as "1", unlike
        // tostring which appends ".0".
        assertEquals("1", eval(s, "return (function() local f=io.tmpfile(); f:write(1.0); f:seek('set'); return f:read('a') end)()"));
        assertEquals("1.0", eval(s, "return tostring(1.0)"));
        assertEquals("255", eval(s, "return (function() local f=io.tmpfile(); f:write(255.0); f:seek('set'); return f:read('a') end)()"));
    }

    @Test
    void standardLibrarySurfaceMatchesStock54() {
        LuaState s = new LuaState();
        // stock 5.4 has no global `unpack` and does expose debug.debug /
        // debug.setcstacklimit.
        assertEquals("nil", eval(s, "return tostring(unpack)"));
        assertEquals("function", eval(s, "return type(debug.debug)"));
        assertEquals("function", eval(s, "return type(debug.setcstacklimit)"));
        assertEquals("200", eval(s, "return tostring(debug.setcstacklimit(100))"));
        assertEquals("integer", eval(s, "return math.type(debug.setcstacklimit(50))"));
    }

    @Test
    void osRenameErrorHasNoPathPrefix() {
        LuaState s = new LuaState();
        // C's os.rename passes NULL as the file name to luaL_fileresult.
        assertEquals("No such file or directory",
                eval(s, "local _, e = os.rename('/no/such/a', '/no/such/b'); return e"));
        assertEquals("/no/such/a: No such file or directory",
                eval(s, "local _, e = os.remove('/no/such/a'); return e"));
    }

    @Test
    void requireReadsSourceAsBytes() throws Exception {
        // A module with a non-ASCII byte must keep its byte length (source is
        // a byte stream, read with ISO-8859-1, not UTF-8).
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("luava-req");
        try {
            java.nio.file.Files.writeString(dir.resolve("m.lua"),
                    "return { s = \"h\u00e9llo\" }\n", java.nio.charset.StandardCharsets.UTF_8);
            LuaState s = new LuaState();
            s.eval("package.path = '" + dir + "/?.lua;' .. package.path");
            assertEquals("6", eval(s, "return tostring(#require('m').s)"));
            assertEquals("5", eval(s, "return tostring(utf8.len(require('m').s))"));
        } finally {
            java.nio.file.Files.deleteIfExists(dir.resolve("m.lua"));
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
}

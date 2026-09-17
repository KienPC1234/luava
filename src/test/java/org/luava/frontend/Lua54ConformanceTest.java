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
    void stringArithmeticGoesThroughTheStringMetatable() {
        // Lua 5.4 installs default __add/__sub/... on the string metatable
        // and they are overridable; a direct coercion would ignore them.
        LuaState s = new LuaState();
        assertEquals("11", eval(s, "return tostring('10' + 1)"));
        assertEquals("17", eval(s, "return tostring('0x10' + 1)"));
        assertEquals("11", eval(s, "return tostring(1 + '10')"));
        assertEquals("function", eval(s, "return type(getmetatable('').__add)"));
        assertEquals("true", eval(s,
                "local mt = getmetatable(''); local old = mt.__add; "
                + "mt.__add = function(a, b) return 'CUSTOM' end; "
                + "local r = ('10' + 1); mt.__add = old; return tostring(r == 'CUSTOM')"));
        assertEquals("true", eval(s,
                "local _, e = pcall(function() return 'a' + 1 end); "
                + "return tostring(e:find(\"attempt to add a 'string' with a 'number'\", 1, true) ~= nil)"));
        assertEquals("true", eval(s,
                "local _, e = pcall(function() return 1 + 'a' end); "
                + "return tostring(e:find(\"attempt to add a 'number' with a 'string'\", 1, true) ~= nil)"));
    }

    @Test
    void errorMessagesBlameTheRightOperand() {
        LuaState s = new LuaState();
        assertEquals("true", eval(s,
                "local _, e = pcall(function() return '3' & 1 end); "
                + "return tostring(e:find(\"attempt to perform bitwise operation on a string value (constant '3')\", 1, true) ~= nil)"));
        assertEquals("true", eval(s,
                "local _, e = pcall(function() return 3.5 & 1 end); "
                + "return tostring(e:find('number has no integer representation', 1, true) ~= nil)"));
        assertEquals("true", eval(s,
                "local _, e = pcall(function() local x = 3.5 return x & 1 end); "
                + "return tostring(e:find(\"number (local 'x') has no integer representation\", 1, true) ~= nil)"));
    }

    @Test
    void baseLibraryValidationMatchesStock() {
        LuaState s = new LuaState();
        // pairs/ipairs only require an argument (no table-type check).
        assertEquals("function", eval(s, "return type((pairs(nil)))"));
        assertEquals("function", eval(s, "return type((ipairs(nil)))"));
        assertEquals("true", eval(s,
                "local _, e = pcall(next, nil); "
                + "return tostring(e:find(\"bad argument #1 to 'next' (table expected, got nil)\", 1, true) ~= nil)"));
        assertEquals("true", eval(s,
                "local _, e = pcall(select, 0, 'a'); "
                + "return tostring(e:find(\"bad argument #1 to 'select' (index out of range)\", 1, true) ~= nil)"));
        assertEquals("true", eval(s,
                "local _, e = pcall(rawlen, 5); "
                + "return tostring(e:find(\"bad argument #1 to 'rawlen' (table or string expected, got number)\", 1, true) ~= nil)"));
    }

    @Test
    void cFunctionsRenderLikeLuaFunctions() {
        LuaState s = new LuaState();
        // PUC prints C functions with the same "function: 0x..." shape.
        assertEquals("true", eval(s, "return tostring(tostring(print):find('function: 0x') ~= nil)"));
        assertEquals("true", eval(s, "return tostring(tostring(string.rep):find('builtin') == nil)"));
    }

    @Test
    void maxStackCoversCallArgumentRegisters() {
        // Regression: the compiler under-reported maxStackSize when a call
        // spread its arguments past funcReg+1, so the JIT capacity guard
        // passed while generated code indexed beyond the shared stack array
        // (intermittent "Index N out of bounds for length N").
        LuaState s = new LuaState();
        for (int i = 0; i < 50; i++) {
            String r = s.eval(
                    "local function loop(x, y, z) return 1 + loop(x, y, z) end "
                    + "local ok, msg = xpcall(loop, function(m) return m end); "
                    + "return tostring(msg)").toLuaString();
            assertEquals(true, r.contains("stack overflow"), "run " + i + ": " + r);
        }
        // The compiled proto must report registers covering its operands.
        org.luava.runtime.bytecode.LuaClosure c = (org.luava.runtime.bytecode.LuaClosure)
                s.eval("local function loop(x, y, z) return 1 + loop(x, y, z) end return loop");
        assertEquals(true, c.proto.maxStackSize >= 8,
                "maxStackSize must cover register 7, was " + c.proto.maxStackSize);
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

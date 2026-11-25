/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend;

import org.junit.jupiter.api.Test;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenType;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies semantic invariants of frontend/compiler optimizations:
 * register watermarks across shadowing, {@code <close>}, upvalues, goto,
 * multi-return, string/number edge cases, and composite protoCache keys.
 */
public class FrontendOptimizationDifferentialTest {

    @Test
    void shadowingAndManyLocalsPreserveBindingsAndRegisters() {
        StringBuilder sb = new StringBuilder();
        sb.append("local function f(seed)\n");
        for (int i = 0; i < 40; i++) {
            sb.append("  local v").append(i).append(" = seed + ").append(i).append('\n');
        }
        sb.append("  do\n");
        for (int i = 0; i < 20; i++) {
            sb.append("    local v").append(i).append(" = v").append(i).append(" * 10\n");
        }
        sb.append("    local inner = v0 + v19\n");
        sb.append("    seed = inner\n");
        sb.append("  end\n");
        sb.append("  return seed + v0 + v19 + v39\n");
        sb.append("end\nreturn f(5)\n");

        try (LuaState st = new LuaState()) {
            LuaValue res = st.eval(sb.toString(), "@shadow_test");
            // inner = (5*10) + ((5+19)*10) = 50 + 240 = 290; outer v0=5, v19=24, v39=44 -> 290+5+24+44 = 363
            assertEquals(363L, res.toLong());
        }
    }

    @Test
    void shadowingUpvaluesToBeClosedAndGotoWorkTogether() {
        String script = """
                local closed = {}
                local function make_closer(tag)
                    return setmetatable({}, {
                        __close = function() closed[#closed + 1] = tag end
                    })
                end
                local x = 10
                local f1, f2
                do
                    local c1 <close> = make_closer("outer")
                    local x = 20
                    f1 = function() return x end
                    do
                        local c2 <close> = make_closer("inner")
                        local x = 30
                        f2 = function() return x end
                        goto skip
                    end
                    ::skip::
                    assert(f2() == 30)
                end
                return x, f1(), f2(), table.concat(closed, ",")
                """;
        try (LuaState st = new LuaState()) {
            LuaValue[] out = ((Varargs) st.compile(script).call()).toArray();
            assertEquals(10L, out[0].toLong());
            assertEquals(20L, out[1].toLong());
            assertEquals(30L, out[2].toLong());
            assertEquals("inner,outer", out[3].toLuaString());
        }
    }

    @Test
    void lexerNumbersEscapesAndDiagnosticsMatchLua54() {
        String script = """
                local s1 = "plain_ascii"
                local s2 = "a\\nb\\tc\\x41\\065\\u{00E9}\\z
                   end"
                local n1 = 0
                local n2 = 123456789012345678
                local n3 = 007
                local n4 = 0x1.8p+2
                local n5 = 0xffffffffffffffff
                return s1, s2, utf8.codepoint(s2, 8), n1, n2, n3, n4, n5
                """;
        try (LuaState st = new LuaState()) {
            LuaValue[] out = ((Varargs) st.compile(script).call()).toArray();
            assertEquals("plain_ascii", out[0].toLuaString());
            assertEquals("a\nb\tcAA\u00c3\u00a9end", out[1].toLuaString());
            assertEquals(0xE9L, out[2].toLong());
            assertEquals(0L, out[3].toLong());
            assertEquals(123456789012345678L, out[4].toLong());
            assertEquals(7L, out[5].toLong());
            assertEquals(6.0, out[6].toDouble(), 1e-12);
            assertEquals(-1L, out[7].toLong());

            LuaException malformed = assertThrows(LuaException.class, () -> st.compile("local x = 123abc"));
            assertTrue(malformed.getMessage().contains("123a"));
            LuaException badEsc = assertThrows(LuaException.class, () -> st.compile("local x = \"hi\\q\""));
            assertTrue(badEsc.getMessage().contains("invalid escape sequence"));
            LuaException badFloatSyntax = assertThrows(LuaException.class, () -> st.compile("1.000"));
            assertTrue(badFloatSyntax.getMessage().contains("1.000"));
        }
    }

    @Test
    void protoCacheCompositeKeySeparatesChunkNamesAndReusesProto() {
        try (LuaState st = new LuaState()) {
            LuaFunction a1 = st.compile("return 42", "@chunkA", null);
            LuaFunction a2 = st.compile("return 42", "@chunkA", null);
            LuaFunction b1 = st.compile("return 42", "@chunkB", null);
            assertSame(((org.luava.runtime.bytecode.LuaClosure) a1).proto,
                    ((org.luava.runtime.bytecode.LuaClosure) a2).proto);
            assertNotSame(((org.luava.runtime.bytecode.LuaClosure) a1).proto,
                    ((org.luava.runtime.bytecode.LuaClosure) b1).proto);
            assertEquals("@chunkA", a1.getSource());
            assertEquals("@chunkB", b1.getSource());
        }
    }

    @Test
    void tokenLexemeContractPreservedForAllTokenKinds() {
        String src = "local x = 42 + 007 + 1.000 + 0x10 .. 'abc' .. \"x\\ny\" .. [[long]]";
        List<Token> tokens = new Lexer(src, false).scanTokens();
        String[] expectedLexemes = {
                "local", "x", "=", "42", "+", "007", "+", "1.000", "+", "0x10",
                "..", "'abc'", "..", "\"x\\ny\"", "..", "[[long]]", ""
        };
        assertEquals(expectedLexemes.length, tokens.size());
        for (int i = 0; i < expectedLexemes.length; i++) {
            assertNotNull(tokens.get(i).lexeme());
            assertEquals(expectedLexemes[i], tokens.get(i).lexeme());
        }
        assertEquals(TokenType.INTEGER_LITERAL, tokens.get(3).type());
        assertEquals(42L, tokens.get(3).literal());
        assertEquals(TokenType.FLOAT_LITERAL, tokens.get(7).type());
        assertEquals(1.0, tokens.get(7).literal());
        assertEquals(TokenType.STRING_LITERAL, tokens.get(11).type());
        assertEquals("abc", tokens.get(11).literal());
    }
}

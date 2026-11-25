/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenStream;
import org.luava.frontend.lexer.TokenType;
import org.luava.frontend.parser.ParseException;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;
import org.luava.runtime.bytecode.BytecodeCompiler;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.JitCompiler;

/**
 * Verifies semantic and API invariants of frontend/compiler optimizations:
 * register watermarks across shadowing, {@code <close>}, upvalues, goto,
 * multi-return, string/number edge cases, composite protoCache keys,
 * Java 21 {@link Token} record compatibility, comment-heavy source bounds,
 * and JIT rejected-attempt telemetry.
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
            assertSame(((LuaClosure) a1).proto, ((LuaClosure) a2).proto);
            assertNotSame(((LuaClosure) a1).proto, ((LuaClosure) b1).proto);
            assertEquals("@chunkA", a1.getSource());
            assertEquals("@chunkB", b1.getSource());
        }
    }

    @Test
    void tokenLexemeContractAndRecordPatternCompatibilityPreserved() {
        assertTrue(Token.class.isRecord(), "Token must remain a public Java record");
        RecordComponent[] components = Token.class.getRecordComponents();
        assertEquals(5, components.length);
        assertEquals("type", components[0].getName());
        assertEquals("lexeme", components[1].getName());
        assertEquals("literal", components[2].getName());
        assertEquals("line", components[3].getName());
        assertEquals("column", components[4].getName());

        String src = new String("local x = 42 + 007 + 1.000 + 0x10 .. 'abc' .. \"x\\ny\" .. [[long]]");
        List<Token> tokens = new Lexer(src, false).scanTokens();
        String[] expectedLexemes = {
                "local", "x", "=", "42", "+", "007", "+", "1.000", "+", "0x10",
                "..", "'abc'", "..", "\"x\\ny\"", "..", "[[long]]", ""
        };
        assertEquals(expectedLexemes.length, tokens.size());
        for (int i = 0; i < expectedLexemes.length; i++) {
            Token tok = tokens.get(i);
            assertNotNull(tok.lexeme());
            assertEquals(expectedLexemes[i], tok.lexeme());
            assertNotSame(src, tok.lexeme(), "Token must not retain full source reference");
            assertNotSame(src, tok.literal(), "Token literal must not retain full source reference");
        }

        Object candidate = tokens.get(3);
        boolean matchedRecordPattern = false;
        if (candidate instanceof Token(TokenType type, String lexeme, Object literal, int line, int col)) {
            matchedRecordPattern = true;
            assertEquals(TokenType.INTEGER_LITERAL, type);
            assertEquals("42", lexeme);
            assertEquals(42L, literal);
            assertEquals(1, line);
            assertEquals(11, col);
        }
        assertTrue(matchedRecordPattern, "Java 21 record deconstruction pattern must match Token");
    }

    @Test
    void largeCommentHeavySourceProducesMinimalTokensAndExactLocations() {
        String commentLine = "-- 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ padding comment\n";
        int commentLines = 50_000; // ~4.1 MB of comments
        StringBuilder sb = new StringBuilder(commentLines * commentLine.length() + 32);
        for (int i = 0; i < commentLines; i++) {
            sb.append(commentLine);
        }
        sb.append("return 99\n");
        String hugeSource = sb.toString();
        assertTrue(hugeSource.length() > 4_000_000);

        List<Token> tokens = new Lexer(hugeSource, false).scanTokens();
        assertEquals(3, tokens.size());
        assertEquals(TokenType.RETURN, tokens.get(0).type());
        assertEquals(commentLines + 1, tokens.get(0).line());
        assertEquals(TokenType.INTEGER_LITERAL, tokens.get(1).type());
        assertEquals(99L, tokens.get(1).literal());
        assertEquals(TokenType.EOF, tokens.get(2).type());

        TokenStream stream = new Lexer(hugeSource, false).scanStream();
        assertEquals(3, stream.size());
        assertEquals(commentLines + 1, stream.line(0));

        try (LuaState st = new LuaState();
             LuaState.CompiledChunk chunk = LuaState.compileChunk(hugeSource, "@huge_comments.lua")) {
            assertEquals(99L, st.eval(chunk).toLong());
        }
    }

    @Test
    void tokenStreamMatchesTokenListAstAndJitRejectedTelemetryRecorded() {
        String validScript = """
                local function compute(a, b)
                    local t = { x = a + 1.5, [2] = 'ok', [[raw]] }
                    return t.x + b, t[2], t[1]
                end
                return compute(10, 20)
                """;
        Statements.BlockStmt fromList = new Parser(new Lexer(validScript, false).scanTokens()).parse();
        Statements.BlockStmt fromStream = new Parser(new Lexer(validScript, false).scanStream()).parse();
        assertEquals(fromList, fromStream);

        String invalidScript = "local x = [[unclosed_expr]] 123";
        ParseException errList = assertThrows(
                ParseException.class,
                () -> new Parser(new Lexer(invalidScript, false).scanTokens()).parse());
        ParseException errStream = assertThrows(
                ParseException.class,
                () -> new Parser(new Lexer(invalidScript, false).scanStream()).parse());
        assertEquals(errList.getMessage(), errStream.getMessage());
        assertEquals(errList.line(), errStream.line());
        assertEquals(errList.column(), errStream.column());

        // Verify rejected JIT compile attempts increment both rejectedCount() and totalCompileNanos().
        Statements.BlockStmt varargAst = new Parser(new Lexer("return ...", false).scanStream()).parse();
        LuaProto ineligibleProto = BytecodeCompiler.compile(varargAst, "@ineligible_vararg.lua");
        long nanosBefore = JitCompiler.totalCompileNanos();
        long rejectedBefore = JitCompiler.rejectedCount();
        assertNull(JitCompiler.tryCompile(ineligibleProto, false));
        long nanosAfter = JitCompiler.totalCompileNanos();
        long rejectedAfter = JitCompiler.rejectedCount();
        assertEquals(rejectedBefore + 1, rejectedAfter);
        assertTrue(nanosAfter > nanosBefore, "Rejected JIT attempt must contribute to totalCompileNanos()");
    }
}

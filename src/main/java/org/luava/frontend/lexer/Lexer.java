/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.lexer;

import java.util.ArrayList;
import java.util.List;
import org.luava.frontend.parser.ParseException;
import org.luava.runtime.LuaValue;

public final class Lexer {

    private final String source;
    private final int length;
    private final boolean skipShebang;
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int lineStartOffset = 0;

    public Lexer(String source) {
        this(source, true);
    }

    public Lexer(String source, boolean skipShebang) {
        this.source = source != null ? source : "";
        this.length = this.source.length();
        this.skipShebang = skipShebang;
    }

    public List<Token> scanTokens() {
        List<Token> tokens = new ArrayList<>(Math.max(16, length >>> 2));
        if (current == 0 && !isAtEnd() && peek() == '\uFEFF') {
            advance();
        }
        int p = current;
        while (p < length && (source.charAt(p) == ' ' || source.charAt(p) == '\t' || source.charAt(p) == '\r' || source.charAt(p) == '\n')) {
            p++;
        }
        boolean hasShebang = false;
        if (p < length && source.charAt(p) == '#') {
            if (skipShebang && p == current) {
                hasShebang = true;
            } else if (p + 1 < length && source.charAt(p + 1) == '!') {
                hasShebang = true;
            }
        }
        if (hasShebang) {
            while (current < p) {
                if (peek() == '\n') {
                    line++;
                    lineStartOffset = current + 1;
                }
                advance();
            }
            while (!isAtEnd() && peek() != '\n' && peek() != '\r') {
                advance();
            }
        }
        while (!isAtEnd()) {
            start = current;
            Token token = scanToken();
            if (token != null) {
                tokens.add(token);
            }
        }
        tokens.add(new Token(TokenType.EOF, "", null, line, getColumn()));
        return tokens;
    }

    private Token scanToken() {
        char c = advance();
        return switch (c) {
            case ' ', '\t', '\f', '\u000B' -> null;
            case '\r' -> {
                if (peek() == '\n') advance();
                line++;
                lineStartOffset = current;
                yield null;
            }
            case '\n' -> {
                if (peek() == '\r') advance();
                line++;
                lineStartOffset = current;
                yield null;
            }
            case '(' -> makeToken(TokenType.LPAREN);
            case ')' -> makeToken(TokenType.RPAREN);
            case '{' -> makeToken(TokenType.LBRACE);
            case '}' -> makeToken(TokenType.RBRACE);
            case ']' -> makeToken(TokenType.RBRACKET);
            case ';' -> makeToken(TokenType.SEMICOLON);
            case ',' -> makeToken(TokenType.COMMA);
            case '#' -> makeToken(TokenType.HASH);
            case '&' -> makeToken(TokenType.AMPERSAND);
            case '|' -> makeToken(TokenType.PIPE);
            case '^' -> makeToken(TokenType.CARET);
            case '%' -> makeToken(TokenType.PERCENT);
            case '+' -> makeToken(TokenType.PLUS);
            case '*' -> makeToken(TokenType.STAR);

            case '-' -> {
                if (match('-')) {
                    skipComment();
                    yield null;
                }
                yield makeToken(TokenType.MINUS);
            }

            case '/' -> {
                if (match('/')) {
                    yield makeToken(TokenType.DOUBLE_SLASH);
                }
                yield makeToken(TokenType.SLASH);
            }

            case '~' -> {
                if (match('=')) {
                    yield makeToken(TokenType.TILDE_EQUAL);
                }
                yield makeToken(TokenType.TILDE);
            }

            case '=' -> {
                if (match('=')) {
                    yield makeToken(TokenType.EQUAL_EQUAL);
                }
                yield makeToken(TokenType.ASSIGN);
            }

            case '<' -> {
                if (match('<')) {
                    yield makeToken(TokenType.SHL);
                } else if (match('=')) {
                    yield makeToken(TokenType.LESS_EQUAL);
                } else if (checkAttribute("close>")) {
                    yield makeToken(TokenType.ATTR_CLOSE);
                } else if (checkAttribute("const>")) {
                    yield makeToken(TokenType.ATTR_CONST);
                }
                yield makeToken(TokenType.LESS);
            }

            case '>' -> {
                if (match('>')) {
                    yield makeToken(TokenType.SHR);
                } else if (match('=')) {
                    yield makeToken(TokenType.GREATER_EQUAL);
                }
                yield makeToken(TokenType.GREATER);
            }

            case ':' -> {
                if (match(':')) {
                    yield makeToken(TokenType.DOUBLE_COLON);
                }
                yield makeToken(TokenType.COLON);
            }

            case '.' -> {
                if (match('.')) {
                    if (match('.')) {
                        yield makeToken(TokenType.DOT_DOT_DOT);
                    }
                    yield makeToken(TokenType.DOT_DOT);
                }
                if (isDigit(peek())) {
                    yield scanNumber(true);
                }
                yield makeToken(TokenType.DOT);
            }

            case '[' -> {
                int strStartLine = line;
                int level = checkLongBracketOpening();
                if (level >= 0) {
                    yield scanLongString(level, strStartLine);
                }
                yield makeToken(TokenType.LBRACKET);
            }

            case '"', '\'' -> scanShortString(c);

            default -> {
                if (isDigit(c)) {
                    yield scanNumber(false);
                } else if (isAlphaOrUnderscore(c)) {
                    yield scanIdentifierOrKeyword();
                }
                yield makeToken(TokenType.INVALID, String.valueOf(c));
            }
        };
    }

    private boolean checkAttribute(String attr) {
        int len = attr.length();
        if (current + len <= length && source.startsWith(attr, current)) {
            current += len;
            return true;
        }
        return false;
    }

    private void skipComment() {
        int commentStartLine = line;
        if (match('[')) {
            int level = checkLongBracketOpening();
            if (level >= 0) {
                skipLongComment(level, commentStartLine);
                return;
            }
        }
        while (peek() != '\n' && peek() != '\r' && !isAtEnd()) {
            advance();
        }
    }

    private int checkLongBracketOpening() {
        int saved = current;
        int level = 0;
        while (peek() == '=') {
            advance();
            level++;
        }
        if (peek() == '[') {
            advance();
            if (peek() == '\r') {
                advance();
                if (peek() == '\n') advance();
                line++;
                lineStartOffset = current;
            } else if (peek() == '\n') {
                advance();
                if (peek() == '\r') advance();
                line++;
                lineStartOffset = current;
            }
            return level;
        }
        current = saved;
        return -1;
    }

    private void skipLongComment(int level, int startLine) {
        while (!isAtEnd()) {
            if (peek() == ']' && checkLongBracketClosing(level)) {
                return;
            }
            char ch = advance();
            if (ch == '\r') {
                if (peek() == '\n') advance();
                line++;
                lineStartOffset = current;
            } else if (ch == '\n') {
                if (peek() == '\r') advance();
                line++;
                lineStartOffset = current;
            }
        }
        throw new ParseException("unfinished long comment (starting at line " + startLine + ") near <eof>", line, getColumn());
    }

    private boolean checkLongBracketClosing(int level) {
        int saved = current;
        advance(); // consume ']'
        int count = 0;
        while (peek() == '=') {
            advance();
            count++;
        }
        if (count == level && peek() == ']') {
            advance(); // consume closing ']'
            return true;
        }
        current = saved;
        return false;
    }

    private Token scanLongString(int level, int startLine) {
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd()) {
            if (peek() == ']' && checkLongBracketClosing(level)) {
                return makeToken(TokenType.STRING_LITERAL, sb.toString());
            }
            char ch = advance();
            if (ch == '\r') {
                if (peek() == '\n') advance();
                line++;
                lineStartOffset = current;
                sb.append('\n');
            } else if (ch == '\n') {
                if (peek() == '\r') advance();
                line++;
                lineStartOffset = current;
                sb.append('\n');
            } else {
                sb.append(ch);
            }
        }
        throw new ParseException("unfinished long string (starting at line " + startLine + ") near <eof>", line, getColumn());
    }

    private static void appendUtf8(StringBuilder sb, long x) {
        if (x < 0x80) {
            sb.append((char) x);
        } else {
            int[] buff = new int[8];
            int n = 1;
            long mfb = 0x3f;
            do {
                buff[8 - (n++)] = (int) (0x80 | (x & 0x3f));
                x >>= 6;
                mfb >>= 1;
            } while (x > mfb);
            buff[8 - n] = (int) (((~mfb << 1) & 0xFF) | x);
            for (; n > 0; n--) {
                sb.append((char) (buff[8 - n] & 0xFF));
            }
        }
    }

    private int readHexDigit() {
        if (isAtEnd()) {
            throw new ParseException("hexadecimal digit expected near '" + errorSnippet() + "'", line, getColumn());
        }
        char c = advance();
        if (!isHexDigit(c)) {
            throw new ParseException("hexadecimal digit expected near '" + errorSnippet() + "'", line, getColumn());
        }
        return Character.digit(c, 16);
    }

    private String errorSnippet() {
        return source.substring(start, Math.min(current, length));
    }

    private Token scanShortString(char quote) {
        int contentStart = current;
        StringBuilder sb = null;
        while (!isAtEnd()) {
            char p = peek();
            if (p == quote) {
                advance();
                String value = (sb == null)
                        ? source.substring(contentStart, current - 1)
                        : sb.toString();
                return new Token(TokenType.STRING_LITERAL, null, value, line, getColumn());
            }
            if (p == '\n' || p == '\r') {
                throw new ParseException("unfinished string near '" + errorSnippet() + "'", line, getColumn());
            }
            char c = advance();
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder(Math.max(16, (current - contentStart) + 8));
                    sb.append(source, contentStart, current - 1);
                }
                if (isAtEnd()) {
                    throw new ParseException("unfinished string near <eof>", line, getColumn());
                }
                char esc = advance();
                switch (esc) {
                    case 'a' -> sb.append('\u0007');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'v' -> sb.append('\u000B');
                    case '\\' -> sb.append('\\');
                    case '\"' -> sb.append('\"');
                    case '\'' -> sb.append('\'');
                    case '\n' -> {
                        if (peek() == '\r') {
                            advance();
                        }
                        line++;
                        lineStartOffset = current;
                        sb.append('\n');
                    }
                    case '\r' -> {
                        if (peek() == '\n') {
                            advance();
                        }
                        line++;
                        lineStartOffset = current;
                        sb.append('\n');
                    }
                    case 'z' -> {
                        while (!isAtEnd() && isLuaWhitespace(peek())) {
                            char ws = advance();
                            if (ws == '\r') {
                                if (peek() == '\n') {
                                    advance();
                                }
                                line++;
                                lineStartOffset = current;
                            } else if (ws == '\n') {
                                if (peek() == '\r') {
                                    advance();
                                }
                                line++;
                                lineStartOffset = current;
                            }
                        }
                    }
                    case 'x' -> {
                        int h1 = readHexDigit();
                        int h2 = readHexDigit();
                        sb.append((char) ((h1 << 4) | h2));
                    }
                    case 'u' -> {
                        if (isAtEnd() || peek() != '{') {
                            if (!isAtEnd()) {
                                advance();
                            }
                            throw new ParseException("missing '{' near '" + errorSnippet() + "'", line, getColumn());
                        }
                        advance(); // consume '{'
                        int firstHex = readHexDigit();
                        long r = firstHex;
                        while (!isAtEnd() && isHexDigit(peek())) {
                            char nextHex = advance();
                            if (r > (0x7FFFFFFFL >> 4)) {
                                throw new ParseException("UTF-8 value too large near '" + errorSnippet() + "'", line, getColumn());
                            }
                            r = (r << 4) | Character.digit(nextHex, 16);
                        }
                        if (isAtEnd() || peek() != '}') {
                            if (!isAtEnd()) {
                                advance();
                            }
                            throw new ParseException("missing '}' near '" + errorSnippet() + "'", line, getColumn());
                        }
                        advance(); // consume '}'
                        appendUtf8(sb, r);
                    }
                    default -> {
                        if (isDigit(esc)) {
                            int r = esc - '0';
                            int count = 1;
                            while (count < 3 && !isAtEnd() && isDigit(peek())) {
                                char d = advance();
                                r = r * 10 + (d - '0');
                                count++;
                            }
                            if (r > 255) {
                                if (!isAtEnd()) {
                                    advance();
                                }
                                throw new ParseException("decimal escape too large near '" + errorSnippet() + "'", line, getColumn());
                            }
                            sb.append((char) r);
                        } else {
                            throw new ParseException("invalid escape sequence near '" + errorSnippet() + "'", line, getColumn());
                        }
                    }
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
        throw new ParseException("unfinished string near <eof>", line, getColumn());
    }

    private Token scanNumber(boolean startedWithDot) {
        char expoLower = 'e';
        char expoUpper = 'E';
        boolean simpleDecimalInt = !startedWithDot;
        char first = source.charAt(start);
        if (startedWithDot) {
            advance(); // consume the first digit after dot
        } else if (first == '0' && (peek() == 'x' || peek() == 'X')) {
            expoLower = 'p';
            expoUpper = 'P';
            simpleDecimalInt = false;
            advance(); // consume 'x' / 'X'
        }

        while (!isAtEnd()) {
            char p = peek();
            if (p == expoLower || p == expoUpper) {
                simpleDecimalInt = false;
                advance();
                if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
            } else if (isDigit(p)) {
                advance();
            } else if (isHexDigit(p) || p == '.') {
                simpleDecimalInt = false;
                advance();
            } else {
                break;
            }
        }

        if (!isAtEnd() && isAlphaOrUnderscore(peek())) {
            simpleDecimalInt = false;
            advance(); // force an error
        }

        if (simpleDecimalInt && (current - start) <= 18
                && (current - start == 1 || source.charAt(start) != '0')) {
            long val = 0;
            for (int i = start; i < current; i++) {
                val = val * 10 + (source.charAt(i) - '0');
            }
            return new Token(TokenType.INTEGER_LITERAL, null, val, line, getColumn());
        }

        String raw = source.substring(start, current);
        LuaValue num = LuaValue.parseNumber(raw, 0, raw.length());
        if (num == null) {
            throw new ParseException("malformed number near '" + raw + "'", line, getColumn());
        }

        if (num.isInteger()) {
            return new Token(TokenType.INTEGER_LITERAL, raw, num.toLong(), line, getColumn());
        } else {
            return new Token(TokenType.FLOAT_LITERAL, raw, num.toDouble(), line, getColumn());
        }
    }

    private Token scanIdentifierOrKeyword() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        TokenType kw = matchKeyword(start, current);
        if (kw != null) {
            return new Token(kw, kw.fixedLexeme(), null, line, getColumn());
        }
        String text = source.substring(start, current);
        return new Token(TokenType.IDENTIFIER, text, text, line, getColumn());
    }

    private TokenType matchKeyword(int s, int e) {
        int len = e - s;
        return switch (len) {
            case 2 -> {
                char c0 = source.charAt(s);
                char c1 = source.charAt(s + 1);
                if (c0 == 'd' && c1 == 'o') yield TokenType.DO;
                if (c0 == 'i' && c1 == 'f') yield TokenType.IF;
                if (c0 == 'i' && c1 == 'n') yield TokenType.IN;
                if (c0 == 'o' && c1 == 'r') yield TokenType.OR;
                yield null;
            }
            case 3 -> {
                if (source.regionMatches(s, "end", 0, 3)) yield TokenType.END;
                if (source.regionMatches(s, "and", 0, 3)) yield TokenType.AND;
                if (source.regionMatches(s, "for", 0, 3)) yield TokenType.FOR;
                if (source.regionMatches(s, "nil", 0, 3)) yield TokenType.NIL;
                if (source.regionMatches(s, "not", 0, 3)) yield TokenType.NOT;
                yield null;
            }
            case 4 -> {
                if (source.regionMatches(s, "then", 0, 4)) yield TokenType.THEN;
                if (source.regionMatches(s, "else", 0, 4)) yield TokenType.ELSE;
                if (source.regionMatches(s, "true", 0, 4)) yield TokenType.TRUE;
                if (source.regionMatches(s, "goto", 0, 4)) yield TokenType.GOTO;
                yield null;
            }
            case 5 -> {
                if (source.regionMatches(s, "local", 0, 5)) yield TokenType.LOCAL;
                if (source.regionMatches(s, "false", 0, 5)) yield TokenType.FALSE;
                if (source.regionMatches(s, "until", 0, 5)) yield TokenType.UNTIL;
                if (source.regionMatches(s, "while", 0, 5)) yield TokenType.WHILE;
                if (source.regionMatches(s, "break", 0, 5)) yield TokenType.BREAK;
                yield null;
            }
            case 6 -> {
                if (source.regionMatches(s, "return", 0, 6)) yield TokenType.RETURN;
                if (source.regionMatches(s, "elseif", 0, 6)) yield TokenType.ELSEIF;
                if (source.regionMatches(s, "repeat", 0, 6)) yield TokenType.REPEAT;
                yield null;
            }
            case 8 -> source.regionMatches(s, "function", 0, 8) ? TokenType.FUNCTION : null;
            default -> null;
        };
    }

    private boolean match(char expected) {
        if (isAtEnd() || source.charAt(current) != expected) {
            return false;
        }
        current++;
        return true;
    }

    private char advance() {
        return source.charAt(current++);
    }

    private char peek() {
        if (isAtEnd()) {
            return '\0';
        }
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= length) {
            return '\0';
        }
        return source.charAt(current + 1);
    }

    private static boolean isLuaWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000B';
    }

    private boolean isAtEnd() {
        return current >= length;
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isAlphaOrUnderscore(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private boolean isAlphaNumeric(char c) {
        return isAlphaOrUnderscore(c) || isDigit(c);
    }

    private int getColumn() {
        return start - lineStartOffset + 1;
    }

    private Token makeToken(TokenType type) {
        String lexeme = type.fixedLexeme();
        if (lexeme == null) {
            lexeme = source.substring(start, current);
        }
        return new Token(type, lexeme, null, line, getColumn());
    }

    private Token makeToken(TokenType type, Object literal) {
        String lexeme = type.fixedLexeme();
        if (lexeme == null) {
            lexeme = source.substring(start, current);
        }
        return new Token(type, lexeme, literal, line, getColumn());
    }

    public static class LexerException extends ParseException {
        public LexerException(String message) {
            super(message, 1, 1);
        }

        public LexerException(String message, int line, int column) {
            super(message, line, column);
        }
    }
}

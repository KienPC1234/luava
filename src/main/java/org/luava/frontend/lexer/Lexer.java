/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.lexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.luava.frontend.parser.ParseException;
import org.luava.runtime.LuaValue;

public final class Lexer {
    private static final Map<String, TokenType> KEYWORDS = new HashMap<>();

    static {
        KEYWORDS.put("and", TokenType.AND);
        KEYWORDS.put("break", TokenType.BREAK);
        KEYWORDS.put("do", TokenType.DO);
        KEYWORDS.put("else", TokenType.ELSE);
        KEYWORDS.put("elseif", TokenType.ELSEIF);
        KEYWORDS.put("end", TokenType.END);
        KEYWORDS.put("false", TokenType.FALSE);
        KEYWORDS.put("for", TokenType.FOR);
        KEYWORDS.put("function", TokenType.FUNCTION);
        KEYWORDS.put("goto", TokenType.GOTO);
        KEYWORDS.put("if", TokenType.IF);
        KEYWORDS.put("in", TokenType.IN);
        KEYWORDS.put("local", TokenType.LOCAL);
        KEYWORDS.put("nil", TokenType.NIL);
        KEYWORDS.put("not", TokenType.NOT);
        KEYWORDS.put("or", TokenType.OR);
        KEYWORDS.put("repeat", TokenType.REPEAT);
        KEYWORDS.put("return", TokenType.RETURN);
        KEYWORDS.put("then", TokenType.THEN);
        KEYWORDS.put("true", TokenType.TRUE);
        KEYWORDS.put("until", TokenType.UNTIL);
        KEYWORDS.put("while", TokenType.WHILE);
    }

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
        List<Token> tokens = new ArrayList<>();
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

    private int readHexDigit(StringBuilder tokenBuff) {
        if (isAtEnd()) {
            throw new ParseException("hexadecimal digit expected near '" + tokenBuff + "'", line, getColumn());
        }
        char c = advance();
        tokenBuff.append(c);
        if (!isHexDigit(c)) {
            throw new ParseException("hexadecimal digit expected near '" + tokenBuff + "'", line, getColumn());
        }
        return Character.digit(c, 16);
    }

    private Token scanShortString(char quote) {
        StringBuilder sb = new StringBuilder();
        StringBuilder tokenBuff = new StringBuilder();
        tokenBuff.append(quote);
        while (!isAtEnd() && peek() != quote) {
            if (peek() == '\n' || peek() == '\r') {
                throw new ParseException("unfinished string near '" + tokenBuff + "'", line, getColumn());
            }
            char c = advance();
            tokenBuff.append(c);
            if (c == '\\') {
                if (isAtEnd()) {
                    throw new ParseException("unfinished string near <eof>", line, getColumn());
                }
                char esc = advance();
                tokenBuff.append(esc);
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
                            tokenBuff.append('\r');
                        }
                        line++;
                        lineStartOffset = current;
                        sb.append('\n');
                    }
                    case '\r' -> {
                        if (peek() == '\n') {
                            advance();
                            tokenBuff.append('\n');
                        }
                        line++;
                        lineStartOffset = current;
                        sb.append('\n');
                    }
                    case 'z' -> {
                        while (!isAtEnd() && isLuaWhitespace(peek())) {
                            char ws = advance();
                            tokenBuff.append(ws);
                            if (ws == '\r') {
                                if (peek() == '\n') {
                                    advance();
                                    tokenBuff.append('\n');
                                }
                                line++;
                                lineStartOffset = current;
                            } else if (ws == '\n') {
                                if (peek() == '\r') {
                                    advance();
                                    tokenBuff.append('\r');
                                }
                                line++;
                                lineStartOffset = current;
                            }
                        }
                    }
                    case 'x' -> {
                        int h1 = readHexDigit(tokenBuff);
                        int h2 = readHexDigit(tokenBuff);
                        sb.append((char) ((h1 << 4) | h2));
                    }
                    case 'u' -> {
                        if (isAtEnd() || peek() != '{') {
                            if (!isAtEnd()) {
                                tokenBuff.append(advance());
                            }
                            throw new ParseException("missing '{' near '" + tokenBuff + "'", line, getColumn());
                        }
                        tokenBuff.append(advance()); // consume '{'
                        int firstHex = readHexDigit(tokenBuff);
                        long r = firstHex;
                        while (!isAtEnd() && isHexDigit(peek())) {
                            char nextHex = advance();
                            tokenBuff.append(nextHex);
                            if (r > (0x7FFFFFFFL >> 4)) {
                                throw new ParseException("UTF-8 value too large near '" + tokenBuff + "'", line, getColumn());
                            }
                            r = (r << 4) | Character.digit(nextHex, 16);
                        }
                        if (isAtEnd() || peek() != '}') {
                            if (!isAtEnd()) {
                                tokenBuff.append(advance());
                            }
                            throw new ParseException("missing '}' near '" + tokenBuff + "'", line, getColumn());
                        }
                        tokenBuff.append(advance()); // consume '}'
                        appendUtf8(sb, r);
                    }
                    default -> {
                        if (isDigit(esc)) {
                            int r = esc - '0';
                            int count = 1;
                            while (count < 3 && !isAtEnd() && isDigit(peek())) {
                                char d = advance();
                                tokenBuff.append(d);
                                r = r * 10 + (d - '0');
                                count++;
                            }
                            if (r > 255) {
                                if (!isAtEnd()) {
                                    tokenBuff.append(advance());
                                }
                                throw new ParseException("decimal escape too large near '" + tokenBuff + "'", line, getColumn());
                            }
                            sb.append((char) r);
                        } else {
                            throw new ParseException("invalid escape sequence near '" + tokenBuff + "'", line, getColumn());
                        }
                    }
                }
            } else {
                sb.append(c);
            }
        }
        if (isAtEnd()) {
            throw new ParseException("unfinished string near <eof>", line, getColumn());
        }
        advance(); // consume closing quote
        return makeToken(TokenType.STRING_LITERAL, sb.toString());
    }

    private Token scanNumber(boolean startedWithDot) {
        String expo = "Ee";
        char first = source.charAt(start);
        if (startedWithDot) {
            advance(); // consume the first digit after dot
        } else if (first == '0' && (peek() == 'x' || peek() == 'X')) {
            expo = "Pp";
            advance(); // consume 'x' / 'X'
        }

        while (!isAtEnd()) {
            char p = peek();
            if (p == expo.charAt(0) || p == expo.charAt(1)) {
                advance();
                if (!isAtEnd() && (peek() == '+' || peek() == '-')) {
                    advance();
                }
            } else if (isHexDigit(p) || p == '.') {
                advance();
            } else {
                break;
            }
        }

        if (!isAtEnd() && isAlphaOrUnderscore(peek())) {
            advance(); // force an error
        }

        String raw = source.substring(start, current);
        LuaValue num = LuaValue.parseNumber(raw);
        if (num == null) {
            throw new ParseException("malformed number near '" + raw + "'", line, getColumn());
        }

        if (num.isInteger()) {
            return makeToken(TokenType.INTEGER_LITERAL, num.toLong());
        } else {
            return makeToken(TokenType.FLOAT_LITERAL, num.toDouble());
        }
    }

    private Token scanIdentifierOrKeyword() {
        while (isAlphaNumeric(peek())) {
            advance();
        }
        String text = source.substring(start, current);
        TokenType type = KEYWORDS.getOrDefault(text, TokenType.IDENTIFIER);
        return makeToken(type, text);
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
        return makeToken(type, null);
    }

    private Token makeToken(TokenType type, Object literal) {
        String lexeme = source.substring(start, current);
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

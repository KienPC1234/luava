package org.luava.frontend.lexer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int lineStartOffset = 0;

    public Lexer(String source) {
        this.source = source != null ? source : "";
        this.length = this.source.length();
    }

    public List<Token> scanTokens() {
        List<Token> tokens = new ArrayList<>();
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
            case ' ', '\r', '\t' -> null;
            case '\n' -> {
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
                int level = checkLongBracketOpening();
                if (level >= 0) {
                    yield scanLongString(level);
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
                throw new LexerException("Unexpected character '" + c + "' at line " + line + ", col " + getColumn());
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
        if (match('[')) {
            int level = checkLongBracketOpening();
            if (level >= 0) {
                skipLongComment(level);
                return;
            }
        }
        while (peek() != '\n' && !isAtEnd()) {
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
            if (peek() == '\n') {
                line++;
                advance();
                lineStartOffset = current;
            }
            return level;
        }
        current = saved;
        return -1;
    }

    private void skipLongComment(int level) {
        while (!isAtEnd()) {
            if (peek() == '\n') {
                line++;
                advance();
                lineStartOffset = current;
                continue;
            }
            if (peek() == ']' && checkLongBracketClosing(level)) {
                return;
            }
            advance();
        }
        throw new LexerException("Unfinished long comment starting at line " + line);
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

    private Token scanLongString(int level) {
        int contentStart = current;
        while (!isAtEnd()) {
            if (peek() == '\n') {
                line++;
                advance();
                lineStartOffset = current;
                continue;
            }
            if (peek() == ']' && checkLongBracketClosing(level)) {
                int contentEnd = current - (level + 2);
                String literal = source.substring(contentStart, contentEnd);
                return makeToken(TokenType.STRING_LITERAL, literal);
            }
            advance();
        }
        throw new LexerException("Unfinished long string starting at line " + line);
    }

    private Token scanShortString(char quote) {
        StringBuilder sb = new StringBuilder();
        while (peek() != quote && !isAtEnd()) {
            if (peek() == '\n' || peek() == '\r') {
                throw new LexerException("Unfinished string literal at line " + line);
            }
            char c = advance();
            if (c == '\\') {
                if (isAtEnd()) {
                    throw new LexerException("Unfinished string escape at line " + line);
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
                    case 'z' -> {
                        // Lua 5.4: skip following whitespace characters
                        while (!isAtEnd() && Character.isWhitespace(peek())) {
                            if (peek() == '\n') {
                                line++;
                                lineStartOffset = current + 1;
                            }
                            advance();
                        }
                    }
                    case 'x' -> {
                        char h1 = advance();
                        char h2 = advance();
                        int hexVal = Integer.parseInt("" + h1 + h2, 16);
                        sb.append((char) hexVal);
                    }
                    case 'u' -> {
                        if (peek() != '{') {
                            throw new LexerException("Malformed UTF-8 escape at line " + line);
                        }
                        advance(); // consume '{'
                        StringBuilder hexSb = new StringBuilder();
                        while (peek() != '}' && !isAtEnd()) {
                            hexSb.append(advance());
                        }
                        if (peek() != '}') {
                            throw new LexerException("Unfinished UTF-8 escape at line " + line);
                        }
                        advance(); // consume '}'
                        int codePoint = Integer.parseInt(hexSb.toString(), 16);
                        sb.append(Character.toChars(codePoint));
                    }
                    default -> {
                        if (isDigit(esc)) {
                            StringBuilder numSb = new StringBuilder();
                            numSb.append(esc);
                            if (isDigit(peek())) {
                                numSb.append(advance());
                                if (isDigit(peek())) {
                                    numSb.append(advance());
                                }
                            }
                            int decVal = Integer.parseInt(numSb.toString());
                            sb.append((char) decVal);
                        } else {
                            sb.append(esc);
                        }
                    }
                }
            } else {
                sb.append(c);
            }
        }
        if (isAtEnd()) {
            throw new LexerException("Unfinished string literal at line " + line);
        }
        advance(); // consume closing quote
        return makeToken(TokenType.STRING_LITERAL, sb.toString());
    }

    private Token scanNumber(boolean startedWithDot) {
        boolean isHex = false;
        boolean isFloat = startedWithDot;

        if (!startedWithDot && source.charAt(start) == '0' && (peek() == 'x' || peek() == 'X')) {
            isHex = true;
            advance(); // consume 'x' / 'X'
        }

        if (isHex) {
            while (isHexDigit(peek())) {
                advance();
            }
            if (peek() == '.' && peekNext() != '.') {
                isFloat = true;
                advance(); // consume '.'
                while (isHexDigit(peek())) {
                    advance();
                }
            }
            if (peek() == 'p' || peek() == 'P') {
                isFloat = true;
                advance(); // consume 'p' / 'P'
                if (peek() == '+' || peek() == '-') {
                    advance();
                }
                while (isDigit(peek())) {
                    advance();
                }
            }
            String raw = source.substring(start, current);
            if (isFloat) {
                double val = Double.parseDouble(raw);
                return makeToken(TokenType.FLOAT_LITERAL, val);
            } else {
                long val = parseHexLong(raw);
                return makeToken(TokenType.INTEGER_LITERAL, val);
            }
        } else {
            while (isDigit(peek())) {
                advance();
            }
            if (!startedWithDot && peek() == '.' && peekNext() != '.') {
                isFloat = true;
                advance(); // consume '.'
                while (isDigit(peek())) {
                    advance();
                }
            }
            if (peek() == 'e' || peek() == 'E') {
                isFloat = true;
                advance(); // consume 'e' / 'E'
                if (peek() == '+' || peek() == '-') {
                    advance();
                }
                while (isDigit(peek())) {
                    advance();
                }
            }
            String raw = source.substring(start, current);
            if (isFloat) {
                double val = Double.parseDouble(raw);
                return makeToken(TokenType.FLOAT_LITERAL, val);
            } else {
                long val = Long.parseLong(raw);
                return makeToken(TokenType.INTEGER_LITERAL, val);
            }
        }
    }

    private long parseHexLong(String raw) {
        String num = raw.substring(2);
        if (num.length() > 16) {
            num = num.substring(num.length() - 16);
        }
        return Long.parseUnsignedLong(num, 16);
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

    public static class LexerException extends RuntimeException {
        public LexerException(String message) {
            super(message);
        }
    }
}

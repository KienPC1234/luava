/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.lexer;

import java.util.Objects;

/**
 * Lexical token produced by {@link Lexer}.
 *
 * <p>Preserves the public {@link #lexeme()} contract for all callers while
 * allowing the lexer to defer substring materialization on literal tokens
 * until {@link #lexeme()} is requested.
 */
public final class Token {
    private final TokenType type;
    private String lexeme;
    private final String sourceRef;
    private final int startOffset;
    private final int endOffset;
    private final Object literal;
    private final int line;
    private final int column;

    public Token(TokenType type, String lexeme, Object literal, int line, int column) {
        this.type = type;
        this.lexeme = lexeme;
        this.sourceRef = null;
        this.startOffset = 0;
        this.endOffset = 0;
        this.literal = literal;
        this.line = line;
        this.column = column;
    }

    public Token(TokenType type, String lexeme, int line, int column) {
        this(type, lexeme, null, line, column);
    }

    Token(TokenType type, String sourceRef, int startOffset, int endOffset, Object literal, int line, int column) {
        this.type = type;
        this.lexeme = null;
        this.sourceRef = sourceRef;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.literal = literal;
        this.line = line;
        this.column = column;
    }

    public TokenType type() {
        return type;
    }

    public String lexeme() {
        String s = lexeme;
        if (s == null && sourceRef != null) {
            s = sourceRef.substring(startOffset, endOffset);
            lexeme = s;
        }
        return s;
    }

    public Object literal() {
        return literal;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Token other)) {
            return false;
        }
        return line == other.line
                && column == other.column
                && type == other.type
                && Objects.equals(lexeme(), other.lexeme())
                && Objects.equals(literal, other.literal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, lexeme(), literal, line, column);
    }

    @Override
    public String toString() {
        return "Token[type=" + type
                + ", lexeme=" + lexeme()
                + ", literal=" + literal
                + ", line=" + line
                + ", column=" + column + "]";
    }
}

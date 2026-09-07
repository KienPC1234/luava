package org.luava.frontend.lexer;

public record Token(
    TokenType type,
    String lexeme,
    Object literal,
    int line,
    int column
) {
    public Token(TokenType type, String lexeme, int line, int column) {
        this(type, lexeme, null, line, column);
    }
}

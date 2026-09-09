package org.luava.frontend.lexer;

public enum TokenType {
    // Keywords
    AND, BREAK, DO, ELSE, ELSEIF, END, FALSE, FOR, FUNCTION,
    GOTO, IF, IN, LOCAL, NIL, NOT, OR, REPEAT, RETURN,
    THEN, TRUE, UNTIL, WHILE,

    // Lua 5.4 Attributes
    ATTR_CLOSE,  // <close>
    ATTR_CONST,  // <const>

    // Literals
    IDENTIFIER,
    STRING_LITERAL,
    INTEGER_LITERAL,
    FLOAT_LITERAL,

    // Arithmetic Operators
    PLUS,       // +
    MINUS,      // -
    STAR,       // *
    SLASH,      // /
    DOUBLE_SLASH, // // (floor division)
    CARET,      // ^ (exponentiation)
    PERCENT,    // % (modulo)

    // Bitwise Operators (Lua 5.3 / 5.4)
    AMPERSAND,  // &
    TILDE,      // ~ (bitwise NOT / XOR)
    PIPE,       // |
    SHL,        // <<
    SHR,        // >>

    // Relational Operators
    EQUAL_EQUAL, // ==
    TILDE_EQUAL, // ~=
    LESS,        // <
    LESS_EQUAL,  // <=
    GREATER,     // >
    GREATER_EQUAL,// >=

    // Other Operators & Delimiters
    ASSIGN,      // =
    HASH,        // # (length operator)
    DOT_DOT,     // .. (concatenation)
    DOT_DOT_DOT, // ... (vararg)
    DOUBLE_COLON,// :: (labels)
    SEMICOLON,   // ;
    COMMA,       // ,
    DOT,         // .
    COLON,       // :
    LPAREN,      // (
    RPAREN,      // )
    LBRACKET,    // [
    RBRACKET,    // ]
    LBRACE,      // {
    RBRACE,      // }

    INVALID,
    EOF
}

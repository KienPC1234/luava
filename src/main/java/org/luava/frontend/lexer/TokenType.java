/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.lexer;

public enum TokenType {
    // Keywords
    AND("and"), BREAK("break"), DO("do"), ELSE("else"), ELSEIF("elseif"), END("end"),
    FALSE("false"), FOR("for"), FUNCTION("function"), GOTO("goto"), IF("if"), IN("in"),
    LOCAL("local"), NIL("nil"), NOT("not"), OR("or"), REPEAT("repeat"), RETURN("return"),
    THEN("then"), TRUE("true"), UNTIL("until"), WHILE("while"),

    // Lua 5.4 Attributes
    ATTR_CLOSE("<close>"),
    ATTR_CONST("<const>"),

    // Literals
    IDENTIFIER(null),
    STRING_LITERAL(null),
    INTEGER_LITERAL(null),
    FLOAT_LITERAL(null),

    // Arithmetic Operators
    PLUS("+"),
    MINUS("-"),
    STAR("*"),
    SLASH("/"),
    DOUBLE_SLASH("//"),
    CARET("^"),
    PERCENT("%"),

    // Bitwise Operators (Lua 5.3 / 5.4)
    AMPERSAND("&"),
    TILDE("~"),
    PIPE("|"),
    SHL("<<"),
    SHR(">>"),

    // Relational Operators
    EQUAL_EQUAL("=="),
    TILDE_EQUAL("~="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),

    // Other Operators & Delimiters
    ASSIGN("="),
    HASH("#"),
    DOT_DOT(".."),
    DOT_DOT_DOT("..."),
    DOUBLE_COLON("::"),
    SEMICOLON(";"),
    COMMA(","),
    DOT("."),
    COLON(":"),
    LPAREN("("),
    RPAREN(")"),
    LBRACKET("["),
    RBRACKET("]"),
    LBRACE("{"),
    RBRACE("}"),

    INVALID(null),
    EOF("");

    private final String fixedLexeme;

    TokenType(String fixedLexeme) {
        this.fixedLexeme = fixedLexeme;
    }

    public String fixedLexeme() {
        return fixedLexeme;
    }
}

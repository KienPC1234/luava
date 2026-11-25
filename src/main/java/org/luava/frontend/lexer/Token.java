/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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

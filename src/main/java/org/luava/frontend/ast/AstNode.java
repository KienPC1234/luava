package org.luava.frontend.ast;

public sealed interface AstNode permits Expression, Statement {
    int line();
    int column();
}

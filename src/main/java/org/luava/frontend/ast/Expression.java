package org.luava.frontend.ast;

public sealed interface Expression extends AstNode
    permits Expressions.NilLiteral,
            Expressions.BooleanLiteral,
            Expressions.IntegerLiteral,
            Expressions.FloatLiteral,
            Expressions.StringLiteral,
            Expressions.VarargLiteral,
            Expressions.VariableExpr,
            Expressions.BinaryExpr,
            Expressions.UnaryExpr,
            Expressions.TableConstructorExpr,
            Expressions.TableAccessExpr,
            Expressions.FunctionCallExpr,
            Expressions.FunctionDefExpr,
            Expressions.ParenExpr {
}

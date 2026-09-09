package org.luava.frontend.ast;

import org.luava.frontend.lexer.TokenType;
import java.util.List;

public final class Expressions {
    private Expressions() {}

    public record NilLiteral(int line, int column) implements Expression {}

    public record BooleanLiteral(boolean value, int line, int column) implements Expression {}

    public record IntegerLiteral(long value, int line, int column) implements Expression {}

    public record FloatLiteral(double value, int line, int column) implements Expression {}

    public record StringLiteral(String value, int line, int column, org.luava.runtime.LuaString luaString) implements Expression {
        public StringLiteral(String value, int line, int column) {
            this(value, line, column, org.luava.runtime.LuaString.valueOf(value));
        }
    }

    public record VarargLiteral(int line, int column) implements Expression {}

    public record VariableExpr(String name, int line, int column) implements Expression {}

    public record BinaryExpr(
        Expression left,
        TokenType operator,
        Expression right,
        int line,
        int column
    ) implements Expression {}

    public record UnaryExpr(
        TokenType operator,
        Expression operand,
        int line,
        int column
    ) implements Expression {}

    public record TableField(Expression key, Expression value) {}

    public record TableConstructorExpr(
        List<TableField> fields,
        int line,
        int column
    ) implements Expression {}

    public record TableAccessExpr(
        Expression table,
        Expression key,
        int line,
        int column
    ) implements Expression {}

    public record FunctionCallExpr(
        Expression target,
        String methodName,
        List<Expression> arguments,
        int line,
        int column
    ) implements Expression {}

    public record FunctionDefExpr(
        List<String> parameters,
        boolean isVararg,
        Statements.BlockStmt body,
        int line,
        int column,
        int endLine
    ) implements Expression {
        public FunctionDefExpr(List<String> parameters, boolean isVararg, Statements.BlockStmt body, int line, int column) {
            this(parameters, isVararg, body, line, column, line);
        }
    }

    public record ParenExpr(
        Expression expression,
        int line,
        int column
    ) implements Expression {}
}

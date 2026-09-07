package org.luava.frontend.ast;

import java.util.List;

public final class Statements {
    private Statements() {}

    public record BlockStmt(
        List<Statement> statements,
        int line,
        int column
    ) implements Statement {}

    public enum VariableAttribute {
        NONE,
        CONST,
        CLOSE
    }

    public record LocalVarBinding(
        String name,
        VariableAttribute attribute
    ) {}

    public record LocalVarDeclStmt(
        List<LocalVarBinding> bindings,
        List<Expression> initializers,
        int line,
        int column
    ) implements Statement {}

    public record AssignmentStmt(
        List<Expression> targets,
        List<Expression> values,
        int line,
        int column
    ) implements Statement {}

    public record IfBranch(
        Expression condition,
        BlockStmt block
    ) {}

    public record IfStmt(
        List<IfBranch> branches,
        BlockStmt elseBlock,
        int line,
        int column
    ) implements Statement {}

    public record WhileStmt(
        Expression condition,
        BlockStmt body,
        int line,
        int column
    ) implements Statement {}

    public record RepeatStmt(
        BlockStmt body,
        Expression condition,
        int line,
        int column
    ) implements Statement {}

    public record ForNumericStmt(
        String variableName,
        Expression start,
        Expression limit,
        Expression step,
        BlockStmt body,
        int line,
        int column
    ) implements Statement {}

    public record ForGenericStmt(
        List<String> variableNames,
        List<Expression> iterators,
        BlockStmt body,
        int line,
        int column
    ) implements Statement {}

    public record FunctionDefStmt(
        Expression targetName,
        boolean isMethod,
        List<String> parameters,
        boolean isVararg,
        BlockStmt body,
        int line,
        int column
    ) implements Statement {}

    public record LocalFunctionDefStmt(
        String name,
        List<String> parameters,
        boolean isVararg,
        BlockStmt body,
        int line,
        int column
    ) implements Statement {}

    public record ReturnStmt(
        List<Expression> values,
        int line,
        int column
    ) implements Statement {}

    public record BreakStmt(int line, int column) implements Statement {}

    public record GotoStmt(String label, int line, int column) implements Statement {}

    public record LabelStmt(String name, int line, int column) implements Statement {}

    public record ExprStmt(Expression expression, int line, int column) implements Statement {}
}

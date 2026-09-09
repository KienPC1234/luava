package org.luava.frontend.ast;

import java.util.List;

public final class Statements {
    private Statements() {}

    public record BlockStmt(
        List<Statement> statements,
        int line,
        int column,
        int endLine
    ) implements Statement {
        public BlockStmt(List<Statement> statements, int line, int column) {
            this(statements, line, column, line);
        }
    }

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
        BlockStmt block,
        int thenLine
    ) {
        public IfBranch(Expression condition, BlockStmt block) {
            this(condition, block, condition != null ? condition.line() : 0);
        }
    }

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
        int column,
        int endLine
    ) implements Statement {
        public WhileStmt(Expression condition, BlockStmt body, int line, int column) {
            this(condition, body, line, column, line);
        }
    }

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
        int column,
        int endLine
    ) implements Statement {
        public ForNumericStmt(String variableName, Expression start, Expression limit, Expression step, BlockStmt body, int line, int column) {
            this(variableName, start, limit, step, body, line, column, line);
        }
    }

    public record ForGenericStmt(
        List<String> variableNames,
        List<Expression> iterators,
        BlockStmt body,
        int line,
        int column,
        int endLine
    ) implements Statement {
        public ForGenericStmt(List<String> variableNames, List<Expression> iterators, BlockStmt body, int line, int column) {
            this(variableNames, iterators, body, line, column, line);
        }
    }

    public record FunctionDefStmt(
        Expression targetName,
        boolean isMethod,
        List<String> parameters,
        boolean isVararg,
        BlockStmt body,
        int line,
        int column,
        int endLine
    ) implements Statement {
        public FunctionDefStmt(Expression targetName, boolean isMethod, List<String> parameters, boolean isVararg, BlockStmt body, int line, int column) {
            this(targetName, isMethod, parameters, isVararg, body, line, column, line);
        }
    }

    public record LocalFunctionDefStmt(
        String name,
        List<String> parameters,
        boolean isVararg,
        BlockStmt body,
        int line,
        int column,
        int endLine
    ) implements Statement {
        public LocalFunctionDefStmt(String name, List<String> parameters, boolean isVararg, BlockStmt body, int line, int column) {
            this(name, parameters, isVararg, body, line, column, line);
        }
    }

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

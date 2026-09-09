package org.luava.frontend.ast;

import org.luava.frontend.lexer.TokenType;

public final class AstPrinter {
    private AstPrinter() {}

    public static String print(Statement stmt) {
        StringBuilder sb = new StringBuilder();
        printStatement(stmt, sb);
        return sb.toString();
    }

    public static String print(Expression expr) {
        StringBuilder sb = new StringBuilder();
        printExpression(expr, sb);
        return sb.toString();
    }

    public static void printStatement(Statement stmt, StringBuilder sb) {
        switch (stmt) {
            case Statements.BlockStmt b -> {
                for (Statement s : b.statements()) {
                    printStatement(s, sb);
                    sb.append(";\n");
                }
            }
            case Statements.LocalVarDeclStmt l -> {
                sb.append("local ");
                for (int i = 0; i < l.bindings().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Statements.LocalVarBinding b = l.bindings().get(i);
                    sb.append(b.name());
                    if (b.attribute() == Statements.VariableAttribute.CONST) {
                        sb.append(" <const>");
                    } else if (b.attribute() == Statements.VariableAttribute.CLOSE) {
                        sb.append(" <close>");
                    }
                }
                if (!l.initializers().isEmpty()) {
                    sb.append(" = ");
                    for (int i = 0; i < l.initializers().size(); i++) {
                        if (i > 0) sb.append(", ");
                        printExpression(l.initializers().get(i), sb);
                    }
                }
            }
            case Statements.AssignmentStmt a -> {
                for (int i = 0; i < a.targets().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printExpression(a.targets().get(i), sb);
                }
                sb.append(" = ");
                for (int i = 0; i < a.values().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printExpression(a.values().get(i), sb);
                }
            }
            case Statements.IfStmt ifs -> {
                for (int i = 0; i < ifs.branches().size(); i++) {
                    Statements.IfBranch branch = ifs.branches().get(i);
                    sb.append(i == 0 ? "if " : "elseif ");
                    printExpression(branch.condition(), sb);
                    sb.append(" then\n");
                    printStatement(branch.block(), sb);
                }
                if (ifs.elseBlock() != null && !ifs.elseBlock().statements().isEmpty()) {
                    sb.append("else\n");
                    printStatement(ifs.elseBlock(), sb);
                }
                sb.append("end");
            }
            case Statements.WhileStmt w -> {
                sb.append("while ");
                printExpression(w.condition(), sb);
                sb.append(" do\n");
                printStatement(w.body(), sb);
                sb.append("end");
            }
            case Statements.RepeatStmt r -> {
                sb.append("repeat\n");
                printStatement(r.body(), sb);
                sb.append("until ");
                printExpression(r.condition(), sb);
            }
            case Statements.ForNumericStmt fn -> {
                sb.append("for ").append(fn.variableName()).append(" = ");
                printExpression(fn.start(), sb);
                sb.append(", ");
                printExpression(fn.limit(), sb);
                if (fn.step() != null) {
                    sb.append(", ");
                    printExpression(fn.step(), sb);
                }
                sb.append(" do\n");
                printStatement(fn.body(), sb);
                sb.append("end");
            }
            case Statements.ForGenericStmt fg -> {
                sb.append("for ");
                for (int i = 0; i < fg.variableNames().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(fg.variableNames().get(i));
                }
                sb.append(" in ");
                for (int i = 0; i < fg.iterators().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printExpression(fg.iterators().get(i), sb);
                }
                sb.append(" do\n");
                printStatement(fg.body(), sb);
                sb.append("end");
            }
            case Statements.FunctionDefStmt fd -> {
                sb.append("function ");
                printExpression(fd.targetName(), sb);
                if (fd.isMethod()) sb.append(":");
                sb.append("(");
                for (int i = 0; i < fd.parameters().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(fd.parameters().get(i));
                }
                if (fd.isVararg()) {
                    if (!fd.parameters().isEmpty()) sb.append(", ");
                    sb.append("...");
                }
                sb.append(")\n");
                printStatement(fd.body(), sb);
                sb.append("end");
            }
            case Statements.LocalFunctionDefStmt lfd -> {
                sb.append("local function ").append(lfd.name()).append("(");
                for (int i = 0; i < lfd.parameters().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(lfd.parameters().get(i));
                }
                if (lfd.isVararg()) {
                    if (!lfd.parameters().isEmpty()) sb.append(", ");
                    sb.append("...");
                }
                sb.append(")\n");
                printStatement(lfd.body(), sb);
                sb.append("end");
            }
            case Statements.ReturnStmt ret -> {
                sb.append("return");
                if (!ret.values().isEmpty()) {
                    sb.append(" ");
                    for (int i = 0; i < ret.values().size(); i++) {
                        if (i > 0) sb.append(", ");
                        printExpression(ret.values().get(i), sb);
                    }
                }
            }
            case Statements.BreakStmt b -> sb.append("break");
            case Statements.GotoStmt g -> sb.append("goto ").append(g.label());
            case Statements.LabelStmt l -> sb.append("::").append(l.name()).append("::");
            case Statements.ExprStmt es -> printExpression(es.expression(), sb);
            default -> {}
        }
    }

    public static void printExpression(Expression expr, StringBuilder sb) {
        switch (expr) {
            case Expressions.NilLiteral n -> sb.append("nil");
            case Expressions.BooleanLiteral b -> sb.append(b.value());
            case Expressions.IntegerLiteral i -> sb.append(i.value());
            case Expressions.FloatLiteral f -> {
                double val = f.value();
                if (Double.isNaN(val)) sb.append("(0/0)");
                else if (Double.isInfinite(val)) sb.append(val > 0 ? "(1/0)" : "(-1/0)");
                else sb.append(val);
            }
            case Expressions.StringLiteral s -> printQuotedString(s.value(), sb);
            case Expressions.VarargLiteral v -> sb.append("...");
            case Expressions.VariableExpr v -> sb.append(v.name());
            case Expressions.BinaryExpr b -> {
                sb.append("(");
                printExpression(b.left(), sb);
                sb.append(" ").append(opString(b.operator())).append(" ");
                printExpression(b.right(), sb);
                sb.append(")");
            }
            case Expressions.UnaryExpr u -> {
                sb.append("(").append(opString(u.operator()));
                if (u.operator() == TokenType.NOT) sb.append(" ");
                printExpression(u.operand(), sb);
                sb.append(")");
            }
            case Expressions.TableConstructorExpr t -> {
                sb.append("{");
                for (int i = 0; i < t.fields().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Expressions.TableField f = t.fields().get(i);
                    if (f.key() != null) {
                        sb.append("[");
                        printExpression(f.key(), sb);
                        sb.append("] = ");
                    }
                    printExpression(f.value(), sb);
                }
                sb.append("}");
            }
            case Expressions.TableAccessExpr ta -> {
                printExpression(ta.table(), sb);
                sb.append("[");
                printExpression(ta.key(), sb);
                sb.append("]");
            }
            case Expressions.FunctionCallExpr fc -> {
                printExpression(fc.target(), sb);
                if (fc.methodName() != null) {
                    sb.append(":").append(fc.methodName());
                }
                sb.append("(");
                for (int i = 0; i < fc.arguments().size(); i++) {
                    if (i > 0) sb.append(", ");
                    printExpression(fc.arguments().get(i), sb);
                }
                sb.append(")");
            }
            case Expressions.FunctionDefExpr fd -> {
                sb.append("(function (");
                for (int i = 0; i < fd.parameters().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(fd.parameters().get(i));
                }
                if (fd.isVararg()) {
                    if (!fd.parameters().isEmpty()) sb.append(", ");
                    sb.append("...");
                }
                sb.append(")\n");
                printStatement(fd.body(), sb);
                sb.append("end)");
            }
            case Expressions.ParenExpr p -> {
                sb.append("(");
                printExpression(p.expression(), sb);
                sb.append(")");
            }
            default -> {}
        }
    }

    private static void printQuotedString(String s, StringBuilder sb) {
        sb.append("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\0' -> sb.append("\\000");
                default -> {
                    if (c < 32 || c >= 127) {
                        sb.append(String.format("\\%03d", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("\"");
    }

    private static String opString(TokenType op) {
        return switch (op) {
            case PLUS -> "+";
            case MINUS -> "-";
            case STAR -> "*";
            case SLASH -> "/";
            case DOUBLE_SLASH -> "//";
            case PERCENT -> "%";
            case CARET -> "^";
            case HASH -> "#";
            case AMPERSAND -> "&";
            case TILDE -> "~";
            case PIPE -> "|";
            case SHL -> "<<";
            case SHR -> ">>";
            case DOT_DOT -> "..";
            case LESS -> "<";
            case LESS_EQUAL -> "<=";
            case GREATER -> ">";
            case GREATER_EQUAL -> ">=";
            case EQUAL_EQUAL -> "==";
            case TILDE_EQUAL -> "~=";
            case AND -> "and";
            case OR -> "or";
            case NOT -> "not";
            default -> op.name();
        };
    }
}

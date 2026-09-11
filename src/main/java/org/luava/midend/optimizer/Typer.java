/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.midend.optimizer;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.lexer.TokenType;
import org.luava.runtime.LuaType;

import java.util.HashMap;
import java.util.Map;

public final class Typer {
    public enum InferredType {
        NIL,
        BOOLEAN,
        INTEGER,
        FLOAT,
        STRING,
        TABLE,
        FUNCTION,
        ANY;

        public boolean isNumeric() {
            return this == INTEGER || this == FLOAT;
        }
    }

    private final Map<String, InferredType> variableTypes = new HashMap<>();

    public void bindVariable(String name, InferredType type) {
        variableTypes.put(name, type != null ? type : InferredType.ANY);
    }

    public InferredType infer(Expression expr) {
        return switch (expr) {
            case Expressions.NilLiteral n -> InferredType.NIL;
            case Expressions.BooleanLiteral b -> InferredType.BOOLEAN;
            case Expressions.IntegerLiteral i -> InferredType.INTEGER;
            case Expressions.FloatLiteral f -> InferredType.FLOAT;
            case Expressions.StringLiteral s -> InferredType.STRING;
            case Expressions.TableConstructorExpr t -> InferredType.TABLE;
            case Expressions.FunctionDefExpr fn -> InferredType.FUNCTION;
            case Expressions.VariableExpr v -> variableTypes.getOrDefault(v.name(), InferredType.ANY);

            case Expressions.UnaryExpr u -> {
                InferredType opType = infer(u.operand());
                yield switch (u.operator()) {
                    case NOT -> InferredType.BOOLEAN;
                    case HASH -> InferredType.INTEGER;
                    case TILDE -> InferredType.INTEGER;
                    case MINUS -> {
                        if (opType == InferredType.INTEGER) yield InferredType.INTEGER;
                        if (opType == InferredType.FLOAT) yield InferredType.FLOAT;
                        yield InferredType.ANY;
                    }
                    default -> InferredType.ANY;
                };
            }

            case Expressions.BinaryExpr b -> {
                InferredType left = infer(b.left());
                InferredType right = infer(b.right());
                yield switch (b.operator()) {
                    case PLUS, MINUS, STAR -> {
                        if (left == InferredType.INTEGER && right == InferredType.INTEGER) {
                            yield InferredType.INTEGER;
                        }
                        if (left.isNumeric() && right.isNumeric()) {
                            yield InferredType.FLOAT;
                        }
                        yield InferredType.ANY;
                    }
                    case SLASH -> InferredType.FLOAT; // Lua 5.4 float division always produces float
                    case DOUBLE_SLASH -> {
                        if (left == InferredType.INTEGER && right == InferredType.INTEGER) {
                            yield InferredType.INTEGER;
                        }
                        if (left.isNumeric() && right.isNumeric()) {
                            yield InferredType.FLOAT;
                        }
                        yield InferredType.ANY;
                    }
                    case PERCENT -> {
                        if (left == InferredType.INTEGER && right == InferredType.INTEGER) {
                            yield InferredType.INTEGER;
                        }
                        if (left.isNumeric() && right.isNumeric()) {
                            yield InferredType.FLOAT;
                        }
                        yield InferredType.ANY;
                    }
                    case CARET -> InferredType.FLOAT;
                    case AMPERSAND, PIPE, TILDE, SHL, SHR -> InferredType.INTEGER;
                    case DOT_DOT -> InferredType.STRING;
                    case EQUAL_EQUAL, TILDE_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> InferredType.BOOLEAN;
                    case AND, OR -> InferredType.ANY;
                    default -> InferredType.ANY;
                };
            }

            default -> InferredType.ANY;
        };
    }

    public boolean canEliminateGuard(Expressions.BinaryExpr expr) {
        InferredType left = infer(expr.left());
        InferredType right = infer(expr.right());
        return switch (expr.operator()) {
            case PLUS, MINUS, STAR, DOUBLE_SLASH, PERCENT ->
                left == InferredType.INTEGER && right == InferredType.INTEGER;
            case AMPERSAND, PIPE, TILDE, SHL, SHR ->
                left == InferredType.INTEGER && right == InferredType.INTEGER;
            default -> false;
        };
    }
}

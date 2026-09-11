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

public final class ConstantFolder {
    private ConstantFolder() {}

    public static Expression fold(Expression expr) {
        if (expr instanceof Expressions.UnaryExpr u) {
            Expression foldedOperand = fold(u.operand());
            if (foldedOperand instanceof Expressions.IntegerLiteral i) {
                long val = i.value();
                return switch (u.operator()) {
                    case MINUS -> new Expressions.IntegerLiteral(-val, u.line(), u.column());
                    case TILDE -> new Expressions.IntegerLiteral(~val, u.line(), u.column());
                    default -> new Expressions.UnaryExpr(u.operator(), foldedOperand, u.line(), u.column());
                };
            }
            if (foldedOperand instanceof Expressions.FloatLiteral f) {
                if (u.operator() == TokenType.MINUS) {
                    return new Expressions.FloatLiteral(-f.value(), u.line(), u.column());
                }
            }
            if (foldedOperand instanceof Expressions.BooleanLiteral b) {
                if (u.operator() == TokenType.NOT) {
                    return new Expressions.BooleanLiteral(!b.value(), u.line(), u.column());
                }
            }
            return new Expressions.UnaryExpr(u.operator(), foldedOperand, u.line(), u.column());
        }

        if (expr instanceof Expressions.BinaryExpr b) {
            Expression left = fold(b.left());
            Expression right = fold(b.right());

            // Integer arithmetic folding
            if (left instanceof Expressions.IntegerLiteral li && right instanceof Expressions.IntegerLiteral ri) {
                long l = li.value();
                long r = ri.value();
                try {
                    return switch (b.operator()) {
                        case PLUS -> new Expressions.IntegerLiteral(l + r, b.line(), b.column());
                        case MINUS -> new Expressions.IntegerLiteral(l - r, b.line(), b.column());
                        case STAR -> new Expressions.IntegerLiteral(l * r, b.line(), b.column());
                        case DOUBLE_SLASH -> r != 0 ? new Expressions.IntegerLiteral(Math.floorDiv(l, r), b.line(), b.column()) : b;
                        case PERCENT -> r != 0 ? new Expressions.IntegerLiteral(Math.floorMod(l, r), b.line(), b.column()) : b;
                        case AMPERSAND -> new Expressions.IntegerLiteral(l & r, b.line(), b.column());
                        case PIPE -> new Expressions.IntegerLiteral(l | r, b.line(), b.column());
                        case TILDE -> new Expressions.IntegerLiteral(l ^ r, b.line(), b.column());
                        case SHL -> new Expressions.IntegerLiteral(r >= 0 && r < 64 ? l << r : 0, b.line(), b.column());
                        case SHR -> new Expressions.IntegerLiteral(r >= 0 && r < 64 ? l >>> r : 0, b.line(), b.column());
                        case EQUAL_EQUAL -> new Expressions.BooleanLiteral(l == r, b.line(), b.column());
                        case TILDE_EQUAL -> new Expressions.BooleanLiteral(l != r, b.line(), b.column());
                        case LESS -> new Expressions.BooleanLiteral(l < r, b.line(), b.column());
                        case LESS_EQUAL -> new Expressions.BooleanLiteral(l <= r, b.line(), b.column());
                        case GREATER -> new Expressions.BooleanLiteral(l > r, b.line(), b.column());
                        case GREATER_EQUAL -> new Expressions.BooleanLiteral(l >= r, b.line(), b.column());
                        default -> new Expressions.BinaryExpr(left, b.operator(), right, b.line(), b.column());
                    };
                } catch (ArithmeticException ignored) {}
            }

            // String concatenation folding
            if (b.operator() == TokenType.DOT_DOT && left instanceof Expressions.StringLiteral sl && right instanceof Expressions.StringLiteral sr) {
                return new Expressions.StringLiteral(sl.value() + sr.value(), b.line(), b.column());
            }

            return new Expressions.BinaryExpr(left, b.operator(), right, b.line(), b.column());
        }

        return expr;
    }
}

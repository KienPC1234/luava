/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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

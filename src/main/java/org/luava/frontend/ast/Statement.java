/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.ast;

public sealed interface Statement extends AstNode
    permits Statements.BlockStmt,
            Statements.LocalVarDeclStmt,
            Statements.AssignmentStmt,
            Statements.IfStmt,
            Statements.WhileStmt,
            Statements.RepeatStmt,
            Statements.ForNumericStmt,
            Statements.ForGenericStmt,
            Statements.FunctionDefStmt,
            Statements.LocalFunctionDefStmt,
            Statements.ReturnStmt,
            Statements.BreakStmt,
            Statements.GotoStmt,
            Statements.LabelStmt,
            Statements.ExprStmt {
}

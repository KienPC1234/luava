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

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.parser;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Parser {
    private final List<Token> tokens;
    private int current = 0;

    private static final class Scope {
        final Scope parent;
        final Map<String, Boolean> locals = new HashMap<>();

        Scope(Scope parent) {
            this.parent = parent;
        }

        void define(String name, boolean isConst) {
            locals.put(name, isConst);
        }

        Boolean find(String name) {
            if (locals.containsKey(name)) {
                return locals.get(name);
            }
            if (parent != null) {
                return parent.find(name);
            }
            return null;
        }
    }

    private static final class LabelDef {
        final String name;
        final int line;
        final int nactvar;

        LabelDef(String name, int line, int nactvar) {
            this.name = name;
            this.line = line;
            this.nactvar = nactvar;
        }
    }

    private static final class GotoRef {
        final String name;
        final int line;
        final int column;
        int nactvar;

        GotoRef(String name, int line, int column, int nactvar) {
            this.name = name;
            this.line = line;
            this.column = column;
            this.nactvar = nactvar;
        }
    }

    private static final class BlockContext {
        final BlockContext parent;
        final int nactvar;
        final int firstlabel;
        final int firstgoto;
        final boolean isLoop;

        BlockContext(BlockContext parent, int nactvar, int firstlabel, int firstgoto, boolean isLoop) {
            this.parent = parent;
            this.nactvar = nactvar;
            this.firstlabel = firstlabel;
            this.firstgoto = firstgoto;
            this.isLoop = isLoop;
        }
    }

    private static final class FunctionContext {
        final FunctionContext parent;
        final int lineDefined;
        final Scope outerScope;
        final Set<String> upvalues = new LinkedHashSet<>();
        final Set<String> funcLocals = new HashSet<>();
        int totalLocals = 0;
        final List<String> activeVars = new ArrayList<>();
        final List<LabelDef> labels = new ArrayList<>();
        final List<GotoRef> gotos = new ArrayList<>();
        BlockContext currentBlock = null;
        /**
         * Number of enclosing loops in this function. PUC rejects a
         * {@code break} outside any loop as a syntax error; a plain counter
         * is enough because any break inside a loop (even nested in an
         * {@code if}/{@code do} block) targets the innermost one.
         */
        int loopDepth = 0;

        FunctionContext(FunctionContext parent, int lineDefined, Scope outerScope) {
            this.parent = parent;
            this.lineDefined = lineDefined;
            this.outerScope = outerScope;
        }
    }

    private Scope currentScope = new Scope(null);
    private FunctionContext currentFuncCtx = new FunctionContext(null, 1, null);
    private int scopeDepth = 0;
    private int nestingDepth = 0;
    private static final int MAX_NESTING_DEPTH = 250;

    private void enterNesting() {
        if (++nestingDepth > MAX_NESTING_DEPTH) {
            Token cur = peek();
            throw new ParseException("C stack overflow", cur.line(), cur.column());
        }
    }

    private void exitNesting() {
        nestingDepth--;
    }

    private void defineLocal(String name, boolean isConst, Token token) {
        currentScope.define(name, isConst);
        currentFuncCtx.funcLocals.add(name);
        currentFuncCtx.totalLocals++;
        currentFuncCtx.activeVars.add(name);
        if (currentFuncCtx.activeVars.size() > 200) {
            int line = currentFuncCtx.lineDefined > 0 ? currentFuncCtx.lineDefined : (token != null ? token.line() : 1);
            throw new ParseException("too many local variables (limit is 200) in function at line " + line + " near " + formatNear(token), token != null ? token.line() : 1, token != null ? token.column() : 0);
        }
    }

    private void checkNotConst(Expressions.VariableExpr varExpr) {
        Boolean isConst = currentScope.find(varExpr.name());
        if (Boolean.TRUE.equals(isConst)) {
            throw new ParseException("attempt to assign to const variable '" + varExpr.name() + "'",
                                     varExpr.line(), varExpr.column());
        }
        if (isConst == null) {
            FunctionContext ctx = currentFuncCtx;
            while (ctx != null && ctx.outerScope != null) {
                Boolean outerConst = ctx.outerScope.find(varExpr.name());
                if (outerConst != null) {
                    if (Boolean.TRUE.equals(outerConst)) {
                        throw new ParseException("attempt to assign to const variable '" + varExpr.name() + "'",
                                                 varExpr.line(), varExpr.column());
                    }
                    break;
                }
                ctx = ctx.parent;
            }
        }
    }

    private void checkUpvalue(String varName, Token token) {
        if (currentFuncCtx.parent == null) {
            return;
        }
        if (currentScope.find(varName) != null) {
            return;
        }
        FunctionContext p = currentFuncCtx.parent;
        boolean isLocalInAncestor = false;
        while (p != null) {
            if (p.funcLocals.contains(varName)) {
                isLocalInAncestor = true;
                break;
            }
            p = p.parent;
        }
        if (isLocalInAncestor) {
            currentFuncCtx.upvalues.add(varName);
            if (currentFuncCtx.upvalues.size() > 255) {
                int line = currentFuncCtx.lineDefined > 0 ? currentFuncCtx.lineDefined : (token != null ? token.line() : 1);
                throw new ParseException("too many upvalues (limit is 255) in function at line " + line + " near " + formatNear(token), token != null ? token.line() : 1, token != null ? token.column() : 0);
            }
        }
    }

    private boolean isLastInBlock() {
        int idx = current;
        while (idx < tokens.size()) {
            TokenType t = tokens.get(idx).type();
            if (t == TokenType.SEMICOLON) {
                idx++;
            } else if (t == TokenType.DOUBLE_COLON) {
                idx++;
                if (idx < tokens.size() && tokens.get(idx).type() == TokenType.IDENTIFIER) {
                    idx++;
                    if (idx < tokens.size() && tokens.get(idx).type() == TokenType.DOUBLE_COLON) {
                        idx++;
                        continue;
                    }
                }
                break;
            } else {
                break;
            }
        }
        if (idx >= tokens.size()) return true;
        TokenType t = tokens.get(idx).type();
        return t == TokenType.END || t == TokenType.ELSE || t == TokenType.ELSEIF || t == TokenType.EOF;
    }

    private void enterScope() {
        enterScope(false);
    }

    private void enterScope(boolean isLoop) {
        currentScope = new Scope(currentScope);
        scopeDepth++;
        currentFuncCtx.currentBlock = new BlockContext(
            currentFuncCtx.currentBlock,
            currentFuncCtx.activeVars.size(),
            currentFuncCtx.labels.size(),
            currentFuncCtx.gotos.size(),
            isLoop
        );
    }

    private void exitScope() {
        scopeDepth--;
        if (currentScope.parent != null) {
            currentScope = currentScope.parent;
        }
        BlockContext bl = currentFuncCtx.currentBlock;
        if (bl != null) {
            while (currentFuncCtx.activeVars.size() > bl.nactvar) {
                currentFuncCtx.activeVars.remove(currentFuncCtx.activeVars.size() - 1);
            }
            while (currentFuncCtx.labels.size() > bl.firstlabel) {
                currentFuncCtx.labels.remove(currentFuncCtx.labels.size() - 1);
            }
            if (bl.parent != null) {
                for (int i = bl.firstgoto; i < currentFuncCtx.gotos.size(); i++) {
                    GotoRef g = currentFuncCtx.gotos.get(i);
                    g.nactvar = bl.nactvar;
                }
            } else {
                if (bl.firstgoto < currentFuncCtx.gotos.size()) {
                    GotoRef g = currentFuncCtx.gotos.get(bl.firstgoto);
                    Token cur = peek();
                    throw new ParseException("no visible label '" + g.name + "' for <goto> at line " + g.line, cur.line(), cur.column());
                }
            }
            currentFuncCtx.currentBlock = bl.parent;
        }
    }

    private final Map<String, org.luava.runtime.LuaString> stringConstants = new HashMap<>();

    private Expressions.StringLiteral makeStringLiteral(String value, int line, int column) {
        org.luava.runtime.LuaString ls = stringConstants.computeIfAbsent(value, org.luava.runtime.LuaString::interned);
        return new Expressions.StringLiteral(value, line, column, ls);
    }

    public Parser(List<Token> tokens) {
        this.tokens = tokens != null ? tokens : List.of();
    }

    public Statements.BlockStmt parse() {
        enterScope();
        Statements.BlockStmt block = parseBlockInternal();
        if (!isAtEnd()) {
            Token t = peek();
            throw new ParseException("<eof> expected near " + formatNear(t), t.line(), t.column());
        }
        exitScope();
        return block;
    }

    private Statements.BlockStmt parseBlockInternal() {
        List<Statement> stmts = new ArrayList<>();
        int startLine = peek().line();
        int startCol = peek().column();

        while (!isAtEnd() && !isBlockEnd()) {
            Statement stmt = parseStatement();
            if (stmt != null) {
                stmts.add(stmt);
                if (stmt instanceof Statements.ReturnStmt) {
                    match(TokenType.SEMICOLON);
                    break;
                }
            }
            while (match(TokenType.SEMICOLON)) {
                // optional semicolons between statements
            }
        }
        Token lastTok = previous();
        int endLine = lastTok != null ? lastTok.line() : startLine;
        return new Statements.BlockStmt(stmts, startLine, startCol, endLine);
    }

    private boolean isBlockEnd() {
        TokenType t = peek().type();
        return t == TokenType.END || t == TokenType.ELSE || t == TokenType.ELSEIF || t == TokenType.UNTIL || t == TokenType.EOF;
    }

    private Statement parseStatement() {
        enterNesting();
        try {
            Token token = peek();
            return switch (token.type()) {
                case LOCAL -> parseLocalStatement();
                case IF -> parseIfStatement();
                case WHILE -> parseWhileStatement();
                case REPEAT -> parseRepeatStatement();
                case FOR -> parseForStatement();
                case FUNCTION -> parseFunctionStatement();
                case DO -> parseDoStatement();
                case BREAK -> {
                    advance();
                    if (currentFuncCtx.loopDepth == 0) {
                        throw new ParseException("break outside loop at line " + token.line(),
                                token.line(), token.column());
                    }
                    yield new Statements.BreakStmt(token.line(), token.column());
                }
                case GOTO -> {
                    Token gotoToken = advance();
                    Token labelToken = consume(TokenType.IDENTIFIER, "<name> expected");
                    String labelName = labelToken.lexeme();
                    LabelDef found = null;
                    for (LabelDef l : currentFuncCtx.labels) {
                        if (l.name.equals(labelName)) {
                            found = l;
                            break;
                        }
                    }
                    if (found == null) {
                        currentFuncCtx.gotos.add(new GotoRef(labelName, gotoToken.line(), gotoToken.column(), currentFuncCtx.activeVars.size()));
                    }
                    yield new Statements.GotoStmt(labelName, gotoToken.line(), gotoToken.column());
                }
                case DOUBLE_COLON -> {
                    Token dcol = advance();
                    Token labelToken = consume(TokenType.IDENTIFIER, "<name> expected");
                    consume(TokenType.DOUBLE_COLON, "'::' expected");
                    String labelName = labelToken.lexeme();
                    for (LabelDef existing : currentFuncCtx.labels) {
                        if (existing.name.equals(labelName)) {
                            throw new ParseException("label '" + labelName + "' already defined on line " + existing.line, labelToken.line(), labelToken.column());
                        }
                    }
                    boolean isLast = isLastInBlock();
                    int labelNactvar = isLast ? (currentFuncCtx.currentBlock != null ? currentFuncCtx.currentBlock.nactvar : 0) : currentFuncCtx.activeVars.size();
                    LabelDef newLabel = new LabelDef(labelName, labelToken.line(), labelNactvar);
                    currentFuncCtx.labels.add(newLabel);
                    int firstgoto = currentFuncCtx.currentBlock != null ? currentFuncCtx.currentBlock.firstgoto : 0;
                    for (int i = firstgoto; i < currentFuncCtx.gotos.size(); ) {
                        GotoRef g = currentFuncCtx.gotos.get(i);
                        if (g.name.equals(labelName)) {
                            if (g.nactvar < newLabel.nactvar) {
                                String varName = currentFuncCtx.activeVars.get(g.nactvar);
                                throw new ParseException("<goto " + g.name + "> at line " + g.line + " jumps into the scope of local '" + varName + "'", g.line, g.column);
                            }
                            currentFuncCtx.gotos.remove(i);
                        } else {
                            i++;
                        }
                    }
                    yield new Statements.LabelStmt(labelName, dcol.line(), dcol.column());
                }
                case RETURN -> parseReturnStatement();
                case SEMICOLON -> {
                    advance();
                    yield null;
                }
                default -> parseAssignmentOrExprStatement();
            };
        } finally {
            exitNesting();
        }
    }

    private Statement parseLocalStatement() {
        Token localToken = advance(); // consume 'local'
        if (match(TokenType.FUNCTION)) {
            Token funcToken = previous();
            Token name = consume(TokenType.IDENTIFIER, "<name> expected");
            defineLocal(name.lexeme(), false, name);
            Token lparen = consume(TokenType.LPAREN, "'(' expected");
            List<String> params = new ArrayList<>();
            boolean isVararg = parseParameterList(params);
            checkMatch(TokenType.RPAREN, TokenType.LPAREN, lparen);
            int savedScopeDepth = scopeDepth;
            Scope savedScope = currentScope;
            scopeDepth = 0;
            currentScope = null;
            FunctionContext prevFuncCtx = currentFuncCtx;
            currentFuncCtx = new FunctionContext(prevFuncCtx, localToken.line(), savedScope);
            enterScope();
            for (String p : params) {
                defineLocal(p, false, localToken);
            }
            Statements.BlockStmt body;
            try {
                body = parseBlockInternal();
                exitScope();
            } finally {
                currentFuncCtx = prevFuncCtx;
                scopeDepth = savedScopeDepth;
                currentScope = savedScope;
            }
            Token endToken = checkMatch(TokenType.END, TokenType.FUNCTION, funcToken);
            return new Statements.LocalFunctionDefStmt(name.lexeme(), params, isVararg, body, funcToken.line(), funcToken.column(), endToken.line());
        }

        List<Statements.LocalVarBinding> bindings = new ArrayList<>();
        do {
            Token name = consume(TokenType.IDENTIFIER, "<name> expected");
            Statements.VariableAttribute attr = Statements.VariableAttribute.NONE;
            if (match(TokenType.ATTR_CLOSE)) {
                attr = Statements.VariableAttribute.CLOSE;
            } else if (match(TokenType.ATTR_CONST)) {
                attr = Statements.VariableAttribute.CONST;
            } else if (match(TokenType.LESS)) {
                Token attrToken = consume(TokenType.IDENTIFIER, "<name> expected");
                consume(TokenType.GREATER, "'>' expected");
                String attrName = attrToken.lexeme();
                if ("const".equals(attrName)) {
                    attr = Statements.VariableAttribute.CONST;
                } else if ("close".equals(attrName)) {
                    attr = Statements.VariableAttribute.CLOSE;
                } else {
                    throw new ParseException("unknown attribute '" + attrName + "'", attrToken.line(), attrToken.column());
                }
            }
            bindings.add(new Statements.LocalVarBinding(name.lexeme(), attr));
        } while (match(TokenType.COMMA));

        List<Expression> initializers = new ArrayList<>();
        if (match(TokenType.ASSIGN)) {
            do {
                initializers.add(parseExpression());
            } while (match(TokenType.COMMA));
        }

        for (Statements.LocalVarBinding binding : bindings) {
            boolean isConst = (binding.attribute() == Statements.VariableAttribute.CONST ||
                               binding.attribute() == Statements.VariableAttribute.CLOSE);
            defineLocal(binding.name(), isConst, localToken);
        }

        return new Statements.LocalVarDeclStmt(bindings, initializers, localToken.line(), localToken.column());
    }

    private Statement parseIfStatement() {
        Token ifToken = advance(); // consume 'if'
        List<Statements.IfBranch> branches = new ArrayList<>();

        Expression cond = parseExpression();
        Token thenToken = consume(TokenType.THEN, "'then' expected");
        enterScope();
        Statements.BlockStmt block = parseBlockInternal();
        exitScope();
        branches.add(new Statements.IfBranch(cond, block, thenToken.line()));

        while (match(TokenType.ELSEIF)) {
            Expression elseifCond = parseExpression();
            Token elseifThenToken = consume(TokenType.THEN, "'then' expected");
            enterScope();
            Statements.BlockStmt elseifBlock = parseBlockInternal();
            exitScope();
            branches.add(new Statements.IfBranch(elseifCond, elseifBlock, elseifThenToken.line()));
        }

        Statements.BlockStmt elseBlock = null;
        if (match(TokenType.ELSE)) {
            enterScope();
            elseBlock = parseBlockInternal();
            exitScope();
        }

        checkMatch(TokenType.END, TokenType.IF, ifToken);
        return new Statements.IfStmt(branches, elseBlock, ifToken.line(), ifToken.column());
    }

    private Statement parseWhileStatement() {
        Token whileToken = advance(); // consume 'while'
        Expression cond = parseExpression();
        consume(TokenType.DO, "'do' expected");
        enterScope();
        currentFuncCtx.loopDepth++;
        Statements.BlockStmt body;
        try {
            body = parseBlockInternal();
        } finally {
            currentFuncCtx.loopDepth--;
        }
        exitScope();
        Token endToken = checkMatch(TokenType.END, TokenType.WHILE, whileToken);
        return new Statements.WhileStmt(cond, body, whileToken.line(), whileToken.column(), endToken.line());
    }

    private Statement parseRepeatStatement() {
        Token repeatToken = advance(); // consume 'repeat'
        enterScope();
        currentFuncCtx.loopDepth++;
        Statements.BlockStmt body;
        try {
            body = parseBlockInternal();
        } finally {
            currentFuncCtx.loopDepth--;
        }
        checkMatch(TokenType.UNTIL, TokenType.REPEAT, repeatToken);
        Expression cond = parseExpression();
        exitScope();
        return new Statements.RepeatStmt(body, cond, repeatToken.line(), repeatToken.column());
    }

    private Statement parseForStatement() {
        Token forToken = advance(); // consume 'for'
        Token varName = consume(TokenType.IDENTIFIER, "<name> expected");

        if (match(TokenType.ASSIGN)) {
            // Numeric for: for var = init, limit [, step] do
            Expression start = parseExpression();
            consume(TokenType.COMMA, "',' expected");
            Expression limit = parseExpression();
            Expression step = null;
            if (match(TokenType.COMMA)) {
                step = parseExpression();
            }
            consume(TokenType.DO, "'do' expected");
            enterScope();
            defineLocal(varName.lexeme(), false, varName);
            currentFuncCtx.loopDepth++;
            Statements.BlockStmt body;
            try {
                body = parseBlockInternal();
            } finally {
                currentFuncCtx.loopDepth--;
            }
            exitScope();
            Token endToken = checkMatch(TokenType.END, TokenType.FOR, forToken);
            return new Statements.ForNumericStmt(varName.lexeme(), start, limit, step, body, forToken.line(), forToken.column(), endToken.line());
        } else {
            // Generic for: for var1, var2 in exp1, exp2 do
            List<String> vars = new ArrayList<>();
            vars.add(varName.lexeme());
            while (match(TokenType.COMMA)) {
                vars.add(consume(TokenType.IDENTIFIER, "<name> expected").lexeme());
            }
            if (vars.size() == 1 && !check(TokenType.IN) && !check(TokenType.ASSIGN)) {
                throw new ParseException("'=' or 'in' expected near " + formatNear(peek()), peek().line(), peek().column());
            }
            consume(TokenType.IN, "'in' expected");
            List<Expression> iterators = new ArrayList<>();
            do {
                iterators.add(parseExpression());
            } while (match(TokenType.COMMA));

            consume(TokenType.DO, "'do' expected");
            enterScope();
            for (String v : vars) {
                defineLocal(v, false, forToken);
            }
            currentFuncCtx.loopDepth++;
            Statements.BlockStmt body;
            try {
                body = parseBlockInternal();
            } finally {
                currentFuncCtx.loopDepth--;
            }
            exitScope();
            Token endToken = checkMatch(TokenType.END, TokenType.FOR, forToken);
            return new Statements.ForGenericStmt(vars, iterators, body, forToken.line(), forToken.column(), endToken.line());
        }
    }

    private Statement parseFunctionStatement() {
        Token funcToken = advance(); // consume 'function'
        Expression target = new Expressions.VariableExpr(
            consume(TokenType.IDENTIFIER, "<name> expected").lexeme(),
            funcToken.line(), funcToken.column()
        );

        while (match(TokenType.DOT)) {
            Token member = consume(TokenType.IDENTIFIER, "<name> expected");
            target = new Expressions.TableAccessExpr(
                target,
                makeStringLiteral(member.lexeme(), member.line(), member.column()),
                member.line(), member.column()
            );
        }

        boolean isMethod = false;
        if (match(TokenType.COLON)) {
            isMethod = true;
            Token method = consume(TokenType.IDENTIFIER, "<name> expected");
            target = new Expressions.TableAccessExpr(
                target,
                makeStringLiteral(method.lexeme(), method.line(), method.column()),
                method.line(), method.column()
            );
        } else if (target instanceof Expressions.VariableExpr varExpr) {
            checkNotConst(varExpr);
        }

        Token lparen = consume(TokenType.LPAREN, "'(' expected");
        List<String> params = new ArrayList<>();
        if (isMethod) {
            params.add("self");
        }
        boolean isVararg = parseParameterList(params);
        checkMatch(TokenType.RPAREN, TokenType.LPAREN, lparen);
        int savedScopeDepth = scopeDepth;
        Scope savedScope = currentScope;
        scopeDepth = 0;
        currentScope = null;
        FunctionContext prevFuncCtx = currentFuncCtx;
        currentFuncCtx = new FunctionContext(prevFuncCtx, funcToken.line(), savedScope);
        enterScope();
        for (String p : params) {
            defineLocal(p, false, lparen);
        }
        Statements.BlockStmt body;
        try {
            body = parseBlockInternal();
            exitScope();
        } finally {
            currentFuncCtx = prevFuncCtx;
            scopeDepth = savedScopeDepth;
            currentScope = savedScope;
        }
        Token endToken = checkMatch(TokenType.END, TokenType.FUNCTION, funcToken);

        return new Statements.FunctionDefStmt(target, isMethod, params, isVararg, body, funcToken.line(), funcToken.column(), endToken.line());
    }

    private Statement parseDoStatement() {
        Token doToken = advance();
        enterScope();
        Statements.BlockStmt body = parseBlockInternal();
        exitScope();
        checkMatch(TokenType.END, TokenType.DO, doToken);
        return body;
    }

    private Statement parseReturnStatement() {
        Token returnToken = advance();
        List<Expression> values = new ArrayList<>();
        if (!isBlockEnd() && peek().type() != TokenType.SEMICOLON) {
            do {
                values.add(parseExpression());
            } while (match(TokenType.COMMA));
        }
        return new Statements.ReturnStmt(values, returnToken.line(), returnToken.column());
    }

    private Statement parseAssignmentOrExprStatement() {
        Token startToken = peek();
        Expression expr = parsePrefixExpression();

        if (expr instanceof Expressions.FunctionCallExpr) {
            return new Statements.ExprStmt(expr, startToken.line(), startToken.column());
        }

        if (!(expr instanceof Expressions.VariableExpr) && !(expr instanceof Expressions.TableAccessExpr)) {
            throw new ParseException("syntax error near " + formatNear(startToken), startToken.line(), startToken.column());
        }

        List<Expression> targets = new ArrayList<>();
        targets.add(expr);
        while (match(TokenType.COMMA)) {
            if (targets.size() >= 200) {
                Token cur = peek();
                throw new ParseException("C stack overflow", cur.line(), cur.column());
            }
            Expression nextExpr = parsePrefixExpression();
            if (!(nextExpr instanceof Expressions.VariableExpr) && !(nextExpr instanceof Expressions.TableAccessExpr)) {
                throw new ParseException("syntax error near " + formatNear(peek()), peek().line(), peek().column());
            }
            targets.add(nextExpr);
        }

        if (!check(TokenType.ASSIGN)) {
            throw new ParseException("syntax error near " + formatNear(peek()), peek().line(), peek().column());
        }
        consume(TokenType.ASSIGN, "'=' expected");

        for (Expression target : targets) {
            if (target instanceof Expressions.VariableExpr varExpr) {
                checkNotConst(varExpr);
            }
        }

        List<Expression> values = new ArrayList<>();
        do {
            if (values.size() >= 200) {
                Token cur = peek();
                throw new ParseException("C stack overflow", cur.line(), cur.column());
            }
            values.add(parseExpression());
        } while (match(TokenType.COMMA));

        return new Statements.AssignmentStmt(targets, values, startToken.line(), startToken.column());
    }

    public Expression parseExpression() {
        enterNesting();
        try {
            return parseLogicalOr();
        } finally {
            exitNesting();
        }
    }

    private Expression parseLogicalOr() {
        Expression expr = parseLogicalAnd();
        while (match(TokenType.OR)) {
            Token op = previous();
            Expression right = parseLogicalAnd();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseLogicalAnd() {
        Expression expr = parseRelational();
        while (match(TokenType.AND)) {
            Token op = previous();
            Expression right = parseRelational();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseRelational() {
        Expression expr = parseBitwiseOr();
        while (match(TokenType.EQUAL_EQUAL, TokenType.TILDE_EQUAL,
                     TokenType.LESS, TokenType.LESS_EQUAL,
                     TokenType.GREATER, TokenType.GREATER_EQUAL)) {
            Token op = previous();
            Expression right = parseBitwiseOr();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseBitwiseOr() {
        Expression expr = parseBitwiseXor();
        while (match(TokenType.PIPE)) {
            Token op = previous();
            Expression right = parseBitwiseXor();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseBitwiseXor() {
        Expression expr = parseBitwiseAnd();
        while (match(TokenType.TILDE)) {
            Token op = previous();
            Expression right = parseBitwiseAnd();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseBitwiseAnd() {
        Expression expr = parseBitwiseShift();
        while (match(TokenType.AMPERSAND)) {
            Token op = previous();
            Expression right = parseBitwiseShift();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseBitwiseShift() {
        Expression expr = parseConcat();
        while (match(TokenType.SHL, TokenType.SHR)) {
            Token op = previous();
            Expression right = parseConcat();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseConcat() {
        Expression expr = parseAdditive();
        if (match(TokenType.DOT_DOT)) {
            enterNesting();
            try {
                Token op = previous();
                // right-associative
                Expression right = parseConcat();
                expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
            } finally {
                exitNesting();
            }
        }
        return expr;
    }

    private Expression parseAdditive() {
        Expression expr = parseMultiplicative();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            Token op = previous();
            Expression right = parseMultiplicative();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseMultiplicative() {
        Expression expr = parseUnary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.DOUBLE_SLASH, TokenType.PERCENT)) {
            Token op = previous();
            Expression right = parseUnary();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parseUnary() {
        if (match(TokenType.NOT, TokenType.HASH, TokenType.MINUS, TokenType.TILDE)) {
            enterNesting();
            try {
                Token op = previous();
                Expression operand = parseUnary();
                return new Expressions.UnaryExpr(op.type(), operand, op.line(), op.column());
            } finally {
                exitNesting();
            }
        }
        return parseExponent();
    }

    private Expression parseExponent() {
        Expression expr = parsePrefixExpression();
        if (match(TokenType.CARET)) {
            enterNesting();
            try {
                Token op = previous();
                // right-associative: in Lua, right-hand operand of ^ has unary precedence
                Expression right = parseUnary();
                expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
            } finally {
                exitNesting();
            }
        }
        return expr;
    }

    private Expression parsePrefixExpression() {
        Token token = peek();
        Expression expr;

        if (match(TokenType.LPAREN)) {
            Token lparen = previous();
            Expression inner = parseExpression();
            checkMatch(TokenType.RPAREN, TokenType.LPAREN, lparen);
            expr = new Expressions.ParenExpr(inner, lparen.line(), lparen.column());
        } else if (match(TokenType.IDENTIFIER)) {
            Token idToken = previous();
            checkUpvalue(idToken.lexeme(), idToken);
            expr = new Expressions.VariableExpr(idToken.lexeme(), token.line(), token.column());
        } else {
            return parsePrimaryLiteral();
        }

        // Parse calls and field access
        while (true) {
            if (match(TokenType.LBRACKET)) {
                Token lbracket = previous();
                Expression index = parseExpression();
                checkMatch(TokenType.RBRACKET, TokenType.LBRACKET, lbracket);
                expr = new Expressions.TableAccessExpr(expr, index, token.line(), token.column());
            } else if (match(TokenType.DOT)) {
                Token field = consume(TokenType.IDENTIFIER, "<name> expected");
                expr = new Expressions.TableAccessExpr(
                    expr,
                    makeStringLiteral(field.lexeme(), field.line(), field.column()),
                    field.line(), field.column()
                );
            } else if (match(TokenType.COLON)) {
                Token method = consume(TokenType.IDENTIFIER, "<name> expected");
                Token callToken = peek();
                List<Expression> args = parseCallArguments();
                expr = new Expressions.FunctionCallExpr(expr, method.lexeme(), args, callToken.line(), callToken.column());
            } else if (peek().type() == TokenType.LPAREN || peek().type() == TokenType.LBRACE || peek().type() == TokenType.STRING_LITERAL) {
                Token callToken = peek();
                List<Expression> args = parseCallArguments();
                expr = new Expressions.FunctionCallExpr(expr, null, args, callToken.line(), callToken.column());
            } else {
                break;
            }
        }
        return expr;
    }

    private List<Expression> parseCallArguments() {
        enterNesting();
        try {
            if (match(TokenType.LPAREN)) {
                Token lparen = previous();
                List<Expression> args = new ArrayList<>();
                if (!check(TokenType.RPAREN)) {
                    do {
                        if (args.size() >= 255) {
                            Token cur = peek();
                            throw new ParseException("function or expression needs too many registers near " + formatNear(cur), cur.line(), cur.column());
                        }
                        args.add(parseExpression());
                    } while (match(TokenType.COMMA));
                }
                checkMatch(TokenType.RPAREN, TokenType.LPAREN, lparen);
                return args;
            } else if (peek().type() == TokenType.LBRACE) {
                return List.of(parseTableConstructor());
            } else if (peek().type() == TokenType.STRING_LITERAL) {
                Token str = advance();
                return List.of(makeStringLiteral((String) str.literal(), str.line(), str.column()));
            }
            throw new ParseException("function arguments expected near " + formatNear(peek()), peek().line(), peek().column());
        } finally {
            exitNesting();
        }
    }

    private Expression parsePrimaryLiteral() {
        Token token = peek();
        return switch (token.type()) {
            case NIL -> {
                advance();
                yield new Expressions.NilLiteral(token.line(), token.column());
            }
            case TRUE -> {
                advance();
                yield new Expressions.BooleanLiteral(true, token.line(), token.column());
            }
            case FALSE -> {
                advance();
                yield new Expressions.BooleanLiteral(false, token.line(), token.column());
            }
            case INTEGER_LITERAL -> {
                advance();
                yield new Expressions.IntegerLiteral((Long) token.literal(), token.line(), token.column());
            }
            case FLOAT_LITERAL -> {
                advance();
                yield new Expressions.FloatLiteral((Double) token.literal(), token.line(), token.column());
            }
            case STRING_LITERAL -> {
                advance();
                yield makeStringLiteral((String) token.literal(), token.line(), token.column());
            }
            case DOT_DOT_DOT -> {
                advance();
                yield new Expressions.VarargLiteral(token.line(), token.column());
            }
            case LBRACE -> parseTableConstructor();
            case FUNCTION -> {
                Token funcToken = advance();
                Token lparen = consume(TokenType.LPAREN, "'(' expected");
                List<String> params = new ArrayList<>();
                boolean isVararg = parseParameterList(params);
                checkMatch(TokenType.RPAREN, TokenType.LPAREN, lparen);
                int savedScopeDepth = scopeDepth;
                Scope savedScope = currentScope;
                scopeDepth = 0;
                currentScope = null;
                FunctionContext prevFuncCtx = currentFuncCtx;
                currentFuncCtx = new FunctionContext(prevFuncCtx, funcToken.line(), savedScope);
                enterScope();
                for (String p : params) {
                    defineLocal(p, false, lparen);
                }
                Statements.BlockStmt body;
                try {
                    body = parseBlockInternal();
                    exitScope();
                } finally {
                    currentFuncCtx = prevFuncCtx;
                    scopeDepth = savedScopeDepth;
                    currentScope = savedScope;
                }
                Token endToken = checkMatch(TokenType.END, TokenType.FUNCTION, funcToken);
                yield new Expressions.FunctionDefExpr(params, isVararg, body, token.line(), token.column(), endToken.line());
            }
            default -> throw new ParseException("unexpected symbol near " + formatNear(token), token.line(), token.column());
        };
    }

    private Expressions.TableConstructorExpr parseTableConstructor() {
        enterNesting();
        try {
            Token braceToken = consume(TokenType.LBRACE, "'{' expected");
            List<Expressions.TableField> fields = new ArrayList<>();

            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                if (match(TokenType.LBRACKET)) {
                    Token openBracket = previous();
                    Expression key = parseExpression();
                    checkMatch(TokenType.RBRACKET, TokenType.LBRACKET, openBracket);
                    consume(TokenType.ASSIGN, "'=' expected");
                    Expression value = parseExpression();
                    fields.add(new Expressions.TableField(key, value));
                } else if (peek().type() == TokenType.IDENTIFIER && peekNext().type() == TokenType.ASSIGN) {
                    Token keyToken = advance();
                    advance(); // consume '='
                    Expression value = parseExpression();
                    fields.add(new Expressions.TableField(
                        makeStringLiteral(keyToken.lexeme(), keyToken.line(), keyToken.column()),
                        value
                    ));
                } else {
                    Expression val = parseExpression();
                    fields.add(new Expressions.TableField(null, val));
                }

                if (!match(TokenType.COMMA) && !match(TokenType.SEMICOLON)) {
                    break;
                }
            }
            checkMatch(TokenType.RBRACE, TokenType.LBRACE, braceToken);
            return new Expressions.TableConstructorExpr(fields, braceToken.line(), braceToken.column());
        } finally {
            exitNesting();
        }
    }

    private boolean parseParameterList(List<String> params) {
        if (check(TokenType.RPAREN)) {
            return false;
        }
        while (true) {
            if (match(TokenType.DOT_DOT_DOT)) {
                return true;
            }
            if (check(TokenType.IDENTIFIER)) {
                Token param = advance();
                params.add(param.lexeme());
                if (match(TokenType.COMMA)) {
                    if (check(TokenType.RPAREN)) {
                        throw new ParseException("<name> or '...' expected near " + formatNear(peek()), peek().line(), peek().column());
                    }
                    continue;
                }
                break;
            } else {
                throw new ParseException("<name> or '...' expected near " + formatNear(peek()), peek().line(), peek().column());
            }
        }
        return false;
    }

    private boolean match(TokenType... types) {
        for (TokenType type : types) {
            if (check(type)) {
                advance();
                return true;
            }
        }
        return false;
    }

    private boolean check(TokenType type) {
        if (isAtEnd()) {
            return false;
        }
        return peek().type() == type;
    }

    private String tokenRepresentation(TokenType type) {
        return switch (type) {
            case END -> "'end'";
            case FUNCTION -> "'function'";
            case DO -> "'do'";
            case IF -> "'if'";
            case THEN -> "'then'";
            case WHILE -> "'while'";
            case REPEAT -> "'repeat'";
            case UNTIL -> "'until'";
            case FOR -> "'for'";
            case IN -> "'in'";
            case LOCAL -> "'local'";
            case RETURN -> "'return'";
            case BREAK -> "'break'";
            case GOTO -> "'goto'";
            case LBRACE -> "'{'";
            case RBRACE -> "'}'";
            case LPAREN -> "'('";
            case RPAREN -> "')'";
            case LBRACKET -> "'['";
            case RBRACKET -> "']'";
            case ASSIGN -> "'='";
            case COMMA -> "','";
            case SEMICOLON -> "';'";
            case COLON -> "':'";
            case DOUBLE_COLON -> "'::'";
            case DOT -> "'.'";
            case DOT_DOT -> "'..'";
            case DOT_DOT_DOT -> "'...'";
            case EOF -> "<eof>";
            default -> "'" + type.name().toLowerCase() + "'";
        };
    }

    private String formatNear(Token token) {
        if (token == null || token.type() == TokenType.EOF) {
            return "<eof>";
        }
        if (token.type() == TokenType.INVALID) {
            String lex = token.lexeme();
            if (lex != null && !lex.isEmpty()) {
                int c = lex.charAt(0) & 0xFF;
                if (c < 32 || c >= 127) {
                    return "'<\\" + c + ">'";
                }
            }
        }
        String lex = token.lexeme();
        if (lex == null || lex.isEmpty()) {
            return "<eof>";
        }
        if (lex.length() == 1) {
            int c = lex.charAt(0) & 0xFF;
            if (c < 32 || c >= 127) {
                return "'<\\" + c + ">'";
            }
        }
        if (token.type() == TokenType.STRING_LITERAL) {
            if (lex.startsWith("[[")) {
                return "'" + lex + "'";
            } else {
                return "''" + token.literal() + "''";
            }
        }
        return "'" + lex + "'";
    }

    private Token checkMatch(TokenType what, TokenType who, Token whoToken) {
        if (check(what)) {
            return advance();
        }
        String whatStr = tokenRepresentation(what);
        String whoStr = tokenRepresentation(who);
        Token cur = peek();
        String msg;
        if (whoToken != null && whoToken.line() != cur.line()) {
            msg = whatStr + " expected (to close " + whoStr + " at line " + whoToken.line() + ") near " + formatNear(cur);
        } else {
            msg = whatStr + " expected near " + formatNear(cur);
        }
        throw new ParseException(msg, cur.line(), cur.column());
    }

    private Token consume(TokenType type, String expectedWhat) {
        if (check(type)) {
            return advance();
        }
        Token cur = peek();
        String prefix = expectedWhat.endsWith("expected") ? expectedWhat : (expectedWhat + " expected");
        throw new ParseException(prefix + " near " + formatNear(cur), cur.line(), cur.column());
    }

    private Token advance() {
        if (!isAtEnd()) {
            current++;
        }
        return previous();
    }

    private Token peek() {
        return tokens.get(current);
    }

    private Token peekNext() {
        if (current + 1 >= tokens.size()) {
            return tokens.get(tokens.size() - 1);
        }
        return tokens.get(current + 1);
    }

    private Token previous() {
        if (current <= 0) {
            return tokens.isEmpty() ? null : tokens.get(0);
        }
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return current >= tokens.size() || peek().type() == TokenType.EOF;
    }
}

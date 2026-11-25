/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenStream;
import org.luava.frontend.lexer.TokenType;

public final class Parser {
    private final List<Token> tokens;
    private final TokenStream stream;
    private final int tokenCount;
    private int current = 0;
    private TokenType curType;

    private static final class Scope {
        final Scope parent;
        Map<String, Boolean> locals;

        Scope(Scope parent) {
            this.parent = parent;
        }

        void define(String name, boolean isConst) {
            if (locals == null) {
                locals = new HashMap<>(4);
            }
            locals.put(name, isConst);
        }

        Boolean find(String name) {
            if (locals != null) {
                Boolean val = locals.get(name);
                if (val != null) {
                    return val;
                }
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
        Set<String> upvalues;
        final Set<String> funcLocals = new HashSet<>(8);
        int totalLocals = 0;
        final List<String> activeVars = new ArrayList<>(8);
        List<LabelDef> labels;
        List<GotoRef> gotos;
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

        int labelCount() {
            return labels == null ? 0 : labels.size();
        }

        int gotoCount() {
            return gotos == null ? 0 : gotos.size();
        }

        List<LabelDef> ensureLabels() {
            if (labels == null) {
                labels = new ArrayList<>(4);
            }
            return labels;
        }

        List<GotoRef> ensureGotos() {
            if (gotos == null) {
                gotos = new ArrayList<>(4);
            }
            return gotos;
        }

        int addUpvalue(String name) {
            if (upvalues == null) {
                upvalues = new LinkedHashSet<>(4);
            }
            upvalues.add(name);
            return upvalues.size();
        }
    }

    private Scope currentScope = new Scope(null);
    private FunctionContext currentFuncCtx = new FunctionContext(null, 1, null);
    private int scopeDepth = 0;
    private int nestingDepth = 0;
    private static final int MAX_NESTING_DEPTH = 250;

    private void enterNesting() {
        if (++nestingDepth > MAX_NESTING_DEPTH) {
            int cur = peekIdx();
            throw new ParseException("C stack overflow", tokLine(cur), tokCol(cur));
        }
    }

    private void exitNesting() {
        nestingDepth--;
    }

    private void defineLocal(String name, boolean isConst, int tokIdx) {
        currentScope.define(name, isConst);
        currentFuncCtx.funcLocals.add(name);
        currentFuncCtx.totalLocals++;
        currentFuncCtx.activeVars.add(name);
        if (currentFuncCtx.activeVars.size() > 200) {
            int tLine = tokIdx >= 0 ? tokLine(tokIdx) : 1;
            int tCol = tokIdx >= 0 ? tokCol(tokIdx) : 0;
            int line = currentFuncCtx.lineDefined > 0 ? currentFuncCtx.lineDefined : tLine;
            throw new ParseException(
                    "too many local variables (limit is 200) in function at line " + line + " near " + formatNear(tokIdx),
                    tLine,
                    tCol);
        }
    }

    private void checkNotConst(Expressions.VariableExpr varExpr) {
        Boolean isConst = currentScope.find(varExpr.name());
        if (Boolean.TRUE.equals(isConst)) {
            throw new ParseException(
                    "attempt to assign to const variable '" + varExpr.name() + "'",
                    varExpr.line(),
                    varExpr.column());
        }
        if (isConst == null) {
            FunctionContext ctx = currentFuncCtx;
            while (ctx != null && ctx.outerScope != null) {
                Boolean outerConst = ctx.outerScope.find(varExpr.name());
                if (outerConst != null) {
                    if (Boolean.TRUE.equals(outerConst)) {
                        throw new ParseException(
                                "attempt to assign to const variable '" + varExpr.name() + "'",
                                varExpr.line(),
                                varExpr.column());
                    }
                    break;
                }
                ctx = ctx.parent;
            }
        }
    }

    private void checkUpvalue(String varName, int tokIdx) {
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
            int count = currentFuncCtx.addUpvalue(varName);
            if (count > 255) {
                int tLine = tokIdx >= 0 ? tokLine(tokIdx) : 1;
                int tCol = tokIdx >= 0 ? tokCol(tokIdx) : 0;
                int line = currentFuncCtx.lineDefined > 0 ? currentFuncCtx.lineDefined : tLine;
                throw new ParseException(
                        "too many upvalues (limit is 255) in function at line " + line + " near " + formatNear(tokIdx),
                        tLine,
                        tCol);
            }
        }
    }

    private boolean isLastInBlock() {
        int idx = current;
        while (idx < tokenCount) {
            TokenType t = tokType(idx);
            if (t == TokenType.SEMICOLON) {
                idx++;
            } else if (t == TokenType.DOUBLE_COLON) {
                idx++;
                if (idx < tokenCount && tokType(idx) == TokenType.IDENTIFIER) {
                    idx++;
                    if (idx < tokenCount && tokType(idx) == TokenType.DOUBLE_COLON) {
                        idx++;
                        continue;
                    }
                }
                break;
            } else {
                break;
            }
        }
        if (idx >= tokenCount) {
            return true;
        }
        TokenType t = tokType(idx);
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
                currentFuncCtx.labelCount(),
                currentFuncCtx.gotoCount(),
                isLoop);
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
            if (currentFuncCtx.labels != null) {
                while (currentFuncCtx.labels.size() > bl.firstlabel) {
                    currentFuncCtx.labels.remove(currentFuncCtx.labels.size() - 1);
                }
            }
            int gotoCount = currentFuncCtx.gotoCount();
            if (bl.parent != null) {
                if (currentFuncCtx.gotos != null) {
                    for (int i = bl.firstgoto; i < gotoCount; i++) {
                        GotoRef g = currentFuncCtx.gotos.get(i);
                        g.nactvar = bl.nactvar;
                    }
                }
            } else {
                if (bl.firstgoto < gotoCount) {
                    GotoRef g = currentFuncCtx.gotos.get(bl.firstgoto);
                    int cur = peekIdx();
                    throw new ParseException(
                            "no visible label '" + g.name + "' for <goto> at line " + g.line,
                            tokLine(cur),
                            tokCol(cur));
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
        this.stream = null;
        this.tokenCount = this.tokens.size();
        this.curType = this.tokenCount > 0 ? this.tokens.get(0).type() : TokenType.EOF;
    }

    public Parser(TokenStream stream) {
        this.stream = Objects.requireNonNull(stream);
        this.tokens = null;
        this.tokenCount = stream.size();
        this.curType = this.tokenCount > 0 ? stream.type(0) : TokenType.EOF;
    }

    public Statements.BlockStmt parse() {
        enterScope();
        Statements.BlockStmt block = parseBlockInternal();
        if (!isAtEnd()) {
            int t = peekIdx();
            throw new ParseException("<eof> expected near " + formatNear(t), tokLine(t), tokCol(t));
        }
        exitScope();
        return block;
    }

    private Statements.BlockStmt parseBlockInternal() {
        List<Statement> stmts = new ArrayList<>();
        int startIdx = peekIdx();
        int startLine = startIdx >= 0 ? tokLine(startIdx) : 1;
        int startCol = startIdx >= 0 ? tokCol(startIdx) : 1;

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
        int lastTok = previousIdx();
        int endLine = lastTok >= 0 ? tokLine(lastTok) : startLine;
        return new Statements.BlockStmt(stmts, startLine, startCol, endLine);
    }

    private boolean isBlockEnd() {
        TokenType t = curType;
        return t == TokenType.END || t == TokenType.ELSE || t == TokenType.ELSEIF || t == TokenType.UNTIL || t == TokenType.EOF;
    }

    private Statement parseStatement() {
        enterNesting();
        try {
            int tok = peekIdx();
            return switch (tokType(tok)) {
                case LOCAL -> parseLocalStatement();
                case IF -> parseIfStatement();
                case WHILE -> parseWhileStatement();
                case REPEAT -> parseRepeatStatement();
                case FOR -> parseForStatement();
                case FUNCTION -> parseFunctionStatement();
                case DO -> parseDoStatement();
                case BREAK -> {
                    advanceIdx();
                    int line = tokLine(tok);
                    int col = tokCol(tok);
                    if (currentFuncCtx.loopDepth == 0) {
                        throw new ParseException("break outside loop at line " + line, line, col);
                    }
                    yield new Statements.BreakStmt(line, col);
                }
                case GOTO -> {
                    int gotoTok = advanceIdx();
                    int labelTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
                    String labelName = tokLexeme(labelTok);
                    LabelDef found = null;
                    if (currentFuncCtx.labels != null) {
                        for (LabelDef l : currentFuncCtx.labels) {
                            if (l.name.equals(labelName)) {
                                found = l;
                                break;
                            }
                        }
                    }
                    int gotoLine = tokLine(gotoTok);
                    int gotoCol = tokCol(gotoTok);
                    if (found == null) {
                        currentFuncCtx.ensureGotos().add(new GotoRef(labelName, gotoLine, gotoCol, currentFuncCtx.activeVars.size()));
                    }
                    yield new Statements.GotoStmt(labelName, gotoLine, gotoCol);
                }
                case DOUBLE_COLON -> {
                    int dcolTok = advanceIdx();
                    int labelTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
                    consumeIdx(TokenType.DOUBLE_COLON, "'::' expected");
                    String labelName = tokLexeme(labelTok);
                    int labelLine = tokLine(labelTok);
                    int labelCol = tokCol(labelTok);
                    List<LabelDef> lbls = currentFuncCtx.ensureLabels();
                    for (LabelDef existing : lbls) {
                        if (existing.name.equals(labelName)) {
                            throw new ParseException(
                                    "label '" + labelName + "' already defined on line " + existing.line,
                                    labelLine,
                                    labelCol);
                        }
                    }
                    boolean isLast = isLastInBlock();
                    int labelNactvar = isLast
                            ? (currentFuncCtx.currentBlock != null ? currentFuncCtx.currentBlock.nactvar : 0)
                            : currentFuncCtx.activeVars.size();
                    LabelDef newLabel = new LabelDef(labelName, labelLine, labelNactvar);
                    lbls.add(newLabel);
                    if (currentFuncCtx.gotos != null) {
                        int firstgoto = currentFuncCtx.currentBlock != null ? currentFuncCtx.currentBlock.firstgoto : 0;
                        for (int i = firstgoto; i < currentFuncCtx.gotos.size(); ) {
                            GotoRef g = currentFuncCtx.gotos.get(i);
                            if (g.name.equals(labelName)) {
                                if (g.nactvar < newLabel.nactvar) {
                                    String varName = currentFuncCtx.activeVars.get(g.nactvar);
                                    throw new ParseException(
                                            "<goto " + g.name + "> at line " + g.line + " jumps into the scope of local '" + varName + "'",
                                            g.line,
                                            g.column);
                                }
                                currentFuncCtx.gotos.remove(i);
                            } else {
                                i++;
                            }
                        }
                    }
                    yield new Statements.LabelStmt(labelName, tokLine(dcolTok), tokCol(dcolTok));
                }
                case RETURN -> parseReturnStatement();
                case SEMICOLON -> {
                    advanceIdx();
                    yield null;
                }
                default -> parseAssignmentOrExprStatement();
            };
        } finally {
            exitNesting();
        }
    }

    private Statement parseLocalStatement() {
        int localTok = advanceIdx(); // consume 'local'
        if (match(TokenType.FUNCTION)) {
            int funcTok = previousIdx();
            int nameTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
            String funcName = tokLexeme(nameTok);
            defineLocal(funcName, false, nameTok);
            int lparenTok = consumeIdx(TokenType.LPAREN, "'(' expected");
            List<String> params = new ArrayList<>();
            boolean isVararg = parseParameterList(params);
            checkMatchIdx(TokenType.RPAREN, TokenType.LPAREN, lparenTok);
            int savedScopeDepth = scopeDepth;
            Scope savedScope = currentScope;
            scopeDepth = 0;
            currentScope = null;
            FunctionContext prevFuncCtx = currentFuncCtx;
            currentFuncCtx = new FunctionContext(prevFuncCtx, tokLine(localTok), savedScope);
            enterScope();
            for (String p : params) {
                defineLocal(p, false, localTok);
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
            int endTok = checkMatchIdx(TokenType.END, TokenType.FUNCTION, funcTok);
            return new Statements.LocalFunctionDefStmt(
                    funcName, params, isVararg, body, tokLine(funcTok), tokCol(funcTok), tokLine(endTok));
        }

        List<Statements.LocalVarBinding> bindings = new ArrayList<>();
        do {
            int nameTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
            Statements.VariableAttribute attr = Statements.VariableAttribute.NONE;
            if (match(TokenType.ATTR_CLOSE)) {
                attr = Statements.VariableAttribute.CLOSE;
            } else if (match(TokenType.ATTR_CONST)) {
                attr = Statements.VariableAttribute.CONST;
            } else if (match(TokenType.LESS)) {
                int attrTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
                consumeIdx(TokenType.GREATER, "'>' expected");
                String attrName = tokLexeme(attrTok);
                if ("const".equals(attrName)) {
                    attr = Statements.VariableAttribute.CONST;
                } else if ("close".equals(attrName)) {
                    attr = Statements.VariableAttribute.CLOSE;
                } else {
                    throw new ParseException("unknown attribute '" + attrName + "'", tokLine(attrTok), tokCol(attrTok));
                }
            }
            bindings.add(new Statements.LocalVarBinding(tokLexeme(nameTok), attr));
        } while (match(TokenType.COMMA));

        List<Expression> initializers = new ArrayList<>();
        if (match(TokenType.ASSIGN)) {
            do {
                initializers.add(parseExpression());
            } while (match(TokenType.COMMA));
        }

        for (Statements.LocalVarBinding binding : bindings) {
            boolean isConst = (binding.attribute() == Statements.VariableAttribute.CONST
                    || binding.attribute() == Statements.VariableAttribute.CLOSE);
            defineLocal(binding.name(), isConst, localTok);
        }

        return new Statements.LocalVarDeclStmt(bindings, initializers, tokLine(localTok), tokCol(localTok));
    }

    private Statement parseIfStatement() {
        int ifTok = advanceIdx(); // consume 'if'
        List<Statements.IfBranch> branches = new ArrayList<>();

        Expression cond = parseExpression();
        int thenTok = consumeIdx(TokenType.THEN, "'then' expected");
        enterScope();
        Statements.BlockStmt block = parseBlockInternal();
        exitScope();
        branches.add(new Statements.IfBranch(cond, block, tokLine(thenTok)));

        while (match(TokenType.ELSEIF)) {
            Expression elseifCond = parseExpression();
            int elseifThenTok = consumeIdx(TokenType.THEN, "'then' expected");
            enterScope();
            Statements.BlockStmt elseifBlock = parseBlockInternal();
            exitScope();
            branches.add(new Statements.IfBranch(elseifCond, elseifBlock, tokLine(elseifThenTok)));
        }

        Statements.BlockStmt elseBlock = null;
        if (match(TokenType.ELSE)) {
            enterScope();
            elseBlock = parseBlockInternal();
            exitScope();
        }

        checkMatchIdx(TokenType.END, TokenType.IF, ifTok);
        return new Statements.IfStmt(branches, elseBlock, tokLine(ifTok), tokCol(ifTok));
    }

    private Statement parseWhileStatement() {
        int whileTok = advanceIdx(); // consume 'while'
        Expression cond = parseExpression();
        consumeIdx(TokenType.DO, "'do' expected");
        enterScope();
        currentFuncCtx.loopDepth++;
        Statements.BlockStmt body;
        try {
            body = parseBlockInternal();
        } finally {
            currentFuncCtx.loopDepth--;
        }
        exitScope();
        int endTok = checkMatchIdx(TokenType.END, TokenType.WHILE, whileTok);
        return new Statements.WhileStmt(cond, body, tokLine(whileTok), tokCol(whileTok), tokLine(endTok));
    }

    private Statement parseRepeatStatement() {
        int repeatTok = advanceIdx(); // consume 'repeat'
        enterScope();
        currentFuncCtx.loopDepth++;
        Statements.BlockStmt body;
        try {
            body = parseBlockInternal();
        } finally {
            currentFuncCtx.loopDepth--;
        }
        checkMatchIdx(TokenType.UNTIL, TokenType.REPEAT, repeatTok);
        Expression cond = parseExpression();
        exitScope();
        return new Statements.RepeatStmt(body, cond, tokLine(repeatTok), tokCol(repeatTok));
    }

    private Statement parseForStatement() {
        int forTok = advanceIdx(); // consume 'for'
        int varNameTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
        String firstVarName = tokLexeme(varNameTok);

        if (match(TokenType.ASSIGN)) {
            Expression start = parseExpression();
            consumeIdx(TokenType.COMMA, "',' expected");
            Expression limit = parseExpression();
            Expression step = null;
            if (match(TokenType.COMMA)) {
                step = parseExpression();
            }
            consumeIdx(TokenType.DO, "'do' expected");
            enterScope();
            defineLocal(firstVarName, false, varNameTok);
            currentFuncCtx.loopDepth++;
            Statements.BlockStmt body;
            try {
                body = parseBlockInternal();
            } finally {
                currentFuncCtx.loopDepth--;
            }
            exitScope();
            int endTok = checkMatchIdx(TokenType.END, TokenType.FOR, forTok);
            return new Statements.ForNumericStmt(
                    firstVarName, start, limit, step, body, tokLine(forTok), tokCol(forTok), tokLine(endTok));
        } else {
            List<String> vars = new ArrayList<>();
            vars.add(firstVarName);
            while (match(TokenType.COMMA)) {
                vars.add(tokLexeme(consumeIdx(TokenType.IDENTIFIER, "<name> expected")));
            }
            if (vars.size() == 1 && !check(TokenType.IN) && !check(TokenType.ASSIGN)) {
                int cur = peekIdx();
                throw new ParseException("'=' or 'in' expected near " + formatNear(cur), tokLine(cur), tokCol(cur));
            }
            consumeIdx(TokenType.IN, "'in' expected");
            List<Expression> iterators = new ArrayList<>();
            do {
                iterators.add(parseExpression());
            } while (match(TokenType.COMMA));

            consumeIdx(TokenType.DO, "'do' expected");
            enterScope();
            for (String v : vars) {
                defineLocal(v, false, forTok);
            }
            currentFuncCtx.loopDepth++;
            Statements.BlockStmt body;
            try {
                body = parseBlockInternal();
            } finally {
                currentFuncCtx.loopDepth--;
            }
            exitScope();
            int endTok = checkMatchIdx(TokenType.END, TokenType.FOR, forTok);
            return new Statements.ForGenericStmt(vars, iterators, body, tokLine(forTok), tokCol(forTok), tokLine(endTok));
        }
    }

    private Statement parseFunctionStatement() {
        int funcTok = advanceIdx(); // consume 'function'
        int funcLine = tokLine(funcTok);
        int funcCol = tokCol(funcTok);
        Expression target = new Expressions.VariableExpr(
                tokLexeme(consumeIdx(TokenType.IDENTIFIER, "<name> expected")),
                funcLine,
                funcCol);

        while (match(TokenType.DOT)) {
            int memberTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
            int mLine = tokLine(memberTok);
            int mCol = tokCol(memberTok);
            target = new Expressions.TableAccessExpr(
                    target,
                    makeStringLiteral(tokLexeme(memberTok), mLine, mCol),
                    mLine,
                    mCol);
        }

        boolean isMethod = false;
        if (match(TokenType.COLON)) {
            isMethod = true;
            int methodTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
            int mLine = tokLine(methodTok);
            int mCol = tokCol(methodTok);
            target = new Expressions.TableAccessExpr(
                    target,
                    makeStringLiteral(tokLexeme(methodTok), mLine, mCol),
                    mLine,
                    mCol);
        } else if (target instanceof Expressions.VariableExpr varExpr) {
            checkNotConst(varExpr);
        }

        int lparenTok = consumeIdx(TokenType.LPAREN, "'(' expected");
        List<String> params = new ArrayList<>();
        if (isMethod) {
            params.add("self");
        }
        boolean isVararg = parseParameterList(params);
        checkMatchIdx(TokenType.RPAREN, TokenType.LPAREN, lparenTok);
        int savedScopeDepth = scopeDepth;
        Scope savedScope = currentScope;
        scopeDepth = 0;
        currentScope = null;
        FunctionContext prevFuncCtx = currentFuncCtx;
        currentFuncCtx = new FunctionContext(prevFuncCtx, funcLine, savedScope);
        enterScope();
        for (String p : params) {
            defineLocal(p, false, lparenTok);
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
        int endTok = checkMatchIdx(TokenType.END, TokenType.FUNCTION, funcTok);

        return new Statements.FunctionDefStmt(target, isMethod, params, isVararg, body, funcLine, funcCol, tokLine(endTok));
    }

    private Statement parseDoStatement() {
        int doTok = advanceIdx();
        enterScope();
        Statements.BlockStmt body = parseBlockInternal();
        exitScope();
        checkMatchIdx(TokenType.END, TokenType.DO, doTok);
        return body;
    }

    private Statement parseReturnStatement() {
        int returnTok = advanceIdx();
        int rLine = tokLine(returnTok);
        int rCol = tokCol(returnTok);
        if (isBlockEnd() || peekType() == TokenType.SEMICOLON) {
            return new Statements.ReturnStmt(List.of(), rLine, rCol);
        }
        Expression first = parseExpression();
        if (!match(TokenType.COMMA)) {
            return new Statements.ReturnStmt(List.of(first), rLine, rCol);
        }
        List<Expression> values = new ArrayList<>(4);
        values.add(first);
        do {
            values.add(parseExpression());
        } while (match(TokenType.COMMA));
        return new Statements.ReturnStmt(values, rLine, rCol);
    }

    private Statement parseAssignmentOrExprStatement() {
        int startTok = peekIdx();
        int startLine = tokLine(startTok);
        int startCol = tokCol(startTok);
        Expression expr = parsePrefixExpression();

        if (expr instanceof Expressions.FunctionCallExpr) {
            return new Statements.ExprStmt(expr, startLine, startCol);
        }

        if (!(expr instanceof Expressions.VariableExpr) && !(expr instanceof Expressions.TableAccessExpr)) {
            throw new ParseException("syntax error near " + formatNear(startTok), startLine, startCol);
        }

        List<Expression> targets;
        if (!match(TokenType.COMMA)) {
            targets = List.of(expr);
        } else {
            targets = new ArrayList<>(4);
            targets.add(expr);
            do {
                if (targets.size() >= 200) {
                    int cur = peekIdx();
                    throw new ParseException("C stack overflow", tokLine(cur), tokCol(cur));
                }
                Expression nextExpr = parsePrefixExpression();
                if (!(nextExpr instanceof Expressions.VariableExpr) && !(nextExpr instanceof Expressions.TableAccessExpr)) {
                    int cur = peekIdx();
                    throw new ParseException("syntax error near " + formatNear(cur), tokLine(cur), tokCol(cur));
                }
                targets.add(nextExpr);
            } while (match(TokenType.COMMA));
        }

        if (!check(TokenType.ASSIGN)) {
            int cur = peekIdx();
            throw new ParseException("syntax error near " + formatNear(cur), tokLine(cur), tokCol(cur));
        }
        consumeIdx(TokenType.ASSIGN, "'=' expected");

        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) instanceof Expressions.VariableExpr varExpr) {
                checkNotConst(varExpr);
            }
        }

        Expression firstVal = parseExpression();
        List<Expression> values;
        if (!match(TokenType.COMMA)) {
            values = List.of(firstVal);
        } else {
            values = new ArrayList<>(4);
            values.add(firstVal);
            do {
                if (values.size() >= 200) {
                    int cur = peekIdx();
                    throw new ParseException("C stack overflow", tokLine(cur), tokCol(cur));
                }
                values.add(parseExpression());
            } while (match(TokenType.COMMA));
        }

        return new Statements.AssignmentStmt(targets, values, startLine, startCol);
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
            int op = previousIdx();
            Expression right = parseLogicalAnd();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseLogicalAnd() {
        Expression expr = parseRelational();
        while (match(TokenType.AND)) {
            int op = previousIdx();
            Expression right = parseRelational();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseRelational() {
        Expression expr = parseBitwiseOr();
        while (match(TokenType.EQUAL_EQUAL, TokenType.TILDE_EQUAL,
                TokenType.LESS, TokenType.LESS_EQUAL,
                TokenType.GREATER, TokenType.GREATER_EQUAL)) {
            int op = previousIdx();
            Expression right = parseBitwiseOr();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseBitwiseOr() {
        Expression expr = parseBitwiseXor();
        while (match(TokenType.PIPE)) {
            int op = previousIdx();
            Expression right = parseBitwiseXor();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseBitwiseXor() {
        Expression expr = parseBitwiseAnd();
        while (match(TokenType.TILDE)) {
            int op = previousIdx();
            Expression right = parseBitwiseAnd();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseBitwiseAnd() {
        Expression expr = parseBitwiseShift();
        while (match(TokenType.AMPERSAND)) {
            int op = previousIdx();
            Expression right = parseBitwiseShift();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseBitwiseShift() {
        Expression expr = parseConcat();
        while (match(TokenType.SHL, TokenType.SHR)) {
            int op = previousIdx();
            Expression right = parseConcat();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseConcat() {
        Expression expr = parseAdditive();
        if (match(TokenType.DOT_DOT)) {
            enterNesting();
            try {
                int op = previousIdx();
                Expression right = parseConcat();
                expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
            } finally {
                exitNesting();
            }
        }
        return expr;
    }

    private Expression parseAdditive() {
        Expression expr = parseMultiplicative();
        while (match(TokenType.PLUS, TokenType.MINUS)) {
            int op = previousIdx();
            Expression right = parseMultiplicative();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseMultiplicative() {
        Expression expr = parseUnary();
        while (match(TokenType.STAR, TokenType.SLASH, TokenType.DOUBLE_SLASH, TokenType.PERCENT)) {
            int op = previousIdx();
            Expression right = parseUnary();
            expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
        }
        return expr;
    }

    private Expression parseUnary() {
        if (match(TokenType.NOT, TokenType.HASH, TokenType.MINUS, TokenType.TILDE)) {
            enterNesting();
            try {
                int op = previousIdx();
                Expression operand = parseUnary();
                return new Expressions.UnaryExpr(tokType(op), operand, tokLine(op), tokCol(op));
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
                int op = previousIdx();
                Expression right = parseUnary();
                expr = new Expressions.BinaryExpr(expr, tokType(op), right, tokLine(op), tokCol(op));
            } finally {
                exitNesting();
            }
        }
        return expr;
    }

    private Expression parsePrefixExpression() {
        int startTok = peekIdx();
        int startLine = tokLine(startTok);
        int startCol = tokCol(startTok);
        Expression expr;

        if (match(TokenType.LPAREN)) {
            int lparenTok = previousIdx();
            Expression inner = parseExpression();
            checkMatchIdx(TokenType.RPAREN, TokenType.LPAREN, lparenTok);
            expr = new Expressions.ParenExpr(inner, tokLine(lparenTok), tokCol(lparenTok));
        } else if (match(TokenType.IDENTIFIER)) {
            int idTok = previousIdx();
            String idName = tokLexeme(idTok);
            checkUpvalue(idName, idTok);
            expr = new Expressions.VariableExpr(idName, startLine, startCol);
        } else {
            return parsePrimaryLiteral();
        }

        while (true) {
            if (match(TokenType.LBRACKET)) {
                int lbracketTok = previousIdx();
                Expression index = parseExpression();
                checkMatchIdx(TokenType.RBRACKET, TokenType.LBRACKET, lbracketTok);
                expr = new Expressions.TableAccessExpr(expr, index, startLine, startCol);
            } else if (match(TokenType.DOT)) {
                int fieldTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
                int fLine = tokLine(fieldTok);
                int fCol = tokCol(fieldTok);
                expr = new Expressions.TableAccessExpr(
                        expr,
                        makeStringLiteral(tokLexeme(fieldTok), fLine, fCol),
                        fLine,
                        fCol);
            } else if (match(TokenType.COLON)) {
                int methodTok = consumeIdx(TokenType.IDENTIFIER, "<name> expected");
                int callTok = peekIdx();
                List<Expression> args = parseCallArguments();
                expr = new Expressions.FunctionCallExpr(
                        expr, tokLexeme(methodTok), args, tokLine(callTok), tokCol(callTok));
            } else {
                TokenType pt = peekType();
                if (pt == TokenType.LPAREN || pt == TokenType.LBRACE || pt == TokenType.STRING_LITERAL) {
                    int callTok = peekIdx();
                    List<Expression> args = parseCallArguments();
                    expr = new Expressions.FunctionCallExpr(expr, null, args, tokLine(callTok), tokCol(callTok));
                } else {
                    break;
                }
            }
        }
        return expr;
    }

    private List<Expression> parseCallArguments() {
        enterNesting();
        try {
            if (match(TokenType.LPAREN)) {
                int lparenTok = previousIdx();
                if (match(TokenType.RPAREN)) {
                    return List.of();
                }
                List<Expression> args = new ArrayList<>(4);
                do {
                    if (args.size() >= 255) {
                        int cur = peekIdx();
                        throw new ParseException(
                                "function or expression needs too many registers near " + formatNear(cur),
                                tokLine(cur),
                                tokCol(cur));
                    }
                    args.add(parseExpression());
                } while (match(TokenType.COMMA));
                checkMatchIdx(TokenType.RPAREN, TokenType.LPAREN, lparenTok);
                return args;
            } else if (peekType() == TokenType.LBRACE) {
                return List.of(parseTableConstructor());
            } else if (peekType() == TokenType.STRING_LITERAL) {
                int strTok = advanceIdx();
                return List.of(makeStringLiteral(tokStringLiteral(strTok), tokLine(strTok), tokCol(strTok)));
            }
            int cur = peekIdx();
            throw new ParseException("function arguments expected near " + formatNear(cur), tokLine(cur), tokCol(cur));
        } finally {
            exitNesting();
        }
    }

    private Expression parsePrimaryLiteral() {
        int tok = peekIdx();
        int tLine = tokLine(tok);
        int tCol = tokCol(tok);
        return switch (tokType(tok)) {
            case NIL -> {
                advanceIdx();
                yield new Expressions.NilLiteral(tLine, tCol);
            }
            case TRUE -> {
                advanceIdx();
                yield new Expressions.BooleanLiteral(true, tLine, tCol);
            }
            case FALSE -> {
                advanceIdx();
                yield new Expressions.BooleanLiteral(false, tLine, tCol);
            }
            case INTEGER_LITERAL -> {
                advanceIdx();
                yield new Expressions.IntegerLiteral(tokLongLiteral(tok), tLine, tCol);
            }
            case FLOAT_LITERAL -> {
                advanceIdx();
                yield new Expressions.FloatLiteral(tokDoubleLiteral(tok), tLine, tCol);
            }
            case STRING_LITERAL -> {
                advanceIdx();
                yield makeStringLiteral(tokStringLiteral(tok), tLine, tCol);
            }
            case DOT_DOT_DOT -> {
                advanceIdx();
                yield new Expressions.VarargLiteral(tLine, tCol);
            }
            case LBRACE -> parseTableConstructor();
            case FUNCTION -> {
                int funcTok = advanceIdx();
                int lparenTok = consumeIdx(TokenType.LPAREN, "'(' expected");
                List<String> params = new ArrayList<>();
                boolean isVararg = parseParameterList(params);
                checkMatchIdx(TokenType.RPAREN, TokenType.LPAREN, lparenTok);
                int savedScopeDepth = scopeDepth;
                Scope savedScope = currentScope;
                scopeDepth = 0;
                currentScope = null;
                FunctionContext prevFuncCtx = currentFuncCtx;
                currentFuncCtx = new FunctionContext(prevFuncCtx, tokLine(funcTok), savedScope);
                enterScope();
                for (String p : params) {
                    defineLocal(p, false, lparenTok);
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
                int endTok = checkMatchIdx(TokenType.END, TokenType.FUNCTION, funcTok);
                yield new Expressions.FunctionDefExpr(params, isVararg, body, tLine, tCol, tokLine(endTok));
            }
            default -> throw new ParseException("unexpected symbol near " + formatNear(tok), tLine, tCol);
        };
    }

    private Expressions.TableConstructorExpr parseTableConstructor() {
        enterNesting();
        try {
            int braceTok = consumeIdx(TokenType.LBRACE, "'{' expected");
            int bLine = tokLine(braceTok);
            int bCol = tokCol(braceTok);
            if (match(TokenType.RBRACE)) {
                return new Expressions.TableConstructorExpr(List.of(), bLine, bCol);
            }
            List<Expressions.TableField> fields = new ArrayList<>(4);

            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                if (match(TokenType.LBRACKET)) {
                    int openBracketTok = previousIdx();
                    Expression key = parseExpression();
                    checkMatchIdx(TokenType.RBRACKET, TokenType.LBRACKET, openBracketTok);
                    consumeIdx(TokenType.ASSIGN, "'=' expected");
                    Expression value = parseExpression();
                    fields.add(new Expressions.TableField(key, value));
                } else if (peekType() == TokenType.IDENTIFIER && peekNextType() == TokenType.ASSIGN) {
                    int keyTok = advanceIdx();
                    advanceIdx(); // consume '='
                    Expression value = parseExpression();
                    fields.add(new Expressions.TableField(
                            makeStringLiteral(tokLexeme(keyTok), tokLine(keyTok), tokCol(keyTok)),
                            value));
                } else {
                    Expression val = parseExpression();
                    fields.add(new Expressions.TableField(null, val));
                }

                if (!match(TokenType.COMMA) && !match(TokenType.SEMICOLON)) {
                    break;
                }
            }
            checkMatchIdx(TokenType.RBRACE, TokenType.LBRACE, braceTok);
            return new Expressions.TableConstructorExpr(fields, bLine, bCol);
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
                int paramTok = advanceIdx();
                params.add(tokLexeme(paramTok));
                if (match(TokenType.COMMA)) {
                    if (check(TokenType.RPAREN)) {
                        int cur = peekIdx();
                        throw new ParseException(
                                "<name> or '...' expected near " + formatNear(cur),
                                tokLine(cur),
                                tokCol(cur));
                    }
                    continue;
                }
                break;
            } else {
                int cur = peekIdx();
                throw new ParseException(
                        "<name> or '...' expected near " + formatNear(cur),
                        tokLine(cur),
                        tokCol(cur));
            }
        }
        return false;
    }

    private boolean match(TokenType t1) {
        if (curType == t1) {
            advanceIdx();
            return true;
        }
        return false;
    }

    private boolean match(TokenType t1, TokenType t2) {
        TokenType cur = curType;
        if (cur == t1 || cur == t2) {
            advanceIdx();
            return true;
        }
        return false;
    }

    private boolean match(TokenType t1, TokenType t2, TokenType t3) {
        TokenType cur = curType;
        if (cur == t1 || cur == t2 || cur == t3) {
            advanceIdx();
            return true;
        }
        return false;
    }

    private boolean match(TokenType t1, TokenType t2, TokenType t3, TokenType t4) {
        TokenType cur = curType;
        if (cur == t1 || cur == t2 || cur == t3 || cur == t4) {
            advanceIdx();
            return true;
        }
        return false;
    }

    private boolean match(TokenType t1, TokenType t2, TokenType t3, TokenType t4, TokenType t5, TokenType t6) {
        TokenType cur = curType;
        if (cur == t1 || cur == t2 || cur == t3 || cur == t4 || cur == t5 || cur == t6) {
            advanceIdx();
            return true;
        }
        return false;
    }

    private boolean check(TokenType type) {
        return curType == type && current < tokenCount;
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

    private String formatNear(int tokIdx) {
        if (tokIdx < 0 || tokIdx >= tokenCount) {
            return "<eof>";
        }
        TokenType t = tokType(tokIdx);
        if (t == TokenType.EOF) {
            return "<eof>";
        }
        String lex = tokLexeme(tokIdx);
        if (t == TokenType.INVALID) {
            if (lex != null && !lex.isEmpty()) {
                int c = lex.charAt(0) & 0xFF;
                if (c < 32 || c >= 127) {
                    return "'<\\" + c + ">'";
                }
            }
        }
        if (t == TokenType.STRING_LITERAL) {
            if (lex != null && lex.startsWith("[[")) {
                return "'" + lex + "'";
            } else {
                return "''" + tokRawLiteral(tokIdx) + "''";
            }
        }
        if (lex == null || lex.isEmpty()) {
            Object rawLit = tokRawLiteral(tokIdx);
            if (rawLit != null) {
                return "'" + rawLit + "'";
            }
            String fixed = t.fixedLexeme();
            if (fixed != null && !fixed.isEmpty()) {
                return "'" + fixed + "'";
            }
            return "<eof>";
        }
        if (lex.length() == 1) {
            int c = lex.charAt(0) & 0xFF;
            if (c < 32 || c >= 127) {
                return "'<\\" + c + ">'";
            }
        }
        return "'" + lex + "'";
    }

    private int checkMatchIdx(TokenType what, TokenType who, int whoIdx) {
        if (check(what)) {
            return advanceIdx();
        }
        String whatStr = tokenRepresentation(what);
        String whoStr = tokenRepresentation(who);
        int cur = peekIdx();
        int curLine = tokLine(cur);
        String msg;
        if (whoIdx >= 0 && tokLine(whoIdx) != curLine) {
            msg = whatStr + " expected (to close " + whoStr + " at line " + tokLine(whoIdx) + ") near " + formatNear(cur);
        } else {
            msg = whatStr + " expected near " + formatNear(cur);
        }
        throw new ParseException(msg, curLine, tokCol(cur));
    }

    private int consumeIdx(TokenType type, String expectedWhat) {
        if (check(type)) {
            return advanceIdx();
        }
        int cur = peekIdx();
        String prefix = expectedWhat.endsWith("expected") ? expectedWhat : (expectedWhat + " expected");
        throw new ParseException(prefix + " near " + formatNear(cur), tokLine(cur), tokCol(cur));
    }

    private int advanceIdx() {
        if (curType != TokenType.EOF && current < tokenCount) {
            int prev = current;
            int next = prev + 1;
            current = next;
            if (next < tokenCount) {
                curType = stream != null ? stream.type(next) : tokens.get(next).type();
            } else {
                curType = TokenType.EOF;
            }
            return prev;
        }
        return previousIdx();
    }

    private int peekIdx() {
        if (tokenCount == 0) {
            return -1;
        }
        return current < tokenCount ? current : tokenCount - 1;
    }

    private TokenType peekType() {
        return curType;
    }

    private TokenType peekNextType() {
        if (tokenCount == 0) {
            return TokenType.EOF;
        }
        int next = current + 1;
        return tokType(next >= tokenCount ? tokenCount - 1 : next);
    }

    private int previousIdx() {
        if (current <= 0) {
            return tokenCount == 0 ? -1 : 0;
        }
        return current - 1;
    }

    private boolean isAtEnd() {
        return curType == TokenType.EOF || current >= tokenCount;
    }

    private TokenType tokType(int idx) {
        return stream != null ? stream.type(idx) : tokens.get(idx).type();
    }

    private int tokLine(int idx) {
        if (idx < 0) {
            return 1;
        }
        return stream != null ? stream.line(idx) : tokens.get(idx).line();
    }

    private int tokCol(int idx) {
        if (idx < 0) {
            return 1;
        }
        return stream != null ? stream.column(idx) : tokens.get(idx).column();
    }

    private String tokLexeme(int idx) {
        return stream != null ? stream.lexeme(idx) : tokens.get(idx).lexeme();
    }

    private String tokStringLiteral(int idx) {
        return stream != null ? stream.stringLiteral(idx) : (String) tokens.get(idx).literal();
    }

    private long tokLongLiteral(int idx) {
        return stream != null ? stream.longLiteral(idx) : (Long) tokens.get(idx).literal();
    }

    private double tokDoubleLiteral(int idx) {
        return stream != null ? stream.doubleLiteral(idx) : (Double) tokens.get(idx).literal();
    }

    private Object tokRawLiteral(int idx) {
        return stream != null ? stream.rawLiteral(idx) : tokens.get(idx).literal();
    }
}

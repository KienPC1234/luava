package org.luava.frontend.parser;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Parser {
    private final List<Token> tokens;
    private int current = 0;

    public Parser(List<Token> tokens) {
        this.tokens = tokens != null ? tokens : List.of();
    }

    public Statements.BlockStmt parse() {
        List<Statement> stmts = new ArrayList<>();
        int startLine = peek().line();
        int startCol = peek().column();

        while (!isAtEnd() && !isBlockEnd()) {
            Statement stmt = parseStatement();
            if (stmt != null) {
                stmts.add(stmt);
            }
            while (match(TokenType.SEMICOLON)) {
                // optional semicolons
            }
        }
        return new Statements.BlockStmt(stmts, startLine, startCol);
    }

    private boolean isBlockEnd() {
        TokenType t = peek().type();
        return t == TokenType.END || t == TokenType.ELSE || t == TokenType.ELSEIF || t == TokenType.UNTIL || t == TokenType.EOF;
    }

    private Statement parseStatement() {
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
                yield new Statements.BreakStmt(token.line(), token.column());
            }
            case GOTO -> {
                advance();
                Token labelToken = consume(TokenType.IDENTIFIER, "Expect label name after goto");
                yield new Statements.GotoStmt(labelToken.lexeme(), token.line(), token.column());
            }
            case DOUBLE_COLON -> {
                advance();
                Token labelToken = consume(TokenType.IDENTIFIER, "Expect label name");
                consume(TokenType.DOUBLE_COLON, "Expect '::' after label name");
                yield new Statements.LabelStmt(labelToken.lexeme(), token.line(), token.column());
            }
            case RETURN -> parseReturnStatement();
            default -> parseAssignmentOrExprStatement();
        };
    }

    private Statement parseLocalStatement() {
        Token localToken = advance(); // consume 'local'
        if (match(TokenType.FUNCTION)) {
            Token name = consume(TokenType.IDENTIFIER, "Expect function name in local function");
            consume(TokenType.LPAREN, "Expect '(' after function name");
            List<String> params = new ArrayList<>();
            boolean isVararg = parseParameterList(params);
            consume(TokenType.RPAREN, "Expect ')' after parameters");
            Statements.BlockStmt body = parse();
            consume(TokenType.END, "Expect 'end' after function body");
            return new Statements.LocalFunctionDefStmt(name.lexeme(), params, isVararg, body, localToken.line(), localToken.column());
        }

        List<Statements.LocalVarBinding> bindings = new ArrayList<>();
        do {
            Token name = consume(TokenType.IDENTIFIER, "Expect variable name");
            Statements.VariableAttribute attr = Statements.VariableAttribute.NONE;
            if (match(TokenType.ATTR_CLOSE)) {
                attr = Statements.VariableAttribute.CLOSE;
            } else if (match(TokenType.ATTR_CONST)) {
                attr = Statements.VariableAttribute.CONST;
            }
            bindings.add(new Statements.LocalVarBinding(name.lexeme(), attr));
        } while (match(TokenType.COMMA));

        List<Expression> initializers = new ArrayList<>();
        if (match(TokenType.ASSIGN)) {
            do {
                initializers.add(parseExpression());
            } while (match(TokenType.COMMA));
        }

        return new Statements.LocalVarDeclStmt(bindings, initializers, localToken.line(), localToken.column());
    }

    private Statement parseIfStatement() {
        Token ifToken = advance(); // consume 'if'
        List<Statements.IfBranch> branches = new ArrayList<>();

        Expression cond = parseExpression();
        consume(TokenType.THEN, "Expect 'then' after if condition");
        Statements.BlockStmt block = parse();
        branches.add(new Statements.IfBranch(cond, block));

        while (match(TokenType.ELSEIF)) {
            Expression elseifCond = parseExpression();
            consume(TokenType.THEN, "Expect 'then' after elseif condition");
            Statements.BlockStmt elseifBlock = parse();
            branches.add(new Statements.IfBranch(elseifCond, elseifBlock));
        }

        Statements.BlockStmt elseBlock = null;
        if (match(TokenType.ELSE)) {
            elseBlock = parse();
        }

        consume(TokenType.END, "Expect 'end' after if statement");
        return new Statements.IfStmt(branches, elseBlock, ifToken.line(), ifToken.column());
    }

    private Statement parseWhileStatement() {
        Token whileToken = advance(); // consume 'while'
        Expression cond = parseExpression();
        consume(TokenType.DO, "Expect 'do' after while condition");
        Statements.BlockStmt body = parse();
        consume(TokenType.END, "Expect 'end' after while body");
        return new Statements.WhileStmt(cond, body, whileToken.line(), whileToken.column());
    }

    private Statement parseRepeatStatement() {
        Token repeatToken = advance(); // consume 'repeat'
        Statements.BlockStmt body = parse();
        consume(TokenType.UNTIL, "Expect 'until' after repeat body");
        Expression cond = parseExpression();
        return new Statements.RepeatStmt(body, cond, repeatToken.line(), repeatToken.column());
    }

    private Statement parseForStatement() {
        Token forToken = advance(); // consume 'for'
        Token varName = consume(TokenType.IDENTIFIER, "Expect variable name in for loop");

        if (match(TokenType.ASSIGN)) {
            // Numeric for: for var = init, limit [, step] do
            Expression start = parseExpression();
            consume(TokenType.COMMA, "Expect ',' after for loop start expression");
            Expression limit = parseExpression();
            Expression step = null;
            if (match(TokenType.COMMA)) {
                step = parseExpression();
            }
            consume(TokenType.DO, "Expect 'do' after for loop specifications");
            Statements.BlockStmt body = parse();
            consume(TokenType.END, "Expect 'end' after for loop body");
            return new Statements.ForNumericStmt(varName.lexeme(), start, limit, step, body, forToken.line(), forToken.column());
        } else {
            // Generic for: for var1, var2 in exp1, exp2 do
            List<String> vars = new ArrayList<>();
            vars.add(varName.lexeme());
            while (match(TokenType.COMMA)) {
                vars.add(consume(TokenType.IDENTIFIER, "Expect variable name").lexeme());
            }
            consume(TokenType.IN, "Expect 'in' after for loop variables");
            List<Expression> iterators = new ArrayList<>();
            do {
                iterators.add(parseExpression());
            } while (match(TokenType.COMMA));

            consume(TokenType.DO, "Expect 'do' after for loop iterators");
            Statements.BlockStmt body = parse();
            consume(TokenType.END, "Expect 'end' after for loop body");
            return new Statements.ForGenericStmt(vars, iterators, body, forToken.line(), forToken.column());
        }
    }

    private Statement parseFunctionStatement() {
        Token funcToken = advance(); // consume 'function'
        Expression target = new Expressions.VariableExpr(
            consume(TokenType.IDENTIFIER, "Expect function name").lexeme(),
            funcToken.line(), funcToken.column()
        );

        while (match(TokenType.DOT)) {
            Token member = consume(TokenType.IDENTIFIER, "Expect member identifier after '.'");
            target = new Expressions.TableAccessExpr(
                target,
                new Expressions.StringLiteral(member.lexeme(), member.line(), member.column()),
                member.line(), member.column()
            );
        }

        boolean isMethod = false;
        if (match(TokenType.COLON)) {
            isMethod = true;
            Token method = consume(TokenType.IDENTIFIER, "Expect method identifier after ':'");
            target = new Expressions.TableAccessExpr(
                target,
                new Expressions.StringLiteral(method.lexeme(), method.line(), method.column()),
                method.line(), method.column()
            );
        }

        consume(TokenType.LPAREN, "Expect '(' after function name");
        List<String> params = new ArrayList<>();
        if (isMethod) {
            params.add("self");
        }
        boolean isVararg = parseParameterList(params);
        consume(TokenType.RPAREN, "Expect ')' after parameters");
        Statements.BlockStmt body = parse();
        consume(TokenType.END, "Expect 'end' after function body");

        return new Statements.FunctionDefStmt(target, isMethod, params, isVararg, body, funcToken.line(), funcToken.column());
    }

    private Statement parseDoStatement() {
        Token doToken = advance();
        Statements.BlockStmt body = parse();
        consume(TokenType.END, "Expect 'end' after do block");
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

        List<Expression> targets = new ArrayList<>();
        targets.add(expr);
        while (match(TokenType.COMMA)) {
            targets.add(parsePrefixExpression());
        }

        consume(TokenType.ASSIGN, "Expect '=' in assignment statement");

        List<Expression> values = new ArrayList<>();
        do {
            values.add(parseExpression());
        } while (match(TokenType.COMMA));

        return new Statements.AssignmentStmt(targets, values, startToken.line(), startToken.column());
    }

    public Expression parseExpression() {
        return parseLogicalOr();
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
            Token op = previous();
            // right-associative
            Expression right = parseConcat();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
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
            Token op = previous();
            Expression operand = parseUnary();
            return new Expressions.UnaryExpr(op.type(), operand, op.line(), op.column());
        }
        return parseExponent();
    }

    private Expression parseExponent() {
        Expression expr = parsePrefixExpression();
        if (match(TokenType.CARET)) {
            Token op = previous();
            // right-associative
            Expression right = parseExponent();
            expr = new Expressions.BinaryExpr(expr, op.type(), right, op.line(), op.column());
        }
        return expr;
    }

    private Expression parsePrefixExpression() {
        Token token = peek();
        Expression expr;

        if (match(TokenType.LPAREN)) {
            expr = parseExpression();
            consume(TokenType.RPAREN, "Expect ')' after grouped expression");
        } else if (match(TokenType.IDENTIFIER)) {
            expr = new Expressions.VariableExpr(previous().lexeme(), token.line(), token.column());
        } else {
            return parsePrimaryLiteral();
        }

        // Parse calls and field access
        while (true) {
            if (match(TokenType.LBRACKET)) {
                Expression index = parseExpression();
                consume(TokenType.RBRACKET, "Expect ']' after table index");
                expr = new Expressions.TableAccessExpr(expr, index, token.line(), token.column());
            } else if (match(TokenType.DOT)) {
                Token field = consume(TokenType.IDENTIFIER, "Expect identifier after '.'");
                expr = new Expressions.TableAccessExpr(
                    expr,
                    new Expressions.StringLiteral(field.lexeme(), field.line(), field.column()),
                    field.line(), field.column()
                );
            } else if (match(TokenType.COLON)) {
                Token method = consume(TokenType.IDENTIFIER, "Expect method name after ':'");
                List<Expression> args = parseCallArguments();
                expr = new Expressions.FunctionCallExpr(expr, method.lexeme(), args, token.line(), token.column());
            } else if (peek().type() == TokenType.LPAREN || peek().type() == TokenType.LBRACE || peek().type() == TokenType.STRING_LITERAL) {
                List<Expression> args = parseCallArguments();
                expr = new Expressions.FunctionCallExpr(expr, null, args, token.line(), token.column());
            } else {
                break;
            }
        }
        return expr;
    }

    private List<Expression> parseCallArguments() {
        if (match(TokenType.LPAREN)) {
            List<Expression> args = new ArrayList<>();
            if (!check(TokenType.RPAREN)) {
                do {
                    args.add(parseExpression());
                } while (match(TokenType.COMMA));
            }
            consume(TokenType.RPAREN, "Expect ')' after function call arguments");
            return args;
        } else if (peek().type() == TokenType.LBRACE) {
            return List.of(parseTableConstructor());
        } else if (peek().type() == TokenType.STRING_LITERAL) {
            Token str = advance();
            return List.of(new Expressions.StringLiteral((String) str.literal(), str.line(), str.column()));
        }
        throw new ParseException("Expect argument list for function call", peek().line(), peek().column());
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
                yield new Expressions.StringLiteral((String) token.literal(), token.line(), token.column());
            }
            case DOT_DOT_DOT -> {
                advance();
                yield new Expressions.VarargLiteral(token.line(), token.column());
            }
            case LBRACE -> parseTableConstructor();
            case FUNCTION -> {
                advance();
                consume(TokenType.LPAREN, "Expect '(' after anonymous function");
                List<String> params = new ArrayList<>();
                boolean isVararg = parseParameterList(params);
                consume(TokenType.RPAREN, "Expect ')' after parameters");
                Statements.BlockStmt body = parse();
                consume(TokenType.END, "Expect 'end' after anonymous function body");
                yield new Expressions.FunctionDefExpr(params, isVararg, body, token.line(), token.column());
            }
            default -> throw new ParseException("Unexpected token '" + token.lexeme() + "' in expression", token.line(), token.column());
        };
    }

    private Expressions.TableConstructorExpr parseTableConstructor() {
        Token braceToken = consume(TokenType.LBRACE, "Expect '{' for table constructor");
        List<Expressions.TableField> fields = new ArrayList<>();

        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            if (match(TokenType.LBRACKET)) {
                Expression key = parseExpression();
                consume(TokenType.RBRACKET, "Expect ']' after table key");
                consume(TokenType.ASSIGN, "Expect '=' after table key");
                Expression value = parseExpression();
                fields.add(new Expressions.TableField(key, value));
            } else if (peek().type() == TokenType.IDENTIFIER && peekNext().type() == TokenType.ASSIGN) {
                Token keyToken = advance();
                advance(); // consume '='
                Expression value = parseExpression();
                fields.add(new Expressions.TableField(
                    new Expressions.StringLiteral(keyToken.lexeme(), keyToken.line(), keyToken.column()),
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
        consume(TokenType.RBRACE, "Expect '}' to close table constructor");
        return new Expressions.TableConstructorExpr(fields, braceToken.line(), braceToken.column());
    }

    private boolean parseParameterList(List<String> params) {
        if (check(TokenType.RPAREN)) {
            return false;
        }
        while (true) {
            if (match(TokenType.DOT_DOT_DOT)) {
                return true;
            }
            Token param = consume(TokenType.IDENTIFIER, "Expect parameter name");
            params.add(param.lexeme());
            if (!match(TokenType.COMMA)) {
                break;
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

    private Token consume(TokenType type, String message) {
        if (check(type)) {
            return advance();
        }
        throw new ParseException(message + ", found '" + peek().lexeme() + "'", peek().line(), peek().column());
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
        return tokens.get(current - 1);
    }

    private boolean isAtEnd() {
        return current >= tokens.size() || peek().type() == TokenType.EOF;
    }
}

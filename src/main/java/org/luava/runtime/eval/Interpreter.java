package org.luava.runtime.eval;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.TokenType;
import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class Interpreter {
    public static class ReturnSignal extends RuntimeException {
        private final LuaValue value;

        public ReturnSignal(LuaValue value) {
            super(null, null, false, false);
            this.value = value != null ? value : LuaNil.NIL;
        }

        public LuaValue getValue() {
            return value;
        }
    }

    public static class BreakSignal extends RuntimeException {
        public BreakSignal() {
            super(null, null, false, false);
        }
    }

    public LuaValue execute(Statements.BlockStmt block, Environment env) {
        try {
            executeBlock(block, env);
            return LuaNil.NIL;
        } catch (ReturnSignal r) {
            return r.getValue();
        }
    }

    public void executeBlock(Statements.BlockStmt block, Environment env) {
        try {
            for (Statement stmt : block.statements()) {
                executeStatement(stmt, env);
            }
        } finally {
            env.closeToCloseVariables();
        }
    }

    private void executeStatement(Statement stmt, Environment env) {
        switch (stmt) {
            case Statements.BlockStmt b -> {
                Environment blockEnv = new Environment(env, env.getGlobals());
                executeBlock(b, blockEnv);
            }
            case Statements.LocalVarDeclStmt decl -> {
                List<LuaValue> values = new ArrayList<>();
                for (Expression init : decl.initializers()) {
                    LuaValue res = evaluate(init, env);
                    if (res instanceof Varargs va) {
                        for (LuaValue v : va.toArray()) {
                            values.add(v);
                        }
                    } else {
                        values.add(res);
                    }
                }
                for (int i = 0; i < decl.bindings().size(); i++) {
                    Statements.LocalVarBinding binding = decl.bindings().get(i);
                    LuaValue val = i < values.size() ? values.get(i) : LuaNil.NIL;
                    boolean isClose = binding.attribute() == Statements.VariableAttribute.CLOSE;
                    boolean isConst = binding.attribute() == Statements.VariableAttribute.CONST;
                    env.defineLocal(binding.name(), val, isClose, isConst);
                }
            }
            case Statements.AssignmentStmt assign -> {
                List<LuaValue> values = new ArrayList<>();
                for (Expression valExpr : assign.values()) {
                    LuaValue res = evaluate(valExpr, env);
                    if (res instanceof Varargs va) {
                        for (LuaValue v : va.toArray()) {
                            values.add(v);
                        }
                    } else {
                        values.add(res);
                    }
                }
                for (int i = 0; i < assign.targets().size(); i++) {
                    Expression target = assign.targets().get(i);
                    LuaValue val = i < values.size() ? values.get(i) : LuaNil.NIL;
                    if (target instanceof Expressions.VariableExpr v) {
                        env.set(v.name(), val);
                    } else if (target instanceof Expressions.TableAccessExpr t) {
                        LuaValue table = evaluate(t.table(), env);
                        LuaValue key = evaluate(t.key(), env);
                        table.set(key, val);
                    } else {
                        throw new LuaException("invalid target in assignment");
                    }
                }
            }
            case Statements.IfStmt ifStmt -> {
                boolean executed = false;
                for (Statements.IfBranch branch : ifStmt.branches()) {
                    if (evaluate(branch.condition(), env).toBoolean()) {
                        Environment branchEnv = new Environment(env, env.getGlobals());
                        executeBlock(branch.block(), branchEnv);
                        executed = true;
                        break;
                    }
                }
                if (!executed && ifStmt.elseBlock() != null) {
                    Environment elseEnv = new Environment(env, env.getGlobals());
                    executeBlock(ifStmt.elseBlock(), elseEnv);
                }
            }
            case Statements.WhileStmt whileStmt -> {
                while (evaluate(whileStmt.condition(), env).toBoolean()) {
                    Environment loopEnv = new Environment(env, env.getGlobals());
                    try {
                        executeBlock(whileStmt.body(), loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    }
                }
            }
            case Statements.RepeatStmt repeatStmt -> {
                while (true) {
                    Environment loopEnv = new Environment(env, env.getGlobals());
                    try {
                        executeBlock(repeatStmt.body(), loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    }
                    if (evaluate(repeatStmt.condition(), loopEnv).toBoolean()) {
                        break;
                    }
                }
            }
            case Statements.ForNumericStmt forNum -> {
                LuaValue initVal = evaluate(forNum.start(), env);
                LuaValue limitVal = evaluate(forNum.limit(), env);
                LuaValue stepVal = forNum.step() != null ? evaluate(forNum.step(), env) : LuaInteger.valueOf(1);

                if (initVal.isInteger() && limitVal.isInteger() && stepVal.isInteger()) {
                    long current = initVal.toLong();
                    long limit = limitVal.toLong();
                    long step = stepVal.toLong();
                    if (step == 0) throw new LuaException("'for' step is zero");

                    while ((step > 0 && current <= limit) || (step < 0 && current >= limit)) {
                        Environment loopEnv = new Environment(env, env.getGlobals());
                        loopEnv.defineLocal(forNum.variableName(), LuaInteger.valueOf(current), false, false);
                        try {
                            executeBlock(forNum.body(), loopEnv);
                        } catch (BreakSignal b) {
                            break;
                        }
                        current += step;
                    }
                } else {
                    double current = initVal.toDouble();
                    double limit = limitVal.toDouble();
                    double step = stepVal.toDouble();
                    if (step == 0) throw new LuaException("'for' step is zero");

                    while ((step > 0 && current <= limit) || (step < 0 && current >= limit)) {
                        Environment loopEnv = new Environment(env, env.getGlobals());
                        loopEnv.defineLocal(forNum.variableName(), LuaFloat.valueOf(current), false, false);
                        try {
                            executeBlock(forNum.body(), loopEnv);
                        } catch (BreakSignal b) {
                            break;
                        }
                        current += step;
                    }
                }
            }
            case Statements.ForGenericStmt forGen -> {
                List<LuaValue> iterVals = new ArrayList<>();
                for (Expression iterExpr : forGen.iterators()) {
                    LuaValue res = evaluate(iterExpr, env);
                    if (res instanceof Varargs va) {
                        for (LuaValue v : va.toArray()) iterVals.add(v);
                    } else {
                        iterVals.add(res);
                    }
                }
                LuaValue iterFunc = iterVals.size() > 0 ? iterVals.get(0) : LuaNil.NIL;
                LuaValue state = iterVals.size() > 1 ? iterVals.get(1) : LuaNil.NIL;
                LuaValue var = iterVals.size() > 2 ? iterVals.get(2) : LuaNil.NIL;

                while (true) {
                    LuaValue res = iterFunc.call(state, var);
                    LuaValue[] results;
                    if (res instanceof Varargs va) {
                        results = va.toArray();
                    } else {
                        results = new LuaValue[]{res};
                    }
                    if (results.length == 0 || results[0].isNil()) {
                        break;
                    }
                    var = results[0];

                    Environment loopEnv = new Environment(env, env.getGlobals());
                    for (int i = 0; i < forGen.variableNames().size(); i++) {
                        String vName = forGen.variableNames().get(i);
                        LuaValue val = i < results.length ? results[i] : LuaNil.NIL;
                        loopEnv.defineLocal(vName, val, false, false);
                    }
                    try {
                        executeBlock(forGen.body(), loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    }
                }
            }
            case Statements.FunctionDefStmt fnStmt -> {
                LuaFunction func = createFunction(fnStmt.parameters(), fnStmt.isVararg(), fnStmt.body(), env);
                if (fnStmt.targetName() instanceof Expressions.VariableExpr v) {
                    env.set(v.name(), func);
                } else if (fnStmt.targetName() instanceof Expressions.TableAccessExpr t) {
                    LuaValue table = evaluate(t.table(), env);
                    LuaValue key = evaluate(t.key(), env);
                    table.set(key, func);
                }
            }
            case Statements.LocalFunctionDefStmt lfnStmt -> {
                // Must define local first so recursive calls capture it
                env.defineLocal(lfnStmt.name(), LuaNil.NIL, false, false);
                LuaFunction func = createFunction(lfnStmt.parameters(), lfnStmt.isVararg(), lfnStmt.body(), env);
                env.set(lfnStmt.name(), func);
            }
            case Statements.ReturnStmt ret -> {
                if (ret.values().isEmpty()) {
                    throw new ReturnSignal(LuaNil.NIL);
                }
                if (ret.values().size() == 1) {
                    throw new ReturnSignal(evaluate(ret.values().get(0), env));
                }
                List<LuaValue> vals = new ArrayList<>();
                for (Expression valExpr : ret.values()) {
                    LuaValue v = evaluate(valExpr, env);
                    if (v instanceof Varargs va) {
                        for (LuaValue item : va.toArray()) vals.add(item);
                    } else {
                        vals.add(v);
                    }
                }
                throw new ReturnSignal(Varargs.of(vals.toArray(new LuaValue[0])));
            }
            case Statements.BreakStmt b -> throw new BreakSignal();
            case Statements.ExprStmt exprStmt -> evaluate(exprStmt.expression(), env);
            default -> {}
        }
    }

    public LuaValue evaluate(Expression expr, Environment env) {
        return switch (expr) {
            case Expressions.NilLiteral n -> LuaNil.NIL;
            case Expressions.BooleanLiteral b -> LuaBoolean.valueOf(b.value());
            case Expressions.IntegerLiteral i -> LuaInteger.valueOf(i.value());
            case Expressions.FloatLiteral f -> LuaFloat.valueOf(f.value());
            case Expressions.StringLiteral s -> LuaString.valueOf(s.value());
            case Expressions.VarargLiteral v -> env.get("...");
            case Expressions.VariableExpr v -> env.get(v.name());

            case Expressions.TableAccessExpr t -> {
                LuaValue table = evaluate(t.table(), env);
                LuaValue key = evaluate(t.key(), env);
                yield table.get(key);
            }

            case Expressions.UnaryExpr u -> {
                LuaValue val = evaluate(u.operand(), env);
                yield switch (u.operator()) {
                    case NOT -> LuaBoolean.valueOf(!val.toBoolean());
                    case HASH -> val.len();
                    case MINUS -> val.unm();
                    case TILDE -> val.bnot();
                    default -> throw new LuaException("unknown unary operator " + u.operator());
                };
            }

            case Expressions.BinaryExpr b -> {
                // Short-circuit logical operators
                if (b.operator() == TokenType.AND) {
                    LuaValue left = evaluate(b.left(), env);
                    if (!left.toBoolean()) yield left;
                    yield evaluate(b.right(), env);
                }
                if (b.operator() == TokenType.OR) {
                    LuaValue left = evaluate(b.left(), env);
                    if (left.toBoolean()) yield left;
                    yield evaluate(b.right(), env);
                }

                LuaValue left = evaluate(b.left(), env);
                LuaValue right = evaluate(b.right(), env);

                yield switch (b.operator()) {
                    case PLUS -> left.add(right);
                    case MINUS -> left.sub(right);
                    case STAR -> left.mul(right);
                    case SLASH -> left.div(right);
                    case DOUBLE_SLASH -> left.idiv(right);
                    case PERCENT -> left.mod(right);
                    case CARET -> left.pow(right);
                    case AMPERSAND -> left.band(right);
                    case PIPE -> left.bor(right);
                    case TILDE -> left.bxor(right);
                    case SHL -> left.shl(right);
                    case SHR -> left.shr(right);
                    case DOT_DOT -> left.concat(right);
                    case EQUAL_EQUAL -> LuaBoolean.valueOf(left.luaEquals(right));
                    case TILDE_EQUAL -> LuaBoolean.valueOf(!left.luaEquals(right));
                    case LESS -> LuaBoolean.valueOf(left.luaLessThan(right));
                    case LESS_EQUAL -> LuaBoolean.valueOf(left.luaLessOrEqual(right));
                    case GREATER -> LuaBoolean.valueOf(!left.luaLessOrEqual(right));
                    case GREATER_EQUAL -> LuaBoolean.valueOf(!left.luaLessThan(right));
                    default -> throw new LuaException("unsupported binary operator " + b.operator());
                };
            }

            case Expressions.TableConstructorExpr t -> {
                LuaTable table = new LuaTable();
                long autoIndex = 1;
                for (Expressions.TableField field : t.fields()) {
                    if (field.key() != null) {
                        LuaValue key = evaluate(field.key(), env);
                        LuaValue val = evaluate(field.value(), env);
                        table.rawset(key, val);
                    } else {
                        LuaValue val = evaluate(field.value(), env);
                        table.rawset(LuaInteger.valueOf(autoIndex++), val);
                    }
                }
                yield table;
            }

            case Expressions.FunctionCallExpr call -> {
                LuaValue target = evaluate(call.target(), env);
                LuaFunction func;
                List<LuaValue> args = new ArrayList<>();

                if (call.methodName() != null) {
                    LuaValue method = target.get(LuaString.valueOf(call.methodName()));
                    if (!method.isFunction()) {
                        throw new LuaException("attempt to call method '" + call.methodName() + "' (a " + method.typeName() + " value)");
                    }
                    func = (LuaFunction) method;
                    args.add(target); // pass 'self'
                } else {
                    if (!target.isFunction()) {
                        throw new LuaException("attempt to call a " + target.typeName() + " value");
                    }
                    func = (LuaFunction) target;
                }

                for (Expression argExpr : call.arguments()) {
                    LuaValue argVal = evaluate(argExpr, env);
                    if (argVal instanceof Varargs va) {
                        for (LuaValue v : va.toArray()) args.add(v);
                    } else {
                        args.add(argVal);
                    }
                }
                yield func.call(args.toArray(new LuaValue[0]));
            }

            case Expressions.FunctionDefExpr fn -> createFunction(fn.parameters(), fn.isVararg(), fn.body(), env);

            default -> LuaNil.NIL;
        };
    }

    private LuaFunction createFunction(List<String> params, boolean isVararg, Statements.BlockStmt body, Environment parentEnv) {
        return new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                Environment fnEnv = new Environment(parentEnv, parentEnv.getGlobals());
                int paramCount = params.size();
                for (int i = 0; i < paramCount; i++) {
                    String paramName = params.get(i);
                    LuaValue argVal = (args != null && i < args.length) ? args[i] : LuaNil.NIL;
                    fnEnv.defineLocal(paramName, argVal, false, false);
                }
                if (isVararg && args != null && args.length > paramCount) {
                    LuaValue[] extra = new LuaValue[args.length - paramCount];
                    System.arraycopy(args, paramCount, extra, 0, extra.length);
                    fnEnv.defineLocal("...", Varargs.of(extra), false, false);
                } else if (isVararg) {
                    fnEnv.defineLocal("...", Varargs.EMPTY, false, false);
                }

                Interpreter interpreter = new Interpreter();
                try {
                    interpreter.executeBlock(body, fnEnv);
                    return LuaNil.NIL;
                } catch (ReturnSignal r) {
                    return r.getValue();
                }
            }
        };
    }
}

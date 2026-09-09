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
import org.luava.runtime.LuaType;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class Interpreter {
    public static final Interpreter INSTANCE = new Interpreter();

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

    public static class GotoSignal extends RuntimeException {
        private final String label;

        public GotoSignal(String label) {
            super(null, null, false, false);
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public LuaValue execute(Statements.BlockStmt block, Environment env) {
        try {
            executeBlock(block, env);
            int lastLine = 0;
            CallStack.Frame frame = CallStack.getFrame(0);
            if (frame != null && frame.function != null && frame.function.getLastLineDefined() > 0) {
                lastLine = frame.function.getLastLineDefined();
            } else if (block != null && block.endLine() > 0) {
                lastLine = block.endLine();
            }
            if (lastLine > 0) {
                CallStack.setLine(lastLine);
            }
            return LuaNil.NIL;
        } catch (ReturnSignal r) {
            LuaValue val = r.getValue();
            while (val instanceof TailCall tc) {
                val = tc.target.call(tc.args);
            }
            return val;
        } catch (GotoSignal g) {
            throw new LuaException("no visible label '" + g.getLabel() + "' for <goto>");
        }
    }

    public void executeBlock(Statements.BlockStmt block, Environment env) {
        List<Statement> stmts = block.statements();
        java.util.Map<String, Integer> labels = null;
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i) instanceof Statements.LabelStmt l) {
                if (labels == null) labels = new java.util.HashMap<>();
                labels.put(l.name(), i);
            }
        }

        LuaValue errorObj = LuaNil.NIL;
        CallStack.CallStackState csState = CallStack.currentState();
        CallStack.Frame curFrame = (csState != null && csState.top > 0) ? csState.stack[csState.top - 1] : null;
        Environment prevEnv = curFrame != null ? curFrame.env : null;
        if (curFrame != null) curFrame.env = env;
        try {
            int pc = 0;
            while (pc < stmts.size()) {
                Statement stmt = stmts.get(pc);
                try {
                    executeStatement(stmt, env);
                    pc++;
                } catch (GotoSignal g) {
                    if (labels != null && labels.containsKey(g.getLabel())) {
                        pc = labels.get(g.getLabel());
                    } else {
                        throw g;
                    }
                }
            }
        } catch (LuaException le) {
            errorObj = le.getErrorObject();
            throw le;
        } finally {
            if (curFrame != null) curFrame.env = prevEnv;
            if (!env.isFunctionBoundary()) {
                env.closeToCloseVariables(errorObj);
            }
        }
    }

    private void executeStatement(Statement stmt, Environment env) {
        boolean skipStmtLine = (stmt instanceof Statements.BlockStmt)
                || (stmt instanceof Statements.IfStmt)
                || (stmt instanceof Statements.RepeatStmt)
                || (stmt instanceof Statements.FunctionDefStmt)
                || (stmt instanceof Statements.LocalFunctionDefStmt)
                || (stmt instanceof Statements.AssignmentStmt assign
                    && assign.values().size() == 1
                    && assign.values().get(0) instanceof Expressions.BinaryExpr bin
                    && bin.line() > assign.line());
        CallStack.fireCountHook();
        if (!skipStmtLine) {
            CallStack.setLine(stmt.line());
        }
        try {
            switch (stmt) {
            case Statements.BlockStmt b -> {
                Environment blockEnv = new Environment(env, env.getGlobals());
                executeBlock(b, blockEnv);
            }
            case Statements.LocalVarDeclStmt decl -> {
                if (decl.bindings().size() == 1 && decl.initializers().size() == 1) {
                    Statements.LocalVarBinding binding = decl.bindings().get(0);
                    LuaValue res = evaluate(decl.initializers().get(0), env);
                    if (res instanceof Varargs va) {
                        res = va.first();
                    }
                    boolean isClose = binding.attribute() == Statements.VariableAttribute.CLOSE;
                    boolean isConst = binding.attribute() == Statements.VariableAttribute.CONST;
                    env.defineLocal(binding.name(), res, isClose, isConst);
                } else if (decl.bindings().size() == 1 && decl.initializers().isEmpty()) {
                    Statements.LocalVarBinding binding = decl.bindings().get(0);
                    boolean isClose = binding.attribute() == Statements.VariableAttribute.CLOSE;
                    boolean isConst = binding.attribute() == Statements.VariableAttribute.CONST;
                    env.defineLocal(binding.name(), LuaNil.NIL, isClose, isConst);
                } else {
                    List<LuaValue> values = new ArrayList<>(decl.bindings().size());
                    int initCount = decl.initializers().size();
                    for (int i = 0; i < initCount; i++) {
                        LuaValue res = evaluate(decl.initializers().get(i), env);
                        boolean isLast = (i == initCount - 1);
                        if (res instanceof Varargs va) {
                            if (isLast) {
                                for (LuaValue v : va.toArray()) {
                                    values.add(v);
                                }
                            } else {
                                values.add(va.first());
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
            }
            case Statements.AssignmentStmt assign -> {
                int targetCount = assign.targets().size();
                if (targetCount == 1 && assign.values().size() == 1) {
                    Expression target = assign.targets().get(0);
                    Expression valExpr = assign.values().get(0);
                    int storeLine = assign.line();
                    if (valExpr instanceof Expressions.BinaryExpr bin && bin.right().line() > 0) {
                        storeLine = bin.right().line();
                    } else if (valExpr.line() > 0) {
                        storeLine = valExpr.line();
                    }
                    if (target instanceof Expressions.VariableExpr v) {
                        LuaValue res = evaluate(valExpr, env);
                        if (res instanceof Varargs va) res = va.first();
                        if (storeLine > 0) {
                            CallStack.setLine(storeLine);
                        }
                        env.set(v.name(), res);
                    } else if (target instanceof Expressions.TableAccessExpr t) {
                        LuaValue table = evaluate(t.table(), env);
                        LuaValue key = evaluate(t.key(), env);
                        if (table instanceof Varargs va) table = va.first();
                        if (key instanceof Varargs va) key = va.first();
                        LuaValue res = evaluate(valExpr, env);
                        if (res instanceof Varargs va) res = va.first();
                        if (storeLine > 0) {
                            CallStack.setLine(storeLine);
                        }
                        try {
                            table.set(key, res);
                        } catch (LuaException le) {
                            attachDesc(le, t.table(), env);
                            throw le;
                        }
                    } else {
                        throw new LuaException("invalid target in assignment");
                    }
                } else {
                    record PreTarget(String varName, LuaValue table, LuaValue key, Expression tableExpr) {}
                    PreTarget[] preTargets = new PreTarget[targetCount];
                    for (int i = 0; i < targetCount; i++) {
                        Expression target = assign.targets().get(i);
                        if (target instanceof Expressions.VariableExpr v) {
                            preTargets[i] = new PreTarget(v.name(), null, null, null);
                        } else if (target instanceof Expressions.TableAccessExpr t) {
                            LuaValue table = evaluate(t.table(), env);
                            LuaValue key = evaluate(t.key(), env);
                            if (table instanceof Varargs va) table = va.first();
                            if (key instanceof Varargs va) key = va.first();
                            preTargets[i] = new PreTarget(null, table, key, t.table());
                        } else {
                            throw new LuaException("invalid target in assignment");
                        }
                    }

                    List<LuaValue> values = new ArrayList<>(targetCount);
                    int valCount = assign.values().size();
                    for (int i = 0; i < valCount; i++) {
                        LuaValue res = evaluate(assign.values().get(i), env);
                        boolean isLast = (i == valCount - 1);
                        if (res instanceof Varargs va) {
                            if (isLast) {
                                for (LuaValue v : va.toArray()) {
                                    values.add(v);
                                }
                            } else {
                                values.add(va.first());
                            }
                        } else {
                            values.add(res);
                        }
                    }

                    int storeLine = assign.line();
                    if (!assign.values().isEmpty()) {
                        Expression lastVal = assign.values().get(assign.values().size() - 1);
                        if (lastVal instanceof Expressions.BinaryExpr bin && bin.right().line() > 0) {
                            storeLine = bin.right().line();
                        } else if (lastVal.line() > 0) {
                            storeLine = lastVal.line();
                        }
                    }
                    if (storeLine > 0) {
                        CallStack.setLine(storeLine);
                    }

                    for (int i = 0; i < targetCount; i++) {
                        PreTarget pt = preTargets[i];
                        LuaValue val = i < values.size() ? values.get(i) : LuaNil.NIL;
                        if (pt.varName() != null) {
                            env.set(pt.varName(), val);
                        } else {
                            try {
                                pt.table().set(pt.key(), val);
                            } catch (LuaException le) {
                                attachDesc(le, pt.tableExpr(), env);
                                throw le;
                            }
                        }
                    }
                }
            }
            case Statements.IfStmt ifStmt -> {
                boolean executed = false;
                for (Statements.IfBranch branch : ifStmt.branches()) {
                    boolean cond = evaluate(branch.condition(), env).toBoolean();
                    if (branch.thenLine() > 0) {
                        CallStack.setLine(branch.thenLine());
                    }
                    if (cond) {
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
                while (true) {
                    CallStack.fireCountHook();
                    CallStack.setLine(whileStmt.condition().line() > 0 ? whileStmt.condition().line() : whileStmt.line());
                    if (!evaluate(whileStmt.condition(), env).toBoolean()) {
                        if (whileStmt.endLine() > 0) {
                            CallStack.setLine(whileStmt.endLine());
                        }
                        break;
                    }
                    Environment loopEnv = new Environment(env, env.getGlobals());
                    try {
                        executeBlock(whileStmt.body(), loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    }
                    CallStack.resetLastLine();
                }
            }
            case Statements.RepeatStmt repeatStmt -> {
                while (true) {
                    CallStack.fireCountHook();
                    Environment loopEnv = new Environment(env, env.getGlobals());
                    try {
                        executeBlock(repeatStmt.body(), loopEnv);
                    } catch (BreakSignal b) {
                        break;
                    }
                    if (repeatStmt.condition().line() > 0) {
                        CallStack.setLine(repeatStmt.condition().line());
                    }
                    if (evaluate(repeatStmt.condition(), loopEnv).toBoolean()) {
                        break;
                    }
                    CallStack.resetLastLine();
                }
            }
            case Statements.ForNumericStmt forNum -> {
                CallStack.fireCountHook();
                LuaValue initVal = evaluate(forNum.start(), env);
                CallStack.fireCountHook();
                LuaValue limitVal = evaluate(forNum.limit(), env);
                CallStack.fireCountHook();
                LuaValue stepVal = forNum.step() != null ? evaluate(forNum.step(), env) : LuaInteger.valueOf(1);

                LuaValue initNum = initVal.toLuaNumber();
                if (initNum == null) {
                    throw new LuaException("bad 'for' initial value (number expected, got " + initVal.typeName() + ")");
                }
                LuaValue limitNum = limitVal.toLuaNumber();
                if (limitNum == null) {
                    throw new LuaException("bad 'for' limit (number expected, got " + limitVal.typeName() + ")");
                }
                LuaValue stepNum = stepVal.toLuaNumber();
                if (stepNum == null) {
                    throw new LuaException("bad 'for' step (number expected, got " + stepVal.typeName() + ")");
                }

                if (initNum.isInteger() && stepNum.isInteger()) {
                    long init = initNum.toLong();
                    long step = stepNum.toLong();
                    if (step == 0) throw new LuaException("'for' step is zero");

                    long limit;
                    boolean skipLoop = false;
                    if (limitNum.isInteger()) {
                        limit = limitNum.toLong();
                    } else {
                        double flim = limitNum.toDouble();
                        if (Double.isNaN(flim)) {
                            throw new LuaException("bad 'for' limit (number expected, got nil)");
                        }
                        double rounded = (step < 0) ? Math.ceil(flim) : Math.floor(flim);
                        if (rounded >= 9223372036854775807.0) {
                            if (step < 0) skipLoop = true;
                            limit = Long.MAX_VALUE;
                        } else if (rounded <= -9223372036854775808.0) {
                            if (step > 0) skipLoop = true;
                            limit = Long.MIN_VALUE;
                        } else {
                            limit = (long) rounded;
                        }
                    }

                    CallStack.setLine(forNum.line());

                    if (step > 0 ? init > limit : init < limit) {
                        skipLoop = true;
                    }

                    if (!skipLoop) {
                        long count;
                        if (step > 0) {
                            count = Long.divideUnsigned(limit - init, step);
                        } else {
                            long ustep = -(step + 1) + 1;
                            count = Long.divideUnsigned(init - limit, ustep);
                        }

                        long current = init;
                        long c = 0;
                        CallStack.fireCountHook();
                        while (true) {
                            CallStack.fireCountHook();
                            Environment loopEnv = new Environment(env, env.getGlobals());
                            loopEnv.defineLocal(forNum.variableName(), LuaInteger.valueOf(current), false, false);
                            try {
                                executeBlock(forNum.body(), loopEnv);
                            } catch (BreakSignal b) {
                                break;
                            }
                            CallStack.setLine(forNum.line());
                            if (Long.compareUnsigned(c, count) == 0) {
                                CallStack.fireCountHook();
                                break;
                            }
                            c++;
                            current += step;
                            CallStack.resetLastLine();
                        }
                    }
                    if (forNum.endLine() > 0) {
                        CallStack.setLine(forNum.endLine());
                    }
                } else {
                    CallStack.setLine(forNum.line());

                    double current = initNum.toDouble();
                    double limit = limitNum.toDouble();
                    double step = stepNum.toDouble();
                    if (step == 0) throw new LuaException("'for' step is zero");

                    boolean inRange = (step > 0 && current <= limit) || (step < 0 && current >= limit);
                    if (inRange) {
                        CallStack.fireCountHook();
                        while (true) {
                            CallStack.fireCountHook();
                            Environment loopEnv = new Environment(env, env.getGlobals());
                            loopEnv.defineLocal(forNum.variableName(), LuaFloat.valueOf(current), false, false);
                            try {
                                executeBlock(forNum.body(), loopEnv);
                            } catch (BreakSignal b) {
                                break;
                            }
                            current += step;
                            CallStack.setLine(forNum.line());
                            boolean stillInRange = (step > 0 && current <= limit) || (step < 0 && current >= limit);
                            if (!stillInRange) {
                                CallStack.fireCountHook();
                                break;
                            }
                            CallStack.resetLastLine();
                        }
                    }
                    if (forNum.endLine() > 0) {
                        CallStack.setLine(forNum.endLine());
                    }
                }
            }
            case Statements.ForGenericStmt forGen -> {
                int iterLine = (!forGen.iterators().isEmpty() && forGen.iterators().get(0).line() > 0)
                        ? forGen.iterators().get(0).line()
                        : forGen.line();
                List<LuaValue> iterVals = new ArrayList<>();
                int iterExprCount = forGen.iterators().size();
                for (int i = 0; i < iterExprCount; i++) {
                    LuaValue res = evaluate(forGen.iterators().get(i), env);
                    boolean isLast = (i == iterExprCount - 1);
                    if (res instanceof Varargs va) {
                        if (isLast) {
                            for (LuaValue v : va.toArray()) iterVals.add(v);
                        } else {
                            iterVals.add(va.first());
                        }
                    } else {
                        iterVals.add(res);
                    }
                }
                LuaValue iterFunc = iterVals.size() > 0 ? iterVals.get(0) : LuaNil.NIL;
                LuaValue state = iterVals.size() > 1 ? iterVals.get(1) : LuaNil.NIL;
                LuaValue var = iterVals.size() > 2 ? iterVals.get(2) : LuaNil.NIL;
                LuaValue toclose = iterVals.size() > 3 ? iterVals.get(3) : LuaNil.NIL;

                Environment forEnv = new Environment(env, env.getGlobals());
                forEnv.defineLocal("(for state)", iterFunc, false, false);
                forEnv.defineLocal("(for state)", state, false, false);
                forEnv.defineLocal("(for state)", var, false, false);
                if (toclose != null && !toclose.isNil() && !toclose.equals(LuaBoolean.FALSE)) {
                    forEnv.defineLocal("(for state)", toclose, true, false);
                } else {
                    forEnv.defineLocal("(for state)", LuaNil.NIL, false, false);
                }

                LuaValue forError = LuaNil.NIL;
                try {
                    while (true) {
                        CallStack.fireCountHook();
                        CallStack.setLine(iterLine);
                        CallStack.setNextCall("for iterator", "for iterator", false, false);
                        LuaValue res;
                        if (iterFunc instanceof LuaFunction fn && !(fn instanceof InterpretedLuaFunction)) {
                            CallStack.setNextTransfer(1, 2, new LuaValue[]{state, var});
                            CallStack.push(fn, "for iterator", "for iterator", iterLine, false, false);
                            try {
                                res = iterFunc.call(state, var);
                            } catch (Throwable t) {
                                CallStack.recordErrorSnapshotIfUnprotected();
                                throw t;
                            } finally {
                                CallStack.pop();
                            }
                        } else {
                            res = iterFunc.call(state, var);
                        }
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

                        Environment loopEnv = new Environment(forEnv, forEnv.getGlobals());
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
                        CallStack.resetLastLine();
                    }
                    if (forGen.endLine() > 0) {
                        CallStack.setLine(forGen.endLine());
                    }
                } catch (LuaException le) {
                    forError = le.getErrorObject();
                    throw le;
                } finally {
                    forEnv.closeToCloseVariables(forError);
                }
            }
            case Statements.FunctionDefStmt fnStmt -> {
                // Set line to function start so errors in table target are attributed correctly
                CallStack.setLine(fnStmt.line() > 0 ? fnStmt.line() : 1);
                LuaFunction func = createFunction(fnStmt.parameters(), fnStmt.isVararg(), fnStmt.body(), env, fnStmt.line(), fnStmt.endLine());
                if (fnStmt.targetName() instanceof Expressions.VariableExpr v) {
                    func.setName(v.name());
                    env.set(v.name(), func);
                } else if (fnStmt.targetName() instanceof Expressions.TableAccessExpr t) {
                    if (t.key() instanceof Expressions.StringLiteral sl) {
                        func.setName((String) sl.value());
                    }
                    LuaValue table = evaluate(t.table(), env);
                    LuaValue key = evaluate(t.key(), env);
                    table.set(key, func);
                }
                // After successful assignment, advance to end line
                if (fnStmt.endLine() > 0) {
                    CallStack.setLine(fnStmt.endLine());
                }
            }
            case Statements.LocalFunctionDefStmt lfnStmt -> {
                CallStack.setLine(lfnStmt.endLine() > 0 ? lfnStmt.endLine() : lfnStmt.line());
                // Must define local first so recursive calls capture it
                env.defineLocal(lfnStmt.name(), LuaNil.NIL, false, false);
                LuaFunction func = createFunction(lfnStmt.parameters(), lfnStmt.isVararg(), lfnStmt.body(), env, lfnStmt.line(), lfnStmt.endLine());
                func.setName(lfnStmt.name());
                env.set(lfnStmt.name(), func);
            }
            case Statements.ReturnStmt ret -> {
                if (ret.values().isEmpty()) {
                    throw new ReturnSignal(Varargs.EMPTY);
                }
                if (ret.values().size() == 1) {
                    Expression single = ret.values().get(0);
                    if (single instanceof Expressions.FunctionCallExpr call && !env.hasToCloseInFunction()) {
                        PreparedCall prep = prepareCall(call, env);
                        CallStack.setNextCall(prep.name(), prep.namewhat(), prep.isMethod(), false);
                        throw new ReturnSignal(new TailCall(prep.callable(), prep.args()));
                    }
                    throw new ReturnSignal(evaluate(single, env));
                }
                List<LuaValue> vals = new ArrayList<>(ret.values().size());
                int retCount = ret.values().size();
                for (int i = 0; i < retCount; i++) {
                    LuaValue item = evaluate(ret.values().get(i), env);
                    boolean isLast = (i == retCount - 1);
                    if (item instanceof Varargs va) {
                        if (isLast) {
                            for (LuaValue v : va.toArray()) vals.add(v);
                        } else {
                            vals.add(va.first());
                        }
                    } else {
                        vals.add(item);
                    }
                }
                throw new ReturnSignal(Varargs.of(vals.toArray(new LuaValue[0])));
            }
            case Statements.BreakStmt b -> throw new BreakSignal();
            case Statements.GotoStmt g -> throw new GotoSignal(g.label());
            case Statements.LabelStmt l -> {}
            case Statements.ExprStmt exprStmt -> evaluate(exprStmt.expression(), env);
            default -> {}
            }
        } catch (LuaException le) {
            if (le.getLine() == -1 && stmt.line() > 0) {
                le.setLine(stmt.line());
            }
            throw le;
        }
    }

    public LuaValue evaluate(Expression expr, Environment env) {
        if (expr != null && expr.line() > 0
                && !(expr instanceof Expressions.VariableExpr)
                && !(expr instanceof Expressions.TableAccessExpr)
                && !(expr instanceof Expressions.ParenExpr)
                && !(expr instanceof Expressions.NilLiteral)
                && !(expr instanceof Expressions.BooleanLiteral)
                && !(expr instanceof Expressions.IntegerLiteral)
                && !(expr instanceof Expressions.FloatLiteral)
                && !(expr instanceof Expressions.StringLiteral)) {
            CallStack.setLine(expr.line());
        }
        return switch (expr) {
            case Expressions.NilLiteral n -> LuaNil.NIL;
            case Expressions.BooleanLiteral b -> LuaBoolean.valueOf(b.value());
            case Expressions.IntegerLiteral i -> LuaInteger.valueOf(i.value());
            case Expressions.FloatLiteral f -> LuaFloat.valueOf(f.value());
            case Expressions.StringLiteral s -> (s.luaString() != null ? s.luaString() : LuaString.valueOf(s.value()));
            case Expressions.VarargLiteral v -> env.get("...");
            case Expressions.VariableExpr v -> env.get(v.name());

            case Expressions.TableAccessExpr t -> {
                LuaValue table = evaluate(t.table(), env);
                LuaValue key = evaluate(t.key(), env);
                if (table instanceof Varargs va) table = va.first();
                if (key instanceof Varargs va) key = va.first();
                try {
                    yield table.get(key);
                } catch (LuaException le) {
                    attachDesc(le, t.table(), env);
                    throw le;
                }
            }

            case Expressions.ParenExpr p -> {
                LuaValue val = evaluate(p.expression(), env);
                if (val instanceof Varargs va) yield va.first();
                yield val;
            }

            case Expressions.UnaryExpr u -> {
                LuaValue val = evaluate(u.operand(), env);
                if (val instanceof Varargs va) val = va.first();
                try {
                    yield switch (u.operator()) {
                        case NOT -> LuaBoolean.valueOf(!val.toBoolean());
                        case HASH -> val.len();
                        case MINUS -> val.unm();
                        case TILDE -> val.bnot();
                        default -> throw new LuaException("unknown unary operator " + u.operator());
                    };
                } catch (LuaException le) {
                    attachDesc(le, u.operand(), env);
                    throw le;
                }
            }

            case Expressions.BinaryExpr b -> {
                // Short-circuit logical operators
                if (b.operator() == TokenType.AND) {
                    LuaValue left = evaluate(b.left(), env);
                    if (left instanceof Varargs va) left = va.first();
                    if (!left.toBoolean()) yield left;
                    LuaValue right = evaluate(b.right(), env);
                    if (right instanceof Varargs va) right = va.first();
                    yield right;
                }
                if (b.operator() == TokenType.OR) {
                    LuaValue left = evaluate(b.left(), env);
                    if (left instanceof Varargs va) left = va.first();
                    if (left.toBoolean()) yield left;
                    LuaValue right = evaluate(b.right(), env);
                    if (right instanceof Varargs va) right = va.first();
                    yield right;
                }

                LuaValue left = evaluate(b.left(), env);
                if (left instanceof Varargs va) left = va.first();
                if (b.line() > 0) CallStack.setLine(b.line());
                CallStack.Frame curFrame = CallStack.currentState().top > 0 ? CallStack.currentState().stack[CallStack.currentState().top - 1] : null;
                if (curFrame != null) curFrame.pushTemp(left);
                int rightLine = b.right().line() > 0 ? b.right().line() : b.line();
                if (rightLine > 0) CallStack.setLine(rightLine);
                LuaValue right;
                try {
                    right = evaluate(b.right(), env);
                } finally {
                    if (curFrame != null) left = curFrame.popTemp();
                }
                if (right instanceof Varargs va) right = va.first();
                if (b.line() > 0) CallStack.setLine(b.line());
                try {
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
                        case GREATER -> LuaBoolean.valueOf(right.luaLessThan(left));
                        case GREATER_EQUAL -> LuaBoolean.valueOf(right.luaLessOrEqual(left));
                        default -> throw new LuaException("unsupported binary operator " + b.operator());
                    };
                } catch (LuaException le) {
                    Expression badExpr;
                    if (le.getMessage() != null && le.getMessage().contains("integer representation")) {
                        badExpr = !hasIntegerRepresentation(left) ? b.left() : b.right();
                    } else {
                        badExpr = (!left.isNumber() && !left.isString()) ? b.left() : b.right();
                    }
                    attachDesc(le, badExpr, env);
                    throw le;
                }
            }

            case Expressions.TableConstructorExpr t -> {
                LuaTable table = new LuaTable();
                long autoIndex = 1;
                int fieldCount = t.fields().size();
                for (int i = 0; i < fieldCount; i++) {
                    Expressions.TableField field = t.fields().get(i);
                    boolean isLast = (i == fieldCount - 1);
                    if (field.key() != null) {
                        LuaValue key = evaluate(field.key(), env);
                        LuaValue val = evaluate(field.value(), env);
                        if (key instanceof Varargs va) key = va.first();
                        if (val instanceof Varargs va) val = va.first();
                        table.rawset(key, val);
                    } else {
                        LuaValue val = evaluate(field.value(), env);
                        if (val instanceof Varargs va) {
                            if (isLast) {
                                for (LuaValue v : va.toArray()) {
                                    table.rawset(LuaInteger.valueOf(autoIndex++), v);
                                }
                            } else {
                                table.rawset(LuaInteger.valueOf(autoIndex++), va.first());
                            }
                        } else {
                            table.rawset(LuaInteger.valueOf(autoIndex++), val);
                        }
                    }
                }
                yield table;
            }

            case Expressions.FunctionCallExpr call -> {
                PreparedCall prep = prepareCall(call, env);
                CallStack.setNextCall(prep.name(), prep.namewhat(), prep.isMethod(), false);
                if (prep.callable() instanceof LuaFunction fn && !(fn instanceof InterpretedLuaFunction)) {
                    CallStack.setNextTransfer(1, prep.args().length, prep.args());
                    CallStack.push(fn, prep.name(), prep.namewhat(), call.line(), prep.isMethod(), false);
                    CallStack.Frame frame = CallStack.currentState().top > 0 ? CallStack.currentState().stack[CallStack.currentState().top - 1] : null;
                    try {
                        LuaValue res = fn.call(prep.args());
                        if (frame != null) {
                            LuaValue[] retVals;
                            if (res instanceof Varargs va) {
                                retVals = va.toArray();
                            } else if (res == null) {
                                retVals = new LuaValue[0];
                            } else {
                                retVals = new LuaValue[]{res};
                            }
                            frame.retValues = retVals;
                            frame.ftransfer = (frame.cArgs != null && frame.cArgs.length > 0) ? (frame.cArgs.length + 1) : 1;
                            frame.ntransfer = retVals.length;
                        }
                        yield res;
                    } catch (Throwable t) {
                        CallStack.recordErrorSnapshotIfUnprotected();
                        throw t;
                    } finally {
                        CallStack.pop();
                    }
                } else {
                    yield prep.callable().call(prep.args());
                }
            }

            case Expressions.FunctionDefExpr fn -> {
                LuaFunction func = createFunction(fn.parameters(), fn.isVararg(), fn.body(), env, fn.line(), fn.endLine());
                if (fn.endLine() > fn.line()) {
                    CallStack.CallStackState cs = CallStack.currentState();
                    CallStack.Frame f = (cs != null && cs.top > 0) ? cs.stack[cs.top - 1] : null;
                    if (f != null) {
                        f.temps.add(func);
                    }
                    CallStack.setLine(fn.endLine());
                    if (f != null) {
                        f.temps.clear();
                    }
                }
                yield func;
            }

            default -> LuaNil.NIL;
        };
    }

    public record PreparedCall(LuaValue callable, LuaValue[] args, String name, String namewhat, boolean isMethod) {}

    public static final class TailCall extends LuaValue {
        public final LuaValue target;
        public final LuaValue[] args;

        public TailCall(LuaValue target, LuaValue[] args) {
            this.target = target;
            this.args = args;
        }

        @Override public LuaType type() { return LuaType.NIL; }
        @Override public String toLuaString() { return "tailcall"; }
    }

    public static final class InterpretedLuaFunction extends LuaFunction {
        final List<String> params;
        final boolean isVararg;
        final Statements.BlockStmt body;
        final Environment parentEnv;

        InterpretedLuaFunction(List<String> params, boolean isVararg, Statements.BlockStmt body, Environment parentEnv) {
            this.params = params;
            this.isVararg = isVararg;
            this.body = body;
            this.parentEnv = parentEnv;
            setBody(body);
        }

        public List<String> getParams() {
            return params;
        }

        public Environment getParentEnv() {
            return parentEnv;
        }

        @Override
        public String getRawSource() {
            if (rawSource == null && body != null) {
                rawSource = org.luava.frontend.ast.AstPrinter.print(body);
            }
            return rawSource;
        }

        @Override
        public LuaValue invoke(LuaValue... initialArgs) {
            boolean pushed = false;
            Environment activeEnv = null;
            try {
                List<String> curParams = params;
                boolean curIsVararg = isVararg;
                Statements.BlockStmt curBody = body;
                Environment curParentEnv = parentEnv;
                InterpretedLuaFunction curFn = this;
                LuaValue[] args = initialArgs;

                while (true) {
                    Environment baseEnv = curParentEnv;
                    if (curFn.getUpvalues() != null && !curFn.getUpvalues().isEmpty()) {
                        baseEnv = new Environment(curParentEnv, curParentEnv != null ? curParentEnv.getGlobals() : null, false);
                        for (org.luava.runtime.eval.Upvalue up : curFn.getUpvalues()) {
                            baseEnv.defineSlot(up.getName(), up.getSlot());
                        }
                    }
                    Environment fnEnv = new Environment(baseEnv, curParentEnv != null ? curParentEnv.getGlobals() : null, true);
                    activeEnv = fnEnv;
                    int paramCount = curParams.size();
                    for (int i = 0; i < paramCount; i++) {
                        String paramName = curParams.get(i);
                        LuaValue argVal = (args != null && i < args.length) ? args[i] : LuaNil.NIL;
                        fnEnv.defineLocal(paramName, argVal, false, false);
                    }
                    if (curIsVararg && args != null && args.length > paramCount) {
                        LuaValue[] extra = new LuaValue[args.length - paramCount];
                        System.arraycopy(args, paramCount, extra, 0, extra.length);
                        fnEnv.setVarargsArray(extra);
                        fnEnv.defineLocal("...", Varargs.of(extra), false, false);
                    } else if (curIsVararg) {
                        fnEnv.setVarargsArray(new LuaValue[0]);
                        fnEnv.defineLocal("...", Varargs.EMPTY, false, false);
                    }

                    CallStack.setNextTransfer(1, curParams.size(), null, fnEnv);
                    if (!pushed) {
                        CallStack.push(curFn, curFn.getName(), curFn.getLineDefined());
                        pushed = true;
                    } else {
                        CallStack.replaceTailCall(curFn, curFn.getName(), curFn.getLineDefined());
                    }

                    CallStack.CallStackState invokeCsState = CallStack.currentState();
                    CallStack.Frame invokeFrame = (invokeCsState != null && invokeCsState.top > 0) ? invokeCsState.stack[invokeCsState.top - 1] : null;

                    try {
                        Interpreter.INSTANCE.executeBlock(curBody, fnEnv);
                        int exitLine = curFn.getLastLineDefined() > 0 ? curFn.getLastLineDefined() : (curBody != null ? curBody.endLine() : 0);
                        if (exitLine > 0) {
                            CallStack.setLine(exitLine);
                        }
                        fnEnv.closeToCloseVariables(LuaNil.NIL);
                        if (invokeFrame != null) {
                            invokeFrame.retValues = new LuaValue[0];
                            invokeFrame.ftransfer = curParams.size() + 1;
                            invokeFrame.ntransfer = 0;
                        }
                        return Varargs.EMPTY;
                    } catch (ReturnSignal r) {
                        LuaValue retVal = r.getValue();
                        if (retVal instanceof TailCall tc) {
                            fnEnv.closeToCloseVariables(LuaNil.NIL);
                            LuaValue target = tc.target;
                            LuaValue[] tcArgs = tc.args;
                            while (!(target instanceof LuaFunction)) {
                                LuaTable mt = target.getMetatable();
                                if (mt != null) {
                                    LuaValue handler = mt.rawget(LuaString.valueOf("__call"));
                                    if (!handler.isNil()) {
                                        LuaValue[] nextArgs = new LuaValue[tcArgs.length + 1];
                                        nextArgs[0] = target;
                                        System.arraycopy(tcArgs, 0, nextArgs, 1, tcArgs.length);
                                        target = handler;
                                        tcArgs = nextArgs;
                                        continue;
                                    }
                                }
                                if (target instanceof org.luava.runtime.LuaUserdata ud) {
                                    LuaValue targetUd = ud;
                                    target = LuaFunction.of(args_ -> targetUd.call(args_));
                                    break;
                                }
                                break;
                            }
                            if (target instanceof InterpretedLuaFunction nextInterp) {
                                curFn = nextInterp;
                                curParams = nextInterp.params;
                                curIsVararg = nextInterp.isVararg;
                                curBody = nextInterp.body;
                                curParentEnv = nextInterp.parentEnv;
                                args = tcArgs;
                                continue;
                            } else {
                                if (target instanceof LuaFunction fn) {
                                    CallStack.setNextTransfer(1, tcArgs.length, tcArgs);
                                    CallStack.replaceTailCall(fn, fn.getName(), -1);
                                    CallStack.Frame cFrame = CallStack.currentState().top > 0 ? CallStack.currentState().stack[CallStack.currentState().top - 1] : null;
                                    try {
                                        LuaValue res = fn.call(tcArgs);
                                        while (res instanceof TailCall nextTc) {
                                            res = nextTc.target.call(nextTc.args);
                                        }
                                        if (cFrame != null) {
                                            LuaValue[] cRetVals;
                                            if (res instanceof Varargs va) {
                                                cRetVals = va.toArray();
                                            } else if (res == null) {
                                                cRetVals = new LuaValue[0];
                                            } else {
                                                cRetVals = new LuaValue[]{res};
                                            }
                                            cFrame.retValues = cRetVals;
                                            cFrame.ftransfer = (cFrame.cArgs != null && cFrame.cArgs.length > 0) ? (cFrame.cArgs.length + 1) : 1;
                                            cFrame.ntransfer = cRetVals.length;
                                        }
                                        if (invokeFrame != null) {
                                            LuaValue[] retVals;
                                            if (res instanceof Varargs va) {
                                                retVals = va.toArray();
                                            } else if (res == null) {
                                                retVals = new LuaValue[0];
                                            } else {
                                                retVals = new LuaValue[]{res};
                                            }
                                            invokeFrame.retValues = retVals;
                                            invokeFrame.ftransfer = curParams.size() + 1;
                                            invokeFrame.ntransfer = retVals.length;
                                        }
                                        return res;
                                    } catch (Throwable t) {
                                        CallStack.recordErrorSnapshotIfUnprotected();
                                        throw t;
                                    }
                                } else {
                                    LuaValue res = target.call(tcArgs);
                                    while (res instanceof TailCall nextTc) {
                                        res = nextTc.target.call(nextTc.args);
                                    }
                                    if (invokeFrame != null) {
                                        LuaValue[] retVals;
                                        if (res instanceof Varargs va) {
                                            retVals = va.toArray();
                                        } else if (res == null) {
                                            retVals = new LuaValue[0];
                                        } else {
                                            retVals = new LuaValue[]{res};
                                        }
                                        invokeFrame.retValues = retVals;
                                        invokeFrame.ftransfer = curParams.size() + 1;
                                        invokeFrame.ntransfer = retVals.length;
                                    }
                                    return res;
                                }
                            }
                        }
                        fnEnv.closeToCloseVariables(LuaNil.NIL);
                        if (invokeFrame != null) {
                            LuaValue[] retVals;
                            if (retVal instanceof Varargs va) {
                                retVals = va.toArray();
                            } else if (retVal == null) {
                                retVals = new LuaValue[0];
                            } else {
                                retVals = new LuaValue[]{retVal};
                            }
                            invokeFrame.retValues = retVals;
                            invokeFrame.ftransfer = curParams.size() + 1;
                            invokeFrame.ntransfer = retVals.length;
                        }
                        return retVal;
                    }
                }
            } catch (LuaException le) {
                if (CallStack.isHandlingError()) {
                    throw new LuaException("error in error handling");
                }
                if (!le.isDecorated() && le.getErrorObject() != null && le.getErrorObject().isString()) {
                    String msg = le.getErrorObject().toLuaString();
                    int line;
                    String source;
                    if (isStripped()) {
                        line = -1;
                        source = "=?";
                    } else {
                        CallStack.Frame frame = CallStack.getFrame(0);
                        line = (frame != null && frame.line > 0) ? frame.line : (le.getLine() > 0 ? le.getLine() : 1);
                        source = (getSource() != null) ? getSource() : "=(load)";
                    }
                    le.setMessage(org.luava.frontend.parser.ParseException.formatChunkName(source) + ":" + line + ": " + msg);
                    le.setDecorated(true);
                }
                if (CallStack.canHandleError()) {
                    org.luava.runtime.LuaValue res = CallStack.runErrorHandler(le.getErrorObject());
                    throw new LuaUnwindException(res, le.getErrorObject());
                }
                CallStack.recordErrorSnapshotIfUnprotected();
                if (pushed) {
                    CallStack.pop();
                    pushed = false;
                }
                if (activeEnv != null) {
                    activeEnv.closeToCloseVariables(le.getErrorObject());
                }
                throw le;
            } catch (StackOverflowError soe) {
                if (CallStack.isHandlingError()) {
                    throw new LuaException("error in error handling");
                }
                CallStack.Frame frame = CallStack.getFrame(0);
                int line = (frame != null && frame.line > 0) ? frame.line : 1;
                String source = (getSource() != null) ? getSource() : "=(load)";
                LuaException le = new LuaException("stack overflow");
                le.setMessage(org.luava.frontend.parser.ParseException.formatChunkName(source) + ":" + line + ": stack overflow");
                le.setDecorated(true);
                if (CallStack.canHandleError()) {
                    org.luava.runtime.LuaValue res = CallStack.runErrorHandler(le.getErrorObject());
                    throw new LuaUnwindException(res, le.getErrorObject());
                }
                CallStack.recordErrorSnapshotIfUnprotected();
                if (pushed) {
                    CallStack.pop();
                    pushed = false;
                }
                if (activeEnv != null) {
                    activeEnv.closeToCloseVariables(le.getErrorObject());
                }
                throw le;
            } catch (LuaUnwindException ue) {
                if (pushed) {
                    CallStack.pop();
                    pushed = false;
                }
                if (activeEnv != null) {
                    activeEnv.closeToCloseVariables(ue.getOriginalError());
                }
                throw ue;
            } finally {
                if (pushed) {
                    if (activeEnv != null) {
                        activeEnv.closeToCloseVariables(LuaNil.NIL);
                    }
                    CallStack.pop();
                    pushed = false;
                }
            }
        }
    }

    private String getDescriptor(Expression expr, Environment env) {
        CallStack.Frame frame = CallStack.getFrame(0);
        if (frame != null && frame.function != null && frame.function.isStripped()) {
            return null;
        }
        if (expr instanceof Expressions.VariableExpr v) {
            if (env.findSlot(v.name()) != null) {
                if (env.isUpvalue(v.name())) {
                    return "(upvalue '" + v.name() + "')";
                }
                return "(local '" + v.name() + "')";
            }
            return "(global '" + v.name() + "')";
        } else if (expr instanceof Expressions.TableAccessExpr t) {
            if (t.key() instanceof Expressions.StringLiteral s) {
                return "(field '" + s.value() + "')";
            }
        }
        return null;
    }

    private boolean hasIntegerRepresentation(LuaValue v) {
        if (v instanceof LuaInteger) return true;
        if (v instanceof LuaFloat f) {
            double d = f.toDouble();
            return d >= -9223372036854775808.0 && d < 9223372036854775808.0 &&
                   Math.floor(d) == d && !Double.isInfinite(d) && !Double.isNaN(d);
        }
        return false;
    }

    private void attachDesc(LuaException le, Expression expr, Environment env) {
        String msg = le.getMessage();
        if (msg != null && (msg.endsWith("value") || msg.endsWith("representation")) && !msg.contains("(")) {
            String desc = getDescriptor(expr, env);
            if (desc != null) {
                if (msg.startsWith("number has no integer representation")) {
                    le.setMessage("number " + desc + " has no integer representation");
                } else {
                    le.setMessage(msg + " " + desc);
                }
            }
        }
    }

    private PreparedCall prepareCall(Expressions.FunctionCallExpr call, Environment env) {
        LuaValue target = evaluate(call.target(), env);
        if (target instanceof Varargs va) target = va.first();
        LuaValue callable;
        List<LuaValue> args = new ArrayList<>(call.arguments().size() + 1);

        String funcName;
        String namewhat;
        boolean isMethod = false;

        if (call.methodName() != null) {
            try {
                callable = target.get(LuaString.valueOf(call.methodName()));
            } catch (LuaException le) {
                attachDesc(le, call.target(), env);
                throw le;
            }
            args.add(target);
            funcName = call.methodName();
            namewhat = "method";
            isMethod = true;
        } else {
            callable = target;
            if (call.target() instanceof Expressions.VariableExpr v) {
                funcName = v.name();
                if (env.findSlot(v.name()) != null) {
                    if (env.isUpvalue(v.name())) {
                        namewhat = "upvalue";
                    } else {
                        namewhat = "local";
                    }
                } else {
                    namewhat = "global";
                }
            } else if (call.target() instanceof Expressions.TableAccessExpr t && t.key() instanceof Expressions.StringLiteral s) {
                funcName = s.value();
                namewhat = "field";
            } else {
                funcName = null;
                namewhat = null;
            }
        }

        int argCount = call.arguments().size();
        for (int i = 0; i < argCount; i++) {
            LuaValue argVal = evaluate(call.arguments().get(i), env);
            boolean isLast = (i == argCount - 1);
            if (argVal instanceof Varargs va) {
                if (isLast) {
                    for (LuaValue v : va.toArray()) args.add(v);
                } else {
                    args.add(va.first());
                }
            } else {
                args.add(argVal);
            }
        }
        LuaValue[] callArgs = args.toArray(new LuaValue[0]);
        while (!(callable instanceof LuaFunction)) {
            LuaTable mt = callable.getMetatable();
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__call"));
                if (!handler.isNil()) {
                    LuaValue[] nextArgs = new LuaValue[callArgs.length + 1];
                    nextArgs[0] = callable;
                    System.arraycopy(callArgs, 0, nextArgs, 1, callArgs.length);
                    callable = handler;
                    callArgs = nextArgs;
                    funcName = "__call";
                    namewhat = "metamethod";
                    isMethod = false;
                    continue;
                }
            }
            if (callable instanceof org.luava.runtime.LuaUserdata ud) {
                LuaValue targetUd = ud;
                callable = LuaFunction.of(args_ -> targetUd.call(args_));
                break;
            }
            String desc = (call.methodName() != null) ? "(method '" + call.methodName() + "')" : getDescriptor(call.target(), env);
            String msg = "attempt to call a " + callable.typeName() + " value" + (desc != null ? " " + desc : "");
            throw new LuaException(msg);
        }
        return new PreparedCall(callable, callArgs, funcName, namewhat, isMethod);
    }

    private LuaFunction createFunction(List<String> params, boolean isVararg, Statements.BlockStmt body, Environment parentEnv, int lineDefined, int endLine) {
        InterpretedLuaFunction fn = new InterpretedLuaFunction(params, isVararg, body, parentEnv);
        fn.setParams(params);
        fn.setNparams(params != null ? params.size() : 0);
        fn.setVararg(isVararg);
        fn.setWhat("Lua");
        fn.setLineDefined(lineDefined > 0 ? lineDefined : body.line());
        fn.setLastLineDefined(endLine > 0 ? endLine : body.line());

        CallStack.Frame currentFrame = CallStack.getFrame(0);
        if (currentFrame != null && currentFrame.function != null) {
            fn.setSource(currentFrame.function.getSource());
            fn.setStripped(currentFrame.function.isStripped());
        }

        if (parentEnv != null && parentEnv.hasAnyLocalSlot()) {
            List<String> upNames = UpvalueScanner.scan(body, params, parentEnv);
            for (String upName : upNames) {
                Environment.VariableSlot slot = parentEnv.findSlot(upName);
                if (slot == null) {
                    slot = new Environment.VariableSlot(parentEnv.get(upName), false, false);
                    parentEnv.defineSlot(upName, slot);
                }
                fn.getUpvalues().add(new Upvalue(upName, slot));
            }
        }
        org.luava.runtime.eval.GCManager.onAlloc();
        return fn;
    }

    public LuaFunction createFunctionFromDump(List<String> params, boolean isVararg, Statements.BlockStmt body, Environment parentEnv, List<Upvalue> upvalues, String rawSource) {
        InterpretedLuaFunction fn = new InterpretedLuaFunction(params, isVararg, body, parentEnv);
        fn.setUpvalues(upvalues);
        fn.setRawSource(rawSource);
        fn.setNparams(params != null ? params.size() : 0);
        fn.setVararg(isVararg);
        fn.setWhat("Lua");
        return fn;
    }

    public LuaFunction createMainChunk(Statements.BlockStmt body, Environment rootEnv, LuaValue envVal, LuaTable globals, String chunkName, String rawSource) {
        LuaTable chunkGlobals = (envVal instanceof LuaTable t) ? t : globals;
        Environment chunkParentEnv = new Environment(rootEnv, chunkGlobals, false);
        Environment.VariableSlot envSlot = new Environment.VariableSlot(envVal != null ? envVal : globals, false, false);
        chunkParentEnv.defineSlot("_ENV", envSlot);

        InterpretedLuaFunction fn = new InterpretedLuaFunction(java.util.Collections.emptyList(), true, body, chunkParentEnv);
        fn.getUpvalues().add(new Upvalue("_ENV", envSlot));
        fn.setBody(body);
        fn.setLineDefined(0);
        fn.setLastLineDefined(0);
        fn.setWhat("main");
        fn.setName(null);
        fn.setSource(chunkName != null ? chunkName : rawSource);
        fn.setRawSource(rawSource);
        fn.setVararg(true);
        return fn;
    }
}

package org.luava.runtime.eval;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;

import java.util.*;

public final class UpvalueScanner {
    private static final Map<Statements.BlockStmt, List<String>> CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private UpvalueScanner() {}

    public static List<String> scan(Statements.BlockStmt body, List<String> params, Environment parentEnv) {
        if (body == null) return Collections.emptyList();
        List<String> freeVars = CACHE.computeIfAbsent(body, b -> computeFreeVars(b, params));
        List<String> upvalueNames = new ArrayList<>(freeVars.size());
        Set<String> seen = new HashSet<>(freeVars.size() * 2);
        for (String name : freeVars) {
            String upName = (parentEnv != null && parentEnv.findSlot(name) != null) ? name : "_ENV";
            if (seen.add(upName)) {
                upvalueNames.add(upName);
            }
        }
        return upvalueNames;
    }

    private static List<String> computeFreeVars(Statements.BlockStmt body, List<String> params) {
        List<String> freeVars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> locals = new HashSet<>(params != null ? params : Collections.emptyList());
        scanBlockFree(body, locals, freeVars, seen);
        return freeVars;
    }

    private static void scanBlockFree(Statements.BlockStmt block, Set<String> locals, List<String> freeVars, Set<String> seen) {
        if (block == null) return;
        Set<String> blockLocals = new HashSet<>(locals);
        for (Statement stmt : block.statements()) {
            scanStatementFree(stmt, blockLocals, freeVars, seen);
        }
    }

    private static void scanStatementFree(Statement stmt, Set<String> locals, List<String> freeVars, Set<String> seen) {
        switch (stmt) {
            case Statements.LocalVarDeclStmt l -> {
                for (Expression init : l.initializers()) {
                    scanExpressionFree(init, locals, freeVars, seen);
                }
                for (Statements.LocalVarBinding b : l.bindings()) {
                    locals.add(b.name());
                }
            }
            case Statements.LocalFunctionDefStmt lf -> {
                locals.add(lf.name());
                Set<String> fnLocals = new HashSet<>(locals);
                fnLocals.addAll(lf.parameters());
                scanBlockFree(lf.body(), fnLocals, freeVars, seen);
            }
            case Statements.FunctionDefStmt f -> {
                scanExpressionFree(f.targetName(), locals, freeVars, seen);
                Set<String> fnLocals = new HashSet<>(locals);
                fnLocals.addAll(f.parameters());
                scanBlockFree(f.body(), fnLocals, freeVars, seen);
            }
            case Statements.AssignmentStmt a -> {
                for (Expression target : a.targets()) {
                    scanExpressionFree(target, locals, freeVars, seen);
                }
                for (Expression val : a.values()) {
                    scanExpressionFree(val, locals, freeVars, seen);
                }
            }
            case Statements.IfStmt ifs -> {
                for (Statements.IfBranch branch : ifs.branches()) {
                    scanExpressionFree(branch.condition(), locals, freeVars, seen);
                    scanBlockFree(branch.block(), locals, freeVars, seen);
                }
                if (ifs.elseBlock() != null) {
                    scanBlockFree(ifs.elseBlock(), locals, freeVars, seen);
                }
            }
            case Statements.WhileStmt w -> {
                scanExpressionFree(w.condition(), locals, freeVars, seen);
                scanBlockFree(w.body(), locals, freeVars, seen);
            }
            case Statements.RepeatStmt r -> {
                scanBlockFree(r.body(), locals, freeVars, seen);
                scanExpressionFree(r.condition(), locals, freeVars, seen);
            }
            case Statements.ForNumericStmt fn -> {
                scanExpressionFree(fn.start(), locals, freeVars, seen);
                scanExpressionFree(fn.limit(), locals, freeVars, seen);
                if (fn.step() != null) scanExpressionFree(fn.step(), locals, freeVars, seen);
                Set<String> forLocals = new HashSet<>(locals);
                forLocals.add(fn.variableName());
                scanBlockFree(fn.body(), forLocals, freeVars, seen);
            }
            case Statements.ForGenericStmt fg -> {
                for (Expression it : fg.iterators()) {
                    scanExpressionFree(it, locals, freeVars, seen);
                }
                Set<String> forLocals = new HashSet<>(locals);
                forLocals.addAll(fg.variableNames());
                scanBlockFree(fg.body(), forLocals, freeVars, seen);
            }
            case Statements.ReturnStmt ret -> {
                for (Expression v : ret.values()) {
                    scanExpressionFree(v, locals, freeVars, seen);
                }
            }
            case Statements.ExprStmt es -> scanExpressionFree(es.expression(), locals, freeVars, seen);
            case Statements.BlockStmt b -> scanBlockFree(b, locals, freeVars, seen);
            default -> {}
        }
    }

    private static void scanExpressionFree(Expression expr, Set<String> locals, List<String> freeVars, Set<String> seen) {
        if (expr == null) return;
        switch (expr) {
            case Expressions.VariableExpr v -> {
                String name = v.name();
                if (!locals.contains(name)) {
                    if (seen.add(name)) {
                        freeVars.add(name);
                    }
                }
            }
            case Expressions.BinaryExpr b -> {
                scanExpressionFree(b.left(), locals, freeVars, seen);
                scanExpressionFree(b.right(), locals, freeVars, seen);
            }
            case Expressions.UnaryExpr u -> scanExpressionFree(u.operand(), locals, freeVars, seen);
            case Expressions.TableConstructorExpr t -> {
                for (Expressions.TableField f : t.fields()) {
                    if (f.key() != null) scanExpressionFree(f.key(), locals, freeVars, seen);
                    scanExpressionFree(f.value(), locals, freeVars, seen);
                }
            }
            case Expressions.TableAccessExpr ta -> {
                scanExpressionFree(ta.table(), locals, freeVars, seen);
                scanExpressionFree(ta.key(), locals, freeVars, seen);
            }
            case Expressions.FunctionCallExpr fc -> {
                scanExpressionFree(fc.target(), locals, freeVars, seen);
                for (Expression arg : fc.arguments()) {
                    scanExpressionFree(arg, locals, freeVars, seen);
                }
            }
            case Expressions.FunctionDefExpr fd -> {
                Set<String> fnLocals = new HashSet<>(locals);
                fnLocals.addAll(fd.parameters());
                scanBlockFree(fd.body(), fnLocals, freeVars, seen);
            }
            case Expressions.ParenExpr p -> scanExpressionFree(p.expression(), locals, freeVars, seen);
            default -> {}
        }
    }
}

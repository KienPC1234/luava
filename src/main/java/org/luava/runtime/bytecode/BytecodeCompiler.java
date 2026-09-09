package org.luava.runtime.bytecode;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.TokenType;
import org.luava.runtime.*;

import java.util.*;

public final class BytecodeCompiler {

    public static LuaProto compile(Statements.BlockStmt block, String source) {
        FuncState fs = new FuncState(null, source, 0, 0, true, 0);
        // Upvalue 0 for main chunk is _ENV
        fs.upvalues.add(new UpvalueDesc("_ENV", true, 0));
        fs.upvalMap.put("_ENV", 0);

        fs.compileBlock(block);
        fs.emit(Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0), block != null ? block.endLine() : 1);
        return fs.toProto();
    }

    private static final class LocalVar {
        final String name;
        final int reg;
        final Statements.VariableAttribute attr;
        int startPc;
        int endPc;

        LocalVar(String name, int reg, Statements.VariableAttribute attr) {
            this.name = name;
            this.reg = reg;
            this.attr = attr;
        }
    }

    private static final class LoopInfo {
        final int startPc;
        final List<Integer> breakList = new ArrayList<>();
        final int baseLocals;

        LoopInfo(int startPc, int baseLocals) {
            this.startPc = startPc;
            this.baseLocals = baseLocals;
        }
    }

    private static final class FuncState {
        final FuncState parent;
        final String source;
        final int lineDefined;
        final int lastLineDefined;
        final boolean isVararg;
        final int numParams;

        final List<Integer> code = new ArrayList<>();
        final List<Integer> lineInfo = new ArrayList<>();
        final List<LuaValue> constants = new ArrayList<>();
        final Map<LuaValue, Integer> constMap = new HashMap<>();
        final List<LuaProto> protos = new ArrayList<>();
        final List<UpvalueDesc> upvalues = new ArrayList<>();
        final Map<String, Integer> upvalMap = new HashMap<>();

        final List<LocalVar> locals = new ArrayList<>();
        final Deque<LoopInfo> loopStack = new ArrayDeque<>();
        int freereg = 0;
        int maxstacksize = 2;

        FuncState(FuncState parent, String source, int lineDefined, int lastLineDefined, boolean isVararg, int numParams) {
            this.parent = parent;
            this.source = source != null ? source : "=?";
            this.lineDefined = lineDefined;
            this.lastLineDefined = lastLineDefined;
            this.isVararg = isVararg;
            this.numParams = numParams;
        }

        int emit(int inst, int line) {
            int pc = code.size();
            code.add(inst);
            lineInfo.add(line > 0 ? line : (lineInfo.isEmpty() ? 1 : lineInfo.get(lineInfo.size() - 1)));
            return pc;
        }

        int emitJmp(int line) {
            return emit(Instruction.encodesJ(OpCode.OP_JMP, 0), line);
        }

        void patchJmp(int jmpPc, int targetPc) {
            int offset = targetPc - (jmpPc + 1);
            int inst = code.get(jmpPc);
            code.set(jmpPc, Instruction.setsJ(inst, offset));
        }

        int allocReg() {
            int r = freereg++;
            if (freereg > maxstacksize) {
                maxstacksize = freereg;
            }
            return r;
        }

        void freeRegs(int toReg) {
            if (freereg > toReg) {
                freereg = toReg;
            }
        }

        int addConst(LuaValue v) {
            Integer idx = constMap.get(v);
            if (idx != null) {
                return idx;
            }
            int newIdx = constants.size();
            constants.add(v);
            constMap.put(v, newIdx);
            return newIdx;
        }

        LocalVar findLocal(String name) {
            for (int i = locals.size() - 1; i >= 0; i--) {
                LocalVar v = locals.get(i);
                if (v.name.equals(name)) {
                    return v;
                }
            }
            return null;
        }

        int findOrAddUpval(String name) {
            Integer idx = upvalMap.get(name);
            if (idx != null) {
                return idx;
            }
            if (parent == null) {
                return -1;
            }
            // Check if it's local in parent
            LocalVar parentLocal = parent.findLocal(name);
            if (parentLocal != null) {
                int newIdx = upvalues.size();
                upvalues.add(new UpvalueDesc(name, true, parentLocal.reg));
                upvalMap.put(name, newIdx);
                return newIdx;
            }
            // Check if parent has it as upvalue
            int parentUpval = parent.findOrAddUpval(name);
            if (parentUpval >= 0) {
                int newIdx = upvalues.size();
                upvalues.add(new UpvalueDesc(name, false, parentUpval));
                upvalMap.put(name, newIdx);
                return newIdx;
            }
            return -1;
        }

        void compileBlock(Statements.BlockStmt block) {
            if (block == null) return;
            int baseLocals = locals.size();
            int baseFreereg = freereg;

            for (Statement stmt : block.statements()) {
                compileStatement(stmt);
            }

            closeLocalsTo(baseLocals, block.endLine());
            freeRegs(baseFreereg);
        }

        void closeLocalsTo(int baseLocals, int line) {
            boolean hasClose = false;
            int firstReg = Integer.MAX_VALUE;
            for (int i = locals.size() - 1; i >= baseLocals; i--) {
                LocalVar v = locals.remove(i);
                if (v.attr == Statements.VariableAttribute.CLOSE) {
                    hasClose = true;
                }
                if (v.reg < firstReg) {
                    firstReg = v.reg;
                }
            }
            if (hasClose && firstReg != Integer.MAX_VALUE) {
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, firstReg, 0, 0), line);
            }
        }

        void compileStatement(Statement stmt) {
            if (stmt instanceof Statements.BlockStmt b) {
                compileBlock(b);
            } else if (stmt instanceof Statements.LocalVarDeclStmt lvd) {
                compileLocalVarDecl(lvd);
            } else if (stmt instanceof Statements.AssignmentStmt as) {
                compileAssignment(as);
            } else if (stmt instanceof Statements.ExprStmt es) {
                compileExprStmt(es);
            } else if (stmt instanceof Statements.IfStmt is) {
                compileIf(is);
            } else if (stmt instanceof Statements.WhileStmt ws) {
                compileWhile(ws);
            } else if (stmt instanceof Statements.RepeatStmt rs) {
                compileRepeat(rs);
            } else if (stmt instanceof Statements.ForNumericStmt fns) {
                compileForNumeric(fns);
            } else if (stmt instanceof Statements.ForGenericStmt fgs) {
                compileForGeneric(fgs);
            } else if (stmt instanceof Statements.ReturnStmt ret) {
                compileReturn(ret);
            } else if (stmt instanceof Statements.BreakStmt bs) {
                compileBreak(bs);
            } else if (stmt instanceof Statements.FunctionDefStmt fds) {
                compileFunctionDef(fds);
            } else if (stmt instanceof Statements.LocalFunctionDefStmt lfds) {
                compileLocalFunctionDef(lfds);
            }
        }

        void compileLocalVarDecl(Statements.LocalVarDeclStmt lvd) {
            int nvars = lvd.bindings().size();
            int nvals = lvd.initializers().size();
            int startReg = freereg;

            for (int i = 0; i < nvars; i++) {
                int r = allocReg();
                if (i < nvals) {
                    Expression init = lvd.initializers().get(i);
                    compileExprToReg(init, r);
                } else {
                    emit(Instruction.encodeABC(OpCode.OP_LOADNIL, r, 0, 0), lvd.line());
                }
                Statements.LocalVarBinding b = lvd.bindings().get(i);
                LocalVar lv = new LocalVar(b.name(), r, b.attribute());
                locals.add(lv);
                if (b.attribute() == Statements.VariableAttribute.CLOSE) {
                    emit(Instruction.encodeABC(OpCode.OP_TBC, r, 0, 0), lvd.line());
                }
            }
        }

        void compileAssignment(Statements.AssignmentStmt as) {
            int nvars = as.targets().size();
            int nvals = as.values().size();
            int[] valRegs = new int[nvars];
            int saveFreereg = freereg;

            for (int i = 0; i < nvars; i++) {
                valRegs[i] = allocReg();
                if (i < nvals) {
                    compileExprToReg(as.values().get(i), valRegs[i]);
                } else {
                    emit(Instruction.encodeABC(OpCode.OP_LOADNIL, valRegs[i], 0, 0), as.line());
                }
            }

            for (int i = 0; i < nvars; i++) {
                assignTarget(as.targets().get(i), valRegs[i], as.line());
            }

            freeRegs(saveFreereg);
        }

        void assignTarget(Expression target, int valReg, int line) {
            if (target instanceof Expressions.VariableExpr ve) {
                LocalVar lv = findLocal(ve.name());
                if (lv != null) {
                    emit(Instruction.encodeABC(OpCode.OP_MOVE, lv.reg, valReg, 0), line);
                    return;
                }
                int up = findOrAddUpval(ve.name());
                if (up >= 0) {
                    emit(Instruction.encodeABC(OpCode.OP_SETUPVAL, valReg, up, 0), line);
                    return;
                }
                // Assign to global via _ENV
                int envUp = findOrAddUpval("_ENV");
                int kKey = addConst(LuaString.valueOf(ve.name()));
                if (kKey <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_SETTABUP, envUp, kKey, valReg, 0), line);
                } else {
                    int kReg = allocReg();
                    emit(Instruction.encodeABx(OpCode.OP_LOADK, kReg, kKey), line);
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, envUp, kReg, valReg, 0), line);
                    freeRegs(kReg);
                }
            } else if (target instanceof Expressions.TableAccessExpr tae) {
                int tblReg = compileExprToAnyReg(tae.table());
                int keyReg = compileExprToAnyReg(tae.key());
                emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tblReg, keyReg, valReg, 0), line);
            }
        }



        void compileIf(Statements.IfStmt is) {
            List<Integer> exitJmps = new ArrayList<>();
            for (Statements.IfBranch branch : is.branches()) {
                int condReg = compileExprToAnyReg(branch.condition());
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), branch.thenLine());
                int falseJmp = emitJmp(branch.thenLine());
                compileBlock(branch.block());
                exitJmps.add(emitJmp(branch.thenLine()));
                patchJmp(falseJmp, code.size());
            }
            if (is.elseBlock() != null) {
                compileBlock(is.elseBlock());
            }
            int endPc = code.size();
            for (int jmp : exitJmps) {
                patchJmp(jmp, endPc);
            }
        }

        void compileWhile(Statements.WhileStmt ws) {
            int loopStart = code.size();
            int condReg = compileExprToAnyReg(ws.condition());
            emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), ws.line());
            int falseJmp = emitJmp(ws.line());

            LoopInfo loop = new LoopInfo(loopStart, locals.size());
            loopStack.push(loop);

            compileBlock(ws.body());
            int backJmp = emitJmp(ws.endLine());
            patchJmp(backJmp, loopStart);

            int loopEnd = code.size();
            patchJmp(falseJmp, loopEnd);
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
        }

        void compileRepeat(Statements.RepeatStmt rs) {
            int loopStart = code.size();
            LoopInfo loop = new LoopInfo(loopStart, locals.size());
            loopStack.push(loop);

            compileBlock(rs.body());

            int condReg = compileExprToAnyReg(rs.condition());
            emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), rs.line());
            int repeatJmp = emitJmp(rs.line());
            patchJmp(repeatJmp, loopStart);

            int loopEnd = code.size();
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
        }

        void compileForNumeric(Statements.ForNumericStmt fns) {
            int baseFreereg = freereg;
            int initReg = allocReg();
            int limitReg = allocReg();
            int stepReg = allocReg();

            compileExprToReg(fns.start(), initReg);
            compileExprToReg(fns.limit(), limitReg);
            if (fns.step() != null) {
                compileExprToReg(fns.step(), stepReg);
            } else {
                emit(Instruction.encodesBx(OpCode.OP_LOADI, stepReg, 1), fns.line());
            }

            int prepPc = emit(Instruction.encodeABx(OpCode.OP_FORPREP, initReg, 0), fns.line());

            int varReg = allocReg();
            LocalVar loopVar = new LocalVar(fns.variableName(), varReg, Statements.VariableAttribute.NONE);
            locals.add(loopVar);

            LoopInfo loop = new LoopInfo(code.size(), locals.size());
            loopStack.push(loop);

            compileBlock(fns.body());

            int loopPc = emit(Instruction.encodeABx(OpCode.OP_FORLOOP, initReg, 0), fns.endLine());
            int loopOffset = loopPc - prepPc;
            code.set(prepPc, Instruction.encodeABx(OpCode.OP_FORPREP, initReg, loopOffset));
            code.set(loopPc, Instruction.encodeABx(OpCode.OP_FORLOOP, initReg, loopOffset));

            int loopEnd = code.size();
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
            locals.remove(loopVar);
            freeRegs(baseFreereg);
        }

        void compileForGeneric(Statements.ForGenericStmt fgs) {
            int baseFreereg = freereg;
            int fReg = allocReg();
            int sReg = allocReg();
            int varReg = allocReg();
            int closeReg = allocReg();

            int nIter = fgs.iterators().size();
            if (nIter > 0) compileExprToReg(fgs.iterators().get(0), fReg);
            if (nIter > 1) compileExprToReg(fgs.iterators().get(1), sReg);
            if (nIter > 2) compileExprToReg(fgs.iterators().get(2), varReg);

            int prepPc = emit(Instruction.encodeABx(OpCode.OP_TFORPREP, fReg, 0), fgs.line());

            List<LocalVar> varLocals = new ArrayList<>();
            for (String name : fgs.variableNames()) {
                int r = allocReg();
                LocalVar lv = new LocalVar(name, r, Statements.VariableAttribute.NONE);
                locals.add(lv);
                varLocals.add(lv);
            }

            LoopInfo loop = new LoopInfo(code.size(), locals.size());
            loopStack.push(loop);

            compileBlock(fgs.body());

            emit(Instruction.encodeABC(OpCode.OP_TFORCALL, fReg, 0, fgs.variableNames().size()), fgs.line());
            int loopPc = emit(Instruction.encodeABx(OpCode.OP_TFORLOOP, fReg, 0), fgs.endLine());

            int prepOffset = loopPc - prepPc;
            code.set(prepPc, Instruction.encodeABx(OpCode.OP_TFORPREP, fReg, prepOffset));
            int backOffset = loopPc - (prepPc + 1);
            code.set(loopPc, Instruction.encodeABx(OpCode.OP_TFORLOOP, fReg, backOffset));

            int loopEnd = code.size();
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
            locals.removeAll(varLocals);
            freeRegs(baseFreereg);
        }



        void compileBreak(Statements.BreakStmt bs) {
            if (!loopStack.isEmpty()) {
                LoopInfo loop = loopStack.peek();
                closeLocalsTo(loop.baseLocals, bs.line());
                int brkJmp = emitJmp(bs.line());
                loop.breakList.add(brkJmp);
            }
        }

        void compileFunctionDef(Statements.FunctionDefStmt fds) {
            LuaProto childProto = compileChildFunction(fds.parameters(), fds.isVararg(), fds.body(), fds.line(), fds.endLine());
            int protoIdx = protos.size();
            protos.add(childProto);

            int clReg = allocReg();
            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, clReg, protoIdx), fds.line());
            assignTarget(fds.targetName(), clReg, fds.line());
            freeRegs(clReg);
        }

        void compileLocalFunctionDef(Statements.LocalFunctionDefStmt lfds) {
            int r = allocReg();
            LocalVar lv = new LocalVar(lfds.name(), r, Statements.VariableAttribute.NONE);
            locals.add(lv);

            LuaProto childProto = compileChildFunction(lfds.parameters(), lfds.isVararg(), lfds.body(), lfds.line(), lfds.endLine());
            int protoIdx = protos.size();
            protos.add(childProto);

            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, r, protoIdx), lfds.line());
        }

        LuaProto compileChildFunction(List<String> params, boolean isVararg, Statements.BlockStmt body, int lineDefined, int lastLineDefined) {
            FuncState child = new FuncState(this, source, lineDefined, lastLineDefined, isVararg, params.size());
            for (String p : params) {
                int r = child.allocReg();
                child.locals.add(new LocalVar(p, r, Statements.VariableAttribute.NONE));
            }
            child.compileBlock(body);
            child.emit(Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0), lastLineDefined);
            return child.toProto();
        }

        int compileExprToAnyReg(Expression expr) {
            if (expr instanceof Expressions.VariableExpr ve) {
                LocalVar lv = findLocal(ve.name());
                if (lv != null) return lv.reg;
            }
            int r = allocReg();
            compileExprToReg(expr, r);
            return r;
        }

        void compileExprToReg(Expression expr, int targetReg) {
            if (expr instanceof Expressions.NilLiteral nl) {
                emit(Instruction.encodeABC(OpCode.OP_LOADNIL, targetReg, 0, 0), nl.line());
            } else if (expr instanceof Expressions.BooleanLiteral bl) {
                emit(Instruction.encodeABC(bl.value() ? OpCode.OP_LOADTRUE : OpCode.OP_LOADFALSE, targetReg, 0, 0), bl.line());
            } else if (expr instanceof Expressions.IntegerLiteral il) {
                if (il.value() >= -Instruction.OFFSET_sBx && il.value() <= Instruction.OFFSET_sBx) {
                    emit(Instruction.encodesBx(OpCode.OP_LOADI, targetReg, (int) il.value()), il.line());
                } else {
                    int k = addConst(LuaInteger.valueOf(il.value()));
                    emit(Instruction.encodeABx(OpCode.OP_LOADK, targetReg, k), il.line());
                }
            } else if (expr instanceof Expressions.FloatLiteral fl) {
                int k = addConst(LuaFloat.valueOf(fl.value()));
                emit(Instruction.encodeABx(OpCode.OP_LOADK, targetReg, k), fl.line());
            } else if (expr instanceof Expressions.StringLiteral sl) {
                int k = addConst(sl.luaString());
                emit(Instruction.encodeABx(OpCode.OP_LOADK, targetReg, k), sl.line());
            } else if (expr instanceof Expressions.VariableExpr ve) {
                LocalVar lv = findLocal(ve.name());
                if (lv != null) {
                    if (lv.reg != targetReg) {
                        emit(Instruction.encodeABC(OpCode.OP_MOVE, targetReg, lv.reg, 0), ve.line());
                    }
                    return;
                }
                int up = findOrAddUpval(ve.name());
                if (up >= 0) {
                    emit(Instruction.encodeABC(OpCode.OP_GETUPVAL, targetReg, up, 0), ve.line());
                    return;
                }
                int envUp = findOrAddUpval("_ENV");
                int kKey = addConst(LuaString.valueOf(ve.name()));
                if (kKey <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_GETTABUP, targetReg, envUp, kKey, 0), ve.line());
                } else {
                    int kReg = allocReg();
                    emit(Instruction.encodeABx(OpCode.OP_LOADK, kReg, kKey), ve.line());
                    emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, envUp, kReg, 0), ve.line());
                    freeRegs(kReg);
                }
            } else if (expr instanceof Expressions.BinaryExpr be) {
                compileBinaryExpr(be, targetReg);
            } else if (expr instanceof Expressions.UnaryExpr ue) {
                compileUnaryExpr(ue, targetReg);
            } else if (expr instanceof Expressions.TableAccessExpr tae) {
                int tblReg = compileExprToAnyReg(tae.table());
                int keyReg = compileExprToAnyReg(tae.key());
                emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, tblReg, keyReg, 0), tae.line());
            } else if (expr instanceof Expressions.TableConstructorExpr tce) {
                compileTableConstructor(tce, targetReg);
            } else if (expr instanceof Expressions.FunctionCallExpr fce) {
                compileFunctionCall(fce, targetReg, 1);
            } else if (expr instanceof Expressions.FunctionDefExpr fde) {
                LuaProto child = compileChildFunction(fde.parameters(), fde.isVararg(), fde.body(), fde.line(), fde.endLine());
                int pIdx = protos.size();
                protos.add(child);
                emit(Instruction.encodeABx(OpCode.OP_CLOSURE, targetReg, pIdx), fde.line());
            } else if (expr instanceof Expressions.VarargLiteral val) {
                emit(Instruction.encodeABC(OpCode.OP_VARARG, targetReg, 2, 0), val.line());
            } else if (expr instanceof Expressions.ParenExpr pe) {
                compileExprToReg(pe.expression(), targetReg);
            }
        }

        void compileExprStmt(Statements.ExprStmt es) {
            int saveFreereg = freereg;
            if (es.expression() instanceof Expressions.FunctionCallExpr fce) {
                int r = allocReg();
                compileFunctionCall(fce, r, 0); // 0 expected results
            } else {
                compileExprToAnyReg(es.expression());
            }
            freeRegs(saveFreereg);
        }

        void compileReturn(Statements.ReturnStmt ret) {
            int nvals = ret.values().size();
            if (nvals == 0) {
                emit(Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0), ret.line());
            } else if (nvals == 1 && ret.values().get(0) instanceof Expressions.FunctionCallExpr fce) {
                int funcReg = allocReg();
                compileFunctionCall(fce, funcReg, 1);
                emit(Instruction.encodeABC(OpCode.OP_RETURN1, funcReg, 0, 0), ret.line());
            } else if (nvals == 1) {
                int r = compileExprToAnyReg(ret.values().get(0));
                emit(Instruction.encodeABC(OpCode.OP_RETURN1, r, 0, 0), ret.line());
            } else {
                int startReg = freereg;
                for (int i = 0; i < nvals; i++) {
                    int r = allocReg();
                    compileExprToReg(ret.values().get(i), r);
                }
                emit(Instruction.encodeABC(OpCode.OP_RETURN, startReg, nvals + 1, 0), ret.line());
            }
        }

        void compileFunctionCall(Expressions.FunctionCallExpr fce, int funcReg, int nResults) {
            int saveFreereg = freereg;
            freereg = funcReg + 1;

            if (fce.methodName() != null) {
                int tblReg = compileExprToAnyReg(fce.target());
                int kMethod = addConst(LuaString.valueOf(fce.methodName()));
                emit(Instruction.encodeABC(OpCode.OP_SELF, funcReg, tblReg, kMethod, 0), fce.line());
            } else {
                compileExprToReg(fce.target(), funcReg);
            }

            int nArgs = fce.arguments().size();
            for (int i = 0; i < nArgs; i++) {
                int argReg = (fce.methodName() != null ? funcReg + 2 : funcReg + 1) + i;
                freereg = argReg + 1;
                compileExprToReg(fce.arguments().get(i), argReg);
            }
            int actualArgs = fce.methodName() != null ? nArgs + 1 : nArgs;
            freereg = funcReg + 1 + actualArgs;
            emit(Instruction.encodeABC(OpCode.OP_CALL, funcReg, actualArgs + 1, nResults + 1), fce.line());
            freeRegs(Math.max(saveFreereg, funcReg + Math.max(1, nResults)));
        }

        void compileBinaryExpr(Expressions.BinaryExpr be, int targetReg) {
            int b = compileExprToAnyReg(be.left());
            int c = compileExprToAnyReg(be.right());
            int op = switch (be.operator()) {
                case PLUS -> OpCode.OP_ADD;
                case MINUS -> OpCode.OP_SUB;
                case STAR -> OpCode.OP_MUL;
                case SLASH -> OpCode.OP_DIV;
                case DOUBLE_SLASH -> OpCode.OP_IDIV;
                case PERCENT -> OpCode.OP_MOD;
                case CARET -> OpCode.OP_POW;
                case AMPERSAND -> OpCode.OP_BAND;
                case PIPE -> OpCode.OP_BOR;
                case TILDE -> OpCode.OP_BXOR;
                case SHL -> OpCode.OP_SHL;
                case SHR -> OpCode.OP_SHR;
                case DOT_DOT -> OpCode.OP_CONCAT;
                case EQUAL_EQUAL -> OpCode.OP_EQ;
                case LESS -> OpCode.OP_LT;
                case LESS_EQUAL -> OpCode.OP_LE;
                default -> OpCode.OP_ADD;
            };

            if (op == OpCode.OP_EQ || op == OpCode.OP_LT || op == OpCode.OP_LE) {
                emit(Instruction.encodeABC(op, b, c, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
            } else if (op == OpCode.OP_CONCAT) {
                emit(Instruction.encodeABC(OpCode.OP_CONCAT, targetReg, 2, 0), be.line());
            } else {
                emit(Instruction.encodeABC(op, targetReg, b, c, 0), be.line());
            }
        }

        void compileUnaryExpr(Expressions.UnaryExpr ue, int targetReg) {
            int b = compileExprToAnyReg(ue.operand());
            int op = switch (ue.operator()) {
                case MINUS -> OpCode.OP_UNM;
                case TILDE -> OpCode.OP_BNOT;
                case NOT -> OpCode.OP_NOT;
                case HASH -> OpCode.OP_LEN;
                default -> OpCode.OP_NOT;
            };
            emit(Instruction.encodeABC(op, targetReg, b, 0, 0), ue.line());
        }

        void compileTableConstructor(Expressions.TableConstructorExpr tce, int targetReg) {
            int nFields = tce.fields().size();
            emit(Instruction.encodeABC(OpCode.OP_NEWTABLE, targetReg, 0, 0, 0), tce.line());
            emit(Instruction.encodeAx(OpCode.OP_EXTRAARG, nFields), tce.line());

            int arrayIdx = 1;
            for (Expressions.TableField tf : tce.fields()) {
                if (tf.key() == null) {
                    int valReg = compileExprToAnyReg(tf.value());
                    emit(Instruction.encodeABC(OpCode.OP_SETI, targetReg, arrayIdx++, valReg, 0), tce.line());
                } else {
                    int keyReg = compileExprToAnyReg(tf.key());
                    int valReg = compileExprToAnyReg(tf.value());
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, targetReg, keyReg, valReg, 0), tce.line());
                }
            }
        }

        LuaProto toProto() {
            int[] codeArr = new int[code.size()];
            for (int i = 0; i < code.size(); i++) codeArr[i] = code.get(i);

            int[] lineArr = new int[lineInfo.size()];
            for (int i = 0; i < lineInfo.size(); i++) lineArr[i] = lineInfo.get(i);

            LuaValue[] constArr = constants.toArray(new LuaValue[0]);
            LuaProto[] protoArr = protos.toArray(new LuaProto[0]);
            UpvalueDesc[] upArr = upvalues.toArray(new UpvalueDesc[0]);

            String[] locArr = new String[locals.size()];
            for (int i = 0; i < locals.size(); i++) locArr[i] = locals.get(i).name;

            return new LuaProto(
                    source,
                    lineDefined,
                    lastLineDefined,
                    numParams,
                    isVararg,
                    maxstacksize,
                    codeArr,
                    constArr,
                    protoArr,
                    upArr,
                    lineArr,
                    locArr
            );
        }
    }

    private BytecodeCompiler() {}
}

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
        return compile(block, source, Collections.emptyList(), true, 0, 0, null);
    }

    public static LuaProto compile(Statements.BlockStmt block, String source, List<String> params, boolean isVararg) {
        return compile(block, source, params, isVararg, 0, 0, null);
    }

    public static LuaProto compile(Statements.BlockStmt block, String source, List<String> params, boolean isVararg, int lineDefined, int lastLineDefined) {
        return compile(block, source, params, isVararg, lineDefined, lastLineDefined, null);
    }

    public static LuaProto compile(Statements.BlockStmt block, String source, List<String> params, boolean isVararg, int lineDefined, int lastLineDefined, List<String> upvalueNames) {
        FuncState fs = new FuncState(null, null, source, lineDefined, lastLineDefined, isVararg, params != null ? params.size() : 0);
        if (upvalueNames != null && !upvalueNames.isEmpty()) {
            for (int i = 0; i < upvalueNames.size(); i++) {
                String upName = upvalueNames.get(i);
                fs.upvalues.add(new UpvalueDesc(upName, false, 0));
                fs.upvalMap.put(upName, i);
            }
        } else {
            // Upvalue 0 for main chunk is _ENV
            fs.upvalues.add(new UpvalueDesc("_ENV", true, 0));
            fs.upvalMap.put("_ENV", 0);
        }

        if (params != null) {
            for (String p : params) {
                int r = fs.allocReg();
                fs.registerLocal(p, r, Statements.VariableAttribute.NONE);
            }
        }

        fs.compileBlock(block);
        fs.checkUnresolvedGotos();
        fs.emit(Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0), block != null ? block.endLine() : 1);
        LuaProto proto = fs.toProto();
        proto.rawSource = org.luava.frontend.ast.AstPrinter.print(block);
        proto.body = block;
        return proto;
    }

    private static final class LocalVar {
        final String name;
        final int reg;
        final Statements.VariableAttribute attr;
        boolean isCaptured = false;
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

    private static final class LabelDesc {
        final String name;
        final int pc;
        final int nactvar;
        final int line;

        LabelDesc(String name, int pc, int nactvar, int line) {
            this.name = name;
            this.pc = pc;
            this.nactvar = nactvar;
            this.line = line;
        }
    }

    private static final class GotoDesc {
        final String name;
        final int pc;
        int nactvar;
        final int line;
        final List<LocalVar> localsSnapshot;

        GotoDesc(String name, int pc, int nactvar, int line, List<LocalVar> localsSnapshot) {
            this.name = name;
            this.pc = pc;
            this.nactvar = nactvar;
            this.line = line;
            this.localsSnapshot = localsSnapshot;
        }
    }

    private static final class FuncState {
        final FuncState parent;
        final String name;
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
        final List<LuaProto.LocVarInfo> allLocVars = new ArrayList<>();
        final Deque<LoopInfo> loopStack = new ArrayDeque<>();
        final List<LabelDesc> labelList = new ArrayList<>();
        final List<GotoDesc> pendingGotos = new ArrayList<>();
        int freereg = 0;
        int maxstacksize = 2;
        boolean hasTbc = false;

        void emitLoadK(int targetReg, int kIdx, int line) {
            if (kIdx <= Instruction.MASK_Bx) {
                emit(Instruction.encodeABx(OpCode.OP_LOADK, targetReg, kIdx), line);
            } else {
                emit(Instruction.encodeABC(OpCode.OP_LOADKX, targetReg, 0, 0), line);
                emit(Instruction.encodeAx(OpCode.OP_EXTRAARG, kIdx), line);
            }
        }

        LocalVar registerLocal(String name, int reg, Statements.VariableAttribute attr) {
            LocalVar lv = new LocalVar(name, reg, attr);
            locals.add(lv);
            allLocVars.add(new LuaProto.LocVarInfo(name, reg, code.size(), -1));
            return lv;
        }

        FuncState(FuncState parent, String name, String source, int lineDefined, int lastLineDefined, boolean isVararg, int numParams) {
            this.parent = parent;
            this.name = name;
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

        private int minFreereg() {
            int minReg = 0;
            for (LocalVar lv : locals) {
                if (lv.reg + 1 > minReg) minReg = lv.reg + 1;
            }
            return minReg;
        }

        int allocReg() {
            int minReg = minFreereg();
            if (freereg < minReg) {
                freereg = minReg;
            }
            int r = freereg++;
            if (freereg > maxstacksize) {
                maxstacksize = freereg;
            }
            return r;
        }

        void freeRegs(int toReg) {
            int target = Math.max(toReg, minFreereg());
            if (freereg > target) {
                freereg = target;
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
                if ("_ENV".equals(name)) {
                    int newIdx = upvalues.size();
                    upvalues.add(new UpvalueDesc(name, true, 0));
                    upvalMap.put(name, newIdx);
                    return newIdx;
                }
                return -1;
            }
            // Check if it's local in parent
            LocalVar parentLocal = parent.findLocal(name);
            if (parentLocal != null) {
                parentLocal.isCaptured = true;
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
            int firstLabel = labelList.size();
            int firstGoto = pendingGotos.size();

            List<Statement> stmts = block.statements();
            for (int i = 0; i < stmts.size(); i++) {
                Statement stmt = stmts.get(i);
                if (stmt instanceof Statements.LabelStmt ls) {
                    boolean isLast = true;
                    for (int j = i + 1; j < stmts.size(); j++) {
                        Statement next = stmts.get(j);
                        if (!(next instanceof Statements.LabelStmt)) {
                            isLast = false;
                            break;
                        }
                    }
                    compileLabel(ls, isLast, baseLocals);
                } else {
                    compileStatement(stmt);
                }
            }

            closeLocalsTo(baseLocals, block.endLine());
            freeRegs(baseFreereg);

            for (int i = firstGoto; i < pendingGotos.size(); i++) {
                GotoDesc gt = pendingGotos.get(i);
                if (gt.nactvar > baseLocals) {
                    gt.nactvar = baseLocals;
                }
            }

            while (labelList.size() > firstLabel) {
                labelList.remove(labelList.size() - 1);
            }
        }

        void closeLocalsTo(int baseLocals, int line) {
            emitCloseLocalsTo(baseLocals, line);
            while (locals.size() > baseLocals) {
                locals.remove(locals.size() - 1);
            }
        }

        void emitCloseLocalsTo(int baseLocals, int line) {
            boolean needClose = false;
            int firstReg = Integer.MAX_VALUE;
            for (int i = locals.size() - 1; i >= baseLocals; i--) {
                LocalVar v = locals.get(i);
                if (v.isCaptured || v.attr == Statements.VariableAttribute.CLOSE) {
                    needClose = true;
                    if (v.reg < firstReg) {
                        firstReg = v.reg;
                    }
                }
            }
            if (needClose && firstReg != Integer.MAX_VALUE) {
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
            } else if (stmt instanceof Statements.GotoStmt gs) {
                compileGoto(gs);
            } else if (stmt instanceof Statements.LabelStmt ls) {
                compileLabel(ls);
            }
        }

        void compileLocalVarDecl(Statements.LocalVarDeclStmt lvd) {
            int nvars = lvd.bindings().size();
            int nvals = lvd.initializers().size();

            boolean lastIsCall = nvals > 0 && lvd.initializers().get(nvals - 1) instanceof Expressions.FunctionCallExpr;
            boolean lastIsVararg = nvals > 0 && lvd.initializers().get(nvals - 1) instanceof Expressions.VarargLiteral;
            int numFixedVals = (lastIsCall || lastIsVararg) ? nvals - 1 : nvals;

            int[] varRegs = new int[nvars];
            for (int i = 0; i < nvars; i++) {
                if (i < numFixedVals) {
                    varRegs[i] = allocReg();
                    String inferredName = lvd.bindings().get(i).name();
                    compileExprToReg(lvd.initializers().get(i), varRegs[i], inferredName);
                } else if (i == numFixedVals && lastIsCall) {
                    int neededResults = nvars - numFixedVals;
                    for (int j = 0; j < neededResults; j++) {
                        varRegs[i + j] = allocReg();
                    }
                    compileFunctionCall((Expressions.FunctionCallExpr) lvd.initializers().get(nvals - 1), varRegs[i], neededResults);
                    freeRegs(varRegs[i] + neededResults);
                    i += (neededResults - 1);
                } else if (i == numFixedVals && lastIsVararg) {
                    int neededResults = nvars - numFixedVals;
                    for (int j = 0; j < neededResults; j++) {
                        varRegs[i + j] = allocReg();
                    }
                    emit(Instruction.encodeABC(OpCode.OP_VARARG, varRegs[i], 0, neededResults + 1), lvd.line());
                    i += (neededResults - 1);
                } else {
                    varRegs[i] = allocReg();
                    emit(Instruction.encodeABC(OpCode.OP_LOADNIL, varRegs[i], 0, 0), lvd.line());
                }
            }

            for (int i = 0; i < nvars; i++) {
                Statements.LocalVarBinding b = lvd.bindings().get(i);
                registerLocal(b.name(), varRegs[i], b.attribute());
                if (b.attribute() == Statements.VariableAttribute.CLOSE) {
                    emit(Instruction.encodeABC(OpCode.OP_TBC, varRegs[i], 0, 0), lvd.line());
                    hasTbc = true;
                }
            }
        }

        void compileAssignment(Statements.AssignmentStmt as) {
            int nvars = as.targets().size();
            int nvals = as.values().size();
            int[] valRegs = new int[nvars];
            int saveFreereg = freereg;

            boolean lastIsCall = nvals > 0 && as.values().get(nvals - 1) instanceof Expressions.FunctionCallExpr;
            boolean lastIsVararg = nvals > 0 && as.values().get(nvals - 1) instanceof Expressions.VarargLiteral;
            int numFixedVals = (lastIsCall || lastIsVararg) ? nvals - 1 : nvals;

            // Pre-resolve upvalue indices for assignment targets BEFORE compiling values.
            // This mirrors C Lua's lparser.c where singlevar() is called on each LHS target
            // first (establishing upvalue indices), then explist() compiles the RHS.
            // Without this, `a = 10+b` would register `b` (from RHS) before `a` (LHS target).
            for (Expression tgt : as.targets()) {
                if (tgt instanceof Expressions.VariableExpr ve) {
                    if (findLocal(ve.name()) == null) {
                        findOrAddUpval(ve.name());
                    }
                }
            }

            for (int i = 0; i < nvars; i++) {
                if (i < numFixedVals) {
                    valRegs[i] = allocReg();
                    String inferredName = null;
                    if (as.targets().get(i) instanceof Expressions.VariableExpr ve) {
                        inferredName = ve.name();
                    } else if (as.targets().get(i) instanceof Expressions.TableAccessExpr tae && tae.key() instanceof Expressions.StringLiteral sl) {
                        inferredName = (String) sl.value();
                    }
                    compileExprToReg(as.values().get(i), valRegs[i], inferredName);
                } else if (i == numFixedVals && lastIsCall) {
                    int neededResults = nvars - numFixedVals;
                    for (int j = 0; j < neededResults; j++) {
                        valRegs[i + j] = allocReg();
                    }
                    compileFunctionCall((Expressions.FunctionCallExpr) as.values().get(nvals - 1), valRegs[i], neededResults);
                    freeRegs(valRegs[i] + neededResults);
                    i += (neededResults - 1);
                } else if (i == numFixedVals && lastIsVararg) {
                    int neededResults = nvars - numFixedVals;
                    for (int j = 0; j < neededResults; j++) {
                        valRegs[i + j] = allocReg();
                    }
                    emit(Instruction.encodeABC(OpCode.OP_VARARG, valRegs[i], 0, neededResults + 1), as.line());
                    i += (neededResults - 1);
                } else {
                    valRegs[i] = allocReg();
                    emit(Instruction.encodeABC(OpCode.OP_LOADNIL, valRegs[i], 0, 0), as.line());
                }
            }

            int[] tblRegs = new int[nvars];
            int[] keyRegs = new int[nvars];
            for (int i = 0; i < nvars; i++) {
                if (as.targets().get(i) instanceof Expressions.TableAccessExpr tae) {
                    int tr = allocReg();
                    compileExprToReg(tae.table(), tr);
                    tblRegs[i] = tr;
                    int kr = allocReg();
                    compileExprToReg(tae.key(), kr);
                    keyRegs[i] = kr;
                } else {
                    tblRegs[i] = -1;
                    keyRegs[i] = -1;
                }
            }

            for (int i = 0; i < nvars; i++) {
                if (tblRegs[i] >= 0) {
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tblRegs[i], keyRegs[i], valRegs[i], 0), as.line());
                } else {
                    assignTarget(as.targets().get(i), valRegs[i], as.line());
                }
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
                LocalVar envLocal = findLocal("_ENV");
                int kKey = addConst(LuaString.valueOf(ve.name()));
                if (envLocal != null) {
                    if (kKey <= 255) {
                        emit(Instruction.encodeABC(OpCode.OP_SETFIELD, envLocal.reg, kKey, valReg, 0), line);
                    } else {
                        int kReg = allocReg();
                        emitLoadK(kReg, kKey, line);
                        emit(Instruction.encodeABC(OpCode.OP_SETTABLE, envLocal.reg, kReg, valReg, 0), line);
                        freeRegs(kReg);
                    }
                    return;
                }
                int envUp = findOrAddUpval("_ENV");
                if (kKey <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_SETTABUP, envUp, kKey, valReg, 0), line);
                } else {
                    int tmpReg = allocReg();
                    emit(Instruction.encodeABC(OpCode.OP_GETUPVAL, tmpReg, envUp, 0), line);
                    int kReg = allocReg();
                    emitLoadK(kReg, kKey, line);
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tmpReg, kReg, valReg, 0), line);
                    freeRegs(tmpReg);
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
            int baseLocals = locals.size();
            int baseFreereg = freereg;
            LoopInfo loop = new LoopInfo(loopStart, baseLocals);
            loopStack.push(loop);

            for (Statement stmt : rs.body().statements()) {
                compileStatement(stmt);
            }

            int condReg = compileExprToAnyReg(rs.condition());

            boolean hasClose = false;
            int firstCloseReg = Integer.MAX_VALUE;
            for (int i = baseLocals; i < locals.size(); i++) {
                LocalVar v = locals.get(i);
                if (v.attr == Statements.VariableAttribute.CLOSE) {
                    hasClose = true;
                }
                if (v.reg < firstCloseReg) {
                    firstCloseReg = v.reg;
                }
            }

            if (hasClose && firstCloseReg != Integer.MAX_VALUE) {
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 1, 0), rs.line());
                int exitJmpTarget = emitJmp(rs.line());
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, firstCloseReg, 0, 0), rs.line());
                int backJmp = emitJmp(rs.line());
                patchJmp(backJmp, loopStart);
                patchJmp(exitJmpTarget, code.size());
            } else {
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), rs.line());
                int repeatJmp = emitJmp(rs.line());
                patchJmp(repeatJmp, loopStart);
            }

            closeLocalsTo(baseLocals, rs.line());
            freeRegs(baseFreereg);

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
            int varReg = allocReg();

            compileExprToReg(fns.start(), initReg);
            compileExprToReg(fns.limit(), limitReg);
            if (fns.step() != null) {
                compileExprToReg(fns.step(), stepReg);
            } else {
                emit(Instruction.encodesBx(OpCode.OP_LOADI, stepReg, 1), fns.line());
            }

            freeRegs(varReg + 1);

            int prepPc = emit(Instruction.encodeABx(OpCode.OP_FORPREP, initReg, 0), fns.line());

            int baseLocals = locals.size();
            registerLocal("(for state)", initReg, Statements.VariableAttribute.NONE);
            registerLocal("(for state)", limitReg, Statements.VariableAttribute.NONE);
            registerLocal("(for state)", stepReg, Statements.VariableAttribute.NONE);
            registerLocal(fns.variableName(), varReg, Statements.VariableAttribute.NONE);

            LoopInfo loop = new LoopInfo(code.size(), baseLocals);
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
            closeLocalsTo(baseLocals, fns.endLine());
            freeRegs(baseFreereg);
        }

        void compileForGeneric(Statements.ForGenericStmt fgs) {
            int baseFreereg = freereg;
            int baseLocals = locals.size();
            int fReg = allocReg();
            int sReg = allocReg();
            int varReg = allocReg();
            int closeReg = allocReg();

            int nIter = fgs.iterators().size();
            boolean lastIsCall = nIter > 0 && fgs.iterators().get(nIter - 1) instanceof Expressions.FunctionCallExpr;
            boolean lastIsVararg = nIter > 0 && fgs.iterators().get(nIter - 1) instanceof Expressions.VarargLiteral;
            int numFixedIter = (lastIsCall || lastIsVararg) ? nIter - 1 : nIter;

            for (int i = 0; i < 4; i++) {
                int reg = fReg + i;
                if (i < numFixedIter) {
                    compileExprToReg(fgs.iterators().get(i), reg);
                } else if (i == numFixedIter && lastIsCall) {
                    int neededResults = 4 - numFixedIter;
                    compileFunctionCall((Expressions.FunctionCallExpr) fgs.iterators().get(nIter - 1), reg, neededResults);
                    freeRegs(reg + neededResults);
                    i += (neededResults - 1);
                } else if (i == numFixedIter && lastIsVararg) {
                    int neededResults = 4 - numFixedIter;
                    emit(Instruction.encodeABC(OpCode.OP_VARARG, reg, 0, neededResults + 1), fgs.line());
                    i += (neededResults - 1);
                } else {
                    emit(Instruction.encodeABC(OpCode.OP_LOADNIL, reg, 0, 0), fgs.line());
                }
            }

            freeRegs(closeReg + 1);

            int prepPc = emit(Instruction.encodeABx(OpCode.OP_TFORPREP, fReg, 0), fgs.line());

            registerLocal("(for state)", fReg, Statements.VariableAttribute.NONE);
            registerLocal("(for state)", sReg, Statements.VariableAttribute.NONE);
            registerLocal("(for state)", varReg, Statements.VariableAttribute.NONE);
            registerLocal("(for state)", closeReg, Statements.VariableAttribute.CLOSE);
            hasTbc = true;

            for (String name : fgs.variableNames()) {
                int r = allocReg();
                registerLocal(name, r, Statements.VariableAttribute.NONE);
            }

            int loopStartPc = code.size();
            LoopInfo loop = new LoopInfo(loopStartPc, baseLocals);
            loopStack.push(loop);

            compileBlock(fgs.body());

            int callPc = emit(Instruction.encodeABC(OpCode.OP_TFORCALL, fReg, 0, fgs.variableNames().size()), fgs.line());
            int loopPc = emit(Instruction.encodeABx(OpCode.OP_TFORLOOP, fReg, 0), fgs.endLine());

            int prepOffset = callPc - (prepPc + 1);
            code.set(prepPc, Instruction.encodeABx(OpCode.OP_TFORPREP, fReg, prepOffset));
            int backOffset = (loopPc + 1) - loopStartPc;
            code.set(loopPc, Instruction.encodeABx(OpCode.OP_TFORLOOP, fReg, backOffset));

            int loopEnd = code.size();
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
            closeLocalsTo(baseLocals, fgs.endLine());
            freeRegs(baseFreereg);
        }



        void compileBreak(Statements.BreakStmt bs) {
            if (!loopStack.isEmpty()) {
                LoopInfo loop = loopStack.peek();
                emitCloseLocalsTo(loop.baseLocals, bs.line());
                int brkJmp = emitJmp(bs.line());
                loop.breakList.add(brkJmp);
            }
        }

        int regLevel(int nvar) {
            while (nvar-- > 0) {
                if (nvar < locals.size()) {
                    return locals.get(nvar).reg + 1;
                }
            }
            return 0;
        }

        void compileGoto(Statements.GotoStmt gs) {
            for (int i = labelList.size() - 1; i >= 0; i--) {
                LabelDesc lb = labelList.get(i);
                if (lb.name.equals(gs.label())) {
                    if (locals.size() > lb.nactvar) {
                        emitCloseLocalsTo(lb.nactvar, gs.line());
                    }
                    int jmp = emitJmp(gs.line());
                    patchJmp(jmp, lb.pc);
                    return;
                }
            }
            int jmp = emitJmp(gs.line());
            pendingGotos.add(new GotoDesc(gs.label(), jmp, locals.size(), gs.line(), new ArrayList<>(locals)));
        }

        void compileLabel(Statements.LabelStmt ls) {
            compileLabel(ls, false, locals.size());
        }

        void compileLabel(Statements.LabelStmt ls, boolean isLast, int baseLocals) {
            for (LabelDesc existing : labelList) {
                if (existing.name.equals(ls.name())) {
                    throw new LuaException("label '" + ls.name() + "' already defined on line " + existing.line);
                }
            }
            int labelPc = code.size();
            int nactvar = isLast ? baseLocals : locals.size();
            LabelDesc lb = new LabelDesc(ls.name(), labelPc, nactvar, ls.line());
            labelList.add(lb);
            boolean needsClose = false;
            Iterator<GotoDesc> it = pendingGotos.iterator();
            while (it.hasNext()) {
                GotoDesc gt = it.next();
                if (gt.name.equals(lb.name)) {
                    if (gt.nactvar < lb.nactvar) {
                        String varname = (gt.nactvar < locals.size()) ? locals.get(gt.nactvar).name : "?";
                        throw new LuaException("<goto " + gt.name + "> at line " + gt.line + " jumps into the scope of local '" + varname + "'");
                    }
                    patchJmp(gt.pc, lb.pc);
                    if (gt.localsSnapshot != null) {
                        for (int i = gt.localsSnapshot.size() - 1; i >= lb.nactvar; i--) {
                            LocalVar v = gt.localsSnapshot.get(i);
                            if (v.isCaptured || v.attr == Statements.VariableAttribute.CLOSE) {
                                needsClose = true;
                                break;
                            }
                        }
                    }
                    it.remove();
                }
            }
            if (needsClose) {
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, regLevel(lb.nactvar), 0, 0), ls.line());
            }
        }

        void checkUnresolvedGotos() {
            if (!pendingGotos.isEmpty()) {
                GotoDesc gt = pendingGotos.get(0);
                throw new LuaException("no visible label '" + gt.name + "' for <goto> at line " + gt.line);
            }
        }

        void compileFunctionDef(Statements.FunctionDefStmt fds) {
            String fnName = null;
            if (fds.targetName() instanceof Expressions.VariableExpr v) {
                fnName = v.name();
            } else if (fds.targetName() instanceof Expressions.TableAccessExpr t && t.key() instanceof Expressions.StringLiteral sl) {
                fnName = (String) sl.value();
            }
            LuaProto childProto = compileChildFunction(fnName, fds.parameters(), fds.isVararg(), fds.body(), fds.line(), fds.endLine());
            int protoIdx = protos.size();
            protos.add(childProto);

            int clReg = allocReg();
            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, clReg, protoIdx), fds.line());
            assignTarget(fds.targetName(), clReg, fds.line());
            freeRegs(clReg);
        }

        void compileLocalFunctionDef(Statements.LocalFunctionDefStmt lfds) {
            int r = allocReg();
            registerLocal(lfds.name(), r, Statements.VariableAttribute.NONE);

            LuaProto childProto = compileChildFunction(lfds.name(), lfds.parameters(), lfds.isVararg(), lfds.body(), lfds.line(), lfds.endLine());
            int protoIdx = protos.size();
            protos.add(childProto);

            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, r, protoIdx), lfds.line());
        }

        LuaProto compileChildFunction(String name, List<String> params, boolean isVararg, Statements.BlockStmt body, int lineDefined, int lastLineDefined) {
            FuncState child = new FuncState(this, name, source, lineDefined, lastLineDefined, isVararg, params.size());
            for (String p : params) {
                int r = child.allocReg();
                child.registerLocal(p, r, Statements.VariableAttribute.NONE);
            }
            child.compileBlock(body);
            child.checkUnresolvedGotos();
            child.emit(Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0), lastLineDefined);
            LuaProto proto = child.toProto();
            proto.rawSource = org.luava.frontend.ast.AstPrinter.print(body);
            proto.body = body;
            return proto;
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
            compileExprToReg(expr, targetReg, null);
        }

        void compileExprToReg(Expression expr, int targetReg, String inferredName) {
            if (expr instanceof Expressions.NilLiteral nl) {
                emit(Instruction.encodeABC(OpCode.OP_LOADNIL, targetReg, 0, 0), nl.line());
            } else if (expr instanceof Expressions.BooleanLiteral bl) {
                emit(Instruction.encodeABC(bl.value() ? OpCode.OP_LOADTRUE : OpCode.OP_LOADFALSE, targetReg, 0, 0), bl.line());
            } else if (expr instanceof Expressions.IntegerLiteral il) {
                if (il.value() >= -Instruction.OFFSET_sBx && il.value() <= Instruction.OFFSET_sBx) {
                    emit(Instruction.encodesBx(OpCode.OP_LOADI, targetReg, (int) il.value()), il.line());
                } else {
                    int k = addConst(LuaInteger.valueOf(il.value()));
                    emitLoadK(targetReg, k, il.line());
                }
            } else if (expr instanceof Expressions.FloatLiteral fl) {
                int k = addConst(LuaFloat.valueOf(fl.value()));
                emitLoadK(targetReg, k, fl.line());
            } else if (expr instanceof Expressions.StringLiteral sl) {
                int k = addConst(sl.luaString());
                emitLoadK(targetReg, k, sl.line());
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
                LocalVar envLocal = findLocal("_ENV");
                int kKey = addConst(LuaString.valueOf(ve.name()));
                if (envLocal != null) {
                    if (kKey <= 255) {
                        emit(Instruction.encodeABC(OpCode.OP_GETFIELD, targetReg, envLocal.reg, kKey, 0), ve.line());
                    } else {
                        int kReg = allocReg();
                        emitLoadK(kReg, kKey, ve.line());
                        emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, envLocal.reg, kReg, 0), ve.line());
                        freeRegs(kReg);
                    }
                    return;
                }
                int envUp = findOrAddUpval("_ENV");
                if (kKey <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_GETTABUP, targetReg, envUp, kKey, 0), ve.line());
                } else {
                    emit(Instruction.encodeABC(OpCode.OP_GETUPVAL, targetReg, envUp, 0), ve.line());
                    int kReg = allocReg();
                    emitLoadK(kReg, kKey, ve.line());
                    emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, targetReg, kReg, 0), ve.line());
                    freeRegs(kReg);
                }
                return;
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
                LuaProto child = compileChildFunction(inferredName, fde.parameters(), fde.isVararg(), fde.body(), fde.line(), fde.endLine());
                int pIdx = protos.size();
                protos.add(child);
                emit(Instruction.encodeABx(OpCode.OP_CLOSURE, targetReg, pIdx), fde.line());
            } else if (expr instanceof Expressions.VarargLiteral val) {
                emit(Instruction.encodeABC(OpCode.OP_VARARG, targetReg, 0, 2), val.line());
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
            } else {
                Expression lastVal = ret.values().get(nvals - 1);
                boolean lastIsCall = lastVal instanceof Expressions.FunctionCallExpr;
                boolean lastIsVararg = lastVal instanceof Expressions.VarargLiteral;

                if (nvals == 1 && !lastIsCall && !lastIsVararg) {
                    int r = compileExprToAnyReg(lastVal);
                    emit(Instruction.encodeABC(OpCode.OP_RETURN1, r, 0, 0), ret.line());
                } else if (nvals == 1 && lastIsCall && !hasTbc) {
                    int r = allocReg();
                    compileFunctionCall((Expressions.FunctionCallExpr) lastVal, r, -1, true);
                    emit(Instruction.encodeABC(OpCode.OP_RETURN, r, 0, 0), ret.line());
                } else if (lastIsCall || lastIsVararg) {
                    int[] retRegs = new int[nvals];
                    for (int i = 0; i < nvals; i++) {
                        retRegs[i] = allocReg();
                    }
                    for (int i = 0; i < nvals - 1; i++) {
                        compileExprToReg(ret.values().get(i), retRegs[i]);
                    }
                    int lastReg = retRegs[nvals - 1];
                    if (lastIsCall) {
                        compileFunctionCall((Expressions.FunctionCallExpr) lastVal, lastReg, -1);
                    } else {
                        emit(Instruction.encodeABC(OpCode.OP_VARARG, lastReg, 0, 0), lastVal.line());
                    }
                    emit(Instruction.encodeABC(OpCode.OP_RETURN, retRegs[0], 0, 0), ret.line());
                } else {
                    int[] retRegs = new int[nvals];
                    for (int i = 0; i < nvals; i++) {
                        retRegs[i] = allocReg();
                    }
                    for (int i = 0; i < nvals; i++) {
                        compileExprToReg(ret.values().get(i), retRegs[i]);
                    }
                    emit(Instruction.encodeABC(OpCode.OP_RETURN, retRegs[0], nvals + 1, 0), ret.line());
                }
            }
        }

        void compileFunctionCall(Expressions.FunctionCallExpr fce, int funcReg, int nResults) {
            compileFunctionCall(fce, funcReg, nResults, false);
        }

        void compileFunctionCall(Expressions.FunctionCallExpr fce, int funcReg, int nResults, boolean isTailCall) {
            int saveFreereg = freereg;
            freereg = funcReg + 1;

            if (fce.methodName() != null) {
                int tblReg = compileExprToAnyReg(fce.target());
                int kMethod = addConst(LuaString.valueOf(fce.methodName()));
                emit(Instruction.encodeABC(OpCode.OP_SELF, funcReg, tblReg, kMethod, 1), fce.line());
            } else {
                compileExprToReg(fce.target(), funcReg);
            }

            int nArgs = fce.arguments().size();
            int argStart = (fce.methodName() != null ? funcReg + 2 : funcReg + 1);
            int callOp = isTailCall ? OpCode.OP_TAILCALL : OpCode.OP_CALL;
            if (nArgs == 0) {
                int actualArgs = fce.methodName() != null ? 1 : 0;
                emit(Instruction.encodeABC(callOp, funcReg, actualArgs + 1, isTailCall ? 0 : nResults + 1), fce.line());
            } else {
                Expression lastArg = fce.arguments().get(nArgs - 1);
                boolean lastIsCall = lastArg instanceof Expressions.FunctionCallExpr;
                boolean lastIsVararg = lastArg instanceof Expressions.VarargLiteral;

                for (int i = 0; i < nArgs - 1; i++) {
                    int argReg = argStart + i;
                    freereg = argReg + 1;
                    compileExprToReg(fce.arguments().get(i), argReg);
                }

                if (lastIsCall || lastIsVararg) {
                    int lastArgReg = argStart + (nArgs - 1);
                    freereg = lastArgReg + 1;
                    if (lastIsCall) {
                        compileFunctionCall((Expressions.FunctionCallExpr) lastArg, lastArgReg, -1);
                    } else {
                        emit(Instruction.encodeABC(OpCode.OP_VARARG, lastArgReg, 0, 0), lastArg.line());
                    }
                    emit(Instruction.encodeABC(callOp, funcReg, 0, isTailCall ? 0 : nResults + 1), fce.line());
                } else {
                    int lastArgReg = argStart + (nArgs - 1);
                    freereg = lastArgReg + 1;
                    compileExprToReg(lastArg, lastArgReg);
                    int actualArgs = fce.methodName() != null ? nArgs + 1 : nArgs;
                    emit(Instruction.encodeABC(callOp, funcReg, actualArgs + 1, isTailCall ? 0 : nResults + 1), fce.line());
                }
            }
            int targetFreereg = Math.max(saveFreereg, funcReg + (nResults == -1 ? 1 : nResults));
            freereg = Math.max(targetFreereg, minFreereg());
        }

        void compileBinaryExpr(Expressions.BinaryExpr be, int targetReg) {
            int saveFreereg = freereg;
            if (be.operator() == TokenType.AND) {
                int regA = compileExprToAnyReg(be.left());
                emit(Instruction.encodeABC(OpCode.OP_TESTSET, targetReg, regA, 0, 0), be.line());
                int jmp = emitJmp(be.line());
                compileExprToReg(be.right(), targetReg);
                patchJmp(jmp, code.size());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.OR) {
                int regA = compileExprToAnyReg(be.left());
                emit(Instruction.encodeABC(OpCode.OP_TESTSET, targetReg, regA, 0, 1), be.line());
                int jmp = emitJmp(be.line());
                compileExprToReg(be.right(), targetReg);
                patchJmp(jmp, code.size());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.TILDE_EQUAL) {
                int b = compileExprToAnyReg(be.left());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_EQ, b, c, 0, 1), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.GREATER) {
                int b = compileExprToAnyReg(be.left());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_LT, c, b, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.GREATER_EQUAL) {
                int b = compileExprToAnyReg(be.left());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_LE, c, b, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.DOT_DOT) {
                int save = freereg;
                compileExprToReg(be.left(), targetReg);
                freereg = targetReg + 2;
                compileExprToReg(be.right(), targetReg + 1);
                emit(Instruction.encodeABC(OpCode.OP_CONCAT, targetReg, 2, 0), be.line());
                freeRegs(save);
                return;
            }

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
                case EQUAL_EQUAL -> OpCode.OP_EQ;
                case LESS -> OpCode.OP_LT;
                case LESS_EQUAL -> OpCode.OP_LE;
                default -> OpCode.OP_ADD;
            };

            if (op == OpCode.OP_EQ || op == OpCode.OP_LT || op == OpCode.OP_LE) {
                emit(Instruction.encodeABC(op, b, c, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
            } else {
                emit(Instruction.encodeABC(op, targetReg, b, c, 0), be.line());
            }
            freeRegs(Math.max(saveFreereg, targetReg + 1));
        }

        void compileUnaryExpr(Expressions.UnaryExpr ue, int targetReg) {
            int saveFreereg = freereg;
            int b = compileExprToAnyReg(ue.operand());
            int op = switch (ue.operator()) {
                case MINUS -> OpCode.OP_UNM;
                case TILDE -> OpCode.OP_BNOT;
                case NOT -> OpCode.OP_NOT;
                case HASH -> OpCode.OP_LEN;
                default -> OpCode.OP_NOT;
            };
            emit(Instruction.encodeABC(op, targetReg, b, 0, 0), ue.line());
            freeRegs(Math.max(saveFreereg, targetReg + 1));
        }

        void compileTableConstructor(Expressions.TableConstructorExpr tce, int targetReg) {
            int nFields = tce.fields().size();
            emit(Instruction.encodeABC(OpCode.OP_NEWTABLE, targetReg, 0, 0, 0), tce.line());
            emit(Instruction.encodeAx(OpCode.OP_EXTRAARG, nFields), tce.line());

            int arrayIdx = 0;
            int saveFreereg = freereg;
            List<Expressions.TableField> pendingList = new ArrayList<>();

            for (int i = 0; i < nFields; i++) {
                Expressions.TableField tf = tce.fields().get(i);
                if (tf.key() != null) {
                    flushListFields(targetReg, pendingList, arrayIdx, tce.line());
                    arrayIdx += pendingList.size();
                    pendingList.clear();

                    int keyReg = compileExprToAnyReg(tf.key());
                    int valReg = compileExprToAnyReg(tf.value());
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, targetReg, keyReg, valReg, 0), tce.line());
                } else {
                    boolean isLast = (i == nFields - 1);
                    boolean isMultret = isLast && (tf.value() instanceof Expressions.FunctionCallExpr || tf.value() instanceof Expressions.VarargLiteral);

                    if (isMultret) {
                        flushListFields(targetReg, pendingList, arrayIdx, tce.line());
                        arrayIdx += pendingList.size();
                        pendingList.clear();

                        int nextReg = targetReg + 1;
                        freereg = nextReg;
                        if (tf.value() instanceof Expressions.FunctionCallExpr fce) {
                            compileFunctionCall(fce, nextReg, -1);
                        } else {
                            emit(Instruction.encodeABC(OpCode.OP_VARARG, nextReg, 0, 0), tce.line());
                        }
                        emitSetList(targetReg, 0, arrayIdx, tce.line());
                    } else {
                        pendingList.add(tf);
                        if (pendingList.size() == 50) {
                            flushListFields(targetReg, pendingList, arrayIdx, tce.line());
                            arrayIdx += 50;
                            pendingList.clear();
                        }
                    }
                }
            }
            if (!pendingList.isEmpty()) {
                flushListFields(targetReg, pendingList, arrayIdx, tce.line());
                arrayIdx += pendingList.size();
                pendingList.clear();
            }
            freeRegs(saveFreereg);
        }

        void flushListFields(int targetReg, List<Expressions.TableField> list, int arrayIdx, int line) {
            if (list.isEmpty()) return;
            int n = list.size();
            int baseReg = targetReg + 1;
            freereg = baseReg;
            for (int i = 0; i < n; i++) {
                int r = allocReg();
                compileExprToReg(list.get(i).value(), r);
            }
            emitSetList(targetReg, n, arrayIdx, line);
            freereg = baseReg;
        }

        void emitSetList(int targetReg, int tostore, int nelems, int line) {
            if (nelems <= Instruction.MASK_C) {
                emit(Instruction.encodeABC(OpCode.OP_SETLIST, targetReg, tostore, nelems, 0), line);
            } else {
                int extra = nelems / (Instruction.MASK_C + 1);
                int rc = nelems % (Instruction.MASK_C + 1);
                emit(Instruction.encodeABC(OpCode.OP_SETLIST, targetReg, tostore, rc, 1), line);
                emit(Instruction.encodeAx(OpCode.OP_EXTRAARG, extra), line);
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
                    name,
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
                    locArr,
                    allLocVars.toArray(new LuaProto.LocVarInfo[0])
            );
        }
    }

    private BytecodeCompiler() {}
}

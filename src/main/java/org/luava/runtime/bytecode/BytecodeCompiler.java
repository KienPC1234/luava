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
        int locVarIndex = -1;

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
        int currentBlockFirstGoto = 0;

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
            lv.locVarIndex = allLocVars.size();
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

        /**
         * Clear dead slots once at loop entry ([freereg, maxstacksize)).
         * At statement boundaries no live temps exist above freereg and live
         * vars are below it, so clearing is safe. This releases stranded heap
         * references from outer temps that the loop never reuses (otherwise
         * they pin garbage as false GC roots and starve finalizers, e.g.
         * db.lua:914). Emitted before loopStart is captured, so back-edges
         * and later patches are unaffected; line 0 inherits (no hook event).
         */
        void clearDeadSlotsAtLoopEntry(int line) {
            int from = freereg;
            int to = maxstacksize;
            while (from < to) {
                int chunk = Math.min(to - from, Instruction.MASK_B + 1);
                emit(Instruction.encodeABC(OpCode.OP_CLEANUP, from, chunk - 1, 0), line);
                from += chunk;
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
            int savedFirstGoto = currentBlockFirstGoto;
            // Scoped goto resolution: a label in this block can only resolve forward jumps originating in this block
            currentBlockFirstGoto = firstGoto;

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
            currentBlockFirstGoto = savedFirstGoto;
        }

        void closeLocalsTo(int baseLocals, int line) {
            emitCloseLocalsTo(baseLocals, line);
            int curPc = code.size();
            while (locals.size() > baseLocals) {
                LocalVar lv = locals.remove(locals.size() - 1);
                // Record variable exit pc so debug.getlocal only observes live variables in current scope
                if (lv.locVarIndex >= 0 && lv.locVarIndex < allLocVars.size()) {
                    LuaProto.LocVarInfo old = allLocVars.get(lv.locVarIndex);
                    allLocVars.set(lv.locVarIndex, new LuaProto.LocVarInfo(old.name(), old.reg(), old.startPc(), curPc));
                }
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
            int[] keyConsts = new int[nvars];
            int[] keyKinds = new int[nvars]; // 0: reg (SETTABLE), 1: int (SETI), 2: str (SETFIELD)
            for (int i = 0; i < nvars; i++) {
                if (as.targets().get(i) instanceof Expressions.TableAccessExpr tae) {
                    int tr = allocReg();
                    compileExprToReg(tae.table(), tr);
                    tblRegs[i] = tr;
                    if (tae.key() instanceof Expressions.IntegerLiteral il && il.value() >= 0 && il.value() <= 255) {
                        keyKinds[i] = 1;
                        keyConsts[i] = (int) il.value();
                        keyRegs[i] = -1;
                    } else if (tae.key() instanceof Expressions.StringLiteral sl) {
                        int kKey = addConst(sl.luaString());
                        if (kKey <= 255) {
                            keyKinds[i] = 2;
                            keyConsts[i] = kKey;
                            keyRegs[i] = -1;
                        } else {
                            keyKinds[i] = 0;
                            int kr = allocReg();
                            compileExprToReg(tae.key(), kr);
                            keyRegs[i] = kr;
                        }
                    } else {
                        keyKinds[i] = 0;
                        int kr = allocReg();
                        compileExprToReg(tae.key(), kr);
                        keyRegs[i] = kr;
                    }
                } else {
                    tblRegs[i] = -1;
                    keyRegs[i] = -1;
                }
            }

            for (int i = 0; i < nvars; i++) {
                // Lua 5.4 (lparser.c: restassign): variable store is emitted after RHS expression has been parsed,
                // so its line info matches the line where the RHS expression ends.
                int assignLine = (i < as.values().size()) ? exprEndLine(as.values().get(i)) : as.line();
                if (tblRegs[i] >= 0) {
                    if (keyKinds[i] == 1) {
                        emit(Instruction.encodeABC(OpCode.OP_SETI, tblRegs[i], keyConsts[i], valRegs[i], 0), assignLine);
                    } else if (keyKinds[i] == 2) {
                        emit(Instruction.encodeABC(OpCode.OP_SETFIELD, tblRegs[i], keyConsts[i], valRegs[i], 0), assignLine);
                    } else {
                        emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tblRegs[i], keyRegs[i], valRegs[i], 0), assignLine);
                    }
                } else {
                    assignTarget(as.targets().get(i), valRegs[i], assignLine);
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
                if (tae.key() instanceof Expressions.IntegerLiteral il && il.value() >= 0 && il.value() <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_SETI, tblReg, (int) il.value(), valReg, 0), line);
                } else if (tae.key() instanceof Expressions.StringLiteral sl) {
                    int kKey = addConst(sl.luaString());
                    if (kKey <= 255) {
                        emit(Instruction.encodeABC(OpCode.OP_SETFIELD, tblReg, kKey, valReg, 0), line);
                    } else {
                        int keyReg = compileExprToAnyReg(tae.key());
                        emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tblReg, keyReg, valReg, 0), line);
                    }
                } else {
                    int keyReg = compileExprToAnyReg(tae.key());
                    emit(Instruction.encodeABC(OpCode.OP_SETTABLE, tblReg, keyReg, valReg, 0), line);
                }
            }
        }



        void compileIf(Statements.IfStmt is) {
            List<Integer> exitJmps = new ArrayList<>();
            for (int i = 0; i < is.branches().size(); i++) {
                Statements.IfBranch branch = is.branches().get(i);
                int condReg = compileExprToAnyReg(branch.condition());
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), branch.thenLine());
                int falseJmp = emitJmp(branch.thenLine());
                compileBlock(branch.block());
                // In Lua 5.4 (lparser.c: test_then_block), only emit jump over following else/elseif,
                // and use the line of the last statement in the 'then' block.
                if (i < is.branches().size() - 1 || is.elseBlock() != null) {
                    int exitLine = !branch.block().statements().isEmpty()
                            ? branch.block().statements().get(branch.block().statements().size() - 1).line()
                            : branch.thenLine();
                    exitJmps.add(emitJmp(exitLine));
                }
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
            clearDeadSlotsAtLoopEntry(ws.line());
            int loopStart = code.size();
            int condReg = compileExprToAnyReg(ws.condition());
            emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), ws.line());
            int falseJmp = emitJmp(ws.line());

            LoopInfo loop = new LoopInfo(loopStart, locals.size());
            loopStack.push(loop);

            compileBlock(ws.body());
            // Lua 5.4 (lparser.c: whilestat): back-jump is emitted before check_match(TK_END),
            // so its line info matches the last line of the body statements.
            int backLine = (ws.body() != null && !ws.body().statements().isEmpty())
                    ? ws.body().statements().get(ws.body().statements().size() - 1).line()
                    : ws.line();
            int backJmp = emitJmp(backLine);
            patchJmp(backJmp, loopStart);

            int loopEnd = code.size();
            patchJmp(falseJmp, loopEnd);
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
        }

        void compileRepeat(Statements.RepeatStmt rs) {
            // Repeat body runs before the condition, so the entry cleanup
            // carries the body's first line (not the condition's) to keep
            // line-hook event sequencing C-like.
            int entryLine = rs.condition().line();
            if (rs.body() != null && !rs.body().statements().isEmpty()) {
                entryLine = rs.body().statements().get(0).line();
            }
            clearDeadSlotsAtLoopEntry(entryLine);
            int loopStart = code.size();
            int baseLocals = locals.size();
            int baseFreereg = freereg;
            LoopInfo loop = new LoopInfo(loopStart, baseLocals);
            loopStack.push(loop);

            for (Statement stmt : rs.body().statements()) {
                compileStatement(stmt);
            }

            // Lua 5.4 (lparser.c: repeatstat): condition test and jump back are evaluated at the 'until' line
            int condLine = rs.condition().line();
            int condReg = compileExprToAnyReg(rs.condition());

            boolean hasClose = false;
            int firstCloseReg = Integer.MAX_VALUE;
            int firstCapturedReg = Integer.MAX_VALUE;
            for (int i = baseLocals; i < locals.size(); i++) {
                LocalVar v = locals.get(i);
                if (v.attr == Statements.VariableAttribute.CLOSE) {
                    hasClose = true;
                }
                if (v.reg < firstCloseReg) {
                    firstCloseReg = v.reg;
                }
                if (v.isCaptured && v.reg < firstCapturedReg) {
                    firstCapturedReg = v.reg;
                }
            }

            if (hasClose && firstCloseReg != Integer.MAX_VALUE) {
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 1, 0), condLine);
                int exitJmpTarget = emitJmp(condLine);
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, firstCloseReg, 0, 0), condLine);
                int backJmp = emitJmp(condLine);
                patchJmp(backJmp, loopStart);
                patchJmp(exitJmpTarget, code.size());
            } else {
                if (firstCapturedReg != Integer.MAX_VALUE) {
                    // Lua 5.4: upvalues declared in repeat body must be closed before
                    // jumping back, so next iteration captures fresh ones.
                    // (Re-entry reuses registers; without CLOSE all iterations share one upvalue.)
                    emit(Instruction.encodeABC(OpCode.OP_CLOSE, firstCapturedReg, 0, 0), condLine);
                }
                emit(Instruction.encodeABC(OpCode.OP_TEST, condReg, 0, 0), condLine);
                int repeatJmp = emitJmp(condLine);
                patchJmp(repeatJmp, loopStart);
            }

            closeLocalsTo(baseLocals, condLine);
            freeRegs(baseFreereg);

            int loopEnd = code.size();
            for (int brk : loop.breakList) {
                patchJmp(brk, loopEnd);
            }
            loopStack.pop();
        }

        void compileForNumeric(Statements.ForNumericStmt fns) {
            clearDeadSlotsAtLoopEntry(fns.line());
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

            // Lua 5.4: Each iteration creates a fresh variable; close open upvalues so closures do not leak across iterations
            LocalVar loopVar = locals.get(baseLocals + 3);
            if (loopVar.isCaptured) {
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, varReg, 0, 0), fns.line());
            }

            // Lua 5.4 (lparser.c: forbody): luaK_fixline(fs, line) pretends OP_FORLOOP is on the first line
            int loopPc = emit(Instruction.encodeABx(OpCode.OP_FORLOOP, initReg, 0), fns.line());
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
            clearDeadSlotsAtLoopEntry(fgs.line());
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

            int iterLine = fgs.iterators().isEmpty() ? fgs.line() : fgs.iterators().get(0).line();
            int prepPc = emit(Instruction.encodeABx(OpCode.OP_TFORPREP, fReg, 0), iterLine);

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

            // Lua 5.4: Close open upvalues for declared iteration variables before advancing to the next iteration
            boolean genVarCaptured = false;
            int firstGenVarReg = Integer.MAX_VALUE;
            for (int i = baseLocals + 4; i < locals.size(); i++) {
                LocalVar v = locals.get(i);
                if (v.isCaptured) {
                    genVarCaptured = true;
                    if (v.reg < firstGenVarReg) firstGenVarReg = v.reg;
                }
            }
            if (genVarCaptured && firstGenVarReg != Integer.MAX_VALUE) {
                emit(Instruction.encodeABC(OpCode.OP_CLOSE, firstGenVarReg, 0, 0), fgs.line());
            }

            // In Lua 5.4 (lparser.c: forbody), OP_TFORCALL and OP_TFORLOOP use the line of the iterator expression after 'in'
            int callPc = emit(Instruction.encodeABC(OpCode.OP_TFORCALL, fReg, 0, fgs.variableNames().size()), iterLine);
            int loopPc = emit(Instruction.encodeABx(OpCode.OP_TFORLOOP, fReg, 0), iterLine);

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
            // Solve forward jumps originating within the current lexical block level (matching Lua C solvegotos)
            for (int i = currentBlockFirstGoto; i < pendingGotos.size(); ) {
                GotoDesc gt = pendingGotos.get(i);
                if (gt.name.equals(lb.name)) {
                    if (gt.nactvar < lb.nactvar) {
                        String varname = (gt.nactvar < locals.size()) ? locals.get(gt.nactvar).name : "?";
                        throw new LuaException("<goto " + gt.name + "> at line " + gt.line + " jumps into the scope of local '" + varname + "'");
                    }
                    patchJmp(gt.pc, lb.pc);
                    if (gt.localsSnapshot != null) {
                        for (int j = gt.localsSnapshot.size() - 1; j >= lb.nactvar; j--) {
                            LocalVar v = gt.localsSnapshot.get(j);
                            if (v.isCaptured || v.attr == Statements.VariableAttribute.CLOSE) {
                                needsClose = true;
                                break;
                            }
                        }
                    }
                    pendingGotos.remove(i);
                } else {
                    i++;
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
            // Lua 5.4 semantics: OP_CLOSURE is emitted on the line of 'end' (lastLineDefined).
            // The variable store fixes line back to definition line (fds.line()).
            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, clReg, protoIdx), fds.endLine());
            assignTarget(fds.targetName(), clReg, fds.line());
            freeRegs(clReg);
        }

        void compileLocalFunctionDef(Statements.LocalFunctionDefStmt lfds) {
            int r = allocReg();
            registerLocal(lfds.name(), r, Statements.VariableAttribute.NONE);

            LuaProto childProto = compileChildFunction(lfds.name(), lfds.parameters(), lfds.isVararg(), lfds.body(), lfds.line(), lfds.endLine());
            int protoIdx = protos.size();
            protos.add(childProto);

            // Lua 5.4 semantics: OP_CLOSURE for local function is emitted on the line of 'end' (lastLineDefined).
            emit(Instruction.encodeABx(OpCode.OP_CLOSURE, r, protoIdx), lfds.endLine());
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

        int exprEndLine(Expression expr) {
            if (expr instanceof Expressions.BinaryExpr be) {
                return exprEndLine(be.right());
            }
            if (expr instanceof Expressions.TableAccessExpr tae) {
                return exprEndLine(tae.key());
            }
            if (expr instanceof Expressions.FunctionCallExpr fce) {
                if (!fce.arguments().isEmpty()) {
                    return exprEndLine(fce.arguments().get(fce.arguments().size() - 1));
                }
                return fce.line();
            }
            if (expr instanceof Expressions.TableConstructorExpr tce) {
                if (!tce.fields().isEmpty()) {
                    return exprEndLine(tce.fields().get(tce.fields().size() - 1).value());
                }
                return tce.line();
            }
            if (expr instanceof Expressions.ParenExpr pe) {
                return exprEndLine(pe.expression());
            }
            return expr != null ? expr.line() : 1;
        }

        int compileExprToAnyReg(Expression expr) {
            return compileExprToAnyReg(expr, -1);
        }

        int compileExprToAnyReg(Expression expr, int lineOverride) {
            if (expr instanceof Expressions.VariableExpr ve) {
                LocalVar lv = findLocal(ve.name());
                if (lv != null) return lv.reg;
            }
            int r = allocReg();
            compileExprToReg(expr, r, null, lineOverride);
            return r;
        }

        void compileExprToReg(Expression expr, int targetReg) {
            compileExprToReg(expr, targetReg, null, -1);
        }

        void compileExprToReg(Expression expr, int targetReg, String inferredName) {
            compileExprToReg(expr, targetReg, inferredName, -1);
        }

        void compileExprToReg(Expression expr, int targetReg, String inferredName, int lineOverride) {
            int effectiveLine = lineOverride > 0 ? lineOverride : (expr != null ? expr.line() : 1);
            if (expr instanceof Expressions.NilLiteral nl) {
                emit(Instruction.encodeABC(OpCode.OP_LOADNIL, targetReg, 0, 0), effectiveLine);
            } else if (expr instanceof Expressions.BooleanLiteral bl) {
                emit(Instruction.encodeABC(bl.value() ? OpCode.OP_LOADTRUE : OpCode.OP_LOADFALSE, targetReg, 0, 0), effectiveLine);
            } else if (expr instanceof Expressions.IntegerLiteral il) {
                if (il.value() >= -Instruction.OFFSET_sBx && il.value() <= Instruction.OFFSET_sBx) {
                    emit(Instruction.encodesBx(OpCode.OP_LOADI, targetReg, (int) il.value()), effectiveLine);
                } else {
                    int k = addConst(LuaInteger.valueOf(il.value()));
                    emitLoadK(targetReg, k, effectiveLine);
                }
            } else if (expr instanceof Expressions.FloatLiteral fl) {
                int k = addConst(LuaFloat.valueOf(fl.value()));
                emitLoadK(targetReg, k, effectiveLine);
            } else if (expr instanceof Expressions.StringLiteral sl) {
                int k = addConst(sl.luaString());
                emitLoadK(targetReg, k, effectiveLine);
            } else if (expr instanceof Expressions.VariableExpr ve) {
                LocalVar lv = findLocal(ve.name());
                if (lv != null) {
                    if (lv.reg != targetReg) {
                        emit(Instruction.encodeABC(OpCode.OP_MOVE, targetReg, lv.reg, 0), effectiveLine);
                    }
                    return;
                }
                int up = findOrAddUpval(ve.name());
                if (up >= 0) {
                    emit(Instruction.encodeABC(OpCode.OP_GETUPVAL, targetReg, up, 0), effectiveLine);
                    return;
                }
                LocalVar envLocal = findLocal("_ENV");
                int kKey = addConst(LuaString.valueOf(ve.name()));
                if (envLocal != null) {
                    if (kKey <= 255) {
                        emit(Instruction.encodeABC(OpCode.OP_GETFIELD, targetReg, envLocal.reg, kKey, 0), effectiveLine);
                    } else {
                        int kReg = allocReg();
                        emitLoadK(kReg, kKey, effectiveLine);
                        emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, envLocal.reg, kReg, 0), effectiveLine);
                        freeRegs(kReg);
                    }
                    return;
                }
                int envUp = findOrAddUpval("_ENV");
                if (kKey <= 255) {
                    emit(Instruction.encodeABC(OpCode.OP_GETTABUP, targetReg, envUp, kKey, 0), effectiveLine);
                } else {
                    emit(Instruction.encodeABC(OpCode.OP_GETUPVAL, targetReg, envUp, 0), effectiveLine);
                    int kReg = allocReg();
                    emitLoadK(kReg, kKey, effectiveLine);
                    emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, targetReg, kReg, 0), effectiveLine);
                    freeRegs(kReg);
                }
                return;
            } else if (expr instanceof Expressions.BinaryExpr be) {
                compileBinaryExpr(be, targetReg);
            } else if (expr instanceof Expressions.UnaryExpr ue) {
                compileUnaryExpr(ue, targetReg);
            } else if (expr instanceof Expressions.TableAccessExpr tae) {
                int tblReg = compileExprToAnyReg(tae.table());
                if (tae.key() instanceof Expressions.IntegerLiteral il && il.value() >= 0 && il.value() <= 255) {
                    // Lua 5.4 (lcode.c: luaK_indexed / isCint): integer constant index in 0..255 uses OP_GETI directly
                    emit(Instruction.encodeABC(OpCode.OP_GETI, targetReg, tblReg, (int) il.value(), 0), effectiveLine);
                } else if (tae.key() instanceof Expressions.StringLiteral sl) {
                    int kKey = addConst(sl.luaString());
                    if (kKey <= 255) {
                        // Lua 5.4 (lcode.c: luaK_indexed / isKstr): literal string index in constant table <= 255 uses OP_GETFIELD directly
                        emit(Instruction.encodeABC(OpCode.OP_GETFIELD, targetReg, tblReg, kKey, 0), effectiveLine);
                    } else {
                        int keyReg = compileExprToAnyReg(tae.key());
                        emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, tblReg, keyReg, 0), effectiveLine);
                    }
                } else {
                    int keyReg = compileExprToAnyReg(tae.key());
                    emit(Instruction.encodeABC(OpCode.OP_GETTABLE, targetReg, tblReg, keyReg, 0), effectiveLine);
                }
            } else if (expr instanceof Expressions.TableConstructorExpr tce) {
                compileTableConstructor(tce, targetReg);
            } else if (expr instanceof Expressions.FunctionCallExpr fce) {
                compileFunctionCall(fce, targetReg, 1);
            } else if (expr instanceof Expressions.FunctionDefExpr fde) {
                LuaProto child = compileChildFunction(inferredName, fde.parameters(), fde.isVararg(), fde.body(), fde.line(), fde.endLine());
                int pIdx = protos.size();
                protos.add(child);
                // Lua 5.4 semantics: OP_CLOSURE for function expression is emitted on the line of 'end' (fde.endLine()).
                emit(Instruction.encodeABx(OpCode.OP_CLOSURE, targetReg, pIdx), fde.endLine());
            } else if (expr instanceof Expressions.VarargLiteral val) {
                emit(Instruction.encodeABC(OpCode.OP_VARARG, targetReg, 0, 2), effectiveLine);
            } else if (expr instanceof Expressions.ParenExpr pe) {
                compileExprToReg(pe.expression(), targetReg, null, lineOverride);
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
                // In Lua 5.4 (lcode.c: exp2RK), if constant index exceeds 8-bit argC (255),
                // load the method name into a register and emit OP_SELF with k=0.
                if (kMethod <= Instruction.MASK_C) {
                    emit(Instruction.encodeABC(OpCode.OP_SELF, funcReg, tblReg, kMethod, 1), fce.line());
                } else {
                    int methodReg = allocReg();
                    emitLoadK(methodReg, kMethod, fce.line());
                    emit(Instruction.encodeABC(OpCode.OP_SELF, funcReg, tblReg, methodReg, 0), fce.line());
                    freeRegs(methodReg);
                }
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
            // Lua 5.4 (lparser.c & lcode.c: luaK_infix):
            // The 1st operand is discharged after reading the operator, so its emitted instruction
            // uses the operator line (be.line()).
            if (be.operator() == TokenType.AND) {
                int regA = compileExprToAnyReg(be.left(), be.line());
                emit(Instruction.encodeABC(OpCode.OP_TESTSET, targetReg, regA, 0, 0), be.line());
                int jmp = emitJmp(be.line());
                compileExprToReg(be.right(), targetReg);
                patchJmp(jmp, code.size());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.OR) {
                int regA = compileExprToAnyReg(be.left(), be.line());
                emit(Instruction.encodeABC(OpCode.OP_TESTSET, targetReg, regA, 0, 1), be.line());
                int jmp = emitJmp(be.line());
                compileExprToReg(be.right(), targetReg);
                patchJmp(jmp, code.size());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.TILDE_EQUAL) {
                int b = compileExprToAnyReg(be.left(), be.line());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_EQ, b, c, 0, 1), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.GREATER) {
                int b = compileExprToAnyReg(be.left(), be.line());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_LT, c, b, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.GREATER_EQUAL) {
                int b = compileExprToAnyReg(be.left(), be.line());
                int c = compileExprToAnyReg(be.right());
                emit(Instruction.encodeABC(OpCode.OP_LE, c, b, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LFALSESKIP, targetReg, 0, 0), be.line());
                emit(Instruction.encodeABC(OpCode.OP_LOADTRUE, targetReg, 0, 0), be.line());
                freeRegs(Math.max(saveFreereg, targetReg + 1));
                return;
            }
            if (be.operator() == TokenType.DOT_DOT) {
                int save = freereg;
                compileExprToReg(be.left(), targetReg, null, be.line());
                freereg = targetReg + 2;
                compileExprToReg(be.right(), targetReg + 1);
                emit(Instruction.encodeABC(OpCode.OP_CONCAT, targetReg, 2, 0), be.line());
                freeRegs(save);
                return;
            }

            int b;
            if (!(be.left() instanceof Expressions.VariableExpr) && targetReg >= minFreereg()) {
                // Lua 5.4 semantics (lcode.c: codearith):
                // Evaluate left operand directly into targetReg when targetReg is an unreserved temporary slot.
                compileExprToReg(be.left(), targetReg, null, be.line());
                b = targetReg;
                freereg = Math.max(freereg, targetReg + 1);
            } else {
                b = compileExprToAnyReg(be.left(), be.line());
            }
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

                    int regStart = freereg;
                    if (tf.key() instanceof Expressions.IntegerLiteral il && il.value() >= 0 && il.value() <= 255) {
                        int valReg = compileExprToAnyReg(tf.value());
                        emit(Instruction.encodeABC(OpCode.OP_SETI, targetReg, (int) il.value(), valReg, 0), tce.line());
                    } else if (tf.key() instanceof Expressions.StringLiteral sl) {
                        int kKey = addConst(sl.luaString());
                        if (kKey <= 255) {
                            int valReg = compileExprToAnyReg(tf.value());
                            emit(Instruction.encodeABC(OpCode.OP_SETFIELD, targetReg, kKey, valReg, 0), tce.line());
                        } else {
                            int keyReg = compileExprToAnyReg(tf.key());
                            int valReg = compileExprToAnyReg(tf.value());
                            emit(Instruction.encodeABC(OpCode.OP_SETTABLE, targetReg, keyReg, valReg, 0), tce.line());
                        }
                    } else {
                        int keyReg = compileExprToAnyReg(tf.key());
                        int valReg = compileExprToAnyReg(tf.value());
                        emit(Instruction.encodeABC(OpCode.OP_SETTABLE, targetReg, keyReg, valReg, 0), tce.line());
                    }
                    // Free key and value registers immediately so freereg does not exhaust 8-bit instruction operand limit (>255)
                    freeRegs(regStart);
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

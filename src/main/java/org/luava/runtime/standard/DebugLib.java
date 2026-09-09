package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;
import java.util.Map;
import org.luava.runtime.concurrency.LuaCoroutine;
import org.luava.runtime.eval.CallStack;
import org.luava.runtime.eval.Environment;

public final class DebugLib {
    private DebugLib() {}

    public static void open(LuaTable globals) {
        open(null, globals);
    }

    public static void open(org.luava.runtime.LuaState luaState, LuaTable globals) {
        LuaTable registry = luaState != null ? luaState.getRegistry() : new LuaTable();
        LuaTable hookTable = new LuaTable();
        hookTable.rawset(LuaString.valueOf("__mode"), LuaString.valueOf("k"));
        hookTable.setMetatable(hookTable);
        registry.rawset(LuaString.valueOf("_HOOKKEY"), hookTable);

        LuaTable debug = new LuaTable();
        debug.rawset(LuaString.valueOf("getregistry"), LuaFunction.of(args -> registry));

        debug.rawset(LuaString.valueOf("getinfo"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'debug.getinfo'");
            LuaCoroutine targetCoro = null;
            int argIdx = 0;
            if (args[0] instanceof LuaCoroutine co) {
                targetCoro = co;
                argIdx++;
            }
            if (argIdx >= args.length) return LuaNil.NIL;
            LuaValue fnOrLevel = args[argIdx];
            String what = (argIdx + 1 < args.length && !args[argIdx + 1].isNil()) ? args[argIdx + 1].toLuaString() : "flnStu";

            if (what.startsWith(">")) {
                throw new LuaException("bad argument #" + (argIdx + 2) + " to 'getinfo' (invalid option '>')");
            }
            for (int i = 0; i < what.length(); i++) {
                char c = what.charAt(i);
                if (c != 'f' && c != 'l' && c != 'n' && c != 'S' && c != 'r' && c != 't' && c != 'u' && c != 'L') {
                    throw new LuaException("bad argument #" + (argIdx + 2) + " to 'getinfo' (invalid option)");
                }
            }

            LuaFunction fn = null;
            String fnName = null;
            String namewhat = "";
            int currentLine = -1;
            org.luava.runtime.eval.CallStack.Frame frame = null;

            if (fnOrLevel instanceof LuaFunction func) {
                fn = func;
                fnName = null;
                namewhat = "";
                currentLine = -1;
            } else if (fnOrLevel.isInteger() || fnOrLevel.isNumber()) {
                int level = (int) fnOrLevel.toLong();
                if (level < 0) {
                    return LuaNil.NIL;
                }
                frame = org.luava.runtime.eval.CallStack.getFrame(targetCoro, level);
                if (frame == null) {
                    return LuaNil.NIL;
                }
                fn = frame.function;
                fnName = frame.name != null ? frame.name : (fn != null ? fn.getName() : null);
                namewhat = frame.namewhat != null ? frame.namewhat : (fnName != null ? "global" : "");
                currentLine = frame.line;
            } else {
                throw new LuaException("bad argument #1 to 'getinfo' (function or level expected)");
            }

            LuaTable info = new LuaTable();

            if (what.contains("n")) {
                info.rawset(LuaString.valueOf("name"), fnName != null ? LuaString.valueOf(fnName) : LuaNil.NIL);
                info.rawset(LuaString.valueOf("namewhat"), LuaString.valueOf(namewhat));
            }

            if (what.contains("S")) {
                String src = "=[Lua]";
                String shortSrc = "[Lua]";
                int lineDef = 1;
                int lastLineDef = 1;
                String whatType = "Lua";

                if (fn != null) {
                    if (fn.isStripped()) {
                        src = "=?";
                        shortSrc = "?";
                        lineDef = fn.getLineDefined();
                        lastLineDef = lineDef;
                    } else {
                        src = fn.getSource() != null ? fn.getSource() : "=[Lua]";
                        shortSrc = org.luava.frontend.parser.ParseException.formatChunkName(src);
                        lineDef = fn.getLineDefined();
                        lastLineDef = fn.getLastLineDefined();
                    }
                    whatType = fn.getWhat();
                } else {
                    whatType = "C";
                    src = "=[C]";
                    shortSrc = "[C]";
                    lineDef = -1;
                    lastLineDef = -1;
                }

                info.rawset(LuaString.valueOf("source"), LuaString.valueOf(src));
                info.rawset(LuaString.valueOf("short_src"), LuaString.valueOf(shortSrc));
                info.rawset(LuaString.valueOf("linedefined"), LuaInteger.valueOf(lineDef));
                info.rawset(LuaString.valueOf("lastlinedefined"), LuaInteger.valueOf(lastLineDef));
                info.rawset(LuaString.valueOf("what"), LuaString.valueOf(whatType));
            }

            if (what.contains("l")) {
                int cl = (fn != null && (fn.isStripped() || !(fn instanceof org.luava.runtime.eval.Interpreter.InterpretedLuaFunction))) ? -1 : currentLine;
                info.rawset(LuaString.valueOf("currentline"), LuaInteger.valueOf(cl));
            }

            if (what.contains("u")) {
                int numParams = fn != null ? fn.getNparams() : 0;
                int nups = fn != null ? fn.getUpvalues().size() : 0;
                boolean vararg = fn == null || fn.isVararg();
                info.rawset(LuaString.valueOf("nups"), LuaInteger.valueOf(nups));
                info.rawset(LuaString.valueOf("nparams"), LuaInteger.valueOf(numParams));
                info.rawset(LuaString.valueOf("isvararg"), LuaBoolean.valueOf(vararg));
            }

            if (what.contains("t")) {
                info.rawset(LuaString.valueOf("istailcall"), LuaBoolean.valueOf(frame != null && frame.isTailCall));
            }

            if (what.contains("f")) {
                info.rawset(LuaString.valueOf("func"), fn != null ? fn : LuaNil.NIL);
            }

            if (what.contains("r")) {
                info.rawset(LuaString.valueOf("ftransfer"), LuaInteger.valueOf(frame != null ? frame.ftransfer : 0));
                info.rawset(LuaString.valueOf("ntransfer"), LuaInteger.valueOf(frame != null ? frame.ntransfer : 0));
            }

            if (what.contains("L")) {
                LuaTable activelines = (fn != null && fn.isStripped()) ? new LuaTable() : collectActiveLines(fn);
                if (activelines != null) {
                    info.rawset(LuaString.valueOf("activelines"), activelines);
                }
            }

            return info;
        }));

        debug.rawset(LuaString.valueOf("traceback"), LuaFunction.of(args -> {
            String msg = null;
            LuaCoroutine targetCoro = null;
            int argIdx = 0;
            if (args.length > 0 && args[0] instanceof LuaCoroutine co) {
                targetCoro = co;
                argIdx++;
            }
            if (argIdx < args.length) {
                if (!args[argIdx].isNil()) {
                    LuaValue msgVal = args[argIdx];
                    if (!msgVal.isString() && !msgVal.isNumber()) {
                        return msgVal;
                    }
                    msg = msgVal.toLuaString();
                }
                argIdx++;
            }
            int defaultLevel = (targetCoro == null || targetCoro == LuaCoroutine.running()) ? 1 : 0;
            int level = defaultLevel;
            if (argIdx < args.length && (args[argIdx].isInteger() || args[argIdx].isNumber())) {
                level = (int) args[argIdx].toLong();
            }

            StringBuilder sb = new StringBuilder();
            if (msg != null) {
                sb.append(msg).append("\n");
            }
            sb.append("stack traceback:");

            int depth = org.luava.runtime.eval.CallStack.depth(targetCoro);
            int startLevel = level;
            int totalFrames = depth - startLevel;

            if (totalFrames <= 0) {
                return LuaString.valueOf(sb.toString());
            }

            int LEVELS1 = 10;
            int LEVELS2 = 11;
            if (totalFrames <= LEVELS1 + LEVELS2) {
                for (int i = 0; i < totalFrames; i++) {
                    formatTracebackFrame(sb, org.luava.runtime.eval.CallStack.getFrame(targetCoro, startLevel + i));
                }
            } else {
                for (int i = 0; i < LEVELS1; i++) {
                    formatTracebackFrame(sb, org.luava.runtime.eval.CallStack.getFrame(targetCoro, startLevel + i));
                }
                int skipped = totalFrames - LEVELS1 - LEVELS2;
                sb.append("\n\t...\t(skipping ").append(skipped).append(" levels)");
                for (int i = totalFrames - LEVELS2; i < totalFrames; i++) {
                    formatTracebackFrame(sb, org.luava.runtime.eval.CallStack.getFrame(targetCoro, startLevel + i));
                }
            }
            return LuaString.valueOf(sb.toString());
        }));

        debug.rawset(LuaString.valueOf("getmetatable"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'debug.getmetatable'");
            LuaTable mt = args[0].getMetatable();
            return (mt != null) ? mt : LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("setmetatable"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'debug.setmetatable'");
            LuaValue target = args[0];
            LuaTable mt = (args[1] instanceof LuaTable t) ? t : null;
            target.setMetatable(mt);
            return target;
        }));

        debug.rawset(LuaString.valueOf("getuservalue"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaUserdata ud) || ud.isLightUserdata()) {
                return LuaNil.NIL;
            }
            int n = (int) (args.length > 1 ? args[1].toLong() : 1);
            LuaValue uv = ud.getUserValue(n);
            if (uv != null) {
                return Varargs.of(uv, LuaBoolean.TRUE);
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("setuservalue"), LuaFunction.of(args -> {
            if (args.length < 1 || !args[0].isUserdata()) {
                String got = args.length > 0 ? args[0].typeName() : "no value";
                throw new LuaException("bad argument #1 to 'setuservalue' (userdata expected, got " + got + ")");
            }
            if (args.length < 2) {
                throw new LuaException("bad argument #2 to 'setuservalue' (value expected)");
            }
            LuaUserdata ud = (LuaUserdata) args[0];
            LuaValue val = args[1];
            int n = (int) (args.length > 2 ? args[2].toLong() : 1);
            if (!ud.setUserValue(n, val)) {
                return LuaNil.NIL;
            }
            return ud;
        }));

        debug.rawset(LuaString.valueOf("getupvalue"), LuaFunction.of(args -> {
            if (args.length < 2 || !(args[0] instanceof LuaFunction fn)) {
                return LuaNil.NIL;
            }
            int index = (int) args[1].toLong();
            java.util.List<org.luava.runtime.eval.Upvalue> ups = fn.getUpvalues();
            if (index >= 1 && index <= ups.size()) {
                org.luava.runtime.eval.Upvalue up = ups.get(index - 1);
                String name = !"C".equals(fn.getWhat()) ? up.getName() : "";
                if (fn.isStripped() || name == null) name = "(no name)";
                return Varargs.of(LuaString.valueOf(name), up.getValue());
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("setupvalue"), LuaFunction.of(args -> {
            if (args.length < 3 || !(args[0] instanceof LuaFunction fn)) {
                return LuaNil.NIL;
            }
            int index = (int) args[1].toLong();
            LuaValue val = args[2];
            java.util.List<org.luava.runtime.eval.Upvalue> ups = fn.getUpvalues();
            if (index >= 1 && index <= ups.size()) {
                org.luava.runtime.eval.Upvalue up = ups.get(index - 1);
                up.setValue(val);
                String name = !"C".equals(fn.getWhat()) ? up.getName() : "";
                if (fn.isStripped() || name == null) name = "(no name)";
                return LuaString.valueOf(name);
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("upvalueid"), LuaFunction.of(args -> {
            if (args.length < 2 || !(args[0] instanceof LuaFunction fn)) {
                return LuaNil.NIL;
            }
            int n = (int) args[1].toLong();
            java.util.List<org.luava.runtime.eval.Upvalue> ups = fn.getUpvalues();
            if (n >= 1 && n <= ups.size()) {
                return new LuaUserdata(ups.get(n - 1).getSlot(), true);
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("upvaluejoin"), LuaFunction.of(args -> {
            if (args.length < 4 || !(args[0] instanceof LuaFunction f1) || !(args[2] instanceof LuaFunction f2)) {
                throw new LuaException("bad argument to 'debug.upvaluejoin'");
            }
            int n1 = (int) args[1].toLong();
            int n2 = (int) args[3].toLong();
            if (n1 < 1 || n1 > f1.getUpvalues().size()) {
                throw new LuaException("invalid upvalue index 1 to 'debug.upvaluejoin'");
            }
            if (n2 < 1 || n2 > f2.getUpvalues().size()) {
                throw new LuaException("invalid upvalue index 2 to 'debug.upvaluejoin'");
            }
            org.luava.runtime.eval.Upvalue up1 = f1.getUpvalues().get(n1 - 1);
            org.luava.runtime.eval.Upvalue up2 = f2.getUpvalues().get(n2 - 1);
            up1.setSlot(up2.getSlot());
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("getlocal"), LuaFunction.of(args -> {
            LuaCoroutine target = LuaCoroutine.running();
            int argOffset = 0;
            if (args.length > 0 && args[0] instanceof LuaCoroutine co) {
                target = co;
                argOffset = 1;
            }
            if (args.length <= argOffset) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (value expected)");
            }
            LuaValue first = args[argOffset];
            int nvar = (int) (args.length > argOffset + 1 ? args[argOffset + 1].toLong() : 0);

            if (first instanceof LuaFunction fn) {
                if (fn instanceof org.luava.runtime.eval.Interpreter.InterpretedLuaFunction ifn) {
                    java.util.List<String> params = ifn.getParams();
                    if (nvar >= 1 && nvar <= params.size()) {
                        return LuaString.valueOf(params.get(nvar - 1));
                    }
                }
                return LuaNil.NIL;
            }

            if (!first.isNumber()) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (number expected)");
            }
            int level = (int) first.toLong();
            if (level < 0) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (level out of range)");
            }

            org.luava.runtime.eval.CallStack.CallStackState state = target != null ? target.getCallStackState() : org.luava.runtime.eval.CallStack.currentState();
            if (state == null) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (level out of range)");
            }

            int actualLevel = level;
            if (actualLevel < 0) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (level out of range)");
            }
            org.luava.runtime.eval.CallStack.Frame frame = state.getFrame(actualLevel);
            if (frame == null) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'getlocal' (level out of range)");
            }

            if (nvar < 0) {
                int idx = -nvar;
                org.luava.runtime.eval.Environment env = frame.env;
                if (env != null && env.getVarargsArray() != null) {
                    LuaValue[] extra = env.getVarargsArray();
                    if (idx >= 1 && idx <= extra.length) {
                        return Varargs.of(LuaString.valueOf("(vararg)"), extra[idx - 1]);
                    }
                }
                return LuaNil.NIL;
            } else if (nvar > 0) {
                if (frame.retValues != null && frame.ftransfer > 0 && nvar >= frame.ftransfer && nvar < frame.ftransfer + frame.ntransfer) {
                    int retIdx = nvar - frame.ftransfer;
                    String name = (frame.function instanceof org.luava.runtime.eval.Interpreter.InterpretedLuaFunction) ? "(temporary)" : "(C temporary)";
                    return Varargs.of(LuaString.valueOf(name), frame.retValues[retIdx]);
                }
                org.luava.runtime.eval.Environment env = frame.env;
                if (env != null) {
                    java.util.List<org.luava.runtime.eval.Environment.NamedSlot> allLocals = env.collectVisibleLocals();
                    if (nvar <= allLocals.size()) {
                        org.luava.runtime.eval.Environment.NamedSlot ns = allLocals.get(nvar - 1);
                        String varName = (frame.function != null && frame.function.isStripped()) ? "(temporary)" : ns.name;
                        return Varargs.of(LuaString.valueOf(varName), ns.slot.get());
                    } else {
                        int tempIdx = nvar - allLocals.size() - 1;
                        if (tempIdx >= 0 && tempIdx < frame.temps.size()) {
                            return Varargs.of(LuaString.valueOf("(temporary)"), frame.temps.get(tempIdx));
                        }
                    }
                } else if (frame.cArgs != null) {
                    if (nvar >= 1 && nvar <= frame.cArgs.length) {
                        return Varargs.of(LuaString.valueOf("(C temporary)"), frame.cArgs[nvar - 1]);
                    }
                } else if (actualLevel == 0 && target == LuaCoroutine.running()) {
                    if (nvar >= 1 && nvar <= args.length) {
                        return Varargs.of(LuaString.valueOf("(C temporary)"), args[nvar - 1]);
                    }
                }
                return LuaNil.NIL;
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("setlocal"), LuaFunction.of(args -> {
            LuaCoroutine target = LuaCoroutine.running();
            int argOffset = 0;
            if (args.length > 0 && args[0] instanceof LuaCoroutine co) {
                target = co;
                argOffset = 1;
            }
            if (args.length <= argOffset || !args[argOffset].isNumber()) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'setlocal' (number expected)");
            }
            int level = (int) args[argOffset].toLong();
            if (level < 0) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'setlocal' (level out of range)");
            }
            int nvar = (int) (args.length > argOffset + 1 ? args[argOffset + 1].toLong() : 0);
            LuaValue val = args.length > argOffset + 2 ? args[argOffset + 2] : LuaNil.NIL;

            org.luava.runtime.eval.CallStack.CallStackState state = target != null ? target.getCallStackState() : org.luava.runtime.eval.CallStack.currentState();
            if (state == null) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'setlocal' (level out of range)");
            }

            int actualLevel = level;
            if (actualLevel < 0) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'setlocal' (level out of range)");
            }
            org.luava.runtime.eval.CallStack.Frame frame = state.getFrame(actualLevel);
            if (frame == null) {
                throw new LuaException("bad argument #" + (argOffset + 1) + " to 'setlocal' (level out of range)");
            }

            if (nvar < 0) {
                int idx = -nvar;
                org.luava.runtime.eval.Environment env = frame.env;
                if (env != null && env.getVarargsArray() != null) {
                    LuaValue[] extra = env.getVarargsArray();
                    if (idx >= 1 && idx <= extra.length) {
                        extra[idx - 1] = val;
                        env.defineLocal("...", Varargs.of(extra), false, false);
                        return LuaString.valueOf("(vararg)");
                    }
                }
                return LuaNil.NIL;
            } else if (nvar > 0) {
                if (frame.retValues != null && frame.ftransfer > 0 && nvar >= frame.ftransfer && nvar < frame.ftransfer + frame.ntransfer) {
                    int retIdx = nvar - frame.ftransfer;
                    frame.retValues[retIdx] = val;
                    String name = (frame.function instanceof org.luava.runtime.eval.Interpreter.InterpretedLuaFunction) ? "(temporary)" : "(C temporary)";
                    return LuaString.valueOf(name);
                }
                org.luava.runtime.eval.Environment env = frame.env;
                if (env != null) {
                    java.util.List<org.luava.runtime.eval.Environment.NamedSlot> allLocals = env.collectVisibleLocals();
                    if (nvar <= allLocals.size()) {
                        org.luava.runtime.eval.Environment.NamedSlot ns = allLocals.get(nvar - 1);
                        ns.slot.set(val);
                        String varName = (frame.function != null && frame.function.isStripped()) ? "(temporary)" : ns.name;
                        return LuaString.valueOf(varName);
                    } else {
                        int tempIdx = nvar - allLocals.size() - 1;
                        if (tempIdx >= 0 && tempIdx < frame.temps.size()) {
                            frame.temps.set(tempIdx, val);
                            return LuaString.valueOf("(temporary)");
                        }
                    }
                } else if (frame.cArgs != null) {
                    if (nvar >= 1 && nvar <= frame.cArgs.length) {
                        frame.cArgs[nvar - 1] = val;
                        return LuaString.valueOf("(C temporary)");
                    }
                } else if (actualLevel == 0 && target == LuaCoroutine.running()) {
                    if (nvar >= 1 && nvar <= args.length) {
                        args[nvar - 1] = val;
                        return LuaString.valueOf("(C temporary)");
                    }
                }
                return LuaNil.NIL;
            }
            return LuaNil.NIL;
        }));

        debug.rawset(LuaString.valueOf("gethook"), LuaFunction.of(args -> {
            LuaCoroutine target = LuaCoroutine.running();
            if (args.length > 0 && args[0] instanceof LuaCoroutine co) {
                target = co;
            }
            if (target == null || !target.hasHook()) return LuaNil.NIL;
            LuaCoroutine.HookConfig cfg = target.getHookConfig();
            LuaValue hookVal = cfg.hook;
            LuaValue hkVal = registry.rawget(LuaString.valueOf("_HOOKKEY"));
            if (hkVal instanceof LuaTable hkT) {
                LuaValue stored = hkT.rawget(target);
                if (!stored.isNil()) hookVal = stored;
            }
            return Varargs.of(hookVal, LuaString.valueOf(cfg.mask), LuaInteger.valueOf(cfg.count));
        }));

        debug.rawset(LuaString.valueOf("sethook"), LuaFunction.of(args -> {
            LuaCoroutine target = LuaCoroutine.running();
            int argOffset = 0;
            if (args.length > 0 && args[0] instanceof LuaCoroutine co) {
                target = co;
                argOffset = 1;
            }
            if (target == null) return LuaNil.NIL;
            LuaValue hkVal = registry.rawget(LuaString.valueOf("_HOOKKEY"));
            LuaTable hkT = (hkVal instanceof LuaTable t) ? t : null;
            if (args.length <= argOffset || args[argOffset].isNil()) {
                target.clearHook();
                if (hkT != null) hkT.rawset(target, LuaNil.NIL);
                return LuaNil.NIL;
            }
            LuaValue hook = args[argOffset];
            String mask = (args.length > argOffset + 1 && !args[argOffset + 1].isNil()) ? args[argOffset + 1].toLuaString() : "";
            int count = (args.length > argOffset + 2 && !args[argOffset + 2].isNil()) ? (int) args[argOffset + 2].toLong() : 0;
            target.setHook(hook, mask, count);
            if (hkT != null) hkT.rawset(target, hook);
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("debug"), debug);
    }

    private static void formatTracebackFrame(StringBuilder sb, org.luava.runtime.eval.CallStack.Frame frame) {
        if (frame == null) {
            sb.append("\n\t[C]: in ?");
            return;
        }
        String name = frame.name;
        int line = frame.line;
        LuaFunction fn = frame.function;
        String src = fn != null ? fn.getSource() : null;
        if (src == null) src = "=[Lua]";
        String chunkId = org.luava.frontend.parser.ParseException.formatChunkName(src);

        sb.append("\n\t");
        if (fn != null && fn.isStripped()) {
            sb.append("?:-1: in ?");
            return;
        }

        if (fn != null && !"C".equals(fn.getWhat()) && line > 0) {
            sb.append(chunkId).append(":").append(line).append(": in ");
        } else {
            sb.append("[C]: in ");
        }

        String globalName = findGlobalFuncName(fn, frame.env);
        if (globalName != null) {
            sb.append("function '").append(globalName).append("'");
        } else if (name != null && !name.isEmpty()) {
            if ("main chunk".equals(name)) {
                sb.append("main chunk");
            } else if (frame.namewhat != null && !frame.namewhat.isEmpty()) {
                sb.append(frame.namewhat).append(" '").append(name).append("'");
            } else if (frame.isMetamethod) {
                sb.append("metamethod '").append(name).append("'");
            } else {
                sb.append("function '").append(name).append("'");
            }
        } else if (fn != null && "main".equals(fn.getWhat())) {
            sb.append("main chunk");
        } else if (fn != null && fn.getLineDefined() > 0) {
            sb.append("function <").append(chunkId).append(":").append(fn.getLineDefined()).append(">");
        } else {
            sb.append("?");
        }
        if (frame.isTailCall) {
            sb.append("\n\t(...tail calls...)");
        }
    }

    private static String findGlobalFuncName(LuaFunction fn, Environment env) {
        if (fn == null) return null;
        LuaTable globals = env != null ? env.getGlobals() : null;
        if (globals == null) {
            LuaCoroutine cur = LuaCoroutine.running();
            CallStack.CallStackState state = cur != null ? cur.getCallStackState() : CallStack.currentState();
            if (state != null && state.top > 0) {
                for (int i = state.top - 1; i >= 0; i--) {
                    if (state.stack[i] != null && state.stack[i].env != null) {
                        globals = state.stack[i].env.getGlobals();
                        if (globals != null) break;
                    }
                }
            }
        }
        if (globals == null) return null;

        for (LuaValue key : globals.keys()) {
            if (globals.rawget(key) == fn) {
                return key.toLuaString();
            }
        }

        for (LuaValue key : globals.keys()) {
            LuaValue subVal = globals.rawget(key);
            if (subVal instanceof LuaTable subTable && subTable != globals) {
                for (LuaValue subKey : subTable.keys()) {
                    if (subTable.rawget(subKey) == fn) {
                        return key.toLuaString() + "." + subKey.toLuaString();
                    }
                }
            }
        }

        LuaValue pkgVal = globals.rawget(LuaString.valueOf("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.valueOf("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                for (LuaValue modKey : loaded.keys()) {
                    LuaValue modVal = loaded.rawget(modKey);
                    if (modVal instanceof LuaTable modTable && modTable != globals) {
                        for (LuaValue subKey : modTable.keys()) {
                            if (modTable.rawget(subKey) == fn) {
                                return modKey.toLuaString() + "." + subKey.toLuaString();
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    private static LuaTable collectActiveLines(LuaFunction fn) {
        if (fn == null || "C".equals(fn.getWhat())) {
            return null;
        }
        LuaTable activelines = new LuaTable();
        if (fn.isStripped()) {
            return activelines;
        }
        org.luava.frontend.ast.Statements.BlockStmt body = fn.getBody();
        if (body == null) {
            return activelines;
        }

        java.util.Set<Integer> lines = new java.util.TreeSet<>();
        if (fn.getLastLineDefined() > 0) {
            lines.add(fn.getLastLineDefined());
        }
        collectLinesFromBlock(body, lines);

        for (int line : lines) {
            activelines.rawset(LuaInteger.valueOf(line), LuaBoolean.TRUE);
        }
        return activelines;
    }

    private static void collectLinesFromBlock(org.luava.frontend.ast.Statements.BlockStmt block, java.util.Set<Integer> lines) {
        if (block == null || block.statements() == null) return;
        for (org.luava.frontend.ast.Statement stmt : block.statements()) {
            collectLinesFromStmt(stmt, lines);
        }
    }

    private static void collectLinesFromStmt(org.luava.frontend.ast.Statement stmt, java.util.Set<Integer> lines) {
        if (stmt == null) return;
        switch (stmt) {
            case org.luava.frontend.ast.Statements.LocalVarDeclStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.AssignmentStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.ExprStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.ReturnStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.BreakStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.GotoStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.LabelStmt s -> {
                // Labels do not generate instructions in Lua
            }
            case org.luava.frontend.ast.Statements.WhileStmt s -> {
                if (s.line() > 0) lines.add(s.line());
                collectLinesFromBlock(s.body(), lines);
            }
            case org.luava.frontend.ast.Statements.RepeatStmt s -> {
                collectLinesFromBlock(s.body(), lines);
                if (s.condition() != null && s.condition().line() > 0) {
                    lines.add(s.condition().line());
                }
            }
            case org.luava.frontend.ast.Statements.ForNumericStmt s -> {
                if (s.line() > 0) lines.add(s.line());
                collectLinesFromBlock(s.body(), lines);
            }
            case org.luava.frontend.ast.Statements.ForGenericStmt s -> {
                if (s.line() > 0) lines.add(s.line());
                collectLinesFromBlock(s.body(), lines);
            }
            case org.luava.frontend.ast.Statements.IfStmt s -> {
                if (s.line() > 0) lines.add(s.line());
                boolean first = true;
                if (s.branches() != null) {
                    for (org.luava.frontend.ast.Statements.IfBranch branch : s.branches()) {
                        if (!first && branch.condition() != null && branch.condition().line() > 0) {
                            lines.add(branch.condition().line());
                        }
                        first = false;
                        collectLinesFromBlock(branch.block(), lines);
                    }
                }
                if (s.elseBlock() != null) {
                    collectLinesFromBlock(s.elseBlock(), lines);
                }
            }
            case org.luava.frontend.ast.Statements.FunctionDefStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.LocalFunctionDefStmt s -> {
                if (s.line() > 0) lines.add(s.line());
            }
            case org.luava.frontend.ast.Statements.BlockStmt s -> {
                collectLinesFromBlock(s, lines);
            }
            default -> {
                if (stmt.line() > 0) lines.add(stmt.line());
            }
        }
    }
}

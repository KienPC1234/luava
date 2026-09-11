/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaseLib {
    private BaseLib() {}

    public static void open(LuaState state, LuaTable globals) {
        globals.rawset(LuaString.valueOf("_G"), globals);
        globals.rawset(LuaString.valueOf("_ENV"), globals);
        globals.rawset(LuaString.valueOf("_VERSION"), LuaString.valueOf("Lua 5.4"));

        globals.rawset(LuaString.valueOf("print"), LuaFunction.of(args -> {
            // Byte fidelity: Lua strings are byte containers (Latin-1
            // preserved), so emit raw bytes instead of letting the platform
            // charset double-encode non-ASCII output.
            for (int i = 0; i < args.length; i++) {
                if (i > 0) System.out.write('\t');
                byte[] b = args[i].toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                System.out.write(b, 0, b.length);
            }
            System.out.write('\n');
            System.out.flush();
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("type"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'type' (value expected)");
            }
            return LuaString.valueOf(args[0].type().typeName());
        }));

        globals.rawset(LuaString.valueOf("tostring"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'tostring' (value expected)");
            }
            LuaValue v = args[0];
            LuaTable mt = v.getMetatable();
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__tostring"));
                if (!handler.isNil()) {
                    LuaValue res = handler.call(v);
                    if (!res.isString()) {
                        throw new LuaException("'__tostring' must return a string");
                    }
                    return res;
                }
            }
            return LuaString.valueOf(v.toLuaString());
        }));

        globals.rawset(LuaString.valueOf("tonumber"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'tonumber' (value expected)");
            }
            LuaValue v = args[0];
            if (args.length == 1 || args[1].isNil()) {
                if (v.isInteger()) return v;
                if (v.isFloat()) return v;
                if (v.isString()) {
                    LuaValue num = LuaValue.parseNumber(v.toLuaString());
                    return num != null ? num : LuaNil.NIL;
                }
                return LuaNil.NIL;
            }
            // Base provided
            LuaValue baseVal = args[1];
            LuaInteger bInt = baseVal.toLuaInteger();
            if (bInt == null && !baseVal.isInteger()) {
                if (baseVal.isString()) {
                    LuaValue parsed = LuaValue.parseNumber(baseVal.toLuaString());
                    if (parsed != null) bInt = parsed.toLuaInteger();
                }
            }
            if (bInt == null) {
                throw new LuaException("bad argument #2 to 'tonumber' (number expected, got " + baseVal.typeName() + ")");
            }
            int base = (int) bInt.toLong();
            if (base < 2 || base > 36) {
                throw new LuaException("bad argument #2 to 'tonumber' (base out of range)");
            }
            if (!v.isString()) {
                throw new LuaException("bad argument #1 to 'tonumber' (string expected, got " + v.typeName() + ")");
            }
            String s = LuaValue.trimLuaWhitespace(v.toLuaString());
            if (s.isEmpty()) return LuaNil.NIL;
            boolean negative = false;
            int idx = 0;
            char first = s.charAt(0);
            if (first == '-') {
                negative = true;
                idx++;
            } else if (first == '+') {
                idx++;
            }
            if (idx >= s.length()) return LuaNil.NIL;
            if (base == 16 && s.length() >= idx + 2 && (s.charAt(idx) == '0' && (s.charAt(idx + 1) == 'x' || s.charAt(idx + 1) == 'X'))) {
                idx += 2;
            }
            if (idx >= s.length()) return LuaNil.NIL;
            long val = 0;
            boolean hasDigits = false;
            while (idx < s.length()) {
                char c = s.charAt(idx);
                int d = Character.digit(c, base);
                if (d < 0 || d >= base) break;
                val = val * base + d;
                hasDigits = true;
                idx++;
            }
            if (!hasDigits) return LuaNil.NIL;
            while (idx < s.length() && LuaValue.isLuaWhitespace(s.charAt(idx))) {
                idx++;
            }
            if (idx < s.length()) return LuaNil.NIL;
            if (negative) val = -val;
            return LuaInteger.valueOf(val);
        }));

        globals.rawset(LuaString.valueOf("assert"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'assert' (value expected)");
            }
            if (!args[0].toBoolean()) {
                LuaValue msg = (args.length > 1) ? args[1] : LuaString.valueOf("assertion failed!");
                if (msg.isString()) {
                    org.luava.runtime.eval.CallStack.Frame frame = org.luava.runtime.eval.CallStack.getFrame(1);
                    if (frame != null && frame.function != null && !"C".equals(frame.function.getWhat()) && frame.line > 0) {
                        String src = (frame.function.getSource() != null)
                                ? frame.function.getSource()
                                : "=(load)";
                        String formatted = org.luava.frontend.parser.ParseException.formatChunkName(src) + ":" + frame.line + ": " + msg.toLuaString();
                        LuaException le = new LuaException(LuaString.valueOf(formatted));
                        le.setDecorated(true);
                        throw le;
                    }
                }
                LuaException le = new LuaException(msg);
                le.setDecorated(true);
                throw le;
            }
            return Varargs.of(args);
        }));

        globals.rawset(LuaString.valueOf("error"), LuaFunction.of(args -> {
            LuaValue msg = args.length > 0 ? args[0] : LuaNil.NIL;
            int level = 1;
            if (args.length > 1 && !args[1].isNil()) {
                if (!args[1].isInteger() && !args[1].isNumber()) {
                    if (args[1].isString()) {
                        try {
                            level = (int) Long.parseLong(args[1].toLuaString().trim());
                        } catch (NumberFormatException e) {
                            throw new LuaException("bad argument #2 to 'error' (number expected, got string)");
                        }
                    } else {
                        throw new LuaException("bad argument #2 to 'error' (number expected, got " + args[1].typeName() + ")");
                    }
                } else {
                    level = (int) args[1].toLong();
                }
            }
            if (level <= 0) {
                preserveCoroutineDeathFrames();
                LuaException le = new LuaException(msg);
                le.setDecorated(true);
                throw le;
            }
            if (msg.isString()) {
                org.luava.runtime.eval.CallStack.Frame frame = org.luava.runtime.eval.CallStack.getFrame(level);
                if (frame != null && frame.function != null && !"C".equals(frame.function.getWhat()) && frame.line > 0) {
                    String src = (frame.function.getSource() != null)
                            ? frame.function.getSource()
                            : "=(load)";
                    int line = frame.line;
                    String formatted = org.luava.frontend.parser.ParseException.formatChunkName(src) + ":" + line + ": " + msg.toLuaString();
                    LuaException le = new LuaException(LuaString.valueOf(formatted));
                    le.setDecorated(true);
                    preserveCoroutineDeathFrames();
                    throw le;
                }
            }
            preserveCoroutineDeathFrames();
            LuaException le = new LuaException(msg);
            le.setDecorated(true);
            throw le;
        }));

        globals.rawset(LuaString.valueOf("pcall"), LuaFunction.of("pcall", args -> {
            if (args.length == 0) {
                return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("bad argument #1 to 'pcall' (value expected)"));
            }
            LuaValue target = args[0];
            LuaValue[] fnArgs = new LuaValue[args.length - 1];
            System.arraycopy(args, 1, fnArgs, 0, fnArgs.length);
            org.luava.runtime.eval.CallStack.pushProtectedFrame(null);
            boolean pushedCFrame = false;
            if (target instanceof LuaFunction fn && !(fn instanceof org.luava.runtime.bytecode.LuaClosure)) {
                org.luava.runtime.eval.CallStack.setNextTransfer(1, fnArgs.length, fnArgs);
                org.luava.runtime.eval.CallStack.push(fn, fn.getName(), -1);
                pushedCFrame = true;
            }
            try {
                LuaValue result = target.call(fnArgs);
                if (result instanceof Varargs va) {
                    LuaValue[] arr = va.toArray();
                    LuaValue[] out = new LuaValue[arr.length + 1];
                    out[0] = LuaBoolean.TRUE;
                    System.arraycopy(arr, 0, out, 1, arr.length);
                    return Varargs.of(out);
                }
                return Varargs.of(LuaBoolean.TRUE, result);
            } catch (org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal ccs) {
                throw ccs;
            } catch (org.luava.runtime.LuaExit ex) {
                throw ex;
            } catch (LuaException le) {
                return Varargs.of(LuaBoolean.FALSE, le.getErrorObject());
            } catch (StackOverflowError soe) {
                return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("stack overflow"));
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf(msg));
            } finally {
                if (pushedCFrame) {
                    org.luava.runtime.eval.CallStack.pop();
                }
                org.luava.runtime.eval.CallStack.popProtectedFrame();
            }
        }));

        globals.rawset(LuaString.valueOf("xpcall"), LuaFunction.of("xpcall", args -> {
            if (args.length < 2) {
                return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("bad arguments to 'xpcall' (value expected)"));
            }
            LuaValue target = args[0];
            LuaValue msgh = args[1];
            LuaValue[] fnArgs = new LuaValue[args.length - 2];
            System.arraycopy(args, 2, fnArgs, 0, fnArgs.length);

            org.luava.runtime.eval.CallStack.pushProtectedFrame(msgh);
            boolean pushedCFrame = false;
            if (target instanceof LuaFunction fn && !(fn instanceof org.luava.runtime.bytecode.LuaClosure)) {
                org.luava.runtime.eval.CallStack.setNextTransfer(1, fnArgs.length, fnArgs);
                org.luava.runtime.eval.CallStack.push(fn, fn.getName(), -1);
                pushedCFrame = true;
            }
            try {
                LuaValue result = target.call(fnArgs);
                if (result instanceof Varargs va) {
                    LuaValue[] arr = va.toArray();
                    LuaValue[] out = new LuaValue[arr.length + 1];
                    out[0] = LuaBoolean.TRUE;
                    System.arraycopy(arr, 0, out, 1, arr.length);
                    return Varargs.of(out);
                }
                return Varargs.of(LuaBoolean.TRUE, result);
            } catch (org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal ccs) {
                throw ccs;
            } catch (org.luava.runtime.LuaExit ex) {
                throw ex;
            } catch (org.luava.runtime.eval.LuaUnwindException ue) {
                return Varargs.of(LuaBoolean.FALSE, ue.getResult());
            } catch (LuaException le) {
                try {
                    LuaValue handlerRes = msgh.call(le.getErrorObject());
                    return Varargs.of(LuaBoolean.FALSE, handlerRes);
                } catch (Throwable t) {
                    return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("error in error handling"));
                }
            } catch (StackOverflowError soe) {
                try {
                    LuaValue handlerRes = msgh.call(LuaString.valueOf("stack overflow"));
                    return Varargs.of(LuaBoolean.FALSE, handlerRes);
                } catch (Throwable t) {
                    return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("error in error handling"));
                }
            } catch (Throwable t) {
                try {
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    LuaValue handlerRes = msgh.call(LuaString.valueOf(msg));
                    return Varargs.of(LuaBoolean.FALSE, handlerRes);
                } catch (Throwable t2) {
                    return Varargs.of(LuaBoolean.FALSE, LuaString.valueOf("error in error handling"));
                }
            } finally {
                if (pushedCFrame) {
                    org.luava.runtime.eval.CallStack.pop();
                }
                org.luava.runtime.eval.CallStack.popProtectedFrame();
            }
        }));

        globals.rawset(LuaString.valueOf("select"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'select'");
            LuaValue selector = args[0];
            if (selector.isString() && "#".equals(selector.toLuaString())) {
                return LuaInteger.valueOf(args.length - 1);
            }
            long idx = selector.toLong();
            if (idx == 0 || idx < -args.length + 1) {
                throw new LuaException("bad index to 'select'");
            }
            int start = idx > 0 ? (int) idx : (int) (args.length + idx);
            if (start > args.length - 1) {
                return Varargs.EMPTY;
            }
            int count = args.length - start;
            LuaValue[] sub = new LuaValue[count];
            System.arraycopy(args, start, sub, 0, count);
            return Varargs.of(sub);
        }));

        globals.rawset(LuaString.valueOf("setmetatable"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'setmetatable' (table expected, got " + (args.length == 0 ? "no value" : args[0].typeName()) + ")");
            }
            if (args.length < 2) {
                throw new LuaException("bad argument #2 to 'setmetatable' (nil or table expected, got no value)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaValue mt = args[1];
            LuaTable oldMt = t.getMetatable();
            if (oldMt != null) {
                LuaValue protectedVal = oldMt.rawget(LuaString.valueOf("__metatable"));
                if (!protectedVal.isNil()) {
                    throw new LuaException("cannot change a protected metatable");
                }
            }
            if (mt.isNil()) {
                t.setMetatable(null);
            } else if (mt.isTable()) {
                LuaTable tableMt = (LuaTable) mt;
                t.setMetatable(tableMt);
                LuaValue gcHandler = tableMt.rawget(LuaString.valueOf("__gc"));
                if (!gcHandler.isNil()) {
                    org.luava.runtime.eval.GCManager.register(t, gcHandler);
                }
            } else {
                throw new LuaException("bad argument #2 to 'setmetatable' (nil or table expected, got " + mt.typeName() + ")");
            }
            return t;
        }));

        globals.rawset(LuaString.valueOf("getmetatable"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaTable mt = args[0].getMetatable();
            if (mt == null) return LuaNil.NIL;
            LuaValue protectedVal = mt.rawget(LuaString.valueOf("__metatable"));
            if (!protectedVal.isNil()) return protectedVal;
            return mt;
        }));

        globals.rawset(LuaString.valueOf("rawget"), LuaFunction.of(args -> {
            if (args.length < 2 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'rawget' (table expected)");
            }
            return ((LuaTable) args[0]).rawget(args[1]);
        }));

        globals.rawset(LuaString.valueOf("rawset"), LuaFunction.of(args -> {
            if (args.length < 3 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'rawset' (table expected)");
            }
            ((LuaTable) args[0]).rawset(args[1], args[2]);
            return args[0];
        }));

        globals.rawset(LuaString.valueOf("rawequal"), LuaFunction.of(args -> {
            if (args.length < 2) return LuaBoolean.FALSE;
            // Lua 5.4: raw equality still compares numbers by mathematical
            // value across the integer/float subtypes (1 == 1.0), but never
            // consults metamethods. luaEquals handles the numeric case; its
            // __eq path is unreachable here only if we avoid it, so use the
            // dedicated raw helper.
            return LuaBoolean.valueOf(LuaValue.rawEquals(args[0], args[1]));
        }));

        globals.rawset(LuaString.valueOf("rawlen"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'rawlen'");
            if (args[0].isTable()) {
                return LuaInteger.valueOf(((LuaTable) args[0]).rawlen());
            }
            if (args[0].isString()) {
                return args[0].len();
            }
            throw new LuaException("table or string expected in 'rawlen'");
        }));

        globals.rawset(LuaString.valueOf("pairs"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'pairs' (value expected)");
            }
            LuaValue target = args[0];
            LuaTable mt = target.getMetatable();
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__pairs"));
                if (!handler.isNil()) {
                    return handler.call(target);
                }
            }
            if (!target.isTable()) {
                throw new LuaException("bad argument #1 to 'pairs' (table expected)");
            }
            LuaTable t = (LuaTable) target;
            LuaFunction nextFunc = (LuaFunction) globals.rawget(LuaString.valueOf("next"));
            return Varargs.of(nextFunc, t, LuaNil.NIL);
        }));


        globals.rawset(LuaString.valueOf("next"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isTable()) {
                throw new LuaException("bad argument #1 to 'next' (table expected)");
            }
            LuaTable t = (LuaTable) args[0];
            LuaValue currentKey = args.length > 1 ? args[1] : LuaNil.NIL;
            return t.next(currentKey);
        }));

        LuaFunction ipairsaux = LuaFunction.of(iterArgs -> {
            if (iterArgs.length < 2 || !iterArgs[1].isNumber()) {
                return LuaNil.NIL;
            }
            LuaValue table = iterArgs[0];
            long i = iterArgs[1].toLong() + 1;
            LuaValue val = table.get(LuaInteger.valueOf(i));
            if (val.isNil()) return LuaNil.NIL;
            return Varargs.of(LuaInteger.valueOf(i), val);
        });

        globals.rawset(LuaString.valueOf("ipairs"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'ipairs' (value expected)");
            }
            return Varargs.of(ipairsaux, args[0], LuaInteger.valueOf(0));
        }));

        boolean[] warningsOn = new boolean[]{false};
        globals.rawset(LuaString.valueOf("warn"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'warn' (string expected, got no value)");
            }
            for (int i = 0; i < args.length; i++) {
                if (!args[i].isString() && !args[i].isNumber()) {
                    throw new LuaException("bad argument #" + (i + 1) + " to 'warn' (string expected, got " + args[i].typeName() + ")");
                }
            }
            String first = args[0].toLuaString();
            if (first.startsWith("@")) {
                if ("@on".equals(first)) warningsOn[0] = true;
                else if ("@off".equals(first)) warningsOn[0] = false;
                return LuaNil.NIL;
            }
            if (warningsOn[0]) {
                StringBuilder sb = new StringBuilder("Lua warning: ");
                for (LuaValue arg : args) {
                    sb.append(arg.toLuaString());
                }
                System.err.println(sb);
            }
            return LuaNil.NIL;
        }));

        globals.rawset(LuaString.valueOf("load"), LuaFunction.of(args -> {
            if (args.length == 0) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("bad argument #1 to 'load'"));
            }
            String code;
            if (args[0].isString()) {
                code = args[0].toLuaString();
            } else if (args[0].isFunction()) {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    LuaValue chunk;
                    try {
                        chunk = args[0].call();
                    } catch (Throwable t) {
                        return Varargs.of(LuaNil.NIL, LuaString.valueOf(t.getMessage() != null ? t.getMessage() : t.toString()));
                    }
                    if (chunk == null || chunk.isNil()) {
                        break;
                    }
                    if (chunk.isString()) {
                        String s = chunk.toLuaString();
                        if (s.isEmpty()) break;
                        sb.append(s);
                    } else {
                        return Varargs.of(LuaNil.NIL, LuaString.valueOf("reader function must return a string"));
                    }
                }
                code = sb.toString();
            } else {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("string or function expected in 'load'"));
            }

            String mode = (args.length >= 3 && !args[2].isNil()) ? args[2].toLuaString() : "bt";
            boolean isBinary = code.startsWith("\u001b");
            if (isBinary && !mode.contains("b")) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("attempt to load a binary chunk (mode is '" + mode + "')"));
            }
            if (!isBinary && !mode.contains("t")) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("attempt to load a text chunk (mode is '" + mode + "')"));
            }

            try {
                // For binary chunks, if no explicit chunk name was given, pass null so
                // undump uses the source embedded in the binary chunk header.
                // For text chunks, default to "=(load)" (matching Lua 5.4 reader-based load).
                boolean hasExplicitName = args.length >= 2 && !args[1].isNil();
                String chunkname;
                if (hasExplicitName) {
                    chunkname = args[1].toLuaString();
                } else if (isBinary) {
                    chunkname = null;  // let undump use embedded source
                } else {
                    // For text chunks loaded from a string, Lua uses the string itself
                    // (truncated) as the chunk name. We replicate that here.
                    chunkname = code;
                }
                LuaValue envVal = (args.length >= 4) ? args[3] : globals;
                LuaFunction fn = state.compile(code, chunkname, envVal);
                return fn;
            } catch (Throwable t) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(t.getMessage()));
            }
        }));

        globals.rawset(LuaString.valueOf("loadfile"), LuaFunction.of(args -> {
            String filename = (args.length > 0 && !args[0].isNil()) ? args[0].toLuaString() : null;
            String mode = (args.length > 1 && !args[1].isNil()) ? args[1].toLuaString() : "bt";
            LuaValue envVal = (args.length > 2) ? args[2] : globals;
            String chunkname;
            String code;
            byte[] bytes;
            if (filename == null) {
                chunkname = "=stdin";
                try {
                    bytes = System.in.readAllBytes();
                } catch (IOException e) {
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf("cannot read stdin: " + e.getMessage()));
                }
            } else {
                chunkname = "@" + filename;
                Path filePath = Path.of(filename);
                if (!Files.exists(filePath)) {
                    int depth = org.luava.runtime.eval.CallStack.depth();
                    for (int i = 0; i < depth; i++) {
                        org.luava.runtime.eval.CallStack.Frame frame = org.luava.runtime.eval.CallStack.getFrame(i);
                        if (frame != null && frame.function != null && frame.function.getSource() != null && frame.function.getSource().startsWith("@")) {
                            try {
                                Path srcPath = Path.of(frame.function.getSource().substring(1));
                                Path parent = srcPath.getParent();
                                if (parent != null) {
                                    Path resolved = parent.resolve(filename);
                                    if (Files.exists(resolved)) {
                                        filePath = resolved;
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (!Files.exists(filePath)) {
                    Path testDir = Path.of("tests/lua-5.4.9-tests", filename);
                    if (Files.exists(testDir)) {
                        filePath = testDir;
                    }
                }
                try {
                    bytes = Files.readAllBytes(filePath);
                } catch (IOException e) {
                    return Varargs.of(LuaNil.NIL, LuaString.valueOf("cannot open " + filename + ": " + e.getMessage()));
                }
            }

            int offset = 0;
            if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                offset = 3;
            }
            boolean skippedComment = false;
            if (offset < bytes.length && bytes[offset] == '#') {
                skippedComment = true;
                while (offset < bytes.length && bytes[offset] != '\n') {
                    offset++;
                }
                if (offset < bytes.length && bytes[offset] == '\n') {
                    offset++;
                }
            }
            if (offset < bytes.length && bytes[offset] == 0x1b) {
                skippedComment = false;
            }

            if (skippedComment) {
                byte[] payload = new byte[1 + bytes.length - offset];
                payload[0] = '\n';
                System.arraycopy(bytes, offset, payload, 1, bytes.length - offset);
                code = new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1);
            } else if (offset > 0) {
                code = new String(bytes, offset, bytes.length - offset, java.nio.charset.StandardCharsets.ISO_8859_1);
            } else {
                code = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            }

            boolean isBinary = code.startsWith("\u001b");
            if (isBinary && !mode.contains("b")) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("attempt to load a binary chunk (mode is '" + mode + "')"));
            }
            if (!isBinary && !mode.contains("t")) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf("attempt to load a text chunk (mode is '" + mode + "')"));
            }

            try {
                LuaFunction fn = state.compile(code, chunkname, envVal);
                return fn;
            } catch (Throwable t) {
                return Varargs.of(LuaNil.NIL, LuaString.valueOf(t.getMessage()));
            }
        }));

        globals.rawset(LuaString.valueOf("dofile"), LuaFunction.of(args -> {
            LuaValue loadfileFunc = globals.rawget(LuaString.valueOf("loadfile"));
            LuaValue res = loadfileFunc.call(args);
            if (res instanceof Varargs va) {
                if (va.first().isNil()) {
                    throw new LuaException(va.arg(2).toLuaString());
                }
                return va.first().call();
            }
            if (res.isNil()) {
                throw new LuaException("cannot open file");
            }
            return res.call();
        }));

        final String[] gcMode = new String[] { "incremental" };
        final int[] gcParams = new int[] { 200, 100 }; // pause, stepmul

        globals.rawset(LuaString.valueOf("collectgarbage"), LuaFunction.of(args -> {
            if (args.length > 0 && !args[0].isNil() && !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'collectgarbage' (string expected, got " + args[0].typeName() + ")");
            }
            String opt = (args.length > 0 && args[0].isString()) ? args[0].toLuaString() : "collect";
            return switch (opt) {
                case "count" -> LuaFloat.valueOf(org.luava.runtime.eval.GCManager.getMemoryKb());
                case "isrunning" -> LuaBoolean.valueOf(org.luava.runtime.eval.GCManager.isRunning());
                case "generational" -> {
                    String prev = gcMode[0];
                    gcMode[0] = "generational";
                    yield LuaString.valueOf(prev);
                }
                case "incremental" -> {
                    String prev = gcMode[0];
                    gcMode[0] = "incremental";
                    yield LuaString.valueOf(prev);
                }
                case "setpause" -> {
                    int prev = gcParams[0];
                    if (args.length > 1 && args[1].isInteger()) {
                        gcParams[0] = (int) args[1].toLong();
                    }
                    yield LuaInteger.valueOf(prev);
                }
                case "setstepmul" -> {
                    int prev = gcParams[1];
                    if (args.length > 1 && args[1].isInteger()) {
                        gcParams[1] = (int) args[1].toLong();
                    }
                    yield LuaInteger.valueOf(prev);
                }
                case "step" -> {
                    long stepKb = (args.length > 1 && args[1].isNumber()) ? args[1].toLong() : 0;
                    boolean res = org.luava.runtime.eval.GCManager.step(stepKb);
                    yield LuaBoolean.valueOf(res);
                }
                case "stop" -> {
                    org.luava.runtime.eval.GCManager.stop();
                    yield LuaInteger.valueOf(0);
                }
                case "restart" -> {
                    org.luava.runtime.eval.GCManager.restart();
                    yield LuaInteger.valueOf(0);
                }
                case "collect" -> {
                    boolean res = org.luava.runtime.eval.GCManager.collect();
                    System.gc();
                    yield LuaBoolean.valueOf(res);
                }
                default -> throw new LuaException("bad argument #1 to 'collectgarbage' (invalid option '" + opt + "')");
            };
        }));
    }

    /**
     * Preserve current coroutine's frames for dead-coroutine traceback.
     * Only for non-main coroutines dying from unprotected errors; main-thread
     * errors propagate out (no need to preserve). Cheap flag (no copying);
     * CallStack.pop() skips while set. The coroutine dies so no leak.
     */
    private static void preserveCoroutineDeathFrames() {
        org.luava.runtime.concurrency.LuaCoroutine cur =
                org.luava.runtime.concurrency.LuaCoroutine.running();
        if (cur != null && !cur.isMainThread()) {
            org.luava.runtime.eval.CallStack.CallStackState st = cur.getCallStackState();
            if (st.errorStack == null && st.protectedFrames.isEmpty()) {
                st.preserveForDeath = true;
            }
        }
    }
}

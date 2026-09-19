/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.util.ArrayList;
import java.util.List;

public final class StringLib {
    private StringLib() {}

    /**
     * {@code luaL_checkinteger} for the string library: coerces a numeric
     * string (lua_tointegerx) and reports the argument index and function
     * name exactly like PUC. {@code func} is the unqualified PUC name
     * (e.g. "sub", "byte", "find"), matching pushglobalfuncname output.
     */
    static long checkInteger(LuaValue[] args, int idx, String func) {
        if (idx >= args.length || args[idx].isNil()) {
            throw LuaValue.argError(idx + 1, func, "number expected, got " + (idx >= args.length ? "no value" : "nil"));
        }
        LuaValue v = args[idx];
        LuaInteger i = v.toLuaIntegerCoercingStrings();
        if (i != null) return i.toLong();
        throw LuaValue.argError(idx + 1, func, v.integerConversionError());
    }

    /**
     * Shared stateless {@code string.gmatch} builtin (see
     * {@code BaseLib.TOSTRING}): one JVM-wide instance so the VM can
     * recognize and inline it on hot paths.
     */
    public static final LuaFunction GMATCH = LuaFunction.of(StringLib::gmatchImpl);

    static LuaValue gmatchImpl(LuaValue[] args) {
        if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
            throw LuaValue.argError(1, "gmatch", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
        }
        if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
            throw LuaValue.argError(2, "gmatch", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
        }
        return LuaPattern.gmatch(args[0], args[1], args.length > 2 ? args[2] : null);
    }

    public static void open(LuaTable globals) {
        LuaTable stringTable = new LuaTable();
        fillInto(stringTable, globals);
        globals.rawset(LuaString.interned("string"), stringTable);
    }

    public static void fillInto(LuaTable stringTable, LuaTable globals) {

        stringTable.rawset(LuaString.interned("len"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.len'");
            if (args[0] instanceof LuaString ls) {
                return LuaInteger.valueOf(ls.value().length());
            }
            return LuaInteger.valueOf(args[0].toLuaString().length());
        }));

        stringTable.rawset(LuaString.interned("lower"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.lower'");
            return LuaString.valueOf(args[0].toLuaString().toLowerCase());
        }));

        stringTable.rawset(LuaString.interned("upper"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.upper'");
            return LuaString.valueOf(args[0].toLuaString().toUpperCase());
        }));

        stringTable.rawset(LuaString.interned("reverse"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.reverse'");
            return LuaString.valueOf(new StringBuilder(args[0].toLuaString()).reverse().toString());
        }));

        stringTable.rawset(LuaString.interned("rep"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.rep'");
            String s = args[0].toLuaString();
            LuaInteger nVal = args[1].toLuaIntegerCoercingStrings();
            if (nVal == null) throw new LuaException("bad argument #2 to 'string.rep' (" + args[1].integerConversionError() + ")");
            long n = nVal.toLong();
            String sep = (args.length > 2 && !args[2].isNil()) ? args[2].toLuaString() : "";
            if (n <= 0) return LuaString.interned("");
            long l = s.length();
            long lsep = sep.length();
            if (n > 1) {
                if (l + lsep > (Integer.MAX_VALUE - 8) / n) {
                    throw new LuaException("resulting string too large");
                }
            } else {
                if (l > Integer.MAX_VALUE - 8) {
                    throw new LuaException("resulting string too large");
                }
            }
            long totallen = n * l + (n - 1) * lsep;
            long cap = org.luava.runtime.LuaState.allocationLimit();
            if (totallen < 0 || totallen > cap) {
                throw new LuaException("resulting string too large");
            }
            if (sep.isEmpty()) {
                return LuaString.valueOf(s.repeat((int) n));
            }
            StringBuilder sb = new StringBuilder((int) totallen);
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(sep);
                sb.append(s);
            }
            return LuaString.valueOf(sb.toString());
        }));

        stringTable.rawset(LuaString.interned("sub"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "sub", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            String s = args[0].toLuaString();
            int len = s.length();
            long start = checkInteger(args, 1, "sub");
            long end = (args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "sub") : -1;

            if (start < 0) start = len + start + 1;
            if (end < 0) end = len + end + 1;

            if (start < 1) start = 1;
            if (end > len) end = len;

            if (start > end) return LuaString.interned("");
            return LuaString.valueOf(s.substring((int) start - 1, (int) end));
        }));

        stringTable.rawset(LuaString.interned("byte"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.byte'");
            String s = (args[0] instanceof LuaString ls) ? ls.value() : args[0].toLuaString();
            int len = s.length();
            long start = (args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "byte") : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "byte") : start;

            if (start < 0) start = len + start + 1;
            if (end < 0) end = len + end + 1;
            if (start < 1) start = 1;
            if (end > len) end = len;
            if (start > end) return Varargs.EMPTY;

            int count = (int) (end - start + 1);
            if (count == 1) {
                return LuaInteger.valueOf((int) s.charAt((int) start - 1));
            }
            LuaValue[] bytes = new LuaValue[count];
            int sIdx = (int) start - 1;
            for (int i = 0; i < count; i++) {
                bytes[i] = LuaInteger.valueOf((int) s.charAt(sIdx + i));
            }
            return Varargs.of(bytes);
        }));

        stringTable.rawset(LuaString.interned("char"), LuaFunction.of(args -> {
            int n = args.length;
            if (n == 0) return LuaString.EMPTY;
            if (n == 1) {
                LuaInteger intVal = args[0].toLuaIntegerCoercingStrings();
                if (intVal == null) {
                    throw new LuaException("bad argument #1 to 'char' (" + args[0].integerConversionError() + ")");
                }
                long val = intVal.toLong();
                if (val < 0 || val > 255) {
                    throw new LuaException("bad argument #1 to 'char' (value out of range)");
                }
                return LuaString.valueOf(String.valueOf((char) val));
            }
            char[] chars = new char[n];
            for (int i = 0; i < n; i++) {
                LuaInteger intVal = args[i].toLuaIntegerCoercingStrings();
                if (intVal == null) {
                    throw new LuaException("bad argument #" + (i + 1) + " to 'char' (" + args[i].integerConversionError() + ")");
                }
                long val = intVal.toLong();
                if (val < 0 || val > 255) {
                    throw new LuaException("bad argument #" + (i + 1) + " to 'char' (value out of range)");
                }
                chars[i] = (char) val;
            }
            return LuaString.valueOf(new String(chars));
        }));

        stringTable.rawset(LuaString.interned("format"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'string.format' (string expected, got no value)");
            String fmt = args[0].toLuaString();
            StringBuilder b = new StringBuilder();
            int top = args.length - 1;
            int argIdx = 1;
            int sfl = fmt.length();
            int p = 0;

            while (p < sfl) {
                char c = fmt.charAt(p);
                if (c != '%') {
                    b.append(c);
                    p++;
                } else if (p + 1 < sfl && fmt.charAt(p + 1) == '%') {
                    b.append('%');
                    p += 2;
                } else {
                    p++; // skip '%'
                    int start = p;
                    while (p < sfl && "-+ #0123456789.".indexOf(fmt.charAt(p)) >= 0) {
                        p++;
                    }
                    if (p < sfl) {
                        p++; // include specifier
                    }
                    int len = p - start;
                    if (len >= 22) { // MAX_FORMAT - 10
                        throw new LuaException("invalid format (too long)");
                    }
                    String form = "%" + fmt.substring(start, p);
                    if (argIdx > top) {
                        throw new LuaException("bad argument #" + (argIdx + 1) + " to 'string.format' (no value)");
                    }
                    LuaValue v = args[argIdx++];
                    char spec = form.charAt(form.length() - 1);

                    int pIdx = 1;
                    StringBuilder flagsSb = new StringBuilder();
                    while (pIdx < form.length() - 1 && "-+ #0".indexOf(form.charAt(pIdx)) >= 0) {
                        flagsSb.append(form.charAt(pIdx++));
                    }
                    String flags = flagsSb.toString();

                    int width = 0;
                    if (pIdx < form.length() - 1 && Character.isDigit(form.charAt(pIdx))) {
                        while (pIdx < form.length() - 1 && Character.isDigit(form.charAt(pIdx))) {
                            width = width * 10 + (form.charAt(pIdx++) - '0');
                        }
                    }

                    int prec = -1;
                    if (pIdx < form.length() - 1 && form.charAt(pIdx) == '.') {
                        pIdx++;
                        prec = 0;
                        while (pIdx < form.length() - 1 && Character.isDigit(form.charAt(pIdx))) {
                            prec = prec * 10 + (form.charAt(pIdx++) - '0');
                        }
                    }

                    switch (spec) {
                        case 'c' -> {
                            checkFormat(form, "-", false);
                            long code = checkFormatInteger(v, argIdx);
                            // C printf %c: converted to unsigned char.
                            String res = String.valueOf((char) (code & 0xFF));
                            if (width > 1) {
                                if (flags.contains("-")) res = res + " ".repeat(width - 1);
                                else res = " ".repeat(width - 1) + res;
                            }
                            b.append(res);
                        }
                        case 'd', 'i' -> {
                            // PUC checks the argument (luaL_checkinteger)
                            // BEFORE validating the format, so a non-integer
                            // number blames the argument even for an invalid
                            // specifier like %#d.
                            long n = checkFormatInteger(v, argIdx);
                            checkFormat(form, "-+ 0", true);
                            b.append(formatInteger(String.valueOf(spec), flags, width, prec, n));
                        }
                        case 'u' -> {
                            long n = checkFormatInteger(v, argIdx);
                            checkFormat(form, "-0", true);
                            b.append(formatInteger("u", flags, width, prec, n));
                        }
                        case 'o', 'x', 'X' -> {
                            long n = checkFormatInteger(v, argIdx);
                            checkFormat(form, "-#0", true);
                            b.append(formatInteger(String.valueOf(spec), flags, width, prec, n));
                        }
                        case 'a', 'A', 'f', 'e', 'E', 'g', 'G' -> {
                            // Same order as PUC: argument conversion first.
                            double d = checkFormatNumber(v, argIdx);
                            checkFormat(form, "-+ #0", true);
                            b.append(formatFloat(String.valueOf(spec), flags, width, prec, d));
                        }
                        case 'p' -> {
                            checkFormat(form, "-", false);
                            // Strings canonicalize first: equal short strings
                            // share one object, so their pointers compare
                            // equal (LuaString interning is lazy).
                            LuaValue id = (v instanceof LuaString ls) ? LuaString.intern(ls) : v;
                            String res = (v.isNil() || v.isBoolean() || v.isNumber()) ? "(null)" : "0x" + Long.toHexString(System.identityHashCode(id));
                            if (width > res.length()) {
                                if (flags.contains("-")) res = res + " ".repeat(width - res.length());
                                else res = " ".repeat(width - res.length()) + res;
                            }
                            b.append(res);
                        }
                        case 'q' -> {
                            if (form.length() > 2) {
                                throw new LuaException("specifier '%q' cannot have modifiers");
                            }
                            if (v.isNil()) {
                                b.append("nil");
                            } else if (v.isBoolean()) {
                                b.append(v.toBoolean() ? "true" : "false");
                            } else if (v.isInteger()) {
                                long n = v.toLong();
                                if (n == Long.MIN_VALUE) {
                                    b.append("0x8000000000000000");
                                } else {
                                    b.append(n);
                                }
                            } else if (v.isFloat()) {
                                double d = v.toDouble();
                                if (Double.isInfinite(d)) {
                                    b.append(d > 0 ? "1e9999" : "-1e9999");
                                } else if (Double.isNaN(d)) {
                                    b.append("(0/0)");
                                } else {
                                    b.append(formatHexFloat(false, "", 0, -1, d));
                                }
                            } else if (v.isString()) {
                                b.append(formatQuoted(v.toLuaString()));
                            } else {
                                throw new LuaException("value has no literal form");
                            }
                        }
                        case 's' -> {
                            checkFormat(form, "-", true);
                            String s = v.toLuaString();
                            if (form.length() > 2 && s.indexOf('\0') >= 0) {
                                throw new LuaException("bad argument #" + argIdx + " to 'string.format' (string contains zeros)");
                            }
                            if (prec >= 0 && s.length() > prec) {
                                s = s.substring(0, prec);
                            }
                            if (width > s.length()) {
                                if (flags.contains("-")) s = s + " ".repeat(width - s.length());
                                else s = " ".repeat(width - s.length()) + s;
                            }
                            b.append(s);
                        }
                        default -> throw new LuaException("invalid conversion specification: '" + form + "'");
                    }
                }
            }
            return LuaString.valueOf(b.toString());
        }));

        stringTable.rawset(LuaString.interned("packsize"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) throw new LuaException("bad argument #1 to 'string.packsize'");
            return LuaInteger.valueOf(StringPacker.packsize(args[0].toLuaString()));
        }));

        stringTable.rawset(LuaString.interned("pack"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) throw new LuaException("bad argument #1 to 'string.pack'");
            byte[] packed = StringPacker.pack(args[0].toLuaString(), args, 1);
            return LuaString.valueOf(new String(packed, java.nio.charset.StandardCharsets.ISO_8859_1));
        }));

        stringTable.rawset(LuaString.interned("unpack"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.unpack'");
            String fmt = args[0].toLuaString();
            String s = args[1].toLuaString();
            // luaL_optinteger(L, 3, 1): a non-integral number must be blamed
            // as `bad argument #3 ... (number has no integer representation)`.
            int pos = 1;
            if (args.length > 2 && !args[2].isNil()) {
                pos = (int) StringPacker.checkIntegerArg(args, 2, "string.unpack");
            }
            byte[] data = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            return StringPacker.unpack(fmt, data, pos);
        }));

        stringTable.rawset(LuaString.interned("find"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "find", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "find", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            boolean plain = (args.length > 3 && !args[3].isNil()) && args[3].toBoolean();
            LuaValue init = (args.length > 2 && !args[2].isNil())
                    ? LuaInteger.valueOf(checkInteger(args, 2, "find")) : null;
            return LuaPattern.find(args[0], args[1], init, plain);
        }));

        stringTable.rawset(LuaString.interned("match"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "match", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "match", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            LuaValue init = (args.length > 2 && !args[2].isNil())
                    ? LuaInteger.valueOf(checkInteger(args, 2, "match")) : null;
            return LuaPattern.match(args[0], args[1], init);
        }));

        stringTable.rawset(LuaString.interned("gsub"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "gsub", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "gsub", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            if (args.length < 3) throw new LuaException("bad argument #3 to 'string.gsub' (value expected)");
            LuaValue max = (args.length > 3 && !args[3].isNil())
                    ? LuaInteger.valueOf(checkInteger(args, 3, "gsub")) : null;
            return LuaPattern.gsub(args[0], args[1], args[2], max);
        }));

        stringTable.rawset(LuaString.interned("gmatch"), GMATCH);

        stringTable.rawset(LuaString.interned("dump"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaFunction fn)) {
                throw new LuaException("bad argument #1 to 'string.dump' (function expected)");
            }
            boolean strip = args.length > 1 && args[1].toBoolean();
            byte[] dumped = ChunkSerializer.dump(fn, strip);
            return LuaString.valueOf(new String(dumped, java.nio.charset.StandardCharsets.ISO_8859_1));
        }));
    }

    // Installs the string metatable eagerly (cheap: two small tables). The
    // __index target is the (possibly still lazy) string library table, so
    // ("x"):upper() works and fills the library on first use.
    public static void installMetatable(LuaTable stringTable) {
        LuaString.setStringMetatable(newStringMetatable(stringTable));
    }

    public static LuaTable newStringMetatable(LuaTable stringTable) {
        LuaTable stringMt = new LuaTable();
        stringMt.rawset(LuaString.interned("__index"), stringTable);
        // Lua 5.4 (lstrlib.c): strings carry default arithmetic metamethods
        // that coerce numerical strings, and are fully overridable/removable.
        // Without these, `"10" + 1` bypasses the string metatable entirely and
        // a user-installed `__add` is silently ignored.
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.ADD, arithMm("add", org.luava.runtime.LuaValue.Meta.ADD));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.SUB, arithMm("sub", org.luava.runtime.LuaValue.Meta.SUB));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.MUL, arithMm("mul", org.luava.runtime.LuaValue.Meta.MUL));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.DIV, arithMm("div", org.luava.runtime.LuaValue.Meta.DIV));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.IDIV, arithMm("idiv", org.luava.runtime.LuaValue.Meta.IDIV));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.MOD, arithMm("mod", org.luava.runtime.LuaValue.Meta.MOD));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.POW, arithMm("pow", org.luava.runtime.LuaValue.Meta.POW));
        stringMt.rawset(org.luava.runtime.LuaValue.Meta.UNM, arithMm("unm", org.luava.runtime.LuaValue.Meta.UNM));
        return stringMt;
    }

    /**
     * One default string-metatable arithmetic metamethod, mirroring PUC
     * {@code lstrlib.c} {@code arith}/{@code trymt}: coerce both operands to
     * numbers and apply the raw operation; otherwise delegate to the second
     * operand's metamethod (unless it is a string, which would recurse);
     * otherwise report {@code "attempt to <op> a '<t1>' with a '<t2>'"}.
     */
    private static LuaFunction arithMm(String op, org.luava.runtime.LuaString key) {
        return LuaFunction.of(args -> {
            LuaValue a = args.length > 0 ? args[0] : LuaNil.NIL;
            LuaValue b = args.length > 1 ? args[1] : LuaNil.NIL;
            LuaValue na = toArithNumber(a);
            LuaValue nb = toArithNumber(b);
            if (na != null && nb != null) {
                switch (op) {
                    case "add": return na.add(nb);
                    case "sub": return na.sub(nb);
                    case "mul": return na.mul(nb);
                    case "div": return na.div(nb);
                    case "idiv": return na.idiv(nb);
                    case "mod": return na.mod(nb);
                    case "pow": return na.pow(nb);
                    default: return na.unm();
                }
            }
            if (!b.isString()) {
                LuaTable mt = b.getMetatable();
                LuaValue mm = (mt != null) ? mt.rawget(key) : null;
                if (mm != null && !mm.isNil()) {
                    return mm.call(a, b);
                }
            }
            throw new LuaException("attempt to " + op + " a '" + a.typeName() + "' with a '" + b.typeName() + "'");
        });
    }

    /** PUC {@code tonum}: an actual number, or a fully numerical string. */
    private static LuaValue toArithNumber(LuaValue v) {
        if (v.isNumber()) return v;
        return v.isString() ? v.toLuaNumber() : null;
    }


    private static String formatQuoted(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '\n') {
                sb.append('\\').append(c);
            } else if (c < 32 || c == 127) {
                boolean nextIsDigit = (i + 1 < len) && Character.isDigit(s.charAt(i + 1));
                if (!nextIsDigit) {
                    sb.append('\\').append((int) c);
                } else {
                    sb.append('\\').append(String.format("%03d", (int) c));
                }
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void checkFormat(String form, String flags, boolean allowPrecision) {
        int idx = 1; // skip '%'
        while (idx < form.length() - 1 && flags.indexOf(form.charAt(idx)) >= 0) {
            idx++;
        }
        if (idx < form.length() - 1 && form.charAt(idx) != '0') {
            // skip width (at most 2 digits)
            if (Character.isDigit(form.charAt(idx))) {
                idx++;
                if (idx < form.length() - 1 && Character.isDigit(form.charAt(idx))) {
                    idx++;
                }
            }
            if (idx < form.length() - 1 && form.charAt(idx) == '.' && allowPrecision) {
                idx++;
                // skip precision (at most 2 digits)
                if (idx < form.length() - 1 && Character.isDigit(form.charAt(idx))) {
                    idx++;
                    if (idx < form.length() - 1 && Character.isDigit(form.charAt(idx))) {
                        idx++;
                    }
                }
            }
        }
        if (idx != form.length() - 1 || !Character.isLetter(form.charAt(idx))) {
            throw new LuaException("invalid conversion specification: '" + form + "'");
        }
    }

    private static long checkFormatInteger(LuaValue v, int argIdx) {
        if (v.isInteger()) {
            return v.toLong();
        }
        if (v.isFloat()) {
            double d = v.toDouble();
            long n = (long) d;
            if (d == (double) n) {
                return n;
            }
            throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number has no integer representation)");
        }
        if (v.isString()) {
            // luaL_checkinteger coerces numeric strings through Lua's own
            // parser (hex, exponents, surrounding spaces), not Java's.
            LuaValue parsed = LuaValue.parseNumber(v.toLuaString());
            if (parsed != null) {
                LuaInteger ci = parsed.toLuaInteger();
                if (ci != null) return ci.toLong();
                throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number has no integer representation)");
            }
        }
        throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got "
                + (v.isString() ? "string" : v.typeName()) + ")");
    }

    private static double checkFormatNumber(LuaValue v, int argIdx) {
        if (v.isNumber()) {
            return v.toDouble();
        }
        if (v.isString()) {
            LuaValue parsed = LuaValue.parseNumber(v.toLuaString());
            if (parsed != null) {
                return parsed.toDouble();
            }
        }
        throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got "
                + (v.isString() ? "string" : v.typeName()) + ")");
    }

    private static String formatInteger(String spec, String flags, int width, int prec, long n) {
        String sign = "";
        String prefix = "";
        String digits;

        if ("d".equals(spec) || "i".equals(spec)) {
            boolean isNeg = n < 0;
            if (isNeg) {
                sign = "-";
                digits = (n == Long.MIN_VALUE) ? "9223372036854775808" : String.valueOf(-n);
            } else {
                if (flags.contains("+")) {
                    sign = "+";
                } else if (flags.contains(" ")) {
                    sign = " ";
                }
                digits = String.valueOf(n);
            }
        } else if ("u".equals(spec)) {
            digits = Long.toUnsignedString(n);
        } else if ("o".equals(spec)) {
            digits = Long.toOctalString(n);
            if (flags.contains("#")) {
                if (n == 0 && prec == 0) {
                    prec = 1;
                } else if (n != 0 && prec <= digits.length()) {
                    // C: the '#' flag increases precision only when the result
                    // does not already start with a zero. For n == 0 the
                    // single digit is already "0", so `%#o` prints "0", not
                    // "00".
                    digits = "0" + digits;
                }
            }
        } else if ("x".equals(spec)) {
            digits = Long.toHexString(n).toLowerCase(java.util.Locale.US);
            if (flags.contains("#") && n != 0) {
                prefix = "0x";
            }
        } else { // "X"
            digits = Long.toHexString(n).toUpperCase(java.util.Locale.US);
            if (flags.contains("#") && n != 0) {
                prefix = "0X";
            }
        }

        if (prec >= 0) {
            if (prec == 0 && n == 0) {
                digits = "";
            } else if (digits.length() < prec) {
                digits = "0".repeat(prec - digits.length()) + digits;
            }
        }

        int totalLen = sign.length() + prefix.length() + digits.length();
        if (width > totalLen) {
            int pad = width - totalLen;
            if (flags.contains("-")) {
                return sign + prefix + digits + " ".repeat(pad);
            } else if (flags.contains("0") && prec < 0) {
                return sign + prefix + "0".repeat(pad) + digits;
            } else {
                return " ".repeat(pad) + sign + prefix + digits;
            }
        } else {
            return sign + prefix + digits;
        }
    }

    private static String formatFloat(String spec, String flags, int width, int prec, double d) {
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            boolean isUpper = Character.isUpperCase(spec.charAt(0));
            String rep;
            if (Double.isInfinite(d)) {
                if (d < 0) {
                    rep = isUpper ? "-INF" : "-inf";
                } else {
                    if (flags.contains("+")) {
                        rep = isUpper ? "+INF" : "+inf";
                    } else if (flags.contains(" ")) {
                        rep = isUpper ? " INF" : " inf";
                    } else {
                        rep = isUpper ? "INF" : "inf";
                    }
                }
            } else {
                boolean isNeg = (Double.doubleToRawLongBits(d) & 0x8000000000000000L) != 0;
                if (isNeg) {
                    rep = isUpper ? "-NAN" : "-nan";
                } else {
                    if (flags.contains("+")) {
                        rep = isUpper ? "+NAN" : "+nan";
                    } else if (flags.contains(" ")) {
                        rep = isUpper ? " NAN" : " nan";
                    } else {
                        rep = isUpper ? "NAN" : "nan";
                    }
                }
            }
            if (width > rep.length()) {
                if (flags.contains("-")) {
                    return rep + " ".repeat(width - rep.length());
                } else {
                    return " ".repeat(width - rep.length()) + rep;
                }
            }
            return rep;
        }

        char s = spec.charAt(0);

        if (s == 'a' || s == 'A') {
            return formatHexFloat(s == 'A', flags, width, prec, d);
        }

        // %f / %e / %g must round the *exact* binary value to the requested
        // decimal precision (glibc semantics, ties-to-even). Java's
        // String.format first rounds to ~17 significant digits and is wrong
        // for large magnitudes and subnormals, so format from BigDecimal.
        boolean upper = (s == 'E' || s == 'G');
        boolean hash = flags.contains("#");
        boolean neg = (Double.doubleToRawLongBits(d) & 0x8000000000000000L) != 0;
        double av = neg ? -d : d;
        String body;
        if (s == 'f' || s == 'F') {
            body = formatFixed(av, prec < 0 ? 6 : prec, hash);
        } else if (s == 'e' || s == 'E') {
            body = formatScientific(av, prec < 0 ? 6 : prec, hash, upper);
        } else {
            body = formatGeneral(av, prec < 0 ? 6 : (prec == 0 ? 1 : prec), hash, upper);
        }
        String sign = neg ? "-" : flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
        String rep = sign + body;
        if (width > rep.length()) {
            int pad = width - rep.length();
            if (flags.contains("-")) return rep + " ".repeat(pad);
            if (flags.contains("0")) return sign + "0".repeat(pad) + body;
            return " ".repeat(pad) + rep;
        }
        return rep;
    }

    /** C {@code %f}: fixed notation with {@code prec} decimal places. */
    private static String formatFixed(double v, int prec, boolean hash) {
        java.math.BigDecimal bd = new java.math.BigDecimal(v)
                .setScale(prec, java.math.RoundingMode.HALF_EVEN);
        String plain = bd.toPlainString();
        if (prec == 0 && hash) plain += ".";
        return plain;
    }

    /** C {@code %e}: scientific notation with {@code prec} fraction digits. */
    private static String formatScientific(double v, int prec, boolean hash, boolean upper) {
        int sig = prec + 1;
        String digits;
        int exp;
        if (v == 0.0) {
            digits = "0";
            exp = 0;
        } else {
            java.math.BigDecimal r = new java.math.BigDecimal(v)
                    .round(new java.math.MathContext(sig, java.math.RoundingMode.HALF_EVEN));
            exp = r.precision() - r.scale() - 1;
            digits = r.unscaledValue().abs().toString();
        }
        StringBuilder ds = new StringBuilder(digits);
        while (ds.length() < sig) ds.append('0');
        StringBuilder sb = new StringBuilder();
        sb.append(ds.charAt(0));
        if (prec > 0 || hash) {
            sb.append('.');
            if (sig > 1) sb.append(ds, 1, sig);
        }
        sb.append(upper ? 'E' : 'e');
        sb.append(exp < 0 ? '-' : '+');
        int ae = Math.abs(exp);
        if (ae < 10) sb.append('0');
        sb.append(ae);
        return sb.toString();
    }

    /** C {@code %g}: {@code %e} or {@code %f} depending on the exponent. */
    private static String formatGeneral(double v, int sig, boolean hash, boolean upper) {
        java.math.BigDecimal r = new java.math.BigDecimal(v)
                .round(new java.math.MathContext(sig, java.math.RoundingMode.HALF_EVEN));
        int exp = (v == 0.0) ? 0 : (r.precision() - r.scale() - 1);
        String body;
        if (exp >= -4 && exp < sig) {
            body = formatFixed(v, sig - 1 - exp, hash);
        } else {
            body = formatScientific(v, sig - 1, hash, upper);
        }
        if (!hash) body = stripTrailingZeros(body);
        return body;
    }

    /** Removes trailing fraction zeros (and a bare dot) from an %e/%f body. */
    private static String stripTrailingZeros(String s) {
        int eIdx = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'e' || c == 'E') { eIdx = i; break; }
        }
        String mant = (eIdx >= 0) ? s.substring(0, eIdx) : s;
        String exp = (eIdx >= 0) ? s.substring(eIdx) : "";
        if (mant.indexOf('.') >= 0) {
            int end = mant.length();
            while (end > 0 && mant.charAt(end - 1) == '0') end--;
            if (end > 0 && mant.charAt(end - 1) == '.') end--;
            mant = mant.substring(0, end);
        }
        return mant + exp;
    }

    /**
     * C99 {@code %a}/{@code %A} hexadecimal floating point, matching glibc:
     * a normalized leading digit {@code 1..2}, a dot and fractional hex
     * digits only when needed (or when {@code #} is given), an always-signed
     * decimal exponent, precision-based rounding (round-half-to-even) and
     * full width/flag handling. {@code prec < 0} means "as many digits as
     * needed" (exact value).
     */
    private static String formatHexFloat(boolean upper, String flags, int width, int prec, double d) {
        if (Double.isInfinite(d) || Double.isNaN(d)) {
            // C formats %a inf/nan like %g: signed, uppercased for %A.
            String rep;
            if (Double.isInfinite(d)) {
                rep = d < 0 ? "-inf" : "inf";
            } else {
                boolean neg = (Double.doubleToRawLongBits(d) & 0x8000000000000000L) != 0;
                rep = neg ? "-nan" : "nan";
            }
            if (!rep.startsWith("-")) {
                if (flags.contains("+")) rep = "+" + rep;
                else if (flags.contains(" ")) rep = " " + rep;
            }
            if (upper) rep = rep.toUpperCase(java.util.Locale.US);
            return padHex(rep, flags, width);
        }

        long bits = Double.doubleToRawLongBits(d);
        boolean negative = (bits & 0x8000000000000000L) != 0;
        int expBits = (int) ((bits >>> 52) & 0x7FF);
        long mantissaBits = bits & 0x000FFFFFFFFFFFFFL;

        int e;          // exponent of the leading digit
        int lead;       // leading hex digit (1 for normals, 0 for subnormals/zero)
        long fracBits;  // 52-bit fraction after the leading digit, MSB-first
        if (d == 0.0) {
            // glibc: frexp(0) -> (0, 0), so the literal is 0x0p+0.
            e = 0;
            lead = 0;
            fracBits = 0;
        } else if (expBits == 0) {
            // Subnormal: value = 0.fraction * 2^-1022.
            e = -1022;
            lead = 0;
            fracBits = mantissaBits;
        } else {
            // Normal: value = 1.fraction * 2^(expBits-1023).
            e = expBits - 1023;
            lead = 1;
            fracBits = mantissaBits;
        }

        // Exact fractional hex digits: 13 nibbles derived from 52 bits.
        int[] digits = new int[13];
        for (int i = 0; i < 13; i++) {
            digits[i] = (int) ((fracBits >>> (48 - 4 * i)) & 0xF);
        }
        int exactLen = 13;
        while (exactLen > 0 && digits[exactLen - 1] == 0) exactLen--;

        int[] out;
        int fracLen;
        if (prec < 0) {
            fracLen = exactLen;
            out = java.util.Arrays.copyOf(digits, fracLen);
        } else {
            fracLen = prec;
            out = new int[fracLen];
            System.arraycopy(digits, 0, out, 0, Math.min(fracLen, 13));
            // Round half-to-even on the first discarded nibble.
            if (fracLen < 13) {
                int guard = digits[fracLen];
                boolean roundUp;
                if (guard > 8) {
                    roundUp = true;
                } else if (guard < 8) {
                    roundUp = false;
                } else {
                    boolean sticky = false;
                    for (int i = fracLen + 1; i < 13; i++) {
                        if (digits[i] != 0) { sticky = true; break; }
                    }
                    int last = (fracLen > 0) ? out[fracLen - 1] : lead;
                    roundUp = sticky || (last & 1) == 1;
                }
                if (roundUp) {
                    int i = fracLen - 1;
                    boolean carry = true;
                    while (i >= 0 && carry) {
                        int v = out[i] + 1;
                        if (v == 16) { out[i] = 0; carry = true; }
                        else { out[i] = v; carry = false; }
                        i--;
                    }
                    if (carry) lead++;
                }
            }
        }

        boolean hash = flags.contains("#");
        StringBuilder sb = new StringBuilder();
        if (negative) sb.append('-');
        else if (flags.contains("+")) sb.append('+');
        else if (flags.contains(" ")) sb.append(' ');

        sb.append('0').append(upper ? 'X' : 'x');
        sb.append(hexDigit(lead, upper));
        if (fracLen > 0 || hash) {
            sb.append('.');
            for (int i = 0; i < fracLen; i++) sb.append(hexDigit(out[i], upper));
        }
        sb.append(upper ? 'P' : 'p');
        sb.append(e >= 0 ? "+" : "-").append(Math.abs(e));

        return padHex(sb.toString(), flags, width);
    }

    private static char hexDigit(int v, boolean upper) {
        return (char) (v < 10 ? '0' + v : (upper ? 'A' : 'a') + v - 10);
    }

    /** Applies width/zero/left-justify padding to a hex-float literal. */
    private static String padHex(String rep, String flags, int width) {
        if (width <= rep.length()) return rep;
        int pad = width - rep.length();
        if (flags.contains("-")) {
            return rep + " ".repeat(pad);
        }
        if (flags.contains("0")) {
            // Zero padding goes after the sign and the "0x" prefix.
            int prefix = 0;
            if (rep.startsWith("+") || rep.startsWith("-") || rep.startsWith(" ")) prefix = 1;
            if (rep.length() > prefix + 1 && rep.charAt(prefix) == '0'
                    && (rep.charAt(prefix + 1) == 'x' || rep.charAt(prefix + 1) == 'X')) {
                prefix += 2;
            }
            return rep.substring(0, prefix) + "0".repeat(pad) + rep.substring(prefix);
        }
        return " ".repeat(pad) + rep;
    }
}

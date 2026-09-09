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

    public static void open(LuaTable globals) {
        LuaTable stringTable = new LuaTable();

        stringTable.rawset(LuaString.valueOf("len"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.len'");
            if (args[0] instanceof LuaString ls) {
                return LuaInteger.valueOf(ls.value().length());
            }
            return LuaInteger.valueOf(args[0].toLuaString().length());
        }));

        stringTable.rawset(LuaString.valueOf("lower"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.lower'");
            return LuaString.valueOf(args[0].toLuaString().toLowerCase());
        }));

        stringTable.rawset(LuaString.valueOf("upper"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.upper'");
            return LuaString.valueOf(args[0].toLuaString().toUpperCase());
        }));

        stringTable.rawset(LuaString.valueOf("reverse"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.reverse'");
            return LuaString.valueOf(new StringBuilder(args[0].toLuaString()).reverse().toString());
        }));

        stringTable.rawset(LuaString.valueOf("rep"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.rep'");
            String s = args[0].toLuaString();
            LuaInteger nVal = args[1].toLuaInteger();
            if (nVal == null) throw new LuaException("bad argument #2 to 'string.rep' (number has no integer representation)");
            long n = nVal.toLong();
            String sep = (args.length > 2 && !args[2].isNil()) ? args[2].toLuaString() : "";
            if (n <= 0) return LuaString.valueOf("");
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
            if (totallen < 0 || totallen > Integer.MAX_VALUE - 8) {
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

        stringTable.rawset(LuaString.valueOf("sub"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "sub", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isNumber() && args[1].toLuaNumber() == null)) {
                throw LuaValue.argError(2, "sub", "number expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            if (args.length > 2 && !args[2].isNil() && !args[2].isNumber() && args[2].toLuaNumber() == null) {
                throw LuaValue.argError(3, "sub", "number expected, got " + args[2].typeName());
            }
            String s = args[0].toLuaString();
            int len = s.length();
            long start = args[1].toLong();
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : -1;

            if (start < 0) start = len + start + 1;
            if (end < 0) end = len + end + 1;

            if (start < 1) start = 1;
            if (end > len) end = len;

            if (start > end) return LuaString.valueOf("");
            return LuaString.valueOf(s.substring((int) start - 1, (int) end));
        }));

        stringTable.rawset(LuaString.valueOf("byte"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'string.byte'");
            String s = (args[0] instanceof LuaString ls) ? ls.value() : args[0].toLuaString();
            int len = s.length();
            long start = (args.length > 1 && !args[1].isNil()) ? args[1].toLong() : 1;
            long end = (args.length > 2 && !args[2].isNil()) ? args[2].toLong() : start;

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

        stringTable.rawset(LuaString.valueOf("char"), LuaFunction.of(args -> {
            int n = args.length;
            if (n == 0) return LuaString.EMPTY;
            if (n == 1) {
                LuaInteger intVal = args[0].toLuaInteger();
                if (intVal == null) {
                    throw new LuaException("bad argument #1 to 'char' (number has no integer representation)");
                }
                long val = intVal.toLong();
                if (val < 0 || val > 255) {
                    throw new LuaException("bad argument #1 to 'char' (value out of range)");
                }
                return LuaString.valueOf(String.valueOf((char) val));
            }
            char[] chars = new char[n];
            for (int i = 0; i < n; i++) {
                LuaInteger intVal = args[i].toLuaInteger();
                if (intVal == null) {
                    throw new LuaException("bad argument #" + (i + 1) + " to 'char' (number has no integer representation)");
                }
                long val = intVal.toLong();
                if (val < 0 || val > 255) {
                    throw new LuaException("bad argument #" + (i + 1) + " to 'char' (value out of range)");
                }
                chars[i] = (char) val;
            }
            return LuaString.valueOf(new String(chars));
        }));

        stringTable.rawset(LuaString.valueOf("format"), LuaFunction.of(args -> {
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
                            String res = String.valueOf((char) (int) code);
                            if (width > 1) {
                                if (flags.contains("-")) res = res + " ".repeat(width - 1);
                                else res = " ".repeat(width - 1) + res;
                            }
                            b.append(res);
                        }
                        case 'd', 'i' -> {
                            checkFormat(form, "-+ 0", true);
                            long n = checkFormatInteger(v, argIdx);
                            b.append(formatInteger(String.valueOf(spec), flags, width, prec, n));
                        }
                        case 'u' -> {
                            checkFormat(form, "-0", true);
                            long n = checkFormatInteger(v, argIdx);
                            b.append(formatInteger("u", flags, width, prec, n));
                        }
                        case 'o', 'x', 'X' -> {
                            checkFormat(form, "-#0", true);
                            long n = checkFormatInteger(v, argIdx);
                            b.append(formatInteger(String.valueOf(spec), flags, width, prec, n));
                        }
                        case 'a', 'A', 'f', 'e', 'E', 'g', 'G' -> {
                            checkFormat(form, "-+ #0", true);
                            double d = checkFormatNumber(v, argIdx);
                            b.append(formatFloat(String.valueOf(spec), flags, width, prec, d));
                        }
                        case 'p' -> {
                            checkFormat(form, "-", false);
                            String res = (v.isNil() || v.isBoolean() || v.isNumber()) ? "(null)" : "0x" + Long.toHexString(System.identityHashCode(v));
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
                                    b.append(Double.toHexString(d).toLowerCase(java.util.Locale.US));
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

        stringTable.rawset(LuaString.valueOf("packsize"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) throw new LuaException("bad argument #1 to 'string.packsize'");
            return LuaInteger.valueOf(StringPacker.packsize(args[0].toLuaString()));
        }));

        stringTable.rawset(LuaString.valueOf("pack"), LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) throw new LuaException("bad argument #1 to 'string.pack'");
            byte[] packed = StringPacker.pack(args[0].toLuaString(), args, 1);
            return LuaString.valueOf(new String(packed, java.nio.charset.StandardCharsets.ISO_8859_1));
        }));

        stringTable.rawset(LuaString.valueOf("unpack"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'string.unpack'");
            String fmt = args[0].toLuaString();
            String s = args[1].toLuaString();
            int pos = (args.length > 2 && !args[2].isNil()) ? (int) args[2].toLong() : 1;
            byte[] data = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            return StringPacker.unpack(fmt, data, pos);
        }));

        stringTable.rawset(LuaString.valueOf("find"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "find", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "find", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            boolean plain = (args.length > 3 && !args[3].isNil()) && args[3].toBoolean();
            return LuaPattern.find(args[0], args[1], args.length > 2 ? args[2] : null, plain);
        }));

        stringTable.rawset(LuaString.valueOf("match"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "match", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "match", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            return LuaPattern.match(args[0], args[1], args.length > 2 ? args[2] : null);
        }));

        stringTable.rawset(LuaString.valueOf("gsub"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "gsub", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "gsub", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            if (args.length < 3) throw new LuaException("bad argument #3 to 'string.gsub' (value expected)");
            return LuaPattern.gsub(args[0], args[1], args[2], args.length > 3 ? args[3] : null);
        }));

        stringTable.rawset(LuaString.valueOf("gmatch"), LuaFunction.of(args -> {
            if (args.length == 0 || (!args[0].isString() && !args[0].isNumber())) {
                throw LuaValue.argError(1, "gmatch", "string expected, got " + (args.length == 0 ? "no value" : args[0].typeName()));
            }
            if (args.length < 2 || (!args[1].isString() && !args[1].isNumber())) {
                throw LuaValue.argError(2, "gmatch", "string expected, got " + (args.length < 2 ? "no value" : args[1].typeName()));
            }
            return LuaPattern.gmatch(args[0], args[1], args.length > 2 ? args[2] : null);
        }));

        stringTable.rawset(LuaString.valueOf("dump"), LuaFunction.of(args -> {
            if (args.length == 0 || !(args[0] instanceof LuaFunction fn)) {
                throw new LuaException("bad argument #1 to 'string.dump' (function expected)");
            }
            boolean strip = args.length > 1 && args[1].toBoolean();
            byte[] dumped = ChunkSerializer.dump(fn, strip);
            return LuaString.valueOf(new String(dumped, java.nio.charset.StandardCharsets.ISO_8859_1));
        }));

        globals.rawset(LuaString.valueOf("string"), stringTable);

        LuaTable stringMt = new LuaTable();
        stringMt.rawset(LuaString.valueOf("__index"), stringTable);
        LuaString.setStringMetatable(stringMt);
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
            try {
                return Long.parseLong(v.toLuaString());
            } catch (NumberFormatException e) {
                try {
                    double d = Double.parseDouble(v.toLuaString());
                    long n = (long) d;
                    if (d == (double) n) {
                        return n;
                    }
                } catch (NumberFormatException ignored) {}
                throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got string)");
            }
        }
        throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got " + v.typeName() + ")");
    }

    private static double checkFormatNumber(LuaValue v, int argIdx) {
        if (v.isNumber()) {
            return v.toDouble();
        }
        if (v.isString()) {
            try {
                return Double.parseDouble(v.toLuaString());
            } catch (NumberFormatException e) {
                throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got string)");
            }
        }
        throw new LuaException("bad argument #" + argIdx + " to 'string.format' (number expected, got " + v.typeName() + ")");
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
                } else if (prec <= digits.length()) {
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

        String precStr = (prec >= 0 ? "." + prec : "");
        String widthStr = (width > 0 ? String.valueOf(width) : "");
        char s = spec.charAt(0);

        if (s == 'g' || s == 'G') {
            boolean hash = flags.contains("#");
            String cleanFlags = flags.replace("#", "");
            String raw = String.format(java.util.Locale.US, "%" + cleanFlags + precStr + s, d);
            if (!hash) {
                int expIdx = -1;
                for (int i = 0; i < raw.length(); i++) {
                    char ch = raw.charAt(i);
                    if (ch == 'e' || ch == 'E') {
                        expIdx = i;
                        break;
                    }
                }
                String mantissa = (expIdx >= 0) ? raw.substring(0, expIdx) : raw;
                String exponent = (expIdx >= 0) ? raw.substring(expIdx) : "";
                if (mantissa.indexOf('.') >= 0) {
                    while (mantissa.endsWith("0")) {
                        mantissa = mantissa.substring(0, mantissa.length() - 1);
                    }
                    if (mantissa.endsWith(".")) {
                        mantissa = mantissa.substring(0, mantissa.length() - 1);
                    }
                }
                raw = mantissa + exponent;
            }
            if (width > raw.length()) {
                int pad = width - raw.length();
                if (flags.contains("-")) {
                    return raw + " ".repeat(pad);
                } else if (flags.contains("0")) {
                    if (raw.startsWith("+") || raw.startsWith("-") || raw.startsWith(" ")) {
                        return raw.substring(0, 1) + "0".repeat(pad) + raw.substring(1);
                    } else {
                        return "0".repeat(pad) + raw;
                    }
                } else {
                    return " ".repeat(pad) + raw;
                }
            }
            return raw;
        } else {
            return String.format(java.util.Locale.US, "%" + flags + widthStr + precStr + s, d);
        }
    }
}

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

public final class Utf8Lib {
    private Utf8Lib() {}

    private static final long[] LIMITS = new long[] {
        ~0L, 0x80L, 0x800L, 0x10000L, 0x200000L, 0x4000000L
    };
    private static final long MAXUTF = 0x7FFFFFFFL;

    private static boolean isCont(byte b) {
        return (b & 0xC0) == 0x80;
    }

    /**
     * {@code luaL_checklstring} coercion: a number is accepted and converted
     * to its Lua string form, exactly as PUC's utf8 library does for its
     * string arguments. Returns null when the value is neither a string nor
     * a number.
     */
    private static LuaString checkString(LuaValue v, int arg, String func) {
        if (v.isString() || v.isNumber()) {
            return v.isString() ? (LuaString) v : LuaString.valueOf(v.toLuaString());
        }
        throw new LuaException("bad argument #" + arg + " to 'utf8." + func
                + "' (string expected, got " + v.typeName() + ")");
    }

    /**
     * {@code luaL_checkinteger} for utf8 positions: coerces a numeric string
     * (lua_tointegerx) and reports the argument index and PUC function name.
     */
    private static long checkInteger(LuaValue[] args, int idx, String func) {
        if (idx >= args.length || args[idx].isNil()) {
            throw LuaValue.argError(idx + 1, func, "number expected, got " + (idx >= args.length ? "no value" : "nil"));
        }
        LuaValue v = args[idx];
        LuaInteger i = v.toLuaIntegerCoercingStrings();
        if (i != null) return i.toLong();
        throw LuaValue.argError(idx + 1, func, v.integerConversionError());
    }

    private static int utf8Decode(byte[] bytes, int pos, int max, long[] val, boolean strict) {
        if (pos >= max) return -1;
        int c = bytes[pos] & 0xFF;
        long res = 0;
        int count = 0;
        if (c < 0x80) {
            res = c;
        } else {
            int first = c;
            for (; (first & 0x40) != 0; first <<= 1) {
                count++;
                if (pos + count >= max) return -1;
                int cc = bytes[pos + count] & 0xFF;
                if ((cc & 0xC0) != 0x80) return -1;
                res = (res << 6) | (cc & 0x3F);
            }
            if (count == 0) return -1;
            res |= ((long) (first & 0x7F) << (count * 5));
            if (count > 5 || res > MAXUTF || res < LIMITS[count]) {
                return -1;
            }
        }
        if (strict) {
            if (res > 0x10FFFFL || (res >= 0xD800L && res <= 0xDFFFL)) {
                return -1;
            }
        }
        if (val != null) val[0] = res;
        return pos + count + 1;
    }

    private static long posRelat(long pos, int len) {
        if (pos >= 0) return pos;
        else if (0 - pos > len) return 0;
        else return len + pos + 1;
    }

    public static void open(LuaTable globals) {
        LuaTable utf8 = new LuaTable();
        fillInto(utf8, globals);
        globals.rawset(LuaString.interned("utf8"), utf8);
    }

    public static void fillInto(LuaTable utf8, LuaTable globals) {

        utf8.rawset(LuaString.interned("charpattern"), LuaString.valueOf("[\0-\u007F\u00C2-\u00FD][\u0080-\u00BF]*"));

        utf8.rawset(LuaString.interned("char"), LuaFunction.of(args -> {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < args.length; i++) {
                LuaValue arg = args[i];
                // luaL_checkinteger coerces a numeric string ("65" -> 'A').
                LuaInteger ci = arg.toLuaIntegerCoercingStrings();
                if (ci == null) {
                    throw LuaValue.argError(i + 1, "utf8.char", arg.integerConversionError());
                }
                long codePoint = ci.toLong();
                if (codePoint < 0 || codePoint > MAXUTF) {
                    throw LuaValue.argError(i + 1, "utf8.char", "value out of range");
                }
                if (codePoint < 0x80) {
                    baos.write((int) codePoint);
                } else {
                    byte[] buf = new byte[8];
                    int n = 1;
                    long x = codePoint;
                    int mfb = 0x3f;
                    do {
                        buf[buf.length - (n++)] = (byte) (0x80 | (x & 0x3f));
                        x >>= 6;
                        mfb >>= 1;
                    } while (x > mfb);
                    buf[buf.length - n] = (byte) ((~mfb << 1) | x);
                    baos.write(buf, buf.length - n, n);
                }
            }
            return LuaString.valueOf(new String(baos.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1));
        }));

        utf8.rawset(LuaString.interned("len"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'utf8.len' (string expected, got no value)");
            byte[] bytes = checkString(args[0], 1, "len").toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = bytes.length;
            long posi = posRelat((args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "len") : 1, len);
            long posj = posRelat((args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "len") : -1, len);
            boolean lax = args.length > 3 && args[3].toBoolean();
            if (1 > posi || posi > len + 1) {
                throw new LuaException("bad argument #2 to 'utf8.len' (initial position out of bounds)");
            }
            posi--;
            posj--;
            if (posj >= len || posj < -1) {
                throw new LuaException("bad argument #3 to 'utf8.len' (final position out of bounds)");
            }
            long n = 0;
            while (posi <= posj) {
                int next = utf8Decode(bytes, (int) posi, len, null, !lax);
                if (next < 0) {
                    return Varargs.of(LuaNil.NIL, LuaInteger.valueOf(posi + 1));
                }
                posi = next;
                n++;
            }
            return LuaInteger.valueOf(n);
        }));

        utf8.rawset(LuaString.interned("codepoint"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'utf8.codepoint' (string expected, got no value)");
            byte[] bytes = checkString(args[0], 1, "codepoint").toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = bytes.length;
            long posi = posRelat((args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "codepoint") : 1, len);
            long pose = posRelat((args.length > 2 && !args[2].isNil()) ? checkInteger(args, 2, "codepoint") : posi, len);
            boolean lax = args.length > 3 && args[3].toBoolean();
            if (posi < 1) throw new LuaException("bad argument #2 to 'utf8.codepoint' (out of bounds)");
            if (pose > len) throw new LuaException("bad argument #3 to 'utf8.codepoint' (out of bounds)");
            if (posi > pose) return Varargs.EMPTY;
            int start = (int) posi - 1;
            int end = (int) pose - 1;
            List<LuaValue> cps = new ArrayList<>();
            long[] val = new long[1];
            while (start <= end) {
                int next = utf8Decode(bytes, start, len, val, !lax);
                if (next < 0) {
                    throw new LuaException("invalid UTF-8 code");
                }
                cps.add(LuaInteger.valueOf(val[0]));
                start = next;
            }
            return Varargs.of(cps.toArray(new LuaValue[0]));
        }));

        utf8.rawset(LuaString.interned("offset"), LuaFunction.of(args -> {
            // luaL_checklstring(L,1) runs before luaL_checkinteger(L,2), so a
            // missing/nil first argument is blamed before the position.
            if (args.length == 0) {
                throw LuaValue.argError(1, "offset", "string expected, got no value");
            }
            byte[] bytes = checkString(args[0], 1, "offset").toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = bytes.length;
            long n = checkInteger(args, 1, "offset");
            long posi = (n >= 0) ? 1 : len + 1;
            if (args.length > 2 && !args[2].isNil()) {
                posi = posRelat(checkInteger(args, 2, "offset"), len);
            }
            if (posi < 1 || posi > len + 1) {
                throw new LuaException("bad argument #3 to 'utf8.offset' (position out of bounds)");
            }
            int p = (int) posi - 1;

            if (n == 0) {
                while (p > 0 && isCont(bytes[p])) {
                    p--;
                }
            } else {
                if (p < len && isCont(bytes[p])) {
                    throw new LuaException("initial position is a continuation byte");
                }
                if (n < 0) {
                    while (n < 0 && p > 0) {
                        do {
                            p--;
                        } while (p > 0 && isCont(bytes[p]));
                        n++;
                    }
                } else {
                    n--;
                    while (n > 0 && p < len) {
                        do {
                            p++;
                        } while (p < len && isCont(bytes[p]));
                        n--;
                    }
                }
            }

            if (n == 0) {
                return LuaInteger.valueOf(p + 1);
            } else {
                return LuaNil.NIL;
            }
        }));

        LuaFunction iterAuxStrict = LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'utf8.codes' iterator (string expected, got no value)");
            byte[] bytes = checkString(args[0], 1, "codes").toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = bytes.length;
            long n = args.length > 1 ? args[1].toLong() : 0;
            int pos = (int) n;
            if (pos < 0) return LuaNil.NIL;
            if (pos < len) {
                while (pos < len && isCont(bytes[pos])) pos++;
            }
            if (pos >= len) return LuaNil.NIL;
            long[] val = new long[1];
            int next = utf8Decode(bytes, pos, len, val, true);
            if (next < 0 || (next < len && isCont(bytes[next]))) {
                throw new LuaException("invalid UTF-8 code");
            }
            return Varargs.of(LuaInteger.valueOf(pos + 1), LuaInteger.valueOf(val[0]));
        });

        LuaFunction iterAuxLax = LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'utf8.codes' iterator (string expected, got no value)");
            byte[] bytes = checkString(args[0], 1, "codes").toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            int len = bytes.length;
            long n = args.length > 1 ? args[1].toLong() : 0;
            int pos = (int) n;
            if (pos < 0) return LuaNil.NIL;
            if (pos < len) {
                while (pos < len && isCont(bytes[pos])) pos++;
            }
            if (pos >= len) return LuaNil.NIL;
            long[] val = new long[1];
            int next = utf8Decode(bytes, pos, len, val, false);
            if (next < 0 || (next < len && isCont(bytes[next]))) {
                throw new LuaException("invalid UTF-8 code");
            }
            return Varargs.of(LuaInteger.valueOf(pos + 1), LuaInteger.valueOf(val[0]));
        });

        utf8.rawset(LuaString.interned("codes"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'utf8.codes' (string expected, got no value)");
            boolean lax = args.length > 1 && args[1].toBoolean();
            LuaString str = checkString(args[0], 1, "codes");
            byte[] bytes = str.toLuaString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            if (bytes.length > 0 && isCont(bytes[0])) {
                throw new LuaException("invalid UTF-8 code");
            }
            return Varargs.of(lax ? iterAuxLax : iterAuxStrict, str, LuaInteger.valueOf(0));
        }));
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class StringPacker {
    private static final int DEFAULT_MAXALIGN = 8;

    private StringPacker() {}

    private static int getalign(int size, int maxalign) {
        if (size == 0 || (size & (size - 1)) != 0) {
            if (size != 0 && maxalign != 1) {
                throw new LuaException("format asks for alignment not power of 2");
            }
            return 1;
        }
        return Math.min(size, maxalign);
    }

    private static int getpadding(int len, int align) {
        if (align <= 1) return 0;
        return ((align - (len & (align - 1))) & (align - 1));
    }

    private static final class Option {
        char code;
        int size;
        int align;
        boolean isSigned;
    }

    private static int parseDigits(String fmt, int[] indexRef) {
        int val = 0;
        int i = indexRef[0];
        int len = fmt.length();
        int maxLimit = (Integer.MAX_VALUE - 9) / 10;
        if (i < len && Character.isDigit(fmt.charAt(i))) {
            do {
                int d = fmt.charAt(i++) - '0';
                val = val * 10 + d;
            } while (i < len && Character.isDigit(fmt.charAt(i)) && val <= maxLimit);
        }
        indexRef[0] = i;
        return val;
    }

    private static Option nextOption(String fmt, int[] indexRef, int maxalign) {
        int len = fmt.length();
        while (indexRef[0] < len) {
            char c = fmt.charAt(indexRef[0]++);
            if (c == ' ') continue;

            Option opt = new Option();
            opt.code = c;

            switch (c) {
                case '<', '>', '=' -> {
                    return opt;
                }
                case '!' -> {
                    if (indexRef[0] < len && Character.isDigit(fmt.charAt(indexRef[0]))) {
                        int a = parseDigits(fmt, indexRef);
                        if (a < 1 || a > 16) {
                            throw new LuaException("integral size (" + a + ") out of limits [1,16]");
                        }
                        // C getnumlimit: only the [1,16] range is validated;
                        // non-power-of-2 alignments (e.g. !3) are accepted.
                        opt.size = a;
                    } else {
                        opt.size = DEFAULT_MAXALIGN;
                    }
                    return opt;
                }
                case 'X' -> {
                    if (indexRef[0] >= len) {
                        throw LuaValue.argError(1, "string.pack", "invalid next option for option 'X'");
                    }
                    char next = fmt.charAt(indexRef[0]++);
                    int a;
                    switch (next) {
                        case 'b', 'B', 'x' -> a = 1;
                        case 'h', 'H' -> a = getalign(2, maxalign);
                        case 'f' -> a = getalign(4, maxalign);
                        case 'l', 'L', 'j', 'J', 'd', 'T', 'n' -> a = getalign(8, maxalign);
                        case 'i', 'I' -> {
                            int size = 4;
                            if (indexRef[0] < len && Character.isDigit(fmt.charAt(indexRef[0]))) {
                                size = parseDigits(fmt, indexRef);
                            }
                            if (size < 1 || size > 16) {
                                throw new LuaException("integral size (" + size + ") out of limits [1,16]");
                            }
                            a = getalign(size, maxalign);
                        }
                        default -> throw LuaValue.argError(1, "string.pack", "invalid next option for option 'X'");
                    }
                    opt.align = a;
                    opt.size = 0; // X only contributes alignment padding
                    return opt;
                }
                case 'b' -> {
                    opt.size = 1;
                    opt.align = 1;
                    opt.isSigned = true;
                    return opt;
                }
                case 'B', 'x' -> {
                    opt.size = 1;
                    opt.align = 1;
                    opt.isSigned = false;
                    return opt;
                }
                case 'h' -> {
                    opt.size = 2;
                    opt.align = getalign(2, maxalign);
                    opt.isSigned = true;
                    return opt;
                }
                case 'H' -> {
                    opt.size = 2;
                    opt.align = getalign(2, maxalign);
                    opt.isSigned = false;
                    return opt;
                }
                case 'l', 'j' -> {
                    opt.size = 8;
                    opt.align = getalign(8, maxalign);
                    opt.isSigned = true;
                    return opt;
                }
                case 'L', 'J', 'T' -> {
                    opt.size = 8;
                    opt.align = getalign(8, maxalign);
                    opt.isSigned = false;
                    return opt;
                }
                case 'i', 'I' -> {
                    int size = 4;
                    if (indexRef[0] < len && Character.isDigit(fmt.charAt(indexRef[0]))) {
                        size = parseDigits(fmt, indexRef);
                    }
                    if (size < 1 || size > 16) {
                        throw new LuaException("integral size (" + size + ") out of limits [1,16]");
                    }
                    opt.size = size;
                    opt.align = getalign(size, maxalign);
                    opt.isSigned = (c == 'i');
                    return opt;
                }
                case 'f' -> {
                    opt.size = 4;
                    opt.align = getalign(4, maxalign);
                    return opt;
                }
                case 'd', 'n' -> {
                    opt.size = 8;
                    opt.align = getalign(8, maxalign);
                    return opt;
                }
                case 'c' -> {
                    if (indexRef[0] >= len || !Character.isDigit(fmt.charAt(indexRef[0]))) {
                        throw new LuaException("missing size for format option 'c'");
                    }
                    int count = parseDigits(fmt, indexRef);
                    opt.size = count;
                    opt.align = 1;
                    return opt;
                }
                case 's' -> {
                    int size = 8;
                    if (indexRef[0] < len && Character.isDigit(fmt.charAt(indexRef[0]))) {
                        size = parseDigits(fmt, indexRef);
                    }
                    if (size < 1 || size > 16) {
                        throw new LuaException("integral size (" + size + ") out of limits [1,16]");
                    }
                    opt.size = size;
                    opt.align = getalign(size, maxalign);
                    return opt;
                }
                case 'z' -> {
                    opt.size = -1; // variable
                    opt.align = 1;
                    return opt;
                }
                default -> throw new LuaException("invalid format option '" + c + "'");
            }
        }
        return null;
    }

    public static int packsize(String fmt) {
        int total = 0;
        int maxalign = 1;
        int[] indexRef = new int[]{0};

        while (true) {
            Option opt = nextOption(fmt, indexRef, maxalign);
            if (opt == null) break;

            if (opt.code == '<' || opt.code == '>' || opt.code == '=') {
                continue;
            }
            if (opt.code == '!') {
                maxalign = opt.size;
                continue;
            }
            if (opt.code == 's' || opt.code == 'z') {
                throw new LuaException("variable-length format in 'string.packsize'");
            }
            if (opt.code == 'X') {
                int pad = getpadding(total, opt.align);
                if ((long) total + pad > Integer.MAX_VALUE) {
                    throw LuaValue.argError(1, "string.packsize", "format result too large");
                }
                total += pad;
                continue;
            }
            int pad = getpadding(total, opt.align);
            if ((long) total + pad + opt.size > Integer.MAX_VALUE) {
                throw LuaValue.argError(1, "string.packsize", "format result too large");
            }
            total += pad + opt.size;
        }
        return total;
    }

    private static void packInt(ByteArrayOutputStream out, long val, ByteOrder order, int size, boolean isSigned) {
        packInt(out, val, order, size, isSigned, -1);
    }

    /**
     * @param argNum 1-based Lua argument index to blame on overflow, or -1
     *               for internal uses (string length prefixes) that PUC does
     *               not attribute to an argument.
     */
    private static void packInt(ByteArrayOutputStream out, long val, ByteOrder order, int size, boolean isSigned, int argNum) {
        if (size < 8) {
            if (isSigned) {
                long max = (1L << (size * 8 - 1)) - 1;
                long min = -(1L << (size * 8 - 1));
                if (val < min || val > max) {
                    throw LuaValue.argError(argNum, "string.pack", "integer overflow");
                }
            } else {
                if (val < 0 || (val >>> (size * 8)) != 0) {
                    throw LuaValue.argError(argNum, "string.pack", "unsigned overflow");
                }
            }
        }

        byte[] buf = new byte[size];
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int k = 0; k < size; k++) {
                if (k < 8) {
                    buf[k] = (byte) ((val >>> (k * 8)) & 0xFF);
                } else {
                    buf[k] = (isSigned && val < 0) ? (byte) 0xFF : 0;
                }
            }
        } else {
            for (int k = 0; k < size; k++) {
                int shiftIndex = size - 1 - k;
                if (shiftIndex < 8) {
                    buf[k] = (byte) ((val >>> (shiftIndex * 8)) & 0xFF);
                } else {
                    buf[k] = (isSigned && val < 0) ? (byte) 0xFF : 0;
                }
            }
        }
        out.writeBytes(buf);
    }

    // C luaL_checkinteger/checknumber/checklstring: a missing value is a Lua
    // argument error, never a Java ArrayIndexOutOfBoundsException.
    private static LuaValue packArg(LuaValue[] args, int argIdx, String what) {
        if (argIdx >= args.length) {
            throw new LuaException("bad argument #" + (argIdx + 1)
                    + " to 'string.pack' (" + what + " expected, got nil)");
        }
        return args[argIdx];
    }

    public static byte[] pack(String fmt, LuaValue[] args, int argOffset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteOrder order = ByteOrder.nativeOrder();
        int maxalign = 1;
        int argIdx = argOffset;
        int[] indexRef = new int[]{0};
        while (true) {
            Option opt = nextOption(fmt, indexRef, maxalign);
            if (opt == null) break;

            if (opt.code == '<') { order = ByteOrder.LITTLE_ENDIAN; continue; }
            if (opt.code == '>') { order = ByteOrder.BIG_ENDIAN; continue; }
            if (opt.code == '=') { order = ByteOrder.nativeOrder(); continue; }
            if (opt.code == '!') { maxalign = opt.size; continue; }

            int pad = getpadding(out.size(), opt.align);
            long cap = org.luava.runtime.LuaState.allocationLimit();
            if ((long) out.size() + pad + (opt.size > 0 ? opt.size : 0) > cap) {
                throw LuaValue.argError(1, "string.pack", "format result too large");
            }
            for (int p = 0; p < pad; p++) out.write(0);

            if (opt.code == 'X') {
                continue;
            }

            switch (opt.code) {
                case 'b', 'B' -> {
                    long val = packArg(args, argIdx++, "number").toLong();
                    packInt(out, val, order, 1, opt.isSigned, argIdx);
                }
                case 'x' -> out.write(0);
                case 'h', 'H' -> {
                    long val = packArg(args, argIdx++, "number").toLong();
                    packInt(out, val, order, 2, opt.isSigned, argIdx);
                }
                case 'l', 'L', 'j', 'J', 'T' -> {
                    long val = packArg(args, argIdx++, "number").toLong();
                    packInt(out, val, order, 8, opt.isSigned, argIdx);
                }
                case 'i', 'I' -> {
                    long val = packArg(args, argIdx++, "number").toLong();
                    packInt(out, val, order, opt.size, opt.isSigned, argIdx);
                }
                case 'f' -> {
                    float f = (float) packArg(args, argIdx++, "number").toDouble();
                    ByteBuffer bb = ByteBuffer.allocate(4).order(order).putFloat(f);
                    out.writeBytes(bb.array());
                }
                case 'd', 'n' -> {
                    double d = packArg(args, argIdx++, "number").toDouble();
                    ByteBuffer bb = ByteBuffer.allocate(8).order(order).putDouble(d);
                    out.writeBytes(bb.array());
                }
                case 'c' -> {
                    String str = packArg(args, argIdx++, "string").toLuaString();
                    byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (bytes.length > opt.size) {
                        throw LuaValue.argError(argIdx, "string.pack", "string longer than given size");
                    }
                    out.writeBytes(bytes);
                    for (int k = bytes.length; k < opt.size; k++) {
                        out.write(0);
                    }
                }
                case 's' -> {
                    String str = packArg(args, argIdx++, "string").toLuaString();
                    byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (opt.size < 8 && bytes.length >= (1L << (opt.size * 8))) {
                        throw LuaValue.argError(argIdx, "string.pack", "string length does not fit in given size");
                    }
                    packInt(out, bytes.length, order, opt.size, false);
                    out.writeBytes(bytes);
                }
                case 'z' -> {
                    String str = packArg(args, argIdx++, "string").toLuaString();
                    if (str.indexOf('\0') >= 0) {
                        throw LuaValue.argError(argIdx, "string.pack", "string contains zeros");
                    }
                    out.writeBytes(str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    out.write(0);
                }
                default -> {}
            }
        }
        return out.toByteArray();
    }

    /** PUC luaL_argcheck(..., 2, "data string too short") from string.unpack. */
    private static LuaException tooShort() {
        return LuaValue.argError(2, "string.unpack", "data string too short");
    }

    private static long unpackInt(byte[] data, int pos, ByteOrder order, int size, boolean isSigned) {
        if (pos + size > data.length) {
            throw tooShort();
        }
        int limit = Math.min(size, 8);
        long res = 0;
        if (order == ByteOrder.LITTLE_ENDIAN) {
            for (int k = limit - 1; k >= 0; k--) {
                res = (res << 8) | (data[pos + k] & 0xFFL);
            }
        } else {
            for (int k = limit - 1; k >= 0; k--) {
                res = (res << 8) | (data[pos + (size - 1 - k)] & 0xFFL);
            }
        }

        if (size < 8) {
            if (isSigned) {
                long mask = 1L << (size * 8 - 1);
                res = ((res ^ mask) - mask);
            }
        } else if (size > 8) {
            int mask = (!isSigned || res >= 0) ? 0 : 0xFF;
            for (int k = limit; k < size; k++) {
                int b = (order == ByteOrder.LITTLE_ENDIAN)
                        ? (data[pos + k] & 0xFF)
                        : (data[pos + (size - 1 - k)] & 0xFF);
                if (b != mask) {
                    throw new LuaException(size + "-byte integer does not fit into Lua Integer");
                }
            }
        }
        return res;
    }

    public static LuaValue unpack(String fmt, byte[] data, int startPos1Based) {
        // C posrelatI: 0 and overly-negative positions clamp to 1; only
        // positions past the end are an error.
        if (startPos1Based < 0) {
            startPos1Based = data.length + startPos1Based + 1;
        }
        if (startPos1Based < 1) {
            startPos1Based = 1;
        }
        if (startPos1Based > data.length + 1) {
            throw LuaValue.argError(3, "string.unpack", "initial position out of string");
        }
        ByteOrder order = ByteOrder.nativeOrder();
        int maxalign = 1;
        int pos = startPos1Based - 1; // 0-based
        int[] indexRef = new int[]{0};
        List<LuaValue> values = new ArrayList<>();

        while (true) {
            Option opt = nextOption(fmt, indexRef, maxalign);
            if (opt == null) break;

            if (opt.code == '<') { order = ByteOrder.LITTLE_ENDIAN; continue; }
            if (opt.code == '>') { order = ByteOrder.BIG_ENDIAN; continue; }
            if (opt.code == '=') { order = ByteOrder.nativeOrder(); continue; }
            if (opt.code == '!') { maxalign = opt.size; continue; }

            int pad = getpadding(pos, opt.align);
            if ((long) pos + pad + (opt.size > 0 ? opt.size : 0) > Integer.MAX_VALUE) {
                throw LuaValue.argError(1, "string.unpack", "format result too large");
            }
            pos += pad;

            if (opt.code == 'X') {
                continue;
            }

            switch (opt.code) {
                case 'b', 'B' -> {
                    long val = unpackInt(data, pos, order, 1, opt.isSigned);
                    values.add(LuaInteger.valueOf(val));
                    pos += 1;
                }
                case 'x' -> {
                    if (pos + 1 > data.length) throw tooShort();
                    pos += 1;
                }
                case 'h', 'H' -> {
                    long val = unpackInt(data, pos, order, 2, opt.isSigned);
                    values.add(LuaInteger.valueOf(val));
                    pos += 2;
                }
                case 'l', 'L', 'j', 'J', 'T' -> {
                    long val = unpackInt(data, pos, order, 8, opt.isSigned);
                    values.add(LuaInteger.valueOf(val));
                    pos += 8;
                }
                case 'i', 'I' -> {
                    long val = unpackInt(data, pos, order, opt.size, opt.isSigned);
                    values.add(LuaInteger.valueOf(val));
                    pos += opt.size;
                }
                case 'f' -> {
                    if (pos + 4 > data.length) throw tooShort();
                    ByteBuffer bb = ByteBuffer.wrap(data, pos, 4).order(order);
                    values.add(LuaFloat.valueOf(bb.getFloat()));
                    pos += 4;
                }
                case 'd', 'n' -> {
                    if (pos + 8 > data.length) throw tooShort();
                    ByteBuffer bb = ByteBuffer.wrap(data, pos, 8).order(order);
                    values.add(LuaFloat.valueOf(bb.getDouble()));
                    pos += 8;
                }
                case 'c' -> {
                    if (pos + opt.size > data.length) throw tooShort();
                    String s = new String(data, pos, opt.size, java.nio.charset.StandardCharsets.ISO_8859_1);
                    values.add(LuaString.valueOf(s));
                    pos += opt.size;
                }
                case 's' -> {
                    long strLen = unpackInt(data, pos, order, opt.size, false);
                    pos += opt.size;
                    if (strLen < 0 || pos + strLen > data.length) {
                        throw tooShort();
                    }
                    String s = new String(data, pos, (int) strLen, java.nio.charset.StandardCharsets.ISO_8859_1);
                    values.add(LuaString.valueOf(s));
                    pos += (int) strLen;
                }
                case 'z' -> {
                    int end = pos;
                    while (end < data.length && data[end] != 0) end++;
                    if (end >= data.length) {
                        throw LuaValue.argError(2, "string.unpack", "unfinished string for format 'z'");
                    }
                    String s = new String(data, pos, end - pos, java.nio.charset.StandardCharsets.ISO_8859_1);
                    values.add(LuaString.valueOf(s));
                    pos = end + 1;
                }
                default -> {}
            }
        }
        values.add(LuaInteger.valueOf(pos + 1));
        return Varargs.of(values.toArray(new LuaValue[0]));
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend.lexer;

import java.util.Arrays;

/**
 * Compact cache-friendly token stream produced by {@link Lexer#scanStream()} for internal
 * compilation. Packs {@code (line, column, type)} into a single {@code long[]} stream for hot
 * parser traversal while keeping source offsets cold unless diagnostics or non-identifier
 * lexemes are requested.
 */
public final class TokenStream {
    private static final TokenType[] TYPES = TokenType.values();
    private static final long COL_MASK = 0x0FFFFFFFL;

    private final String source;
    private long[] meta;
    private long[] spans;
    private Object[] literals;
    private long[] numBits;
    private int size;

    TokenStream(String source, int initialCapacity) {
        this.source = source;
        this.meta = new long[initialCapacity];
        this.spans = new long[initialCapacity];
        this.literals = new Object[initialCapacity];
        this.numBits = new long[initialCapacity];
    }

    void add(TokenType type, int line, int column, int start, int end, Object literal, long bits) {
        int idx = size;
        if (idx == meta.length) {
            grow();
        }
        long clampedCol = column > COL_MASK ? COL_MASK : (column & COL_MASK);
        meta[idx] = (((long) line) << 36) | (clampedCol << 8) | (type.ordinal() & 0xFFL);
        spans[idx] = (((long) start) << 32) | (end & 0xFFFFFFFFL);
        literals[idx] = literal;
        numBits[idx] = bits;
        size = idx + 1;
    }

    private void grow() {
        int oldCap = meta.length;
        int newCap = oldCap + Math.max(16, oldCap);
        meta = Arrays.copyOf(meta, newCap);
        spans = Arrays.copyOf(spans, newCap);
        literals = Arrays.copyOf(literals, newCap);
        numBits = Arrays.copyOf(numBits, newCap);
    }

    public int size() {
        return size;
    }

    public long meta(int idx) {
        return meta[idx];
    }

    public TokenType type(int idx) {
        return TYPES[(int) (meta[idx] & 0xFFL)];
    }

    public static TokenType unpackType(long m) {
        return TYPES[(int) (m & 0xFFL)];
    }

    public static int unpackLine(long m) {
        return (int) (m >>> 36);
    }

    public static int unpackColumn(long m) {
        return (int) ((m >>> 8) & COL_MASK);
    }

    public int line(int idx) {
        return (int) (meta[idx] >>> 36);
    }

    public int column(int idx) {
        return (int) ((meta[idx] >>> 8) & COL_MASK);
    }

    public String stringLiteral(int idx) {
        return (String) literals[idx];
    }

    public long longLiteral(int idx) {
        return numBits[idx];
    }

    public double doubleLiteral(int idx) {
        return Double.longBitsToDouble(numBits[idx]);
    }

    public Object rawLiteral(int idx) {
        TokenType t = TYPES[(int) (meta[idx] & 0xFFL)];
        if (t == TokenType.INTEGER_LITERAL) {
            return numBits[idx];
        }
        if (t == TokenType.FLOAT_LITERAL) {
            return Double.longBitsToDouble(numBits[idx]);
        }
        return literals[idx];
    }

    public String lexeme(int idx) {
        TokenType t = TYPES[(int) (meta[idx] & 0xFFL)];
        if (t == TokenType.IDENTIFIER || t == TokenType.INVALID) {
            return (String) literals[idx];
        }
        String fixed = t.fixedLexeme();
        if (fixed != null) {
            return fixed;
        }
        long sp = spans[idx];
        int start = (int) (sp >>> 32);
        int end = (int) sp;
        return source.substring(start, end);
    }
}

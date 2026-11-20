/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;


public final class LuaString extends LuaValue {
    public static final LuaString EMPTY = new LuaString("");
    /**
     * Pre-allocated PUC memory-error message ({@code MEMERRMSG}). When the JVM
     * heap is exhausted, building the error message must not allocate again or
     * the second {@code OutOfMemoryError} escapes past {@code pcall}. PUC
     * solves this with a pre-created message string; this is the same idea.
     */
    public static final LuaString MEMORY_ERROR = new LuaString("not enough memory");
    private static final LuaString[] ASCII_CACHE = new LuaString[256];
    static {
        for (int i = 0; i < 256; i++) {
            ASCII_CACHE[i] = new LuaString(String.valueOf((char) i));
        }
    }
    private static final int MAX_SHORT_STRING = 40;
    private static final int MAX_INTERN_ENTRIES = 65_536;
    private static final int INT_CACHE_SIZE = 1024;
    private static final int SLICE_SHARE_SMALL_CAP = 256;
    private static final LuaString[] INT_STRING_CACHE = new LuaString[INT_CACHE_SIZE];
    private static final LuaString MIN_LONG_STRING = new LuaString(Long.toString(Long.MIN_VALUE));
    private static final byte[] DIGIT_TENS = new byte[100];
    private static final byte[] DIGIT_ONES = new byte[100];
    static {
        for (int i = 0; i < 100; i++) {
            DIGIT_TENS[i] = (byte) ('0' + (i / 10));
            DIGIT_ONES[i] = (byte) ('0' + (i % 10));
        }
        for (int i = 0; i < INT_CACHE_SIZE; i++) {
            INT_STRING_CACHE[i] = (i < 10) ? ASCII_CACHE['0' + i] : new LuaString(Integer.toString(i));
        }
    }
    /**
     * Intern pool for short strings, mirroring Lua's global string table:
     * equal short strings share one object while they are alive (required by
     * Lua 5.4 semantics, e.g. {@code string.format('%p', s1) ==
     * string.format('%p', s2)} for equal short strings). The pool is
     * <em>weak</em> on both sides: the key is only strongly reachable through
     * the interned {@code LuaString}'s value, and the value is a
     * {@link WeakReference}. So a long-running server that churns unique
     * short strings does not pin them forever — once the interpreter drops a
     * string, both the entry and the string are collected. An unbounded
     * strong map here was a real leak (200k unique ids grew it to 200k
     * entries held for the JVM's life).
     */
    /**
     * Canonical pool for {@link #interned} (compiler constants, metamethod
     * and stdlib registration names — all bounded, program-shaped sets, not
     * script-generated data). Strong both ways on purpose:
     *
     * <p>These objects become table keys. The previous weak pool let a
     * canonical key be collected, after which {@code interned("__index")}
     * produced a <em>different</em> object and every hash lookup degraded to
     * {@code String.equals} on collision (hundreds of ms on OOP workloads).
     * Only cold paths call {@code interned}, so an unbounded strong map here
     * cannot be driven by untrusted scripts (they go through
     * {@link #valueOf}, which never pools).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, LuaString> INTERN_POOL =
            new java.util.concurrent.ConcurrentHashMap<>(1024);

    /** Allocation-free probe for pool hits (thread-confined, mutated per use). */
    private static final class ProbeKey {
        String str;
        int hash;

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof String s ? str.equals(s) : o == this;
        }
    }

    private static final ThreadLocal<ProbeKey> PROBE = ThreadLocal.withInitial(ProbeKey::new);

    private volatile String value;
    private volatile byte[] rawBytes;
    private final int rawOffset;
    private final int rawLength;
    private int strHash;

    public static void setStringMetatable(LuaTable mt) {
        LuaValue.setBasicMetatable(LuaType.STRING, mt);
    }

    @Override
    public LuaTable getMetatable() {
        return LuaValue.getBasicMetatable(LuaType.STRING);
    }

    public LuaString(String value) {
        String s = value != null ? value : "";
        this.value = s;
        this.rawBytes = null;
        this.rawOffset = 0;
        this.rawLength = s.length();
        this.strHash = s.hashCode();
        accountAlloc(this.rawLength);
    }

    private LuaString(byte[] bytes, int off, int len) {
        this.value = null;
        this.rawBytes = bytes;
        this.rawOffset = off;
        this.rawLength = len;
        this.strHash = 0;
        accountAlloc(len);
    }

    private void accountAlloc(int len) {
        if (len >= 1024) {
            org.luava.runtime.eval.GCManager.beginAllocLargeString(this);
            try {
                org.luava.runtime.eval.GCManager.onAlloc(Math.max(16, len));
            } finally {
                org.luava.runtime.eval.GCManager.endAllocLargeString(this);
            }
        } else {
            org.luava.runtime.eval.GCManager.onAlloc(Math.max(16, len));
        }
    }

    private static int latin1Hash(byte[] bytes, int off, int len) {
        int h = 0;
        for (int i = 0; i < len; i++) {
            h = 31 * h + (bytes[off + i] & 0xFF);
        }
        return h;
    }

    static int positiveLongLength(long v) {
        long p = 10;
        for (int i = 1; i < 19; i++) {
            if (v < p) return i;
            p = 10 * p;
        }
        return 19;
    }

    public static int writeLongBytes(long v, byte[] buf, int endPos) {
        if (v == Long.MIN_VALUE) {
            byte[] m = MIN_LONG_STRING.rawBytesUnsafe();
            int pos = endPos - m.length;
            System.arraycopy(m, 0, buf, pos, m.length);
            return m.length;
        }
        boolean neg = v < 0;
        long n = neg ? -v : v;
        int digits = positiveLongLength(n);
        int len = neg ? digits + 1 : digits;
        int pos = endPos;
        while (n >= 100) {
            long q = n / 100;
            int r = (int) (n - q * 100);
            n = q;
            buf[--pos] = DIGIT_ONES[r];
            buf[--pos] = DIGIT_TENS[r];
        }
        if (n >= 10) {
            int r = (int) n;
            buf[--pos] = DIGIT_ONES[r];
            buf[--pos] = DIGIT_TENS[r];
        } else {
            buf[--pos] = (byte) ('0' + n);
        }
        if (neg) {
            buf[endPos - len] = '-';
        }
        return len;
    }

    public static int longStringLength(long v) {
        if (v == Long.MIN_VALUE) return MIN_LONG_STRING.rawLength;
        return v < 0 ? 1 + positiveLongLength(-v) : positiveLongLength(v);
    }

    /**
     * Renders an integer into a LuaString, hitting the [0..1023] table without
     * allocating and formatting larger integers directly into ASCII bytes.
     */
    public static LuaString fromLong(long v) {
        if (v >= 0 && v < INT_CACHE_SIZE) {
            return INT_STRING_CACHE[(int) v];
        }
        if (v == Long.MIN_VALUE) {
            return MIN_LONG_STRING;
        }
        int len = longStringLength(v);
        byte[] buf = new byte[len];
        writeLongBytes(v, buf, len);
        return new LuaString(buf, 0, len);
    }

    /**
     * Wraps a slice of an immutable byte array as a LuaString. Applies the
     * slice-retention guard so tiny views do not pin oversized backing arrays.
     */
    public static LuaString fromSlice(byte[] src, int off, int len) {
        if (len <= 0) return EMPTY;
        if (len == 1) return ASCII_CACHE[src[off] & 0xFF];
        if (src.length > SLICE_SHARE_SMALL_CAP && len * 4 < src.length) {
            byte[] copy = java.util.Arrays.copyOfRange(src, off, off + len);
            return new LuaString(copy, 0, len);
        }
        return new LuaString(src, off, len);
    }

    /** Wraps a freshly owned byte array prefix of length {@code len}. */
    public static LuaString fromOwnedRange(byte[] bytes, int len) {
        if (len <= 0) return EMPTY;
        if (len == 1) return ASCII_CACHE[bytes[0] & 0xFF];
        if (bytes.length > SLICE_SHARE_SMALL_CAP && len * 4 < bytes.length) {
            byte[] copy = java.util.Arrays.copyOf(bytes, len);
            return new LuaString(copy, 0, len);
        }
        return new LuaString(bytes, 0, len);
    }

    /** Wraps an exact-sized freshly allocated immutable byte array. */
    public static LuaString fromOwnedBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return EMPTY;
        if (bytes.length == 1) return ASCII_CACHE[bytes[0] & 0xFF];
        return new LuaString(bytes, 0, bytes.length);
    }

    /** Copies a slice out of a mutable buffer into a fresh LuaString. */
    public static LuaString copyOfRange(byte[] src, int off, int len) {
        if (len <= 0) return EMPTY;
        if (len == 1) return ASCII_CACHE[src[off] & 0xFF];
        byte[] copy = java.util.Arrays.copyOfRange(src, off, off + len);
        return new LuaString(copy, 0, len);
    }

    public static LuaString newString(String s) {
        return valueOf(s);
    }

    /**
     * Hot-path string creation: always fresh, never pooled. String equality
     * in Lua is by content ({@code ==}, table keys, pattern captures), so
     * eagerly interning every runtime string (tostring results, concat
     * pieces, captures) is pure overhead — 50k distinct strings pay 50k
     * pool misses with zero hits. Identity is observable only through
     * {@code string.format('%p')}, which canonicalizes via
     * {@link #intern(LuaString)} instead.
     */
    public static LuaString valueOf(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        if (s.length() == 1) {
            char c = s.charAt(0);
            if (c < 256) return ASCII_CACHE[c];
        }
        return new LuaString(s);
    }

    /**
     * Canonicalizing creation for cold paths (compiler string constants):
     * equal short strings share one object, as before.
     */
    public static LuaString interned(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        if (s.length() == 1) {
            char c = s.charAt(0);
            if (c < 256) return ASCII_CACHE[c];
        }
        if (s.length() > MAX_SHORT_STRING) {
            return new LuaString(s);
        }
        ProbeKey probe = PROBE.get();
        probe.str = s;
        probe.hash = s.hashCode();
        LuaString cached = INTERN_POOL.get(probe);
        if (cached != null) return cached;
        if (INTERN_POOL.size() >= MAX_INTERN_ENTRIES) {
            return new LuaString(s);
        }
        LuaString fresh = new LuaString(s);
        LuaString raced = INTERN_POOL.putIfAbsent(s, fresh);
        return raced != null ? raced : fresh;
    }

    /** Current number of entries in the intern pool (package-private for tests). */
    static int internPoolSize() {
        return INTERN_POOL.size();
    }

    /**
     * Returns a stable pointer identifier for {@code string.format('%p')}:
     * short strings ({@code <= 40} chars) get a deterministic 64-bit content
     * hash without populating the strong {@link #INTERN_POOL} (eliminating OOM
     * from {@code %p} loops), while long strings return their object identity
     * hash to preserve Lua 5.4 long-string non-reuse checks.
     */
    public static long pointerId(LuaString s) {
        int len = s.rawLength;
        if (len > MAX_SHORT_STRING) {
            return Integer.toUnsignedLong(System.identityHashCode(s));
        }
        long h = 0xcbf29ce484222325L;
        String v = s.value;
        if (v != null) {
            for (int i = 0; i < len; i++) {
                h ^= v.charAt(i);
                h *= 0x100000001b3L;
            }
        } else {
            byte[] b = s.rawBytes;
            int off = s.rawOffset;
            for (int i = 0; i < len; i++) {
                h ^= (b[off + i] & 0xFF);
                h *= 0x100000001b3L;
            }
        }
        h ^= h >>> 30;
        h *= 0xbf58476d1ce4e5b9L;
        h ^= h >>> 27;
        h *= 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return 0x10000000_00000000L | (h & 0x0FFFFFFF_FFFFFFFFL);
    }

    /**
     * Returns the canonical instance for {@code s} (itself when already
     * canonical, long, or empty).
     */
    public static LuaString intern(LuaString s) {
        if (s == null) return null;
        int len = s.rawLength;
        if (len == 0 || len > MAX_SHORT_STRING) {
            return s;
        }
        return interned(s.value());
    }

    @Override
    public LuaType type() {
        return LuaType.STRING;
    }

    @Override
    public boolean isString() {
        return true;
    }

    public int rawLength() {
        return rawLength;
    }

    public int rawOffset() {
        return rawBytes != null ? rawOffset : 0;
    }

    /**
     * Returns the backing byte array (which may be a shared slice when
     * {@link #rawOffset()} &gt; 0 or longer than {@link #rawLength()}).
     * Never mutate the returned array.
     */
    public byte[] rawBytesUnsafe() {
        byte[] b = rawBytes;
        if (b == null) {
            b = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            rawBytes = b;
        }
        return b;
    }

    public String value() {
        String v = value;
        if (v == null) {
            v = new String(rawBytes, rawOffset, rawLength, java.nio.charset.StandardCharsets.ISO_8859_1);
            value = v;
        }
        return v;
    }

    /**
     * Cached Latin-1 encoding of this string. Pattern matching, {@code find}
     * and {@code gsub} otherwise re-encode the same string on every call.
     */
    public byte[] latin1Bytes() {
        byte[] b = rawBytesUnsafe();
        if (rawOffset == 0 && b.length == rawLength) {
            return b;
        }
        return java.util.Arrays.copyOfRange(b, rawOffset, rawOffset + rawLength);
    }

    @Override
    public long toLong() {
        String s = value();
        LuaValue num = parseNumber(s);
        if (num != null) {
            LuaInteger i = num.toLuaInteger();
            if (i != null) return i.toLong();
        }
        throw new LuaException("attempt to convert string '" + s + "' to integer");
    }

    @Override
    public double toDouble() {
        String s = value();
        LuaValue num = parseNumber(s);
        if (num != null) {
            return num.toDouble();
        }
        throw new LuaException("attempt to convert string '" + s + "' to float");
    }

    @Override
    public LuaValue len() {
        return LuaInteger.valueOf(rawLength);
    }

    @Override
    public String toLuaString() {
        return value();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LuaString other) || this.rawLength != other.rawLength) {
            return false;
        }
        int h1 = this.strHash;
        int h2 = other.strHash;
        if (h1 != 0 && h2 != 0 && h1 != h2) {
            return false;
        }
        return equalsSameShape(other);
    }

    private boolean equalsSameShape(LuaString other) {
        String sv = this.value;
        String ov = other.value;
        if (sv != null && ov != null) {
            return sv.equals(ov);
        }
        byte[] sb = this.rawBytes;
        byte[] ob = other.rawBytes;
        if (sb != null && ob != null) {
            return java.util.Arrays.equals(
                    sb, this.rawOffset, this.rawOffset + this.rawLength,
                    ob, other.rawOffset, other.rawOffset + other.rawLength);
        }
        return equalsMixed(sv, sb, this.rawOffset, ov, ob, other.rawOffset, this.rawLength);
    }

    private static boolean equalsMixed(String sv, byte[] sb, int so,
            String ov, byte[] ob, int oo, int len) {
        if (sv != null) {
            for (int i = 0; i < len; i++) {
                if (sv.charAt(i) != (ob[oo + i] & 0xFF)) return false;
            }
            return true;
        }
        for (int i = 0; i < len; i++) {
            if ((sb[so + i] & 0xFF) != ov.charAt(i)) return false;
        }
        return true;
    }

    public int fastHash() {
        int h = strHash;
        if (h == 0 && rawLength > 0) {
            return computeFastHash();
        }
        return h;
    }

    private int computeFastHash() {
        String v = value;
        int h = (v != null) ? v.hashCode() : latin1Hash(rawBytes, rawOffset, rawLength);
        strHash = h;
        return h;
    }

    @Override
    public int hashCode() {
        return fastHash();
    }
}

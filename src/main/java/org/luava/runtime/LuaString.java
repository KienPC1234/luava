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
    private static final LuaString[] ASCII_CACHE = new LuaString[256];
    static {
        for (int i = 0; i < 256; i++) {
            ASCII_CACHE[i] = new LuaString(String.valueOf((char) i));
        }
    }
    private static final int MAX_SHORT_STRING = 40;
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

    private final String value;

    public static void setStringMetatable(LuaTable mt) {
        LuaValue.setBasicMetatable(LuaType.STRING, mt);
    }

    @Override
    public LuaTable getMetatable() {
        return LuaValue.getBasicMetatable(LuaType.STRING);
    }

    public LuaString(String value) {
        this.value = value != null ? value : "";
        if (this.value.length() >= 1024) {
            org.luava.runtime.eval.GCManager.onAllocLargeString(this);
        }
        org.luava.runtime.eval.GCManager.onAlloc(Math.max(16, this.value.length()));
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
        LuaString fresh = new LuaString(s);
        LuaString raced = INTERN_POOL.putIfAbsent(s, fresh);
        return raced != null ? raced : fresh;
    }

    /**
     * Returns the canonical instance for {@code s} (itself when already
     * canonical, long, or empty). The only identity-sensitive use of strings;
     * {@code string.format('%p')} renders the canonical's identity so equal
     * short strings report equal pointers, per the PUC suite.
     */
    public static LuaString intern(LuaString s) {
        if (s == null) return null;
        String v = s.value;
        if (v.isEmpty() || v.length() > MAX_SHORT_STRING) {
            return s;
        }
        return interned(v);
    }

    @Override
    public LuaType type() {
        return LuaType.STRING;
    }

    @Override
    public boolean isString() {
        return true;
    }

    public String value() {
        return value;
    }

    /**
     * Cached Latin-1 encoding of this string. Pattern matching, {@code find}
     * and {@code gsub} otherwise re-encode the same string on every call
     * (a hot loop calling {@code s:gmatch(p)} copies both operands per
     * iteration). The bytes are a pure function of the immutable value, so a
     * benign race computing them twice is harmless.
     */
    private byte[] latin1Cache;

    public byte[] latin1Bytes() {
        byte[] b = latin1Cache;
        if (b == null) {
            b = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            latin1Cache = b;
        }
        return b;
    }

    @Override
    public long toLong() {
        LuaValue num = parseNumber(value);
        if (num != null) {
            LuaInteger i = num.toLuaInteger();
            if (i != null) return i.toLong();
        }
        throw new LuaException("attempt to convert string '" + value + "' to integer");
    }

    @Override
    public double toDouble() {
        LuaValue num = parseNumber(value);
        if (num != null) {
            return num.toDouble();
        }
        throw new LuaException("attempt to convert string '" + value + "' to float");
    }

    @Override
    public LuaValue len() {
        return LuaInteger.valueOf(value.length());
    }

    @Override
    public String toLuaString() {
        return value;
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof LuaString other && this.value.equals(other.value));
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}

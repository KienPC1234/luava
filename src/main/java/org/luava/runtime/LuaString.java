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
    private static final java.util.concurrent.ConcurrentHashMap<String, LuaString> SHORT_STRING_CACHE = new java.util.concurrent.ConcurrentHashMap<>(1024);

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

    public static LuaString valueOf(String s) {
        if (s == null || s.isEmpty()) return EMPTY;
        if (s.length() == 1) {
            char c = s.charAt(0);
            if (c < 256) return ASCII_CACHE[c];
        }
        if (s.length() <= MAX_SHORT_STRING) {
            return SHORT_STRING_CACHE.computeIfAbsent(s, LuaString::new);
        }
        return new LuaString(s);
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

package org.luava.runtime;

import java.util.concurrent.ConcurrentHashMap;

public final class LuaString extends LuaValue {
    private static final ConcurrentHashMap<String, LuaString> STRING_POOL = new ConcurrentHashMap<>();

    private final String value;

    private LuaString(String value) {
        this.value = value != null ? value : "";
    }

    public static LuaString valueOf(String s) {
        if (s == null) return valueOf("");
        if (s.length() <= 64) {
            return STRING_POOL.computeIfAbsent(s, LuaString::new);
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
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new LuaException("attempt to convert string '" + value + "' to integer");
        }
    }

    @Override
    public double toDouble() {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new LuaException("attempt to convert string '" + value + "' to float");
        }
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

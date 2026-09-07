package org.luava.runtime;

public final class LuaBoolean extends LuaValue {
    public static final LuaBoolean TRUE = new LuaBoolean(true);
    public static final LuaBoolean FALSE = new LuaBoolean(false);

    private final boolean value;

    private LuaBoolean(boolean value) {
        this.value = value;
    }

    public static LuaBoolean valueOf(boolean b) {
        return b ? TRUE : FALSE;
    }

    @Override
    public LuaType type() {
        return LuaType.BOOLEAN;
    }

    @Override
    public boolean isBoolean() {
        return true;
    }

    @Override
    public boolean toBoolean() {
        return value;
    }

    @Override
    public String toLuaString() {
        return value ? "true" : "false";
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof LuaBoolean other && this.value == other.value);
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(value);
    }
}

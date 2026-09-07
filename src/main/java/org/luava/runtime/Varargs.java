package org.luava.runtime;

import java.util.Arrays;

public final class Varargs extends LuaValue {
    public static final Varargs EMPTY = new Varargs(new LuaValue[0]);

    private final LuaValue[] values;

    public Varargs(LuaValue[] values) {
        this.values = values != null ? values : new LuaValue[0];
    }

    public static Varargs of(LuaValue... vals) {
        if (vals == null || vals.length == 0) return EMPTY;
        return new Varargs(vals);
    }

    public int count() {
        return values.length;
    }

    public LuaValue arg(int index) {
        // 1-based index
        if (index >= 1 && index <= values.length) {
            return values[index - 1];
        }
        return LuaNil.NIL;
    }

    public LuaValue first() {
        return arg(1);
    }

    public LuaValue[] toArray() {
        return Arrays.copyOf(values, values.length);
    }

    @Override
    public LuaType type() {
        return first().type();
    }

    @Override
    public boolean toBoolean() {
        return first().toBoolean();
    }

    @Override
    public long toLong() {
        return first().toLong();
    }

    @Override
    public double toDouble() {
        return first().toDouble();
    }

    @Override
    public String toLuaString() {
        return first().toLuaString();
    }
}

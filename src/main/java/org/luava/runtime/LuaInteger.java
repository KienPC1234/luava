package org.luava.runtime;

public final class LuaInteger extends LuaValue {
    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 2048;
    private static final LuaInteger[] CACHE = new LuaInteger[CACHE_HIGH - CACHE_LOW + 1];

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new LuaInteger(i + CACHE_LOW);
        }
    }

    private final long value;

    private LuaInteger(long value) {
        this.value = value;
    }

    public static LuaInteger valueOf(long val) {
        if (val >= CACHE_LOW && val <= CACHE_HIGH) {
            return CACHE[(int) (val - CACHE_LOW)];
        }
        return new LuaInteger(val);
    }

    @Override
    public LuaType type() {
        return LuaType.NUMBER;
    }

    @Override
    public boolean isInteger() {
        return true;
    }

    @Override
    public long toLong() {
        return value;
    }

    @Override
    public double toDouble() {
        return (double) value;
    }

    @Override
    public String toLuaString() {
        return Long.toString(value);
    }

    @Override
    public LuaValue add(LuaValue other) {
        if (other instanceof LuaInteger i) {
            return LuaInteger.valueOf(this.value + i.value);
        }
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf((double) this.value + f.toDouble());
        }
        return super.add(other);
    }

    @Override
    public LuaValue sub(LuaValue other) {
        if (other instanceof LuaInteger i) {
            return LuaInteger.valueOf(this.value - i.value);
        }
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf((double) this.value - f.toDouble());
        }
        return super.sub(other);
    }

    @Override
    public LuaValue mul(LuaValue other) {
        if (other instanceof LuaInteger i) {
            return LuaInteger.valueOf(this.value * i.value);
        }
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf((double) this.value * f.toDouble());
        }
        return super.mul(other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof LuaInteger other) {
            return this.value == other.value;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }
}

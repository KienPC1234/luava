package org.luava.runtime;

public final class LuaFloat extends LuaValue {
    private final double value;

    private LuaFloat(double value) {
        this.value = value;
    }

    public static LuaFloat valueOf(double val) {
        return new LuaFloat(val);
    }

    @Override
    public LuaType type() {
        return LuaType.NUMBER;
    }

    @Override
    public boolean isFloat() {
        return true;
    }

    @Override
    public long toLong() {
        if (value >= -9223372036854775808.0 && value < 9223372036854775808.0 &&
            Math.floor(value) == value && !Double.isInfinite(value) && !Double.isNaN(value)) {
            return (long) value;
        }
        throw new LuaException("number has no integer representation");
    }

    @Override
    public double toDouble() {
        return value;
    }

    @Override
    public String toLuaString() {
        if (Double.isNaN(value)) return "nan";
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        if (value == Math.floor(value)) {
            return String.format("%.1f", value);
        }
        return Double.toString(value);
    }

    @Override
    public LuaValue add(LuaValue other) {
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf(this.value + f.value);
        }
        if (other instanceof LuaInteger i) {
            return LuaFloat.valueOf(this.value + (double) i.toLong());
        }
        return super.add(other);
    }

    @Override
    public LuaValue sub(LuaValue other) {
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf(this.value - f.value);
        }
        if (other instanceof LuaInteger i) {
            return LuaFloat.valueOf(this.value - (double) i.toLong());
        }
        return super.sub(other);
    }

    @Override
    public LuaValue mul(LuaValue other) {
        if (other instanceof LuaFloat f) {
            return LuaFloat.valueOf(this.value * f.value);
        }
        if (other instanceof LuaInteger i) {
            return LuaFloat.valueOf(this.value * (double) i.toLong());
        }
        return super.mul(other);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof LuaFloat other) {
            return Double.compare(this.value, other.value) == 0;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }
}

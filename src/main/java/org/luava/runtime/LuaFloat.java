/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

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
        return formatFloat(value);
    }

    // Lua lua_Number2str: C "%.14g" plus a ".0" suffix when the result looks
    // like an integer. Java's %.14g neither strips zeros nor rounds ties to
    // even, so round the exact value with BigDecimal (HALF_EVEN) and render
    // manually with C %g style rules.
    static String formatFloat(double v) {
        if (Double.isNaN(v)) {
            return (Double.doubleToRawLongBits(v) < 0) ? "-nan" : "nan";
        }
        if (Double.isInfinite(v)) return v > 0 ? "inf" : "-inf";
        boolean neg = (Double.doubleToRawLongBits(v) < 0);
        BigDecimal bd = new BigDecimal(neg ? -v : v).round(new MathContext(14, RoundingMode.HALF_EVEN));
        String digits = bd.unscaledValue().abs().toString();
        int e10 = digits.length() - bd.scale() - 1; // decimal exponent of first digit
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') end--;
        digits = digits.substring(0, end);
        String s;
        if (e10 < -4 || e10 >= 14) {
            StringBuilder sb = new StringBuilder();
            if (neg) sb.append('-');
            sb.append(digits.charAt(0));
            if (digits.length() > 1) sb.append('.').append(digits.substring(1));
            sb.append('e');
            if (e10 < 0) sb.append('-'); else sb.append('+');
            String exp = Integer.toString(Math.abs(e10));
            if (exp.length() < 2) sb.append('0');
            sb.append(exp);
            s = sb.toString();
        } else if (e10 >= 0) {
            StringBuilder sb = new StringBuilder();
            if (neg) sb.append('-');
            if (digits.length() > e10 + 1) {
                sb.append(digits, 0, e10 + 1).append('.').append(digits.substring(e10 + 1));
            } else {
                sb.append(digits);
                for (int i = digits.length(); i <= e10; i++) sb.append('0');
            }
            s = sb.toString();
        } else {
            StringBuilder sb = new StringBuilder();
            if (neg) sb.append('-');
            sb.append("0.");
            for (int i = 0; i < -(e10 + 1); i++) sb.append('0');
            sb.append(digits);
            s = sb.toString();
        }
        if (s.matches("-?[0-9]+")) s += ".0";
        return s;
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

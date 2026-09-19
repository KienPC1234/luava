/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFloat;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;
import org.luava.runtime.Varargs;

public final class MathLib {
    private MathLib() {}

    /**
     * Shared stateless {@code math.sqrt} builtin (see
     * {@code BaseLib.TOSTRING}): one JVM-wide instance so the VM can
     * recognize and inline it on hot paths.
     */
    public static final org.luava.runtime.LuaFunction SQRT =
            org.luava.runtime.LuaFunction.of(args -> org.luava.runtime.LuaFloat.valueOf(Math.sqrt(checkNumber(args, 0, "sqrt"))));

    public static void open(LuaTable globals) {
        LuaTable math = new LuaTable();
        fillInto(math, globals);
        globals.rawset(LuaString.interned("math"), math);
    }

    public static void fillInto(LuaTable math, LuaTable globals) {

        math.rawset(LuaString.interned("pi"), LuaFloat.valueOf(Math.PI));
        math.rawset(LuaString.interned("huge"), LuaFloat.valueOf(Double.POSITIVE_INFINITY));
        math.rawset(LuaString.interned("maxinteger"), LuaInteger.valueOf(Long.MAX_VALUE));
        math.rawset(LuaString.interned("mininteger"), LuaInteger.valueOf(Long.MIN_VALUE));

        math.rawset(LuaString.interned("abs"), LuaFunction.of(args -> {
            LuaValue v = checkNumberValue(args, 0, "abs");
            if (v.isInteger()) return LuaInteger.valueOf(Math.abs(v.toLong()));
            return LuaFloat.valueOf(Math.abs(v.toDouble()));
        }));

        math.rawset(LuaString.interned("floor"), LuaFunction.of(args -> {
            LuaValue v = checkNumberValue(args, 0, "floor");
            if (v.isInteger()) return v;
            double d = Math.floor(v.toDouble());
            if (d >= -9223372036854775808.0 && d < 9223372036854775808.0) {
                return LuaInteger.valueOf((long) d);
            }
            return LuaFloat.valueOf(d);
        }));

        math.rawset(LuaString.interned("ceil"), LuaFunction.of(args -> {
            LuaValue v = checkNumberValue(args, 0, "ceil");
            if (v.isInteger()) return v;
            double d = Math.ceil(v.toDouble());
            if (d >= -9223372036854775808.0 && d < 9223372036854775808.0) {
                return LuaInteger.valueOf((long) d);
            }
            return LuaFloat.valueOf(d);
        }));

        math.rawset(LuaString.interned("sqrt"), SQRT);

        math.rawset(LuaString.interned("exp"), LuaFunction.of(args -> LuaFloat.valueOf(Math.exp(checkNumber(args, 0, "exp")))));

        math.rawset(LuaString.interned("log"), LuaFunction.of(args -> {
            double val = checkNumber(args, 0, "log");
            if (args.length > 1 && !args[1].isNil()) {
                double base = checkNumber(args, 1, "log");
                return LuaFloat.valueOf(Math.log(val) / Math.log(base));
            }
            return LuaFloat.valueOf(Math.log(val));
        }));

        math.rawset(LuaString.interned("sin"), LuaFunction.of(args -> LuaFloat.valueOf(Math.sin(checkNumber(args, 0, "sin")))));
        math.rawset(LuaString.interned("cos"), LuaFunction.of(args -> LuaFloat.valueOf(Math.cos(checkNumber(args, 0, "cos")))));
        math.rawset(LuaString.interned("tan"), LuaFunction.of(args -> LuaFloat.valueOf(Math.tan(checkNumber(args, 0, "tan")))));
        math.rawset(LuaString.interned("asin"), LuaFunction.of(args -> LuaFloat.valueOf(Math.asin(checkNumber(args, 0, "asin")))));
        math.rawset(LuaString.interned("acos"), LuaFunction.of(args -> LuaFloat.valueOf(Math.acos(checkNumber(args, 0, "acos")))));
        math.rawset(LuaString.interned("atan"), LuaFunction.of(args -> {
            double y = checkNumber(args, 0, "atan");
            double x = (args.length > 1 && !args[1].isNil()) ? checkNumber(args, 1, "atan") : 1.0;
            return LuaFloat.valueOf(Math.atan2(y, x));
        }));

        math.rawset(LuaString.interned("deg"), LuaFunction.of(args -> LuaFloat.valueOf(Math.toDegrees(checkNumber(args, 0, "deg")))));
        math.rawset(LuaString.interned("rad"), LuaFunction.of(args -> LuaFloat.valueOf(Math.toRadians(checkNumber(args, 0, "rad")))));

        math.rawset(LuaString.interned("max"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'max' (value expected)");
            LuaValue max = args[0];
            for (int i = 1; i < args.length; i++) {
                if (max.luaLessThan(args[i])) {
                    max = args[i];
                }
            }
            return max;
        }));

        math.rawset(LuaString.interned("min"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument #1 to 'min' (value expected)");
            LuaValue min = args[0];
            for (int i = 1; i < args.length; i++) {
                if (args[i].luaLessThan(min)) {
                    min = args[i];
                }
            }
            return min;
        }));

        math.rawset(LuaString.interned("type"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaValue v = args[0];
            if (v.isInteger()) return LuaString.interned("integer");
            if (v.isFloat()) return LuaString.interned("float");
            return LuaNil.NIL;
        }));

        math.rawset(LuaString.interned("tointeger"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaValue v = args[0];
            if (v.isInteger()) return v;
            if (v.isFloat()) {
                LuaInteger i = v.toLuaInteger();
                return i != null ? i : LuaNil.NIL;
            }
            if (v.isString()) {
                LuaValue parsed = LuaValue.parseNumber(v.toLuaString());
                if (parsed != null) {
                    LuaInteger i = parsed.toLuaInteger();
                    return i != null ? i : LuaNil.NIL;
                }
            }
            return LuaNil.NIL;
        }));

        math.rawset(LuaString.interned("ult"), LuaFunction.of(args -> {
            long m = checkInteger(args, 0, "ult");
            long n = checkInteger(args, 1, "ult");
            return LuaBoolean.valueOf(Long.compareUnsigned(m, n) < 0);
        }));

        math.rawset(LuaString.interned("fmod"), LuaFunction.of(args -> {
            LuaValue xv = checkNumberValue(args, 0, "fmod");
            LuaValue yv = checkNumberValue(args, 1, "fmod");
            if (xv.isInteger() && yv.isInteger()) {
                long y = yv.toLong();
                if (y == 0) {
                    throw new LuaException("bad argument #2 to 'fmod' (zero)");
                }
                return LuaInteger.valueOf(xv.toLong() % y);
            }
            double x = xv.toDouble();
            double y = yv.toDouble();
            return LuaFloat.valueOf(x % y);
        }));

        math.rawset(LuaString.interned("modf"), LuaFunction.of(args -> {
            LuaValue v = checkNumberValue(args, 0, "modf");
            if (v.isInteger()) {
                return Varargs.of(v, LuaFloat.valueOf(0.0));
            }
            double d = v.toDouble();
            if (Double.isInfinite(d)) {
                return Varargs.of(LuaFloat.valueOf(d), LuaFloat.valueOf(0.0));
            }
            if (Double.isNaN(d)) {
                return Varargs.of(LuaFloat.valueOf(d), LuaFloat.valueOf(d));
            }
            double intPart = (d >= 0) ? Math.floor(d) : Math.ceil(d);
            // Lua's pushnumint: the integer part is pushed as a Lua integer
            // when it fits, otherwise as a float (e.g. huge magnitudes).
            LuaValue ip;
            if (intPart >= -9223372036854775808.0 && intPart < 9223372036854775808.0) {
                ip = LuaInteger.valueOf((long) intPart);
            } else {
                ip = LuaFloat.valueOf(intPart);
            }
            double fracPart = d - intPart;
            return Varargs.of(ip, LuaFloat.valueOf(fracPart));
        }));

        long[] rngState = new long[4];
        setseed(rngState, System.currentTimeMillis(), System.identityHashCode(globals));

        math.rawset(LuaString.interned("random"), LuaFunction.of(args -> {
            long rv = nextrand(rngState);
            if (args.length == 0) {
                return LuaFloat.valueOf(I2d(rv));
            }
            long low, up;
            if (args.length == 1) {
                low = 1;
                up = checkInteger(args, 0, "random");
                if (up == 0) {
                    return LuaInteger.valueOf(rv);
                }
            } else if (args.length == 2) {
                low = checkInteger(args, 0, "random");
                up = checkInteger(args, 1, "random");
            } else {
                throw new LuaException("wrong number of arguments");
            }
            if (low > up) {
                throw new LuaException("bad argument #1 to 'random' (interval is empty)");
            }
            long p = project(rv, up - low, rngState);
            return LuaInteger.valueOf(p + low);
        }));

        math.rawset(LuaString.interned("randomseed"), LuaFunction.of(args -> {
            long s1, s2;
            if (args.length == 0 || args[0].isNil()) {
                s1 = System.currentTimeMillis();
                s2 = System.identityHashCode(globals);
            } else {
                s1 = checkInteger(args, 0, "randomseed");
                s2 = (args.length > 1 && !args[1].isNil()) ? checkInteger(args, 1, "randomseed") : 0;
            }
            setseed(rngState, s1, s2);
            return Varargs.of(LuaInteger.valueOf(s1), LuaInteger.valueOf(s2));
        }));
    }

    private static double checkNumber(LuaValue[] args, int index, String funcName) {
        if (args.length <= index || args[index].isNil()) {
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + (args.length <= index ? "no value" : "nil") + ")");
        }
        LuaValue v = args[index];
        LuaValue num = v.toLuaNumber();
        if (num == null) {
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + v.typeName() + ")");
        }
        return num.toDouble();
    }

    private static LuaValue checkNumberValue(LuaValue[] args, int index, String funcName) {
        if (args.length <= index || args[index].isNil()) {
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + (args.length <= index ? "no value" : "nil") + ")");
        }
        LuaValue v = args[index];
        if (v.isInteger() || v.isFloat()) {
            return v;
        }
        // luaL_checknumber parses a numeric string into a lua_Number (float).
        // PUC's math_abs/floor/ceil first test lua_isinteger on the raw
        // argument, which is false for a string, so they take the float path
        // (e.g. math.abs("120") == 120.0, a float).
        LuaValue num = v.toLuaNumber();
        if (num == null) {
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + v.typeName() + ")");
        }
        return LuaFloat.valueOf(num.toDouble());
    }

    private static long checkInteger(LuaValue[] args, int index, String funcName) {
        if (args.length <= index || args[index].isNil()) {
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + (args.length <= index ? "no value" : "nil") + ")");
        }
        LuaValue v = args[index];
        LuaInteger integer = v.toLuaInteger();
        if (integer == null) {
            if (v.toLuaNumber() != null) {
                throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number has no integer representation)");
            }
            throw new LuaException("bad argument #" + (index + 1) + " to '" + funcName + "' (number expected, got " + v.typeName() + ")");
        }
        return integer.toLong();
    }

    private static double I2d(long x) {
        long sx = x >>> 11;
        return sx * (1.0 / 9007199254740992.0); // 2^-53
    }

    private static long nextrand(long[] state) {
        long state0 = state[0];
        long state1 = state[1];
        long state2 = state[2] ^ state0;
        long state3 = state[3] ^ state1;
        long res = Long.rotateLeft(state1 * 5, 7) * 9;
        state[0] = state0 ^ state3;
        state[1] = state1 ^ state2;
        state[2] = state2 ^ (state1 << 17);
        state[3] = Long.rotateLeft(state3, 45);
        return res;
    }

    private static void setseed(long[] state, long n1, long n2) {
        state[0] = n1;
        state[1] = 0xffL;
        state[2] = n2;
        state[3] = 0L;
        for (int i = 0; i < 16; i++) {
            nextrand(state);
        }
    }

    private static long project(long ran, long n, long[] state) {
        if ((n & (n + 1)) == 0) {
            return ran & n;
        } else {
            long lim = n;
            lim |= (lim >>> 1);
            lim |= (lim >>> 2);
            lim |= (lim >>> 4);
            lim |= (lim >>> 8);
            lim |= (lim >>> 16);
            lim |= (lim >>> 32);
            while (Long.compareUnsigned(ran &= lim, n) > 0) {
                ran = nextrand(state);
            }
            return ran;
        }
    }
}

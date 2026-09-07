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

    public static void open(LuaTable globals) {
        LuaTable math = new LuaTable();

        math.rawset(LuaString.valueOf("pi"), LuaFloat.valueOf(Math.PI));
        math.rawset(LuaString.valueOf("huge"), LuaFloat.valueOf(Double.POSITIVE_INFINITY));
        math.rawset(LuaString.valueOf("maxinteger"), LuaInteger.valueOf(Long.MAX_VALUE));
        math.rawset(LuaString.valueOf("mininteger"), LuaInteger.valueOf(Long.MIN_VALUE));

        math.rawset(LuaString.valueOf("abs"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.abs'");
            LuaValue v = args[0];
            if (v.isInteger()) return LuaInteger.valueOf(Math.abs(v.toLong()));
            return LuaFloat.valueOf(Math.abs(v.toDouble()));
        }));

        math.rawset(LuaString.valueOf("floor"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.floor'");
            LuaValue v = args[0];
            if (v.isInteger()) return v;
            return LuaInteger.valueOf((long) Math.floor(v.toDouble()));
        }));

        math.rawset(LuaString.valueOf("ceil"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.ceil'");
            LuaValue v = args[0];
            if (v.isInteger()) return v;
            return LuaInteger.valueOf((long) Math.ceil(v.toDouble()));
        }));

        math.rawset(LuaString.valueOf("sqrt"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.sqrt'");
            return LuaFloat.valueOf(Math.sqrt(args[0].toDouble()));
        }));

        math.rawset(LuaString.valueOf("exp"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.exp'");
            return LuaFloat.valueOf(Math.exp(args[0].toDouble()));
        }));

        math.rawset(LuaString.valueOf("log"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.log'");
            double val = args[0].toDouble();
            if (args.length > 1 && !args[1].isNil()) {
                double base = args[1].toDouble();
                return LuaFloat.valueOf(Math.log(val) / Math.log(base));
            }
            return LuaFloat.valueOf(Math.log(val));
        }));

        math.rawset(LuaString.valueOf("sin"), LuaFunction.of(args -> LuaFloat.valueOf(Math.sin(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("cos"), LuaFunction.of(args -> LuaFloat.valueOf(Math.cos(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("tan"), LuaFunction.of(args -> LuaFloat.valueOf(Math.tan(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("asin"), LuaFunction.of(args -> LuaFloat.valueOf(Math.asin(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("acos"), LuaFunction.of(args -> LuaFloat.valueOf(Math.acos(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("atan"), LuaFunction.of(args -> {
            double y = args[0].toDouble();
            double x = (args.length > 1 && !args[1].isNil()) ? args[1].toDouble() : 1.0;
            return LuaFloat.valueOf(Math.atan2(y, x));
        }));

        math.rawset(LuaString.valueOf("deg"), LuaFunction.of(args -> LuaFloat.valueOf(Math.toDegrees(args[0].toDouble()))));
        math.rawset(LuaString.valueOf("rad"), LuaFunction.of(args -> LuaFloat.valueOf(Math.toRadians(args[0].toDouble()))));

        math.rawset(LuaString.valueOf("max"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.max'");
            LuaValue max = args[0];
            for (int i = 1; i < args.length; i++) {
                if (max.luaLessThan(args[i])) {
                    max = args[i];
                }
            }
            return max;
        }));

        math.rawset(LuaString.valueOf("min"), LuaFunction.of(args -> {
            if (args.length == 0) throw new LuaException("bad argument to 'math.min'");
            LuaValue min = args[0];
            for (int i = 1; i < args.length; i++) {
                if (args[i].luaLessThan(min)) {
                    min = args[i];
                }
            }
            return min;
        }));

        math.rawset(LuaString.valueOf("type"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaValue v = args[0];
            if (v.isInteger()) return LuaString.valueOf("integer");
            if (v.isFloat()) return LuaString.valueOf("float");
            return LuaNil.NIL;
        }));

        math.rawset(LuaString.valueOf("tointeger"), LuaFunction.of(args -> {
            if (args.length == 0) return LuaNil.NIL;
            LuaValue v = args[0];
            if (v.isInteger()) return v;
            if (v.isFloat()) {
                double d = v.toDouble();
                if (d == Math.floor(d) && !Double.isInfinite(d) && !Double.isNaN(d)) {
                    return LuaInteger.valueOf((long) d);
                }
            }
            return LuaNil.NIL;
        }));

        math.rawset(LuaString.valueOf("ult"), LuaFunction.of(args -> {
            if (args.length < 2) throw new LuaException("bad argument to 'math.ult'");
            long m = args[0].toLong();
            long n = args[1].toLong();
            return LuaBoolean.valueOf(Long.compareUnsigned(m, n) < 0);
        }));

        globals.rawset(LuaString.valueOf("math"), math);
    }
}

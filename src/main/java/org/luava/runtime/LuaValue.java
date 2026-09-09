package org.luava.runtime;

public abstract class LuaValue {
    public static final LuaValue[] EMPTY_ARRAY = new LuaValue[0];

    public static LuaBoolean valueOf(boolean b) {
        return LuaBoolean.valueOf(b);
    }

    public static LuaInteger valueOf(long l) {
        return LuaInteger.valueOf(l);
    }

    public static LuaFloat valueOf(double d) {
        return LuaFloat.valueOf(d);
    }

    public static LuaString valueOf(String s) {
        return LuaString.valueOf(s);
    }

    public abstract LuaType type();

    public String typeName() {
        if (this.isTable() || this.isUserdata()) {
            LuaTable mt = getMetatable();
            if (mt != null) {
                LuaValue nameVal = mt.rawget(LuaString.valueOf("__name"));
                if (nameVal != null && nameVal.isString()) {
                    return nameVal.toLuaString();
                }
            }
        }
        return type().typeName();
    }

    public boolean isNil() {
        return false;
    }

    public boolean isBoolean() {
        return false;
    }

    public boolean isInteger() {
        return false;
    }

    public boolean isFloat() {
        return false;
    }

    public boolean isNumber() {
        return isInteger() || isFloat();
    }

    public boolean isString() {
        return false;
    }

    public boolean isTable() {
        return false;
    }

    public boolean isFunction() {
        return false;
    }

    public boolean isUserdata() {
        return false;
    }

    public boolean isLightUserdata() {
        return false;
    }

    public boolean isThread() {
        return false;
    }

    public boolean toBoolean() {
        return true; // only nil and false evaluate to false in Lua
    }

    public long toLong() {
        throw new LuaException("attempt to convert " + typeName() + " to integer");
    }

    public double toDouble() {
        throw new LuaException("attempt to convert " + typeName() + " to float");
    }

    public abstract String toLuaString();

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj instanceof LuaTable.WeakKey wk) {
            return this.equals(wk.get());
        }
        return false;
    }

    private static final LuaTable[] BASIC_METATABLES = new LuaTable[LuaType.values().length];

    public static LuaTable getBasicMetatable(LuaType type) {
        return BASIC_METATABLES[type.ordinal()];
    }

    public static void setBasicMetatable(LuaType type, LuaTable mt) {
        BASIC_METATABLES[type.ordinal()] = mt;
    }

    public static void resetBasicMetatables() {
        for (int i = 0; i < BASIC_METATABLES.length; i++) {
            if (i != LuaType.STRING.ordinal()) {
                BASIC_METATABLES[i] = null;
            }
        }
    }

    public LuaTable getMetatable() {
        return BASIC_METATABLES[type().ordinal()];
    }

    public void setMetatable(LuaTable metatable) {
        setBasicMetatable(type(), metatable);
    }

    public static final int MAXTAGLOOP = 2000;

    public LuaValue get(LuaValue key) {
        LuaValue t = this;
        for (int loop = 0; loop < MAXTAGLOOP; loop++) {
            LuaTable mt;
            if (t instanceof LuaTable tbl) {
                LuaValue val = tbl.rawget(key);
                if (!val.isNil()) {
                    return val;
                }
                mt = tbl.getMetatable();
            } else {
                mt = t.getMetatable();
            }
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__index"));
                if (!handler.isNil()) {
                    if (handler.isFunction()) {
                        org.luava.runtime.eval.CallStack.setNextCall("index", true);
                        return handler.call(t, key);
                    }
                    t = handler;
                    continue;
                }
            }
            if (t.isTable()) return LuaNil.NIL;
            throw new LuaException("attempt to index a " + t.typeName() + " value");
        }
        throw new LuaException("'__index' chain too long; possible loop");
    }

    public void set(LuaValue key, LuaValue value) {
        LuaValue t = this;
        for (int loop = 0; loop < MAXTAGLOOP; loop++) {
            LuaTable mt;
            if (t instanceof LuaTable tbl) {
                LuaValue existing = tbl.rawget(key);
                if (!existing.isNil() || tbl.getMetatable() == null) {
                    tbl.rawset(key, value);
                    return;
                }
                mt = tbl.getMetatable();
            } else {
                mt = t.getMetatable();
            }
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__newindex"));
                if (!handler.isNil()) {
                    if (handler.isFunction()) {
                        org.luava.runtime.eval.CallStack.setNextCall("newindex", true);
                        handler.call(t, key, value);
                        return;
                    }
                    t = handler;
                    continue;
                }
            }
            if (t instanceof LuaTable tbl) {
                tbl.rawset(key, value);
                return;
            }
            throw new LuaException("attempt to index a " + t.typeName() + " value");
        }
        throw new LuaException("'__newindex' chain too long; possible loop");
    }

    public LuaValue call(LuaValue... args) {
        LuaValue target = this;
        LuaValue[] currentArgs = args;
        while (!(target instanceof LuaFunction)) {
            LuaTable mt = target.getMetatable();
            if (mt != null) {
                LuaValue handler = mt.rawget(LuaString.valueOf("__call"));
                if (!handler.isNil()) {
                    LuaValue[] callArgs = new LuaValue[currentArgs.length + 1];
                    callArgs[0] = target;
                    System.arraycopy(currentArgs, 0, callArgs, 1, currentArgs.length);
                    target = handler;
                    currentArgs = callArgs;
                    continue;
                }
            }
            org.luava.runtime.eval.CallStack.CallStackState cs = org.luava.runtime.eval.CallStack.currentState();
            String desc = "";
            if (cs.nextMetamethod && cs.nextName != null) {
                desc = " (metamethod '" + cs.nextName + "')";
            }
            throw new LuaException("attempt to call a " + target.typeName() + " value" + desc);
        }
        return target.call(currentArgs);
    }

    public LuaValue len() {
        if (this.isString()) {
            return LuaInteger.valueOf(this.toLuaString().length());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__len"));
            if (!handler.isNil()) {
                org.luava.runtime.eval.CallStack.setNextCall("len", true);
                return handler.call(this, this);
            }
        }
        if (this.isTable()) {
            return LuaInteger.valueOf(((LuaTable) this).rawlen());
        }
        throw new LuaException("attempt to get length of a " + typeName() + " value");
    }

    public LuaValue add(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() + other.toLong());
        }
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() + other.toDouble());
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            if (a.isInteger() && b.isInteger()) {
                return LuaInteger.valueOf(a.toLong() + b.toLong());
            }
            return LuaFloat.valueOf(a.toDouble() + b.toDouble());
        }
        return dispatchBinaryMetamethod(this, other, "__add", "perform arithmetic on");
    }

    public LuaValue sub(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() - other.toLong());
        }
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() - other.toDouble());
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            if (a.isInteger() && b.isInteger()) {
                return LuaInteger.valueOf(a.toLong() - b.toLong());
            }
            return LuaFloat.valueOf(a.toDouble() - b.toDouble());
        }
        return dispatchBinaryMetamethod(this, other, "__sub", "perform arithmetic on");
    }

    public LuaValue mul(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() * other.toLong());
        }
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() * other.toDouble());
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            if (a.isInteger() && b.isInteger()) {
                return LuaInteger.valueOf(a.toLong() * b.toLong());
            }
            return LuaFloat.valueOf(a.toDouble() * b.toDouble());
        }
        return dispatchBinaryMetamethod(this, other, "__mul", "perform arithmetic on");
    }

    public LuaValue div(LuaValue other) {
        // Lua 5.4: float division always returns float
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() / other.toDouble());
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            return LuaFloat.valueOf(a.toDouble() / b.toDouble());
        }
        return dispatchBinaryMetamethod(this, other, "__div", "perform arithmetic on");
    }

    public LuaValue idiv(LuaValue other) {
        // Lua 5.4: floor division //
        if (this.isInteger() && other.isInteger()) {
            long b = other.toLong();
            if (b == 0) {
                throw new LuaException("attempt to divide by zero");
            }
            return LuaInteger.valueOf(Math.floorDiv(this.toLong(), b));
        }
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(Math.floor(this.toDouble() / other.toDouble()));
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            if (a.isInteger() && b.isInteger()) {
                long bVal = b.toLong();
                if (bVal == 0) {
                    throw new LuaException("attempt to divide by zero");
                }
                return LuaInteger.valueOf(Math.floorDiv(a.toLong(), bVal));
            }
            return LuaFloat.valueOf(Math.floor(a.toDouble() / b.toDouble()));
        }
        return dispatchBinaryMetamethod(this, other, "__idiv", "perform arithmetic on");
    }

    public LuaValue mod(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            long b = other.toLong();
            if (b == 0) {
                throw new LuaException("attempt to perform 'n%0'");
            }
            return LuaInteger.valueOf(Math.floorMod(this.toLong(), b));
        }
        if (this.isNumber() && other.isNumber()) {
            double a = this.toDouble();
            double b = other.toDouble();
            return LuaFloat.valueOf(luaFloatMod(a, b));
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            if (a.isInteger() && b.isInteger()) {
                long bVal = b.toLong();
                if (bVal == 0) {
                    throw new LuaException("attempt to perform 'n%0'");
                }
                return LuaInteger.valueOf(Math.floorMod(a.toLong(), bVal));
            }
            double aD = a.toDouble();
            double bD = b.toDouble();
            return LuaFloat.valueOf(luaFloatMod(aD, bD));
        }
        return dispatchBinaryMetamethod(this, other, "__mod", "perform arithmetic on");
    }

    private static double luaFloatMod(double a, double b) {
        double m = a % b;
        if (m > 0 ? b < 0 : (m < 0 && b > 0)) {
            m += b;
        }
        return m;
    }

    public LuaValue pow(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(Math.pow(this.toDouble(), other.toDouble()));
        }
        LuaValue a = this.isNumber() ? this : this.toLuaNumber();
        LuaValue b = other.isNumber() ? other : other.toLuaNumber();
        if (a != null && b != null) {
            return LuaFloat.valueOf(Math.pow(a.toDouble(), b.toDouble()));
        }
        return dispatchBinaryMetamethod(this, other, "__pow", "perform arithmetic on");
    }

    public LuaValue unm() {
        if (this.isInteger()) {
            return LuaInteger.valueOf(-this.toLong());
        }
        if (this.isFloat()) {
            return LuaFloat.valueOf(-this.toDouble());
        }
        LuaValue a = this.toLuaNumber();
        if (a != null) {
            if (a.isInteger()) return LuaInteger.valueOf(-a.toLong());
            return LuaFloat.valueOf(-a.toDouble());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__unm"));
            if (!handler.isNil()) {
                org.luava.runtime.eval.CallStack.setNextCall("unm", true);
                return handler.call(this, this);
            }
        }
        throw new LuaException("attempt to perform arithmetic on a " + typeName() + " value");
    }

    public LuaValue toLuaNumber() {
        if (this.isInteger() || this.isFloat()) return this;
        if (this.isString()) {
            return parseNumber(this.toLuaString());
        }
        return null;
    }

    public static boolean isLuaWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == 0x0B;
    }

    public static String trimLuaWhitespace(String s) {
        if (s == null) return null;
        int start = 0;
        int end = s.length();
        while (start < end && isLuaWhitespace(s.charAt(start))) {
            start++;
        }
        while (end > start && isLuaWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    public static LuaValue parseNumber(String raw) {
        if (raw == null) return null;
        String s = trimLuaWhitespace(raw);
        if (s.isEmpty()) return null;

        boolean negative = false;
        int idx = 0;
        char first = s.charAt(0);
        if (first == '-') {
            negative = true;
            idx++;
        } else if (first == '+') {
            idx++;
        }
        if (idx >= s.length()) return null;

        String rest = s.substring(idx);
        if (rest.startsWith("0x") || rest.startsWith("0X")) {
            String hexBody = rest.substring(2);
            if (hexBody.isEmpty()) return null;
            boolean hasDot = false;
            boolean hasP = false;
            for (int i = 0; i < hexBody.length(); i++) {
                char c = hexBody.charAt(i);
                if (c == '.') {
                    if (hasDot || hasP) return null;
                    hasDot = true;
                } else if (c == 'p' || c == 'P') {
                    if (hasP) return null;
                    hasP = true;
                    if (i + 1 < hexBody.length() && (hexBody.charAt(i + 1) == '+' || hexBody.charAt(i + 1) == '-')) {
                        i++;
                    }
                } else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                    return null;
                }
            }

            if (hasDot || hasP) {
                String hexFloat = (negative ? "-" : "") + rest;
                if (!hasP) {
                    hexFloat = hexFloat + "p0";
                }
                try {
                    double d = Double.parseDouble(hexFloat);
                    return LuaFloat.valueOf(d);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                long val = 0;
                for (int i = 0; i < hexBody.length(); i++) {
                    char c = hexBody.charAt(i);
                    int d = Character.digit(c, 16);
                    if (d < 0) return null;
                    val = val * 16 + d;
                }
                if (negative) val = -val;
                return LuaInteger.valueOf(val);
            }
        } else {
            boolean hasDot = false;
            boolean hasE = false;
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '.') {
                    if (hasDot || hasE) return null;
                    hasDot = true;
                } else if (c == 'e' || c == 'E') {
                    if (hasE) return null;
                    hasE = true;
                    if (i + 1 < rest.length() && (rest.charAt(i + 1) == '+' || rest.charAt(i + 1) == '-')) {
                        i++;
                    }
                } else if (c < '0' || c > '9') {
                    return null;
                }
            }

            if (hasDot || hasE) {
                try {
                    double d = Double.parseDouble(s);
                    return LuaFloat.valueOf(d);
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                try {
                    long val = Long.parseLong(s);
                    return LuaInteger.valueOf(val);
                } catch (NumberFormatException e) {
                    try {
                        double d = Double.parseDouble(s);
                        return LuaFloat.valueOf(d);
                    } catch (NumberFormatException ignored) {}
                    return null;
                }
            }
        }
    }

    public LuaInteger toLuaInteger() {
        if (this.isInteger()) return (LuaInteger) this;
        if (this.isFloat()) {
            double d = this.toDouble();
            if (!Double.isNaN(d) && !Double.isInfinite(d) && d >= -9223372036854775808.0 && d < 9223372036854775808.0 && Math.floor(d) == d) {
                return LuaInteger.valueOf((long) d);
            }
        }
        return null;
    }

    public LuaValue bnot() {
        LuaInteger a = this.toLuaInteger();
        if (a != null) {
            return LuaInteger.valueOf(~a.toLong());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__bnot"));
            if (!handler.isNil()) {
                if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(LuaString.valueOf("__call")).isNil())) {
                    throw new LuaException("attempt to call a " + handler.typeName() + " value (metamethod 'bnot')");
                }
                org.luava.runtime.eval.CallStack.setNextCall("bnot", true);
                return handler.call(this, this);
            }
        }
        if (this.isNumber()) {
            throw new LuaException("number has no integer representation");
        }
        throw new LuaException("attempt to perform bitwise operation on a " + typeName() + " value");
    }

    public LuaValue band(LuaValue other) {
        LuaInteger a = this.toLuaInteger();
        LuaInteger b = other.toLuaInteger();
        if (a != null && b != null) {
            return LuaInteger.valueOf(a.toLong() & b.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__band", "perform bitwise operation on");
    }

    public LuaValue bor(LuaValue other) {
        LuaInteger a = this.toLuaInteger();
        LuaInteger b = other.toLuaInteger();
        if (a != null && b != null) {
            return LuaInteger.valueOf(a.toLong() | b.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__bor", "perform bitwise operation on");
    }

    public LuaValue bxor(LuaValue other) {
        LuaInteger a = this.toLuaInteger();
        LuaInteger b = other.toLuaInteger();
        if (a != null && b != null) {
            return LuaInteger.valueOf(a.toLong() ^ b.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__bxor", "perform bitwise operation on");
    }

    public LuaValue shl(LuaValue other) {
        LuaInteger a = this.toLuaInteger();
        LuaInteger b = other.toLuaInteger();
        if (a != null && b != null) {
            long shift = b.toLong();
            if (shift >= 64 || shift <= -64) return LuaInteger.valueOf(0);
            if (shift < 0) return LuaInteger.valueOf(a.toLong() >>> -shift);
            return LuaInteger.valueOf(a.toLong() << shift);
        }
        return dispatchBinaryMetamethod(this, other, "__shl", "perform bitwise operation on");
    }

    public LuaValue shr(LuaValue other) {
        LuaInteger a = this.toLuaInteger();
        LuaInteger b = other.toLuaInteger();
        if (a != null && b != null) {
            long shift = b.toLong();
            if (shift >= 64 || shift <= -64) return LuaInteger.valueOf(0);
            if (shift < 0) return LuaInteger.valueOf(a.toLong() << -shift);
            return LuaInteger.valueOf(a.toLong() >>> shift);
        }
        return dispatchBinaryMetamethod(this, other, "__shr", "perform bitwise operation on");
    }

    public LuaValue concat(LuaValue other) {
        if ((this.isString() || this.isNumber()) && (other.isString() || other.isNumber())) {
            return LuaString.valueOf(this.toLuaString() + other.toLuaString());
        }
        return dispatchBinaryMetamethod(this, other, "__concat", "concatenate");
    }

    public static boolean numberEquals(LuaValue a, LuaValue b) {
        if (a.isInteger() && b.isInteger()) {
            return a.toLong() == b.toLong();
        }
        if (a.isFloat() && b.isFloat()) {
            return a.toDouble() == b.toDouble();
        }
        long i;
        double f;
        if (a.isInteger()) {
            i = a.toLong();
            f = b.toDouble();
        } else {
            i = b.toLong();
            f = a.toDouble();
        }
        if (Double.isNaN(f) || Double.isInfinite(f)) {
            return false;
        }
        if (f < (double) Long.MIN_VALUE || f >= 9223372036854775808.0) {
            return false;
        }
        long l = (long) f;
        if ((double) l != f) {
            return false;
        }
        return i == l;
    }

    public static boolean numberLessThan(LuaValue a, LuaValue b) {
        if (a.isInteger() && b.isInteger()) {
            return a.toLong() < b.toLong();
        }
        if (a.isFloat() && b.isFloat()) {
            return a.toDouble() < b.toDouble();
        }
        if (a.isInteger()) {
            long i = a.toLong();
            double f = b.toDouble();
            if (Double.isNaN(f)) return false;
            if (f <= (double) Long.MIN_VALUE) return false;
            if (f >= 9223372036854775808.0) return true;
            long l = (long) f;
            double diff = f - (double) l;
            if (diff == 0) return i < l;
            if (diff > 0) return i <= l;
            return i < l;
        } else {
            double f = a.toDouble();
            long i = b.toLong();
            if (Double.isNaN(f)) return false;
            if (f < (double) Long.MIN_VALUE) return true;
            if (f >= 9223372036854775808.0) return false;
            long l = (long) f;
            double diff = f - (double) l;
            if (diff == 0) return l < i;
            if (diff > 0) return l < i;
            return l <= i;
        }
    }

    public static boolean numberLessOrEqual(LuaValue a, LuaValue b) {
        if (a.isInteger() && b.isInteger()) {
            return a.toLong() <= b.toLong();
        }
        if (a.isFloat() && b.isFloat()) {
            return a.toDouble() <= b.toDouble();
        }
        if (a.isInteger()) {
            long i = a.toLong();
            double f = b.toDouble();
            if (Double.isNaN(f)) return false;
            if (f < (double) Long.MIN_VALUE) return false;
            if (f >= 9223372036854775808.0) return true;
            long l = (long) f;
            double diff = f - (double) l;
            if (diff == 0) return i <= l;
            if (diff > 0) return i <= l;
            return i < l;
        } else {
            double f = a.toDouble();
            long i = b.toLong();
            if (Double.isNaN(f)) return false;
            if (f <= (double) Long.MIN_VALUE) return true;
            if (f >= 9223372036854775808.0) return false;
            long l = (long) f;
            double diff = f - (double) l;
            if (diff == 0) return l <= i;
            if (diff > 0) return l < i;
            return l <= i;
        }
    }

    public boolean luaEquals(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return numberEquals(this, other);
        }
        if (this == other) return true;
        if (this.type() != other.type()) {
            return false;
        }
        LuaTable mt = getMetatable();
        LuaValue handler = null;
        if (mt != null) {
            handler = mt.rawget(LuaString.valueOf("__eq"));
        }
        if (handler == null || handler.isNil()) {
            LuaTable otherMt = other.getMetatable();
            if (otherMt != null) {
                handler = otherMt.rawget(LuaString.valueOf("__eq"));
            }
        }
        if (handler != null && !handler.isNil()) {
            org.luava.runtime.eval.CallStack.setNextCall("eq", true);
            return handler.call(this, other).toBoolean();
        }
        return this.equals(other);
    }

    public boolean luaLessThan(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return numberLessThan(this, other);
        }
        if (this.isString() && other.isString()) {
            return this.toLuaString().compareTo(other.toLuaString()) < 0;
        }
        LuaValue handler = getBinaryHandler(this, other, "__lt");
        if (handler != null) {
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(LuaString.valueOf("__call")).isNil())) {
                throw new LuaException("attempt to call a " + handler.typeName() + " value (metamethod 'lt')");
            }
            org.luava.runtime.eval.CallStack.setNextCall("lt", true);
            return handler.call(this, other).toBoolean();
        }
        if (typeName().equals(other.typeName())) {
            throw new LuaException("attempt to compare two " + typeName() + " values");
        }
        throw new LuaException("attempt to compare " + typeName() + " with " + other.typeName());
    }

    public boolean luaLessOrEqual(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return numberLessOrEqual(this, other);
        }
        if (this.isString() && other.isString()) {
            return this.toLuaString().compareTo(other.toLuaString()) <= 0;
        }
        LuaValue handler = getBinaryHandler(this, other, "__le");
        if (handler != null) {
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(LuaString.valueOf("__call")).isNil())) {
                throw new LuaException("attempt to call a " + handler.typeName() + " value (metamethod 'le')");
            }
            org.luava.runtime.eval.CallStack.setNextCall("le", true);
            return handler.call(this, other).toBoolean();
        }
        if (typeName().equals(other.typeName())) {
            throw new LuaException("attempt to compare two " + typeName() + " values");
        }
        throw new LuaException("attempt to compare " + typeName() + " with " + other.typeName());
    }

    private static LuaValue dispatchBinaryMetamethod(LuaValue a, LuaValue b, String event, String opDesc) {
        LuaValue handler = getBinaryHandler(a, b, event);
        if (handler != null) {
            String eventName = event.startsWith("__") ? event.substring(2) : event;
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(LuaString.valueOf("__call")).isNil())) {
                throw new LuaException("attempt to call a " + handler.typeName() + " value (metamethod '" + eventName + "')");
            }
            org.luava.runtime.eval.CallStack.setNextCall(eventName, true);
            return handler.call(a, b);
        }
        if ("perform bitwise operation on".equals(opDesc)) {
            if (a.isNumber() && b.isNumber()) {
                throw new LuaException("number has no integer representation");
            }
        }
        LuaValue bad = (!a.isNumber() && !a.isString()) ? a : b;
        throw new LuaException("attempt to " + opDesc + " a " + bad.typeName() + " value");
    }

    private static LuaValue getBinaryHandler(LuaValue a, LuaValue b, String event) {
        LuaTable mt = a.getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf(event));
            if (!handler.isNil()) return handler;
        }
        mt = b.getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf(event));
            if (!handler.isNil()) return handler;
        }
        return null;
    }

    public static LuaException argError(int argNum, String funcName, String extramsg) {
        org.luava.runtime.eval.CallStack.Frame frame = org.luava.runtime.eval.CallStack.getFrame(0);
        if (frame != null && frame.isMethod) {
            argNum--;
            if (argNum == 0) {
                return new LuaException("calling '" + funcName + "' on bad self (" + extramsg + ")");
            }
        }
        return new LuaException("bad argument #" + argNum + " to '" + funcName + "' (" + extramsg + ")");
    }

    @Override
    public String toString() {
        return toLuaString();
    }
}

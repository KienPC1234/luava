/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import java.util.EnumMap;

public abstract class LuaValue {
    public static final LuaValue[] EMPTY_ARRAY = new LuaValue[0];

    /**
     * Interned metamethod names. {@code LuaString.valueOf} routes short
     * strings through a concurrent interning map, so resolving e.g.
     * {@code "__index"} on every table access pays a hash lookup plus
     * hashCode. These constants are resolved once at class init; metamethod
     * dispatch uses them directly.
     */
    public static final class Meta {
        public static final LuaString INDEX = LuaString.interned("__index");
        public static final LuaString NEWINDEX = LuaString.interned("__newindex");
        public static final LuaString CALL = LuaString.interned("__call");
        public static final LuaString LEN = LuaString.interned("__len");
        public static final LuaString TOSTRING = LuaString.interned("__tostring");
        public static final LuaString NAME = LuaString.interned("__name");
        public static final LuaString MODE = LuaString.interned("__mode");
        public static final LuaString METATABLE = LuaString.interned("__metatable");
        public static final LuaString EQ = LuaString.interned("__eq");
        public static final LuaString LT = LuaString.interned("__lt");
        public static final LuaString LE = LuaString.interned("__le");
        public static final LuaString CONCAT = LuaString.interned("__concat");
        public static final LuaString CLOSE = LuaString.interned("__close");
        public static final LuaString GC = LuaString.interned("__gc");
        public static final LuaString PAIRS = LuaString.interned("__pairs");
        public static final LuaString UNM = LuaString.interned("__unm");
        public static final LuaString BNOT = LuaString.interned("__bnot");
        public static final LuaString ADD = LuaString.interned("__add");
        public static final LuaString SUB = LuaString.interned("__sub");
        public static final LuaString MUL = LuaString.interned("__mul");
        public static final LuaString DIV = LuaString.interned("__div");
        public static final LuaString IDIV = LuaString.interned("__idiv");
        public static final LuaString MOD = LuaString.interned("__mod");
        public static final LuaString POW = LuaString.interned("__pow");
        public static final LuaString BAND = LuaString.interned("__band");
        public static final LuaString BOR = LuaString.interned("__bor");
        public static final LuaString BXOR = LuaString.interned("__bxor");
        public static final LuaString SHL = LuaString.interned("__shl");
        public static final LuaString SHR = LuaString.interned("__shr");

        private Meta() {}
    }

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
                LuaValue nameVal = mt.rawget(Meta.NAME);
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
    private static final ThreadLocal<LuaState> ACTIVE_BASIC_STATE = new ThreadLocal<>();

    public static LuaState pushBasicState(LuaState state) {
        LuaState previous = ACTIVE_BASIC_STATE.get();
        ACTIVE_BASIC_STATE.set(state);
        return previous;
    }

    public static void popBasicState(LuaState previous) {
        if (previous == null) {
            ACTIVE_BASIC_STATE.remove();
        } else {
            ACTIVE_BASIC_STATE.set(previous);
        }
    }

    public static LuaState activeBasicState() {
        return ACTIVE_BASIC_STATE.get();
    }

    public static LuaTable getBasicMetatable(LuaType type) {
        LuaState active = ACTIVE_BASIC_STATE.get();
        if (active != null) {
            // Inside a running state, the per-state map is authoritative: a
            // missing entry means "no metatable" (e.g. after
            // debug.setmetatable("", nil)). Falling back to the JVM-wide
            // default here would make the metatable impossible to remove,
            // unlike PUC. Every state installs its own string metatable in
            // its constructor, so nothing else is lost.
            return active.basicMetatables().get(type);
        }
        return BASIC_METATABLES[type.ordinal()];
    }

    public static void setBasicMetatable(LuaType type, LuaTable mt) {
        LuaState active = ACTIVE_BASIC_STATE.get();
        if (active != null) {
            if (mt == null) {
                active.basicMetatables().remove(type);
            } else {
                active.basicMetatables().put(type, mt);
            }
            return;
        }
        BASIC_METATABLES[type.ordinal()] = mt;
    }

    /**
     * Clears JVM-wide default basic metatables. This intentionally does not
     * touch the isolated per-state registries used while Lua code executes.
     * It is retained for compatibility and test setup; production states do
     * not call it when they are constructed.
     */
    public static void resetBasicMetatables() {
        for (int i = 0; i < BASIC_METATABLES.length; i++) {
            if (i != LuaType.STRING.ordinal()) {
                BASIC_METATABLES[i] = null;
            }
        }
    }

    public LuaTable getMetatable() {
        return getBasicMetatable(type());
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
                LuaValue handler = mt.rawget(Meta.INDEX);
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
                LuaValue handler = mt.rawget(Meta.NEWINDEX);
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
                LuaValue handler = mt.rawget(Meta.CALL);
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
            LuaValue handler = mt.rawget(Meta.LEN);
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
        return dispatchBinaryMetamethod(this, other, "__add", "perform arithmetic on");
    }

    public LuaValue sub(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() - other.toLong());
        }
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() - other.toDouble());
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
        return dispatchBinaryMetamethod(this, other, "__mul", "perform arithmetic on");
    }

    public LuaValue div(LuaValue other) {
        // Lua 5.4: float division always returns float
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(this.toDouble() / other.toDouble());
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
            return LuaFloat.valueOf(luaFloatMod(this.toDouble(), other.toDouble()));
        }
        return dispatchBinaryMetamethod(this, other, "__mod", "perform arithmetic on");
    }

    public static double luaFloatMod(double a, double b) {
        double m = a % b;
        if (m > 0 ? b < 0 : (m < 0 && b > 0)) {
            m += b;
        }
        return m;
    }

    public LuaValue pow(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(luaNumPow(this.toDouble(), other.toDouble()));
        }
        return dispatchBinaryMetamethod(this, other, "__pow", "perform arithmetic on");
    }

    /**
     * C99 {@code pow} (Annex F), which PUC Lua uses via libm. Java's
     * {@link Math#pow} follows a different convention for two cases: it
     * returns NaN for {@code pow(1, y)} when y is NaN/±infinity, and for
     * {@code pow(-1, ±infinity)}. C99 mandates 1.0 for all of these.
     */
    public static double luaNumPow(double base, double exp) {
        if (base == 1.0) {
            return 1.0;
        }
        if (base == -1.0 && (exp == Double.POSITIVE_INFINITY || exp == Double.NEGATIVE_INFINITY)) {
            return 1.0;
        }
        return Math.pow(base, exp);
    }

    public LuaValue unm() {
        if (this.isInteger()) {
            return LuaInteger.valueOf(-this.toLong());
        }
        if (this.isFloat()) {
            return LuaFloat.valueOf(-this.toDouble());
        }
        return dispatchBinaryMetamethod(this, this, "__unm", "perform arithmetic on");
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

    /**
     * {@code lua_tointegerx}: like {@link #toLuaInteger()} but also coerces a
     * numeric string ("120", "0x10", " 3 "). This is the C-API conversion used
     * by {@code luaL_checkinteger}; the VM's arithmetic and bitwise operators
     * deliberately use the stricter {@code luaV_tointeger}, which does not
     * coerce strings.
     */
    public LuaInteger toLuaIntegerCoercingStrings() {
        LuaInteger i = toLuaInteger();
        if (i != null) return i;
        if (isString()) {
            LuaValue n = parseNumber(toLuaString());
            if (n != null && n != this) {
                return n.toLuaInteger();
            }
        }
        return null;
    }

    /**
     * {@code luaL_checkinteger} error for a value that failed integer
     * conversion: a value convertible to a number (including a numeric string
     * like "3.5") reports the integer-representation error; anything else
     * reports a type error. Callers build the
     * {@code bad argument #N to '<func>'} prefix.
     */
    public String integerConversionError() {
        if (toLuaNumber() != null) {
            return "number has no integer representation";
        }
        return "number expected, got " + typeName();
    }

    public LuaValue bnot() {
        LuaInteger a = this.toLuaInteger();
        if (a != null) {
            return LuaInteger.valueOf(~a.toLong());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(Meta.BNOT);
            if (!handler.isNil()) {
                if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(Meta.CALL).isNil())) {
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
            // A single `..` can double the operand size, so a long chain
            // (x = x..x) is a classic host-OOM vector. Honor the host's
            // per-allocation cap the same way string.rep/pack do.
            String left = this.toLuaString();
            String right = other.toLuaString();
            long resultLen = (long) left.length() + right.length();
            if (resultLen > org.luava.runtime.LuaState.allocationLimit()) {
                throw new LuaException("string length overflow");
            }
            return LuaString.valueOf(left + right);
        }
        return dispatchBinaryMetamethod(this, other, "__concat", "concatenate");
    }

    /**
     * Raw equality ({@code rawequal}, table key lookup): numbers compare by
     * mathematical value across the integer/float subtypes, all other types
     * by primitive equality, and metamethods are never consulted.
     */
    public static boolean rawEquals(LuaValue a, LuaValue b) {
        if (a.isNumber() && b.isNumber()) {
            return numberEquals(a, b);
        }
        return a.equals(b);
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
            handler = mt.rawget(Meta.EQ);
        }
        if (handler == null || handler.isNil()) {
            LuaTable otherMt = other.getMetatable();
            if (otherMt != null) {
                handler = otherMt.rawget(Meta.EQ);
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
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(Meta.CALL).isNil())) {
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
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(Meta.CALL).isNil())) {
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
            if (!(handler instanceof LuaFunction) && (handler.getMetatable() == null || handler.getMetatable().rawget(Meta.CALL).isNil())) {
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
        LuaValue bad;
        if ("concatenate".equals(opDesc)) {
            // C luaG_concaterror: blame the second operand unless the first is
            // itself non-concatenable (a numeric-looking string is still a
            // string, so `"3" .. {}` blames the table).
            bad = (a.isString() || a.isNumber()) ? b : a;
        } else {
            // C luaG_opinterror: blame the first operand unless it is a
            // number, in which case blame the second. (A numeric-looking
            // string is still a string here, so `"3" & 1` blames the string.)
            bad = !a.isNumber() ? a : b;
        }
        throw new LuaException("attempt to " + opDesc + " a " + bad.typeName() + " value");
    }

    private static LuaValue getBinaryHandler(LuaValue a, LuaValue b, String event) {
        LuaString key = metaKey(event);
        LuaTable mt = a.getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(key);
            if (!handler.isNil()) return handler;
        }
        mt = b.getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(key);
            if (!handler.isNil()) return handler;
        }
        return null;
    }

    /** Resolve a metamethod event name to its interned {@link Meta} key. */
    private static LuaString metaKey(String event) {
        return switch (event) {
            case "__add" -> Meta.ADD;
            case "__sub" -> Meta.SUB;
            case "__mul" -> Meta.MUL;
            case "__div" -> Meta.DIV;
            case "__idiv" -> Meta.IDIV;
            case "__mod" -> Meta.MOD;
            case "__pow" -> Meta.POW;
            case "__band" -> Meta.BAND;
            case "__bor" -> Meta.BOR;
            case "__bxor" -> Meta.BXOR;
            case "__shl" -> Meta.SHL;
            case "__shr" -> Meta.SHR;
            case "__concat" -> Meta.CONCAT;
            case "__eq" -> Meta.EQ;
            case "__lt" -> Meta.LT;
            case "__le" -> Meta.LE;
            case "__index" -> Meta.INDEX;
            case "__newindex" -> Meta.NEWINDEX;
            case "__call" -> Meta.CALL;
            case "__len" -> Meta.LEN;
            case "__unm" -> Meta.UNM;
            case "__bnot" -> Meta.BNOT;
            default -> LuaString.valueOf(event);
        };
    }

    public static LuaException argError(int argNum, String funcName, String extramsg) {
        org.luava.runtime.eval.CallStack.Frame frame = org.luava.runtime.eval.CallStack.getFrame(0);
        // PUC's luaL_argerror names the offending function from lua_getinfo "n"
        // on the current frame: a direct Lua call carries the call-site name
        // (table.insert -> 'insert', local f = string.rep -> 'f'), while a
        // C-invoked call pushed by LuaFunction.call (a comparator run by
        // table.sort, a function run by pcall) has no call-site name and falls
        // back to pushglobalfuncname, an identity search over the globals/
        // loaded tables (table.insert -> 'table.insert'). Prefer the frame name,
        // then the qualified name, then the builtin's own name.
        String name = funcName;
        boolean method = false;
        if (frame != null) {
            method = frame.isMethod;
            if (frame.name != null && !frame.name.isEmpty() && !"?".equals(frame.name)) {
                name = frame.name;
            } else if (frame.function != null) {
                String global = org.luava.runtime.standard.DebugLib.findGlobalFuncName(frame.function, frame.env);
                if (global != null) {
                    name = global;
                }
            }
        }
        if (method) {
            argNum--;
            if (argNum == 0) {
                return new LuaException("calling '" + name + "' on bad self (" + extramsg + ")");
            }
        }
        return new LuaException("bad argument #" + argNum + " to '" + name + "' (" + extramsg + ")");
    }

    @Override
    public String toString() {
        return toLuaString();
    }
}

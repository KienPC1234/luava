package org.luava.runtime;

public abstract class LuaValue {
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

    public boolean isThread() {
        return false;
    }

    public boolean toBoolean() {
        return true; // only nil and false evaluate to false in Lua
    }

    public long toLong() {
        throw new LuaException("Attempt to convert " + typeName() + " to integer");
    }

    public double toDouble() {
        throw new LuaException("Attempt to convert " + typeName() + " to float");
    }

    public abstract String toLuaString();

    public LuaTable getMetatable() {
        return null;
    }

    public void setMetatable(LuaTable metatable) {
        throw new LuaException("Cannot set metatable on " + typeName());
    }

    public LuaValue get(LuaValue key) {
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__index"));
            if (!handler.isNil()) {
                if (handler.isFunction()) {
                    return handler.call(this, key);
                } else {
                    return handler.get(key);
                }
            }
        }
        throw new LuaException("Attempt to index a " + typeName() + " value");
    }

    public void set(LuaValue key, LuaValue value) {
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__newindex"));
            if (!handler.isNil()) {
                if (handler.isFunction()) {
                    handler.call(this, key, value);
                    return;
                } else {
                    handler.set(key, value);
                    return;
                }
            }
        }
        throw new LuaException("Attempt to index a " + typeName() + " value");
    }

    public LuaValue call(LuaValue... args) {
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__call"));
            if (!handler.isNil()) {
                LuaValue[] callArgs = new LuaValue[args.length + 1];
                callArgs[0] = this;
                System.arraycopy(args, 0, callArgs, 1, args.length);
                return handler.call(callArgs);
            }
        }
        throw new LuaException("Attempt to call a " + typeName() + " value");
    }

    public LuaValue len() {
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__len"));
            if (!handler.isNil()) {
                return handler.call(this);
            }
        }
        throw new LuaException("Attempt to get length of a " + typeName() + " value");
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
            double a = this.toDouble();
            double b = other.toDouble();
            double res = a - Math.floor(a / b) * b;
            return LuaFloat.valueOf(res);
        }
        return dispatchBinaryMetamethod(this, other, "__mod", "perform arithmetic on");
    }

    public LuaValue pow(LuaValue other) {
        if (this.isNumber() && other.isNumber()) {
            return LuaFloat.valueOf(Math.pow(this.toDouble(), other.toDouble()));
        }
        return dispatchBinaryMetamethod(this, other, "__pow", "perform arithmetic on");
    }

    public LuaValue unm() {
        if (this.isInteger()) {
            return LuaInteger.valueOf(-this.toLong());
        }
        if (this.isNumber()) {
            return LuaFloat.valueOf(-this.toDouble());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__unm"));
            if (!handler.isNil()) {
                return handler.call(this);
            }
        }
        throw new LuaException("attempt to perform arithmetic on a " + typeName() + " value");
    }

    public LuaValue bnot() {
        if (this.isInteger()) {
            return LuaInteger.valueOf(~this.toLong());
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__bnot"));
            if (!handler.isNil()) {
                return handler.call(this);
            }
        }
        throw new LuaException("attempt to perform bitwise operation on a " + typeName() + " value");
    }

    public LuaValue band(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() & other.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__band", "perform bitwise operation on");
    }

    public LuaValue bor(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() | other.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__bor", "perform bitwise operation on");
    }

    public LuaValue bxor(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return LuaInteger.valueOf(this.toLong() ^ other.toLong());
        }
        return dispatchBinaryMetamethod(this, other, "__bxor", "perform bitwise operation on");
    }

    public LuaValue shl(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            long shift = other.toLong();
            if (shift >= 64 || shift <= -64) return LuaInteger.valueOf(0);
            if (shift < 0) return LuaInteger.valueOf(this.toLong() >>> -shift);
            return LuaInteger.valueOf(this.toLong() << shift);
        }
        return dispatchBinaryMetamethod(this, other, "__shl", "perform bitwise operation on");
    }

    public LuaValue shr(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            long shift = other.toLong();
            if (shift >= 64 || shift <= -64) return LuaInteger.valueOf(0);
            if (shift < 0) return LuaInteger.valueOf(this.toLong() << -shift);
            return LuaInteger.valueOf(this.toLong() >>> shift);
        }
        return dispatchBinaryMetamethod(this, other, "__shr", "perform bitwise operation on");
    }

    public LuaValue concat(LuaValue other) {
        if ((this.isString() || this.isNumber()) && (other.isString() || other.isNumber())) {
            return LuaString.valueOf(this.toLuaString() + other.toLuaString());
        }
        return dispatchBinaryMetamethod(this, other, "__concat", "concatenate");
    }

    public boolean luaEquals(LuaValue other) {
        if (this == other) return true;
        if (this.type() != other.type()) {
            // Check cross-number equality between integer and float
            if (this.isInteger() && other.isFloat()) {
                return (double) this.toLong() == other.toDouble();
            }
            if (this.isFloat() && other.isInteger()) {
                return this.toDouble() == (double) other.toLong();
            }
            return false;
        }
        LuaTable mt = getMetatable();
        if (mt != null) {
            LuaValue handler = mt.rawget(LuaString.valueOf("__eq"));
            LuaTable otherMt = other.getMetatable();
            if (!handler.isNil() && otherMt != null && handler.equals(otherMt.rawget(LuaString.valueOf("__eq")))) {
                return handler.call(this, other).toBoolean();
            }
        }
        return this.equals(other);
    }

    public boolean luaLessThan(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return this.toLong() < other.toLong();
        }
        if (this.isNumber() && other.isNumber()) {
            return this.toDouble() < other.toDouble();
        }
        if (this.isString() && other.isString()) {
            return this.toLuaString().compareTo(other.toLuaString()) < 0;
        }
        LuaValue handler = getBinaryHandler(this, other, "__lt");
        if (handler != null) {
            return handler.call(this, other).toBoolean();
        }
        throw new LuaException("attempt to compare " + typeName() + " with " + other.typeName());
    }

    public boolean luaLessOrEqual(LuaValue other) {
        if (this.isInteger() && other.isInteger()) {
            return this.toLong() <= other.toLong();
        }
        if (this.isNumber() && other.isNumber()) {
            return this.toDouble() <= other.toDouble();
        }
        if (this.isString() && other.isString()) {
            return this.toLuaString().compareTo(other.toLuaString()) <= 0;
        }
        LuaValue handler = getBinaryHandler(this, other, "__le");
        if (handler != null) {
            return handler.call(this, other).toBoolean();
        }
        // Fallback to not (other < this)
        handler = getBinaryHandler(this, other, "__lt");
        if (handler != null) {
            return !handler.call(other, this).toBoolean();
        }
        throw new LuaException("attempt to compare " + typeName() + " with " + other.typeName());
    }

    private static LuaValue dispatchBinaryMetamethod(LuaValue a, LuaValue b, String event, String opDesc) {
        LuaValue handler = getBinaryHandler(a, b, event);
        if (handler != null) {
            return handler.call(a, b);
        }
        throw new LuaException("attempt to " + opDesc + " a " + a.typeName() + " and a " + b.typeName());
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

    @Override
    public String toString() {
        return toLuaString();
    }
}

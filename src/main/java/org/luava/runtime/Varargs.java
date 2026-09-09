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

    public LuaValue[] getValuesUnsafe() {
        return values;
    }

    @Override
    public LuaType type() {
        return first().type();
    }

    @Override
    public boolean isNil() {
        return first().isNil();
    }

    @Override
    public boolean isBoolean() {
        return first().isBoolean();
    }

    @Override
    public boolean isInteger() {
        return first().isInteger();
    }

    @Override
    public boolean isFloat() {
        return first().isFloat();
    }

    @Override
    public boolean isNumber() {
        return first().isNumber();
    }

    @Override
    public boolean isString() {
        return first().isString();
    }

    @Override
    public boolean isTable() {
        return first().isTable();
    }

    @Override
    public boolean isFunction() {
        return first().isFunction();
    }

    @Override
    public boolean isUserdata() {
        return first().isUserdata();
    }

    @Override
    public boolean isThread() {
        return first().isThread();
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

    @Override
    public LuaTable getMetatable() {
        return first().getMetatable();
    }

    @Override
    public LuaValue get(LuaValue key) {
        return first().get(key);
    }

    @Override
    public LuaValue call(LuaValue... args) {
        return first().call(args);
    }

    public LuaValue rawget(LuaValue key) {
        if (first() instanceof LuaTable t) {
            return t.rawget(key);
        }
        return LuaNil.NIL;
    }

    public void rawset(LuaValue key, LuaValue value) {
        if (first() instanceof LuaTable t) {
            t.rawset(key, value);
        }
    }

    public int rawlen() {
        if (first() instanceof LuaTable t) {
            return t.rawlen();
        }
        return 0;
    }

    @Override
    public LuaValue len() {
        return first().len();
    }

    @Override
    public LuaValue add(LuaValue other) {
        return first().add(other);
    }

    @Override
    public LuaValue sub(LuaValue other) {
        return first().sub(other);
    }

    @Override
    public LuaValue mul(LuaValue other) {
        return first().mul(other);
    }

    @Override
    public LuaValue div(LuaValue other) {
        return first().div(other);
    }

    @Override
    public LuaValue idiv(LuaValue other) {
        return first().idiv(other);
    }

    @Override
    public LuaValue mod(LuaValue other) {
        return first().mod(other);
    }

    @Override
    public LuaValue pow(LuaValue other) {
        return first().pow(other);
    }

    @Override
    public LuaValue unm() {
        return first().unm();
    }

    @Override
    public LuaValue band(LuaValue other) {
        return first().band(other);
    }

    @Override
    public LuaValue bor(LuaValue other) {
        return first().bor(other);
    }

    @Override
    public LuaValue bxor(LuaValue other) {
        return first().bxor(other);
    }

    @Override
    public LuaValue shl(LuaValue other) {
        return first().shl(other);
    }

    @Override
    public LuaValue shr(LuaValue other) {
        return first().shr(other);
    }

    @Override
    public LuaValue bnot() {
        return first().bnot();
    }

    @Override
    public LuaValue concat(LuaValue other) {
        return first().concat(other);
    }

    @Override
    public boolean luaLessThan(LuaValue other) {
        return first().luaLessThan(other);
    }

    @Override
    public boolean luaLessOrEqual(LuaValue other) {
        return first().luaLessOrEqual(other);
    }
}

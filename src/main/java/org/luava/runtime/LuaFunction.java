package org.luava.runtime;

public abstract class LuaFunction extends LuaValue {
    @Override
    public LuaType type() {
        return LuaType.FUNCTION;
    }

    @Override
    public boolean isFunction() {
        return true;
    }

    public abstract LuaValue invoke(LuaValue... args);

    @Override
    public LuaValue call(LuaValue... args) {
        return invoke(args);
    }

    public static LuaFunction of(LuaInvokable invokable) {
        return new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                return invokable.invoke(args);
            }

            @Override
            public String toLuaString() {
                return "function: builtin@0x" + Integer.toHexString(System.identityHashCode(this));
            }
        };
    }

    @Override
    public String toLuaString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

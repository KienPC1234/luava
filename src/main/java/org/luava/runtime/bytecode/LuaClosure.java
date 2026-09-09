package org.luava.runtime.bytecode;

import org.luava.runtime.*;
import org.luava.runtime.eval.Upvalue;

import java.util.Arrays;

public final class LuaClosure extends LuaFunction {
    public final LuaProto proto;
    public final Upvalue[] upvals;
    public final LuaTable env;
    private LuaState state;

    public LuaClosure(LuaProto proto, Upvalue[] upvals, LuaTable env, LuaState state) {
        this.proto = proto;
        this.upvals = upvals != null ? upvals : new Upvalue[0];
        this.env = env;
        this.state = state;

        this.source = proto.source;
        this.lineDefined = proto.lineDefined;
        this.lastLineDefined = proto.lastLineDefined;
        this.what = proto.lineDefined == 0 ? "main" : "Lua";
        this.nparams = proto.numParams;
        this.isVararg = proto.isVararg;
        this.upvalues = Arrays.asList(this.upvals);
    }

    public LuaClosure(LuaProto proto, Upvalue[] upvals, LuaTable env) {
        this(proto, upvals, env, null);
    }

    public LuaState getState() {
        return state;
    }

    public void setState(LuaState state) {
        this.state = state;
    }

    @Override
    public LuaValue invoke(LuaValue... args) {
        if (state == null) {
            state = new LuaState();
        }
        LuaValue[] res = BytecodeVM.execute(state, this, args);
        if (res == null || res.length == 0) return LuaNil.NIL;
        if (res.length == 1) return res[0];
        return org.luava.runtime.Varargs.of(res);
    }

    @Override
    public String toLuaString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

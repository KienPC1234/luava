/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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
        this.upvalues = new java.util.ArrayList<>(Arrays.asList(this.upvals));
        this.rawSource = proto.rawSource;
        this.body = proto.body;
        if (proto.locVarInfos != null && proto.numParams > 0) {
            this.params = new java.util.ArrayList<>(proto.numParams);
            for (int i = 0; i < proto.numParams && i < proto.locVarInfos.length; i++) {
                this.params.add(proto.locVarInfos[i].name());
            }
        }
        if (proto.name != null) {
            this.setName(proto.name);
        }
        org.luava.runtime.eval.GCManager.onAlloc(64 + this.upvals.length * 16);
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
    public void replaceUpvalue(int index, org.luava.runtime.eval.Upvalue uv) {
        upvals[index] = uv;
        upvalues.set(index, uv);
    }

    @Override
    public LuaValue invoke(LuaValue... args) {
        if (state == null) {
            state = new LuaState();
        }
        LuaValue[] res = BytecodeVM.execute(state, this, args);
        // Lua 5.4: bare 'return' yields zero values (not one nil).
        // Mirror AST InterpretedLuaFunction which returns Varargs.EMPTY.
        if (res == null || res.length == 0) return org.luava.runtime.Varargs.EMPTY;
        if (res.length == 1) return res[0];
        return org.luava.runtime.Varargs.of(res);
    }

    @Override
    public String toLuaString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

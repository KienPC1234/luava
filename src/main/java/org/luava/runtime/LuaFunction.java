/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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

    protected String source = "=[Lua]";
    protected int lineDefined = 1;
    protected int lastLineDefined = 1;
    protected String what = "Lua";
    protected int nparams = 0;
    protected boolean isVararg = true;
    protected String name = null;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public int getLineDefined() { return lineDefined; }
    public void setLineDefined(int lineDefined) { this.lineDefined = lineDefined; }
    public int getLastLineDefined() { return lastLineDefined; }
    public void setLastLineDefined(int lastLineDefined) { this.lastLineDefined = lastLineDefined; }
    public String getWhat() {
        if ("C".equals(what)) return "C";
        return (lineDefined == 0) ? "main" : what;
    }
    public void setWhat(String what) { this.what = what; }
    public int getNparams() { return nparams; }
    public void setNparams(int nparams) { this.nparams = nparams; }
    public boolean isVararg() { return isVararg; }
    public void setVararg(boolean vararg) { isVararg = vararg; }
    protected String rawSource = null;
    public String getRawSource() { return rawSource; }
    public void setRawSource(String rawSource) { this.rawSource = rawSource; }
    protected java.util.List<org.luava.runtime.eval.Upvalue> upvalues = new java.util.ArrayList<>();
    public java.util.List<org.luava.runtime.eval.Upvalue> getUpvalues() { return upvalues; }
    public void setUpvalues(java.util.List<org.luava.runtime.eval.Upvalue> upvalues) { this.upvalues = upvalues != null ? upvalues : new java.util.ArrayList<>(); }
    /** Replace upvalue at 0-based index; subclasses may override to update internal arrays. */
    public void replaceUpvalue(int index, org.luava.runtime.eval.Upvalue uv) {
        upvalues.set(index, uv);
    }
    protected java.util.List<String> params = new java.util.ArrayList<>();
    public java.util.List<String> getParams() { return params; }
    public void setParams(java.util.List<String> params) { this.params = params != null ? params : new java.util.ArrayList<>(); }
    protected boolean stripped = false;
    public boolean isStripped() { return stripped; }
    public void setStripped(boolean stripped) { this.stripped = stripped; }
    protected org.luava.frontend.ast.Statements.BlockStmt body = null;
    public org.luava.frontend.ast.Statements.BlockStmt getBody() { return body; }
    public void setBody(org.luava.frontend.ast.Statements.BlockStmt body) { this.body = body; }

    public static LuaFunction of(String name, LuaInvokable invokable) {
        LuaFunction fn = of(invokable);
        fn.setName(name);
        return fn;
    }

    public static LuaFunction of(LuaInvokable invokable) {
        LuaFunction fn = new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                return invokable.invoke(args);
            }

            @Override
            public String toLuaString() {
                return "function: builtin@0x" + Integer.toHexString(System.identityHashCode(this));
            }
        };
        fn.setWhat("C");
        fn.setSource("=[C]");
        fn.setLineDefined(-1);
        fn.setLastLineDefined(-1);
        return fn;
    }

    @Override
    public String toLuaString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

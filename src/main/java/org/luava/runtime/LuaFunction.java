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
    // Lazily materialized: most LuaFunctions are LuaClosures, which store
    // their upvalues/params in arrays and never read these lists on the hot
    // path. Eagerly allocating two ArrayLists in the superclass constructor
    // then discarding them (as LuaClosure does) was pure garbage on the hottest
    // allocation site (one closure per call in the closures benchmark).
    protected java.util.List<org.luava.runtime.eval.Upvalue> upvalues;
    public java.util.List<org.luava.runtime.eval.Upvalue> getUpvalues() {
        java.util.List<org.luava.runtime.eval.Upvalue> list = upvalues;
        if (list == null) {
            list = new java.util.ArrayList<>();
            upvalues = list;
        }
        return list;
    }
    public void setUpvalues(java.util.List<org.luava.runtime.eval.Upvalue> upvalues) {
        this.upvalues = upvalues != null ? upvalues : new java.util.ArrayList<>();
    }
    /** Replace upvalue at 0-based index; subclasses may override to update internal arrays. */
    public void replaceUpvalue(int index, org.luava.runtime.eval.Upvalue uv) {
        getUpvalues().set(index, uv);
    }
    protected java.util.List<String> params;
    public java.util.List<String> getParams() {
        java.util.List<String> list = params;
        if (list == null) {
            list = new java.util.ArrayList<>();
            params = list;
        }
        return list;
    }
    public void setParams(java.util.List<String> params) {
        this.params = params != null ? params : new java.util.ArrayList<>();
    }
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
        // No toLuaString override: PUC Lua renders C functions exactly like
        // Lua ones ("function: 0xADDR"), so inherit the base method.
        LuaFunction fn = new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                return invokable.invoke(args);
            }
        };
        fn.setWhat("C");
        fn.setSource("=[C]");
        fn.setLineDefined(-1);
        fn.setLastLineDefined(-1);
        return fn;
    }

    /**
     * Wraps a host-provided {@link LuaInvokable} so unchecked Java exceptions
     * never leak to the host or to Lua: control-flow signals
     * ({@link LuaException}, {@link LuaExit}, unwind/close signals) pass
     * through unchanged, everything else becomes a {@link LuaException}.
     * Use for every function the host registers ({@code registerFunction},
     * {@code JavaFunctionBuilder}).
     */
    public static LuaFunction ofGuarded(LuaInvokable invokable) {
        LuaFunction fn = new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                try {
                    return invokable.invoke(args);
                } catch (LuaException | LuaExit
                        | org.luava.runtime.eval.LuaUnwindException
                        | org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal e) {
                    throw e;
                } catch (StackOverflowError e) {
                    throw new LuaException("stack overflow");
                } catch (Throwable t) {
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    throw new LuaException("Java error in host function: " + msg);
                }
            }
        };
        fn.setWhat("C");
        fn.setSource("=[C]");
        fn.setLineDefined(-1);
        fn.setLastLineDefined(-1);
        return fn;
    }

    /**
     * Converts a reflection/host failure into a {@link LuaException} while
     * letting control-flow signals pass through: {@link LuaException},
     * {@link LuaExit}, {@link org.luava.runtime.eval.LuaUnwindException} and
     * {@link org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal}
     * are never swallowed, so {@code os.exit} or a coroutine close triggered
     * inside a host callback still unwinds correctly. Unwraps the
     * {@link java.lang.reflect.InvocationTargetException} added by
     * {@code Method.invoke}.
     */
    public static LuaException hostError(String context, Throwable t) {
        Throwable cause = (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null)
                ? t.getCause() : t;
        if (cause instanceof LuaException le) return le;
        if (cause instanceof LuaExit le) throw le;
        if (cause instanceof org.luava.runtime.eval.LuaUnwindException ue) throw ue;
        if (cause instanceof org.luava.runtime.concurrency.LuaCoroutine.CoroutineCloseSignal ccs) throw ccs;
        if (cause instanceof StackOverflowError) return new LuaException("stack overflow");
        String msg = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new LuaException(context + ": " + msg);
    }

    @Override
    public String toLuaString() {
        return "function: 0x" + Integer.toHexString(System.identityHashCode(this));
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

public class LuaException extends RuntimeException {
    private LuaValue errorObject;
    private String customMessage;
    private int line = -1;
    private boolean decorated = false;
    /**
     * True when the message is already complete and must not receive the
     * VM's {@code source:line:} prefix or operand descriptor. PUC raises
     * {@code luaG_runerror} from inside a C function (e.g. {@code luaL_len}'s
     * "attempt to get length of a X value"); since the current frame is C,
     * {@code luaG_runerror} adds neither the Lua line nor the operand
     * description. This is an explicit opt-in via {@link #undecorated}: the
     * alternative, guessing from the message text, silently dropped the
     * caller location from every {@code luaL_error}-style message that a C
     * helper rethrew verbatim (e.g. {@code io}'s "attempt to use a closed
     * file", the string metatable's "attempt to add a 'string' with a
     * 'number'"), all of which PUC does locate.
     */
    private boolean noDecorate = false;
    private final java.util.List<org.luava.runtime.eval.CallStack.Frame> luaFrames;

    public LuaException(String message) {
        super(message);
        this.errorObject = message != null ? LuaString.valueOf(message) : LuaNil.NIL;
        this.luaFrames = captureLuaFrames();
    }

    /**
     * A {@code luaG_runerror}/{@code luaG_typeerror} raised from a C frame:
     * the message is final and must not gain a {@code source:line:} prefix
     * from the enclosing Lua frame. Used only where PUC's C code really does
     * raise from C (currently {@code table}'s {@code luaL_len} call sites).
     */
    public static LuaException undecorated(String message) {
        LuaException e = new LuaException(message);
        e.noDecorate = true;
        return e;
    }

    public LuaException(LuaValue errorObject) {
        super(errorObject != null ? errorObject.toLuaString() : "nil");
        this.errorObject = errorObject != null ? errorObject : LuaNil.NIL;
        this.luaFrames = captureLuaFrames();
    }

    private static java.util.List<org.luava.runtime.eval.CallStack.Frame> captureLuaFrames() {
        try {
            org.luava.runtime.eval.CallStack.CallStackState state = org.luava.runtime.eval.CallStack.currentState();
            if (state != null && state.top > 0) {
                java.util.List<org.luava.runtime.eval.CallStack.Frame> list = new java.util.ArrayList<>(state.top);
                for (int i = state.top - 1; i >= 0; i--) {
                    org.luava.runtime.eval.CallStack.Frame f = state.stack[i];
                    if (f != null) {
                        list.add(new org.luava.runtime.eval.CallStack.Frame(f.function, f.name, f.line, f.isMethod, f.isMetamethod));
                    }
                }
                return list;
            }
        } catch (Throwable ignored) {}
        return java.util.Collections.emptyList();
    }

    public java.util.List<org.luava.runtime.eval.CallStack.Frame> getLuaFrames() {
        return luaFrames;
    }

    public void printLuaStackTrace() {
        System.err.println("Lua Stack Trace:");
        for (org.luava.runtime.eval.CallStack.Frame frame : luaFrames) {
            String src = (frame.function != null && frame.function.getSource() != null) ? frame.function.getSource() : "=[Lua]";
            System.err.println("\tat " + src + ":" + frame.line + " (" + (frame.name != null ? frame.name : "?") + ")");
        }
    }

    public LuaValue getErrorObject() {
        return errorObject;
    }

    public void setErrorObject(LuaValue errorObject) {
        this.errorObject = errorObject;
    }

    public void setMessage(String message) {
        this.customMessage = message;
        this.errorObject = message != null ? LuaString.valueOf(message) : LuaNil.NIL;
    }

    public boolean isDecorated() {
        return decorated;
    }

    public void setDecorated(boolean decorated) {
        this.decorated = decorated;
    }

    public boolean isNoDecorate() {
        return noDecorate;
    }

    public void setNoDecorate(boolean noDecorate) {
        this.noDecorate = noDecorate;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        if (this.line == -1) {
            this.line = line;
        }
    }

    @Override
    public String getMessage() {
        if (customMessage != null) {
            return customMessage;
        }
        String msg = super.getMessage();
        if (line > 0 && msg != null && !msg.contains(":" + line + ":")) {
            return "line " + line + ": " + msg;
        }
        return msg;
    }
}

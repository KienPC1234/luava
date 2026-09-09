package org.luava.runtime;

public class LuaException extends RuntimeException {
    private LuaValue errorObject;
    private String customMessage;
    private int line = -1;
    private boolean decorated = false;
    private final java.util.List<org.luava.runtime.eval.CallStack.Frame> luaFrames;

    public LuaException(String message) {
        super(message);
        this.errorObject = message != null ? LuaString.valueOf(message) : LuaNil.NIL;
        this.luaFrames = captureLuaFrames();
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

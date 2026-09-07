package org.luava.runtime;

public class LuaException extends RuntimeException {
    private final LuaValue errorObject;

    public LuaException(String message) {
        super(message);
        this.errorObject = LuaString.valueOf(message);
    }

    public LuaException(LuaValue errorObject) {
        super(errorObject != null ? errorObject.toLuaString() : "nil");
        this.errorObject = errorObject != null ? errorObject : LuaNil.NIL;
    }

    public LuaValue getErrorObject() {
        return errorObject;
    }
}

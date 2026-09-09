package org.luava.runtime.eval;

import org.luava.runtime.LuaValue;

public final class LuaUnwindException extends RuntimeException {
    private final LuaValue result;
    private final LuaValue originalError;

    public LuaUnwindException(LuaValue result, LuaValue originalError) {
        super(null, null, false, false);
        this.result = result;
        this.originalError = originalError;
    }

    public LuaValue getResult() {
        return result;
    }

    public LuaValue getOriginalError() {
        return originalError;
    }
}

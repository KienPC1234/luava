/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
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

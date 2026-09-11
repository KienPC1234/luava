/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

/**
 * Thrown by {@code os.exit} to unwind the current chunk without terminating
 * the host JVM. It extends {@link Error} on purpose: Lua's {@code pcall} /
 * {@code xpcall} must NOT catch a process-exit request. A host embedding
 * Luava catches it around {@link LuaState#eval} and decides whether to stop.
 */
public final class LuaExit extends Error {
    private final int code;

    public LuaExit(int code) {
        super("os.exit(" + code + ")", null, false, false);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

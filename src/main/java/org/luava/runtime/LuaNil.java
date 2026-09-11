/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

public final class LuaNil extends LuaValue {
    public static final LuaNil NIL = new LuaNil();

    private LuaNil() {}

    @Override
    public LuaType type() {
        return LuaType.NIL;
    }

    @Override
    public boolean isNil() {
        return true;
    }

    @Override
    public boolean toBoolean() {
        return false;
    }

    @Override
    public String toLuaString() {
        return "nil";
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this;
    }

    @Override
    public int hashCode() {
        return 0;
    }
}

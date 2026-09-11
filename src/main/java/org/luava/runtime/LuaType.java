/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

public enum LuaType {
    NIL("nil"),
    BOOLEAN("boolean"),
    NUMBER("number"),
    STRING("string"),
    TABLE("table"),
    FUNCTION("function"),
    USERDATA("userdata"),
    THREAD("thread");

    private final String name;

    LuaType(String name) {
        this.name = name;
    }

    public String typeName() {
        return name;
    }
}

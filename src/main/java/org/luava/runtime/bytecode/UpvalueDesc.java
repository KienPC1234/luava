/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.bytecode;

public final class UpvalueDesc {
    public final String name;
    public final boolean inStack;
    public final int index;
    public final byte kind; // 0 = regular, 1 = const, 2 = to-be-closed

    public UpvalueDesc(String name, boolean inStack, int index, byte kind) {
        this.name = name;
        this.inStack = inStack;
        this.index = index;
        this.kind = kind;
    }

    public UpvalueDesc(String name, boolean inStack, int index) {
        this(name, inStack, index, (byte) 0);
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import java.util.LinkedHashMap;
import java.util.Map;
import org.luava.runtime.bytecode.LuaProto;

/**
 * Bounded registry of compiled protos. Caps the number of live hidden
 * classes so the JIT can never leak Metaspace (PLANS.md section IX):
 * evicting a victim clears its {@code proto.jitCode} so the interpreter
 * transparently takes over and the class becomes collectable.
 */
public final class JitCodeCache {
    private static final int MAX_ENTRIES = 512;

    private final Map<LuaProto, JitCode> map = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<LuaProto, JitCode> eldest) {
            if (size() > MAX_ENTRIES) {
                LuaProto victim = eldest.getKey();
                if (victim.jitCode == eldest.getValue()) {
                    victim.jitCode = null;
                }
                return true;
            }
            return false;
        }
    };

    public synchronized void put(LuaProto proto, JitCode code) {
        map.put(proto, code);
    }

    public synchronized int size() {
        return map.size();
    }
}

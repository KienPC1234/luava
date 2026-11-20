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
 * classes so the JIT can never leak Metaspace (plan.md section 2.1):
 * evicting a victim clears its {@code proto.jitCode} so the interpreter
 * transparently takes over and the class becomes collectable.
 *
 * <p>Uses a zero-lock-on-hit second-chance (CLOCK) eviction policy: hot execution
 * sets {@link JitCode#recentlyUsed} without acquiring the cache lock, and
 * eviction scans from the oldest inserted entry giving recently used kernels
 * one second chance.
 */
public final class JitCodeCache {
    private static final int MAX_ENTRIES = 512;

    private final Map<JitCode, LuaProto> map = new LinkedHashMap<>(64, 0.75f, false);

    public synchronized void put(LuaProto proto, JitCode code) {
        if (proto == null || code == null) {
            return;
        }
        map.remove(code);
        int spins = 0;
        while (map.size() >= MAX_ENTRIES) {
            java.util.Iterator<Map.Entry<JitCode, LuaProto>> it = map.entrySet().iterator();
            if (!it.hasNext()) {
                break;
            }
            Map.Entry<JitCode, LuaProto> entry = it.next();
            it.remove();
            JitCode jc = entry.getKey();
            LuaProto victim = entry.getValue();
            if (jc.recentlyUsed && spins++ < MAX_ENTRIES) {
                jc.recentlyUsed = false;
                map.put(jc, victim);
            } else {
                clearProtoRef(victim, jc);
            }
        }
        map.put(code, proto);
    }

    public synchronized void remove(LuaProto proto) {
        if (proto == null) {
            return;
        }
        JitCode unguarded = proto.jitCode;
        if (unguarded != null) {
            map.remove(unguarded);
            proto.jitCode = null;
        }
        JitCode guarded = proto.guardedJitCode;
        if (guarded != null) {
            map.remove(guarded);
            proto.guardedJitCode = null;
        }
    }

    private static void clearProtoRef(LuaProto victim, JitCode jc) {
        if (victim.jitCode == jc) {
            victim.jitCode = null;
        }
        if (victim.guardedJitCode == jc) {
            victim.guardedJitCode = null;
        }
    }

    public synchronized int size() {
        return map.size();
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.frontend;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Differential stdlib/runtime sweep. The Lua program ({@code
 * stdlib_sweep.lua}) canonicalizes every observable (fixed-precision numbers,
 * booleans, sorted/concat'd tables) and collapses every {@code pcall} failure
 * to the literal {@code ERR}, so stdout is engine-independent. The expected
 * block ({@code stdlib_sweep.expected.txt}) was captured verbatim from stock
 * PUC Lua 5.4 (built from {@code lua-source} with {@code -DLUA_COMPAT_5_3}).
 *
 * <p>This suite exists because the 30-file PUC test corpus does not exercise
 * many boundary inputs that the engine got wrong: {@code string.format('%d',
 * 2^63)}, an exhausted {@code gmatch} iterator, {@code pcall()}/{@code xpcall}
 * bad-argument raising, {@code os.date('!%Z')}, and negative-division/modulo
 * corners. Each case here is a real divergence found by differential testing.
 *
 * <p>Run under both JIT settings: the sweep must be byte-identical whether the
 * interpreter or compiled kernels execute it.
 */
public class StdlibSweepConformanceTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = StdlibSweepConformanceTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static String runSweep(boolean jit) throws IOException {
        String src = resource("stdlib_sweep.lua");
        LuaState state = new LuaState().jitEnabled(jit);
        LuaValue v = state.eval(src, "@stdlib_sweep");
        return v.toLuaString();
    }

    private static String normalize(String s) {
        // Output lines are separated by '\n'; trailing newline from print is
        // not part of the value.
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    @Test
    void stdlibSweepMatchesPucWithJitOn() throws IOException {
        String expected = normalize(resource("stdlib_sweep.expected.txt"));
        assertEquals(expected, normalize(runSweep(true)), "JIT-on sweep diverged from PUC 5.4");
    }

    @Test
    void stdlibSweepMatchesPucWithJitOff() throws IOException {
        String expected = normalize(resource("stdlib_sweep.expected.txt"));
        assertEquals(expected, normalize(runSweep(false)), "JIT-off sweep diverged from PUC 5.4");
    }
}

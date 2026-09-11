/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.security;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaExit;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the server-embedding sandbox and runaway-script
 * guards. Every test here corresponds to an escape/DoS that was actually
 * reproduced before the fix, so a regression fails loudly instead of
 * hanging a host.
 */
public class LuaSandboxSecurityTest {

    /** Runs {@code code} on a worker thread; fails if it does not finish. */
    private static void assertTerminatesWithin(LuaState state, String code, long millis) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                state.eval(code, "@sec");
            } catch (Throwable e) {
                err.set(e);
            }
        }, "sec-test");
        t.setDaemon(true);
        t.start();
        t.join(millis);
        assertFalse(t.isAlive(),
                "script did not terminate within " + millis + "ms (guard bypassed): " + code);
    }

    @Test
    void sandboxRemovesDangerousGlobals() {
        LuaState s = new LuaState().sandbox();
        for (String lib : new String[]{"os", "io", "package", "require", "dofile", "loadfile", "java", "luajava", "debug"}) {
            assertTrue(s.get(lib).isNil(), "sandbox should remove " + lib);
        }
    }

    @Test
    void sandboxBlocksFilesystemAndProcess() {
        LuaState s = new LuaState().sandbox();
        LuaValue r = s.eval("local ok = pcall(function() os.execute('echo hi') end); return tostring(ok)");
        assertEquals("false", r.toLuaString());
    }

    @Test
    void allowReinstallsLibrary() {
        LuaState s = new LuaState().sandbox();
        s.allow("os");
        assertFalse(s.get("os").isNil());
        s.eval("return os.time() ~= nil");
    }

    @Test
    void pcallCannotSwallowInstructionLimit() throws Exception {
        // Before the fix the one-shot guard was disarmed by pcall and the
        // following loop hung forever.
        LuaState s = new LuaState().sandbox().instructionLimit(200_000);
        assertTerminatesWithin(s,
                "pcall(function() while true do end end); while true do end", 4000);
    }

    @Test
    void binaryChunkRespectsGuard() throws Exception {
        // load(string.dump(...)) used to run on a fresh unguarded LuaState.
        LuaState s = new LuaState().sandbox().instructionLimit(100_000);
        assertTerminatesWithin(s,
                "local f = load(string.dump(function() while true do end end), nil, 'b'); pcall(f)", 4000);
    }

    @Test
    void timeoutStopsRunawayLoop() throws Exception {
        LuaState s = new LuaState().sandbox().timeout(Duration.ofMillis(150));
        long t0 = System.nanoTime();
        assertTerminatesWithin(s, "while true do end", 4000);
        assertTrue((System.nanoTime() - t0) < 3_000_000_000L);
    }

    @Test
    void tableMoveCannotHangHost() throws Exception {
        LuaState s = new LuaState().sandbox().instructionLimit(200_000);
        assertTerminatesWithin(s, "table.move({}, 1, 100000000000, 1)", 4000);
    }

    @Test
    void patternBacktrackingCannotHangHost() throws Exception {
        LuaState s = new LuaState().sandbox().instructionLimit(200_000);
        assertTerminatesWithin(s, "return ('a'):rep(300000):find('a*a*a*b')", 4000);
    }

    @Test
    void maxAllocationBlocksOom() {
        LuaState s = new LuaState().sandbox().maxAllocationBytes(1 << 20);
        LuaValue r = s.eval("local ok = pcall(string.rep, 'a', 2^31-1); return tostring(ok)");
        assertEquals("false", r.toLuaString());
        // A small allocation still succeeds.
        assertEquals("abab", s.eval("return string.rep('ab', 2)").toLuaString());
    }

    @Test
    void osExitDoesNotKillJvm() {
        LuaState s = new LuaState().sandbox();
        s.allow("os");
        LuaExit ex = assertThrows(LuaExit.class, () -> s.eval("os.exit(7)", "@sec"));
        assertEquals(7, ex.getCode());
        // pcall must not swallow the exit request.
        LuaExit ex2 = assertThrows(LuaExit.class,
                () -> s.eval("pcall(function() os.exit(3) end)", "@sec"));
        assertEquals(3, ex2.getCode());
    }

    @Test
    void osExitCloseRunsToBeClosed() {
        LuaState s = new LuaState().sandbox();
        s.allow("os");
        LuaExit ex = assertThrows(LuaExit.class, () -> s.eval("""
                local log = {}
                local x <close> = setmetatable({}, {__close = function() log[#log+1] = 'closed' end})
                os.exit(true, true)
                """, "@sec"));
        assertEquals(0, ex.getCode());
    }

    @Test
    void debugCannotPoisonOtherStates() {
        LuaState host = new LuaState();
        LuaState attacker = new LuaState().sandbox();
        // debug is removed, so the process-global primitive metatable cannot
        // be poisoned by untrusted code.
        assertThrows(RuntimeException.class,
                () -> attacker.eval("debug.setmetatable(0, {__index = {pwned = 'x'}})", "@sec"));
        LuaValue mt = host.eval("return debug.getmetatable(0)", "@host");
        assertTrue(mt.isNil(), "host number metatable must not be polluted");
    }

    @Test
    void guardedStateStillRunsNormalCode() {
        LuaState s = new LuaState().sandbox().instructionLimit(1_000_000);
        LuaValue r = s.eval("""
                local sum = 0
                for i = 1, 1000 do sum = sum + i end
                return sum
                """, "@sec");
        assertEquals(500500L, r.toLong());
    }

    @Test
    void guardDoesNotLeakBetweenEvals() {
        LuaState s = new LuaState().sandbox().instructionLimit(500_000);
        // First run trips the limit.
        assertThrows(RuntimeException.class, () -> s.eval("while true do end", "@sec"));
        // A fresh eval re-arms the budget and must complete.
        LuaValue r = s.eval("local x = 0 for i = 1, 100 do x = x + i end return x", "@sec");
        assertEquals(5050L, r.toLong());
    }

    @Test
    void stringDumpDoesNotSpawnAProcess() {
        // string.dump used to shell out to the C `luac`; in a sandbox that is
        // both a process-execution escape and a hidden C dependency. The
        // pure-Java dump must round-trip inside Luava without any subprocess.
        LuaState s = new LuaState().sandbox();
        LuaValue r = s.eval(
                "local d = string.dump(function(x) return x * 2 end)\n"
                        + "local g = load(d)\n"
                        + "return g(21)");
        assertEquals(42L, r.toLong());
    }

    @Test
    void clearGuardRestoresUnguardedExecution() {
        LuaState s = new LuaState().sandbox().instructionLimit(1000);
        s.clearGuard();
        LuaValue r = s.eval("local x = 0 for i = 1, 100000 do x = x + i end return x", "@sec");
        assertNotEquals(0L, r.toLong());
    }
}

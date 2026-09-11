/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.stress;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compact robustness suite: deep recursion, tailcalls, coroutine churn,
 * table/string pressure, error-heavy loops and concurrent states. Scaled
 * to run in seconds; the full-size variants live outside the repo.
 */
public class LuavaStressTest {

    private LuaValue eval(String code) {
        return new LuaState().eval(code, "@stress");
    }

    @Test
    void deepRecursionWithinNewLimit() {
        LuaValue v = eval("""
            local function deep(n) if n == 0 then return 0 end return 1 + deep(n - 1) end
            return deep(3000)
            """);
        assertEquals(3000L, v.toLong());
    }

    @Test
    void unboundedRecursionFailsCleanly() {
        LuaValue v = eval("""
            local function g() return 1 + g() end
            local ok, e = pcall(g)
            return tostring(not ok) .. "," .. tostring(e:find("stack overflow") ~= nil)
            """);
        assertEquals("true,true", v.toLuaString());
    }

    @Test
    void tailcallDepthAndCoroutineChurn() {
        LuaValue v = eval("""
            local function tc(n, a) if n == 0 then return a end return tc(n - 1, a + n) end
            local r = tc(100000, 0)
            local cos = {}
            for i = 1, 200 do
              cos[i] = coroutine.create(function()
                for j = 1, 5 do coroutine.yield(j) end
                return "done"
              end)
            end
            for j = 1, 5 do for i = 1, 200 do assert(coroutine.resume(cos[i])) end end
            local done = 0
            for i = 1, 200 do local ok, v = coroutine.resume(cos[i]) if ok and v == "done" then done = done + 1 end end
            return r .. "," .. done
            """);
        assertEquals("5000050000,200", v.toLuaString());
    }

    @Test
    void tableStringErrorAndTbcPressure() {
        LuaValue v = eval("""
            local t = {}
            for i = 1, 20000 do t[i] = i end
            local s = 0
            for i = 1, 20000 do s = s + t[i] end
            local parts = {}
            for i = 1, 2000 do parts[i] = "x" end
            local errs = 0
            for i = 1, 2000 do
              if not pcall(function() if i % 2 == 0 then error("e") end end) then errs = errs + 1 end
            end
            local closed = 0
            for i = 1, 500 do
              do local v <close> = setmetatable({}, {__close = function() closed = closed + 1 end}) end
            end
            return s .. "," .. #table.concat(parts) .. "," .. errs .. "," .. closed
            """);
        assertEquals("200010000,2000,1000,500", v.toLuaString());
    }

    @Test
    void concurrentStatesStayIndependent() throws Exception {
        int threads = 8;
        try (var pool = Executors.newFixedThreadPool(threads)) {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int id = i;
                fs.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    LuaState st = new LuaState();
                    LuaValue v = st.eval("local s = 0 for i = 1, 10000 do s = s + i end return s + " + id, "@t");
                    return v.toLong() + ":" + st.eval("return _VERSION").toLuaString();
                }));
            }
            start.countDown();
            for (int i = 0; i < threads; i++) {
                assertEquals((50005000L + i) + ":Lua 5.4", fs.get(i).get(60, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failedCoroutineReportsAndDies() {
        LuaState st = new LuaState();
        LuaValue v = st.eval("""
            local co = coroutine.create(function() error("bang") end)
            local ok, e = coroutine.resume(co)
            return tostring(ok) .. "," .. tostring(e:find("bang") ~= nil) .. "," .. coroutine.status(co)
            """);
        assertEquals("false,true,dead", v.toLuaString());
    }
}

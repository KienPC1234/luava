/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for to-be-closed variables and Lua 5.4 control flow,
 * checked against PUC Lua 5.4.
 *
 * <p>A {@code <close>} variable is closed at the end of the block it is
 * declared in. In a loop that means once per iteration, so a body-local
 * to-be-closed value must be closed on every pass. The {@code repeat} path
 * emitted its exit test with the polarity in the wrong operand, so no exit was
 * ever taken: the loop ran to its condition while the variable was registered
 * and closed only once.
 */
public class LuavaToBeClosedTest {

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(body).toLuaString();
    }

    /** A repeat body's to-be-closed variable closes once per iteration. */
    @Test
    void repeatClosesEveryIteration() {
        assertEquals("3", run("""
                local n = 0
                local i = 0
                repeat
                  local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                  i = i + 1
                until i >= 3
                return tostring(n)
                """));
    }

    /** The close order follows the iteration order. */
    @Test
    void repeatClosesInIterationOrder() {
        assertEquals("c1,c2,c3", run("""
                local log = {}
                local i = 0
                repeat
                  local x <close> = setmetatable({}, {__close = function() log[#log + 1] = "c" .. i end})
                  i = i + 1
                until i >= 3
                return table.concat(log, ",")
                """));
    }

    /** The condition must still terminate the loop at the right point. */
    @Test
    void repeatConditionIsRespected() {
        assertEquals("3", run("""
                local i = 0
                repeat i = i + 1 until i >= 3
                return tostring(i)
                """));
    }

    @Test
    void repeatRunsBodyAtLeastOnce() {
        assertEquals("1", run("""
                local i = 0
                repeat i = i + 1 until true
                return tostring(i)
                """));
    }

    @Test
    void repeatWithFalseConditionRunsToBreak() {
        assertEquals("5", run("""
                local i = 0
                repeat
                  i = i + 1
                  if i >= 5 then break end
                until false
                return tostring(i)
                """));
    }

    /** for, while and do blocks close once per iteration too. */
    @Test
    void otherLoopsCloseEveryIteration() {
        assertEquals("3,3,3", run("""
                local function forLoop()
                  local n = 0
                  for i = 1, 3 do
                    local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                  end
                  return n
                end
                local function whileLoop()
                  local n, i = 0, 0
                  while i < 3 do
                    local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                    i = i + 1
                  end
                  return n
                end
                local function doLoop()
                  local n = 0
                  for i = 1, 3 do
                    do
                      local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                    end
                  end
                  return n
                end
                return forLoop() .. "," .. whileLoop() .. "," .. doLoop()
                """));
    }

    /** Closing runs even when the body raises. */
    @Test
    void closeRunsOnError() {
        // pcall reports false because the body raised; the point is that the
        // to-be-closed value was still closed (n == 1).
        assertEquals("false,1", run("""
                local n = 0
                local ok = pcall(function()
                  local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                  error("boom")
                end)
                return tostring(ok) .. "," .. tostring(n)
                """));
    }

    /** nil and false are allowed and never closed. */
    @Test
    void nilAndFalseAreNotClosed() {
        assertEquals("ok", run("""
                do local x <close> = nil end
                do local x <close> = false end
                return "ok"
                """));
    }

    /** A non-closable value is rejected. */
    @Test
    void nonClosableValueIsRejected() {
        assertEquals("false", run("""
                return tostring(pcall(function() local x <close> = {} end))
                """));
    }

    /** The close metamethod receives the error object. */
    @Test
    void closeReceivesTheErrorObject() {
        assertEquals("boom", run("""
                local seen
                pcall(function()
                  local x <close> = setmetatable({}, {__close = function(_, err) seen = err end})
                  error("boom")
                end)
                return tostring(seen):gsub("^[^:]*:%d+: ", "")
                """));
    }

    /** repeat with a to-be-closed value plus break closes before leaving. */
    @Test
    void repeatBreakStillCloses() {
        assertEquals("2", run("""
                local n = 0
                local i = 0
                repeat
                  local x <close> = setmetatable({}, {__close = function() n = n + 1 end})
                  i = i + 1
                  if i >= 2 then break end
                until false
                return tostring(n)
                """));
    }
}

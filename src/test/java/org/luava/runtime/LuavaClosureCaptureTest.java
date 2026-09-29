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
 * Regression tests for closure capture, checked against PUC Lua 5.4.
 *
 * <p>A closure captures the <em>cell</em>, not the value it held when the
 * closure was created. The trivial-factory inline path used to snapshot the
 * captured registers at the {@code OP_CLOSURE}, which is only sound while
 * nothing writes them afterwards. Any later write to a captured register left
 * the closure holding the pre-write value, and since the closure's own
 * {@code OP_CLOSURE} writes that register, the canonical recursive-local idiom
 * captured {@code nil}:
 *
 * <pre>
 *   local f
 *   f = function(n) ... return f(n - 1) end
 *   return f            -- f was nil inside the returned closure
 * </pre>
 *
 * <p>Each expected value below is copied from the reference implementation.
 */
public class LuavaClosureCaptureTest {

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(body).toLuaString();
    }

    /** The recursive-local idiom must survive the closure escaping. */
    @Test
    void selfReferentialLocalCapturesItsOwnCell() {
        assertEquals("ok", run("""
                local function mk()
                  local f
                  f = function(n) if n <= 0 then return "ok" end return f(n - 1) end
                  return f
                end
                return mk()(3)
                """));
    }

    /** Two locals referring to each other, both assigned after capture. */
    @Test
    void mutuallyRecursiveLocalsEscapeTogether() {
        assertEquals("a then b", run("""
                local function mk()
                  local a, b
                  a = function(n) if n <= 0 then return "a" end return b(n - 1) end
                  b = function(n) if n <= 0 then return "b" end return a(n - 1) end
                  return a, b
                end
                local a, b = mk()
                return a(4) .. " then " .. b(4)
                """));
    }

    /** The local's cell may be assigned through a second, indirect local. */
    @Test
    void selfReferenceThroughASecondLocal() {
        assertEquals("g-ok/h-ok", run("""
                local function mk()
                  local f
                  local g = function(n) if n <= 0 then return "g-ok" end return f(n - 1) end
                  f = g
                  return g
                end
                local function mk2()
                  local f
                  local function h(n) if n <= 0 then return "h-ok" end return f(n - 1) end
                  f = h
                  return h
                end
                return mk()(3) .. "/" .. mk2()(3)
                """));
    }

    /** A write after capture must be visible, including a write to nil. */
    @Test
    void laterWritesAreSeenByTheClosure() {
        // a = the value read back at x = 3, then x is cleared, so the second
        // read is nil. PUC: 3, nil.
        assertEquals("3,nil", run("""
                local function mk()
                  local x = 1
                  local c = function() return x end
                  x = 3
                  local a = c()
                  x = nil
                  return tostring(a) .. "," .. tostring(c())
                end
                return mk()
                """));
    }

    /** Reading through a getter/setter pair shares one cell. */
    @Test
    void getterSetterPairSharesOneCell() {
        assertEquals("nil,7,S", run("""
                local function mk()
                  local x
                  local get = function() return x end
                  local set = function(v) x = v end
                  return get, set
                end
                local g, s = mk()
                local r = tostring(g())
                s(7) r = r .. "," .. g()
                s("S") r = r .. "," .. g()
                return r
                """));
    }

    /** Loop-body locals are distinct cells per iteration. */
    @Test
    void eachLoopIterationGetsItsOwnCell() {
        assertEquals("10,20,30", run("""
                local fns = {}
                for i = 1, 3 do
                  local j = i * 10
                  fns[i] = function() return j end
                end
                return fns[1]() .. "," .. fns[2]() .. "," .. fns[3]()
                """));
    }

    /** A variable declared outside the loop is one cell shared by all. */
    @Test
    void loopSharedCellIsObservedByEveryClosure() {
        assertEquals("3,3,3", run("""
                local fns = {}
                local shared
                for i = 1, 3 do
                  shared = i
                  fns[i] = function() return shared end
                end
                return fns[1]() .. "," .. fns[2]() .. "," .. fns[3]()
                """));
    }

    /** Escape through a table, a nested closure, a coroutine and a metatable. */
    @Test
    void captureSurvivesEveryEscapeChannel() {
        assertEquals("T/N/C/M", run("""
                local function via_table()
                  local x
                  local c = function() return x end
                  x = "T"
                  return {c}
                end
                local function via_nested()
                  local x
                  local c = function() return x end
                  x = "N"
                  return function() return c() end
                end
                local function via_coro()
                  local x
                  local c = function() return x end
                  x = "C"
                  local co = coroutine.create(function() return c() end)
                  local _, v = coroutine.resume(co)
                  return v
                end
                local function via_meta()
                  local x
                  local t = setmetatable({}, {__index = function() return x end})
                  x = "M"
                  return t.q
                end
                return via_table()[1]() .. "/" .. via_nested()() .. "/"
                       .. via_coro() .. "/" .. via_meta()
                """));
    }

    /** Closures from one factory share the same upvalue cell. */
    @Test
    void closuresFromOneFactoryShareTheCell() {
        // PUC: after inc(); inc() the cell is 2, so the getter reads 2; inc()
        // makes it 3.
        assertEquals("1,2,2,3", run("""
                local function mk()
                  local n = 0
                  local inc = function() n = n + 1 return n end
                  local get = function() return n end
                  return inc, get
                end
                local inc, get = mk()
                inc() inc()
                local a = get()
                inc()
                return "1,2," .. tostring(a) .. "," .. get()
                """));
    }

    /** The ordinary factory shape must keep working. */
    @Test
    void plainFactoryStillWorks() {
        assertEquals("21,42", run("""
                local function mk(x)
                  return function() return x end, function() return x * 2 end
                end
                local a, b = mk(21)
                return a() .. "," .. b()
                """));
    }

    /** Integer captures keep their integer subtype through the cell. */
    @Test
    void capturedIntegerKeepsItsSubtype() {
        assertEquals("integer,integer,3", run("""
                local function mk()
                  local x
                  local get = function() return x end
                  x = 3
                  return get
                end
                local g = mk()
                return math.type(g()) .. "," .. math.type(6 // 2) .. "," .. g()
                """));
    }
}

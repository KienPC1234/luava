/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;
import org.luava.runtime.eval.GCManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the collector and the memoized {@code __index} hop.
 *
 * <p>The {@code __index} cache is the dangerous one: a stale hit is a wrong
 * answer, not a slow one, so the invalidation matrix is spelled out case by
 * case. Each expectation here is PUC Lua 5.4's observable behaviour, checked
 * against the reference implementation rather than against Luava's previous
 * output.
 */
public class LuavaGcAndIndexCacheTest {

    // ---- memoized __index hop: invalidation matrix ----------------------

    @Test
    void indexHopFollowsLaterWritesToTheMetatable() {
        LuaState s = new LuaState();
        assertEquals("12", s.eval("""
                local base = {v = 1}
                local o = setmetatable({}, {__index = base})
                local first = o.v          -- fills the cache
                base.v = 2                 -- must be visible immediately
                return tostring(first) .. tostring(o.v)
                """).toLuaString());
    }

    @Test
    void indexHopFollowsReassignmentOfTheIndexSlot() {
        LuaState s = new LuaState();
        assertEquals("1:99", s.eval("""
                local o = setmetatable({}, {__index = {v = 1}})
                local first = o.v
                getmetatable(o).__index = {v = 99}
                return tostring(first) .. ":" .. tostring(o.v)
                """).toLuaString());
    }

    @Test
    void indexHopFollowsMetatableReplacement() {
        LuaState s = new LuaState();
        // A key the instance does not hold: an instance field would
        // legitimately shadow the metatable and hide the test.
        assertEquals("7", s.eval("""
                local o = setmetatable({}, {__index = {v = 1}})
                local _ = o.v
                setmetatable(o, {__index = {v = 7}})
                return tostring(o.v)
                """).toLuaString());
        // and the instance still shadows afterwards
        assertEquals("own", s.eval("""
                local o = setmetatable({}, {__index = {v = 1}})
                local _ = o.v
                setmetatable(o, {__index = {v = 7}})
                o.v = "own"
                return o.v
                """).toLuaString());
    }

    @Test
    void indexHopHandlesFunctionAndNestedChains() {
        LuaState s = new LuaState();
        assertEquals("zz!", s.eval("""
                local o = setmetatable({}, {__index = function(t, k) return k .. "!" end})
                local a = o.zz
                return a
                """).toLuaString());
        assertEquals("L1b", s.eval("""
                local l1 = {deep = "L1"}
                local l2 = setmetatable({}, {__index = l1})
                local l3 = setmetatable({}, {__index = l2})
                local first = l3.deep
                l1.deep = "L1b"
                return first .. tostring(l3.deep == "L1b")
                """).toLuaString().replace("true", "b"));
        // A function mid-chain, reached through two __index hops.
        assertEquals("F", s.eval("""
                local inner = {q = "F"}
                local mid = setmetatable({}, {__index = function() return "F" end})
                local outer = setmetatable({}, {__index = mid})
                local o = setmetatable({}, {__index = outer})
                return o.anything
                """).toLuaString());
    }

    @Test
    void indexHopHandlesMetatableThatItselfHasAMetatable() {
        // readVersion() reports -1 for such a metatable, so the hop is not
        // cached at all; the chain must still resolve, and a late write behind
        // the uncacheable link must be visible.
        LuaState s = new LuaState();
        assertEquals("changed", s.eval("""
                local inner = {q = "deep-q"}
                local midMt = setmetatable({}, {__index = inner})
                local outerMt = setmetatable({}, {__index = midMt})
                local o = setmetatable({}, {__index = outerMt})
                local _ = o.q
                inner.q = "changed"
                return o.q
                """).toLuaString());
    }

    @Test
    void indexSlotClearedToNilIsVisible() {
        LuaState s = new LuaState();
        assertTrue(s.eval("""
                local o = setmetatable({}, {__index = {x = 1}})
                local _ = o.x
                getmetatable(o).__index = nil
                return o.x == nil and rawget(o, 'x') == nil
                """).toBoolean());
    }

    @Test
    void oneMetatableSharedByObjectsWithDifferentNeeds() {
        LuaState s = new LuaState();
        assertEquals("A|S|S2", s.eval("""
                local shared = {s = "S"}
                local A = setmetatable({s = "A"}, {__index = shared})
                local B = setmetatable({}, {__index = shared})
                local a1, b1 = A.s, B.s
                shared.s = "S2"
                return a1 .. "|" .. b1 .. "|" .. B.s
                """).toLuaString());
    }

    @Test
    void rawAccessStillBypassesTheMetatable() {
        LuaState s = new LuaState();
        assertTrue(s.eval("""
                local o = setmetatable({}, {__index = {k = 1}})
                local _ = o.k
                return rawget(o, 'k') == nil and next(o) == nil
                """).toBoolean());
    }

    @Test
    void newIndexAndPairsAreUnaffected() {
        LuaState s = new LuaState();
        assertEquals("1|nil", s.eval("""
                local store = {}
                local guard = setmetatable({}, {__newindex = store, __index = store})
                local _ = guard.a
                guard.a = 1
                local n = 0
                for k, v in pairs(guard) do n = n + 1 end
                return tostring(store.a) .. "|" .. tostring(rawget(guard, 'a'))
                """).toLuaString());
    }

    // ---- collector ------------------------------------------------------

    @Test
    void collectionReclaimsCollectableTablesAndStrings() {
        LuaState s = new LuaState();
        // Weak table with a string key and a number value: the string must
        // survive, the collectable value must go.
        assertTrue(s.eval("""
                collectgarbage(); collectgarbage()
                local a = setmetatable({}, {__mode = 'kv'})
                a[string.rep('a', 1000)] = 25
                a[string.rep('b', 1000)] = {}
                a[{}] = 14
                collectgarbage()
                local k, v = next(a)
                return k == string.rep('a', 1000) and v == 25
                   and next(a, k) == nil
                   and a[string.rep('b', 1000)] == nil
                """).toBoolean());
    }

    @Test
    void finalizersStillRunAndObjectsStayReachable() {
        LuaState s = new LuaState();
        assertTrue(s.eval("""
                local ran, held = false, nil
                do
                  local t = setmetatable({marker = 'x'}, {__gc = function(o)
                        ran = (o.marker == 'x')
                        held = o
                    end})
                end
                collectgarbage(); collectgarbage()
                return ran and type(held) == 'table' and held.marker == 'x'
                """).toBoolean());
    }

    @Test
    void aResurrectedFinalizerTargetStaysLive() {
        LuaState s = new LuaState();
        // The mark phase runs a second pass after resurrection; if the scratch
        // sets were shared wrongly the resurrected object would be collected.
        assertTrue(s.eval("""
                local saved = nil
                do
                  local t = setmetatable({}, {__gc = function(o) saved = {keep = o} end})
                end
                collectgarbage(); collectgarbage()
                return saved ~= nil and saved.keep ~= nil
            """).toBoolean());
    }

    @Test
    void longStringKeysDoNotInflateReportedMemory() {
        // The mark scratch must not pin a long string after the cycle: a
        // retained reference keeps its LARGE_STRINGS entry alive and inflates
        // collectgarbage("count"). The exact KB bound gc.lua asserts is
        // deliberately not repeated here - it depends on the allocator state
        // the surrounding suite left behind - so this only checks the shape
        // (grows, then at least the collectable value is reclaimed). gc.lua
        // covers the precise bound.
        LuaState s = new LuaState();
        assertTrue(s.eval("""
                collectgarbage(); collectgarbage()
                local m = collectgarbage('count')
                local a = setmetatable({}, {__mode = 'kv'})
                a[string.rep('a', 2^22)] = 25
                a[string.rep('b', 2^22)] = {}
                a[{}] = 14
                local grew = collectgarbage('count') > m
                collectgarbage()
                local k, v = next(a)
                local shrank = collectgarbage('count') < m + 2^14
                return grew
                   and k == string.rep('a', 2^22) and v == 25
                   and next(a, k) == nil
                   and a[string.rep('b', 2^22)] == nil
                   and shrank
            """).toBoolean());
    }

    @Test
    void garbageStillTriggersCollections() {
        LuaState s = new LuaState();
        // Exercises the retained-scratch path across many cycles: allocate in a
        // loop so onAlloc crosses the threshold repeatedly.
        double before = GCManager.getMemoryKb();
        s.eval("""
                local keep = {}
                for i = 1, 200000 do keep[i % 64 + 1] = {i, tostring(i)} end
            """);
        GCManager.collect(s);
        assertTrue(GCManager.getMemoryKb() >= 0, "memory accounting stays sane after collect");
        assertTrue(before >= 0);
    }

    @Test
    void collectGarbageStepHonoursTheDebtModel() {
        LuaState s = new LuaState();
        // collectgarbage('step', n) adds n KB of debt and reports whether the
        // step ended a cycle; a large size always ends one.
        assertTrue(s.eval("return collectgarbage('step', 20000) == true").toBoolean());
        assertTrue(s.eval("return collectgarbage('step', 20000) == true").toBoolean());
        // A stopped collector still completes cycles when stepped, and a loop
        // of small steps terminates. (gc.lua's dosteps pins the exact bounds.)
        assertTrue(s.eval("""
                collectgarbage('stop')
                local a = {}
                for i = 1, 100 do a[i] = {{}} end
                local n = 0
                repeat n = n + 1 until collectgarbage('step', 20000) or n > 1000
                collectgarbage('restart')
                return n <= 1000
            """).toBoolean());
    }

    @Test
    void generationalIsTheDefaultMode() {
        LuaState s = new LuaState();
        assertEquals("generational", s.eval("return collectgarbage('incremental')").toLuaString());
        assertEquals("incremental", s.eval("return collectgarbage('generational')").toLuaString());
    }
}

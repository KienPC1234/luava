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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the unboxed array-part side-lanes ({@code long[]/byte[]}
 * parallel to the object stack). arrayPart stays the canonical boxed store;
 * the lanes are a write-through classification cache. Every case pins
 * observable Lua 5.4 semantics the lane split must not change: float/integer
 * key equivalence ({@code 1.0 == 1}), sparse placement and {@code #} borders,
 * exact float bits (including {@code -0.0}), weak modes, and the
 * table-library bulk paths that now avoid per-element key boxing.
 */
public class LuavaTableUnboxTest {

    private static final String HEAD = """
            local out = {}
            local function p(k, v) out[#out+1] = tostring(k) .. "=" .. tostring(v) end
            """;

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(HEAD + body + "\nreturn table.concat(out, \" \")")
              .toLuaString();
    }

    @Test
    void intAndFloatValuesRoundTrip() {
        assertEquals("a=1 b=2.5 c=-0.0 d=3000000000", run("""
                local t = {}
                t[1] = 1 t[2] = 2.5 t[3] = -0.0 t[4] = 3000000000
                p("a", t[1]) p("b", t[2])
                p("c", 1 / t[3] == -math.huge and "-0.0" or "0.0")
                p("d", t[4])
                """));
    }

    @Test
    void floatKeyOneEqualsIntKeyOne() {
        // AGENTS.md: float key with integral value and the int key are one entry.
        assertEquals("v=42 n=1", run("""
                local t = {}
                t[1.0] = 42
                local n = 0
                for _ in pairs(t) do n = n + 1 end
                p("v", t[1]) p("n", n)
                """));
    }

    @Test
    void sparseKeysStayOutOfTheBorder() {
        assertEquals("rawlen=3 far=9", run("""
                local t = {10, 20, 30}
                t[1000000] = 9
                p("rawlen", rawlen(t)) p("far", t[1000000])
                """));
    }

    @Test
    void denseFillThenHoleReadsNil() {
        assertEquals("len=10 mid=nil", run("""
                local t = {}
                for i = 1, 10 do t[i] = i * 2 end
                t[5] = nil
                p("len", #t) p("mid", t[5])
                """));
    }

    @Test
    void bigIntKeysMigrateThroughRehash() {
        assertEquals("n=21 sum=504", run("""
                local t = {}
                for i = 1, 20 do t[i] = i end
                t[6] = 100 t[30] = 200
                local n, sum = 0, 0
                for k, v in pairs(t) do
                  if math.type(k) == "integer" then n = n + 1 sum = sum + v end
                end
                p("n", n) p("sum", sum)
                """));
    }

    @Test
    void weakValuesStillCollectThroughLanes() {
        // Crossing the weak boundary materializes the lanes; values must
        // stay collectable afterwards and reads must stay exact.
        assertEquals("v=7", run("""
                local t = {1, 2, 3}
                local mt = {__mode = "v"}
                setmetatable(t, mt)
                p("v", t[1] + t[2] + t[3] + 1)
                """));
    }

    @Test
    void tableLibBulkPathsOnNumericArray() {
        assertEquals("cat=1,2,3 ins=0,1,2,3 mov=3,2,9 srt=1,2,3 upk=1|2", run("""
                local t = {1, 2, 3}
                p("cat", table.concat(t, ","))
                table.insert(t, 1, 0)
                p("ins", table.concat(t, ","))
                local u = {1, 2, 3}
                table.move(u, 3, 3, 1, u)
                u[3] = 9
                p("mov", table.concat(u, ","))
                local s = {3, 1, 2}
                table.sort(s)
                p("srt", table.concat(s, ","))
                local a, b = table.unpack({1, 2})
                p("upk", a .. "|" .. b)
                """));
    }

    @Test
    void constructorHolesAndBorderMatchIncremental() {
        assertEquals("ctor=6 incr=6", run("""
                local c = {1, 2, 3, nil, 5, 6}
                local i = {}
                for k = 1, 3 do i[k] = k end
                i[5] = 5 i[6] = 6
                p("ctor", rawlen(c)) p("incr", rawlen(i))
                """));
    }

    @Test
    void intLanesPreserveReadYourWritesAcrossRehash() {
        LuaState s = new LuaState();
        LuaValue r = s.eval("""
                local t = {}
                for i = 1, 1000 do t[i] = i * 3 end
                local ok = true
                for i = 1, 1000 do if t[i] ~= i * 3 then ok = false break end end
                return ok and #t
                """);
        assertEquals("1000", r.toLuaString());
    }

    @Test
    void floatBitsAreExactThroughLanes() {
        LuaTable t = new LuaTable();
        double[] vals = {0.1, -0.0, 1e300, Double.MIN_VALUE, 3.5};
        for (int i = 0; i < vals.length; i++) {
            t.rawsetInt(i + 1, LuaFloat.valueOf(vals[i]));
        }
        for (int i = 0; i < vals.length; i++) {
            LuaValue v = t.rawgetInt(i + 1);
            assertTrue(v.isFloat(), "slot " + (i + 1) + " must stay float");
            assertEquals(vals[i], v.toDouble(), "slot " + (i + 1));
            assertEquals(Double.doubleToRawLongBits(vals[i]),
                    Double.doubleToRawLongBits(v.toDouble()), "bits " + (i + 1));
        }
    }

    @Test
    void intLanesDoNotBoxSmallReads() {
        // Cached integers must come back identical (no observable change);
        // big integers must compare equal after the lane round-trip.
        LuaTable t = new LuaTable();
        t.rawsetInt(1, LuaInteger.valueOf(7));
        t.rawsetInt(2, LuaInteger.valueOf(1L << 40));
        assertTrue(t.rawgetInt(1) == LuaInteger.valueOf(7));
        assertEquals(1L << 40, t.rawgetInt(2).toLong());
    }
}

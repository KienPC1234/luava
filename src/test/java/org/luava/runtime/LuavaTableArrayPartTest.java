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
 * Regression tests for the array part's growth rule, checked against PUC
 * Lua 5.4's observable answers.
 *
 * <p>Placement inside the table is not observable, but where the array part
 * ends up is: it decides {@code rawlen}, which border {@code #} finds, and
 * therefore the index {@code table.insert} writes to. Luava mirrors PUC's
 * {@code rehash} (ltable.c) — bucket {@code ceil(log2 k)}, the key being
 * inserted counted alongside the existing ones, and the loop bounded by the
 * total key count — because an earlier approximation got several of those
 * wrong. These cases pin the specific shapes where it showed.
 *
 * <p>Each case asserts a value PUC returns, taken from the reference
 * implementation rather than from Luava's own output.
 */
public class LuavaTableArrayPartTest {

    private static final String SCRIPT_HEAD = """
            local out = {}
            local function p(k, v) out[#out+1] = tostring(k) .. "=" .. tostring(v) end
            """;

    /** The dense-build fast path must not be reachable through a gap insert. */
    @Test
    void gapInsertDoesNotStretchTheArrayPastTheDensityTest() {
        // PUC: rehash picks 4 for keys {1,2,3,5}, so 5 stays hashed and the
        // border is 3. A version that let the retry append at size+1 after
        // growth reported 5 here.
        assertEquals("rawlen=3 #=3", run("""
                local t = {1, 2, 3}
                t[5] = 1
                p("rawlen", rawlen(t)) p("#", #t)
                """));
    }

    /** ... but a key inside the chosen size does join the array part. */
    @Test
    void keyInsideTheRehashSizeIsAbsorbed() {
        // 1..4 plus 6 is more than half of 8 slots, so the array grows to 8
        // and the hole at 5 is no longer the border. The pending key has to be
        // counted for this to happen: keys 1..4 alone never beat half of 8.
        assertEquals("rawlen=6 #=6", run("""
                local t = {}
                t[1] = 1 t[2] = 2 t[3] = 3 t[4] = 4
                t[6] = 6
                p("rawlen", rawlen(t)) p("#", #t)
                """));
    }

    /** A single far key must not drag the array part out to meet it. */
    @Test
    void distantKeyLeavesTheArrayPartAlone() {
        // The far key is still readable (it lives in the hash part) and the
        // border is still 20, so the value must survive the insert. What has
        // to be bounded is the array part: PUC's computesizes stops as soon
        // as the total key count can no longer fill half the candidate size,
        // so 20 small keys leave the array at 32. A cumulative scan over every
        // bucket instead kept climbing towards 1<<25.
        assertEquals("rawlen=20 #=20 far=far", run("""
                local t = {}
                for i = 1, 20 do t[i] = i end
                t[1 << 25] = "far"
                p("rawlen", rawlen(t)) p("#", #t) p("far", t[1 << 25])
                """));
    }

    /**
     * Two-sided fill. Interleaving the odd and even writes gives the same
     * border as filling sequentially: 10, not the 5 the halfway point would
     * suggest, and not a smaller array-part size either.
     */
    @Test
    void interleavedFillFindsTheSameBorder() {
        assertEquals("rawlen=10 #=10", run("""
                local t = {}
                for i = 1, 5 do t[i * 2 - 1] = i end
                for i = 1, 5 do t[i * 2] = i end
                p("rawlen", rawlen(t)) p("#", #t)
                """));
    }

    /** Descending fill must land the same as ascending. */
    @Test
    void reverseFillMatchesForwardFill() {
        assertEquals("fwd=8 rev=8", run("""
                local f = {}
                for i = 1, 8 do f[i] = i end
                local r = {}
                for i = 8, 1, -1 do r[i] = i end
                p("fwd", rawlen(f)) p("rev", rawlen(r))
                """));
    }

    /** A hole-free table's border is unique, so insert must land at n+1. */
    @Test
    void insertAppendsAtTheUniqueBorder() {
        assertEquals("pos=13 len=13", run("""
                local t = {}
                for i = 1, 12 do t[i] = i end
                table.insert(t, "x")
                local pos = 0
                for i = 1, 32 do if t[i] == "x" then pos = i break end end
                p("pos", pos) p("len", #t)  -- PUC: 13 and 13
                """));
    }

    /**
     * Keys at 2, 4, ..., 16 with no key 1. The array part covers the whole
     * range, but {@code rawlen} is a border search and {@code t[1]} is nil, so
     * both engines answer 0. This pins that a strided build does not silently
     * report the array-part size instead.
     */
    @Test
    void stridedKeysDoNotReportTheArraySize() {
        assertEquals("rawlen=0 #=0", run("""
                local t = {}
                for i = 1, 8 do t[i * 2] = i end
                p("rawlen", rawlen(t)) p("#", #t)
                """));
    }

    /** Deleting never shrinks the array part, matching PUC. */
    @Test
    void deletionNeverShrinksTheArrayPart() {
        assertEquals("rawlen=2 #=2", run("""
                local t = {}
                for i = 1, 10 do t[i] = i end
                for i = 10, 3, -1 do t[i] = nil end
                p("rawlen", rawlen(t)) p("#", #t)
                """));
    }

    /** A constructor with a hole and the same table built statement by
     * statement must land on the same border. */
    @Test
    void constructorWithHoleMatchesIncrementalBuild() {
        assertEquals("ctor=6 incr=6", run("""
                local c = {1, 2, 3, 4, nil, 6}
                local i = {}
                for k = 1, 4 do i[k] = k end
                i[6] = 6
                p("ctor", rawlen(c)) p("incr", rawlen(i))
                """));
    }

    /**
     * Growth must not lose or duplicate a key that shadows the array part.
     * The 20 keys plus 30 give 21 integer keys, and the sum proves no key was
     * counted twice (a duplicate would inflate n) or dropped.
     */
    @Test
    void rehashKeepsEveryKeyExactlyOnce() {
        assertEquals("n=21 sum=504", run("""
                local t = {}
                for i = 1, 20 do t[i] = i end
                t[6] = 100 t[30] = 200
                local n, sum = 0, 0
                for k, v in pairs(t) do
                  if math.type(k) == "integer" then n = n + 1 sum = sum + v end
                end
                p("n", n) p("sum", sum)  -- PUC: 21 and 504
                """));
    }

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(SCRIPT_HEAD + body + "\nreturn table.concat(out, \" \")")
             .toLuaString();
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.luava.frontend.ast.AstNode;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.jit.JitCompiler;

/**
 * Adversarial regression and differential fuzz suite for runtime, table,
 * metatable, condition-lowering, coroutine, proto-cache, and tiered JIT
 * optimizations, cross-checked across Interpreter, Sync JIT, Guarded JIT,
 * and PUC-Rio Lua 5.4 when available on {@code PATH}.
 */
public class RuntimeJitDifferentialFuzzTest {

    private static final String PUC_LUA_BIN = detectPucLua54();

    private static String detectPucLua54() {
        String configured = System.getProperty("luava.test.luaBin");
        String[] candidates = configured != null && !configured.isBlank()
                ? new String[] {configured, "lua5.4", "lua"}
                : new String[] {"lua5.4", "lua"};
        for (String bin : candidates) {
            try {
                Process p = new ProcessBuilder(bin, "-v")
                        .redirectErrorStream(true)
                        .start();
                if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    if (out.contains("Lua 5.4")) {
                        return bin;
                    }
                }
            } catch (IOException | InterruptedException ignored) {
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return null;
    }

    private static void prewarmTree(LuaState state) {
        LuaTable globals = state.getGlobals();
        globals.forEach((k, v) -> {
            if (v instanceof LuaClosure lc) {
                prewarmProtoClosure(lc, state);
            }
        });
    }

    private static void prewarmProtoClosure(LuaClosure lc, LuaState state) {
        JitCompiler.prewarm(lc, state);
        prewarmChildren(lc.proto, lc.env, state);
    }

    private static void prewarmChildren(LuaProto proto, LuaTable env, LuaState state) {
        for (LuaProto child : proto.protos) {
            org.luava.runtime.eval.Upvalue[] dummyUps =
                    new org.luava.runtime.eval.Upvalue[child.upvalues.length];
            for (int i = 0; i < dummyUps.length; i++) {
                dummyUps[i] = new org.luava.runtime.eval.Upvalue(child.upvalues[i].name, LuaNil.NIL);
            }
            LuaClosure childCl = new LuaClosure(child, dummyUps, env, state);
            JitCompiler.prewarm(childCl, state);
            prewarmChildren(child, env, state);
        }
    }

    private static String evalAllModes(String script) throws Exception {
        String interpOut;
        try (LuaState interp = new LuaState().jitEnabled(false)) {
            interpOut = interp.eval(script, "@diff_interp.lua").toLuaString();
        }
        String jitOut;
        try (LuaState jit = new LuaState().jitEnabled(true)) {
            LuaFunction fn = jit.compile(script, "@diff_jit.lua", jit.getGlobals());
            if (fn instanceof LuaClosure lc) {
                lc.setState(jit);
                prewarmProtoClosure(lc, jit);
            }
            jitOut = fn.call().toLuaString();
        }
        String guardedOut;
        try (LuaState guarded = new LuaState().jitEnabled(true).instructionLimit(2_000_000)) {
            LuaFunction fn = guarded.compile(script, "@diff_guarded.lua", guarded.getGlobals());
            if (fn instanceof LuaClosure lc) {
                lc.setState(guarded);
                prewarmProtoClosure(lc, guarded);
            }
            guardedOut = fn.call().toLuaString();
        }
        assertEquals(interpOut, jitOut, "Sync JIT diverged from interpreter");
        assertEquals(interpOut, guardedOut, "Guarded JIT diverged from interpreter");
        if (PUC_LUA_BIN != null) {
            String pucOut = runPucSingle(script);
            assertEquals(pucOut, jitOut, "Luava diverged from PUC Lua 5.4");
        }
        return jitOut;
    }

    private static String runPucSingle(String script) throws Exception {
        Path tmp = Files.createTempFile("luava-diff-", ".lua");
        try {
            String wrapped = "local function __entry()\n" + script + "\nend\n"
                    + "io.write(tostring(__entry()))\n";
            Files.writeString(tmp, wrapped, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(PUC_LUA_BIN, tmp.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(p.waitFor(10, TimeUnit.SECONDS), "PUC Lua timed out");
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.exitValue(), "PUC Lua failed: " + out);
            return out;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static List<String> runPucBatch(List<String> scripts) throws Exception {
        Path tmp = Files.createTempFile("luava-fuzz-batch-", ".lua");
        try {
            StringBuilder sb = new StringBuilder(scripts.size() * 256);
            for (int i = 0; i < scripts.size(); i++) {
                sb.append("do\n")
                        .append("  local ok, res = pcall(function()\n")
                        .append(scripts.get(i))
                        .append("\n  end)\n")
                        .append("  if ok then io.write('OK:', tostring(res), '\\n')\n")
                        .append("  else io.write('ERR\\n') end\n")
                        .append("end\n");
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Process p = new ProcessBuilder(PUC_LUA_BIN, tmp.toString())
                    .redirectErrorStream(true)
                    .start();
            assertTrue(p.waitFor(20, TimeUnit.SECONDS), "PUC Lua batch timed out");
            String raw = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, p.exitValue(), "PUC Lua batch failed: " + raw);
            String[] lines = raw.split("\n", -1);
            List<String> results = new ArrayList<>(scripts.size());
            for (int i = 0; i < scripts.size(); i++) {
                results.add(lines[i]);
            }
            return results;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void metatableSwapsProtectedMetatableAndCacheInvalidationMatchPuc() throws Exception {
        String script = """
                local writes = {}
                local mtA = {
                    __index = { tag = "A", step = function(self, x) return x + 10 end }
                }
                local mtB = {
                    __index = { tag = "B", step = function(self, x) return x * 3 end },
                    __newindex = function(t, k, v)
                        writes[#writes + 1] = k .. "=" .. tostring(v)
                        rawset(t, "_" .. k, v)
                    end
                }
                local mtFn = {
                    __index = function(t, k)
                        if k == "step" then
                            return function(self, x) return x - 5 end
                        end
                        return "FN:" .. tostring(k)
                    end
                }
                local mtLocked = {
                    __index = { tag = "LOCK", step = function(self, x) return x + 100 end },
                    __metatable = "sealed-meta"
                }

                local function drive(obj, n)
                    local acc = 0
                    for i = 1, n do
                        acc = acc + obj:step(i)
                    end
                    return acc .. ":" .. tostring(obj.tag)
                end

                local o = { own = 1 }
                setmetatable(o, mtA)
                local r1 = drive(o, 120)
                setmetatable(o, mtB)
                o.dyn = 42
                local r2 = drive(o, 120)
                setmetatable(o, mtFn)
                local r3 = drive(o, 20)
                setmetatable(o, nil)
                o.step = function(self, x) return x + 1 end
                o.tag = "PLAIN"
                local r4 = drive(o, 60)
                o.step = nil
                o.tag = nil
                setmetatable(o, mtLocked)
                local r5 = drive(o, 60)
                local metaView = getmetatable(o)
                local okChange, errChange = pcall(setmetatable, o, mtA)

                -- String receiver SelfCache invalidation when string.__index mutates and restores
                local strIndex = getmetatable("").__index
                strIndex.__tmpCustom = function(s, k) return #s + k end
                local function callStr(s, n)
                    local total = 0
                    for i = 1, n do
                        total = total + s:__tmpCustom(i)
                    end
                    return total
                end
                local s1 = callStr("abcd", 100)
                strIndex.__tmpCustom = function(s, k) return (#s * 10) + k end
                local s2 = callStr("abcd", 100)
                strIndex.__tmpCustom = nil
                local okRemoved = pcall(callStr, "abcd", 2)

                return table.concat({
                    r1, r2, r3, r4, r5,
                    tostring(o._dyn), table.concat(writes, ";"),
                    tostring(metaView), tostring(okChange),
                    tostring(tostring(errChange):find("protected metatable") ~= nil),
                    tostring(s1), tostring(s2), tostring(okRemoved)
                }, "|")
                """;
        String res = evalAllModes(script);
        assertTrue(res.contains("sealed-meta|false|true"));
    }

    @Test
    void weakAndFinalizerMetatableTransitionsUnwrapCleanlyAndMatchPuc() throws Exception {
        String script = """
                local finalized = 0
                local resurrected = nil
                local gcMt = {
                    __gc = function(self)
                        finalized = finalized + 1
                        if self.id == 3 then
                            resurrected = self
                        end
                    end
                }

                local function makeObj(id, mt)
                    local t = { id = id, payload = id * 10 }
                    return setmetatable(t, mt)
                end

                local plainMt = { __index = { kind = "plain" } }
                local sumPlain = 0
                for i = 1, 90 do
                    local p = makeObj(i, plainMt)
                    sumPlain = sumPlain + p.payload
                end
                do
                    for i = 1, 5 do
                        local g = makeObj(i, gcMt)
                        assert(g.id == i)
                    end
                end
                collectgarbage()
                collectgarbage()

                -- Transition inline/array/hash table: strong -> weak kv -> strong nil -> ephemeron k -> dynamic __mode
                local anchorKey = { name = "kLive" }
                local anchorKeyVal = { kept = 55 }
                local anchorVal = { num = 777 }
                local anchorArr = { arrNum = 888 }
                local box = { anchorArr, a = anchorVal, b = { dead = true } }
                box[anchorKey] = anchorKeyVal
                setmetatable(box, { __mode = "kv" })
                do
                    local ephemeralKey = { name = "kDead" }
                    box[ephemeralKey] = { kept = 99 }
                    box.c = { gone = 123 }
                    box[2] = { goneArr = 456 }
                end
                collectgarbage()
                collectgarbage()

                -- Switch back to strong (nil and plain metatable): WeakVal/WeakKey must unwrap cleanly
                setmetatable(box, nil)
                local function readStrong(t, kObj)
                    local s = 0
                    for i = 1, 80 do
                        s = s + t[1].arrNum + t.a.num + t[kObj].kept
                    end
                    return s
                end
                local strongSum = readStrong(box, anchorKey)
                local deadCleared = (box.c == nil and box[2] == nil)

                -- Ephemeron (__mode = "k") where live key keeps unanchored value alive, then unwraps to strong
                local eph = setmetatable({}, { __mode = "k" })
                eph[anchorKey] = { ephKept = 321 }
                do
                    eph[{ deadKey = 1 }] = { ephGone = 654 }
                end
                collectgarbage()
                collectgarbage()
                setmetatable(eph, {})
                local ephOk = (eph[anchorKey].ephKept == 321)

                -- Dynamic __mode mutation after setmetatable
                local dynMt = {}
                local dynTbl = setmetatable({ live = anchorVal }, dynMt)
                do
                    dynTbl.dropMe = { z = 1 }
                end
                dynMt.__mode = "v"
                collectgarbage()
                collectgarbage()
                local dynDropped = (dynTbl.dropMe == nil and dynTbl.live.num == 777)

                return table.concat({
                    tostring(sumPlain),
                    tostring(finalized == 5),
                    tostring(resurrected ~= nil and resurrected.id == 3),
                    tostring(type(box[1])),
                    tostring(type(box.a)),
                    tostring(strongSum),
                    tostring(deadCleared),
                    tostring(ephOk),
                    tostring(dynDropped)
                }, "|")
                """;
        String res = evalAllModes(script);
        assertEquals("40950|true|true|table|table|137600|true|true|true", res);
    }

    @Test
    void tableConstructorsNilHolesDuplicateKeysMixedHashAndEscapeDeoptMatchPuc() throws Exception {
        String script = """
                -- 1) 6+ canonical fields (HashMap pre-sized) with duplicate keys and nil overwrite
                local function buildLargeRecord(x)
                    return {
                        a = x,
                        b = x + 1,
                        c = x + 2,
                        d = x + 3,
                        e = x + 4,
                        f = x + 5,
                        b = x + 100,
                        d = nil
                    }
                end

                -- 2) Mixed [0]/[-1] integer hash keys + 1..4 canonical string keys in one constructor
                local function buildMixed(x)
                    return {
                        [0] = x * 10,
                        [-1] = x * 20,
                        a = x + 1,
                        b = x + 2,
                        c = x + 3,
                        d = x + 4
                    }
                end

                -- 3) Array holes + explicit index override + table.insert border check
                local function buildHoles(x)
                    local t = { x, nil, x + 2, [1] = x + 50, 99 }
                    table.insert(t, 777)
                    return rawlen(t), t[1], tostring(t[2]), t[3], t[4], t[5]
                end

                -- 4) Escaping table before deopt must NOT duplicate side effects
                local escapedLog = {}
                local addMeta = {
                    __add = function(lhs, rhs)
                        return lhs.v + rhs
                    end
                }
                local function buildEscaping(x, y)
                    local t = { a = x }
                    escapedLog[#escapedLog + 1] = t
                    t.b = y + 10
                    return t
                end

                -- 5) Unescaped fresh table inside pure callee that deopts mid-constructor on metamethod/error
                local function buildPureThenDeopt(x, y)
                    local t = { a = x, b = y + 5, c = x * 2 }
                    return t
                end
                local function callerPure(x, y)
                    local keepLeft = x + 1000
                    local obj = buildPureThenDeopt(x, y)
                    return keepLeft + obj.a + obj.b + obj.c
                end

                local sumLarge = 0
                local pairCountLarge = 0
                for i = 1, 90 do
                    local r = buildLargeRecord(i)
                    sumLarge = sumLarge + r.a + r.b + r.c + r.e + r.f + (r.d or 0)
                    if i == 90 then
                        for _, _ in pairs(r) do pairCountLarge = pairCountLarge + 1 end
                    end
                end

                local sumMixed = 0
                local mixedKeys = 0
                for i = 1, 90 do
                    local m = buildMixed(i)
                    sumMixed = sumMixed + m[0] + m[-1] + m.a + m.b + m.c + m.d
                    if i == 90 then
                        for _, _ in pairs(m) do mixedKeys = mixedKeys + 1 end
                    end
                end

                local hl, h1, h2, h3, h4, h5 = buildHoles(10)

                for i = 1, 80 do
                    buildEscaping(i, i)
                end
                local escMeta = buildEscaping(99, setmetatable({ v = 50 }, addMeta))
                local okEscErr = pcall(buildEscaping, 100, "not_num")

                local pureSum = 0
                for i = 1, 80 do
                    pureSum = pureSum + callerPure(i, i)
                end
                local pureMeta = callerPure(7, setmetatable({ v = 30 }, addMeta))
                local okPureErr = pcall(callerPure, 8, {})

                return table.concat({
                    tostring(sumLarge), tostring(pairCountLarge),
                    tostring(sumMixed), tostring(mixedKeys),
                    tostring(hl), tostring(h1), h2, tostring(h3), tostring(h4), tostring(h5),
                    tostring(#escapedLog), tostring(escMeta.a), tostring(escMeta.b), tostring(okEscErr),
                    tostring(pureSum), tostring(pureMeta), tostring(okPureErr)
                }, "|")
                """;
        String res = evalAllModes(script);
        assertTrue(res.contains("|5|") && res.contains("|6|") && res.contains("|82|99|60|false|"));
    }

    @Test
    void nestedShortCircuitConditionsAndOrderMetamethodsMatchPuc() throws Exception {
        String script = """
                local trace = {}
                local mt = {
                    __eq = function(a, b)
                        trace[#trace + 1] = "eq:" .. a.id .. ":" .. b.id
                        return a.v == b.v
                    end,
                    __lt = function(a, b)
                        trace[#trace + 1] = "lt:" .. a.id .. ":" .. b.id
                        return a.v < b.v
                    end,
                    __le = function(a, b)
                        trace[#trace + 1] = "le:" .. a.id .. ":" .. b.id
                        return a.v <= b.v
                    end
                }
                local function box(id, v)
                    return setmetatable({ id = id, v = v }, mt)
                end

                local function evalBranch(a, b, c, flag, str)
                    local guardBefore = 111
                    local hit = 0
                    if ((not (false or nil)) and ((a < b and not (b <= c)) or (a == c and flag)))
                            or (not flag and (str ~= "skip" and (true and c >= a))) then
                        local innerKeep = 222
                        hit = guardBefore + innerKeep
                    else
                        hit = guardBefore - 11
                    end
                    local valExpr = ((a == b and "EQ") or (not (a < b) and "GE") or (flag and "FL") or "FALL")
                    return hit, valExpr, guardBefore
                end

                local out = {}
                for i = 1, 80 do
                    local h, v, g = evalBranch(i, i + 2, i + 5, (i % 2 == 0), (i % 3 == 0) and "skip" or "go")
                    if i == 1 or i == 40 or i == 80 then
                        out[#out + 1] = h .. ":" .. v .. ":" .. g
                    end
                end

                local x1, x2, x3 = box("A", 10), box("B", 20), box("C", 10)
                local mh1, mv1, mg1 = evalBranch(x1, x2, x3, false, "skip")
                local mh2, mv2, mg2 = evalBranch(x2, x1, x3, true, "go")

                return table.concat(out, ",") .. "|"
                        .. mh1 .. ":" .. mv1 .. ":" .. mg1 .. "|"
                        .. mh2 .. ":" .. mv2 .. ":" .. mg2 .. "|"
                        .. table.concat(trace, ",")
                """;
        String res = evalAllModes(script);
        assertTrue(res.contains("lt:A:B,le:B:C") && res.contains(":111"));
    }

    @Test
    void coroutinesInterleavedWithJitHooksAndGuardsMatchPuc() throws Exception {
        String script = """
                local function worker(seed)
                    local a = coroutine.yield(nil)
                    local b = coroutine.yield(false)
                    local c = coroutine.yield(seed + a)
                    local d = coroutine.yield(1.5 + c)
                    local e, f = coroutine.yield({ sum = seed + d }, "two")
                    return e + (f or 0)
                end

                local function runCaller(rounds)
                    local total = 0.0
                    for r = 1, rounds do
                        local co = coroutine.create(worker)
                        local ok1, v1 = coroutine.resume(co, r)
                        assert(ok1 and v1 == nil)
                        local ok2, v2 = coroutine.resume(co, 10)
                        assert(ok2 and v2 == false)
                        local ok3, v3 = coroutine.resume(co, 20)
                        assert(ok3 and v3 == r + 10)
                        -- Inspect suspended stack frame while lazy yield frame is pending
                        local info = debug.getinfo(co, 1, "Sl")
                        assert(info ~= nil and info.currentline > 0)
                        local tb = debug.traceback(co)
                        assert(type(tb) == "string" and #tb > 0)
                        local ok4, v4 = coroutine.resume(co, 4)
                        assert(ok4 and v4 == 5.5)
                        local ok5, tbl, tag = coroutine.resume(co, 6)
                        assert(ok5 and tbl.sum == r + 6 and tag == "two")
                        local ok6, fin = coroutine.resume(co, 100, 23)
                        assert(ok6 and fin == 123)
                        total = total + v3 + v4 + tbl.sum + fin
                    end
                    return total
                end

                local baseTotal = runCaller(60)

                -- coroutine.wrap in generic-for yielding nil-adjacent falsy/int/object values
                local wrapAcc = 0
                local gen = coroutine.wrap(function()
                    for i = 1, 10 do
                        coroutine.yield(i * i)
                    end
                end)
                for v in gen do
                    wrapAcc = wrapAcc + v
                end

                -- Hook armed mid-execution around coroutine resume
                local hookHits = 0
                debug.sethook(function() hookHits = hookHits + 1 end, "", 25)
                local hookedTotal = runCaller(5)
                debug.sethook()

                return string.format("%.1f|%d|%.1f|%s", baseTotal, wrapAcc, hookedTotal, tostring(hookHits > 0))
                """;
        String res = evalAllModes(script);
        assertEquals("12330.0|385|752.5|true", res);

        // Verify instructionLimit and explicit cancellation still preempt JIT + coroutine resume loops
        String infCo = """
                local co = coroutine.create(function()
                    while true do
                        coroutine.yield(1)
                    end
                end)
                local function spin()
                    local s = 0
                    for i = 1, 10000000 do
                        local ok, v = coroutine.resume(co)
                        if ok then s = s + v end
                    end
                    return s
                end
                return spin()
                """;
        try (LuaState limited = new LuaState().jitEnabled(true).instructionLimit(25_000)) {
            LuaException ex = assertThrows(LuaException.class, () -> limited.eval(infCo));
            assertTrue(ex.getMessage().contains("instruction limit"));
        }
        try (LuaState timed = new LuaState().jitEnabled(true).timeout(Duration.ofMillis(60))) {
            LuaException ex = assertThrows(LuaException.class, () -> timed.eval(infCo));
            assertTrue(ex.getMessage().contains("timed out"));
        }
    }

    @Test
    void loopTypeTransitionsOverflowAndAliasingDeoptsMatchPuc() throws Exception {
        String script = """
                -- 1) Promoted loop accumulator: int -> float -> metamethod object mid-loop deopt
                local function sumDynamic(arr, n, init)
                    local acc = init
                    for i = 1, n do
                        acc = acc + arr[i]
                    end
                    return acc
                end

                local intArr = {}
                for i = 1, 64 do intArr[i] = i end
                local warm = 0
                for k = 1, 80 do
                    warm = warm + sumDynamic(intArr, 64, 0)
                end

                local mixedArr = {}
                for i = 1, 64 do
                    mixedArr[i] = (i == 30) and 0.5 or i
                end
                local floatRes = sumDynamic(mixedArr, 64, 0)

                local metaAdd = {
                    __add = function(a, b)
                        local av = type(a) == "table" and a.v or a
                        local bv = type(b) == "table" and b.v or b
                        return setmetatable({ v = av + bv + 1000 }, {
                            __add = function(x, y)
                                local xv = type(x) == "table" and x.v or x
                                local yv = type(y) == "table" and y.v or y
                                return xv + yv
                            end
                        })
                    end
                }
                local metaArr = {}
                for i = 1, 64 do
                    metaArr[i] = (i == 45) and setmetatable({ v = 45 }, metaAdd) or i
                end
                local metaRes = sumDynamic(metaArr, 64, 0)

                -- 2) Fused IntArith + SetTable deopting when target table has __newindex or is aliased
                local function fillAndAccum(dst, src, n)
                    local acc = 0
                    for i = 1, n do
                        dst[i] = i * 3 + 1
                        acc = acc + src[i]
                    end
                    return acc
                end

                local plainA, plainB = {}, {}
                for i = 1, 50 do plainB[i] = i end
                for k = 1, 80 do
                    fillAndAccum(plainA, plainB, 50)
                end
                -- Alias dst == src: src[i] must observe the write dst[i] performed on the same iteration!
                local aliased = {}
                local aliasSum = fillAndAccum(aliased, aliased, 50)

                -- Proxy table with __newindex and __index passed to fused kernel
                local backing = {}
                local proxyWrites = 0
                local proxy = setmetatable({}, {
                    __newindex = function(_, k, v)
                        proxyWrites = proxyWrites + 1
                        backing[k] = v + 2
                    end,
                    __index = function(_, k)
                        return backing[k]
                    end
                })
                local proxySum = fillAndAccum(proxy, proxy, 20)

                -- 3) 64-bit two's complement integer overflow inside promoted loop
                local function overflowLoop(n)
                    local acc = 0x7ffffffffffffffd
                    for i = 1, n do
                        acc = acc + 2
                    end
                    return acc
                end
                local ov = 0
                for k = 1, 70 do ov = overflowLoop(4) end

                return string.format("%d|%.1f|%d|%d|%d|%d|%d",
                        warm, floatRes, metaRes, aliasSum, proxySum, proxyWrites, ov)
                """;
        String res = evalAllModes(script);
        assertTrue(res.contains("|2050.5|3080|3875|690|20|"));
    }

    @Test
    void seededDifferentialFuzz2600ExecutionsAcrossJitModesAndPuc() throws Exception {
        int programCount = 650;
        Random rng = new Random(0x4C554156413534L);
        List<String> scripts = new ArrayList<>(programCount);
        for (int i = 0; i < programCount; i++) {
            scripts.add(generateFuzzProgram(rng, i));
        }

        List<String> pucExpected = PUC_LUA_BIN != null ? runPucBatch(scripts) : null;
        int totalExecutions = 0;

        try (LuaState interp = new LuaState().jitEnabled(false);
             LuaState jitSync = new LuaState().jitEnabled(true);
             LuaState jitGuarded = new LuaState().jitEnabled(true).instructionLimit(500_000)) {

            for (int i = 0; i < programCount; i++) {
                String src = scripts.get(i);
                String name = "@fuzz_" + i + ".lua";

                String rInterp = evalOutcome(interp, src, name, false);
                totalExecutions++;

                String rJit = evalOutcome(jitSync, src, name, true);
                totalExecutions++;

                jitGuarded.instructionLimit(500_000);
                String rGuarded = evalOutcome(jitGuarded, src, name, true);
                totalExecutions++;

                assertEquals(rInterp, rJit, "Sync JIT mismatch at fuzz case #" + i + ":\n" + src);
                assertEquals(rInterp, rGuarded, "Guarded JIT mismatch at fuzz case #" + i + ":\n" + src);
                if (pucExpected != null) {
                    totalExecutions++;
                    assertEquals(pucExpected.get(i), rJit, "PUC 5.4 mismatch at fuzz case #" + i + ":\n" + src);
                } else {
                    // Run a second warm invocation on Sync JIT when PUC binary is absent in CI
                    String rJitWarm2 = evalOutcome(jitSync, src, name, false);
                    totalExecutions++;
                    assertEquals(rInterp, rJitWarm2, "Warm JIT mismatch at fuzz case #" + i);
                }
            }
        }
        assertTrue(totalExecutions >= 2600, "Expected at least 2600 differential executions, ran " + totalExecutions);
    }

    private static String evalOutcome(LuaState state, String script, String chunkName, boolean prewarm) {
        try {
            LuaFunction fn = state.compile(script, chunkName, state.getGlobals());
            if (fn instanceof LuaClosure lc) {
                lc.setState(state);
                if (prewarm) {
                    prewarmProtoClosure(lc, state);
                }
            }
            LuaValue res = fn.call();
            if (res instanceof Varargs va) {
                res = va.arg(1);
            }
            return "OK:" + res.toLuaString();
        } catch (LuaException le) {
            return "ERR";
        }
    }

    private static String generateFuzzProgram(Random rng, int idx) {
        int a = rng.nextInt(15) - 5;
        int b = rng.nextInt(15) + 1;
        int trip = 6 + rng.nextInt(18);
        int fieldCount = 1 + rng.nextInt(6);
        boolean useMeta = rng.nextBoolean();
        boolean swapMetaMidLoop = rng.nextInt(3) == 0;
        boolean useCoro = rng.nextInt(4) == 0;
        boolean injectFloatMidLoop = rng.nextInt(3) == 0;
        boolean zeroKey = rng.nextBoolean();

        StringBuilder sb = new StringBuilder(512);
        sb.append("local mt1 = { __index = function(_, k) return type(k) == 'number' and k or 3 end }\n");
        sb.append("local mt2 = { __index = { x = ").append(b + 2).append(", f0 = 7, f1 = 9 } }\n");
        sb.append("local function makeTbl(v)\n  local t = { ");
        if (zeroKey) {
            sb.append("[0] = v + 1, ");
        }
        for (int f = 0; f < fieldCount; f++) {
            sb.append("f").append(f).append(" = v + ").append(f).append(", ");
        }
        if (rng.nextInt(3) == 0) {
            sb.append("f0 = v * 2, ");
        }
        sb.append("v, nil, v + 5 }\n");
        if (useMeta) {
            sb.append("  setmetatable(t, mt1)\n");
        }
        sb.append("  return t\nend\n");

        if (useCoro) {
            sb.append("local co = coroutine.create(function(start)\n")
                    .append("  local cur = start\n")
                    .append("  for k = 1, ").append(trip + 2).append(" do\n")
                    .append("    cur = coroutine.yield(cur + k)\n")
                    .append("  end\n")
                    .append("  return cur\n")
                    .append("end)\n");
        }

        sb.append("local function kernel(n, seed)\n")
                .append("  local acc = seed\n")
                .append("  local arr = {}\n")
                .append("  for i = 1, n do\n")
                .append("    arr[i] = i * ").append(1 + (idx % 5)).append(" + ").append(a).append("\n")
                .append("  end\n");
        if (injectFloatMidLoop) {
            sb.append("  arr[").append(Math.max(1, trip / 2)).append("] = 1.5\n");
        }
        sb.append("  for i = 1, n do\n")
                .append("    local rec = makeTbl(i)\n");
        if (swapMetaMidLoop) {
            sb.append("    if (i % 2 == 0 and not (i == 4 and false)) or i == n then setmetatable(rec, mt2) end\n");
        }
        sb.append("    local cond = ((not (i < 2)) and (arr[i] ~= 0 or rec.f0 == i)) or (i == 1 and true)\n")
                .append("    if cond then\n")
                .append("      acc = acc + arr[i] + (rec.f0 or 0) + (rec.x or 1)\n")
                .append("    else\n")
                .append("      acc = acc - (rec[1] or 0)\n")
                .append("    end\n");
        if (useCoro) {
            sb.append("    local ok, cv = coroutine.resume(co, i)\n")
                    .append("    if ok and cv then acc = acc + cv end\n");
        }
        sb.append("  end\n")
                .append("  return acc, rawlen(arr)\n")
                .append("end\n")
                .append("local r1, r2 = kernel(").append(trip).append(", ").append(a).append(")\n")
                .append("if type(r1) == 'number' and math.type(r1) == 'float' then\n")
                .append("  return string.format('%.2f:%d', r1, r2)\n")
                .append("end\n")
                .append("return tostring(r1) .. ':' .. tostring(r2)\n");
        return sb.toString();
    }

    @Test
    void protoRetainsZeroAstAndProtoCacheHonorsByteAndHotColdBounds() {
        for (Field f : LuaProto.class.getDeclaredFields()) {
            assertFalse(AstNode.class.isAssignableFrom(f.getType()),
                    "LuaProto must not retain AST node field: " + f.getName());
            assertFalse("rawSource".equals(f.getName()),
                    "LuaProto must not retain rawSource field");
        }

        // Compile a ~4.5 MB comment-heavy source: compiled chunk must not retain the 4.5 MB string
        String padLine = "-- 0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789\n";
        int lines = 60_000;
        StringBuilder huge = new StringBuilder(lines * padLine.length() + 64);
        for (int i = 0; i < lines; i++) {
            huge.append(padLine);
        }
        huge.append("return 42\n");
        String hugeSrc = huge.toString();
        assertTrue(hugeSrc.length() > 4_400_000);

        try (LuaState.CompiledChunk chunk = LuaState.compileChunk(hugeSrc, "@huge_4mb.lua")) {
            assertTrue(chunk.proto.estimatedRetainedBytes() < 2048L,
                    "CompiledChunk proto retained unexpected weight: " + chunk.proto.estimatedRetainedBytes());
        }

        // Verify string.dump/load round-trips inner & stripped closures without AST/rawSource
        try (LuaState st = new LuaState().jitEnabled(true)) {
            LuaValue roundTrip = st.eval("""
                    local up = 10
                    local function outer(base)
                        local function inner(x)
                            return x * 3 + base + up
                        end
                        return inner
                    end
                    local fn = outer(5)
                    local dumped = string.dump(fn)
                    local dumpedStripped = string.dump(fn, true)
                    local loaded = assert(load(dumped))
                    local loadedStripped = assert(load(dumpedStripped))
                    debug.setupvalue(loaded, 1, 7)
                    debug.setupvalue(loaded, 2, 11)
                    debug.setupvalue(loadedStripped, 1, 7)
                    debug.setupvalue(loadedStripped, 2, 11)
                    return tostring(loaded(4)) .. ":" .. tostring(loadedStripped(4))
                            .. ":" .. tostring(debug.getinfo(loadedStripped).source)
                    """);
            assertEquals("30:30:=?", roundTrip.toLuaString());

            // Tier up a hot handler, then churn 2,000 cold ~4 KB chunks + one 2.2 MB chunk
            String hotHandler = "local function h(n) local s = 0 for i = 1, n do s = s + i end return s end return h(64)";
            for (int i = 0; i < 65; i++) {
                assertEquals(2080L, st.eval(hotHandler, "@hot_handler.lua").toLong());
            }
            LuaClosure hotClosure = (LuaClosure) st.compile(hotHandler, "@hot_handler.lua", st.getGlobals());
            LuaProto hotChild = hotClosure.proto.protos[0];
            if (hotChild.jitCode == null) {
                JitCompiler.prewarm(new LuaClosure(hotChild, new org.luava.runtime.eval.Upvalue[0], st.getGlobals(), st), st);
            }
            assertNotNull(hotChild.jitCode, "Hot handler child proto should be compiled");

            // Oversized 2.2 MB one-off eval must execute accurately without entering protoCache
            int beforeCount = st.cachedProtoCount();
            assertEquals(42L, st.eval(hugeSrc, "@one_off_huge.lua").toLong());
            assertEquals(beforeCount, st.cachedProtoCount(), "Oversized one-off chunk should bypass protoCache");

            String fillerComment = "-- " + "x".repeat(3800) + "\n";
            for (int i = 0; i < 2000; i++) {
                String coldScript = fillerComment + "return " + i;
                assertEquals(i, st.eval(coldScript, "@cold_" + i + ".lua").toLong());
                if ((i & 63) == 63) {
                    // Periodically touch the hot handler as in realistic multi-route traffic
                    assertEquals(2080L, st.eval(hotHandler, "@hot_handler.lua").toLong());
                }
            }
            assertTrue(st.cachedProtoCount() <= 256, "protoCache count exceeded bound: " + st.cachedProtoCount());
            assertTrue(st.cachedProtoBytes() <= 8L * 1024L * 1024L,
                    "protoCache bytes exceeded 8 MiB bound: " + st.cachedProtoBytes());
            LuaClosure hotAfter = (LuaClosure) st.compile(hotHandler, "@hot_handler.lua", st.getGlobals());
            assertEquals(hotClosure.proto, hotAfter.proto, "Hot handler proto should survive cold churn");
            assertNotNull(hotAfter.proto.protos[0].jitCode, "Hot handler JIT kernel should remain intact");
        }
    }
}

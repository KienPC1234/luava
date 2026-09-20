/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.bytecode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BytecodeVMTest {

    @BeforeEach
    void setUp() {
        LuaState.USE_BYTECODE_VM = true;
    }

    @AfterEach
    void tearDown() {
        LuaState.USE_BYTECODE_VM = true;
    }

    @Test
    void testBasicArithmetic() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("return 10 + 20 * 3");
        assertEquals(70, res.toLong());
    }

    @Test
    void testLocalsAndAssignments() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = 5
            local b = 15
            local c = a + b
            return c
        """);
        assertEquals(20, res.toLong());
    }

    @Test
    void testIfBranch() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local x = 10
            local y = 0
            if x > 5 then
                y = 100
            else
                y = 200
            end
            return y
        """);
        assertEquals(100, res.toLong());
    }

    @Test
    void testWhileLoop() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local sum = 0
            local i = 1
            while i <= 10 do
                sum = sum + i
                i = i + 1
            end
            return sum
        """);
        assertEquals(55, res.toLong());
    }

    @Test
    void testForNumeric() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local sum = 0
            for i = 1, 10 do
                sum = sum + i
            end
            return sum
        """);
        assertEquals(55, res.toLong());
    }

    @Test
    void testFunctionsAndClosures() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function makeCounter()
                local count = 0
                return function()
                    count = count + 1
                    return count
                end
            end
            local c = makeCounter()
            c()
            c()
            return c()
        """);
        assertEquals(3, res.toLong());
    }

    @Test
    void testTableOperations() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local t = {10, 20, 30}
            t[4] = 40
            return t[1] + t[2] + t[3] + t[4]
        """);
        assertEquals(100, res.toLong());
    }

    @Test
    void testTailCallRecursion() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function sum(n, acc)
                if n == 0 then return acc end
                return sum(n - 1, acc + n)
            end
            return sum(1000, 0)
        """);
        assertEquals(500500, res.toLong());
    }

    @Test
    void testLogicalShortCircuit() {
        LuaState state = new LuaState();
        LuaValue res1 = state.eval("return false and 10");
        assertEquals(org.luava.runtime.LuaBoolean.FALSE, res1);

        LuaValue res2 = state.eval("return true and 10");
        assertEquals(10, res2.toLong());

        LuaValue res3 = state.eval("return nil or 42");
        assertEquals(42, res3.toLong());

        LuaValue res4 = state.eval("return 10 or 20");
        assertEquals(10, res4.toLong());

        LuaValue res5 = state.eval("""
            local sideEffect = 0
            local function f() sideEffect = 1 return 99 end
            local x = true or f()
            local y = false and f()
            return sideEffect
        """);
        assertEquals(0, res5.toLong());
    }

    @Test
    void testComparisonOperators() {
        LuaState state = new LuaState();
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, state.eval("return 5 ~= 10"));
        assertEquals(org.luava.runtime.LuaBoolean.FALSE, state.eval("return 5 ~= 5"));
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, state.eval("return 10 > 5"));
        assertEquals(org.luava.runtime.LuaBoolean.FALSE, state.eval("return 5 > 10"));
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, state.eval("return 10 >= 10"));
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, state.eval("return 10 >= 5"));
        assertEquals(org.luava.runtime.LuaBoolean.FALSE, state.eval("return 5 >= 10"));
    }

    @Test
    void testMultretAssignmentAndReturns() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function multi()
                return 10, 20, 30
            end
            local a, b, c = multi()
            return a + b * 2 + c * 3
        """);
        assertEquals(140, res.toLong()); // 10 + 40 + 90 = 140
    }

    @Test
    void testMultretFunctionArgs() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function multi()
                return 20, 30
            end
            local function add3(a, b, c)
                return a + b + c
            end
            return add3(10, multi())
        """);
        assertEquals(60, res.toLong());
    }

    @Test
    void testTableConstructorMultret() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function multi()
                return 20, 30, 40
            end
            local t = {10, multi()}
            return t[1] + t[2] + t[3] + t[4]
        """);
        assertEquals(100, res.toLong());
    }

    @Test
    void testStringConcat() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = "Hello"
            local b = " "
            local c = "World"
            return a .. b .. c
        """);
        assertEquals("Hello World", res.toLuaString());
    }

    @Test
    void testGotoForwardAndBackward() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local x = 0
            goto jump
            x = 999
            ::jump::
            x = x + 10
            return x
        """);
        assertEquals(10, res.toLong());

        LuaValue resLoop = state.eval("""
            local i = 0
            local sum = 0
            ::loop::
            i = i + 1
            sum = sum + i
            if i < 5 then
                goto loop
            end
            return sum
        """);
        assertEquals(15, resLoop.toLong());
    }

    @Test
    void testGotoScopeError() {
        LuaState state = new LuaState();
        org.junit.jupiter.api.Assertions.assertThrows(org.luava.runtime.LuaException.class, () -> {
            state.eval("""
                goto bad
                local x = 1
                ::bad::
                return x
            """);
        });
    }

    @Test
    void testToBeClosedVariable() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local closed = false
            local obj = setmetatable({}, {
                __close = function(self, err)
                    closed = true
                end
            })
            do
                local x <close> = obj
            end
            return closed
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void testConcurrentMultipleVMs() throws InterruptedException {
        int numThreads = 20;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(numThreads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(numThreads);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    LuaState state = new LuaState();
                    LuaValue res = state.eval("""
                        local function fib(n)
                            if n < 2 then return n end
                            return fib(n - 1) + fib(n - 2)
                        end
                        return fib(15)
                    """);
                    if (res.toLong() == 610) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();
        assertEquals(numThreads, successCount.get());
    }

    @Test
    void testCoroutineBasicYieldResume() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local co = coroutine.create(function(x)
                local y = coroutine.yield(x + 10)
                return y * 2
            end)
            local ok1, val1 = coroutine.resume(co, 5)
            local ok2, val2 = coroutine.resume(co, 20)
            return val1 + val2
        """);
        assertEquals(55, res.toLong()); // val1 = 15, val2 = 40 => 55
    }

    @Test
    void testCoroutineMultretYieldResume() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local co = coroutine.create(function(a, b)
                local x, y = coroutine.yield(a + 1, b + 2)
                return x * 10, y * 20
            end)
            local ok1, r1, r2 = coroutine.resume(co, 10, 20)
            local ok2, r3, r4 = coroutine.resume(co, 3, 4)
            return r1 + r2 + r3 + r4
        """);
        assertEquals(143, res.toLong()); // r1=11, r2=22, r3=30, r4=80 => 143
    }

    @Test
    void testCoroutineProducerConsumer() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function producer()
                return coroutine.wrap(function()
                    for i = 1, 5 do
                        coroutine.yield(i * 10)
                    end
                end)
            end
            local sum = 0
            local gen = producer()
            for v in gen do
                sum = sum + v
            end
            return sum
        """);
        assertEquals(150, res.toLong()); // 10 + 20 + 30 + 40 + 50 = 150
    }

    @Test
    void tableConstructorListValuesStayContiguous() {
        // Regression: list fields were compiled with allocReg(), but an
        // element that allocates its own temporaries (e.g. a global read like
        // `math.maxinteger` allocates a register for the table before the
        // GETFIELD) scattered the values across non-consecutive registers,
        // while SETLIST reads R[target+1 .. target+n]. That made
        // `{math.maxinteger, math.maxinteger, math.maxinteger}` store the
        // math table at index 2. Every element must land in its own slot.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local t = {math.maxinteger, math.maxinteger, math.maxinteger}
            return t[1] == math.maxinteger and t[2] == math.maxinteger
               and t[3] == math.maxinteger and #t == 3
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void tableConstructorMixesFieldsAndListValues() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local g = {x = 7}
            local t = {g.x, 1 + 1, ("abc"):len(), g.x, 9}
            return t[1] == 7 and t[2] == 2 and t[3] == 3 and t[4] == 7 and t[5] == 9 and #t == 5
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void builtinsReturnZeroValuesLikePuc() {
        // PUC's print/table.sort/table.insert/debug.sethook/debug.getupvalue
        // return zero values, observable via select('#', ...).
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = select('#', print())
            local b = select('#', table.sort({3, 1, 2}))
            local c = select('#', table.insert({}, 1))
            local d = select('#', debug.sethook())
            local e = select('#', debug.getupvalue(print, 99))
            local t = {print()}
            return a + b + c + d + e + #t
        """);
        assertEquals(0, res.toLong());
    }

    @Test
    void cTailCallKeepsCallerFrameVisible() {
        // PUC does not tail-call C functions: the Lua caller's frame survives
        // the C call, so a C builtin tail-called from f() still sees f() at
        // debug level 1. Regression: Luava used to replace the frame, making
        // debug.getlocal/getinfo report the wrong function.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function f()
                local marker = 7
                return debug.getlocal(1, 1)
            end
            local name, value = f()
            local function g() local x = 11 return debug.getinfo(1, "f").func end
            return name == "marker" and value == 7 and g() == g
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void cTailCallReturnHookStillFires() {
        // PUC emits return hooks for the C tail call, the enclosing Lua frame,
        // and the chunk frame (three returns for `local r = g()`).
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local function g() return tostring(5) end
            local log = {}
            debug.sethook(function(ev) log[#log+1] = ev end, "r")
            local r = g()
            debug.sethook()
            local count = 0
            for _ = 1, #log do count = count + 1 end
            return r == "5" and count == 3
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void utf8AndOsDateCoerceNumbersLikePuc() {
        // PUC uses luaL_checklstring / luaL_optlstring, so a number is accepted
        // where a string is expected: utf8.len(123) == 3, os.date(123) formats
        // the string "123". Regression: these used to raise a type error.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = utf8.len(123)
            local b = utf8.codepoint(123)
            local c = os.date(123, 0)
            local ok = pcall(utf8.len, {})
            return a == 3 and b == 49 and c == "123" and not ok
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void stringPackArgumentErrorsMatchPuc() {
        // luaL_checkinteger blames the argument index and reports a
        // non-integral number distinctly from a wrong type; a missing packed
        // argument reads as nil (PUC pushes a nil marker), not "no value".
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local m1 = select(2, pcall(string.pack, "<i2", 3.5))
            local m2 = select(2, pcall(string.pack, "<i4", "x"))
            local m3 = select(2, pcall(string.pack, "<i4"))
            return m1:find("bad argument #2", 1, true) ~= nil
               and m1:find("number has no integer representation", 1, true) ~= nil
               and m2:find("number expected, got string", 1, true) ~= nil
               and m3:find("number expected, got nil", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void ioReadBlamesFormatArgumentIndex() {
        // PUC g_read: `io.read('x')` blames #1, while `f:read('x')` blames #2
        // (self is #1). Regression: both reported a bare message.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local m1 = select(2, pcall(io.read, "x"))
            local f = io.open("/tmp/luava_io_read_test.txt", "w")
            f:write("line\\n")
            f:close()
            local g = io.open("/tmp/luava_io_read_test.txt", "r")
            local m2 = select(2, pcall(g.read, g, "x"))
            g:close()
            os.remove("/tmp/luava_io_read_test.txt")
            return m1:find("bad argument #1", 1, true) ~= nil
               and m2:find("bad argument #2", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void ioOpenFailureMessagesIncludeOsReason() {
        // PUC's opencheck raises "cannot open file '<name>' (<strerror>)";
        // io.input/io.lines must include the OS reason, not just the name.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local m = select(2, pcall(io.lines, "/nonexistent/luava_x"))
            return m:find("cannot open file", 1, true) ~= nil
               and m:find("No such file or directory", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void stringFormatOctalAndCheckOrderMatchPuc() {
        // %#o on zero prints "0" (C does not add a second zero). PUC also
        // converts the numeric argument before validating the format, so a
        // non-integral value blames the argument even for an invalid
        // specifier such as %#d.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = string.format("%#o", 0)
            local b = string.format("%#o", 8)
            local m1 = select(2, pcall(string.format, "%#d", 3.5))
            local m2 = select(2, pcall(string.format, "%#d", 5))
            return a == "0" and b == "010"
               and m1:find("number has no integer representation", 1, true) ~= nil
               and m2:find("invalid conversion specification", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void powerSpecialCasesFollowC99() {
        // PUC uses libm pow, which follows C99 Annex F: pow(1, y) == 1 for any
        // y (including NaN and infinities) and pow(-1, ±inf) == 1. Java's
        // Math.pow returns NaN for these, so the runtime special-cases them.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local inf = math.huge
            local nan = 0/0
            return (1 ^ inf) == 1 and (1 ^ -inf) == 1 and (1 ^ nan) == 1
               and ((-1) ^ inf) == 1 and ((-1) ^ -inf) == 1
               and (1.0 ^ nan) == 1
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void concatBlamesSecondOperandLikePuc() {
        // C luaG_concaterror blames the second operand when the first is
        // concatenable; arithmetic uses a different rule (luaG_opinterror).
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local m1 = select(2, pcall(function() return "abc" .. {} end))
            local m2 = select(2, pcall(function() return "abc" .. nil end))
            local m3 = select(2, pcall(function() return {} .. "abc" end))
            return m1:find("concatenate a table value", 1, true) ~= nil
               and m2:find("concatenate a nil value", 1, true) ~= nil
               and m3:find("concatenate a table value", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void numericStringsCoerceForCheckIntegerLikePuc() {
        // luaL_checkinteger (lua_tointegerx) coerces numeric strings, but the
        // VM's arithmetic/bitwise operators do not. Regression: string.char
        // and string.rep rejected numeric strings, and math.abs kept the
        // integer subtype where PUC yields a float.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local a = string.char("120")
            local b = string.rep("x", "3")
            local c = math.abs("120")
            local d = string.sub("abcd", "2", "3")
            local e = string.format("%d", "0x10")
            local f = table.concat({1, 2}, "-", "1", "2")
            local g = select("2", "a", "b", "c")
            local bits = pcall(function() return "3" & 1 end)
            return a == "x" and b == "xxx" and math.type(c) == "float" and c == 120.0
               and d == "bc" and e == "16" and f == "1-2" and g == "b"
               and not bits
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void packageSearchpathAndRequireMessagesMatchPuc() {
        // PUC pusherrornotfound joins path segments as "'\n\tno file '"
        // starting with "no file" (no leading separator); findloader adds the
        // "\n\t" prefix per searcher. Regression: Luava emitted a leading
        // separator in searchpath and concatenated require searcher messages
        // without separators.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local _, sp = package.searchpath("x", "./?.lua")
            local oldPath, oldCpath = package.path, package.cpath
            package.path = "?.lua;?/?"
            package.cpath = "?.so;?/init"
            local _, msg = pcall(require, "XXX_NOT_PRESENT")
            package.path, package.cpath = oldPath, oldCpath
            return sp == "no file './x.lua'" and msg ==
                "module 'XXX_NOT_PRESENT' not found:\\n\\tno field package.preload['XXX_NOT_PRESENT']"
                .. "\\n\\tno file 'XXX_NOT_PRESENT.lua'\\n\\tno file 'XXX_NOT_PRESENT/XXX_NOT_PRESENT'"
                .. "\\n\\tno file 'XXX_NOT_PRESENT.so'\\n\\tno file 'XXX_NOT_PRESENT/init'"
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void tonumberWithBaseRejectsHexPrefix() {
        // PUC's b_str2int never accepts a "0x" prefix when a base is given;
        // "0x10" with base 16 is not a valid numeral.
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            return tonumber("0x10", 16) == nil and tonumber("0X10", 16) == nil
               and tonumber("0x10") == 16 and tonumber("10", 16) == 16
               and tonumber("ff", 16) == 255
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void stringPacksizeVariableLengthErrorMatchesPuc() {
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local _, e = pcall(string.packsize, "z")
            return e:find("bad argument #1", 1, true) ~= nil
               and e:find("variable-length format", 1, true) ~= nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void gmatchIteratorRendersLikeAPlainFunction() {
        // PUC renders every function value as "function: 0x..."; the gmatch
        // iterator used to print "function: builtin@0x...".
        LuaState state = new LuaState();
        LuaValue res = state.eval("""
            local s = tostring(string.gmatch("a", "a"))
            return s:sub(1, 10) == "function: "
               and s:find("builtin", 1, true) == nil
        """);
        assertEquals(org.luava.runtime.LuaBoolean.TRUE, res);
    }

    @Test
    void debugSetmetatableCanRemoveStringMetatablePerState() {
        // debug.setmetatable("", nil) must actually clear the string
        // metatable (PUC), and the change must stay local to that state.
        LuaState s1 = new LuaState();
        assertEquals("true", s1.eval("return tostring(getmetatable('') ~= nil)").toLuaString());
        s1.eval("debug.setmetatable('', nil)");
        assertEquals("nil", s1.eval("return tostring(getmetatable(''))").toLuaString());
        // A fresh state is unaffected.
        LuaState s2 = new LuaState();
        assertEquals("true", s2.eval("return tostring(getmetatable('') ~= nil)").toLuaString());
        assertEquals("ABC", s2.eval("return ('abc'):upper()").toLuaString());
    }
}

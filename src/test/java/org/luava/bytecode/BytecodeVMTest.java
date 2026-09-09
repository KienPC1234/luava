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
        LuaState.USE_BYTECODE_VM = false;
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
}

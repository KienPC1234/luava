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
}

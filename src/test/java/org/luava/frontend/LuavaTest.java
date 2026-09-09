package org.luava.frontend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.lexer.TokenType;
import org.luava.frontend.parser.Parser;
import org.luava.midend.optimizer.ConstantFolder;
import org.luava.midend.optimizer.Typer;
import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LuavaTest {
    private LuaState state;

    @BeforeEach
    void setUp() {
        state = new LuaState();
    }

    @Test
    void testLexerLua54Tokens() {
        String code = "local x <close> = 0x1A\nlocal y <const> = 10.5\nlocal z = a // b & c | d ~ e >> 2 << 1";
        Lexer lexer = new Lexer(code);
        List<Token> tokens = lexer.scanTokens();

        assertNotNull(tokens);
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.ATTR_CLOSE));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.ATTR_CONST));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.DOUBLE_SLASH));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.AMPERSAND));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.PIPE));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.TILDE));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.SHR));
        assertTrue(tokens.stream().anyMatch(t -> t.type() == TokenType.SHL));
    }

    @Test
    void testConstantFolding() {
        // 10 + 20 * 3
        Expression expr = new Expressions.BinaryExpr(
            new Expressions.IntegerLiteral(10, 1, 1),
            TokenType.PLUS,
            new Expressions.BinaryExpr(
                new Expressions.IntegerLiteral(20, 1, 5),
                TokenType.STAR,
                new Expressions.IntegerLiteral(3, 1, 10),
                1, 7
            ),
            1, 3
        );

        Expression folded = ConstantFolder.fold(expr);
        assertTrue(folded instanceof Expressions.IntegerLiteral);
        assertEquals(70L, ((Expressions.IntegerLiteral) folded).value());
    }

    @Test
    void testTypeInferenceAndGuardElimination() {
        Typer typer = new Typer();
        Expressions.BinaryExpr intAddition = new Expressions.BinaryExpr(
            new Expressions.IntegerLiteral(5, 1, 1),
            TokenType.PLUS,
            new Expressions.IntegerLiteral(10, 1, 5),
            1, 3
        );

        assertEquals(Typer.InferredType.INTEGER, typer.infer(intAddition));
        assertTrue(typer.canEliminateGuard(intAddition));
    }

    @Test
    void testBasicArithmeticAndBitwise() {
        LuaValue res1 = state.eval("return 100 // 7");
        assertEquals(14L, res1.toLong());

        LuaValue res2 = state.eval("return 10 / 4");
        assertEquals(2.5, res2.toDouble(), 1e-9);

        LuaValue res3 = state.eval("return (0xFF & 0x0F) | (1 << 4)");
        assertEquals(31L, res3.toLong());
    }

    @Test
    void testClosuresAndLexicalScoping() {
        String code = """
            function make_counter()
                local count = 0
                return function()
                    count = count + 1
                    return count
                end
            end
            local c = make_counter()
            c()
            c()
            return c()
            """;
        LuaValue res = state.eval(code);
        assertEquals(3L, res.toLong());
    }

    @Test
    void testLua54ToBeClosedVariables() {
        String code = """
            local closed = false
            do
                local resource <close> = setmetatable({}, {
                    __close = function(self)
                        closed = true
                    end
                })
            end
            return closed
            """;
        LuaValue res = state.eval(code);
        assertTrue(res.toBoolean());
    }

    @Test
    void testVirtualThreadCoroutines() {
        String code = """
            local function generator()
                coroutine.yield(10)
                coroutine.yield(20)
                return 30
            end

            local co = coroutine.create(generator)
            local ok1, v1 = coroutine.resume(co)
            local ok2, v2 = coroutine.resume(co)
            local ok3, v3 = coroutine.resume(co)
            return ok1 and ok2 and ok3 and (v1 + v2 + v3 == 60)
            """;
        LuaValue res = state.eval(code);
        assertTrue(res.toBoolean());
    }

    @Test
    void testTableAndMetatableOperations() {
        String code = """
            local Point = {}
            Point.__index = Point

            function Point.new(x, y)
                local self = setmetatable({}, Point)
                self.x = x
                self.y = y
                return self
            end

            function Point:length_sq()
                return self.x * self.x + self.y * self.y
            end

            local p = Point.new(3, 4)
            return p:length_sq()
            """;
        LuaValue res = state.eval(code);
        assertEquals(25L, res.toLong());
    }

    @Test
    void testStandardMathAndString() {
        LuaValue mathRes = state.eval("return math.floor(math.sqrt(144))");
        assertEquals(12L, mathRes.toLong());

        LuaValue strRes = state.eval("return string.upper(string.sub('hello world', 1, 5))");
        assertEquals("HELLO", strRes.toLuaString());
    }

    @Test
    void testOfficialLua54BitwiseSuite() {
        String code = """
            assert(~0 == -1)
            local numbits = 64
            assert((1 << (numbits - 1)) == math.mininteger)

            local a, b, c, d
            a = 0xFFFFFFFFFFFFFFFF
            assert(a == -1 and (a & -1 == a) and (a & 35 == 35))
            a = 0xF0F0F0F0F0F0F0F0
            assert((a | -1) == -1)
            assert((a ~ a == 0) and (a ~ 0 == a) and (a ~ ~a == -1))
            assert((a >> 4) == ~a)

            a = 0xF0; b = 0xCC; c = 0xAA; d = 0xFD
            assert((a | (b ~ (c & d))) == 0xF4)
            return true
            """;
        LuaValue res = state.eval(code);
        assertTrue(res.toBoolean());
    }

    @Test
    void testOfficialLua54CloseFromLocalsSuite() {
        String code = """
            local a = {}
            do
                local b <close> = false
                local x <close> = setmetatable({"x"}, {
                    __close = function(self)
                        a[#a + 1] = self[1]
                    end
                })
                local y <close> = setmetatable({"y"}, {
                    __close = function(self, err)
                        assert(err == nil)
                        a[#a + 1] = "y"
                    end
                })
                local c <close> = nil
                a[#a + 1] = "in"
            end
            a[#a + 1] = "out"
            assert(a[1] == "in" and a[2] == "y" and a[3] == "x" and a[4] == "out")
            return true
            """;
        LuaValue res = state.eval(code);
        assertTrue(res.toBoolean());
    }


    public interface Calculator {
        long add(long a, long b);
        String greet(String name);
    }

    @Test
    void testJavaInteropAndDynamicProxy() {
        // Test Lua Table implementing Java Interface via DynamicProxyBridge
        String code = """
            local calc = {}
            function calc:add(a, b)
                return a + b
            end
            function calc:greet(name)
                return "Hello, " .. name
            end
            return calc
            """;
        LuaValue tableVal = state.eval(code);
        assertTrue(tableVal.isTable());

        Calculator proxy = org.luava.runtime.interop.DynamicProxyBridge.createProxy(Calculator.class, (LuaTable) tableVal);
        assertEquals(42L, proxy.add(20, 22));
        assertEquals("Hello, Java 21", proxy.greet("Java 21"));
    }

    @org.luava.binding.annotation.LuaModule(name = "PlayerSystem", description = "Manages game player stats")
    public static class PlayerService {
        @org.luava.binding.annotation.LuaField(name = "MAX_LEVEL", description = "Highest reachable level", readOnly = true)
        public int maxLevel = 100;

        @org.luava.binding.annotation.LuaMethod(description = "Calculates damage with armor reduction")
        public double calculateDamage(
            @org.luava.binding.annotation.LuaParam(name = "rawDamage", type = "number", description = "Base damage") double rawDamage,
            @org.luava.binding.annotation.LuaParam(name = "armor", type = "number", description = "Armor value") double armor
        ) {
            return rawDamage * (100.0 / (100.0 + armor));
        }

        @org.luava.binding.annotation.LuaMethod(description = "Retrieves user inventory")
        @org.luava.binding.annotation.LuaReturn(type = "table", description = "Array of item names")
        public java.util.List<String> getInventory(
            @org.luava.binding.annotation.LuaParam(name = "playerId", type = "integer") long playerId
        ) {
            return java.util.List.of("Sword", "Shield", "Potion");
        }
    }

    @Test
    void testAnnotatedModuleBindingAndEmmyDoc() {
        PlayerService service = new PlayerService();
        state.registerModule(service);

        // Test field access
        LuaValue maxLevel = state.eval("return PlayerSystem.MAX_LEVEL");
        assertEquals(100L, maxLevel.toLong());

        // Test method invocation with automatic unboxing
        LuaValue dmg = state.eval("return PlayerSystem.calculateDamage(150.0, 50.0)");
        assertEquals(100.0, dmg.toDouble(), 1e-6);

        // Test method returning Java List converted to Lua Table
        LuaValue inv = state.eval("return PlayerSystem.getInventory(42)");
        assertTrue(inv.isTable());
        LuaTable invTable = (LuaTable) inv;
        assertEquals(3, invTable.rawlen());
        assertEquals("Sword", invTable.rawget(LuaInteger.valueOf(1)).toLuaString());
        assertEquals("Shield", invTable.rawget(LuaInteger.valueOf(2)).toLuaString());
        assertEquals("Potion", invTable.rawget(LuaInteger.valueOf(3)).toLuaString());

        // Test EmmyDoc generation
        String emmyDoc = state.generateEmmyDocs();
        assertNotNull(emmyDoc);
        assertTrue(emmyDoc.contains("---@class PlayerSystem"));
        assertTrue(emmyDoc.contains("---@field readonly MAX_LEVEL"));
        assertTrue(emmyDoc.contains("---@param rawDamage number"));
        assertTrue(emmyDoc.contains("---@return table"));
        assertTrue(emmyDoc.contains("function PlayerSystem.calculateDamage(rawDamage, armor) end"));
    }

    @Test
    void testFluentJavaFunctionRegistration() {
        state.registerFunction("multiply", Long.class, Long.class, (a, b) -> a * b);
        state.registerFunction("shout", String.class, s -> s.toUpperCase() + "!");

        LuaValue res1 = state.eval("return multiply(6, 7)");
        assertEquals(42L, res1.toLong());

        LuaValue res2 = state.eval("return shout('luava')");
        assertEquals("LUAVA!", res2.toLuaString());
    }

    @Test
    void testBidirectionalDataConversion() {
        java.util.Map<String, Object> config = java.util.Map.of("port", 8080, "host", "127.0.0.1", "active", true);
        state.set("serverConfig", config);

        LuaValue portVal = state.eval("return serverConfig.port");
        assertEquals(8080L, portVal.toLong());

        LuaValue hostVal = state.eval("return serverConfig.host");
        assertEquals("127.0.0.1", hostVal.toLuaString());

        // Convert back to Java Map
        java.util.Map<?, ?> convertedMap = state.get("serverConfig", java.util.Map.class);
        assertNotNull(convertedMap);
        assertEquals(8080L, convertedMap.get("port"));
        assertEquals("127.0.0.1", convertedMap.get("host"));
    }

    @Test
    void testRequireAndPackagePreload() {
        state.registerModule("my_engine_utils", table -> {
            table.rawset(LuaString.valueOf("version"), LuaString.valueOf("2.1.0"));
            table.rawset(LuaString.valueOf("compute"), LuaFunction.of(args -> LuaInteger.valueOf(args[0].toLong() * 2)));
        });

        String code = """
            local utils = require("my_engine_utils")
            local v = utils.version
            local calc = utils.compute(21)
            return (v == "2.1.0") and (calc == 42)
            """;
        LuaValue res = state.eval(code);
        assertTrue(res.toBoolean());
    }

    @Test
    void testSandboxingExecutionTimeout() {
        String infiniteLoop = "while true do end";
        assertThrows(java.util.concurrent.TimeoutException.class, () -> {
            state.evalWithTimeout(infiniteLoop, java.time.Duration.ofMillis(100));
        });
    }
}

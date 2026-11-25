package org.luava.bench;

import org.luava.frontend.ast.Expression;
import org.luava.frontend.ast.Expressions;
import org.luava.frontend.ast.Statement;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.bytecode.BytecodeCompiler;
import org.luava.runtime.bytecode.LuaProto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Lua 5.4 source corpus and static frontend metrics collector
 * covering file-size tiers (small 1-5 KB, medium 20-100 KB, large 500 KB-1 MB)
 * and targeted frontend/compiler stress patterns.
 */
public final class CorpusLoader {

    public record WorkloadMetrics(
            String name,
            int sourceBytes,
            int tokenCount,
            int astNodeCount,
            int bytecodeCount,
            int protoCount) {}

    private static final Map<String, String> CORPUS = buildCorpus();

    private CorpusLoader() {}

    public static String sourceFor(String name) {
        String src = CORPUS.get(name);
        if (src == null) {
            throw new IllegalArgumentException("Unknown workload: " + name);
        }
        return src;
    }

    public static Map<String, String> allSources() {
        return CORPUS;
    }

    public static WorkloadMetrics measureMetrics(String name) {
        String src = sourceFor(name);
        List<Token> tokens = new Lexer(src, false).scanTokens();
        Statements.BlockStmt block = new Parser(tokens).parse();
        LuaProto proto = BytecodeCompiler.compile(block, "@" + name + ".lua");
        int astNodes = countBlockNodes(block);
        int insts = countInstructions(proto);
        int protos = countProtos(proto);
        return new WorkloadMetrics(
                name,
                src.getBytes(StandardCharsets.UTF_8).length,
                tokens.size(),
                astNodes,
                insts,
                protos);
    }

    private static int countInstructions(LuaProto p) {
        int sum = p.code != null ? p.code.length : 0;
        if (p.protos != null) {
            for (LuaProto child : p.protos) {
                sum += countInstructions(child);
            }
        }
        return sum;
    }

    private static int countProtos(LuaProto p) {
        int sum = 1;
        if (p.protos != null) {
            for (LuaProto child : p.protos) {
                sum += countProtos(child);
            }
        }
        return sum;
    }

    private static int countBlockNodes(Statements.BlockStmt block) {
        if (block == null) {
            return 0;
        }
        int count = 1;
        for (Statement stmt : block.statements()) {
            count += countStmtNodes(stmt);
        }
        return count;
    }

    private static int countStmtNodes(Statement stmt) {
        if (stmt == null) {
            return 0;
        }
        return switch (stmt) {
            case Statements.BlockStmt b -> countBlockNodes(b);
            case Statements.LocalVarDeclStmt l -> 1 + sumExprs(l.initializers());
            case Statements.AssignmentStmt a -> 1 + sumExprs(a.targets()) + sumExprs(a.values());
            case Statements.ExprStmt e -> 1 + countExprNodes(e.expression());
            case Statements.IfStmt ifs -> {
                int c = 1 + countBlockNodes(ifs.elseBlock());
                for (Statements.IfBranch br : ifs.branches()) {
                    c += countExprNodes(br.condition()) + countBlockNodes(br.block());
                }
                yield c;
            }
            case Statements.WhileStmt w -> 1 + countExprNodes(w.condition()) + countBlockNodes(w.body());
            case Statements.RepeatStmt r -> 1 + countBlockNodes(r.body()) + countExprNodes(r.condition());
            case Statements.ForNumericStmt f ->
                    1 + countExprNodes(f.start()) + countExprNodes(f.limit())
                            + countExprNodes(f.step()) + countBlockNodes(f.body());
            case Statements.ForGenericStmt fg -> 1 + sumExprs(fg.iterators()) + countBlockNodes(fg.body());
            case Statements.FunctionDefStmt fd ->
                    1 + countExprNodes(fd.targetName()) + countBlockNodes(fd.body());
            case Statements.LocalFunctionDefStmt lfd -> 1 + countBlockNodes(lfd.body());
            case Statements.ReturnStmt r -> 1 + sumExprs(r.values());
            case Statements.BreakStmt b -> 1;
            case Statements.GotoStmt g -> 1;
            case Statements.LabelStmt l -> 1;
        };
    }

    private static int sumExprs(List<Expression> exprs) {
        if (exprs == null) {
            return 0;
        }
        int c = 0;
        for (Expression e : exprs) {
            c += countExprNodes(e);
        }
        return c;
    }

    private static int countExprNodes(Expression expr) {
        if (expr == null) {
            return 0;
        }
        return switch (expr) {
            case Expressions.NilLiteral e -> 1;
            case Expressions.BooleanLiteral e -> 1;
            case Expressions.IntegerLiteral e -> 1;
            case Expressions.FloatLiteral e -> 1;
            case Expressions.StringLiteral e -> 1;
            case Expressions.VarargLiteral e -> 1;
            case Expressions.VariableExpr e -> 1;
            case Expressions.BinaryExpr b -> 1 + countExprNodes(b.left()) + countExprNodes(b.right());
            case Expressions.UnaryExpr u -> 1 + countExprNodes(u.operand());
            case Expressions.ParenExpr p -> 1 + countExprNodes(p.expression());
            case Expressions.TableAccessExpr ta -> 1 + countExprNodes(ta.table()) + countExprNodes(ta.key());
            case Expressions.TableConstructorExpr tc -> {
                int c = 1;
                for (Expressions.TableField f : tc.fields()) {
                    c += countExprNodes(f.key()) + countExprNodes(f.value());
                }
                yield c;
            }
            case Expressions.FunctionCallExpr fc ->
                    1 + countExprNodes(fc.target()) + sumExprs(fc.arguments());
            case Expressions.FunctionDefExpr fd -> 1 + countBlockNodes(fd.body());
        };
    }

    private static Map<String, String> buildCorpus() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("small_3kb", generateMixedScript(12));
        m.put("medium_50kb", generateMixedScript(190));
        m.put("large_600kb", generateMixedScript(2300));
        m.put("many_locals", generateManyLocals(20));
        m.put("nested_closures", generateNestedClosures(35));
        m.put("table_constructors", generateTableConstructors(120));
        m.put("escaped_strings", generateEscapedStrings(220));
        m.put("deep_exprs", generateDeepExprs(80));
        m.put("heavy_comments", generateHeavyComments(180));
        m.put("jit_kernel", generateJitKernel());
        m.put("exec_table_ops", loadBenchLua("03_table_ops.lua"));
        m.put("exec_oop_metatables", loadBenchLua("08_oop_metatables.lua"));
        return m;
    }

    private static String loadBenchLua(String fileName) {
        Path[] candidates = {
                Path.of("benchmarks/lua", fileName),
                Path.of("../lua", fileName),
                Path.of("../../benchmarks/lua", fileName)
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read " + p, e);
                }
            }
        }
        throw new IllegalStateException("Cannot locate benchmarks/lua/" + fileName);
    }

    private static String generateMixedScript(int blocks) {
        StringBuilder sb = new StringBuilder(blocks * 280);
        sb.append("local M = {}\nlocal total = 0\n");
        for (int i = 0; i < blocks; i++) {
            sb.append("function M.step_").append(i).append("(x, y, z)\n");
            sb.append("  local a = (x + ").append(i).append(") * 3 - (y // 2)\n");
            sb.append("  local b = { k1 = a, k2 = z or \"v_").append(i).append("\", a + 1, a + 2 }\n");
            sb.append("  if a > 10 and z ~= nil then\n");
            sb.append("    b[1] = b[1] + (b.k1 & 0xff)\n");
            sb.append("  elseif a < 0 then\n");
            sb.append("    b[1] = -b[1]\n");
            sb.append("  end\n");
            sb.append("  return b[1] + b[2]\n");
            sb.append("end\n");
        }
        sb.append("for i = 1, 10 do total = total + M.step_0(i, i + 1, 'ok') end\n");
        sb.append("return total\n");
        return sb.toString();
    }

    private static String generateManyLocals(int funcs) {
        StringBuilder sb = new StringBuilder(funcs * 2400);
        sb.append("local acc = 0\n");
        for (int f = 0; f < funcs; f++) {
            sb.append("local function locals_").append(f).append("(seed)\n");
            for (int i = 0; i < 60; i++) {
                sb.append("  local v").append(i).append(" = seed + ").append(i).append('\n');
            }
            sb.append("  do\n");
            for (int i = 0; i < 40; i++) {
                sb.append("    local v").append(i).append(" = v").append(i).append(" * 2 + 1\n");
            }
            sb.append("    local s = 0\n");
            for (int i = 0; i < 40; i++) {
                sb.append("    s = s + v").append(i).append('\n');
            }
            sb.append("    seed = s\n");
            sb.append("  end\n");
            for (int i = 0; i < 60; i++) {
                sb.append("  seed = seed + v").append(i).append('\n');
            }
            sb.append("  return seed\nend\n");
        }
        sb.append("return acc\n");
        return sb.toString();
    }

    private static String generateNestedClosures(int groups) {
        StringBuilder sb = new StringBuilder(groups * 700);
        sb.append("local res = 0\n");
        for (int g = 0; g < groups; g++) {
            sb.append("local function outer_").append(g).append("(a, b)\n");
            sb.append("  local x = a + ").append(g).append('\n');
            sb.append("  local y = b * 2\n");
            sb.append("  return function(c)\n");
            sb.append("    local z = x + y + c\n");
            sb.append("    return function(d)\n");
            sb.append("      local w = z + d\n");
            sb.append("      return function(e)\n");
            sb.append("        x = x + 1\n");
            sb.append("        return x + y + z + w + e\n");
            sb.append("      end\n");
            sb.append("    end\n");
            sb.append("  end\n");
            sb.append("end\n");
        }
        sb.append("return res\n");
        return sb.toString();
    }

    private static String generateTableConstructors(int count) {
        StringBuilder sb = new StringBuilder(count * 350);
        sb.append("local all = {}\n");
        for (int i = 0; i < count; i++) {
            sb.append("all[").append(i + 1).append("] = {");
            for (int k = 1; k <= 12; k++) {
                sb.append("f").append(k).append(" = ").append(i * 12 + k).append(", ");
                sb.append("[").append(k).append("] = \"item_").append(k).append("\", ");
            }
            sb.append("nested = { a = 1, b = 2, c = { 10, 20, 30 } } }\n");
        }
        sb.append("return #all\n");
        return sb.toString();
    }

    private static String generateEscapedStrings(int count) {
        StringBuilder sb = new StringBuilder(count * 200);
        sb.append("local t = {}\n");
        for (int i = 0; i < count; i++) {
            sb.append("t[").append(i + 1).append("] = \"line\\n\\t\\x41\\065\\u{00E9}\\z\n   tail_")
                    .append(i).append("\\\"\\\\\"\n");
        }
        sb.append("return #t\n");
        return sb.toString();
    }

    private static String generateDeepExprs(int statements) {
        StringBuilder sb = new StringBuilder(statements * 400);
        sb.append("local a, b, c, d = 1, 2, 3, 4\n");
        for (int i = 0; i < statements; i++) {
            sb.append("a = (((a + b) * (c - d) + (a << 2) - (b >> 1)) ^ ((c & 0xff) | (d & 0x0f)))");
            for (int k = 1; k <= 12; k++) {
                sb.append(" + ((").append(k).append(" * a - b) // (c + 1))");
            }
            sb.append('\n');
        }
        sb.append("return a\n");
        return sb.toString();
    }

    private static String generateHeavyComments(int blocks) {
        StringBuilder sb = new StringBuilder(blocks * 350);
        sb.append("--[[ Top header comment block\n  multi-line documentation\n]]\n");
        sb.append("local sum = 0\n");
        for (int i = 0; i < blocks; i++) {
            sb.append("-- Single line comment number ").append(i).append(" explaining logic\n");
            sb.append("-- Another dense comment line with symbols () [] {} = + - * /\n");
            sb.append("--[[ inline long comment ]] sum = sum + ").append(i).append(" -- trailing\n");
        }
        sb.append("return sum\n");
        return sb.toString();
    }

    private static String generateJitKernel() {
        return """
                local function sum_step(n, acc)
                    local s = acc
                    for i = 1, n do
                        local x = (i * 3) ~ (i >> 1)
                        if (x & 1) == 0 then
                            s = s + x
                        else
                            s = s - (x // 2)
                        end
                    end
                    return s
                end
                local total = 0
                for k = 1, 200 do
                    total = total + sum_step(1000, k)
                end
                return total
                """;
    }
}

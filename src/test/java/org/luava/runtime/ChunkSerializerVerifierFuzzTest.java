/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.luava.runtime.bytecode.Instruction;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.OpCode;
import org.luava.runtime.jit.JitCompiler;
import org.luava.runtime.standard.ChunkSerializer;

/**
 * Compatibility, error-propagation, and adversarial verifier fuzz tests for
 * {@link ChunkSerializer}: cross-version v0.2.7 payload loading, PUC {@code luac}
 * bytecode rejection, {@link Error} non-swallowing, and malformed {@code LP\2\0}
 * bytecode verification.
 */
public class ChunkSerializerVerifierFuzzTest {

    private static final boolean REQUIRE_PUC = Boolean.getBoolean("luava.test.requirePuc");

    /** Authentic PUC-Rio luac 5.4.8 binary chunk for `return 1 + 2`. */
    private static final byte[] PUC_LUAC_54_RETURN_3 = HexFormat.of().parseHex(
            "1b4c7561540019930d0a1a0a040808"
                    + "78560000000000000000000000287740"
                    + "01873d737464696e808000010284510000"
                    + "810b010046000101808101008084810101"
                    + "0181855f454e560000");

    private static String detectLuac54() {
        String configured = System.getProperty("luava.test.luacBin");
        String[] candidates = configured != null && !configured.isBlank()
                ? new String[] {configured, "luac5.4", "luac"}
                : new String[] {"luac5.4", "luac"};
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

    /** Builds the exact binary wire layout produced by Luava v0.2.7 (commit 0f0a935). */
    private static byte[] buildV027LegacyChunk(
            boolean isVararg,
            boolean stripped,
            String source,
            int lineDefined,
            int lastLineDefined,
            List<String> params,
            List<String> upvalues,
            String bodyCode,
            boolean withFooter)
            throws IOException {
        ByteArrayOutputStream payloadBaos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payloadBaos);
        out.writeBoolean(isVararg);
        out.writeBoolean(stripped);
        out.writeUTF(stripped ? "=?" : source);
        out.writeInt(lineDefined);
        out.writeInt(stripped ? lineDefined : lastLineDefined);
        out.writeInt(params.size());
        for (String p : params) {
            out.writeUTF(p);
        }
        out.writeInt(upvalues.size());
        for (String u : upvalues) {
            out.writeUTF(u);
        }
        byte[] codeBytes = bodyCode.getBytes(StandardCharsets.UTF_8);
        out.writeInt(codeBytes.length);
        out.write(codeBytes);
        out.flush();
        byte[] payload = payloadBaos.toByteArray();

        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(ChunkSerializer.HEADER);
        ByteBuffer check = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        check.putLong(0x5678L);
        check.putDouble(370.5);
        full.write(check.array());
        full.write(payload);
        if (withFooter) {
            ByteBuffer foot = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            foot.putInt(payload.length);
            full.write(foot.array());
            full.write("LUAVA_V1".getBytes(StandardCharsets.US_ASCII));
        }
        return full.toByteArray();
    }

    @Test
    void crossVersionV027LegacyBinaryChunksDecodeAndExecuteIdentically() throws Exception {
        byte[] legacyNormal = buildV027LegacyChunk(
                false,
                false,
                "@legacy_v027.lua",
                1,
                5,
                List.of("x", "y"),
                List.of("_ENV", "scale"),
                "return (x + y) * scale",
                false);
        byte[] legacyStrippedFooter = buildV027LegacyChunk(
                true,
                true,
                "@legacy_v027.lua",
                10,
                14,
                List.of("head"),
                List.of("_ENV"),
                "local extra = ...; return head * 10 + (extra or 0)",
                true);

        for (boolean jitOn : new boolean[] {false, true}) {
            try (LuaState st = new LuaState().jitEnabled(jitOn)) {
                st.getGlobals().rawset(LuaString.valueOf("chunkA"), LuaString.fromOwnedBytes(legacyNormal.clone()));
                st.getGlobals().rawset(LuaString.valueOf("chunkB"), LuaString.fromOwnedBytes(legacyStrippedFooter.clone()));
                String out = st.eval("""
                        local f1 = assert(load(chunkA, nil, "b"))
                        debug.setupvalue(f1, 2, 4)
                        local f2 = assert(load(chunkB, nil, "b"))
                        local info1 = debug.getinfo(f1, "S")
                        local info2 = debug.getinfo(f2, "S")
                        return table.concat({
                            tostring(f1(3, 5)),
                            tostring(f2(7, 9)),
                            info1.source,
                            info2.source
                        }, "|")
                        """).toLuaString();
                assertEquals("32|79|@legacy_v027.lua|=?", out);
            }
        }
    }

    @Test
    void pucLuacBinaryChunksAreCleanlyRejectedAsUnsupported() throws Exception {
        try (LuaState st = new LuaState()) {
            st.getGlobals().rawset(LuaString.valueOf("pucChunk"), LuaString.fromOwnedBytes(PUC_LUAC_54_RETURN_3.clone()));
            String msg = st.eval("local f, err = load(pucChunk, nil, 'b'); assert(f == nil); return err").toLuaString();
            assertTrue(
                    msg.contains("incompatible binary chunk") || msg.contains("corrupt"),
                    "Unexpected diagnostic for PUC luac chunk: " + msg);

            String luacBin = detectLuac54();
            if (REQUIRE_PUC) {
                assertNotNull(luacBin, "luac 5.4 binary required when -Dluava.test.requirePuc=true");
            }
            if (luacBin != null) {
                for (String[] args : new String[][] {{luacBin, "-o", "-", "-"}, {luacBin, "-s", "-o", "-", "-"}}) {
                    Process p = new ProcessBuilder(args).start();
                    p.getOutputStream().write("local x = 40; return x + 2\n".getBytes(StandardCharsets.UTF_8));
                    p.getOutputStream().close();
                    assertTrue(p.waitFor(5, TimeUnit.SECONDS), "luac timed out");
                    assertEquals(0, p.exitValue());
                    byte[] compiled = p.getInputStream().readAllBytes();
                    st.getGlobals().rawset(LuaString.valueOf("liveLuac"), LuaString.fromOwnedBytes(compiled));
                    String liveErr = st.eval("local f, err = load(liveLuac, nil, 'b'); assert(f == nil); return err")
                            .toLuaString();
                    assertTrue(
                            liveErr.contains("incompatible binary chunk") || liveErr.contains("corrupt"),
                            "Unexpected diagnostic for live luac output: " + liveErr);
                }
            }
        }
    }

    @Test
    void jvmErrorsAreNeverSwallowedByUndump() {
        try (LuaState donor = new LuaState();
             LuaState loader = new LuaState()) {
            LuaFunction fn = donor.compile("return 1 + 2", "@err_probe.lua", donor.getGlobals());
            byte[] validBytes = ChunkSerializer.dump(fn, false);

            Throwable[] directFromUndump = new Throwable[1];
            // Activate loader as ACTIVE_STATE via a native function boundary, then arm loopGuard right before undump
            loader.registerFunction("triggerUndump", rawArgs -> {
                loader.loopGuard = () -> {
                    throw new OutOfMemoryError("synthetic-jvm-oom-during-undump");
                };
                try {
                    return ChunkSerializer.undump(
                            validBytes, "@err_probe.lua", loader.getGlobals(), loader.getGlobals(), null);
                } catch (Throwable t) {
                    directFromUndump[0] = t;
                    throw t;
                }
            });
            LuaException hostMemErr = assertThrows(
                    LuaException.class,
                    () -> loader.eval("return triggerUndump()"));
            OutOfMemoryError propagated = assertInstanceOf(
                    OutOfMemoryError.class,
                    directFromUndump[0],
                    "ChunkSerializer.undump must propagate VirtualMachineError / OutOfMemoryError directly without wrapping as corrupted chunk");
            assertEquals("synthetic-jvm-oom-during-undump", propagated.getMessage());
            assertTrue(!hostMemErr.getMessage().contains("corrupted chunk"));
        }
    }

    private static byte[] buildMinimalLpChunk(int maxStack, int[] code, LuaValue[] constants) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        out.write(new byte[] {'L', 'P', 0x02, 0x00});
        out.writeBoolean(true); // stripped
        out.writeInt(0);        // empty root source
        out.writeBoolean(false); // hasName = false
        out.writeInt(1);        // lineDefined
        out.writeInt(1);        // lastLineDefined
        out.writeInt(0);        // numParams
        out.writeBoolean(false); // isVararg
        out.writeInt(maxStack);
        out.writeInt(code.length);
        for (int inst : code) {
            out.writeInt(inst);
        }
        out.writeInt(constants.length);
        for (LuaValue c : constants) {
            if (c == null || c.isNil()) {
                out.writeByte(0);
            } else if (c instanceof LuaBoolean lb) {
                out.writeByte(1);
                out.writeBoolean(lb.toBoolean());
            } else if (c instanceof LuaInteger li) {
                out.writeByte(2);
                out.writeLong(li.toLong());
            } else if (c instanceof LuaFloat lf) {
                out.writeByte(3);
                out.writeDouble(lf.toDouble());
            } else if (c instanceof LuaString ls) {
                out.writeByte(4);
                int len = ls.rawLength();
                out.writeInt(len);
                if (len > 0) {
                    out.write(ls.rawBytesUnsafe(), ls.rawOffset(), len);
                }
            }
        }
        out.writeInt(1); // 1 upvalue (_ENV)
        out.writeBoolean(true);
        out.writeInt(0);
        out.writeBoolean(true);
        out.writeInt(0);
        out.writeInt(0); // 0 child protos
        out.flush();

        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(ChunkSerializer.HEADER);
        ByteBuffer check = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        check.putLong(0x5678L);
        check.putDouble(370.5);
        full.write(check.array());
        full.write(payload.toByteArray());
        return full.toByteArray();
    }

    @Test
    void craftedBoundaryOpcodesAndDivByZeroConstantAreHandledSafely() throws Exception {
        // 1) OP_LFALSESKIP at pc == len - 2 must be rejected (skip would jump past OP_RETURN0 to pc == len)
        byte[] badLfalseSkip = buildMinimalLpChunk(
                4,
                new int[] {
                    Instruction.encodeABC(OpCode.OP_LFALSESKIP, 0, 0, 0, 0),
                    Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0, 0)
                },
                new LuaValue[0]);
        assertUndumpRejected(badLfalseSkip);

        // 2) Conditional skip (OP_TEST, OP_EQ, OP_LTI, OP_EQK, OP_TESTSET) at pc == len - 2 must be rejected
        for (int condInst : new int[] {
            Instruction.encodeABC(OpCode.OP_TEST, 0, 0, 0, 0),
            Instruction.encodeABC(OpCode.OP_EQ, 0, 0, 0, 0),
            Instruction.encodeABC(OpCode.OP_LTI, 0, 128, 0, 0),
            Instruction.encodeABC(OpCode.OP_EQK, 0, 0, 0, 0),
            Instruction.encodeABC(OpCode.OP_TESTSET, 0, 1, 0, 0)
        }) {
            byte[] badCond = buildMinimalLpChunk(
                    4,
                    new int[] {condInst, Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0, 0)},
                    new LuaValue[] {LuaInteger.valueOf(1)});
            assertUndumpRejected(badCond);
        }

        // 3) OP_LOADKX / OP_NEWTABLE(k=1) / OP_SETLIST(k=1) without OP_EXTRAARG must be rejected
        for (int needsExtra : new int[] {
            Instruction.encodeABx(OpCode.OP_LOADKX, 0, 0),
            Instruction.encodeABC(OpCode.OP_NEWTABLE, 0, 0, 0, 1),
            Instruction.encodeABC(OpCode.OP_SETLIST, 0, 1, 0, 1)
        }) {
            byte[] badExtra = buildMinimalLpChunk(
                    4,
                    new int[] {
                        needsExtra,
                        Instruction.encodeABC(OpCode.OP_LOADTRUE, 1, 0, 0, 0),
                        Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0, 0)
                    },
                    new LuaValue[] {LuaInteger.valueOf(1)});
            assertUndumpRejected(badExtra);
        }

        // 4) Out-of-bounds register, constant, jump, and OP_TFORPREP target must be rejected
        byte[] badReg = buildMinimalLpChunk(
                4,
                new int[] {
                    Instruction.encodeABC(OpCode.OP_LOADNIL, 250, 10, 0, 0),
                    Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0, 0)
                },
                new LuaValue[0]);
        assertUndumpRejected(badReg);

        byte[] badTforPrep = buildMinimalLpChunk(
                8,
                new int[] {
                    Instruction.encodeABx(OpCode.OP_TFORPREP, 0, 5),
                    Instruction.encodeABC(OpCode.OP_RETURN0, 0, 0, 0, 0)
                },
                new LuaValue[0]);
        assertUndumpRejected(badTforPrep);

        // 5) Valid-bounds OP_IDIVK and OP_MODK with constant 0 must raise LuaException (never ArithmeticException)
        for (int divOp : new int[] {OpCode.OP_IDIVK, OpCode.OP_MODK}) {
            byte[] zeroDivChunk = buildMinimalLpChunk(
                    4,
                    new int[] {
                        Instruction.encodeABx(OpCode.OP_LOADI, 0, Instruction.OFFSET_sBx + 42),
                        Instruction.encodeABC(divOp, 1, 0, 0, 0),
                        Instruction.encodeABC(OpCode.OP_RETURN1, 1, 0, 0, 0)
                    },
                    new LuaValue[] {LuaInteger.valueOf(0)});
            try (LuaState interp = new LuaState().jitEnabled(false);
                 LuaState jit = new LuaState().jitEnabled(true)) {
                LuaFunction fnInterp = ChunkSerializer.undump(
                        zeroDivChunk, "@zero_div.lua", interp.getGlobals(), interp.getGlobals(), null);
                assertThrows(LuaException.class, fnInterp::call);

                LuaFunction fnJit = ChunkSerializer.undump(
                        zeroDivChunk, "@zero_div.lua", jit.getGlobals(), jit.getGlobals(), null);
                if (fnJit instanceof LuaClosure lc) {
                    lc.setState(jit);
                    JitCompiler.prewarm(lc, jit);
                }
                assertThrows(LuaException.class, fnJit::call);
            }
        }
    }

    @Test
    void malformedAndMutatedBinaryChunksFuzz1200CasesWithoutUncheckedExceptions() {
        byte[] seedNormal;
        byte[] seedStripped;
        try (LuaState donor = new LuaState()) {
            LuaValue fnVal = donor.eval("""
                    local up = 9
                    return function(n)
                        local t = { 10, 20, k = "hello" }
                        local acc = up
                        local function helper(v) return v * 2 + acc end
                        for i = 1, (n or 4) do
                            if i % 2 == 0 then
                                acc = acc + helper(i) + (t[i] or 1)
                            else
                                acc = acc ~ i
                            end
                        end
                        return acc .. ":" .. t.k
                    end
                    """);
            LuaFunction fn = (LuaFunction) fnVal;
            seedNormal = ChunkSerializer.dump(fn, false);
            seedStripped = ChunkSerializer.dump(fn, true);
        }

        Random rng = new Random(0x5645524946593534L);
        int cases = 1200;
        int rejected = 0;
        int loadedAndRan = 0;

        try (LuaState interp = new LuaState().sandbox().jitEnabled(false)
                        .instructionLimit(10_000).maxAllocationBytes(4 * 1024 * 1024);
             LuaState jit = new LuaState().sandbox().jitEnabled(true)
                        .instructionLimit(20_000).maxAllocationBytes(4 * 1024 * 1024)) {
            for (int i = 0; i < cases; i++) {
                byte[] base = (i & 1) == 0 ? seedNormal : seedStripped;
                byte[] mutated = mutateChunk(rng, base, i);

                LuaFunction fnInterp;
                try {
                    fnInterp = ChunkSerializer.undump(
                            mutated, "@fuzz_bin_" + i, interp.getGlobals(), interp.getGlobals(), null);
                } catch (LuaException expected) {
                    rejected++;
                    continue;
                }

                loadedAndRan++;
                // Any chunk accepted by the verifier must execute under both Interpreter and Sync JIT
                // without throwing unchecked non-LuaException JVM errors (AIOOBE, NPE, CCE, VerifyError).
                try {
                    if (fnInterp instanceof LuaClosure lc) {
                        lc.setState(interp);
                    }
                    interp.instructionLimit(10_000);
                    fnInterp.call(LuaInteger.valueOf(3));
                } catch (LuaException ignored) {
                }

                try {
                    LuaFunction fnJit = ChunkSerializer.undump(
                            mutated, "@fuzz_bin_jit_" + i, jit.getGlobals(), jit.getGlobals(), null);
                    if (fnJit instanceof LuaClosure lc) {
                        lc.setState(jit);
                        JitCompiler.prewarm(lc, jit);
                    }
                    jit.instructionLimit(20_000);
                    fnJit.call(LuaInteger.valueOf(3));
                } catch (LuaException ignored) {
                }
            }
        }
        assertTrue(rejected > 600, "Expected majority of corrupted chunks to be rejected, got " + rejected);
        assertEquals(cases, rejected + loadedAndRan);
    }

    private static byte[] mutateChunk(Random rng, byte[] base, int idx) {
        int mode = idx % 5;
        if (mode == 0) {
            // Truncation at every region (header, LP header, code, constants, child protos)
            int cut = rng.nextInt(base.length);
            byte[] out = new byte[cut];
            System.arraycopy(base, 0, out, 0, cut);
            return out;
        }
        byte[] copy = base.clone();
        int payloadStart = Math.min(copy.length, 35);
        if (mode == 1) {
            // Single-byte corruption inside LP payload
            int pos = payloadStart + rng.nextInt(Math.max(1, copy.length - payloadStart));
            copy[pos] ^= (byte) (1 << rng.nextInt(8));
            return copy;
        }
        if (mode == 2) {
            // Multi-byte random overwrite (corrupts instruction words, counts, tags, jump offsets)
            int flips = 2 + rng.nextInt(6);
            for (int f = 0; f < flips; f++) {
                int pos = payloadStart + rng.nextInt(Math.max(1, copy.length - payloadStart));
                copy[pos] = (byte) rng.nextInt(256);
            }
            return copy;
        }
        if (mode == 3) {
            // Inject extreme 32-bit length/index values (0x7fffffff, -1, 65536) in payload
            if (copy.length >= payloadStart + 8) {
                int pos = payloadStart + rng.nextInt(copy.length - payloadStart - 4);
                int[] extremes = {0x7fffffff, -1, 0x00100000, 256, -128};
                ByteBuffer.wrap(copy, pos, 4).order(ByteOrder.BIG_ENDIAN)
                        .putInt(extremes[rng.nextInt(extremes.length)]);
            }
            return copy;
        }
        // Append trailing garbage bytes after valid payload
        byte[] extended = new byte[copy.length + 1 + rng.nextInt(8)];
        System.arraycopy(copy, 0, extended, 0, copy.length);
        extended[copy.length] = (byte) (1 + rng.nextInt(254));
        return extended;
    }

    private static void assertUndumpRejected(byte[] chunk) {
        try (LuaState st = new LuaState()) {
            assertThrows(
                    LuaException.class,
                    () -> ChunkSerializer.undump(
                            chunk, "@crafted.lua", st.getGlobals(), st.getGlobals(), null));
        }
    }
}

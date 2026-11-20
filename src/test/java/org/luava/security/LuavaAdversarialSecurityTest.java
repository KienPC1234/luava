/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.security;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harsh adversarial tests targeting parser/deserializer crashes, OOM bypasses,
 * guard bypasses in native stdlib loops, thread interrupts, and sparse tables.
 */
public class LuavaAdversarialSecurityTest {

    private static byte[] craftBinaryPayload(int paramCount, int upCount, int codeLen) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(raw)) {
            dos.write("LUAVA\0".getBytes(StandardCharsets.US_ASCII));
            dos.writeInt(1); // version
            dos.writeUTF("evil");
            dos.writeInt(0); // maxStackSize
            dos.writeByte(0); // isVararg
            dos.writeInt(paramCount);
            if (paramCount >= 0 && paramCount < 4) {
                for (int i = 0; i < paramCount; i++) dos.writeUTF("p" + i);
            }
            dos.writeInt(upCount);
            if (upCount >= 0 && upCount < 4) {
                for (int i = 0; i < upCount; i++) dos.writeUTF("u" + i);
            }
            dos.writeInt(codeLen);
        }
        byte[] payload = raw.toByteArray();
        ByteBuffer lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("--compiled\n".getBytes(StandardCharsets.US_ASCII));
        out.write(payload);
        out.write(lenBuf.array());
        out.write("LUAVA_V1".getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    @Test
    void corruptAndExtremeBinaryChunksFailWithLuaExceptionInsteadOfOomOrNegativeArrayCrash() throws Exception {
        try (LuaState s = new LuaState()) {
            byte[][] crafted = {
                    craftBinaryPayload(2_000_000_000, 0, 0),
                    craftBinaryPayload(-1, 0, 0),
                    craftBinaryPayload(0, 2_000_000_000, 0),
                    craftBinaryPayload(0, -1, 0),
                    craftBinaryPayload(0, 0, 2_000_000_000),
                    craftBinaryPayload(0, 0, -1)
            };
            for (byte[] chunkBytes : crafted) {
                String asStr = new String(chunkBytes, StandardCharsets.ISO_8859_1);
                s.set("badChunk", asStr);
                LuaValue loaded = s.eval("local fn, err = load(badChunk); assert(fn == nil and type(err) == 'string'); return err");
                assertNotNull(loaded);
                assertTrue(loaded.isString());
            }

            // Crafted footer with overflowing payloadLen
            byte[] footerBomb = new byte[32];
            ByteBuffer.wrap(footerBomb, 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MAX_VALUE);
            System.arraycopy("LUAVA_V1".getBytes(StandardCharsets.US_ASCII), 0, footerBomb, 24, 8);
            s.set("footerBomb", new String(footerBomb, StandardCharsets.ISO_8859_1));
            assertTrue(s.eval("local fn, err = load(footerBomb); return fn == nil and type(err) == 'string'").toBoolean());
        }
    }

    @Test
    void stringFormatRespectsMaxAllocationBytesAcrossAllSpecifiersAndWidths() {
        try (LuaState s = new LuaState().maxAllocationBytes(32)) {
            assertThrows(LuaException.class, () -> s.eval("return string.format('%99c', 65)"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%99p', {})"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%99d', 1)"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%99f', 1.5)"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%99s', 'x')"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%20s%20s', 'a', 'b')"));
            assertThrows(LuaException.class, () -> s.eval("return string.format('%999999999c', 65)"));
            assertEquals("A", s.eval("return string.format('%1c', 65)").toLuaString());
        }
    }

    @Test
    void patternMatchAndLiteralFindAbortPromptlyUnderGuardTimeout() {
        try (LuaState literalState = new LuaState().timeout(Duration.ofMillis(120))) {
            literalState.eval("hay = string.rep('a', 150000) .. 'b'; ndl = string.rep('a', 50000) .. 'c'");
            assertThrows(LuaException.class, () -> literalState.eval("""
                    for i = 1, 200 do
                        string.find(hay, ndl, 1, true)
                    end
                    """));
        }

        try (LuaState balState = new LuaState().timeout(Duration.ofMillis(120))) {
            balState.eval("unclosed = '(' .. string.rep('a', 200000)");
            assertThrows(LuaException.class, () -> balState.eval("""
                    for i = 1, 200 do
                        string.match(unclosed, '%b()')
                    end
                    """));
        }

        try (LuaState backtrackState = new LuaState().timeout(Duration.ofMillis(120))) {
            backtrackState.eval("s = string.rep('a', 40)");
            assertThrows(LuaException.class, () -> backtrackState.eval(
                    "return string.match(s, 'a*a*a*a*a*a*b')"));
        }
    }

    @Test
    void sparseGiantTableKeysDoNotPreallocateHugeDenseArrays() {
        try (LuaState s = new LuaState()) {
            LuaValue res = s.eval("""
                    local t = {}
                    t[1] = 10
                    t[1000000000] = 20
                    t[1 << 60] = 30
                    local count = 0
                    for k, v in pairs(t) do
                        count = count + 1
                    end
                    return {t[1] + t[1000000000] + t[1 << 60], count}
                    """);
            assertEquals(60L, res.get(LuaValue.valueOf(1)).toLong());
            assertEquals(3L, res.get(LuaValue.valueOf(2)).toLong());
        }
    }

    @Test
    void interruptedCallerThreadDoesNotBusySpinInCoroutineAwaitYield() throws Exception {
        try (LuaState s = new LuaState()) {
            CountDownLatch workerBlocked = new CountDownLatch(1);
            CountDownLatch releaseWorker = new CountDownLatch(1);
            s.registerFunction("yieldFromJava", () -> {
                org.luava.runtime.concurrency.LuaCoroutine.yield();
                return 0;
            });
            s.registerFunction("blockWorker", () -> {
                workerBlocked.countDown();
                try {
                    releaseWorker.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            });

            AtomicReference<Throwable> thrown = new AtomicReference<>();
            CountDownLatch runnerDone = new CountDownLatch(1);
            Thread runner = new Thread(() -> {
                try {
                    s.eval("""
                            local co = coroutine.create(function()
                                yieldFromJava()
                                return blockWorker()
                            end)
                            coroutine.resume(co) -- graduates to virtual thread via yieldFromJava
                            coroutine.resume(co) -- parks caller in awaitYield while worker blocks
                            """);
                } catch (Throwable t) {
                    thrown.set(t);
                } finally {
                    runnerDone.countDown();
                }
            }, "interrupted-runner");
            runner.start();

            assertTrue(workerBlocked.await(5, TimeUnit.SECONDS));
            runner.interrupt();
            assertTrue(runnerDone.await(2, TimeUnit.SECONDS),
                    "interrupted coroutine resumer must exit promptly instead of spinning");
            runner.join(2000);
            releaseWorker.countDown();
            assertNotNull(thrown.get());
            assertTrue(thrown.get().getMessage().contains("interrupted"),
                    "expected interruption failure, got: " + thrown.get());
            assertFalse(runner.isAlive());
        }
    }

    @Test
    void hugeBytecodeChunkFallsBackCleanlyWithoutJitCrash() {
        StringBuilder sb = new StringBuilder();
        sb.append("local x = 0\n");
        for (int i = 0; i < 4500; i++) {
            sb.append("x = x + 1\n");
        }
        sb.append("return x");
        try (LuaState s = new LuaState()) {
            LuaValue res = s.eval(sb.toString());
            assertEquals(4500L, res.toLong());
        }
    }
}

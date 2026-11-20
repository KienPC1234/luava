/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.security;

import org.junit.jupiter.api.Test;
import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.JavaCapability;
import org.luava.runtime.ExecutionPolicy;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaState.CompiledChunk;
import org.luava.runtime.LuaStatePool;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.jit.JitCompiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security audit regression gate covering SEC-01 through SEC-14:
 * pool guard retention, ownership/lease verification, tenant isolation,
 * sandbox lifecycle immutability & policy revocation, capability/SAM/allowlist
 * enforcement, memory pre-allocation caps, index bounds, async backpressure,
 * cross-state chunk safety, binary chunk fuzzing, class init deferral, and
 * JIT budget/telemetry guarantees.
 */
public class LuavaSecurityAuditSuiteTest {

    public static final class ExposedService {
        public String safeOp(String arg) {
            return "safe:" + arg;
        }

        public String privilegedAdminOp() {
            return "ADMIN_SECRET";
        }
    }

    public static final class AttenuatingFactoryService {
        private final ExposedService delegate = new ExposedService();

        public String safeSummary() {
            return "summary";
        }

        public int safeInt() {
            return 42;
        }

        public List<String> safeStringList() {
            return List.of("a", "b");
        }

        public ExposedService leakedObject() {
            return delegate;
        }

        public List<ExposedService> leakedList() {
            return List.of(delegate);
        }

        public Map<String, ExposedService> leakedMap() {
            return Collections.singletonMap("svc", delegate);
        }

        public Optional<ExposedService> leakedOptional() {
            return Optional.of(delegate);
        }

        public ExposedService[] leakedArray() {
            return new ExposedService[]{delegate};
        }

        public JavaCapability attenuatedChild() {
            return new JavaCapability(delegate, "safeOp");
        }

        public List<Object> deeplyNestedList() {
            List<Object> cur = List.of("leaf");
            for (int i = 0; i < 40; i++) {
                cur = List.of(cur);
            }
            return cur;
        }

        public List<Integer> oversizedList() {
            return Collections.nCopies(70_000, 1);
        }

        public List<Integer> massiveVirtualList() {
            return Collections.nCopies(100_000_000, 1);
        }

        public Map<String, Integer> massiveVirtualMap() {
            return new AbstractMap<>() {
                @Override
                public int size() {
                    return 100_000;
                }

                @Override
                public Set<Entry<String, Integer>> entrySet() {
                    throw new AssertionError("massive map entries must never be iterated before size check");
                }
            };
        }
    }

    public static final class DeniedSamImpl implements LongUnaryOperator {
        @Override
        public long applyAsLong(long operand) {
            return operand + 100;
        }
    }

    private static final AtomicBoolean LAZY_CLASS_INITIALIZED = new AtomicBoolean(false);

    public static final class LazyInitClass {
        static {
            LAZY_CLASS_INITIALIZED.set(true);
        }

        public static int value() {
            return 7;
        }
    }

    @Test
    void poolGuardRetentionTest() {
        ExecutionPolicy policy = ExecutionPolicy.builder()
                .sandbox(true)
                .instructionLimit(20_000)
                .timeout(Duration.ofSeconds(2))
                .maxAllocationBytes(1024)
                .cancellable(true)
                .build();

        try (LuaStatePool pool = LuaStatePool.stateful(1, policy);
             CompiledChunk okChunk = LuaState.compileChunk("local s = 0 for i = 1, 20 do s = s + i end return s", "ok");
             CompiledChunk spinChunk = LuaState.compileChunk("while true do end", "spin");
             CompiledChunk allocChunk = LuaState.compileChunk("return string.rep('A', 4096)", "alloc")) {

            // Multiple consecutive acquires enforce the exact policy every time.
            for (int round = 0; round < 5; round++) {
                assertEquals(210L, pool.eval(okChunk).toLong());
                assertThrows(LuaException.class, () -> pool.eval(allocChunk));
            }

            // Tripped runaway instruction limit marks state tainted & retired, next lease gets fresh governed state.
            assertThrows(LuaException.class, () -> pool.eval(spinChunk));
            assertEquals(0, pool.idleCount());
            assertEquals(210L, pool.eval(okChunk).toLong());
            assertThrows(LuaException.class, () -> pool.eval(spinChunk));
        }
    }

    @Test
    void poolOwnershipAndActiveLimitTest() throws Exception {
        ExecutionPolicy policy = ExecutionPolicy.builder().sandbox(true).build();
        try (LuaStatePool pool = LuaStatePool.builder()
                .isolationMode(LuaStatePool.IsolationMode.ISOLATED)
                .maxTotal(2)
                .maxIdle(1)
                .acquireTimeout(Duration.ofMillis(120))
                .policy(policy)
                .build();
             LuaState foreign = new LuaState()) {

            // 1. Foreign state rejected
            assertThrows(LuaException.class, () -> pool.release(foreign));

            // 2. Concurrent borrowing cannot exceed maxTotal; third fails with timeout
            LuaStatePool.Lease l1 = pool.acquireLease();
            LuaStatePool.Lease l2 = pool.acquireLease();
            assertEquals(2, pool.activeCount());
            assertEquals(0, pool.availablePermits());
            assertThrows(LuaException.class, pool::acquireLease);

            // 3. Double release on raw LuaState throws; idempotent Lease.close() works safely
            LuaState raw1 = l1.state();
            l1.close();
            l1.close(); // idempotent on Lease
            assertThrows(LuaException.class, () -> pool.release(raw1));
            assertEquals(1, pool.activeCount());

            // 4. Closing pool while l2 is still leased handles cleanup cleanly on release
            pool.close();
            assertTrue(pool.isClosed());
            assertThrows(LuaException.class, pool::acquireLease);
            assertFalse(raw1 == l2.state());
            LuaState raw2 = l2.state();
            l2.close();
            assertTrue(raw2.isClosed());
            assertEquals(0, pool.activeCount());
        }
    }

    @Test
    void poolTenantIsolationTest() {
        ExecutionPolicy policy = ExecutionPolicy.builder()
                .sandbox(true)
                .instructionLimit(100_000)
                .build();

        try (LuaStatePool pool = LuaStatePool.isolated(2, policy);
             CompiledChunk tenantA = LuaState.compileChunk("""
                     secret = 'tenantA-secret'
                     getmetatable('').__index.pwn = function() return 'leaked' end
                     return { key = 'val', nested = { 10, 20 } }
                     """, "tenantA");
             CompiledChunk tenantB = LuaState.compileChunk("""
                     return {
                       hasSecret = secret ~= nil,
                       hasPwn = ("").pwn ~= nil
                     }
                     """, "tenantB");
             CompiledChunk tableReturn = LuaState.compileChunk("return { a = 42, b = 'ok' }", "retTbl");
             CompiledChunk closureReturn = LuaState.compileChunk("return function() return 1 end", "retFn")) {

            LuaValue outA = pool.eval(tenantA);
            assertEquals("val", outA.get(org.luava.runtime.LuaString.valueOf("key")).toLuaString());

            LuaValue outB = pool.eval(tenantB);
            assertFalse(outB.get(org.luava.runtime.LuaString.valueOf("hasSecret")).toBoolean(),
                    "tenant B must never see tenant A globals");
            assertFalse(outB.get(org.luava.runtime.LuaString.valueOf("hasPwn")).toBoolean(),
                    "tenant B must never see tenant A metatable mutations");

            // SEC-13: isolated eval snapshots plain tables and blocks state-bound closures/userdata from escaping
            LuaValue tbl = pool.eval(tableReturn);
            assertEquals(42L, tbl.get(org.luava.runtime.LuaString.valueOf("a")).toLong());
            assertEquals("ok", tbl.get(org.luava.runtime.LuaString.valueOf("b")).toLuaString());
            assertThrows(LuaException.class, () -> pool.eval(closureReturn));
            // Scoped execute() allows inspecting live functions cleanly inside the lease lifetime
            assertEquals(1L, pool.execute(closureReturn, LuaValue::call).toLong());
            assertTrue(pool.warmIdle(1) <= 1);
        }
    }

    @Test
    void policyRevocationTest() {
        // 1. Converting an already-executed state into a sandbox is forbidden
        try (LuaState trusted = new LuaState()) {
            trusted.eval("trusted_flag = 123");
            assertThrows(LuaException.class, trusted::sandbox);
        }

        // 2. Widening a sandbox after code execution is forbidden
        try (LuaState sb = new LuaState().sandbox()) {
            sb.eval("return 1 + 1");
            assertThrows(LuaException.class, () -> sb.allow("os"));
            assertThrows(LuaException.class, () -> sb.allow("java"));
            assertThrows(LuaException.class, () -> sb.javaAllow("java.lang.Runtime"));
            assertThrows(LuaException.class, () -> sb.javaPolicy(JavaAccessPolicy.UNRESTRICTED));
        }

        // 3. Cached method invoker captured BEFORE tightening policy stops working IMMEDIATELY after revocation
        try (LuaState s = new LuaState()) {
            s.setLive("buf", new StringBuilder("abc"));
            s.eval("cachedAppend = buf.append; return cachedAppend('d'):toString()");
            // Revoke StringBuilder access dynamically
            s.javaDeny("java.lang.StringBuilder");
            LuaException ex = assertThrows(LuaException.class,
                    () -> s.eval("return cachedAppend('e'):toString()"));
            assertTrue(ex.getMessage().contains("access denied"),
                    "expected cached invoker revocation, got: " + ex.getMessage());
        }
    }

    @Test
    void hostObjectCapabilityAndSamPolicyTest() {
        // 1. JavaCapability exposes ONLY allowlisted methods (no unlisted methods, fields, or getClass)
        ExposedService svc = new ExposedService();
        try (LuaState s = new LuaState().sandbox()) {
            s.setCapability("svc", svc, "safeOp");
            assertEquals("safe:hello", s.eval("return svc.safeOp('hello')").toLuaString());
            assertEquals("safe:colon", s.eval("return svc:safeOp('colon')").toLuaString());
            assertTrue(s.eval("return svc.privilegedAdminOp == nil").toBoolean());
            assertTrue(s.eval("return svc.getClass == nil").toBoolean());
            assertTrue(s.eval("return svc.wait == nil").toBoolean());
            assertThrows(LuaException.class, () -> s.eval("svc.anyField = 1"));
        }

        // 2. STRICT_ALLOWLIST blocks un-allowlisted host objects & their SAM call paths
        try (LuaState strict = new LuaState()
                .sandbox()
                .javaPolicy(JavaAccessPolicy.STRICT_ALLOWLIST)) {
            strict.setLive("rawSvc", svc);
            strict.setLive("rawSam", new DeniedSamImpl());
            strict.setCapability("capSvc", svc, "safeOp");

            assertTrue(strict.eval("return rawSvc.safeOp == nil").toBoolean(),
                    "STRICT_ALLOWLIST must block non-allowlisted host object methods");
            LuaException samEx = assertThrows(LuaException.class, () -> strict.eval("return rawSam(5)"));
            assertTrue(samEx.getMessage().contains("access denied"));
            assertEquals("safe:ok", strict.eval("return capSvc.safeOp('ok')").toLuaString(),
                    "capability wrapper still explicitly grants approved operations");
        }

        // 3. Denied SAM class is always rejected even under permissive member access (SEC-08)
        try (LuaState s = new LuaState().sandbox().javaDeny(DeniedSamImpl.class.getName())) {
            s.setLive("blockedSam", new DeniedSamImpl());
            LuaException ex = assertThrows(LuaException.class, () -> s.eval("return blockedSam(1)"));
            assertTrue(ex.getMessage().contains("access denied"));
        }

        // 4. SEC-14: strict java.import & instanceof do not trigger premature static initializers
        String lazyClassName = LuavaSecurityAuditSuiteTest.class.getName() + "$LazyInitClass";
        try (LuaState s = new LuaState()
                .sandbox()
                .allow("java")
                .javaAllow(lazyClassName)) {
            s.eval("local cls = java.import('" + lazyClassName + "'); assert(cls ~= nil)");
            assertFalse(LAZY_CLASS_INITIALIZED.get(),
                    "STRICT import must defer class static initializer execution until member use");
            assertEquals(7L, s.eval("return java.import('" + lazyClassName + "').value()").toLong());
            assertTrue(LAZY_CLASS_INITIALIZED.get());
        }
    }

    @Test
    void sandboxMemoryBoundTest() {
        // Pre-allocation checks catch sparse/pre-sized table bombs BEFORE allocating huge backing arrays
        try (LuaState s = new LuaState().sandbox().maxMemoryBytes(256 * 1024L)) {
            LuaException tblBomb = assertThrows(LuaException.class, () -> s.eval("""
                    collectgarbage('stop')
                    local t = {}
                    for i = 1, 100000 do
                        t[i] = i
                    end
                    """));
            assertTrue(tblBomb.getMessage().contains("memory limit"),
                    "expected memory limit error on large sequential array, got: " + tblBomb.getMessage());

            LuaException hashBomb = assertThrows(LuaException.class, () -> s.eval("""
                    collectgarbage('stop')
                    local h = {}
                    for i = 1, 50000 do
                        h['k_' .. i] = i
                    end
                    """));
            assertTrue(hashBomb.getMessage().contains("memory limit"),
                    "expected memory limit error on large hash table, got: " + hashBomb.getMessage());
        }
    }

    @Test
    void interopIndexBoundsTest() {
        try (LuaState s = new LuaState().sandbox()) {
            int[] arr = {11, 22, 33};
            List<String> list = new ArrayList<>(List.of("a", "b"));
            s.setLive("arr", arr);
            s.setLive("list", list);

            // 2^32 + 1 = 4294967297 -> narrowing to int used to produce 1 (idx 0)!
            assertTrue(s.eval("return arr[4294967297] == nil").toBoolean());
            assertTrue(s.eval("return arr[-4294967295] == nil").toBoolean());
            assertTrue(s.eval("return arr[0] == nil").toBoolean());
            assertTrue(s.eval("return list[4294967297] == nil").toBoolean());

            assertThrows(LuaException.class, () -> s.eval("arr[4294967297] = 999"));
            assertThrows(LuaException.class, () -> s.eval("list[4294967297] = 'pwn'"));
            assertEquals(11, arr[0]);
            assertEquals("a", list.get(0));
        }
    }

    @Test
    void asyncQueuePressureAndCancellationTest() throws Exception {
        // 1. REJECT policy rejects submissions past maxPendingAsyncTasks
        try (LuaState s = new LuaState()
                .maxPendingAsyncTasks(2)
                .asyncRejectionPolicy(LuaState.AsyncRejectionPolicy.REJECT)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            s.registerFunction("awaitGate", () -> {
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            });

            CompletableFuture<LuaValue> f1 = s.evalAsync("awaitGate(); return 1");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<LuaValue> f2 = s.evalAsync("return 2");
            assertThrows(LuaException.class, () -> s.evalAsync("return 3"));

            gate.countDown();
            assertEquals(1L, f1.get(5, TimeUnit.SECONDS).toLong());
            assertEquals(2L, f2.get(5, TimeUnit.SECONDS).toLong());
            assertEquals(0, s.getPendingAsyncTaskCount());
        }

        // 2. Async cancellation unwinds cleanly and frees pending queue slots
        try (LuaState s = new LuaState()
                .cancellable()
                .maxPendingAsyncTasks(4)) {
            CountDownLatch entered = new CountDownLatch(1);
            s.registerFunction("markEntered", () -> {
                entered.countDown();
                return true;
            });
            CompletableFuture<LuaValue> spin = s.evalAsync("markEntered(); while true do end");
            CompletableFuture<LuaValue> queued = s.evalAsync("return 99");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            s.cancel();

            assertThrows(Exception.class, () -> spin.get(5, TimeUnit.SECONDS));
            assertThrows(Exception.class, () -> queued.get(5, TimeUnit.SECONDS));
            s.resetCancellation();
            assertEquals(42L, s.evalAsync("return 42").get(5, TimeUnit.SECONDS).toLong());
            assertEquals(0, s.getPendingAsyncTaskCount());
        }

        // 3. CALLER_BLOCKS backpressure blocks caller until slot opens without dropping work
        try (LuaState s = new LuaState()
                .maxPendingAsyncTasks(1)
                .asyncRejectionPolicy(LuaState.AsyncRejectionPolicy.CALLER_BLOCKS)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch gate = new CountDownLatch(1);
            s.registerFunction("awaitBlock", () -> {
                entered.countDown();
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return 1;
            });
            CompletableFuture<LuaValue> first = s.evalAsync("awaitBlock(); return 1");
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            CompletableFuture<LuaValue> secondSubmitted = new CompletableFuture<>();
            Thread submitter = new Thread(() -> secondSubmitted.complete(s.evalAsync("return 7").join()), "backpressure");
            submitter.setDaemon(true);
            submitter.start();
            Thread.sleep(50);
            assertFalse(secondSubmitted.isDone(), "CALLER_BLOCKS must hold caller while queue is full");

            gate.countDown();
            assertEquals(1L, first.get(5, TimeUnit.SECONDS).toLong());
            assertEquals(7L, secondSubmitted.get(5, TimeUnit.SECONDS).toLong());
            submitter.join(2000);
        }
    }

    @Test
    void crossStateCompiledChunkTest() {
        try (CompiledChunk shared = LuaState.compileChunk("""
                shared_global = (shared_global or 0) + 1
                local ok = pcall(function() return java.import('java.lang.Runtime') end)
                return shared_global, ok
                """, "shared.lua");
             LuaState unrestricted = new LuaState().javaPolicy(JavaAccessPolicy.UNRESTRICTED);
             LuaState restricted = new LuaState().sandbox();
             LuaState openOther = new LuaState().javaPolicy(JavaAccessPolicy.UNRESTRICTED)) {

            org.luava.runtime.Varargs r1 = (org.luava.runtime.Varargs) unrestricted.eval(shared);
            assertEquals(1L, r1.arg(1).toLong());
            assertTrue(r1.arg(2).toBoolean());

            org.luava.runtime.Varargs r2 = (org.luava.runtime.Varargs) restricted.eval(shared);
            assertEquals(1L, r2.arg(1).toLong(), "compiled chunk must not share globals across states");
            assertFalse(r2.arg(2).toBoolean(), "compiled chunk must enforce receiving state's sandbox policy");

            org.luava.runtime.Varargs r3 = (org.luava.runtime.Varargs) openOther.eval(shared);
            assertEquals(1L, r3.arg(1).toLong());
            assertTrue(r3.arg(2).toBoolean());
        }
    }

    @Test
    void binaryChunkFuzzTest() {
        byte[] validDump;
        try (LuaState donor = new LuaState()) {
            LuaValue v = donor.eval("return string.dump(function(a, b) return (a or 1) + (b or 2) end)");
            validDump = v.toLuaString().getBytes(StandardCharsets.ISO_8859_1);
        }

        try (LuaState target = new LuaState().sandbox().maxAllocationBytes(64 * 1024).instructionLimit(50_000)) {
            Random rng = new Random(0x5EED2026L);
            for (int iter = 0; iter < 250; iter++) {
                byte[] mutated = validDump.clone();
                int mutations = 1 + rng.nextInt(5);
                for (int m = 0; m < mutations; m++) {
                    int idx = rng.nextInt(mutated.length);
                    mutated[idx] ^= (byte) (1 + rng.nextInt(255));
                }
                if (rng.nextInt(5) == 0 && mutated.length > 10) {
                    int cut = rng.nextInt(mutated.length);
                    byte[] trunc = new byte[cut];
                    System.arraycopy(mutated, 0, trunc, 0, cut);
                    mutated = trunc;
                }
                target.set("fuzzed", new String(mutated, StandardCharsets.ISO_8859_1));
                LuaValue ok = target.eval("local f, err = load(fuzzed, 'fuzz', 'b'); return f == nil or type(f) == 'function'");
                assertTrue(ok.toBoolean());
            }

            // Overlapping LUAVA_V1 footer offset attempting to bleed into header must be rejected safely
            byte[] overlapFooter = new byte[64];
            System.arraycopy(validDump, 0, overlapFooter, 0, Math.min(31, validDump.length));
            ByteBuffer.wrap(overlapFooter, 52, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(52);
            System.arraycopy("LUAVA_V1".getBytes(StandardCharsets.US_ASCII), 0, overlapFooter, 56, 8);
            target.set("overlap", new String(overlapFooter, StandardCharsets.ISO_8859_1));
            assertFalse(target.eval("local f = load(overlap, 'ov', 'b'); return f ~= nil").toBoolean());

            // Deeply nested expression bomb fails gracefully without StackOverflowError leaking
            String deepExpr = "return " + "(".repeat(400) + "1" + ")".repeat(400);
            target.set("deepSrc", deepExpr);
            assertFalse(target.eval("local f = load(deepSrc); return f ~= nil").toBoolean());
        }
    }

    @Test
    void jitBudgetConsistencyTest() {
        String prog = """
                function loopSum(n)
                    local acc = 0
                    for i = 1, n do acc = acc + i end
                    return acc
                end
                """;
        // Verify identical results & instruction-limit enforcement across interpreter and guarded JIT
        try (LuaState interp = new LuaState().jitEnabled(false).instructionLimit(20_000);
             LuaState jit = new LuaState().jitEnabled(true).instructionLimit(20_000)) {
            interp.eval(prog);
            jit.eval(prog);
            if (jit.get("loopSum") instanceof LuaClosure lc) {
                JitCompiler.prewarm(lc, jit);
            }
            assertEquals(interp.call("loopSum", 100).toLong(), jit.call("loopSum", 100).toLong());
            assertThrows(LuaException.class, () -> interp.call("loopSum", 10_000_000));
            assertThrows(LuaException.class, () -> jit.call("loopSum", 10_000_000));

            JitCompiler.Telemetry stats = JitCompiler.telemetry();
            assertFalse(stats.globallyDisabled());
            assertEquals(0L, stats.vmErrorCount());
        }
    }

    @Test
    void concurrentIsolatedPoolStressTest() throws Exception {
        ExecutionPolicy policy = ExecutionPolicy.builder()
                .sandbox(true)
                .instructionLimit(200_000)
                .maxMemoryBytes(512 * 1024L)
                .build();
        try (LuaStatePool pool = LuaStatePool.builder()
                .isolationMode(LuaStatePool.IsolationMode.ISOLATED)
                .maxTotal(4)
                .maxIdle(4)
                .acquireTimeout(Duration.ofSeconds(5))
                .policy(policy)
                .build();
             CompiledChunk chunk = LuaState.compileChunk("""
                     local me = secret_tag
                     assert(me == nil)
                     secret_tag = 999
                     local s = 0
                     for i = 1, 50 do s = s + i end
                     return s
                     """, "stress.lua")) {
            AtomicInteger maxObservedActive = new AtomicInteger();
            ExecutorService exec = Executors.newFixedThreadPool(8);
            try {
                List<Future<Long>> futures = new ArrayList<>();
                for (int i = 0; i < 40; i++) {
                    futures.add(exec.submit(() -> {
                        try (LuaStatePool.Lease lease = pool.acquireLease()) {
                            int act = pool.activeCount();
                            maxObservedActive.accumulateAndGet(act, Math::max);
                            return lease.eval(chunk).toLong();
                        }
                    }));
                }
                for (Future<Long> f : futures) {
                    assertEquals(1275L, f.get(10, TimeUnit.SECONDS));
                }
                assertTrue(maxObservedActive.get() <= 4,
                        "active states exceeded maxTotal=4: " + maxObservedActive.get());
                assertEquals(0, pool.activeCount());
                assertNotEquals(-1, pool.availablePermits());
            } finally {
                exec.shutdownNow();
            }
        }
    }

    @Test
    void capabilityReturnAttenuationTest() {
        try (LuaState state = new LuaState().sandbox()) {
            state.setCapability("svc", new AttenuatingFactoryService(),
                    "safeSummary", "safeInt", "safeStringList",
                    "leakedObject", "leakedList", "leakedMap", "leakedOptional", "leakedArray",
                    "attenuatedChild", "deeplyNestedList", "oversizedList",
                    "massiveVirtualList", "massiveVirtualMap");

            assertEquals("summary", state.eval("return svc:safeSummary()").toLuaString());
            assertEquals(42L, state.eval("return svc:safeInt()").toLong());
            assertEquals("a:b", state.eval("local l = svc:safeStringList(); return l[1] .. ':' .. l[2]").toLuaString());

            // Unwrapped Java objects and containers carrying unwrapped Java objects must fail closed.
            for (String call : List.of(
                    "return svc:leakedObject()",
                    "return svc:leakedList()",
                    "return svc:leakedMap()",
                    "return svc:leakedOptional()",
                    "return svc:leakedArray()",
                    "return svc:deeplyNestedList()",
                    "return svc:oversizedList()",
                    "return svc:massiveVirtualList()",
                    "return svc:massiveVirtualMap()")) {
                LuaException err = assertThrows(LuaException.class, () -> state.eval(call));
                assertTrue(err.getMessage().contains("access denied"),
                        "Expected access denied for '" + call + "', got: " + err.getMessage());
            }

            // Explicit JavaCapability returns stay attenuated to their own method allowlist.
            assertEquals("safe:ok", state.eval("local c = svc:attenuatedChild(); return c:safeOp('ok')").toLuaString());
            assertTrue(state.eval("local c = svc:attenuatedChild(); return c.privilegedAdminOp == nil").toBoolean());
            LuaException writeErr = assertThrows(LuaException.class,
                    () -> state.eval("local c = svc:attenuatedChild(); c.x = 1"));
            assertTrue(writeErr.getMessage().contains("access denied"));
        }
    }

    @Test
    void asyncLeaseLifecycleRetiresInFlightStateTest() throws Exception {
        try (LuaStatePool pool = LuaStatePool.stateful(2, ExecutionPolicy.builder()
                .sandbox(true)
                .instructionLimit(5_000_000)
                .build())) {
            LuaState leakedRef;
            CompletableFuture<LuaValue> inFlight;
            try (LuaStatePool.Lease lease = pool.acquireLease()) {
                leakedRef = lease.state();
                inFlight = leakedRef.evalAsync("""
                        local s = 0
                        for i = 1, 200000 do s = s + i end
                        marker_from_async = s
                        return s
                        """, "async.lua");
                // Release immediately while async task is queued or active.
            }

            assertEquals(0, pool.activeCount());
            assertEquals(0, pool.idleCount(), "state with in-flight async work must be retired, not returned idle");
            assertEquals(2, pool.availablePermits());
            assertTrue(leakedRef.isClosed(), "retired state must be closed cleanly");

            ExecutionException asyncErr = assertThrows(ExecutionException.class,
                    () -> inFlight.get(5, TimeUnit.SECONDS));
            assertTrue(asyncErr.getCause() instanceof LuaException);

            try (LuaStatePool.Lease next = pool.acquireLease()) {
                assertTrue(next.eval("return marker_from_async == nil").toBoolean(),
                        "subsequent borrower must receive a fresh state");
            }
        }
    }

    @Test
    void duplicateFactoryStateRejectedWithoutCorruptingPoolTest() {
        LuaState shared = new LuaState();
        try (LuaStatePool pool = LuaStatePool.builder()
                .isolationMode(LuaStatePool.IsolationMode.STATEFUL)
                .maxTotal(2)
                .maxIdle(2)
                .factory(() -> shared)
                .build()) {
            try (LuaStatePool.Lease first = pool.acquireLease()) {
                assertEquals(1, pool.activeCount());
                assertEquals(1, pool.availablePermits());

                LuaException dupErr = assertThrows(LuaException.class, pool::acquireLease);
                assertTrue(dupErr.getMessage().contains("already managed")
                        || dupErr.getMessage().contains("already leased"));
                assertEquals(1, pool.activeCount());
                assertEquals(1, pool.availablePermits(), "failed acquire must release its permit");
                assertFalse(first.state().isClosed(), "rejected duplicate must not close active borrower's state");
                assertEquals(7L, first.eval("return 3 + 4").toLong());
            }
            assertEquals(0, pool.activeCount());
            assertEquals(2, pool.availablePermits());
        }
    }

    @Test
    void staleStateAfterStatefulReleaseIsBlockedInIdleTest() {
        try (LuaStatePool pool = LuaStatePool.stateful(1, ExecutionPolicy.builder().sandbox(true).build())) {
            LuaState retained;
            LuaStatePool.Lease releasedLease;
            try (LuaStatePool.Lease lease = pool.acquireLease()) {
                releasedLease = lease;
                retained = lease.state();
                assertEquals(10L, lease.eval("x = 10; return x").toLong());
            }
            assertEquals(1, pool.idleCount());
            assertThrows(LuaException.class, releasedLease::state);
            assertThrows(LuaException.class, () -> releasedLease.eval("return 1"));
            LuaException idleUse = assertThrows(LuaException.class, () -> retained.eval("x = 999"));
            assertTrue(idleUse.getMessage().contains("idle"));
        }
    }

    @Test
    void memoryLimitBestEffortPressureTest() {
        ExecutionPolicy strictMem = ExecutionPolicy.builder()
                .sandbox(true)
                .maxMemoryBytes(64 * 1024L)
                .instructionLimit(5_000_000)
                .build();
        try (LuaStatePool pool = LuaStatePool.stateful(1, strictMem)) {
            try (LuaStatePool.Lease lease = pool.acquireLease()) {
                LuaException oom = assertThrows(LuaException.class, () -> lease.eval("""
                        local t = {}
                        for i = 1, 5000 do
                            t[i] = { i, i + 1, i + 2, string.rep('x', 128) }
                        end
                        """));
                assertTrue(oom.getMessage().contains("memory"),
                        "Expected memory limit error, got: " + oom.getMessage());
            }
            assertEquals(0, pool.idleCount(), "state tripping maxMemoryBytes must be tainted and discarded");
            assertEquals(1, pool.availablePermits());
        }
    }

    @Test
    void asyncCallerBlocksContinuationResubmissionTest() throws Exception {
        try (LuaState s = new LuaState()
                .maxPendingAsyncTasks(1)
                .asyncRejectionPolicy(LuaState.AsyncRejectionPolicy.CALLER_BLOCKS)) {
            CompletableFuture<LuaValue> chain = s.evalAsync("return 1");
            for (int step = 2; step <= 6; step++) {
                final int expectedNext = step;
                chain = chain.thenCompose(prev -> s.evalAsync("return " + (prev.toLong() + 1) + " -- " + expectedNext));
            }
            assertEquals(6L, chain.get(5, TimeUnit.SECONDS).toLong());
            assertEquals(0, s.getPendingAsyncTaskCount(),
                    "pending count must reach 0 before future completion notifies waiters/continuations");
        }
    }

    @Test
    void crossPoolDuplicateFactoryStateRejectedWithoutClosingOtherPoolTest() {
        LuaState shared = new LuaState();
        try (LuaStatePool poolA = LuaStatePool.builder()
                .isolationMode(LuaStatePool.IsolationMode.STATEFUL)
                .maxTotal(1)
                .maxIdle(1)
                .factory(() -> shared)
                .build();
             LuaStatePool poolB = LuaStatePool.builder()
                     .isolationMode(LuaStatePool.IsolationMode.STATEFUL)
                     .maxTotal(1)
                     .maxIdle(1)
                     .factory(() -> shared)
                     .build()) {
            try (LuaStatePool.Lease leaseA = poolA.acquireLease()) {
                LuaException crossErr = assertThrows(LuaException.class, poolB::acquireLease);
                assertTrue(crossErr.getMessage().contains("already managed")
                        || crossErr.getMessage().contains("already owned"));
                assertEquals(0, poolB.activeCount());
                assertEquals(1, poolB.availablePermits());
                assertFalse(leaseA.state().isClosed(), "poolB rejection must not close poolA's active state");
                assertEquals(9L, leaseA.eval("return 4 + 5").toLong());
            }
        }
    }

    @Test
    void warmIdleDoesNotExceedMaxTotalTest() {
        try (LuaStatePool pool = LuaStatePool.builder()
                .isolationMode(LuaStatePool.IsolationMode.STATEFUL)
                .maxTotal(2)
                .maxIdle(2)
                .policy(ExecutionPolicy.builder().sandbox(true).build())
                .build()) {
            LuaStatePool.Lease first = pool.acquireLease();
            LuaStatePool.Lease second = pool.acquireLease();
            assertEquals(2, pool.activeCount());
            assertEquals(2, pool.totalCount());

            first.close();
            assertEquals(1, pool.activeCount());
            assertEquals(1, pool.idleCount());
            assertEquals(2, pool.totalCount());

            int created = pool.warmIdle(2);
            assertEquals(0, created, "warmIdle must not exceed maxTotal across active + idle states");
            assertEquals(1, pool.activeCount());
            assertEquals(1, pool.idleCount());
            assertEquals(2, pool.totalCount());
            assertEquals(1, pool.availablePermits());

            second.close();
            assertEquals(0, pool.activeCount());
            assertEquals(2, pool.idleCount());
            assertEquals(2, pool.totalCount());
            assertEquals(2, pool.availablePermits());
        }
    }
}

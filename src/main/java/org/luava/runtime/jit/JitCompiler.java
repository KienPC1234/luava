/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.bytecode.OpCode;

/**
 * Compiles eligible Lua protos to hidden JVM classes (one method per Lua
 * function) and caches them. Anything unexpected disables JIT for that
 * proto and the interpreter transparently takes over.
 *
 * <p>Compilation normally happens on a single background daemon thread so a
 * hot request never pays compile latency (server-friendly); set
 * {@code -Dluava.jit.sync=true} for deterministic synchronous tier-up in
 * benchmarks and tests.
 */
public final class JitCompiler {
    private static final AtomicInteger CLASS_SEQ = new AtomicInteger();
    private static final JitCodeCache CACHE = new JitCodeCache();
    private static final LinkedBlockingQueue<LuaProto> QUEUE = new LinkedBlockingQueue<>();
    private static volatile boolean workerStarted;

    private JitCompiler() {}

    /**
     * Requests compilation of {@code proto}. Synchronous when
     * {@code luava.jit.sync} is set, otherwise enqueued for the background
     * compiler thread (idempotent; duplicate requests collapse).
     */
    public static void requestCompile(LuaProto proto) {
        if (proto.jitCode != null || proto.jitDisabled || proto.jitQueued) {
            return;
        }
        if (Boolean.getBoolean("luava.jit.sync")) {
            proto.jitQueued = true;
            try {
                tryCompile(proto);
            } finally {
                proto.jitQueued = false;
            }
            return;
        }
        proto.jitQueued = true;
        startWorker();
        if (!QUEUE.offer(proto)) {
            proto.jitQueued = false;
        }
    }

    /**
     * Synchronously compiles every eligible proto in the closure tree.
     * Hosts call this once after loading scripts (pre-warm) so steady-state
     * requests never observe compile or C2 warmup on the hot path.
     */
    public static void prewarm(LuaClosure closure) {
        if (closure == null || closure.proto == null) {
            return;
        }
        prewarmProto(closure.proto);
    }

    private static void prewarmProto(LuaProto proto) {
        tryCompile(proto);
        for (LuaProto child : proto.protos) {
            prewarmProto(child);
        }
    }

    private static void startWorker() {
        if (workerStarted) {
            return;
        }
        synchronized (JitCompiler.class) {
            if (workerStarted) {
                return;
            }
            Thread worker = new Thread(JitCompiler::drain, "luava-jit");
            worker.setDaemon(true);
            worker.start();
            workerStarted = true;
        }
    }

    private static void drain() {
        while (true) {
            try {
                LuaProto proto = QUEUE.take();
                try {
                    tryCompile(proto);
                } finally {
                    proto.jitQueued = false;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                // Never let the compiler thread die; the proto simply stays
                // interpreted (jitDisabled is set inside tryCompile paths).
            }
        }
    }

    /**
     * Attempts to compile {@code proto}. Returns the code on success, null
     * when the proto is outside the JIT subset. Never throws: failures are
     * reported as null and the proto is marked {@code jitDisabled} so the
     * caller stops retrying and continues interpreted. Idempotent.
     */
    public static synchronized JitCode tryCompile(LuaProto proto) {
        if (proto.jitCode != null || proto.jitDisabled) {
            return proto.jitCode;
        }
        boolean debug = Boolean.getBoolean("luava.jit.debug");
        try {
            LuaToJvmTranslator.Info info = LuaToJvmTranslator.analyze(proto);
            if (info == null || !eligible(proto)) {
                proto.jitDisabled = true;
                if (debug) {
                    System.err.println("[jit] not eligible: " + proto.name);
                }
                return null;
            }
            String name = "org/luava/runtime/jit/Gen$" + CLASS_SEQ.getAndIncrement();
            LuaToJvmTranslator.Translation tr = LuaToJvmTranslator.translate(proto, name);
            if (tr == null) {
                proto.jitDisabled = true;
                return null;
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> cls = lookup.defineHiddenClass(tr.bytes(), true,
                    MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            java.lang.invoke.MethodHandle mh = MethodHandles.lookup().findStatic(cls, "exec",
                    MethodType.methodType(long.class, LuaClosure.class, Object[].class,
                            long[].class, byte[].class, org.luava.runtime.LuaValue[].class, int.class));
            JitCode code = new JitCode(proto, mh, info.pure());
            proto.jitCode = code;
            CACHE.put(proto, code);
            if (debug) {
                System.err.println("[jit] compiled: " + proto.name);
            }
            return code;
        } catch (Throwable t) {
            proto.jitDisabled = true;
            if (debug) {
                System.err.println("[jit] compile failed for " + proto.name + ": " + t);
            }
            return null;
        }
    }

    static boolean eligible(LuaProto proto) {
        if (!LuaToJvmTranslator.eligible(proto)) {
            return false;
        }
        // Conservative yield rule for Phase 2: the integer subset has no
        // table/global access, no varargs, no TAILCALL and no closure
        // creation, so it can never reach coroutine.yield.
        for (int inst : proto.code) {
            int op = org.luava.runtime.bytecode.Instruction.getOp(inst);
            if (op == OpCode.OP_TAILCALL) {
                return false;
            }
        }
        proto.mayYield = false;
        return true;
    }

    public static int cacheSize() {
        return CACHE.size();
    }
}

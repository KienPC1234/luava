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
import java.util.concurrent.atomic.AtomicLong;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;

/**
 * Compiles eligible Lua protos to hidden JVM classes (one method per Lua
 * function) and caches them. Anything unexpected disables JIT for that
 * proto and the interpreter transparently takes over.
 *
 * <p>Compilation normally happens on a background daemon pool so a hot
 * request never pays compile latency (server-friendly); set
 * {@code -Dluava.jit.sync=true} for deterministic synchronous tier-up in
 * benchmarks and tests.
 */
public final class JitCompiler {
    public record Telemetry(
            long compiledCount,
            long failedCount,
            long vmErrorCount,
            boolean globallyDisabled,
            int cacheSize,
            int queueSize) {}

    private static final AtomicInteger CLASS_SEQ = new AtomicInteger();
    private static final AtomicLong COMPILED_COUNT = new AtomicLong();
    private static final AtomicLong REJECTED_COUNT = new AtomicLong();
    private static final AtomicLong FAILED_COUNT = new AtomicLong();
    private static final AtomicLong VM_ERROR_COUNT = new AtomicLong();
    private static final AtomicLong TOTAL_COMPILE_NANOS = new AtomicLong();
    private static final JitCodeCache CACHE = new JitCodeCache();
    private static final int QUEUE_CAPACITY = Integer.getInteger("luava.jit.queueCapacity", 512);
    private static final int WORKER_THREADS = Math.max(2, Math.min(4,
            Integer.getInteger("luava.jit.threads", Runtime.getRuntime().availableProcessors())));
    private static final LinkedBlockingQueue<LuaProto> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static volatile boolean workerStarted;
    private static volatile boolean globalEmergencyDisabled;
    /** One-shot "ASM is missing, you are running interpreted" notice. */
    private static volatile boolean asmMissingReported;

    private JitCompiler() {}

    /**
     * Requests compilation of {@code proto}. Synchronous when
     * {@code luava.jit.sync} is set, otherwise enqueued for the background
     * compiler pool (idempotent; duplicate requests collapse).
     */
    public static void requestCompile(LuaProto proto) {
        requestCompile(proto, false);
    }

    /**
     * Requests compilation of {@code proto}, optionally emitting safepoints
     * for {@link org.luava.runtime.LuaState.LoopGuard}-protected execution.
     */
    public static void requestCompile(LuaProto proto, boolean guarded) {
        if (globalEmergencyDisabled || proto == null) {
            return;
        }
        if (!guarded) {
            if (proto.jitCode != null || proto.jitDisabled || proto.jitQueued) {
                return;
            }
            if (Boolean.getBoolean("luava.jit.sync")) {
                proto.jitQueued = true;
                try {
                    tryCompile(proto, false);
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
            return;
        }
        if (proto.guardedJitCode != null || proto.guardedJitDisabled || proto.guardedJitQueued) {
            return;
        }
        if (Boolean.getBoolean("luava.jit.sync")) {
            proto.guardedJitQueued = true;
            try {
                tryCompile(proto, true);
            } finally {
                proto.guardedJitQueued = false;
            }
            return;
        }
        proto.guardedJitQueued = true;
        startWorker();
        if (!QUEUE.offer(proto)) {
            proto.guardedJitQueued = false;
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

    /**
     * Convenience overload for the common {@code state.compile(...)} /
     * {@code state.eval(...)} result. A non-closure function (a Java-backed
     * {@link org.luava.runtime.LuaFunction}) has no Lua bytecode to compile,
     * so this is a no-op for it rather than an error.
     */
    public static void prewarm(org.luava.runtime.LuaFunction function) {
        if (function instanceof LuaClosure lc) {
            prewarm(lc);
        }
    }

    /**
     * Pre-compiles a whole closure tree, but only when JIT is enabled for
     * the owning state. Prewarming while JIT is off would still allocate
     * hidden classes, contradicting the host's opt-out; this keeps
     * {@code -Dluava.jit=false} / {@code state.jitEnabled(false)} honest.
     */
    public static void prewarm(LuaClosure closure, org.luava.runtime.LuaState state) {
        if (state != null && !state.isJitEnabled()) {
            return;
        }
        if (closure == null || closure.proto == null) {
            return;
        }
        boolean guarded = state != null && state.loopGuard != null;
        prewarmProto(closure.proto, guarded);
    }

    private static void prewarmProto(LuaProto proto) {
        prewarmProto(proto, false);
    }

    private static void prewarmProto(LuaProto proto, boolean guarded) {
        tryCompile(proto, guarded);
        for (LuaProto child : proto.protos) {
            prewarmProto(child, guarded);
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
            for (int i = 0; i < WORKER_THREADS; i++) {
                Thread worker = new Thread(JitCompiler::drain, "luava-jit-" + i);
                worker.setDaemon(true);
                worker.start();
            }
            workerStarted = true;
        }
    }

    private static void drain() {
        while (!globalEmergencyDisabled) {
            try {
                LuaProto proto = QUEUE.take();
                if (proto.jitQueued) {
                    try {
                        tryCompile(proto, false);
                    } finally {
                        proto.jitQueued = false;
                    }
                }
                if (proto.guardedJitQueued) {
                    try {
                        tryCompile(proto, true);
                    } finally {
                        proto.guardedJitQueued = false;
                    }
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (VirtualMachineError vme) {
                if (vme instanceof StackOverflowError) {
                    FAILED_COUNT.incrementAndGet();
                    continue;
                }
                tripEmergencyDisable(vme);
                return;
            } catch (Throwable t) {
                FAILED_COUNT.incrementAndGet();
                if (Boolean.getBoolean("luava.jit.debug")) {
                    System.err.println("[jit] worker exception: " + t);
                }
            }
        }
    }

    private static void tripEmergencyDisable(VirtualMachineError vme) {
        VM_ERROR_COUNT.incrementAndGet();
        globalEmergencyDisabled = true;
        QUEUE.clear();
        if (Boolean.getBoolean("luava.jit.debug")) {
            System.err.println("[jit] emergency disabled after JVM error: " + vme);
        }
    }

    /**
     * Attempts to compile {@code proto}. Returns the code on success, null
     * when the proto is outside the JIT subset. Never throws for normal compile
     * or bytecode fallback errors: failures are reported as null and the proto
     * is marked {@code jitDisabled} so execution continues interpreted.
     */
    public static JitCode tryCompile(LuaProto proto) {
        return tryCompile(proto, false);
    }

    public static JitCode tryCompile(LuaProto proto, boolean guarded) {
        if (globalEmergencyDisabled || proto == null) {
            return null;
        }
        JitCode existing = guarded ? proto.guardedJitCode : proto.jitCode;
        if (existing != null || (guarded ? proto.guardedJitDisabled : proto.jitDisabled)) {
            return existing;
        }
        boolean debug = Boolean.getBoolean("luava.jit.debug");
        synchronized (proto) {
            if (guarded) {
                if (proto.guardedJitCode != null || proto.guardedJitDisabled) {
                    return proto.guardedJitCode;
                }
            } else if (proto.jitCode != null || proto.jitDisabled) {
                return proto.jitCode;
            }
            long t0 = System.nanoTime();
            try {
                LuaToJvmTranslator.AnalysisResult analysis = LuaToJvmTranslator.analyzeFull(proto);
                if (analysis == null) {
                    REJECTED_COUNT.incrementAndGet();
                    if (guarded) {
                        proto.guardedJitDisabled = true;
                    } else {
                        proto.jitDisabled = true;
                    }
                    if (debug) {
                        System.err.println("[jit] not eligible: " + proto.name);
                    }
                    return null;
                }
                // Conservative yield rule: the JIT subset has no global/table
                // writes through metamethods, no varargs and no yield-capable calls
                // (only pure self/monomorphic targets, resolved with guards), so it
                // can never reach coroutine.yield.
                proto.mayYield = false;
                LuaToJvmTranslator.Info info = analysis.info();
                String name = "org/luava/runtime/jit/Gen$" + CLASS_SEQ.getAndIncrement();
                LuaToJvmTranslator.Translation tr =
                        LuaToJvmTranslator.translate(proto, analysis, name, org.luava.runtime.LuaState.ENABLE_OSR, guarded);
                if (tr == null) {
                    REJECTED_COUNT.incrementAndGet();
                    if (guarded) {
                        proto.guardedJitDisabled = true;
                    } else {
                        proto.jitDisabled = true;
                    }
                    return null;
                }
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Class<?> cls = lookup.defineHiddenClass(tr.bytes(), true,
                        MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
                JitEntry entry = (JitEntry) cls.getDeclaredConstructor().newInstance();
                JitCode code;
                if (info.returnsInt()) {
                    java.lang.invoke.MethodHandle mh = MethodHandles.lookup().findStatic(cls, "exec",
                            MethodType.methodType(long.class, LuaClosure.class, Object[].class,
                                    long[].class, byte[].class, org.luava.runtime.LuaValue[].class, int.class));
                    java.lang.invoke.MethodHandle osr = null;
                    if (tr.osrTargets() != null) {
                        osr = MethodHandles.lookup().findStatic(cls, "execOsr",
                                MethodType.methodType(long.class, LuaClosure.class, Object[].class,
                                        long[].class, byte[].class, org.luava.runtime.LuaValue[].class,
                                        int.class, int.class));
                    }
                    code = new JitCode(proto, entry, mh, null, osr, tr.osrTargets(), info.pure(),
                            info.callSafe(), info.leafNoWindow(), true, info.returnsVoid());
                } else {
                    java.lang.invoke.MethodHandle mh = MethodHandles.lookup().findStatic(cls, "execObj",
                            MethodType.methodType(org.luava.runtime.LuaValue.class, LuaClosure.class,
                                    Object[].class, long[].class, byte[].class,
                                    org.luava.runtime.LuaValue[].class, int.class));
                    java.lang.invoke.MethodHandle osr = null;
                    if (tr.osrTargets() != null) {
                        osr = MethodHandles.lookup().findStatic(cls, "execObjOsr",
                                MethodType.methodType(org.luava.runtime.LuaValue.class, LuaClosure.class,
                                        Object[].class, long[].class, byte[].class,
                                        org.luava.runtime.LuaValue[].class, int.class, int.class));
                    }
                    code = new JitCode(proto, entry, null, mh, osr, tr.osrTargets(), info.pure(),
                            info.callSafe(), info.leafNoWindow(), false, false);
                }
                if (guarded ? proto.guardedJitDisabled : proto.jitDisabled) {
                    REJECTED_COUNT.incrementAndGet();
                    return null;
                }
                if (guarded) {
                    proto.guardedJitCode = code;
                } else {
                    proto.jitCode = code;
                }
                CACHE.put(proto, code);
                COMPILED_COUNT.incrementAndGet();
                if (debug) {
                    System.err.println("[jit] compiled" + (guarded ? " (guarded): " : ": ") + proto.name);
                }
                return code;
            } catch (VirtualMachineError vme) {
                if (guarded) {
                    proto.guardedJitDisabled = true;
                } else {
                    proto.jitDisabled = true;
                }
                if (vme instanceof StackOverflowError) {
                    FAILED_COUNT.incrementAndGet();
                    return null;
                }
                tripEmergencyDisable(vme);
                if (!Thread.currentThread().getName().startsWith("luava-jit-")) {
                    throw vme;
                }
                return null;
            } catch (Throwable t) {
                if (guarded) {
                    proto.guardedJitDisabled = true;
                } else {
                    proto.jitDisabled = true;
                }
                FAILED_COUNT.incrementAndGet();
                reportMissingAsm(t);
                if (debug) {
                    System.err.println("[jit] compile failed for " + proto.name + ": " + t);
                }
                return null;
            } finally {
                TOTAL_COMPILE_NANOS.addAndGet(System.nanoTime() - t0);
            }
        }
    }

    /**
     * The released jar shades ASM, so a normal consumer never sees this. It
     * does happen when the engine is run from exploded classes (an IDE,
     * {@code mvn exec}, a shaded-away build): {@code asm} is an optional
     * dependency, so {@code org.luava.shaded.asm.Opcodes} is absent and every
     * compile fails with a {@link NoClassDefFoundError}. That used to be
     * completely silent - the proto is simply marked uncompilable and the
     * engine runs interpreted, so a benchmark of the exploded classes
     * measures the interpreter while reporting it as the JIT. Say so once.
     */
    private static void reportMissingAsm(Throwable t) {
        if (asmMissingReported) {
            return;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NoClassDefFoundError || c instanceof ClassNotFoundException) {
                asmMissingReported = true;
                System.err.println("[jit] disabled: the bytecode translator needs the shaded ASM "
                        + "classes, which are absent on this classpath. Falling back to the "
                        + "interpreter. Run against the packaged jar (target/luava-*.jar), which "
                        + "includes them, or put asm on the classpath.");
                return;
            }
        }
    }

    public static int cacheSize() {
        return CACHE.size();
    }

    public static long totalCompileNanos() {
        return TOTAL_COMPILE_NANOS.get();
    }

    public static long rejectedCount() {
        return REJECTED_COUNT.get();
    }

    public static Telemetry telemetry() {
        return new Telemetry(
                COMPILED_COUNT.get(),
                FAILED_COUNT.get(),
                VM_ERROR_COUNT.get(),
                globalEmergencyDisabled,
                CACHE.size(),
                QUEUE.size());
    }

    public static boolean isGloballyDisabled() {
        return globalEmergencyDisabled;
    }

    public static void disableGlobally() {
        globalEmergencyDisabled = true;
        QUEUE.clear();
    }

    /**
     * Releases all cached JIT kernels and pending queue entries for a proto tree
     * (used when closing a {@link org.luava.runtime.LuaState}).
     */
    public static void invalidateTree(LuaProto proto) {
        if (proto == null) {
            return;
        }
        synchronized (proto) {
            proto.jitDisabled = true;
            proto.guardedJitDisabled = true;
            proto.jitQueued = false;
            proto.guardedJitQueued = false;
            QUEUE.remove(proto);
            CACHE.remove(proto);
            proto.jitCode = null;
            proto.guardedJitCode = null;
        }
        if (proto.protos != null) {
            for (LuaProto child : proto.protos) {
                invalidateTree(child);
            }
        }
    }
}

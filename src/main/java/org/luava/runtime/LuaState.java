/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.luava.binding.JavaAccessPolicy;
import org.luava.binding.JavaFunctionBuilder;
import org.luava.binding.LuaDataConverter;
import org.luava.binding.ModuleBinder;
import org.luava.emmydoc.EmmyDocGenerator;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.eval.Environment;
import org.luava.runtime.standard.BaseLib;
import org.luava.runtime.standard.CoroutineLib;
import org.luava.runtime.standard.MathLib;
import org.luava.runtime.standard.PackageLib;
import org.luava.runtime.standard.StringLib;
import org.luava.runtime.standard.TableLib;
import org.luava.runtime.standard.Utf8Lib;
import org.luava.runtime.standard.OsLib;
import org.luava.runtime.standard.IoLib;
import org.luava.runtime.standard.DebugLib;
import org.luava.runtime.concurrency.LuaCoroutine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Lua universe: globals, registry, main thread, allocator caps, security
 * policy, and value-type metatables.
 *
 * <p>One state supports many coroutines and nested calls, but concurrent
 * top-level {@code eval} calls on the same state from different host threads
 * are not supported. Use one {@code LuaState} per host thread/tenant for
 * concurrent servers; states do not share globals, security policy, value
 * metatables, or explicit finalizer ownership.
 */
public final class LuaState {
    private static final AtomicLong NEXT_GC_OWNER_ID = new AtomicLong(1);
    private final long gcOwnerId = NEXT_GC_OWNER_ID.getAndIncrement();
    private final LuaTable globals = new LuaTable();
    private final LuaTable registry = new LuaTable();
    private final LuaCoroutine mainThread = LuaCoroutine.createMainThread();
    private final EnumMap<LuaType, LuaTable> basicMetatables = new EnumMap<>(LuaType.class);
    private final Environment rootEnvironment;
    private final List<ModuleBinder.ModuleInfo> registeredModules = new ArrayList<>();
    private LuaValue savedLoadfile;
    private LuaValue savedDofile;

    /**
     * Bounded cache of compiled {@code LuaProto} trees keyed by
     * {@code chunkName + '\0' + source}. A server that evaluates the same
     * script on every request otherwise rebuilds a fresh proto each time, so
     * its hot functions can never reach the JIT tier-up threshold (the
     * documented "eval again per request never tiers up" limitation).
     * Reusing the proto tree lets hotness accumulate across evaluations.
     *
     * <p>Per-state (no cross-tenant sharing) and LRU-bounded so script data
     * cannot grow it without limit. Protos are immutable after compilation
     * except for JIT state, which is exactly what must persist.
     */
    private final Map<String, org.luava.runtime.bytecode.LuaProto> protoCache =
            new java.util.LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, org.luava.runtime.bytecode.LuaProto> eldest) {
                    return size() > PROTO_CACHE_MAX;
                }
            };

    private static final int PROTO_CACHE_MAX = 256;

    /**
     * Cooperative VM guard, checked once per instruction while non-null.
     * Set via {@link #instructionLimit(long)} / {@link #timeout(Duration)}
     * to stop runaway scripts ({@code while true do end}) from hanging the
     * host. Null on the default (unguarded) fast path.
     */
    public interface LoopGuard {
        void tick();
    }

    /**
     * Hard instruction/time budget. Unlike a one-shot hook, once tripped it
     * stays tripped for the rest of the top-level run, so {@code pcall} can
     * catch the error but the very next instruction throws again — a script
     * cannot swallow the guard and continue. The budget is re-armed at each
     * top-level {@link #eval} entry (see {@link #armGuard()}).
     */
    public static final class Guard implements LoopGuard {
        private final long maxInstr;
        private final long timeoutNanos;
        private long remaining;
        private long deadline;
        private int countdown;
        private volatile boolean tripped;
        private volatile boolean cancelRequested;
        private volatile boolean timeoutTrip;

        Guard(long maxInstr, long timeoutNanos) {
            this.maxInstr = maxInstr;
            this.timeoutNanos = timeoutNanos;
        }

        void arm() {
            this.remaining = maxInstr;
            this.deadline = timeoutNanos > 0 ? System.nanoTime() + timeoutNanos : 0;
            this.countdown = 4096;
            // Preserve an explicit cancellation: a re-arm by a nested/racing
            // eval must not revive a chunk its caller already abandoned.
            if (!cancelRequested) {
                this.tripped = false;
                this.timeoutTrip = false;
            }
        }

        /** Forces the next {@link #tick()} to fail. Used to cancel a runaway
         * eval whose caller already gave up, so no worker keeps spinning. */
        void cancel() {
            this.timeoutTrip = true;
            this.cancelRequested = true;
            this.tripped = true;
        }

        @Override
        public void tick() {
            if (tripped) {
                throw trip();
            }
            if (maxInstr > 0 && --remaining < 0) {
                tripped = true;
                timeoutTrip = false;
                throw trip();
            }
            if (timeoutNanos > 0 && --countdown <= 0) {
                countdown = 4096;
                if (System.nanoTime() - deadline >= 0) {
                    tripped = true;
                    timeoutTrip = true;
                    throw trip();
                }
            }
        }

        private LuaException trip() {
            return timeoutTrip
                    ? new GuardTimeout("execution timed out")
                    : new LuaException("instruction limit exceeded");
        }
    }

    /**
     * A {@link Guard} budget exhaustion caused by wall-clock timeout (rather
     * than an instruction limit). Lets {@link #evalWithTimeout} translate the
     * worker's failure into the declared {@link TimeoutException} even when
     * the self-terminating guard wins the race against the waiting caller.
     */
    static final class GuardTimeout extends LuaException {
        GuardTimeout(String message) {
            super(message);
        }
    }

    public volatile LoopGuard loopGuard;

    /** Active state for stdlib guard checks (set around a top-level run). */
    private static final ThreadLocal<LuaState> ACTIVE_STATE = new ThreadLocal<>();

    /**
     * Guard check for pure-Java stdlib loops (table.move, pattern matching,
     * string.rep, ...) that would otherwise never return to the VM dispatch
     * loop. Cheap no-op when no guard is active.
     */
    public static void checkGuard() {
        LuaState s = ACTIVE_STATE.get();
        if (s != null) {
            LoopGuard g = s.loopGuard;
            if (g != null) g.tick();
        }
    }

    /**
     * Maximum number of bytes a single stdlib allocation may request
     * (string.rep, string.pack, table.concat, ...). 0 = use the Lua default
     * (Integer.MAX_VALUE - 8). Servers should set a small value so untrusted
     * scripts cannot OOM the host.
     */
    private static final ThreadLocal<Long> ACTIVE_MAX_ALLOC = new ThreadLocal<>();

    /**
     * True once any state asked for an allocation cap. Keeps the concat hot
     * path free of a ThreadLocal lookup in the default (uncapped) case.
     */
    private static volatile boolean ANY_MAX_ALLOC = false;

    public LuaState maxAllocationBytes(long bytes) {
        this.maxAllocBytes = bytes;
        if (bytes > 0) {
            ANY_MAX_ALLOC = true;
        }
        return this;
    }

    private long maxAllocBytes = 0;

    private JavaAccessPolicy javaAccessPolicy = JavaAccessPolicy.DEFAULT;

    /**
     * Replaces the Java interop host-access policy. Use
     * {@link JavaAccessPolicy#UNRESTRICTED} only for fully trusted scripts;
     * the default blocks process execution, reflection, filesystem and
     * network classes.
     */
    public LuaState javaPolicy(JavaAccessPolicy policy) {
        this.javaAccessPolicy = (policy != null) ? policy : JavaAccessPolicy.DEFAULT;
        return this;
    }

    /** Allows additional Java class/package patterns through the policy. */
    public LuaState javaAllow(String... patterns) {
        this.javaAccessPolicy = javaAccessPolicy.withAllow(patterns);
        return this;
    }

    /** Denies additional Java class/package patterns through the policy. */
    public LuaState javaDeny(String... patterns) {
        this.javaAccessPolicy = javaAccessPolicy.withDeny(patterns);
        return this;
    }

    public JavaAccessPolicy getJavaAccessPolicy() {
        return javaAccessPolicy;
    }

    /** Effective per-allocation cap for the currently running chunk. */
    public static long allocationLimit() {
        if (!ANY_MAX_ALLOC) return Integer.MAX_VALUE - 8;
        Long l = ACTIVE_MAX_ALLOC.get();
        if (l != null && l > 0) return l;
        return Integer.MAX_VALUE - 8;
    }

    private void armGuard() {
        LoopGuard g = loopGuard;
        if (g instanceof Guard hard) {
            hard.arm();
        }
    }

    public LuaState() {
        // Force-initialize the core value classes here, at a shallow call
        // depth, before any script runs. Class initialization can allocate
        // (static caches) and thus can throw StackOverflowError if it first
        // happens at the recursion limit — after which the JVM permanently
        // poisons the class ("Could not initialize class ..."). The PUC
        // suite deliberately overflows the C stack while running error
        // handlers, so this must be settled up front.
        initValueClasses();
        registry.rawset(LuaInteger.valueOf(1), mainThread);
        registry.rawset(LuaInteger.valueOf(2), globals);
        this.rootEnvironment = new Environment(null, globals);
        org.luava.runtime.eval.GCManager.registerState(this);
        openStandardLibraries();
        savedLoadfile = globals.rawget(LuaString.interned("loadfile"));
        savedDofile = globals.rawget(LuaString.interned("dofile"));
    }

    /** Touches each core value class so its {@code <clinit>} runs early. */
    private static void initValueClasses() {
        LuaValue[] probe = {
            LuaNil.NIL, LuaBoolean.TRUE, LuaBoolean.FALSE,
            LuaInteger.valueOf(0), LuaFloat.valueOf(0.0),
            LuaString.EMPTY, Varargs.EMPTY,
        };
        if (probe.length == 0) {
            throw new IllegalStateException();
        }
    }

    private void openStandardLibraries() {
        BaseLib.open(this, globals);
        lazyLib("math", t -> MathLib.fillInto(t, globals));
        LuaTable stringLib = lazyLib("string", t -> StringLib.fillInto(t, globals));
        installStringMetatable(stringLib);
        lazyLib("table", t -> TableLib.fillInto(t, globals));
        lazyLib("coroutine", t -> CoroutineLib.fillInto(t, globals, mainThread));
        lazyLib("utf8", t -> Utf8Lib.fillInto(t, globals));
        lazyLib("os", t -> OsLib.fillInto(t, globals));
        lazyLib("io", t -> IoLib.fillInto(t, globals));
        lazyLib("debug", t -> DebugLib.fillInto(t, this, globals));
        LuaTable pkgLib = lazyLib("package", t -> PackageLib.fillInto(t, this, globals));
        LuaTable javaLib = lazyLib("java", t -> org.luava.binding.JavaInteropLib.fillInto(t, globals));
        globals.rawset(LuaString.interned("luajava"), javaLib);
        // Bare-global require: stub fills package lib on first call, then
        // delegates to the real implementation (which overwrites this stub).
        globals.rawset(LuaString.interned("require"), LuaFunction.of(args -> {
            pkgLib.ensureFilled();
            LuaValue real = globals.rawget(LuaString.interned("require"));
            return ((LuaFunction) real).call(args);
        }));
        LuaTable argTable = new LuaTable();
        // No stand-alone program exists for an embedded engine; use a stable
        // name instead of pointing at the reference C binary (that would make
        // CLI-oriented suite files test PUC Lua, not Luava).
        String progName = System.getProperty("lua.prog", "luava");
        argTable.rawset(LuaInteger.valueOf(0), LuaString.valueOf(progName));
        globals.rawset(LuaString.interned("arg"), argTable);
    }

    private LuaTable lazyLib(String name, java.util.function.Consumer<LuaTable> filler) {
        LuaTable[] holder = new LuaTable[1];
        LuaTable table = new LuaTable();
        table.setLazyFiller(() -> filler.accept(holder[0]));
        holder[0] = table;
        globals.rawset(LuaString.valueOf(name), table);
        return table;
    }

    /**
     * Disables the dangerous standard libraries in one call: {@code os},
     * {@code io}, {@code package} (+ {@code require}/{@code dofile}/
     * {@code loadfile}) and the Java interop table. Use this before running
     * untrusted scripts to prevent filesystem/process/reflection abuse.
     *
     * @return this state (fluent one-liner: {@code new LuaState().sandbox()})
     */
    public LuaState sandbox() {
        javaAccessPolicy = JavaAccessPolicy.STRICT;
        return deny("os", "io", "package", "require", "dofile", "loadfile", "java", "luajava", "debug");
    }

    /** Removes the named globals (library tables or functions). */
    public LuaState deny(String... names) {
        for (String name : names) {
            globals.rawset(LuaString.valueOf(name), LuaNil.NIL);
        }
        return this;
    }

    /**
     * Re-enables a standard library by (lazily) reinstalling it. Supported
     * names: math, string, table, coroutine, utf8, os, io, debug, package,
     * java. {@code require}/{@code dofile}/{@code loadfile} are restored with
     * the package library.
     */
    public LuaState allow(String name) {
        switch (name) {
            case "math" -> lazyLib("math", t -> MathLib.fillInto(t, globals));
            case "string" -> {
                LuaTable stringLib = lazyLib("string", t -> StringLib.fillInto(t, globals));
                installStringMetatable(stringLib);
            }
            case "table" -> lazyLib("table", t -> TableLib.fillInto(t, globals));
            case "coroutine" -> lazyLib("coroutine", t -> CoroutineLib.fillInto(t, globals, mainThread));
            case "utf8" -> lazyLib("utf8", t -> Utf8Lib.fillInto(t, globals));
            case "os" -> lazyLib("os", t -> OsLib.fillInto(t, globals));
            case "io" -> lazyLib("io", t -> IoLib.fillInto(t, globals));
            case "debug" -> lazyLib("debug", t -> DebugLib.fillInto(t, this, globals));
            case "java" -> {
                LuaTable javaLib = lazyLib("java", t -> org.luava.binding.JavaInteropLib.fillInto(t, globals));
                globals.rawset(LuaString.interned("luajava"), javaLib);
            }
            case "package" -> installPackage();
            default -> throw new LuaException("unknown library: " + name);
        }
        return this;
    }

    private void installPackage() {        LuaTable pkgLib = lazyLib("package", t -> PackageLib.fillInto(t, this, globals));
        globals.rawset(LuaString.interned("require"), LuaFunction.of(args -> {
            pkgLib.ensureFilled();
            LuaValue real = globals.rawget(LuaString.interned("require"));
            return ((LuaFunction) real).call(args);
        }));
        if (savedLoadfile != null) {
            globals.rawset(LuaString.interned("loadfile"), savedLoadfile);
        }
        if (savedDofile != null) {
            globals.rawset(LuaString.interned("dofile"), savedDofile);
        }
    }

    public LuaTable getRegistry() {
        return registry;
    }

    public long gcOwnerId() {
        return gcOwnerId;
    }

    EnumMap<LuaType, LuaTable> basicMetatables() {
        return basicMetatables;
    }

    /**
     * This state's basic metatable for {@code type} (null when unset).
     * Hot-path alternative to {@code LuaValue.getBasicMetatable}: the VM
     * already holds the executing state, so this skips the ThreadLocal
     * lookup. Only valid while this state is the active basic scope, which
     * always holds inside its own {@code execute()}.
     */
    public LuaTable basicMetatable(LuaType type) {
        return basicMetatables.get(type);
    }

    private void installStringMetatable(LuaTable stringLib) {
        LuaTable stringMetatable = StringLib.newStringMetatable(stringLib);
        basicMetatables.put(LuaType.STRING, stringMetatable);
        synchronized (LuaValue.class) {
            if (LuaValue.getBasicMetatable(LuaType.STRING) == null) {
                LuaValue.setBasicMetatable(LuaType.STRING, stringMetatable);
            }
        }
    }

    public LuaTable getGlobals() {
        return globals;
    }

    public LuaCoroutine getMainThread() {
        return mainThread;
    }

    public LuaValue get(String name) {
        return globals.get(LuaString.valueOf(name));
    }

    public <T> T get(String name, Class<T> type) {
        LuaValue val = get(name);
        return LuaDataConverter.toJava(val, type);
    }

    public void set(String name, Object value) {
        globals.set(LuaString.valueOf(name), LuaDataConverter.toLua(value));
    }

    public void setLive(String name, Object value) {
        globals.set(LuaString.valueOf(name), LuaDataConverter.toLuaLive(value));
    }

    public void registerClass(Class<?> clazz) {
        registerClass(clazz.getSimpleName(), clazz);
    }

    public void registerClass(String alias, Class<?> clazz) {
        globals.rawset(LuaString.valueOf(alias), new LuaUserdata(clazz));
    }

    public void registerFunction(String name, LuaInvokable invokable) {
        globals.rawset(LuaString.valueOf(name), LuaFunction.ofGuarded(invokable));
    }

    public void registerFunction(String name, Supplier<?> supplier) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromSupplier(supplier));
    }

    public <T> void registerFunction(String name, Class<T> type, Consumer<T> consumer) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromConsumer(type, consumer));
    }

    public <T, U> void registerFunction(String name, Class<T> type1, Class<U> type2, BiConsumer<T, U> consumer) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromBiConsumer(type1, type2, consumer));
    }

    public <T, R> void registerFunction(String name, Class<T> argType, Function<T, R> function) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromFunction(argType, function));
    }

    public <T, U, R> void registerFunction(String name, Class<T> argType1, Class<U> argType2, BiFunction<T, U, R> function) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromBiFunction(argType1, argType2, function));
    }

    public <T, U, V, R> void registerFunction(
        String name,
        Class<T> argType1,
        Class<U> argType2,
        Class<V> argType3,
        JavaFunctionBuilder.TriFunction<T, U, V, R> function
    ) {
        globals.rawset(LuaString.valueOf(name), JavaFunctionBuilder.fromTriFunction(argType1, argType2, argType3, function));
    }

    public void registerModule(Object target) {
        ModuleBinder.ModuleBindingResult result = ModuleBinder.bind(target);
        registeredModules.add(result.info());
        String modName = result.info().name();
        globals.rawset(LuaString.valueOf(modName), result.table());

        LuaValue pkgVal = globals.rawget(LuaString.interned("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                loaded.rawset(LuaString.valueOf(modName), result.table());
            }
        }
    }

    public void registerModule(String moduleName, Consumer<LuaTable> configurator) {
        LuaTable table = new LuaTable();
        configurator.accept(table);
        globals.rawset(LuaString.valueOf(moduleName), table);

        LuaValue pkgVal = globals.rawget(LuaString.interned("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.interned("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                loaded.rawset(LuaString.valueOf(moduleName), table);
            }
        }
    }

    public LuaValue call(String functionName, Object... args) {
        LuaValue fn = get(functionName);
        if (!fn.isFunction()) {
            throw new LuaException("global '" + functionName + "' is not a function");
        }
        LuaValue[] luaArgs = new LuaValue[args.length];
        for (int i = 0; i < args.length; i++) {
            luaArgs[i] = LuaDataConverter.toLua(args[i]);
        }
        return fn.call(luaArgs);
    }

    public LuaValue eval(String luaSource) {
        return eval(luaSource, "chunk");
    }

    public LuaValue eval(String luaSource, String chunkName) {
        LuaCoroutine prev = LuaCoroutine.running();
        if (prev == null) {
            LuaCoroutine.setCurrent(mainThread);
        }
        LuaState prevActive = ACTIVE_STATE.get();
        if (prevActive == null) {
            ACTIVE_STATE.set(this);
        }
        Long prevMax = ACTIVE_MAX_ALLOC.get();
        if (prevMax == null && maxAllocBytes > 0) {
            ACTIVE_MAX_ALLOC.set(maxAllocBytes);
        }
        JavaAccessPolicy prevPolicy = JavaAccessPolicy.setActive(javaAccessPolicy);
        armGuard();
        try {
            LuaFunction chunk = compile(luaSource, chunkName, globals);
            if (chunk instanceof org.luava.runtime.bytecode.LuaClosure lc) {
                lc.setState(this);
            }
            return chunk.call();
        } finally {
            JavaAccessPolicy.restoreActive(prevPolicy);
            if (prevMax == null) {
                ACTIVE_MAX_ALLOC.remove();
            }
            if (prevActive == null) {
                ACTIVE_STATE.remove();
            }
            if (prev == null) {
                LuaCoroutine.setCurrent(null);
            }
        }
    }

    public LuaValue eval(String luaSource, Map<String, Object> context) {
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                set(entry.getKey(), entry.getValue());
            }
        }
        return eval(luaSource);
    }

    public LuaValue evalWithTimeout(String luaSource, Duration timeout) throws TimeoutException {
        // A private hard guard makes the worker self-terminate on timeout
        // instead of leaking a virtual thread that spins forever. The guard
        // is installed before the worker starts and removed once the worker
        // has unwound, so the state is reusable afterwards.
        Guard guard = new Guard(0, timeout.toNanos());
        guard.arm();
        LoopGuard prevGuard = loopGuard;
        loopGuard = guard;
        CompletableFuture<LuaValue> future = new CompletableFuture<>();
        Thread worker = Thread.ofVirtual().name("lua-timeout-task").start(() -> {
            try {
                LuaValue result = eval(luaSource);
                future.complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            guard.cancel();
            try {
                worker.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw new LuaException("Execution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            // The self-terminating guard uses the same deadline as the
            // waiting caller, so it can win the race and fail the worker
            // with a timeout error before future.get() raises its own.
            // Surface that uniformly as the declared TimeoutException.
            if (cause instanceof GuardTimeout) {
                throw new TimeoutException(cause.getMessage());
            }
            if (cause instanceof RuntimeException re) throw re;
            throw new LuaException("Error during execution: " + cause.getMessage());
        } catch (TimeoutException e) {
            guard.cancel();
            future.cancel(false);
            try {
                worker.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            throw e;
        } finally {
            loopGuard = prevGuard;
        }
    }

    /**
     * Stops execution after at most {@code maxInstructions} VM instructions
     * with a Lua error {@code "instruction limit exceeded"}. Cooperative and
     * cheap (one field check per instruction). Pass {@code 0} to disable.
     */
    public LuaState instructionLimit(long maxInstructions) {
        if (maxInstructions <= 0) {
            loopGuard = null;
            return this;
        }
        long timeoutNanos = (loopGuard instanceof Guard g) ? g.timeoutNanos : 0;
        Guard guard = new Guard(maxInstructions, timeoutNanos);
        guard.arm();
        loopGuard = guard;
        return this;
    }

    /**
     * Stops execution after {@code timeout} of wall-clock time with a Lua
     * error {@code "execution timed out"}. Checked periodically (not every
     * instruction) to keep the hot path cheap. Pass {@code null} to disable.
     */
    public LuaState timeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            loopGuard = null;
            return this;
        }
        long maxInstr = (loopGuard instanceof Guard g) ? g.maxInstr : 0;
        Guard guard = new Guard(maxInstr, timeout.toNanos());
        guard.arm();
        loopGuard = guard;
        return this;
    }

    /** Clears any instruction/timeout guard installed by this state. */
    public LuaState clearGuard() {
        loopGuard = null;
        return this;
    }

    public String generateEmmyDocs() {
        StringBuilder sb = new StringBuilder();
        for (ModuleBinder.ModuleInfo info : registeredModules) {
            sb.append(EmmyDocGenerator.generate(info)).append("\n\n");
        }
        return sb.toString();
    }

    public void exportEmmyDocs(Path directory) throws IOException {
        for (ModuleBinder.ModuleInfo info : registeredModules) {
            Path targetFile = directory.resolve(info.name() + ".lua");
            EmmyDocGenerator.exportToFile(targetFile, info);
        }
    }

    public LuaFunction compile(String luaSource) {
        return compile(luaSource, "=(load)", globals);
    }

    public Environment getRootEnvironment() {
        return rootEnvironment;
    }

    public LuaFunction compile(String luaSource, String chunkName, LuaValue env) {
        LuaValue chunkEnvVal = (env != null) ? env : globals;
        LuaTable chunkGlobals = (chunkEnvVal instanceof LuaTable t) ? t : globals;

        if (luaSource.startsWith("\u001b")) {
            LuaFunction fn = org.luava.runtime.standard.ChunkSerializer.undump(
                luaSource.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                chunkName, chunkEnvVal, globals, rootEnvironment
            );
            // undump may build a closure without a state; bind this state so
            // the loopGuard/instructionLimit applies (otherwise invoke() would
            // lazily create a fresh, unguarded LuaState).
            if (fn instanceof org.luava.runtime.bytecode.LuaClosure lc) {
                lc.setState(this);
            }
            return fn;
        }

        if (!USE_BYTECODE_VM) {
            throw new LuaException("AST interpreter retired; bytecode VM is the only execution path");
        }
        // Reuse the compiled proto tree for a repeated chunk. The proto is
        // immutable after compilation (only JIT tier-up state mutates), and
        // each call still gets a fresh closure with its own _ENV upvalue, so
        // sharing is semantically transparent. This is what lets a server
        // that eval's the same script per request accumulate JIT hotness
        // instead of rebuilding a cold proto every time.
        String key = (chunkName != null ? chunkName : "chunk") + '\0' + luaSource;
        org.luava.runtime.bytecode.LuaProto proto;
        synchronized (protoCache) {
            proto = protoCache.get(key);
        }
        if (proto == null) {
            try {
                boolean isFile = chunkName != null && chunkName.startsWith("@");
                Lexer lexer = new Lexer(luaSource, isFile);
                List<Token> tokens = lexer.scanTokens();
                Parser parser = new Parser(tokens);
                Statements.BlockStmt block = parser.parse();
                proto = org.luava.runtime.bytecode.BytecodeCompiler.compile(block, chunkName);
            } catch (org.luava.frontend.parser.ParseException pe) {
                throw new LuaException(pe.format(chunkName != null ? chunkName : luaSource));
            }
            synchronized (protoCache) {
                protoCache.put(key, proto);
            }
        }
        org.luava.runtime.eval.Upvalue envUpval = new org.luava.runtime.eval.Upvalue("_ENV", chunkEnvVal);
        return new org.luava.runtime.bytecode.LuaClosure(proto, new org.luava.runtime.eval.Upvalue[]{envUpval}, chunkGlobals, this);
    }

    // AST interpreter retired: the register-based bytecode VM is the only
    // execution path (verified 31/31 official suite + 32/32 VM files).
    public static boolean USE_BYTECODE_VM = true;

    /**
     * Hybrid tiered JIT master switch (plan.md). Default ON: hot kernels
     * tier up automatically while the interpreter stays the fallback for
     * anything unsupported (identical semantics, verified 30/30 + fuzz both
     * modes). Opt out with {@code -Dluava.jit=false}; force synchronous
     * tier-up for deterministic measurement with
     * {@code -Dluava.jit.sync=true}.
     *
     * <p>This is the process-wide default; per-state control is
     * {@link #jitEnabled(boolean)} / {@link #isJitEnabled()}. A state's own
     * setting wins over this field.
     */
    public static volatile boolean ENABLE_JIT = !"false".equalsIgnoreCase(System.getProperty("luava.jit", "true"));

    /** Interpreter calls of one proto before a JIT compile is attempted. */
    public static final int JIT_HOT_THRESHOLD = 50;

    /**
     * Loop back-edges of one proto before a JIT compile is attempted. Much
     * larger than {@link #JIT_HOT_THRESHOLD}: a single loop iteration is far
     * cheaper than a call, and this keeps short/once-around loops from
     * triggering a compile for no benefit. Used for protos that are hot
     * because of a loop (top-level chunks, {@code while}/{@code repeat}, or a
     * function called only once).
     */
    public static final int JIT_LOOP_THRESHOLD = 8192;

    /**
     * Per-state JIT override. {@code null} means "follow the global
     * {@link #ENABLE_JIT}"; true/false pins this state only, so a server can
     * keep JIT on for trusted tenants and off for others in the same JVM.
     */
    private volatile Boolean jitEnabledOverride;

    /**
     * Enables or disables tiered JIT for this state only, independent of
     * {@link #ENABLE_JIT} and other states. Thread-safe; may be flipped at
     * runtime. Pass {@code null} to fall back to the global default.
     */
    public LuaState jitEnabled(Boolean enabled) {
        this.jitEnabledOverride = enabled;
        return this;
    }

    /** Whether this state currently uses the JIT (its override or the global default). */
    public boolean isJitEnabled() {
        Boolean o = jitEnabledOverride;
        return o != null ? o : ENABLE_JIT;
    }

    public LuaCoroutine getCurrentThread() {
        LuaCoroutine cur = LuaCoroutine.running();
        return (cur != null) ? cur : mainThread;
    }

    public long[] getPrimitiveStack() {
        return getCurrentThread().getPrimitiveStack();
    }

    public byte[] getTypeStack() {
        return getCurrentThread().getTypeStack();
    }

    public LuaValue[] getObjectStack() {
        return getCurrentThread().getObjectStack();
    }

    public int getStackTop() {
        return getCurrentThread().getStackTop();
    }

    public void setStackTop(int top) {
        getCurrentThread().setStackTop(top);
    }

    public void ensureStackCapacity(int needed) {
        getCurrentThread().ensureStackCapacity(needed);
    }

    public LuaValue[] ensureStack(int needed) {
        ensureStackCapacity(needed);
        return getCurrentThread().getObjectStack();
    }

    public LuaValue getStackValue(int index) {
        LuaCoroutine th = getCurrentThread();
        return org.luava.runtime.bytecode.BytecodeVM.getLuaValue(th.getPrimitiveStack(), th.getTypeStack(), th.getObjectStack(), index);
    }

    public void setStackValue(int index, LuaValue val) {
        LuaCoroutine th = getCurrentThread();
        org.luava.runtime.bytecode.BytecodeVM.setLuaValue(th.getPrimitiveStack(), th.getTypeStack(), th.getObjectStack(), index, val);
    }

    public org.luava.runtime.eval.Upvalue findOrCreateOpenUpvalue(int stackIndex, String name) {
        return findOrCreateOpenUpvalue(getCurrentThread(), stackIndex, name);
    }

    /**
     * Hot-path overload: the VM already holds its thread ({@code ctx.thread},
     * identical to {@code getCurrentThread()} for the whole execute), so
     * pass it explicitly instead of paying a ThreadLocal lookup per call.
     */
    public org.luava.runtime.eval.Upvalue findOrCreateOpenUpvalue(LuaCoroutine thread, int stackIndex, String name) {
        org.luava.runtime.eval.Upvalue prev = null;
        org.luava.runtime.eval.Upvalue curr = thread.getOpenUpvaluesHead();
        while (curr != null && curr.getStackIndex() >= stackIndex) {
            if (curr.getStackIndex() == stackIndex) {
                return curr;
            }
            prev = curr;
            curr = curr.nextOpen;
        }

        org.luava.runtime.eval.Upvalue newUv = new org.luava.runtime.eval.Upvalue(name, thread, stackIndex);
        newUv.nextOpen = curr;
        if (prev == null) {
            thread.setOpenUpvaluesHead(newUv);
        } else {
            prev.nextOpen = newUv;
        }
        return newUv;
    }

    public void closeUpvalues(int fromIndex) {
        closeUpvalues(getCurrentThread(), fromIndex);
    }

    /** Hot-path overload: explicit thread, no ThreadLocal lookup (see above). */
    public void closeUpvalues(LuaCoroutine thread, int fromIndex) {
        while (thread.getOpenUpvaluesHead() != null && thread.getOpenUpvaluesHead().getStackIndex() >= fromIndex) {
            org.luava.runtime.eval.Upvalue uv = thread.getOpenUpvaluesHead();
            thread.setOpenUpvaluesHead(uv.nextOpen);
            uv.nextOpen = null;
            uv.close();
        }
    }

    /**
     * Runs {@code os.exit(_, true)} close semantics: closes pending
     * to-be-closed variables of the running thread, then runs finalizers.
     * Called from {@code os.exit} before unwinding with {@link LuaExit}.
     */
    public static void runExitFinalizers() {
        LuaCoroutine cur = LuaCoroutine.running();
        if (cur != null) {
            try {
                cur.closeAllTbc();
            } catch (Throwable ignored) {
            }
        }
        try {
            org.luava.runtime.eval.GCManager.collect();
        } catch (Throwable ignored) {
        }
    }

    public static final class TbcEntry {        public final int stackIndex;
        public final LuaValue value;
        public final String varName;
        public TbcEntry next;

        public TbcEntry(int stackIndex, LuaValue value, String varName, TbcEntry next) {
            this.stackIndex = stackIndex;
            this.value = value;
            this.varName = varName;
            this.next = next;
        }
    }

    public void pushTbc(int stackIndex, LuaValue val, String varName) {
        if (val == null || val.isNil() || val.equals(LuaBoolean.FALSE)) {
            return;
        }
        LuaTable mt = val.getMetatable();
        if (mt == null || mt.rawget(LuaValue.Meta.CLOSE).isNil()) {
            throw new LuaException("variable '" + (varName != null ? varName : "?") + "' got a non-closable value");
        }
        LuaCoroutine thread = getCurrentThread();
        thread.setTbcHead(new TbcEntry(stackIndex, val, varName, thread.getTbcHead()));
    }

    public void closeTbc(int fromIndex, LuaValue errorObj) {
        closeTbc(getCurrentThread(), fromIndex, errorObj);
    }

    /**
     * ThreadLocal-free {@link #closeTbc(int, LuaValue)} for VM callers that
     * already hoisted the running thread (avoids a {@code ThreadLocalMap}
     * lookup on every return path).
     */
    public void closeTbc(LuaCoroutine thread, int fromIndex, LuaValue errorObj) {
        Throwable lastError = null;
        while (thread.getTbcHead() != null && thread.getTbcHead().stackIndex >= fromIndex) {
            TbcEntry entry = thread.getTbcHead();
            thread.setTbcHead(entry.next);
            LuaValue val = entry.value;
            LuaTable mt = val.getMetatable();
            LuaValue closeMth = mt != null ? mt.rawget(LuaValue.Meta.CLOSE) : LuaNil.NIL;
            try {
                if (closeMth.isNil()) {
                    throw new LuaException("attempt to call a nil value (metamethod 'close')");
                }
                org.luava.runtime.eval.CallStack.setNextCall("close", "metamethod", false, true);
                closeMth.call(val, errorObj != null ? errorObj : LuaNil.NIL);
            } catch (org.luava.runtime.eval.LuaUnwindException ue) {
                lastError = ue;
                errorObj = ue.getOriginalError();
            } catch (LuaException le) {
                lastError = le;
                errorObj = le.getErrorObject();
            } catch (Throwable t) {
                lastError = t;
                errorObj = LuaString.valueOf(t.getMessage() != null ? t.getMessage() : t.toString());
            }
        }
        if (lastError != null) {
            if (lastError instanceof RuntimeException re) throw re;
            throw new LuaException(lastError.getMessage());
        }
    }
}

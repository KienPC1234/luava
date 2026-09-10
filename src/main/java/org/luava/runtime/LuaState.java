package org.luava.runtime;

import org.luava.binding.JavaFunctionBuilder;
import org.luava.binding.LuaDataConverter;
import org.luava.binding.ModuleBinder;
import org.luava.emmydoc.EmmyDocGenerator;
import org.luava.frontend.ast.Statements;
import org.luava.frontend.lexer.Lexer;
import org.luava.frontend.lexer.Token;
import org.luava.frontend.parser.Parser;
import org.luava.runtime.eval.Environment;
import org.luava.runtime.eval.Interpreter;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LuaState {
    private final LuaTable globals = new LuaTable();
    private final LuaTable registry = new LuaTable();
    private final LuaCoroutine mainThread = LuaCoroutine.createMainThread();
    private final Environment rootEnvironment;
    private final List<ModuleBinder.ModuleInfo> registeredModules = new ArrayList<>();

    public LuaState() {
        LuaValue.resetBasicMetatables();
        registry.rawset(LuaInteger.valueOf(1), mainThread);
        registry.rawset(LuaInteger.valueOf(2), globals);
        this.rootEnvironment = new Environment(null, globals);
        org.luava.runtime.eval.GCManager.registerState(this);
        openStandardLibraries();
    }

    private void openStandardLibraries() {
        BaseLib.open(this, globals);
        MathLib.open(globals);
        StringLib.open(globals);
        TableLib.open(globals);
        CoroutineLib.open(globals, mainThread);
        Utf8Lib.open(globals);
        OsLib.open(globals);
        IoLib.open(globals);
        DebugLib.open(this, globals);
        PackageLib.open(this, globals);
        org.luava.binding.JavaInteropLib.open(globals);
        LuaTable argTable = new LuaTable();
        String progName = System.getProperty("lua.prog");
        if (progName == null || progName.isEmpty()) {
            java.io.File localLua = new java.io.File("lua-source/src/lua");
            progName = localLua.exists() ? localLua.getAbsolutePath() : "lua";
        }
        argTable.rawset(LuaInteger.valueOf(0), LuaString.valueOf(progName));
        globals.rawset(LuaString.valueOf("arg"), argTable);
    }

    public LuaTable getRegistry() {
        return registry;
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
        globals.rawset(LuaString.valueOf(name), LuaFunction.of(invokable));
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

        LuaValue pkgVal = globals.rawget(LuaString.valueOf("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.valueOf("loaded"));
            if (loadedVal instanceof LuaTable loaded) {
                loaded.rawset(LuaString.valueOf(modName), result.table());
            }
        }
    }

    public void registerModule(String moduleName, Consumer<LuaTable> configurator) {
        LuaTable table = new LuaTable();
        configurator.accept(table);
        globals.rawset(LuaString.valueOf(moduleName), table);

        LuaValue pkgVal = globals.rawget(LuaString.valueOf("package"));
        if (pkgVal instanceof LuaTable pkg) {
            LuaValue loadedVal = pkg.rawget(LuaString.valueOf("loaded"));
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
        try {
            LuaFunction chunk = compile(luaSource, chunkName, globals);
            return chunk.call();
        } finally {
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
        CompletableFuture<LuaValue> future = new CompletableFuture<>();
        Thread.ofVirtual().name("lua-timeout-task").start(() -> {
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
            throw new LuaException("Execution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new LuaException("Error during execution: " + cause.getMessage());
        }
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
            return org.luava.runtime.standard.ChunkSerializer.undump(
                luaSource.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                chunkName, chunkEnvVal, globals, rootEnvironment
            );
        }

        try {
            boolean isFile = chunkName != null && chunkName.startsWith("@");
            Lexer lexer = new Lexer(luaSource, isFile);
            List<Token> tokens = lexer.scanTokens();
            Parser parser = new Parser(tokens);
            Statements.BlockStmt block = parser.parse();

            if (USE_BYTECODE_VM) {
                org.luava.runtime.bytecode.LuaProto proto = org.luava.runtime.bytecode.BytecodeCompiler.compile(block, chunkName);
                org.luava.runtime.eval.Upvalue envUpval = new org.luava.runtime.eval.Upvalue("_ENV", chunkEnvVal);
                return new org.luava.runtime.bytecode.LuaClosure(proto, new org.luava.runtime.eval.Upvalue[]{envUpval}, chunkGlobals, this);
            }

            return Interpreter.INSTANCE.createMainChunk(block, rootEnvironment, chunkEnvVal, globals, chunkName, luaSource);
        } catch (org.luava.frontend.parser.ParseException pe) {
            throw new LuaException(pe.format(chunkName != null ? chunkName : luaSource));
        }
    }

    public static boolean USE_BYTECODE_VM = false;

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
        LuaCoroutine thread = getCurrentThread();
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
        LuaCoroutine thread = getCurrentThread();
        while (thread.getOpenUpvaluesHead() != null && thread.getOpenUpvaluesHead().getStackIndex() >= fromIndex) {
            org.luava.runtime.eval.Upvalue uv = thread.getOpenUpvaluesHead();
            thread.setOpenUpvaluesHead(uv.nextOpen);
            uv.nextOpen = null;
            uv.close();
        }
    }

    public static final class TbcEntry {
        public final int stackIndex;
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
        if (mt == null || mt.rawget(LuaString.valueOf("__close")).isNil()) {
            throw new LuaException("variable '" + (varName != null ? varName : "?") + "' got a non-closable value");
        }
        LuaCoroutine thread = getCurrentThread();
        thread.setTbcHead(new TbcEntry(stackIndex, val, varName, thread.getTbcHead()));
    }

    public void closeTbc(int fromIndex, LuaValue errorObj) {
        LuaCoroutine thread = getCurrentThread();
        Throwable lastError = null;
        while (thread.getTbcHead() != null && thread.getTbcHead().stackIndex >= fromIndex) {
            TbcEntry entry = thread.getTbcHead();
            thread.setTbcHead(entry.next);
            LuaValue val = entry.value;
            LuaTable mt = val.getMetatable();
            LuaValue closeMth = mt != null ? mt.rawget(LuaString.valueOf("__close")) : LuaNil.NIL;
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

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
    private final Environment rootEnvironment;
    private final List<ModuleBinder.ModuleInfo> registeredModules = new ArrayList<>();

    public LuaState() {
        this.rootEnvironment = new Environment(null, globals);
        openStandardLibraries();
    }

    private void openStandardLibraries() {
        BaseLib.open(this, globals);
        MathLib.open(globals);
        StringLib.open(globals);
        TableLib.open(globals);
        CoroutineLib.open(globals);
        Utf8Lib.open(globals);
        PackageLib.open(this, globals);
    }

    public LuaTable getGlobals() {
        return globals;
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
        LuaFunction chunk = compile(luaSource);
        return chunk.call();
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
        Lexer lexer = new Lexer(luaSource);
        List<Token> tokens = lexer.scanTokens();
        Parser parser = new Parser(tokens);
        Statements.BlockStmt block = parser.parse();

        return new LuaFunction() {
            @Override
            public LuaValue invoke(LuaValue... args) {
                Environment chunkEnv = new Environment(rootEnvironment, globals);
                if (args != null && args.length > 0) {
                    chunkEnv.defineLocal("...", Varargs.of(args), false, false);
                } else {
                    chunkEnv.defineLocal("...", Varargs.EMPTY, false, false);
                }
                Interpreter interpreter = new Interpreter();
                return interpreter.execute(block, chunkEnv);
            }
        };
    }
}

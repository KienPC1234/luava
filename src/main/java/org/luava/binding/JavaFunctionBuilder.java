package org.luava.binding;

import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaValue;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class JavaFunctionBuilder {
    @FunctionalInterface
    public interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }

    @FunctionalInterface
    public interface QuadFunction<A, B, C, D, R> {
        R apply(A a, B b, C c, D d);
    }

    private JavaFunctionBuilder() {}

    public static LuaFunction fromSupplier(Supplier<?> supplier) {
        return LuaFunction.of(args -> LuaDataConverter.toLua(supplier.get()));
    }

    public static <T> LuaFunction fromConsumer(Class<T> type, Consumer<T> consumer) {
        return LuaFunction.of(args -> {
            T val = args.length > 0 ? LuaDataConverter.toJava(args[0], type) : null;
            consumer.accept(val);
            return LuaNil.NIL;
        });
    }

    public static <T, U> LuaFunction fromBiConsumer(Class<T> type1, Class<U> type2, BiConsumer<T, U> consumer) {
        return LuaFunction.of(args -> {
            T val1 = args.length > 0 ? LuaDataConverter.toJava(args[0], type1) : null;
            U val2 = args.length > 1 ? LuaDataConverter.toJava(args[1], type2) : null;
            consumer.accept(val1, val2);
            return LuaNil.NIL;
        });
    }

    public static <T, R> LuaFunction fromFunction(Class<T> argType, Function<T, R> function) {
        return LuaFunction.of(args -> {
            T arg = args.length > 0 ? LuaDataConverter.toJava(args[0], argType) : null;
            return LuaDataConverter.toLua(function.apply(arg));
        });
    }

    public static <T, U, R> LuaFunction fromBiFunction(Class<T> argType1, Class<U> argType2, BiFunction<T, U, R> function) {
        return LuaFunction.of(args -> {
            T arg1 = args.length > 0 ? LuaDataConverter.toJava(args[0], argType1) : null;
            U arg2 = args.length > 1 ? LuaDataConverter.toJava(args[1], argType2) : null;
            return LuaDataConverter.toLua(function.apply(arg1, arg2));
        });
    }

    public static <T, U, V, R> LuaFunction fromTriFunction(
        Class<T> argType1,
        Class<U> argType2,
        Class<V> argType3,
        TriFunction<T, U, V, R> function
    ) {
        return LuaFunction.of(args -> {
            T arg1 = args.length > 0 ? LuaDataConverter.toJava(args[0], argType1) : null;
            U arg2 = args.length > 1 ? LuaDataConverter.toJava(args[1], argType2) : null;
            V arg3 = args.length > 2 ? LuaDataConverter.toJava(args[2], argType3) : null;
            return LuaDataConverter.toLua(function.apply(arg1, arg2, arg3));
        });
    }

    public static <A, B, C, D, R> LuaFunction fromQuadFunction(
        Class<A> argType1,
        Class<B> argType2,
        Class<C> argType3,
        Class<D> argType4,
        QuadFunction<A, B, C, D, R> function
    ) {
        return LuaFunction.of(args -> {
            A arg1 = args.length > 0 ? LuaDataConverter.toJava(args[0], argType1) : null;
            B arg2 = args.length > 1 ? LuaDataConverter.toJava(args[1], argType2) : null;
            C arg3 = args.length > 2 ? LuaDataConverter.toJava(args[2], argType3) : null;
            D arg4 = args.length > 3 ? LuaDataConverter.toJava(args[3], argType4) : null;
            return LuaDataConverter.toLua(function.apply(arg1, arg2, arg3, arg4));
        });
    }
}

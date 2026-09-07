package org.luava.runtime.indy;

import org.luava.runtime.LuaValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

public final class InlineCacheCallSite extends MutableCallSite {
    private static final int MAX_POLYMORPHIC_ENTRIES = 4;

    private final String operationName;
    private final MethodHandle fallbackHandle;
    private int depth = 0;

    public InlineCacheCallSite(MethodType type, String operationName) {
        super(type);
        this.operationName = operationName;
        try {
            this.fallbackHandle = MethodHandles.lookup().findVirtual(
                InlineCacheCallSite.class,
                "fallback",
                type
            ).bindTo(this);
            setTarget(fallbackHandle);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize InlineCacheCallSite", e);
        }
    }

    public LuaValue fallback(LuaValue target, LuaValue key) throws Throwable {
        if (depth < MAX_POLYMORPHIC_ENTRIES) {
            Class<?> targetClass = target.getClass();
            MethodHandle test = MethodHandles.lookup().findVirtual(
                Object.class,
                "getClass",
                MethodType.methodType(Class.class)
            );
            MethodHandle testTarget = MethodHandles.filterReturnValue(
                test,
                MethodHandles.lookup().findStatic(
                    InlineCacheCallSite.class,
                    "isSameClass",
                    MethodType.methodType(boolean.class, Class.class, Object.class)
                ).bindTo(targetClass)
            );

            MethodHandle action = MethodHandles.lookup().findVirtual(
                LuaValue.class,
                "get",
                MethodType.methodType(LuaValue.class, LuaValue.class)
            );

            MethodHandle guarded = MethodHandles.guardWithTest(
                MethodHandles.dropArguments(testTarget, 1, LuaValue.class),
                action,
                getTarget()
            );

            depth++;
            setTarget(guarded);
        }

        return target.get(key);
    }

    public static boolean isSameClass(Class<?> expected, Object actual) {
        return actual == expected;
    }
}

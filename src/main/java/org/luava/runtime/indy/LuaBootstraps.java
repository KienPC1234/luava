package org.luava.runtime.indy;

import org.luava.runtime.LuaValue;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public final class LuaBootstraps {
    private LuaBootstraps() {}

    public static CallSite bootstrapGet(
        MethodHandles.Lookup lookup,
        String name,
        MethodType type
    ) {
        return new InlineCacheCallSite(type, name);
    }

    public static CallSite bootstrapBinaryOp(
        MethodHandles.Lookup lookup,
        String opName,
        MethodType type
    ) throws NoSuchMethodException, IllegalAccessException {
        MethodHandle handle = lookup.findVirtual(
            LuaValue.class,
            opName,
            MethodType.methodType(LuaValue.class, LuaValue.class)
        );
        return new ConstantCallSite(handle);
    }

    public static CallSite bootstrapInvoke(
        MethodHandles.Lookup lookup,
        String name,
        MethodType type
    ) throws NoSuchMethodException, IllegalAccessException {
        MethodHandle handle = lookup.findVirtual(
            LuaValue.class,
            "call",
            MethodType.methodType(LuaValue.class, LuaValue[].class)
        );
        return new ConstantCallSite(handle);
    }
}

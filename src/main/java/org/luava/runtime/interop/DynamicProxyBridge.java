/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.interop;

import org.luava.binding.LuaDataConverter;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaValue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class DynamicProxyBridge {
    private DynamicProxyBridge() {}

    /**
     * Creates a Java proxy that implements every interface in
     * {@code interfaces} and routes member access to {@code table}, mirroring
     * LuaJ's {@code luajava.createProxy("Iface1", "Iface2", table)}.
     *
     * <p>{@code Object} methods are handled locally ({@code equals} by
     * identity, {@code hashCode} by the table's, {@code toString} by the
     * table's Lua string form) so a missing Lua handler never breaks them.
     * The first interface's class loader is used, matching
     * {@link Proxy#newProxyInstance} semantics for a single interface.
     */
    public static Object createProxy(Class<?>[] interfaces, LuaTable table) {
        if (interfaces == null || interfaces.length == 0) {
            throw new LuaException("java.proxy expects at least one interface");
        }
        for (Class<?> iface : interfaces) {
            if (iface == null || !iface.isInterface()) {
                throw new LuaException((iface == null ? "null" : iface.getName()) + " is not an interface");
            }
        }

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            // Handle Object methods
            if ("toString".equals(name)) return table.toLuaString();
            if ("hashCode".equals(name)) return table.hashCode();
            if ("equals".equals(name)) return args != null && args.length == 1 && args[0] == proxy;

            LuaValue member = table.get(LuaString.valueOf(name));
            if (member.isNil() || !member.isFunction()) {
                throw new LuaException("Method '" + name + "' not implemented in Lua table for " + describe(interfaces));
            }

            int argCount = (args != null ? args.length : 0);
            LuaValue[] luaArgs = new LuaValue[argCount + 1];
            luaArgs[0] = table; // pass 'self'
            for (int i = 0; i < argCount; i++) {
                luaArgs[i + 1] = LuaDataConverter.toLua(args[i]);
            }

            LuaValue result = member.call(luaArgs);
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class || returnType == Void.class) {
                return null;
            }
            return LuaDataConverter.toJava(result, returnType);
        };

        ClassLoader loader = interfaces[0].getClassLoader();
        return Proxy.newProxyInstance(loader, interfaces, handler);
    }

    /** Convenience for the single-interface case. */
    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> interfaceClass, LuaTable table) {
        return (T) createProxy(new Class<?>[]{interfaceClass}, table);
    }

    private static String describe(Class<?>[] interfaces) {
        StringBuilder sb = new StringBuilder("interface(s) ");
        for (int i = 0; i < interfaces.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(interfaces[i].getName());
        }
        return sb.toString();
    }
}

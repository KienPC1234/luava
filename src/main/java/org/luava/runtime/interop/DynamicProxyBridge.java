package org.luava.runtime.interop;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class DynamicProxyBridge {
    private DynamicProxyBridge() {}

    @SuppressWarnings("unchecked")
    public static <T> T createProxy(Class<T> interfaceClass, LuaTable table) {
        if (!interfaceClass.isInterface()) {
            throw new IllegalArgumentException(interfaceClass.getName() + " is not an interface");
        }

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            // Handle Object methods
            if ("toString".equals(name)) return table.toLuaString();
            if ("hashCode".equals(name)) return table.hashCode();
            if ("equals".equals(name)) return args.length == 1 && args[0] == proxy;

            LuaValue member = table.get(LuaString.valueOf(name));
            if (member.isNil()) {
                throw new LuaException("Method '" + name + "' not implemented in Lua table");
            }

            int argCount = (args != null ? args.length : 0);
            LuaValue[] luaArgs = new LuaValue[argCount + 1];
            luaArgs[0] = table; // pass 'self'
            for (int i = 0; i < argCount; i++) {
                luaArgs[i + 1] = org.luava.binding.LuaDataConverter.toLua(args[i]);
            }

            LuaValue result = member.call(luaArgs);
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class || returnType == Void.class) {
                return null;
            }
            return org.luava.binding.LuaDataConverter.toJava(result, returnType);
        };

        return (T) Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class<?>[]{interfaceClass}, handler);
    }
}

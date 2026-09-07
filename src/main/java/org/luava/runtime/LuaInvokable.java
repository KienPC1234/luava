package org.luava.runtime;

@FunctionalInterface
public interface LuaInvokable {
    LuaValue invoke(LuaValue... args);
}

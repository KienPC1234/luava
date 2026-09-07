package org.luava.runtime;

public enum LuaType {
    NIL("nil"),
    BOOLEAN("boolean"),
    NUMBER("number"),
    STRING("string"),
    TABLE("table"),
    FUNCTION("function"),
    USERDATA("userdata"),
    THREAD("thread");

    private final String name;

    LuaType(String name) {
        this.name = name;
    }

    public String typeName() {
        return name;
    }
}

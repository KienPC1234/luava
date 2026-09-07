package org.luava.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaUserdata extends LuaValue {
    private static final Map<Class<?>, Map<String, MethodHandle>> METHOD_CACHE = new ConcurrentHashMap<>();
    private final Object instance;
    private LuaTable metatable;

    public LuaUserdata(Object instance) {
        this.instance = instance;
    }

    public Object getJavaInstance() {
        return instance;
    }

    @Override
    public LuaType type() {
        return LuaType.USERDATA;
    }

    @Override
    public boolean isUserdata() {
        return true;
    }

    @Override
    public LuaTable getMetatable() {
        return metatable;
    }

    @Override
    public void setMetatable(LuaTable mt) {
        this.metatable = mt;
    }

    @Override
    public LuaValue get(LuaValue key) {
        if (metatable != null) {
            LuaValue handler = metatable.rawget(LuaString.valueOf("__index"));
            if (!handler.isNil()) {
                if (handler.isFunction()) {
                    return handler.call(this, key);
                } else {
                    return handler.get(key);
                }
            }
        }

        if (instance != null && key.isString()) {
            String methodName = key.toLuaString();
            MethodHandle mh = getCachedMethod(instance.getClass(), methodName);
            if (mh != null) {
                return LuaFunction.of(args -> {
                    try {
                        Object[] javaArgs = new Object[args.length];
                        for (int i = 0; i < args.length; i++) {
                            javaArgs[i] = unwrap(args[i]);
                        }
                        Object result = mh.bindTo(instance).invokeWithArguments(javaArgs);
                        return wrap(result);
                    } catch (Throwable t) {
                        throw new LuaException("Error invoking Java method " + methodName + ": " + t.getMessage());
                    }
                });
            }
        }
        return LuaNil.NIL;
    }

    private static MethodHandle getCachedMethod(Class<?> clazz, String name) {
        Map<String, MethodHandle> classMethods = METHOD_CACHE.computeIfAbsent(clazz, c -> {
            Map<String, MethodHandle> map = new ConcurrentHashMap<>();
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            for (Method m : c.getMethods()) {
                if (Modifier.isPublic(m.getModifiers())) {
                    try {
                        map.putIfAbsent(m.getName(), lookup.unreflect(m));
                    } catch (IllegalAccessException ignored) {}
                }
            }
            return map;
        });
        return classMethods.get(name);
    }

    public static Object unwrap(LuaValue val) {
        if (val == null || val.isNil()) return null;
        if (val.isBoolean()) return val.toBoolean();
        if (val.isInteger()) return val.toLong();
        if (val.isFloat()) return val.toDouble();
        if (val.isString()) return val.toLuaString();
        if (val.isUserdata()) return ((LuaUserdata) val).getJavaInstance();
        return val;
    }

    public static LuaValue wrap(Object obj) {
        if (obj == null) return LuaNil.NIL;
        if (obj instanceof LuaValue lv) return lv;
        if (obj instanceof Boolean b) return LuaBoolean.valueOf(b);
        if (obj instanceof Integer i) return LuaInteger.valueOf(i);
        if (obj instanceof Long l) return LuaInteger.valueOf(l);
        if (obj instanceof Float f) return LuaFloat.valueOf(f.doubleValue());
        if (obj instanceof Double d) return LuaFloat.valueOf(d);
        if (obj instanceof String s) return LuaString.valueOf(s);
        return new LuaUserdata(obj);
    }

    @Override
    public String toLuaString() {
        return "userdata: 0x" + Integer.toHexString(System.identityHashCode(instance));
    }
}

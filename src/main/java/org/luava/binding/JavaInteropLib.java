/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.runtime.LuaBoolean;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaInteger;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;

public final class JavaInteropLib {
    private JavaInteropLib() {}

    public static void open(LuaTable globals) {
        LuaTable javaMod = new LuaTable();
        fillInto(javaMod, globals);
        globals.rawset(LuaString.valueOf("java"), javaMod);
        globals.rawset(LuaString.valueOf("luajava"), javaMod);
    }

    public static void fillInto(LuaTable javaMod, LuaTable globals) {

        // 1. java.import / java.bindClass
        LuaFunction importFn = LuaFunction.of(args -> {
            if (args.length == 0 || !args[0].isString()) {
                throw new LuaException("bad argument #1 to 'java.import' (string expected)");
            }
            String className = args[0].toLuaString();
            Class<?> clazz = loadClass(className);
            return new LuaUserdata(clazz);
        });
        javaMod.rawset(LuaString.valueOf("import"), importFn);
        javaMod.rawset(LuaString.valueOf("bindClass"), importFn);

        // 2. java.new
        javaMod.rawset(LuaString.valueOf("new"), LuaFunction.of(args -> {
            if (args.length == 0) {
                throw new LuaException("bad argument #1 to 'java.new' (class or class name expected)");
            }
            Class<?> clazz;
            if (args[0].isString()) {
                clazz = loadClass(args[0].toLuaString());
            } else if (args[0].isUserdata() && ((LuaUserdata) args[0]).getJavaInstance() instanceof Class<?> c) {
                checkClass(c);
                clazz = c;
            } else {
                throw new LuaException("bad argument #1 to 'java.new' (expected Class or class name string)");
            }

            LuaValue newFn = new LuaUserdata(clazz).get(LuaString.valueOf("new"));
            LuaValue[] ctorArgs = new LuaValue[args.length - 1];
            System.arraycopy(args, 1, ctorArgs, 0, ctorArgs.length);
            return newFn.call(ctorArgs);
        }));

        // 3. java.proxy
        javaMod.rawset(LuaString.valueOf("proxy"), LuaFunction.of(args -> {
            if (args.length < 2) {
                throw new LuaException("java.proxy expects (interfaceNameOrClass, tableOrFunction)");
            }
            Class<?> iface;
            if (args[0].isString()) {
                iface = loadClass(args[0].toLuaString());
            } else if (args[0].isUserdata() && ((LuaUserdata) args[0]).getJavaInstance() instanceof Class<?> c) {
                checkClass(c);
                iface = c;
            } else {
                throw new LuaException("bad argument #1 to 'java.proxy' (interface expected)");
            }

            if (!iface.isInterface()) {
                throw new LuaException(iface.getName() + " is not an interface");
            }

            LuaValue handler = args[1];
            if (handler.isFunction()) {
                Object proxy = LuaDataConverter.toJava(handler, iface);
                return new LuaUserdata(proxy);
            } else if (handler.isTable()) {
                LuaTable table = (LuaTable) handler;
                Object proxy = Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface}, (p, method, mArgs) -> {
                    String mName = method.getName();
                    if ("equals".equals(mName)) return mArgs != null && mArgs.length == 1 && mArgs[0] == p;
                    if ("hashCode".equals(mName)) return System.identityHashCode(p);
                    if ("toString".equals(mName)) return table.toLuaString();

                    LuaValue member = table.get(LuaString.valueOf(mName));
                    if (member.isNil() || !member.isFunction()) {
                        throw new LuaException("Method '" + mName + "' not implemented in Lua table for interface " + iface.getName());
                    }

                    int count = (mArgs != null ? mArgs.length : 0);
                    LuaValue[] luaArgs = new LuaValue[count + 1];
                    luaArgs[0] = table; // pass self
                    for (int i = 0; i < count; i++) {
                        luaArgs[i + 1] = LuaDataConverter.toLua(mArgs[i]);
                    }
                    LuaValue result = member.call(luaArgs);
                    if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                        return null;
                    }
                    return LuaDataConverter.toJava(result, method.getReturnType());
                });
                return new LuaUserdata(proxy);
            } else {
                throw new LuaException("bad argument #2 to 'java.proxy' (function or table expected)");
            }
        }));

        // 4. java.array
        javaMod.rawset(LuaString.valueOf("array"), LuaFunction.of(args -> {
            if (args.length < 2) {
                throw new LuaException("java.array expects (componentType, size)");
            }
            Class<?> compType;
            if (args[0].isString()) {
                String typeName = args[0].toLuaString();
                compType = switch (typeName) {
                    case "int" -> int.class;
                    case "long" -> long.class;
                    case "double" -> double.class;
                    case "float" -> float.class;
                    case "byte" -> byte.class;
                    case "short" -> short.class;
                    case "boolean" -> boolean.class;
                    case "char" -> char.class;
                    case "String", "string" -> String.class;
                    case "Object", "object" -> Object.class;
                    default -> {
                        yield loadClass(typeName);
                    }
                };
            } else if (args[0].isUserdata() && ((LuaUserdata) args[0]).getJavaInstance() instanceof Class<?> c) {
                checkClass(c);
                compType = c;
            } else {
                throw new LuaException("bad argument #1 to 'java.array' (expected component type string or Class)");
            }

            int size = (int) args[1].toLong();
            Object arr = Array.newInstance(compType, size);
            return new LuaUserdata(arr);
        }));

        // 5. java.instanceof
        javaMod.rawset(LuaString.valueOf("instanceof"), LuaFunction.of(args -> {
            if (args.length < 2) return LuaBoolean.FALSE;
            if (!args[0].isUserdata()) return LuaBoolean.FALSE;
            Object inst = ((LuaUserdata) args[0]).getJavaInstance();
            if (inst == null) return LuaBoolean.FALSE;

            Class<?> targetClass;
            if (args[1].isString()) {
                targetClass = loadClassOrNull(args[1].toLuaString());
                if (targetClass == null) return LuaBoolean.FALSE;
            } else if (args[1].isUserdata() && ((LuaUserdata) args[1]).getJavaInstance() instanceof Class<?> c) {
                checkClass(c);
                targetClass = c;
            } else {
                return LuaBoolean.FALSE;
            }

            return LuaBoolean.valueOf(targetClass.isInstance(inst));
        }));
    }

    /** Loads a class after checking it against the active access policy. */
    static Class<?> loadClass(String className) {
        JavaAccessPolicy.active().check(className);
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new LuaException("Class not found: " + className);
        }
    }

    /** Checks a Class userdata before it is used as a type handle. */
    static void checkClass(Class<?> clazz) {
        JavaAccessPolicy.active().check(clazz.getName());
    }

    /**
     * Loads a class for a boolean {@code instanceof} probe: denied or
     * missing classes both yield {@code null} instead of throwing.
     */
    static Class<?> loadClassOrNull(String className) {
        if (!JavaAccessPolicy.active().isAllowed(className)) return null;
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}

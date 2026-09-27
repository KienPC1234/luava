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
        LuaFunction importFn = LuaFunction.ofGuarded(args -> {
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
        javaMod.rawset(LuaString.valueOf("new"), LuaFunction.ofGuarded(args -> {
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

        // 3. java.proxy / luajava.createProxy
        // LuaJ-compatible form: java.proxy("Iface1", "Iface2", ..., table).
        // The handler is always the last argument; every preceding argument is
        // an interface (string name or Class userdata).
        LuaFunction proxyFn = LuaFunction.ofGuarded(args -> {
            if (args.length < 2) {
                throw new LuaException("java.proxy expects (interface..., tableOrFunction)");
            }
            LuaValue handler = args[args.length - 1];
            Class<?>[] ifaces = new Class<?>[args.length - 1];
            for (int i = 0; i < ifaces.length; i++) {
                ifaces[i] = resolveInterface(args[i], "java.proxy");
            }

            if (handler.isTable()) {
                Object proxy = org.luava.runtime.interop.DynamicProxyBridge.createProxy(ifaces, (LuaTable) handler);
                return new LuaUserdata(proxy);
            } else if (handler.isFunction()) {
                if (ifaces.length != 1) {
                    // A bare Lua function can only stand in for one SAM
                    // interface; there is no method-name dispatch across many.
                    throw new LuaException("java.proxy with a function handler accepts exactly one interface (got " + ifaces.length + ")");
                }
                Object proxy = LuaDataConverter.toJava(handler, ifaces[0]);
                return new LuaUserdata(proxy);
            } else {
                throw new LuaException("bad argument to 'java.proxy' (function or table expected as last argument)");
            }
        });
        javaMod.rawset(LuaString.valueOf("proxy"), proxyFn);
        javaMod.rawset(LuaString.valueOf("createProxy"), proxyFn);

        // 4. java.array
        javaMod.rawset(LuaString.valueOf("array"), LuaFunction.ofGuarded(args -> {
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
        javaMod.rawset(LuaString.valueOf("instanceof"), LuaFunction.ofGuarded(args -> {
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

    /** Resolves one interface argument (string name or Class userdata). */
    private static Class<?> resolveInterface(LuaValue arg, String ctx) {
        Class<?> iface;
        if (arg.isString()) {
            iface = loadClass(arg.toLuaString());
        } else if (arg.isUserdata() && ((LuaUserdata) arg).getJavaInstance() instanceof Class<?> c) {
            checkClass(c);
            iface = c;
        } else {
            throw new LuaException("bad argument to '" + ctx + "' (interface name or Class expected)");
        }
        if (!iface.isInterface()) {
            throw new LuaException(iface.getName() + " is not an interface");
        }
        return iface;
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

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.binding.annotation.LuaField;
import org.luava.binding.annotation.LuaMethod;
import org.luava.binding.annotation.LuaModule;
import org.luava.binding.annotation.LuaParam;
import org.luava.binding.annotation.LuaReturn;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaFunction;
import org.luava.runtime.LuaNil;
import org.luava.runtime.LuaString;
import org.luava.runtime.LuaTable;
import org.luava.runtime.LuaUserdata;
import org.luava.runtime.LuaValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ModuleBinder {
    public record ParamInfo(String name, String type, String description, boolean optional) {}

    public record MethodInfo(
        String name,
        boolean isMethod,
        String description,
        List<ParamInfo> parameters,
        String returnType,
        String returnDescription
    ) {}

    public record FieldInfo(String name, String type, String description, boolean readOnly) {}

    public record ModuleInfo(
        String name,
        String description,
        List<FieldInfo> fields,
        List<MethodInfo> methods
    ) {}

    private ModuleBinder() {}

    public static ModuleBindingResult bind(Object target) {
        Class<?> clazz = (target instanceof Class<?>) ? (Class<?>) target : target.getClass();
        Object instance = (target instanceof Class<?>) ? null : target;

        LuaModule moduleAnno = clazz.getAnnotation(LuaModule.class);
        String moduleName = (moduleAnno != null && !moduleAnno.name().isEmpty()) ? moduleAnno.name() : clazz.getSimpleName();
        String moduleDesc = (moduleAnno != null) ? moduleAnno.description() : "";

        LuaTable table = new LuaTable();
        List<FieldInfo> fieldInfos = new ArrayList<>();
        List<MethodInfo> methodInfos = new ArrayList<>();

        MethodHandles.Lookup lookup = MethodHandles.lookup();

        // 1. Scan Fields
        for (Field field : clazz.getDeclaredFields()) {
            LuaField fieldAnno = field.getAnnotation(LuaField.class);
            if (fieldAnno != null) {
                field.setAccessible(true);
                String fieldName = !fieldAnno.name().isEmpty() ? fieldAnno.name() : field.getName();
                try {
                    Object val = field.get(instance);
                    table.rawset(LuaString.valueOf(fieldName), LuaDataConverter.toLua(val));
                    fieldInfos.add(new FieldInfo(
                        fieldName,
                        mapJavaTypeToLuaType(field.getType()),
                        fieldAnno.description(),
                        fieldAnno.readOnly()
                    ));
                } catch (IllegalAccessException e) {
                    throw new LuaException("Failed to access field " + fieldName + ": " + e.getMessage());
                }
            }
        }

        // 2. Scan Methods
        java.util.Map<String, List<Method>> methodsByName = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> methodIsMethod = new java.util.HashMap<>();

        for (Method method : clazz.getDeclaredMethods()) {
            LuaMethod methodAnno = method.getAnnotation(LuaMethod.class);
            if (methodAnno != null) {
                method.setAccessible(true);
                String methodName = !methodAnno.name().isEmpty() ? methodAnno.name() : method.getName();
                boolean isMethod = methodAnno.isMethod();
                methodIsMethod.put(methodName, isMethod);
                methodsByName.computeIfAbsent(methodName, k -> new ArrayList<>()).add(method);

                Parameter[] params = method.getParameters();
                Class<?>[] paramTypes = method.getParameterTypes();
                List<ParamInfo> paramInfos = new ArrayList<>();

                for (int pIdx = 0; pIdx < params.length; pIdx++) {
                    Parameter p = params[pIdx];
                    LuaParam pAnno = p.getAnnotation(LuaParam.class);
                    String pName = (pAnno != null && !pAnno.name().isEmpty()) ? pAnno.name() : p.getName();
                    String pType = (pAnno != null && !pAnno.type().isEmpty()) ? pAnno.type() : mapJavaTypeToLuaType(paramTypes[pIdx]);
                    String pDesc = (pAnno != null) ? pAnno.description() : "";
                    boolean pOpt = (pAnno != null) && pAnno.optional();
                    paramInfos.add(new ParamInfo(pName, pType, pDesc, pOpt));
                }

                LuaReturn retAnno = method.getAnnotation(LuaReturn.class);
                String retType = (retAnno != null && !retAnno.type().isEmpty()) ? retAnno.type() : mapJavaTypeToLuaType(method.getReturnType());
                String retDesc = (retAnno != null) ? retAnno.description() : "";

                methodInfos.add(new MethodInfo(methodName, isMethod, methodAnno.description(), paramInfos, retType, retDesc));
            }
        }

        for (Map.Entry<String, List<Method>> entry : methodsByName.entrySet()) {
            String methodName = entry.getKey();
            List<Method> candidates = entry.getValue();
            boolean isMethod = methodIsMethod.getOrDefault(methodName, false);

            LuaFunction luaFunc = LuaFunction.of(args -> {
                int offset = (isMethod && args.length > 0) ? 1 : 0;
                LuaValue[] effectiveArgs = new LuaValue[args.length - offset];
                System.arraycopy(args, offset, effectiveArgs, 0, effectiveArgs.length);

                Method bestMatch = null;
                int bestScore = -1;

                for (Method m : candidates) {
                    Class<?>[] paramTypes = m.getParameterTypes();
                    if (!m.isVarArgs() && paramTypes.length != effectiveArgs.length) continue;
                    if (m.isVarArgs() && effectiveArgs.length < paramTypes.length - 1) continue;

                    int score = 0;
                    int fixed = m.isVarArgs() ? paramTypes.length - 1 : paramTypes.length;
                    boolean ok = true;
                    for (int i = 0; i < fixed; i++) {
                        int s = scoreArg(paramTypes[i], effectiveArgs[i]);
                        if (s < 0) { ok = false; break; }
                        score += s;
                    }
                    if (ok && m.isVarArgs()) {
                        Class<?> varType = paramTypes[fixed].getComponentType();
                        for (int i = fixed; i < effectiveArgs.length; i++) {
                            int s = scoreArg(varType, effectiveArgs[i]);
                            if (s < 0) { ok = false; break; }
                            score += s;
                        }
                    }
                    if (ok && score > bestScore) {
                        bestScore = score;
                        bestMatch = m;
                    }
                }

                if (bestMatch == null) {
                    throw new LuaException("No matching overload for bound method '" + methodName + "' with " + effectiveArgs.length + " arguments");
                }

                try {
                    Class<?>[] paramTypes = bestMatch.getParameterTypes();
                    Object[] javaArgs;
                    if (!bestMatch.isVarArgs()) {
                        javaArgs = new Object[paramTypes.length];
                        for (int i = 0; i < paramTypes.length; i++) {
                            javaArgs[i] = LuaDataConverter.toJava(effectiveArgs[i], paramTypes[i]);
                        }
                    } else {
                        int fixed = paramTypes.length - 1;
                        javaArgs = new Object[paramTypes.length];
                        for (int i = 0; i < fixed; i++) {
                            javaArgs[i] = LuaDataConverter.toJava(effectiveArgs[i], paramTypes[i]);
                        }
                        int varCount = effectiveArgs.length - fixed;
                        Class<?> compType = paramTypes[fixed].getComponentType();
                        Object varArray = Array.newInstance(compType, varCount);
                        for (int i = 0; i < varCount; i++) {
                            Array.set(varArray, i, LuaDataConverter.toJava(effectiveArgs[fixed + i], compType));
                        }
                        javaArgs[fixed] = varArray;
                    }

                    Object res = bestMatch.invoke(Modifier.isStatic(bestMatch.getModifiers()) ? null : instance, javaArgs);
                    if (bestMatch.getReturnType() == void.class || bestMatch.getReturnType() == Void.class) {
                        return LuaNil.NIL;
                    }
                    return LuaDataConverter.toLua(res);
                } catch (Throwable t) {
                    throw LuaFunction.hostError("Error invoking bound method " + methodName, t);
                }
            });

            table.rawset(LuaString.valueOf(methodName), luaFunc);
        }

        ModuleInfo info = new ModuleInfo(moduleName, moduleDesc, fieldInfos, methodInfos);
        return new ModuleBindingResult(table, info);
    }

    private static int scoreArg(Class<?> target, LuaValue val) {
        if (val == null || val.isNil()) return target.isPrimitive() ? 1 : 5;
        if (target == Object.class) return 2;
        if (target.isInstance(val)) return 10;
        if (val.isInteger()) {
            if (target == long.class || target == Long.class) return 10;
            if (target == int.class || target == Integer.class) return 9;
            if (target == short.class || target == Short.class || target == byte.class || target == Byte.class) return 8;
            if (target == double.class || target == Double.class || target == float.class || target == Float.class) return 7;
        }
        if (val.isFloat()) {
            if (target == double.class || target == Double.class) return 10;
            if (target == float.class || target == Float.class) return 9;
        }
        if (val.isBoolean() && (target == boolean.class || target == Boolean.class)) return 10;
        if (val.isString() && (target == String.class || CharSequence.class.isAssignableFrom(target))) return 10;
        if (val.isString() && target.isEnum()) return 8;
        if (val.isFunction() && target.isInterface() && LuaDataConverter.findSingleAbstractMethod(target) != null) return 9;
        if (val.isTable() && (target.isArray() || target == List.class || target == Map.class)) return 8;
        if (val.isUserdata()) {
            Object inst = ((LuaUserdata) val).getJavaInstance();
            if (inst != null && target.isInstance(inst)) return 10;
        }
        return -1;
    }

    public record ModuleBindingResult(LuaTable table, ModuleInfo info) {}

    public static String mapJavaTypeToLuaType(Class<?> clazz) {
        if (clazz == void.class || clazz == Void.class) return "nil";
        if (clazz == boolean.class || clazz == Boolean.class) return "boolean";
        if (clazz == int.class || clazz == Integer.class || clazz == long.class || clazz == Long.class || clazz == short.class || clazz == byte.class) return "integer";
        if (clazz == double.class || clazz == Double.class || clazz == float.class || clazz == Float.class) return "number";
        if (clazz == String.class || CharSequence.class.isAssignableFrom(clazz)) return "string";
        if (LuaTable.class.isAssignableFrom(clazz) || List.class.isAssignableFrom(clazz)) return "table";
        if (LuaFunction.class.isAssignableFrom(clazz)) return "function";
        return clazz.getSimpleName();
    }

    private static Object getDefaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        return null;
    }
}

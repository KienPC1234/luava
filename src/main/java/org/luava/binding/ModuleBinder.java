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
import org.luava.runtime.LuaValue;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

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
        for (Method method : clazz.getDeclaredMethods()) {
            LuaMethod methodAnno = method.getAnnotation(LuaMethod.class);
            if (methodAnno != null) {
                method.setAccessible(true);
                String methodName = !methodAnno.name().isEmpty() ? methodAnno.name() : method.getName();
                boolean isMethod = methodAnno.isMethod();

                try {
                    MethodHandle handle = lookup.unreflect(method);
                    if (instance != null && !Modifier.isStatic(method.getModifiers())) {
                        handle = handle.bindTo(instance);
                    }

                    MethodHandle finalHandle = handle;
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

                    LuaFunction luaFunc = LuaFunction.of(args -> {
                        try {
                            int offset = (isMethod && args.length > 0) ? 1 : 0; // skip 'self' if method call
                            Object[] javaArgs = new Object[params.length];
                            for (int i = 0; i < params.length; i++) {
                                int argIdx = i + offset;
                                if (argIdx < args.length && !args[argIdx].isNil()) {
                                    javaArgs[i] = LuaDataConverter.toJava(args[argIdx], paramTypes[i]);
                                } else {
                                    javaArgs[i] = getDefaultValue(paramTypes[i]);
                                }
                            }
                            Object result = finalHandle.invokeWithArguments(javaArgs);
                            if (method.getReturnType() == void.class) {
                                return LuaNil.NIL;
                            }
                            return LuaDataConverter.toLua(result);
                        } catch (Throwable t) {
                            throw new LuaException("Error invoking bound method " + methodName + ": " + t.getMessage());
                        }
                    });

                    table.rawset(LuaString.valueOf(methodName), luaFunc);
                } catch (IllegalAccessException e) {
                    throw new LuaException("Failed to bind method " + methodName + ": " + e.getMessage());
                }
            }
        }

        ModuleInfo info = new ModuleInfo(moduleName, moduleDesc, fieldInfos, methodInfos);
        return new ModuleBindingResult(table, info);
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

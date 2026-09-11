/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.emmydoc;

import org.luava.binding.ModuleBinder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class EmmyDocGenerator {
    private EmmyDocGenerator() {}

    public static String generate(Class<?> clazz) {
        ModuleBinder.ModuleBindingResult result = ModuleBinder.bind(clazz);
        return generate(result.info());
    }

    public static String generate(ModuleBinder.ModuleInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("---@meta\n\n");

        if (info.description() != null && !info.description().isEmpty()) {
            sb.append("--- ").append(info.description()).append("\n");
        }
        sb.append("---@class ").append(info.name()).append("\n");

        for (ModuleBinder.FieldInfo field : info.fields()) {
            sb.append("---@field ");
            if (field.readOnly()) {
                sb.append("readonly ");
            }
            sb.append(field.name()).append(" ").append(field.type());
            if (field.description() != null && !field.description().isEmpty()) {
                sb.append(" # ").append(field.description());
            }
            sb.append("\n");
        }

        sb.append("local ").append(info.name()).append(" = {}\n\n");

        for (ModuleBinder.MethodInfo method : info.methods()) {
            if (method.description() != null && !method.description().isEmpty()) {
                sb.append("--- ").append(method.description()).append("\n");
            }

            for (ModuleBinder.ParamInfo param : method.parameters()) {
                sb.append("---@param ").append(param.name()).append(" ").append(param.type());
                if (param.optional()) {
                    sb.append("|nil");
                }
                if (param.description() != null && !param.description().isEmpty()) {
                    sb.append(" # ").append(param.description());
                }
                sb.append("\n");
            }

            if (method.returnType() != null && !method.returnType().equals("nil")) {
                sb.append("---@return ").append(method.returnType());
                if (method.returnDescription() != null && !method.returnDescription().isEmpty()) {
                    sb.append(" # ").append(method.returnDescription());
                }
                sb.append("\n");
            }

            String separator = method.isMethod() ? ":" : ".";
            sb.append("function ").append(info.name()).append(separator).append(method.name()).append("(");

            for (int i = 0; i < method.parameters().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(method.parameters().get(i).name());
            }
            sb.append(") end\n\n");
        }

        sb.append("return ").append(info.name()).append("\n");
        return sb.toString();
    }

    public static String generateAll(List<Class<?>> classes) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : classes) {
            sb.append(generate(c)).append("\n----------------------------------------\n\n");
        }
        return sb.toString();
    }

    public static void exportToFile(Path destination, ModuleBinder.ModuleInfo info) throws IOException {
        String content = generate(info);
        if (destination.getParent() != null) {
            Files.createDirectories(destination.getParent());
        }
        Files.writeString(destination, content);
    }

    public static void exportToFile(Path destination, Class<?> clazz) throws IOException {
        ModuleBinder.ModuleBindingResult result = ModuleBinder.bind(clazz);
        exportToFile(destination, result.info());
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.jit;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import org.luava.runtime.bytecode.LuaClosure;
import org.luava.runtime.bytecode.LuaProto;
import org.luava.runtime.bytecode.OpCode;

/**
 * Compiles eligible Lua protos to hidden JVM classes (one method per Lua
 * function) and caches them. Anything unexpected disables JIT for that
 * proto and the interpreter transparently takes over.
 */
public final class JitCompiler {
    private static final AtomicInteger CLASS_SEQ = new AtomicInteger();
    private static final JitCodeCache CACHE = new JitCodeCache();

    private JitCompiler() {}

    /**
     * Attempts to compile {@code proto}. Returns the code on success, null
     * when the proto is outside the JIT subset. Never throws: failures are
     * reported as null so the caller can mark the proto and continue
     * interpreted.
     */
    public static synchronized JitCode tryCompile(LuaProto proto) {
        if (proto.jitCode != null || proto.jitDisabled) {
            return proto.jitCode;
        }
        try {
            LuaToJvmTranslator.Info info = LuaToJvmTranslator.analyze(proto);
            if (info == null || !eligible(proto)) {
                return null;
            }
            String name = "org/luava/runtime/jit/Gen$" + CLASS_SEQ.getAndIncrement();
            LuaToJvmTranslator.Translation tr = LuaToJvmTranslator.translate(proto, name);
            if (tr == null) {
                return null;
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> cls = lookup.defineHiddenClass(tr.bytes(), true,
                    MethodHandles.Lookup.ClassOption.NESTMATE).lookupClass();
            java.lang.invoke.MethodHandle mh = MethodHandles.lookup().findStatic(cls, "exec",
                    MethodType.methodType(long.class, LuaClosure.class, Object[].class,
                            long[].class, byte[].class, org.luava.runtime.LuaValue[].class, int.class));
            JitCode code = new JitCode(proto, mh, info.pure());
            proto.jitCode = code;
            CACHE.put(proto, code);
            return code;
        } catch (Throwable t) {
            if (Boolean.getBoolean("luava.jit.debug")) {
                System.err.println("[jit] compile failed for " + proto.name + ": " + t);
            }
            return null;
        }
    }

    static boolean eligible(LuaProto proto) {
        if (!LuaToJvmTranslator.eligible(proto)) {
            return false;
        }
        // Conservative yield rule for Phase 2: the integer subset has no
        // table/global access, no varargs, no TAILCALL and no closure
        // creation, so it can never reach coroutine.yield.
        for (int inst : proto.code) {
            int op = org.luava.runtime.bytecode.Instruction.getOp(inst);
            if (op == OpCode.OP_TAILCALL) {
                return false;
            }
        }
        proto.mayYield = false;
        return true;
    }

    public static int cacheSize() {
        return CACHE.size();
    }
}

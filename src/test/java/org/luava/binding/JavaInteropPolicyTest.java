/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.junit.jupiter.api.Test;
import org.luava.runtime.LuaException;
import org.luava.runtime.LuaState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the Java interop host-access policy. Before the
 * policy existed, a fresh {@code LuaState} let untrusted Lua run
 * {@code java.lang.Runtime.exec}, read arbitrary files and kill the JVM via
 * {@code System.exit}; {@code sandbox().allow("java")} re-opened all of it.
 */
public class JavaInteropPolicyTest {

    @Test
    void defaultPolicyBlocksProcessExecution() {
        LuaState s = new LuaState();
        assertDenied(s, "return java.import('java.lang.Runtime')");
        assertDenied(s, "local R = java.import('java.lang.Runtime'); return R.getRuntime().exec('id')");
        assertDenied(s, "return java.import('java.lang.ProcessBuilder')");
        assertDenied(s, "return java.import('java.lang.ProcessHandle')");
    }

    @Test
    void defaultPolicyBlocksJvmTermination() {
        LuaState s = new LuaState();
        assertDenied(s, "return java.import('java.lang.System')");
        assertDenied(s, "local S = java.import('java.lang.System'); S.exit(1)");
    }

    @Test
    void defaultPolicyBlocksFilesystemAndNetwork() {
        LuaState s = new LuaState();
        assertDenied(s, "return java.import('java.io.FileInputStream')");
        assertDenied(s, "return java.import('java.nio.file.Files')");
        assertDenied(s, "return java.import('java.net.Socket')");
    }

    @Test
    void defaultPolicyBlocksReflectionAndClassLoading() {
        LuaState s = new LuaState();
        assertDenied(s, "return java.import('java.lang.Class')");
        assertDenied(s, "return java.import('java.lang.ClassLoader')");
        assertDenied(s, "return java.import('java.lang.reflect.Method')");
        assertDenied(s, "return java.import('java.lang.invoke.MethodHandles')");
        assertDenied(s, "return java.import('sun.misc.Unsafe')");
    }

    @Test
    void defaultPolicyStillAllowsSafeApplicationClasses() {
        LuaState s = new LuaState();
        assertEquals(7L, s.eval("return java.import('java.lang.Math').max(3, 7)").toLong());
        assertEquals("a", s.eval("local L = java.new('java.util.ArrayList'); L.add('a'); return L.get(0)").toLuaString());
        assertEquals("v", s.eval("local M = java.new('java.util.HashMap'); M.put('k', 'v'); return M.get('k')").toLuaString());
    }

    @Test
    void sandboxStrictPolicyKeepsBlockingEvenWhenJavaAllowed() {
        LuaState s = new LuaState().sandbox().allow("java");
        assertFalse(s.get("java").isNil());
        assertDenied(s, "return java.import('java.lang.Runtime')");
        assertDenied(s, "return java.import('java.lang.System')");
        // The safe allowlist still works.
        assertEquals(2L, s.eval("local L = java.new('java.util.ArrayList'); L.add(1); L.add(2); return L.size()").toLong());
    }

    @Test
    void hostCanExplicitlyAllowADeniedClass() {
        LuaState s = new LuaState().sandbox().allow("java").javaAllow("java.lang.Runtime");
        // Import now succeeds (the policy check passes).
        s.eval("local R = java.import('java.lang.Runtime'); rt = R.getRuntime()");
        assertFalse(s.get("rt").isNil());
    }

    @Test
    void hostCanOptIntoUnrestrictedInterop() {
        LuaState s = new LuaState().javaPolicy(JavaAccessPolicy.UNRESTRICTED);
        assertEquals("class java.lang.Runtime", s.eval("return tostring(java.import('java.lang.Runtime'))").toLuaString());
    }

    @Test
    void policyPatternsSupportPackageWildcards() {
        JavaAccessPolicy p = JavaAccessPolicy.DEFAULT.withAllow("java.io.*");
        assertTrue(p.isAllowed("java.io.FileInputStream"));
        assertFalse(p.isAllowed("java.net.Socket"));
        assertTrue(p.isAllowed("java.util.ArrayList"));
        assertFalse(p.isAllowed("java.lang.Runtime"));
    }

    @Test
    void policyDoesNotLeakBetweenStates() {
        LuaState strict = new LuaState().sandbox().allow("java");
        LuaState open = new LuaState().javaPolicy(JavaAccessPolicy.UNRESTRICTED);
        // Run the permissive state first, then confirm the strict one is
        // still enforcing its own policy.
        open.eval("return java.import('java.lang.Runtime')");
        assertDenied(strict, "return java.import('java.lang.Runtime')");
    }

    @Test
    void sandboxStillExposesHostRegisteredServices() {
        // Host services are handed to Lua directly; the policy must not
        // block their own methods, only class loading and capability classes.
        LuaState s = new LuaState().sandbox();
        s.setLive("api", new HostService());
        assertEquals("hi", s.eval("return api.hello()").toLuaString());
        assertEquals(5L, s.eval("return api.add(2, 3)").toLong());
        // ...but their getClass() path cannot reach capability classes:
        // the members are filtered out, so the call fails rather than
        // exposing the class loader or reflection.
        assertThrows(LuaException.class, () -> s.eval("return api.getClass().getClassLoader()", "@policy"));
        assertThrows(LuaException.class,
                () -> s.eval("local c = api.getClass(); return c.forName('java.lang.Runtime')", "@policy"));
    }

    public static class HostService {
        public String hello() {
            return "hi";
        }

        public int add(int a, int b) {
            return a + b;
        }
    }

    private static void assertDenied(LuaState state, String code) {
        LuaException ex = assertThrows(LuaException.class, () -> state.eval(code, "@policy"));
        assertTrue(ex.getMessage().contains("access denied"),
                "expected an access-denied error, got: " + ex.getMessage());
    }
}

/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.runtime.LuaException;

/**
 * Host-access policy for the Java interop layer. Every {@code java.import},
 * {@code java.new}, {@code java.proxy}, {@code java.array} and every member
 * lookup on a {@code Class} userdata is checked against the active policy,
 * so a script cannot reach {@code java.lang.Runtime}, reflection, the
 * filesystem or {@code System.exit} unless the host explicitly allows it.
 *
 * <p>A class name is allowed when it matches an allow pattern, otherwise
 * denied when it matches a deny pattern, otherwise it falls back to the
 * policy's {@code defaultAllow}. Patterns are either an exact binary class
 * name, a package prefix ending in {@code .*} (for example
 * {@code java.io.*}) or {@code *} for everything.
 */
public final class JavaAccessPolicy {

    private static final String[] DEFAULT_ALLOW = {
        "java.lang.Math", "java.lang.String", "java.lang.StringBuilder",
        "java.lang.StringBuffer", "java.lang.Object", "java.lang.Number",
        "java.lang.Integer", "java.lang.Long", "java.lang.Double",
        "java.lang.Float", "java.lang.Short", "java.lang.Byte",
        "java.lang.Boolean", "java.lang.Character", "java.lang.CharSequence",
        "java.lang.Enum", "java.util.*"
    };

    private static final String[] DEFAULT_DENY = {
        "java.lang.Runtime", "java.lang.Process", "java.lang.ProcessBuilder",
        "java.lang.ProcessHandle", "java.lang.System", "java.lang.Thread",
        "java.lang.ThreadGroup", "java.lang.Class", "java.lang.ClassLoader",
        "java.lang.SecurityManager", "java.lang.reflect.*",
        "java.lang.invoke.*", "java.io.*", "java.nio.*", "java.net.*",
        "java.security.*", "javax.script.*", "sun.*", "com.sun.*", "jdk.*"
    };

    /** Allows every class. Hosts embedding trusted code only. */
    public static final JavaAccessPolicy UNRESTRICTED =
            new JavaAccessPolicy(new String[0], new String[0], true, "unrestricted");

    /**
     * Default policy for a fresh {@code LuaState}: ordinary application and
     * collection classes work, but process execution, reflection, file and
     * network access are denied.
     */
    public static final JavaAccessPolicy DEFAULT =
            new JavaAccessPolicy(DEFAULT_ALLOW, DEFAULT_DENY, true, "default");

    /**
     * Strict policy installed by {@link org.luava.runtime.LuaState#sandbox()}:
     * only the small safe allowlist is reachable; everything else is denied
     * unless the host adds an explicit allow pattern.
     */
    public static final JavaAccessPolicy STRICT =
            new JavaAccessPolicy(DEFAULT_ALLOW, new String[0], false, "strict");

    private final String[] allow;
    private final String[] deny;
    private final boolean defaultAllow;
    private final String label;

    public JavaAccessPolicy(String[] allow, String[] deny, boolean defaultAllow) {
        this(allow, deny, defaultAllow, "custom");
    }

    private JavaAccessPolicy(String[] allow, String[] deny, boolean defaultAllow, String label) {
        this.allow = allow.clone();
        this.deny = deny.clone();
        this.defaultAllow = defaultAllow;
        this.label = label;
    }

    /** Returns a copy of this policy with additional allow patterns. */
    public JavaAccessPolicy withAllow(String... patterns) {
        return new JavaAccessPolicy(concat(this.allow, patterns), this.deny, this.defaultAllow, label);
    }

    /** Returns a copy of this policy with additional deny patterns. */
    public JavaAccessPolicy withDeny(String... patterns) {
        return new JavaAccessPolicy(this.allow, concat(this.deny, patterns), this.defaultAllow, label);
    }

    public boolean isAllowed(String className) {
        if (className == null) return false;
        for (String p : allow) {
            if (matches(p, className)) return true;
        }
        for (String p : deny) {
            if (matches(p, className)) return false;
        }
        return defaultAllow;
    }

    /**
     * Throws a {@link LuaException} when {@code className} is not reachable
     * under this policy.
     */
    public void check(String className) {
        if (!isAllowed(className)) {
            throw new LuaException("access denied: " + className
                    + " is blocked by the Java access policy (" + label + ")");
        }
    }

    public String label() {
        return label;
    }

    private static boolean matches(String pattern, String name) {
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith(".*")) {
            return name.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(name);
    }

    private static String[] concat(String[] a, String[] b) {
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // --- Active policy for the currently running Lua chunk ----------------

    private static final ThreadLocal<JavaAccessPolicy> ACTIVE = new ThreadLocal<>();

    /** Installs {@code policy} as the active one and returns the previous. */
    public static JavaAccessPolicy setActive(JavaAccessPolicy policy) {
        JavaAccessPolicy prev = ACTIVE.get();
        ACTIVE.set(policy);
        return prev;
    }

    /** Restores the policy returned by {@link #setActive}. */
    public static void restoreActive(JavaAccessPolicy prev) {
        if (prev == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(prev);
        }
    }

    /**
     * The policy governing the current thread, defaulting to {@link #DEFAULT}
     * when no Lua chunk is running (safe fallback, never unrestricted).
     */
    public static JavaAccessPolicy active() {
        JavaAccessPolicy p = ACTIVE.get();
        return p != null ? p : DEFAULT;
    }
}

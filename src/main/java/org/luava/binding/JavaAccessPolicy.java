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
 * <p>Rules are ordered and evaluated last-match-wins (with an allow/deny list
 * in default policies placing dangerous {@code java.util} subpackages/classes
 * after {@code java.util.*}, while subsequent {@link #withAllow} and
 * {@link #withDeny} calls append overriding rules). When no rule matches,
 * {@code isAllowed} falls back to {@code defaultAllow}. Patterns are either an
 * exact binary class name (also matching inner classes {@code Foo$*}), a
 * package prefix ending in {@code .*} (for example {@code java.io.*}) or
 * {@code *} for everything.
 */
public final class JavaAccessPolicy {

    private record Rule(String pattern, boolean allow) {}

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
        "java.lang.invoke.*", "java.lang.instrument.*", "java.lang.management.*",
        "java.lang.module.*", "java.lang.foreign.*",
        "java.io.*", "java.nio.*", "java.net.*", "java.rmi.*", "java.sql.*",
        "java.security.*", "javax.script.*", "javax.naming.*", "javax.management.*",
        "javax.tools.*", "javax.xml.*", "javax.sql.*", "javax.net.*",
        "javax.security.*", "javax.crypto.*",
        "java.util.Formatter", "java.util.Timer", "java.util.TimerTask",
        "java.util.ServiceLoader", "java.util.ResourceBundle",
        "java.util.PropertyResourceBundle",
        "java.util.logging.*", "java.util.zip.*", "java.util.jar.*",
        "java.util.concurrent.*", "java.util.prefs.*", "java.util.spi.*",
        "sun.*", "com.sun.*", "jdk.*",
        "org.luava.binding.JavaAccessPolicy", "org.luava.binding.JavaInteropLib",
        "org.luava.binding.JavaFunctionBuilder", "org.luava.binding.LuaDataConverter",
        "org.luava.binding.ModuleBinder", "org.luava.binding.OverloadResolver",
        "org.luava.runtime.LuaState", "org.luava.runtime.LuaValue",
        "org.luava.runtime.LuaUserdata", "org.luava.runtime.LuaFunction",
        "org.luava.runtime.LuaTable", "org.luava.runtime.LuaString",
        "org.luava.runtime.LuaInteger", "org.luava.runtime.LuaFloat",
        "org.luava.runtime.LuaBoolean", "org.luava.runtime.LuaNil",
        "org.luava.runtime.Varargs", "org.luava.runtime.Stats",
        "org.luava.runtime.LuaException", "org.luava.runtime.LuaExit",
        "org.luava.runtime.jit.*", "org.luava.runtime.bytecode.*",
        "org.luava.runtime.eval.*", "org.luava.runtime.standard.*",
        "org.luava.runtime.concurrency.*", "org.luava.runtime.interop.*",
        "org.luava.cli.*", "org.luava.frontend.lexer.*",
        "org.luava.frontend.parser.*", "org.luava.frontend.ast.*",
        "org.luava.midend.*", "org.luava.emmydoc.*"
    };

    /** Allows every class. Hosts embedding trusted code only. */
    public static final JavaAccessPolicy UNRESTRICTED =
            new JavaAccessPolicy(new Rule[0], true, "unrestricted");

    /**
     * Default policy for a fresh {@code LuaState}: ordinary application and
     * collection classes work, but process execution, reflection, file,
     * network, engine internals and dangerous {@code java.util} subpackages are denied.
     */
    public static final JavaAccessPolicy DEFAULT =
            new JavaAccessPolicy(buildOrderedRules(DEFAULT_ALLOW, DEFAULT_DENY), true, "default");

    /**
     * Strict policy installed by {@link org.luava.runtime.LuaState#sandbox()}:
     * only the safe subset of the allowlist can be loaded by name; everything
     * else is denied unless the host adds an explicit allow pattern. The deny
     * list is evaluated after {@code DEFAULT_ALLOW} so dangerous
     * {@code java.util} classes and subpackages stay blocked even when
     * {@code java} is re-enabled.
     */
    public static final JavaAccessPolicy STRICT =
            new JavaAccessPolicy(buildOrderedRules(DEFAULT_ALLOW, DEFAULT_DENY), false, "strict", false, true);

    /**
     * Closed member-allowlist variant of {@link #STRICT}: both class loading
     * and member/SAM invocation on host-injected objects require an explicit
     * allow match (unless wrapped via {@link JavaCapability}).
     */
    public static final JavaAccessPolicy STRICT_ALLOWLIST =
            new JavaAccessPolicy(buildOrderedRules(DEFAULT_ALLOW, DEFAULT_DENY),
                    false, "strict-allowlist", true, true);

    private final Rule[] rules;
    private final boolean defaultAllow;
    private final String label;
    private final boolean memberAllowlistOnly;
    private final boolean deferClassInit;

    public JavaAccessPolicy(String[] allow, String[] deny, boolean defaultAllow) {
        this(buildCustomRules(allow, deny), defaultAllow, "custom", false, false);
    }

    private JavaAccessPolicy(Rule[] rules, boolean defaultAllow, String label) {
        this(rules, defaultAllow, label, false, false);
    }

    private JavaAccessPolicy(Rule[] rules, boolean defaultAllow, String label,
            boolean memberAllowlistOnly, boolean deferClassInit) {
        this.rules = rules.clone();
        this.defaultAllow = defaultAllow;
        this.label = label;
        this.memberAllowlistOnly = memberAllowlistOnly;
        this.deferClassInit = deferClassInit;
    }

    private static Rule[] buildOrderedRules(String[] firstAllow, String[] thenDeny) {
        Rule[] out = new Rule[firstAllow.length + thenDeny.length];
        int idx = 0;
        for (String p : firstAllow) {
            out[idx++] = new Rule(p, true);
        }
        for (String p : thenDeny) {
            out[idx++] = new Rule(p, false);
        }
        return out;
    }

    private static Rule[] buildCustomRules(String[] allow, String[] deny) {
        int aLen = (allow != null) ? allow.length : 0;
        int dLen = (deny != null) ? deny.length : 0;
        Rule[] out = new Rule[aLen + dLen];
        int idx = 0;
        if (deny != null) {
            for (String p : deny) {
                out[idx++] = new Rule(p, false);
            }
        }
        if (allow != null) {
            for (String p : allow) {
                out[idx++] = new Rule(p, true);
            }
        }
        return out;
    }

    /** Returns a copy of this policy with additional allow patterns. */
    public JavaAccessPolicy withAllow(String... patterns) {
        return new JavaAccessPolicy(appendRules(this.rules, patterns, true),
                this.defaultAllow, label, memberAllowlistOnly, deferClassInit);
    }

    /** Returns a copy of this policy with additional deny patterns. */
    public JavaAccessPolicy withDeny(String... patterns) {
        return new JavaAccessPolicy(appendRules(this.rules, patterns, false),
                this.defaultAllow, label, memberAllowlistOnly, deferClassInit);
    }

    /** Returns a copy controlling whether member access uses allowlist-only mode. */
    public JavaAccessPolicy withMemberAllowlistOnly(boolean memberAllowlistOnly) {
        if (this.memberAllowlistOnly == memberAllowlistOnly) {
            return this;
        }
        return new JavaAccessPolicy(this.rules, this.defaultAllow, label, memberAllowlistOnly, deferClassInit);
    }

    /** Returns a copy controlling whether {@code java.import} defers static initializers. */
    public JavaAccessPolicy withDeferClassInit(boolean deferClassInit) {
        if (this.deferClassInit == deferClassInit) {
            return this;
        }
        return new JavaAccessPolicy(this.rules, this.defaultAllow, label, memberAllowlistOnly, deferClassInit);
    }

    public boolean isMemberAllowlistOnly() {
        return memberAllowlistOnly;
    }

    public boolean isDeferClassInit() {
        return deferClassInit;
    }

    public boolean isAllowed(String className) {
        String normalized = normalizeClassName(className);
        if (normalized == null) return false;
        for (int i = rules.length - 1; i >= 0; i--) {
            if (matches(rules[i].pattern, normalized)) {
                return rules[i].allow;
            }
        }
        return defaultAllow;
    }

    /**
     * Whether {@code className} is explicitly denied under the rule chain
     * (including dangerous JDK subpackages carved out of {@code java.util.*}
     * and engine internals). Used for member access on objects the host has
     * already handed to Lua: host service classes do not match any deny rule
     * and stay usable, while {@code getClassLoader()} / reflection paths are
     * cut off.
     */
    public boolean isDenied(String className) {
        String normalized = normalizeClassName(className);
        if (normalized == null) return true;
        for (int i = rules.length - 1; i >= 0; i--) {
            if (matches(rules[i].pattern, normalized)) {
                return !rules[i].allow;
            }
        }
        return false;
    }

    /**
     * Checks {@code clazz} and all of its supertypes/interfaces against deny rules.
     */
    public boolean isDeniedHierarchy(Class<?> clazz) {
        for (Class<?> cur = clazz; cur != null; cur = cur.getSuperclass()) {
            if (cur == java.io.Serializable.class) {
                continue;
            }
            if (isDenied(cur.getName())) {
                return true;
            }
            for (Class<?> iface : cur.getInterfaces()) {
                if (iface != java.io.Serializable.class && isDeniedHierarchy(iface)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether member or SAM access is allowed on {@code targetClass} and {@code declaringClass}.
     */
    public boolean allowsMember(Class<?> targetClass, Class<?> declaringClass, String memberName) {
        if (this != UNRESTRICTED && memberName != null && isDangerousObjectMethod(declaringClass, memberName)) {
            return false;
        }
        if (targetClass != null) {
            if (isDeniedHierarchy(targetClass)) {
                return false;
            }
            if (memberAllowlistOnly) {
                boolean allowedTarget = isAllowed(targetClass.getName())
                        || (targetClass.isSynthetic() && declaringClass != null
                                && declaringClass != Object.class && isAllowed(declaringClass.getName()));
                if (!allowedTarget) {
                    return false;
                }
            }
        }
        if (declaringClass != null) {
            if (isDeniedHierarchy(declaringClass)) {
                return false;
            }
            if (memberAllowlistOnly && !isAllowed(declaringClass.getName())) {
                return false;
            }
        }
        return true;
    }

    /** Throws a {@link LuaException} if member/SAM access is blocked by this policy. */
    public void checkMember(Class<?> targetClass, Class<?> declaringClass, String memberName) {
        if (!allowsMember(targetClass, declaringClass, memberName)) {
            String culprit = (targetClass != null ? targetClass.getName()
                    : (declaringClass != null ? declaringClass.getName() : "<unknown>"));
            throw new LuaException("access denied: " + culprit
                    + (memberName != null ? "." + memberName : "")
                    + " is blocked by the Java access policy (" + label + ")");
        }
    }

    private static boolean isDangerousObjectMethod(Class<?> declaringClass, String memberName) {
        if (declaringClass != Object.class && declaringClass != null) {
            return false;
        }
        return "wait".equals(memberName) || "notify".equals(memberName) || "notifyAll".equals(memberName);
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

    private static String normalizeClassName(String className) {
        if (className == null) return null;
        int i = 0;
        int len = className.length();
        while (i < len && className.charAt(i) == '[') {
            i++;
        }
        if (i == 0) {
            return className;
        }
        if (i < len && className.charAt(i) == 'L' && className.endsWith(";")) {
            return className.substring(i + 1, len - 1);
        }
        return className.substring(i);
    }

    private static boolean matches(String pattern, String name) {
        if (pattern == null) return false;
        if ("*".equals(pattern)) return true;
        if (pattern.endsWith(".*")) {
            return name.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(name) || (name.length() > pattern.length()
                && name.charAt(pattern.length()) == '$'
                && name.startsWith(pattern));
    }

    private static Rule[] appendRules(Rule[] existing, String[] patterns, boolean allow) {
        if (patterns == null || patterns.length == 0) {
            return existing;
        }
        Rule[] out = new Rule[existing.length + patterns.length];
        System.arraycopy(existing, 0, out, 0, existing.length);
        for (int i = 0; i < patterns.length; i++) {
            out[existing.length + i] = new Rule(patterns[i], allow);
        }
        return out;
    }

    // --- Active policy for the currently running Lua chunk ----------------

    private static final ThreadLocal<JavaAccessPolicy> ACTIVE = new ThreadLocal<>();

    /** Installs {@code policy} as the active one and returns the previous. */
    public static JavaAccessPolicy setActive(JavaAccessPolicy policy) {
        JavaAccessPolicy prev = ACTIVE.get();
        if (policy == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(policy);
        }
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

    /** Returns the thread's explicitly installed policy, or {@code null} when none is active. */
    public static JavaAccessPolicy activeOrNull() {
        return ACTIVE.get();
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

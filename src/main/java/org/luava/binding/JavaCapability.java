/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.binding;

import org.luava.runtime.LuaException;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Capability wrapper restricting Lua access on a host-provided Java object to
 * an explicit allowlist of public method names. Fields, {@code getClass()},
 * monitor methods, array/list/map indexing, unlisted methods, and unwrapped
 * non-primitive return values remain inaccessible even if the underlying object
 * exposes dozens of public APIs.
 */
public final class JavaCapability {
    private final Object target;
    private final Set<String> allowedMethods;

    public JavaCapability(Object target, String... allowedMethods) {
        if (target == null) {
            throw new LuaException("capability target must not be null");
        }
        if (target instanceof JavaCapability nested) {
            this.target = nested.target;
            Set<String> intersection = new LinkedHashSet<>();
            if (allowedMethods != null) {
                for (String name : allowedMethods) {
                    if (name != null && nested.allowedMethods.contains(name)) {
                        intersection.add(name);
                    }
                }
            }
            this.allowedMethods = Collections.unmodifiableSet(intersection);
            return;
        }
        this.target = target;
        Set<String> set = new LinkedHashSet<>();
        if (allowedMethods != null) {
            for (String name : allowedMethods) {
                if (name != null && !isBlockedIntrinsic(name)) {
                    set.add(name);
                }
            }
        }
        this.allowedMethods = Collections.unmodifiableSet(set);
    }

    private static boolean isBlockedIntrinsic(String name) {
        return "getClass".equals(name)
                || "wait".equals(name)
                || "notify".equals(name)
                || "notifyAll".equals(name);
    }

    public Object target() {
        return target;
    }

    public boolean allowsMethod(String name) {
        return name != null && allowedMethods.contains(name);
    }

    public Set<String> allowedMethods() {
        return allowedMethods;
    }

    @Override
    public String toString() {
        return "capability<" + target.getClass().getSimpleName() + ">";
    }
}

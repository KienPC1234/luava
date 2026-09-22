/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime.standard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Host hook for {@code require}: maps a module resource path to Lua source
 * <em>without</em> touching the filesystem. Register one on a
 * {@link org.luava.runtime.LuaState} to load scripts packaged inside a JAR
 * ({@code classpath:scripts/rules.lua}), an in-memory map, a database, a ZIP
 * archive, or any other virtual store.
 *
 * <p>Lua source is a byte stream: implementations must decode with ISO-8859-1
 * (one char per byte) so non-ASCII literals survive, exactly like the
 * filesystem searcher. Returning {@code null} means "not found", which lets
 * {@code require} fall through to the next searcher.
 */
@FunctionalInterface
public interface LuaResourceLoader {
    /**
     * Returns the Lua source for {@code path}, or {@code null} when this
     * loader does not provide it.
     */
    String read(String path);

    /**
     * Default loader backed by a {@link ClassLoader}: {@code read("a/b.lua")}
     * fetches the classpath resource {@code a/b.lua}. This makes
     * {@code require} work for scripts bundled in the application JAR with
     * no host configuration.
     */
    static LuaResourceLoader classpath() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = LuaResourceLoader.class.getClassLoader();
        }
        return classpath(cl);
    }

    /** Classpath loader for an explicit {@link ClassLoader}. */
    static LuaResourceLoader classpath(ClassLoader cl) {
        return path -> {
            if (path == null || path.isEmpty()) {
                return null;
            }
            String resource = path.startsWith("/") ? path.substring(1) : path;
            try (InputStream in = cl.getResourceAsStream(resource)) {
                if (in == null) {
                    return null;
                }
                return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            } catch (IOException e) {
                return null;
            }
        };
    }
}

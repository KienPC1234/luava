/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;
import org.luava.runtime.standard.LuaResourceLoader;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code require} through a host-provided {@link LuaResourceLoader}, so Lua
 * modules can live inside a JAR, an in-memory map, or any virtual store
 * rather than only on the filesystem.
 */
public class VirtualModuleLoaderTest {

    private static LuaResourceLoader mapLoader(Map<String, String> files) {
        return files::get;
    }

    @Test
    void requireLoadsFromVirtualStore() {
        LuaState state = new LuaState().resourceLoader(mapLoader(Map.of(
                "rules.lua", "local M={} function M.greet(n) return 'hi '..n end return M")));
        assertEquals("hi lua",
                state.eval("local r=require('rules') return r.greet('lua')").toLuaString());
    }

    @Test
    void dottedModuleNameMapsToPathSegment() {
        LuaState state = new LuaState().resourceLoader(mapLoader(Map.of(
                "scripts/rules.lua", "return { answer = 42 }")));
        assertEquals(42L,
                state.eval("return require('scripts.rules').answer").toLong());
    }

    @Test
    void virtualModulesAreCachedInPackageLoaded() {
        // A single load must populate package.loaded, so a second require of
        // the same module returns the identical table.
        LuaState state = new LuaState().resourceLoader(mapLoader(Map.of(
                "once.lua", "return { }")));
        assertTrue(state.eval("return require('once') == require('once')").toBoolean());
    }

    @Test
    void missingModuleStillReportsStandardSearcherMessages() {
        // With a loader present but nothing found, the error must still list
        // the PUC filesystem search attempts (the virtual searcher adds no
        // error text, keeping standard require semantics).
        LuaState state = new LuaState().resourceLoader(mapLoader(Map.of()));
        LuaException e = assertThrows(LuaException.class,
                () -> state.eval("require('definitely_absent')"));
        assertTrue(e.getMessage().contains("module 'definitely_absent' not found"),
                "missing module error: " + e.getMessage());
        assertTrue(e.getMessage().contains("no field package.preload"),
                "error must include the preload searcher message");
    }

    @Test
    void resourceSearcherIsAppendedWithoutDisturbingPucIndices() {
        // PUC's four standard searchers keep their indices 1..4; the virtual
        // searcher is appended at 5, so existing searcher indices are stable.
        // It is a no-op for states with no loader registered.
        LuaState state = new LuaState().resourceLoader(mapLoader(Map.of()));
        assertTrue(state.eval("return package.searchers[5]").isFunction());
        assertEquals(5L, state.eval("return #package.searchers").toLong());
        assertTrue(state.eval("return package.searchers[1] ~= nil and package.searchers[4] ~= nil")
                .toBoolean());
    }

    @Test
    void inMemoryLoaderCanOverrideSearchPath() {
        LuaState state = new LuaState()
                .resourceLoader(mapLoader(Map.of("mods/a.lua", "return 'A'")))
                .resourcePath("mods/?.lua");
        assertEquals("A", state.eval("return require('a')").toLuaString());
    }

    @Test
    void classpathLoaderFactoryIsUsable() {
        // The built-in classpath loader must not throw even when a module is
        // absent, so it is safe to leave installed.
        LuaState state = new LuaState().resourceLoader(LuaResourceLoader.classpath());
        assertThrows(LuaException.class, () -> state.eval("require('no.such.classpath.module')"));
    }
}

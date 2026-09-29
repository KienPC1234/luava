/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for {@code load} / binary-chunk diagnostics, checked against
 * PUC Lua 5.4.
 *
 * <p>PUC's loader reports which header field is wrong, names the chunk the way
 * {@code luaU_undump} derives it, and reads each field before comparing it (so
 * a short read is "truncated chunk" while a wrong value gets its own message).
 * Luava used to answer "truncated binary chunk" for every failure, which hid
 * the actual cause. These cases pin the messages to the reference.
 */
public class LuavaBinaryChunkMessageTest {

    /** The header of a real chunk, as a Lua source literal, for corruptions. */
    private static final String HEADER_LITERAL =
            "\"\\27Lua\\84\\0\\25\\147\\r\\n\\26\\n\\4\\8\\8\"";

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(body).toLuaString();
    }

    private static String loadError(String literal) {
        return run("local f, e = load(" + literal + ") return tostring(e)");
    }

    @Test
    void garbageAfterTheSignatureIsAVersionMismatch() {
        assertEquals("binary string: bad binary format (version mismatch)",
                loadError("\"\\27Lua garbage\""));
    }

    @Test
    void signatureOnlyIsTruncated() {
        assertEquals("binary string: bad binary format (truncated chunk)",
                loadError("\"\\27Lua\""));
    }

    @Test
    void aWrongSignatureIsNotABinaryChunk() {
        assertEquals("binary string: bad binary format (not a binary chunk)",
                loadError("\"\\27Lux\""));
    }

    @Test
    void wrongFormatByteIsReported() {
        assertEquals("binary string: bad binary format (format mismatch)",
                loadError("\"\\27Lua\\84\\1\\25\\147\\r\\n\\26\\n\\4\\8\\8\""));
    }

    @Test
    void wrongDataBytesAreACorruptedChunk() {
        assertEquals("binary string: bad binary format (corrupted chunk)",
                loadError("\"\\27Lua\\84\\0XXXXXXXXXX\\4\\8\\8\""));
    }

    @Test
    void wrongInstructionSizeIsNamed() {
        assertEquals("binary string: bad binary format (Instruction size mismatch)",
                loadError("\"\\27Lua\\84\\0\\25\\147\\r\\n\\26\\n\\8\\8\\8\""));
    }

    @Test
    void wrongIntegerSizeIsNamed() {
        assertEquals("binary string: bad binary format (lua_Integer size mismatch)",
                loadError("\"\\27Lua\\84\\0\\25\\147\\r\\n\\26\\n\\4\\4\\8\""));
    }

    @Test
    void wrongNumberSizeIsNamed() {
        assertEquals("binary string: bad binary format (lua_Number size mismatch)",
                loadError("\"\\27Lua\\84\\0\\25\\147\\r\\n\\26\\n\\4\\8\\4\""));
    }

    @Test
    void wrongCheckIntegerIsNamed() {
        assertEquals("binary string: bad binary format (integer format mismatch)",
                loadError(HEADER_LITERAL + " .. string.rep(\"\\0\", 20)"));
    }

    @Test
    void aHeaderOnlyChunkIsTruncated() {
        assertEquals("binary string: bad binary format (truncated chunk)",
                loadError(HEADER_LITERAL));
    }

    /** The name in the message follows luaU_undump's derivation. */
    @Test
    void anEqualsNameIsStripped() {
        assertEquals("mychunk: bad binary format (version mismatch)",
                loadError("\"\\27Lua garbage\", \"=mychunk\""));
    }

    @Test
    void anAtNameIsStripped() {
        assertEquals("file.lua: bad binary format (version mismatch)",
                loadError("\"\\27Lua garbage\", \"@file.lua\""));
    }

    @Test
    void noNameBecomesBinaryString() {
        assertEquals("binary string: bad binary format (version mismatch)",
                loadError("\"\\27Lua garbage\""));
    }

    /**
     * A number from a load reader is a valid piece: lua_tolstring accepts a
     * number, so PUC's generic_reader does not reject it.
     */
    @Test
    void aNumberFromAReaderIsAccepted() {
        assertEquals("3", run("""
                local parts = {"return ", 1, " + 2", nil}
                local i = 0
                return load(function() i = i + 1 return parts[i] end)()
                """));
    }

    /** A table from a reader is rejected. */
    @Test
    void aTableFromAReaderIsRejected() {
        assertEquals("reader function must return a string", run("""
                local parts = {"return 1", {}}
                local i = 0
                local f, e = load(function() i = i + 1 return parts[i] end)
                return tostring(e)
                """));
    }

    /** A string.dump round-trips, so the stricter checks did not break it. */
    @Test
    void ownDumpStillRoundTrips() {
        assertEquals("5,9,13", run("""
                local f = function(a, b) return a + b end
                local a = load(string.dump(f))(2, 3)
                local b = load(string.dump(f, true))(4, 5)
                local p = os.tmpname()
                local w = assert(io.open(p, "wb"))
                w:write(string.dump(f))
                w:close()
                local c = loadfile(p)(6, 7)
                os.remove(p)
                return a .. "," .. b .. "," .. c
                """));
    }
}

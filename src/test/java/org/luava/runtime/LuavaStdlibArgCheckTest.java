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
 * Regression tests for standard-library argument handling, checked against
 * PUC Lua 5.4.
 *
 * <p>The theme is arguments that were neither accepted nor rejected: a value
 * that is not a string must not be silently stringified, because for
 * {@code os.execute} that means the shell actually runs whatever the coerced
 * text says, and for {@code file:read} it means the bad format quietly turns
 * into a line read. PUC's {@code luaL_checkstring} accepts a number (a number
 * is a string to {@code lua_tolstring}) and rejects everything else; these
 * cases pin that boundary.
 */
public class LuavaStdlibArgCheckTest {

    private static String run(String body) {
        LuaState s = new LuaState();
        return s.eval(body).toLuaString();
    }

    /** A table passed to os.execute must be rejected, not run as a shell word. */
    @Test
    void osExecuteRejectsATable() {
        assertEquals("false", run("return tostring(pcall(os.execute, {}))"));
    }

    /** A boolean is not a command either. */
    @Test
    void osExecuteRejectsABoolean() {
        assertEquals("false", run("return tostring(pcall(os.execute, true))"));
    }

    /**
     * The failure must come from the argument check, not from the shell
     * failing. A coerced table used to reach startShell and come back as a
     * command error ("exit 127") with ok == true.
     */
    @Test
    void osExecuteTableDoesNotReachTheShell() {
        assertEquals("false:string expected", run("""
                local ok, err = pcall(os.execute, {})
                return tostring(ok) .. ":" .. tostring(err):match("string expected") 
                """));
    }

    /** A number is a valid command string, matching luaL_checkstring. */
    @Test
    void osExecuteAcceptsANumber() {
        // `5` is not a real program, so the shell reports it; the point is
        // that the call is allowed through to the shell rather than rejected.
        assertEquals("true", run("return tostring(pcall(os.execute, 5))"));
    }

    /** No argument is still Lua's true (the shell is available). */
    @Test
    void osExecuteWithNoArgument() {
        assertEquals("true,true", run("""
                local ok, a = pcall(os.execute)
                return tostring(ok) .. "," .. tostring(a)
                """));
    }

    /** A table format for file:read must raise, not consume a line. */
    @Test
    void fileReadRejectsATableFormat() {
        assertEquals("false", run("""
                local path = os.tmpname()
                local w = assert(io.open(path, "w"))
                w:write("hello\\n")
                w:close()
                local f = assert(io.open(path, "r"))
                local ok = pcall(f.read, f, {})
                f:close()
                os.remove(path)
                return tostring(ok)
                """));
    }

    /** Same for a boolean. */
    @Test
    void fileReadRejectsABooleanFormat() {
        assertEquals("false", run("""
                local path = os.tmpname()
                local w = assert(io.open(path, "w"))
                w:write("hello\\n")
                w:close()
                local f = assert(io.open(path, "r"))
                local ok = pcall(f.read, f, true)
                f:close()
                os.remove(path)
                return tostring(ok)
                """));
    }

    /** A table format on the io.read default-input path too. */
    @Test
    void ioReadRejectsATableFormat() {
        assertEquals("false", run("return tostring(pcall(io.read, {}))"));
    }

    /**
     * A bad string format is an invalid format, not a line read. Asserted on
     * the stable parts rather than the exact function name: PUC itself reports
     * 'read' here but '?' for the method form, and this engine deliberately
     * uses one qualified name everywhere.
     */
    @Test
    void fileReadRejectsAnUnknownStringFormat() {
        assertEquals("true,true", run("""
                local path = os.tmpname()
                local w = assert(io.open(path, "w"))
                w:write("hello\\n")
                w:close()
                local f = assert(io.open(path, "r"))
                local ok, err = pcall(f.read, f, "x")
                f:close()
                os.remove(path)
                local e = tostring(err)
                return tostring(e:match("bad argument #2") ~= nil)
                       .. "," .. tostring(e:match("invalid format") ~= nil)
                """));
    }

    /** A non-integral count is rejected as having no integer representation. */
    @Test
    void fileReadRejectsAFractionalCount() {
        assertEquals("number has no integer representation", run("""
                local path = os.tmpname()
                local w = assert(io.open(path, "w"))
                w:write("hello\\n")
                w:close()
                local f = assert(io.open(path, "r"))
                local ok, err = pcall(f.read, f, 2.5)
                f:close()
                os.remove(path)
                return tostring(err):match("number has no integer representation")
                """));
    }

    /** The valid formats must keep working. */
    @Test
    void fileReadStillWorksForEveryValidFormat() {
        assertEquals("hello|123|123", run("""
                local path = os.tmpname()
                local w = assert(io.open(path, "w"))
                w:write("hello\\n123 tail\\n")
                w:close()
                local f = assert(io.open(path, "r"))
                local a = f:read("l")
                local b = f:read(3)
                f:close()
                local g = assert(io.open(path, "r"))
                g:read("l")
                local c = g:read("n")
                g:close()
                os.remove(path)
                return a .. "|" .. b .. "|" .. tostring(c)
                """));
    }

    /**
     * os.remove names the offending argument, type and function. The exact
     * function name is not asserted: PUC reports 'remove' from the CLI and
     * 'os.remove' through the API, and this engine uses the qualified name
     * everywhere.
     */
    @Test
    void osRemoveMessageIncludesTheType() {
        assertEquals("true,true,true", run("""
                local ok, err = pcall(os.remove, {})
                local e = tostring(err)
                return tostring(e:match("bad argument #1") ~= nil)
                       .. "," .. tostring(e:match("string expected, got table") ~= nil)
                       .. "," .. tostring(e:match("remove") ~= nil)
                """));
    }

    /** io.write still rejects a table and names the type. */
    @Test
    void ioWriteMessageIncludesTheType() {
        assertEquals("true,true,true", run("""
                local ok, err = pcall(io.write, {})
                local e = tostring(err)
                return tostring(e:match("bad argument #1") ~= nil)
                       .. "," .. tostring(e:match("string expected, got table") ~= nil)
                       .. "," .. tostring(e:match("write") ~= nil)
                """));
    }
}

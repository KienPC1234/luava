/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke tests for the stand-alone CLI and REPL entry point. */
public class MainTest {

    private record Run(int status, String out, String err) {}

    private static Run run(String stdin, String... args) {
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
            int status = Main.run(args, in);
            return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
    }

    @Test
    void dashERunsStatement() {
        Run r = run("", "-e", "print('hello', 1+2)");
        assertEquals(0, r.status());
        assertEquals("hello\t3", r.out().trim());
    }

    @Test
    void runFileExposesArgTable(@TempDir Path dir) throws Exception {
        Path script = dir.resolve("s.lua");
        Files.writeString(script, "print(#arg, arg[0], arg[1])");
        Run r = run("", script.toString(), "one");
        assertEquals(0, r.status());
        assertTrue(r.out().startsWith("1\t" + script), "output was: " + r.out());
        assertTrue(r.out().contains("one"), "arg[1] must be exposed");
    }

    @Test
    void replEvaluatesExpressionsStatementsAndRecovers() {
        // Each REPL line is its own chunk (like the reference interpreter), so
        // persistent values must be globals, not locals.
        String input = "1+2\n"
                + "x = 10\n"
                + "x * 3\n"
                + "error('boom')\n"
                + "'a' .. 'b'\n";
        Run r = run(input);
        // A clean exit on EOF.
        assertEquals(0, r.status());
        assertTrue(r.out().contains("3"), "1+2 should print 3: " + r.out());
        assertTrue(r.out().contains("30"), "x*3 should print 30: " + r.out());
        assertTrue(r.out().contains("ab"), "concat should print ab: " + r.out());
        assertTrue(r.err().contains("boom"), "runtime errors must be reported, not fatal");
    }

    @Test
    void replHandlesMultiLineConstruct() {
        Run r = run("for i=1,2 do\nprint('n', i)\nend\n");
        assertEquals(0, r.status());
        assertTrue(r.out().contains("n\t1") && r.out().contains("n\t2"), r.out());
    }

    @Test
    void replReusesOneStateAcrossLines() {
        // Globals persist across REPL lines (each line is its own chunk, like
        // the reference interpreter), and the state must not be rebuilt per
        // line (which used to leak a registered state every keystroke).
        Run r = run("x = 1\nx = x + 41\nprint(x)\n");
        assertEquals(0, r.status());
        assertTrue(r.out().contains("42"), r.out());
    }

    @Test
    void versionAndUsage() {
        assertTrue(run("", "-v").out().contains("Lua 5.4"));
        assertTrue(run("", "-h").out().contains("usage: luava"));
        assertEquals(1, run("", "-e").status());
    }
}

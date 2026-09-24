/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava.cli;

import org.luava.runtime.LuaException;
import org.luava.runtime.LuaState;
import org.luava.runtime.LuaValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stand-alone command line front end for Luava: run a script file, evaluate
 * a string with {@code -e}, or open an interactive REPL. This is a
 * convenience tool for developing and smoke-testing Lua 5.4 syntax against
 * the engine; the engine's primary use remains embedding via
 * {@link LuaState}.
 *
 * <pre>
 *   java -jar luava.jar script.lua [args...]
 *   java -jar luava.jar -e "print('hi')"
 *   java -jar luava.jar            # interactive REPL (also on a TTY)
 * </pre>
 */
public final class Main {

    private static final String PROMPT = "> ";
    private static final String CONTINUE = ">> ";

    private Main() {}

    public static void main(String[] args) {
        int status = run(args, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        if (status != 0) {
            System.exit(status);
        }
    }

    static int run(String[] args, BufferedReader in) {
        LuaState state = new LuaState();
        try {
            if (args.length == 0) {
                return repl(state, in);
            }
            String first = args[0];
            if ("-e".equals(first) || "-E".equals(first)) {
                if (args.length < 2) {
                    System.err.println("luava: '-e' needs an argument");
                    return 1;
                }
                return evalChunk(state, args[1], "=(command line)");
            }
            if ("-v".equals(first) || "--version".equals(first)) {
                System.out.println(versionLine());
                return 0;
            }
            if ("-i".equals(first) || "--interactive".equals(first)) {
                // PUC's lua -i [script args]: run the script first (when given),
                // then enter the REPL with the same state.
                if (args.length > 1) {
                    String[] scriptArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
                    int status = runFile(state, scriptArgs[0], scriptArgs);
                    if (status != 0) {
                        return status;
                    }
                }
                return repl(state, in);
            }
            if ("-h".equals(first) || "--help".equals(first)) {
                printUsage();
                return 0;
            }
            if (first.startsWith("-")) {
                System.err.println("luava: unrecognized option '" + first + "'");
                printUsage();
                return 1;
            }
            return runFile(state, first, args);
        } catch (LuaException e) {
            printError(e);
            return 1;
        } catch (Throwable t) {
            System.err.println("luava: " + t);
            return 1;
        }
    }

    private static int runFile(LuaState state, String file, String[] args) {
        Path path = Path.of(file);
        final String source;
        try {
            // Lua source is bytes: one char per byte, so string.byte/#s and
            // non-ASCII literals behave exactly as in the reference CLI.
            source = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            System.err.println("luava: cannot open " + file + ": " + e.getMessage());
            return 1;
        }
        // Expose the arguments as the standard 'arg' table: arg[0] is the
        // script name and arg[1..] the script arguments (PUC convention).
        state.getGlobals().rawset(
                org.luava.runtime.LuaString.valueOf("arg"), buildArgTable(file, args));
        return evalChunk(state, source, "@" + file);
    }

    private static org.luava.runtime.LuaTable buildArgTable(String file, String[] args) {
        org.luava.runtime.LuaTable arg = new org.luava.runtime.LuaTable();
        arg.rawset(org.luava.runtime.LuaInteger.valueOf(0),
                org.luava.binding.LuaDataConverter.toLua(file));
        for (int i = 1; i < args.length; i++) {
            arg.rawset(org.luava.runtime.LuaInteger.valueOf(i),
                    org.luava.binding.LuaDataConverter.toLua(args[i]));
        }
        return arg;
    }

    private static int evalChunk(LuaState state, String source, String chunkName) {
        try {
            state.eval(source, chunkName);
            return 0;
        } catch (LuaException e) {
            printError(e);
            return 1;
        }
    }

    /**
     * Interactive read-eval-print loop. Multi-line constructs are detected by
     * a parse error that mentions {@code <eof>} / incomplete input, in which
     * case the REPL keeps reading with a continuation prompt. Each completed
     * chunk is evaluated as an expression when possible, so typing {@code 1+2}
     * prints {@code 3}, mirroring the reference {@code lua} interpreter.
     */
    private static int repl(LuaState state, BufferedReader in) {
        System.out.println(versionLine() + " — Ctrl-D to exit");
        StringBuilder buffer = new StringBuilder();
        boolean pending = false;
        while (true) {
            System.out.print(pending ? CONTINUE : PROMPT);
            System.out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                System.out.println();
                return 0;
            }
            if (line == null) {
                System.out.println();
                return 0;
            }
            buffer.append(line).append('\n');
            String chunk = buffer.toString();
            if (isIncomplete(state, chunk)) {
                pending = true;
                continue;
            }
            buffer.setLength(0);
            pending = false;
            evaluateInteractive(state, chunk);
        }
    }

    /**
     * Evaluates one REPL chunk. If it parses as {@code return <expr>} the
     * value is printed; otherwise it is compiled and run as a statement. A
     * parse failure of the expression form is not reported (the statement
     * form is authoritative); runtime errors from either form are reported
     * without exiting the REPL.
     */
    private static void evaluateInteractive(LuaState state, String chunk) {
        String expr = "return " + chunk;
        boolean exprParses;
        try {
            state.compile(expr, "=stdin", state.getGlobals());
            exprParses = true;
        } catch (LuaException e) {
            exprParses = false;
        }
        if (exprParses) {
            try {
                printResults(state.eval(expr, "=stdin"));
            } catch (LuaException e) {
                printError(e);
            }
            return;
        }
        try {
            state.eval(chunk, "=stdin");
        } catch (LuaException e) {
            printError(e);
        }
    }

    private static void printResults(LuaValue result) {
        if (result == null || result.isNil()) {
            return;
        }
        if (result instanceof org.luava.runtime.Varargs v) {
            for (LuaValue val : v.getValuesUnsafe()) {
                System.out.println(toDisplayString(val));
            }
            return;
        }
        System.out.println(toDisplayString(result));
    }

    private static String toDisplayString(LuaValue val) {
        if (val == null || val.isNil()) {
            return "nil";
        }
        return val.toLuaString();
    }

    /**
     * True when a parse failure is caused by a truncated construct rather
     * than a genuine syntax error, so the REPL should keep reading. The
     * parser reports the missing token as {@code <eof>} in that case.
     *
     * <p>Uses the REPL's own state (not a fresh one): compiling against the
     * live globals is correct, and creating a state per line would leak a
     * registered state and reinstall the stdlib on every keystroke.
     */
    private static boolean isIncomplete(LuaState state, String chunk) {
        try {
            state.compile(chunk, "=stdin", state.getGlobals());
            return false;
        } catch (LuaException e) {
            String msg = e.getMessage();
            return msg != null && msg.contains("<eof>");
        }
    }

    private static void printError(LuaException e) {
        String msg = e.getMessage();
        System.err.println(msg != null ? msg : e.toString());
    }

    /**
     * Project version, read from the jar manifest (Implementation-Version) so
     * it tracks {@code pom.xml} automatically. Falls back to {@code dev} when
     * running from a plain classpath, e.g. in tests or an IDE.
     */
    private static String version() {
        Package pkg = Main.class.getPackage();
        String v = pkg != null ? pkg.getImplementationVersion() : null;
        return v != null ? v : "dev";
    }

    /**
     * Release code name from the manifest, or empty when running from a plain
     * classpath (no manifest entry). Appended to the {@code -v}/{@code --version}
     * output so a jar self-identifies its release.
     */
    private static String codename() {
        // Package does not expose Implementation-Codename as an accessor, so
        // read it from the jar manifest directly.
        try {
            java.io.InputStream in = Main.class.getResourceAsStream("/META-INF/MANIFEST.MF");
            if (in != null) {
                try (java.io.InputStream stream = in) {
                    java.util.jar.Manifest mf = new java.util.jar.Manifest(stream);
                    return mf.getMainAttributes().getValue("Implementation-Codename");
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static String versionLine() {
        String c = codename();
        String name = (c != null && !c.isEmpty()) ? c + " " : "";
        return "Luava " + version() + " " + name + "(Lua 5.4)";
    }

    private static void printUsage() {
        System.out.println("""
                usage: luava [options] [script [args]]
                Available options are:
                  -e stat   execute string 'stat'
                  -i        enter interactive mode after executing 'script'
                  -v        show version information
                  -h        show this help
                With no script and no -e, an interactive REPL is started.
                """);
    }
}

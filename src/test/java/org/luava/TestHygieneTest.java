/*
 * Copyright 2026 Ha Tri Kien
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.luava;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the test suite itself against tests that can never fail.
 *
 * <p>A {@code @Test} method that asserts nothing is worse than no test: it
 * reports green forever and hides a regression. One such test existed here —
 * a "benchmark" that timed four workloads, printed the timings, and checked
 * nothing, so it would have passed even if every loop returned garbage.
 *
 * <p>This scans the test sources and requires every {@code @Test} to either
 * contain an assertion or delegate to a helper method defined in the same
 * file that contains one. It is deliberately a source scan: it needs no
 * instrumentation and it also catches a test whose body was emptied.
 */
public class TestHygieneTest {

    /** Assertion entry points: JUnit, AssertJ and Hamcrest spellings. */
    private static final Pattern ASSERTION =
            Pattern.compile("\\b(assert[A-Za-z]*|fail|verify|expect[A-Za-z]*)\\s*\\(");

    private static final Pattern TEST_ANNOTATION = Pattern.compile("@Test\\b");

    /**
     * Java keywords that syntactically look like a call followed by a block
     * ({@code if (x) { ... }}) but are not methods. Without this filter a
     * control block containing an assertion would register as an
     * "asserting helper" named {@code if}, and any test with an {@code if}
     * would be wrongly excused.
     */
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "do",
            "else", "new", "return", "throw", "assert");

    /** One declared method: name, body text, and whether it asserts. */
    private record Method(String name, String body, boolean asserts) {}

    @Test
    void everyTestAssertsSomething() throws IOException {
        Path root = Path.of("src/test/java");
        assertTrue(Files.isDirectory(root),
                "run from the module root; src/test/java must exist");

        List<String> offenders = new ArrayList<>();
        int tests = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.getFileName().toString().equals("TestHygieneTest.java")) {
                    continue;
                }
                String src = Files.readString(file, StandardCharsets.UTF_8);

                // Every method in the file, so a test may delegate its asserts.
                Set<String> assertingHelpers = new HashSet<>();
                Set<String> allHelpers = new HashSet<>();
                for (Method m : declaredMethods(src)) {
                    allHelpers.add(m.name());
                    if (m.asserts()) {
                        assertingHelpers.add(m.name());
                    }
                }

                for (Method t : testMethods(src)) {
                    tests++;
                    if (t.asserts() || callsAny(t.body(), assertingHelpers)) {
                        continue;
                    }
                    offenders.add(file + "::" + t.name()
                            + (callsAny(t.body(), allHelpers) ? " (only calls non-asserting helpers)" : ""));
                }
            }
        }
        assertTrue(tests > 100, "sanity: the scan must find the real suite, found " + tests);
        assertTrue(offenders.isEmpty(),
                "these @Test methods assert nothing and can never fail:\n  "
                        + String.join("\n  ", offenders));
    }

    @Test
    void testMethodsExistAndWereParsed() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            long annotated = files.filter(p -> p.toString().endsWith(".java"))
                    .mapToLong(p -> {
                        try {
                            return count(Files.readString(p, StandardCharsets.UTF_8), TEST_ANNOTATION);
                        } catch (IOException e) {
                            return 0;
                        }
                    }).sum();
            assertTrue(annotated > 100, "expected a large suite, found " + annotated + " @Test");
        }
    }

    /** The body of every {@code @Test}-annotated method. */
    private static List<Method> testMethods(String src) {
        List<Method> out = new ArrayList<>();
        Matcher m = TEST_ANNOTATION.matcher(src);
        while (m.find()) {
            String body = bodyAfterAnnotation(src, m.end());
            if (body == null) {
                continue;
            }
            out.add(new Method(nameOf(src, m.end()), body, ASSERTION.matcher(body).find()));
        }
        return out;
    }

    /** Every declared method in the file, annotated or not. */
    private static List<Method> declaredMethods(String src) {
        List<Method> out = new ArrayList<>();
        // A method declaration looks like `... name(args) ... {`. We find every
        // `{` that follows a `)` and treat the identifier before `(` as the name.
        Pattern decl = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\([^;{}]*\\)\\s*(?:throws [\\w., ]+)?\\{");
        Matcher m = decl.matcher(src);
        while (m.find()) {
            if (KEYWORDS.contains(m.group(1))) {
                continue;
            }
            String body = braceBody(src, m.end() - 1);
            if (body != null) {
                out.add(new Method(m.group(1), body, ASSERTION.matcher(body).find()));
            }
        }
        return out;
    }

    private static String bodyAfterAnnotation(String src, int from) {
        int brace = src.indexOf('{', from);
        return brace < 0 ? null : braceBody(src, brace);
    }

    /** Returns text from {@code open} through its matching brace, inclusive. */
    private static String braceBody(String src, int open) {
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open, i + 1);
                }
            } else if (c == '"' || c == '\'') {
                // Skip string/char literals so braces inside them do not count.
                char quote = c;
                for (i++; i < src.length(); i++) {
                    char d = src.charAt(i);
                    if (d == '\\') {
                        i++;
                    } else if (d == quote) {
                        break;
                    }
                }
            }
        }
        return null;
    }

    private static String nameOf(String src, int from) {
        int brace = src.indexOf('{', from);
        String head = src.substring(from, brace < 0 ? src.length() : brace);
        Matcher m = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(").matcher(head);
        String name = "?";
        while (m.find()) {
            name = m.group(1);
        }
        return name;
    }

    private static boolean callsAny(String body, Set<String> names) {
        for (String n : names) {
            if (Pattern.compile("\\b" + Pattern.quote(n) + "\\s*\\(").matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private static long count(String s, Pattern p) {
        return p.matcher(s).results().count();
    }
}

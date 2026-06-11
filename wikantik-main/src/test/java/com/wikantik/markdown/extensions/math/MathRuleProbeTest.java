/*
    Licensed to the Apache Software Foundation (ASF) under one
    or more contributor license agreements.  See the NOTICE file
    distributed with this work for additional information
    regarding copyright ownership.  The ASF licenses this file
    to you under the Apache License, Version 2.0 (the
    "License"); you may not use this file except in compliance
    with the License.  You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on an
    "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied.  See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package com.wikantik.markdown.extensions.math;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-discovery probe: measures precision, recall, support, and false-positive count for candidate
 * LaTeX-error detector predicates against the KaTeX ground truth in probe-dataset.json.
 *
 * <p>This is a <em>measurement</em> test — it does not assert correctness thresholds. The only
 * hard assertion is that the dataset loaded ≥400 rows (so the metrics are meaningful). Results are
 * printed to stdout as a ranked table for the orchestrator to inspect.
 */
class MathRuleProbeTest {

    // -----------------------------------------------------------------------
    // Dataset loading
    // -----------------------------------------------------------------------

    record Row(String id, String latex, String family, boolean katexOk, String katexError) {}

    private static List<Row> loadDataset() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = MathRuleProbeTest.class.getClassLoader()
                .getResourceAsStream("math/probe-dataset.json")) {
            assertNotNull(in, "probe-dataset.json not found on classpath (run node scripts/math-probe/probe.mjs first)");
            final JsonNode arr = mapper.readTree(in);
            final List<Row> rows = new ArrayList<>();
            for (final JsonNode n : arr) {
                rows.add(new Row(
                        n.get("id").asText(),
                        n.get("latex").asText(),
                        n.get("family").asText(),
                        n.get("katexOk").asBoolean(),
                        n.path("katexError").asText("")));
            }
            return rows;
        }
    }

    // -----------------------------------------------------------------------
    // Candidate rules — each Predicate<String> returns true when it predicts
    // the expression is INVALID (i.e. KaTeX would fail).
    // -----------------------------------------------------------------------

    /** Unbalanced curly braces, skipping escaped \{ and \}. */
    static boolean unbalancedBraces(final String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '{') { depth++; }
            else if (c == '}') { if (--depth < 0) { return true; } }
        }
        return depth != 0;
    }

    /**
     * Naive leftRight: counts plain substrings {@code \left} and {@code \right}.
     * Known false-positive risk: {@code \rightarrow} contains {@code \right}.
     */
    static boolean leftRightMismatch_naive(final String s) {
        int lefts  = 0, rights = 0, i = 0;
        while ((i = s.indexOf("\\left", i)) >= 0) { lefts++;  i += 5; }
        i = 0;
        while ((i = s.indexOf("\\right", i)) >= 0) { rights++; i += 6; }
        return lefts != rights;
    }

    /**
     * Token leftRight: only counts {@code \left} / {@code \right} when <em>not</em> followed by a
     * letter (so {@code \rightarrow} does <strong>not</strong> bump the right counter).
     * Regex: {@code \\left(?![a-zA-Z])} and {@code \\right(?![a-zA-Z])}.
     */
    private static final Pattern LEFT_TOKEN  = Pattern.compile("\\\\left(?![a-zA-Z])");
    private static final Pattern RIGHT_TOKEN = Pattern.compile("\\\\right(?![a-zA-Z])");

    static boolean leftRightMismatch_token(final String s) {
        final int lefts  = countMatches(LEFT_TOKEN,  s);
        final int rights = countMatches(RIGHT_TOKEN, s);
        return lefts != rights;
    }

    private static int countMatches(final Pattern p, final String s) {
        int n = 0;
        final Matcher m = p.matcher(s);
        while (m.find()) { n++; }
        return n;
    }

    /** begin/end mismatch using an environment-name stack. */
    static boolean beginEndMismatch(final String s) {
        final Pattern env = Pattern.compile("\\\\(begin|end)\\{([^}]*)\\}");
        final Matcher m = env.matcher(s);
        final Deque<String> stack = new ArrayDeque<>();
        while (m.find()) {
            if ("begin".equals(m.group(1))) {
                stack.push(m.group(2));
            } else {
                if (stack.isEmpty() || !stack.pop().equals(m.group(2))) { return true; }
            }
        }
        return !stack.isEmpty();
    }

    /**
     * fracArity: a {@code \frac} not immediately followed by two {@code {…}} groups
     * (allowing arbitrary whitespace between them).
     */
    private static final Pattern FRAC_FULL = Pattern.compile(
            "\\\\frac\\s*\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}\\s*\\{[^{}]*(?:\\{[^{}]*\\}[^{}]*)*\\}");
    private static final Pattern FRAC_ANY  = Pattern.compile("\\\\frac");

    static boolean fracArity(final String s) {
        // Count total \frac occurrences vs those followed by two balanced {…}
        int total   = countMatches(FRAC_ANY,  s);
        int matched = countMatches(FRAC_FULL, s);
        return matched < total;
    }

    /**
     * emptyScript: a {@code ^} or {@code _} not followed by a non-space argument
     * (end-of-string, another {@code ^}/{@code _}, a closing brace/paren, or
     * only whitespace follows).
     */
    static boolean emptyScript(final String s) {
        // Tokenise: look for ^ or _ not inside a \command name
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '\\') { i++; continue; }  // skip escaped char
            if (c == '^' || c == '_') {
                // skip whitespace
                int j = i + 1;
                while (j < s.length() && s.charAt(j) == ' ') { j++; }
                if (j >= s.length()) { return true; }                     // end of string
                final char next = s.charAt(j);
                if (next == '^' || next == '_' || next == '}' || next == ')' || next == ']') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * doubleScript: detects {@code x^a^b} or {@code x_a_b} — a base with two consecutive
     * same-kind scripts where the first script argument is a single token (not braced).
     * Pattern: {@code [a-zA-Z0-9]}^[a-zA-Z0-9]^  or  _x_ variant.
     */
    private static final Pattern DOUBLE_SUP = Pattern.compile("[^{]\\^[^{\\s]\\^");
    private static final Pattern DOUBLE_SUB = Pattern.compile("[^{]_[^{\\s]_");

    static boolean doubleScript(final String s) {
        return DOUBLE_SUP.matcher(s).find() || DOUBLE_SUB.matcher(s).find();
    }

    /**
     * sqrtBadOptional: a {@code \sqrt[} not followed by a {@code ]} before
     * the radicand group starts.
     */
    static boolean sqrtBadOptional(final String s) {
        int i = 0;
        while ((i = s.indexOf("\\sqrt[", i)) >= 0) {
            // Look for ] before the next { or end
            int j = i + 6;
            boolean foundClose = false;
            while (j < s.length() && s.charAt(j) != '{') {
                if (s.charAt(j) == ']') { foundClose = true; break; }
                j++;
            }
            if (!foundClose) { return true; }
            i += 6;
        }
        return false;
    }

    /**
     * ampOutsideEnv: a bare {@code &} when not inside any {@code \begin…\end} block.
     */
    static boolean ampOutsideEnv(final String s) {
        // Track environment depth
        final Pattern envPat = Pattern.compile("\\\\(begin|end)\\{[^}]*\\}|&");
        final Matcher m = envPat.matcher(s);
        int depth = 0;
        while (m.find()) {
            final String hit = m.group();
            if (hit.startsWith("\\begin")) { depth++; }
            else if (hit.startsWith("\\end")) { depth = Math.max(0, depth - 1); }
            else if ("&".equals(hit) && depth == 0) { return true; }
        }
        return false;
    }

    /**
     * unknownCommand: backslash-command not in the linter's allowlist.
     * Mirrors {@link LatexSyntaxLinter#KNOWN}.
     */
    private static final Set<String> KNOWN_COMMANDS = Set.of(
            "frac", "sqrt", "sum", "int", "prod", "lim", "infty", "partial", "nabla",
            "left", "right", "begin", "end", "text", "mathrm", "mathbf", "mathbb", "mathcal",
            "alpha", "beta", "gamma", "delta", "epsilon", "theta", "lambda", "mu", "nu", "pi",
            "rho", "sigma", "tau", "phi", "psi", "omega", "Delta", "Gamma", "Phi", "Omega",
            "cdot", "times", "div", "pm", "mp", "leq", "geq", "neq", "approx", "equiv",
            "rightarrow", "leftarrow", "Rightarrow", "cos", "sin", "tan", "log", "ln", "exp",
            "hat", "bar", "vec", "dot", "ddot", "overline", "underline", "binom", "cases", "matrix",
            "pmatrix", "bmatrix", "vmatrix", "quad", "qquad", "space", "displaystyle");

    private static final Pattern COMMAND_PATTERN = Pattern.compile("\\\\([a-zA-Z]+)");

    static boolean unknownCommand(final String s) {
        final Matcher m = COMMAND_PATTERN.matcher(s);
        while (m.find()) {
            if (!KNOWN_COMMANDS.contains(m.group(1))) { return true; }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Metrics
    // -----------------------------------------------------------------------

    record RuleResult(String name, int tp, int fp, int fn, int tn, List<String> fpExamples) {
        double precision() { return (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp); }
        double recall()    { return (tp + fn) == 0 ? 1.0 : (double) tp / (tp + fn); }
        int support()      { return tp + fp; }
    }

    private static RuleResult measure(final String name, final Predicate<String> rule,
                                      final List<Row> rows) {
        int tp = 0, fp = 0, fn = 0, tn = 0;
        final List<String> fpExamples = new ArrayList<>();

        for (final Row row : rows) {
            final boolean predicted = rule.test(row.latex());
            final boolean actual    = !row.katexOk();   // true = KaTeX failed = INVALID

            if (predicted && actual)  { tp++; }
            else if (predicted)       {
                fp++;
                if (fpExamples.size() < 3) {
                    fpExamples.add(row.latex().replace("\n", "↵").substring(0, Math.min(60, row.latex().length())));
                }
            }
            else if (actual)          { fn++; }
            else                      { tn++; }
        }
        return new RuleResult(name, tp, fp, fn, tn, fpExamples);
    }

    // -----------------------------------------------------------------------
    // Test
    // -----------------------------------------------------------------------

    @Test
    void probeRules() throws Exception {
        final List<Row> rows = loadDataset();

        // Guard: dataset must be meaningful
        assertTrue(rows.size() >= 400,
                "probe-dataset.json must have ≥400 rows to give reliable metrics; found " + rows.size()
                        + " — run: node scripts/math-probe/probe.mjs");

        final List<RuleResult> results = new ArrayList<>();
        results.add(measure("unbalancedBraces",        MathRuleProbeTest::unbalancedBraces,         rows));
        results.add(measure("leftRightMismatch_naive",  MathRuleProbeTest::leftRightMismatch_naive,  rows));
        results.add(measure("leftRightMismatch_token",  MathRuleProbeTest::leftRightMismatch_token,  rows));
        results.add(measure("beginEndMismatch",         MathRuleProbeTest::beginEndMismatch,         rows));
        results.add(measure("fracArity",                MathRuleProbeTest::fracArity,                rows));
        results.add(measure("emptyScript",              MathRuleProbeTest::emptyScript,              rows));
        results.add(measure("doubleScript",             MathRuleProbeTest::doubleScript,             rows));
        results.add(measure("sqrtBadOptional",          MathRuleProbeTest::sqrtBadOptional,          rows));
        results.add(measure("ampOutsideEnv",            MathRuleProbeTest::ampOutsideEnv,            rows));
        results.add(measure("unknownCommand",           MathRuleProbeTest::unknownCommand,           rows));

        // Sort: precision desc, then support desc
        results.sort(Comparator
                .comparingDouble(RuleResult::precision).reversed()
                .thenComparingInt(RuleResult::support).reversed());

        // Summary line
        final long katexOkCount  = rows.stream().filter(Row::katexOk).count();
        final long katexErrCount = rows.stream().filter(r -> !r.katexOk()).count();

        System.out.println();
        System.out.printf("=== Math Rule Probe — dataset: %d total, %d katexOk, %d katexError ===%n",
                rows.size(), katexOkCount, katexErrCount);
        System.out.println();
        System.out.printf("%-28s  %9s  %7s  %7s  %4s  %s%n",
                "rule", "precision", "recall", "support", "FP", "example_FPs");
        System.out.println("-".repeat(110));

        for (final RuleResult r : results) {
            System.out.printf("%-28s  %9.3f  %7.3f  %7d  %4d  %s%n",
                    r.name(),
                    r.precision(),
                    r.recall(),
                    r.support(),
                    r.fp(),
                    r.fpExamples().isEmpty() ? "(none)" : String.join(" | ", r.fpExamples()));
        }

        System.out.println();
        System.out.println("--- naïve vs token leftRight FP contrast ---");
        final RuleResult naive = results.stream().filter(r -> r.name().endsWith("_naive")).findFirst().orElseThrow();
        final RuleResult token = results.stream().filter(r -> r.name().endsWith("_token")).findFirst().orElseThrow();
        System.out.printf("  leftRightMismatch_naive: FP=%d  precision=%.3f  examples: %s%n",
                naive.fp(), naive.precision(), naive.fpExamples());
        System.out.printf("  leftRightMismatch_token: FP=%d  precision=%.3f  examples: %s%n",
                token.fp(), token.precision(), token.fpExamples());
        System.out.println();
    }
}

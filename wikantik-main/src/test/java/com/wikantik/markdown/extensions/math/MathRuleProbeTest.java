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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-discovery probe: measures precision, recall, support, and false-positive count for candidate
 * LaTeX-error detector predicates against the KaTeX ground truth in probe-dataset.json.
 *
 * <p>The high-confidence rules delegate directly to {@link LatexRules} — this is the regression
 * guard ensuring the shipped linter == the measured rules. The {@code leftRightMismatch_naive}
 * variant is kept locally to document the known false-positive contrast.
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
    // Candidate rules — all high-confidence rules now delegate to LatexRules
    // -----------------------------------------------------------------------

    /**
     * Naive leftRight: counts plain substrings {@code \left} and {@code \right}.
     * Known false-positive risk: {@code \rightarrow} contains {@code \right}.
     * Kept here for documented contrast against the token form in {@link LatexRules#leftRightMismatch}.
     */
    static boolean leftRightMismatch_naive(final String s) {
        int lefts  = 0, rights = 0, i = 0;
        while ((i = s.indexOf("\\left", i)) >= 0) { lefts++;  i += 5; }
        i = 0;
        while ((i = s.indexOf("\\right", i)) >= 0) { rights++; i += 6; }
        return lefts != rights;
    }

    /** Mirror of {@link LatexRules#unknownCommand} using the same allowlist. */
    static boolean unknownCommand(final String s) {
        final Pattern cmdPattern = Pattern.compile("\\\\([a-zA-Z]+)");
        final Matcher m = cmdPattern.matcher(s);
        while (m.find()) {
            if (!LatexRules.KNOWN_COMMANDS.contains(m.group(1))) { return true; }
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
        results.add(measure("unbalancedBraces",        LatexRules::unbalancedBraces,        rows));
        results.add(measure("leftRightMismatch_naive",  MathRuleProbeTest::leftRightMismatch_naive, rows));
        results.add(measure("leftRightMismatch_token",  LatexRules::leftRightMismatch,       rows));
        results.add(measure("beginEndMismatch",         LatexRules::beginEndMismatch,        rows));
        results.add(measure("fracArity",                LatexRules::fracArity,               rows));
        results.add(measure("emptyScript",              LatexRules::emptyScript,             rows));
        results.add(measure("doubleScript",             LatexRules::doubleScript,            rows));
        results.add(measure("sqrtBadOptional",          LatexRules::sqrtBadOptional,         rows));
        results.add(measure("ampOutsideEnv",            LatexRules::ampOutsideEnv,           rows));
        results.add(measure("unknownCommand",           MathRuleProbeTest::unknownCommand,   rows));

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

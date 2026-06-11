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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MathValidationCorpusTest {

    private static final MathSpanExtractor EXTRACTOR = new MathSpanExtractor();
    private static final MathStructureValidator STRUCTURE = new MathStructureValidator();
    private static final LatexSyntaxLinter LINTER = new LatexSyntaxLinter();

    record Row(String id, String source, String category,
               String structureExpect, String katexExpect, String linterExpect, String note) {}

    static List<Row> corpus() throws Exception {
        final ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = MathValidationCorpusTest.class.getClassLoader()
                .getResourceAsStream("math/math-validation-corpus.json")) {
            assertNotNull(in, "corpus resource missing");
            final JsonNode arr = mapper.readTree(in);
            final List<Row> rows = new ArrayList<>();
            for (final JsonNode n : arr) {
                rows.add(new Row(n.get("id").asText(), n.get("source").asText(),
                        n.get("category").asText(), n.get("structureExpect").asText(),
                        n.get("katexExpect").asText(), n.get("linterExpect").asText(),
                        n.path("note").asText("")));
            }
            return rows;
        }
    }

    static Stream<Arguments> rows() throws Exception {
        return corpus().stream().map(Arguments::of);
    }

    private static boolean structureErrors(final String body) {
        return STRUCTURE.validate(body).stream().anyMatch(v -> v.severity() == Severity.ERROR);
    }

    private static boolean linterWarns(final String body) {
        return EXTRACTOR.extract(body).stream()
                .flatMap(s -> LINTER.lint(s.content()).stream())
                .anyMatch(v -> v.severity() == Severity.WARNING);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void structureMatchesCorpus(final Row row) {
        final boolean expectError = "error".equals(row.structureExpect());
        assertEquals(expectError, structureErrors(row.source()),
                "structureExpect mismatch for '" + row.id() + "' (" + row.note() + ")");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void linterMatchesCorpus(final Row row) {
        final boolean expectWarn = "warn".equals(row.linterExpect());
        assertEquals(expectWarn, linterWarns(row.source()),
                "linterExpect mismatch for '" + row.id() + "' (" + row.note() + ")");
    }

    @Test
    void corpusHasFiftyOfEach() throws Exception {
        final List<Row> rows = corpus();
        final long valid = rows.stream().filter(r ->
                "ok".equals(r.structureExpect()) && !"warn".equals(r.linterExpect())
                && !"error".equals(r.katexExpect())).count();
        final long invalid = rows.size() - valid;
        assertTrue(valid >= 45, "expected ~50 fully-valid rows, got " + valid);
        assertTrue(invalid >= 45, "expected ~50 invalid rows, got " + invalid);
    }

    /** Inventory (does not fail): rows real-KaTeX rejects but the pragmatic linter misses — the TODO list. */
    @Test
    void reportsLinterBlindSpots() throws Exception {
        final List<String> blind = corpus().stream()
                .filter(r -> "error".equals(r.katexExpect()) && !"warn".equals(r.linterExpect()))
                .map(r -> r.id() + " — " + r.note())
                .toList();
        System.out.println("[math-linter blind spots / TODO] " + blind.size() + " rows:");
        blind.forEach(b -> System.out.println("  - " + b));
        // Intentionally no assertion: this is a tracked-TODO inventory, not a gate.
    }

    @Test
    void fastenerEngineeringPageIsClean() throws Exception {
        // user.dir is the module dir (wikantik-main) during surefire; walk up to repo root.
        final java.nio.file.Path repoRoot = java.nio.file.Path.of(System.getProperty("user.dir")).getParent();
        final java.nio.file.Path md = repoRoot.resolve("docs/wikantik-pages/FastenerEngineering.md");
        final String body = java.nio.file.Files.readString(md);
        final List<MathViolation> v = STRUCTURE.validate(body);
        assertTrue(v.stream().noneMatch(x -> x.severity() == Severity.ERROR),
                "FastenerEngineering must have zero structure ERRORs after the fix; found: "
                        + v.stream().filter(x -> x.severity() == Severity.ERROR).toList());
    }
}

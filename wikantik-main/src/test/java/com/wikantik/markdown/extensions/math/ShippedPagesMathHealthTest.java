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

import com.wikantik.api.frontmatter.schema.Severity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scans every shipped wiki page under {@code docs/wikantik-pages} for math ERROR-level defects —
 * the same defects {@code MathValidationPageFilter} now blocks on save. Guards the corpus against
 * the FastenerEngineering-class rendering bug regressing back in.
 */
class ShippedPagesMathHealthTest {

    private final MathStructureValidator structure = new MathStructureValidator();
    private final MathSpanExtractor extractor = new MathSpanExtractor();
    private final LatexSyntaxLinter linter = new LatexSyntaxLinter();

    private static Path pagesDir() {
        // surefire user.dir is the module dir (wikantik-main); pages live at repoRoot/docs/wikantik-pages.
        return Path.of(System.getProperty("user.dir")).getParent().resolve("docs/wikantik-pages");
    }

    private List<String> errorCodes(final String content) {
        final String body = stripFrontmatter(content).replace("\r\n", "\n").replace('\r', '\n');
        final List<String> codes = new ArrayList<>();
        for (final MathViolation v : structure.validate(body)) {
            if (v.severity() == Severity.ERROR) { codes.add(v.code()); }
        }
        for (final MathSpan s : extractor.extract(body)) {
            for (final MathViolation v : linter.lint(s.content())) {
                if (v.severity() == Severity.ERROR) { codes.add(v.code()); }
            }
        }
        return codes;
    }

    private static String stripFrontmatter(final String content) {
        if (!content.startsWith("---")) { return content; }
        final int nl = content.indexOf('\n');
        if (nl < 0) { return content; }
        final int close = content.indexOf("\n---", nl);
        if (close < 0) { return content; }
        final int after = content.indexOf('\n', close + 1);
        return after < 0 ? "" : content.substring(after + 1);
    }

    @Test
    void shippedPagesHaveNoMathErrors() throws IOException {
        final Path dir = pagesDir();
        if (!Files.isDirectory(dir)) { return; }
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(dir)) {
            for (final Path p : (Iterable<Path>) paths.filter(x -> x.toString().endsWith(".md"))::iterator) {
                final List<String> codes = errorCodes(Files.readString(p));
                if (!codes.isEmpty()) {
                    offenders.add(dir.relativize(p) + " -> " + codes);
                }
            }
        }
        offenders.forEach(o -> System.out.println("[math defect] " + o));
        assertTrue(offenders.isEmpty(),
                "Shipped pages with math ERROR defects:\n  " + String.join("\n  ", offenders));
    }
}

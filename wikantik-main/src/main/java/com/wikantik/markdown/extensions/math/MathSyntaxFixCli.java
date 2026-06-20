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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * One-off batch CLI for the corpus math-syntax migration. Reads every {@code *.md} file in
 * {@code inDir}, applies {@link MathSyntaxFixer#escapeCurrency} (the unit-tested transform), and writes
 * ONLY the files it changed to {@code outDir} (same filename). A driver fetches each page's markdown
 * into {@code inDir}, runs this once, and re-saves the pages that appear in {@code outDir}.
 *
 * <p>Usage: {@code java -cp wikantik-main/target/classes \
 *   com.wikantik.markdown.extensions.math.MathSyntaxFixCli IN_DIR OUT_DIR}</p>
 */
public final class MathSyntaxFixCli {

    private MathSyntaxFixCli() {}

    public static void main(final String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: MathSyntaxFixCli IN_DIR OUT_DIR");
            System.exit(2);
            return;
        }
        final Path inDir = Path.of(args[0]);
        final Path outDir = Path.of(args[1]);
        Files.createDirectories(outDir);

        int scanned = 0;
        int changed = 0;
        try (Stream<Path> files = Files.list(inDir)) {
            for (final Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".md"))::iterator) {
                scanned++;
                final String body = Files.readString(f, StandardCharsets.UTF_8);
                final String fixed = MathSyntaxFixer.isolateDisplayMath(
                        MathSyntaxFixer.escapeCurrency(body));
                if (!fixed.equals(body)) {                             // changed
                    Files.writeString(outDir.resolve(f.getFileName()), fixed, StandardCharsets.UTF_8);
                    changed++;
                }
            }
        }
        System.out.println("scanned=" + scanned + " changed=" + changed);
    }
}

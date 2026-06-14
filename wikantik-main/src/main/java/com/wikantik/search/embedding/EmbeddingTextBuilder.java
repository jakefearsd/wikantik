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
package com.wikantik.search.embedding;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the text that gets handed to a document-embedding model. The chunk's
 * body is prepended with its {@code heading_path} so the embedder sees the
 * section context ("PostgreSQLLocalDeployment > Applying database migrations
 * manually") rather than a bare command line.
 *
 * <p>Keep call sites consistent — the stored chunk text is body-only, and the
 * hash over {@code heading_path + text} covers identity. This helper is the
 * single rendering point so experiment and production embed the same strings.
 */
public final class EmbeddingTextBuilder {

    private static final String SEP = " > ";

    private EmbeddingTextBuilder() {}

    /**
     * Page-level context for a chunk, sourced from frontmatter. Prepended to the
     * document embedding text so the embedder sees the page's topic and domain,
     * not just the bare section body. The 2026-06-14 contextual-embedding spike
     * showed this structured context lifts section recall@12 from ~0.60 to ~0.74
     * — our frontmatter is exactly the disambiguation missing from raw chunks.
     * Any field may be null/blank and is then omitted.
     */
    public record PageContext(String title, String cluster, String summary) {
        public static final PageContext EMPTY = new PageContext(null, null, null);
    }

    /**
     * Contextual document-side format used by the production embedding indexer:
     * {@code "Page: <title> | Cluster: <cluster> | Section: <a > b>\nSummary: <summary>\n\n<body>"}.
     * Each piece is omitted when its source field is null/blank, and an all-empty
     * context falls back to {@code body} unchanged. The query side is NOT contextual
     * (queries keep their instruction prefix) — context enriches the document only.
     */
    public static String forDocument(final PageContext ctx, final List<String> headingPath,
                                     final String body) {
        final List<String> inline = new ArrayList<>();
        if (ctx != null && notBlank(ctx.title()))   inline.add("Page: " + ctx.title().strip());
        if (ctx != null && notBlank(ctx.cluster())) inline.add("Cluster: " + ctx.cluster().strip());
        final String section = joinHeadings(headingPath);
        if (!section.isEmpty())                      inline.add("Section: " + section);

        final StringBuilder header = new StringBuilder(String.join(" | ", inline));
        if (ctx != null && notBlank(ctx.summary())) {
            if (header.length() > 0) header.append('\n');
            header.append("Summary: ").append(ctx.summary().strip());
        }
        if (header.length() == 0) {
            return body == null ? "" : body;
        }
        header.append("\n\n");
        if (body != null) header.append(body);
        return header.toString();
    }

    private static boolean notBlank(final String s) { return s != null && !s.isBlank(); }

    /** Joins a heading path with {@code " > "}, skipping null/blank segments. */
    private static String joinHeadings(final List<String> headingPath) {
        if (headingPath == null || headingPath.isEmpty()) return "";
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (final String h : headingPath) {
            if (h == null || h.isBlank()) continue;
            if (!first) sb.append(SEP);
            sb.append(h.strip());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Document-side format: {@code "<path> > <to> > <section>\n\n<body>"}. When
     * {@code headingPath} is null or empty, returns {@code body} unchanged so
     * root-level chunks aren't padded with a stray delimiter.
     */
    public static String forDocument(final List<String> headingPath, final String body) {
        if (headingPath == null || headingPath.isEmpty()) {
            return body == null ? "" : body;
        }
        final StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (final String h : headingPath) {
            if (h == null || h.isBlank()) continue;
            if (!first) sb.append(SEP);
            sb.append(h.strip());
            first = false;
        }
        if (sb.length() == 0) {
            return body == null ? "" : body;
        }
        sb.append("\n\n");
        if (body != null) sb.append(body);
        return sb.toString();
    }
}

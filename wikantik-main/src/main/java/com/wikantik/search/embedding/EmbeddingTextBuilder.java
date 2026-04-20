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

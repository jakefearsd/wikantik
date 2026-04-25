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
package com.wikantik.extractcli.mainpage;

import java.util.List;

/**
 * Editorial layout for the generated {@code Main.md}, parsed from
 * {@code docs/wikantik-pages/Main.pins.yaml}. Sections are rendered in the
 * order they appear here; pages within each section are rendered in their
 * listed order. {@link #intro} and {@link #footer} are inlined verbatim.
 */
public record PinsConfig(
        String intro,
        String footer,
        List< PinsSection > sections
) {
    public PinsConfig {
        intro    = intro    == null ? "" : intro;
        footer   = footer   == null ? "" : footer;
        sections = sections == null ? List.of() : List.copyOf( sections );
    }

    /** A single H3 section in the rendered Main.md. */
    public record PinsSection(
            String label,
            String cluster,
            List< PinsPage > pages
    ) {
        public PinsSection {
            if ( label == null || label.isBlank() ) {
                throw new IllegalArgumentException( "section label required" );
            }
            pages = pages == null ? List.of() : List.copyOf( pages );
        }
    }

    /**
     * A single bullet-list entry in a section. Both {@code titleOverride} and
     * {@code summaryOverride} default to {@code null}; when null, the renderer
     * falls back to the page's frontmatter {@code title} / {@code summary}.
     * Title override is the practical default for the bootstrap migration —
     * many pages carry no frontmatter title and would otherwise render their
     * raw slug.
     */
    public record PinsPage(
            String canonicalId,
            String titleOverride,
            String summaryOverride
    ) {
        public PinsPage {
            if ( canonicalId == null || canonicalId.isBlank() ) {
                throw new IllegalArgumentException( "canonical_id required" );
            }
            titleOverride   = titleOverride   == null || titleOverride.isBlank()   ? null : titleOverride;
            summaryOverride = summaryOverride == null || summaryOverride.isBlank() ? null : summaryOverride;
        }
    }
}

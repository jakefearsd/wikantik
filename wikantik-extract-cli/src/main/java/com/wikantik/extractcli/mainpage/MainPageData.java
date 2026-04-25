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
 * Resolved view of {@link PinsConfig} ready for the Mustache template:
 * canonical_ids have been looked up to slugs/titles/summaries, and any
 * summary overrides from the pins file have already been applied.
 *
 * <p>Field names are kebab-free for Mustache compatibility.</p>
 */
public record MainPageData(
        String intro,
        String footer,
        List< Section > sections,
        List< String > warnings
) {
    public MainPageData {
        intro    = intro    == null ? "" : intro;
        footer   = footer   == null ? "" : footer;
        sections = sections == null ? List.of() : List.copyOf( sections );
        warnings = warnings == null ? List.of() : List.copyOf( warnings );
    }

    public record Section(
            String label,
            String cluster,
            List< Page > pages
    ) {
        public Section {
            pages = pages == null ? List.of() : List.copyOf( pages );
        }
    }

    public record Page(
            String canonicalId,
            String slug,
            String title,
            String summary
    ) {
        public Page {
            if ( slug == null || slug.isBlank() ) {
                throw new IllegalArgumentException( "slug required" );
            }
            if ( title == null || title.isBlank() ) {
                title = slug;
            }
            summary = summary == null ? "" : summary;
        }
    }
}

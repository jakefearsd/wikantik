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
package com.wikantik.export;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HeadingSlugsTest {
    @Test void githubStyleSlug() {
        assertEquals( "2-advanced-portfolio-optimization-hrp-2025",
                HeadingSlugs.slug( "2. Advanced Portfolio Optimization (HRP, 2025)" ) );
        assertEquals( "case-study-the-2026-iran-war-shock", HeadingSlugs.slug( "Case Study: The 2026 Iran War Shock" ) );
        assertEquals( "a-b", HeadingSlugs.slug( "A & B" ) );
    }

    @Test void headingsBySlugIgnoresCodeBlocks() {
        final String body = "# Top\n\n```\n# not a heading\n```\n\n## Second Part\n";
        final Map< String, String > m = HeadingSlugs.headingsBySlug( body );
        assertEquals( List.of( "top", "second-part" ), List.copyOf( m.keySet() ) );
        assertEquals( "Second Part", m.get( "second-part" ) );
    }

    @Test void inlineMarkupStrippedFromHeadingText() {
        assertEquals( "Using Foo", HeadingSlugs.headingsBySlug( "## Using `Foo`\n" ).get( "using-foo" ) );
    }

    @Test
    void slugMatchesSharedFrontendCaseTable() throws Exception {
        // Shared with wikantik-frontend/src/utils/headings.test.jsx — the page view's slugify is canonical.
        final java.nio.file.Path fixture = java.nio.file.Path.of(
                "..", "wikantik-frontend", "src", "utils", "__fixtures__", "heading-slugs.json" );
        final com.google.gson.JsonArray cases = com.google.gson.JsonParser
                .parseString( java.nio.file.Files.readString( fixture ) ).getAsJsonArray();
        assertFalse( cases.isEmpty() );
        for ( final com.google.gson.JsonElement e : cases ) {
            final com.google.gson.JsonObject c = e.getAsJsonObject();
            final String heading = c.get( "heading" ).getAsString();
            assertEquals( c.get( "slug" ).getAsString(), HeadingSlugs.slug( heading ), heading );
        }
    }

    @Test
    void duplicateH2H3HeadingsAreNumberedLikeThePageView() {
        final Map< String, String > m = HeadingSlugs.headingsBySlug( "## Setup\n\n### Setup\n\n## Setup\n" );
        assertEquals( java.util.List.of( "setup", "setup-2", "setup-3" ), java.util.List.copyOf( m.keySet() ) );
    }
}

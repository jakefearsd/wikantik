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
package com.wikantik.extractcli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusDiffTest {

    private static PageFacts facts( final String slug, final String cluster, final String type ) {
        return new PageFacts( slug, "01H8G3Z1K6Q5W7P9X2V4R0T8M" + slug.length(), cluster, type );
    }

    private static CorpusSnapshot complete( final String name, final PageFacts... pages ) {
        final Map< String, PageFacts > m = new java.util.LinkedHashMap<>();
        for ( final PageFacts p : pages ) {
            m.put( p.slug(), p );
        }
        return new CorpusSnapshot( name, m, List.of() );
    }

    @Test
    void identical_corpora_do_not_diverge() {
        final var local = complete( "repo", facts( "MLHub", "machine-learning", "hub" ) );
        final var remote = complete( "prod", facts( "MLHub", "machine-learning", "hub" ) );
        assertTrue( CorpusDiff.compare( local, remote ).isEmpty() );
    }

    @Test
    void reports_a_page_present_only_in_the_repository() {
        final var local = complete( "repo", facts( "OnlyHere", "c1", "article" ) );
        final var remote = complete( "prod" );
        assertEquals( List.of( "OnlyHere" ), CorpusDiff.compare( local, remote ).onlyLocal() );
    }

    @Test
    void reports_a_page_present_only_in_production() {
        final var local = complete( "repo" );
        final var remote = complete( "prod", facts( "ProgrammingLanguagesHub", "computer-science", "hub" ) );
        assertEquals( List.of( "ProgrammingLanguagesHub" ),
                      CorpusDiff.compare( local, remote ).onlyRemote() );
    }

    @Test
    void reports_a_cluster_that_differs_between_the_two() {
        final var local = complete( "repo", facts( "Berlin", "berlin-history", "hub" ) );
        final var remote = complete( "prod", facts( "Berlin", "berlin-history", "article" ) );
        final var deltas = CorpusDiff.compare( local, remote ).deltas();
        assertEquals( 1, deltas.size() );
        assertEquals( "type", deltas.get( 0 ).field() );
        assertEquals( "hub", deltas.get( 0 ).local() );
        assertEquals( "article", deltas.get( 0 ).remote() );
    }

    /**
     * The lesson `pages-pull` taught: a transport that fails on some pages and returns the
     * rest looks authoritative, and every unread page then reads as "missing from production".
     * Comparing against an incomplete snapshot must be impossible, not merely discouraged.
     */
    @Test
    void refuses_to_compare_against_an_incomplete_snapshot() {
        final var local = complete( "repo", facts( "A", "c1", "article" ) );
        final var partial = new CorpusSnapshot( "prod", Map.of(), List.of( "Permission denied: B.md" ) );

        final IllegalStateException boom =
                assertThrows( IllegalStateException.class, () -> CorpusDiff.compare( local, partial ) );
        assertTrue( boom.getMessage().contains( "incomplete" ),
                    "the refusal must name the cause, got: " + boom.getMessage() );
        assertTrue( boom.getMessage().contains( "Permission denied" ),
                    "the refusal must surface the underlying error, got: " + boom.getMessage() );
    }

    @Test
    void a_divergence_with_any_finding_is_not_empty() {
        final var local = complete( "repo", facts( "A", "c1", "article" ) );
        final var remote = complete( "prod", facts( "A", "c2", "article" ) );
        assertFalse( CorpusDiff.compare( local, remote ).isEmpty() );
    }
}

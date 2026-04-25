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
package com.wikantik.knowledge.agent;

import com.wikantik.api.agent.KeyFact;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeyFactsExtractorTest {

    private final KeyFactsExtractor extractor = new KeyFactsExtractor();

    @Test
    void frontmatter_key_facts_are_passed_through_verbatim() {
        final Map< String, Object > fm = Map.of(
                "key_facts", List.of(
                        "Retrieval fuses BM25 and dense embeddings via RRF (k=60).",
                        "Falls back to BM25 when embedding service is unavailable." )
        );
        final List< KeyFact > out = extractor.extract( fm, "irrelevant body" );
        assertEquals( 2, out.size() );
        assertEquals( "Retrieval fuses BM25 and dense embeddings via RRF (k=60).",
                out.get( 0 ).text() );
        assertEquals( "frontmatter", out.get( 0 ).sourceHint() );
    }

    @Test
    void empty_frontmatter_falls_back_to_body_heuristic() {
        final String body = "Hybrid retrieval combines BM25 with dense embeddings. " +
                "It runs in 60 milliseconds. " +
                "The fallback is BM25-only. " +
                "Lorem ipsum dolor.";
        final List< KeyFact > out = extractor.extract( Map.of(), body );
        assertFalse( out.isEmpty(), "heuristic must yield at least one fact" );
        assertEquals( "body", out.get( 0 ).sourceHint() );
        assertTrue( out.get( 0 ).text().contains( "BM25" )
                 || out.get( 0 ).text().contains( "60 milliseconds" ) );
    }

    @Test
    void caps_at_max_facts() {
        final StringBuilder b = new StringBuilder();
        for ( int i = 0; i < 50; i++ ) {
            b.append( "Wikantik renders 100 pages per second. " );
        }
        final List< KeyFact > out = extractor.extract( Map.of(), b.toString() );
        assertTrue( out.size() <= KeyFactsExtractor.MAX_FACTS,
                "must cap at " + KeyFactsExtractor.MAX_FACTS );
    }

    @Test
    void skips_sentences_without_verb_or_entity() {
        final String body = "The cat. The dog. The mat. " +
                "Wikantik runs 100 queries per second.";
        final List< KeyFact > out = extractor.extract( Map.of(), body );
        assertEquals( 1, out.size() );
        assertTrue( out.get( 0 ).text().contains( "100" ) );
    }

    @Test
    void null_or_empty_body_with_empty_fm_returns_empty() {
        assertTrue( extractor.extract( Map.of(), null ).isEmpty() );
        assertTrue( extractor.extract( Map.of(), "" ).isEmpty() );
    }

    @Test
    void frontmatter_non_string_entries_are_skipped_gracefully() {
        // Map.of disallows null values, so build a regular HashMap-backed list.
        final List< Object > entries = Arrays.asList( "Real fact.", null, 42, "Another fact." );
        final Map< String, Object > fm = new HashMap<>();
        fm.put( "key_facts", entries );
        final List< KeyFact > out = extractor.extract( fm, "irrelevant" );
        assertEquals( 3, out.size() );
        assertEquals( "Real fact.",    out.get( 0 ).text() );
        assertEquals( "42",            out.get( 1 ).text() );
        assertEquals( "Another fact.", out.get( 2 ).text() );
    }

    @Test
    void only_scans_first_three_paragraphs_for_speed() {
        final StringBuilder b = new StringBuilder();
        for ( int i = 0; i < 10; i++ ) {
            b.append( "Filler paragraph number " ).append( i ).append( ".\n\n" );
        }
        b.append( "Wikantik invented 99 things." );  // last paragraph — should not be reached
        final List< KeyFact > out = extractor.extract( Map.of(), b.toString() );
        for ( final KeyFact kf : out ) {
            assertFalse( kf.text().contains( "99" ),
                    "extractor must not scan past the first 3 paragraphs" );
        }
    }
}

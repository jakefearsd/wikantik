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
package com.wikantik.knowledge.extraction;

import com.wikantik.api.knowledge.ExtractionChunk;
import com.wikantik.api.knowledge.ExtractionContext;
import com.wikantik.api.knowledge.ExtractionResult;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared contract test for the JSON envelope both extractors emit. Covers
 * the invariants the plan pins down: mention coverage, proposal suppression
 * below the threshold, relation grounding, malformed input handling, fence
 * stripping.
 */
class ExtractionResponseParserTest {

    private static final String CODE = "test";
    private static final Duration L = Duration.ofMillis( 10 );
    private static final double THRESHOLD = 0.6;

    private static final ExtractionChunk CHUNK = new ExtractionChunk(
        UUID.randomUUID(), "TestPage", 0, List.of(), "Napoleon led the French army at Waterloo." );

    private static ExtractionContext contextWithKnownNodes( final String... knownNames ) {
        final List< KgNode > existing = java.util.Arrays.stream( knownNames )
            .map( n -> new KgNode( UUID.randomUUID(), n, "Person", null,
                                    Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now() ) )
            .toList();
        return new ExtractionContext( "TestPage", existing, Map.of() );
    }

    @Test
    void parsesWellFormedJsonIntoMentionsAndProposals() {
        final String json = "{ \"entities\": ["
          + " { \"name\": \"Napoleon\", \"type\": \"Person\", \"confidence\": 0.95, \"reasoning\": \"named\" },"
          + " { \"name\": \"Waterloo\", \"type\": \"Place\",  \"confidence\": 0.9,  \"reasoning\": \"named\" } ],"
          + "\"relations\": ["
          + " { \"source\": \"Napoleon\", \"target\": \"Waterloo\", \"type\": \"fought_at\", \"confidence\": 0.85, \"reasoning\": \"\" } ]"
          + " }";

        // Napoleon is known; Waterloo is new → one proposed-node, two mentions, one edge.
        final ExtractionResult r = ExtractionResponseParser.parse(
            json, CHUNK, contextWithKnownNodes( "Napoleon" ), CODE, L, THRESHOLD );

        assertEquals( 2, r.mentions().size(), "one mention per entity" );
        assertEquals( 1, r.nodes().size(), "only the unknown entity becomes a node proposal" );
        assertEquals( "Waterloo", r.nodes().get( 0 ).name() );
        assertEquals( 1, r.edges().size() );
        assertEquals( "fought_at", r.edges().get( 0 ).relationshipType() );
    }

    @Test
    void dropsProposalsBelowConfidenceThresholdButKeepsMentions() {
        final String json = "{ \"entities\": ["
          + " { \"name\": \"Foo\", \"type\": \"Thing\", \"confidence\": 0.30, \"reasoning\": \"weak\" } ],"
          + "\"relations\": [] }";

        final ExtractionResult r = ExtractionResponseParser.parse(
            json, CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD );

        assertEquals( 1, r.mentions().size(), "low-confidence entities still produce mentions" );
        assertTrue( r.nodes().isEmpty(), "but below-threshold entities are not proposed as new nodes" );
    }

    @Test
    void dropsRelationsWhoseEndsAreNotEntities() {
        final String json = "{ \"entities\": ["
          + " { \"name\": \"Napoleon\", \"type\": \"Person\", \"confidence\": 0.9, \"reasoning\": \"\" } ],"
          + "\"relations\": ["
          + " { \"source\": \"Napoleon\", \"target\": \"MysteryCity\", \"type\": \"visited\", \"confidence\": 0.9 } ]"
          + " }";

        final ExtractionResult r = ExtractionResponseParser.parse(
            json, CHUNK, contextWithKnownNodes( "Napoleon" ), CODE, L, THRESHOLD );

        assertTrue( r.edges().isEmpty(), "ungrounded relations are dropped so we never propose hallucinated nodes" );
    }

    @Test
    void stripsCodeFenceWrappersBeforeParsing() {
        final String wrapped = "```json\n"
          + "{ \"entities\": [ { \"name\": \"A\", \"type\": \"X\", \"confidence\": 0.9 } ], \"relations\": [] }\n"
          + "```";
        final ExtractionResult r = ExtractionResponseParser.parse(
            wrapped, CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD );
        assertEquals( 1, r.mentions().size() );
        assertEquals( 1, r.nodes().size() );
    }

    @Test
    void returnsEmptyResultOnMalformedJson() {
        final ExtractionResult r = ExtractionResponseParser.parse(
            "this is not json", CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD );
        assertTrue( r.mentions().isEmpty() );
        assertTrue( r.nodes().isEmpty() );
        assertTrue( r.edges().isEmpty() );
        assertEquals( CODE, r.extractorCode(), "code is preserved even on empty results" );
    }

    @Test
    void returnsEmptyResultOnNullAndBlankInput() {
        assertTrue( ExtractionResponseParser.parse(
            null, CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD ).mentions().isEmpty() );
        assertTrue( ExtractionResponseParser.parse(
            "   ", CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD ).mentions().isEmpty() );
    }

    @Test
    void deduplicatesMentionsByCaseInsensitiveName() {
        final String json = "{ \"entities\": ["
          + " { \"name\": \"Paris\", \"type\": \"Place\", \"confidence\": 0.9 },"
          + " { \"name\": \"paris\", \"type\": \"Place\", \"confidence\": 0.8 },"
          + " { \"name\": \"PARIS\", \"type\": \"Place\", \"confidence\": 0.7 } ],"
          + "\"relations\": [] }";

        final ExtractionResult r = ExtractionResponseParser.parse(
            json, CHUNK, contextWithKnownNodes( "Paris" ), CODE, L, THRESHOLD );
        assertEquals( 1, r.mentions().size(), "a chunk-level mention is one per entity, case-insensitive" );
    }

    @Test
    void skipsMalformedEntityObjects() {
        final String json = "{ \"entities\": ["
          + " { \"name\": \"Good\", \"type\": \"X\", \"confidence\": 0.9 },"
          + " \"not-an-object\","
          + " { \"type\": \"Y\", \"confidence\": 0.9 } ],"
          + "\"relations\": [] }";
        final ExtractionResult r = ExtractionResponseParser.parse(
            json, CHUNK, contextWithKnownNodes(), CODE, L, THRESHOLD );
        assertEquals( 1, r.mentions().size(), "only the well-formed entity is kept" );
        assertFalse( r.mentions().isEmpty() );
    }
}

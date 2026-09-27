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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity tests for {@link ExtractionPromptBuilder}. These guard the invariants
 * that allowed V030 to extend the closed vocabulary with {@code generalizes}
 * without silent drift between extractor prompts, the DB CHECK constraint, and
 * the schema-discovery dropdown.
 */
class ExtractionPromptBuilderTest {

    // Covers ExtractionPromptBuilder.java:49-50 — RELATION_TYPES must mirror
    // RelationshipTypeVocabulary.CLOSED_VOCAB byte-for-byte (order included),
    // since the same array drives both prompt text and the test contract.
    @Test
    void relationTypesMatchClosedVocab() {
        assertArrayEquals(
                RelationshipTypeVocabulary.CLOSED_VOCAB.toArray( new String[ 0 ] ),
                ExtractionPromptBuilder.RELATION_TYPES,
                "RELATION_TYPES must equal RelationshipTypeVocabulary.CLOSED_VOCAB.toArray()" );
    }

    // Covers ExtractionPromptBuilder.java:67 — SYSTEM_PROMPT bakes in
    // RelationshipTypeVocabulary.promptDescription(), so every closed-vocab
    // entry MUST appear verbatim in the rendered prompt.
    @Test
    void systemPromptContainsEveryClosedVocabEntry() {
        for ( final String rel : RelationshipTypeVocabulary.CLOSED_VOCAB ) {
            assertTrue( ExtractionPromptBuilder.SYSTEM_PROMPT.contains( rel ),
                    "SYSTEM_PROMPT missing closed-vocab predicate: " + rel );
        }
    }

    // Covers V030 vocabulary parity: both chunk-extractor and page-extractor
    // prompts must mention `generalizes` so the LLM never invents that
    // relationship outside the closed vocabulary.
    @Test
    void bothSystemPromptsMentionGeneralizes() {
        assertTrue( ExtractionPromptBuilder.SYSTEM_PROMPT.contains( "generalizes" ),
                "ExtractionPromptBuilder.SYSTEM_PROMPT must mention `generalizes` (V030 vocab parity)" );
        assertTrue( PageExtractionPromptBuilder.SYSTEM_PROMPT.contains( "generalizes" ),
                "PageExtractionPromptBuilder.SYSTEM_PROMPT must mention `generalizes` (V030 vocab parity)" );
    }

    private static KgNode node( final String name, final String type ) {
        return new KgNode( UUID.randomUUID(), name, type, null,
                Provenance.HUMAN_AUTHORED, Map.of(), Instant.now(), Instant.now(), "human", null );
    }

    // ---- buildUserPrompt: heading path rendering ----

    @Test
    void buildUserPromptIncludesHeadingPathWhenPresent() {
        final ExtractionChunk chunk = new ExtractionChunk(
                UUID.randomUUID(), "TestPage", 0, List.of( "Section A", "Subsection B" ), "chunk text" );
        final ExtractionContext ctx = new ExtractionContext( "TestPage", List.of(), Map.of() );

        final String prompt = ExtractionPromptBuilder.buildUserPrompt( chunk, ctx, 10 );

        assertTrue( prompt.contains( "Section: Section A › Subsection B" ),
                "expected the heading path joined with the › separator; got: " + prompt );
    }

    @Test
    void buildUserPromptOmitsSectionLineWhenHeadingPathIsEmpty() {
        final ExtractionChunk chunk = new ExtractionChunk(
                UUID.randomUUID(), "TestPage", 0, List.of(), "chunk text" );
        final ExtractionContext ctx = new ExtractionContext( "TestPage", List.of(), Map.of() );

        final String prompt = ExtractionPromptBuilder.buildUserPrompt( chunk, ctx, 10 );

        assertFalse( prompt.contains( "Section:" ) );
    }

    // ---- buildUserPrompt: known-entities dictionary ----

    @Test
    void buildUserPromptIncludesKnownEntitiesDictionaryWhenNonEmpty() {
        final ExtractionChunk chunk = new ExtractionChunk(
                UUID.randomUUID(), "TestPage", 0, List.of(), "chunk text" );
        final ExtractionContext ctx = new ExtractionContext( "TestPage", List.of( node( "Napoleon", "Person" ) ), Map.of() );

        final String prompt = ExtractionPromptBuilder.buildUserPrompt( chunk, ctx, 10 );

        assertTrue( prompt.contains( "Known entities (name :: type)" ) );
        assertTrue( prompt.contains( "- Napoleon :: person" ) );
    }

    // ---- existingNodesDictionary: formatting, null type, cap, sort order ----

    @Test
    void existingNodesDictionaryReturnsEmptyForNullOrEmptyContextOrZeroCap() {
        assertEquals( "", ExtractionPromptBuilder.existingNodesDictionary( null, 10 ) );
        assertEquals( "", ExtractionPromptBuilder.existingNodesDictionary(
                new ExtractionContext( "P", List.of(), Map.of() ), 10 ) );
        assertEquals( "", ExtractionPromptBuilder.existingNodesDictionary(
                new ExtractionContext( "P", List.of( node( "A", "Concept" ) ), Map.of() ), 0 ) );
    }

    @Test
    void existingNodesDictionaryFormatsNullTypeAsConcept() {
        final ExtractionContext ctx = new ExtractionContext( "P", List.of( node( "Mystery", null ) ), Map.of() );
        assertEquals( "- Mystery :: concept", ExtractionPromptBuilder.existingNodesDictionary( ctx, 10 ) );
    }

    @Test
    void existingNodesDictionarySortsCaseInsensitivelyAndRespectsCap() {
        final ExtractionContext ctx = new ExtractionContext( "P", List.of(
                node( "zebra", "Concept" ), node( "Apple", "Concept" ), node( "banana", "Concept" ) ), Map.of() );

        // Cap of 2 keeps only the first two in sorted order: Apple, banana.
        final String dict = ExtractionPromptBuilder.existingNodesDictionary( ctx, 2 );
        assertEquals( "- Apple :: concept\n- banana :: concept", dict );
    }
}

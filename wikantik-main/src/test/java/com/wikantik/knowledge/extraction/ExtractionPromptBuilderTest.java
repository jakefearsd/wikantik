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

import com.wikantik.api.knowledge.RelationshipTypeVocabulary;
import org.junit.jupiter.api.Test;

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
}

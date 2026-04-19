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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingModelTest {

    @Test
    void nomicEmitsAsymmetricPrefixes() {
        final EmbeddingModel m = EmbeddingModel.NOMIC_EMBED_V1_5;
        assertEquals( "search_query: ", m.prefix( EmbeddingKind.QUERY ) );
        assertEquals( "search_document: ", m.prefix( EmbeddingKind.DOCUMENT ) );
        assertNotEquals( m.prefix( EmbeddingKind.QUERY ), m.prefix( EmbeddingKind.DOCUMENT ) );
        assertEquals( 768, m.dimension() );
    }

    @Test
    void bgeM3UsesNoPrefix() {
        final EmbeddingModel m = EmbeddingModel.BGE_M3;
        assertEquals( "", m.prefix( EmbeddingKind.QUERY ) );
        assertEquals( "", m.prefix( EmbeddingKind.DOCUMENT ) );
        assertEquals( 1024, m.dimension() );
    }

    @Test
    void qwen3PrefixesOnlyTheQuerySide() {
        final EmbeddingModel m = EmbeddingModel.QWEN3_EMBEDDING_06B;
        assertTrue( m.prefix( EmbeddingKind.QUERY ).contains( "Query:" ),
                    "Qwen3 query prefix must include the instruction template" );
        assertEquals( "", m.prefix( EmbeddingKind.DOCUMENT ),
                      "Qwen3 documents are embedded without a prefix" );
        assertEquals( 1024, m.dimension() );
    }

    @Test
    void fromCodeIsCaseInsensitiveAndStable() {
        assertSame( EmbeddingModel.NOMIC_EMBED_V1_5, EmbeddingModel.fromCode( "nomic-embed-v1.5" ) );
        assertSame( EmbeddingModel.NOMIC_EMBED_V1_5, EmbeddingModel.fromCode( "  NOMIC-EMBED-V1.5 " ) );
        assertSame( EmbeddingModel.BGE_M3, EmbeddingModel.fromCode( "bge-m3" ) );
        assertSame( EmbeddingModel.QWEN3_EMBEDDING_06B, EmbeddingModel.fromCode( "qwen3-embedding-0.6b" ) );
    }

    @Test
    void fromCodeRejectsUnknownIdentifiers() {
        assertThrows( IllegalArgumentException.class, () -> EmbeddingModel.fromCode( "text-embedding-ada-002" ) );
        assertThrows( IllegalArgumentException.class, () -> EmbeddingModel.fromCode( null ) );
    }

    @Test
    void everyModelAdvertisesAnOllamaTag() {
        for( final EmbeddingModel m : EmbeddingModel.values() ) {
            assertTrue( m.defaultOllamaTag() != null && !m.defaultOllamaTag().isBlank(),
                        "model " + m + " has no default Ollama tag" );
        }
    }
}

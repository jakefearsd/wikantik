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
package com.wikantik.search.hybrid;

import com.wikantik.search.hybrid.LuceneBm25ChunkIndex.IndexedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LuceneBm25ChunkIndexTest {

    private static final UUID C1 = UUID.fromString( "00000000-0000-0000-0000-000000000001" );
    private static final UUID C2 = UUID.fromString( "00000000-0000-0000-0000-000000000002" );
    private static final UUID C3 = UUID.fromString( "00000000-0000-0000-0000-000000000003" );

    private static LuceneBm25ChunkIndex index() {
        return new LuceneBm25ChunkIndex( List.of(
            new IndexedChunk( C1, "BlueGreenDeployments", "blue green deployment strategy with instant rollback" ),
            new IndexedChunk( C2, "CanaryDeployments", "canary release gradually splits production traffic" ),
            new IndexedChunk( C3, "GraphRAG", "knowledge graph entity extraction and retrieval" ) ) );
    }

    @Test
    void lexicalMatchRanksFirst() {
        final List< ScoredChunk > hits = index().topKChunks( "canary traffic", 5 );
        assertFalse( hits.isEmpty() );
        assertEquals( C2, hits.get( 0 ).chunkId() );
        assertEquals( "CanaryDeployments", hits.get( 0 ).pageName() );
        assertTrue( hits.get( 0 ).score() > 0.0 );
    }

    @Test
    void distinctTermSelectsItsChunk() {
        final List< ScoredChunk > hits = index().topKChunks( "rollback", 5 );
        assertEquals( 1, hits.size() );
        assertEquals( C1, hits.get( 0 ).chunkId() );
    }

    @Test
    void noLexicalOverlapReturnsEmpty() {
        assertTrue( index().topKChunks( "kubernetes helm istio", 5 ).isEmpty() );
    }

    @Test
    void blankQueryAndSize() {
        final LuceneBm25ChunkIndex idx = index();
        assertEquals( 3, idx.size() );
        assertTrue( idx.topKChunks( "", 5 ).isEmpty() );
        assertTrue( idx.topKChunks( "canary", 0 ).isEmpty() );
    }

    @Test
    void blankAndNullTextChunksAreSkipped() {
        final LuceneBm25ChunkIndex idx = new LuceneBm25ChunkIndex( java.util.Arrays.asList(
            new IndexedChunk( C1, "P", "real text here" ),
            new IndexedChunk( C2, "P", "  " ),
            new IndexedChunk( C3, "P", null ) ) );
        assertEquals( 1, idx.size() );
    }
}

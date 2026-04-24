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
package com.wikantik.knowledge;

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers( disabledWithoutDocker = true )
class MentionIndexTest {

    private static DataSource dataSource;

    @BeforeAll
    static void init() { dataSource = PostgresTestContainer.createDataSource(); }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    @Test
    void isMentioned_returnsTrueForMentionedNode() throws Exception {
        final UUID nodeId = UUID.randomUUID();
        final UUID chunkId = UUID.randomUUID();
        seedChunk( chunkId, "P", 0 );
        seedNode( nodeId, "Alpha" );
        seedMention( chunkId, nodeId, 0.9 );

        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.isMentioned( nodeId ) );
    }

    @Test
    void isMentioned_returnsFalseForUnmentionedNode() {
        final UUID nodeId = UUID.randomUUID();
        final MentionIndex idx = new MentionIndex( dataSource );
        assertFalse( idx.isMentioned( nodeId ) );
    }

    @Test
    void getMentionedIds_returnsAllDistinctIds() throws Exception {
        final UUID n1 = UUID.randomUUID();
        final UUID n2 = UUID.randomUUID();
        final UUID n3 = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        seedChunk( c1, "P1", 0 );
        seedChunk( c2, "P2", 0 );
        seedNode( n1, "A" ); seedNode( n2, "B" ); seedNode( n3, "C" );
        seedMention( c1, n1, 0.9 );
        seedMention( c1, n2, 0.8 );
        seedMention( c2, n2, 0.7 );

        final MentionIndex idx = new MentionIndex( dataSource );
        final Set< UUID > ids = idx.getMentionedIds();
        assertEquals( 2, ids.size() );
        assertTrue( ids.contains( n1 ) );
        assertTrue( ids.contains( n2 ) );
        assertFalse( ids.contains( n3 ) );
    }

    @Test
    void getCoMentionCounts_returnsSharedChunkCounts() throws Exception {
        final UUID alpha = UUID.randomUUID();
        final UUID beta  = UUID.randomUUID();
        final UUID gamma = UUID.randomUUID();
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        final UUID c3 = UUID.randomUUID();
        seedChunk( c1, "P1", 0 );
        seedChunk( c2, "P1", 1 );
        seedChunk( c3, "P2", 0 );
        seedNode( alpha, "Alpha" ); seedNode( beta, "Beta" ); seedNode( gamma, "Gamma" );
        seedMention( c1, alpha, 0.9 );
        seedMention( c1, beta, 0.8 );
        seedMention( c2, alpha, 0.9 );
        seedMention( c2, beta, 0.8 );
        seedMention( c3, alpha, 0.9 );
        seedMention( c3, gamma, 0.8 );

        final MentionIndex idx = new MentionIndex( dataSource );
        final Map< UUID, Integer > counts = idx.getCoMentionCounts( alpha );
        assertEquals( 2, (int) counts.get( beta ) );
        assertEquals( 1, (int) counts.get( gamma ) );
        assertFalse( counts.containsKey( alpha ),
            "self should not appear in co-mention counts" );
    }

    @Test
    void getCoMentionCounts_returnsEmptyForUnmentionedNode() {
        final UUID nodeId = UUID.randomUUID();
        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.getCoMentionCounts( nodeId ).isEmpty() );
    }

    @Test
    void findRelatedPages_returnsPagesSharingEntityMentions() throws Exception {
        // Page graph (via shared node mentions):
        //   Alpha  mentions BM25, Qwen3       (chunk a1)
        //   Beta   mentions BM25, Qwen3, RRF  (chunks b1, b2) — shares 2 with Alpha
        //   Gamma  mentions BM25              (chunk g1)      — shares 1 with Alpha
        //   Delta  mentions UnrelatedEntity   (chunk d1)      — shares 0
        final UUID bm25    = UUID.randomUUID();
        final UUID qwen3   = UUID.randomUUID();
        final UUID rrf     = UUID.randomUUID();
        final UUID unrel   = UUID.randomUUID();
        seedNode( bm25,  "BM25" );
        seedNode( qwen3, "Qwen3" );
        seedNode( rrf,   "RRF" );
        seedNode( unrel, "UnrelatedEntity" );

        final UUID a1 = UUID.randomUUID();
        final UUID b1 = UUID.randomUUID();
        final UUID b2 = UUID.randomUUID();
        final UUID g1 = UUID.randomUUID();
        final UUID d1 = UUID.randomUUID();
        seedChunk( a1, "Alpha", 0 );
        seedChunk( b1, "Beta",  0 );
        seedChunk( b2, "Beta",  1 );
        seedChunk( g1, "Gamma", 0 );
        seedChunk( d1, "Delta", 0 );

        seedMention( a1, bm25,  0.9 ); seedMention( a1, qwen3, 0.9 );
        seedMention( b1, bm25,  0.9 ); seedMention( b2, qwen3, 0.8 ); seedMention( b2, rrf, 0.8 );
        seedMention( g1, bm25,  0.9 );
        seedMention( d1, unrel, 0.9 );

        final MentionIndex idx = new MentionIndex( dataSource );
        final var related = idx.findRelatedPages( "Alpha", 5 );

        assertEquals( 2, related.size(),
            "Delta shares 0 entities; Beta and Gamma are the only matches" );
        assertEquals( "Beta", related.get( 0 ).pageName(),
            "Beta shares 2 entities (BM25 + Qwen3) — highest count first" );
        assertEquals( 2, related.get( 0 ).sharedCount() );
        assertEquals( java.util.Set.of( "BM25", "Qwen3" ),
            new java.util.HashSet<>( related.get( 0 ).sharedEntityNames() ),
            "shared entity names should be the actual node names" );

        assertEquals( "Gamma", related.get( 1 ).pageName() );
        assertEquals( 1, related.get( 1 ).sharedCount() );
        assertEquals( List.of( "BM25" ), related.get( 1 ).sharedEntityNames() );
    }

    @Test
    void findRelatedPages_excludesSelf() throws Exception {
        final UUID node = UUID.randomUUID();
        seedNode( node, "OnlyEntity" );
        final UUID c1 = UUID.randomUUID();
        final UUID c2 = UUID.randomUUID();
        seedChunk( c1, "SelfPage", 0 );
        seedChunk( c2, "SelfPage", 1 );
        seedMention( c1, node, 0.9 );
        seedMention( c2, node, 0.9 );

        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.findRelatedPages( "SelfPage", 5 ).isEmpty(),
            "a page that only shares mentions with its own chunks has no related pages" );
    }

    @Test
    void findRelatedPages_rejectsNullOrBlankInput() {
        final MentionIndex idx = new MentionIndex( dataSource );
        assertTrue( idx.findRelatedPages( null, 5 ).isEmpty() );
        assertTrue( idx.findRelatedPages( "", 5 ).isEmpty() );
        assertTrue( idx.findRelatedPages( "Foo", 0 ).isEmpty(),
            "zero limit returns empty" );
    }

    private static void seedChunk( UUID id, String page, int idx ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_content_chunks (id, page_name, chunk_index, heading_path, text, "
              + "char_count, token_count_estimate, content_hash) VALUES "
              + "('" + id + "', '" + page + "', " + idx + ", ARRAY['H'], 'body', 4, 1, '" + id + "h')" );
        }
    }

    private static void seedNode( UUID id, String name ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO kg_nodes (id, name, node_type, provenance, created, modified) VALUES "
              + "('" + id + "', '" + name + "', 'type', 'human-authored', NOW(), NOW())" );
        }
    }

    private static void seedMention( UUID chunkId, UUID nodeId, double confidence ) throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute(
                "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor, extracted_at) VALUES "
              + "('" + chunkId + "', '" + nodeId + "', " + confidence + ", 'test', NOW())" );
        }
    }
}

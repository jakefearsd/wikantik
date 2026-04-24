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

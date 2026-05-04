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

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Testcontainers( disabledWithoutDocker = true )
class ChunkEntityMentionRepositoryTest {

    private DataSource ds;
    private ChunkEntityMentionRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DELETE FROM chunk_entity_mentions" );
            st.execute( "DELETE FROM kg_content_chunks" );
            st.execute( "DELETE FROM kg_edges" );
            st.execute( "DELETE FROM kg_nodes" );
        }
        repo = new ChunkEntityMentionRepository( ds );
    }

    /**
     * Reproducer for the chunk_entity_mentions_chunk_id_fkey race seen in
     * production: ChunkProjector re-chunks a page (cascading-delete the old
     * chunk and inserting new ones) while AsyncEntityExtractionListener is
     * still resolving mentions against the prior chunk ids. The upsert
     * should not throw — the orphan row is a no-op the cascade would have
     * wiped anyway.
     */
    @Test
    void upsertAll_swallowsForeignKeyViolation_returnsZero() throws Exception {
        final UUID nodeId = insertNode( "Foo", "concept" );
        final UUID phantomChunkId = UUID.randomUUID();

        final int wrote = assertDoesNotThrow( () -> repo.upsertAll( List.of(
            new ChunkEntityMentionRepository.Row( phantomChunkId, nodeId, 0.9, "test-extractor" ) ) ) );

        assertEquals( 0, wrote, "stale-chunk upsert is silently dropped" );
    }

    private UUID insertNode( final String name, final String type ) throws Exception {
        try ( Connection c = ds.getConnection();
              var ps = c.prepareStatement(
                  "INSERT INTO kg_nodes (id, name, node_type, source_page, provenance) " +
                  "VALUES (?, ?, ?, 'TestPage', 'human-authored')" ) ) {
            final UUID id = UUID.randomUUID();
            ps.setObject( 1, id );
            ps.setString( 2, name );
            ps.setString( 3, type );
            ps.executeUpdate();
            return id;
        }
    }
}

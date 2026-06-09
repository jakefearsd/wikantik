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
import com.wikantik.api.knowledge.KgEdgeView;
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.PageKnowledgeSlice;
import com.wikantik.api.knowledge.Provenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultKnowledgeGraphService#getPageSlice(String)}.
 *
 * <p>Uses the shared pgvector Testcontainer (same harness as
 * {@link DefaultKnowledgeGraphServiceTraverseByCoMentionTest}) to exercise
 * the real SQL join across {@code kg_content_chunks}, {@code chunk_entity_mentions},
 * and {@code kg_edges}.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServicePageSliceTest {

    private static DataSource dataSource;
    private DefaultKnowledgeGraphService service;

    @BeforeAll
    static void initDs() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void setUp() {
        service = new DefaultKnowledgeGraphService(
            new KgNodeRepository( dataSource ),
            new KgEdgeRepository( dataSource ),
            new KgProposalRepository( dataSource ),
            new KgRejectionRepository( dataSource ),
            dataSource );
    }

    @AfterEach
    void cleanUp() throws Exception {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
        }
    }

    /** Seeds a chunk on the given page and attaches the given node ids as mentions. */
    private UUID seedChunkWithMentions( final String pageName, final String suffix,
                                         final UUID... nodeIds ) throws Exception {
        final UUID chunkId = UUID.randomUUID();
        try ( final Connection c = dataSource.getConnection() ) {
            try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kg_content_chunks "
              + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, 0, ?, ?, ?, ?)" ) ) {
                ps.setObject( 1, chunkId );
                ps.setString( 2, pageName );
                final String text = "text-" + suffix;
                ps.setString( 3, text );
                ps.setInt( 4, text.length() );
                ps.setInt( 5, text.length() );
                ps.setString( 6, Integer.toHexString( pageName.hashCode() ^ suffix.hashCode() ) );
                ps.executeUpdate();
            }
            for ( final UUID nodeId : nodeIds ) {
                try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
                  + "VALUES (?, ?, 1.0, 'test')" ) ) {
                    ps.setObject( 1, chunkId );
                    ps.setObject( 2, nodeId );
                    ps.executeUpdate();
                }
            }
        }
        return chunkId;
    }

    // -------------------------------------------------------------------------
    // getPageSlice — empty page
    // -------------------------------------------------------------------------

    @Test
    void getPageSlice_unknownPage_returnsEmptyLists() {
        final PageKnowledgeSlice slice = service.getPageSlice( "NoSuchPage.md" );
        assertNotNull( slice );
        assertTrue( slice.entities().isEmpty() );
        assertTrue( slice.edges().isEmpty() );
    }

    // -------------------------------------------------------------------------
    // getPageSlice — entities and intra-page edge
    // -------------------------------------------------------------------------

    @Test
    void getPageSlice_returnsEntitiesAndIntraPageEdge() throws Exception {
        // Two nodes on the page, one edge between them.
        final KgNode alpha = service.upsertNode( "Alpha", "concept", "PageA.md",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode beta = service.upsertNode( "Beta", "concept", "PageA.md",
            Provenance.HUMAN_AUTHORED, Map.of() );

        seedChunkWithMentions( "PageA.md", "chunk1", alpha.id(), beta.id() );

        service.upsertEdge( alpha.id(), beta.id(), "uses",
            Provenance.HUMAN_AUTHORED, Map.of() );

        final PageKnowledgeSlice slice = service.getPageSlice( "PageA.md" );

        // Both nodes returned
        final List< UUID > entityIds = slice.entities().stream().map( KgNode::id ).toList();
        assertTrue( entityIds.contains( alpha.id() ),
            "Expected Alpha in entities; got " + entityIds );
        assertTrue( entityIds.contains( beta.id() ),
            "Expected Beta in entities; got " + entityIds );

        // The intra-page edge is returned with names resolved
        assertEquals( 1, slice.edges().size(),
            "Expected 1 intra-page edge; got " + slice.edges().size() );
        final KgEdgeView ev = slice.edges().get( 0 );
        assertEquals( alpha.id(), ev.sourceId() );
        assertEquals( beta.id(), ev.targetId() );
        assertEquals( "Alpha", ev.sourceName() );
        assertEquals( "Beta", ev.targetName() );
        assertEquals( "uses", ev.relationshipType() );
    }

    // -------------------------------------------------------------------------
    // getPageSlice — cross-page edge excluded
    // -------------------------------------------------------------------------

    @Test
    void getPageSlice_excludesCrossPageEdge() throws Exception {
        // Alpha is on PageA, Gamma is on a different page (PageB).
        final KgNode alpha = service.upsertNode( "Alpha", "concept", "PageA.md",
            Provenance.HUMAN_AUTHORED, Map.of() );
        final KgNode gamma = service.upsertNode( "Gamma", "concept", "PageB.md",
            Provenance.HUMAN_AUTHORED, Map.of() );

        seedChunkWithMentions( "PageA.md", "chunk-a", alpha.id() );
        // Gamma is mentioned on PageB, not PageA.
        seedChunkWithMentions( "PageB.md", "chunk-b", gamma.id() );

        // Edge between Alpha (on-page) and Gamma (off-page).
        service.upsertEdge( alpha.id(), gamma.id(), "uses",
            Provenance.HUMAN_AUTHORED, Map.of() );

        final PageKnowledgeSlice slice = service.getPageSlice( "PageA.md" );

        // Only Alpha should be in the slice (only PageA mentions).
        assertEquals( 1, slice.entities().size() );
        assertEquals( alpha.id(), slice.entities().get( 0 ).id() );

        // The cross-page edge must be excluded.
        assertTrue( slice.edges().isEmpty(),
            "Cross-page edge must not appear in the page slice" );
    }

    // -------------------------------------------------------------------------
    // getPageSlice — deduplication when a node is mentioned in multiple chunks
    // -------------------------------------------------------------------------

    @Test
    void getPageSlice_deduplicatesNodesAcrossChunks() throws Exception {
        final KgNode alpha = service.upsertNode( "Alpha", "concept", "PageA.md",
            Provenance.HUMAN_AUTHORED, Map.of() );

        // Alpha is mentioned in two different chunks on the same page.
        try ( final Connection c = dataSource.getConnection() ) {
            final UUID c1 = UUID.randomUUID();
            final UUID c2 = UUID.randomUUID();
            try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO kg_content_chunks "
              + "(id, page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?)" ) ) {
                ps.setObject( 1, c1 ); ps.setString( 2, "PageA.md" ); ps.setInt( 3, 1 );
                ps.setString( 4, "t1" ); ps.setInt( 5, 2 ); ps.setInt( 6, 2 );
                ps.setString( 7, "h1" );
                ps.executeUpdate();
                ps.setObject( 1, c2 ); ps.setString( 2, "PageA.md" ); ps.setInt( 3, 2 );
                ps.setString( 4, "t2" ); ps.setInt( 5, 2 ); ps.setInt( 6, 2 );
                ps.setString( 7, "h2" );
                ps.executeUpdate();
            }
            for ( final UUID cid : new UUID[]{ c1, c2 } ) {
                try ( final java.sql.PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chunk_entity_mentions (chunk_id, node_id, confidence, extractor) "
                  + "VALUES (?, ?, 1.0, 'test')" ) ) {
                    ps.setObject( 1, cid );
                    ps.setObject( 2, alpha.id() );
                    ps.executeUpdate();
                }
            }
        }

        final PageKnowledgeSlice slice = service.getPageSlice( "PageA.md" );
        assertEquals( 1, slice.entities().size(),
            "Node mentioned in multiple chunks must appear only once" );
        assertEquals( alpha.id(), slice.entities().get( 0 ).id() );
    }
}

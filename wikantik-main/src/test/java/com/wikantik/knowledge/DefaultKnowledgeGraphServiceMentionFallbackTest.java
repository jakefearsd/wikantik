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
import com.wikantik.api.knowledge.KgNode;
import com.wikantik.api.knowledge.KgProposal;
import com.wikantik.api.knowledge.NodeMention;
import com.wikantik.api.knowledge.Provenance;
import com.wikantik.knowledge.chunking.Chunk;
import com.wikantik.knowledge.chunking.ChunkDiff;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the fallback path of {@link DefaultKnowledgeGraphService#getMentionsForNode}:
 * when a concept node has no {@code chunk_entity_mentions} rows — typically
 * because it was auto-created as an edge endpoint by
 * {@link com.wikantik.knowledge.judge.KgMaterializationService} — the service
 * should synthesise mention chunks at read time from the originating
 * proposal's {@code source_page}, tagged with extractor
 * {@code "edge-proposal-fallback"} so the UI can label them as inferred
 * context. This is a display-only fallback; no rows are written to
 * {@code chunk_entity_mentions} or {@code kg_content_chunks}.
 */
@Testcontainers( disabledWithoutDocker = true )
class DefaultKnowledgeGraphServiceMentionFallbackTest {

    private DataSource ds;
    private KgNodeRepository kgNodes;
    private KgEdgeRepository kgEdges;
    private KgProposalRepository kgProposals;
    private KgRejectionRepository kgRejections;
    private ContentChunkRepository kgChunks;
    private DefaultKnowledgeGraphService svc;

    @BeforeEach
    void setUp() throws Exception {
        ds = PostgresTestContainer.createDataSource();
        kgNodes      = new KgNodeRepository( ds );
        kgEdges      = new KgEdgeRepository( ds );
        kgProposals  = new KgProposalRepository( ds );
        kgRejections = new KgRejectionRepository( ds );
        kgChunks     = new ContentChunkRepository( ds );
        svc = new DefaultKnowledgeGraphService( kgNodes, kgEdges, kgProposals, kgRejections, ds );
        clean();
    }

    @AfterEach
    void tearDown() throws Exception {
        clean();
    }

    private void clean() throws Exception {
        try ( Connection c = ds.getConnection() ) {
            c.createStatement().execute( "DELETE FROM chunk_entity_mentions" );
            c.createStatement().execute( "DELETE FROM kg_edges" );
            c.createStatement().execute( "DELETE FROM kg_proposals" );
            c.createStatement().execute( "DELETE FROM kg_nodes" );
            c.createStatement().execute( "DELETE FROM kg_rejections" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
    }

    private UUID seedChunk( final String pageName, final int idx,
                            final List< String > headingPath, final String text ) {
        final Chunk c = new Chunk( pageName, idx, headingPath, text,
            text.length(), Math.max( 1, text.length() / 4 ), "h-" + pageName + "-" + idx );
        kgChunks.apply( pageName,
            new ChunkDiff.Diff( List.of( c ), List.of(), List.of() ) );
        final List< UUID > ids = kgChunks.listChunkIdsForPage( pageName );
        return ids.get( ids.size() - 1 );
    }

    private void writeMention( final UUID chunkId, final UUID nodeId,
                                final double confidence, final String extractor ) throws Exception {
        try ( Connection c = ds.getConnection();
              PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO chunk_entity_mentions ( chunk_id, node_id, confidence, extractor ) "
                + "VALUES ( ?, ?, ?, ? )" ) ) {
            ps.setObject( 1, chunkId );
            ps.setObject( 2, nodeId );
            ps.setDouble( 3, confidence );
            ps.setString( 4, extractor );
            ps.executeUpdate();
        }
    }

    @Test
    void returnsRealMentionsWhenAttributionRowsExist() throws Exception {
        // Sanity: when chunk_entity_mentions has rows the fallback never fires.
        final KgNode node = svc.upsertNode( "ReadinessProbe", "concept", "KubernetesDeep",
            Provenance.AI_INFERRED, Map.of() );
        final UUID chunkId = seedChunk( "KubernetesDeep", 0, List.of( "Probes" ),
            "The readinessProbe is run continuously after startup." );
        writeMention( chunkId, node.id(), 0.91, "gemma4-assist:latest" );

        final List< NodeMention > out = svc.getMentionsForNode( node.id(), 5 );
        assertEquals( 1, out.size() );
        assertEquals( "gemma4-assist:latest", out.get( 0 ).extractor() );
        assertEquals( 0.91, out.get( 0 ).confidence(), 0.0001 );
    }

    @Test
    void fallsBackToProposalSourcePageChunksWhenNoMentionsExist() throws Exception {
        // A new-edge proposal sourced from PhilosophyHub names "Confucianism"
        // as the edge target. Materialisation auto-creates the node with no
        // chunk attribution. The fallback finds chunks on PhilosophyHub that
        // contain the entity name and returns them tagged as inferred.
        final KgProposal proposal = kgProposals.insertProposal( "new-edge", "PhilosophyHub",
            Map.of( "source", "Eastern Philosophy", "target", "Confucianism",
                    "relationship", "contains" ),
            1.0, "" );
        final KgNode node = kgNodes.upsertNodeWithProvenance(
            "Confucianism", "concept", null, Provenance.AI_INFERRED, Map.of(),
            "machine", proposal.id() );
        seedChunk( "PhilosophyHub", 0, List.of( "Schools" ),
            "Confucianism is a system of ethical and philosophical thought." );
        seedChunk( "PhilosophyHub", 1, List.of( "Schools", "Modern era" ),
            "Modern Confucianism still shapes East Asian institutions." );
        seedChunk( "PhilosophyHub", 2, List.of( "Other" ),
            "Taoism, by contrast, takes a different stance entirely." );

        final List< NodeMention > out = svc.getMentionsForNode( node.id(), 5 );

        assertEquals( 2, out.size(), "should return only the two chunks containing 'Confucianism'" );
        assertEquals( "PhilosophyHub", out.get( 0 ).pageName() );
        assertTrue( out.stream().allMatch( m -> "edge-proposal-fallback".equals( m.extractor() ) ),
            "fallback rows must be tagged with extractor='edge-proposal-fallback'" );
        assertTrue( out.stream().allMatch(
            m -> m.text().toLowerCase().contains( "confucianism" ) ),
            "fallback rows must actually contain the entity name" );

        // No writes are made — verify chunk_entity_mentions is still empty.
        try ( Connection c = ds.getConnection();
              var rs = c.createStatement().executeQuery(
                  "SELECT COUNT(*) FROM chunk_entity_mentions" ) ) {
            rs.next();
            assertEquals( 0, rs.getInt( 1 ),
                "fallback must NOT write to chunk_entity_mentions" );
        }
    }

    @Test
    void returnsEmptyWhenNoProposalAndNoMentions() throws Exception {
        // No provenance_proposal_id → no fallback possible. Empty list.
        final KgNode node = svc.upsertNode( "Orphan", "concept", null,
            Provenance.AI_INFERRED, Map.of() );
        assertTrue( svc.getMentionsForNode( node.id(), 5 ).isEmpty() );
    }

    @Test
    void returnsEmptyWhenProposalSourcePageIsBlank() throws Exception {
        // Proposals with null source_page (rare but legal in the schema) leave
        // the fallback with nothing to query — keep returning empty cleanly.
        final KgProposal proposal = kgProposals.insertProposal( "new-edge", null,
            Map.of( "source", "X", "target", "Y", "relationship", "related_to" ),
            1.0, "" );
        final KgNode node = kgNodes.upsertNodeWithProvenance(
            "Y", "concept", null, Provenance.AI_INFERRED, Map.of(),
            "machine", proposal.id() );
        assertTrue( svc.getMentionsForNode( node.id(), 5 ).isEmpty() );
    }
}

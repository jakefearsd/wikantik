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

import com.wikantik.PostgresTestContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incremental-refresh contract for {@link LuceneBm25ChunkIndex} — the lexical half
 * of the chunk-level hybrid behind {@code /api/bundle}.
 *
 * <p>Regression cover for the defect this class was built to close: the index used
 * to be a snapshot taken once at wiring time by {@link LuceneBm25ChunkIndex#fromDataSource},
 * with no update path at all. Every page written after startup stayed invisible to
 * lexical chunk retrieval until the process was restarted, while the dense half of the
 * same fusion updated on every save. On a wiki whose pages are authored continuously by
 * agents over MCP, the lexical half decayed for as long as the instance stayed up.</p>
 *
 * <p>These tests drive the database directly rather than through the save pipeline:
 * the unit under test is the index's own refresh contract. The wiring that calls it on
 * every chunk change is covered separately by {@code AsyncEmbeddingIndexListenerTest}
 * and end to end by {@code DenseBundleIT}.</p>
 */
@Testcontainers( disabledWithoutDocker = true )
class LuceneBm25ChunkIndexRefreshTest {

    private static DataSource dataSource;

    @BeforeAll
    static void initDataSource() {
        dataSource = PostgresTestContainer.createDataSource();
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try ( final Connection c = dataSource.getConnection() ) {
            c.createStatement().execute( "DELETE FROM content_chunk_embeddings" );
            c.createStatement().execute( "DELETE FROM kg_content_chunks" );
        }
    }

    private static LuceneBm25ChunkIndex index() {
        return LuceneBm25ChunkIndex.fromDataSource( dataSource, LuceneBm25ChunkIndex.analyzerFor( "standard" ) );
    }

    /**
     * The headline defect. An index built while the corpus was empty — exactly what an
     * integration-test or freshly-restarted deployment does — must pick up a page saved
     * afterwards. Before the fix this returned nothing, forever.
     */
    @Test
    void chunkWrittenAfterTheIndexWasBuiltBecomesSearchable() throws SQLException {
        final LuceneBm25ChunkIndex idx = index();
        assertEquals( 0, idx.size(), "precondition: index built against an empty corpus" );

        final UUID id = seedChunk( "BlueGreenDeployments", 0, "blue green deployment with instant rollback" );
        idx.upsertChunks( List.of( id ) );

        final List< ScoredChunk > hits = idx.topKChunks( "rollback", 5 );
        assertEquals( 1, hits.size(), "the chunk saved after startup must be lexically retrievable" );
        assertEquals( id, hits.get( 0 ).chunkId() );
        assertEquals( "BlueGreenDeployments", hits.get( 0 ).pageName() );
        assertEquals( 1, idx.size() );
    }

    /** An edit must replace the old text, not shadow it with a second copy. */
    @Test
    void editedChunkTextReplacesTheOldRevision() throws SQLException {
        final UUID id = seedChunk( "CanaryDeployments", 0, "canary release splits production traffic" );
        final LuceneBm25ChunkIndex idx = index();
        assertEquals( 1, idx.topKChunks( "canary", 5 ).size(), "precondition: original text indexed" );

        updateChunkText( id, "progressive delivery shifts requests gradually" );
        idx.upsertChunks( List.of( id ) );

        assertTrue( idx.topKChunks( "canary", 5 ).isEmpty(), "the superseded text must be gone" );
        assertEquals( 1, idx.topKChunks( "progressive delivery", 5 ).size(), "the new text must be present" );
        assertEquals( 1, idx.size(), "an edit must not leave a duplicate document behind" );
    }

    /**
     * A page that shrinks — 3 chunks edited down to 1 — must not leave the dropped chunks
     * searchable. The save-time notification carries only the ids that still exist, so the
     * index has to reconcile the whole page rather than the ids it was handed.
     */
    @Test
    void chunksDroppedFromAPageDisappearFromTheIndex() throws SQLException {
        seedChunk( "ReleaseStrategies", 0, "first section about staging environments" );
        seedChunk( "ReleaseStrategies", 1, "second section about smoke verification" );
        final LuceneBm25ChunkIndex idx = index();
        assertEquals( 2, idx.size(), "precondition: both chunks indexed" );

        // The page is re-chunked down to one chunk; the survivor is the only id the
        // post-chunk sink will hand over.
        deleteChunk( "ReleaseStrategies", 1 );
        final UUID survivor = chunkId( "ReleaseStrategies", 0 );
        idx.upsertChunks( List.of( survivor ) );

        assertTrue( idx.topKChunks( "smoke verification", 5 ).isEmpty(),
            "text from a chunk the page no longer has must not stay retrievable" );
        assertEquals( 1, idx.topKChunks( "staging environments", 5 ).size() );
        assertEquals( 1, idx.size() );
    }

    /** Reconciliation is scoped to the touched pages — an unrelated page is left alone. */
    @Test
    void upsertDoesNotDisturbUntouchedPages() throws SQLException {
        seedChunk( "GraphRAG", 0, "knowledge graph entity extraction" );
        final LuceneBm25ChunkIndex idx = index();

        final UUID other = seedChunk( "VectorSearch", 0, "approximate nearest neighbour recall" );
        idx.upsertChunks( List.of( other ) );

        assertEquals( 2, idx.size() );
        assertEquals( 1, idx.topKChunks( "entity extraction", 5 ).size(), "untouched page still indexed" );
        assertEquals( 1, idx.topKChunks( "nearest neighbour", 5 ).size() );
    }

    /** Degenerate inputs are no-ops, not exceptions — this runs on the save path's executor. */
    @Test
    void nullEmptyAndUnknownIdsAreNoOps() throws SQLException {
        seedChunk( "GraphRAG", 0, "knowledge graph entity extraction" );
        final LuceneBm25ChunkIndex idx = index();

        idx.upsertChunks( null );
        idx.upsertChunks( List.of() );
        idx.upsertChunks( List.of( UUID.randomUUID() ) );

        assertEquals( 1, idx.size(), "no-op inputs must not mutate the index" );
        assertFalse( idx.topKChunks( "entity extraction", 5 ).isEmpty() );
    }

    /** Full rebuild — the path the admin content-reindex action leans on. */
    @Test
    void reloadRebuildsFromTheDatabase() throws SQLException {
        final LuceneBm25ChunkIndex idx = index();
        seedChunk( "BlueGreenDeployments", 0, "blue green deployment with instant rollback" );
        seedChunk( "CanaryDeployments", 0, "canary release splits production traffic" );

        idx.reload();

        assertEquals( 2, idx.size() );
        assertEquals( 1, idx.topKChunks( "rollback", 5 ).size() );
        assertEquals( 1, idx.topKChunks( "canary", 5 ).size() );
    }

    // -----------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------

    private static UUID seedChunk( final String pageName, final int idx, final String text ) throws SQLException {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "INSERT INTO kg_content_chunks "
                + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
                + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id" ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, idx );
            ps.setString( 3, text );
            ps.setInt( 4, text.length() );
            ps.setInt( 5, text.length() / 4 );
            ps.setString( 6, UUID.randomUUID().toString() );
            try ( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getObject( 1, UUID.class );
            }
        }
    }

    private static void updateChunkText( final UUID id, final String text ) throws SQLException {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "UPDATE kg_content_chunks SET text = ? WHERE id = ?" ) ) {
            ps.setString( 1, text );
            ps.setObject( 2, id );
            ps.executeUpdate();
        }
    }

    private static void deleteChunk( final String pageName, final int idx ) throws SQLException {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "DELETE FROM kg_content_chunks WHERE page_name = ? AND chunk_index = ?" ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, idx );
            ps.executeUpdate();
        }
    }

    private static UUID chunkId( final String pageName, final int idx ) throws SQLException {
        try ( final Connection c = dataSource.getConnection();
              final PreparedStatement ps = c.prepareStatement(
                  "SELECT id FROM kg_content_chunks WHERE page_name = ? AND chunk_index = ?" ) ) {
            ps.setString( 1, pageName );
            ps.setInt( 2, idx );
            try ( final ResultSet rs = ps.executeQuery() ) {
                rs.next();
                return rs.getObject( 1, UUID.class );
            }
        }
    }
}

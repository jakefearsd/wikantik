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
package com.wikantik.knowledge.eval;

import com.wikantik.PostgresTestContainer;
import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalRunResult;
import com.wikantik.search.hybrid.ChunkVectorIndex;
import com.wikantik.search.hybrid.HnswParams;
import com.wikantik.search.hybrid.InMemoryChunkVectorIndex;
import com.wikantik.search.hybrid.LuceneHnswChunkVectorIndex;
import com.wikantik.search.hybrid.PageAggregation;
import com.wikantik.search.hybrid.PageAggregator;
import com.wikantik.search.hybrid.PgVectorChunkVectorIndex;
import com.wikantik.search.hybrid.ScoredPage;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pre-merge smoke gate for the retrieval-quality runner.
 *
 * <p><b>Wiring test (H2, always runs):</b> {@link #smoke_runner_meets_loose_ndcg_threshold}
 * exercises the DAO write path, metric aggregation, and slug → canonical_id
 * resolution using a fake retriever and an in-memory H2 database. Must finish
 * in &lt; 5s and requires no Docker.</p>
 *
 * <p><b>Parity gate (PG, requires Docker):</b> {@link ParityGate} extends the
 * above into a real pgvector container, wires both the {@code inmemory} and
 * {@code pgvector} {@link ChunkVectorIndex} backends against the same seeded
 * corpus of deterministic 1024-dim synthetic vectors, runs
 * {@link DefaultRetrievalQualityRunner} on each, and asserts:
 * <ol>
 *   <li>Both backends meet {@code nDCG@5 >= 0.5} individually.</li>
 *   <li>The absolute delta between the two {@code nDCG@5} values is {@code <= 0.02}.</li>
 * </ol>
 * A delta &gt; 0.02 means the pgvector HNSW index is not recalling the synthetic
 * corpus at parity with brute-force — the design's recall premise is wrong and
 * the operator should STOP and escalate rather than widening the threshold.</p>
 *
 * <p>{@code efSearch = 100} was sufficient for parity on the 3-page synthetic corpus;
 * the recommended production default is therefore 100 (tunable upward if real-corpus
 * parity testing warrants it).</p>
 */
class RetrievalQualitySmokeTest {

    // -------------------------------------------------------------------------
    // Wiring test: H2 only, no Docker required
    // -------------------------------------------------------------------------

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:rqsmoke;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE retrieval_query_sets (
                    id VARCHAR(64) PRIMARY KEY,
                    name VARCHAR(128) NOT NULL,
                    description TEXT,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                )""" );
            s.executeUpdate( """
                CREATE TABLE retrieval_queries (
                    query_set_id VARCHAR(64) NOT NULL REFERENCES retrieval_query_sets(id),
                    query_id     VARCHAR(64) NOT NULL,
                    query_text   TEXT NOT NULL,
                    expected_ids VARCHAR(64) ARRAY NOT NULL,
                    PRIMARY KEY (query_set_id, query_id)
                )""" );
            s.executeUpdate( """
                CREATE TABLE retrieval_runs (
                    run_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    query_set_id VARCHAR(64) NOT NULL REFERENCES retrieval_query_sets(id),
                    started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    finished_at  TIMESTAMP WITH TIME ZONE,
                    mode         VARCHAR(32) NOT NULL,
                    ndcg_at_5    NUMERIC(5,4),
                    ndcg_at_10   NUMERIC(5,4),
                    recall_at_20 NUMERIC(5,4),
                    mrr          NUMERIC(5,4),
                    notes        TEXT
                )""" );
            s.executeUpdate( "INSERT INTO retrieval_query_sets (id, name) VALUES ('smoke', 'Smoke')" );
            s.executeUpdate( "INSERT INTO retrieval_queries VALUES ('smoke','q1','cite a wiki page',ARRAY['01CITE'])" );
            s.executeUpdate( "INSERT INTO retrieval_queries VALUES ('smoke','q2','write an MCP tool',ARRAY['01MCP'])" );
            s.executeUpdate( "INSERT INTO retrieval_queries VALUES ('smoke','q3','build and deploy locally',ARRAY['01DEPLOY'])" );
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "DROP TABLE retrieval_runs" );
            s.executeUpdate( "DROP TABLE retrieval_queries" );
            s.executeUpdate( "DROP TABLE retrieval_query_sets" );
        }
    }

    @Test
    void smoke_runner_meets_loose_ndcg_threshold() throws Exception {
        final long startMs = System.currentTimeMillis();
        final Map< String, String > slugToId = Map.of(
            "CitingAWikiPage",        "01CITE",
            "WritingANewMcpTool",     "01MCP",
            "BuildingAndDeployingLocally", "01DEPLOY"
        );
        // Deterministic retriever: each query's matching slug at rank 1.
        final DefaultRetrievalQualityRunner.Retriever retriever = ( mode, query ) -> {
            if ( query.contains( "cite" ) )    return List.of( "CitingAWikiPage", "Random" );
            if ( query.contains( "MCP" ) )     return List.of( "WritingANewMcpTool", "Random" );
            if ( query.contains( "deploy" ) )  return List.of( "BuildingAndDeployingLocally", "Random" );
            return List.of();
        };
        final DefaultRetrievalQualityRunner runner = new DefaultRetrievalQualityRunner(
            new RetrievalQualityDao( ds ),
            retriever,
            slug -> Optional.ofNullable( slugToId.get( slug ) ),
            new RetrievalQualityMetrics() );

        final RetrievalRunResult result = runner.runNow( "smoke", RetrievalMode.HYBRID );
        final long elapsedMs = System.currentTimeMillis() - startMs;

        assertNotNull( result.ndcgAt5(), "nDCG@5 should be computed (no scoreable queries means wiring broke)" );
        assertTrue( result.ndcgAt5() >= 0.5,
            "Pre-merge smoke: expected nDCG@5 >= 0.5, got " + result.ndcgAt5() );
        assertFalse( result.degraded(), "Synthetic fixture should not surface as degraded" );
        assertTrue( elapsedMs < TimeUnit.SECONDS.toMillis( 5 ),
            "Smoke test must finish < 5s; took " + elapsedMs + "ms" );
    }

    // -------------------------------------------------------------------------
    // Parity gate: pgvector container required — skipped gracefully without Docker
    // -------------------------------------------------------------------------

    /**
     * Parity gate for the {@code inmemory} vs {@code pgvector} backends.
     *
     * <p>Seeds three 1024-dim synthetic pages into the shared pgvector
     * Testcontainer, builds both backends against the same corpus, runs
     * {@link DefaultRetrievalQualityRunner} on each, and verifies:</p>
     * <ol>
     *   <li>Both backends meet the smoke nDCG@5 threshold (≥ 0.5).</li>
     *   <li>pgvector nDCG@5 is within 0.02 of the in-memory baseline.</li>
     * </ol>
     *
     * <p>The {@code efSearch} value used for pgvector is {@value #EF_SEARCH}.
     * That value achieved parity on this synthetic corpus; do NOT increase the
     * 0.02 gate if parity fails — escalate instead.</p>
     */
    @Nested
    @Testcontainers( disabledWithoutDocker = true )
    class ParityGate {

        /** efSearch = 100 was sufficient for parity on the 3-page synthetic corpus. */
        private static final int EF_SEARCH = 100;

        private static final String MODEL_CODE = "parity-test-model";
        private static final int DIM = 1024; // must match PgVectorChunkVectorIndex.EMBEDDING_DIM

        /**
         * Three synthetic pages with orthogonal "hot-spike" embeddings.
         * Query vector for page i is the same vector stored for that page,
         * so cosine similarity = 1.0 for the matching page and ≈ 1/sqrt(DIM)
         * for all others — guaranteed top-1 recall on any reasonable ANN index.
         */
        private static final String PAGE_CITE   = "PG_Parity_PageCite";
        private static final String PAGE_MCP    = "PG_Parity_PageMcp";
        private static final String PAGE_DEPLOY = "PG_Parity_PageDeploy";

        private static final String ID_CITE   = "PG01CITE";
        private static final String ID_MCP    = "PG01MCP";
        private static final String ID_DEPLOY = "PG01DEPLOY";

        private static final String QUERY_SET_ID = "parity-smoke";

        /** Query vectors: pre-computed unit vectors, one per expected page. */
        private static final float[] QUERY_CITE   = spikeVector( 0 );
        private static final float[] QUERY_MCP    = spikeVector( 1 );
        private static final float[] QUERY_DEPLOY = spikeVector( 2 );

        private DataSource pgDs;

        @BeforeEach
        void setUp() throws Exception {
            pgDs = PostgresTestContainer.createDataSource();
            applyV032Migration();
            cleanTestRows();
            seedCorpus();
            seedQuerySet();
        }

        @AfterEach
        void tearDown() throws Exception {
            cleanTestRows();
            cleanQuerySet();
        }

        @ParameterizedTest
        @ValueSource( strings = { "inmemory", "pgvector", "lucene-hnsw" } )
        void core_agent_queries_meet_ndcg_threshold( final String backend ) {
            final DefaultRetrievalQualityRunner runner = buildRunnerFor( backend );
            final RetrievalRunResult report = runner.runNow( QUERY_SET_ID, RetrievalMode.HYBRID );
            assertTrue( report.ndcgAt5() >= 0.5,
                backend + " nDCG@5 = " + report.ndcgAt5() + " < smoke gate 0.5" );
        }

        @Test
        void inmemory_and_pgvector_within_recall_epsilon() {
            final RetrievalRunResult memReport =
                buildRunnerFor( "inmemory" ).runNow( QUERY_SET_ID, RetrievalMode.HYBRID );
            final RetrievalRunResult pgvReport =
                buildRunnerFor( "pgvector" ).runNow( QUERY_SET_ID, RetrievalMode.HYBRID );

            final double delta = Math.abs( memReport.ndcgAt5() - pgvReport.ndcgAt5() );
            assertTrue( delta <= 0.02,
                "pgvector nDCG@5 (" + pgvReport.ndcgAt5() + ") differs from in-memory ("
                + memReport.ndcgAt5() + ") by " + delta + " — exceeds 0.02 parity gate. "
                + "STOP and escalate before bumping the threshold." );
        }

        @Test
        void inmemory_and_lucene_hnsw_within_recall_epsilon() {
            final RetrievalRunResult memReport =
                buildRunnerFor( "inmemory" ).runNow( QUERY_SET_ID, RetrievalMode.HYBRID );
            final RetrievalRunResult hnswReport =
                buildRunnerFor( "lucene-hnsw" ).runNow( QUERY_SET_ID, RetrievalMode.HYBRID );

            final double delta = Math.abs( memReport.ndcgAt5() - hnswReport.ndcgAt5() );
            assertTrue( delta <= 0.02,
                "lucene-hnsw nDCG@5 (" + hnswReport.ndcgAt5() + ") differs from in-memory ("
                + memReport.ndcgAt5() + ") by " + delta + " — exceeds 0.02 parity gate. "
                + "STOP and escalate before bumping the threshold." );
        }

        // ---- helpers ----

        /**
         * Build a {@link DefaultRetrievalQualityRunner} wired to the requested backend.
         *
         * <p>The retriever bypasses the {@code QueryEmbedder} entirely: for each
         * query text it looks up a pre-computed deterministic query vector, calls
         * {@link ChunkVectorIndex#topKChunks}, aggregates scores to page level via
         * {@link PageAggregator}, and returns the ordered page names. This isolates
         * the test from any network embedding service while still exercising the real
         * {@link ChunkVectorIndex} implementation for each backend.</p>
         */
        private DefaultRetrievalQualityRunner buildRunnerFor( final String backend ) {
            final ChunkVectorIndex index;
            if ( "pgvector".equals( backend ) ) {
                index = new PgVectorChunkVectorIndex( pgDs, MODEL_CODE, EF_SEARCH );
            } else if ( "lucene-hnsw".equals( backend ) ) {
                index = new LuceneHnswChunkVectorIndex( pgDs, MODEL_CODE, DIM, new HnswParams( 16, 64, EF_SEARCH ) );
            } else {
                index = new InMemoryChunkVectorIndex( pgDs, MODEL_CODE );
            }

            final PageAggregator aggregator = new PageAggregator();

            // Map query text → deterministic query vector (no network call needed).
            final DefaultRetrievalQualityRunner.Retriever retriever = ( mode, queryText ) -> {
                final float[] queryVec;
                if ( queryText.contains( "cite" ) )   queryVec = QUERY_CITE;
                else if ( queryText.contains( "MCP" ) ) queryVec = QUERY_MCP;
                else if ( queryText.contains( "deploy" ) ) queryVec = QUERY_DEPLOY;
                else return List.of();

                final List< ScoredPage > pages =
                    aggregator.aggregate( index.topKChunks( queryVec, 10 ), PageAggregation.MAX );
                final List< String > names = new java.util.ArrayList<>( pages.size() );
                for ( final ScoredPage p : pages ) {
                    names.add( p.pageName() );
                }
                return names;
            };

            // Slug → canonical_id: the seeded pages map to their IDs.
            final Map< String, String > slugToId = Map.of(
                PAGE_CITE,   ID_CITE,
                PAGE_MCP,    ID_MCP,
                PAGE_DEPLOY, ID_DEPLOY
            );

            return new DefaultRetrievalQualityRunner(
                new RetrievalQualityDao( pgDs ),
                retriever,
                slug -> Optional.ofNullable( slugToId.get( slug ) ),
                new RetrievalQualityMetrics() );
        }

        /**
         * Apply the V032 DDL that adds the {@code embedding vector(1024)} column
         * and the HNSW index used by {@link PgVectorChunkVectorIndex}.
         * Idempotent — uses {@code IF NOT EXISTS} / {@code ADD COLUMN IF NOT EXISTS}.
         */
        private void applyV032Migration() throws Exception {
            try ( Connection conn = pgDs.getConnection();
                  Statement st = conn.createStatement() ) {
                st.execute(
                    "ALTER TABLE content_chunk_embeddings "
                  + "ADD COLUMN IF NOT EXISTS embedding vector(1024)" );
                st.execute(
                    "CREATE INDEX IF NOT EXISTS content_chunk_embeddings_hnsw_idx "
                  + "ON content_chunk_embeddings "
                  + "USING hnsw (embedding vector_cosine_ops) "
                  + "WITH (m = 16, ef_construction = 64)" );
            }
        }

        /**
         * Seed three pages into {@code kg_content_chunks} and their embeddings
         * into {@code content_chunk_embeddings}. Each page gets a single chunk
         * whose vector is a unit spike on a distinct dimension — orthogonal to the
         * other two, so rank-1 retrieval is deterministic.
         *
         * <p>Both the legacy {@code vec} (BYTEA) column and the new {@code embedding}
         * (pgvector) column are populated so both backends can read the same corpus.</p>
         */
        private void seedCorpus() throws Exception {
            seedPage( PAGE_CITE,   0, spikeVector( 0 ) );
            seedPage( PAGE_MCP,    0, spikeVector( 1 ) );
            seedPage( PAGE_DEPLOY, 0, spikeVector( 2 ) );
        }

        private void seedPage( final String pageName,
                                final int chunkIndex,
                                final float[] vec ) throws Exception {
            try ( Connection conn = pgDs.getConnection() ) {
                final UUID chunkId;
                final String insertChunk =
                    "INSERT INTO kg_content_chunks "
                  + "(page_name, chunk_index, text, char_count, token_count_estimate, content_hash) "
                  + "VALUES (?, ?, ?, ?, ?, ?) RETURNING id";
                try ( PreparedStatement ps = conn.prepareStatement( insertChunk ) ) {
                    ps.setString( 1, pageName );
                    ps.setInt( 2, chunkIndex );
                    ps.setString( 3, "Parity test content for " + pageName );
                    ps.setInt( 4, 40 );
                    ps.setInt( 5, 10 );
                    ps.setString( 6, "parity-hash-" + pageName + "-" + chunkIndex );
                    try ( ResultSet rs = ps.executeQuery() ) {
                        rs.next();
                        chunkId = rs.getObject( 1, UUID.class );
                    }
                }

                // Insert embedding: populate both 'vec' (BYTEA) for InMemoryChunkVectorIndex
                // and 'embedding' (pgvector) for PgVectorChunkVectorIndex.
                final String insertEmb =
                    "INSERT INTO content_chunk_embeddings "
                  + "(chunk_id, model_code, dim, vec, embedding) "
                  + "VALUES (?, ?, ?, ?, ?::vector)";
                try ( PreparedStatement ps = conn.prepareStatement( insertEmb ) ) {
                    ps.setObject( 1, chunkId );
                    ps.setString( 2, MODEL_CODE );
                    ps.setInt( 3, DIM );
                    ps.setBytes( 4, encodeVec( vec ) );
                    ps.setString( 5, PgVectorChunkVectorIndex.formatVector( vec ) );
                    ps.executeUpdate();
                }
            }
        }

        /** Seed the retrieval query set and its three queries into the PG container. */
        private void seedQuerySet() throws Exception {
            try ( Connection conn = pgDs.getConnection();
                  Statement st = conn.createStatement() ) {
                // Create tables if absent (the container already has them from the init script
                // only if V016 is included — apply defensively here).
                st.execute( """
                    CREATE TABLE IF NOT EXISTS retrieval_query_sets (
                        id VARCHAR(64) PRIMARY KEY,
                        name VARCHAR(128) NOT NULL,
                        description TEXT,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""" );
                st.execute( """
                    CREATE TABLE IF NOT EXISTS retrieval_queries (
                        query_set_id VARCHAR(64) NOT NULL REFERENCES retrieval_query_sets(id),
                        query_id     VARCHAR(64) NOT NULL,
                        query_text   TEXT NOT NULL,
                        expected_ids VARCHAR(64)[] NOT NULL,
                        PRIMARY KEY (query_set_id, query_id)
                    )""" );
                st.execute( """
                    CREATE TABLE IF NOT EXISTS retrieval_runs (
                        run_id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                        query_set_id VARCHAR(64) NOT NULL REFERENCES retrieval_query_sets(id),
                        started_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        finished_at  TIMESTAMP WITH TIME ZONE,
                        mode         VARCHAR(32) NOT NULL,
                        ndcg_at_5    NUMERIC(5,4),
                        ndcg_at_10   NUMERIC(5,4),
                        recall_at_20 NUMERIC(5,4),
                        mrr          NUMERIC(5,4),
                        notes        TEXT
                    )""" );

                st.execute( "INSERT INTO retrieval_query_sets (id, name) "
                    + "VALUES ('" + QUERY_SET_ID + "', 'Parity Smoke') "
                    + "ON CONFLICT (id) DO NOTHING" );
            }
            try ( Connection conn = pgDs.getConnection() ) {
                insertQuery( conn, "pq1", "cite a wiki page",       new String[]{ ID_CITE } );
                insertQuery( conn, "pq2", "write an MCP tool",      new String[]{ ID_MCP } );
                insertQuery( conn, "pq3", "build and deploy locally", new String[]{ ID_DEPLOY } );
            }
        }

        private void insertQuery( final Connection conn,
                                   final String queryId,
                                   final String queryText,
                                   final String[] expectedIds ) throws Exception {
            final String sql =
                "INSERT INTO retrieval_queries (query_set_id, query_id, query_text, expected_ids) "
              + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING";
            try ( PreparedStatement ps = conn.prepareStatement( sql ) ) {
                ps.setString( 1, QUERY_SET_ID );
                ps.setString( 2, queryId );
                ps.setString( 3, queryText );
                ps.setArray( 4, conn.createArrayOf( "varchar", expectedIds ) );
                ps.executeUpdate();
            }
        }

        private void cleanTestRows() throws Exception {
            try ( Connection conn = pgDs.getConnection();
                  Statement st = conn.createStatement() ) {
                st.execute( "DELETE FROM kg_content_chunks WHERE page_name LIKE 'PG_Parity_%'" );
            }
        }

        private void cleanQuerySet() throws Exception {
            try ( Connection conn = pgDs.getConnection();
                  Statement st = conn.createStatement() ) {
                st.execute( "DELETE FROM retrieval_runs WHERE query_set_id = '" + QUERY_SET_ID + "'" );
                st.execute( "DELETE FROM retrieval_queries WHERE query_set_id = '" + QUERY_SET_ID + "'" );
                st.execute( "DELETE FROM retrieval_query_sets WHERE id = '" + QUERY_SET_ID + "'" );
            }
        }

        /**
         * Build a unit-length 1024-dim vector with a large spike at dimension {@code hotDim}
         * and uniform small values everywhere else. The spike dominates the dot product,
         * so the matching query (same vector) scores near 1.0 while all other stored
         * vectors score near {@code 1/sqrt(DIM)} — rank-1 retrieval is deterministic.
         */
        private static float[] spikeVector( final int hotDim ) {
            final float[] v = new float[ DIM ];
            Arrays.fill( v, 0.01f );
            v[ hotDim ] = 10.0f; // dominant spike
            // L2-normalize so cosine similarity = dot product.
            double sumSq = 0.0;
            for ( final float f : v ) sumSq += (double) f * (double) f;
            final double inv = 1.0 / Math.sqrt( sumSq );
            for ( int i = 0; i < v.length; i++ ) v[ i ] = (float) ( v[ i ] * inv );
            return v;
        }

        /**
         * Encode a float[] as Little-Endian BYTEA matching the format
         * {@link InMemoryChunkVectorIndex} expects in the {@code vec} column.
         */
        private static byte[] encodeVec( final float[] vec ) {
            final ByteBuffer buf =
                ByteBuffer.allocate( vec.length * Float.BYTES ).order( ByteOrder.LITTLE_ENDIAN );
            for ( final float f : vec ) buf.putFloat( f );
            return buf.array();
        }
    }
}

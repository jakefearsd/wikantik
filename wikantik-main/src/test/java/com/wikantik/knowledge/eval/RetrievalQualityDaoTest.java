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

import com.wikantik.api.eval.RetrievalMode;
import com.wikantik.api.eval.RetrievalRunResult;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class RetrievalQualityDaoTest {

    private DataSource ds;
    private RetrievalQualityDao dao;

    @BeforeEach
    void setUp() throws Exception {
        this.ds = PostgresTestDb.createDataSource();
        // retrieval_runs/retrieval_queries carry FKs to retrieval_query_sets; CASCADE
        // handles the delete order regardless of the list order given here. The V017
        // seed set (`core-agent-queries`) is untouched — this test's fixture id ('core')
        // never collides with it.
        PostgresTestDb.truncate( "retrieval_runs", "retrieval_queries", "retrieval_query_sets" );
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "INSERT INTO retrieval_query_sets (id, name) VALUES ('core', 'Core')" );
            s.executeUpdate( "INSERT INTO retrieval_queries (query_set_id, query_id, query_text, expected_ids) "
                + "VALUES ('core', 'q1', 'how do I cite a wiki page', ARRAY['01ABC', '01DEF'])" );
            s.executeUpdate( "INSERT INTO retrieval_queries (query_set_id, query_id, query_text, expected_ids) "
                + "VALUES ('core', 'q2', 'how do I write an MCP tool', ARRAY['01XYZ'])" );
        }
        this.dao = new RetrievalQualityDao( ds );
    }

    @Test
    void querySetExists_trueForKnown_falseForUnknown() {
        assertTrue( dao.querySetExists( "core" ) );
        assertFalse( dao.querySetExists( "no-such-set" ) );
    }

    @Test
    void loadQueries_returnsBothSeedRowsInOrder() {
        final List< RetrievalQualityDao.EvalQuery > qs = dao.loadQueries( "core" );
        assertEquals( 2, qs.size() );
        assertEquals( "q1", qs.get( 0 ).queryId() );
        assertEquals( "how do I cite a wiki page", qs.get( 0 ).queryText() );
        assertEquals( Set.of( "01ABC", "01DEF" ), qs.get( 0 ).expectedIds() );
        assertEquals( "q2", qs.get( 1 ).queryId() );
        assertEquals( Set.of( "01XYZ" ), qs.get( 1 ).expectedIds() );
    }

    @Test
    void loadQueries_unknownSetReturnsEmpty() {
        assertTrue( dao.loadQueries( "no-such-set" ).isEmpty() );
    }

    @Test
    void insertRun_returnsGeneratedRunId() {
        final RetrievalRunResult r = new RetrievalRunResult(
            0L, "core", RetrievalMode.HYBRID,
            0.85, 0.83, 0.92, 0.74,
            Instant.parse( "2026-04-25T03:00:00Z" ),
            Instant.parse( "2026-04-25T03:01:00Z" ),
            16, 0, false );
        final long id = dao.insertRun( r );
        assertNotEquals( 0L, id );
    }

    @Test
    void insertRun_thenRecentRuns_roundTripsAggregateMetrics() {
        final Instant start = Instant.parse( "2026-04-25T03:00:00Z" );
        final long id = dao.insertRun( new RetrievalRunResult(
            0L, "core", RetrievalMode.HYBRID,
            0.85, 0.83, 0.92, 0.74,
            start, start.plusSeconds( 60 ),
            16, 0, false ) );
        final List< RetrievalRunResult > rows = dao.recentRuns( "core", null, 10 );
        assertEquals( 1, rows.size() );
        final RetrievalRunResult loaded = rows.get( 0 );
        assertEquals( id, loaded.runId() );
        assertEquals( "core", loaded.querySetId() );
        assertEquals( RetrievalMode.HYBRID, loaded.mode() );
        assertEquals( 0.85, loaded.ndcgAt5() );
        assertEquals( 0.92, loaded.recallAt20() );
        assertNotNull( loaded.finishedAt() );
    }

    @Test
    void insertRun_acceptsNullMetrics() {
        final long id = dao.insertRun( new RetrievalRunResult(
            0L, "core", RetrievalMode.BM25,
            null, null, null, null,
            Instant.now(), null, 0, 2, true ) );
        assertNotEquals( 0L, id );
        final RetrievalRunResult loaded = dao.recentRuns( "core", RetrievalMode.BM25, 1 ).get( 0 );
        assertNull( loaded.ndcgAt5() );
        assertNull( loaded.ndcgAt10() );
        assertNull( loaded.recallAt20() );
        assertNull( loaded.mrr() );
        assertNull( loaded.finishedAt() );
    }

    @Test
    void recentRuns_filterByModeReturnsOnlyMatchingRows() {
        final Instant t = Instant.now();
        dao.insertRun( new RetrievalRunResult( 0L, "core", RetrievalMode.BM25,   0.5, 0.5, 0.5, 0.5, t, t, 1, 0, false ) );
        dao.insertRun( new RetrievalRunResult( 0L, "core", RetrievalMode.HYBRID, 0.7, 0.7, 0.7, 0.7, t, t, 1, 0, false ) );
        dao.insertRun( new RetrievalRunResult( 0L, "core", RetrievalMode.HYBRID_GRAPH, 0.8, 0.8, 0.8, 0.8, t, t, 1, 0, false ) );
        final List< RetrievalRunResult > hybrids = dao.recentRuns( "core", RetrievalMode.HYBRID, 10 );
        assertEquals( 1, hybrids.size() );
        assertEquals( RetrievalMode.HYBRID, hybrids.get( 0 ).mode() );
    }

    @Test
    void recentRuns_unfilteredReturnsAll() {
        final Instant t = Instant.now();
        for ( int i = 0; i < 3; i++ ) {
            dao.insertRun( new RetrievalRunResult( 0L, "core", RetrievalMode.HYBRID,
                0.5, 0.5, 0.5, 0.5, t, t, 1, 0, false ) );
        }
        assertEquals( 3, dao.recentRuns( null, null, 100 ).size() );
    }

    @Test
    void recentRuns_clampsLimit() {
        // Limit clamped low (0 -> 1) — only one row even if many were inserted.
        final Instant t = Instant.now();
        for ( int i = 0; i < 3; i++ ) {
            dao.insertRun( new RetrievalRunResult( 0L, "core", RetrievalMode.HYBRID,
                0.5, 0.5, 0.5, 0.5, t, t, 1, 0, false ) );
        }
        assertEquals( 1, dao.recentRuns( "core", null, 0 ).size() );
    }
}

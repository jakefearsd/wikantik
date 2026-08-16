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
package com.wikantik.insights;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * H2-backed unit tests for {@link JdbcInsightsStore}, restricted to behavior H2 can actually
 * exercise: the no-op empty-batch path, the fail-soft path when the table is missing, and the
 * {@link VisibilityRow} allowlist guard.
 *
 * <p>H2 2.4.240 (even in {@code MODE=PostgreSQL}) does not implement {@code ON CONFLICT}, which
 * {@link JdbcInsightsStore}'s upsert relies on. Testing convergence/upsert semantics here would
 * prove nothing — a query that never reaches the conflict clause can't demonstrate that the
 * clause works. Those tests live in {@code JdbcInsightsStorePostgresTest} instead, run against a
 * real PostgreSQL container so the actual {@code ON CONFLICT ... DO UPDATE} SQL is exercised.</p>
 */
class JdbcInsightsStoreTest {

    private DataSource ds;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:svs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DROP TABLE IF EXISTS search_visibility_snapshot" );
            st.execute( """
                CREATE TABLE search_visibility_snapshot (
                    snapshot_date DATE NOT NULL,
                    window_days SMALLINT NOT NULL,
                    engine VARCHAR NOT NULL,
                    site_host VARCHAR NOT NULL,
                    page_path VARCHAR NOT NULL,
                    query_text VARCHAR NOT NULL,
                    impressions INTEGER NOT NULL,
                    clicks INTEGER NOT NULL,
                    position NUMERIC(6,2),
                    ingested_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
                    PRIMARY KEY (snapshot_date, engine, site_host, page_path, query_text))""" );
        }
    }

    private VisibilityRow row( final int impressions, final int clicks, final Double position ) {
        return new VisibilityRow( LocalDate.of( 2026, 8, 14 ), 28, "bing",
                "wiki.wikantik.com", "/wiki/PhilosophyHub", "", impressions, clicks, position );
    }

    @Test
    void emptyBatchIsANoOp() {
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertEquals( 0, store.upsert( List.of() ) );
    }

    @Test
    void upsertFailsSoftWhenTheTableIsMissing() throws Exception {
        // Ingestion must never throw into the request path; a broken store degrades to 0 rows.
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DROP TABLE search_visibility_snapshot" );
        }
        final InsightsStore store = new JdbcInsightsStore( ds );
        assertEquals( 0, store.upsert( List.of( row( 1, 1, 1.0 ) ) ) );
    }

    @Test
    void rowRejectsBlankEngineAndSite() {
        // Guard the allowlist contract at the type boundary rather than only at parse time.
        assertNull( VisibilityRow.of( LocalDate.of( 2026, 8, 14 ), 28, "  ", "wiki.wikantik.com",
                "/wiki/A", "", 1, 0, 1.0 ) );
        assertNull( VisibilityRow.of( LocalDate.of( 2026, 8, 14 ), 28, "bing", "",
                "/wiki/A", "", 1, 0, 1.0 ) );
    }
}

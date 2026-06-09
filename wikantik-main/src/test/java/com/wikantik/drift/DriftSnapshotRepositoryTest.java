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
package com.wikantik.drift;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DriftSnapshotRepositoryTest {

    private DataSource ds;
    private DriftSnapshotRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:drift" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        this.ds = h2;
        try ( Connection c = ds.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( """
                CREATE TABLE drift_sweeps (
                    id            BIGSERIAL PRIMARY KEY,
                    swept_at      TIMESTAMP WITH TIME ZONE NOT NULL,
                    pages_scanned INT     NOT NULL,
                    duration_ms   BIGINT  NOT NULL,
                    triggered_by  TEXT    NOT NULL,
                    shacl_checked BOOLEAN NOT NULL DEFAULT TRUE
                )""" );
            s.executeUpdate( """
                CREATE TABLE drift_snapshot_counts (
                    sweep_id  BIGINT NOT NULL REFERENCES drift_sweeps(id) ON DELETE CASCADE,
                    family    TEXT   NOT NULL,
                    code      TEXT   NOT NULL,
                    severity  TEXT   NOT NULL,
                    "count"   INT    NOT NULL,
                    PRIMARY KEY (sweep_id, family, code, severity)
                )""" );
        }
        this.repo = new DriftSnapshotRepository( ds );
    }

    @Test
    void insertAndReadBackLatest() {
        final long id = repo.insertSweep( Instant.now(), 42, 1234L, "manual", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 7 ),
                         new DriftCount( "shacl", "implements", "ERROR", 2 ) ) );
        assertTrue( id > 0 );

        final Optional< DriftSweepRecord > latest = repo.latest();
        assertTrue( latest.isPresent() );
        assertEquals( 42, latest.get().pagesScanned() );
        assertEquals( "manual", latest.get().triggeredBy() );
        assertTrue( latest.get().shaclChecked() );
        assertEquals( 2, latest.get().counts().size() );
        assertTrue( latest.get().counts().contains(
                new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 7 ) ) );
    }

    @Test
    void latestIsEmptyBeforeFirstSweep() {
        assertTrue( repo.latest().isEmpty() );
    }

    @Test
    void previousBeforeReturnsTheSweepJustBefore() {
        final long first = repo.insertSweep( Instant.now().minus( 1, ChronoUnit.DAYS ), 10, 1L,
                "scheduled", true, List.of( new DriftCount( "frontmatter", "x", "WARNING", 5 ) ) );
        final long second = repo.insertSweep( Instant.now(), 11, 1L,
                "scheduled", true, List.of( new DriftCount( "frontmatter", "x", "WARNING", 3 ) ) );

        final Optional< DriftSweepRecord > prev = repo.previousBefore( second );
        assertTrue( prev.isPresent() );
        assertEquals( first, prev.get().id() );
        assertEquals( 5, prev.get().counts().get( 0 ).count() );
        assertTrue( repo.previousBefore( first ).isEmpty() );
    }

    @Test
    void trendReturnsWindowAscending() {
        repo.insertSweep( Instant.now().minus( 40, ChronoUnit.DAYS ), 1, 1L, "scheduled", true, List.of() );
        final long recentOld = repo.insertSweep( Instant.now().minus( 5, ChronoUnit.DAYS ), 2, 1L,
                "scheduled", true, List.of() );
        final long newest = repo.insertSweep( Instant.now(), 3, 1L, "manual", false, List.of() );

        final List< DriftSweepRecord > trend = repo.trend( 30 );
        assertEquals( 2, trend.size() );
        assertEquals( recentOld, trend.get( 0 ).id() );
        assertEquals( newest, trend.get( 1 ).id() );
        assertFalse( trend.get( 1 ).shaclChecked() );
    }

    @Test
    void emptyCountListPersistsSweepRowOnly() {
        final long id = repo.insertSweep( Instant.now(), 0, 0L, "manual", false, List.of() );
        assertEquals( id, repo.latest().orElseThrow().id() );
        assertTrue( repo.latest().orElseThrow().counts().isEmpty() );
    }
}

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

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@RequiresPostgres
class DriftSnapshotRepositoryTest {

    private DriftSnapshotRepository repo;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "drift_snapshot_counts", "drift_sweeps" );
        final DataSource ds = PostgresTestDb.createDataSource();
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

    @Test
    void countsAreOrderedByFamilyCodeSeverity() {
        repo.insertSweep( Instant.now(), 1, 1L, "manual", true, List.of(
                new DriftCount( "shacl", "wk:implements", "ERROR", 1 ),
                new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 2 ),
                new DriftCount( "frontmatter", "date.format", "WARNING", 3 ) ) );

        final List< DriftCount > counts = repo.latest().orElseThrow().counts();
        assertEquals( "date.format", counts.get( 0 ).code() );
        assertEquals( "status.noncanonical", counts.get( 1 ).code() );
        assertEquals( "wk:implements", counts.get( 2 ).code() );
    }

    @Test
    void trend_loadsCountsForAllSweepsInOneStatementAndKeepsThemPerSweep() {
        final long[] ids = new long[ 5 ];
        for ( int i = 0; i < ids.length; i++ ) {
            ids[ i ] = repo.insertSweep( Instant.now().minus( 5 - i, ChronoUnit.HOURS ), 10 + i, 1L, "scheduled", true,
                    List.of( new DriftCount( "frontmatter", "b", "WARNING", i ),
                             new DriftCount( "frontmatter", "a", "ERROR", i + 100 ) ) );
        }
        final java.util.concurrent.atomic.AtomicInteger statements = new java.util.concurrent.atomic.AtomicInteger();
        final DataSource real = PostgresTestDb.createDataSource();
        final DataSource counting = ( DataSource ) java.lang.reflect.Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class< ? >[] { DataSource.class }, ( proxy, m, a ) -> {
                    try {
                        final Object r = m.invoke( real, a );
                        if ( r instanceof java.sql.Connection c ) {
                            return java.lang.reflect.Proxy.newProxyInstance( c.getClass().getClassLoader(),
                                    new Class< ? >[] { java.sql.Connection.class }, ( p2, m2, a2 ) -> {
                                        if ( m2.getName().equals( "prepareStatement" ) ) statements.incrementAndGet();
                                        try {
                                            return m2.invoke( c, a2 );
                                        } catch ( final java.lang.reflect.InvocationTargetException e ) {
                                            throw e.getCause();
                                        }
                                    } );
                        }
                        return r;
                    } catch ( final java.lang.reflect.InvocationTargetException e ) {
                        throw e.getCause();
                    }
                } );

        final List< DriftSweepRecord > trend = new DriftSnapshotRepository( counting ).trend( 7 );

        assertEquals( 5, trend.size() );
        assertEquals( 2, statements.get(), "one statement for the sweeps plus one for all their counts" );
        for ( int i = 0; i < ids.length; i++ ) {
            assertEquals( ids[ i ], trend.get( i ).id() );
            assertEquals( List.of( new DriftCount( "frontmatter", "a", "ERROR", i + 100 ),
                                   new DriftCount( "frontmatter", "b", "WARNING", i ) ), trend.get( i ).counts() );
        }
    }
}

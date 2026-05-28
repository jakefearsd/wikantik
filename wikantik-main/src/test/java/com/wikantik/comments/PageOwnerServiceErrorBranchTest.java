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
package com.wikantik.comments;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Drives every {@link SQLException} catch path in {@link PageOwnerService} by
 * supplying a {@link DataSource} whose {@link DataSource#getConnection()} always
 * throws. Read methods swallow + log + return defensive defaults; write methods
 * wrap and rethrow as {@link RuntimeException}.
 *
 * <p>Coverage rationale: the equivalent of {@link CommentStoreErrorBranchTest}
 * for {@link PageOwnerService} — without these tests, the JaCoCo line coverage
 * sits at ~78.6%, well below the project's 90% target, even though the success
 * paths are all exercised in {@link PageOwnerServiceTest}.
 */
class PageOwnerServiceErrorBranchTest {

    private static DataSource failingDataSource() throws SQLException {
        final DataSource ds = mock( DataSource.class );
        when( ds.getConnection() ).thenThrow( new SQLException( "boom" ) );
        return ds;
    }

    private static PageOwnerService svcWithFailingDs() throws SQLException {
        return new PageOwnerService( failingDataSource(), s -> true, cid -> Optional.empty() );
    }

    /* ---- read methods: swallow + return defensive default ---- */

    @Test
    void findRaw_returns_empty_on_sql_failure() throws SQLException {
        assertTrue( svcWithFailingDs().findRaw( "CID-X" ).isEmpty() );
    }

    @Test
    void listOrphaned_returns_empty_list_on_sql_failure() throws SQLException {
        assertTrue( svcWithFailingDs().listOrphaned( 10, 0 ).isEmpty() );
    }

    @Test
    void listByOwner_returns_empty_list_on_sql_failure() throws SQLException {
        assertTrue( svcWithFailingDs().listByOwner( "alice", 10, 0 ).isEmpty() );
    }

    @Test
    void countOrphaned_returns_zero_on_sql_failure() throws SQLException {
        assertEquals( 0, svcWithFailingDs().countOrphaned() );
    }

    @Test
    void countByOwner_returns_zero_on_sql_failure() throws SQLException {
        assertEquals( 0, svcWithFailingDs().countByOwner( "alice" ) );
    }

    @Test
    void getOwner_falls_back_to_admin_when_bootstrap_insert_fails() throws SQLException {
        // findRaw fails → returns empty → bootstrap path → insertRow fails (also swallowed) →
        // resolveWithFallback applied to the would-be initial owner (null because resolver is empty).
        final PageOwnerService svc = new PageOwnerService(
                failingDataSource(), s -> true, cid -> Optional.empty() );
        assertEquals( PageOwnerService.ADMIN_FALLBACK, svc.getOwner( "CID-X" ) );
    }

    /* ---- write methods: wrap + rethrow ---- */

    @Test
    void setOwner_wraps_sql_failure_in_runtime_exception() throws SQLException {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> svcWithFailingDs().setOwner( "CID-X", "alice", "admin" ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void bulkReassign_wraps_sql_failure_in_runtime_exception() throws SQLException {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> svcWithFailingDs().bulkReassign( "alice", "bob", "admin" ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void reassignFromOrphaned_wraps_sql_failure_in_runtime_exception() throws SQLException {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> svcWithFailingDs().reassignFromOrphaned( "bob", "admin" ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void orphanByOwner_wraps_sql_failure_in_runtime_exception() throws SQLException {
        final RuntimeException ex = assertThrows( RuntimeException.class,
                () -> svcWithFailingDs().orphanByOwner( "alice", "admin" ) );
        assertTrue( ex.getCause() instanceof SQLException );
    }

    @Test
    void readRow_translates_null_timestamp_to_null_instant() throws Exception {
        // Cover the `at == null ? null : at.toInstant()` branch in readRow via the
        // listOrphaned success path: an H2 row with NULL assigned_at must yield a
        // PageOwnership whose assignedAt() is null. Use real H2 here — the failing
        // datasource above cannot exercise success paths.
        final org.h2.jdbcx.JdbcDataSource h2 = new org.h2.jdbcx.JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:nullts;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" );
        try ( java.sql.Connection c = h2.getConnection();
              java.sql.Statement s = c.createStatement() ) {
            // assigned_at made nullable for this test only.
            s.executeUpdate( """
                CREATE TABLE page_owners (
                    canonical_id TEXT PRIMARY KEY,
                    owner_login  TEXT,
                    assigned_by  TEXT NOT NULL,
                    assigned_at  TIMESTAMP WITH TIME ZONE
                )""" );
            s.executeUpdate( "INSERT INTO page_owners (canonical_id, owner_login, assigned_by) " +
                    "VALUES ('CID-NULL-TS', NULL, 'admin')" );
        }
        final PageOwnerService svc = new PageOwnerService( h2, s -> true, cid -> Optional.empty() );
        final var orphans = svc.listOrphaned( 10, 0 );
        assertEquals( 1, orphans.size() );
        assertNull( orphans.get( 0 ).assignedAt(), "NULL assigned_at must translate to null Instant" );
    }
}

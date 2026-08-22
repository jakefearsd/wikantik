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
package com.wikantik.audit;

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link JdbcAuditRepository#append} rolls back completely — including the partition DDL —
 * when an unchecked exception interrupts the transaction mid-flight. {@code append()}'s hand-rolled
 * transaction used to catch only {@link SQLException} around its {@code rollback()}: an unchecked
 * failure skipped that catch, fell straight to {@code finally { c.setAutoCommit(true) }}, and — per
 * the JDBC contract — that auto-commit flip <em>commits</em> whatever had already been written in
 * the transaction instead of discarding it.
 *
 * <p>The migrated schema always has the current month's partition pre-created (V036), so the common
 * append path never exercises {@code ensurePartition}'s DDL branch. This test forces it: the
 * partition is dropped first, so {@code ensurePartition} must actually run its
 * table-creation {@code ... PARTITION OF} DDL — the transaction's first write. The fault is then injected
 * on the very next statement (the row INSERT), simulating an unchecked failure <em>after</em> that
 * first write has already executed inside the (still open) transaction — the DDL persisting despite
 * the overall {@code append()} failing is the discriminator that fails before the fix and passes
 * after it; the row-level assertions below hold either way because the row insert itself is a
 * single batched statement (never partially visible regardless of the bug).
 */
@RequiresPostgres
class JdbcAuditRepositoryRollbackTest {

    private DataSource superuserDs;

    @BeforeEach
    void setUp() {
        superuserDs = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "audit_log" );
    }

    /** Current month's partition name — mirrors {@code ensurePartition}'s own computation. */
    private static String currentPartitionName() {
        final ZonedDateTime start = Instant.now().atZone( ZoneOffset.UTC )
                .withDayOfMonth( 1 ).toLocalDate().atStartOfDay( ZoneOffset.UTC );
        return String.format( "audit_log_%04d_%02d", start.getYear(), start.getMonthValue() );
    }

    /** Recreates the current month's partition exactly as {@code ensurePartition}/the migration would. */
    private static String currentPartitionCreateDdl() {
        final ZonedDateTime start = Instant.now().atZone( ZoneOffset.UTC )
                .withDayOfMonth( 1 ).toLocalDate().atStartOfDay( ZoneOffset.UTC );
        final ZonedDateTime end = start.plusMonths( 1 );
        return "CREATE TABLE IF NOT EXISTS " + currentPartitionName() + " PARTITION OF audit_log "
                + "FOR VALUES FROM ('" + start.toLocalDate() + "') TO ('" + end.toLocalDate() + "')";
    }

    private long currentChainSeq() throws SQLException {
        try ( Connection c = superuserDs.getConnection();
              Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( "SELECT COALESCE(MAX(seq), 0) FROM audit_log" ) ) {
            rs.next();
            return rs.getLong( 1 );
        }
    }

    private boolean partitionExists( final String name ) throws SQLException {
        try ( Connection c = superuserDs.getConnection();
              Statement st = c.createStatement();
              ResultSet rs = st.executeQuery( "SELECT to_regclass('" + name + "') IS NOT NULL" ) ) {
            rs.next();
            return rs.getBoolean( 1 );
        }
    }

    @Test
    void append_rolls_back_partition_ddl_and_insert_on_unchecked_mid_transaction_failure() throws Exception {
        final String partition = currentPartitionName();
        try ( Connection c = superuserDs.getConnection(); Statement st = c.createStatement() ) {
            st.execute( "DROP TABLE IF EXISTS " + partition );
        }

        final long seqBefore = currentChainSeq();
        try {
            // Statement order inside append() when the partition is missing: #1 lockChain's
            // advisory-lock SELECT, #2 ensurePartition's to_regclass existence check (false), #3
            // the table-creation ... PARTITION OF DDL (the first write), #4 chainHeadTx's SELECT, #5
            // the row INSERT. Fail on #5 — right after the DDL write has already executed.
            final FaultInjectingDataSource faultyDs = new FaultInjectingDataSource( superuserDs );
            faultyDs.failOn( 5, new RuntimeException( "boom: simulated mid-transaction failure" ) );
            final JdbcAuditRepository faultyRepo = new JdbcAuditRepository( faultyDs );

            final AuditEntry entry = AuditEntry.builder()
                    .eventTime( Instant.now() )
                    .category( AuditCategory.ADMIN )
                    .eventType( "rollback.test" )
                    .actorType( "TEST" )
                    .actorPrincipal( "tester" )
                    .outcome( AuditOutcome.SUCCESS )
                    .detail( "{\"src\":\"rollback-test\"}" )
                    .build();

            assertThrows( RuntimeException.class, () -> faultyRepo.append( List.of( entry ) ),
                    "the unchecked failure must propagate, not be swallowed" );

            try ( Connection c = superuserDs.getConnection();
                  Statement st = c.createStatement();
                  ResultSet rs = st.executeQuery(
                          "SELECT count(*) FROM audit_log WHERE event_type = 'rollback.test'" ) ) {
                rs.next();
                assertEquals( 0, rs.getInt( 1 ), "no row may be visible after the transaction rolls back" );
            }
            assertEquals( seqBefore, currentChainSeq(), "chain head must be unchanged after rollback" );

            // The discriminator: before the fix, the missing catch(Throwable) branch let
            // `finally { c.setAutoCommit(true) }` implicitly COMMIT the just-created partition even
            // though the overall append() failed. After the fix, an explicit rollback runs first.
            assertFalse( partitionExists( partition ),
                    "the partition DDL must have rolled back along with the failed insert" );
        } finally {
            // Restore the partition exactly as the migration/self-heal would, regardless of
            // outcome, so any other test against the shared PostgresTestDb container still finds
            // the current month's partition present.
            try ( Connection c = superuserDs.getConnection(); Statement st = c.createStatement() ) {
                st.execute( currentPartitionCreateDdl() );
            }
        }
    }
}

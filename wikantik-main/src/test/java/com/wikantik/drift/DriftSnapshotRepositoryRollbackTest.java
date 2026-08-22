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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proves {@link DriftSnapshotRepository#insertSweep} rolls back the {@code drift_sweeps} row when
 * the follow-up {@code drift_snapshot_counts} write fails with an <em>unchecked</em> exception.
 * The hand-rolled transaction this class used to have only caught {@link SQLException} around its
 * commit/rollback block, so an unchecked failure skipped {@code rollback()} entirely and fell
 * straight to {@code finally { conn.setAutoCommit(prevAutoCommit) } }, which — per the JDBC
 * contract — commits whatever was written before the failure instead of discarding it.
 */
@RequiresPostgres
class DriftSnapshotRepositoryRollbackTest {

    private FaultInjectingDataSource faultyDataSource;
    private DriftSnapshotRepository repo;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "drift_snapshot_counts", "drift_sweeps" );
        faultyDataSource = new FaultInjectingDataSource( PostgresTestDb.createDataSource() );
        repo = new DriftSnapshotRepository( faultyDataSource );
    }

    @Test
    void insertSweepLeavesNoPartialRowsWhenTheSecondInsertFailsUnchecked() {
        // Statement #1 = the drift_sweeps INSERT (succeeds); statement #2 = the
        // drift_snapshot_counts INSERT — fail preparing it with an unchecked exception, the
        // shape a validation bug or an invariant failure would actually throw.
        faultyDataSource.failOn( 2, new RuntimeException( "boom" ) );

        assertThrows( RuntimeException.class, () -> repo.insertSweep(
                Instant.now(), 1, 1L, "manual", true,
                List.of( new DriftCount( "frontmatter", "status.noncanonical", "WARNING", 1 ) ) ) );

        assertEquals( 0, countRows( "drift_sweeps" ), "drift_sweeps row must not survive the rolled-back transaction" );
        assertEquals( 0, countRows( "drift_snapshot_counts" ), "drift_snapshot_counts must stay empty too" );
    }

    private int countRows( final String table ) {
        try ( Connection conn = PostgresTestDb.createDataSource().getConnection();
              Statement st = conn.createStatement() ) {
            final var rs = st.executeQuery( "SELECT COUNT(*) FROM " + table );
            rs.next();
            return rs.getInt( 1 );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( "count failed for " + table, e );
        }
    }
}

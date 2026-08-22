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
package com.wikantik.citation;

import static org.junit.jupiter.api.Assertions.*;

import com.wikantik.api.citation.CitationStatus;
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link CitationRepository#replaceForSource} rolls back completely when an unchecked
 * exception interrupts the transaction mid-flight. Before the {@code Jdbc.inTransaction} fix,
 * {@code replaceForSource} caught only {@link java.sql.SQLException} around its hand-rolled
 * {@code rollback()}: an unchecked failure skipped that catch, fell straight into the
 * {@code finally { conn.setAutoCommit(prevAutoCommit) }}, and — per the JDBC contract — that
 * auto-commit flip silently committed whatever writes had already happened in the transaction.
 */
@RequiresPostgres
class CitationRepositoryRollbackTest {

    private DataSource ds;
    private CitationRepository repo;

    @BeforeEach
    void setUp() {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( "citations" );
        repo = new CitationRepository( ds );
    }

    private static CitationRow newRow( final String src, final String tgt, final String head,
                                       final String span, final CitationStatus st ) {
        final String hash = Spans.hash( Spans.normalize( span ) );
        return new CitationRow( 0, src, tgt, head, span, hash, "claim", 0, 7, st, null, null, null );
    }

    @Test
    void replaceForSourceRollsBackAllWritesOnUncheckedMidTransactionFailure() {
        // Seed one existing citation for "s1" targeting "t-old". Because the next replaceForSource
        // call below won't include "t-old" among its incoming rows, this identity lands in the
        // vanished-identity DELETE step — the third statement in the transaction.
        repo.replaceForSource( "s1", List.of( newRow( "s1", "t-old", "H", "old span", CitationStatus.CURRENT ) ) );
        assertEquals( 1, repo.findBySource( "s1" ).size(), "seed row must exist before the fault-injected call" );

        // Statement #1 inside replaceForSource is the SELECT (loadExisting); #2 is the INSERT of
        // the new "t-new" identity (the first *write*); #3 is the DELETE of the vanished "t-old"
        // identity. Fail on #3 so the first write has already executed inside the (still open)
        // transaction when the unchecked failure hits.
        final FaultInjectingDataSource faultyDs = new FaultInjectingDataSource( ds );
        faultyDs.failOn( 3, new RuntimeException( "boom: simulated mid-transaction failure" ) );
        final CitationRepository faultyRepo = new CitationRepository( faultyDs );

        assertThrows( RuntimeException.class, () -> faultyRepo.replaceForSource( "s1",
                List.of( newRow( "s1", "t-new", "H", "new span", CitationStatus.CURRENT ) ) ) );

        // Verify through the clean (non-faulty) DataSource: no partial write from the failed
        // transaction may be visible — the pre-existing "t-old" row must be exactly as it was.
        final List< CitationRow > rows = repo.findBySource( "s1" );
        assertEquals( 1, rows.size(), "no partial rows may be visible after a mid-transaction failure" );
        assertEquals( "t-old", rows.get( 0 ).targetCanonicalId(),
                "the pre-existing row must be untouched — not deleted, and no new row inserted" );
    }
}

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
package com.wikantik.knowledge;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that JdbcKnowledgeRepository translates "Data source is closed"
 * SQLExceptions into PoolClosedException (not plain RuntimeException) so that
 * callers can distinguish graceful-shutdown noise from real errors.
 *
 * No Docker / Testcontainers needed — the DataSource is fully mocked.
 */
class JdbcKnowledgeRepositoryPoolClosedTest {

    private static DataSource mockClosedDataSource() throws SQLException {
        final DataSource ds = Mockito.mock( DataSource.class );
        when( ds.getConnection() ).thenThrow(
            new SQLException( "Data source is closed" ) );
        return ds;
    }

    @Test
    void applyMachineVerdict_throwsPoolClosedException_whenDataSourceIsClosed() throws Exception {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( mockClosedDataSource() );
        final UUID id = UUID.randomUUID();

        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> repo.applyMachineVerdict( id, "abstain", 0.0, "test-model" ) );

        assertInstanceOf( PoolClosedException.class, ex,
            "expected PoolClosedException but got " + ex.getClass().getName() );
    }

    @Test
    void recordReview_throwsPoolClosedException_whenDataSourceIsClosed() throws Exception {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( mockClosedDataSource() );
        final UUID id = UUID.randomUUID();

        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> repo.recordReview( id, "machine", "test-model", "abstain", 0.0, "rationale" ) );

        assertInstanceOf( PoolClosedException.class, ex,
            "expected PoolClosedException but got " + ex.getClass().getName() );
    }

    @Test
    void listReviews_throwsPoolClosedException_whenDataSourceIsClosed() throws Exception {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( mockClosedDataSource() );
        final UUID id = UUID.randomUUID();

        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> repo.listReviews( id ) );

        assertInstanceOf( PoolClosedException.class, ex,
            "expected PoolClosedException but got " + ex.getClass().getName() );
    }

    @Test
    void getProposalsForJudging_throwsPoolClosedException_whenDataSourceIsClosed() throws Exception {
        final JdbcKnowledgeRepository repo = new JdbcKnowledgeRepository( mockClosedDataSource() );

        final RuntimeException ex = assertThrows( RuntimeException.class,
            () -> repo.getProposalsForJudging( 10 ) );

        assertInstanceOf( PoolClosedException.class, ex,
            "expected PoolClosedException but got " + ex.getClass().getName() );
    }
}

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
package com.wikantik.jdbc;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link Transactions}.
 *
 * <p>Small surface, but both behaviours it encodes were real defects elsewhere in this
 * codebase: a write path that never rolled back at all, and a rollback whose own failure
 * replaced the exception that explained the problem.
 */
class TransactionsTest {

    private static final Logger LOG = LogManager.getLogger( TransactionsTest.class );

    @Test
    void rollsBackTheConnection() throws SQLException {
        final Connection conn = mock( Connection.class );

        Transactions.rollbackQuietly( conn, new SQLException( "original" ), LOG, "ctx" );

        verify( conn ).rollback();
    }

    /**
     * The caller is already unwinding a failure when this runs. Throwing from here would
     * replace the cause with a cleanup error and hide what actually went wrong.
     */
    @Test
    void aFailingRollbackIsSwallowedSoTheOriginalCauseSurvives() throws SQLException {
        final Connection conn = mock( Connection.class );
        doThrow( new SQLException( "connection already closed" ) ).when( conn ).rollback();

        assertDoesNotThrow(
            () -> Transactions.rollbackQuietly( conn, new SQLException( "the real cause" ), LOG, "ctx" ),
            "rollbackQuietly must never throw — the caller rethrows the original failure." );
    }

    /** A null cause must not turn the log line into an NPE while unwinding a failure. */
    @Test
    void toleratesANullCauseMessage() throws SQLException {
        final Connection conn = mock( Connection.class );
        doThrow( new SQLException( "rollback boom" ) ).when( conn ).rollback();

        assertDoesNotThrow(
            () -> Transactions.rollbackQuietly( conn, new SQLException( (String) null ), LOG, "ctx" ) );
    }
}

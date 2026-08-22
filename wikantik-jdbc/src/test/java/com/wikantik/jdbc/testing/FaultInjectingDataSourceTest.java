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
package com.wikantik.jdbc.testing;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * H2 is fine here (as in the rest of {@code wikantik-jdbc}): these tests exercise the proxy's
 * counting/faulting control flow, not any dialect-specific SQL.
 */
class FaultInjectingDataSourceTest {

    private JdbcDataSource realH2;
    private FaultInjectingDataSource faulting;

    @BeforeEach
    void setUp() throws SQLException {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:faultinject_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        this.realH2 = h2;
        this.faulting = new FaultInjectingDataSource( h2 );
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))" );
        }
    }

    @Test
    void delegatesNormallyWhenNoFaultArmed() throws SQLException {
        try ( Connection conn = faulting.getConnection() ) {
            try ( PreparedStatement ps = conn.prepareStatement( "INSERT INTO t (id, name) VALUES (?, ?)" ) ) {
                ps.setInt( 1, 1 );
                ps.setString( 2, "a" );
                assertEquals( 1, ps.executeUpdate() );
            }
        }
    }

    @Test
    void throwsTheArmedExceptionOnTheNthStatementAcrossConnections() throws SQLException {
        final RuntimeException boom = new RuntimeException( "boom" );
        faulting.failOn( 2, boom );

        try ( Connection conn = faulting.getConnection() ) {
            // 1st statement call: not the target, must succeed.
            assertDoesNotThrow( () -> conn.prepareStatement( "SELECT 1" ) );
        }

        try ( Connection conn2 = faulting.getConnection() ) {
            // 2nd statement call overall, on a *different* connection handed out by the same
            // FaultInjectingDataSource: the count is across connections, not per-connection.
            final RuntimeException thrown = assertThrows( RuntimeException.class,
                () -> conn2.prepareStatement( "SELECT 1" ) );
            assertSame( boom, thrown );
        }
    }

    @Test
    void createStatementCountsTowardTheSameCounterAsPrepareStatement() throws SQLException {
        final RuntimeException boom = new RuntimeException( "boom" );
        faulting.failOn( 2, boom );

        try ( Connection conn = faulting.getConnection() ) {
            assertDoesNotThrow( () -> conn.prepareStatement( "SELECT 1" ) );
            final RuntimeException thrown = assertThrows( RuntimeException.class, conn::createStatement );
            assertSame( boom, thrown );
        }
    }

    @Test
    void statementsAfterTheArmedOneAreUnaffected() throws SQLException {
        final RuntimeException boom = new RuntimeException( "boom" );
        faulting.failOn( 1, boom );

        try ( Connection conn = faulting.getConnection() ) {
            assertThrows( RuntimeException.class, () -> conn.prepareStatement( "SELECT 1" ) );
            // 2nd call is past the armed statement — must succeed.
            assertDoesNotThrow( () -> conn.prepareStatement( "SELECT 2" ) );
        }
    }

    @Test
    void resetClearsTheCounterAndDisarmsTheFault() throws SQLException {
        final RuntimeException boom = new RuntimeException( "boom" );
        faulting.failOn( 1, boom );
        try ( Connection conn = faulting.getConnection() ) {
            assertThrows( RuntimeException.class, () -> conn.prepareStatement( "SELECT 1" ) );
        }

        faulting.reset();

        try ( Connection conn = faulting.getConnection() ) {
            assertDoesNotThrow( () -> conn.prepareStatement( "SELECT 1" ) );
        }
    }

    @Test
    void otherConnectionOperationsStillDelegateToTheRealConnection() throws SQLException {
        faulting.failOn( 99, new RuntimeException( "never" ) );
        try ( Connection conn = faulting.getConnection() ) {
            conn.setAutoCommit( false );
            assertEquals( false, conn.getAutoCommit() );
            conn.commit();
        }
    }

    @Test
    void failOnThrowableAcceptsAnError() throws SQLException {
        final AssertionError boom = new AssertionError( "simulated invariant violation" );
        faulting.failOn( 1, ( Throwable ) boom );

        try ( Connection conn = faulting.getConnection() ) {
            final AssertionError thrown = assertThrows( AssertionError.class, () -> conn.prepareStatement( "SELECT 1" ) );
            assertSame( boom, thrown );
        }
    }

    @Test
    void failOnRejectsACheckedException() {
        final Exception checked = new java.io.IOException( "checked, not allowed" );
        final IllegalArgumentException thrown = assertThrows( IllegalArgumentException.class,
            () -> faulting.failOn( 1, checked ) );
        assertEquals( true, thrown.getMessage().contains( "IOException" ) );
    }

    @Test
    void rollbacksAndCommitsAreCountedAcrossEveryConnectionHandedOut() throws SQLException {
        assertEquals( 0, faulting.rollbacks() );
        assertEquals( 0, faulting.commits() );

        try ( Connection conn = faulting.getConnection() ) {
            conn.setAutoCommit( false );
            conn.commit();
            conn.commit();
        }
        try ( Connection conn2 = faulting.getConnection() ) {
            conn2.setAutoCommit( false );
            conn2.rollback();
        }

        assertEquals( 1, faulting.rollbacks() );
        assertEquals( 2, faulting.commits() );
    }

    @Test
    void resetClearsTheRollbackAndCommitCounters() throws SQLException {
        try ( Connection conn = faulting.getConnection() ) {
            conn.setAutoCommit( false );
            conn.commit();
            conn.rollback();
        }
        assertEquals( 1, faulting.rollbacks() );
        assertEquals( 1, faulting.commits() );

        faulting.reset();

        assertEquals( 0, faulting.rollbacks() );
        assertEquals( 0, faulting.commits() );
    }
}

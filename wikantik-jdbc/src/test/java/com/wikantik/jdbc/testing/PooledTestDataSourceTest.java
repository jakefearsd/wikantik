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

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pooled connection must be indistinguishable from a fresh one, or tests would leak state into each other. */
@RequiresPostgres
class PooledTestDataSourceTest {

    private static String scalar( final Connection c, final String sql ) throws SQLException {
        try ( Statement st = c.createStatement(); ResultSet rs = st.executeQuery( sql ) ) {
            rs.next();
            return rs.getString( 1 );
        }
    }

    private static PooledTestDataSource pool() {
        return ( PooledTestDataSource ) PostgresTestDb.createDataSource();
    }

    @Test
    void aReturnedConnectionIsReusedAndLooksFresh() throws SQLException {
        final PooledTestDataSource ds = pool();
        final String pid;
        try ( Connection c = ds.getConnection(); Statement st = c.createStatement() ) {
            pid = scalar( c, "SELECT pg_backend_pid()" );
            st.execute( "CREATE TEMP TABLE pool_leak ( x int )" );
            st.execute( "SET statement_timeout = 1234" );
            c.setAutoCommit( false );
            st.execute( "CREATE TABLE pool_uncommitted ( x int )" );
        }
        try ( Connection c = ds.getConnection() ) {
            c.setReadOnly( true );
        }
        final int opened = ds.physicalConnectionsOpened();
        try ( Connection c = ds.getConnection() ) {
            assertEquals( pid, scalar( c, "SELECT pg_backend_pid()" ), "the idle connection is lent again" );
            assertEquals( opened, ds.physicalConnectionsOpened() );
            assertTrue( c.getAutoCommit() );
            assertFalse( c.isReadOnly() );
            assertEquals( "0", scalar( c, "SHOW statement_timeout" ) );
            assertEquals( "0", scalar( c, "SELECT count(*) FROM pg_class WHERE relname IN ('pool_leak', 'pool_uncommitted')" ),
                "temp table dropped and the open transaction rolled back" );
        }
    }

    @Test
    void aClosedConnectionRefusesWorkAndClosesIdempotently() throws SQLException {
        final Connection c = pool().getConnection();
        c.close();
        assertTrue( c.isClosed() );
        assertThrows( SQLException.class, c::createStatement );
        c.close();
    }

    @Test
    void twoOpenConnectionsAreDistinctSessions() throws SQLException {
        final PooledTestDataSource ds = pool();
        try ( Connection a = ds.getConnection(); Connection b = ds.getConnection() ) {
            assertFalse( scalar( a, "SELECT pg_backend_pid()" ).equals( scalar( b, "SELECT pg_backend_pid()" ) ) );
            assertFalse( a.equals( b ) );
        }
    }
}

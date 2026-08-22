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
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JdbcSupport}: it is a thin delegating wrapper over {@link Jdbc}, so these
 * confirm the delegation actually reaches the database and that {@link JdbcSupport#inTransaction}
 * inherits {@link Jdbc#inTransaction}'s full rollback contract (including the {@link Error}
 * branch) through the subclass path that the 8 real repositories/DAOs use.
 */
class JdbcSupportTest {

    /** A minimal concrete subclass — {@link JdbcSupport} is abstract only because of {@link #log()}. */
    private static final class TestSupport extends JdbcSupport {
        private static final Logger LOG = LogManager.getLogger( TestSupport.class );

        TestSupport( final DataSource dataSource ) {
            super( dataSource );
        }

        @Override
        protected Logger log() {
            return LOG;
        }

        List< String > namesOrdered() throws SQLException {
            return query( "SELECT name FROM t ORDER BY id", SqlBinder.NONE, rs -> rs.getString( 1 ) );
        }

        Optional< String > nameById( final int id ) throws SQLException {
            return queryOne( "SELECT name FROM t WHERE id = ?", ps -> ps.setInt( 1, id ), rs -> rs.getString( 1 ) );
        }

        int insert( final int id, final String name ) throws SQLException {
            return update( "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, id ); ps.setString( 2, name ); } );
        }

        void insertInTx( final Connection conn, final int id, final String name ) throws SQLException {
            update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, id ); ps.setString( 2, name ); } );
        }

        < T > T runInTransaction( final TransactionBody< T > body ) throws SQLException {
            return inTransaction( body );
        }
    }

    private TestSupport support;

    @BeforeEach
    void setUp() throws SQLException {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:jdbcsupporttest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        this.support = new TestSupport( h2 );
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))" );
        }
    }

    @Test
    void delegatesUpdateAndQuery() throws SQLException {
        assertEquals( 1, support.insert( 1, "alice" ) );
        assertEquals( 1, support.insert( 2, "bob" ) );

        assertEquals( List.of( "alice", "bob" ), support.namesOrdered() );
    }

    @Test
    void delegatesQueryOne() throws SQLException {
        support.insert( 1, "alice" );

        assertEquals( Optional.of( "alice" ), support.nameById( 1 ) );
        assertTrue( support.nameById( 999 ).isEmpty() );
    }

    @Test
    void inTransactionCommitsOnSuccess() throws SQLException {
        support.runInTransaction( conn -> {
            support.insertInTx( conn, 1, "committed" );
            return null;
        } );

        assertEquals( List.of( "committed" ), support.namesOrdered() );
    }

    @Test
    void inTransactionRollsBackOnRuntimeException() {
        assertThrows( IllegalStateException.class, () -> support.runInTransaction( conn -> {
            support.insertInTx( conn, 1, "should-not-persist" );
            throw new IllegalStateException( "boom" );
        } ) );

        assertTrue( assertDoesNotThrowSql( support::namesOrdered ).isEmpty() );
    }

    @Test
    void inTransactionRollsBackOnError() {
        assertThrows( AssertionError.class, () -> support.runInTransaction( conn -> {
            support.insertInTx( conn, 1, "should-not-persist" );
            throw new AssertionError( "boom" );
        } ) );

        assertTrue( assertDoesNotThrowSql( support::namesOrdered ).isEmpty() );
    }

    @FunctionalInterface
    private interface SqlSupplier< T > {
        T get() throws SQLException;
    }

    private static < T > T assertDoesNotThrowSql( final SqlSupplier< T > supplier ) {
        try {
            return supplier.get();
        } catch ( final SQLException e ) {
            throw new AssertionError( "unexpected SQLException: " + e.getMessage(), e );
        }
    }
}

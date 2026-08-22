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

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link Jdbc}'s control flow. H2 in-memory is used deliberately — this module is
 * the one place a hand-written H2 schema is acceptable, because these tests exercise the
 * primitive's connect/bind/execute/commit/rollback plumbing, not any dialect-specific SQL.
 */
class JdbcTest {

    private JdbcDataSource realH2;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:jdbctest_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        this.realH2 = h2;
        this.jdbc = new Jdbc( h2 );
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))" );
        }
    }

    // ----- query / queryOne / update happy paths -----------------------------------------

    @Test
    void updateAndQueryRoundTrip() throws SQLException {
        assertEquals( 1, jdbc.update( "INSERT INTO t (id, name) VALUES (?, ?)",
            ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "alice" ); } ) );
        assertEquals( 1, jdbc.update( "INSERT INTO t (id, name) VALUES (?, ?)",
            ps -> { ps.setInt( 1, 2 ); ps.setString( 2, "bob" ); } ) );

        final List< String > names = jdbc.query( "SELECT name FROM t ORDER BY id",
            SqlBinder.NONE, rs -> rs.getString( 1 ) );

        assertEquals( List.of( "alice", "bob" ), names );
    }

    @Test
    void queryOneFindsAndMisses() throws SQLException {
        jdbc.update( "INSERT INTO t (id, name) VALUES (?, ?)",
            ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "alice" ); } );

        final Optional< String > found = jdbc.queryOne( "SELECT name FROM t WHERE id = ?",
            ps -> ps.setInt( 1, 1 ), rs -> rs.getString( 1 ) );
        final Optional< String > missing = jdbc.queryOne( "SELECT name FROM t WHERE id = ?",
            ps -> ps.setInt( 1, 999 ), rs -> rs.getString( 1 ) );

        assertEquals( Optional.of( "alice" ), found );
        assertTrue( missing.isEmpty() );
    }

    @Test
    void queryAndUpdateOnAnOpenConnection() throws SQLException {
        try ( Connection conn = realH2.getConnection() ) {
            jdbc.update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "carol" ); } );
            final List< String > names = jdbc.query( conn, "SELECT name FROM t", SqlBinder.NONE,
                rs -> rs.getString( 1 ) );
            assertEquals( List.of( "carol" ), names );
        }
    }

    // ----- batch ---------------------------------------------------------------------------

    @Test
    void batchInsertsEveryRow() throws SQLException {
        try ( Connection conn = realH2.getConnection() ) {
            final int[] counts = jdbc.batch( conn, "INSERT INTO t (id, name) VALUES (?, ?)", List.of(
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "a" ); },
                ps -> { ps.setInt( 1, 2 ); ps.setString( 2, "b" ); },
                ps -> { ps.setInt( 1, 3 ); ps.setString( 2, "c" ); }
            ) );
            assertEquals( 3, counts.length );
        }

        final List< String > names = jdbc.query( "SELECT name FROM t ORDER BY id", SqlBinder.NONE,
            rs -> rs.getString( 1 ) );
        assertEquals( List.of( "a", "b", "c" ), names );
    }

    // ----- forEachRow ------------------------------------------------------------------------

    @Test
    void forEachRowStreamsEveryRow() throws SQLException {
        jdbc.update( "INSERT INTO t (id, name) VALUES (?, ?)", ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "x" ); } );
        jdbc.update( "INSERT INTO t (id, name) VALUES (?, ?)", ps -> { ps.setInt( 1, 2 ); ps.setString( 2, "y" ); } );

        final List< String > seen = new ArrayList<>();
        jdbc.forEachRow( "SELECT name FROM t ORDER BY id", SqlBinder.NONE, 10, rs -> seen.add( rs.getString( 1 ) ) );

        assertEquals( List.of( "x", "y" ), seen );
    }

    // ----- execute ---------------------------------------------------------------------------

    @Test
    void executeRunsDdlWithNoBinds() throws SQLException {
        jdbc.execute( "CREATE TABLE t2 (id INT PRIMARY KEY)" );

        // No exception means the table now exists — prove it by inserting into it.
        jdbc.update( "INSERT INTO t2 (id) VALUES (?)", ps -> ps.setInt( 1, 42 ) );
        final List< Integer > ids = jdbc.query( "SELECT id FROM t2", SqlBinder.NONE, rs -> rs.getInt( 1 ) );
        assertEquals( List.of( 42 ), ids );
    }

    // ----- ping ------------------------------------------------------------------------------

    @Test
    void pingIsTrueForAReachableDatabase() {
        assertTrue( jdbc.ping() );
    }

    @Test
    void pingIsFalseWhenTheConnectionCannotBeObtained() {
        final Jdbc broken = new Jdbc( new FakeDataSource( () -> {
            throw new UncheckedSqlException( new SQLException( "no route to host" ) );
        } ) );
        assertFalse( broken.ping() );
    }

    // ----- inTransaction: commit ---------------------------------------------------------

    @Test
    void inTransactionCommitsOnSuccess() throws SQLException {
        final String result = jdbc.inTransaction( conn -> {
            jdbc.update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "committed" ); } );
            return "ok";
        } );

        assertEquals( "ok", result );
        assertEquals( List.of( "committed" ),
            jdbc.query( "SELECT name FROM t", SqlBinder.NONE, rs -> rs.getString( 1 ) ) );
    }

    // ----- inTransaction: rollback on every failure kind ----------------------------------

    @Test
    void inTransactionRollsBackOnSqlException() {
        assertThrows( SQLException.class, () -> jdbc.inTransaction( conn -> {
            jdbc.update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "should-not-persist" ); } );
            throw new SQLException( "boom" );
        } ) );

        assertNoRowsVisible();
    }

    @Test
    void inTransactionRollsBackOnRuntimeException() {
        assertThrows( IllegalStateException.class, () -> jdbc.inTransaction( conn -> {
            jdbc.update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "should-not-persist" ); } );
            throw new IllegalStateException( "boom" );
        } ) );

        assertNoRowsVisible();
    }

    @Test
    void inTransactionRollsBackOnError() {
        assertThrows( AssertionError.class, () -> jdbc.inTransaction( conn -> {
            jdbc.update( conn, "INSERT INTO t (id, name) VALUES (?, ?)",
                ps -> { ps.setInt( 1, 1 ); ps.setString( 2, "should-not-persist" ); } );
            throw new AssertionError( "boom" );
        } ) );

        assertNoRowsVisible();
    }

    private void assertNoRowsVisible() {
        assertTrue( assertDoesNotThrowSql( () ->
            jdbc.query( "SELECT name FROM t", SqlBinder.NONE, rs -> rs.getString( 1 ) ) ).isEmpty() );
    }

    // ----- inTransaction: a failing rollback never masks the original cause --------------

    @Test
    void aFailingRollbackDoesNotMaskTheOriginalCause() {
        final AtomicInteger closeCount = new AtomicInteger();
        final Jdbc withBadRollback = new Jdbc(
            new FakeDataSource( () -> connectionWithFailingRollback( realH2, closeCount ) ) );

        final IllegalStateException thrown = assertThrows( IllegalStateException.class,
            () -> withBadRollback.inTransaction( conn -> {
                throw new IllegalStateException( "the real cause" );
            } ) );

        assertEquals( "the real cause", thrown.getMessage(),
            "the rollback's own SQLException must not replace the original cause" );
        assertEquals( 1, closeCount.get(), "the connection must still be closed" );
    }

    // ----- inTransaction: previous auto-commit state is restored --------------------------

    @Test
    void restoresAutoCommitFalseWhenThatWasThePreviousState() throws SQLException {
        final List< Boolean > autoCommitCalls = new ArrayList<>();
        final Connection real = realH2.getConnection();
        real.setAutoCommit( false );
        final Jdbc withRecordedConn = new Jdbc(
            new FakeDataSource( () -> connectionRecordingAutoCommit( real, autoCommitCalls ) ) );

        withRecordedConn.inTransaction( conn -> "ok" );

        assertEquals( List.of( false, false ), autoCommitCalls,
            "setAutoCommit(false) to start the tx, then restored to the previous value (false)" );
        real.close();
    }

    @Test
    void restoresAutoCommitTrueWhenThatWasThePreviousState() throws SQLException {
        final List< Boolean > autoCommitCalls = new ArrayList<>();
        final Connection real = realH2.getConnection();
        assertTrue( real.getAutoCommit(), "a fresh JDBC connection defaults to auto-commit true" );
        final Jdbc withRecordedConn = new Jdbc(
            new FakeDataSource( () -> connectionRecordingAutoCommit( real, autoCommitCalls ) ) );

        withRecordedConn.inTransaction( conn -> "ok" );

        assertEquals( List.of( false, true ), autoCommitCalls,
            "setAutoCommit(false) to start the tx, then restored to the previous value (true)" );
        real.close();
    }

    // ----- inTransaction: the connection is always closed ---------------------------------

    @Test
    void connectionIsClosedEvenWhenTheBodyThrows() {
        final AtomicInteger closeCount = new AtomicInteger();
        final Jdbc counting = new Jdbc(
            new FakeDataSource( () -> countingConnection( realH2, closeCount ) ) );

        assertThrows( IllegalStateException.class, () -> counting.inTransaction( conn -> {
            throw new IllegalStateException( "boom" );
        } ) );

        assertEquals( 1, closeCount.get() );
    }

    @Test
    void connectionIsClosedOnSuccess() throws SQLException {
        final AtomicInteger closeCount = new AtomicInteger();
        final Jdbc counting = new Jdbc(
            new FakeDataSource( () -> countingConnection( realH2, closeCount ) ) );

        counting.inTransaction( conn -> "ok" );

        assertEquals( 1, closeCount.get() );
    }

    // ----- helpers ---------------------------------------------------------------------------

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

    /** Wraps a checked {@link SQLException} so it can escape a {@link java.util.function.Supplier}. */
    private static final class UncheckedSqlException extends RuntimeException {
        UncheckedSqlException( final SQLException cause ) { super( cause ); }
    }

    /** Minimal {@link DataSource} whose only meaningful method is {@link #getConnection()}. */
    private static final class FakeDataSource implements DataSource {
        private final SqlSupplier< Connection > supplier;

        FakeDataSource( final SqlSupplier< Connection > supplier ) {
            this.supplier = supplier;
        }

        @Override
        public Connection getConnection() throws SQLException {
            try {
                return supplier.get();
            } catch ( final UncheckedSqlException e ) {
                throw ( SQLException ) e.getCause();
            }
        }

        @Override
        public Connection getConnection( final String username, final String password ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.io.PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }

        @Override
        public void setLogWriter( final java.io.PrintWriter out ) { throw new UnsupportedOperationException(); }

        @Override
        public void setLoginTimeout( final int seconds ) { throw new UnsupportedOperationException(); }

        @Override
        public int getLoginTimeout() { throw new UnsupportedOperationException(); }

        @Override
        public java.util.logging.Logger getParentLogger() { throw new UnsupportedOperationException(); }

        @Override
        public < T > T unwrap( final Class< T > iface ) { throw new UnsupportedOperationException(); }

        @Override
        public boolean isWrapperFor( final Class< ? > iface ) { return false; }
    }

    /** Proxies {@code real} so {@link Connection#rollback()} throws instead of rolling back. */
    private static Connection connectionWithFailingRollback( final DataSource realDs, final AtomicInteger closeCount )
            throws SQLException {
        final Connection real = realDs.getConnection();
        return proxy( real, ( method, args ) -> {
            if ( "close".equals( method.getName() ) ) closeCount.incrementAndGet();
            if ( "rollback".equals( method.getName() ) && ( args == null || args.length == 0 ) ) {
                throw new SQLException( "rollback failed" );
            }
            return NO_OVERRIDE;
        } );
    }

    /** Proxies {@code real} to record every {@code setAutoCommit(boolean)} call, in order. */
    private static Connection connectionRecordingAutoCommit( final Connection real, final List< Boolean > calls ) {
        return proxy( real, ( method, args ) -> {
            if ( "setAutoCommit".equals( method.getName() ) ) {
                calls.add( ( Boolean ) args[ 0 ] );
            }
            if ( "close".equals( method.getName() ) ) {
                return null; // this connection is reused across the test; do not actually close it.
            }
            return NO_OVERRIDE;
        } );
    }

    /** Proxies {@code real} to count {@link Connection#close()} calls without actually closing it. */
    private static Connection countingConnection( final DataSource realDs, final AtomicInteger closeCount )
            throws SQLException {
        final Connection real = realDs.getConnection();
        return proxy( real, ( method, args ) -> {
            if ( "close".equals( method.getName() ) ) {
                closeCount.incrementAndGet();
                real.close();
                return null;
            }
            return NO_OVERRIDE;
        } );
    }

    private static final Object NO_OVERRIDE = new Object();

    @FunctionalInterface
    private interface Intercept {
        Object beforeDelegate( Method method, Object[] args ) throws SQLException;
    }

    private static Connection proxy( final Connection real, final Intercept intercept ) {
        final InvocationHandler handler = ( p, method, args ) -> {
            final Object overridden = intercept.beforeDelegate( method, args );
            if ( overridden != NO_OVERRIDE ) return overridden;
            try {
                return method.invoke( real, args );
            } catch ( final InvocationTargetException e ) {
                throw e.getCause();
            }
        };
        return ( Connection ) Proxy.newProxyInstance(
            JdbcTest.class.getClassLoader(), new Class< ? >[] { Connection.class }, handler );
    }
}

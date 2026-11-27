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
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the {@link Jdbc} contracts that a real driver masks: PgJDBC commits when auto-commit is
 * switched back on, so a dropped {@code commit()} is invisible against a real database. These
 * tests use a mocked connection and assert the call order directly, plus parameter binding on the
 * open-connection paths and the negative answers of {@code ping} / {@code supportsTransactions}.
 */
class JdbcContractTest {

    private DataSource ds;
    private Connection conn;
    private PreparedStatement ps;
    private ResultSet rs;
    private Jdbc jdbc;

    @BeforeEach
    void setUp() throws SQLException {
        ds = mock( DataSource.class );
        conn = mock( Connection.class );
        ps = mock( PreparedStatement.class );
        rs = mock( ResultSet.class );
        when( ds.getConnection() ).thenReturn( conn );
        when( conn.prepareStatement( anyString() ) ).thenReturn( ps );
        when( ps.executeQuery() ).thenReturn( rs );
        when( rs.next() ).thenReturn( true, false );
        jdbc = new Jdbc( ds );
    }

    // ----- parameter binding on the open-connection paths ---------------------------------

    @Test
    void queryOnAnOpenConnectionBindsParameters() throws SQLException {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:jdbccontract_q_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        seedTwoRows( h2 );
        try ( Connection c = h2.getConnection() ) {
            final List< String > names = new Jdbc( h2 ).query( c, "SELECT name FROM t WHERE id = ?",
                p -> p.setInt( 1, 2 ), r -> r.getString( 1 ) );
            assertEquals( List.of( "bob" ), names );
        }
    }

    @Test
    void forEachRowOnAnOpenConnectionBindsParametersWithAndWithoutFetchSize() throws SQLException {
        final JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL( "jdbc:h2:mem:jdbccontract_f_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1" );
        seedTwoRows( h2 );
        try ( Connection c = h2.getConnection() ) {
            for ( final int fetchSize : new int[] { 0, 10 } ) {
                final List< String > seen = new ArrayList<>();
                new Jdbc( h2 ).forEachRow( c, "SELECT name FROM t WHERE id = ?", p -> p.setInt( 1, 2 ),
                    fetchSize, r -> seen.add( r.getString( 1 ) ) );
                assertEquals( List.of( "bob" ), seen, "fetchSize " + fetchSize );
            }
        }
    }

    private static void seedTwoRows( final JdbcDataSource h2 ) throws SQLException {
        try ( Connection c = h2.getConnection(); Statement s = c.createStatement() ) {
            s.executeUpdate( "CREATE TABLE t (id INT PRIMARY KEY, name VARCHAR(50))" );
            s.executeUpdate( "INSERT INTO t VALUES (1, 'alice'), (2, 'bob')" );
        }
    }

    // ----- forEachRow: fetch size and the read-transaction wrap ----------------------------

    @Test
    void forEachRowPassesThePositiveFetchSizeToTheStatement() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        jdbc.forEachRow( "SELECT 1", SqlBinder.NONE, 25, r -> { } );

        verify( ps ).setFetchSize( 25 );
    }

    @Test
    void forEachRowOnAnAutoCommitConnectionWrapsTheScanAndCommitsBeforeRestoring() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        jdbc.forEachRow( "SELECT 1", SqlBinder.NONE, 10, r -> { } );

        final InOrder order = inOrder( conn, ps );
        order.verify( conn ).setAutoCommit( false );
        order.verify( ps ).executeQuery();
        order.verify( conn ).commit();
        order.verify( conn ).setAutoCommit( true );
        verify( conn, never() ).rollback();
    }

    @Test
    void forEachRowFailureOnAnAutoCommitConnectionRollsBackAndNeverCommits() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        assertThrows( IllegalStateException.class, () -> jdbc.forEachRow( "SELECT 1", SqlBinder.NONE, 10,
            r -> { throw new IllegalStateException( "boom" ); } ) );

        final InOrder order = inOrder( conn );
        order.verify( conn ).setAutoCommit( false );
        order.verify( conn ).rollback();
        order.verify( conn ).setAutoCommit( true );
        verify( conn, never() ).commit();
    }

    @Test
    void forEachRowInsideTheCallersTransactionNeitherCommitsNorRollsBackNorTouchesAutoCommit() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( false );

        jdbc.forEachRow( conn, "SELECT 1", SqlBinder.NONE, 10, r -> { } );
        assertThrows( IllegalStateException.class, () -> {
            when( rs.next() ).thenReturn( true, false );
            jdbc.forEachRow( conn, "SELECT 1", SqlBinder.NONE, 10, r -> { throw new IllegalStateException( "boom" ); } );
        } );

        verify( conn, never() ).commit();
        verify( conn, never() ).rollback();
        verify( conn, never() ).setAutoCommit( true );
        verify( conn, never() ).setAutoCommit( false );
    }

    @Test
    void forEachRowWithZeroFetchSizeOnAnAutoCommitConnectionDoesNotWrap() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        jdbc.forEachRow( conn, "SELECT 1", SqlBinder.NONE, 0, r -> { } );
        assertThrows( IllegalStateException.class, () -> {
            when( rs.next() ).thenReturn( true, false );
            jdbc.forEachRow( conn, "SELECT 1", SqlBinder.NONE, 0, r -> { throw new IllegalStateException( "boom" ); } );
        } );

        verify( conn, never() ).commit();
        verify( conn, never() ).rollback();
        verify( conn, never() ).setAutoCommit( false );
    }

    // ----- inTransaction: commit/rollback strictly before the auto-commit restore ----------

    @Test
    void inTransactionCommitsBeforeRestoringAutoCommit() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        final String result = jdbc.inTransaction( c -> "done" );

        assertEquals( "done", result );
        final InOrder order = inOrder( conn );
        order.verify( conn ).setAutoCommit( false );
        order.verify( conn ).commit();
        order.verify( conn ).setAutoCommit( true );
        verify( conn, never() ).rollback();
    }

    @Test
    void inTransactionFailureRollsBackBeforeRestoringAutoCommitAndNeverCommits() throws SQLException {
        when( conn.getAutoCommit() ).thenReturn( true );

        assertThrows( IllegalStateException.class, () -> jdbc.inTransaction( c -> {
            throw new IllegalStateException( "boom" );
        } ) );

        final InOrder order = inOrder( conn );
        order.verify( conn ).setAutoCommit( false );
        order.verify( conn ).rollback();
        order.verify( conn ).setAutoCommit( true );
        verify( conn, never() ).commit();
    }

    // ----- ping / supportsTransactions negative answers ------------------------------------

    @Test
    void pingIsFalseWhenTheConnectionReportsItselfInvalid() throws SQLException {
        when( conn.isValid( anyInt() ) ).thenReturn( false );

        assertFalse( jdbc.ping() );
    }

    @Test
    void supportsTransactionsIsFalseWhenTheDriverSaysSo() throws SQLException {
        final DatabaseMetaData meta = mock( DatabaseMetaData.class );
        when( conn.getMetaData() ).thenReturn( meta );
        when( meta.supportsTransactions() ).thenReturn( false );

        assertFalse( jdbc.supportsTransactions() );
    }
}

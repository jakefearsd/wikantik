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
package com.wikantik.auth;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link AbstractJDBCDatabase} utility methods.
 */
class AbstractJDBCDatabaseTest {

    /** Concrete subclass for testing the abstract class. */
    private static class TestJDBCDatabase extends AbstractJDBCDatabase {
        void setDataSource( final DataSource dataSource ) {
            this.ds = dataSource;
        }

        void setSupportsCommits( final boolean supports ) {
            this.supportsCommits = supports;
        }
    }

    private TestJDBCDatabase db;
    private DataSource mockDs;
    private Connection mockConn;

    @BeforeEach
    void setUp() throws Exception {
        db = new TestJDBCDatabase();
        mockDs = mock( DataSource.class );
        mockConn = mock( Connection.class );
        doReturn( mockConn ).when( mockDs ).getConnection();
        db.setDataSource( mockDs );
    }

    /**
     * Tests that supportsCommits() returns false by default.
     */
    @Test
    void testSupportsCommitsDefault() {
        Assertions.assertFalse( db.supportsCommits(), "supportsCommits should be false by default" );
    }

    /**
     * Tests that supportsCommits() returns true after being set.
     */
    @Test
    void testSupportsCommitsTrue() {
        db.setSupportsCommits( true );
        Assertions.assertTrue( db.supportsCommits() );
    }

    /**
     * Tests closeQuietly with all null arguments does not throw.
     */
    @Test
    void testCloseQuietlyAllNull() {
        Assertions.assertDoesNotThrow( () -> db.closeQuietly( null, null, null ),
                                        "closeQuietly should handle all-null arguments" );
    }

    /**
     * Tests closeQuietly with valid resources closes them without throwing.
     */
    @Test
    void testCloseQuietlyWithResources() throws Exception {
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );

        db.closeQuietly( conn, ps, rs );

        verify( conn ).close();
        verify( ps ).close();
        verify( rs ).close();
    }

    /**
     * Tests closeQuietly when resources throw exceptions on close - should not propagate.
     */
    @Test
    void testCloseQuietlySuppressesExceptions() throws Exception {
        final Connection conn = mock( Connection.class );
        final PreparedStatement ps = mock( PreparedStatement.class );
        final ResultSet rs = mock( ResultSet.class );

        doThrow( new SQLException( "close failed" ) ).when( rs ).close();
        doThrow( new SQLException( "close failed" ) ).when( ps ).close();
        doThrow( new SQLException( "close failed" ) ).when( conn ).close();

        Assertions.assertDoesNotThrow( () -> db.closeQuietly( conn, ps, rs ),
                                        "closeQuietly should suppress exceptions from close" );
    }

    /**
     * Tests runInTransaction with a successful operation commits the result.
     */
    @Test
    void testRunInTransactionSuccess() throws Exception {
        db.setSupportsCommits( true );

        final String result = db.runInTransaction( conn -> "success" );

        Assertions.assertEquals( "success", result );
        verify( mockConn ).setAutoCommit( false );
        verify( mockConn ).commit();
        verify( mockConn ).close();
    }

    /**
     * Tests runInTransaction without commit support skips autocommit/commit calls.
     */
    @Test
    void testRunInTransactionNoCommitSupport() throws Exception {
        db.setSupportsCommits( false );

        final String result = db.runInTransaction( conn -> "no-commit" );

        Assertions.assertEquals( "no-commit", result );
        verify( mockConn, never() ).setAutoCommit( false );
        verify( mockConn, never() ).commit();
        verify( mockConn ).close();
    }

    /**
     * Tests runInTransaction wraps exceptions in WikiSecurityException.
     */
    @Test
    void testRunInTransactionException() {
        db.setSupportsCommits( false );

        final WikiSecurityException ex = Assertions.assertThrows( WikiSecurityException.class,
            () -> db.runInTransaction( conn -> { throw new RuntimeException( "test error" ); } ) );

        Assertions.assertTrue( ex.getMessage().contains( "test error" ),
                               "Should contain original error message" );
    }

    /**
     * A failed transaction must be rolled back HERE, not left for the connection pool.
     *
     * <p>runInTransaction turned auto-commit off and then, on failure, simply let
     * try-with-resources close the connection with the transaction still open. Whether that
     * discards or commits the partial work is pool-defined: DBCP2 (what this app deploys)
     * defaults to rollbackOnReturn=true and saves us, but nothing in the codebase pins that
     * setting, and the one production caller is JDBCGroupDatabase.save() — a half-applied
     * group membership rewrite is a security-relevant partial write.
     */
    @Test
    void testRunInTransactionRollsBackOnFailure() throws Exception {
        db.setSupportsCommits( true );

        Assertions.assertThrows( WikiSecurityException.class,
            () -> db.runInTransaction( conn -> { throw new SQLException( "mid-transaction failure" ); } ) );

        verify( mockConn ).rollback();
        verify( mockConn, never() ).commit();
    }

    /**
     * Rolling back a connection that never left auto-commit mode is an error in JDBC, so the
     * rollback must be gated on the same flag that turned auto-commit off.
     */
    @Test
    void testRunInTransactionDoesNotRollBackWhenTransactionsUnsupported() throws Exception {
        db.setSupportsCommits( false );

        Assertions.assertThrows( WikiSecurityException.class,
            () -> db.runInTransaction( conn -> { throw new SQLException( "boom" ); } ) );

        verify( mockConn, never() ).rollback();
    }

    /**
     * If the rollback itself fails, the original failure is still what explains the problem
     * and must not be replaced by the rollback's own exception.
     */
    @Test
    void testRunInTransactionRollbackFailureDoesNotMaskOriginalError() throws Exception {
        db.setSupportsCommits( true );
        doThrow( new SQLException( "rollback also failed" ) ).when( mockConn ).rollback();

        final WikiSecurityException ex = Assertions.assertThrows( WikiSecurityException.class,
            () -> db.runInTransaction( conn -> { throw new SQLException( "the real cause" ); } ) );

        Assertions.assertTrue( ex.getMessage().contains( "the real cause" ),
            "The original failure must survive a failing rollback, got: " + ex.getMessage() );
    }

    /**
     * Tests runInTransaction re-throws WikiSecurityException directly.
     */
    @Test
    void testRunInTransactionWikiSecurityException() {
        db.setSupportsCommits( false );

        final WikiSecurityException thrown = Assertions.assertThrows( WikiSecurityException.class,
            () -> db.runInTransaction( conn -> { throw new WikiSecurityException( "direct error" ); } ) );

        Assertions.assertEquals( "direct error", thrown.getMessage() );
    }

    /**
     * Tests getConnection returns a connection from the DataSource.
     */
    @Test
    void testGetConnection() throws Exception {
        final Connection conn = db.getConnection();
        Assertions.assertNotNull( conn );
        Assertions.assertSame( mockConn, conn );
    }






}

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

import com.wikantik.jdbc.Jdbc;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Abstract base class for JDBC-backed databases (UserDatabase, GroupDatabase).
 * Provides common infrastructure for JNDI DataSource lookup, transaction support
 * detection, and connection management.
 *
 * @since 3.0.7
 */
public abstract class AbstractJDBCDatabase {

    private static final Logger LOG = LogManager.getLogger( AbstractJDBCDatabase.class );

    /** Property name for the single shared JNDI DataSource. */
    public static final String PROP_DATASOURCE = "wikantik.datasource";

    /** Default JNDI name for the shared DataSource. */
    public static final String DEFAULT_DATASOURCE = "jdbc/WikiDatabase";

    /** The JDBC DataSource obtained via JNDI. */
    protected DataSource ds;

    /** Whether the database supports transactions (commits). */
    protected boolean supportsCommits;

    /**
     * Quietly closes database resources, ignoring any exceptions.
     * This method is useful for cleanup in finally blocks.
     *
     * @param conn the connection to close (may be null)
     * @param ps the prepared statement to close (may be null)
     * @param rs the result set to close (may be null)
     */
    public void closeQuietly( final Connection conn, final PreparedStatement ps, final ResultSet rs ) {
        if( rs != null ) {
            try {
                rs.close();
            } catch( final Exception e ) {
                // Ignore
            }
        }
        if( ps != null ) {
            try {
                ps.close();
            } catch( final Exception e ) {
                // Ignore
            }
        }
        if( conn != null ) {
            try {
                conn.close();
            } catch( final Exception e ) {
                // Ignore
            }
        }
    }

    /**
     * Returns whether the database supports transactions.
     *
     * @return true if the database supports transactions
     */
    public boolean supportsCommits() {
        return supportsCommits;
    }

    /**
     * Functional interface for a database operation that runs within a transaction.
     *
     * <p>{@code public} (rather than the narrower access a purely internal helper would get) so
     * {@link #runInTransaction(DataSource, boolean, TransactionalOperation)} can be called by a
     * class that cannot extend this one — see that method's Javadoc.
     *
     * @param <T> the return type of the operation
     * @since 3.0.7
     */
    @FunctionalInterface
    public interface TransactionalOperation<T> {
        T execute( Connection conn ) throws Exception;
    }

    /**
     * Executes a database operation within a transaction. Handles obtaining the connection,
     * setting auto-commit, committing, and closing the connection. If an exception occurs,
     * it is wrapped in a {@link WikiSecurityException}.
     *
     * @param <T> the return type of the operation
     * @param operation the database operation to execute
     * @return the result of the operation
     * @throws WikiSecurityException if the operation fails
     * @since 3.0.7
     */
    protected <T> T runInTransaction( final TransactionalOperation<T> operation ) throws WikiSecurityException {
        return runInTransaction( ds, supportsCommits, operation );
    }

    /**
     * Shared implementation of {@link #runInTransaction(TransactionalOperation)}, exposed
     * {@code static} so a class that cannot extend this one — {@link com.wikantik.auth.user.JDBCUserDatabase}
     * already extends {@code AbstractUserDatabase} — still gets the exact same transaction
     * semantics without re-implementing them.
     *
     * <p>Delegates to {@link Jdbc#inTransaction} when {@code supportsCommits} is {@code true}
     * (rollback on any {@link Throwable}, auto-commit restored only after commit/rollback has
     * run — see its Javadoc). When {@code false}, {@code operation} simply runs on a plain
     * connection with no transaction control, matching this class's historical behaviour for a
     * datasource that doesn't support transactions.
     *
     * <p>{@code operation} may throw any {@link Exception}, but {@link Jdbc#inTransaction}'s
     * {@code TransactionBody} may only declare {@link SQLException} — a checked, non-{@code
     * SQLException} failure (e.g. a directly-thrown {@link WikiSecurityException}) is carried
     * across that boundary as an internal unchecked {@link OperationFailure} and unwrapped again
     * on the way out, so the caller never sees it.
     *
     * @param dataSource      the DataSource to run against
     * @param supportsCommits whether the datasource supports transactions
     * @param operation       the database operation to execute
     * @return the result of the operation
     * @throws WikiSecurityException if the operation fails
     */
    public static <T> T runInTransaction( final DataSource dataSource, final boolean supportsCommits,
                                           final TransactionalOperation<T> operation ) throws WikiSecurityException {
        try {
            final Jdbc jdbc = new Jdbc( dataSource );
            if( supportsCommits ) {
                return jdbc.inTransaction( conn -> runUnchecked( operation, conn ) );
            }
            return jdbc.withConnection( conn -> runUnchecked( operation, conn ) );
        } catch( final OperationFailure e ) {
            throw unwrap( e );
        } catch( final Exception e ) {
            throw new WikiSecurityException( "Database operation failed: " + e.getMessage(), e );
        }
    }

    /**
     * Runs {@code operation} inside a {@link com.wikantik.jdbc.TransactionBody}, whose functional
     * method may only throw {@link SQLException}: checked exceptions and runtime exceptions pass
     * straight through unchanged (both are permitted without a {@code throws} declaration), but
     * any other checked {@link Exception} is wrapped in an unchecked {@link OperationFailure} so
     * it can cross the boundary too, to be unwrapped by {@link #unwrap(OperationFailure)}.
     */
    private static <T> T runUnchecked( final TransactionalOperation<T> operation, final Connection conn )
            throws SQLException {
        try {
            return operation.execute( conn );
        } catch( final SQLException e ) {
            throw e;
        } catch( final RuntimeException e ) {
            throw e;
        } catch( final Exception e ) {
            throw new OperationFailure( e );
        }
    }

    /** Unwraps {@code e}'s checked cause into the same {@link WikiSecurityException} contract {@link #runInTransaction} uses for the non-transactional path. */
    private static WikiSecurityException unwrap( final OperationFailure e ) {
        final Exception cause = e.checkedCause();
        if( cause instanceof WikiSecurityException wse ) {
            return wse;
        }
        return new WikiSecurityException( "Database operation failed: " + cause.getMessage(), cause );
    }

    /**
     * Probes whether {@code dataSource} supports transactions, via a throwaway connection's
     * {@link java.sql.DatabaseMetaData#supportsTransactions()}. Shared by {@code initialize()} in
     * both {@link com.wikantik.auth.user.JDBCUserDatabase} and
     * {@link com.wikantik.auth.authorize.JDBCGroupDatabase} so this one-time startup probe — the
     * only reason either class still needs to see a raw {@link Connection} — isn't duplicated
     * (neither {@link Jdbc} nor {@link com.wikantik.jdbc.JdbcSupport} exposes connection
     * metadata; their surface is SQL execution, not introspection). A failure to even obtain a
     * connection is treated the same as "transactions not supported" and logged at {@code WARN},
     * matching this method's historical fail-soft behaviour.
     *
     * @param dataSource the DataSource to probe
     * @return {@code true} if the datasource reports transaction support
     */
    public static boolean probeSupportsTransactions( final DataSource dataSource ) {
        return new Jdbc( dataSource ).supportsTransactions();
    }

    /**
     * Unchecked carrier for a checked, non-{@link SQLException} failure from a
     * {@link TransactionalOperation}, so it can cross {@link Jdbc#inTransaction}'s
     * {@code TransactionBody} boundary (which only permits {@link SQLException}) without losing
     * its original type or message.
     */
    private static final class OperationFailure extends RuntimeException {
        OperationFailure( final Exception cause ) {
            super( cause );
        }

        Exception checkedCause() {
            return ( Exception ) getCause();
        }
    }

}

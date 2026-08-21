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

    /** Property name for the single shared JNDI DataSource. */
    public static final String PROP_DATASOURCE = "wikantik.datasource";

    /** Default JNDI name for the shared DataSource. */
    public static final String DEFAULT_DATASOURCE = "jdbc/WikiDatabase";

    private static final Logger LOG = LogManager.getLogger( AbstractJDBCDatabase.class );

    /** The JDBC DataSource obtained via JNDI. */
    protected DataSource ds;

    /** Whether the database supports transactions (commits). */
    protected boolean supportsCommits;

    /**
     * Obtains a connection from the DataSource.
     *
     * @return a database connection
     * @throws SQLException if a connection cannot be obtained
     */
    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

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
     * @param <T> the return type of the operation
     * @since 3.0.7
     */
    @FunctionalInterface
    protected interface TransactionalOperation<T> {
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
        try ( Connection conn = ds.getConnection() ) {
            if( supportsCommits ) {
                conn.setAutoCommit( false );
            }
            try {
                final T result = operation.execute( conn );
                if( supportsCommits ) {
                    conn.commit();
                }
                return result;
            } catch( final Exception e ) {
                rollbackQuietly( conn, e );
                throw e;
            }
        } catch( final WikiSecurityException e ) {
            throw e;
        } catch( final Exception e ) {
            throw new WikiSecurityException( "Database operation failed: " + e.getMessage(), e );
        }
    }

    /**
     * Rolls back a failed transaction, swallowing any failure of the rollback itself.
     *
     * <p>Previously nothing rolled back here: on failure the connection was simply closed with
     * its transaction still open, leaving the outcome to the pool. DBCP2 — what this
     * application deploys — happens to default {@code rollbackOnReturn} to true, but that is
     * not configured anywhere in the repo, and the production caller is
     * {@code JDBCGroupDatabase.save()}, where a half-applied membership rewrite is a
     * security-relevant partial write. Rolling back explicitly makes the behaviour ours.
     *
     * <p>Gated on {@code supportsCommits} for the same reason the commit is: rolling back a
     * connection still in auto-commit mode is an error in JDBC.
     *
     * <p>A failing rollback is logged, never rethrown — the exception that caused the rollback
     * is the one that explains the failure, and it must not be replaced.
     */
    private void rollbackQuietly( final Connection conn, final Exception cause ) {
        if( !supportsCommits ) {
            return;
        }
        com.wikantik.jdbc.Transactions.rollbackQuietly( conn, cause, LOG, getClass().getSimpleName() );
    }

}

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
package org.apache.wiki.auth;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.exceptions.NoRequiredPropertyException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
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

    /** The JDBC DataSource obtained via JNDI. */
    protected DataSource ds;

    /** Whether the database supports transactions (commits). */
    protected boolean supportsCommits;

    /**
     * Looks up a JDBC DataSource from JNDI using the standard Java EE naming convention.
     *
     * @param jndiName the JNDI name of the DataSource (e.g., "jdbc/UserDatabase")
     * @param datasourcePropertyName the property name for error reporting
     * @throws NoRequiredPropertyException if the JNDI lookup fails
     */
    public void lookupDataSource( final String jndiName, final String datasourcePropertyName )
            throws NoRequiredPropertyException {
        try {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            ds = (DataSource) ctx.lookup( jndiName );
        } catch( final NamingException e ) {
            LOG.error( "{} initialization error: {}", getClass().getSimpleName(), e.getMessage() );
            throw new NoRequiredPropertyException( datasourcePropertyName,
                    getClass().getSimpleName() + " initialization error: " + e.getMessage() );
        }
    }

    /**
     * Tests the database connection by executing a simple query.
     *
     * @param testSql the SQL statement to execute for testing (e.g., "SELECT * FROM users")
     * @throws WikiSecurityException if the connection test fails
     */
    public void testConnection( final String testSql ) throws WikiSecurityException {
        try( final Connection conn = ds.getConnection();
             final PreparedStatement ps = conn.prepareStatement( testSql ) ) {
            // Just prepare the statement to test connectivity
        } catch( final SQLException e ) {
            LOG.error( "DB connectivity error: {}", e.getMessage() );
            throw new WikiSecurityException( "DB connectivity error: " + e.getMessage(), e );
        }
        LOG.info( "{} connection test successful.", getClass().getSimpleName() );
    }

    /**
     * Detects whether the database supports transactions and sets the supportsCommits flag.
     */
    public void detectTransactionSupport() {
        try( final Connection conn = ds.getConnection() ) {
            final DatabaseMetaData dmd = conn.getMetaData();
            if( dmd.supportsTransactions() ) {
                supportsCommits = true;
                conn.setAutoCommit( false );
                LOG.info( "{} supports transactions. Good; we will use them.", getClass().getSimpleName() );
            }
        } catch( final SQLException e ) {
            LOG.warn( "{} warning: database doesn't seem to support transactions. Reason: {}",
                    getClass().getSimpleName(), e.getMessage() );
        }
    }

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
     * Prepares a connection for a transactional operation by disabling auto-commit
     * if the database supports transactions.
     *
     * @param conn the connection to prepare
     * @throws SQLException if the auto-commit setting fails
     */
    public void prepareForTransaction( final Connection conn ) throws SQLException {
        if( supportsCommits ) {
            conn.setAutoCommit( false );
        }
    }

    /**
     * Commits the transaction if the database supports transactions.
     *
     * @param conn the connection to commit
     * @throws SQLException if the commit fails
     */
    public void commitIfSupported( final Connection conn ) throws SQLException {
        if( supportsCommits ) {
            conn.commit();
        }
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

}

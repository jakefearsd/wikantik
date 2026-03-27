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
package com.wikantik.observability.health;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Checks database connectivity by performing a JNDI DataSource lookup and executing
 * a simple query. Reports the response time of the check.
 */
public class DatabaseHealthCheck implements HealthCheck {

    private static final Logger LOG = LogManager.getLogger( DatabaseHealthCheck.class );

    private final String jndiName;
    private final DataSource directDataSource;

    /**
     * Creates a database health check that looks up the DataSource via JNDI.
     *
     * @param jndiName the JNDI name (e.g., "jdbc/UserDatabase")
     */
    public DatabaseHealthCheck( final String jndiName ) {
        this.jndiName = jndiName;
        this.directDataSource = null;
    }

    /**
     * Creates a database health check with a pre-resolved DataSource (for testing
     * or environments without JNDI).
     *
     * @param dataSource the DataSource to check
     */
    public DatabaseHealthCheck( final DataSource dataSource ) {
        this.jndiName = null;
        this.directDataSource = dataSource;
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public HealthResult check() {
        final long start = System.currentTimeMillis();
        try {
            final DataSource ds = resolveDataSource();
            try ( final Connection conn = ds.getConnection();
                  final Statement stmt = conn.createStatement();
                  final ResultSet rs = stmt.executeQuery( "SELECT 1" ) ) {
                rs.next();
            }
            return HealthResult.up( System.currentTimeMillis() - start );
        } catch ( final Exception e ) {
            LOG.warn( "Database health check failed", e );
            return HealthResult.down( System.currentTimeMillis() - start, "Database connectivity check failed" );
        }
    }

    private DataSource resolveDataSource() throws Exception {
        if ( directDataSource != null ) {
            return directDataSource;
        }
        final Context initContext = new InitialContext();
        final Context envContext = (Context) initContext.lookup( "java:comp/env" );
        return (DataSource) envContext.lookup( jndiName );
    }

}

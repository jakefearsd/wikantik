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
import com.wikantik.api.exceptions.NoRequiredPropertyException;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;

/**
 * Shared JNDI DataSource lookup for the JDBC-backed auth stores.
 *
 * <p>{@link AbstractJDBCDatabase} (extended by {@code JDBCGroupDatabase}) and
 * {@code JDBCUserDatabase} (which extends {@code AbstractUserDatabase} and so
 * cannot also extend {@code AbstractJDBCDatabase}) both need the identical
 * {@code java:comp/env} lookup. Single inheritance forces them apart, so the
 * lookup lives here as a static helper rather than being copy-pasted into both.</p>
 *
 * @since 3.0.7
 */
public final class JndiDataSources {

    private static final Logger LOG = LogManager.getLogger( JndiDataSources.class );

    private JndiDataSources() {
    }

    /**
     * Looks up a JDBC DataSource from JNDI using the standard Java EE naming convention.
     *
     * @param jndiName the JNDI name of the DataSource (e.g., "jdbc/UserDatabase")
     * @param ownerName the simple name of the calling store, used for diagnostic logging
     * @param datasourcePropertyName the property name for error reporting
     * @return the resolved DataSource
     * @throws NoRequiredPropertyException if the JNDI lookup fails
     */
    public static DataSource lookup( final String jndiName, final String ownerName,
            final String datasourcePropertyName ) throws NoRequiredPropertyException {
        try {
            final Context initCtx = new InitialContext();
            final Context ctx = (Context) initCtx.lookup( "java:comp/env" );
            return (DataSource) ctx.lookup( jndiName );
        } catch( final NamingException e ) {
            // LOG.error justified: a failed JNDI DataSource lookup is fatal — the JDBC auth store cannot initialize.
            LOG.error( "{} initialization error: {}", ownerName, e.getMessage() );
            throw new NoRequiredPropertyException( datasourcePropertyName,
                    ownerName + " initialization error: " + e.getMessage(), e );
        }
    }
}

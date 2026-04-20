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
package com.wikantik.auth.apikeys;

import com.wikantik.auth.AbstractJDBCDatabase;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.sql.DataSource;

import java.util.Properties;

/**
 * Lazily constructs and caches a process-wide {@link ApiKeyService} backed by
 * the {@code wikantik.datasource} JNDI DataSource.
 *
 * <p>Both {@code ToolsAccessFilter} and {@code McpAccessFilter} (in
 * wikantik-tools and wikantik-mcp) plus the admin REST resource need the same
 * service instance; this holder keeps the JNDI plumbing in one place and
 * avoids repeated lookups on hot request paths. Returns {@code null} when no
 * datasource is configured — callers should treat that as "DB keys disabled"
 * and fall back to their legacy config-file keys.
 */
public final class ApiKeyServiceHolder {

    private static final Logger LOG = LogManager.getLogger( ApiKeyServiceHolder.class );

    private static volatile ApiKeyService cached;

    private ApiKeyServiceHolder() { }

    /**
     * Returns the shared service, or {@code null} if the engine has no
     * database configured. Safe to call repeatedly — the first successful
     * lookup is cached for the lifetime of the classloader.
     */
    public static ApiKeyService get( final Properties engineProperties ) {
        if ( cached != null ) return cached;
        final String datasource = engineProperties != null
                ? engineProperties.getProperty( AbstractJDBCDatabase.PROP_DATASOURCE )
                : null;
        if ( datasource == null || datasource.isBlank() ) {
            return null;
        }
        synchronized ( ApiKeyServiceHolder.class ) {
            if ( cached != null ) return cached;
            try {
                final Context initCtx = new InitialContext();
                final Context ctx = ( Context ) initCtx.lookup( "java:comp/env" );
                final DataSource ds = ( DataSource ) ctx.lookup( datasource );
                final ApiKeyService svc = new ApiKeyService( ds );
                cached = svc;
                LOG.info( "ApiKeyService initialized from JNDI DataSource '{}'", datasource );
                return svc;
            } catch ( final Exception e ) {
                LOG.warn( "Could not initialize ApiKeyService from '{}': {}", datasource, e.getMessage() );
                return null;
            }
        }
    }

    /** Testing hook — replaces or clears the cached service. */
    public static void setForTesting( final ApiKeyService svc ) {
        cached = svc;
    }
}

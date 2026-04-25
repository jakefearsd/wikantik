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

import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Checks that the search/page subsystem is operational by verifying the page provider
 * is accessible. This is a lightweight check that avoids importing SearchManager
 * (which lives in wikantik-main).
 */
public class SearchIndexHealthCheck implements HealthCheck {

    private static final Logger LOG = LogManager.getLogger( SearchIndexHealthCheck.class );

    private final Engine engine;

    @SuppressFBWarnings( value = "EI_EXPOSE_REP2",
            justification = "Engine is the shared runtime singleton; the health check intentionally holds the live reference, not a copy." )
    public SearchIndexHealthCheck( final Engine engine ) {
        this.engine = engine;
    }

    @Override
    public String name() {
        return "searchIndex";
    }

    @Override
    public HealthResult check() {
        final long start = System.currentTimeMillis();
        try {
            // PageManager is the registered manager that provides storage/search access; the
            // PageProvider it wraps is not registered in the engine manager map, so we resolve
            // the manager and use it to verify the underlying storage subsystem is reachable.
            final PageManager pageManager = engine.getManager( PageManager.class );
            if ( pageManager == null ) {
                // Fallback: legacy callers may register PageProvider directly
                final PageProvider provider = engine.getManager( PageProvider.class );
                if ( provider == null ) {
                    return HealthResult.down( System.currentTimeMillis() - start, "PageManager not available" );
                }
                provider.getProviderInfo();
                return HealthResult.up( System.currentTimeMillis() - start );
            }
            // Calling getAllPages() would be too expensive; just touch a cheap accessor on the
            // manager to confirm the provider chain is alive.
            pageManager.getProvider();
            return HealthResult.up( System.currentTimeMillis() - start );
        } catch ( final Exception e ) {
            LOG.warn( "Search index health check failed", e );
            return HealthResult.down( System.currentTimeMillis() - start, "Search index check failed" );
        }
    }

}

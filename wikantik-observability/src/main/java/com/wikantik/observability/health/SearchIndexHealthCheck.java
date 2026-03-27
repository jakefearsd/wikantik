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
import com.wikantik.api.providers.PageProvider;

/**
 * Checks that the search/page subsystem is operational by verifying the page provider
 * is accessible. This is a lightweight check that avoids importing SearchManager
 * (which lives in wikantik-main).
 */
public class SearchIndexHealthCheck implements HealthCheck {

    private final Engine engine;

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
            final PageProvider provider = engine.getManager( PageProvider.class );
            if ( provider == null ) {
                return HealthResult.down( System.currentTimeMillis() - start, "PageProvider not available" );
            }
            // Calling getAllPages() would be too expensive; just verify the provider is accessible
            provider.getProviderInfo();
            return HealthResult.up( System.currentTimeMillis() - start );
        } catch ( final Exception e ) {
            return HealthResult.down( System.currentTimeMillis() - start, e.getMessage() );
        }
    }

}

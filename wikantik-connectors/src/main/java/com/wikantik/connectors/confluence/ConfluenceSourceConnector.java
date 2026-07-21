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
package com.wikantik.connectors.confluence;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.TokenAuthenticatedSourceConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/** Syncs the pages of one Confluence Cloud space into derived pages. Static-token CredentialStore
 *  consumer (credential name "api_token" + config email, HTTP Basic), resolved lazily per-poll.
 *  Fail-closed per the untrusted-enumeration contract: missing token / API failure →
 *  complete=false with the input cursor. */
public final class ConfluenceSourceConnector extends TokenAuthenticatedSourceConnector {

    private static final Logger LOG = LogManager.getLogger( ConfluenceSourceConnector.class );

    private final ConfluenceConfig config;
    private final ConfluenceApiFactory apiFactory;

    public ConfluenceSourceConnector( final String connectorId, final ConfluenceConfig config,
            final Supplier< Optional< String > > tokenSupplier, final ConfluenceApiFactory apiFactory ) {
        super( connectorId, tokenSupplier );
        this.config = config;
        this.apiFactory = apiFactory;
    }

    @Override protected String providerLabel()   { return "confluence"; }
    @Override protected String credentialLabel() { return "api_token"; }

    @Override
    protected FetchOutcome fetchItems( final String token ) throws Exception {
        final ConfluenceApi api = apiFactory.create( config.baseUrl(), config.spaceKey(),
            config.email(), token );
        final PageListing listing = api.listPages( config.maxPages() );
        if ( listing.pages().size() >= config.maxPages() ) {
            LOG.info( "confluence '{}': hit max_pages={}, truncated", connectorId(), config.maxPages() );
        }
        boolean trusted = true;
        if ( listing.skippedMalformed() > 0 ) {
            trusted = false;
            LOG.warn( "confluence '{}': {} malformed page(s) skipped in listing — batch marked incomplete, "
                + "no tombstones this cycle", connectorId(), listing.skippedMalformed() );
        }
        final List< SourceItem > items = new ArrayList<>();
        for ( final ConfluencePage p : listing.pages() ) {
            items.add( ConfluenceItems.toItem( config.baseUrl(), config.spaceKey(), p ) );
        }
        return new FetchOutcome( items, trusted );
    }
}

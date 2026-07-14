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
public final class ConfluenceSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( ConfluenceSourceConnector.class );

    private final String connectorId;
    private final ConfluenceConfig config;
    private final Supplier< Optional< String > > tokenSupplier;
    private final ConfluenceApiFactory apiFactory;

    public ConfluenceSourceConnector( final String connectorId, final ConfluenceConfig config,
            final Supplier< Optional< String > > tokenSupplier, final ConfluenceApiFactory apiFactory ) {
        this.connectorId = connectorId;
        this.config = config;
        this.tokenSupplier = tokenSupplier;
        this.apiFactory = apiFactory;
    }

    @Override public String connectorId() { return connectorId; }
    @Override public boolean reflectsFullCorpus() { return true; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token;
        try {
            token = tokenSupplier.get();
        } catch ( final RuntimeException e ) {
            // poll() never throws — even a failing credential-store lookup degrades to a skipped cycle
            LOG.warn( "confluence '{}': token lookup failed — skipping sync: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "confluence '{}': no api_token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final List< SourceItem > items = new ArrayList<>();
        try {
            final ConfluenceApi api = apiFactory.create( config.baseUrl(), config.spaceKey(),
                config.email(), token.get() );
            final List< ConfluencePage > pages = api.listPages( config.maxPages() );
            if ( pages.size() >= config.maxPages() ) {
                LOG.info( "confluence '{}': hit max_pages={}, truncated", connectorId, config.maxPages() );
            }
            for ( final ConfluencePage p : pages ) {
                items.add( ConfluenceItems.toItem( config.baseUrl(), config.spaceKey(), p ) );
            }
        } catch ( final Exception e ) {   // poll() never throws; any Confluence/HTTP error → empty INCOMPLETE batch
            LOG.warn( "confluence '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }
}

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
package com.wikantik.connectors.github;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** Syncs markdown files from a GitHub repository tree. Static-token CredentialStore consumer
 *  (credential name "token"), resolved lazily per-poll. Fail-closed per the untrusted-enumeration
 *  contract: missing token / API failure / truncated listing → complete=false with the input cursor. */
public final class GithubSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( GithubSourceConnector.class );

    private final String connectorId;
    private final GithubConfig config;
    private final Supplier< Optional< String > > tokenSupplier;
    private final GithubApiFactory apiFactory;

    public GithubSourceConnector( final String connectorId, final GithubConfig config,
            final Supplier< Optional< String > > tokenSupplier, final GithubApiFactory apiFactory ) {
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
            LOG.warn( "github '{}': token lookup failed — skipping sync: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "github '{}': no token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final List< SourceItem > items = new ArrayList<>();
        boolean trusted = true;
        try {
            final GithubApi api = apiFactory.create( config.repo(), token.get() );
            final String branch = config.branch() == null || config.branch().isBlank()
                ? api.defaultBranch() : config.branch();
            final TreeListing tree = api.listTree( branch );
            if ( tree.truncated() ) {
                trusted = false;
                LOG.warn( "github '{}': tree listing truncated by the API — batch marked incomplete, "
                    + "no tombstones this cycle", connectorId );
            }
            for ( final GithubFile f : tree.files() ) {
                if ( items.size() >= config.maxFiles() ) {
                    LOG.info( "github '{}': hit max_files={}, truncated", connectorId, config.maxFiles() );
                    break;
                }
                if ( !wanted( f.path() ) ) continue;
                final Optional< byte[] > raw = api.rawContent( f.path(), branch );
                if ( raw.isEmpty() ) continue;             // 404 = authoritative absence, no taint
                items.add( GithubItems.toItem( config.repo(), branch, f, raw.get() ) );
            }
        } catch ( final Exception e ) {   // poll() never throws; any GitHub/HTTP error → empty INCOMPLETE batch
            LOG.warn( "github '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        if ( !trusted ) {
            return new SyncBatch( items, List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
    }

    private boolean wanted( final String path ) {
        if ( !path.toLowerCase( Locale.ROOT ).endsWith( ".md" ) ) return false;
        return config.pathPrefix() == null || config.pathPrefix().isBlank()
            || path.startsWith( config.pathPrefix() );
    }
}

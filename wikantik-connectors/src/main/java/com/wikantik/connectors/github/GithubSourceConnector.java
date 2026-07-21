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
import com.wikantik.connectors.TokenAuthenticatedSourceConnector;
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
public final class GithubSourceConnector extends TokenAuthenticatedSourceConnector {

    private static final Logger LOG = LogManager.getLogger( GithubSourceConnector.class );

    private final GithubConfig config;
    private final GithubApiFactory apiFactory;

    public GithubSourceConnector( final String connectorId, final GithubConfig config,
            final Supplier< Optional< String > > tokenSupplier, final GithubApiFactory apiFactory ) {
        super( connectorId, tokenSupplier );
        this.config = config;
        this.apiFactory = apiFactory;
    }

    @Override protected String providerLabel()   { return "github"; }
    @Override protected String credentialLabel() { return "token"; }

    @Override
    protected FetchOutcome fetchItems( final String token ) throws Exception {
        final GithubApi api = apiFactory.create( config.repo(), token );
        final String branch = config.branch() == null || config.branch().isBlank()
            ? api.defaultBranch() : config.branch();
        final TreeListing tree = api.listTree( branch );
        boolean trusted = true;
        if ( tree.truncated() ) {
            trusted = false;
            LOG.warn( "github '{}': tree listing truncated by the API — batch marked incomplete, "
                + "no tombstones this cycle", connectorId() );
        }
        final List< SourceItem > items = new ArrayList<>();
        for ( final GithubFile f : tree.files() ) {
            if ( items.size() >= config.maxFiles() ) {
                LOG.info( "github '{}': hit max_files={}, truncated", connectorId(), config.maxFiles() );
                break;
            }
            if ( !wanted( f.path() ) ) continue;
            final Optional< byte[] > raw = api.rawContent( f.path(), branch );
            if ( raw.isEmpty() ) continue;             // 404 = authoritative absence, no taint
            items.add( GithubItems.toItem( config.repo(), branch, f, raw.get() ) );
        }
        return new FetchOutcome( items, trusted );
    }

    private boolean wanted( final String path ) {
        if ( !path.toLowerCase( Locale.ROOT ).endsWith( ".md" ) ) return false;
        return config.pathPrefix() == null || config.pathPrefix().isBlank()
            || path.startsWith( config.pathPrefix() );
    }
}

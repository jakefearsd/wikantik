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
package com.wikantik.connectors.gdrive;

import com.wikantik.api.connectors.*;
import com.wikantik.connectors.TokenAuthenticatedSourceConnector;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.function.Supplier;

/** Syncs configured Drive folders (recursively) into derived pages: Docs→markdown, native md/txt
 *  fetched, other binaries skipped. Resolves its OAuth2 refresh token lazily per poll (fail-closed). */
public final class DriveSourceConnector extends TokenAuthenticatedSourceConnector {

    private static final Logger LOG = LogManager.getLogger( DriveSourceConnector.class );
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String DOC_MIME    = "application/vnd.google-apps.document";
    private static final Set< String > NATIVE_TEXT = Set.of( "text/markdown", "text/x-markdown", "text/plain" );

    private final DriveConfig config;
    private final DriveApiFactory apiFactory;

    public DriveSourceConnector( final String connectorId, final DriveConfig config,
            final Supplier< Optional< String > > refreshTokenSupplier, final DriveApiFactory apiFactory ) {
        super( connectorId, refreshTokenSupplier );
        this.config = config;
        this.apiFactory = apiFactory;
    }

    @Override protected String providerLabel()   { return "gdrive"; }
    @Override protected String credentialLabel() { return "refresh_token"; }

    @Override
    protected FetchOutcome fetchItems( final String token ) throws Exception {
        final DriveApi api = apiFactory.create( config.clientId(), config.clientSecret(), token );
        final Set< String > visitedFolders = new HashSet<>();
        final List< DriveFile > files = new ArrayList<>();
        for ( final String folderId : config.folderIds() ) walk( api, folderId, files, visitedFolders );
        final List< SourceItem > items = new ArrayList<>();
        for ( final DriveFile f : files ) {
            if ( items.size() >= config.maxFiles() ) break;
            final SourceItem item = toItem( api, f );
            if ( item != null ) items.add( item );
        }
        if ( items.size() >= config.maxFiles() ) {
            LOG.info( "gdrive '{}': hit max_files={}, truncated", connectorId(), config.maxFiles() );
        }
        return new FetchOutcome( items, true );
    }

    private void walk( final DriveApi api, final String folderId, final List< DriveFile > out,
            final Set< String > visited ) throws java.io.IOException {
        if ( !visited.add( folderId ) ) return;                        // cycle / duplicate guard
        if ( out.size() >= config.maxFiles() ) return;
        for ( final DriveFile f : api.listFolder( folderId ) ) {
            if ( FOLDER_MIME.equals( f.mimeType() ) ) walk( api, f.id(), out, visited );
            else {
                out.add( f );
                if ( out.size() >= config.maxFiles() ) return;         // stop enumerating further children
            }
        }
    }

    private SourceItem toItem( final DriveApi api, final DriveFile f ) throws java.io.IOException {
        if ( DOC_MIME.equals( f.mimeType() ) ) {
            return DriveItems.toItem( f, api.export( f.id(), config.exportMimeType() ), config.exportMimeType() );
        }
        if ( NATIVE_TEXT.contains( f.mimeType() ) ) {
            return DriveItems.toItem( f, api.getMedia( f.id() ), f.mimeType() );
        }
        LOG.info( "gdrive '{}': skipping unsupported type {} ({})", connectorId(), f.mimeType(), f.name() );
        return null;
    }
}

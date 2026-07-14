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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.function.Supplier;

/** Syncs configured Drive folders (recursively) into derived pages: Docs→markdown, native md/txt
 *  fetched, other binaries skipped. Resolves its OAuth2 refresh token lazily per poll (fail-closed). */
public final class DriveSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( DriveSourceConnector.class );
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String DOC_MIME    = "application/vnd.google-apps.document";
    private static final Set< String > NATIVE_TEXT = Set.of( "text/markdown", "text/x-markdown", "text/plain" );

    private final String connectorId;
    private final DriveConfig config;
    private final Supplier< Optional< String > > refreshTokenSupplier;
    private final DriveApiFactory apiFactory;

    public DriveSourceConnector( final String connectorId, final DriveConfig config,
            final Supplier< Optional< String > > refreshTokenSupplier, final DriveApiFactory apiFactory ) {
        this.connectorId = connectorId;
        this.config = config;
        this.refreshTokenSupplier = refreshTokenSupplier;
        this.apiFactory = apiFactory;
    }

    @Override public String connectorId() { return connectorId; }
    @Override public boolean reflectsFullCorpus() { return true; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final Optional< String > token = refreshTokenSupplier.get();
        if ( token.isEmpty() || token.get().isBlank() ) {
            LOG.warn( "gdrive '{}': no refresh_token available (credential store disabled or token not set) — "
                + "skipping sync", connectorId );
            // complete=false: "couldn't enumerate" must never read as "source is empty" — an empty
            // COMPLETE batch from a full-corpus connector would tombstone every previously-synced page.
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        final List< SourceItem > items = new ArrayList<>();
        try {
            final DriveApi api = apiFactory.create( config.clientId(), config.clientSecret(), token.get() );
            final Set< String > visitedFolders = new HashSet<>();
            final List< DriveFile > files = new ArrayList<>();
            for ( final String folderId : config.folderIds() ) walk( api, folderId, files, visitedFolders );
            for ( final DriveFile f : files ) {
                if ( items.size() >= config.maxFiles() ) break;
                final SourceItem item = toItem( api, f );
                if ( item != null ) items.add( item );
            }
            if ( items.size() >= config.maxFiles() ) {
                LOG.info( "gdrive '{}': hit max_files={}, truncated", connectorId, config.maxFiles() );
            }
        } catch ( final Exception e ) {   // poll() never throws; any Drive/OAuth error → empty INCOMPLETE batch
            LOG.warn( "gdrive '{}': sync failed, skipping cycle: {}", connectorId, e.getMessage() );
            return new SyncBatch( List.of(), List.of(), cursor, false );   // untrusted → no tombstone derivation
        }
        return new SyncBatch( items, List.of(), new SyncCursor( String.valueOf( items.size() ) ), true );
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
        LOG.info( "gdrive '{}': skipping unsupported type {} ({})", connectorId, f.mimeType(), f.name() );
        return null;
    }
}

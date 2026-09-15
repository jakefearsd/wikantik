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
package com.wikantik.connectors.filesystem;

import com.wikantik.connectors.ItemDigest;

import com.wikantik.api.connectors.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Phase-1 fixture connector: full-scans a directory tree, one {@link SourceItem} per file. */
public final class FilesystemSourceConnector implements SourceConnector {

    private static final Logger LOG = LogManager.getLogger( FilesystemSourceConnector.class );

    private final String connectorId;
    private final Path root;

    public FilesystemSourceConnector( final String connectorId, final Path root ) {
        this.connectorId = connectorId;
        this.root = root;
    }

    @Override public String connectorId() { return connectorId; }

    @Override
    public SyncBatch poll( final SyncCursor cursor ) {
        final List< SourceItem > items = new ArrayList<>();
        try ( Stream< Path > walk = Files.walk( root ) ) {
            walk.filter( Files::isRegularFile ).forEach( p -> add( items, p ) );
        } catch ( final IOException e ) {
            LOG.warn( "Filesystem connector '{}' scan of {} failed: {}", connectorId, root, e.getMessage() );
            // fail-closed: an empty complete batch — the orchestrator would then tombstone everything,
            // which is wrong on a scan error, so signal incomplete to skip tombstone derivation.
            return new SyncBatch( List.of(), List.of(), cursor, false );
        }
        return new SyncBatch( items, List.of(), new SyncCursor( scanWatermark() ), true );
    }

    private void add( final List< SourceItem > items, final Path file ) {
        try {
            final Path rel = root.relativize( file );
            final String relStr = rel.toString().replace( '\\', '/' );
            final byte[] content = Files.readAllBytes( file );
            final Map< String, Object > md = new LinkedHashMap<>();
            md.put( "path", relStr );
            md.put( "size", content.length );
            md.put( "modified", Files.getLastModifiedTime( file ).toString() );
            items.add( new SourceItem( "file:" + relStr, content, contentType( relStr ), md, parentDirAcl( rel, file ), ItemDigest.sha256Hex( content ) ) );
        } catch ( final IOException e ) {
            LOG.warn( "Filesystem connector '{}' could not read {}: {}", connectorId, file, e.getMessage() );
        }
    }

    /**
     * The single-segment ACL derived from the immediate parent directory name, or an
     * empty list when the item sits directly under {@code root} (no parent) or when the
     * parent path unexpectedly has no file-name component (an empty/root-like relative
     * path) — {@link Path#getFileName()} is nullable and must not be dereferenced blindly.
     */
    private List< String > parentDirAcl( final Path rel, final Path file ) {
        final Path parent = rel.getParent();
        if ( parent == null ) {
            return List.of();
        }
        final Path parentFileName = parent.getFileName();
        if ( parentFileName == null ) {
            LOG.warn( "Filesystem connector '{}': parent path of {} has no file-name component; skipping ACL", connectorId, file );
            return List.of();
        }
        return List.of( parentFileName.toString() );
    }

    private static String contentType( final String path ) {
        if ( path.endsWith( ".md" ) )   return "text/markdown";
        if ( path.endsWith( ".txt" ) )  return "text/plain";
        if ( path.endsWith( ".pdf" ) )  return "application/pdf";
        if ( path.endsWith( ".html" ) ) return "text/html";
        return "application/octet-stream";
    }

    private String scanWatermark() {
        // A monotonically-changing token so callers can see the cursor advanced. Time is unavailable in
        // deterministic tests, so derive from the root's identity + a scan counter would drift — use the
        // greatest last-modified time seen, which is stable and meaningful.
        try ( Stream< Path > walk = Files.walk( root ) ) {
            return walk.filter( Files::isRegularFile )
                .map( p -> { try { return Files.getLastModifiedTime( p ).toMillis(); } catch ( IOException e ) { return 0L; } } )
                .max( Long::compareTo ).map( String::valueOf ).orElse( "0" );
        } catch ( final IOException e ) {
            return "0";
        }
    }

}

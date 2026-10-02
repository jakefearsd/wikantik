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
package com.wikantik.importer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves link and embed targets for the plan. Notes are rewritten in vault order and only NEW notes are rewritten, so
 * the owner of an attachment is always the first NEW page that references it.
 */
final class PlanTargets implements VaultTargets {

    private final Map< String, String > names;
    private final Map< String, PageStatus > statuses;
    private final WikiSnapshot snapshot;
    private final VaultLinkIndex index;
    private final AttachmentGate gate;
    private final Map< String, VaultFile > filesByPath;
    private final Map< String, String > owners = new HashMap<>();
    private final Map< String, String > fileNames = new HashMap<>();
    private final Map< String, Set< String > > namesByOwner = new HashMap<>();
    private final Set< String > blocked = new LinkedHashSet<>();
    private String currentPage;

    PlanTargets( final Map< String, String > names, final Map< String, PageStatus > statuses, final WikiSnapshot snapshot,
                 final VaultLinkIndex index, final AttachmentGate gate, final Map< String, VaultFile > filesByPath ) {
        this.names = names;
        this.statuses = statuses;
        this.snapshot = snapshot;
        this.index = index;
        this.gate = gate;
        this.filesByPath = filesByPath;
    }

    void currentPage( final String name ) {
        currentPage = name;
    }

    Set< String > blocked() {
        return blocked;
    }

    String owner( final String filePath ) {
        return owners.get( filePath );
    }

    String fileName( final String filePath ) {
        return fileNames.get( filePath );
    }

    @Override
    public LinkTarget page( final String t, final String from, final boolean relative ) {
        final Optional< String > path = relative ? index.resolveNoteRelative( t, from ) : index.resolveNote( t );
        if ( path.isPresent() ) {
            return forNote( path.get(), t );
        }
        return snapshot.existingPage( t ).isPresent() ? LinkTarget.keep() : LinkTarget.unresolved();
    }

    private LinkTarget forNote( final String path, final String written ) {
        return switch ( statuses.get( path ) ) {
            case NEW, WILL_FAIL -> LinkTarget.rename( names.get( path ) );
            case SKIPPED_EXISTS -> {
                final String existing = snapshot.existingPage( names.get( path ) ).orElseThrow();
                yield existing.equalsIgnoreCase( written ) ? LinkTarget.keep() : LinkTarget.rename( existing );
            }
            case SKIPPED_RESERVED -> LinkTarget.keep();
        };
    }

    @Override
    public LinkTarget attachment( final String t, final String from, final boolean relative ) {
        final Optional< String > path = relative ? index.resolveFileRelative( t, from ) : index.resolveFile( t );
        if ( path.isEmpty() ) {
            return LinkTarget.unresolved();
        }
        final VaultFile file = filesByPath.get( path.get() );
        if ( gate.rejection( VaultPaths.basename( file.path() ), file.size() ).isPresent() ) {
            blocked.add( file.path() );
            return LinkTarget.keep();
        }
        final String owner = owners.computeIfAbsent( file.path(), p -> currentPage );
        final String fileName = fileNames.computeIfAbsent( file.path(), p -> allocate( owner, file.path() ) );
        return LinkTarget.rename( owner + "/" + fileName );
    }

    /** {@code name.ext}, or {@code name 2.ext}, {@code name 3.ext} when the owner already uses the name (case-insensitively). */
    private String allocate( final String owner, final String filePath ) {
        final String base = VaultNames.attachmentName( VaultPaths.basename( filePath ) );
        final Set< String > used = namesByOwner.computeIfAbsent( owner, o -> new HashSet<>() );
        final int dot = base.lastIndexOf( '.' );
        final String stem = dot > 0 ? base.substring( 0, dot ) : base;
        final String ext = dot > 0 ? base.substring( dot ) : "";
        String candidate = base;
        for ( int n = 2; !used.add( candidate.toLowerCase( Locale.ROOT ) ); n++ ) {
            candidate = stem + " " + n + ext;
        }
        return candidate;
    }
}

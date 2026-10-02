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

import com.wikantik.util.WikiPageNameValidator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Assigns each vault note a unique, legal wiki page name. */
public final class VaultNoteNamer {

    private final WikiSnapshot snapshot;
    private final Set< String > taken = new HashSet<>();

    public VaultNoteNamer( final WikiSnapshot snapshot ) {
        this.snapshot = snapshot;
    }

    /** Vault path to page name, in {@link VaultPaths#ORDER}. Existing wiki pages are not considered here. */
    public Map< String, String > assign( final List< String > notePaths ) {
        final List< String > sorted = new ArrayList<>( notePaths );
        sorted.sort( VaultPaths.ORDER );
        final Map< String, String > names = new LinkedHashMap<>();
        for ( final String path : sorted ) {
            final String name = nameFor( path );
            taken.add( name.toLowerCase( Locale.ROOT ) );
            names.put( path, name );
        }
        return names;
    }

    /** A legal name not used by any assigned note nor any existing wiki page. */
    public String allocateGenerated( final String desired ) {
        final String name = number( VaultNames.legalPageName( desired ), true );
        taken.add( name.toLowerCase( Locale.ROOT ) );
        return name;
    }

    private String nameFor( final String path ) {
        String candidate = VaultNames.legalPageName( VaultPaths.withoutMd( VaultPaths.basename( path ) ) );
        final String folder = VaultPaths.parentFolder( path );
        if ( isTaken( candidate, false ) && !folder.isEmpty() ) {
            candidate = fit( candidate, " (" + VaultNames.legalPageName( VaultPaths.basename( folder ) ) + ")" );
        }
        return number( candidate, false );
    }

    private String number( final String start, final boolean checkWiki ) {
        String candidate = start;
        for ( int n = 2; isTaken( candidate, checkWiki ); n++ ) {
            candidate = fit( start, " " + n );
        }
        return candidate;
    }

    private boolean isTaken( final String candidate, final boolean checkWiki ) {
        return taken.contains( candidate.toLowerCase( Locale.ROOT ) )
            || checkWiki && snapshot.existingPage( candidate ).isPresent();
    }

    private static String fit( final String base, final String suffix ) {
        final int room = WikiPageNameValidator.MAX_LENGTH - suffix.length();
        final String head = base.length() > room ? base.substring( 0, room ).trim() : base;
        return head + suffix;
    }
}

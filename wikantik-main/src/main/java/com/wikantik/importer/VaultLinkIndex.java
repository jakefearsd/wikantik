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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves Obsidian link targets to vault notes and files (case-insensitive, NFC-normalised, shortest path wins).
 * Lookups are O(1): a suffix index maps every {@code /}-boundary suffix of each key to its sorted candidates.
 */
public final class VaultLinkIndex {

    private static final Comparator< String > SHORTEST_THEN_ORDER =
        Comparator.< String >comparingInt( String::length ).thenComparing( VaultPaths.ORDER );

    private final Index notes;
    private final Index files;

    public VaultLinkIndex( final Collection< String > notePaths, final Collection< String > filePaths ) {
        notes = new Index( notePaths, true );
        files = new Index( filePaths, false );
    }

    /** Resolves a link target (no fragment; {@code .md} optional) to a note path. */
    public Optional< String > resolveNote( final String target ) {
        return notes.resolve( target );
    }

    /** Resolves a full file name (with extension) to an attachment path. */
    public Optional< String > resolveFile( final String target ) {
        return files.resolve( target );
    }

    /** Resolves a markdown-link target relative to the note at {@code fromPath}, then vault-wide by name. */
    public Optional< String > resolveNoteRelative( final String target, final String fromPath ) {
        return notes.resolveRelative( target, fromPath );
    }

    /** Resolves a markdown-link file target relative to the note at {@code fromPath}, then vault-wide by name. */
    public Optional< String > resolveFileRelative( final String target, final String fromPath ) {
        return files.resolveRelative( target, fromPath );
    }

    /** Collapses {@code .} and {@code ..} segments; a {@code ..} above the root is dropped when {@code lenient}, else yields empty. */
    private static Optional< String > normalise( final String path, final boolean lenient ) {
        final Deque< String > stack = new ArrayDeque<>();
        for ( final String seg : path.split( "/" ) ) {
            if ( seg.isEmpty() || ".".equals( seg ) ) {
                continue;
            }
            if ( !"..".equals( seg ) ) {
                stack.addLast( seg );
            } else if ( !stack.isEmpty() ) {
                stack.removeLast();
            } else if ( !lenient ) {
                return Optional.empty();
            }
        }
        return Optional.of( String.join( "/", stack ) );
    }

    private static final class Index {
        private final boolean note;
        private final Map< String, String > byKey = new HashMap<>();
        private final Map< String, List< String > > bySuffix = new HashMap<>();

        Index( final Collection< String > paths, final boolean note ) {
            this.note = note;
            final List< String > sorted = new ArrayList<>( paths );
            sorted.sort( VaultPaths.ORDER );
            for ( final String path : sorted ) {
                byKey.putIfAbsent( key( path ), path );
            }
            for ( final Map.Entry< String, String > e : byKey.entrySet() ) {
                final String k = e.getKey();
                for ( int i = k.indexOf( '/' ); i >= -1; i = k.indexOf( '/', i + 1 ) ) {
                    bySuffix.computeIfAbsent( k.substring( i + 1 ), x -> new ArrayList<>() ).add( e.getValue() );
                    if ( i < 0 ) {
                        break;
                    }
                }
            }
            bySuffix.values().forEach( l -> l.sort( SHORTEST_THEN_ORDER ) );
        }

        Optional< String > resolve( final String target ) {
            final String stripped = target.startsWith( "/" ) ? target.substring( 1 ) : target;
            final String k = key( stripped );
            final String exact = byKey.get( k );
            if ( exact != null ) {
                return Optional.of( exact );
            }
            final List< String > hits = bySuffix.get( k );
            return hits == null ? Optional.empty() : Optional.of( hits.get( 0 ) );
        }

        Optional< String > resolveRelative( final String target, final String fromPath ) {
            final String parent = VaultPaths.parentFolder( VaultNames.nfc( fromPath ) );
            final Optional< String > joined = normalise( parent.isEmpty() ? target : parent + "/" + target, false );
            final String hit = joined.map( j -> byKey.get( key( j ) ) ).orElse( null );
            if ( hit != null ) {
                return Optional.of( hit );
            }
            final String name = normalise( target, true ).orElse( target );
            final Optional< String > byPath = resolve( name );
            return byPath.isPresent() ? byPath : resolve( VaultPaths.basename( name ) );
        }

        private String key( final String path ) {
            final String nfc = VaultNames.nfc( path );
            return ( note ? VaultPaths.withoutMd( nfc ) : nfc ).toLowerCase( Locale.ROOT );
        }
    }
}

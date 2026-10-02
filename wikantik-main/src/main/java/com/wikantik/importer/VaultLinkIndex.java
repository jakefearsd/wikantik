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

/** Resolves Obsidian link targets to vault notes and files (case-insensitive, shortest path wins). */
public final class VaultLinkIndex {

    private static final Comparator< String > SHORTEST_THEN_ORDER =
        Comparator.< String >comparingInt( String::length ).thenComparing( VaultPaths.ORDER );

    private final Map< String, String > noteByKey = new HashMap<>();
    private final Map< String, String > fileByKey = new HashMap<>();

    public VaultLinkIndex( final Collection< String > notePaths, final Collection< String > filePaths ) {
        index( noteByKey, notePaths, true );
        index( fileByKey, filePaths, false );
    }

    private static void index( final Map< String, String > map, final Collection< String > paths, final boolean note ) {
        final List< String > sorted = new ArrayList<>( paths );
        sorted.sort( VaultPaths.ORDER );
        for ( final String path : sorted ) {
            map.putIfAbsent( key( path, note ), path );
        }
    }

    /** Resolves a link target (no fragment; {@code .md} optional) to a note path. */
    public Optional< String > resolveNote( final String target ) {
        return resolve( noteByKey, target, true );
    }

    /** Resolves a full file name (with extension) to an attachment path. */
    public Optional< String > resolveFile( final String target ) {
        return resolve( fileByKey, target, false );
    }

    /** Resolves a markdown-link target relative to the note at {@code fromPath}, then vault-wide. */
    public Optional< String > resolveNoteRelative( final String target, final String fromPath ) {
        return resolveRelative( noteByKey, target, fromPath, true );
    }

    /** Resolves a markdown-link file target relative to the note at {@code fromPath}, then vault-wide. */
    public Optional< String > resolveFileRelative( final String target, final String fromPath ) {
        return resolveRelative( fileByKey, target, fromPath, false );
    }

    private static Optional< String > resolveRelative( final Map< String, String > map, final String target,
                                                       final String fromPath, final boolean note ) {
        final String parent = VaultPaths.parentFolder( fromPath );
        final Optional< String > joined = normalise( parent.isEmpty() ? target : parent + "/" + target );
        if ( joined.isEmpty() ) {
            return Optional.empty();
        }
        final String hit = map.get( key( joined.get(), note ) );
        return hit != null ? Optional.of( hit ) : resolve( map, target, note );
    }

    /** Collapses {@code .} and {@code ..} segments; empty when the path climbs out of the vault root. */
    private static Optional< String > normalise( final String path ) {
        final Deque< String > stack = new ArrayDeque<>();
        for ( final String seg : path.split( "/" ) ) {
            if ( seg.isEmpty() || ".".equals( seg ) ) {
                continue;
            }
            if ( "..".equals( seg ) ) {
                if ( stack.isEmpty() ) {
                    return Optional.empty();
                }
                stack.removeLast();
            } else {
                stack.addLast( seg );
            }
        }
        return Optional.of( String.join( "/", stack ) );
    }

    private static Optional< String > resolve( final Map< String, String > map, final String target, final boolean note ) {
        final String stripped = target.startsWith( "/" ) ? target.substring( 1 ) : target;
        final String k = key( stripped, note );
        final String exact = map.get( k );
        if ( exact != null ) {
            return Optional.of( exact );
        }
        return map.entrySet().stream()
            .filter( e -> k.indexOf( '/' ) >= 0 ? e.getKey().endsWith( "/" + k ) : basename( e.getKey() ).equals( k ) )
            .map( Map.Entry::getValue )
            .min( SHORTEST_THEN_ORDER );
    }

    private static String basename( final String key ) {
        return key.substring( key.lastIndexOf( '/' ) + 1 );
    }

    private static String key( final String path, final boolean note ) {
        return ( note ? VaultPaths.withoutMd( path ) : path ).toLowerCase( Locale.ROOT );
    }
}

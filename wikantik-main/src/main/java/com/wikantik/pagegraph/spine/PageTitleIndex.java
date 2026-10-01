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
package com.wikantik.pagegraph.spine;

import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.util.TextUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Immutable title/alias index over the structural projection: feeds {@code GET /api/pages?q=} ranking and
 * the unlinked-mention scan. Rebuilt lazily by {@link DefaultStructuralIndexService} whenever its projection
 * or alias map changes.
 */
public final class PageTitleIndex implements PageTitleLookup {

    private static final int EXACT = 0;
    private static final int PREFIX = 1;
    private static final int SUBSTRING = 2;
    private static final int SUBSEQUENCE = 3;
    private static final int NO_MATCH = Integer.MAX_VALUE;

    private final List< TitleEntry > entries;
    private final Map< String, List< String > > normalizedKeysBySlug;

    private PageTitleIndex( final List< TitleEntry > entries ) {
        this.entries = List.copyOf( entries );
        final Map< String, List< String > > keys = new HashMap<>();
        for ( final TitleEntry e : entries ) {
            keys.put( e.slug(), normalizedKeys( e.slug(), e.phrases() ) );
        }
        this.normalizedKeysBySlug = Map.copyOf( keys );
    }

    public static PageTitleIndex of( final Collection< PageDescriptor > pages,
                                     final Map< String, List< String > > aliasesBySlug ) {
        final List< TitleEntry > out = new ArrayList<>( pages.size() );
        for ( final PageDescriptor p : pages ) {
            final Set< String > phrases = new LinkedHashSet<>();
            phrases.add( phraseOf( p.slug() ) );
            if ( p.title() != null && !p.title().isBlank() && !p.title().equals( p.slug() ) ) {
                phrases.add( p.title().trim() );
            }
            for ( final String a : aliasesBySlug.getOrDefault( p.slug(), List.of() ) ) {
                if ( a != null && !a.isBlank() ) {
                    phrases.add( a.trim() );
                }
            }
            final String title = p.title() == null || p.title().isBlank() || p.title().equals( p.slug() )
                    ? phraseOf( p.slug() ) : p.title().trim();
            out.add( new TitleEntry( p.slug(), title, List.copyOf( phrases ) ) );
        }
        return new PageTitleIndex( out );
    }

    /** De-CamelCased page name, e.g. {@code LowCostIndexFundInvesting} → {@code Low Cost Index Fund Investing}. */
    public static String phraseOf( final String slug ) {
        return TextUtil.beautifyString( slug );
    }

    @Override
    public List< TitleEntry > entries() {
        return entries;
    }

    @Override
    public List< String > rank( final Collection< String > names, final String query ) {
        final Comparator< String > natural = Comparator.naturalOrder();
        final String needle = normalize( query == null ? "" : query );
        if ( needle.isEmpty() ) {
            return names.stream().sorted( natural ).toList();
        }
        final Map< String, Integer > tiers = new HashMap<>();
        for ( final String name : names ) {
            final List< String > keys = normalizedKeysBySlug.getOrDefault( name,
                    normalizedKeys( name, List.of( phraseOf( name ) ) ) );
            int best = NO_MATCH;
            for ( final String key : keys ) {
                best = Math.min( best, tier( key, needle ) );
            }
            if ( best != NO_MATCH ) {
                tiers.put( name, best );
            }
        }
        return tiers.keySet().stream()
                .sorted( Comparator.comparingInt( ( String n ) -> tiers.get( n ) ).thenComparing( natural ) )
                .toList();
    }

    private static List< String > normalizedKeys( final String slug, final List< String > phrases ) {
        final Set< String > keys = new LinkedHashSet<>();
        keys.add( normalize( slug ) );
        phrases.forEach( p -> keys.add( normalize( p ) ) );
        return List.copyOf( keys );
    }

    private static String normalize( final String s ) {
        return s.toLowerCase( Locale.ROOT ).replaceAll( "\\s+", "" );
    }

    private static int tier( final String key, final String needle ) {
        if ( key.equals( needle ) ) return EXACT;
        if ( key.startsWith( needle ) ) return PREFIX;
        if ( key.contains( needle ) ) return SUBSTRING;
        return isSubsequence( needle, key ) ? SUBSEQUENCE : NO_MATCH;
    }

    private static boolean isSubsequence( final String needle, final String key ) {
        int i = 0;
        for ( int j = 0; j < key.length() && i < needle.length(); j++ ) {
            if ( key.charAt( j ) == needle.charAt( i ) ) {
                i++;
            }
        }
        return i == needle.length();
    }
}

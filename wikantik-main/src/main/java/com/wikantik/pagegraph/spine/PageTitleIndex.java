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
import java.util.Map;
import java.util.Objects;
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
    /** Per slug, the inputs each entry was built from, so the next rebuild can reuse unchanged entries. */
    private final Map< String, Source > sources;
    /** Match keys for {@link #rank}; built on first use (the wikilink resolver, rebuilt after every save, never ranks). */
    private volatile Map< String, List< MatchKey > > normalizedKeysBySlug;

    private record Source( String title, List< String > aliases, TitleEntry entry ) {}

    private PageTitleIndex( final List< TitleEntry > entries, final Map< String, Source > sources ) {
        this.entries = List.copyOf( entries );
        this.sources = sources;
    }

    public static PageTitleIndex of( final Collection< PageDescriptor > pages,
                                     final Map< String, List< String > > aliasesBySlug ) {
        return of( pages, aliasesBySlug, null );
    }

    /**
     * As {@link #of(Collection, Map)}, reusing {@code previous}'s entry for every page whose title and aliases are
     * unchanged, and returning {@code previous} itself when nothing changed (the common case after a body-only
     * save), so whatever callers cached against that instance stays valid.
     */
    public static PageTitleIndex of( final Collection< PageDescriptor > pages,
                                     final Map< String, List< String > > aliasesBySlug,
                                     final PageTitleIndex previous ) {
        final List< TitleEntry > out = new ArrayList<>( pages.size() );
        final Map< String, Source > sources = new HashMap<>( pages.size() * 2 );
        boolean unchanged = previous != null && previous.sources.size() == pages.size();
        for ( final PageDescriptor p : pages ) {
            final List< String > aliases = aliasesBySlug.getOrDefault( p.slug(), List.of() );
            final Source prior = previous == null ? null : previous.sources.get( p.slug() );
            final Source src = prior != null && Objects.equals( prior.title(), p.title() ) && prior.aliases().equals( aliases )
                    ? prior : new Source( p.title(), aliases, entryOf( p, aliases ) );
            unchanged &= src == prior;
            sources.put( p.slug(), src );
            out.add( src.entry() );
        }
        if ( unchanged && sources.size() == pages.size() ) {
            return previous;
        }
        return new PageTitleIndex( out, sources );
    }

    private static TitleEntry entryOf( final PageDescriptor p, final List< String > aliases ) {
        final Set< String > phrases = new LinkedHashSet<>();
        phrases.add( phraseOf( p.slug() ) );
        if ( p.title() != null && !p.title().isBlank() && !p.title().equals( p.slug() ) ) {
            phrases.add( p.title().trim() );
        }
        for ( final String a : aliases ) {
            if ( a != null && !a.isBlank() ) {
                phrases.add( a.trim() );
            }
        }
        final String title = p.title() == null || p.title().isBlank() || p.title().equals( p.slug() )
                ? phraseOf( p.slug() ) : p.title().trim();
        return new TitleEntry( p.slug(), title, List.copyOf( phrases ) );
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
            final List< MatchKey > known = normalizedKeys().get( name );
            final List< MatchKey > keys = known != null ? known : normalizedKeys( name, List.of( phraseOf( name ) ) );
            int best = NO_MATCH;
            for ( final MatchKey key : keys ) {
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

    private Map< String, List< MatchKey > > normalizedKeys() {
        Map< String, List< MatchKey > > keys = normalizedKeysBySlug;
        if ( keys == null ) { // benign race: concurrent first callers build identical maps
            final Map< String, List< MatchKey > > built = new HashMap<>();
            for ( final TitleEntry e : entries ) {
                built.put( e.slug(), normalizedKeys( e.slug(), e.phrases() ) );
            }
            keys = Map.copyOf( built );
            normalizedKeysBySlug = keys;
        }
        return keys;
    }

    /** Match keys built from the ORIGINAL-case slug and phrases, so CamelCase word starts survive. */
    private static List< MatchKey > normalizedKeys( final String slug, final List< String > phrases ) {
        final Set< String > originals = new LinkedHashSet<>();
        originals.add( slug );
        originals.addAll( phrases );
        return originals.stream().map( MatchKey::of ).toList();
    }

    private static String normalize( final String s ) {
        return MatchKey.normalize( s );
    }

    private static int tier( final MatchKey key, final String needle ) {
        final String text = key.text();
        if ( text.equals( needle ) ) return EXACT;
        if ( text.startsWith( needle ) ) return PREFIX;
        if ( text.contains( needle ) ) return SUBSTRING;
        return key.matchesWordStarts( needle ) ? SUBSEQUENCE : NO_MATCH;
    }
}

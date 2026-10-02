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
package com.wikantik.wikilink;

import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.pagegraph.PageTitleLookup;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;
import com.wikantik.parser.MarkupParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Resolves the target of an Obsidian-style {@code [[target]]} to a page name: exact (including
 * plural matching), then case-insensitive page name, then case-insensitive title/alias phrase.
 * Case and phrase collisions resolve deterministically to the lexicographically lowest page name.
 */
public final class WikiLinkResolver {

    private static final Logger LOG = LogManager.getLogger( WikiLinkResolver.class );

    /** Exact page lookup; {@code Engine#getFinalPageName} in production (keeps plural matching). */
    @FunctionalInterface
    public interface ExactLookup {
        String finalPageName( String name ) throws ProviderException;
    }

    /** Outcome of a resolution; {@code pageName} is a cleaned display name when {@code exists} is false. */
    public record Resolution( String pageName, boolean exists ) {}

    private record Folded( PageTitleLookup source, Map< String, String > byName, Map< String, String > byPhrase ) {}

    private static final Pattern WHITESPACE = Pattern.compile( "\\s+" );

    private static final AtomicReference< Folded > FOLDED = new AtomicReference<>();

    private final ExactLookup exact;
    private final Supplier< Optional< PageTitleLookup > > titles;

    public WikiLinkResolver( final ExactLookup exact, final Supplier< Optional< PageTitleLookup > > titles ) {
        this.exact = exact;
        this.titles = titles;
    }

    public static WikiLinkResolver forEngine( final Engine engine ) {
        return new WikiLinkResolver( engine::getFinalPageName, () -> titleLookup( engine ) );
    }

    private static Optional< PageTitleLookup > titleLookup( final Engine engine ) {
        try {
            final StructuralIndexService svc = PageGraphSubsystemBridge.fromLegacyEngine( engine ).structuralIndexService();
            return svc == null ? Optional.empty() : svc.titleLookup();
        } catch ( final RuntimeException e ) {
            LOG.warn( "Title index unavailable for wikilink resolution: {}", e.getMessage() );
            return Optional.empty();
        }
    }

    /** Resolves {@code target}; never throws and never returns null. */
    public Resolution resolve( final String target ) {
        final String t = target == null ? "" : target.strip();
        if ( t.isEmpty() ) {
            return new Resolution( "", false );
        }
        final String exactName = exactName( t );
        if ( exactName != null ) {
            return new Resolution( exactName, true );
        }
        final Optional< Folded > folded = foldedIndex( t );
        final String key = key( t );
        final String byName = folded.map( f -> f.byName().get( key ) ).orElse( null );
        if ( byName != null ) {
            return new Resolution( byName, true );
        }
        final String byPhrase = folded.map( f -> f.byPhrase().get( key ) ).orElse( null );
        return byPhrase != null ? new Resolution( byPhrase, true ) : new Resolution( MarkupParser.cleanLink( t ), false );
    }

    private String exactName( final String t ) {
        try {
            return exact.finalPageName( t );
        } catch ( final ProviderException | RuntimeException e ) {
            LOG.warn( "Wikilink exact lookup failed for '{}': {}", t, e.getMessage() );
            return null;
        }
    }

    private Optional< Folded > foldedIndex( final String t ) {
        try {
            return titles.get().map( WikiLinkResolver::fold );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Wikilink title index failed for '{}': {}", t, e.getMessage() );
            return Optional.empty();
        }
    }

    static String key( final String s ) {
        return WHITESPACE.matcher( s ).replaceAll( " " ).strip().toLowerCase( Locale.ROOT );
    }

    private static Folded fold( final PageTitleLookup lookup ) {
        final Folded cached = FOLDED.get();
        if ( cached != null && cached.source() == lookup ) {
            return cached;
        }
        final Map< String, String > byName = new HashMap<>();
        final Map< String, String > byPhrase = new HashMap<>();
        for ( final PageTitleLookup.TitleEntry e : lookup.entries() ) {
            byName.merge( key( e.slug() ), e.slug(), WikiLinkResolver::lowest );
            for ( final String phrase : e.phrases() ) {
                byPhrase.merge( key( phrase ), e.slug(), WikiLinkResolver::lowest );
            }
        }
        final Folded built = new Folded( lookup, byName, byPhrase );
        FOLDED.set( built );
        return built;
    }

    private static String lowest( final String a, final String b ) {
        return a.compareTo( b ) <= 0 ? a : b;
    }
}

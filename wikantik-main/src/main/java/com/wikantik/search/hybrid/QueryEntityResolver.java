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
package com.wikantik.search.hybrid;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves free-text search queries to {@link java.util.UUID} KG node ids via
 * case-insensitive exact-name matching on {@code kg_nodes.name}. The resolver
 * generates 1-, 2-, and 3-token windows from the query and looks them up in
 * one SQL round-trip; results are memoized in a Caffeine cache sized and
 * TTL'd from {@link GraphRerankConfig}.
 *
 * <p>The resolver deliberately only matches exact node names (lower-cased on
 * both sides). Trigram / fuzzy name matching is deferred — the priority for
 * Phase 3 is operational safety: a typo in a query must not silently invent
 * entity matches that warp the ranking. A caller that wants fuzzier recall
 * can plug in a different implementation against the neighbor-scorer.</p>
 */
public class QueryEntityResolver {

    private static final Logger LOG = LogManager.getLogger( QueryEntityResolver.class );

    /** At most this many tokens per window — 3-grams cover the vast majority of entity names. */
    private static final int MAX_WINDOW = 3;

    /** Cap on distinct candidate strings passed to the name lookup, to bound query size. */
    private static final int MAX_CANDIDATES = 64;

    /** Ignored single-token candidates — common stop-word-shaped strings that explode name lookups. */
    private static final Set< String > STOP_SINGLES = Set.of(
        "the", "a", "an", "of", "and", "or", "for", "in", "on", "at", "to", "by",
        "is", "are", "was", "were", "be", "been", "with", "from", "that", "this"
    );

    private static final String SELECT_IDS_SQL =
        "SELECT id FROM kg_nodes WHERE LOWER( name ) = ANY( ? )";

    private final DataSource dataSource;
    private final Cache< String, Set< UUID > > cache;

    public QueryEntityResolver( final DataSource dataSource, final GraphRerankConfig cfg ) {
        if( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if( cfg == null ) throw new IllegalArgumentException( "cfg must not be null" );
        this.dataSource = dataSource;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite( Duration.ofSeconds( cfg.queryEntityCacheTtlSeconds() ) )
                .maximumSize( cfg.queryEntityCacheMax() )
                .build();
    }

    /**
     * Returns the set of node ids the query's tokens map to, after normalizing
     * and windowing. Empty query or no matches produce an empty set. Never
     * throws — SQL or DB failures are logged and collapse to an empty result
     * so the caller's rerank step degrades to a no-op rather than a 500.
     */
    public Set< UUID > resolve( final String query ) {
        if( query == null || query.isBlank() ) return Set.of();
        final String key = query.trim().toLowerCase( Locale.ROOT );
        final Set< UUID > cached = cache.getIfPresent( key );
        if( cached != null ) return cached;

        final List< String > candidates = tokenWindows( key );
        if( candidates.isEmpty() ) {
            cache.put( key, Set.of() );
            return Set.of();
        }
        final Set< UUID > ids = lookup( candidates );
        cache.put( key, ids );
        return ids;
    }

    /** Test hook: invalidate the whole cache. */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    // ---- internals ----

    private Set< UUID > lookup( final List< String > candidates ) {
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( SELECT_IDS_SQL ) ) {
            ps.setArray( 1, c.createArrayOf( "text", candidates.toArray( new String[ 0 ] ) ) );
            try( ResultSet rs = ps.executeQuery() ) {
                final Set< UUID > ids = new LinkedHashSet<>();
                while( rs.next() ) {
                    final UUID id = rs.getObject( 1, UUID.class );
                    if( id != null ) ids.add( id );
                }
                return Collections.unmodifiableSet( ids );
            }
        } catch( final SQLException e ) {
            LOG.warn( "QueryEntityResolver lookup failed; returning empty result: {}", e.getMessage(), e );
            return Set.of();
        }
    }

    /**
     * Produces a capped list of candidate lowercase strings — single tokens and
     * 2-/3-token windows. Deduplicated while preserving order so the SQL
     * in-list is compact. Splits on any non-letter/digit character so that
     * punctuation boundaries like {@code napoleon's} produce {@code napoleon}
     * rather than a spurious {@code napoleons}.
     */
    static List< String > tokenWindows( final String lowercasedQuery ) {
        final String[] rawTokens = lowercasedQuery.split( "[^\\p{L}\\p{N}]+" );
        final List< String > tokens = new ArrayList<>( rawTokens.length );
        for( final String raw : rawTokens ) {
            if( raw == null || raw.isEmpty() ) continue;
            tokens.add( raw );
        }
        if( tokens.isEmpty() ) return List.of();

        final LinkedHashSet< String > out = new LinkedHashSet<>();
        // Singles, skipping obvious stop-word shapes and length-1 noise.
        for( final String t : tokens ) {
            if( t.length() < 2 ) continue;
            if( STOP_SINGLES.contains( t ) ) continue;
            out.add( t );
            if( out.size() >= MAX_CANDIDATES ) break;
        }
        // 2- and 3-grams — these are where multi-word entity names (e.g. "new york",
        // "claude haiku 4.5") surface, and they're independent of stop-word filtering.
        for( int window = 2; window <= MAX_WINDOW && out.size() < MAX_CANDIDATES; window++ ) {
            for( int i = 0; i + window <= tokens.size() && out.size() < MAX_CANDIDATES; i++ ) {
                out.add( String.join( " ", tokens.subList( i, i + window ) ) );
            }
        }
        return new ArrayList<>( out );
    }

}

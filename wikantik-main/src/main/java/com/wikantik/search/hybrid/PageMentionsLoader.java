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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bulk loader for {@code pageName -> mentioned entity ids}. Resolves the
 * candidate page set to their {@code chunk_entity_mentions} rows in a single
 * SQL round-trip so the graph rerank step avoids N round-trips per query.
 *
 * <p>Joins {@code kg_content_chunks} to {@code chunk_entity_mentions} to map
 * chunk-level mentions back up to the page grain at which the search results
 * are ranked. Pages with no mentions are simply absent from the returned map
 * so callers can fold the output straight into {@link GraphProximityScorer}.
 * </p>
 */
public class PageMentionsLoader {

    private static final Logger LOG = LogManager.getLogger( PageMentionsLoader.class );

    private static final String SELECT_SQL =
        "SELECT c.page_name, m.node_id "
      + "  FROM kg_content_chunks c "
      + "  JOIN chunk_entity_mentions m ON m.chunk_id = c.id "
      + " WHERE c.page_name = ANY( ? )";

    private static final String SELECT_WITH_CONFIDENCE_SQL =
        "SELECT c.page_name, m.node_id, m.confidence "
      + "  FROM kg_content_chunks c "
      + "  JOIN chunk_entity_mentions m ON m.chunk_id = c.id "
      + " WHERE c.page_name = ANY( ? )";

    private final DataSource dataSource;

    /**
     * Cache for {@link #loadFor}: page_name → immutable {@code Set<UUID>}.
     * A page with no mentions is cached as an empty set (negative result) so
     * we don't re-query it on the next graph rerank. The result set is wrapped
     * with {@link Collections#unmodifiableSet} before caching so callers can't
     * mutate shared state.
     *
     * <p>Eviction is purely TTL-driven (no explicit invalidation hook): entity
     * mentions are a rerank signal, not authoritative content, so eventual
     * consistency over a few minutes after a page edit is acceptable.</p>
     */
    private final Cache< String, Set< UUID > > loadForCache;

    /** Parallel cache for the confidence-weighted variant. */
    private final Cache< String, Map< UUID, Double > > loadForWithConfidenceCache;

    public PageMentionsLoader( final DataSource dataSource ) {
        if( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        this.dataSource = dataSource;
        this.loadForCache = Caffeine.newBuilder()
            .maximumSize( 5_000 )
            .expireAfterWrite( Duration.ofMinutes( 5 ) )
            .recordStats()
            .build();
        this.loadForWithConfidenceCache = Caffeine.newBuilder()
            .maximumSize( 5_000 )
            .expireAfterWrite( Duration.ofMinutes( 5 ) )
            .recordStats()
            .build();
    }

    /**
     * Returns {@code pageName -> set of mentioned node ids} for pages that have
     * at least one mention. An empty input collection or a SQL failure yields
     * an empty map — the caller's rerank step treats empty mentions as "no
     * boost available" and degrades gracefully.
     */
    public Map< String, Set< UUID > > loadFor( final Collection< String > pageNames ) {
        if( pageNames == null || pageNames.isEmpty() ) return Map.of();

        // Cache-first split. Negative results (empty Set) are also cached so a
        // page known to have no mentions doesn't trigger another query. The
        // contract of "absent from the returned map = no mentions" is preserved:
        // empty cached entries skip the output map.
        final Map< String, Set< UUID > > out = new HashMap<>();
        final List< String > misses = new ArrayList<>();
        for( final String name : pageNames ) {
            if( name == null ) continue;
            final Set< UUID > cached = loadForCache.getIfPresent( name );
            if( cached != null ) {
                if( !cached.isEmpty() ) out.put( name, cached );
            } else {
                misses.add( name );
            }
        }
        if( misses.isEmpty() ) return out;

        final Map< String, Set< UUID > > fromDb = queryLoadFor( misses );
        for( final String name : misses ) {
            final Set< UUID > raw = fromDb.get( name );
            final Set< UUID > frozen = raw == null || raw.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet( raw );
            loadForCache.put( name, frozen );
            if( !frozen.isEmpty() ) out.put( name, frozen );
        }
        return out;
    }

    /** Raw DB fetch, no cache. Package-private for tests. */
    Map< String, Set< UUID > > queryLoadFor( final Collection< String > pageNames ) {
        final String[] nameArr = pageNames.toArray( new String[ 0 ] );
        final Map< String, Set< UUID > > out = new HashMap<>( nameArr.length * 2 );
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( SELECT_SQL ) ) {
            ps.setArray( 1, c.createArrayOf( "text", nameArr ) );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final String page = rs.getString( 1 );
                    final UUID node = rs.getObject( 2, UUID.class );
                    if( page == null || node == null ) continue;
                    out.computeIfAbsent( page, k -> new HashSet<>() ).add( node );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "PageMentionsLoader failed for {} pages: {}", nameArr.length, e.getMessage(), e );
            return Map.of();
        }
        return out;
    }

    /**
     * Confidence-aware variant for the weighted rerank: returns
     * {@code pageName -> (nodeId -> max mention confidence)}. When the same
     * node appears multiple times for one page (multiple chunks), the highest
     * confidence wins — matches the {@code max} aggregation the scorer
     * already applies across mentioned entities. Failure semantics match
     * {@link #loadFor}: a SQL error degrades to an empty map.
     */
    public Map< String, Map< UUID, Double > > loadForWithConfidence( final Collection< String > pageNames ) {
        if( pageNames == null || pageNames.isEmpty() ) return Map.of();

        final Map< String, Map< UUID, Double > > out = new HashMap<>();
        final List< String > misses = new ArrayList<>();
        for( final String name : pageNames ) {
            if( name == null ) continue;
            final Map< UUID, Double > cached = loadForWithConfidenceCache.getIfPresent( name );
            if( cached != null ) {
                if( !cached.isEmpty() ) out.put( name, cached );
            } else {
                misses.add( name );
            }
        }
        if( misses.isEmpty() ) return out;

        final Map< String, Map< UUID, Double > > fromDb = queryLoadForWithConfidence( misses );
        for( final String name : misses ) {
            final Map< UUID, Double > raw = fromDb.get( name );
            final Map< UUID, Double > frozen = raw == null || raw.isEmpty()
                ? Map.of()
                : Collections.unmodifiableMap( raw );
            loadForWithConfidenceCache.put( name, frozen );
            if( !frozen.isEmpty() ) out.put( name, frozen );
        }
        return out;
    }

    /** Raw DB fetch, no cache. Package-private for tests. */
    Map< String, Map< UUID, Double > > queryLoadForWithConfidence( final Collection< String > pageNames ) {
        final String[] nameArr = pageNames.toArray( new String[ 0 ] );
        final Map< String, Map< UUID, Double > > out = new HashMap<>( nameArr.length * 2 );
        try( Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement( SELECT_WITH_CONFIDENCE_SQL ) ) {
            ps.setArray( 1, c.createArrayOf( "text", nameArr ) );
            try( ResultSet rs = ps.executeQuery() ) {
                while( rs.next() ) {
                    final String page = rs.getString( 1 );
                    final UUID node = rs.getObject( 2, UUID.class );
                    final double conf = rs.getDouble( 3 );
                    if( page == null || node == null ) continue;
                    final Map< UUID, Double > row = out.computeIfAbsent( page, k -> new HashMap<>() );
                    final Double prior = row.get( node );
                    if( prior == null || conf > prior ) row.put( node, conf );
                }
            }
        } catch( final SQLException e ) {
            LOG.warn( "PageMentionsLoader.loadForWithConfidence failed for {} pages: {}",
                nameArr.length, e.getMessage(), e );
            return Map.of();
        }
        return out;
    }

    /**
     * Cache stats — exposed for {@code /metrics} and admin diagnostics.
     * Returns a snapshot of both inner caches as {@code (loadFor, loadForWithConfidence)}.
     */
    public CacheStatsSnapshot cacheStats() {
        return new CacheStatsSnapshot( loadForCache.stats(), loadForWithConfidenceCache.stats() );
    }

    /** Snapshot of both Caffeine caches' stats; returned by {@link #cacheStats}. */
    public record CacheStatsSnapshot(
        com.github.benmanes.caffeine.cache.stats.CacheStats loadFor,
        com.github.benmanes.caffeine.cache.stats.CacheStats loadForWithConfidence ) {}

    /**
     * The {@link #loadFor} cache — exposed for metric registration via
     * {@code CaffeineCacheMetricsBridge}. Not intended for callers outside
     * the metrics path.
     */
    public Cache< String, Set< UUID > > loadForCache() {
        return loadForCache;
    }

    /** The {@link #loadForWithConfidence} cache; metrics-registration only. */
    public Cache< String, Map< UUID, Double > > loadForWithConfidenceCache() {
        return loadForWithConfidenceCache;
    }
}

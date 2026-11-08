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
package com.wikantik.search.embedding;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Page-to-page similarity from the dense retrieval embeddings: each page is represented by the
 * centroid of its chunk vectors (see {@link PageCentroidLoader}), and pages are ranked by cosine
 * similarity of those centroids. This backs the reader's "Similar pages" panel and deliberately does
 * not touch the Knowledge Graph.
 *
 * <p>The centroid map is cached for {@link #CACHE_TTL}; with ~1.5k pages at 1024 dimensions it is a
 * few MB, and a query is one dot product per page. An empty load (no embeddings yet) or a failed load
 * is retried after {@link #RETRY_TTL}, and a failed refresh keeps serving the previous centroids.
 */
public class PageEmbeddingSimilarity {

    /** How long a loaded centroid snapshot is served before it is rebuilt. */
    static final Duration CACHE_TTL = Duration.ofMinutes( 10 );

    /** How long an empty or failed load is remembered before the next attempt. */
    static final Duration RETRY_TTL = Duration.ofSeconds( 60 );

    /** Upper bound on ranked candidates passed to the accept filter per query. */
    static final int MAX_CANDIDATES_EXAMINED = 200;

    /** A page name with its cosine similarity to the query page. */
    public record ScoredPage( String name, double score ) {}

    /** Centroids plus when they were loaded and how long they stay fresh. */
    private record Snapshot( Map< String, float[] > centroids, long loadedAtNanos, long ttlNanos ) {}

    private final PageCentroidLoader loader;
    private final LongSupplier nanoClock;
    private volatile Snapshot snapshot;

    public PageEmbeddingSimilarity( final DataSource dataSource, final String modelCode ) {
        this( dataSource, modelCode, System::nanoTime );
    }

    PageEmbeddingSimilarity( final DataSource dataSource, final String modelCode, final LongSupplier nanoClock ) {
        if ( dataSource == null ) throw new IllegalArgumentException( "dataSource must not be null" );
        if ( modelCode == null || modelCode.isBlank() ) {
            throw new IllegalArgumentException( "modelCode must not be blank" );
        }
        this.loader = new PageCentroidLoader( dataSource, modelCode );
        this.nanoClock = nanoClock;
    }

    /** True iff at least one page has an embedded chunk for the active model. */
    public boolean isReady() {
        return !centroids().isEmpty();
    }

    /**
     * Returns up to {@code limit} pages most similar to {@code pageName}, best first. The query page
     * is never included; candidates failing {@code accept} (e.g. not viewable, no longer existing)
     * are skipped and the limit is filled from further down the ranking, examining at most
     * {@link #MAX_CANDIDATES_EXAMINED} candidates. Empty when the page has no embedded chunks.
     */
    public List< ScoredPage > similarTo( final String pageName, final int limit, final Predicate< String > accept ) {
        if ( pageName == null || pageName.isBlank() || limit <= 0 ) return List.of();
        final Map< String, float[] > centroids = centroids();
        final float[] query = centroids.get( pageName );
        if ( query == null ) return List.of();

        final List< ScoredPage > ranked = new ArrayList<>( centroids.size() );
        for ( final Map.Entry< String, float[] > e : centroids.entrySet() ) {
            if ( e.getKey().equals( pageName ) || e.getValue().length != query.length ) continue;
            ranked.add( new ScoredPage( e.getKey(), PageCentroidLoader.dot( query, e.getValue() ) ) );
        }
        ranked.sort( ( a, b ) -> Double.compare( b.score(), a.score() ) );

        final List< ScoredPage > out = new ArrayList<>( limit );
        final int examinable = Math.min( ranked.size(), MAX_CANDIDATES_EXAMINED );
        for ( int i = 0; i < examinable && out.size() < limit; i++ ) {
            final ScoredPage candidate = ranked.get( i );
            if ( accept == null || accept.test( candidate.name() ) ) out.add( candidate );
        }
        return out;
    }

    /**
     * Returns the cached centroids, reloading when stale. A successful non-empty load is kept for
     * {@link #CACHE_TTL}; an empty or failed load is remembered for {@link #RETRY_TTL} so a burst of
     * page views cannot turn into a full scan per request. A failed refresh keeps serving the last
     * good centroids rather than blanking the panel.
     */
    private Map< String, float[] > centroids() {
        final Snapshot current = snapshot;
        if ( isFresh( current ) ) return current.centroids();
        synchronized ( this ) {
            final Snapshot recheck = snapshot;
            if ( isFresh( recheck ) ) return recheck.centroids();
            final long now = nanoClock.getAsLong();
            final Map< String, float[] > loaded = loader.load();
            final Snapshot next;
            if ( loaded == null ) {
                // Failure already logged; keep the previous centroids (if any) until the retry window passes.
                next = new Snapshot( recheck != null ? recheck.centroids() : Map.of(), now, RETRY_TTL.toNanos() );
            } else if ( loaded.isEmpty() ) {
                next = new Snapshot( loaded, now, RETRY_TTL.toNanos() );
            } else {
                next = new Snapshot( loaded, now, CACHE_TTL.toNanos() );
            }
            snapshot = next;
            return next.centroids();
        }
    }

    private boolean isFresh( final Snapshot s ) {
        return s != null && nanoClock.getAsLong() - s.loadedAtNanos() < s.ttlNanos();
    }
}

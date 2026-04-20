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
package com.wikantik.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Date;
import java.util.Map;
import java.util.Objects;

/**
 * Caches parsed page frontmatter keyed on {@code (pageName, lastModified)} so the
 * search response path doesn't re-read and re-parse every page on every query.
 *
 * <p>The lastModified timestamp is part of the key, so a page edit naturally
 * invalidates the cached entry on the next read — the lookup misses, the new
 * markdown is fetched, and the new metadata replaces the entry. Callers that
 * pass a {@code null} lastModified bypass the cache and parse fresh, since we
 * cannot tell whether the page has changed.</p>
 *
 * <p>Returned maps are unmodifiable snapshots produced by {@link FrontmatterParser};
 * concurrent callers see consistent state without locking.</p>
 */
public final class FrontmatterMetadataCache {

    private static final Logger LOG = LogManager.getLogger( FrontmatterMetadataCache.class );

    /** Default cache capacity — covers a busy admin index page comfortably. */
    public static final int DEFAULT_MAX_ENTRIES = 2_000;

    private final PageManager pageManager;
    private final Cache< Key, Map< String, Object > > cache;

    public FrontmatterMetadataCache( final PageManager pageManager ) {
        this( pageManager, DEFAULT_MAX_ENTRIES );
    }

    public FrontmatterMetadataCache( final PageManager pageManager, final long maxEntries ) {
        this.pageManager = Objects.requireNonNull( pageManager, "pageManager" );
        this.cache = Caffeine.newBuilder()
            .maximumSize( maxEntries )
            .build();
    }

    /**
     * Look up frontmatter metadata for the given page version. Returns an empty
     * map for unknown pages, parser failures, or pages without a frontmatter
     * block — callers can simply iterate over the result without null guards.
     */
    public Map< String, Object > get( final String pageName, final Date lastModified ) {
        if ( pageName == null || pageName.isEmpty() ) {
            return Map.of();
        }
        // null lastModified means we can't safely cache — parse fresh.
        if ( lastModified == null ) {
            return loadAndParse( pageName );
        }
        return cache.get( new Key( pageName, lastModified.getTime() ),
            k -> loadAndParse( k.pageName ) );
    }

    /** Drop any cached entry for {@code pageName}. Safe to call with null. */
    public void invalidate( final String pageName ) {
        if ( pageName == null ) return;
        // Caffeine has no prefix scan; we don't know the lastModified timestamp,
        // so iterate the snapshot and remove every key whose pageName matches.
        cache.asMap().keySet().removeIf( k -> pageName.equals( k.pageName ) );
    }

    private Map< String, Object > loadAndParse( final String pageName ) {
        final String raw;
        try {
            raw = pageManager.getPureText( pageName, -1 );
        } catch( final Exception e ) {
            LOG.debug( "FrontmatterMetadataCache: getPureText failed for {}: {}",
                pageName, e.getMessage() );
            return Map.of();
        }
        if ( raw == null || raw.isEmpty() ) {
            return Map.of();
        }
        try {
            final ParsedPage parsed = FrontmatterParser.parse( raw );
            final Map< String, Object > meta = parsed.metadata();
            return meta != null ? meta : Map.of();
        } catch( final Exception e ) {
            LOG.debug( "FrontmatterMetadataCache: parse failed for {}: {}",
                pageName, e.getMessage() );
            return Map.of();
        }
    }

    private record Key( String pageName, long lastModifiedMillis ) {}
}

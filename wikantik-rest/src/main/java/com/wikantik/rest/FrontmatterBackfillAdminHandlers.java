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
package com.wikantik.rest;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.knowledge.SummaryExtractor;
import com.wikantik.knowledge.TagExtractor;
import com.wikantik.knowledge.TitleDeriver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Frontmatter maintenance admin handlers served at {@code /admin/embeddings/*}: listing pages that
 * lack frontmatter and the default-frontmatter backfill job (status + trigger + the background worker).
 * Deliberately independent of the retrieval stack, so they work when embeddings are switched off.
 * <p>
 * The backfill progress fields are instance state: the owning servlet holds one handler for its
 * life, so {@code GET backfill-frontmatter} reflects the single job started by {@code POST}.
 */
public final class FrontmatterBackfillAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( FrontmatterBackfillAdminHandlers.class );

    private final Supplier< PageManager > pageManager;
    private final Supplier< SystemPageRegistry > systemPageRegistry;
    private final Supplier< Engine > engine;

    public FrontmatterBackfillAdminHandlers( final Supplier< PageManager > pageManager,
                                        final Supplier< SystemPageRegistry > systemPageRegistry,
                                        final Supplier< Engine > engine ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
        this.engine = engine;
    }

    // --- Pages without frontmatter ---

    /**
     *  Cached result of the corpus scan behind {@code /pages-without-frontmatter}.
     *
     *  <p>The handler walks every page and runs {@code getPureText} plus a full YAML parse
     *  just to test {@code parsed.metadata().isEmpty()}. {@code getAllPages()} and the page
     *  text are both already cached by {@code CachingProvider}, so the cost is purely the
     *  per-page SnakeYAML parse — roughly 1,200 of them per request on this corpus. Once the
     *  sibling derived-status endpoint was memoised, the 2026-09-26 profiling campaign put
     *  this handler at 46% of {@code FrontmatterParser.parseYaml} and 62% of {@code split}.</p>
     *
     *  <p>The SCAN RESULT is cached, not the response: {@code limit}/{@code offset} must
     *  still paginate per request. Held as a plain volatile snapshot rather than a Caffeine
     *  cache — one value, and Caffeine is not a dependency of this module.</p>
     *
     *  <p>Value and timestamp are held together in one immutable {@link CachedScan} behind a
     *  single volatile field, so a reader can never pair a freshly-written snapshot with a
     *  stale timestamp (or vice versa) the way two independently-written volatile fields can.</p>
     */
    private record CachedScan( List< Map< String, Object > > pages, long atMs ) {}

    private volatile CachedScan cachedPagesWithoutFm;

    /** TTL in seconds for {@link #cachedPagesWithoutFm}; {@code <= 0} disables caching. */
    private long pagesWithoutFrontmatterCacheTtlSeconds() {
        final Engine eng = engine.get();
        if ( eng == null ) {
            return 30L;
        }
        final String raw = eng.getWikiProperties()
                .getProperty( "wikantik.admin.kg.pagesWithoutFrontmatterCacheTTL", "30" );
        try {
            return Long.parseLong( raw.trim() );
        } catch ( final NumberFormatException e ) {
            // Fail SAFE, not closed: a typo must not silently restore the full-corpus scan.
            LOG.warn( "Invalid wikantik.admin.kg.pagesWithoutFrontmatterCacheTTL='{}' — using 30s", raw );
            return 30L;
        }
    }

    /** Drops the cached scan so the next poll rescans. Called once a backfill completes,
     *  successfully or not, since a partial run can still have fixed some pages. */
    private void invalidatePagesWithoutFmCache() {
        cachedPagesWithoutFm = null;
    }

    /**
     *  Returns the sorted list of pages lacking frontmatter, rescanning only when the cache
     *  is empty or older than {@link #pagesWithoutFrontmatterCacheTtlSeconds()}.
     */
    private List< Map< String, Object > > pagesWithoutFrontmatterSnapshot(
            final PageManager pm, final SystemPageRegistry spr ) throws Exception {
        final long ttl = pagesWithoutFrontmatterCacheTtlSeconds();
        if ( ttl > 0 ) {
            final CachedScan cached = cachedPagesWithoutFm;
            if ( cached != null && System.currentTimeMillis() - cached.atMs() < ttl * 1000L ) {
                return cached.pages();
            }
        }
        final List< Map< String, Object > > pages = new ArrayList<>();
        for ( final Page page : pm.getAllPages() ) {
            if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                continue;
            }
            final String text = pm.getPureText( page );
            final ParsedPage parsed = FrontmatterParser.parse( text != null ? text : "" );
            if ( parsed.metadata().isEmpty() ) {
                final Map< String, Object > entry = new LinkedHashMap<>();
                entry.put( "name", page.getName() );
                entry.put( "lastModified", page.getLastModified() != null
                        ? page.getLastModified().toInstant().toString() : null );
                pages.add( entry );
            }
        }
        pages.sort( Comparator.comparing( m -> ( String ) m.get( "name" ) ) );
        final List< Map< String, Object > > snapshot = List.copyOf( pages );
        cachedPagesWithoutFm = new CachedScan( snapshot, System.currentTimeMillis() );
        return snapshot;
    }

    public void handleGetPagesWithoutFrontmatter( final HttpServletRequest request,
                                                   final HttpServletResponse response ) throws IOException {
        final PageManager pm = pageManager.get();
        if ( pm == null ) {
            RestJson.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PageManager not available" );
            return;
        }
        final SystemPageRegistry spr = systemPageRegistry.get();
        final int limit = RestJson.parseIntParam( request, "limit", 100 );
        final int offset = RestJson.parseIntParam( request, "offset", 0 );
        try {
            final List< Map< String, Object > > pages = pagesWithoutFrontmatterSnapshot( pm, spr );
            final int total = pages.size();
            final List< Map< String, Object > > paged = pages.subList(
                    Math.min( offset, total ), Math.min( offset + limit, total ) );
            RestJson.sendJson( response, Map.of( "total", total, "pages", paged ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list pages without frontmatter", e );
            RestJson.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed: " + e.getMessage() );
        }
    }

    // --- Backfill ---

    private volatile boolean backfillRunning = false;
    private volatile int backfillTotal = 0;
    private final AtomicInteger backfillProcessed = new AtomicInteger();
    private final AtomicInteger backfillErrors = new AtomicInteger();

    public void handleGetBackfillStatus( final HttpServletResponse response ) throws IOException {
        RestJson.sendJson( response, Map.of(
            "running", backfillRunning,
            "total", backfillTotal,
            "processed", backfillProcessed.get(),
            "errors", backfillErrors.get()
        ) );
    }

    public void handlePostBackfillFrontmatter( final HttpServletResponse response ) throws IOException {
        if ( backfillRunning ) {
            RestJson.sendError( response, HttpServletResponse.SC_CONFLICT, "Backfill already in progress" );
            return;
        }

        final Thread t = new Thread( this::runBackfill, "frontmatter-backfill" );
        t.setDaemon( true );
        t.start();
        RestJson.sendJson( response, Map.of( "status", "started" ) );
    }

    @SuppressWarnings( "PMD.UnusedAssignment" ) // `backfillRunning` is read by the backfill status endpoint on other threads.
    private void runBackfill() {
        backfillRunning = true;
        backfillProcessed.set( 0 );
        backfillErrors.set( 0 );
        try {
            final PageManager pm = pageManager.get();
            final SystemPageRegistry spr = systemPageRegistry.get();
            final var allPages = pm.getAllPages();
            backfillTotal = allPages.size();

            final PageSaveHelper saveHelper = new PageSaveHelper( engine.get(), pm );

            for ( final Page page : allPages ) {
                try {
                    if ( spr != null && spr.isSystemPage( page.getName() ) ) {
                        backfillProcessed.incrementAndGet();
                        continue;
                    }
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( !parsed.metadata().isEmpty() ) {
                        backfillProcessed.incrementAndGet();
                        continue;
                    }

                    final String body = parsed.body();
                    // Published frontmatter is derived from the text a reader may see; the stored body keeps its rules.
                    final String visibleBody = AclDirectiveText.parseStripped( content, " " ).body();
                    final Map< String, Object > metadata = new LinkedHashMap<>();
                    metadata.put( "title", TitleDeriver.derive( page.getName() ) );
                    metadata.put( "type", "article" );
                    metadata.put( "tags", TagExtractor.extract( visibleBody, 3 ) );
                    metadata.put( "summary", SummaryExtractor.extract( visibleBody ) );
                    metadata.put( "auto-generated", Boolean.TRUE );

                    final String updated = FrontmatterWriter.write( metadata, body );
                    saveHelper.saveText( page.getName(), updated,
                        SaveOptions.builder().changeNote( "Backfill default frontmatter" ).build() );
                    backfillProcessed.incrementAndGet();
                } catch ( final Exception e ) {
                    backfillErrors.incrementAndGet();
                    LOG.warn( "Backfill failed for page '{}': {}", page.getName(), e.getMessage() );
                }
            }
        } catch ( final Exception e ) {
            LOG.error( "Backfill failed: {}", e.getMessage(), e );
        } finally {
            backfillRunning = false;
            // A backfill changes which pages lack frontmatter, so the cached scan is stale —
            // even a partially-failed run may have fixed some pages (see class javadoc).
            invalidatePagesWithoutFmCache();
        }
    }
}

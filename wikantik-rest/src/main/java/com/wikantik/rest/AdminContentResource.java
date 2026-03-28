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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CacheInfo;
import com.wikantik.cache.CachingManager;
import com.wikantik.search.SearchManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST servlet for admin content management operations.
 * <p>
 * Mapped to {@code /admin/content/*}. Protected by {@link AdminAuthFilter}.
 * <ul>
 *   <li>{@code GET  /admin/content/stats} — wiki health dashboard</li>
 *   <li>{@code GET  /admin/content/orphaned-pages} — unreferenced pages</li>
 *   <li>{@code GET  /admin/content/broken-links} — missing link targets</li>
 *   <li>{@code POST /admin/content/bulk-delete} — delete multiple pages</li>
 *   <li>{@code POST /admin/content/purge-versions} — purge old page versions</li>
 *   <li>{@code POST /admin/content/reindex} — force full search index rebuild</li>
 *   <li>{@code POST /admin/content/cache/flush} — flush caches</li>
 * </ul>
 */
public class AdminContentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminContentResource.class );

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    private static final String[] CACHE_NAMES = {
        CachingManager.CACHE_PAGES,
        CachingManager.CACHE_PAGES_TEXT,
        CachingManager.CACHE_PAGES_HISTORY,
        CachingManager.CACHE_ATTACHMENTS,
        CachingManager.CACHE_ATTACHMENTS_COLLECTION,
        CachingManager.CACHE_ATTACHMENTS_DYNAMIC,
        CachingManager.CACHE_DOCUMENTS,
        CachingManager.CACHE_HTML,
    };

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "stats".equals( action ) ) {
            handleStats( response );
        } else if ( "orphaned-pages".equals( action ) ) {
            handleOrphanedPages( response );
        } else if ( "broken-links".equals( action ) ) {
            handleBrokenLinks( response );
        } else {
            sendNotFound( response, "Unknown content endpoint: " + action );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        final String action = extractPathParam( request );

        if ( "bulk-delete".equals( action ) ) {
            handleBulkDelete( request, response );
        } else if ( "purge-versions".equals( action ) ) {
            handlePurgeVersions( request, response );
        } else if ( "reindex".equals( action ) ) {
            handleReindex( response );
        } else if ( "cache/flush".equals( action ) ) {
            handleCacheFlush( request, response );
        } else {
            sendNotFound( response, "Unknown content endpoint: " + action );
        }
    }

    // ---- GET handlers ----

    private void handleStats( final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final PageManager pm = engine.getManager( PageManager.class );
        final ReferenceManager rm = engine.getManager( ReferenceManager.class );
        final CachingManager cm = engine.getManager( CachingManager.class );

        final Map< String, Object > stats = new LinkedHashMap<>();
        stats.put( "pageCount", pm.getTotalPageCount() );

        if ( rm != null ) {
            stats.put( "orphanedCount", rm.findUnreferenced().size() );
            stats.put( "brokenLinkCount", rm.findUncreated().size() );
        }

        // Cache stats
        final List< Map< String, Object > > cacheStats = new ArrayList<>();
        if ( cm != null ) {
            for ( final String cacheName : CACHE_NAMES ) {
                if ( cm.enabled( cacheName ) ) {
                    final CacheInfo info = cm.info( cacheName );
                    if ( info != null ) {
                        final Map< String, Object > c = new LinkedHashMap<>();
                        c.put( "name", shortCacheName( cacheName ) );
                        c.put( "fullName", cacheName );
                        c.put( "size", cm.keys( cacheName ).size() );
                        c.put( "maxSize", info.getMaxElementsAllowed() );
                        c.put( "hits", info.getHits() );
                        c.put( "misses", info.getMisses() );
                        final long total = info.getHits() + info.getMisses();
                        c.put( "hitRatio", total > 0 ? Math.round( 100.0 * info.getHits() / total ) : 0 );
                        cacheStats.add( c );
                    }
                }
            }
        }
        stats.put( "caches", cacheStats );

        sendJson( response, stats );
    }

    private void handleOrphanedPages( final HttpServletResponse response ) throws IOException {
        final ReferenceManager rm = getEngine().getManager( ReferenceManager.class );
        if ( rm == null ) {
            sendJson( response, Map.of( "pages", List.of() ) );
            return;
        }

        final Collection< String > orphaned = rm.findUnreferenced();
        final List< String > sorted = new ArrayList<>( orphaned );
        sorted.sort( String::compareToIgnoreCase );
        sendJson( response, Map.of( "pages", sorted ) );
    }

    private void handleBrokenLinks( final HttpServletResponse response ) throws IOException {
        final ReferenceManager rm = getEngine().getManager( ReferenceManager.class );
        if ( rm == null ) {
            sendJson( response, Map.of( "links", List.of() ) );
            return;
        }

        final Collection< String > uncreated = rm.findUncreated();
        final List< Map< String, Object > > links = new ArrayList<>();

        for ( final String target : uncreated ) {
            final Set< String > referrers = rm.findReferrers( target );
            final Map< String, Object > entry = new LinkedHashMap<>();
            entry.put( "target", target );
            entry.put( "referencedBy", referrers != null ? new ArrayList<>( referrers ) : List.of() );
            entry.put( "referrerCount", referrers != null ? referrers.size() : 0 );
            links.add( entry );
        }

        // Sort by referrer count descending
        links.sort( ( a, b ) -> Integer.compare(
            ( int ) b.get( "referrerCount" ), ( int ) a.get( "referrerCount" ) ) );

        sendJson( response, Map.of( "links", links ) );
    }

    // ---- POST handlers ----

    private void handleBulkDelete( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final JsonArray pagesArray = body.has( "pages" ) ? body.getAsJsonArray( "pages" ) : null;
        if ( pagesArray == null || pagesArray.isEmpty() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "pages array is required" );
            return;
        }

        final PageManager pm = getEngine().getManager( PageManager.class );
        final List< String > deleted = new ArrayList<>();
        final List< Map< String, String > > failed = new ArrayList<>();

        for ( int i = 0; i < pagesArray.size(); i++ ) {
            final String pageName = pagesArray.get( i ).getAsString();
            try {
                pm.deletePage( pageName );
                deleted.add( pageName );
                LOG.info( "Bulk delete: deleted page {}", pageName );
            } catch ( final ProviderException e ) {
                failed.add( Map.of( "page", pageName, "error", "deletion failed" ) );
                LOG.warn( "Bulk delete: failed to delete {}: {}", pageName, e.getMessage() );
            }
        }

        sendJson( response, Map.of( "deleted", deleted, "failed", failed ) );
    }

    private void handlePurgeVersions( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final String pageName = body.has( "page" ) ? body.get( "page" ).getAsString() : null;
        final int keepLatest = body.has( "keepLatest" ) ? body.get( "keepLatest" ).getAsInt() : 1;

        if ( pageName == null || pageName.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "page name is required" );
            return;
        }
        if ( keepLatest < 1 ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "keepLatest must be at least 1" );
            return;
        }

        try {
            final Engine engine = getEngine();
            final PageManager pm = engine.getManager( PageManager.class );
            final List< ? extends Page > history = pm.getVersionHistory( pageName );

            if ( history == null || history.isEmpty() ) {
                sendJson( response, Map.of( "purged", 0, "remaining", 0 ) );
                return;
            }

            // History is in descending order (newest first). Keep the first N.
            int purged = 0;
            for ( int i = keepLatest; i < history.size(); i++ ) {
                final Page oldVersion = history.get( i );
                try {
                    pm.deleteVersion( oldVersion );
                    purged++;
                } catch ( final ProviderException e ) {
                    LOG.warn( "Failed to purge version {} of {}: {}", oldVersion.getVersion(), pageName, e.getMessage() );
                }
            }

            LOG.info( "Purged {} old versions of {}, kept latest {}", purged, pageName, keepLatest );
            sendJson( response, Map.of( "purged", purged, "remaining", Math.min( keepLatest, history.size() ) ) );
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Failed to purge versions" );
        }
    }

    private void handleReindex( final HttpServletResponse response ) throws IOException {
        try {
            final Engine engine = getEngine();
            final SearchManager sm = engine.getManager( SearchManager.class );
            final PageManager pm = engine.getManager( PageManager.class );

            // Reindex all pages by queuing each one
            final Collection< ? extends Page > allPages = pm.getAllPages();
            int count = 0;
            for ( final Page page : allPages ) {
                sm.reindexPage( page );
                count++;
            }

            LOG.info( "Queued {} pages for reindexing", count );
            sendJson( response, Map.of( "started", true, "pagesQueued", count ) );
        } catch ( final ProviderException e ) {
            LOG.error( "Failed to trigger reindex", e );
            sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Reindex failed" );
        }
    }

    private void handleCacheFlush( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final JsonObject body = parseJsonBody( request, response );
        if ( body == null ) return;

        final CachingManager cm = getEngine().getManager( CachingManager.class );
        if ( cm == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Caching is not enabled" );
            return;
        }

        final String specificCache = body.has( "cache" ) && !body.get( "cache" ).isJsonNull()
            ? body.get( "cache" ).getAsString() : null;

        final String[] toFlush = specificCache != null ? new String[]{ specificCache } : CACHE_NAMES;
        int flushed = 0;

        for ( final String cacheName : toFlush ) {
            if ( cm.enabled( cacheName ) ) {
                final List< ? extends Serializable > keys = cm.keys( cacheName );
                for ( final Serializable key : keys ) {
                    cm.remove( cacheName, key );
                }
                flushed += keys.size();
                LOG.info( "Flushed cache {} ({} entries)", shortCacheName( cacheName ), keys.size() );
            }
        }

        sendJson( response, Map.of( "flushed", true, "entriesRemoved", flushed ) );
    }

    // ---- Helpers ----

    private String shortCacheName( final String fullName ) {
        return fullName.replace( "wikantik.", "" );
    }

    private JsonObject parseJsonBody( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        try ( final BufferedReader reader = request.getReader() ) {
            return JsonParser.parseReader( reader ).getAsJsonObject();
        } catch ( final Exception e ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid JSON body" );
            return null;
        }
    }
}

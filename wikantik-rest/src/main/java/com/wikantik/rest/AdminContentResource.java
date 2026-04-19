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

import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.IndexStatusSnapshot;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CacheInfo;
import com.wikantik.cache.CachingManager;
import com.wikantik.content.NewsPageGenerator;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.search.SearchManager;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
 *   <li>{@code POST /admin/content/reindex} — force full search index rebuild (deprecated — use {@code /rebuild-indexes})</li>
 *   <li>{@code GET  /admin/content/index-status} — unified Lucene + chunk index snapshot</li>
 *   <li>{@code POST /admin/content/rebuild-indexes} — trigger async rebuild of Lucene + chunks</li>
 *   <li>{@code POST /admin/content/reindex-embeddings} — force full embedding reindex</li>
 *   <li>{@code POST /admin/content/refresh-news} — trigger immediate news page update from git</li>
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
        } else if ( "index-status".equals( action ) ) {
            handleIndexStatus( response );
        } else if ( "chunks".equals( action ) ) {
            handleChunksByPage( request, response );
        } else if ( "chunks/outliers".equals( action ) ) {
            handleChunkOutliers( response );
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
        } else if ( "rebuild-indexes".equals( action ) ) {
            handleRebuildIndexes( response );
        } else if ( "reindex-embeddings".equals( action ) ) {
            handleReindexEmbeddings( response );
        } else if ( "cache/flush".equals( action ) ) {
            handleCacheFlush( request, response );
        } else if ( "refresh-news".equals( action ) ) {
            handleRefreshNews( request, response );
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

        final String actor = resolveActor( request );
        LOG.info( "Bulk delete started by {}: {} page(s) requested",
            actor, pagesArray.size() );

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

        LOG.info( "Bulk delete finished by {}: {} deleted, {} failed",
            actor, deleted.size(), failed.size() );
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

    /**
     * Deprecated: prefer {@code POST /admin/content/rebuild-indexes}. This
     * handler is retained for legacy operator scripts; response carries
     * RFC-8594 / RFC-8288 deprecation headers advertising the successor.
     */
    private void handleReindex( final HttpServletResponse response ) throws IOException {
        // Set before any body is written (sendJson/sendError commits the response).
        response.setHeader( "Deprecation", "true" );
        response.setHeader( "Link",
            "</admin/content/rebuild-indexes>; rel=\"successor-version\"" );
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

    private void handleIndexStatus( final HttpServletResponse response ) throws IOException {
        final ContentIndexRebuildService svc = getEngine().getManager( ContentIndexRebuildService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "rebuild service not available" );
            return;
        }
        sendJsonWithStatus( response, HttpServletResponse.SC_OK,
            snapshotToMap( svc.snapshot() ) );
    }

    private void handleRebuildIndexes( final HttpServletResponse response ) throws IOException {
        final ContentIndexRebuildService svc = getEngine().getManager( ContentIndexRebuildService.class );
        if ( svc == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "rebuild service not available" );
            return;
        }
        try {
            final IndexStatusSnapshot snap = svc.triggerRebuild();
            LOG.info( "Content index rebuild triggered" );
            sendJsonWithStatus( response, 202, snapshotToMap( snap ) );
        } catch ( final ContentIndexRebuildService.ConflictException e ) {
            LOG.warn( "Rebuild rejected — already in flight: {}", e.getMessage() );
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT,
                snapshotToMap( e.current() ) );
        } catch ( final ContentIndexRebuildService.DisabledException e ) {
            LOG.warn( "Rebuild rejected — disabled by configuration" );
            sendJsonWithStatus( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                Map.of(
                    "error", "rebuild disabled",
                    "flag", "wikantik.rebuild.enabled" ) );
        }
    }

    /**
     * Force-starts a full embedding reindex via
     * {@link com.wikantik.search.embedding.BootstrapEmbeddingIndexer#forceStart()}.
     * Returns 202 on dispatch, 409 when a run is already in flight, 503 when
     * hybrid retrieval is disabled.
     */
    private void handleReindexEmbeddings( final HttpServletResponse response ) throws IOException {
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer boot =
            getEngine().getManager( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class );
        if ( boot == null ) {
            sendJsonWithStatus( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                Map.of(
                    "error", true,
                    "status", HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "message", "Embedding reindex unavailable — hybrid search is disabled.",
                    "flag", "wikantik.search.hybrid.enabled" ) );
            return;
        }
        try {
            boot.forceStart();
            LOG.info( "Embedding reindex force-started via admin UI (model={})", boot.modelCode() );
            sendJsonWithStatus( response, 202,
                Map.of( "state", boot.progress().state().name(),
                        "model_code", boot.modelCode() ) );
        } catch( final IllegalStateException e ) {
            LOG.warn( "Embedding reindex rejected — already running: {}", e.getMessage() );
            sendJsonWithStatus( response, HttpServletResponse.SC_CONFLICT,
                Map.of(
                    "error", true,
                    "status", HttpServletResponse.SC_CONFLICT,
                    "message", "Embedding reindex is already running.",
                    "state", boot.progress().state().name(),
                    "model_code", boot.modelCode() ) );
        }
    }

    // ---- Chunk inspector ----

    /**
     * Serves every chunk for a single page so operators can inspect exactly
     * what the chunker produced. Returns 400 if {@code ?page=} is missing
     * or blank, and 404 if the page has no chunk rows.
     */
    private void handleChunksByPage( final HttpServletRequest request,
                                     final HttpServletResponse response ) throws IOException {
        final String page = request.getParameter( "page" );
        if ( page == null || page.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "page query parameter is required" );
            return;
        }
        final ContentChunkRepository repo = getEngine().getManager( ContentChunkRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "chunk repository not available" );
            return;
        }
        final List< ContentChunkRepository.FullChunk > chunks = repo.findFullByPage( page );
        if ( chunks.isEmpty() ) {
            final Map< String, Object > body = new LinkedHashMap<>();
            body.put( "error", "page not found" );
            body.put( "page", page );
            sendJsonWithStatus( response, HttpServletResponse.SC_NOT_FOUND, body );
            return;
        }
        final List< Map< String, Object > > rows = new ArrayList<>( chunks.size() );
        for ( final ContentChunkRepository.FullChunk c : chunks ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "chunk_index", c.chunkIndex() );
            m.put( "heading_path", c.headingPath() );
            m.put( "text", c.text() );
            m.put( "char_count", c.charCount() );
            m.put( "token_count_estimate", c.tokenCountEstimate() );
            m.put( "content_hash", c.contentHash() );
            m.put( "created", c.created() == null ? null : c.created().toString() );
            m.put( "modified", c.modified() == null ? null : c.modified().toString() );
            rows.add( m );
        }
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "page", page );
        body.put( "chunks", rows );
        sendJsonWithStatus( response, HttpServletResponse.SC_OK, body );
    }

    /**
     * Serves corpus-wide chunk outliers (top 10 each): pages with the most
     * chunks, single-chunk pages whose sole chunk is unusually large, and
     * chunks whose estimated token count exceeds the chunker's max target.
     */
    private void handleChunkOutliers( final HttpServletResponse response ) throws IOException {
        final ContentChunkRepository repo = getEngine().getManager( ContentChunkRepository.class );
        if ( repo == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "chunk repository not available" );
            return;
        }
        final ContentChunkRepository.OutlierReport report = repo.outliers();
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "most_chunks", outlierEntriesToMaps( report.mostChunks() ) );
        body.put( "large_single_chunks", outlierEntriesToMaps( report.largeSingleChunks() ) );
        body.put( "oversized_chunks", outlierEntriesToMaps( report.oversizedChunks() ) );
        sendJsonWithStatus( response, HttpServletResponse.SC_OK, body );
    }

    private List< Map< String, Object > > outlierEntriesToMaps(
            final List< ContentChunkRepository.OutlierEntry > src ) {
        final List< Map< String, Object > > out = new ArrayList<>( src.size() );
        for ( final ContentChunkRepository.OutlierEntry e : src ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "page_name", e.pageName() );
            m.put( "chunk_count", e.chunkCount() );
            m.put( "max_tokens", e.maxTokens() );
            m.put( "total_tokens", e.totalTokens() );
            m.put( "char_count", e.charCount() );
            out.add( m );
        }
        return out;
    }

    /**
     * Like {@link #sendJson} but applies an explicit HTTP status code and emits
     * {@code null}-valued fields so clients can rely on a stable JSON shape
     * (e.g. {@code "started_at": null} when no rebuild has run yet).
     */
    private void sendJsonWithStatus( final HttpServletResponse response, final int status,
                                     final Object payload ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write(
            new com.google.gson.GsonBuilder().serializeNulls().create().toJson( payload ) );
    }

    /**
     * Builds the {@code embeddings.embedder} sub-block from the live
     * {@link com.wikantik.search.hybrid.QueryEmbedder} manager. Returns a
     * {@code DISABLED} shape when hybrid retrieval is off so the UI can
     * render a stable table without null-checks.
     */
    private Map< String, Object > embedderMap() {
        final Map< String, Object > m = new LinkedHashMap<>();
        final com.wikantik.search.hybrid.QueryEmbedder qe =
            getEngine().getManager( com.wikantik.search.hybrid.QueryEmbedder.class );
        if ( qe == null ) {
            m.put( "circuit_state", "DISABLED" );
            m.put( "call_success", 0L );
            m.put( "call_failure", 0L );
            m.put( "call_timeout", 0L );
            m.put( "cache_hit", 0L );
            m.put( "cache_miss", 0L );
            m.put( "breaker_open", 0L );
            m.put( "breaker_close", 0L );
            m.put( "breaker_half_open_probe", 0L );
            m.put( "breaker_call_rejected", 0L );
            return m;
        }
        final com.wikantik.search.hybrid.QueryEmbedderMetrics mt = qe.metrics();
        m.put( "circuit_state", qe.circuitState().name() );
        m.put( "call_success", mt.callSuccess() );
        m.put( "call_failure", mt.callFailure() );
        m.put( "call_timeout", mt.callTimeout() );
        m.put( "cache_hit", mt.cacheHit() );
        m.put( "cache_miss", mt.cacheMiss() );
        m.put( "breaker_open", mt.breakerOpen() );
        m.put( "breaker_close", mt.breakerClose() );
        m.put( "breaker_half_open_probe", mt.breakerHalfOpenProbe() );
        m.put( "breaker_call_rejected", mt.breakerCallRejected() );
        return m;
    }

    /**
     * Builds the {@code embeddings.bootstrap} sub-block from the live
     * {@link com.wikantik.search.embedding.BootstrapEmbeddingIndexer} manager.
     * Returns a stable shape even when hybrid retrieval is disabled (state =
     * {@code "DISABLED"}) so the UI can drive off it unconditionally.
     *
     * <p>{@code chunks_processed} is the live embeddings row count, not a value
     * tracked inside the bootstrap indexer — joining the two avoids lying while
     * the batch is in-flight.</p>
     */
    private Map< String, Object > bootstrapMap( final int liveRowCount ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer boot =
            getEngine().getManager( com.wikantik.search.embedding.BootstrapEmbeddingIndexer.class );
        if ( boot == null ) {
            m.put( "state", "DISABLED" );
            m.put( "chunks_total", 0L );
            m.put( "chunks_processed", liveRowCount );
            m.put( "started_at", null );
            m.put( "completed_at", null );
            m.put( "error_message", null );
            return m;
        }
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer.Progress p = boot.progress();
        m.put( "state", p.state().name() );
        m.put( "chunks_total", p.chunksTotal() );
        m.put( "chunks_processed", liveRowCount );
        m.put( "started_at", p.startedAt() == null ? null : p.startedAt().toString() );
        m.put( "completed_at", p.completedAt() == null ? null : p.completedAt().toString() );
        m.put( "error_message", p.errorMessage() );
        return m;
    }

    /** Serialises an {@link IndexStatusSnapshot} into the JSON shape the REST contract promises. */
    private Map< String, Object > snapshotToMap( final IndexStatusSnapshot s ) {
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "pages", Map.of(
            "total", s.pages().total(),
            "system", s.pages().system(),
            "indexable", s.pages().indexable() ) );

        final Map< String, Object > lucene = new LinkedHashMap<>();
        lucene.put( "documents_indexed", s.lucene().documentsIndexed() );
        lucene.put( "queue_depth", s.lucene().queueDepth() );
        lucene.put( "last_update",
            s.lucene().lastUpdate() == null ? null : s.lucene().lastUpdate().toString() );
        out.put( "lucene", lucene );

        out.put( "chunks", Map.of(
            "pages_with_chunks", s.chunks().pagesWithChunks(),
            "pages_missing_chunks", s.chunks().pagesMissingChunks(),
            "total_chunks", s.chunks().totalChunks(),
            "avg_tokens", s.chunks().avgTokens(),
            "min_tokens", s.chunks().minTokens(),
            "max_tokens", s.chunks().maxTokens() ) );

        // Embedding index status (Phase 1 of hybrid retrieval). When the
        // embedding subsystem is disabled the record carries empty defaults,
        // which still render to a stable JSON shape so UI can drive off them.
        final Map< String, Object > embeddings = new LinkedHashMap<>();
        embeddings.put( "model_code", s.embeddings().modelCode() );
        embeddings.put( "dim", s.embeddings().dim() );
        embeddings.put( "row_count", s.embeddings().rowCount() );
        embeddings.put( "last_update",
            s.embeddings().lastUpdate() == null ? null : s.embeddings().lastUpdate().toString() );
        embeddings.put( "bootstrap", bootstrapMap( s.embeddings().rowCount() ) );
        embeddings.put( "embedder", embedderMap() );
        out.put( "embeddings", embeddings );

        // Rebuild block has 9 fields — Map.of caps at 10 entries; use LinkedHashMap
        // both to stay under the cap and to keep a predictable JSON ordering.
        final Map< String, Object > rebuild = new LinkedHashMap<>();
        rebuild.put( "state", s.rebuild().state() );
        rebuild.put( "started_at",
            s.rebuild().startedAt() == null ? null : s.rebuild().startedAt().toString() );
        rebuild.put( "pages_total", s.rebuild().pagesTotal() );
        rebuild.put( "pages_iterated", s.rebuild().pagesIterated() );
        rebuild.put( "pages_chunked", s.rebuild().pagesChunked() );
        rebuild.put( "system_pages_skipped", s.rebuild().systemPagesSkipped() );
        rebuild.put( "lucene_queued", s.rebuild().luceneQueued() );
        rebuild.put( "chunks_written", s.rebuild().chunksWritten() );
        rebuild.put( "errors", s.rebuild().errors().stream().map( e -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "page", e.page() );
            m.put( "error", e.error() );
            m.put( "at", e.at() == null ? null : e.at().toString() );
            return m;
        } ).toList() );
        out.put( "rebuild", rebuild );
        return out;
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

    private void handleRefreshNews( final HttpServletRequest request,
                                     final HttpServletResponse response ) throws IOException {
        final NewsPageGenerator gen = getEngine().getManager( NewsPageGenerator.class );
        if ( gen == null ) {
            sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                       "News page generator is not running" );
            return;
        }
        LOG.info( "News page refresh triggered by {}", resolveActor( request ) );
        gen.generateNow();
        sendJson( response, Map.of( "triggered", true ) );
    }

    // ---- Helpers ----

    private String shortCacheName( final String fullName ) {
        return fullName.replace( "wikantik.", "" );
    }

    private static String resolveActor( final HttpServletRequest request ) {
        final String user = request.getRemoteUser();
        return ( user != null && !user.isEmpty() ) ? user : "admin";
    }

}

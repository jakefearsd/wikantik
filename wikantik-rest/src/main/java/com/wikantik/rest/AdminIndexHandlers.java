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

import com.wikantik.admin.ContentIndexRebuildService;
import com.wikantik.admin.IndexStatusSnapshot;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.knowledge.chunking.ContentChunkRepository;
import com.wikantik.search.SearchManager;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Search, chunk and embedding index routes of {@link AdminContentResource}, with the DTO mapping they serve. */
final class AdminIndexHandlers {

    private static final Logger LOG = LogManager.getLogger( AdminIndexHandlers.class );

    private final AdminContentResource resource;

    AdminIndexHandlers( final AdminContentResource resource ) {
        this.resource = resource;
    }

    /**
     * Deprecated: prefer {@code POST /admin/content/rebuild-indexes}. This
     * handler is retained for legacy operator scripts; response carries
     * RFC-8594 / RFC-8288 deprecation headers advertising the successor.
     */
    void handleReindex( final HttpServletResponse response ) throws IOException {
        // Set before any body is written (sendJson/sendError commits the response).
        response.setHeader( "Deprecation", "true" );
        response.setHeader( "Link",
            "</admin/content/rebuild-indexes>; rel=\"successor-version\"" );
        try {
            final SearchManager sm = resource.getSubsystems().search().searchManager();
            final PageManager pm = resource.getSubsystems().page().pages();

            // Reindex all pages by queuing each one
            final Collection< ? extends Page > allPages = pm.getAllPages();
            int count = 0;
            for ( final Page page : allPages ) {
                sm.reindexPage( page );
                count++;
            }

            LOG.info( "Queued {} pages for reindexing", count );
            resource.sendJson( response, Map.of( "started", true, "pagesQueued", count ) );
        } catch ( final ProviderException e ) {
            LOG.error( "Failed to trigger reindex", e );
            resource.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Reindex failed" );
        }
    }

    void handleIndexStatus( final HttpServletResponse response ) throws IOException {
        final ContentIndexRebuildService svc = resource.getSubsystems().pageGraph().contentIndexRebuildService();
        if ( svc == null ) {
            resource.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "rebuild service not available" );
            return;
        }
        RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_OK,
            snapshotToMap( svc.snapshot() ) );
    }

    void handleRebuildIndexes( final HttpServletResponse response ) throws IOException {
        final ContentIndexRebuildService svc = resource.getSubsystems().pageGraph().contentIndexRebuildService();
        if ( svc == null ) {
            resource.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "rebuild service not available" );
            return;
        }
        try {
            final IndexStatusSnapshot snap = svc.triggerRebuild();
            LOG.info( "Content index rebuild triggered" );
            RestJson.sendJsonKeepingNulls( response, 202, snapshotToMap( snap ) );
        } catch ( final ContentIndexRebuildService.ConflictException e ) {
            LOG.warn( "Rebuild rejected — already in flight: {}", e.getMessage() );
            RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_CONFLICT,
                snapshotToMap( e.current() ) );
        } catch ( final ContentIndexRebuildService.DisabledException e ) {
            LOG.warn( "Rebuild rejected — disabled by configuration" );
            RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
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
    @SuppressWarnings( "PMD.CloseResource" ) // BootstrapEmbeddingIndexer runs on a background thread and shuts itself down once bootstrap completes.
    void handleReindexEmbeddings( final HttpServletResponse response ) throws IOException {
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer boot =
            resource.getSubsystems().search().bootstrapEmbeddingIndexer();
        if ( boot == null ) {
            RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
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
            RestJson.sendJsonKeepingNulls( response, 202,
                Map.of( "state", boot.progress().state().name(),
                        "model_code", boot.modelCode() ) );
        } catch( final IllegalStateException e ) {
            LOG.warn( "Embedding reindex rejected — already running: {}", e.getMessage() );
            RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_CONFLICT,
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
    void handleChunksByPage( final HttpServletRequest request,
                                     final HttpServletResponse response ) throws IOException {
        final String page = request.getParameter( "page" );
        if ( page == null || page.isBlank() ) {
            resource.sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "page query parameter is required" );
            return;
        }
        final ContentChunkRepository repo = resource.getSubsystems().knowledge().contentChunkRepository();
        if ( repo == null ) {
            resource.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "chunk repository not available" );
            return;
        }
        final List< ContentChunkRepository.FullChunk > chunks = repo.findFullByPage( page );
        if ( chunks.isEmpty() ) {
            final Map< String, Object > body = new LinkedHashMap<>();
            body.put( "error", "page not found" );
            body.put( "page", page );
            RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_NOT_FOUND, body );
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
        RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_OK, body );
    }

    /**
     * Serves corpus-wide chunk outliers (top 10 each): pages with the most
     * chunks, single-chunk pages whose sole chunk is unusually large, and
     * chunks whose estimated token count exceeds the chunker's max target.
     */
    void handleChunkOutliers( final HttpServletResponse response ) throws IOException {
        final ContentChunkRepository repo = resource.getSubsystems().knowledge().contentChunkRepository();
        if ( repo == null ) {
            resource.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "chunk repository not available" );
            return;
        }
        final ContentChunkRepository.OutlierReport report = repo.outliers();
        final Map< String, Object > body = new LinkedHashMap<>();
        body.put( "most_chunks", outlierEntriesToMaps( report.mostChunks() ) );
        body.put( "large_single_chunks", outlierEntriesToMaps( report.largeSingleChunks() ) );
        body.put( "oversized_chunks", outlierEntriesToMaps( report.oversizedChunks() ) );
        RestJson.sendJsonKeepingNulls( response, HttpServletResponse.SC_OK, body );
    }

    List< Map< String, Object > > outlierEntriesToMaps(
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
     * Builds the {@code embeddings.embedder} sub-block from the live
     * {@link com.wikantik.search.hybrid.QueryEmbedder} manager. Returns a
     * {@code DISABLED} shape when hybrid retrieval is off so the UI can
     * render a stable table without null-checks.
     */
    Map< String, Object > embedderMap() {
        final Map< String, Object > m = new LinkedHashMap<>();
        final com.wikantik.search.hybrid.QueryEmbedder qe =
            resource.getSubsystems().search().queryEmbedder();
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
    @SuppressWarnings( "PMD.CloseResource" ) // BootstrapEmbeddingIndexer runs on a background thread and shuts itself down once bootstrap completes.
    Map< String, Object > bootstrapMap( final int liveRowCount ) {
        final Map< String, Object > m = new LinkedHashMap<>();
        final com.wikantik.search.embedding.BootstrapEmbeddingIndexer boot =
            resource.getSubsystems().search().bootstrapEmbeddingIndexer();
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
    Map< String, Object > snapshotToMap( final IndexStatusSnapshot s ) {
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

        // Rebuild block now has 10 numeric fields plus state/started_at/errors —
        // well past Map.of's 10-entry cap, so LinkedHashMap (also gives stable JSON ordering).
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
        rebuild.put( "embeddings_indexed", s.rebuild().embeddingsIndexed() );
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
}

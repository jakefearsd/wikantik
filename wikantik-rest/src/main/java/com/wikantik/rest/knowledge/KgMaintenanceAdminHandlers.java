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
package com.wikantik.rest.knowledge;

import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.knowledge.SummaryExtractor;
import com.wikantik.knowledge.TagExtractor;
import com.wikantik.knowledge.TitleDeriver;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;

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
 * Maintenance admin handlers extracted verbatim from {@code AdminKnowledgeResource}: listing
 * pages that lack frontmatter, the mention-embedding index status endpoint, the default-frontmatter
 * backfill job (status + trigger + the background worker itself), and the hub-membership resync.
 * <p>
 * Package-private-in-spirit: the type is {@code public} only because it must be constructed and
 * invoked from {@code com.wikantik.rest.AdminKnowledgeResource}'s dispatch table, which lives in a
 * different package — it is not part of any documented public API of {@code wikantik-rest}.
 * <p>
 * The {@code backfillRunning}/{@code backfillTotal}/{@code backfillProcessed}/{@code backfillErrors}
 * fields are instance state: this handler is constructed once in {@code buildResources()} and held
 * for the life of the servlet, so the backfill-progress state observed by
 * {@code GET /backfill-frontmatter} reflects the single background job started by
 * {@code POST /backfill-frontmatter}. One deliberate divergence from the pre-extraction layout:
 * these fields used to live on the servlet itself and survived Java deserialization
 * ({@code readObject} rebuilds only the dispatch table), whereas now deserialization constructs a
 * fresh handler and resets the guard. That path is inert in this deployment — web.xml declares no
 * {@code <distributable/>} and nothing serializes this servlet — but if session replication or
 * clustering is ever enabled, an in-flight backfill's guard would reset on failover.
 */
public final class KgMaintenanceAdminHandlers {

    private static final Logger LOG = LogManager.getLogger( KgMaintenanceAdminHandlers.class );

    private final Supplier< PageManager > pageManager;
    private final Supplier< SystemPageRegistry > systemPageRegistry;
    private final Supplier< NodeMentionSimilarity > nodeMentionSimilarity;
    private final Supplier< Engine > engine;

    public KgMaintenanceAdminHandlers( final Supplier< PageManager > pageManager,
                                        final Supplier< SystemPageRegistry > systemPageRegistry,
                                        final Supplier< NodeMentionSimilarity > nodeMentionSimilarity,
                                        final Supplier< Engine > engine ) {
        this.pageManager = pageManager;
        this.systemPageRegistry = systemPageRegistry;
        this.nodeMentionSimilarity = nodeMentionSimilarity;
        this.engine = engine;
    }

    // --- Pages without frontmatter ---

    public void handleGetPagesWithoutFrontmatter( final HttpServletRequest request,
                                                   final HttpServletResponse response ) throws IOException {
        final PageManager pm = pageManager.get();
        if ( pm == null ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "PageManager not available" );
            return;
        }
        final SystemPageRegistry spr = systemPageRegistry.get();
        final int limit = AdminKnowledgeIo.parseIntParam( request, "limit", 100 );
        final int offset = AdminKnowledgeIo.parseIntParam( request, "offset", 0 );
        try {
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
            final int total = pages.size();
            final List< Map< String, Object > > paged = pages.subList(
                    Math.min( offset, total ), Math.min( offset + limit, total ) );
            AdminKnowledgeIo.sendJson( response, Map.of( "total", total, "pages", paged ) );
        } catch ( final Exception e ) {
            LOG.error( "Failed to list pages without frontmatter", e );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed: " + e.getMessage() );
        }
    }

    // --- Embedding handlers ---

    private NodeMentionSimilarity getSimilarity() {
        return nodeMentionSimilarity.get();
    }

    public void handleGetEmbeddings( final HttpServletRequest request,
                                      final HttpServletResponse response,
                                      final String[] segments ) throws IOException {
        final NodeMentionSimilarity sim = getSimilarity();
        if ( segments.length >= 2 && "status".equals( segments[1] ) ) {
            // GET /admin/knowledge-graph/embeddings/status — reports the shared Ollama-backed
            // mention-centroid index.
            final Map< String, Object > result = new LinkedHashMap<>();
            final boolean ready = sim != null && sim.isReady();
            result.put( "ready", ready );
            result.put( "dimension", ready ? sim.dimension() : 0 );
            result.put( "mentioned_node_count", ready ? sim.mentionedNodeNames().size() : 0 );
            AdminKnowledgeIo.sendJson( response, result );
        } else {
            AdminKnowledgeIo.sendNotFound( response, "Unknown embeddings sub-resource" );
        }
    }

    public void handlePostEmbeddings( final HttpServletResponse response,
                                       final String[] segments ) throws IOException {
        // No post actions remain on /admin/knowledge-graph/embeddings — the chunk
        // embedding indexer runs continuously via AsyncEmbeddingIndexListener,
        // so there is nothing to manually retrigger here.
        AdminKnowledgeIo.sendNotFound( response, "Unknown embeddings sub-resource" );
    }

    // --- Backfill ---

    private volatile boolean backfillRunning = false;
    private volatile int backfillTotal = 0;
    private final AtomicInteger backfillProcessed = new AtomicInteger();
    private final AtomicInteger backfillErrors = new AtomicInteger();

    public void handleGetBackfillStatus( final HttpServletResponse response ) throws IOException {
        AdminKnowledgeIo.sendJson( response, Map.of(
            "running", backfillRunning,
            "total", backfillTotal,
            "processed", backfillProcessed.get(),
            "errors", backfillErrors.get()
        ) );
    }

    public void handlePostBackfillFrontmatter( final HttpServletResponse response ) throws IOException {
        if ( backfillRunning ) {
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_CONFLICT, "Backfill already in progress" );
            return;
        }

        final Thread t = new Thread( this::runBackfill, "frontmatter-backfill" );
        t.setDaemon( true );
        t.start();
        AdminKnowledgeIo.sendJson( response, Map.of( "status", "started" ) );
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
                    final Map< String, Object > metadata = new LinkedHashMap<>();
                    metadata.put( "title", TitleDeriver.derive( page.getName() ) );
                    metadata.put( "type", "article" );
                    metadata.put( "tags", TagExtractor.extract( body, 3 ) );
                    metadata.put( "summary", SummaryExtractor.extract( body ) );
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
        }
    }

    // --- Hub membership sync ---

    public void handlePostSyncHubMemberships( final HttpServletResponse response ) throws IOException {
        try {
            final PageManager pm = pageManager.get();
            final PageSaveHelper saveHelper = new PageSaveHelper( engine.get(), pm );
            int synced = 0;
            for ( final Page page : pm.getAllPages() ) {
                try {
                    final String content = pm.getPureText( page );
                    final ParsedPage parsed = FrontmatterParser.parse( content != null ? content : "" );
                    if ( "hub".equals( parsed.metadata().get( "type" ) ) ) {
                        saveHelper.saveText( page.getName(), content, SaveOptions.builder().build() );
                        synced++;
                    }
                } catch ( final Exception e ) {
                    LOG.warn( "Hub sync failed for '{}': {}", page.getName(), e.getMessage() );
                }
            }
            AdminKnowledgeIo.sendJson( response, Map.of( "status", "ok", "hubsSynced", synced ) );
        } catch ( final Exception e ) {
            LOG.warn( "Hub sync bootstrap failed: {}", e.getMessage() );
            AdminKnowledgeIo.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Hub sync failed: " + e.getMessage() );
        }
    }
}

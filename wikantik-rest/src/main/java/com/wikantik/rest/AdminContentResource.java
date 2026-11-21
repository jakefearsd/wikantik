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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * REST servlet for admin content management operations.
 * <p>
 * Mapped to {@code /admin/content/*}. Protected by {@link AdminAuthFilter}.
 * <ul>
 *   <li>{@code GET  /admin/content/stats} — wiki health dashboard</li>
 *   <li>{@code GET  /admin/content/orphaned-pages} — unreferenced pages</li>
 *   <li>{@code GET  /admin/content/broken-links} — missing link targets</li>
 *   <li>{@code GET  /admin/content/locked-pages} — pages whose access rules cannot be read, so only admins may use them</li>
 *   <li>{@code POST /admin/content/bulk-delete} — delete multiple pages</li>
 *   <li>{@code POST /admin/content/purge-versions} — purge old page versions</li>
 *   <li>{@code POST /admin/content/reindex} — force full search index rebuild (deprecated — use {@code /rebuild-indexes})</li>
 *   <li>{@code GET  /admin/content/index-status} — unified Lucene + chunk index snapshot</li>
 *   <li>{@code POST /admin/content/rebuild-indexes} — trigger async rebuild of Lucene + chunks</li>
 *   <li>{@code POST /admin/content/reindex-embeddings} — force full embedding reindex</li>
 *   <li>{@code POST /admin/content/cache/flush} — flush caches</li>
 * </ul>
 */
public class AdminContentResource extends RestServletBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    /** Test seam: clock (epoch millis) driving the scan-cache TTL. Read by {@link ContentHealthAdminHandlers}. */
    java.util.function.LongSupplier clockMs = System::currentTimeMillis;

    /** Test seam: replaces the real block-ref scan when set. Read by {@link ContentHealthAdminHandlers}. */
    java.util.function.Supplier< List< com.wikantik.references.BrokenBlockRefs.Entry > > blockRefScan;

    /** One action for one HTTP verb on one endpoint. Same idiom as the admin REST. */
    @FunctionalInterface
    private interface RouteAction {
        void invoke( HttpServletRequest request, HttpServletResponse response )
                throws ServletException, IOException;
    }

    /** Per-verb slots; a {@code null} slot 404s ("Unknown content endpoint"), matching pre-refactor behavior. */
    private record Route( RouteAction get, RouteAction post ) {
        static Route get( final RouteAction a )  { return new Route( a, null ); }
        static Route post( final RouteAction a ) { return new Route( null, a ); }
    }

    /**
     * Dispatch table — the single source of truth for this servlet's endpoints
     * (GoF Command registry, aligned to the admin REST house
     * pattern). Adding an endpoint is one line here plus one handler method. See
     * {@link PathRouteTable} for the shared matching engine and the
     * transient-field-plus-lazy-rebuild convention every route table here follows.
     */
    private transient volatile PathRouteTable< Route > routes;

    private PathRouteTable< Route > routes() {
        if ( routes == null ) {
            routes = buildRoutes();
        }
        return routes;
    }

    private PathRouteTable< Route > buildRoutes() {
        final CacheAdminHandlers cache = new CacheAdminHandlers( this );
        final ContentHealthAdminHandlers health = new ContentHealthAdminHandlers( this, cache );
        final ContentMutationAdminHandlers mutation = new ContentMutationAdminHandlers( this );
        final IndexAdminHandlers index = new IndexAdminHandlers( this );
        return PathRouteTable.< Route >builder()
            .exact( "stats",              Route.get( ( req, resp ) -> health.handleStats( resp ) ) )
            .exact( "orphaned-pages",     Route.get( ( req, resp ) -> health.handleOrphanedPages( resp ) ) )
            .exact( "broken-links",       Route.get( ( req, resp ) -> health.handleBrokenLinks( resp ) ) )
            .exact( "locked-pages",       Route.get( ( req, resp ) -> health.handleLockedPages( resp ) ) )
            .exact( "index-status",       Route.get( ( req, resp ) -> index.handleIndexStatus( resp ) ) )
            .exact( "chunks",             Route.get( index::handleChunksByPage ) )
            .exact( "chunks/outliers",    Route.get( ( req, resp ) -> index.handleChunkOutliers( resp ) ) )
            .exact( "bulk-delete",        Route.post( mutation::handleBulkDelete ) )
            .exact( "purge-versions",     Route.post( mutation::handlePurgeVersions ) )
            .exact( "reindex",            Route.post( ( req, resp ) -> index.handleReindex( resp ) ) )
            .exact( "rebuild-indexes",    Route.post( ( req, resp ) -> index.handleRebuildIndexes( resp ) ) )
            .exact( "reindex-embeddings", Route.post( ( req, resp ) -> index.handleReindexEmbeddings( resp ) ) )
            .exact( "cache/flush",        Route.post( cache::handleCacheFlush ) )
            .build();
    }

    private void dispatch( final HttpServletRequest request, final HttpServletResponse response,
            final java.util.function.Function< Route, RouteAction > verb )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        final Route route = routes().match( action ).orElse( null );
        final RouteAction handler = route == null ? null : verb.apply( route );
        if ( handler == null ) {
            sendNotFound( response, "Unknown content endpoint: " + action );
            return;
        }
        handler.invoke( request, response );
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( request, response, Route::get );
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        dispatch( request, response, Route::post );
    }
}

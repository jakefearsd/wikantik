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

import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.auth.JndiDataSources;
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.search.embedding.EmbeddingConfig;
import com.wikantik.search.embedding.EmbeddingIndexStatsRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedding-index and frontmatter-maintenance admin tools, mapped to {@code /admin/embeddings/*} and
 * protected by {@link AdminAuthFilter}.
 * <ul>
 *   <li>{@code GET  /admin/embeddings/status} — dense-retrieval index counts for the active model</li>
 *   <li>{@code GET  /admin/embeddings/pages-without-frontmatter?limit=&amp;offset=}</li>
 *   <li>{@code GET  /admin/embeddings/backfill-frontmatter} — backfill progress</li>
 *   <li>{@code POST /admin/embeddings/backfill-frontmatter} — start the backfill</li>
 * </ul>
 */
public class AdminEmbeddingsResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminEmbeddingsResource.class );

    private transient volatile FrontmatterBackfillAdminHandlers frontmatterHandlers;

    @Override
    protected boolean isCrossOriginAllowed() {
        return false;
    }

    /** One handler per servlet: it carries the single in-flight backfill's progress state. */
    private FrontmatterBackfillAdminHandlers frontmatterHandlers() {
        FrontmatterBackfillAdminHandlers h = frontmatterHandlers;
        if ( h == null ) {
            synchronized ( this ) {
                h = frontmatterHandlers;
                if ( h == null ) {
                    h = new FrontmatterBackfillAdminHandlers(
                            () -> getSubsystems().page().pages(),
                            () -> getSubsystems().core().systemPageRegistry(),
                            this::getEngine );
                    frontmatterHandlers = h;
                }
            }
        }
        return h;
    }

    /** Test seam: the embedding configuration in force. */
    EmbeddingConfig embeddingConfig() {
        final Engine engine = getEngine();
        return engine == null ? null : EmbeddingConfig.fromProperties( engine.getWikiProperties() );
    }

    /** Test seam: resolves the wiki datasource; {@code null} when none is configured. */
    EmbeddingIndexStatsRepository buildStatsRepository() {
        final Engine engine = getEngine();
        if ( engine == null ) {
            return null;
        }
        final String datasourceName = AbstractJDBCDatabase.datasourceName( engine.getWikiProperties() );
        try {
            final DataSource ds = JndiDataSources.lookup( datasourceName,
                    "AdminEmbeddingsResource", AbstractJDBCDatabase.PROP_DATASOURCE );
            return new EmbeddingIndexStatsRepository( ds );
        } catch ( final NoRequiredPropertyException e ) {
            LOG.warn( "no JNDI datasource resolved for embedding status ({}): {}", datasourceName, e.getMessage() );
            return null;
        }
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String resource = firstSegment( request, response );
        if ( resource == null ) {
            return;
        }
        switch ( resource ) {
            case "status" -> handleStatus( response );
            case "pages-without-frontmatter" -> frontmatterHandlers().handleGetPagesWithoutFrontmatter( request, response );
            case "backfill-frontmatter" -> frontmatterHandlers().handleGetBackfillStatus( response );
            default -> sendNotFound( response, "Unknown resource: " + resource );
        }
    }

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response ) throws IOException {
        final String resource = firstSegment( request, response );
        if ( resource == null ) {
            return;
        }
        if ( "backfill-frontmatter".equals( resource ) ) {
            frontmatterHandlers().handlePostBackfillFrontmatter( response );
        } else {
            sendNotFound( response, "Unknown resource: " + resource );
        }
    }

    /** First path segment, or {@code null} after writing a 400. */
    private String firstSegment( final HttpServletRequest request, final HttpServletResponse response )
            throws IOException {
        final String pathInfo = request.getPathInfo();
        if ( pathInfo == null || "/".equals( pathInfo ) ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Path required" );
            return null;
        }
        return pathInfo.substring( 1 ).split( "/" )[ 0 ];
    }

    private void handleStatus( final HttpServletResponse response ) throws IOException {
        final Map< String, Object > result = new LinkedHashMap<>();
        final EmbeddingConfig cfg = embeddingConfig();
        EmbeddingIndexStatsRepository.Stats stats = EmbeddingIndexStatsRepository.Stats.empty();
        String model = "";
        int dimension = 0;
        if ( cfg != null && cfg.enabled() ) {
            model = cfg.model().code();
            dimension = cfg.model().dimension();
            final EmbeddingIndexStatsRepository repo = buildStatsRepository();
            if ( repo != null ) {
                stats = repo.stats( model );
            }
        }
        result.put( "ready", stats.chunksEmbedded() > 0 );
        result.put( "model", model );
        result.put( "dimension", dimension );
        result.put( "pagesEmbedded", stats.pagesEmbedded() );
        result.put( "chunksTotal", stats.chunksTotal() );
        result.put( "chunksEmbedded", stats.chunksEmbedded() );
        sendJson( response, result );
    }
}

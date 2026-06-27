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

import com.wikantik.api.core.Session;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.derived.DerivedPageIngestionService;
import com.wikantik.derived.DerivedReflowService;
import com.wikantik.derived.IngestResult;
import com.wikantik.ingest.TikaSourceExtractor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Admin endpoint for derived-page reflow operations.
 *
 * <ul>
 *   <li>{@code GET  /admin/derived/status} — returns {@code {derivedTotal, staleCount, currentExtractorVersion}}.</li>
 *   <li>{@code POST /admin/derived/reflow} — re-ingests derived pages from their retained source attachments.
 *       Optional query parameter {@code ?page=PageName} scopes the operation to a single page;
 *       omitting it triggers a corpus-wide reflow ({@link DerivedReflowService#reflowAll}).</li>
 * </ul>
 *
 * <p>All endpoints are protected by {@code AdminAuthFilter} (the {@code /admin/*} filter mapping).
 */
public class AdminDerivedResource extends RestServletBase {

    private static final long   serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( AdminDerivedResource.class );

    // -------------------------------------------------------------------------
    // GET
    // -------------------------------------------------------------------------

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "status".equals( action ) ) {
            handleStatus( response );
        } else {
            sendNotFound( response, "Unknown derived endpoint: " + action );
        }
    }

    private void handleStatus( final HttpServletResponse response ) throws IOException {
        final DerivedReflowService svc = buildReflowService();
        final DerivedReflowService.ReflowStatus status = svc.status();
        sendJsonWithStatus( response, 200, Map.of(
            "derivedTotal",          status.derivedTotal(),
            "staleCount",            status.staleCount(),
            "currentExtractorVersion", status.currentExtractorVersion() ) );
    }

    // -------------------------------------------------------------------------
    // POST
    // -------------------------------------------------------------------------

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final String action = extractPathParam( request );
        if ( "reflow".equals( action ) ) {
            handleReflow( request, response );
        } else {
            sendNotFound( response, "Unknown derived endpoint: " + action );
        }
    }

    private void handleReflow( final HttpServletRequest request,
                                final HttpServletResponse response ) throws IOException {
        final DerivedReflowService svc    = buildReflowService();
        final String               page   = request.getParameter( "page" );
        final String               admin  = resolveAdminAuthor( request );

        if ( page != null && !page.isBlank() ) {
            // Single-page reflow
            LOG.info( "AdminDerivedResource: reflow requested for page='{}' by author='{}'", page, admin );
            final IngestResult result = svc.reflow( page, admin );
            sendJsonWithStatus( response, 200, Map.of(
                "page",    result.pageName(),
                "status",  result.status().name().toLowerCase( Locale.ROOT ),
                "message", result.message() != null ? result.message() : "" ) );
        } else {
            // Corpus-wide reflow
            LOG.info( "AdminDerivedResource: corpus-wide reflow requested by author='{}'", admin );
            final DerivedReflowService.ReflowSummary summary = svc.reflowAll( admin );
            LOG.info( "AdminDerivedResource: reflowAll complete — reflowed={} skipped={} failed={}",
                summary.reflowed(), summary.skipped(), summary.failed() );
            sendJsonWithStatus( response, 200, Map.of(
                "reflowed", summary.reflowed(),
                "skipped",  summary.skipped(),
                "failed",   summary.failed() ) );
        }
    }

    /**
     * Resolves the author name from the admin session principal.
     * This endpoint is always behind {@code AdminAuthFilter}, so the caller is an
     * authenticated admin. Falls back to {@code "system"} if for any reason the
     * session is not authenticated (defensive — should not occur in production).
     * Protected so tests can override without engine infrastructure.
     */
    protected String resolveAdminAuthor( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( getEngine(), request );
        return session.isAuthenticated() ? session.getUserPrincipal().getName() : "system";
    }

    // -------------------------------------------------------------------------
    // Service construction — overridable for tests
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link DerivedReflowService} from the live wiki managers.
     * Protected so tests can override without multipart or engine infrastructure.
     */
    protected DerivedReflowService buildReflowService() {
        final var engine     = getEngine();
        final AttachmentManager am = getSubsystems().page().attachments();
        final PageManager       pm = getSubsystems().page().pages();
        final PageSaveHelper    saveHelper = new PageSaveHelper( engine, pm );

        final DerivedPageIngestionService.AttachmentStore attachmentStore =
            ( pageName, filename, bytes ) -> {
                final var att = Wiki.contents().attachment( engine, pageName, filename );
                am.storeAttachment( att, new ByteArrayInputStream( bytes ) );
            };

        final DerivedPageIngestionService.PageReader pageReader =
            pageName -> {
                try {
                    final String text = pm.getPureText( pageName, WikiProvider.LATEST_VERSION );
                    if ( text == null || text.isBlank() ) {
                        return Optional.empty();
                    }
                    return Optional.of( FrontmatterParser.parse( text ).metadata() );
                } catch ( final Exception e ) {
                    LOG.warn( "AdminDerivedResource: could not read page '{}': {}", pageName, e.getMessage() );
                    return Optional.empty();
                }
            };

        final DerivedPageIngestionService.PageWriter pageWriter =
            ( pageName, body, metadata, author ) -> {
                final SaveOptions opts = SaveOptions.builder()
                        .metadata( metadata )
                        .author( author )
                        .replaceMetadata( true )
                        .changeNote( "derived page — reflowed from source document" )
                        .build();
                saveHelper.saveText( pageName, body, opts );
            };

        final DerivedPageIngestionService.PageDeleter pageDeleter =
            pageName -> pm.deletePage( pageName );

        final DerivedPageIngestionService ingestionService = new DerivedPageIngestionService(
                new TikaSourceExtractor(),
                attachmentStore,
                pageReader,
                pageWriter,
                pageDeleter );

        final DerivedReflowService.PageLister pageLister = () -> {
            try {
                return pm.getAllPages().stream()
                         .map( p -> p.getName() )
                         .toList();
            } catch ( final Exception e ) {
                LOG.warn( "AdminDerivedResource: could not list pages: {}", e.getMessage(), e );
                return java.util.List.of();
            }
        };

        final DerivedReflowService.AttachmentBytesReader attachmentReader =
            ( pageName, filename ) -> {
                final var att = am.getAttachmentInfo( pageName + "/" + filename );
                if ( att == null ) {
                    throw new java.io.FileNotFoundException(
                        "Attachment not found: " + pageName + "/" + filename );
                }
                try ( final var is = am.getAttachmentStream( att ) ) {
                    return is.readAllBytes();
                }
            };

        return new DerivedReflowService( pageReader::readMetadata, pageLister,
            attachmentReader, ingestionService );
    }

    // -------------------------------------------------------------------------
    // JSON helper (mirrors AdminOntologyResource)
    // -------------------------------------------------------------------------

    private void sendJsonWithStatus( final HttpServletResponse response,
                                     final int                  status,
                                     final Object               payload ) throws IOException {
        response.setStatus( status );
        response.setContentType( "application/json" );
        response.setCharacterEncoding( "UTF-8" );
        response.getWriter().write(
            new com.google.gson.GsonBuilder().serializeNulls().create().toJson( payload ) );
    }
}

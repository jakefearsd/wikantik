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
import com.wikantik.api.core.Session;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.SaveOptions;
import com.wikantik.api.providers.WikiProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.WikiPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.derived.DerivedPageIngestionService;
import com.wikantik.derived.IngestOptions;
import com.wikantik.derived.IngestResult;
import com.wikantik.ingest.TikaSourceExtractor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * REST endpoint for ingesting documents as derived wiki pages.
 *
 * <p>Mapped to {@code POST /api/ingest}. Accepts a {@code multipart/form-data}
 * request with a {@code file} part. Extracts the document body via Apache Tika,
 * stores the source bytes as an attachment, and saves (or updates) a derived
 * wiki page with {@code derived_from} provenance frontmatter.
 *
 * <p>Optional query parameters:
 * <ul>
 *   <li>{@code force=true} — re-ingest even if the source SHA is unchanged.</li>
 * </ul>
 *
 * <p>Requires the {@code createPages} wiki permission. Returns 403 if denied,
 * 400 if the {@code file} part is absent, 415 if the request is not multipart.
 */
public class DerivedIngestResource extends RestServletBase {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LogManager.getLogger( DerivedIngestResource.class );

    @Override
    protected void doPost( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {

        // Permission check: createPages wiki permission (not a page-scoped action —
        // there is no target page yet when ingesting a new document).
        if ( !checkCreatePagesPermission( request, response ) ) {
            return;
        }

        // Guard content type — getPart() throws on a non-multipart body.
        final String contentType = request.getContentType();
        if ( contentType == null || !contentType.regionMatches( true, 0, "multipart/", 0, 10 ) ) {
            sendError( response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "Document ingest requires a multipart/form-data request" );
            return;
        }

        final Part filePart = request.getPart( "file" );
        if ( filePart == null ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File part 'file' is required" );
            return;
        }

        final String rawFilename = filePart.getSubmittedFileName();
        if ( rawFilename == null || rawFilename.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST, "File name is required" );
            return;
        }

        // Security boundary: sanitize the submitted filename to a path-safe basename
        // before it is used as the attachment name or derived_from provenance value.
        // This prevents path-traversal attacks via crafted filenames such as
        // "../../etc/passwd.pdf" or "..\..\\Main.pdf".
        final String filename = sanitizeFilename( rawFilename );
        if ( filename.isBlank() ) {
            sendError( response, HttpServletResponse.SC_BAD_REQUEST,
                "File name is invalid after sanitization" );
            return;
        }

        final String fileMime = filePart.getContentType();
        final byte[] bytes = filePart.getInputStream().readAllBytes();

        final boolean force  = "true".equalsIgnoreCase( request.getParameter( "force" ) );
        final Session session = Wiki.session().find( getEngine(), request );
        final String  author  = session.isAuthenticated() ? session.getUserPrincipal().getName() : null;

        final DerivedPageIngestionService service = buildService();
        ingest( bytes, filename, fileMime, service, response, new IngestOptions( force, author ) );
    }

    // -------------------------------------------------------------------------
    // Package-private helper — allows tests to inject a mock service directly
    // without multipart infrastructure.
    // -------------------------------------------------------------------------

    /**
     * Executes the ingest and writes the JSON response.
     * Package-private so tests can call it with a mocked {@link DerivedPageIngestionService}.
     *
     * <p>The {@code filename} parameter is sanitized to a path-safe basename before being
     * forwarded to the service, so callers (including tests) may pass raw submitted filenames.
     */
    void ingest( final byte[] bytes,
                 final String filename,
                 final String contentType,
                 final DerivedPageIngestionService service,
                 final HttpServletResponse response ) throws IOException {
        ingest( bytes, sanitizeFilename( filename ), contentType, service, response,
            new IngestOptions( false, null ) );
    }

    /**
     * Reduces a caller-supplied filename to a path-safe basename, retaining the extension.
     *
     * <p>Strips any leading path components (both {@code /} and {@code \} separators),
     * removes null bytes and ASCII control characters, and trims whitespace.
     * The extension is preserved so Tika content-type detection continues to work.
     * An empty result after stripping is returned as-is (callers should reject it).
     */
    static String sanitizeFilename( final String filename ) {
        if ( filename == null ) {
            return "";
        }
        // Extract basename: strip everything up to and including the last / or \
        String base = filename;
        final int lastSep = Math.max( base.lastIndexOf( '/' ), base.lastIndexOf( '\\' ) );
        if ( lastSep >= 0 ) {
            base = base.substring( lastSep + 1 );
        }
        // Remove null bytes and ASCII control characters
        base = base.replaceAll( "[\\x00-\\x1F\\x7F]", "" );
        // Trim and return (extension kept for Tika)
        return base.trim();
    }

    private void ingest( final byte[] bytes,
                         final String filename,
                         final String contentType,
                         final DerivedPageIngestionService service,
                         final HttpServletResponse response,
                         final IngestOptions opts ) throws IOException {
        LOG.debug( "POST /api/ingest: filename='{}', contentType='{}', force={}, author={}",
                filename, contentType, opts.force(), opts.author() );

        final IngestResult result = service.ingest( bytes, filename, contentType, opts );

        sendJson( response, Map.of(
                "page",    result.pageName(),
                "status",  result.status().name().toLowerCase(),
                "message", result.message() != null ? result.message() : "" ) );
    }

    // -------------------------------------------------------------------------
    // Service construction — per-request, from live managers (stateless + cheap)
    // -------------------------------------------------------------------------

    /**
     * Builds a {@link DerivedPageIngestionService} from the live wiki managers.
     * Constructed per-request; the service is stateless so this is cheap.
     * Protected so tests can override without multipart or engine infrastructure.
     */
    protected DerivedPageIngestionService buildService() {
        final Engine engine = getEngine();
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
                    LOG.warn( "DerivedIngestResource: could not read page '{}': {}", pageName, e.getMessage() );
                    return Optional.empty();
                }
            };

        final DerivedPageIngestionService.PageWriter pageWriter =
            ( pageName, body, metadata, author ) -> {
                final SaveOptions opts = SaveOptions.builder()
                        .metadata( metadata )
                        .author( author )
                        .replaceMetadata( true )
                        .changeNote( "derived page — ingested from source document" )
                        .build();
                saveHelper.saveText( pageName, body, opts );
            };

        return new DerivedPageIngestionService(
                new TikaSourceExtractor(),
                attachmentStore,
                pageReader,
                pageWriter );
    }

    // -------------------------------------------------------------------------
    // Permission helper
    // -------------------------------------------------------------------------

    /**
     * Checks the {@code createPages} wiki permission for the current session.
     * Sends a 403 and returns {@code false} if denied.
     */
    private boolean checkCreatePagesPermission( final HttpServletRequest request,
                                                final HttpServletResponse response ) throws IOException {
        final Engine engine = getEngine();
        final Session session = Wiki.session().find( engine, request );
        final AuthorizationManager authMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();
        if ( authMgr.checkPermission( session, WikiPermission.CREATE_PAGES ) ) {
            return true;
        }
        sendError( response, HttpServletResponse.SC_FORBIDDEN, "Forbidden: createPages permission required" );
        return false;
    }
}

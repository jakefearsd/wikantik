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
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.content.ContentWarningSink;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.frontmatter.schema.FrontmatterWarningSink;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.content.BlockDrafts;
import com.wikantik.content.BlockIdService;
import com.wikantik.derived.DerivedPage;
import com.wikantik.preview.PageExcerpts;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.spi.Wiki;
import com.wikantik.parser.markdown.BlockIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code GET/POST /api/pages/{name}/blocks}: lists a page's addressable blocks (for the block-link picker) and gives one
 * of them a {@code ^id}. Missing and unviewable pages are the same 404; a viewable page the caller cannot edit is a 403
 * on POST. Lives outside {@link PageResource} to keep that servlet's complexity in check.
 */
final class PageBlocksHandler {

    private static final Logger LOG = LogManager.getLogger( PageBlocksHandler.class );

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final int TEXT_CHARS = 120;
    private static final int ABSENT = Integer.MIN_VALUE;
    /** A JSON body may spell each character of the text in up to this many (a backslash-u escape). */
    private static final int DRAFT_ESCAPE_FACTOR = 6;
    /** Room in a draft body for everything but the text. */
    private static final int DRAFT_SLACK = 4096;

    private final RestServletBase servlet;

    PageBlocksHandler( final RestServletBase servlet ) {
        this.servlet = servlet;
    }

    /** {@code GET}: the block list; see the spec for the JSON shape. */
    void handleGet( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        final Page page = viewablePage( request, pm, pageName );
        if ( page == null ) {
            servlet.sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return;
        }
        final String text = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final int limit = Math.min( MAX_LIMIT, Math.max( 1, servlet.parseIntParam( request, "limit", DEFAULT_LIMIT ) ) );
        final JsonArray rows = rows( blocksOf( pageName, text, false ), request.getParameter( "q" ), limit, false );
        final JsonObject body = new JsonObject();
        body.addProperty( "version", Math.max( page.getVersion(), 1 ) );
        body.addProperty( "canAssign", text != null && servlet.hasPagePermission( request, pageName, "edit" )
                && !DerivedPage.isDerived( FrontmatterParser.parse( text ).metadata() ) );
        body.add( "blocks", rows );
        response.setHeader( "Cache-Control", "private, no-cache" );
        RestJson.sendJsonKeepingNulls( response, body );
    }

    /**
     * The listing rows of {@code blocks} matching {@code q} (in their text or id, any case; all when blank), at most
     * {@code limit}; with each block's {@code from}/{@code to} in its text when {@code withRange}.
     */
    private static JsonArray rows( final java.util.List< BlockIds.Block > blocks, final String q, final int limit,
                                   final boolean withRange ) {
        final String needle = q == null ? "" : q.trim().toLowerCase( Locale.ROOT );
        final JsonArray rows = new JsonArray();
        for ( final BlockIds.Block b : blocks ) {
            final String shown = shownText( b );
            if ( !needle.isEmpty() && !shown.toLowerCase( Locale.ROOT ).contains( needle )
                    && ( b.id() == null || !b.id().toLowerCase( Locale.ROOT ).contains( needle ) ) ) {
                continue;
            }
            final JsonObject row = new JsonObject();
            row.addProperty( "index", b.index() );
            row.add( "id", b.id() == null ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive( b.id() ) );
            row.addProperty( "kind", b.kind().name().toLowerCase( Locale.ROOT ).replace( '_', '-' ) );
            row.addProperty( "text", shown.length() > TEXT_CHARS ? shown.substring( 0, TEXT_CHARS ) : shown );
            if ( withRange ) {
                row.addProperty( "from", b.start() );
                row.addProperty( "to", b.end() );
            }
            rows.add( row );
            if ( rows.size() >= limit ) {
                break;
            }
        }
        return rows;
    }

    /**
     * {@code POST /blocks/draft}: the server's block model of the caller's unsaved text (the editor's buffer), which is
     * the one authority for block identity — the editor never decides an id from its own parse. The draft is what a PUT
     * of {@code {content: text, metadata}} (with {@code replaceMetadata}) would save: with a non-empty {@code metadata}
     * object, {@code text} is a body behind that frontmatter, so a leading {@code ---} pair in it is content
     * ({@link BlockIds#listBody}); without one, {@code text} is the whole page. {@code {text}} lists the blocks (with
     * {@code from}/{@code to} in that text; optional {@code q}, {@code limit}); {@code {text, offset}} answers the block
     * the reader shows at {@code offset}: {@code {id, change: null}} when it carries an id, else
     * {@code {id, change: {from, to, insert}}}, the one edit that gives it one — or 409 {@code no-block} /
     * {@code refused}. Nothing is saved.
     *
     * <p>An existing page has the posture of {@code POST /blocks}: 404 when the caller cannot view it, an audited 403
     * without edit. A page not saved yet takes a draft from whoever holds PUT-create's permission — {@code edit} on the
     * name — so its editor can link blocks before the first save; without it, the same 404 as a hidden page. The body is read no further
     * than the cap allows, with or without a {@code Content-Length} (413), and the text is capped like a page body.
     */
    void handleDraft( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        if ( !draftAllowed( request, response, pageName ) ) {
            return;
        }
        final int maxBytes = PageResource.parseMaxPageBytes( servlet.getEngine() );
        // JSON can spell a character in up to six (a u-escape): a body beyond that bound cannot hold an allowed text
        final long maxBody = DRAFT_ESCAPE_FACTOR * ( long ) maxBytes + DRAFT_SLACK;
        if ( request.getContentLengthLong() > maxBody ) {
            servlet.sendError( response, 413, "Draft text is limited to " + maxBytes + " bytes" );
            return;
        }
        final JsonObject body = RestJson.parseJsonBody( request, response, maxBody );
        if ( body == null ) {
            return;
        }
        final String text = servlet.getJsonString( body, "text" );
        if ( text == null ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "text is required" );
            return;
        }
        if ( text.getBytes( java.nio.charset.StandardCharsets.UTF_8 ).length > maxBytes ) {
            servlet.sendError( response, 413, "Draft text is limited to " + maxBytes + " bytes" );
            return;
        }
        final JsonElement metadata = body.get( "metadata" );
        if ( metadata != null && !metadata.isJsonNull() && !metadata.isJsonObject() ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "metadata must be an object" );
            return;
        }
        // a PUT writes frontmatter only for a non-empty metadata object; then the text is a body behind it
        final boolean behindFrontmatter = metadata != null && metadata.isJsonObject() && !metadata.getAsJsonObject().isEmpty();
        response.setHeader( "Cache-Control", "private, no-store" );
        final int offset = servlet.getJsonInt( body, "offset", ABSENT );
        if ( offset == ABSENT ) {
            final int limit = Math.min( MAX_LIMIT, Math.max( 1, servlet.getJsonInt( body, "limit", MAX_LIMIT ) ) );
            final JsonObject out = new JsonObject();
            out.add( "blocks", rows( blocksOf( pageName, text, behindFrontmatter ), servlet.getJsonString( body, "q" ), limit, true ) );
            RestJson.sendJsonKeepingNulls( response, out );
            return;
        }
        draftLink( response, pageName, text, behindFrontmatter, offset );
    }

    /**
     * Whether the caller may have a draft of {@code pageName} read; when not, the 404 or 403 has been sent. An existing
     * page: view (else 404), then the audited edit check (else 403). A page not saved yet: PUT-create's own permission,
     * edit on a valid name — asked quietly, and without it the very 404 a hidden page gets, so the endpoint never tells
     * a page that is missing from one the caller may not see (as {@code GET}/{@code POST /blocks} do not).
     */
    private boolean draftAllowed( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        final boolean exists = pm.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION ) != null;
        final boolean found = exists ? viewablePage( request, pm, pageName ) != null
                : !PageResource.isInvalidPageName( pageName ) && servlet.hasPagePermission( request, pageName, "edit" );
        if ( !found ) {
            servlet.sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return false;
        }
        return !exists || servlet.checkPagePermission( request, response, pageName, "edit" );
    }

    /** Answers the block at {@code offset} of the draft {@code text}: its id, or the edit that gives it one, or a 409. */
    private void draftLink( final HttpServletResponse response, final String pageName, final String text, final boolean body,
                            final int offset ) throws IOException {
        final BlockDrafts.Link link;
        try {
            link = BlockDrafts.linkAt( pageName, text, body, offset, BlockIds::newId );
        } catch ( final BlockDrafts.Refused e ) {
            conflict( response, e.reason() == BlockDrafts.Refusal.NO_BLOCK ? "no-block" : "refused" );
            return;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Draft block link of '{}' at {} could not be read ({}); refused", pageName, offset, e.toString(), e );
            conflict( response, "refused" );
            return;
        }
        final JsonObject out = new JsonObject();
        out.addProperty( "id", link.id() );
        if ( link.change() == null ) {
            out.add( "change", JsonNull.INSTANCE );
        } else {
            final JsonObject change = new JsonObject();
            change.addProperty( "from", link.change().from() );
            change.addProperty( "to", link.change().to() );
            change.addProperty( "insert", link.change().insert() );
            out.add( "change", change );
        }
        final JsonObject block = new JsonObject();
        block.addProperty( "index", link.block().index() );
        block.addProperty( "from", link.block().start() );
        block.addProperty( "to", link.block().end() );
        out.add( "block", block );
        RestJson.sendJsonKeepingNulls( response, out );
    }

    /**
     * The blocks of {@code text} — a whole page, or a body behind frontmatter when {@code body} — or none when its text
     * is gone (deleted since it was looked up) or cannot be read into blocks: a listing never fails the request.
     */
    private static java.util.List< BlockIds.Block > blocksOf( final String pageName, final String text, final boolean body ) {
        if ( text == null ) {
            LOG.warn( "Block list of '{}': the page has no text; listing no blocks", pageName );
            return java.util.List.of();
        }
        try {
            return BlockDrafts.list( text, body );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Block list of '{}' could not be read ({}); listing no blocks", pageName, e.toString(), e );
            return java.util.List.of();
        }
    }

    /** {@code POST}: assigns an id to the located block. */
    void handlePost( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        if ( viewablePage( request, pm, pageName ) == null ) {
            servlet.sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return;
        }
        if ( !servlet.checkPagePermission( request, response, pageName, "edit" ) ) {   // audited 403, as PUT
            return;
        }
        final JsonObject body = servlet.parseJsonBody( request, response );
        if ( body == null ) {
            return;
        }
        final int version = servlet.getJsonInt( body, "version", ABSENT );
        final int index = servlet.getJsonInt( body, "index", ABSENT );
        if ( version == ABSENT || index == ABSENT ) {
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "version and index are required" );
            return;
        }
        final String fingerprint = servlet.getJsonString( body, "fingerprint" );
        final Session session = Wiki.session().find( servlet.getEngine(), request );
        final String author = session.isAuthenticated() ? AuthorKeys.authorKeyFor( session ) : null;
        final BlockIdService service = new BlockIdService( pm, new PageSaveHelper( servlet.getEngine(), pm ) );
        assign( response, service, pageName, version, index, fingerprint, author );
    }

    /** Runs the assignment and maps each way it can end to its status and body. */
    private void assign( final HttpServletResponse response, final BlockIdService service, final String pageName,
                         final int version, final int index, final String fingerprint, final String author )
            throws IOException {
        try {
            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
            final BlockIdService.Assigned a = service.assign( pageName, version, index, fingerprint, author );
            final Map< String, Object > out = new LinkedHashMap<>();
            out.put( "id", a.id() );
            out.put( "version", a.version() );
            FrontmatterWarningSink.drain( pageName );
            ContentWarningSink.drain();
            servlet.sendJson( response, out );
        } catch ( final BlockIdService.Refused e ) {
            conflict( response, switch ( e.reason() ) {
                case STALE -> "stale";
                case NOT_LOCATED -> "block-not-located";
                case DERIVED -> "derived";
            } );
        } catch ( final ContentValidationException e ) {
            ContentWarningSink.clear();
            LOG.debug( "Math validation rejected block id on '{}': {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageResource.mathValidationErrorBody( e ) );
        } catch ( final FrontmatterValidationException e ) {
            FrontmatterWarningSink.clear();
            LOG.debug( "Frontmatter validation rejected block id on '{}': {}", pageName, e.getMessage() );
            response.setStatus( 422 );
            servlet.sendJson( response, PageResource.validationErrorBody( e ) );
        } catch ( final VersionConflictException e ) {
            LOG.debug( "Block id assignment on '{}' lost a version race: {}", pageName, e.getMessage() );
            conflict( response, "stale" );
        } catch ( final WikiException | RuntimeException e ) {
            FrontmatterWarningSink.clear();
            ContentWarningSink.clear();
            LOG.error( "Block id assignment on '{}' (block {}) failed: {}", pageName, index, e.getMessage(), e );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not add a block id." );
        }
    }

    /**
     * The excerpt for {@code section=^id} on {@code /preview}: the block's plain text, or empty when the page holds no
     * such block (the caller then falls back to the page excerpt and flags {@code blockMissing}).
     */
    static Optional< String > blockExcerpt( final String pageName, final String rawText, final String section ) {
        if ( !BlockIds.isBlockRef( section ) ) {
            return Optional.empty();
        }
        final Optional< BlockIds.Block > block;
        try {
            block = BlockIds.find( rawText, BlockIds.idOf( section ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Block {} of '{}' could not be read for a preview ({}); showing the page excerpt", section, pageName,
                    e.toString(), e );
            return Optional.empty();
        }
        return block.map( b -> {
            final String shown = shownText( b );
            return shown.length() > PageExcerpts.MAX_CHARS ? shown.substring( 0, PageExcerpts.MAX_CHARS ).trim() + "\u2026" : shown;
        } );
    }

    /** The block's plain text without access-rule directives, which a viewer must not read. */
    private static String shownText( final BlockIds.Block b ) {
        return AclDirectiveText.strip( b.plainText(), "" ).trim();
    }

    private Page viewablePage( final HttpServletRequest request, final PageManager pm, final String pageName ) {
        final Page page = pm.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
        return page == null || !servlet.hasPagePermission( request, pageName, "view" ) ? null : page;
    }

    private void conflict( final HttpServletResponse response, final String reason ) throws IOException {
        response.setStatus( HttpServletResponse.SC_CONFLICT );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "reason", reason );
        servlet.sendJson( response, out );
    }
}

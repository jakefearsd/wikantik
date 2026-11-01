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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pages.PageSaveHelper;
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.auth.AuthorKeys;
import com.wikantik.auth.acl.AclDirectiveText;
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
        final String q = request.getParameter( "q" );
        final String needle = q == null ? "" : q.trim().toLowerCase( Locale.ROOT );
        final int limit = Math.min( MAX_LIMIT, Math.max( 1, servlet.parseIntParam( request, "limit", DEFAULT_LIMIT ) ) );
        final JsonArray rows = new JsonArray();
        for ( final BlockIds.Block b : BlockIds.list( text ) ) {
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
            rows.add( row );
            if ( rows.size() >= limit ) {
                break;
            }
        }
        final JsonObject body = new JsonObject();
        body.addProperty( "version", Math.max( page.getVersion(), 1 ) );
        body.addProperty( "canAssign", servlet.hasPagePermission( request, pageName, "edit" )
                && !DerivedPage.isDerived( FrontmatterParser.parse( text ).metadata() ) );
        body.add( "blocks", rows );
        response.setHeader( "Cache-Control", "private, no-cache" );
        RestJson.sendJsonKeepingNulls( response, body );
    }

    /** {@code POST}: assigns an id to the located block. */
    void handlePost( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        if ( viewablePage( request, pm, pageName ) == null ) {
            servlet.sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return;
        }
        if ( !servlet.hasPagePermission( request, pageName, "edit" ) ) {
            servlet.sendError( response, HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
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
        try {
            final BlockIdService.Assigned a = service.assign( pageName, version, index, fingerprint, author );
            final Map< String, Object > out = new LinkedHashMap<>();
            out.put( "id", a.id() );
            out.put( "version", a.version() );
            servlet.sendJson( response, out );
        } catch ( final BlockIdService.Refused e ) {
            conflict( response, switch ( e.reason() ) {
                case STALE -> "stale";
                case NOT_LOCATED -> "block-not-located";
                case DERIVED -> "derived";
            } );
        } catch ( final VersionConflictException e ) {
            LOG.debug( "Block id assignment on '{}' lost a version race: {}", pageName, e.getMessage() );
            conflict( response, "stale" );
        } catch ( final WikiException | RuntimeException e ) {
            LOG.error( "Block id assignment on '{}' (block {}) failed: {}", pageName, index, e.getMessage(), e );
            servlet.sendError( response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Could not add a block id." );
        }
    }

    /**
     * The excerpt for {@code section=^id} on {@code /preview}: the block's plain text, or empty when the page holds no
     * such block (the caller then falls back to the page excerpt and flags {@code blockMissing}).
     */
    static Optional< String > blockExcerpt( final String rawText, final String section ) {
        if ( !BlockIds.isBlockRef( section ) ) {
            return Optional.empty();
        }
        return BlockIds.find( rawText, BlockIds.idOf( section ) ).map( b -> {
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

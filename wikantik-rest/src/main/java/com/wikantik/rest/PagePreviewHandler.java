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

import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.export.HeadingSlugs;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.ReaderComments;
import com.wikantik.preview.PageExcerpts;
import com.wikantik.util.TextUtil;
import com.wikantik.wikilink.WikiEmbedRenderer;
import com.wikantik.wikilink.WikiLinkResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code /preview} (link-preview card) and {@code /embed} (transclusion body) sub-resources of
 * {@code /api/pages/{name}}. Split out of {@link PageReadHandler}; behaviour unchanged.
 */
final class PagePreviewHandler {

    private static final Logger LOG = LogManager.getLogger( PagePreviewHandler.class );

    private final RestServletBase servlet;

    PagePreviewHandler( final RestServletBase servlet ) {
        this.servlet = servlet;
    }

    /** {@code GET /api/pages/{name}/preview}: link-preview card data; missing and unviewable are the same 404. */
    void handlePreview( final HttpServletRequest request, final HttpServletResponse response,
                        final String pageName ) throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        final Page page = pm.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
        if ( page == null || !servlet.hasPagePermission( request, pageName, "view" ) ) {
            servlet.sendError( response, HttpServletResponse.SC_NOT_FOUND, "Page not found" );
            return;
        }
        // The excerpt is cut from the stripped FULL text (a section is cut from that, not stripped on its own).
        final String rawText = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final ParsedPage parsed = AclDirectiveText.parseStripped( rawText, " " );
        final Map< String, Object > fm = parsed.metadata();
        final PreviewExcerpt excerpt = previewExcerpt( pageName, rawText, parsed.body(), request.getParameter( "section" ) );
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "name", pageName );
        final Object title = fm.get( "title" );
        out.put( "title", title != null && !title.toString().isBlank()
                ? title.toString().trim() : TextUtil.beautifyString( pageName ) );
        putIfText( out, "type", fm.get( "type" ) );
        final Object cluster = fm.get( "cluster" );
        putIfText( out, "cluster", cluster instanceof List< ? > l && !l.isEmpty() ? l.get( 0 ) : cluster );
        putIfText( out, "summary", fm.get( "summary" ) );
        out.put( "excerpt", excerpt.text() );
        if ( excerpt.blockMissing() ) {
            out.put( "blockMissing", true );
        }
        out.put( "lastModified", page.getLastModified() );
        // Per-viewer (ACL-filtered) and stale after a save: always revalidate, never reuse from the HTTP cache.
        response.setHeader( "Cache-Control", "private, no-cache" );
        servlet.sendJson( response, out );
    }

    /** A preview card's excerpt, and whether a requested block reference no longer resolves. */
    private record PreviewExcerpt( String text, boolean blockMissing ) { }

    /** The excerpt for the requested {@code section} (a heading slug or a block reference), or the page's opening. */
    private static PreviewExcerpt previewExcerpt( final String pageName, final String rawText, final String strippedBody,
                                                  final String section ) {
        // Without its %%comments%% before a section is cut, so a comment crossing the section's end is never left open.
        String body = ReaderComments.strip( strippedBody );
        if ( section != null && BlockIds.isBlockRef( section ) ) {
            final String blockExcerpt = PageBlocksHandler.blockExcerpt( pageName, rawText, section ).orElse( null );
            if ( blockExcerpt != null ) {
                return new PreviewExcerpt( blockExcerpt, false );
            }
            return new PreviewExcerpt( PageExcerpts.excerpt( body, PageExcerpts.MAX_CHARS ), true );
        }
        if ( section != null && !section.isBlank() ) {
            body = HeadingSlugs.sectionBody( body, section ).orElse( body );
        }
        return new PreviewExcerpt( PageExcerpts.excerpt( body, PageExcerpts.MAX_CHARS ), false );
    }

    /** Placeholder host page that seeds the embed renderer's loop stack; never a real page name. */
    private static final String EMBED_HOST_PAGE = "<embed>";

    /**
     * Embed body for {@code ![[name]]}: {@code 403} only when the caller cannot view the resolved page; an
     * unresolvable target answers {@code 200 missing:true}. The title is the client's to render (html is body only).
     */
    void handleEmbed( final HttpServletRequest request, final HttpServletResponse response,
                      final String name ) throws IOException {
        final Engine engine = servlet.getEngine();
        final WikiLinkResolver.Resolution resolved = WikiLinkResolver.forEngine( engine ).resolve( name );
        if ( resolved.exists() && !servlet.checkPagePermission( request, response, resolved.pageName(), "view" ) ) {
            return;
        }
        // The endpoint has no host page: use a placeholder whose name cannot be a real page, so the renderer's
        // loop guard (seeded with the context page) never mistakes the embedded page for its own host.
        final Page page = Wiki.contents().page( engine, EMBED_HOST_PAGE );
        final Context context = Wiki.context().create( engine, request, page );
        final String section = request.getParameter( "section" );
        final WikiEmbedRenderer.EmbedResult r = WikiEmbedRenderer.forEngine( engine ).render( context, name, section );
        final Map< String, Object > out = new LinkedHashMap<>();
        // The body left the rendering manager outside textToHTML, so its server-fence placeholders are resolved here.
        final String body = postTranslate( context, name, r.bodyHtml() == null ? "" : r.bodyHtml() );
        out.put( "html", servlet.getSubsystems().rendering().renderingManager().resolveServerFences( body ) );
        out.put( "missing", r.missing() );
        out.put( "restricted", r.restricted() );
        out.put( "truncated", r.truncated() );
        // Viewer-dependent and stale after a save: never reusable from the HTTP cache.
        response.setHeader( "Cache-Control", "private, no-cache" );
        servlet.sendJson( response, out );
    }

    /**
     * Applies the post-translate filters a server-side embed inherits from its host render (e.g. {@code cite://}
     * href rewriting), so a previewed body matches the rendered one. A failing filter leaves the body unfiltered.
     */
    private String postTranslate( final Context context, final String name, final String html ) {
        try {
            return servlet.getSubsystems().rendering().filterManager().doPostTranslateFiltering( context, html );
        } catch ( final FilterException e ) {
            LOG.warn( "Post-translate filtering of the embed preview of '{}' failed; serving it unfiltered: {}",
                    name, e.getMessage() );
            return html;
        }
    }

    private static void putIfText( final Map< String, Object > out, final String key, final Object value ) {
        if ( value != null && !value.toString().isBlank() ) {
            out.put( key, value.toString().trim() );
        }
    }
}

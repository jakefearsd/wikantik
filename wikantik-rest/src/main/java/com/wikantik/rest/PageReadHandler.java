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

import com.google.gson.JsonObject;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.content.WikiToMarkdownConverter;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.parser.markdown.ReaderText;
import com.wikantik.render.RenderingManager;
import com.wikantik.search.embedding.PageEmbeddingSimilarity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read side of {@code /api/pages/{name}}: the page payload ({@code GET}, with optional rendered HTML or a pinned
 * version) and its {@code /similar} sub-resource; {@code /preview} and {@code /embed} are {@link PagePreviewHandler}'s. Lives outside
 * {@link PageResource} to keep that servlet's complexity in check.
 */
final class PageReadHandler {

    private static final Logger LOG = LogManager.getLogger( PageReadHandler.class );

    private final RestServletBase servlet;

    PageReadHandler( final RestServletBase servlet ) {
        this.servlet = servlet;
    }

    /**
     * {@code GET /api/pages/{name}}: checks view permission, then answers the page's text, metadata, version,
     * permissions and (optionally) rendered HTML.
     */
    void handleGet( final HttpServletRequest request, final HttpServletResponse response, final String pageName )
            throws IOException {
        if ( !servlet.checkPagePermission( request, response, pageName, "view" ) ) return;

        LOG.debug( "GET page: {}", pageName );

        final Engine engine = servlet.getEngine();

        // Rendering decision is hoisted ABOVE the page load because it selects the
        // accessor: only the rendering path needs page metadata. textToHTML builds a
        // Context on the page, so [{SET}] page variables must be populated there; the
        // JSON payload otherwise reads name/version/author/lastModified and takes its
        // metadata from FrontmatterParser.parse(rawText) directly.
        //
        // 2026-09-25 profiling campaign: after the authorization fix,
        // CachingProvider.refreshMetadata was still 10.00% of ALL CPU and 100% of it
        // arrived through this endpoint's own getPage.
        final boolean render = "true".equalsIgnoreCase( request.getParameter( "render" ) );

        final Loaded loaded = load( request, response, pageName, render );
        if ( loaded == null ) return;
        final Page page = loaded.page();
        final String rawText = loaded.rawText();

        // Page-view metric: notify listeners (WikiMetrics) that a page was read.
        firePageRequested( engine, page.getName() );

        // Access-rule directives ([{ALLOW edit alice@…}]) name people by login. Whoever may edit the page
        // gets its text verbatim — the editor round-trips this very field — everyone else reads it without them.
        // The text is stripped once, here, and everything a reader is shown (body, frontmatter, hash) derives from it;
        // the rendered HTML below starts from rawText because the renderer consumes the directives itself.
        final boolean canEdit = servlet.hasPagePermission( request, pageName, "edit" );
        final String aclVisible = AclDirectiveText.forViewer( canEdit, rawText );
        final String visibleText = ReaderText.forViewer( canEdit, aclVisible );
        final ParsedPage parsed = FrontmatterParser.parse( visibleText );
        final String versionParam = request.getParameter( "version" );
        auditRead( request, pageName, latestFrontmatterForAudit(
                versionParam == null || versionParam.isEmpty(), rawText, aclVisible, parsed ) );

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "name", page.getName() );
        result.put( "content", parsed.body() );
        if ( render ) {
            result.put( "contentHtml", renderHtml( request, pageName, page, rawText ) );
        }

        // Readers see display names, never stored logins. Frontmatter author/verified_by are editor-typed text:
        // they are replaced only when they happen to be a login. The editor SPA writes this metadata back verbatim
        // (replaceMetadata), so a caller who can edit the page gets it exactly as stored.
        final PublicIdentity identity = servlet.getSubsystems().auth().publicIdentity();
        result.put( "metadata", canEdit ? parsed.metadata() : IdentityDisplay.frontmatter( identity, parsed.metadata() ) );
        // content and metadata above stay raw (the editor saves them back, so an expanded value there would be stored
        // permanently); readers take the live-value expansion of the summary from this separate block.
        final Map< String, Object > resolved = resolvedBlock( parsed );
        if ( resolved != null ) {
            result.put( "resolved", resolved );
        }
        result.put( "version", Math.max( page.getVersion(), 1 ) );
        result.put( "author", IdentityDisplay.ofAuthor( identity, page.getAuthor() ) );
        result.put( "lastModified", page.getLastModified() );
        result.put( "exists", true );
        // Live-session-first, never cached with the page: someone editing it, or an unpublished draft (MultiEdit 16).
        result.put( "draft_active", com.wikantik.collab.adapter.CollabEngineAccess.draftActive( servlet.getEngine(), page.getName() ) );

        // D19: surface the content hash so clients can round-trip GET → PUT with
        // expectedContentHash without computing the digest themselves.
        // An editor round-trips GET → PUT with the hash of the full text. A reader never PUTs, and the hash of the
        // full text would let them confirm a guess at a hidden directive by hashing candidate texts — so they get
        // the hash of what they can see.
        final String contentHash = com.wikantik.api.pages.PageSaveHelper.computeContentHash( visibleText );
        result.put( "contentHash", contentHash );
        result.put( "markupSyntax", markupSyntax( page, contentHash, parsed.body() ) );
        result.put( "permissions", permissions( request, pageName, canEdit ) );

        final JsonObject clusterStatus = new ClusterStatusResolver( servlet ).resolve( request, parsed.metadata() );
        if ( clusterStatus != null ) {
            result.put( "cluster_status", clusterStatus );
        }

        // Why the page is locked (#104), for administrators only: the reasons quote the rule text, logins included.
        final Map< String, Object > accessRules = isAdministrator( request ) ? LockedAccessRules.of( rawText ) : null;
        if ( accessRules != null ) {
            result.put( "accessRules", accessRules );
        }

        servlet.sendJson( response, result );
    }

    /** {@code {summary: <expanded>}} for a page with a string summary; {@code null} (omit the block) otherwise. */
    private Map< String, Object > resolvedBlock( final ParsedPage parsed ) {
        final ParsedPage expanded = com.wikantik.livevalues.LiveFrontmatter.expandSummary( parsed,
                com.wikantik.livevalues.LiveValuesLookup.of( servlet.getEngine() ) );
        return expanded.metadata().get( "summary" ) instanceof String summary ? Map.of( "summary", summary ) : null;
    }

    /** A page as read: its handle and its raw stored text. */
    private record Loaded( Page page, String rawText ) { }

    /** The requested (or latest) version of the page, or {@code null} after answering 400 or 404. */
    private Loaded load( final HttpServletRequest request, final HttpServletResponse response, final String pageName,
                         final boolean render ) throws IOException {
        final PageManager pm = servlet.getSubsystems().page().pages();
        final String versionParam = request.getParameter( "version" );
        if ( versionParam == null || versionParam.isEmpty() ) {
            final Page page = render ? pm.getPage( pageName )
                                     : pm.getPageWithoutMetadata( pageName, PageProvider.LATEST_VERSION );
            if ( page == null ) {
                servlet.sendNotFound( response, "Page not found: " + pageName );
                return null;
            }
            return new Loaded( page, pm.getPureText( pageName, PageProvider.LATEST_VERSION ) );
        }
        final int version;
        try {
            version = Integer.parseInt( versionParam );
        } catch ( final NumberFormatException e ) {
            LOG.info( "Rejecting page request for '{}' with non-numeric version '{}': {}",
                pageName, versionParam, e.getMessage() );
            servlet.sendError( response, HttpServletResponse.SC_BAD_REQUEST, "Invalid version number: " + versionParam );
            return null;
        }
        final Page page = render ? pm.getPage( pageName, version )
                                 : pm.getPageWithoutMetadata( pageName, version );
        final String rawText = page == null ? null : pm.getPureText( pageName, version );
        if ( rawText == null ) {
            servlet.sendNotFound( response, "Page not found: " + pageName + " version " + version );
            return null;
        }
        return new Loaded( page, rawText );
    }

    /**
     * Audits sensitive reads only when the page opts in (default: no-op). The policy guard short-circuits cheaply —
     * the normal path is allocation-free. A failing audit is logged and never breaks the read.
     */
    /**
     * The page's latest frontmatter for the read-audit policy, so the policy does not load and parse the page a
     * second time; {@code null} (let the policy look it up) when this request read an older version. The policy
     * must see the page's real frontmatter, not the reader's view of it: comment stripping never touches the YAML
     * block, but access-rule stripping can (a directive inside a frontmatter value, or a block emptied in fail-closed
     * mode), so the parsed reader view is reused only when nothing was stripped — {@code AclDirectiveText} returns
     * the very same instance then.
     */
    static Map< String, Object > latestFrontmatterForAudit( final boolean latestRead, final String rawText,
                                                           final String aclVisible, final ParsedPage parsedVisible ) {
        if ( !latestRead ) {
            return null;
        }
        return aclVisible == rawText ? parsedVisible.metadata() : FrontmatterParser.parse( rawText ).metadata();
    }

    private void auditRead( final HttpServletRequest request, final String pageName,
                            final Map< String, Object > latestFrontmatter ) {
        final Engine engine = servlet.getEngine();
        final var auditPolicy = engine instanceof com.wikantik.WikiEngine we ? we.getAuditReadPolicy() : null;
        final var auditService = engine instanceof com.wikantik.WikiEngine we2 ? we2.getAuditService() : null;
        if ( auditPolicy == null || auditService == null || !auditPolicy.shouldAudit( pageName, latestFrontmatter ) ) {
            return;
        }
        try {
            final com.wikantik.api.core.Session auditSession = Wiki.session().find( engine, request );
            final String principalName = auditSession.isAuthenticated()
                    ? auditSession.getUserPrincipal().getName()
                    : null;
            auditService.record( com.wikantik.audit.AuditEntry.builder()
                    .eventTime( java.time.Instant.now() )
                    .category( com.wikantik.audit.AuditCategory.READ )
                    .eventType( "page.read" )
                    .outcome( com.wikantik.audit.AuditOutcome.SUCCESS )
                    .actorPrincipal( principalName )
                    .actorType( principalName == null ? "anonymous" : "user" )
                    .targetType( "page" ).targetId( pageName ).targetLabel( pageName )
                    .sourceIp( request.getRemoteAddr() )
                    .userAgent( request.getHeader( "User-Agent" ) )
                    .build() );
        } catch ( final Exception auditEx ) {
            LOG.warn( "Failed to record page-read audit for {}: {}", pageName, auditEx.getMessage(), auditEx );
        }
    }

    /** The page rendered to HTML, or {@code null} when rendering fails (the SPA then degrades to raw markdown). */
    private String renderHtml( final HttpServletRequest request, final String pageName, final Page page,
                               final String rawText ) {
        final long t0 = System.nanoTime();
        try {
            final RenderingManager renderingManager = servlet.getSubsystems().rendering().renderingManager();
            final Context context = Wiki.context().create( servlet.getEngine(), request, page );
            final String html = renderingManager.textToHTML( context, rawText );
            LOG.info( "Rendered page={} bytes={} durationMs={}",
                    pageName, html != null ? html.length() : 0, ( System.nanoTime() - t0 ) / 1_000_000L );
            return html;
        } catch ( final Exception e ) {
            // Full throwable, not just getMessage(): an NPE/CME here logs "null"
            // and the stack is the only clue (this exact line hid the shared-
            // WikiDocument concurrent-render bug for months). The SPA degrades
            // to raw markdown when contentHtml is null, so this must be loud.
            LOG.warn( "Failed to render page {} (durationMs={})",
                    pageName, ( System.nanoTime() - t0 ) / 1_000_000L, e );
            return null;
        }
    }

    /**
     * Markup syntax — "wiki" for legacy .txt pages, "markdown" for .md pages, or "likely-wiki" if the heuristic
     * detects wiki syntax in a .md page. The heuristic is content-hash-memoised (markdown-native corpus: almost always
     * false, and the answer only changes when the body changes) so this is ~free on the read path.
     */
    private static String markupSyntax( final Page page, final String contentHash, final String body ) {
        final String attribute = page.getAttribute( Page.MARKUP_SYNTAX );
        final String syntax = attribute == null ? "markdown" : attribute;
        return "markdown".equals( syntax ) && WikiToMarkdownConverter.isLikelyWikiSyntax( contentHash, body )
                ? "likely-wiki" : syntax;
    }

    /** The current user's effective permissions for the page. */
    private Map< String, Boolean > permissions( final HttpServletRequest request, final String pageName,
                                                final boolean canEdit ) {
        final Map< String, Boolean > permissions = new LinkedHashMap<>();
        permissions.put( "edit", canEdit );
        permissions.put( "comment", servlet.hasPagePermission( request, pageName, "comment" ) );
        permissions.put( "upload", servlet.hasPagePermission( request, pageName, "upload" ) );
        permissions.put( "rename", servlet.hasPagePermission( request, pageName, "rename" ) );
        permissions.put( "delete", servlet.hasPagePermission( request, pageName, "delete" ) );
        return permissions;
    }

    /** Whether the caller holds {@link AllPermission}: a silent check, no audit row. */
    private boolean isAdministrator( final HttpServletRequest request ) {
        final Session session = Wiki.session().find( servlet.getEngine(), request );
        return servlet.getSubsystems().auth().authorization().isPermitted( session, new AllPermission( servlet.getEngine().getApplicationName() ) );
    }

    /**
     * Fires a {@link WikiPageEvent#PAGE_REQUESTED} event so observers — notably the
     * Micrometer {@code wikantik.page.views} counter — see reader / agent page reads.
     * Best-effort: a metrics-path failure must never break serving the page.
     */
    private void firePageRequested( final Engine engine, final String pageName ) {
        try {
            // Fire with the engine as the client: WikiEventManager routes by client
            // object, and WikiMetrics registers its listener on the engine.
            com.wikantik.core.subsystem.CoreSubsystemBridge.fromLegacyEngine( engine ).eventBus()
                    .fireEvent( engine, new WikiPageEvent( engine, WikiPageEvent.PAGE_REQUESTED, pageName ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "Failed to fire PAGE_REQUESTED event for '{}': {}", pageName, e.getMessage() );
        }
    }

    /**
     * Returns pages similar to {@code pageName} by cosine similarity of their chunk-embedding
     * centroids (the same vectors hybrid search uses — no Knowledge Graph). Only existing pages the
     * caller may view are suggested. Degrades to an empty list when embeddings are unavailable.
     */
    void handleSimilarPages( final HttpServletRequest request,
                                        final HttpServletResponse response,
                                        final String pageName ) throws IOException {
        if ( !servlet.checkPagePermission( request, response, pageName, "view" ) ) return;
        final PageEmbeddingSimilarity similarity = servlet.getSubsystems().search().pageSimilarity();
        if ( similarity == null ) {
            servlet.sendJson( response, Map.of( "similar", List.of() ) );
            return;
        }
        final int limit = Math.max( 0, Math.min( servlet.parseIntParam( request, "limit", 5 ), PageApiLimits.MAX_SIMILAR_LIMIT ) );
        final PageManager pm = servlet.getSubsystems().page().pages();
        final var similar = similarity.similarTo( pageName, limit,
            name -> pm.wikiPageExists( name ) && !servlet.filterViewable( request, List.of( name ) ).isEmpty() );
        servlet.sendJson( response, Map.of( "similar", similar.stream().map( s -> {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "name", s.name() );
            m.put( "similarity", s.score() );
            return m;
        } ).toList() ) );
    }
}

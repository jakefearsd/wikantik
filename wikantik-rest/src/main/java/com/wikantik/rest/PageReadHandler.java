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
import com.google.gson.JsonObject;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.core.Session;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.pagegraph.ClusterDetails;
import com.wikantik.api.pagegraph.ClusterPath;
import com.wikantik.api.pagegraph.ClusterStatus;
import com.wikantik.api.pagegraph.PageDescriptor;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.content.WikiToMarkdownConverter;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.export.HeadingSlugs;
import com.wikantik.parser.markdown.BlockIds;
import com.wikantik.parser.markdown.ReaderComments;
import com.wikantik.parser.markdown.ReaderText;
import com.wikantik.preview.PageExcerpts;
import com.wikantik.render.RenderingManager;
import com.wikantik.search.embedding.PageEmbeddingSimilarity;
import com.wikantik.util.TextUtil;
import com.wikantik.wikilink.WikiEmbedRenderer;
import com.wikantik.wikilink.WikiLinkResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The read side of {@code /api/pages/{name}}: the page payload ({@code GET}, with optional rendered HTML or a pinned
 * version) and its {@code /similar}, {@code /preview} and {@code /embed} sub-resources. Lives outside
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
        auditRead( request, pageName );

        // Access-rule directives ([{ALLOW edit alice@…}]) name people by login. Whoever may edit the page
        // gets its text verbatim — the editor round-trips this very field — everyone else reads it without them.
        // The text is stripped once, here, and everything a reader is shown (body, frontmatter, hash) derives from it;
        // the rendered HTML below starts from rawText because the renderer consumes the directives itself.
        final boolean canEdit = servlet.hasPagePermission( request, pageName, "edit" );
        final String visibleText = ReaderText.forViewer( canEdit, AclDirectiveText.forViewer( canEdit, rawText ) );
        final ParsedPage parsed = FrontmatterParser.parse( visibleText );

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

        final JsonObject clusterStatus = clusterStatusJson( request, parsed.metadata() );
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
    private void auditRead( final HttpServletRequest request, final String pageName ) {
        final Engine engine = servlet.getEngine();
        final var auditPolicy = engine instanceof com.wikantik.WikiEngine we ? we.getAuditReadPolicy() : null;
        final var auditService = engine instanceof com.wikantik.WikiEngine we2 ? we2.getAuditService() : null;
        if ( auditPolicy == null || auditService == null || !auditPolicy.shouldAudit( pageName ) ) {
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
     *  Builds the derived {@code cluster_status} block for a page, or {@code null} when the
     *  page names no cluster.
     *
     *  <p>Whether a cluster has a declaring hub is not in the page's own frontmatter, so the
     *  reader cannot compute it — the server resolves it here.</p>
     *
     *  <p>{@code hub_slug} is omitted when no hub declares the cluster (Gson drops nulls), so
     *  the boolean {@code hub_declared} carries that signal instead: it is always present and
     *  cannot vanish, and the client keys its "cluster not yet defined" badge off it rather
     *  than inferring meaning from an absent key.</p>
     *
     *  <p>Never throws: a structural index that is absent or still warming degrades to the
     *  declared path with no hub, which the UI renders as an undeclared cluster.</p>
     */
    private JsonObject clusterStatusJson( final HttpServletRequest request, final Map< String, Object > metadata ) {
        // Phase 5: `cluster:` is a scalar or a list. The FIRST entry is the primary and keeps
        // driving placement — breadcrumbs, sidebar, JSON-LD — so `path` never changes meaning.
        final List< String > memberships =
                ClusterPath.memberships( metadata == null ? null : metadata.get( "cluster" ) );
        if ( memberships.isEmpty() ) {
            return null;
        }
        final ClusterStatus status = resolveCluster( request, memberships.get( 0 ), true );

        final JsonObject out = new JsonObject();
        out.addProperty( "path", status.path() );
        out.addProperty( "parent", status.parent() );
        out.addProperty( "hub_slug", status.hubSlug() );
        out.addProperty( "hub_declared", status.hubSlug() != null );
        out.addProperty( "member_count", status.memberCount() );

        // Emitted only when there is genuinely more than one home, so a single-cluster page's
        // payload is byte-identical to what pre-Phase-5 clients already handle.
        if ( memberships.size() > 1 ) {
            final JsonArray all = new JsonArray();
            for ( final String path : memberships ) {
                final ClusterStatus each = resolveCluster( request, path, false );
                final JsonObject entry = new JsonObject();
                entry.addProperty( "path", each.path() );
                entry.addProperty( "hub_slug", each.hubSlug() );
                entry.addProperty( "hub_declared", each.hubSlug() != null );
                all.add( entry );
            }
            out.add( "memberships", all );
        }
        return out;
    }

    /**
     *  Resolves one cluster path against the structural index, degrading to an undeclared
     *  status when the index is unavailable — a warming or absent index must leave the page
     *  renderable rather than failing the whole payload.
     */
    private ClusterStatus resolveCluster( final HttpServletRequest request, final String cluster,
                                          final boolean countMembers ) {
        try {
            final StructuralIndexService idx = servlet.getSubsystems().pageGraph().structuralIndexService();
            final ClusterDetails details = idx == null ? null : idx.getCluster( cluster ).orElse( null );
            return ClusterStatus.of( cluster, details == null ? null : asSeenBy( request, details, countMembers ) );
        } catch ( final RuntimeException e ) {
            LOG.warn( "cluster_status: structural index unavailable for cluster '{}': {}",
                      cluster, e.getMessage() );
            return ClusterStatus.of( cluster, null );
        }
    }

    /**
     *  The cluster as the caller sees it: a hub they may not view is absent and, when {@code countMembers}, only the
     *  members they may view are counted (as on every other cluster count). Secondary memberships report no count,
     *  so they cost one check, for the hub.
     */
    private ClusterDetails asSeenBy( final HttpServletRequest request, final ClusterDetails details,
                                     final boolean countMembers ) {
        final List< String > names = new ArrayList<>();
        if ( details.hubPage() != null ) {
            names.add( details.hubPage().slug() );
        }
        if ( countMembers ) {
            details.articles().forEach( a -> names.add( a.slug() ) );
        }
        final java.util.Set< String > viewable = servlet.filterViewable( request, names );
        final PageDescriptor hub = details.hubPage() != null && viewable.contains( details.hubPage().slug() )
                ? details.hubPage() : null;
        final List< PageDescriptor > articles = countMembers
                ? details.articles().stream().filter( a -> viewable.contains( a.slug() ) ).toList()
                : details.articles();
        return new ClusterDetails( details.name(), hub, articles, details.tagDistribution(), details.updatedAt() );
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
        final int limit = Math.max( 0, Math.min( servlet.parseIntParam( request, "limit", 5 ), PageResource.MAX_SIMILAR_LIMIT ) );
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

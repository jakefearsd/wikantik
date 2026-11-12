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
import com.wikantik.api.core.Session;
import com.wikantik.auth.permissions.PermissionFilter;
import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.ParsedPage;
import com.wikantik.api.identity.IdentityDisplay;
import com.wikantik.api.identity.PublicIdentity;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.identity.PublicIdentities;
import com.wikantik.render.RenderingManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;
import com.wikantik.ui.SemanticHeadRenderer;
import com.wikantik.auth.acl.AclDirectiveText;
import com.wikantik.parser.markdown.ReaderText;
import com.wikantik.util.BaseUrlResolver;
import com.wikantik.api.pagegraph.PageType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * SPA routing filter for the React frontend.
 *
 * <p>Handles three concerns:
 * <ol>
 *   <li><b>Redirects</b> — {@code /}, {@code /wiki}, and {@code /wiki/} redirect
 *       to {@code /wiki/Main} so the wiki always has a concrete page.
 *       {@code /graph} redirects 301 to {@code /page-graph} (renamed SPA route).
 *       </li>
 *   <li><b>SPA forwarding</b> — known SPA prefixes ({@code /wiki/}, {@code /edit/},
 *       {@code /diff/}) and exact SPA routes ({@code /search}, {@code /page-graph},
 *       {@code /preferences}, {@code /reset-password}, {@code /admin/*} tab routes)
 *       are served the React app's {@code /index.html}. Admin API endpoints like
 *       {@code /admin/content/stats} pass through to their servlets.</li>
 *   <li><b>Static assets</b> — requests containing a file extension (other than
 *       {@code .html}) pass through to Tomcat's default servlet.</li>
 * </ol>
 *
 * <p>All prefix/exact matching is done on the path <em>after</em> stripping the
 * servlet context path. This lets the WAR be deployed either at root
 * ({@code /}) or under a sub-context ({@code /wikantik-it-test-custom}) and
 * still match {@code /wiki/*} correctly. Redirects are also context-aware so
 * browsers land on a URL inside the current context.
 *
 * <p>The React bundle references its own assets with absolute paths
 * ({@code /assets/index-*.js}) because the vite {@code base} is set to
 * {@code "/"}. That works only at root context; under a sub-context the
 * browser would resolve {@code /assets/...} to the host root and miss the
 * WAR entirely. When serving {@code index.html} for a non-root context,
 * {@link #rewriteIndexHtml(String, String)} injects the context prefix into
 * asset URLs so the browser fetches them from inside the WAR. It also
 * injects an {@code <script type="importmap">} that remaps {@code /assets/}
 * to the context-prefixed path, so lazy-loaded chunks resolved via
 * {@code import()} at runtime also hit the correct WAR. A
 * {@code vite:preloadError} listener suppresses the CSS-preload 404 that
 * Vite's helper would otherwise throw (Vite prepends {@code /} instead of
 * the context path when constructing {@code <link>} tags at runtime for
 * lazy-chunk CSS deps). Finally, it exposes {@code window.__WIKANTIK_BASE__}
 * so the API client can prepend the same prefix to fetch calls.
 */
public class SpaRoutingFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( SpaRoutingFilter.class );

    private static final String[] SPA_PREFIXES = { "/wiki/", "/edit/", "/diff/", "/admin/" };
    private static final String[] SPA_EXACT = { "/admin", "/admin/insights", "/search", "/page-graph", "/preferences", "/reset-password", "/login", "/me/mentions", "/change-password" };

    private volatile Engine engine;
    private ServletContext servletContext;
    private volatile String shellFingerprint;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        this.servletContext = filterConfig != null ? filterConfig.getServletContext() : null;
    }

    /**
     *  Slugs of the pages in this page's cluster that {@code viewer} may view, when the page is a hub — used as the
     *  hub's JSON-LD {@code hasPart}.
     *
     *  <p>What a cluster contains is a fact held by the structural index, not by the hub's
     *  own frontmatter, so it has to be resolved here rather than derived from
     *  {@code related:}. The hub itself is excluded: a collection is not part of itself.
     *  The index lists every member, including pages the caller may not view (a page that also joins a private
     *  cluster, a member of a private sub-cluster), so the list is cut down to the caller's view: the hub's head then
     *  differs per viewer, which {@link #isViewerDependent} accounts for.</p>
     *
     *  <p>Returns an empty list for non-hubs and whenever the index is unavailable — the
     *  emitter then falls back to frontmatter {@code related}, so SSR never breaks or
     *  blocks on a warming index.</p>
     */
    private static java.util.List< String > hubMembers( final Engine eng, final Session viewer, final String pageName,
                                                         final ParsedPage parsed, final HttpServletRequest req ) {
        final Object type = parsed.metadata().get( "type" );
        if ( PageType.fromFrontmatter( type ) != PageType.HUB ) {
            return java.util.List.of();
        }
        // A hub declares exactly one cluster — its primary — even if the page also joins others.
        final String cluster = java.util.Objects.requireNonNullElse(
                com.wikantik.api.pagegraph.ClusterPath.primary( parsed.metadata().get( "cluster" ) ), "" );
        if ( cluster.isBlank() ) {
            return java.util.List.of();
        }
        try {
            final Object subs = req.getServletContext()
                                   .getAttribute( com.wikantik.WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE );
            if ( !( subs instanceof com.wikantik.WikiSubsystems ws ) || ws.pageGraph() == null ) {
                return java.util.List.of();
            }
            final com.wikantik.api.pagegraph.StructuralIndexService idx = ws.pageGraph().structuralIndexService();
            if ( idx == null ) {
                return java.util.List.of();
            }
            final java.util.List< String > members = idx.getCluster( cluster )
                      .map( d -> d.articles().stream()
                                  .map( com.wikantik.api.pagegraph.PageDescriptor::slug )
                                  .filter( s -> !s.equals( pageName ) )
                                  .toList() )
                      .orElseGet( java.util.List::of );
            final java.util.Set< String > viewable = viewer == null
                    ? java.util.Set.of() : new PermissionFilter( eng ).filterViewableQuietly( viewer, members );
            return members.stream().filter( viewable::contains ).toList();
        } catch ( final RuntimeException e ) {
            LOG.warn( "SpaRoutingFilter: hub membership unavailable for cluster '{}': {}",
                      cluster, e.getMessage() );
            return java.util.List.of();
        }
    }

    /**
     * Lazily resolve the {@link Engine}. The filter may be constructed before
     * WikiEngine completes initialization (filter {@code init()} runs early in
     * the servlet context startup sequence), so we defer the lookup until the
     * first request and cache the result.
     */
    private Engine resolveEngine() {
        Engine e = engine;
        if ( e == null && servletContext != null ) {
            try {
                e = Wiki.engine().find( servletContext, null );
                if ( e != null ) {
                    engine = e;
                }
            } catch ( final RuntimeException ex ) {
                LOG.warn( "SpaRoutingFilter: engine lookup failed, semantic head will be skipped: {}",
                        ex.getMessage() );
            }
        }
        return e;
    }

    /** Test seam: inject a resolved engine so unit tests can exercise the
     *  semantic-injection / 404 path without a live servlet container. */
    void setEngineForTest( final Engine e ) {
        this.engine = e;
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        // Strip the context path so prefix matching is context-agnostic.
        // For root-context deployments contextPath is "", for subcontexts (like
        // IT test WARs) it's e.g. "/wikantik-it-test-custom". Without this
        // strip, "/wikantik-it-test-custom/wiki/Main".startsWith("/wiki/") is
        // false and every browser navigation falls through to a 404.
        final String contextPath = req.getContextPath() != null ? req.getContextPath() : "";
        final String rawUri = req.getRequestURI();
        final String path = rawUri != null && rawUri.startsWith( contextPath )
            ? rawUri.substring( contextPath.length() )
            : rawUri;

        // 301 redirects for renamed SPA routes.
        if ( "/graph".equals( path ) ) {
            final String qs = req.getQueryString();
            final String target = contextPath + "/page-graph" + ( qs != null ? "?" + qs : "" );
            sendMovedPermanently( resp, target );
            return;
        }

        // /wiki and /wiki/ have no page of their own; the front page lives at the site root.
        if ( "/wiki/".equals( path ) || "/wiki".equals( path ) ) {
            sendMovedPermanently( resp, contextPath + "/" );
            return;
        }

        // The site root (and /index.html, which the container's welcome-file handling resolves it to)
        // serves the front page in place, so the root URL carries real content and is canonical.
        if ( "".equals( path ) || "/".equals( path ) || "/index.html".equals( path ) ) {
            final Engine eng = resolveEngine();
            final String front = eng != null ? eng.getFrontPage() : null;
            // A missing front page still gets the plain shell with a 200 (never a 404 at the site root).
            final boolean frontExists = front != null && !front.isEmpty() && resolvePageForCaching( front ) != null;
            serveIndexHtml( req, resp, contextPath, frontExists ? front : null, front );
            return;
        }

        // Let static assets through (JS, CSS, images, fonts, favicon).
        // Cache headers are set by CacheHeaderFilter.
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            chain.doFilter( request, response );
            return;
        }

        // /wiki/* is always a browser navigation target — nothing else is
        // mapped to that URL space. Always forward, regardless of Accept
        // header, so crawlers (which send Accept: */* or omit the header)
        // still receive the server-rendered semantic head + body fallback.
        if ( path.startsWith( "/wiki/" ) ) {
            final String pageName = extractPageName( path );
            serveIndexHtml( req, resp, contextPath, pageName, frontPageName() );
            return;
        }

        // Other SPA prefixes (/edit/, /diff/, /admin/) and exact
        // routes (/search etc.) share URL space with JSON APIs, so we gate
        // forwarding on Accept: text/html to let fetch() calls reach their
        // servlets.
        final String accept = req.getHeader( "Accept" );
        final boolean isBrowserNavigation = accept != null && accept.contains( "text/html" );

        if ( isBrowserNavigation ) {
            for ( final String prefix : SPA_PREFIXES ) {
                if ( path.startsWith( prefix ) ) {
                    serveIndexHtml( req, resp, contextPath, null, null );
                    return;
                }
            }
            for ( final String exact : SPA_EXACT ) {
                if ( path.equals( exact ) || path.startsWith( exact + "?" ) ) {
                    serveIndexHtml( req, resp, contextPath, null, null );
                    return;
                }
            }
        }

        // Everything else passes through
        chain.doFilter( request, response );
    }

    /** The engine's front page name, or {@code null} when the engine is not available. */
    private String frontPageName() {
        final Engine eng = resolveEngine();
        return eng != null ? eng.getFrontPage() : null;
    }

    /**
     * Pull the page name out of a {@code /wiki/PageName} path, stripping any
     * query string and URL-decoding the segment. Returns an empty string for
     * {@code /wiki/} so callers can short-circuit safely.
     */
    static String extractPageName( final String wikiPath ) {
        if ( wikiPath == null ) {
            return "";
        }
        int start = "/wiki/".length();
        if ( wikiPath.length() <= start ) {
            return "";
        }
        String rest = wikiPath.substring( start );
        final int slashIdx = rest.indexOf( '/' );
        if ( slashIdx >= 0 ) {
            rest = rest.substring( 0, slashIdx );
        }
        final int queryIdx = rest.indexOf( '?' );
        if ( queryIdx >= 0 ) {
            rest = rest.substring( 0, queryIdx );
        }
        final int hashIdx = rest.indexOf( '#' );
        if ( hashIdx >= 0 ) {
            rest = rest.substring( 0, hashIdx );
        }
        try {
            return URLDecoder.decode( rest, StandardCharsets.UTF_8 );
        } catch ( final IllegalArgumentException ex ) {
            // Client-supplied path; the raw path is the best available answer.
            LOG.debug( "Path '{}' is not valid URL-encoding; using it undecoded: {}", rest, ex.getMessage() );
            return rest;
        }
    }

    /**
     * Read {@code /index.html} from the servlet context, rewrite asset URLs to
     * include the current context path, inject semantic head/body for the
     * requested wiki page (if any), and write the result to the response.
     *
     * @param pageName the wiki page name for {@code /wiki/*} navigations, or
     *                 {@code null} for other SPA routes where no semantic
     *                 rendering is needed
     */
    private void serveIndexHtml( final HttpServletRequest req, final HttpServletResponse resp,
                                  final String contextPath, final String pageName,
                                  final String frontPageName ) throws IOException {
        final Page cacheablePage = pageName != null && !pageName.isEmpty()
                ? resolvePageForCaching( pageName ) : null;
        // The caller and their view permission are resolved ONCE per request and threaded into both the ETag
        // and the injection step: a denied caller must neither be server-rendered nor
        // share a validator with a permitted one.
        final Session viewer = cacheablePage == null ? null : viewerSession( req );
        final boolean viewAllowed = cacheablePage == null || mayView( viewer, pageName );
        final boolean viewerDependent = cacheablePage != null && isViewerDependent( pageName );
        if ( viewerDependent && !isAnonymous( viewer ) ) {
            // A transclusion, a plugin or a hub's hasPart renders per viewer: whatever the caller may not view is
            // omitted, so a signed-in caller's body must never be replayed to anyone else via a 304 on the same
            // browser. Anonymous visitors all see the same thing, so their copy stays revalidatable (below).
            resp.setHeader( "Cache-Control", "private, no-store" );
        } else if ( cacheablePage != null ) {
            // The SSR output for a page is a pure function of (shell build, page
            // version, mtime, viewer-allowed, and — for a per-viewer page — the anonymous audience) — the underlying
            // render cache is already shared across users by content hash. private+no-cache forces revalidation
            // on every navigation; matching validators short-circuit to a 304
            // before any body read, render, or injection work.
            final long lm = cacheablePage.getLastModified() == null
                    ? 0L : cacheablePage.getLastModified().getTime();
            final String etag = etagFor( shellFingerprint( req.getServletContext() ),
                    Math.max( cacheablePage.getVersion(), 1 ), lm, viewAllowed, viewerDependent );
            resp.setHeader( "ETag", etag );
            resp.setHeader( "Cache-Control", "private, no-cache" );
            if ( etag.equals( req.getHeader( "If-None-Match" ) ) ) {
                resp.setStatus( HttpServletResponse.SC_NOT_MODIFIED );
                return;
            }
        } else {
            setNoCacheHeaders( resp );
        }
        final ServletContext ctx = req.getServletContext();
        try ( InputStream in = ctx != null ? ctx.getResourceAsStream( "/index.html" ) : null ) {
            if ( in == null ) {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND, "index.html not found" );
                return;
            }
            final String html = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            String rewritten = rewriteIndexHtml( html, contextPath );
            if ( pageName != null && !pageName.isEmpty() ) {
                if ( viewAllowed ) {
                    rewritten = injectSemantic( rewritten, req, resp, pageName, frontPageName, viewer );
                } else {
                    // Denied: serve the bare SPA shell with a 404, exactly as for a page
                    // that does not exist, so the restricted page's existence — and its
                    // body, title, summary and JSON data island — stay hidden. The SPA
                    // then renders its own not-found/permission view client-side.
                    LOG.info( "SpaRoutingFilter: denying unauthorized server-side render of '{}'", pageName );
                    resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
                }
            }
            final byte[] bytes = rewritten.getBytes( StandardCharsets.UTF_8 );
            resp.setContentType( "text/html;charset=UTF-8" );
            resp.setCharacterEncoding( "UTF-8" );
            resp.setContentLength( bytes.length );
            resp.getOutputStream().write( bytes );
        }
    }

    /** SHA-256-prefix fingerprint of the deployed index.html; stable per deployment. */
    private String shellFingerprint( final ServletContext ctx ) {
        String fp = shellFingerprint;
        if ( fp == null ) {
            try ( InputStream in = ctx != null ? ctx.getResourceAsStream( "/index.html" ) : null ) {
                if ( in == null ) {
                    return "0";
                }
                final byte[] digest = java.security.MessageDigest.getInstance( "SHA-256" )
                        .digest( in.readAllBytes() );
                final StringBuilder sb = new StringBuilder( 12 );
                for ( int i = 0; i < 6; i++ ) {
                    sb.append( String.format( "%02x", digest[ i ] ) );
                }
                fp = sb.toString();
                shellFingerprint = fp;
            } catch ( final java.io.IOException | java.security.NoSuchAlgorithmException
                      | RuntimeException e ) {
                LOG.warn( "SpaRoutingFilter: shell fingerprint failed: {}", e.getMessage() );
                return "0";
            }
        }
        return fp;
    }

    /**
     * Weak ETag for a served /wiki/{page} document: shell build + page version + mtime.
     *
     * <p>A denied caller gets a different response body (the bare SPA shell with a 404)
     * than a permitted one, so the validator MUST distinguish them: otherwise a browser
     * that cached the permitted body would be handed a 304 for it after the user logs
     * out, re-serving restricted content from its own cache.
     *
     * @param viewAllowed whether the caller holds {@code view} on the page
     */
    static String etagFor( final String shellFp, final int version, final long lastModifiedMillis,
                            final boolean viewAllowed ) {
        return etagFor( shellFp, version, lastModifiedMillis, viewAllowed, false );
    }

    /**
     * As {@link #etagFor(String, int, long, boolean)}, for a page whose body differs per viewer when
     * {@code anonymousAudience} is set: only anonymous callers get a validator for such a page, and it is marked so that
     * no copy cached for one audience can be confirmed for another.
     */
    static String etagFor( final String shellFp, final int version, final long lastModifiedMillis,
                            final boolean viewAllowed, final boolean anonymousAudience ) {
        return "W/\"" + shellFp + '-' + version + '-' + lastModifiedMillis
                + ( viewAllowed ? "-v" : "-d" ) + ( anonymousAudience ? "-a" : "" ) + '"';
    }

    /** A native embed, or any plugin or variable call other than the {@code ALLOW}/{@code SET} directives. */
    private static final java.util.regex.Pattern VIEWER_DEPENDENT_MARKUP =
            java.util.regex.Pattern.compile( "!\\[\\[|\\[\\{(?!\\s*(?:ALLOW|SET)\\b)", java.util.regex.Pattern.CASE_INSENSITIVE );

    /**
     * Whether the page's rendered output depends on who is viewing: its raw text transcludes other pages
     * ({@code ![[...]]} native embeds, {@code InsertPage}), calls a plugin or variable (page listings, {@code If},
     * {@code $username}, ...), or it is a hub, whose JSON-LD {@code hasPart} names only the members the caller may
     * view. Fails <em>closed</em>: an unreadable page is treated as viewer-dependent.
     */
    private boolean isViewerDependent( final String pageName ) {
        final Engine eng = resolveEngine();
        if ( eng == null ) {
            return true;
        }
        try {
            final String text = PageSubsystemBridge.fromLegacyEngine( eng ).pages().getPureText( pageName, -1 );
            return text == null || VIEWER_DEPENDENT_MARKUP.matcher( text ).find() || isHub( text );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: could not read '{}' to classify viewer-dependence; treating as dependent: {}",
                    pageName, ex.getMessage() );
            return true;
        }
    }

    /** Whether the text declares {@code type: hub} in its frontmatter (only frontmatter is parsed, and only if present). */
    private static boolean isHub( final String text ) {
        return text.startsWith( "---" )
                && PageType.fromFrontmatter( FrontmatterParser.parse( text ).metadata().get( "type" ) ) == PageType.HUB;
    }

    /** Null-safe page lookup mirroring injectSemantic's ladder; null = engine/pm/page unavailable. */
    private Page resolvePageForCaching( final String pageName ) {
        final Engine eng = resolveEngine();
        if ( eng == null || pageName == null || pageName.isEmpty() ) {
            return null;
        }
        try {
            final PageManager pm = PageSubsystemBridge.fromLegacyEngine( eng ).pages();
            if ( pm == null || !pm.wikiPageExists( pageName ) ) {
                return null;
            }
            return pm.getPage( pageName );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: page lookup for caching failed for '{}': {}",
                    pageName, ex.getMessage() );
            return null;
        }
    }

    /**
     * Whether the caller may {@code view} {@code pageName}. The server-rendered body,
     * head metadata and JSON data island are all page content, so this path must apply
     * the same view ACL that {@link WikiPageFormatFilter} applies to the raw-content
     * representation of the very same URL.
     *
     * <p>Fails <em>closed</em>: if the permission subsystem throws, the page is not
     * server-rendered. A denied answer costs SEO on that page; a wrong "allowed"
     * answer discloses restricted content.
     *
     * @param viewer   the caller's session (a transient guest when unauthenticated);
     *                 {@code null} when it could not be resolved, which is denied
     * @param pageName the wiki page being navigated to
     * @return {@code true} if the caller holds {@code view} on the page
     */
    private boolean mayView( final Session viewer, final String pageName ) {
        final Engine eng = resolveEngine();
        if ( eng == null ) {
            // Without an engine nothing is server-rendered anyway (injectSemantic bails
            // on the same condition), so there is no content to protect here.
            return true;
        }
        if ( viewer == null ) {
            return false;
        }
        try {
            return new PermissionFilter( eng ).canAccess( viewer, pageName, "view" );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: view-permission check failed for '{}' ({}) — refusing to server-render",
                    pageName, ex.toString() );
            return false;
        }
    }

    /**
     * The caller's session (a transient guest when unauthenticated), or {@code null} when it cannot be resolved —
     * every caller of this treats {@code null} as "may view nothing, share nothing".
     */
    private Session viewerSession( final HttpServletRequest req ) {
        final Engine eng = resolveEngine();
        if ( eng == null ) {
            return null;
        }
        try {
            return Wiki.session().find( eng, req );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: session lookup failed ({}) — treating the caller as unknown", ex.toString() );
            return null;
        }
    }

    /** Anonymous visitors are one audience: they all hold the same permissions, so they may share a cached copy. */
    private static boolean isAnonymous( final Session viewer ) {
        return viewer != null && viewer.isAnonymous();
    }

    /**
     * The date search engines see as "modified": the stored content-changed date, which bulk frontmatter edits do
     * not move, else the page's file modification time (also the fallback when no service is wired or it fails).
     */
    private static java.util.Date contentChangedDate( final Engine eng, final Page page ) {
        try {
            final com.wikantik.seo.ContentDateService service =
                    com.wikantik.seo.ContentDateWiringHelper.serviceOf( eng );
            if ( service != null ) {
                final java.time.Instant changed = service.contentDate( page );
                if ( changed != null ) {
                    return java.util.Date.from( changed );
                }
            }
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: content date lookup failed for '{}', using last-modified: {}",
                    page.getName(), ex.getMessage(), ex );
        }
        return page.getLastModified();
    }

    /**
     * Load the named page and inject its {@link SemanticHeadRenderer}-generated
     * semantic {@code <head>} fragment (meta tags, JSON-LD, feed autodiscovery)
     * and body fallback (no-JS heading + paragraphs) into the SPA index.html.
     *
     * <p>If the engine or page is not available, the index.html is returned
     * unchanged — the React app will still handle the route client-side and
     * show a 404 for missing pages.
     */
    private String injectSemantic( final String indexHtml, final HttpServletRequest req,
                                     final HttpServletResponse resp, final String pageName,
                                     final String frontPageName, final Session viewer ) {
        final Engine eng = resolveEngine();
        if ( eng == null ) {
            return indexHtml;
        }
        final PageManager pm;
        try {
            pm = PageSubsystemBridge.fromLegacyEngine( eng ).pages();
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: PageManager lookup failed for '{}': {}",
                    pageName, ex.getMessage() );
            return indexHtml;
        }
        if ( pm == null ) {
            return indexHtml;
        }
        if ( !pm.wikiPageExists( pageName ) ) {
            // Missing page: still serve the SPA shell (React renders its NotFound
            // view) but with a 404 status, so crawlers drop the URL instead of
            // treating a 200 "page not found" screen as a Soft 404.
            resp.setStatus( HttpServletResponse.SC_NOT_FOUND );
            return indexHtml;
        }
        final Page page;
        final String rawText;
        final java.util.Date modified;
        try {
            page = pm.getPage( pageName );
            if ( page == null ) {
                return indexHtml;
            }
            rawText = pm.getPureText( page );
            modified = contentChangedDate( eng, page );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: failed to load page '{}': {}", pageName, ex.getMessage() );
            return indexHtml;
        }
        // Parsed once and threaded through the head, body-fallback, and data-island
        // derivations below — those used to each re-parse the same frontmatter.
        final String visibleText = ReaderText.withoutComments( AclDirectiveText.strip( rawText == null ? "" : rawText ) );
        // The head, the no-JS body fallback and the JSON data island all carry page text, and access-rule
        // directives name people by login. None of them needs the directives (the SPA's editor re-fetches the
        // raw text through GET /api/pages, which keeps them for editors), so none gets them. Nor do they get
        // %%comments%%: this document is served to anonymous readers and crawlers and cached at the edge, so it is
        // always a reader's view. The render below still starts from rawText: the renderer consumes both itself.
        final ParsedPage parsed = FrontmatterParser.parse( visibleText );
        final String baseUrl = BaseUrlResolver.resolve( eng, req, null );
        final String appName = eng.getApplicationName();
        final String head = SemanticHeadRenderer.renderHead( pageName, parsed, baseUrl, appName, modified,
                                                              hubMembers( eng, viewer, pageName, parsed, req ), frontPageName );
        // Full flexmark render of the body, reused for BOTH the no-JS #root body
        // and the JSON data island (rendered once). Falls back to the lightweight
        // no-JS fragment if the full render is unavailable. Putting real rendered
        // HTML in #root means non-JS crawlers get proper content (tables, lists,
        // headings) instead of raw markdown text.
        final String contentHtml = renderContentHtml( eng, req, page, pageName, rawText );
        final String rootBody = ( contentHtml != null && !contentHtml.isBlank() )
                ? contentHtml
                : SemanticHeadRenderer.renderBodyFragment( pageName, parsed );

        // Drop the static shell <title>; renderHead emits a per-page <title>,
        // so stripping the shell one leaves exactly one (correct) title element.
        // Likewise drop the "JavaScript is required" notice: this page carries real content, and crawlers
        // that read <noscript> would otherwise index the notice as part of it.
        String out = stripNoscriptNotice( stripShellTitle( indexHtml ) );
        final int headClose = out.indexOf( "</head>" );
        if ( headClose >= 0 ) {
            out = out.substring( 0, headClose ) + head + out.substring( headClose );
        }
        // Replace the SPA loading fallback inside <div id="root"> with the
        // server-rendered body fragment. React will overwrite this when it
        // mounts on the root element.
        final int rootOpen = out.indexOf( "<div id=\"root\">" );
        if ( rootOpen >= 0 ) {
            final int rootInner = rootOpen + "<div id=\"root\">".length();
            final int rootClose = findMatchingDivClose( out, rootInner );
            if ( rootClose > rootInner ) {
                out = out.substring( 0, rootInner ) + rootBody + out.substring( rootClose );
            }
        }
        // Inject a JSON data island so the React reader renders immediately from
        // the SSR data instead of refetching /api/pages on mount. A crawler's JS
        // renderer may skip that fetch (or it may be slow), leaving an empty
        // "Loading…" DOM that Google classifies as Soft 404. Seeding from the
        // island keeps content on screen for the first paint and for crawlers.
        // See PageView.jsx + useApi.js (initialData).
        final String island = buildPageIsland( pageName, parsed, contentHtml, PublicIdentities.forEngine( eng ) );
        if ( island != null ) {
            final int bodyClose = out.lastIndexOf( "</body>" );
            if ( bodyClose >= 0 ) {
                out = out.substring( 0, bodyClose ) + island + out.substring( bodyClose );
            }
        }
        return out;
    }

    /** Gson with default HTML-escaping on, so serialized output contains no
     *  literal {@code </script>} and is safe to embed in an inline script. */
    private static final com.google.gson.Gson ISLAND_GSON = new com.google.gson.Gson();

    /**
     * Render the full page body to HTML via the flexmark pipeline (same output as
     * {@code GET /api/pages/{name}?render=true}). Returns {@code null} on failure,
     * letting callers fall back to the lightweight no-JS fragment.
     */
    private static String renderContentHtml( final Engine eng, final HttpServletRequest req,
                                             final Page page, final String pageName, final String rawText ) {
        try {
            final RenderingManager rm = RenderingSubsystemBridge.fromLegacyEngine( eng ).renderingManager();
            final Context ctx = Wiki.context().create( eng, req, page );
            return rm.textToHTML( ctx, rawText );
        } catch ( final Exception rex ) {
            LOG.warn( "SpaRoutingFilter: content render failed for '{}': {}", pageName, rex.getMessage() );
            return null;
        }
    }

    /**
     * Build the {@code window.__WIKANTIK_PAGE__} data island from the precomputed
     * {@code contentHtml} (name, content, contentHtml, metadata) so the client can
     * seed its initial state without a render-critical network fetch. Returns
     * {@code null} on failure (the SPA then falls back to its normal fetch).
     *
     * <p>The island is served before the SPA knows who is viewing, so its {@code metadata} is what a reader who cannot
     * edit would get from {@code GET /api/pages}: {@code author} and {@code verified_by} that are logins become display
     * names. An editor re-fetches the raw metadata through the API, which is what the editor round-trips.
     */
    private static String buildPageIsland( final String pageName, final ParsedPage parsed,
                                           final String contentHtml, final PublicIdentity identity ) {
        try {
            final java.util.Map< String, Object > island = new java.util.LinkedHashMap<>();
            island.put( "name", pageName );
            island.put( "content", parsed.body() );
            island.put( "contentHtml", contentHtml );
            island.put( "metadata", IdentityDisplay.frontmatter( identity, parsed.metadata() ) );
            island.put( "exists", true );
            return "<script>window.__WIKANTIK_PAGE__=" + ISLAND_GSON.toJson( island ) + ";</script>";
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: data-island build failed for '{}': {}", pageName, ex.getMessage() );
            return null;
        }
    }

    /**
     * Remove the static shell {@code <title>...</title>} element so that the
     * per-page {@code <title>} emitted by {@link SemanticHeadRenderer#renderHead}
     * is the only title in the served document. Without this the page would have
     * two {@code <title>} elements (the generic shell one and the page one), and
     * crawlers honour the first — defeating per-page titles.
     *
     * <p>No-op when the html has no title element. Package-private for unit testing.
     *
     * @param html the index.html (or {@code null})
     * @return the html with the first {@code <title>} element removed
     */
    static String stripShellTitle( final String html ) {
        if ( html == null ) {
            return null;
        }
        return html.replaceFirst( "(?is)<title>.*?</title>", "" );
    }

    /**
     * Removes the shell's {@code <noscript>} "JavaScript is required" element (the one holding
     * {@code id="noscript-notice"}). Other {@code <noscript>} elements, such as the font stylesheet
     * fallback in the head, are untouched.
     *
     * @param html the index.html (or {@code null})
     * @return the html without the notice element
     */
    static String stripNoscriptNotice( final String html ) {
        if ( html == null ) {
            return null;
        }
        return NOSCRIPT_NOTICE.matcher( html ).replaceFirst( "" );
    }

    /** One {@code <noscript>} element that contains the notice marker; never spans two noscript elements. */
    private static final java.util.regex.Pattern NOSCRIPT_NOTICE = java.util.regex.Pattern.compile(
            "(?is)<noscript>(?:(?!</noscript>).)*?id=\"noscript-notice\"(?:(?!</noscript>).)*</noscript>\\s*" );

    /**
     * Scan forward from {@code start} counting nested {@code <div>} opens and
     * closes to find the matching {@code </div>} for the outermost root div.
     * Returns the index of the opening {@code <} of the closing tag, or
     * {@code -1} if no matching close is found.
     */
    private static int findMatchingDivClose( final String html, final int start ) {
        int depth = 1;
        int i = start;
        while ( i < html.length() ) {
            final int nextOpen = html.indexOf( "<div", i );
            final int nextClose = html.indexOf( "</div>", i );
            if ( nextClose < 0 ) {
                return -1;
            }
            if ( nextOpen >= 0 && nextOpen < nextClose ) {
                depth++;
                i = nextOpen + 4;
            } else {
                depth--;
                if ( depth == 0 ) {
                    return nextClose;
                }
                i = nextClose + 6;
            }
        }
        return -1;
    }

    /**
     * Rewrite the vite-built {@code index.html} so it works when deployed at a
     * non-root context path. Injects {@code window.__WIKANTIK_BASE__} so client
     * JavaScript can prefix its own fetch() calls. Also rewrites static
     * {@code src=/assets/}, {@code href=/assets/} and {@code href=/favicon}
     * references which are otherwise absolute to the host root.
     *
     * <p>Package-private for direct unit testing without standing up a servlet
     * container.
     */
    static String rewriteIndexHtml( final String html, final String contextPath ) {
        final String ctx = contextPath == null ? "" : contextPath;
        String out = html;
        if ( !ctx.isEmpty() ) {
            out = out.replace( "src=\"/assets/", "src=\"" + ctx + "/assets/" );
            out = out.replace( "href=\"/assets/", "href=\"" + ctx + "/assets/" );
            out = out.replace( "href=\"/favicon", "href=\"" + ctx + "/favicon" );
        }
        final StringBuilder injected = new StringBuilder();
        if ( !ctx.isEmpty() ) {
            injected.append( "<script type=\"importmap\">{\"imports\":{\"/assets/\":\"" )
                    .append( ctx )
                    .append( "/assets/\"}}</script>" );
        }
        injected.append( "<script>window.__WIKANTIK_BASE__=\"" ).append( ctx ).append( "\";" );
        if ( !ctx.isEmpty() ) {
            injected.append( "window.addEventListener('vite:preloadError',function(e){e.preventDefault()});" );
            injected.append( "window.addEventListener('error',function(e){var t=e.target;" )
                    .append( "if(t&&t.tagName==='LINK'){var h=t.getAttribute('href');" )
                    .append( "if(h&&h.startsWith('/assets/'))e.stopImmediatePropagation()}" )
                    .append( "},true);" );
        }
        injected.append( "</script>" );
        final String injection = injected.toString();
        final int scriptIdx = out.indexOf( "<script" );
        final int headCloseIdx = out.indexOf( "</head>" );
        if ( scriptIdx >= 0 && ( headCloseIdx < 0 || scriptIdx < headCloseIdx ) ) {
            out = out.substring( 0, scriptIdx ) + injection + out.substring( scriptIdx );
        } else if ( headCloseIdx >= 0 ) {
            out = out.substring( 0, headCloseIdx ) + injection + out.substring( headCloseIdx );
        }
        return out;
    }

    private static void setNoCacheHeaders( final HttpServletResponse resp ) {
        resp.setHeader( "Cache-Control", "no-store" );
        resp.setHeader( "Pragma", "no-cache" );
        resp.setHeader( "Expires", "0" );
        resp.setHeader( "Vary", "*" );
    }

    /**
     * Emits a 301 with the given {@code Location} only when the target is free
     * of CR/LF — guarding against HTTP response-splitting, since the redirect
     * target is built from request-derived path/query fragments. A tainted
     * target yields a 400 instead.
     */
    private static void sendMovedPermanently( final HttpServletResponse resp, final String target )
            throws IOException {
        if ( target.indexOf( '\r' ) >= 0 || target.indexOf( '\n' ) >= 0 ) {
            resp.sendError( HttpServletResponse.SC_BAD_REQUEST );
            return;
        }
        resp.setStatus( HttpServletResponse.SC_MOVED_PERMANENTLY );
        resp.setHeader( "Location", target );
    }

    @Override
    public void destroy() {
    }
}

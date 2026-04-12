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
import com.wikantik.api.core.Page;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.ui.SemanticHeadRenderer;
import com.wikantik.util.BaseUrlResolver;
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
 *       to {@code /wiki/Main} so the wiki always has a concrete page.</li>
 *   <li><b>SPA forwarding</b> — known SPA prefixes ({@code /wiki/}, {@code /edit/},
 *       {@code /diff/}) and exact SPA routes ({@code /search}, {@code /preferences},
 *       {@code /reset-password}, {@code /admin/*} tab routes) are served the
 *       React app's {@code /index.html}. Admin API endpoints like
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
 * exposes {@code window.__WIKANTIK_BASE__} so the API client can prepend the
 * same prefix to fetch calls.
 */
public class SpaRoutingFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( SpaRoutingFilter.class );

    private static final String[] SPA_PREFIXES = { "/wiki/", "/edit/", "/diff/", "/admin/", "/blog/" };
    private static final String[] SPA_EXACT = { "/search", "/graph", "/preferences", "/reset-password", "/blog" };

    private volatile Engine engine;
    private ServletContext servletContext;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        this.servletContext = filterConfig != null ? filterConfig.getServletContext() : null;
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

        // Redirect / and /wiki/ to /wiki/Main (server-side, independent of SPA)
        if ( "".equals( path ) || "/".equals( path ) || "/wiki/".equals( path ) || "/wiki".equals( path ) ) {
            setNoCacheHeaders( resp );
            resp.sendRedirect( contextPath + "/wiki/Main" );
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
            serveIndexHtml( req, resp, contextPath, pageName );
            return;
        }

        // Other SPA prefixes (/edit/, /diff/, /admin/, /blog/) and exact
        // routes (/search etc.) share URL space with JSON APIs, so we gate
        // forwarding on Accept: text/html to let fetch() calls reach their
        // servlets.
        final String accept = req.getHeader( "Accept" );
        final boolean isBrowserNavigation = accept != null && accept.contains( "text/html" );

        if ( isBrowserNavigation ) {
            for ( final String prefix : SPA_PREFIXES ) {
                if ( path.startsWith( prefix ) ) {
                    serveIndexHtml( req, resp, contextPath, null );
                    return;
                }
            }
            for ( final String exact : SPA_EXACT ) {
                if ( path.equals( exact ) || path.startsWith( exact + "?" ) ) {
                    serveIndexHtml( req, resp, contextPath, null );
                    return;
                }
            }
        }

        // Everything else passes through
        chain.doFilter( request, response );
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
                                  final String contextPath, final String pageName ) throws IOException {
        setNoCacheHeaders( resp );
        final ServletContext ctx = req.getServletContext();
        try ( final InputStream in = ctx != null ? ctx.getResourceAsStream( "/index.html" ) : null ) {
            if ( in == null ) {
                resp.sendError( HttpServletResponse.SC_NOT_FOUND, "index.html not found" );
                return;
            }
            final String html = new String( in.readAllBytes(), StandardCharsets.UTF_8 );
            String rewritten = rewriteIndexHtml( html, contextPath );
            if ( pageName != null && !pageName.isEmpty() ) {
                rewritten = injectSemantic( rewritten, req, pageName );
            }
            final byte[] bytes = rewritten.getBytes( StandardCharsets.UTF_8 );
            resp.setContentType( "text/html;charset=UTF-8" );
            resp.setCharacterEncoding( "UTF-8" );
            resp.setContentLength( bytes.length );
            resp.getOutputStream().write( bytes );
        }
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
                                     final String pageName ) {
        final Engine eng = resolveEngine();
        if ( eng == null ) {
            return indexHtml;
        }
        final PageManager pm;
        try {
            pm = eng.getManager( PageManager.class );
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: PageManager lookup failed for '{}': {}",
                    pageName, ex.getMessage() );
            return indexHtml;
        }
        if ( pm == null || !pm.wikiPageExists( pageName ) ) {
            return indexHtml;
        }
        final String rawText;
        final java.util.Date modified;
        try {
            final Page page = pm.getPage( pageName );
            if ( page == null ) {
                return indexHtml;
            }
            rawText = pm.getPureText( page );
            modified = page.getLastModified();
        } catch ( final RuntimeException ex ) {
            LOG.warn( "SpaRoutingFilter: failed to load page '{}': {}", pageName, ex.getMessage() );
            return indexHtml;
        }
        final String baseUrl = BaseUrlResolver.resolve( eng, req, null );
        final String appName = eng.getApplicationName();
        final String head = SemanticHeadRenderer.renderHead( pageName, rawText, baseUrl, appName, modified );
        final String bodyFragment = SemanticHeadRenderer.renderBodyFragment( pageName, rawText );

        String out = indexHtml;
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
                out = out.substring( 0, rootInner ) + bodyFragment + out.substring( rootClose );
            }
        }
        return out;
    }

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
        final String baseScript = "<script>window.__WIKANTIK_BASE__=\"" + ctx + "\";</script>";
        // Inject the base script right before the first <script> tag so it
        // runs before any bundled code reads the global. If there's no script
        // tag, drop it just before </head>.
        final int scriptIdx = out.indexOf( "<script" );
        final int headCloseIdx = out.indexOf( "</head>" );
        if ( scriptIdx >= 0 && ( headCloseIdx < 0 || scriptIdx < headCloseIdx ) ) {
            out = out.substring( 0, scriptIdx ) + baseScript + out.substring( scriptIdx );
        } else if ( headCloseIdx >= 0 ) {
            out = out.substring( 0, headCloseIdx ) + baseScript + out.substring( headCloseIdx );
        }
        return out;
    }

    private static void setNoCacheHeaders( final HttpServletResponse resp ) {
        resp.setHeader( "Cache-Control", "no-store" );
        resp.setHeader( "Pragma", "no-cache" );
        resp.setHeader( "Expires", "0" );
        resp.setHeader( "Vary", "*" );
    }

    @Override
    public void destroy() {
    }
}

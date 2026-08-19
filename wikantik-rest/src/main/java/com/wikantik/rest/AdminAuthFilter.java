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
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.permissions.AdminPermission;
import com.wikantik.auth.permissions.AllPermission;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * Servlet filter that enforces admin authorization on all {@code /admin/*} endpoints.
 *
 * <p>Checks whether the current user's {@link Session} has {@link AllPermission}
 * (granted to the "Admin" group and "Admin" container role via the security policy).
 * Non-admin requests receive a 403 JSON response. Admin requests pass through.
 */
public class AdminAuthFilter implements Filter {

    private static final Logger LOG = LogManager.getLogger( AdminAuthFilter.class );

    private static final String FORBIDDEN_HTML = """
            <!doctype html>
            <html lang="en">
              <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Wikantik: Access Denied</title>
                <link rel="preconnect" href="https://fonts.googleapis.com" />
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
                <link href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@400;700&family=DM+Sans:wght@400;500&display=swap" rel="stylesheet" />
                <style>
                body { margin: 0; min-height: 100vh; display: flex; align-items: center;
                       justify-content: center; background: #FEFCF8; color: #2D2D2D;
                       font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, sans-serif; }
                .card { max-width: 480px; padding: 2.5rem; }
                h1 { font-family: 'Playfair Display', Georgia, serif; font-size: 2rem;
                     font-weight: 700; margin-bottom: 1.5rem; letter-spacing: -0.02em; }
                p { line-height: 1.7; color: #6B6560; margin-bottom: 1rem; }
                .actions { margin-top: 2rem; display: flex; gap: 0.75rem; }
                .btn { display: inline-block; padding: 0.6em 1.5em; border-radius: 8px;
                       font-family: inherit; font-size: 0.9rem; font-weight: 500;
                       text-decoration: none; transition: background 150ms, border-color 150ms; }
                .btn-primary { background: #C45D3E; color: #fff; border: 1px solid #C45D3E; }
                .btn-primary:hover { background: #A8442A; border-color: #A8442A; }
                .btn-secondary { background: transparent; color: #2D2D2D; border: 1px solid #E8E4DC; }
                .btn-secondary:hover { border-color: #D0C8BC; }
                </style>
              </head>
              <body>
                <div class="card">
                  <h1>Access Denied</h1>
                  <p>Your session has expired or you are not logged in as an administrator.</p>
                  <p>Admin pages require an active session with administrator privileges.\
             This commonly happens after a server restart or when your session times out.</p>
                  <div class="actions">
                    <a class="btn btn-primary" href="/wiki/Main">Go to Home</a>
                  </div>
                </div>
              </body>
            </html>
            """;

    private Engine engine;

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
        engine = Wiki.engine().find( filterConfig.getServletContext(), null );
        LOG.info( "AdminAuthFilter initialized" );
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {

        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;

        // Allow CORS preflight through without auth
        if ( "OPTIONS".equalsIgnoreCase( req.getMethod() ) ) {
            chain.doFilter( request, response );
            return;
        }

        // SPA navigation (a browser GET asking for an HTML document) bypasses the
        // permission check so the SpaRoutingFilter further down the chain can serve
        // the React shell. The shell itself carries no admin data — once it boots
        // it polls /api/auth/user and routes to the login page if the user isn't
        // authenticated. Without this guard, a stale or absent JSESSIONID cookie
        // (e.g. right after a Tomcat redeploy) returns the static "Access Denied"
        // HTML for every /admin/* navigation, kicking the user out of the SPA.
        // JSON / XHR API calls still go through the auth check below because
        // fetch() does not include text/html in Accept by default.
        if ( isSpaNavigation( req ) ) {
            chain.doFilter( request, response );
            return;
        }

        final Session session = Wiki.session().find( engine, req );
        final AllPermission adminPerm = new AllPermission( engine.getApplicationName() );
        final AuthorizationManager authMgr = AuthSubsystemBridge.fromLegacyEngine( engine ).authorization();

        // Scoped area grant is checked FIRST, and with the SILENT twin. Ordering and choice of
        // method are both load-bearing:
        //
        //   checkPermission() is the audited enforcement call — it emits access.denied on failure.
        //   Asking it about AllPermission first and only then falling back would log a denial for
        //   every request that goes on to SUCCEED via a scoped grant, putting false "access denied"
        //   records in the audit log for allowed traffic. (Caught by AuditLogIT, which also saw the
        //   second denial overwrite the first and change its reported targetType.)
        //
        // So: ask the speculative question silently with isPermitted(); if that does not grant,
        // fall through to the audited AllPermission check, which then behaves exactly as it did
        // before this change — same decision, same single audit event.
        if ( !hasAreaGrant( authMgr, session, req ) && !authMgr.checkPermission( session, adminPerm ) ) {
            // WARN so operators can correlate "I got logged out" reports with the
            // exact request that produced the 403. Includes session ID + principal
            // + path so a bouncing/changing JSESSIONID is visible at a glance.
            LOG.warn( "Admin access denied: path={} sessionId={} principal={} authenticated={}",
                req.getRequestURI(),
                req.getSession( false ) != null ? req.getSession( false ).getId() : "(none)",
                session.getLoginPrincipal() != null ? session.getLoginPrincipal().getName() : "(null)",
                session.isAuthenticated() );
            resp.setStatus( HttpServletResponse.SC_FORBIDDEN );
            resp.setCharacterEncoding( "UTF-8" );

            final String accept = req.getHeader( "Accept" );
            if ( accept != null && accept.contains( "text/html" ) ) {
                resp.setContentType( "text/html" );
                resp.getWriter().write( FORBIDDEN_HTML );
            } else {
                resp.setContentType( "application/json" );
                resp.getWriter().write( "{\"error\":true,\"status\":403,\"message\":\"Forbidden\"}" );
            }
            return;
        }

        chain.doFilter( request, response );
    }

    /**
     * True when the request looks like a browser asking for an HTML document
     * — a {@code GET} whose {@code Accept} header advertises {@code text/html}.
     * fetch()/XHR API calls don't include that media type by default, so this
     * cleanly distinguishes "user navigating in the SPA" from "SPA calling a
     * JSON endpoint."
     */
    static boolean isSpaNavigation( final HttpServletRequest req ) {
        if ( !"GET".equalsIgnoreCase( req.getMethod() ) ) {
            return false;
        }
        final String accept = req.getHeader( "Accept" );
        if ( accept == null || !accept.contains( "text/html" ) ) {
            return false;
        }
        // The bypass is ONLY safe for a path SpaRoutingFilter will answer with the
        // React shell. SpaRoutingFilter treats any path containing a dot (and not
        // ending in .html) as a static asset and forwards it to the servlet
        // (SpaRoutingFilter.doFilter: `path.contains(".") && !path.endsWith(".html")`).
        // For such a path, passing through here means the admin servlet runs WITH
        // AUTH ALREADY SKIPPED — an unauthenticated admin-data leak (e.g.
        // /admin/apikeys/a.b). Mirror that carve-out exactly so a dotted path can
        // never both skip auth and reach a servlet: the two filters must agree on
        // what "an SPA navigation" is, or the gap between them is the vulnerability.
        final String path = servletRelativePath( req );
        if ( path.contains( "." ) && !path.endsWith( ".html" ) ) {
            return false;
        }
        return true;
    }

    /** Request path with the context path stripped, matching how SpaRoutingFilter
     *  computes the path it routes on. Uses getRequestURI() (the raw, undecoded
     *  URI) deliberately: a %2e-encoded dot must be judged on the same bytes the
     *  servlet container will use to dispatch, not a decoded form. */
    private static String servletRelativePath( final HttpServletRequest req ) {
        final String contextPath = req.getContextPath() != null ? req.getContextPath() : "";
        final String rawUri = req.getRequestURI();
        if ( rawUri == null ) {
            return "";
        }
        return rawUri.startsWith( contextPath ) ? rawUri.substring( contextPath.length() ) : rawUri;
    }

    /**
     * Second chance for a principal holding a scoped grant on this one admin area.
     *
     * <p>Widens access but never narrows it: this can only turn a request that would have been
     * denied into one that is allowed. An administrator holding {@link AllPermission} is granted
     * here too (AllPermission implies every {@code AdminPermission}), so their behaviour is
     * unchanged. {@code /admin/*} spans 26 functional areas, and an integration that needs one of
     * them should not also hold {@code connector-credentials}, {@code apikeys} and
     * {@code policy}.</p>
     *
     * <p>Uses {@code isPermitted} — the <em>silent</em> twin of {@code checkPermission} — because
     * this is a speculative question, not an enforcement decision. The enforcement denial is still
     * emitted, once, by the {@link AllPermission} check that follows when this returns false.</p>
     *
     * <p><strong>Fails closed by construction.</strong> The area is *derived* from the first path
     * segment rather than read from a lookup table, so there is no map to fall out of sync with the
     * servlet registrations — a newly added endpoint automatically gets its own area, which nobody
     * holds a grant for, so it keeps requiring {@link AllPermission} exactly as it does today. A
     * path with no area segment ({@code /admin}, {@code /admin/}) is refused here outright.</p>
     */
    private boolean hasAreaGrant( final AuthorizationManager authMgr, final Session session,
                                  final HttpServletRequest req ) {
        final String area = adminAreaOf( req.getRequestURI(), req.getContextPath() );
        if ( area == null ) {
            return false;
        }
        final boolean granted = authMgr.isPermitted( session,
                new AdminPermission( area, AdminPermission.ACCESS_ACTION ) );
        if ( granted ) {
            LOG.info( "Admin area access granted by scoped grant: area={} path={} principal={}",
                    area, req.getRequestURI(),
                    session.getLoginPrincipal() != null ? session.getLoginPrincipal().getName() : "(null)" );
        }
        return granted;
    }

    /**
     * The admin area a request path falls in — the first segment after {@code /admin/}, lowercased.
     * {@code null} when the path carries no area segment.
     *
     * <p>Package-private for test: the mapping is security-relevant and a path-traversal-ish input
     * ({@code /admin/../foo}, a bare {@code /admin}) must not resolve to something a grant could
     * match.</p>
     */
    static String adminAreaOf( final String requestUri, final String contextPath ) {
        if ( requestUri == null ) {
            return null;
        }
        String path = requestUri;
        if ( contextPath != null && !contextPath.isEmpty() && path.startsWith( contextPath ) ) {
            path = path.substring( contextPath.length() );
        }
        final String prefix = "/admin/";
        if ( !path.startsWith( prefix ) ) {
            return null;
        }
        final String rest = path.substring( prefix.length() );
        final int slash = rest.indexOf( '/' );
        final String segment = ( slash < 0 ? rest : rest.substring( 0, slash ) ).strip();
        if ( segment.isEmpty() || segment.contains( ".." ) || segment.contains( ":" ) ) {
            return null;
        }
        return segment.toLowerCase( java.util.Locale.ROOT );
    }

    @Override
    public void destroy() {
    }
}

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
package com.wikantik.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import org.pac4j.core.config.Config;
import org.pac4j.core.engine.CallbackLogic;
import org.pac4j.core.engine.DefaultCallbackLogic;
import org.pac4j.jee.context.JEEFrameworkParameters;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Servlet that handles the SSO callback from Identity Providers (OIDC or SAML).
 * <p>
 * This servlet is mapped to {@code /sso/callback} and processes the authentication
 * response from the IdP. After pac4j validates the response and stores the user profile
 * in the HTTP session, this servlet redirects the user to the wiki's front page (or the
 * page they originally requested).
 * </p>
 *
 * @since 3.1
 */
public class SSOCallbackServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LogManager.getLogger( SSOCallbackServlet.class );

    private transient Engine engine;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config.getServletContext(), null );
    }

    @Override
    protected void service( final HttpServletRequest request, final HttpServletResponse response )
            throws ServletException, IOException {
        final SSOConfig ssoConfig = SSOConfigHolder.getConfig( engine );
        if( ssoConfig == null || !ssoConfig.isEnabled() ) {
            response.sendError( HttpServletResponse.SC_NOT_FOUND, "SSO is not enabled." );
            return;
        }

        final Config pac4jConfig = ssoConfig.getPac4jConfig();
        if( pac4jConfig == null ) {
            response.sendError( HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "SSO configuration error." );
            return;
        }

        LOG.debug( "Processing SSO callback for request: {}", request.getRequestURI() );

        try {
            final CallbackLogic callbackLogic = new DefaultCallbackLogic();
            final var frameworkParameters = new JEEFrameworkParameters( request, response );
            // Land on a no-store SPA route (the front page) rather than the context
            // root "/": the root shell is browser-cacheable and could serve a stale
            // bundle here, tripping the SPA's version-mismatch banner on every SSO login.
            final String defaultUrl = request.getContextPath() + "/wiki/Main";
            // renewSession=true causes pac4j to invalidate the pre-authentication session
            // and issue a fresh JSESSIONID while migrating the stored profile, preventing
            // session-fixation attacks.
            callbackLogic.perform( pac4jConfig, defaultUrl, true, null, frameworkParameters );

            // Captured BEFORE login() because login() rotates the session ID again (see the
            // diagnostic below) and that rotation is only observable in relation to whether
            // the redirect above had already committed the response.
            final boolean committedBeforeLogin = response.isCommitted();

            // pac4j has stored the profile in the HTTP session; translate it into
            // WikiSession principals now. WikiServletFilter is only mapped to
            // /attach/*, so without this explicit call the SSOLoginModule never
            // runs and the React UI keeps seeing an anonymous session.
            AuthSubsystemBridge.fromLegacyEngine( engine ).authentication().login( request );

            logIfStillAnonymous( request, committedBeforeLogin );
        } catch( final Exception e ) {
            // Most callback failures are bad/stale input (missing state, expired
            // session, user hit /sso/callback directly, replayed code) rather
            // than a server fault — log at WARN so genuine misconfigurations
            // still stand out when an operator turns ERROR-level alerts on.
            LOG.warn( "SSO callback processing failed", e );
            response.sendRedirect( request.getContextPath() + "/login?error=sso_callback_failed" );
        }
    }

    /**
     * Records the one failure mode this servlet cannot otherwise be diagnosed from: the callback
     * completes without throwing, yet the WikiSession is still anonymous, so the user lands on the
     * front page logged out and the log says nothing at all.
     *
     * <p>Written for issue #49 — a single unreproduced {@code SSOLoginIT} failure whose symptom was
     * exactly this, and which could not be root-caused because the evidence was gone. The state
     * captured here is what a diagnosis would need:</p>
     *
     * <ul>
     *   <li><b>Whether the response was already committed.</b> The leading hypothesis is a cookie
     *       that never reaches the browser. This path rotates the HTTP session ID <em>twice</em>:
     *       {@code callbackLogic.perform(..., renewSession=true, ...)} renews it and sends the
     *       redirect, then {@code login()} rotates it again for its own fixation defense. The
     *       second rotation must emit a fresh {@code Set-Cookie}, and a committed response silently
     *       discards it — leaving the browser holding the pre-rotation ID, which resolves to no
     *       session and therefore a fresh guest. That fits the symptom exactly. What it does not
     *       yet explain is the intermittency: {@code sendRedirect} suspends the response without
     *       necessarily committing it, so whether this bites may depend on buffer state. Hence a
     *       measurement rather than a fix.</li>
     *   <li><b>The session ID.</b> Cross-references against the access log to show whether the
     *       browser's next request presented this ID or a different one.</li>
     * </ul>
     *
     * <p>If the committed flag is ever {@code true} here, the second rotation is the cause and the
     * fix is to drop it — pac4j has already renewed the session, so it adds no security anyway.</p>
     */
    private void logIfStillAnonymous( final HttpServletRequest request, final boolean committedBeforeLogin ) {
        try {
            final var session = Wiki.session().find( engine, request );
            if ( session == null || session.isAuthenticated() ) {
                return;
            }
            LOG.warn( "SSO callback completed but the WikiSession is still anonymous "
                    + "(sessionId={}, responseCommittedBeforeLogin={}). See issue #49: if the "
                    + "committed flag is true, the post-login session-ID rotation could not emit "
                    + "its Set-Cookie and the browser is holding a stale JSESSIONID.",
                request.getSession( false ) == null ? "(none)" : request.getSession( false ).getId(),
                committedBeforeLogin );
        } catch ( final RuntimeException e ) {
            // A diagnostic must never be able to break the login it is observing.
            LOG.warn( "SSO callback post-login diagnostic failed: {}", e.getMessage(), e );
        }
    }
}

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
import org.pac4j.core.config.Config;
import org.pac4j.core.engine.CallbackLogic;
import org.pac4j.core.engine.DefaultCallbackLogic;
import org.pac4j.jee.context.JEEContext;
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
            final String defaultUrl = request.getContextPath() + "/";
            callbackLogic.perform( pac4jConfig, defaultUrl, false, null, frameworkParameters );
        } catch( final Exception e ) {
            LOG.error( "SSO callback processing failed", e );
            response.sendRedirect( request.getContextPath() + "/Login.jsp?error=sso_callback_failed" );
        }
    }
}

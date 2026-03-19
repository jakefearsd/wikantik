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
package org.apache.wiki.auth.sso;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.wiki.api.core.Engine;
import org.apache.wiki.api.spi.Wiki;
import org.pac4j.core.client.Client;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.WithLocationAction;
import org.pac4j.jee.context.JEEContext;
import org.pac4j.jee.context.session.JEESessionStore;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;

/**
 * Servlet that initiates the SSO authentication flow by redirecting the user
 * to the configured Identity Provider.
 * <p>
 * This servlet is mapped to {@code /sso/login} and accepts an optional
 * {@code client_name} parameter to select the SSO client (e.g., "OidcClient"
 * or "SAML2Client"). If not specified, the first configured client is used.
 * </p>
 *
 * @since 3.1
 */
public class SSORedirectServlet extends HttpServlet {

    private static final Logger LOG = LogManager.getLogger( SSORedirectServlet.class );

    private transient Engine engine;

    @Override
    public void init( final ServletConfig config ) throws ServletException {
        super.init( config );
        engine = Wiki.engine().find( config.getServletContext(), null );
    }

    @Override
    protected void doGet( final HttpServletRequest request, final HttpServletResponse response )
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

        final String clientName = request.getParameter( "client_name" );
        final Clients clients = pac4jConfig.getClients();

        try {
            final Optional< Client > clientOpt;
            if( clientName != null && !clientName.isBlank() ) {
                clientOpt = clients.findClient( clientName );
            } else {
                // Use the first configured client
                clientOpt = clients.findAllClients().stream().findFirst();
            }

            if( clientOpt.isEmpty() ) {
                LOG.error( "No SSO client found for name: {}", clientName );
                response.sendRedirect( request.getContextPath() + "/Login.jsp?error=no_sso_client" );
                return;
            }

            final Client client = clientOpt.get();
            final JEEContext webContext = new JEEContext( request, response );
            final JEESessionStore sessionStore = new JEESessionStore();
            final CallContext callContext = new CallContext( webContext, sessionStore );

            LOG.debug( "Initiating SSO redirect with client: {}", client.getName() );

            final HttpAction action = client.getRedirectionAction( callContext ).orElse( null );
            if( action instanceof WithLocationAction locationAction ) {
                response.sendRedirect( locationAction.getLocation() );
            } else if( action != null ) {
                response.setStatus( action.getCode() );
            } else {
                LOG.error( "SSO client {} did not produce a redirection action", client.getName() );
                response.sendRedirect( request.getContextPath() + "/Login.jsp?error=sso_redirect_failed" );
            }
        } catch( final Exception e ) {
            LOG.error( "Failed to initiate SSO redirect", e );
            response.sendRedirect( request.getContextPath() + "/Login.jsp?error=sso_redirect_failed" );
        }
    }
}

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
package com.wikantik.mcp;

import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.logging.log4j.Logger;

/**
 * The startup skeleton shared by the admin and knowledge MCP endpoint listeners: find the engine,
 * preflight, register the access filter, register the transport, prepare, build the server, publish it
 * as a {@link ServletContext} attribute. Each phase runs inside {@link #phase} so a failure is logged
 * under the endpoint's own message and stops startup exactly as before; the endpoints differ only in the
 * hooks below. The subclass owns the built server (it closes it on {@code contextDestroyed}).
 */
public abstract class McpEndpointInitializer implements ServletContextListener {

    /** The subclass's own logger, so log categories stay those operators already match. */
    protected abstract Logger log();

    /** Bootstrapper settings for this endpoint (the skeleton supplies the engine and builds it). */
    protected abstract McpEndpointBootstrapper.Builder bootstrapper( Engine engine );

    /** Log prefix, e.g. {@code "MCP"}; also the bootstrapper's {@code logTag}. */
    protected abstract String logTag();

    /** The {@link ServletContext} attribute the built server is published under. */
    protected abstract String attributeName();

    /** Failure message (with one {@code {}} slot for the cause) for the access-filter phase. */
    protected abstract String accessFilterFailureMessage();

    /** Failure message (with one {@code {}} slot for the cause) for the transport-registration phase. */
    protected abstract String transportFailureMessage();

    /** Failure message (with one {@code {}} slot for the cause) for the server-build phase. */
    protected abstract String buildFailureMessage();

    /**
     * Runs before anything is registered; return false to leave the endpoint unstarted (after logging why).
     * Default: always start.
     */
    protected boolean preflight( final Engine engine ) {
        return true;
    }

    /**
     * Failure message for the optional {@link #prepare} phase, or null when the endpoint has no such phase
     * (the admin endpoint builds everything in the server-build phase; the knowledge endpoint assembles its
     * tools first, under its own message).
     */
    protected String prepareFailureMessage() {
        return null;
    }

    /** Runs between transport registration and server build, when {@link #prepareFailureMessage} is non-null. */
    protected void prepare( final Engine engine ) {
        // nothing to prepare by default
    }

    /** Configures the server: info, capabilities, tools, and whatever else the endpoint serves. */
    protected abstract void registerTools( McpServer.SyncSpecification< ? > server, Engine engine );

    /** Receives the built server, before it is published as an attribute (the subclass keeps it to close it). */
    protected abstract void serverBuilt( McpSyncServer server, Engine engine );

    /** Writes the final "started" log lines, after the server is published. */
    protected abstract void started( Engine engine );

    @Override
    public final void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        // Eagerly create the WikiEngine if it doesn't exist yet.
        // WikiBootstrapServletContextListener has already initialized SPIs by the time
        // this listener runs, so find() is safe to call here.
        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            log().warn( "WikiEngine could not be created — {} server not started: {}", logTag(), e.getMessage() );
            return;
        }

        if ( !preflight( engine ) ) {
            return;
        }

        final McpEndpointBootstrapper bootstrapper = bootstrapper( engine ).engine( engine ).build();

        if ( !phase( accessFilterFailureMessage(), () -> bootstrapper.registerAccessFilter( servletContext ) ) ) {
            return;
        }

        final HttpServletStreamableServerTransportProvider[] transport = new HttpServletStreamableServerTransportProvider[ 1 ];
        if ( !phase( transportFailureMessage(), () -> transport[ 0 ] = bootstrapper.registerTransport( servletContext ) ) ) {
            return;
        }

        final String prepareFailure = prepareFailureMessage();
        if ( prepareFailure != null && !phase( prepareFailure, () -> prepare( engine ) ) ) {
            return;
        }

        phase( buildFailureMessage(), () -> {
            final McpServer.SyncSpecification< ? > spec = McpServer.sync( transport[ 0 ] );
            registerTools( spec, engine );
            final McpSyncServer server = spec.build();
            serverBuilt( server, engine );
            servletContext.setAttribute( attributeName(), server );
            started( engine );
        } );
    }

    /** Runs one startup step; on any exception logs {@code failureMessage} at ERROR and reports false. */
    private boolean phase( final String failureMessage, final Runnable step ) {
        try {
            step.run();
            return true;
        } catch ( final Exception e ) {
            log().error( failureMessage, e.getMessage(), e );
            return false;
        }
    }
}

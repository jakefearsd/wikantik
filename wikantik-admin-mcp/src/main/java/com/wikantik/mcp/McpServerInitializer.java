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

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.mcp.completions.WikiCompletions;
import com.wikantik.mcp.prompts.WikiPrompts;
import com.wikantik.mcp.resources.WikiEventSubscriptionBridge;
import com.wikantik.mcp.resources.WikiResources;
import com.wikantik.mcp.tools.AuthorConfigurable;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.ReferenceManager;


/**
 * Bootstraps the MCP server on application startup. Retrieves the shared WikiEngine
 * from the ServletContext, creates MCP tool instances, resources, and prompts, and
 * registers a Streamable HTTP transport servlet at {@code /wikantik-admin-mcp}.
 */
public class McpServerInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( McpServerInitializer.class );
    static final String ATTR_MCP_SERVER = "com.wikantik.mcp.McpSyncServer";

    private McpSyncServer mcpServer;

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        // Eagerly create the WikiEngine if it doesn't exist yet.
        // WikiBootstrapServletContextListener has already initialized SPIs by the time
        // this listener runs, so getInstance() is safe to call here.
        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — MCP server not started: {}", e.getMessage() );
            return;
        }

        final McpEndpointBootstrapper bootstrapper = McpEndpointBootstrapper.builder()
                .logTag( "MCP" )
                .endpointPath( "/wikantik-admin-mcp" )
                .filterName( "McpAccessFilter" )
                .servletName( "McpTransportServlet" )
                .loadOnStartup( 2 )
                .engine( engine )
                .build();

        // Phase 1: register the access-control filter (config + rate limiter + API-key service).
        try {
            bootstrapper.registerAccessFilter( servletContext );
        } catch ( final Exception e ) {
            LOG.error( "MCP server startup failed during config/access-filter setup — endpoint will be unavailable: {}",
                    e.getMessage(), e );
            return;
        }

        // Phase 2: create the streamable HTTP transport and register it as a servlet.
        final HttpServletStreamableServerTransportProvider transportProvider;
        try {
            transportProvider = bootstrapper.registerTransport( servletContext );
        } catch ( final Exception e ) {
            LOG.error( "MCP server startup failed during transport servlet registration — " +
                    "access filter was registered but endpoint has no servlet: {}", e.getMessage(), e );
            return;
        }

        // Phase 3: build the MCP server (tools, resources, prompts, completions, event bridge).
        try {
            final McpConfig config = new McpConfig();
            final McpToolRegistry toolRegistry = new McpToolRegistry( engine );

            final PageManager pageManager = engine.getManager( PageManager.class );
            final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
            final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );
            final SystemPageRegistry systemPageRegistry = engine.getManager( SystemPageRegistry.class );

            final WikiResources wikiResources = new WikiResources(
                    pageManager, referenceManager, attachmentManager, systemPageRegistry );

            final var serverImpl = new McpSchema.Implementation(
                    config.serverName(), config.serverTitle(), config.serverVersion() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .resources( true, true )
                            .prompts( true )
                            .build() );

            final String instructions = config.instructions();
            if ( instructions != null ) {
                builder.instructions( instructions );
            }

            for ( final McpTool tool : toolRegistry.readOnlyTools() ) {
                builder.toolCall( tool.definition(), ( exchange, request ) ->
                        tool.execute( request.arguments() ) );
            }

            for ( final McpTool tool : toolRegistry.authorConfigurableTools() ) {
                builder.toolCall( tool.definition(), ( exchange, request ) -> {
                    resolveAuthor( exchange, tool );
                    return tool.execute( request.arguments() );
                } );
            }

            mcpServer = builder
                    .resources( wikiResources.staticResources() )
                    .resourceTemplates( wikiResources.resourceTemplates() )
                    .prompts( WikiPrompts.all() )
                    .completions( WikiCompletions.all( referenceManager ) )
                    .build();

            final WikiEventSubscriptionBridge subscriptionBridge = new WikiEventSubscriptionBridge( mcpServer );
            subscriptionBridge.register( pageManager );

            servletContext.setAttribute( ATTR_MCP_SERVER, mcpServer );
            final int totalTools = toolRegistry.readOnlyTools().size() + toolRegistry.authorConfigurableTools().size();
            LOG.info( "MCP server started with {} tools, 6 resources, 8 prompts, and 3 completions at /wikantik-admin-mcp", totalTools );
        } catch ( final Exception e ) {
            LOG.error( "MCP server startup failed while wiring tools/resources/prompts — " +
                    "transport servlet is registered but will return protocol errors: {}", e.getMessage(), e );
        }
    }

    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        if ( mcpServer != null ) {
            try {
                mcpServer.close();
                LOG.info( "MCP server shut down" );
            } catch ( final Exception e ) {
                LOG.warn( "Error shutting down MCP server: {}", e.getMessage() );
            }
        }
    }

    private static void resolveAuthor( final io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                        final McpTool tool ) {
        try {
            final McpSchema.Implementation clientInfo = exchange.getClientInfo();
            if ( clientInfo != null && clientInfo.name() != null && !clientInfo.name().isBlank() ) {
                ( ( AuthorConfigurable ) tool ).setDefaultAuthor( clientInfo.name() );
            }
        } catch ( final Exception e ) {
            LOG.info( "Could not resolve MCP client name for tool {} — falling back to default author: {}",
                    tool.name(), e.getMessage() );
        }
    }

}

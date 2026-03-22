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
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.WikiEngine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.mcp.completions.WikiCompletions;
import com.wikantik.mcp.prompts.WikiPrompts;
import com.wikantik.mcp.resources.WikiEventSubscriptionBridge;
import com.wikantik.mcp.resources.WikiResources;
import com.wikantik.mcp.tools.AuthorConfigurable;
import com.wikantik.mcp.tools.LockPageTool;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.pages.PageManager;
import com.wikantik.references.ReferenceManager;

import java.util.EnumSet;


/**
 * Bootstraps the MCP server on application startup. Retrieves the shared WikiEngine
 * from the ServletContext, creates MCP tool instances, resources, and prompts, and
 * registers a Streamable HTTP transport servlet at {@code /mcp}.
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
        final WikiEngine engine;
        try {
            engine = ( WikiEngine ) Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — MCP server not started: {}", e.getMessage() );
            return;
        }

        try {
            // Load MCP configuration
            final McpConfig config = new McpConfig();
            LOG.info( "MCP config: name={}, title={}, version={}, instructions={}",
                    config.serverName(), config.serverTitle(), config.serverVersion(),
                    config.instructions() != null ? config.instructions().length() + " chars" : "none" );

            // Register access control filter with rate limiter
            final McpRateLimiter rateLimiter = new McpRateLimiter(
                    config.rateLimitGlobal(), config.rateLimitPerClient() );
            LOG.info( "MCP rate limiting: global={}/s, perClient={}/s",
                    config.rateLimitGlobal(), config.rateLimitPerClient() );
            final McpAccessFilter accessFilter = new McpAccessFilter( config, rateLimiter );
            final FilterRegistration.Dynamic filterReg =
                    servletContext.addFilter( "McpAccessFilter", accessFilter );
            filterReg.addMappingForUrlPatterns( EnumSet.of( DispatcherType.REQUEST ), false, "/mcp" );
            filterReg.setAsyncSupported( true );

            // Create transport provider (which is itself a Servlet)
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint( "/mcp" )
                            .build();

            // Register the transport servlet programmatically
            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "McpTransportServlet", transportProvider );
            registration.addMapping( "/mcp" );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 2 );

            // Build MCP server with all tools, resources, and prompts
            final McpToolRegistry toolRegistry = new McpToolRegistry( engine );

            final PageManager pageManager = engine.getManager( PageManager.class );
            final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
            final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );
            final SystemPageRegistry systemPageRegistry = engine.getManager( SystemPageRegistry.class );

            // Resources
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

            // Register read-only tools (no author resolution needed)
            for ( final McpTool tool : toolRegistry.readOnlyTools() ) {
                builder.toolCall( tool.definition(), ( exchange, request ) ->
                        tool.execute( request.arguments() ) );
            }

            // Register tools that need author resolution from the MCP exchange
            for ( final McpTool tool : toolRegistry.authorConfigurableTools() ) {
                builder.toolCall( tool.definition(), ( exchange, request ) -> {
                    resolveAuthor( exchange, tool );
                    return tool.execute( request.arguments() );
                } );
            }

            // Lock tool needs user resolution (different from author)
            final LockPageTool lockPage = toolRegistry.lockPageTool();
            builder.toolCall( lockPage.definition(), ( exchange, request ) -> {
                resolveUser( exchange, lockPage );
                return lockPage.execute( request.arguments() );
            } );

            mcpServer = builder
                    // Register resources
                    .resources( wikiResources.staticResources() )
                    .resourceTemplates( wikiResources.resourceTemplates() )
                    // Register prompts
                    .prompts( WikiPrompts.all() )
                    // Register completions
                    .completions( WikiCompletions.all( referenceManager ) )
                    .build();

            // Wire WikiEvent → MCP resource subscriptions
            final WikiEventSubscriptionBridge subscriptionBridge = new WikiEventSubscriptionBridge( mcpServer );
            subscriptionBridge.register( pageManager );

            servletContext.setAttribute( ATTR_MCP_SERVER, mcpServer );
            LOG.info( "MCP server started successfully with 37 tools, 6 resources, 8 prompts, and 3 completions at /mcp" );

        } catch ( final Exception e ) {
            LOG.error( "Failed to start MCP server: {}", e.getMessage(), e );
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
            // Ignore — fall back to default
        }
    }

    private static void resolveUser( final io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                      final LockPageTool tool ) {
        try {
            final McpSchema.Implementation clientInfo = exchange.getClientInfo();
            if ( clientInfo != null && clientInfo.name() != null && !clientInfo.name().isBlank() ) {
                tool.setDefaultUser( clientInfo.name() );
            }
        } catch ( final Exception e ) {
            // Ignore — fall back to default
        }
    }
}

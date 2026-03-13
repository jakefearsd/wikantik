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
package org.apache.wiki.mcp;

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
import org.apache.wiki.WikiEngine;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.attachment.AttachmentManager;
import org.apache.wiki.mcp.tools.*;
import org.apache.wiki.pages.PageManager;
import org.apache.wiki.references.ReferenceManager;

import java.util.EnumSet;


/**
 * Bootstraps the MCP server on application startup. Retrieves the shared WikiEngine
 * from the ServletContext, creates MCP tool instances, and registers a Streamable HTTP
 * transport servlet at {@code /mcp}.
 */
public class McpServerInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( McpServerInitializer.class );
    static final String ATTR_MCP_SERVER = "org.apache.wiki.mcp.McpSyncServer";

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

            // Register access control filter
            final McpAccessFilter accessFilter = new McpAccessFilter( config );
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

            // Build MCP server with all tools
            final PageManager pageManager = engine.getManager( PageManager.class );
            final ReferenceManager referenceManager = engine.getManager( ReferenceManager.class );
            final AttachmentManager attachmentManager = engine.getManager( AttachmentManager.class );

            final ReadPageTool readPage = new ReadPageTool( pageManager );
            final WritePageTool writePage = new WritePageTool( engine );
            final SearchPagesTool searchPages = new SearchPagesTool( engine );
            final ListPagesTool listPages = new ListPagesTool( pageManager );
            final GetBacklinksTool getBacklinks = new GetBacklinksTool( referenceManager );
            final RecentChangesTool recentChanges = new RecentChangesTool( pageManager );
            final GetAttachmentsTool getAttachments = new GetAttachmentsTool( pageManager, attachmentManager );
            final QueryMetadataTool queryMetadata = new QueryMetadataTool( pageManager );

            final var serverImpl = new McpSchema.Implementation(
                    config.serverName(), config.serverTitle(), config.serverVersion() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() );

            final String instructions = config.instructions();
            if ( instructions != null ) {
                builder.instructions( instructions );
            }

            mcpServer = builder
                    .toolCall( readPage.toolDefinition(), ( exchange, request ) ->
                            readPage.execute( request.arguments() ) )
                    .toolCall( writePage.toolDefinition(), ( exchange, request ) ->
                            writePage.execute( request.arguments() ) )
                    .toolCall( searchPages.toolDefinition(), ( exchange, request ) ->
                            searchPages.execute( request.arguments() ) )
                    .toolCall( listPages.toolDefinition(), ( exchange, request ) ->
                            listPages.execute( request.arguments() ) )
                    .toolCall( getBacklinks.toolDefinition(), ( exchange, request ) ->
                            getBacklinks.execute( request.arguments() ) )
                    .toolCall( recentChanges.toolDefinition(), ( exchange, request ) ->
                            recentChanges.execute( request.arguments() ) )
                    .toolCall( getAttachments.toolDefinition(), ( exchange, request ) ->
                            getAttachments.execute( request.arguments() ) )
                    .toolCall( queryMetadata.toolDefinition(), ( exchange, request ) ->
                            queryMetadata.execute( request.arguments() ) )
                    .build();

            servletContext.setAttribute( ATTR_MCP_SERVER, mcpServer );
            LOG.info( "MCP server started successfully with 8 tools at /mcp" );

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
}

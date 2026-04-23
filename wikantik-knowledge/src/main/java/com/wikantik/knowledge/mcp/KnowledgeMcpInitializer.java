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
package com.wikantik.knowledge.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.Release;
import com.wikantik.api.core.Engine;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.Wiki;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.mcp.tools.McpTool;

import java.util.ArrayList;
import java.util.List;


/**
 * Bootstraps the read-only Knowledge MCP server on application startup.
 * Retrieves the shared WikiEngine from the ServletContext, obtains the
 * {@link KnowledgeGraphService} manager, and registers a Streamable HTTP
 * transport servlet at {@code /knowledge-mcp} with the five consumption tools.
 *
 * <p>If the knowledge graph datasource is not configured, the initializer
 * logs an informational message and returns without starting the server.</p>
 */
public class KnowledgeMcpInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( KnowledgeMcpInitializer.class );
    static final String ATTR_KNOWLEDGE_MCP_SERVER = "com.wikantik.knowledge.mcp.McpSyncServer";

    private McpSyncServer mcpServer;

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        // Obtain the WikiEngine (already initialized by WikiBootstrapServletContextListener).
        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — Knowledge MCP server not started: {}", e.getMessage() );
            return;
        }

        // Obtain KnowledgeGraphService; if null the datasource was not configured.
        final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
        if ( kgService == null ) {
            LOG.info( "Knowledge graph not configured — Knowledge MCP server not started" );
            return;
        }

        try {
            // Create transport provider (which is itself a Servlet)
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint( "/knowledge-mcp" )
                            .build();

            // Register the transport servlet programmatically
            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "KnowledgeMcpTransportServlet", transportProvider );
            registration.addMapping( "/knowledge-mcp" );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 3 );

            // Create consumption tools (5 core + optional similarity tool)
            final List< McpTool > tools = new ArrayList<>( List.of(
                    new DiscoverSchemaTool( kgService ),
                    new QueryNodesTool( kgService ),
                    new GetNodeTool( kgService ),
                    new TraverseTool( kgService ),
                    new SearchKnowledgeTool( kgService )
            ) );

            // Register find_similar when the mention-centroid similarity service is wired.
            final NodeMentionSimilarity similarity = engine.getManager( NodeMentionSimilarity.class );
            if ( similarity != null ) {
                tools.add( new FindSimilarTool( similarity ) );
            }

            final var serverImpl = new McpSchema.Implementation(
                    "wikantik-knowledge", "Wikantik Knowledge Graph", Release.getVersionString() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .instructions( "This is a read-only knowledge graph endpoint. Use discover_schema first " +
                            "to understand the shape of the knowledge base, then use query_nodes, get_node, " +
                            "traverse, or search_knowledge to explore." )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() );

            // Register all tools
            for ( final McpTool tool : tools ) {
                builder.toolCall( tool.definition(), ( exchange, request ) ->
                        tool.execute( request.arguments() ) );
            }

            mcpServer = builder.build();

            servletContext.setAttribute( ATTR_KNOWLEDGE_MCP_SERVER, mcpServer );
            LOG.info( "Knowledge MCP server started successfully with {} tools at /knowledge-mcp", tools.size() );

        } catch ( final Exception e ) {
            LOG.error( "Failed to start Knowledge MCP server: {}", e.getMessage(), e );
        }
    }

    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        if ( mcpServer != null ) {
            try {
                mcpServer.close();
                LOG.info( "Knowledge MCP server shut down" );
            } catch ( final Exception e ) {
                LOG.warn( "Error shutting down Knowledge MCP server: {}", e.getMessage() );
            }
        }
    }
}

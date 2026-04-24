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
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.knowledge.KnowledgeGraphService;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.AbstractJDBCDatabase;
import com.wikantik.knowledge.MentionIndex;
import com.wikantik.knowledge.embedding.NodeMentionSimilarity;
import com.wikantik.mcp.tools.McpTool;

import java.util.ArrayList;
import java.util.List;


/**
 * Bootstraps the read-only Knowledge MCP server on application startup.
 * Retrieves the shared WikiEngine from the ServletContext, obtains
 * {@link KnowledgeGraphService} and {@link ContextRetrievalService} managers,
 * and registers a Streamable HTTP transport servlet at {@code /knowledge-mcp}
 * with tools from whichever services are configured.
 *
 * <p>The server starts if at least one service is configured. KG tools
 * ({@code discover_schema}, {@code query_nodes}, {@code get_node},
 * {@code traverse}, {@code search_knowledge}, {@code find_similar}) are
 * registered when {@link KnowledgeGraphService} is present; context retrieval
 * tools ({@code retrieve_context}, {@code get_page}, {@code list_pages},
 * {@code list_metadata_values}) are registered when
 * {@link ContextRetrievalService} is present.</p>
 *
 * <p>If neither service is configured, the initializer logs an informational
 * message and returns without starting the server.</p>
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

        final KnowledgeGraphService kgService = engine.getManager( KnowledgeGraphService.class );
        final ContextRetrievalService ctxService = engine.getManager( ContextRetrievalService.class );

        if ( kgService == null && ctxService == null ) {
            LOG.info( "Neither KnowledgeGraphService nor ContextRetrievalService configured — " +
                "Knowledge MCP server not started" );
            return;
        }

        try {
            final HttpServletStreamableServerTransportProvider transportProvider =
                    HttpServletStreamableServerTransportProvider.builder()
                            .mcpEndpoint( "/knowledge-mcp" )
                            .build();

            final ServletRegistration.Dynamic registration =
                    servletContext.addServlet( "KnowledgeMcpTransportServlet", transportProvider );
            registration.addMapping( "/knowledge-mcp" );
            registration.setAsyncSupported( true );
            registration.setLoadOnStartup( 3 );

            final List< McpTool > tools = new ArrayList<>();

            if ( kgService != null ) {
                // Resolve the shared JNDI DataSource so MentionIndex can filter and
                // report coverage statistics. Uses the same property + default as
                // WikiEngine.initKnowledgeGraph. If JNDI lookup fails (e.g. in a
                // lightweight test harness) we fall back to null and the tools still
                // function — they just skip the mention-coverage filter/stats.
                MentionIndex mentionIndex = null;
                try {
                    final String jndiName = engine.getWikiProperties().getProperty(
                        AbstractJDBCDatabase.PROP_DATASOURCE,
                        AbstractJDBCDatabase.DEFAULT_DATASOURCE );
                    final javax.naming.Context initCtx = new javax.naming.InitialContext();
                    final javax.naming.Context envCtx =
                        ( javax.naming.Context ) initCtx.lookup( "java:comp/env" );
                    final javax.sql.DataSource dataSource =
                        ( javax.sql.DataSource ) envCtx.lookup( jndiName );
                    mentionIndex = new MentionIndex( dataSource );
                    LOG.debug( "MentionIndex wired to JNDI DataSource '{}'", jndiName );
                } catch ( final javax.naming.NamingException e ) {
                    LOG.warn( "MentionIndex not available — JNDI DataSource lookup failed: {}; " +
                        "KG tools will run without mention-coverage filter/stats", e.getMessage() );
                }

                tools.add( new DiscoverSchemaTool( kgService, mentionIndex ) );
                tools.add( new QueryNodesTool( kgService, mentionIndex ) );
                tools.add( new GetNodeTool( kgService ) );
                tools.add( new TraverseTool( kgService ) );
                tools.add( new SearchKnowledgeTool( kgService, mentionIndex ) );
                final NodeMentionSimilarity similarity = engine.getManager( NodeMentionSimilarity.class );
                if ( similarity != null ) {
                    tools.add( new FindSimilarTool( similarity ) );
                }
            }

            if ( ctxService != null ) {
                tools.add( new RetrieveContextTool( ctxService ) );
                tools.add( new GetPageTool( ctxService ) );
                tools.add( new ListPagesTool( ctxService ) );
                tools.add( new ListMetadataValuesTool( ctxService ) );
            }

            final var serverImpl = new McpSchema.Implementation(
                    "wikantik-knowledge", "Wikantik Knowledge Graph", Release.getVersionString() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .instructions( "Agent-facing MCP endpoint. For wiki content use " +
                        "retrieve_context (primary RAG), get_page (pinned fetch), " +
                        "list_pages (browse), or list_metadata_values (discovery). " +
                        "For knowledge graph structure use discover_schema, query_nodes, " +
                        "get_node, traverse, search_knowledge, or find_similar." )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() );

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

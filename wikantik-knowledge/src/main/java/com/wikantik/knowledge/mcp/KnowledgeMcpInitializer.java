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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.Release;
import com.wikantik.api.agent.ForAgentProjectionService;
import com.wikantik.api.core.Engine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.knowledge.ContextRetrievalService;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.pagegraph.StructuralIndexService;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.pagegraph.subsystem.PageGraphSubsystemBridge;
import com.wikantik.auth.subsystem.AuthSubsystemBridge;
import com.wikantik.mcp.McpEndpointBootstrapper;
import com.wikantik.mcp.SdkSessionSweepers;
import com.wikantik.mcp.tools.McpTool;

import java.util.ArrayList;
import java.util.List;


/**
 * Bootstraps the read-only Knowledge MCP server on application startup.
 * Retrieves the shared WikiEngine from the ServletContext, obtains the
 * {@link ContextRetrievalService} and structural-index services,
 * and registers a Streamable HTTP transport servlet at {@code /knowledge-mcp}
 * with tools from whichever services are configured.
 *
 * <p>The server starts if at least one service is configured. Context retrieval
 * tools ({@code retrieve_context}, {@code get_page}, {@code list_pages},
 * {@code list_metadata_values}) are registered when
 * {@link ContextRetrievalService} is present.</p>
 *
 * <p>If no service is configured, the initializer logs an informational
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

        // Retrieval services come from the typed KnowledgeSubsystem.Services bundle.
        final com.wikantik.knowledge.subsystem.KnowledgeSubsystem.Services kg =
            com.wikantik.knowledge.subsystem.KnowledgeSubsystemBridge.fromLegacyEngine( engine );
        final ContextRetrievalService ctxService = kg.contextRetrievalService();
        final StructuralIndexService structuralIndex = PageGraphSubsystemBridge.fromLegacyEngine( engine ).structuralIndexService();
        final ForAgentProjectionService forAgent = kg.forAgentProjectionService();
        final PageManager pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();

        // Guest view-gate: the MCP transport carries no caller identity, so the read-only retrieval
        // tools enforce page view-ACLs as an anonymous guest — only publicly-viewable pages are
        // returned. Callers needing restricted content use the privileged /wikantik-admin-mcp surface.
        final PageViewGate viewGate = guestViewGate( engine );

        if ( ctxService == null && structuralIndex == null ) {
            LOG.info( "Neither ContextRetrievalService nor StructuralIndexService configured — " +
                "Knowledge MCP server not started" );
            return;
        }

        final McpEndpointBootstrapper bootstrapper = McpEndpointBootstrapper.builder()
                .logTag( "Knowledge MCP" )
                .endpointPath( "/knowledge-mcp" )
                .filterName( "KnowledgeMcpAccessFilter" )
                .requiredScope( com.wikantik.auth.apikeys.ApiKeyService.Scope.MCP_READ )
                .servletName( "KnowledgeMcpTransportServlet" )
                .loadOnStartup( 3 )
                .engine( engine )
                .build();

        // Phase 1: register the access-control filter so /knowledge-mcp requires
        // the same bearer-token / API-key auth as /wikantik-admin-mcp.
        try {
            bootstrapper.registerAccessFilter( servletContext );
        } catch ( final Exception e ) {
            LOG.error( "Knowledge MCP startup failed during access-filter setup — /knowledge-mcp will be unavailable: {}",
                    e.getMessage(), e );
            return;
        }

        // Phase 2: create and register the streamable HTTP transport as a servlet.
        final HttpServletStreamableServerTransportProvider transportProvider;
        try {
            transportProvider = bootstrapper.registerTransport( servletContext );
        } catch ( final Exception e ) {
            LOG.error( "Knowledge MCP startup failed during transport servlet registration — " +
                    "access filter was registered but endpoint has no servlet: {}", e.getMessage(), e );
            return;
        }

        // Phase 3: assemble the tool list from whichever services are configured.
        final List< McpTool > tools;
        try {
            // Ontology tools (read-only): present only when the ontology runtime is wired.
            final com.wikantik.ontology.runtime.OntologyRebuildCoordinator ontoCoord =
                PageGraphSubsystemBridge.fromLegacyEngine( engine ).ontologyRebuildCoordinator();
            final com.wikantik.ontology.OntologyModelManager ontoMgr =
                ontoCoord == null ? null : ontoCoord.modelManager();
            final com.wikantik.citation.CitationRepository citationRepo =
                PageGraphSubsystemBridge.fromLegacyEngine( engine ).citationRepository();
            tools = assembleTools( KnowledgeToolDeps.builder()
                .ctxService( ctxService )
                .pageManager( pageManager )
                .structuralIndex( structuralIndex )
                .forAgent( forAgent )
                .bundleService( kg.bundleAssemblyService() )
                .briefingService( kg.briefingAssemblyService() )
                .ontoMgr( ontoMgr )
                .citationRepo( citationRepo )
                .queryLog( () -> engine instanceof com.wikantik.WikiEngine we ? we.queryLogService() : null )
                .briefingLog( () -> engine instanceof com.wikantik.WikiEngine we ? we.briefingLogService() : null )
                .viewGate( viewGate )
                .draftActive( page -> com.wikantik.collab.adapter.CollabEngineAccess.draftActive( engine, page ) )
                .publicIdentity( () -> AuthSubsystemBridge.fromLegacyEngine( engine ).publicIdentity() )
                .referenceManager( () -> PageSubsystemBridge.fromLegacyEngine( engine ).referenceManager() )
                .build() );
        } catch ( final Exception e ) {
            LOG.error( "Knowledge MCP startup failed while assembling tools — transport servlet is registered " +
                    "but the server will have no tools to dispatch: {}", e.getMessage(), e );
            return;
        }

        // Phase 4: build the MCP sync server and wire it to the transport.
        try {
            final var serverImpl = new McpSchema.Implementation(
                    "wikantik-knowledge", SERVER_DISPLAY_NAME, Release.getVersionString() );

            final var builder = McpServer.sync( transportProvider )
                    .serverInfo( serverImpl )
                    .instructions( serverInstructions() )
                    .capabilities( ServerCapabilities.builder()
                            .tools( true )
                            .build() );

            for ( final McpTool tool : tools ) {
                builder.toolCall( tool.definition(), ( exchange, request ) ->
                        auditedExecute( tool, request.arguments(), exchange ) );
            }

            mcpServer = builder.build();
            servletContext.setAttribute( ATTR_KNOWLEDGE_MCP_SERVER, mcpServer );
            LOG.info( "Knowledge MCP server started successfully with {} tools at /knowledge-mcp", tools.size() );
        } catch ( final Exception e ) {
            LOG.error( "Knowledge MCP startup failed during server build — transport servlet is registered " +
                    "but will return protocol errors: {}", e.getMessage(), e );
        }
    }

    /** Server display name advertised in the MCP {@code initialize} response. */
    static final String SERVER_DISPLAY_NAME = "Wikantik Knowledge";

    /** The server-level instruction string advertised in the MCP {@code initialize} response. */
    static String serverInstructions() {
        return "Agent-facing MCP endpoint. For wiki structure (fastest, " +
            "no full-text search) use list_clusters, list_tags, list_pages_by_filter, " +
            "or get_page_by_id; get_page_neighbors lists a page's wikilinks in and out " +
            "plus its cluster siblings. For wiki content use assemble_bundle " +
            "(primary answer-grounding tool), retrieve_context (page/section discovery), " +
            "get_page (pinned fetch), read_pages (batched markdown fetch, max 20), " +
            "get_briefing (session-start briefing: your project's pages and the contracts it cites), " +
            "list_pages (browse), or list_metadata_values (discovery).";
    }

    /**
     * Assembles the read-only tool list from whichever collaborators are present.
     * Pure function of its inputs (no engine, no servlet context) so the tool
     * surface is unit-testable.
     */
    static List< McpTool > assembleTools( final KnowledgeToolDeps deps ) {
        final List< McpTool > tools = new ArrayList<>();
        addRetrievalTools( tools, deps );
        addStructuralSpineTools( tools, deps );
        addProjectionAndBundleTools( tools, deps );
        addOntologyAndCitationTools( tools, deps );
        return tools;
    }

    private static void addRetrievalTools( final List< McpTool > tools, final KnowledgeToolDeps deps ) {
        final ContextRetrievalService ctxService = deps.ctxService();
        if ( ctxService == null ) {
            return;
        }
        final PageViewGate viewGate = deps.viewGate();
        tools.add( new RetrieveContextTool( ctxService, viewGate ) );
        tools.add( new GetPageTool( ctxService, viewGate ) );
        tools.add( new ListPagesTool( ctxService, viewGate ) );
        tools.add( new ListMetadataValuesTool( ctxService, viewGate ) );
        tools.add( new ReadPagesTool( deps.pageManager(), ReadPagesMetrics.resolveAndBind(), viewGate ) );
    }

    private static void addStructuralSpineTools( final List< McpTool > tools, final KnowledgeToolDeps deps ) {
        final StructuralIndexService structuralIndex = deps.structuralIndex();
        if ( structuralIndex == null ) {
            return;
        }
        final PageViewGate viewGate = deps.viewGate();
        tools.add( new ListClustersTool( structuralIndex, viewGate ) );
        tools.add( new ListTagsTool( structuralIndex, viewGate ) );
        tools.add( new ListPagesByFilterTool( structuralIndex, viewGate ) );
        tools.add( new GetPageByIdTool( structuralIndex, viewGate ) );
        tools.add( new GetPageNeighborsTool( structuralIndex, deps.referenceManager(), viewGate ) );
    }

    private static void addProjectionAndBundleTools( final List< McpTool > tools, final KnowledgeToolDeps deps ) {
        final PageViewGate viewGate = deps.viewGate();
        if ( deps.forAgent() != null ) {
            tools.add( new GetPageForAgentTool( deps.forAgent(), viewGate, deps.publicIdentity(), deps.draftActive() ) );
        }
        if ( deps.bundleService() != null ) {
            // Resolve the query log lazily (the service is set during engine startup; resolving
            // at call time avoids any startup-ordering coupling). Agent-by-construction surface.
            tools.add( new AssembleBundleTool( deps.bundleService(), deps.queryLog(), viewGate ) );
        }
        if ( deps.briefingService() != null ) {
            tools.add( new GetBriefingTool( deps.briefingService(), deps.queryLog(), deps.briefingLog(), viewGate ) );
        }
    }

    private static void addOntologyAndCitationTools( final List< McpTool > tools, final KnowledgeToolDeps deps ) {
        if ( deps.ontoMgr() != null ) {
            tools.add( new GetOntologyTool( deps.ontoMgr() ) );
            tools.add( new SparqlQueryTool( deps.ontoMgr() ) );
        }
        if ( deps.citationRepo() != null ) {
            tools.add( new ListStaleCitationsTool( deps.citationRepo() ) );
        }
    }

    /**
     * Wraps {@link McpTool#execute} with an INFO-level audit line so admins can see
     * which knowledge-MCP tool ran, by which client, how long it took, and whether
     * the tool returned an error envelope.
     */
    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult auditedExecute(
            final McpTool tool,
            final java.util.Map< String, Object > arguments,
            final io.modelcontextprotocol.server.McpSyncServerExchange exchange ) {
        final long t0 = System.nanoTime();
        io.modelcontextprotocol.spec.McpSchema.CallToolResult result = null;
        Throwable thrown = null;
        try {
            result = tool.execute( arguments );
            return result;
        } catch ( final RuntimeException re ) {
            thrown = re;
            throw re;
        } finally {
            final long ms = ( System.nanoTime() - t0 ) / 1_000_000L;
            String client = "?";
            try {
                final var ci = exchange != null ? exchange.getClientInfo() : null;
                if ( ci != null && ci.name() != null ) client = ci.name();
            } catch ( final RuntimeException ignored ) {
                LOG.debug( "Client info unavailable for MCP call telemetry; using '?': {}", ignored.getMessage() );
                // exchange.getClientInfo can throw before initialize completes
            }
            final boolean isError = result != null && Boolean.TRUE.equals( result.isError() );
            if ( thrown != null ) {
                LOG.info( "Knowledge MCP tools/call name={} client={} durationMs={} threw={}",
                        tool.name(), client, ms, thrown.getClass().getSimpleName() );
            } else {
                LOG.info( "Knowledge MCP tools/call name={} client={} durationMs={} isError={}",
                        tool.name(), client, ms, isError );
            }
        }
    }

    /**
     * Builds the guest {@link PageViewGate} the retrieval tools use to enforce page view-ACLs.
     * Uses the request-free guest session + {@code PermissionFilter.canAccessQuietly} pattern that
     * the public RDF/ontology surface uses, so only anonymously-viewable pages are returned.
     *
     * <p>If the guest session cannot be built (effectively unreachable — {@code engine} is already
     * confirmed healthy above), it logs loudly and degrades to {@link PageViewGate#ALLOW_ALL} rather
     * than breaking all retrieval; the alternative (deny-all) would silently disable the whole
     * surface. The construction is deterministic for a healthy engine.
     */
    private static PageViewGate guestViewGate( final Engine engine ) {
        try {
            final var guest = com.wikantik.WikiSession.guestSession( engine );
            final var permFilter = new com.wikantik.auth.permissions.PermissionFilter( engine );
            return slug -> slug != null && !slug.isBlank()
                    && permFilter.canAccessQuietly( guest, slug, "view" );
        } catch ( final RuntimeException e ) {
            LOG.error( "Knowledge MCP: could not build guest view-gate — retrieval ACL enforcement is "
                    + "DISABLED (degraded to allow-all); restricted pages may be exposed: {}",
                    e.getMessage(), e );
            return PageViewGate.ALLOW_ALL;
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
        // Closing the server leaves the SDK's non-daemon session sweeper running; see SdkSessionSweepers.
        SdkSessionSweepers.disposeAll();
    }
}

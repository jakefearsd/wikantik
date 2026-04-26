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
import com.wikantik.filters.FilterManager;


/**
 * Bootstraps the MCP server on application startup. Retrieves the shared WikiEngine
 * from the ServletContext, creates MCP tool instances, resources, and prompts, and
 * registers a Streamable HTTP transport servlet at {@code /wikantik-admin-mcp}.
 */
public class McpServerInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( McpServerInitializer.class );
    static final String ATTR_MCP_SERVER = "com.wikantik.mcp.McpSyncServer";

    private McpSyncServer mcpServer;
    // WikiEventManager holds listeners as WeakReferences — keep a strong reference
    // to the bridge here so it isn't GC'd between save/delete events. Without this,
    // REST-saved pages never reach the MCP client (mirrors the
    // StructuralIndexEventListener anchor in WikiEngine#initStructuralIndex).
    private WikiEventSubscriptionBridge subscriptionBridge;

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

            subscriptionBridge = new WikiEventSubscriptionBridge( mcpServer );
            subscriptionBridge.register( pageManager, engine.getManager( FilterManager.class ) );

            servletContext.setAttribute( ATTR_MCP_SERVER, mcpServer );
            final int totalTools = toolRegistry.readOnlyTools().size() + toolRegistry.authorConfigurableTools().size();
            // D12: warn loudly when the static instructions text mentions tools that are
            // not actually registered (or vice versa). The instructions are loaded from
            // a static file; tool registration is dynamic. A drift means the description
            // we hand the agent is lying about what's available.
            if ( instructions != null ) {
                final java.util.Set< String > registered = new java.util.LinkedHashSet<>();
                for ( final McpTool t : toolRegistry.readOnlyTools() ) registered.add( t.name() );
                for ( final McpTool t : toolRegistry.authorConfigurableTools() ) registered.add( t.name() );
                logToolNameDriftIfAny( instructions, registered );
            }
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

    /**
     * D12: log a warning when the instructions text and the live tool registry disagree.
     * The check is deliberately conservative — we only flag names that look like tool
     * identifiers (lowercase + underscores). Hits an exact-name regex `\b[a-z][a-z0-9_]+\b`
     * against the instructions, intersects with the registered set, and reports both
     * directions of the diff.
     */
    static void logToolNameDriftIfAny( final String instructions, final java.util.Set< String > registered ) {
        // Find candidate tool names mentioned in the instructions: lower-case + underscore tokens.
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile( "\\b[a-z][a-z0-9_]{2,}\\b" )
                .matcher( instructions );
        final java.util.Set< String > mentioned = new java.util.LinkedHashSet<>();
        while ( m.find() ) {
            final String tok = m.group();
            // A heuristic to avoid noise: only consider tokens that look like tool names
            // (must contain an underscore, since all our tools follow snake_case).
            if ( tok.indexOf( '_' ) >= 0 ) {
                mentioned.add( tok );
            }
        }
        // For drift reporting we look at "mentioned but not registered" — the most
        // misleading case for an agent.
        final java.util.Set< String > toolLikeNames = new java.util.LinkedHashSet<>();
        for ( final String name : mentioned ) {
            // Filter mentioned tokens to those that actually look like tool identifiers
            // by intersecting with a small set of common verb prefixes used in our registry.
            if ( name.startsWith( "get_" ) || name.startsWith( "list_" ) || name.startsWith( "search_" )
                    || name.startsWith( "find_" ) || name.startsWith( "write_" ) || name.startsWith( "delete_" )
                    || name.startsWith( "rename_" ) || name.startsWith( "diff_" ) || name.startsWith( "verify_" )
                    || name.startsWith( "ping_" ) || name.startsWith( "preview_" ) || name.startsWith( "propose_" )
                    || name.startsWith( "mark_" ) || name.startsWith( "update_" ) || name.startsWith( "read_" )
                    || name.startsWith( "retrieve_" ) || name.startsWith( "traverse" ) || name.startsWith( "discover_" )
                    || name.startsWith( "query_" ) ) {
                toolLikeNames.add( name );
            }
        }
        final java.util.Set< String > missingFromRegistry = new java.util.LinkedHashSet<>( toolLikeNames );
        missingFromRegistry.removeAll( registered );
        if ( !missingFromRegistry.isEmpty() ) {
            LOG.warn( "MCP instructions mention tool name(s) that are NOT registered: {} — "
                    + "the description text is drifting from the runtime registry. "
                    + "Update wikantik-mcp-instructions.txt or implement the missing tools.",
                    missingFromRegistry );
        }
        final java.util.Set< String > missingFromInstructions = new java.util.LinkedHashSet<>( registered );
        missingFromInstructions.removeAll( toolLikeNames );
        if ( !missingFromInstructions.isEmpty() ) {
            LOG.warn( "MCP tools registered but NOT mentioned in the instructions text: {} — "
                    + "the agent will not know about these without seeing tools/list directly.",
                    missingFromInstructions );
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

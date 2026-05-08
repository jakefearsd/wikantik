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
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.core.subsystem.CoreSubsystemBridge;
import com.wikantik.mcp.completions.WikiCompletions;
import com.wikantik.mcp.prompts.WikiPrompts;
import com.wikantik.mcp.resources.WikiEventSubscriptionBridge;
import com.wikantik.mcp.resources.WikiResources;
import com.wikantik.mcp.tools.AuthorConfigurable;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.api.managers.PageManager;
import com.wikantik.page.subsystem.PageSubsystemBridge;
import com.wikantik.api.managers.ReferenceManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.render.subsystem.RenderingSubsystemBridge;


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

            final PageManager pageManager = PageSubsystemBridge.fromLegacyEngine( engine ).pages();
            final ReferenceManager referenceManager = PageSubsystemBridge.fromLegacyEngine( engine ).referenceManager();
            final AttachmentManager attachmentManager = PageSubsystemBridge.fromLegacyEngine( engine ).attachments();
            final SystemPageRegistry systemPageRegistry = CoreSubsystemBridge.fromLegacyEngine( engine ).systemPageRegistry();

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
                        auditedExecute( tool, request.arguments(), exchange, false ) );
            }

            for ( final McpTool tool : toolRegistry.authorConfigurableTools() ) {
                builder.toolCall( tool.definition(), ( exchange, request ) -> {
                    resolveAuthor( exchange, tool );
                    return auditedExecute( tool, request.arguments(), exchange, true );
                } );
            }

            mcpServer = builder
                    .resources( wikiResources.staticResources() )
                    .resourceTemplates( wikiResources.resourceTemplates() )
                    .prompts( WikiPrompts.all() )
                    .completions( WikiCompletions.all( referenceManager ) )
                    .build();

            subscriptionBridge = new WikiEventSubscriptionBridge( mcpServer );
            subscriptionBridge.register( pageManager, RenderingSubsystemBridge.fromLegacyEngine( engine ).filterManager() );

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
     * Tool-like verb prefixes that mark a snake_case token as a candidate tool name in the
     * instructions text. Centralised so the live drift detector and the regression test
     * agree on what counts as a "mention." Add a new prefix here when a new tool family
     * lands (e.g. {@code archive_*}); otherwise the heuristic underreports drift.
     */
    static final java.util.Set< String > TOOL_NAME_PREFIXES = java.util.Set.of(
            "get_", "list_", "search_", "find_", "write_", "delete_", "rename_", "diff_",
            "verify_", "ping_", "preview_", "propose_", "mark_", "update_", "read_",
            "retrieve_", "traverse_", "discover_", "query_" );

    /**
     * Snapshot of how the static instructions text and the live tool registry agree.
     * Both sets are empty when the file is in sync.
     */
    public record ToolNameDrift( java.util.Set< String > mentionedButNotRegistered,
                                 java.util.Set< String > registeredButNotMentioned ) {
        public boolean isEmpty() {
            return mentionedButNotRegistered.isEmpty() && registeredButNotMentioned.isEmpty();
        }
    }

    /**
     * Extracts tool-name candidates from a free-text instructions blob: snake_case tokens
     * starting with one of {@link #TOOL_NAME_PREFIXES}. Shared between the runtime drift
     * warning and the regression test that fails the build on drift.
     */
    static java.util.Set< String > extractToolLikeNames( final String instructions ) {
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile( "\\b[a-z][a-z0-9_]{2,}\\b" )
                .matcher( instructions );
        final java.util.Set< String > result = new java.util.LinkedHashSet<>();
        while ( m.find() ) {
            final String tok = m.group();
            if ( tok.indexOf( '_' ) < 0 ) {
                continue;
            }
            for ( final String prefix : TOOL_NAME_PREFIXES ) {
                if ( tok.startsWith( prefix ) ) {
                    result.add( tok );
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Compares the snake_case tokens visible in the instructions to the live tool registry.
     * Pure function — the runtime warning {@link #logToolNameDriftIfAny} and the
     * regression test both build on this.
     */
    static ToolNameDrift computeToolNameDrift( final String instructions,
                                                final java.util.Set< String > registered ) {
        final java.util.Set< String > mentioned = extractToolLikeNames( instructions );
        final java.util.Set< String > extras = new java.util.LinkedHashSet<>( mentioned );
        extras.removeAll( registered );
        final java.util.Set< String > missing = new java.util.LinkedHashSet<>( registered );
        missing.removeAll( mentioned );
        return new ToolNameDrift( extras, missing );
    }

    /**
     * D12: warn loudly when the instructions and the live registry disagree. Production
     * keeps booting (an outdated instructions file is annoying, not fatal); the
     * regression test in {@code InstructionsRegistryDriftTest} fails the build instead.
     */
    static void logToolNameDriftIfAny( final String instructions, final java.util.Set< String > registered ) {
        final ToolNameDrift drift = computeToolNameDrift( instructions, registered );
        if ( !drift.mentionedButNotRegistered().isEmpty() ) {
            LOG.warn( "MCP instructions mention tool name(s) that are NOT registered: {} — "
                    + "the description text is drifting from the runtime registry. "
                    + "Update wikantik-mcp-instructions.txt or implement the missing tools.",
                    drift.mentionedButNotRegistered() );
        }
        if ( !drift.registeredButNotMentioned().isEmpty() ) {
            LOG.warn( "MCP tools registered but NOT mentioned in the instructions text: {} — "
                    + "the agent will not know about these without seeing tools/list directly.",
                    drift.registeredButNotMentioned() );
        }
    }

    /**
     * Wraps {@link McpTool#execute} with an INFO-level audit line so admins can see
     * which MCP tool ran, by which client, how long it took, and whether the tool
     * returned an error envelope (CallToolResult.isError) — none of which is
     * visible in the access log alone.
     */
    private static McpSchema.CallToolResult auditedExecute(
            final McpTool tool,
            final Map< String, Object > arguments,
            final io.modelcontextprotocol.server.McpSyncServerExchange exchange,
            final boolean writeSurface ) {
        final long t0 = System.nanoTime();
        McpSchema.CallToolResult result = null;
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
                final McpSchema.Implementation ci = exchange != null ? exchange.getClientInfo() : null;
                if ( ci != null && ci.name() != null ) client = ci.name();
            } catch ( final RuntimeException ignored ) {
                // exchange.getClientInfo can throw before initialize completes
            }
            final boolean isError = result != null && Boolean.TRUE.equals( result.isError() );
            if ( thrown != null ) {
                LOG.info( "MCP tools/call name={} client={} surface={} durationMs={} threw={}",
                        tool.name(), client, writeSurface ? "write" : "read", ms,
                        thrown.getClass().getSimpleName() );
            } else {
                LOG.info( "MCP tools/call name={} client={} surface={} durationMs={} isError={}",
                        tool.name(), client, writeSurface ? "write" : "read", ms, isError );
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

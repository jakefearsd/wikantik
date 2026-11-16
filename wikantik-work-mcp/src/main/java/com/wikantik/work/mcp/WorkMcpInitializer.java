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
package com.wikantik.work.mcp;

import com.wikantik.WikiEngine;
import com.wikantik.api.Release;
import com.wikantik.api.core.Engine;
import com.wikantik.api.spi.Wiki;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.mcp.McpEndpointBootstrapper;
import com.wikantik.mcp.SdkSessionSweepers;
import com.wikantik.work.Actor;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkService;
import com.wikantik.work.runtime.WikiWorkDirectory;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Bootstraps the work MCP server at {@code /work-mcp}: the endpoint through which work-scoped API keys, agents above
 * all, read and change work items.
 *
 * <p>The endpoint is reachable by key only (see {@link McpEndpointBootstrapper.Builder#keyOnly}), and who is calling
 * is decided per tool call from that call's own request ({@link WorkMcpActors}). When {@code wikantik.work.enabled}
 * is false, or work failed to wire, the engine has no {@link WorkService}: nothing is registered and
 * {@code /work-mcp} is a container 404.
 */
public class WorkMcpInitializer implements ServletContextListener {

    private static final Logger LOG = LogManager.getLogger( WorkMcpInitializer.class );

    static final String ATTR_WORK_MCP_SERVER = "com.wikantik.work.mcp.McpSyncServer";
    static final String SERVER_DISPLAY_NAME = "Wikantik Work";
    private static final String INSTRUCTIONS_RESOURCE = "/wikantik-work-mcp-instructions.txt";

    private McpSyncServer mcpServer;
    private boolean transportTracked;

    @Override
    public void contextInitialized( final ServletContextEvent sce ) {
        final ServletContext servletContext = sce.getServletContext();

        final Engine engine;
        try {
            engine = Wiki.engine().find( servletContext, null );
        } catch ( final Exception e ) {
            LOG.warn( "WikiEngine could not be created — Work MCP server not started: {}", e.getMessage() );
            return;
        }
        final WorkService svc = engine instanceof WikiEngine we ? we.workService() : null;
        if ( svc == null ) {
            LOG.info( "Work management is not enabled (wikantik.work.enabled) — Work MCP server not started" );
            return;
        }

        final McpEndpointBootstrapper bootstrapper = McpEndpointBootstrapper.builder()
                .logTag( "Work MCP" )
                .endpointPath( "/work-mcp" )
                .filterName( "WorkMcpAccessFilter" )
                .requiredScope( ApiKeyService.Scope.WORK )
                .keyOnly( true )
                .servletName( "WorkMcpTransportServlet" )
                .loadOnStartup( 4 )
                .engine( engine )
                .build();

        try {
            bootstrapper.registerAccessFilter( servletContext );
        } catch ( final Exception e ) {
            LOG.error( "Work MCP startup failed during access-filter setup — /work-mcp will be unavailable: {}",
                    e.getMessage(), e );
            return;
        }

        final HttpServletStreamableServerTransportProvider transportProvider;
        try {
            transportProvider = bootstrapper.registerTransport( servletContext, WorkMcpActors::extract );
            transportTracked = true;
        } catch ( final Exception e ) {
            LOG.error( "Work MCP startup failed during transport servlet registration — "
                    + "access filter was registered but endpoint has no servlet: {}", e.getMessage(), e );
            return;
        }

        try {
            final WorkDirectory directory = new WikiWorkDirectory( engine );
            final List< WorkMcpTool > tools = assembleTools( svc, directory );
            final McpServer.SyncSpecification< ? > spec = McpServer.sync( transportProvider )
                    .serverInfo( new McpSchema.Implementation( "wikantik-work", SERVER_DISPLAY_NAME, Release.getVersionString() ) )
                    .instructions( serverInstructions() )
                    .capabilities( McpSchema.ServerCapabilities.builder().tools( true ).build() );
            registerTools( spec, tools, svc );
            mcpServer = spec.build();
            servletContext.setAttribute( ATTR_WORK_MCP_SERVER, mcpServer );
            LOG.info( "Work MCP server started successfully with {} tools at /work-mcp", tools.size() );
        } catch ( final Exception e ) {
            LOG.error( "Work MCP startup failed while building the server — transport servlet is registered "
                    + "but will return protocol errors: {}", e.getMessage(), e );
        }
    }

    /**
     * The tools this endpoint registers, in the order they are advertised. Pure function of its inputs (no engine, no
     * servlet context) so the tool surface is unit-testable.
     */
    static List< WorkMcpTool > assembleTools( final WorkService svc, final WorkDirectory directory ) {
        return List.of(
            new GetWorkContextTool( svc, directory ),
            new ListClaimableTool( svc, directory ),
            new GetItemTool( svc, directory ) );
    }

    /** Registers each tool so that every call is dispatched with an actor resolved from that call's own request. */
    static void registerTools( final McpServer.SyncSpecification< ? > spec, final List< WorkMcpTool > tools,
                               final WorkService svc ) {
        for ( final WorkMcpTool tool : tools ) {
            spec.toolCall( tool.definition(), ( exchange, request ) ->
                    dispatch( tool, svc, exchange.transportContext(), request.arguments() ) );
        }
    }

    /**
     * One tool call: the actor comes from {@code context}, the context of this call's own HTTP request, and is
     * resolved now, so a key that stopped being valid since the session began is refused.
     */
    static McpSchema.CallToolResult dispatch( final WorkMcpTool tool, final WorkService svc,
                                              final McpTransportContext context, final Map< String, Object > args ) {
        final long t0 = System.nanoTime();
        Actor actor = null;
        McpSchema.CallToolResult result;
        try {
            actor = WorkMcpActors.actorFor( context, svc );
            result = tool.call( actor, args );
        } catch ( final SQLException e ) {
            LOG.warn( "Work MCP could not resolve the caller's key for {}: {}", tool.name(), e.getMessage(), e );
            result = WorkMcpTool.error( WorkMcpTool.UNAVAILABLE );
        }
        LOG.info( "Work MCP tools/call name={} caller={} durationMs={} isError={}", tool.name(),
                actor == null ? "none" : actor.kind() == Actor.Kind.AGENT ? "agent:" + actor.agentId() : "person",
                ( System.nanoTime() - t0 ) / 1_000_000L, Boolean.TRUE.equals( result.isError() ) );
        return result;
    }

    /** The server-level instructions advertised in the MCP {@code initialize} reply. */
    static String serverInstructions() {
        try ( InputStream in = WorkMcpInitializer.class.getResourceAsStream( INSTRUCTIONS_RESOURCE ) ) {
            if ( in == null ) {
                LOG.warn( "Work MCP instructions resource {} is missing", INSTRUCTIONS_RESOURCE );
                return "Work management endpoint.";
            }
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        } catch ( final IOException e ) {
            LOG.warn( "Work MCP instructions could not be read: {}", e.getMessage(), e );
            return "Work management endpoint.";
        }
    }

    @Override
    public void contextDestroyed( final ServletContextEvent sce ) {
        if ( mcpServer != null ) {
            try {
                mcpServer.close();
                LOG.info( "Work MCP server shut down" );
            } catch ( final Exception e ) {
                LOG.warn( "Error shutting down Work MCP server: {}", e.getMessage() );
            }
        }
        if ( transportTracked ) {
            // Closing the server leaves the SDK's non-daemon session sweeper running; see SdkSessionSweepers.
            SdkSessionSweepers.disposeAll();
        }
    }
}

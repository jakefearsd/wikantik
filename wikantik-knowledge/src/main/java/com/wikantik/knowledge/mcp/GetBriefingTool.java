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

import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingLogEntry;
import com.wikantik.api.briefing.BriefingLogService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.briefing.ScopeMode;
import com.wikantik.api.querylog.ActorType;
import com.wikantik.api.querylog.QueryLogService;
import com.wikantik.api.querylog.SourceSurface;
import com.wikantik.knowledge.briefing.BriefingAclGate;
import com.wikantik.knowledge.briefing.MarkdownBriefingRenderer;
import com.wikantik.mcp.tools.McpTool;
import com.wikantik.mcp.tools.McpToolUtils;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP tool — assemble a session-start context briefing: prompt-refined wiki sections plus
 * pinned pages and cluster members, filled into a token budget with pointers for what did
 * not fit. Unlike {@link AssembleBundleTool} (JSON, repeated per-question calls), this tool
 * returns injection-ready <b>markdown</b> and is meant to be called once at session start
 * (ADR-0001 — never synthesizes an answer).
 */
public class GetBriefingTool implements McpTool {

    private static final Logger LOG = LogManager.getLogger( GetBriefingTool.class );
    public static final String TOOL_NAME = "get_briefing";

    private final BriefingAssemblyService service;
    /** Resolved at call time (not construction) so it survives the post-startup wiring order; may yield null. */
    private final Supplier< QueryLogService > queryLog;
    private final Supplier< BriefingLogService > briefingLog;
    private final PageViewGate viewGate;

    public GetBriefingTool( final BriefingAssemblyService service,
                            final Supplier< QueryLogService > queryLog,
                            final Supplier< BriefingLogService > briefingLog,
                            final PageViewGate viewGate ) {
        this.service = service;
        this.queryLog = queryLog;
        this.briefingLog = briefingLog;
        this.viewGate = viewGate == null ? PageViewGate.ALLOW_ALL : viewGate;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > props = new LinkedHashMap<>();
        props.put( "pins", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Page slugs to include in full, regardless of retrieval relevance."
        ) );
        props.put( "clusters", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Cluster names whose member pages should be included."
        ) );
        props.put( "prompt", Map.of(
                "type", "string",
                "description", "The user's first request this session — widens the briefing with retrieval-driven sections."
        ) );
        props.put( "budget", Map.of(
                "type", "number",
                "description", "Token budget for the assembled briefing."
        ) );
        props.put( "scope_mode", Map.of(
                "type", "string",
                "description", "prefer (default; may widen beyond pins/clusters) or strict (stay within them)."
        ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Assemble a session-start context briefing: prompt-refined wiki sections plus "
                        + "pinned pages and cluster members, filled into a token budget with pointers for what "
                        + "did not fit. Returns injection-ready markdown. Call this ONCE at the start of a "
                        + "session with the clusters/pins configured for your project and the user's first "
                        + "request as `prompt`. For follow-up questions use assemble_bundle instead. Does NOT "
                        + "synthesize an answer." )
                .inputSchema( new McpSchema.JsonSchema( "object", props, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    public McpSchema.CallToolResult execute( final Map< String, Object > arguments ) {
        try {
            final List< String > pins = stringList( McpToolUtils.firstListArg( arguments, "pins" ) );
            final List< String > clusters = stringList( McpToolUtils.firstListArg( arguments, "clusters" ) );
            final String prompt = McpToolUtils.getString( arguments, "prompt" );
            final Integer budget = arguments.get( "budget" ) instanceof Number n ? n.intValue() : null;

            final ScopeMode scopeMode;
            try {
                scopeMode = ScopeMode.fromWire( McpToolUtils.getString( arguments, "scope_mode" ) );
            } catch ( final IllegalArgumentException e ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
            }

            final BriefingRequest request = new BriefingRequest( pins, clusters, prompt, budget, scopeMode );
            if ( !request.hasAnySource() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "at least one of pins, clusters, prompt is required" );
            }

            final ContextBriefing briefing = service.assemble( request );

            // Guest view-ACL: the MCP surface has no caller identity, so only publicly-viewable pages
            // are returned. Pointers are gated too — dropping a restricted page's title, not just its
            // body (404-hiding semantics, see PageViewGate). A dropped-restricted pin gets the same
            // "unknown pin" warning a nonexistent pin would, so the two are indistinguishable
            // (see BriefingAclGate).
            final ContextBriefing gated = BriefingAclGate.gate( briefing, pins, viewGate::canView );

            final boolean promptPresent = prompt != null && !prompt.isBlank();
            final QueryLogService qlog = queryLog == null ? null : queryLog.get();
            if ( qlog != null && promptPresent ) {
                qlog.log( prompt, ActorType.AGENT, SourceSurface.MCP_GET_BRIEFING, gated.sections().size() );
            }

            final BriefingLogService blog = briefingLog == null ? null : briefingLog.get();
            if ( blog != null ) {
                final int pinCount = (int) gated.items().stream()
                        .filter( i -> i.included() && "pin".equals( i.origin() ) ).count();
                final int pointerCount = (int) gated.items().stream().filter( i -> !i.included() ).count();
                blog.log( new BriefingLogEntry( String.join( ",", pins ), String.join( ",", clusters ),
                        promptPresent, gated.budgetTokens(), gated.usedTokens(), gated.sections().size(),
                        pinCount, pointerCount, SourceSurface.MCP_GET_BRIEFING.wire() ) );
            }

            return McpSchema.CallToolResult.builder()
                    .content( List.of( new McpSchema.TextContent( MarkdownBriefingRenderer.render( gated ) ) ) )
                    .isError( false )
                    .build();
        } catch ( final Exception e ) {
            LOG.error( "get_briefing failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, e.getMessage() );
        }
    }

    private static List< String > stringList( final List< ? > raw ) {
        if ( raw == null || raw.isEmpty() ) {
            return List.of();
        }
        final List< String > out = new ArrayList<>( raw.size() );
        for ( final Object o : raw ) {
            if ( o != null ) {
                final String s = o.toString().trim();
                if ( !s.isEmpty() ) out.add( s );
            }
        }
        return out;
    }
}

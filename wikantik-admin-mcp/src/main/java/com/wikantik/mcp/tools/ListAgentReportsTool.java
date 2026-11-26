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
package com.wikantik.mcp.tools;

import com.wikantik.agentreports.AgentReport;
import com.wikantik.agentreports.AgentReportService;
import com.wikantik.agentreports.AgentReportView;
import com.wikantik.agentreports.ReportQuery;
import com.wikantik.agentreports.ReportQueryParser;
import com.wikantik.mcp.AbstractMcpTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Read-only admin view of the field reports agents file through {@code file_agent_report}: what they worked on,
 * what context helped, and where they hit friction. Filters are all optional; the default window is the last
 * seven days.
 */
public class ListAgentReportsTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "list_agent_reports";
    private static final Logger LOG = LogManager.getLogger( ListAgentReportsTool.class );

    /** Yields the report service, or null while the report store is not wired (no datasource). */
    private final Supplier< AgentReportService > service;
    private final Clock clock;

    public ListAgentReportsTool( final Supplier< AgentReportService > service, final Clock clock ) {
        this.service = service;
        this.clock = clock;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > p = new LinkedHashMap<>();
        p.put( "since", Map.of( "type", "string",
                "description", "ISO date or instant; reports filed at or after it (default: 7 days ago)" ) );
        p.put( "until", Map.of( "type", "string", "description", "ISO date or instant; reports filed before it" ) );
        p.put( "kind_of_work", Map.of( "type", "string", "description", "Only this kind of work, e.g. build" ) );
        p.put( "product", Map.of( "type", "string", "description", "Only reports naming this product" ) );
        p.put( "client", Map.of( "type", "string", "description", "Only this agent client, e.g. claude-code" ) );
        p.put( "skill", Map.of( "type", "string", "description", "Only reports naming this skill" ) );
        p.put( "outcome", Map.of( "type", "string", "description", "Only this outcome" ) );
        p.put( "limit", Map.of( "type", "integer", "description", "Max reports, newest first (default 100, max 500)" ) );
        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List the field reports agents filed with file_agent_report, newest first: client, "
                        + "kind of work, products, skills, task, outcome, the context that helped or was missing, "
                        + "and friction. Returns {reports, count}. Filters are optional; the default window is the "
                        + "last 7 days." )
                .inputSchema( new McpSchema.JsonSchema( "object", p, List.of(), null, null, null ) )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) {
        final AgentReportService svc = service.get();
        if ( svc == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Agent report store unavailable",
                    "report store unavailable: it needs a configured datasource." );
        }
        final ReportQuery query;
        try {
            query = ReportQueryParser.parse( k -> {
                final Object v = arguments.get( k );
                return v == null ? null : v.toString();
            }, clock );
        } catch ( final IllegalArgumentException e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Invalid argument", e.getMessage() );
        }
        final List< AgentReport > reports;
        try {
            reports = svc.list( query );
        } catch ( final RuntimeException e ) {
            LOG.warn( "list_agent_reports failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, "Failed to list agent reports",
                    "Check that the database is reachable and migration V098 has been applied." );
        }
        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "reports", AgentReportView.toMaps( reports ) );
        result.put( "count", reports.size() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }
}

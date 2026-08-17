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

import com.wikantik.insights.runtime.ContentOpportunityService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declines a content opportunity for a period, so it stops being re-proposed (design section
 * 7.5.1).
 *
 * <p>{@code reason} is <strong>mandatory</strong> and that is a deliberate design constraint, not
 * input validation for its own sake. A declined suggestion with no recorded reason is
 * indistinguishable from a bug six months later: the next reader cannot tell whether the rule
 * misfired, the work was done differently, or the page was judged out of reach. Requiring the
 * reason is what keeps the snooze table an audit trail rather than a silence switch.</p>
 */
public class SnoozeOpportunityTool extends DefaultAuthorTool {

    public static final String TOOL_NAME = "snooze_opportunity";
    private static final Logger LOG = LogManager.getLogger( SnoozeOpportunityTool.class );

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;

    /** Null when no datasource is configured; the tool then refuses at call time. */
    private final ContentOpportunityService service;

    public SnoozeOpportunityTool( final ContentOpportunityService service ) {
        this.service = service;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "type", Map.of( "type", "string",
                "description", "Opportunity type to snooze, e.g. stale_high_traffic" ) );
        properties.put( "target", Map.of( "type", "string",
                "description", "The opportunity's target: a page path, or a query string for agent_gap" ) );
        properties.put( "days", Map.of( "type", "integer",
                "description", "How long to suppress it, 1-365" ) );
        properties.put( "reason", Map.of( "type", "string",
                "description", "Why it is being declined. Required - a declined suggestion with no "
                        + "recorded reason is indistinguishable from a bug later." ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "snoozedUntil", "2026-10-16", "previouslySnoozed", false ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Snooze a content opportunity so it stops being re-proposed. All four "
                        + "arguments are required; 'reason' is recorded and is what makes a declined "
                        + "suggestion auditable rather than merely silent." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "type", "target", "days", "reason" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, false, true, null, null ) )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        if ( service == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "The content-intelligence subsystem is not available",
                    "It needs a configured datasource and wikantik.insights.enabled=true." );
        }

        final String type = stringArg( arguments, "type" );
        final String target = stringArg( arguments, "target" );
        final String reason = stringArg( arguments, "reason" );
        final Object daysArg = arguments.get( "days" );

        if ( type == null || target == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Both 'type' and 'target' are required",
                    "Pass the type and target exactly as list_content_opportunities reported them." );
        }
        if ( reason == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "'reason' is required",
                    "Record why this suggestion is being declined; it is the only thing that "
                            + "distinguishes a considered decline from a bug later." );
        }
        final int days;
        try {
            days = daysArg instanceof Number n ? n.intValue()
                    : Integer.parseInt( String.valueOf( daysArg ).strip() );
        } catch ( final NumberFormatException e ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "'days' must be a whole number",
                    "Pass a value between " + MIN_DAYS + " and " + MAX_DAYS + "." );
        }
        if ( days < MIN_DAYS || days > MAX_DAYS ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "'days' must be between " + MIN_DAYS + " and " + MAX_DAYS,
                    "A snooze is a deferral, not a deletion; re-snooze if it is still not worth doing." );
        }

        final ContentOpportunityService.SnoozeResult result;
        try {
            result = service.snooze( type, target, days, reason, getDefaultAuthor() );
        } catch ( final RuntimeException e ) {
            LOG.warn( "snooze_opportunity failed for {}/{}: {}", type, target, e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to record the snooze",
                    "Check that the database is reachable and migration V053 has been applied." );
        }

        McpAudit.logWrite( TOOL_NAME, "snoozed", type + "/" + target, getDefaultAuthor() );
        LOG.info( "snooze_opportunity: {}/{} until {} by {} ({})",
                type, target, result.snoozedUntil(), getDefaultAuthor(), reason );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "snoozedUntil", result.snoozedUntil() == null ? null : result.snoozedUntil().toString() );
        out.put( "previouslySnoozed", result.previouslySnoozed() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }

    private static String stringArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        return v == null || v.toString().isBlank() ? null : v.toString().strip();
    }
}

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

import com.wikantik.insights.Opportunity;
import com.wikantik.insights.SuppressedRule;
import com.wikantik.insights.runtime.ContentOpportunityService;
import com.wikantik.mcp.AbstractMcpTool;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The agent-facing read surface of the content-intelligence backlog (design section 7.5.1).
 *
 * <p>Returns opportunities ranked by estimated recoverable clicks, each carrying the
 * {@code evidence} that fired its rule so the agent can sanity-check a suggestion rather than
 * obey a score.</p>
 *
 * <p><strong>{@code suppressed} is as important as {@code opportunities}.</strong> Three of the
 * four native rules are driven by search volume and sit behind a site-level traffic gate
 * (design section 7.3.0): on a corpus drawing 2,626 impressions per 28 days across 479 pages, a
 * per-page verdict would be built on a median of two impressions. When the gate is closed those
 * rules do not run, and they are reported here with the measured and required values. An empty
 * {@code opportunities} list with entries in {@code suppressed} means "not enough evidence to
 * look", which is a completely different statement from "nothing to do" -- and the difference is
 * invisible if the caller only reads the list.</p>
 *
 * <p>{@code calibrated} is exposed per opportunity for the same reason: until a rule type has
 * accumulated enough evaluated outcomes its priority weight is a guess, and an agent should weigh
 * an uncalibrated suggestion less. The tool says which numbers have been earned.</p>
 *
 * <p>{@code ctrCurveSource} says which CTR-by-position curve priced {@code engine_divergence}:
 * {@code "imported:<as_of>"} when jakemon's real, measured curve was fresh enough to use, or
 * {@code "builtin"} when it fell back to the invented placeholder table. A jakemon shipment
 * quietly going stale must be visible here, not just inferable from different-looking numbers.</p>
 */
public class ListContentOpportunitiesTool extends AbstractMcpTool {

    public static final String TOOL_NAME = "list_content_opportunities";
    private static final Logger LOG = LogManager.getLogger( ListContentOpportunitiesTool.class );

    private static final int DEFAULT_LIMIT = 20;

    /** Null when no datasource is configured; the tool then refuses at call time. */
    private final ContentOpportunityService service;

    public ListContentOpportunitiesTool( final ContentOpportunityService service ) {
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
                "description", "Filter to one rule type: agent_gap | engine_divergence | "
                        + "vocabulary_gap | stale_high_traffic (default: all)" ) );
        properties.put( "limit", Map.of( "type", "integer",
                "description", "Max opportunities to return (default 20, max 200)" ) );
        properties.put( "min_priority", Map.of( "type", "number",
                "description", "Only return opportunities scoring at least this" ) );
        properties.put( "include_snoozed", Map.of( "type", "boolean",
                "description", "Include snoozed (type, target) pairs (default false)" ) );
        properties.put( "site", Map.of( "type", "string",
                "description", "Site host to evaluate (default: the first configured rules site)" ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "opportunities", List.of( Map.of(
                        "type", "agent_gap", "target", "how do I deploy locally",
                        "priority", 6.0, "calibrated", false,
                        "suggestedAction", "Curate the Knowledge Graph relations, or write the missing section.",
                        "firstSeen", "2026-08-02",
                        "evidence", Map.of( "occurrences", 3, "distinctSessions", 2 ) ) ),
                "count", 1,
                "suppressed", List.of( Map.of( "type", "engine_divergence",
                        "reason", "traffic_gate", "measured", 2626.0, "required", 5000.0 ) ),
                "uncalibratedTypes", List.of( "agent_gap", "engine_divergence" ),
                "ctrCurveSource", "imported:2026-08-14" ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "List the ranked content-opportunity backlog with the evidence behind each "
                        + "suggestion. ALWAYS read 'suppressed' as well as 'opportunities': an empty list "
                        + "with suppressed entries means the rules were gated off for lack of traffic, not "
                        + "that there is nothing to do. Weigh opportunities with calibrated=false less - "
                        + "their priority weight is still a guess." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties, List.of(), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, true, false, true, null, null ) )
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
        final String site = stringArg( arguments, "site" );
        final int limit = intArg( arguments, "limit", DEFAULT_LIMIT );
        final boolean includeSnoozed = boolArg( arguments, "include_snoozed" );
        final Double minPriority = arguments.get( "min_priority" ) instanceof Number n
                ? n.doubleValue() : null;

        final ContentOpportunityService.BacklogView view;
        try {
            view = service.backlog( site, type, minPriority, limit, includeSnoozed );
        } catch ( final RuntimeException e ) {
            LOG.warn( "list_content_opportunities failed: {}", e.getMessage(), e );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Failed to assemble the opportunity backlog",
                    "Check that the database is reachable and migrations V050-V055 have been applied." );
        }

        final List< Map< String, Object > > opportunities = new ArrayList<>();
        for ( final Opportunity o : view.opportunities() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", o.type() );
            m.put( "target", o.target() );
            m.put( "priority", o.priority() );
            m.put( "calibrated", o.calibrated() );
            m.put( "suggestedAction", o.suggestedAction() );
            m.put( "firstSeen", o.firstSeen() == null ? null : o.firstSeen().toString() );
            m.put( "evidence", o.evidence() );
            opportunities.add( m );
        }

        final List< Map< String, Object > > suppressed = new ArrayList<>();
        for ( final SuppressedRule s : view.suppressed() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "type", s.type() );
            m.put( "reason", s.reason() );
            m.put( "measured", s.measured() );
            m.put( "required", s.required() );
            suppressed.add( m );
        }

        final Map< String, Object > result = new LinkedHashMap<>();
        result.put( "opportunities", opportunities );
        result.put( "count", opportunities.size() );
        result.put( "suppressed", suppressed );
        result.put( "uncalibratedTypes", new ArrayList<>( view.uncalibratedTypes() ) );
        result.put( "generatedAt", view.generatedAt() == null ? null : view.generatedAt().toString() );
        result.put( "site", view.site() );
        result.put( "ctrCurveSource", view.ctrCurveSource() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, result );
    }

    private static String stringArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        return v == null || v.toString().isBlank() ? null : v.toString().strip();
    }

    private static boolean boolArg( final Map< String, Object > args, final String key ) {
        final Object v = args.get( key );
        if ( v instanceof Boolean b ) {
            return b;
        }
        return v != null && Boolean.parseBoolean( v.toString().strip() );
    }

    private static int intArg( final Map< String, Object > args, final String key, final int dflt ) {
        final Object v = args.get( key );
        if ( v instanceof Number n ) {
            return n.intValue();
        }
        if ( v != null ) {
            try {
                return Integer.parseInt( v.toString().strip() );
            } catch ( final NumberFormatException e ) {
                return dflt;
            }
        }
        return dflt;
    }
}

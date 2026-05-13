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

import com.wikantik.api.knowledge.KgCurationOps;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * MCP tool that bulk-reviews KG proposals (approve / reject / judge).
 *
 * <p>Input: {@code {verdict, ids[], reason?}}. Output: {@code {status, succeeded[], failed[], message}}.
 * Per-id failures are collected rather than aborting the batch. Top-level errors (missing verdict,
 * missing reject reason, bulk-limit exceeded) use {@code isError=true}.</p>
 */
public class ReviewProposalsTool implements McpTool, AuthorConfigurable {

    private static final Logger LOG = LogManager.getLogger( ReviewProposalsTool.class );
    public static final String TOOL_NAME = "review_proposals";

    private final KgCurationOps ops;
    private final int bulkLimit;
    private volatile String defaultAuthor = "admin";

    public ReviewProposalsTool( final KgCurationOps ops, final int bulkLimit ) {
        this.ops = ops;
        this.bulkLimit = bulkLimit;
    }

    @Override public String name() { return TOOL_NAME; }
    @Override public void setDefaultAuthor( final String author ) { this.defaultAuthor = author; }

    @Override public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "verdict", Map.of(
                "type", "string",
                "enum", List.of( "approve", "reject", "judge" ),
                "description", "Bulk verdict applied to every id.",
                "examples", List.of( "approve" )
        ) );
        properties.put( "ids", Map.of(
                "type", "array",
                "items", Map.of( "type", "string" ),
                "description", "Proposal UUIDs (1.." + bulkLimit + ")"
        ) );
        properties.put( "reason", Map.of(
                "type", "string",
                "description", "Required iff verdict == reject"
        ) );

        final Map< String, Object > exampleApprove = Map.of(
                "status", "completed",
                "succeeded", List.of( "8f3c2a1b-..." ),
                "failed", List.of(),
                "message", "1 of 1 proposals approved" );
        final Map< String, Object > exampleReject = Map.of(
                "status", "completed",
                "succeeded", List.of(),
                "failed", List.of( Map.of( "id", "8f3c2a1b-...", "error", "Not found: ..." ) ),
                "message", "0 of 1 proposals rejected" );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( exampleApprove, exampleReject ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Bulk review of 1.." + bulkLimit + " KG proposals. Verdict is applied " +
                        "to every id; per-id failures surface in `failed[]` with a reason. " +
                        "verdict='reject' requires a top-level `reason`." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "verdict", "ids" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, true, null, null ) )
                .build();
    }

    @Override public McpSchema.CallToolResult execute( final Map< String, Object > args ) {
        final String verdict = McpToolUtils.getString( args, "verdict" );
        if ( verdict == null || verdict.isBlank() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "verdict is required (approve | reject | judge)" );
        }
        if ( !Set.of( "approve", "reject", "judge" ).contains( verdict ) ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Unsupported verdict '" + verdict + "'" );
        }
        final String reason;
        if ( "reject".equals( verdict ) ) {
            reason = McpToolUtils.getString( args, "reason" );
            if ( reason == null || reason.isBlank() ) {
                return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                        "reason is required for verdict='reject'" );
            }
        } else { reason = null; }

        final Object rawIds = args.get( "ids" );
        if ( !( rawIds instanceof List< ? > rawList ) || rawList.isEmpty() ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "ids is required and must be a non-empty array" );
        }
        if ( rawList.size() > bulkLimit ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "bulk limit exceeded: " + rawList.size() + " > " + bulkLimit );
        }

        final List< String > succeeded = new ArrayList<>();
        final List< Map< String, Object > > failed = new ArrayList<>();
        final Map< String, List< String > > warningsByProposal = new LinkedHashMap<>();

        for ( final Object idEl : rawList ) {
            final String idStr = idEl == null ? null : idEl.toString();
            UUID id;
            try { id = UUID.fromString( idStr ); }
            catch ( final IllegalArgumentException e ) {
                LOG.warn( "review_proposals: invalid UUID '{}': {}", idStr, e.getMessage() );
                final Map< String, Object > f = new LinkedHashMap<>();
                f.put( "id", idStr );
                f.put( "error", "Invalid UUID: " + idStr );
                failed.add( f );
                continue;
            }

            if ( "approve".equals( verdict ) ) {
                final KgCurationOps.ApproveOutcome o = ops.tryApprove( id, defaultAuthor );
                if ( o.error().isPresent() ) {
                    final Map< String, Object > f = new LinkedHashMap<>();
                    f.put( "id", idStr );
                    f.put( "error", o.error().get() );
                    failed.add( f );
                } else {
                    succeeded.add( idStr );
                    if ( !o.warnings().isEmpty() ) {
                        warningsByProposal.put( idStr, o.warnings() );
                    }
                }
            } else {
                final Optional< String > err = switch ( verdict ) {
                    case "reject"  -> ops.tryRejectProposal( id, defaultAuthor, reason );
                    default        -> ops.tryJudgeProposal( id, defaultAuthor );
                };
                if ( err.isEmpty() ) {
                    succeeded.add( idStr );
                } else {
                    final Map< String, Object > f = new LinkedHashMap<>();
                    f.put( "id", idStr );
                    f.put( "error", err.get() );
                    failed.add( f );
                }
            }
        }

        McpAudit.logBulkWrite( TOOL_NAME, rawList.size(), succeeded.size(), failed.size(), defaultAuthor );

        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "status", "completed" );
        out.put( "succeeded", succeeded );
        out.put( "failed", failed );
        final String verbed = switch ( verdict ) {
            case "approve" -> "approved";
            case "reject"  -> "rejected";
            case "judge"   -> "judged";
            default        -> verdict + "d";
        };
        out.put( "message", succeeded.size() + " of " + rawList.size() + " proposals " + verbed );
        if ( !warningsByProposal.isEmpty() ) {
            out.put( "warnings_by_proposal", warningsByProposal );
        }
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, out );
    }
}

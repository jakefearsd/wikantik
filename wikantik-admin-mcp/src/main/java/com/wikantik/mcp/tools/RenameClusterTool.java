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

import com.wikantik.pagegraph.spine.ClusterRenamePlan;
import com.wikantik.pagegraph.spine.ClusterRenameResult;
import com.wikantik.pagegraph.spine.ClusterRenameService;
import io.modelcontextprotocol.spec.McpSchema;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *  MCP tool that renames a cluster across every page that names it.
 *
 *  <p>ClusterDeclarationDesign Phase 4. Cluster membership is a path in each member's
 *  frontmatter, so reorganizing the taxonomy is O(member pages) by construction. This tool
 *  makes that one operation instead of N edits — without it, the reorganization cost
 *  <i>is</i> the curation overhead the design exists to minimize.</p>
 *
 *  <p>Unlike {@code rename_page}, an unconfirmed call is not an error: it returns the
 *  <b>plan</b>. A bulk rewrite is precisely the operation whose blast radius a curator
 *  should see before committing to it, and computing the plan writes nothing.</p>
 */
public class RenameClusterTool extends DefaultAuthorTool {

    private static final Logger LOG = LogManager.getLogger( RenameClusterTool.class );
    public static final String TOOL_NAME = "rename_cluster";

    private final ClusterRenameService renameService;

    /**
     *  @param renameService the rename service, or {@code null} when the structural index is
     *                       not wired in this deployment — the tool is still registered and
     *                       refuses at call time, so the advertised tool surface never varies
     *                       with wiring (an agent must not have to discover that a tool it was
     *                       told about is missing here).
     */
    public RenameClusterTool( final ClusterRenameService renameService ) {
        this.renameService = renameService;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public McpSchema.Tool definition() {
        final Map< String, Object > properties = new LinkedHashMap<>();
        properties.put( "from", Map.of(
                "type", "string",
                "description", "Cluster path to rename. Sub-clusters beneath it move with it.",
                "examples", List.of( "machine-learning" )
        ) );
        properties.put( "to", Map.of(
                "type", "string",
                "description", "New cluster path (kebab-case; 'parent/child' for a sub-cluster)",
                "examples", List.of( "ml" )
        ) );
        properties.put( "confirm", Map.of(
                "type", "boolean",
                "description", "Omit or set false to preview the plan without writing. "
                        + "Set true to apply it.",
                "examples", List.of( true )
        ) );

        final Map< String, Object > outputSchema = new LinkedHashMap<>();
        outputSchema.put( "type", "object" );
        outputSchema.put( "examples", List.of( Map.of(
                "applied", true,
                "from", "machine-learning",
                "to", "ml",
                "pageCount", 44,
                "renamed", List.of( "MLHub", "InferenceServing" ),
                "complete", true
        ) ) );

        return McpSchema.Tool.builder()
                .name( TOOL_NAME )
                .description( "Rename a cluster across every page whose frontmatter names it, carrying its "
                        + "sub-clusters along. Without confirm=true this returns the plan (which pages would "
                        + "change, and any conflicts) and writes nothing. Refuses a target another hub already "
                        + "declares, since one cluster may have only one hub. Page names, canonical_ids and URLs "
                        + "are untouched — this edits frontmatter only. Returns {applied, from, to, pageCount, "
                        + "changes|renamed, conflicts, failures, complete}." )
                .inputSchema( new McpSchema.JsonSchema( "object", properties,
                        List.of( "from", "to" ), null, null, null ) )
                .outputSchema( outputSchema )
                .annotations( new McpSchema.ToolAnnotations( null, false, true, false, null, null ) )
                .build();
    }

    @Override
    protected McpSchema.CallToolResult doExecute( final Map< String, Object > arguments ) throws Exception {
        final String from = McpToolUtils.getString( arguments, "from" );
        final String to = McpToolUtils.getString( arguments, "to" );
        final boolean confirm = arguments.containsKey( "confirm" )
                && McpToolUtils.getBoolean( arguments, "confirm" );

        if ( renameService == null ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Cluster rename is unavailable: the structural index is not wired in this deployment",
                    "Without the structural index a cluster's members cannot be resolved. "
                            + "Rebuild the structural index and retry." );
        }

        final ClusterRenamePlan plan;
        try {
            plan = renameService.plan( from, to );
        } catch ( final IllegalArgumentException iae ) {
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON, iae.getMessage(),
                    "Provide two different, non-blank cluster paths. Use list_clusters to see what exists." );
        }

        if ( plan.hasConflicts() ) {
            McpAudit.logWrite( TOOL_NAME, "refused-conflict", from + "->" + to, getDefaultAuthor() );
            return McpToolUtils.errorResult( McpToolUtils.SHARED_GSON,
                    "Cannot rename '" + from + "' to '" + to + "': " + String.join( "; ", plan.conflicts() ),
                    "One cluster may be declared by exactly one hub. Retire or move the conflicting hub first." );
        }

        if ( !confirm ) {
            return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, previewOf( plan ) );
        }

        final ClusterRenameResult result = renameService.apply( from, to, getDefaultAuthor() );
        McpAudit.logWrite( TOOL_NAME, "renamed", from + "->" + to + " (" + result.renamed().size() + " pages)",
                          getDefaultAuthor() );
        LOG.info( "rename_cluster applied: '{}' -> '{}' by {}", from, to, getDefaultAuthor() );

        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "applied", true );
        payload.put( "from", result.from() );
        payload.put( "to", result.to() );
        payload.put( "pageCount", plan.changes().size() );
        payload.put( "renamed", result.renamed() );
        payload.put( "failures", result.failures() );
        payload.put( "complete", result.complete() );
        return McpToolUtils.jsonResult( McpToolUtils.SHARED_GSON, payload );
    }

    private static Map< String, Object > previewOf( final ClusterRenamePlan plan ) {
        final List< Map< String, Object > > changes = new ArrayList<>();
        for ( final ClusterRenamePlan.PageChange change : plan.changes() ) {
            changes.add( Map.of( "slug", change.slug(),
                                 "fromCluster", change.fromCluster(),
                                 "toCluster", change.toCluster() ) );
        }
        final Map< String, Object > payload = new LinkedHashMap<>();
        payload.put( "applied", false );
        payload.put( "from", plan.from() );
        payload.put( "to", plan.to() );
        payload.put( "pageCount", changes.size() );
        payload.put( "changes", changes );
        payload.put( "conflicts", plan.conflicts() );
        payload.put( "hint", plan.isEmpty()
                ? "No page names cluster '" + plan.from() + "'. Nothing to rename."
                : "Re-run with confirm=true to apply." );
        return payload;
    }
}

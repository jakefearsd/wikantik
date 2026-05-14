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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class CurateNodesToolTest {

    private KgCurationOps ops;
    private CurateNodesTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new CurateNodesTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void upsertOpEchoesTagAndResultingNodeId() {
        final UUID nodeId = UUID.randomUUID();
        when( ops.tryUpsertNode( eq( "Raft" ), eq( "concept" ), eq( "PaxosAndRaft" ),
                any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.NodeResult.ok( nodeId ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert",
                        "tag", "node-1",
                        "name", "Raft",
                        "node_type", "concept",
                        "source_page", "PaxosAndRaft" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"node-1\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + nodeId + "\"" ), body );
    }

    @Test
    void mergeSelfIsPerOpError() {
        final String id = UUID.randomUUID().toString();
        when( ops.tryMergeNodes( any(), any(), any() ) )
                .thenReturn( Optional.of( "source_id and target_id are the same" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "merge",
                        "tag", "node-merge",
                        "source_id", id,
                        "target_id", id ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "same" ), body );
        assertTrue( body.contains( "\"action\":\"merge\"" ), body );
    }

    @Test
    void upsertMissingRequiredNameIsPerOpError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "x" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "name is required" )
                || body.contains( "requires name" ), body );
    }

    @Test
    void mergeServiceErrorSurfacesPerOp() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        when( ops.tryMergeNodes( eq( src ), eq( tgt ), any() ) )
                .thenReturn( Optional.of( "merge constraint violation" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "merge", "tag", "n1",
                        "source_id", src.toString(), "target_id", tgt.toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "merge constraint violation" ), body );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< Object > ops51 = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops51.add( Map.of( "action", "delete",
                "id", UUID.randomUUID().toString() ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "operations", ops51 ) );
        assertTrue( r.isError() );
    }

    @Test
    void upsertRejectsNestedNodeShapeWithGuidance() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "bad",
                        "node", Map.of( "name", "X", "node_type", "concept" ) ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "top level" ),
                "Error should explain top-level shape: " + body );
        assertTrue( body.contains( "not nested under" ) && body.contains( "node" ), body );
    }

    // Covers CurateNodesTool.java:110-115 — doDelete happy path returns the id.
    @Test
    void deleteSuccessReturnsId() {
        final UUID id = UUID.randomUUID();
        when( ops.tryDeleteNode( eq( id ), eq( "alice" ) ) ).thenReturn( Optional.empty() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "delete", "tag", "node-del", "id", id.toString() ) ) ) );

        assertFalse( r.isError(), "happy-path delete must not flag isError" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"node-del\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + id + "\"" ), body );
        assertTrue( body.contains( "\"action\":\"delete\"" ), body );
    }

    // Covers CurateNodesTool.java:104-107 — when tryUpsertNode returns NodeResult.fail
    // the per-op response carries the failure message in the failed[] array.
    @Test
    void upsertServiceFailureSurfacesAsPerOpError() {
        when( ops.tryUpsertNode( eq( "Excluded" ), eq( "concept" ), eq( "ExcludedPage" ),
                any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.NodeResult.fail(
                        "node not visible after insert (excluded source page or other policy filter)" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "fail-node",
                        "name", "Excluded",
                        "node_type", "concept",
                        "source_page", "ExcludedPage" ) ) ) );

        // curate_nodes returns a non-error envelope even when every op fails;
        // per-op errors live in failed[].error. KgCurationIT in /wikantik-it-tests
        // pins this contract.
        assertFalse( r.isError(), "curate_nodes returns non-error envelope on per-op failure" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "not visible after insert" ), body );
        assertTrue( body.contains( "\"tag\":\"fail-node\"" ), body );
        assertTrue( body.contains( "\"action\":\"upsert\"" ), body );
        assertTrue( body.contains( "\"status\":\"failed\"" ), body );
    }
}

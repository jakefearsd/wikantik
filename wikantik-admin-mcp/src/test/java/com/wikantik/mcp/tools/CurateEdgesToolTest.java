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

public class CurateEdgesToolTest {

    private KgCurationOps ops;
    private CurateEdgesTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new CurateEdgesTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void upsertOpEchoesTagAndResultingEdgeId() {
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        final UUID edgeId = UUID.randomUUID();
        when( ops.tryUpsertEdge( eq( src ), eq( tgt ), eq( "rel" ), any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.EdgeResult.ok( edgeId ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert",
                        "tag", "edge-1",
                        "source_id", src.toString(),
                        "target_id", tgt.toString(),
                        "relationship_type", "rel" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"edge-1\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + edgeId + "\"" ), body );
    }

    @Test
    void confirmOpReportsServiceError() {
        final UUID id = UUID.randomUUID();
        when( ops.tryConfirmEdge( eq( id ), any() ) )
                .thenReturn( Optional.of( "Edge not found: " + id ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "confirm", "tag", "edge-2", "id", id.toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Edge not found" ), body );
        assertTrue( body.contains( "\"action\":\"confirm\"" ), body );
    }

    @Test
    void unknownActionIsPerOpError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "bogus", "tag", "x" ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Unsupported action" ), body );
    }

    @Test
    void deleteAndRejectRequiresReason() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "delete_and_reject", "tag", "x", "id", UUID.randomUUID().toString() ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "reason is required" ), body );
    }

    @Test
    void allFailedOperationsFlagBulkResultAsIsError() {
        // When zero of N operations succeed, the bulk result is hard-flagged as
        // isError so the calling model treats it as a tool failure instead of
        // glancing past status:"completed" and missing the failed[].error array.
        final UUID src = UUID.randomUUID();
        final UUID tgt = UUID.randomUUID();
        when( ops.tryUpsertEdge( eq( src ), eq( tgt ), eq( "rel" ), any(), eq( "alice" ) ) )
                .thenReturn( KgCurationOps.EdgeResult.fail(
                        "edge rejected: endpoints cross the page/entity boundary" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "x",
                        "source_id", src.toString(), "target_id", tgt.toString(),
                        "relationship_type", "rel" ) ) ) );

        assertTrue( r.isError(), "0-of-N-succeeded must set isError" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"status\":\"failed\"" ), body );
        assertTrue( body.contains( "page/entity boundary" ), body );
    }

    @Test
    void partialSuccessDoesNotFlagBulkResultAsIsError() {
        // Mixed outcomes: the model needs to consume both succeeded and failed arrays.
        // isError must stay false so the response isn't treated as a hard failure.
        final UUID okSrc = UUID.randomUUID(), okTgt = UUID.randomUUID(), okId = UUID.randomUUID();
        final UUID badSrc = UUID.randomUUID(), badTgt = UUID.randomUUID();
        when( ops.tryUpsertEdge( eq( okSrc ), eq( okTgt ), any(), any(), any() ) )
                .thenReturn( KgCurationOps.EdgeResult.ok( okId ) );
        when( ops.tryUpsertEdge( eq( badSrc ), eq( badTgt ), any(), any(), any() ) )
                .thenReturn( KgCurationOps.EdgeResult.fail( "edge rejected" ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of(
                        Map.of( "action", "upsert", "tag", "ok",
                                "source_id", okSrc.toString(), "target_id", okTgt.toString(),
                                "relationship_type", "rel" ),
                        Map.of( "action", "upsert", "tag", "bad",
                                "source_id", badSrc.toString(), "target_id", badTgt.toString(),
                                "relationship_type", "rel" ) ) ) );

        assertFalse( r.isError(), "partial success must NOT flag isError" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"status\":\"completed\"" ), body );
    }

    @Test
    void toolDescriptionDocumentsPageEntityBoundaryPolicy() {
        // The description is the highest-leverage place to communicate policy —
        // the model reads it before assembling arguments. Without this line the
        // gemini-cli-mcp-client kept calling curate_edges with page↔entity endpoints.
        final String desc = tool.definition().description();
        assertTrue( desc.toLowerCase().contains( "homogeneous" )
                || desc.toLowerCase().contains( "page→page" )
                || desc.toLowerCase().contains( "mixed page/entity" ),
                "description should warn about the page/entity boundary policy; got: " + desc );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< Object > ops51 = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops51.add( Map.of( "action", "confirm",
                "id", UUID.randomUUID().toString() ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "operations", ops51 ) );
        assertTrue( r.isError() );
    }

    @Test
    void upsertRejectsNestedEdgeShapeWithGuidance() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "upsert", "tag", "bad",
                        "edge", Map.of( "source_id", UUID.randomUUID().toString(),
                                         "target_id", UUID.randomUUID().toString(),
                                         "relationship_type", "rel" ) ) ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "top level" ), body );
        assertTrue( body.contains( "not nested under" ) && body.contains( "edge" ), body );
    }

    // Covers CurateEdgesTool.java:126-131 — doDelete happy path returns the id.
    @Test
    void deleteSuccessReturnsId() {
        final UUID id = UUID.randomUUID();
        when( ops.tryDeleteEdge( eq( id ), eq( "alice" ) ) ).thenReturn( Optional.empty() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "delete", "tag", "edge-del", "id", id.toString() ) ) ) );

        assertFalse( r.isError(), "happy-path delete must not flag isError" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"edge-del\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + id + "\"" ), body );
        assertTrue( body.contains( "\"action\":\"delete\"" ), body );
    }

    // Covers CurateEdgesTool.java:133-140 — doDeleteAndReject happy path returns the id.
    @Test
    void deleteAndRejectSuccessReturnsId() {
        final UUID id = UUID.randomUUID();
        when( ops.tryDeleteAndRejectEdge( eq( id ), eq( "alice" ), eq( "spurious co-mention" ) ) )
                .thenReturn( Optional.empty() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "operations", List.of( Map.of(
                        "action", "delete_and_reject",
                        "tag", "edge-dar",
                        "id", id.toString(),
                        "reason", "spurious co-mention" ) ) ) );

        assertFalse( r.isError(), "happy-path delete_and_reject must not flag isError" );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"tag\":\"edge-dar\"" ), body );
        assertTrue( body.contains( "\"id\":\"" + id + "\"" ), body );
        assertTrue( body.contains( "\"action\":\"delete_and_reject\"" ), body );
    }
}

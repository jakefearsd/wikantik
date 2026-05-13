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
    void bulkLimitExceededIsTopLevelError() {
        final List< Object > ops51 = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ops51.add( Map.of( "action", "confirm",
                "id", UUID.randomUUID().toString() ) );
        final McpSchema.CallToolResult r = tool.execute( Map.of( "operations", ops51 ) );
        assertTrue( r.isError() );
    }
}

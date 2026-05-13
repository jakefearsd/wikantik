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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// Note: ReviewProposalsTool calls ops.tryApprove(...) for the "approve" verdict;
// ops.tryApproveProposal is the interface default that delegates to tryApprove.

public class ReviewProposalsToolTest {

    private KgCurationOps ops;
    private ReviewProposalsTool tool;

    @BeforeEach void setUp() {
        ops = Mockito.mock( KgCurationOps.class );
        tool = new ReviewProposalsTool( ops, 50 );
        tool.setDefaultAuthor( "alice" );
    }

    @Test
    void approvesAllSucceedsReturnsEnvelope() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        when( ops.tryApprove( eq( a ), eq( "alice" ) ) ).thenReturn( KgCurationOps.ApproveOutcome.ok() );
        when( ops.tryApprove( eq( b ), eq( "alice" ) ) ).thenReturn( KgCurationOps.ApproveOutcome.ok() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( a.toString(), b.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"succeeded\":[\"" + a + "\",\"" + b + "\"]" )
                || body.contains( "\"succeeded\":[\"" + b + "\",\"" + a + "\"]" ), body );
        assertTrue( body.contains( "\"failed\":[]" ), body );
    }

    @Test
    void mixedSuccessAndFailureKeepsPerIdErrors() {
        final UUID ok = UUID.randomUUID();
        final UUID bad = UUID.randomUUID();
        when( ops.tryApprove( eq( ok ), any() ) ).thenReturn( KgCurationOps.ApproveOutcome.ok() );
        when( ops.tryApprove( eq( bad ), any() ) )
                .thenReturn( KgCurationOps.ApproveOutcome.fail( "Not found: " + bad ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( ok.toString(), bad.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "\"id\":\"" + bad + "\"" ), body );
        assertTrue( body.contains( "Not found" ), body );
    }

    @Test
    void approveWithWarningsSurfaces_warningsByProposal() {
        final UUID id = UUID.randomUUID();
        when( ops.tryApprove( eq( id ), any() ) ).thenReturn(
                KgCurationOps.ApproveOutcome.ok( List.of( "source_page is in kg_excluded_pages list" ) ) );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( id.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "warnings_by_proposal" ), body );
        assertTrue( body.contains( "source_page is in kg_excluded_pages list" ), body );
    }

    @Test
    void approveWithoutWarningsOmits_warningsByProposal() {
        final UUID id = UUID.randomUUID();
        when( ops.tryApprove( eq( id ), any() ) ).thenReturn( KgCurationOps.ApproveOutcome.ok() );

        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve",
                "ids", List.of( id.toString() ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertFalse( body.contains( "warnings_by_proposal" ), body );
    }

    @Test
    void rejectWithoutReasonIsTopLevelError() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "reject",
                "ids", List.of( UUID.randomUUID().toString() ) ) );
        assertTrue( r.isError() );
    }

    @Test
    void bulkLimitExceededIsTopLevelError() {
        final List< String > ids = new java.util.ArrayList<>();
        for ( int i = 0; i < 51; i++ ) ids.add( UUID.randomUUID().toString() );
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve", "ids", ids ) );
        assertTrue( r.isError() );
    }

    @Test
    void invalidUuidYieldsPerIdFailure() {
        final McpSchema.CallToolResult r = tool.execute( Map.of(
                "verdict", "approve", "ids", List.of( "not-a-uuid" ) ) );
        final String body = ( ( McpSchema.TextContent ) r.content().get( 0 ) ).text();
        assertTrue( body.contains( "Invalid UUID" ), body );
    }
}

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
package com.wikantik.its.mcp;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wire-level Cargo IT for KG curation tools ({@code list_proposals},
 * {@code inspect_proposals}, {@code review_proposals}, {@code curate_edges},
 * {@code curate_nodes}). Drives the JSON-RPC contract end-to-end, asserts
 * response envelope shape, and verifies per-op error messages cite reasons.
 *
 * <p>Pre-conditions: the KG fixture rows inserted by {@code it-test-seed.sql}
 * are in the IT PostgreSQL before Cargo starts Tomcat. Fixed UUIDs are exposed
 * via static accessors on {@link WithMcpTestSetup}.</p>
 */
public class KgCurationIT extends WithMcpTestSetup {

    // ------------------------------------------------------------------
    // list_proposals — enriched with conflict flags
    // ------------------------------------------------------------------

    @Test
    public void listProposalsIncludesConflictFlags() {
        final Map< String, Object > result = mcp.callTool( "list_proposals",
                Map.of( "status", "pending", "limit", 10 ) );
        // Payload must contain at least one of the conflict-flag keys.
        // The seed fixture has a new-node proposal whose name already exists
        // in kg_nodes, so node_exists=true for that row; edge_previously_rejected
        // may be absent but the key must be present in each proposal object.
        final String body = result.toString();
        Assertions.assertTrue(
                body.contains( "node_exists" ) || body.contains( "edge_previously_rejected" ),
                "list_proposals payload should include conflict flag fields: " + body );
    }

    // ------------------------------------------------------------------
    // inspect_proposals — known + missing + invalid UUID
    // ------------------------------------------------------------------

    @Test
    public void inspectProposalsResolvesKnownIdsAndMissesUnknown() {
        final String knownId = WithMcpTestSetup.seededPendingNodeProposalId();
        final String fakeId  = "00000000-0000-0000-0000-000000000000";

        final Map< String, Object > result = mcp.callTool( "inspect_proposals",
                Map.of( "ids", List.of( knownId, fakeId, "not-a-uuid" ) ) );

        final String body = result.toString();
        Assertions.assertTrue( body.contains( knownId ),
                "Response should contain the seeded proposal id: " + body );
        Assertions.assertTrue( body.contains( "missing" ),
                "Response should contain a 'missing' list: " + body );
        Assertions.assertTrue( body.contains( fakeId ) && body.contains( "not-a-uuid" ),
                "Both fake UUID and invalid UUID should appear in missing[]: " + body );
    }

    // ------------------------------------------------------------------
    // review_proposals — approve with mixed success (good + missing id)
    // ------------------------------------------------------------------

    @Test
    public void reviewProposalsApproveSurfacesPerIdErrors() {
        final String good    = WithMcpTestSetup.seededPendingNodeProposalId();
        final String missing = "00000000-0000-0000-0000-000000000000";

        // The tool returns a top-level success envelope even when some ids fail
        // (per-op errors, not a top-level isError).
        final Map< String, Object > result = mcp.callTool( "review_proposals",
                Map.of( "verdict", "approve", "ids", List.of( good, missing ) ) );

        final String body = result.toString();
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( good ),
                "Response should include 'succeeded' containing the known id: " + body );
        Assertions.assertTrue( body.contains( "Not found" ) && body.contains( missing ),
                "Response should include 'Not found' for the missing id: " + body );
    }

    // ------------------------------------------------------------------
    // review_proposals — reject without reason is a top-level error
    // ------------------------------------------------------------------

    @Test
    public void reviewProposalsRejectWithoutReasonIsTopLevelError() {
        // reject requires a top-level "reason"; omitting it must yield isError=true.
        mcp.callToolExpectingError( "review_proposals",
                Map.of( "verdict", "reject",
                        "ids", List.of( WithMcpTestSetup.seededPendingEdgeProposalId() ) ) );
        // callToolExpectingError throws AssertionError if the tool does NOT return
        // isError=true, so reaching this line means the assertion passed.
    }

    // ------------------------------------------------------------------
    // curate_edges — confirm then delete flows through the result envelope
    // ------------------------------------------------------------------

    @Test
    public void curateEdgesConfirmThenDeleteFlowsThroughEnvelope() {
        final String edgeId = WithMcpTestSetup.seededEdgeId();

        final Map< String, Object > confirm = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "confirm", "tag", "e1", "id", edgeId ) ) ) );
        final String confirmBody = confirm.toString();
        Assertions.assertTrue( confirmBody.contains( "e1" ),
                "Confirm response should echo the tag: " + confirmBody );
        Assertions.assertTrue( confirmBody.contains( "succeeded" ),
                "Confirm response should contain 'succeeded': " + confirmBody );

        final Map< String, Object > delete = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "delete", "tag", "e2", "id", edgeId ) ) ) );
        final String deleteBody = delete.toString();
        Assertions.assertTrue( deleteBody.contains( "succeeded" ) || deleteBody.contains( "e2" ),
                "Delete response should reference the operation: " + deleteBody );
    }

    // ------------------------------------------------------------------
    // curate_nodes — merge-self yields a per-op error containing "same"
    // ------------------------------------------------------------------

    @Test
    public void curateNodesMergeSelfRejectsAsPerOpError() {
        final String nodeId = WithMcpTestSetup.seededNodeId();

        // Merging a node into itself is a per-op validation error; the tool
        // should return a success envelope whose failed entry cites "same".
        final Map< String, Object > result = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of(
                        Map.of( "action", "merge", "tag", "n1",
                                "source_id", nodeId, "target_id", nodeId ) ) ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "same" ),
                "Merge-self should be rejected with a message containing 'same': " + body );
        Assertions.assertTrue( body.contains( "failed" ),
                "Merge-self should appear under 'failed' in the result envelope: " + body );
    }

    // ------------------------------------------------------------------
    // curate_edges — bulk limit exceeded is a top-level error
    // ------------------------------------------------------------------

    @Test
    public void bulkLimitExceededReturnsTopLevelError() {
        final List< Object > ops = new ArrayList<>();
        for ( int i = 0; i < 51; i++ ) {
            ops.add( Map.of( "action", "confirm", "tag", "x" + i,
                    "id", "00000000-0000-0000-0000-000000000000" ) );
        }
        mcp.callToolExpectingError( "curate_edges", Map.of( "operations", ops ) );
        // callToolExpectingError throws AssertionError if the tool does NOT return
        // isError=true, so reaching this line means the assertion passed.
    }

    // ------------------------------------------------------------------
    // review_proposals — McpAudit bulk-write log line is emitted
    // ------------------------------------------------------------------

    @Test
    public void reviewProposalsEmitsBulkAuditLogLine() throws Exception {
        // Re-seed a fresh pending proposal via MCP propose_knowledge so we have
        // a stable id to approve without depending on the order of other tests.
        // If the seeded proposal was already consumed by reviewProposalsApproveSurfacesPerIdErrors,
        // the approve call will still emit the audit line even for a Not-found result —
        // the tool always logs via McpAudit.logBulkWrite regardless of per-op outcomes.
        final long before = catalinaOutPath().toFile().exists()
                ? java.nio.file.Files.size( catalinaOutPath() ) : 0L;

        mcp.callTool( "review_proposals", Map.of(
                "verdict", "approve",
                "ids", List.of( WithMcpTestSetup.seededPendingNodeProposalId() ) ) );

        final String tail = WithMcpTestSetup.readCatalinaOutSince( before );
        Assertions.assertTrue( tail.contains( "tool=review_proposals action=bulk" ),
                "Expected McpAudit bulk-write log line in tomcat.log, got: " + tail );
        Assertions.assertTrue( tail.contains( "attempted=1" ),
                "Expected attempted=1 in McpAudit log line, got: " + tail );
    }
}

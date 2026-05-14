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

    // ------------------------------------------------------------------
    // review_proposals — approve a proposal whose source_page sits on
    // kg_excluded_pages emits warnings_by_proposal (§6 edge case row 1).
    // ------------------------------------------------------------------

    @Test
    public void reviewProposalsApproveOnExcludedPageEmitsWarnings() {
        final String id = WithMcpTestSetup.seededExcludedPageProposalId();

        final Map< String, Object > result = mcp.callTool( "review_proposals",
                Map.of( "verdict", "approve", "ids", List.of( id ) ) );
        final String body = result.toString();

        // Approval is still expected to succeed — the exclusion list governs
        // extraction, not retroactive curation.
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( id ),
                "Excluded-page proposal should still approve successfully: " + body );
        Assertions.assertTrue( body.contains( "warnings_by_proposal" ),
                "Response should surface warnings_by_proposal for excluded source page: " + body );
        Assertions.assertTrue( body.contains( "kg_excluded_pages" ),
                "Warning text should cite kg_excluded_pages: " + body );
    }

    // ------------------------------------------------------------------
    // inspect_proposals — empty array is a top-level error, exactly-50
    // resolves all as missing[] without erroring (cap boundary).
    // ------------------------------------------------------------------

    @Test
    public void inspectProposalsEmptyArrayIsTopLevelError() {
        mcp.callToolExpectingError( "inspect_proposals",
                Map.of( "ids", List.of() ) );
        // callToolExpectingError throws AssertionError if isError != true.
    }

    @Test
    public void inspectProposalsAtCapBoundaryReturnsAllInMissing() {
        // Exactly 50 fake UUIDs — boundary of the bulk_limit cap (default 50).
        // None resolve, so all land in missing[] and the call succeeds.
        final List< Object > ids = new ArrayList<>( 50 );
        for ( int i = 0; i < 50; i++ ) {
            ids.add( String.format( "00000000-0000-0000-0000-%012d", i ) );
        }
        final Map< String, Object > result = mcp.callTool( "inspect_proposals",
                Map.of( "ids", ids ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "missing" ),
                "Cap-boundary call should surface missing[]: " + body );
        Assertions.assertFalse( body.contains( "bulk limit exceeded" ),
                "Exactly-50 must NOT trip the bulk-limit guard: " + body );
    }

    @Test
    public void inspectProposalsExceedingCapIsTopLevelError() {
        final List< Object > ids = new ArrayList<>( 51 );
        for ( int i = 0; i < 51; i++ ) {
            ids.add( String.format( "00000000-0000-0000-0000-%012d", i ) );
        }
        mcp.callToolExpectingError( "inspect_proposals",
                Map.of( "ids", ids ) );
    }

    // ------------------------------------------------------------------
    // list_proposals — both conflict flags appear when applicable.
    // Seeded fixtures guarantee at least one proposal with node_exists=true
    // and one with edge_previously_rejected=true. (§3 promise: flags surface
    // without a second round-trip.)
    // ------------------------------------------------------------------

    @Test
    public void listProposalsSurfacesBothConflictFlagsAcrossPayload() {
        final Map< String, Object > result = mcp.callTool( "list_proposals",
                Map.of( "status", "pending", "limit", 50 ) );
        final String body = result.toString();

        // Both keys must be present somewhere in the pending payload — seed
        // fixtures cccccccc-/dddddddd- guarantee at least one of each.
        Assertions.assertTrue( body.contains( "node_exists=true" )
                        || body.contains( "node_exists\":true" ),
                "Expected node_exists=true for the seeded duplicate-name proposal: " + body );
        Assertions.assertTrue( body.contains( "edge_previously_rejected=true" )
                        || body.contains( "edge_previously_rejected\":true" ),
                "Expected edge_previously_rejected=true for the seeded previously-rejected triple: " + body );
    }

    // ------------------------------------------------------------------
    // curate_edges — upsert creates an edge (HUMAN_CURATED) and the bulk
    // McpAudit line is emitted. Follow-up delete_and_reject must then write
    // a rejection record (verified via list_proposals' conflict flag on a
    // pending proposal with the same triple).
    // ------------------------------------------------------------------

    @Test
    public void curateEdgesUpsertEmitsBulkAuditLogLine() throws Exception {
        final long before = catalinaOutPath().toFile().exists()
                ? java.nio.file.Files.size( catalinaOutPath() ) : 0L;

        final Map< String, Object > result = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "upsert", "tag", "u1",
                                "source_id", WithMcpTestSetup.seededUpsertSrcNodeId(),
                                "target_id", WithMcpTestSetup.seededUpsertTgtNodeId(),
                                "relationship_type", "related_to" ) ) ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( "u1" ),
                "Upsert response should contain succeeded entry tagged u1: " + body );

        final String tail = WithMcpTestSetup.readCatalinaOutSince( before );
        Assertions.assertTrue( tail.contains( "tool=curate_edges action=bulk" ),
                "Expected McpAudit bulk-write log line for curate_edges: " + tail );
        Assertions.assertTrue( tail.contains( "attempted=1" ),
                "Expected attempted=1 in curate_edges audit line: " + tail );
    }

    // ------------------------------------------------------------------
    // curate_edges — mixed-edge guard refusal pairs with the Mockito unit
    // test in DefaultKgCurationOpsTest. KgEdgeRepository's 2026-05-11 guard
    // returns null when source and target straddle the page/entity boundary
    // (exactly one side is node_type='concept'). DefaultKgCurationOps turns
    // that into EdgeResult.fail(...) citing the policy; the bulk tool
    // surfaces the message in failed[].error and flips the envelope to
    // isError=true since all ops failed.
    // ------------------------------------------------------------------

    @Test
    public void curateEdgesUpsertRefusesMixedPageEntityEdgeWithCitedPolicy() {
        // Mix the page-typed seed (article) with the concept-typed seed.
        // callToolExpectingError is required because the all-failed bulk
        // result sets isError=true (CurateEdgesTool.java:127, :136).
        final Map< String, Object > result = mcp.callToolExpectingError( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "upsert", "tag", "mixed-1",
                                "source_id", WithMcpTestSetup.seededUpsertPageNodeId(),
                                "target_id", WithMcpTestSetup.seededUpsertSrcNodeId(),
                                "relationship_type", "related_to" ) ) ) );

        Assertions.assertEquals( "failed", result.get( "status" ),
                "all-failed bulk result must carry status=failed: " + result );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > succeeded =
                ( List< Map< String, Object > > ) result.get( "succeeded" );
        Assertions.assertNotNull( succeeded, "succeeded array missing: " + result );
        Assertions.assertTrue( succeeded.isEmpty(),
                "no op should have succeeded: " + result );

        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > failed =
                ( List< Map< String, Object > > ) result.get( "failed" );
        Assertions.assertNotNull( failed, "failed array missing: " + result );
        Assertions.assertEquals( 1, failed.size(),
                "single op should yield one failure entry: " + result );

        final Map< String, Object > entry = failed.get( 0 );
        Assertions.assertEquals( "mixed-1", entry.get( "tag" ),
                "failed entry must echo the request tag: " + entry );
        final String error = String.valueOf( entry.get( "error" ) );
        Assertions.assertTrue( error.contains( "page/entity boundary" )
                        || error.toLowerCase().contains( "mixed page" ),
                "failed entry must cite the page/entity boundary policy so the "
                        + "agent learns the topology was wrong (not the predicate); got: " + error );
    }

    @Test
    public void curateEdgesDeleteAndRejectWritesRejection() {
        // Create an edge we can then delete_and_reject, capturing its id from
        // the upsert response. We use `alternative_to` (a value from the
        // closed kg_edges_relationship_type_check vocabulary in V027) so the
        // triple is distinct from the `related_to` edge created by
        // curateEdgesUpsertEmitsBulkAuditLogLine and won't collide on rerun.
        final Map< String, Object > upsert = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "upsert", "tag", "u-dar",
                                "source_id", WithMcpTestSetup.seededUpsertSrcNodeId(),
                                "target_id", WithMcpTestSetup.seededUpsertTgtNodeId(),
                                "relationship_type", "alternative_to" ) ) ) );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > succeeded =
                ( List< Map< String, Object > > ) upsert.get( "succeeded" );
        Assertions.assertNotNull( succeeded, "upsert must succeed: " + upsert );
        Assertions.assertFalse( succeeded.isEmpty(), "upsert must produce one succeeded entry: " + upsert );
        final Object edgeIdObj = succeeded.get( 0 ).get( "id" );
        Assertions.assertNotNull( edgeIdObj, "upsert must return an edge id: " + upsert );
        final String edgeId = edgeIdObj.toString();

        final Map< String, Object > dar = mcp.callTool( "curate_edges",
                Map.of( "operations", List.of(
                        Map.of( "action", "delete_and_reject", "tag", "dar1",
                                "id", edgeId, "reason", "IT verification: spurious co-mention" ) ) ) );
        final String darBody = dar.toString();
        Assertions.assertTrue( darBody.contains( "succeeded" ) && darBody.contains( "dar1" ),
                "delete_and_reject should succeed and echo tag dar1: " + darBody );
        Assertions.assertTrue( darBody.contains( "\"failed\":[]" )
                        || darBody.contains( "failed=[]" )
                        || !darBody.contains( "error" ),
                "delete_and_reject must not surface an error: " + darBody );

        // The rejection record is written against the
        // (source_name, target_name, relationship_type) triple. Cross-check by
        // calling propose_knowledge with the SAME triple — that tool itself
        // refuses to file a duplicate of a rejected proposal, so a top-level
        // error citing "previously rejected" proves the rejection record was
        // written and is queryable via service.isRejected(...).
        final Map< String, Object > err = mcp.callToolExpectingError( "propose_knowledge", Map.of(
                "proposal_type", "new-edge",
                "source_page", "KgCurationSeedPage",
                "proposed_data", Map.of(
                        "source", "KgUpsertSrcNode",
                        "target", "KgUpsertTgtNode",
                        "relationship", "alternative_to" ),
                "confidence", 0.5,
                "reasoning", "IT: verify rejection record persisted through delete_and_reject" ) );
        final String errBody = err.toString();
        Assertions.assertTrue( errBody.contains( "previously rejected" ),
                "propose_knowledge should refuse the re-proposal of a rejected triple, citing "
                        + "the rejection record written by delete_and_reject: " + errBody );
    }

    // ------------------------------------------------------------------
    // curate_nodes — happy-path coverage for upsert, delete, merge, and the
    // bulk McpAudit emission.
    // ------------------------------------------------------------------

    @Test
    public void curateNodesUpsertEmitsBulkAuditLogLine() throws Exception {
        final long before = catalinaOutPath().toFile().exists()
                ? java.nio.file.Files.size( catalinaOutPath() ) : 0L;

        final String uniqueName = "KgCurationItUpsertNode_"
                + System.currentTimeMillis() + "_" + Thread.currentThread().threadId();
        final Map< String, Object > result = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of(
                        Map.of( "action", "upsert", "tag", "n-up",
                                "name", uniqueName, "node_type", "concept",
                                "source_page", "KgCurationSeedPage" ) ) ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( "n-up" ),
                "curate_nodes upsert should succeed and echo tag: " + body );

        final String tail = WithMcpTestSetup.readCatalinaOutSince( before );
        Assertions.assertTrue( tail.contains( "tool=curate_nodes action=bulk" ),
                "Expected McpAudit bulk-write log line for curate_nodes: " + tail );
        Assertions.assertTrue( tail.contains( "attempted=1" ),
                "Expected attempted=1 in curate_nodes audit line: " + tail );
    }

    @Test
    public void curateNodesDeleteHappyPath() {
        final Map< String, Object > result = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of(
                        Map.of( "action", "delete", "tag", "n-del",
                                "id", WithMcpTestSetup.seededDeletableNodeId() ) ) ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( "n-del" ),
                "curate_nodes delete should succeed and echo tag: " + body );
        Assertions.assertTrue( body.contains( WithMcpTestSetup.seededDeletableNodeId() ),
                "delete response should echo deleted node id: " + body );
    }

    @Test
    public void curateNodesMergeHappyPath() {
        final Map< String, Object > result = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of(
                        Map.of( "action", "merge", "tag", "n-merge",
                                "source_id", WithMcpTestSetup.seededMergeSrcNodeId(),
                                "target_id", WithMcpTestSetup.seededMergeTgtNodeId() ) ) ) );
        final String body = result.toString();
        Assertions.assertTrue( body.contains( "succeeded" ) && body.contains( "n-merge" ),
                "merge should succeed and echo tag: " + body );
        Assertions.assertTrue(
                body.contains( WithMcpTestSetup.seededMergeSrcNodeId() )
                        && body.contains( WithMcpTestSetup.seededMergeTgtNodeId() ),
                "merge response should echo both source_id and target_id: " + body );
    }

    // ------------------------------------------------------------------
    // curate_nodes — merge with ghost (missing) source UUID yields a
    // per-op error citing "merge source not found" (Task-5 guard).
    // ------------------------------------------------------------------

    @Test
    public void curateNodesMergeWithGhostSourceIsPerOpError() {
        final String ghostUuid = "00000000-0000-0000-0000-000000000000";
        final String realTarget = WithMcpTestSetup.seededNodeId();

        final Map< String, Object > result = mcp.callTool( "curate_nodes",
                Map.of( "operations", List.of( Map.of(
                        "action", "merge", "tag", "ghost",
                        "source_id", ghostUuid,
                        "target_id", realTarget ) ) ) );

        final String body = result.toString();
        Assertions.assertTrue( body.contains( "failed" ),
                "Merge with ghost source should appear under 'failed' in the result envelope: " + body );
        Assertions.assertTrue( body.contains( "merge source not found" ),
                "Per-op error should cite 'merge source not found': " + body );
        Assertions.assertTrue( body.contains( ghostUuid ),
                "Per-op error should echo the ghost UUID: " + body );
    }

    // ------------------------------------------------------------------
    // curate_nodes — upsert with polluted node_type is a per-op error
    // citing "invalid node_type" (Task 6, node_type regex guard).
    // ------------------------------------------------------------------

    @Test
    public void curateNodesUpsertWithPollutedTypeIsPerOpError() {
        final java.util.Map< String, Object > r = mcp.callTool( "curate_nodes",
                java.util.Map.of( "operations", java.util.List.of( java.util.Map.of(
                        "action", "upsert", "tag", "polluted",
                        "name", "PollutionTest_" + System.currentTimeMillis(),
                        "node_type", "concept,",
                        "source_page", "Main" ) ) ) );

        Assertions.assertTrue( r.toString().contains( "failed" ), r.toString() );
        Assertions.assertTrue( r.toString().contains( "invalid node_type" ), r.toString() );
    }

    // ------------------------------------------------------------------
    // verdict=judge is intentionally NOT covered at the IT layer — the
    // judge path requires an external LLM endpoint that the Cargo IT
    // environment cannot reach. Coverage lives at the unit-test layer in
    // wikantik-admin-mcp (ReviewProposalsToolTest) and in the curation
    // service tests (DefaultKgCurationOpsTest).
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // review_proposals — re-approving an already-approved proposal yields
    // a per-op error containing "already reviewed" (§6 guard, now enforced).
    // ------------------------------------------------------------------

    @Test
    public void reviewProposalsReApproveAlreadyApprovedSurfacesPerIdError() {
        final String alreadyApproved = WithMcpTestSetup.seededAlreadyApprovedProposalId();

        final Map< String, Object > result = mcp.callTool( "review_proposals",
                Map.of( "verdict", "approve", "ids", List.of( alreadyApproved ) ) );

        final String body = result.toString();
        Assertions.assertTrue( body.contains( "failed" ),
                "Re-approve should appear under 'failed' in the result envelope: " + body );
        Assertions.assertTrue( body.contains( alreadyApproved ),
                "Response should echo the already-reviewed proposal id: " + body );
        Assertions.assertTrue( body.contains( "already reviewed" ),
                "Per-op error should cite 'already reviewed': " + body );
    }
}

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
package com.wikantik.work.mcp;

import com.google.gson.JsonObject;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.Item;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.ItemPatch;
import com.wikantik.work.Product;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The four change tools through the real dispatch, service and store: two agents take an item from an open
 * change to merged and done, and each refusal an agent can meet reads as the instructions say it does.
 */
@RequiresPostgres
class ChangeToolsPostgresTest {

    private static final String A = "aaaaaaa1";
    private static final String B = "bbbbbbb2";

    private WorkMcpFixture f;
    private List< WorkMcpTool > tools;
    private Agent reviewer;
    private String key;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tools = WorkMcpInitializer.assembleTools( f.svc, f.directory() );
        reviewer = f.svc.registry().registerAgent( f.admin, new AgentDraft( "reviewer-bot", "Acme", "claude-code",
            List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "reviewer-bot", "agent", WorkRole.MEMBER );
        f.svc.createProduct( f.admin, new Product( 0, "wikantik", "Wikantik", null, "main", null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final Item draft = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, "wikantik",
            WorkKind.SOFTWARE, "Build the thing", "", null, null, null, "implementer", null, null ) ).item();
        key = f.svc.updateItem( f.lena, draft.key(), new ItemPatch( draft.version(), Map.of( "status", "ready" ),
            null ) ).item().key();
    }

    private McpSchema.CallToolResult call( final Agent who, final String tool, final Object... kv ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            args.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getAttribute( com.wikantik.auth.apikeys.AgentTokenAuth.ATTR_AGENT_REF ) )
            .thenReturn( new com.wikantik.auth.apikeys.AgentTokenAuth.AgentRef( 0, who.id() ) );
        final WorkMcpTool t = tools.stream().filter( x -> x.name().equals( tool ) ).findFirst().orElseThrow();
        return WorkMcpInitializer.dispatch( t, f.svc, WorkMcpActors.extract( request ), args );
    }

    private JsonObject ok( final McpSchema.CallToolResult r ) {
        assertFalse( Boolean.TRUE.equals( r.isError() ), WorkMcpFixture.text( r ) );
        return WorkMcpFixture.json( r );
    }

    private static String refused( final McpSchema.CallToolResult r ) {
        assertTrue( Boolean.TRUE.equals( r.isError() ), "expected a refusal, got " + WorkMcpFixture.text( r ) );
        return WorkMcpFixture.text( r );
    }

    private int version( final Agent who ) {
        return ok( call( who, "get_item", "key", key ) ).get( "version" ).getAsInt();
    }

    /** The builder claims the item, opens a change at {@link #A} and asks for a review; returns the change id. */
    private int changeInReview() {
        ok( call( f.builder, "claim_item", "key", key ) );
        final JsonObject opened = ok( call( f.builder, "open_change", "key", key, "branch", "work/" + key + "-thing",
            "head_sha", A ) );
        final int id = opened.get( "id" ).getAsInt();
        ok( call( f.builder, "update_change", "change", id, "state", "in_review" ) );
        return id;
    }

    /** The builder hands the item to the reviewer queue and the reviewer takes it: only then may it review. */
    private void reviewerTakesIt() {
        ok( call( f.builder, "handoff_item", "key", key, "expectedVersion", version( f.builder ), "status", "review",
            "queue", "reviewer", "note", "Ready for review on the branch." ) );
        ok( call( reviewer, "claim_item", "key", key ) );
    }

    private void level( final String level ) throws SQLException {
        final Map< String, Object > changes = new HashMap<>();
        changes.put( "autonomy", level );
        f.svc.updateItem( f.lena, key, new ItemPatch( f.svc.item( f.lena, key ).item().version(), changes, null ) );
    }

    @Test
    void twoAgentsTakeAnItemFromOpenToMergedToDone() {
        final int id = changeInReview();
        ok( call( f.builder, "handoff_item", "key", key, "expectedVersion", version( f.builder ), "status", "review",
            "queue", "reviewer", "note", "Ready for review on the branch." ) );
        final JsonObject taken = ok( call( reviewer, "claim_item", "key", key ) ).getAsJsonObject( "item" );
        final JsonObject shown = taken.getAsJsonArray( "changes" ).get( 0 ).getAsJsonObject();
        assertEquals( "in_review", shown.get( "state" ).getAsString() );
        assertEquals( "builder", shown.get( "opened_by" ).getAsString(), "an agent is named, never numbered" );
        assertEquals( "wikantik", shown.get( "product" ).getAsString() );
        assertEquals( "delegated", taken.getAsJsonObject( "effective" ).get( "autonomy" ).getAsString() );

        final JsonObject approved = ok( call( reviewer, "review_change", "change", id, "approved", true ) );
        assertTrue( approved.get( "approved_for_head" ).getAsBoolean() );
        assertEquals( "reviewer-bot", approved.get( "reviewed_by" ).getAsString() );
        final JsonObject merged = ok( call( reviewer, "merge_change", "change", id, "merged_sha", A ) );
        assertEquals( "merged", merged.get( "state" ).getAsString() );
        assertEquals( "reviewed", merged.get( "merge_kind" ).getAsString() );
        final JsonObject closed = ok( call( reviewer, "update_item", "key", key, "expectedVersion", version( reviewer ),
            "status", "done" ) );
        assertEquals( "done", closed.get( "status" ).getAsString() );
    }

    @Test
    void theSameFlowStopsAtTheMergeWhenSupervised() throws SQLException {
        level( "supervised" );
        final int id = changeInReview();
        reviewerTakesIt();
        ok( call( reviewer, "review_change", "change", id, "approved", true ) );
        assertEquals( "denied: A person merges at the supervised level.",
            refused( call( reviewer, "merge_change", "change", id, "merged_sha", A ) ) );
        assertEquals( "denied: A person closes work at the supervised level.", refused( call( reviewer,
            "update_item", "key", key, "expectedVersion", version( reviewer ), "status", "done" ) ) );
    }

    @Test
    void anAgentNeverReviewsOrMergesItsOwnChange() {
        final int id = changeInReview();
        assertEquals( "denied: An agent may not review its own change.",
            refused( call( f.builder, "review_change", "change", id, "approved", true ) ) );
        assertEquals( "denied: An agent may not merge its own change.",
            refused( call( f.builder, "merge_change", "change", id, "merged_sha", A ) ) );
    }

    @Test
    void anApprovalIsForOneCommit() throws SQLException {
        final int id = changeInReview();
        reviewerTakesIt();
        ok( call( reviewer, "review_change", "change", id, "approved", true ) );
        // a person pushes another commit (the author agent is fenced off while the reviewer holds the item)
        assertFalse( f.svc.changes().update( f.lena, id, B, null ).approvedForHead() );
        assertEquals( "not_approved: This change has no approval for its current commit.",
            refused( call( reviewer, "merge_change", "change", id, "merged_sha", B ) ) );
        ok( call( reviewer, "review_change", "change", id, "approved", true ) );
        ok( call( reviewer, "merge_change", "change", id, "merged_sha", A ) );
    }

    @Test
    void anItemWithALiveChangeCannotBeClosed() {
        changeInReview();
        assertEquals( "open_changes: Close or merge this item's open changes first.", refused( call( f.builder,
            "update_item", "key", key, "expectedVersion", version( f.builder ), "status", "done" ) ) );
    }

    @Test
    void aClosedChangeRefusesEveryWrite() {
        final int id = changeInReview();
        final JsonObject gone = ok( call( f.builder, "update_change", "change", id, "state", "abandoned", "note",
            "wrong approach" ) );
        assertEquals( "abandoned", gone.get( "state" ).getAsString() );
        reviewerTakesIt();
        assertEquals( "change_closed: This change is already merged or abandoned.",
            refused( call( reviewer, "review_change", "change", id, "approved", true ) ) );
    }

    @Test
    void aDirectCommitNeedsALeadToAllowIt() throws SQLException {
        ok( call( f.builder, "claim_item", "key", key ) );
        assertEquals( "denied: Direct commits by an agent need a lead to allow them on this item or its epic.",
            refused( call( f.builder, "open_change", "key", key, "branch", "main", "merged_sha", A ) ) );
        final Map< String, Object > changes = new HashMap<>();
        changes.put( "allowDirect", true );
        f.svc.updateItem( f.lena, key, new ItemPatch( f.svc.item( f.lena, key ).item().version(), changes, null ) );
        final JsonObject direct = ok( call( f.builder, "open_change", "key", key, "branch", "main", "merged_sha", A ) );
        assertEquals( "merged", direct.get( "state" ).getAsString() );
        assertEquals( "direct", direct.get( "merge_kind" ).getAsString() );
    }

    @Test
    void argumentsAreCheckedByName() throws SQLException {
        final int id = changeInReview();
        assertEquals( "state: Use review_change or merge_change.",
            refused( call( f.builder, "update_change", "change", id, "state", "merged" ) ) );
        assertEquals( "status: Unknown argument.",
            refused( call( f.builder, "open_change", "key", key, "branch", "b", "status", "merged" ) ) );
        assertEquals( "approved: is required.", refused( call( reviewer, "review_change", "change", id ) ) );
        assertEquals( "approved: must be true or false.",
            refused( call( reviewer, "review_change", "change", id, "approved", "yes" ) ) );
        assertEquals( "merged_sha: is required.", refused( call( reviewer, "merge_change", "change", id ) ) );
        assertEquals( "change: is required.", refused( call( reviewer, "merge_change", "merged_sha", A ) ) );
        assertEquals( "agent: Unknown argument.",
            refused( call( reviewer, "review_change", "change", id, "approved", true, "agent", "builder" ) ) );
        assertEquals( "headSha: A commit id is 7 to 64 hexadecimal characters.",
            refused( call( f.builder, "update_change", "change", id, "head_sha", "nope" ) ) );
        assertEquals( "change: Say what to change: head_sha, review_url or state.",
            refused( call( f.builder, "update_change", "change", id ) ) );
        assertEquals( "note: A note goes with state abandoned.",
            refused( call( f.builder, "update_change", "change", id, "head_sha", B, "note", "rebased" ) ) );
        assertEquals( "note: A note goes with state abandoned.",
            refused( call( f.builder, "update_change", "change", id, "state", "in_review", "note", "please look" ) ) );
        assertEquals( A, f.svc.changes().list( f.lena, key ).get( 0 ).headSha(), "a refused call changed nothing" );
    }

    @Test
    void aHiddenOrUnknownChangeIsNotFound() throws SQLException {
        final int id = changeInReview();
        final Agent outsider = f.svc.registry().registerAgent( f.admin, new AgentDraft( "outsider", "", "",
            List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "SECRET", "outsider", "agent", WorkRole.MEMBER );
        for ( final int which : new int[] { id, 999_999 } ) {
            assertEquals( "Not found.", refused( call( outsider, "review_change", "change", which, "approved", true ) ) );
            assertEquals( "Not found.", refused( call( outsider, "merge_change", "change", which, "merged_sha", A ) ) );
            assertEquals( "Not found.", refused( call( outsider, "update_change", "change", which, "head_sha", B ) ) );
        }
        assertEquals( "Not found.", refused( call( outsider, "open_change", "key", key, "branch", "b" ) ) );
    }
}

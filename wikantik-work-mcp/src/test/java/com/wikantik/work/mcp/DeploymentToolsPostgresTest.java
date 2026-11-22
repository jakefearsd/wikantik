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
import com.wikantik.work.DeploymentDraft;
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
 * The deployment tools through the real dispatch, service and store, and the whole incident path: under a
 * standing epic that is autonomous and done only in production, with a product agents may release, two agents
 * take an item from nothing to verified in production with no person in the path, and it then waits for one.
 */
@RequiresPostgres
class DeploymentToolsPostgresTest {

    private static final String SHA = "aaaaaaa1";

    private WorkMcpFixture f;
    private List< WorkMcpTool > tools;
    private Agent reviewer;
    private String epic;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tools = WorkMcpInitializer.assembleTools( f.svc, f.directory() );
        reviewer = f.svc.registry().registerAgent( f.admin, new AgentDraft( "reviewer-bot", "Acme", "claude-code",
            List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "reviewer-bot", "agent", WorkRole.MEMBER );
        product( Autonomy.AUTONOMOUS, DeployGate.AGENT );
        final Item e = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE,
            "Production incidents", "", null, null, null, null, null, null ) ).item();
        final Map< String, Object > standing = new HashMap<>();
        standing.put( "autonomy", "autonomous" );
        standing.put( "doneMeans", "in_production" );
        f.svc.updateItem( f.lena, e.key(), new ItemPatch( e.version(), standing, null ) );
        epic = e.key();
    }

    private void product( final Autonomy ceiling, final DeployGate gate ) throws SQLException {
        final List< Product > existing = f.svc.listProducts( f.admin );
        if ( existing.isEmpty() ) {
            f.svc.createProduct( f.admin, new Product( 0, "wikantik", "Wikantik", null, "main", null, ceiling, gate, null ) );
        } else {
            final Product p = existing.get( 0 );
            f.svc.updateProduct( f.admin, new Product( p.id(), p.key(), p.name(), p.repoUrl(), p.defaultBranch(),
                p.wikiCluster(), ceiling, gate, p.archivedAt() ) );
        }
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

    private int version( final Agent who, final String key ) {
        return ok( call( who, "get_item", "key", key ) ).get( "version" ).getAsInt();
    }

    /** The builder files a story under the standing epic, approves it, does the work; the reviewer merges it. */
    private String mergedStory() {
        final String key = ok( call( f.builder, "create_item", "project", "PLAT", "level", "story", "title",
            "Indexer stalled", "parentKey", epic, "productKey", "wikantik", "queue", "implementer" ) ).get( "key" ).getAsString();
        ok( call( f.builder, "update_item", "key", key, "expectedVersion", version( f.builder, key ), "status", "ready" ) );
        ok( call( f.builder, "claim_item", "key", key ) );
        final int change = ok( call( f.builder, "open_change", "key", key, "branch", "work/" + key + "-indexer",
            "head_sha", SHA ) ).get( "id" ).getAsInt();
        ok( call( f.builder, "update_change", "change", change, "state", "in_review" ) );
        ok( call( f.builder, "handoff_item", "key", key, "expectedVersion", version( f.builder, key ), "status",
            "review", "queue", "reviewer", "note", "Fix is on the branch." ) );
        ok( call( reviewer, "claim_item", "key", key ) );
        ok( call( reviewer, "review_change", "change", change, "approved", true ) );
        ok( call( reviewer, "merge_change", "change", change, "merged_sha", SHA ) );
        return key;
    }

    @Test
    void theIncidentPathEndToEnd() throws SQLException {
        final String key = mergedStory();
        assertEquals( "awaiting_deployment: This closes when a deployment that carries it is verified.",
            refused( call( reviewer, "update_item", "key", key, "expectedVersion", version( reviewer, key ),
                "status", "done" ) ), "merged is not done under this epic" );

        final JsonObject requested = ok( call( reviewer, "request_deployment", "product", "wikantik", "version",
            "2.5.26", "items", List.of( key ) ) );
        final int id = requested.get( "id" ).getAsInt();
        assertEquals( "requested", requested.get( "state" ).getAsString() );
        assertEquals( "production", requested.get( "environment" ).getAsString() );
        assertEquals( "reviewer-bot", requested.get( "requested_by" ).getAsString() );
        assertEquals( key, requested.getAsJsonArray( "items" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertTrue( requested.get( "may_approve" ).getAsBoolean(), "the product lets an agent release" );

        ok( call( reviewer, "update_deployment", "deployment", id, "state", "approved" ) );
        ok( call( reviewer, "update_deployment", "deployment", id, "state", "running" ) );
        assertEquals( "evidence: Attach the health and smoke-test output before marking this verified.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "verified" ) ) );
        final JsonObject verified = ok( call( reviewer, "update_deployment", "deployment", id, "state", "verified",
            "evidence", "health 200\nsmoke ok" ) );
        assertEquals( "verified", verified.get( "state" ).getAsString() );
        assertEquals( "health 200\nsmoke ok", ok( call( f.builder, "get_deployment", "deployment", id ) )
            .get( "evidence" ).getAsString() );

        final JsonObject closed = ok( call( reviewer, "update_item", "key", key, "expectedVersion",
            version( reviewer, key ), "status", "done" ) );
        assertEquals( "done", closed.get( "status" ).getAsString() );
        assertEquals( "2.5.26", closed.getAsJsonArray( "shipped_in" ).get( 0 ).getAsJsonObject().get( "version" ).getAsString() );
        assertTrue( f.svc.policyOf( f.lena, key ).ack().waiting(), "no person was in the path: one must acknowledge it" );

        final JsonObject fix = ok( call( reviewer, "request_deployment", "product", "wikantik", "version", "2.5.27",
            "items", List.of( key ), "remediates", id ) );
        assertEquals( id, fix.get( "remediates" ).getAsInt() );
        assertEquals( id, ok( call( f.builder, "get_deployment", "deployment", fix.get( "id" ).getAsInt() ) )
            .get( "remediates" ).getAsInt(), "get_deployment shows what it fixes" );
        assertTrue( ok( call( f.builder, "get_deployment", "deployment", id ) ).get( "remediates" ).isJsonNull() );
    }

    @Test
    void aFixForwardOfADeploymentThePlatAgentCannotSeeNamesNoId() throws SQLException {
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        final Item ops = f.svc.createItem( f.admin, new ItemDraft( "OPS", WorkLevel.TASK, null, "wikantik",
            WorkKind.SOFTWARE, "Ops work", "", null, null, null, null, null, null ) ).item();
        final Item plat = f.svc.createItem( f.admin, new ItemDraft( "PLAT", WorkLevel.TASK, null, "wikantik",
            WorkKind.SOFTWARE, "Plat work", "", null, null, null, null, null, null ) ).item();
        final long target = f.svc.deployments().request( f.admin, new DeploymentDraft( "wikantik", "production",
            "3.0.0", List.of( ops.key() ), null ) ).deployment().id();
        f.svc.deployments().approve( f.admin, target, null );
        f.svc.deployments().start( f.admin, target );
        f.svc.deployments().verify( f.admin, target, "health 200" );
        final long fix = f.svc.deployments().request( f.admin, new DeploymentDraft( "wikantik", "production", "3.0.1",
            List.of( plat.key() ), target ) ).deployment().id();
        assertTrue( ok( call( f.builder, "get_deployment", "deployment", ( int ) fix ) ).get( "remediates" ).isJsonNull(),
            "the target carries only OPS's work: the agent is not shown its number" );
        assertEquals( target, f.svc.deployments().deployment( f.admin, fix ).deployment().remediatesId() );
    }

    @Test
    void theSamePathStopsAtApprovalUnderAHumanGate() throws SQLException {
        product( Autonomy.AUTONOMOUS, DeployGate.HUMAN );
        final String key = mergedStory();
        final JsonObject requested = ok( call( reviewer, "request_deployment", "product", "wikantik", "version",
            "2.5.26", "items", List.of( key ) ) );
        assertFalse( requested.get( "may_approve" ).getAsBoolean() );
        final int id = requested.get( "id" ).getAsInt();
        assertEquals( "denied: A person approves deployments of this product.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "approved" ) ) );
        assertEquals( "bad_transition: A deployment that is requested cannot become running.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "running" ) ) );
        f.svc.deployments().approve( f.lena, id, "go" );
        assertEquals( "running", ok( call( reviewer, "update_deployment", "deployment", id, "state", "running" ) )
            .get( "state" ).getAsString(), "once a person approved, the agent runs it" );
    }

    @Test
    void argumentsAreCheckedByName() {
        final String key = mergedStory();
        assertEquals( "items: must be a list of item keys.", refused( call( reviewer, "request_deployment", "product",
            "wikantik", "version", "2.5.26", "items", key ) ) );
        assertEquals( "items: A deployment carries at least one item.", refused( call( reviewer, "request_deployment",
            "product", "wikantik", "version", "2.5.26", "items", List.of() ) ) );
        assertEquals( "state: Unknown argument.", refused( call( reviewer, "request_deployment", "product", "wikantik",
            "version", "2.5.26", "items", List.of( key ), "state", "approved" ) ) );
        final int id = ok( call( reviewer, "request_deployment", "product", "wikantik", "version", "2.5.26", "items",
            List.of( key ) ) ).get( "id" ).getAsInt();
        assertEquals( "state: is required.", refused( call( reviewer, "update_deployment", "deployment", id ) ) );
        assertEquals( "state: Use approved, rejected, running, verified, failed or rolled_back.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "requested" ) ) );
        assertEquals( "evidence: Evidence goes with verified.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "approved", "evidence", "x" ) ) );
        assertEquals( "note: A note goes with approved, rejected, failed or rolled_back.",
            refused( call( reviewer, "update_deployment", "deployment", id, "state", "running", "note", "starting" ) ) );
        assertEquals( "rejected", ok( call( reviewer, "update_deployment", "deployment", id, "state", "rejected",
            "note", "requested by mistake" ) ).get( "state" ).getAsString(), "the requester withdraws it" );
        assertEquals( "deployment: is required.", refused( call( reviewer, "get_deployment" ) ) );
    }

    @Test
    void aHiddenOrUnknownDeploymentIsNotFound() throws SQLException {
        final String key = mergedStory();
        final int id = ok( call( reviewer, "request_deployment", "product", "wikantik", "version", "2.5.26", "items",
            List.of( key ) ) ).get( "id" ).getAsInt();
        final Agent outsider = f.svc.registry().registerAgent( f.admin, new AgentDraft( "outsider", "", "",
            List.of( "reviewer" ) ) ).agent();
        f.svc.putMember( f.admin, "SECRET", "outsider", "agent", WorkRole.MEMBER );
        for ( final int which : new int[] { id, 999_999 } ) {
            assertEquals( "Not found.", refused( call( outsider, "get_deployment", "deployment", which ) ) );
            assertEquals( "Not found.", refused( call( outsider, "update_deployment", "deployment", which, "state",
                "approved" ) ) );
        }
        assertEquals( "Not found.", refused( call( outsider, "request_deployment", "product", "wikantik", "version",
            "2.5.26", "items", List.of( key ) ) ) );
    }
}

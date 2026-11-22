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
package com.wikantik.rest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.Deployment;
import com.wikantik.work.DeploymentDraft;
import com.wikantik.work.DeploymentEvent;
import com.wikantik.work.DeploymentState;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkDeploymentsService;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/** The deployment routes: what they pass to the service and how its answers go on the wire. */
class WorkDeploymentResourceTest {

    private static final Instant T0 = Instant.parse( "2026-11-17T10:00:00Z" );

    private WorkService svc;
    private WorkDeploymentsService deployments;
    private WorkDeploymentResource servlet;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class );
        deployments = Mockito.mock( WorkDeploymentsService.class );
        Mockito.when( svc.deployments() ).thenReturn( deployments );
        Mockito.when( svc.registry() ).thenReturn( Mockito.mock( com.wikantik.work.WorkRegistryService.class ) );
        servlet = WorkHttp.wire( new WorkDeploymentResource(), svc, ALICE );
    }

    /** Requested by agent 7, approved by bob, carrying the one item the caller can see. */
    private static DeploymentView view( final DeploymentState state, final String evidence ) {
        final Deployment d = new Deployment( 4, 9, "production", "2.5.26", state, "codex", 7L, "bob", null, T0, T0,
            null, null, null, evidence, null, List.of( 1L ) );
        return new DeploymentView( d, "wikantik", List.of( WorkHttp.item( "PLAT-1", null, 1 ) ), true, false );
    }

    private WorkHttp.Result call( final String method, final String path, final Map< String, String > q,
                                  final String body ) throws Exception {
        return WorkHttp.call( servlet, method, "/api/work/deployments", path, q, body );
    }

    @Test
    void requestsADeploymentWith201() throws Exception {
        Mockito.when( deployments.request( eq( ALICE ), any() ) ).thenReturn( view( DeploymentState.REQUESTED, null ) );
        final WorkHttp.Result r = call( "POST", null, Map.of(),
            "{\"product\":\"wikantik\",\"version\":\"2.5.26\",\"environment\":\"staging\",\"items\":[\"PLAT-1\",\"PLAT-2\"]}" );
        assertEquals( 201, r.status() );
        final ArgumentCaptor< DeploymentDraft > d = ArgumentCaptor.forClass( DeploymentDraft.class );
        Mockito.verify( deployments ).request( eq( ALICE ), d.capture() );
        assertEquals( new DeploymentDraft( "wikantik", "staging", "2.5.26", List.of( "PLAT-1", "PLAT-2" ) ), d.getValue() );
    }

    @Test
    void passesRemediatesToTheDraft() throws Exception {
        Mockito.when( deployments.request( eq( ALICE ), any() ) ).thenReturn( view( DeploymentState.REQUESTED, null ) );
        final WorkHttp.Result r = call( "POST", null, Map.of(),
            "{\"product\":\"wikantik\",\"version\":\"2.5.27\",\"items\":[\"PLAT-1\"],\"remediates\":4}" );
        assertEquals( 201, r.status() );
        final ArgumentCaptor< DeploymentDraft > d = ArgumentCaptor.forClass( DeploymentDraft.class );
        Mockito.verify( deployments ).request( eq( ALICE ), d.capture() );
        assertEquals( 4L, d.getValue().remediatesId() );
    }

    @Test
    void aNonNumericRemediatesIs400() throws Exception {
        for ( final String bad : List.of( "\"four\"", "4.5", "-1", "0", "true", "[4]", "99999999999999999999" ) ) {
            final WorkHttp.Result r = call( "POST", null, Map.of(),
                "{\"product\":\"wikantik\",\"version\":\"1\",\"items\":[\"PLAT-1\"],\"remediates\":" + bad + "}" );
            assertEquals( 400, r.status(), bad );
            assertEquals( "remediates", r.json().getAsJsonObject().get( "field" ).getAsString(), bad );
            assertEquals( "remediates must be a deployment number.",
                r.json().getAsJsonObject().get( "message" ).getAsString(), bad );
        }
        Mockito.verifyNoInteractions( deployments );
    }

    @Test
    void theDeploymentCarriesWhatItRemediates() throws Exception {
        final Deployment base = view( DeploymentState.VERIFIED, "ok" ).deployment();
        final Deployment fix = new Deployment( base.id(), base.productId(), base.environment(), base.version(),
            base.state(), base.requestedBy(), base.requestedByAgentId(), base.approvedBy(), base.approvedByAgentId(),
            base.requestedAt(), base.approvedAt(), base.startedAt(), base.verifiedAt(), base.closedAt(), base.evidence(),
            3L, base.itemIds() );
        Mockito.when( deployments.deployment( ALICE, 4L ) ).thenReturn( new DeploymentView( fix, "wikantik",
            List.of( WorkHttp.item( "PLAT-1", null, 1 ) ), true, false ) );
        assertEquals( 3, call( "GET", "/4", Map.of(), null ).json().getAsJsonObject().get( "remediatesId" ).getAsLong() );
        Mockito.when( deployments.deployment( ALICE, 5L ) ).thenReturn( view( DeploymentState.VERIFIED, "ok" ) );
        final JsonObject none = call( "GET", "/5", Map.of(), null ).json().getAsJsonObject();
        assertTrue( !none.has( "remediatesId" ) || none.get( "remediatesId" ).isJsonNull(), "null when it remediates nothing" );
    }

    @Test
    void aRequestBodyIsCheckedByField() throws Exception {
        final WorkHttp.Result notAList = call( "POST", null, Map.of(), "{\"product\":\"wikantik\",\"version\":\"1\",\"items\":\"PLAT-1\"}" );
        assertEquals( 400, notAList.status() );
        assertEquals( "items", notAList.json().getAsJsonObject().get( "field" ).getAsString() );
        final WorkHttp.Result unknown = call( "POST", null, Map.of(), "{\"product\":\"wikantik\",\"state\":\"approved\"}" );
        assertEquals( 400, unknown.status() );
        assertEquals( "state", unknown.json().getAsJsonObject().get( "field" ).getAsString() );
        Mockito.verifyNoInteractions( deployments );
    }

    @Test
    void showsADeploymentWithNamesKeysAndOnlyWhatTheCallerSees() throws Exception {
        Mockito.when( deployments.deployment( ALICE, 4L ) ).thenReturn( view( DeploymentState.VERIFIED, "<b>health</b> 200" ) );
        final WorkHttp.Result r = call( "GET", "/4", Map.of(), null );
        assertEquals( 200, r.status() );
        final JsonObject o = r.json().getAsJsonObject();
        assertEquals( 4, o.get( "id" ).getAsLong() );
        assertEquals( "wikantik", o.get( "product" ).getAsString() );
        assertEquals( "verified", o.get( "state" ).getAsString() );
        assertEquals( "codex", o.get( "requestedByName" ).getAsString() );
        assertEquals( "agent", o.get( "requestedByKind" ).getAsString() );
        assertEquals( "Name of bob", o.get( "approvedBy" ).getAsString(), "a non-admin sees no login" );
        assertEquals( "<b>health</b> 200", o.get( "evidence" ).getAsString(), "evidence is text, returned as it was given" );
        final JsonArray items = o.getAsJsonArray( "items" );
        assertEquals( 1, items.size() );
        assertEquals( "PLAT-1", items.get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertTrue( o.get( "mayApprove" ).getAsBoolean() );
        assertFalse( o.get( "mayRun" ).getAsBoolean() );
        assertFalse( o.has( "itemIds" ) );
        assertFalse( o.has( "productId" ) );
        assertFalse( r.body().contains( "\"bob\"" ), r.body() );
    }

    @Test
    void listsAProductsDeployments() throws Exception {
        Mockito.when( deployments.forProduct( ALICE, "wikantik", 5 ) )
            .thenReturn( List.of( view( DeploymentState.REQUESTED, null ) ) );
        final WorkHttp.Result r = call( "GET", null, Map.of( "product", "wikantik", "limit", "5" ), null );
        assertEquals( 200, r.status() );
        assertEquals( 1, r.json().getAsJsonObject().getAsJsonArray( "deployments" ).size() );
        assertEquals( 400, call( "GET", null, Map.of(), null ).status(), "product is required" );
    }

    @Test
    void showsTheHistory() throws Exception {
        Mockito.when( deployments.history( ALICE, 4L ) ).thenReturn( List.of(
            new DeploymentEvent( 1, 4, T0, "codex", Actor.Kind.AGENT, 7L, null, DeploymentState.REQUESTED, null ),
            new DeploymentEvent( 2, 4, T0, "bob", Actor.Kind.HUMAN, null, DeploymentState.REQUESTED,
                DeploymentState.APPROVED, "go" ) ) );
        final JsonArray h = call( "GET", "/4/history", Map.of(), null ).json().getAsJsonObject().getAsJsonArray( "history" );
        assertEquals( "requested", h.get( 0 ).getAsJsonObject().get( "to" ).getAsString() );
        assertEquals( "codex", h.get( 0 ).getAsJsonObject().get( "actorName" ).getAsString() );
        assertEquals( "Name of bob", h.get( 1 ).getAsJsonObject().get( "actor" ).getAsString() );
        assertEquals( "go", h.get( 1 ).getAsJsonObject().get( "note" ).getAsString() );
    }

    @Test
    void movesADeployment() throws Exception {
        final DeploymentView v = view( DeploymentState.APPROVED, null );
        Mockito.when( deployments.approve( ALICE, 4L, "go" ) ).thenReturn( v );
        Mockito.when( deployments.start( ALICE, 4L ) ).thenReturn( v );
        Mockito.when( deployments.verify( ALICE, 4L, "health 200" ) ).thenReturn( v );
        Mockito.when( deployments.fail( ALICE, 4L, "red" ) ).thenReturn( v );
        Mockito.when( deployments.rollBack( ALICE, 4L, null ) ).thenReturn( v );
        assertEquals( 200, call( "POST", "/4/approve", Map.of(), "{\"note\":\"go\"}" ).status() );
        assertEquals( 200, call( "POST", "/4/start", Map.of(), null ).status() );
        assertEquals( 200, call( "POST", "/4/verify", Map.of(), "{\"evidence\":\"health 200\"}" ).status() );
        assertEquals( 200, call( "POST", "/4/fail", Map.of(), "{\"note\":\"red\"}" ).status() );
        assertEquals( 200, call( "POST", "/4/roll-back", Map.of(), null ).status() );
        Mockito.verify( deployments ).rollBack( ALICE, 4L, null );
    }

    @Test
    void refusalsValidationAndConflictsKeepTheirShape() throws Exception {
        Mockito.when( deployments.approve( ALICE, 4L, null ) )
            .thenThrow( new WorkDeniedException( "A person approves deployments of this product." ) );
        final WorkHttp.Result denied = call( "POST", "/4/approve", Map.of(), null );
        assertEquals( 403, denied.status() );
        assertEquals( "A person approves deployments of this product.",
            denied.json().getAsJsonObject().get( "message" ).getAsString() );
        Mockito.when( deployments.verify( ALICE, 4L, null ) ).thenThrow( new WorkValidationException( "evidence",
            "Attach the health and smoke-test output before marking this verified." ) );
        final WorkHttp.Result invalid = call( "POST", "/4/verify", Map.of(), "{}" );
        assertEquals( 400, invalid.status() );
        assertEquals( "evidence", invalid.json().getAsJsonObject().get( "field" ).getAsString() );
        Mockito.when( deployments.start( ALICE, 4L ) ).thenThrow( new WorkConflictException(
            "A deployment that is requested cannot become running.", WorkConflictException.BAD_TRANSITION ) );
        final WorkHttp.Result conflict = call( "POST", "/4/start", Map.of(), null );
        assertEquals( 409, conflict.status() );
        assertEquals( "bad_transition", conflict.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void theGateRouteAnswersTheDeployScript() throws Exception {
        Mockito.when( deployments.gate( ALICE, 4L, "2.5.26" ) ).thenReturn(
            new WorkDeploymentsService.GateAnswer( true, DeploymentState.APPROVED, "2.5.26", "production" ) );
        Mockito.when( deployments.gate( ALICE, 4L, "2.5.27" ) ).thenReturn(
            new WorkDeploymentsService.GateAnswer( false, DeploymentState.APPROVED, "2.5.26", "production" ) );
        final WorkHttp.Result yes = call( "GET", "/4/gate", Map.of( "version", "2.5.26" ), null );
        assertEquals( 200, yes.status() );
        final JsonObject answer = yes.json().getAsJsonObject();
        assertTrue( answer.get( "approved" ).getAsBoolean() );
        assertEquals( "approved", answer.get( "state" ).getAsString() );
        assertEquals( "2.5.26", answer.get( "version" ).getAsString() );
        assertEquals( "production", answer.get( "environment" ).getAsString() );
        assertEquals( "no-store", yes.headers().get( "Cache-Control" ) );
        final WorkHttp.Result other = call( "GET", "/4/gate", Map.of( "version", "2.5.27" ), null );
        assertFalse( other.json().getAsJsonObject().get( "approved" ).getAsBoolean(), "another version is not approved" );
        final WorkHttp.Result none = call( "GET", "/4/gate", Map.of(), null );
        assertEquals( 400, none.status(), "the script must say which version it is about to deploy" );
        Mockito.verify( deployments, Mockito.never() ).deployment( Mockito.any(), Mockito.anyLong() );
    }

    @Test
    void rejectsOrWithdrawsADeployment() throws Exception {
        Mockito.when( deployments.reject( ALICE, 4L, "wrong version" ) ).thenReturn( view( DeploymentState.REJECTED, null ) );
        final WorkHttp.Result r = call( "POST", "/4/reject", Map.of(), "{\"note\":\"wrong version\"}" );
        assertEquals( 200, r.status() );
        assertEquals( "rejected", r.json().getAsJsonObject().get( "state" ).getAsString() );
    }

    @Test
    void anUnseenOrMalformedDeploymentIsNotFound() throws Exception {
        Mockito.when( deployments.deployment( ALICE, 4L ) ).thenThrow( new WorkNotFoundException( "x" ) );
        Mockito.when( deployments.gate( ALICE, 4L, "2.5.26" ) ).thenThrow( new WorkNotFoundException( "x" ) );
        final WorkHttp.Result hidden = call( "GET", "/4", Map.of(), null );
        final WorkHttp.Result malformed = call( "GET", "/four", Map.of(), null );
        assertEquals( 404, hidden.status() );
        assertEquals( 404, malformed.status() );
        assertEquals( hidden.body(), malformed.body() );
        assertEquals( 404, call( "GET", "/4/gate", Map.of( "version", "2.5.26" ), null ).status() );
    }

    @Test
    void anItemSaysWhatItShippedInAndTheCallerWhatAwaitsTheirApproval() throws Exception {
        Mockito.when( deployments.shippedIn( ALICE, "PLAT-1" ) ).thenReturn( List.of( view( DeploymentState.VERIFIED, "ok" ) ) );
        final WorkItemResource items = WorkHttp.wire( new WorkItemResource(), svc, ALICE );
        final JsonObject shipped = WorkHttp.call( items, "GET", "/api/work/items", "/PLAT-1/shipped-in", Map.of(), null )
            .json().getAsJsonObject().getAsJsonArray( "shippedIn" ).get( 0 ).getAsJsonObject();
        assertEquals( "2.5.26", shipped.get( "version" ).getAsString() );
        assertEquals( "production", shipped.get( "environment" ).getAsString() );
        assertFalse( shipped.has( "evidence" ), "the short form carries no evidence" );

        Mockito.when( deployments.awaitingMyApproval( ALICE ) ).thenReturn( List.of( view( DeploymentState.REQUESTED, null ) ) );
        Mockito.when( svc.waitingOn( ALICE ) ).thenReturn( List.of() );
        Mockito.when( svc.listProjectsWithRoles( ALICE ) ).thenReturn( List.of() );
        final com.wikantik.work.WorkFeatures features = Mockito.mock( com.wikantik.work.WorkFeatures.class );
        final com.wikantik.work.WorkNotificationsService notifications =
            Mockito.mock( com.wikantik.work.WorkNotificationsService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.notifications() ).thenReturn( notifications );
        Mockito.when( notifications.preference( ALICE ) ).thenReturn(
            new com.wikantik.work.NotifyPreference( com.wikantik.work.EmailMode.OFF, false, false ) );
        final WorkMeResource me = WorkHttp.wire( new WorkMeResource(), svc, ALICE );
        final JsonObject waiting = WorkHttp.call( me, "GET", "/api/work/me", null, Map.of(), null )
            .json().getAsJsonObject().getAsJsonArray( "deploymentsAwaiting" ).get( 0 ).getAsJsonObject();
        assertEquals( 4, waiting.get( "id" ).getAsLong() );
        assertEquals( "wikantik", waiting.get( "product" ).getAsString() );
        assertEquals( 1, waiting.get( "itemCount" ).getAsInt(), "only the items the caller can see are counted" );
    }
}

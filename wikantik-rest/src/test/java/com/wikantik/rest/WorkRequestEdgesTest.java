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

import com.wikantik.work.Actor;
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeploymentDraft;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.ItemFilter;
import com.wikantik.work.Product;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * What the work API answers to a body it cannot read and to values that are missing, blank or of the wrong type:
 * a 400 that names the field, with nothing written.
 */
class WorkRequestEdgesTest {

    private static final Actor ALICE = Actor.human( "alice", Set.of(), false );

    private WorkService svc;

    @BeforeEach
    void setUp() {
        svc = Mockito.mock( WorkService.class, Mockito.RETURNS_DEEP_STUBS );
    }

    private < T extends WorkResourceBase > WorkHttp.Result call( final T resource, final String method,
            final String servletPath, final String pathInfo, final Map< String, String > query, final String body )
            throws Exception {
        return WorkHttp.call( WorkHttp.wire( resource, svc, ALICE ), method, servletPath, pathInfo, query, body );
    }

    private static String fieldOf( final WorkHttp.Result r ) {
        assertEquals( 400, r.status(), r.body() );
        return r.json().getAsJsonObject().get( "field" ).getAsString();
    }

    // ---- a body that is not JSON

    @Test
    void everyWriteRouteAnswers400ToABodyThatIsNotJson() throws Exception {
        final Object[][] routes = {
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", null },
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", "/4/approve" },
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", "/4/reject" },
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", "/4/verify" },
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", "/4/fail" },
            { new WorkDeploymentResource(), "POST", "/api/work/deployments", "/4/roll-back" },
            { new WorkItemResource(), "POST", "/api/work/items", null },
            { new WorkItemResource(), "PATCH", "/api/work/items", "/PLAT-1" },
            { new WorkItemResource(), "POST", "/api/work/items", "/PLAT-1/comments" },
            { new WorkItemResource(), "PATCH", "/api/work/comments", "/3" },
            { new WorkItemResource(), "POST", "/api/work/items", "/PLAT-1/changes" },
            { new WorkItemResource(), "POST", "/api/work/items", "/PLAT-1/handoff" },
            { new WorkItemResource(), "POST", "/api/work/items", "/PLAT-1/links" },
            { new WorkQueueResource(), "POST", "/api/work/queues", null },
            { new WorkQueueResource(), "PATCH", "/api/work/queues", "/reviewer" },
            { new WorkProjectResource(), "POST", "/api/work/projects", null },
            { new WorkProjectResource(), "PATCH", "/api/work/projects", "/PLAT" },
            { new WorkProjectResource(), "PUT", "/api/work/projects", "/PLAT/members" },
            { new WorkProjectResource(), "DELETE", "/api/work/projects", "/PLAT/members" },
            { new WorkChangeResource(), "PATCH", "/api/work/changes", "/7" },
            { new WorkChangeResource(), "POST", "/api/work/changes", "/7/review" },
            { new WorkChangeResource(), "POST", "/api/work/changes", "/7/merge" },
            { new WorkChangeResource(), "POST", "/api/work/changes", "/7/abandon" },
            { new WorkAgentResource(), "POST", "/api/work/agents", null },
            { new WorkAgentResource(), "PATCH", "/api/work/agents", "/7" },
            { new WorkProductResource(), "POST", "/api/work/products", null },
            { new WorkProductResource(), "PATCH", "/api/work/products", "/wikantik" },
        };
        for ( final Object[] r : routes ) {
            final WorkHttp.Result out = call( ( WorkResourceBase ) r[ 0 ], ( String ) r[ 1 ], ( String ) r[ 2 ],
                ( String ) r[ 3 ], Map.of(), "{ this is not json" );
            assertEquals( 400, out.status(), r[ 1 ] + " " + r[ 2 ] + r[ 3 ] + " -> " + out.body() );
        }
    }

    // ---- deployments

    @Test
    void aDeploymentRequestWithNoItemsReachesTheServiceWhichRefusesIt() throws Exception {
        call( new WorkDeploymentResource(), "POST", "/api/work/deployments", null, Map.of(),
            "{\"product\":\"wikantik\",\"version\":\"2.6.0\",\"items\":null}" );
        final ArgumentCaptor< DeploymentDraft > d = ArgumentCaptor.forClass( DeploymentDraft.class );
        Mockito.verify( svc.deployments() ).request( eq( ALICE ), d.capture() );
        assertEquals( List.of(), d.getValue().itemKeys() );
        assertEquals( "items", fieldOf( call( new WorkDeploymentResource(), "POST", "/api/work/deployments", null,
            Map.of(), "{\"product\":\"wikantik\",\"version\":\"2.6.0\",\"items\":[7]}" ) ) );
        assertEquals( "items", fieldOf( call( new WorkDeploymentResource(), "POST", "/api/work/deployments", null,
            Map.of(), "{\"product\":\"wikantik\",\"version\":\"2.6.0\",\"items\":[{\"key\":\"PLAT-1\"}]}" ) ) );
    }

    @Test
    void aDeploymentListHasADefaultLength() throws Exception {
        call( new WorkDeploymentResource(), "GET", "/api/work/deployments", null, Map.of( "product", "wikantik" ), null );
        Mockito.verify( svc.deployments() ).forProduct( ALICE, "wikantik", 20 );
        call( new WorkDeploymentResource(), "GET", "/api/work/deployments", null,
            Map.of( "product", "wikantik", "limit", "0" ), null );
        Mockito.verify( svc.deployments(), Mockito.times( 2 ) ).forProduct( ALICE, "wikantik", 20 );
        assertEquals( "product", fieldOf( call( new WorkDeploymentResource(), "GET", "/api/work/deployments", null,
            Map.of(), null ) ) );
    }

    // ---- items

    @Test
    void aNewItemNamesItsProjectEitherWayAndLeavesOutWhatItLikes() throws Exception {
        call( new WorkItemResource(), "POST", "/api/work/items", null, Map.of(), "{\"project\":\"PLAT\",\"title\":\"T\"}" );
        call( new WorkItemResource(), "POST", "/api/work/items", null, Map.of(),
            "{\"projectKey\":\"OPS\",\"level\":\"story\",\"kind\":\"docs\",\"title\":\"T\",\"priority\":2,\"dueOn\":\" \"}" );
        final ArgumentCaptor< ItemDraft > d = ArgumentCaptor.forClass( ItemDraft.class );
        Mockito.verify( svc, Mockito.times( 2 ) ).createItem( eq( ALICE ), d.capture() );
        assertEquals( "PLAT", d.getAllValues().get( 0 ).projectKey() );
        assertNull( d.getAllValues().get( 0 ).level(), "the service says a level is needed" );
        assertNull( d.getAllValues().get( 0 ).kind() );
        assertNull( d.getAllValues().get( 0 ).priority() );
        assertEquals( "OPS", d.getAllValues().get( 1 ).projectKey() );
        assertEquals( 2, d.getAllValues().get( 1 ).priority() );
        assertNull( d.getAllValues().get( 1 ).dueOn(), "a blank date is no date" );
    }

    @Test
    void aNewItemsPriorityAndDateAreChecked() throws Exception {
        assertEquals( "priority", fieldOf( call( new WorkItemResource(), "POST", "/api/work/items", null, Map.of(),
            "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\",\"priority\":\"high\"}" ) ) );
        assertEquals( "priority", fieldOf( call( new WorkItemResource(), "POST", "/api/work/items", null, Map.of(),
            "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\",\"priority\":{\"level\":1}}" ) ) );
        assertEquals( "dueOn", fieldOf( call( new WorkItemResource(), "POST", "/api/work/items", null, Map.of(),
            "{\"project\":\"PLAT\",\"level\":\"task\",\"title\":\"T\",\"dueOn\":\"tomorrow\"}" ) ) );
        Mockito.verify( svc, Mockito.never() ).createItem( any(), any() );
    }

    @Test
    void anItemPatchTakesAnObjectOfPlainValues() throws Exception {
        assertEquals( "changes", fieldOf( call( new WorkItemResource(), "PATCH", "/api/work/items", "/PLAT-1", Map.of(),
            "{\"expectedVersion\":1,\"changes\":\"status=done\"}" ) ) );
        assertEquals( "title", fieldOf( call( new WorkItemResource(), "PATCH", "/api/work/items", "/PLAT-1", Map.of(),
            "{\"expectedVersion\":1,\"changes\":{\"title\":{\"nested\":true}}}" ) ) );
        Mockito.verify( svc, Mockito.never() ).updateItem( any(), any(), any() );
        call( new WorkItemResource(), "PATCH", "/api/work/items", "/PLAT-1", Map.of(),
            "{\"expectedVersion\":1,\"changes\":{\"title\":\"New\",\"priority\":2,\"allowDirect\":true,\"dueOn\":null}}" );
        Mockito.verify( svc ).updateItem( eq( ALICE ), eq( "PLAT-1" ), any() );
    }

    @Test
    void aListFilterNamesAProductAParentAndStatuses() throws Exception {
        Mockito.when( svc.listProducts( ALICE ) ).thenReturn( List.of( new Product( 9, "wikantik", "Wikantik", null,
            "main", null, Autonomy.DELEGATED, null, null ) ) );
        Mockito.when( svc.item( ALICE, "PLAT-1" ).item().id() ).thenReturn( 41L );
        call( new WorkItemResource(), "GET", "/api/work/items", null, Map.of( "project", "PLAT", "product", "wikantik",
            "parent", "PLAT-1", "status", "ready, review", "level", "task" ), null );
        call( new WorkItemResource(), "GET", "/api/work/items", null, Map.of( "project", "PLAT", "product", "nope" ), null );
        final ArgumentCaptor< ItemFilter > f = ArgumentCaptor.forClass( ItemFilter.class );
        Mockito.verify( svc, Mockito.atLeast( 2 ) ).itemSummaries( eq( ALICE ), eq( "PLAT" ), f.capture() );
        assertEquals( 9L, f.getAllValues().get( 0 ).productId() );
        assertEquals( 41L, f.getAllValues().get( 0 ).parentId() );
        assertEquals( 2, f.getAllValues().get( 0 ).statuses().size() );
        assertEquals( -1L, f.getAllValues().get( 1 ).productId(), "an unknown product matches nothing" );
    }

    @Test
    void theClaimableListHasADefaultAndAMaximum() throws Exception {
        call( new WorkItemResource(), "GET", "/api/work/claimable", null, Map.of(), null );
        call( new WorkItemResource(), "GET", "/api/work/claimable", null, Map.of( "limit", "5000" ), null );
        final ArgumentCaptor< Integer > limit = ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( svc.claims(), Mockito.times( 2 ) ).claimable( eq( ALICE ), any(), any(), limit.capture() );
        assertEquals( 20, limit.getAllValues().get( 0 ) );
        assertEquals( true, limit.getAllValues().get( 1 ) < 5000 );
    }

    // ---- projects

    @Test
    void aProjectPatchKeepsWhatItDoesNotName() throws Exception {
        Mockito.when( svc.project( ALICE, "PLAT" ) ).thenReturn( WorkHttp.project( "PLAT" ) );
        call( new WorkProjectResource(), "PATCH", "/api/work/projects", "/PLAT", Map.of(), "{\"defaultAutonomy\":\"supervised\"}" );
        Mockito.verify( svc ).updateProject( ALICE, "PLAT", "PLAT name", "desc", Autonomy.SUPERVISED );
        call( new WorkProjectResource(), "PATCH", "/api/work/projects", "/PLAT", Map.of(), "{\"name\":\"New\",\"description\":\"d\"}" );
        Mockito.verify( svc ).updateProject( ALICE, "PLAT", "New", "d", null );
    }

    @Test
    void aMemberIsRemovedByQueryOrByBody() throws Exception {
        call( new WorkProjectResource(), "DELETE", "/api/work/projects", "/PLAT/members",
            Map.of( "principal", "Bob", "principalKind", "user" ), null );
        call( new WorkProjectResource(), "DELETE", "/api/work/projects", "/PLAT/members", Map.of(),
            "{\"principal\":\"Dev\",\"principalKind\":\"group\"}" );
        Mockito.verify( svc ).removeMember( ALICE, "PLAT", "Bob", "user" );
        Mockito.verify( svc ).removeMember( ALICE, "PLAT", "Dev", "group" );
        call( new WorkProjectResource(), "PUT", "/api/work/projects", "/PLAT/members", Map.of(),
            "{\"principal\":\"Bob\",\"principalKind\":\"user\"}" );
        Mockito.verify( svc ).putMember( eq( ALICE ), eq( "PLAT" ), eq( "Bob" ), eq( "user" ), eq( null ) );
    }

    // ---- shared readers

    @Test
    void aRequiredFieldIsPresentAndNotBlankAndTextIsText() {
        final com.google.gson.JsonObject b = com.google.gson.JsonParser.parseString(
            "{\"a\":\"x\",\"blank\":\" \",\"nil\":null,\"obj\":{},\"n\":3,\"flag\":true}" ).getAsJsonObject();
        assertEquals( "x", WorkRequests.requiredString( b, "a" ) );
        for ( final String missing : List.of( "blank", "nil", "absent" ) ) {
            assertEquals( missing, org.junit.jupiter.api.Assertions.assertThrows(
                com.wikantik.work.WorkValidationException.class, () -> WorkRequests.requiredString( b, missing ) ).field() );
        }
        assertEquals( "obj", org.junit.jupiter.api.Assertions.assertThrows(
            com.wikantik.work.WorkValidationException.class, () -> WorkRequests.optString( b, "obj" ) ).field() );
        assertNull( WorkRequests.plainValue( "x", null ) );
        assertNull( WorkRequests.plainValue( "nil", b.get( "nil" ) ) );
        assertEquals( true, WorkRequests.plainValue( "flag", b.get( "flag" ) ) );
        assertEquals( 3, ( ( Number ) WorkRequests.plainValue( "n", b.get( "n" ) ) ).intValue() );
        assertEquals( "x", WorkRequests.plainValue( "a", b.get( "a" ) ) );
    }
}

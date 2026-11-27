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

import com.wikantik.work.Actor;
import com.wikantik.work.Attachment;
import com.wikantik.work.Autonomy;
import com.wikantik.work.Change;
import com.wikantik.work.ChangeState;
import com.wikantik.work.CommentView;
import com.wikantik.work.Deployment;
import com.wikantik.work.DeploymentState;
import com.wikantik.work.DoneMeans;
import com.wikantik.work.Item;
import com.wikantik.work.Sprint;
import com.wikantik.work.SprintState;
import com.wikantik.work.WorkChangesService.ChangeView;
import com.wikantik.work.WorkDeploymentsService.DeploymentView;
import com.wikantik.work.WorkDirectory;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkSprintsService.SprintView;
import com.wikantik.work.WorkStatus;
import com.wikantik.work.WorkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How items, comments, events, changes and deployments are named and worded for an agent, driven through plain
 * records and a stub directory (no database): people by display name, agents and queues by name, a value that is
 * absent shown as null (or an empty body), never a login and never a failure.
 */
class WorkMcpJsonRenderingTest {

    private static final Instant AT = Instant.parse( "2026-03-01T09:30:00Z" );

    private WorkDirectory directory;
    private Map< Long, String > agents;
    private Map< String, String > queues;
    private WorkMcpJson json;

    @BeforeEach
    void setUp() {
        directory = mock( WorkDirectory.class );
        when( directory.displayName( any() ) ).thenAnswer( inv -> "Name of " + inv.getArgument( 0 ) );
        agents = new HashMap<>( Map.of( 5L, "Builder" ) );
        queues = new HashMap<>( Map.of( "implementer", "Implementer" ) );
        json = new WorkMcpJson( directory, agents, queues );
    }

    // ---- who

    @Test
    void aPersonIsTheirDisplayNameAndNoPersonIsNoName() {
        assertEquals( "Name of lena", json.person( "lena" ) );
        assertNull( json.person( null ) );
    }

    @Test
    void anAgentIsNamedByTheRegistryThenByWhatWasStoredThenAsUnknown() {
        assertEquals( "Builder", json.agent( 5L, "stored" ), "the registry's name wins" );
        assertEquals( "stored", json.agent( 6L, "stored" ), "an agent the registry does not show: the stored name" );
        assertEquals( "stored", json.agent( null, "stored" ) );
        assertEquals( "an agent", json.agent( 6L, null ) );
        assertEquals( "an agent", json.agent( null, null ) );
    }

    @Test
    void aQueueIsItsNameOrItsKeyWhenTheRegistryDoesNotShowItOrNothing() {
        assertEquals( "Implementer", json.queue( "implementer" ) );
        assertEquals( "hidden-queue", json.queue( "hidden-queue" ) );
        assertNull( json.queue( null ) );
    }

    // ---- an item

    private static Item item( final String body, final Long claimedBy, final Instant claimExpires ) {
        return new Item( 1, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "A title", body,
            WorkStatus.IN_PROGRESS, 3, 1.0, null, "lena", "lena", Autonomy.SUPERVISED, DoneMeans.MERGED, null, 4, AT, AT,
            null, "implementer", claimedBy, null, claimExpires, null, WorkType.BUG, List.of( "ui" ), null,
            new BigDecimal( "2.5" ) );
    }

    private static com.wikantik.work.WorkService.ItemView view( final Item item, final List< CommentView > comments ) {
        return new com.wikantik.work.WorkService.ItemView( item, "<p>x</p>", "PLAT", null, null, List.of(), comments,
            List.of(), false, null, false, null );
    }

    @Test
    void anItemWithNoBodyShowsAnEmptyBodyAndOneWithABodyShowsItAsWritten() {
        assertEquals( "", json.item( view( item( null, null, null ), List.of() ), List.of() ).get( "body" ) );
        assertEquals( "# Hi", json.item( view( item( "# Hi", null, null ), List.of() ), List.of() ).get( "body" ) );
    }

    @Test
    void aClaimNamesItsHolderAndShowsItsExpiryOnlyWhenThereIsOne() {
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > open = ( Map< String, Object > ) json.item(
            view( item( "b", 5L, null ), List.of() ), List.of() ).get( "claim" );
        assertEquals( "Builder", open.get( "agent" ) );
        assertNull( open.get( "expiresAt" ) );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > timed = ( Map< String, Object > ) json.item(
            view( item( "b", 5L, AT ), List.of() ), List.of() ).get( "claim" );
        assertEquals( "2026-03-01T09:30:00Z", timed.get( "expiresAt" ) );
        assertNull( json.item( view( item( "b", null, null ), List.of() ), List.of() ).get( "claim" ) );
    }

    @Test
    void aCommentIsAttributedToItsAgentOrItsPersonAndAnUndatedOneHasNoTime() {
        final CommentView byAgent = new CommentView( 1, 1, "builder", "from the agent", AT, null, null, "<p/>", List.of(), 5L );
        final CommentView byPerson = new CommentView( 2, 1, "lena", "from a person", null, null, null, "<p/>", List.of(), null );
        final CommentView removed = new CommentView( 3, 1, "lena", "gone", AT, null, AT, "<p/>", List.of(), null );
        final Map< String, Object > o = json.item( view( item( "b", null, null ), List.of( byAgent, byPerson, removed ) ),
            List.of() );
        @SuppressWarnings( "unchecked" )
        final List< Map< String, Object > > comments = ( List< Map< String, Object > > ) o.get( "comments" );
        assertEquals( 2, comments.size(), "a removed comment is not shown" );
        assertEquals( Map.of( "author", "Builder", "authorKind", "agent", "body", "from the agent",
            "at", "2026-03-01T09:30:00Z" ), comments.get( 0 ) );
        final Map< String, Object > person = comments.get( 1 );
        assertEquals( "Name of lena", person.get( "author" ) );
        assertEquals( "human", person.get( "authorKind" ) );
        assertEquals( "from a person", person.get( "body" ) );
        assertNull( person.get( "at" ) );
    }

    // ---- events

    private static WorkEvent event( final String type, final Actor.Kind kind, final String actor, final Long agentId,
                                    final String from, final String to, final Instant at ) {
        return new WorkEvent( 1, 1, 7L, "PLAT-1", at, actor, kind, agentId, type, from, to, "a note" );
    }

    @Test
    void anEventShowsWhoDidItAndWhenAndAnUndatedOneHasNoTime() {
        final Map< String, Object > byAgent = json.event( event( WorkEvent.CLAIMED, Actor.Kind.AGENT, "builder", 5L,
            null, null, AT ) );
        assertEquals( "claimed", byAgent.get( "type" ) );
        assertEquals( "Builder", byAgent.get( "actor" ) );
        assertEquals( "agent", byAgent.get( "actorKind" ) );
        assertEquals( "2026-03-01T09:30:00Z", byAgent.get( "at" ) );
        assertEquals( "a note", byAgent.get( "note" ) );
        final Map< String, Object > byPerson = json.event( event( WorkEvent.COMMENTED, Actor.Kind.HUMAN, "lena", null,
            null, null, null ) );
        assertEquals( "Name of lena", byPerson.get( "actor" ) );
        assertEquals( "human", byPerson.get( "actorKind" ) );
        assertNull( byPerson.get( "at" ) );
    }

    @Test
    void anEventValueThatIsAbsentOrEmptyIsShownAsItIs() {
        final Map< String, Object > e = json.event( event( WorkEvent.ASSIGNED, Actor.Kind.AGENT, "builder", 5L, null, "",
            AT ) );
        assertNull( e.get( "from" ) );
        assertEquals( "", e.get( "to" ) );
        verifyNoInteractions( directory );   // not even asked: "" is not a login
        assertNull( json.event( event( WorkEvent.HANDOFF, Actor.Kind.AGENT, "builder", 5L, null, null, AT ) ).get( "to" ) );
    }

    @Test
    void anAssignmentIsShownByThePersonsOrTheQueuesName() {
        assertEquals( "Name of jdoe", json.event( event( WorkEvent.ASSIGNED, Actor.Kind.HUMAN, "lena", null, "lena", "jdoe",
            AT ) ).get( "to" ) );
        assertEquals( "Name of lena", json.event( event( WorkEvent.ASSIGNED, Actor.Kind.HUMAN, "lena", null, "lena", "jdoe",
            AT ) ).get( "from" ) );
        assertEquals( "Implementer", json.event( event( WorkEvent.ASSIGNED, Actor.Kind.HUMAN, "lena", null, null,
            "queue:implementer", AT ) ).get( "to" ) );
        assertEquals( "elsewhere", json.event( event( WorkEvent.ASSIGNED, Actor.Kind.HUMAN, "lena", null, null,
            "queue:elsewhere", AT ) ).get( "to" ), "a queue the registry does not show: its key" );
    }

    @ParameterizedTest
    @CsvSource( delimiter = ';', nullValues = "NULL", value = {
        "review|queue:implementer;review|Implementer",
        "review|jdoe;review|Name of jdoe",
        "review|;review|",
        "|queue:implementer;|Implementer",
        "in_progress;in_progress"
    } )
    void aHandoffValueKeepsItsStatusAndNamesItsTarget( final String stored, final String shown ) {
        assertEquals( shown, json.event( event( WorkEvent.HANDOFF, Actor.Kind.AGENT, "builder", 5L, null, stored, AT ) )
            .get( "to" ) );
    }

    @Test
    void anEmptyHandoffTargetIsNotLookedUpInTheDirectory() {
        json.event( event( WorkEvent.HANDOFF, Actor.Kind.AGENT, "builder", 5L, null, "review|", AT ) );
        verifyNoInteractions( directory );
    }

    @Test
    void aReleaseNamesTheAgentThatHeldTheClaimAndLeavesAnyOtherValueAlone() {
        assertEquals( "Builder", json.event( event( WorkEvent.RELEASED, Actor.Kind.AGENT, "builder", 5L, "agent:5", null,
            AT ) ).get( "from" ) );
        assertEquals( "Builder", json.event( event( WorkEvent.RELEASED, Actor.Kind.AGENT, "builder", 5L, "agent: 5 ", null,
            AT ) ).get( "from" ), "the id is trimmed" );
        assertEquals( "an agent", json.event( event( WorkEvent.RELEASED, Actor.Kind.AGENT, "builder", 5L, "agent:6", null,
            AT ) ).get( "from" ), "an agent the registry does not show" );
        assertEquals( "an agent", json.event( event( WorkEvent.RELEASED, Actor.Kind.AGENT, "builder", 5L, "agent:abc", null,
            AT ) ).get( "from" ), "not an id: unknown, not a failure" );
        assertEquals( "expired", json.event( event( WorkEvent.RELEASED, Actor.Kind.AGENT, "builder", 5L, "expired", null,
            AT ) ).get( "from" ) );
    }

    @Test
    void aValueOfAnotherEventTypeIsShownAsStored() {
        assertEquals( "ready", json.event( event( WorkEvent.STATUS, Actor.Kind.HUMAN, "lena", null, "draft", "ready",
            AT ) ).get( "to" ) );
        assertEquals( "queue:implementer", json.event( event( WorkEvent.FIELD, Actor.Kind.HUMAN, "lena", null, null,
            "queue:implementer", AT ) ).get( "to" ) );
    }

    // ---- the whole item, read for an actor

    @Test
    void aFullItemCarriesItsSprintAndItsAttachmentsAndTheirRestPaths() throws SQLException {
        final MockWork w = new MockWork();
        final Item inSprint = new Item( 1, 1, "PLAT-1", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "T", "b",
            WorkStatus.READY, 3, 1.0, null, null, null, Autonomy.SUPERVISED, DoneMeans.MERGED, null, 4, AT, AT, null,
            null, null, null, null, null, WorkType.FEATURE, List.of(), 11L, null );
        when( w.sprints.list( any(), any() ) ).thenReturn( List.of( new SprintView( new Sprint( 11, 1, "PLAT", "Sprint 1",
            "goal", LocalDate.parse( "2026-03-01" ), LocalDate.parse( "2026-03-14" ), SprintState.ACTIVE, AT, null, null,
            null, null, null, 1 ), 0, BigDecimal.ZERO, 0, BigDecimal.ZERO ) ) );
        when( w.features.attachments().list( any(), any() ) ).thenReturn( List.of(
            new Attachment( 21, 1, null, "shot.png", "image/png", 13, "sha", "lena", null, AT, null ) ) );
        when( w.changes.views( any(), any() ) ).thenReturn( List.of() );
        when( w.deployments.shippedIn( any(), any() ) ).thenReturn( List.of() );

        final Map< String, Object > o = WorkMcpJson.fullItem( w.svc, w.directory, w.agent, MockWork.view( inSprint ) );

        assertEquals( Map.of( "name", "Sprint 1", "state", "active", "endsOn", "2026-03-14" ), o.get( "sprint" ) );
        assertEquals( List.of( Map.of( "id", 21L, "name", "shot.png", "size", 13L, "contentType", "image/png",
            "path", "/api/work/items/PLAT-1/attachments/21" ) ), o.get( "attachments" ) );
        assertEquals( List.of(), o.get( "changes" ) );
        assertEquals( List.of(), o.get( "shipped_in" ) );
        assertTrue( o.containsKey( "effective" ) );
    }

    @Test
    void anItemInNoSprintHasANullSprintAndNoFilesAnEmptyList() throws SQLException {
        final MockWork w = new MockWork();
        when( w.features.attachments().list( any(), any() ) ).thenReturn( List.of() );
        when( w.changes.views( any(), any() ) ).thenReturn( List.of() );
        when( w.deployments.shippedIn( any(), any() ) ).thenReturn( List.of() );
        final Map< String, Object > o = WorkMcpJson.fullItem( w.svc, w.directory, w.agent,
            MockWork.view( MockWork.item( "PLAT-1", WorkStatus.READY, 1, null, null ) ) );
        assertTrue( o.containsKey( "sprint" ) );
        assertNull( o.get( "sprint" ) );
        assertEquals( List.of(), o.get( "attachments" ) );
    }

    // ---- changes and deployments name people the same way

    private static Change change( final String openedBy, final Long openedByAgent, final String externalOpenedBy,
                                  final Instant flagAckedAt ) {
        return new Change( 5, 1, 1, "feature", "main", "abc123", null, ChangeState.OPEN, null, null, openedBy,
            openedByAgent, null, null, null, null, null, null, AT, null, List.of(), null, "native", externalOpenedBy,
            null, null, null, null, null, flagAckedAt, null );
    }

    private Map< String, Object > rendered( final Change c ) {
        return WorkMcpChanges.change( json, new ChangeView( c, "PLAT-1", "web" ) );
    }

    @Test
    void aChangeNamesItsOpenerAsAPersonAnAgentOrAnOutsideAuthor() {
        assertEquals( "Name of lena", rendered( change( "lena", null, null, null ) ).get( "opened_by" ) );
        assertEquals( "Builder", rendered( change( "builder", 5L, null, null ) ).get( "opened_by" ) );
        assertEquals( "Ada (outside the wiki)", rendered( change( null, null, "Ada", null ) ).get( "opened_by" ) );
        assertNull( rendered( change( null, null, null, null ) ).get( "opened_by" ) );
        assertNull( rendered( change( null, null, null, null ) ).get( "reviewed_by" ) );
        assertNull( rendered( change( null, null, null, null ) ).get( "merged_by" ) );
    }

    @Test
    void aChangeSaysWhetherItsPolicyFlagWasAcknowledgedWithoutSayingByWhom() {
        assertEquals( false, rendered( change( "lena", null, null, null ) ).get( "flag_acknowledged" ) );
        final Map< String, Object > acked = rendered( change( "lena", null, null, AT ) );
        assertEquals( true, acked.get( "flag_acknowledged" ) );
        assertFalse( acked.toString().contains( "lena\"" ) );
    }

    private DeploymentView deployment( final String requestedBy, final Long requestedByAgent, final String approvedBy ) {
        return new DeploymentView( new Deployment( 4, 1, "prod", "1.2.3", DeploymentState.REQUESTED, requestedBy,
            requestedByAgent, approvedBy, null, AT, null, null, null, null, null, null, List.of() ), "web", List.of(),
            false, false );
    }

    @Test
    void aDeploymentNamesItsRequesterAndApproverAndLeavesAnAbsentOneNull() throws SQLException {
        final MockWork w = new MockWork();
        when( w.registry.listAgents( any() ) ).thenReturn( List.of( new com.wikantik.work.Agent( 5, "builder", "Acme",
            "claude-code", "x", List.of(), AT, AT ) ) );
        final Map< String, Object > o = WorkMcpDeployments.deployment( w.svc, w.directory, w.agent,
            deployment( "builder", 5L, "lena" ) );
        assertEquals( "builder", o.get( "requested_by" ) );
        assertEquals( "Someone", o.get( "approved_by" ), "a person by display name" );
        final Map< String, Object > none = WorkMcpDeployments.deployment( w.svc, w.directory, w.agent,
            deployment( "lena", null, null ) );
        assertEquals( "Someone", none.get( "requested_by" ) );
        assertNull( none.get( "approved_by" ) );
    }
}

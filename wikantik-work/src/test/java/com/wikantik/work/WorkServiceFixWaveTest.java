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
package com.wikantik.work;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rulings of the phase-1 review fix wave at the service: people named by display name (I1), archived refusals
 * (I2), comments rendered (I3), keys in the timeline (I4) and the minors that live in the service.
 */
class WorkServiceFixWaveTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );

    private WorkStore store;
    private WorkService service;
    private final List< String > rendered = new ArrayList<>();
    private Project plat;
    private Item task;

    /** Logins (u-…) that look nothing like the display names, as WorkPeopleTest's directory has them. */
    private final Actor lead = Actor.human( "u-c01d", Set.of(), false );
    private final Actor ana = Actor.human( "u-7f3a", Set.of(), false );
    private final Actor admin = Actor.human( "root", Set.of(), true );
    private final Actor agentAdmin = new Actor( "bot", Set.of(), true, Actor.Kind.AGENT, 9L, admin );

    private void role( final String login, final WorkRole role ) throws SQLException {
        when( store.roleFor( eq( 1L ), argThat( a -> a != null && login.equals( a.login() ) ) ) )
            .thenReturn( Optional.of( role ) );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        org.mockito.Mockito.when( store.claims() ).thenReturn( org.mockito.Mockito.mock( WorkClaimStore.class ) );
        service = new WorkService( store, md -> {
            rendered.add( md );
            return "<p>" + md + "</p>";
        }, WorkPeopleTest.DIRECTORY, Clock.fixed( NOW, ZoneOffset.UTC ) );
        plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null, null );
        task = new Item( 12, 1, "PLAT-3", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", WorkStatus.DRAFT,
            3, 1024, null, null, "u-7f3a", null, null, null, 4, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.item( "PLAT-3" ) ).thenReturn( Optional.of( task ) );
        when( store.itemById( 12 ) ).thenReturn( Optional.of( task ) );
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenAnswer( inv -> new WorkStore.ItemWrite( inv.getArgument( 1 ), null ) );
        role( "u-c01d", WorkRole.LEAD );
        role( "u-7f3a", WorkRole.MEMBER );
    }

    private static WorkValidationException invalid( final org.junit.jupiter.api.function.Executable call ) {
        return assertThrows( WorkValidationException.class, call );
    }

    // ---- I1: display names in, logins stored

    @Test
    void aMemberIsAddedByDisplayNameAndStoredByLogin() throws SQLException {
        service.putMember( lead, "PLAT", "Ana Lima", "user", WorkRole.MEMBER );
        verify( store ).putMember( 1, "u-7f3a", "user", WorkRole.MEMBER, lead, java.util.List.of() );
    }

    @Test
    void aNonAdminSendingALoginGetsTheUnknownUserAnswerWhetherOrNotItExists() throws SQLException {
        final WorkValidationException real = invalid( () -> service.putMember( lead, "PLAT", "u-7f3a", "user",
            WorkRole.MEMBER ) );
        final WorkValidationException unreal = invalid( () -> service.putMember( lead, "PLAT", "no-such", "user",
            WorkRole.MEMBER ) );
        assertEquals( "principal", real.field() );
        assertEquals( real.getMessage(), unreal.getMessage() );
        verify( store, never() ).putMember( anyLong(), anyString(), anyString(), any(), any( Actor.class ), any() );
    }

    @Test
    void anAdminMayAlsoSendALogin() throws SQLException {
        service.putMember( admin, "PLAT", "u-7f3a", "user", WorkRole.VIEWER );
        verify( store ).putMember( 1, "u-7f3a", "user", WorkRole.VIEWER, admin, java.util.List.of() );
        service.removeMember( admin, "PLAT", "Bo", "user" );
        verify( store ).removeMember( 1, "u-c01d", "user", admin, java.util.List.of() );
    }

    @Test
    void groupsAreNamedAsTheyAre() throws SQLException {
        service.putMember( lead, "PLAT", "editors", "group", WorkRole.VIEWER );
        verify( store ).putMember( 1, "editors", "group", WorkRole.VIEWER, lead, java.util.List.of() );
    }

    @Test
    void theAssigneeIsADisplayNameAndALoginFromAMemberIsNotAMember() throws SQLException {
        service.updateItem( lead, "PLAT-3", new ItemPatch( 4, Map.of( "assignee", "Ana Lima" ), null ) );
        final ArgumentCaptor< Item > next = ArgumentCaptor.forClass( Item.class );
        verify( store ).writeItem( any(), next.capture(), any( Actor.class ), isNull(), eq( false ) );
        assertEquals( "u-7f3a", next.getValue().assignee() );

        final WorkValidationException login = invalid( () -> service.updateItem( lead, "PLAT-3",
            new ItemPatch( 4, Map.of( "assignee", "u-7f3a" ), null ) ) );
        assertEquals( "assignee", login.field() );
        assertEquals( "The assignee is not a member of this project.", login.getMessage() );
    }

    @Test
    void anUnresolvedAssigneeFilterMatchesNobody() throws SQLException {
        final ItemFilter byName = new ItemFilter( null, null, "Ana Lima", null, null, null, null, null, 0, null, null, null, null, null );
        service.items( lead, "PLAT", byName );
        verify( store ).items( argThat( f -> "u-7f3a".equals( f.assignee() ) && f.projectId() == 1L ) );
        final ItemFilter byLogin = new ItemFilter( null, null, "u-7f3a", null, null, null, null, null, 0, null, null, null, null, null );
        service.board( lead, "PLAT", byLogin );
        verify( store ).board( eq( 1L ), argThat( f -> "\n".equals( f.assignee() ) ) );
    }

    @Test
    void theDueOrderSurvivesPinning() throws SQLException {
        service.items( lead, "PLAT", new ItemFilter( 99L, null, null, null, null, null, null, null, 0,
            ItemFilter.Order.DUE, null, null, null, null ) );
        verify( store ).items( argThat( f -> f.order() == ItemFilter.Order.DUE && f.projectId() == 1L ) );
    }

    // ---- I2 and M3

    @Test
    void archivedRefusalsSayArchived() throws SQLException {
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( new Project( 1, "PLAT", "Platform", "",
            Autonomy.SUPERVISED, NOW, NOW, null ) ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.addComment( ana, "PLAT-3", "hi" ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
    }

    @Test
    void theItemViewSaysWhetherTheProjectIsArchived() throws SQLException {
        assertEquals( false, service.item( ana, "PLAT-3" ).archived() );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( new Project( 1, "PLAT", "Platform", "",
            Autonomy.SUPERVISED, NOW, NOW, null ) ) );
        assertEquals( true, service.item( ana, "PLAT-3" ).archived() );
    }

    @Test
    void aStaleVersionSaysStaleAndADuplicateSaysExists() throws SQLException {
        assertEquals( WorkConflictException.STALE, new WorkConflictException( "x" ).reason() );
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> service.createProject( admin, "PLAT", "n", "" ) ).reason() );
    }

    @Test
    void aHumanAdminCountsAsLeadWhateverTheirRow() throws SQLException {
        role( "root", WorkRole.VIEWER );
        assertEquals( WorkRole.LEAD, service.roleIn( admin, "PLAT" ) );
        when( store.roleFor( eq( 1L ), argThat( a -> a != null && "bot".equals( a.login() ) ) ) )
            .thenReturn( Optional.of( WorkRole.MEMBER ) );
        assertEquals( WorkRole.MEMBER, service.roleIn( agentAdmin, "PLAT" ), "an agent shows its own row" );
        assertThrows( WorkDeniedException.class, () -> service.archiveProject( agentAdmin, "PLAT" ) );
    }

    @Test
    void projectsComeWithTheCallersRole() throws SQLException {
        when( store.visibleProjects( lead ) ).thenReturn( List.of( plat ) );
        when( store.visibleProjects( admin ) ).thenReturn( List.of( plat ) );
        assertEquals( List.of( new WorkService.ProjectRole( plat, WorkRole.LEAD ) ),
            service.listProjectsWithRoles( lead ) );
        assertEquals( WorkRole.LEAD, service.listProjectsWithRoles( admin ).get( 0 ).role() );
        assertTrue( service.listProjectsWithRoles( Actor.human( "x", Set.of(), false ) ).isEmpty() );
    }

    // ---- C1 at the service: the actor reaches the store

    @Test
    void settingsAndArchiveCarryTheActor() throws SQLException {
        service.updateProject( lead, "PLAT", "New", "d", null );
        verify( store ).updateProject( 1, "New", "d", Autonomy.SUPERVISED, lead );
        service.archiveProject( admin, "PLAT" );
        verify( store ).archiveProject( 1, NOW, admin );
    }

    // ---- I3 and M2

    @Test
    void commentsAreRenderedWithMentionsAsDisplayNames() throws SQLException {
        when( store.addComment( any(), any( Actor.class ), anyString(), anyList() ) ).thenAnswer( inv ->
            new Comment( 1, 12, "u-7f3a", inv.getArgument( 2 ), NOW, null, null, null ) );
        final CommentView v = service.addComment( ana, "PLAT-3", "**done**, @Bo" );
        assertEquals( "**done**, @[Bo](user:id-bo)", v.body() );
        assertEquals( "**done**, @Bo", rendered.get( rendered.size() - 1 ) );
        assertEquals( "<p>**done**, @Bo</p>", v.bodyHtml() );
        assertEquals( List.of( "Bo" ), v.mentions() );
        final ArgumentCaptor< List< String > > mentioned = ArgumentCaptor.forClass( List.class );
        verify( store ).addComment( any(), any( Actor.class ), anyString(), mentioned.capture() );
        assertEquals( List.of( "u-c01d" ), mentioned.getValue() );
    }

    @Test
    void theItemViewRendersItsComments() throws SQLException {
        when( store.comments( 12 ) ).thenReturn( List.of(
            new Comment( 1, 12, "u-7f3a", "see @[x](user:id-ana)", NOW, null, null, null ),
            new Comment( 2, 12, "u-7f3a", "", NOW, null, NOW, null ) ) );
        final List< CommentView > comments = service.item( ana, "PLAT-3" ).comments();
        assertEquals( "<p>see @Ana Lima</p>", comments.get( 0 ).bodyHtml() );
        assertEquals( "", comments.get( 1 ).bodyHtml() );
        assertTrue( comments.get( 1 ).mentions().isEmpty() );
    }

    @Test
    void editingACommentRecomputesItsMentions() throws SQLException {
        when( store.comment( 5 ) ).thenReturn( Optional.of( new Comment( 5, 12, "u-7f3a", "hi @Bo", NOW, null,
            null, null ) ) );
        when( store.editComment( eq( 5L ), anyString(), any( Actor.class ), anyList() ) ).thenAnswer( inv ->
            new Comment( 5, 12, "u-7f3a", inv.getArgument( 1 ), NOW, NOW, null, null ) );
        service.editComment( ana, 5, "now @[Ana](user:id-ana) only" );
        verify( store ).editComment( eq( 5L ), eq( "now @[Ana](user:id-ana) only" ), eq( ana ),
            eq( List.of( "u-7f3a" ) ) );
    }

    // ---- I4 and M11

    @Test
    void parentAndProductEventsAreShownAsKeys() throws SQLException {
        final Item parent = new Item( 30, 1, "PLAT-7", WorkLevel.STORY, null, null, WorkKind.SOFTWARE, "p", "",
            WorkStatus.DRAFT, 3, 1, null, null, "x", null, null, null, 1, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
        final Item foreign = new Item( 40, 2, "OPS-1", WorkLevel.STORY, null, null, WorkKind.SOFTWARE, "p", "",
            WorkStatus.DRAFT, 3, 1, null, null, "x", null, null, null, 1, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
        when( store.itemById( 30 ) ).thenReturn( Optional.of( parent ) );
        when( store.itemById( 40 ) ).thenReturn( Optional.of( foreign ) );
        when( store.products() ).thenReturn( List.of( new Product( 3, "web", "Web", null, null, null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) ) );
        when( store.itemEvents( 12 ) ).thenReturn( List.of(
            field( 1, "parent_id", null, "30" ), field( 2, "parent_id", "30", "40" ),
            field( 3, "product_id", "3", null ), field( 4, "priority", "3", "1" ) ) );
        final List< WorkEvent > e = service.events( ana, "PLAT-3" );
        assertEquals( "parent", e.get( 0 ).note() );
        assertNull( e.get( 0 ).fromValue() );
        assertEquals( "PLAT-7", e.get( 0 ).toValue() );
        assertEquals( "unknown", e.get( 1 ).toValue(), "never a key from another project" );
        assertEquals( "product", e.get( 2 ).note() );
        assertEquals( "web", e.get( 2 ).fromValue() );
        assertEquals( "priority", e.get( 3 ).note() );
    }

    private static WorkEvent field( final long id, final String note, final String from, final String to ) {
        return new WorkEvent( id, 1, 12L, "PLAT-3", NOW, "u-7f3a", Actor.Kind.HUMAN, null, WorkEvent.FIELD, from, to,
            note );
    }

    @Test
    void activityNarrowsToAnItemOfTheProjectOnly() throws SQLException {
        service.activity( ana, "PLAT", null, null, "PLAT-3", null, 10 );
        verify( store ).projectEvents( 1, null, null, 12L, null, false, 10 );
        final Project ops = new Project( 2, "OPS", "Ops", "", Autonomy.SUPERVISED, NOW, null, null );
        when( store.project( "OPS" ) ).thenReturn( Optional.of( ops ) );
        assertThrows( WorkNotFoundException.class, () -> service.activity( ana, "PLAT", null, null, "OPS-1", null, 10 ) );
        assertThrows( WorkNotFoundException.class, () -> service.activity( ana, "PLAT", null, null, "PLAT-99", null, 10 ) );
    }

    // ---- M8 and M9

    @Test
    void keysTheWorkPagesUseAreReserved() {
        assertEquals( "key", invalid( () -> service.createProject( admin, "ITEM", "n", "" ) ).field() );
        assertEquals( "key", invalid( () -> service.createProject( admin, "PRODUCTS", "n", "" ) ).field() );
    }

    @Test
    void aMemberSeesOnlyKeyNameAndArchivedOfAProduct() throws SQLException {
        final Product full = new Product( 3, "web", "Web", "https://git.example/web", "main", "WebHub",
            Autonomy.DELEGATED, DeployGate.HUMAN, NOW );
        when( store.products() ).thenReturn( List.of( full ) );
        when( store.visibleProjects( ana ) ).thenReturn( List.of( plat ) );
        final Product seen = service.listProducts( ana ).get( 0 );
        assertEquals( "web", seen.key() );
        assertEquals( "Web", seen.name() );
        assertEquals( NOW, seen.archivedAt() );
        assertNull( seen.repoUrl() );
        assertNull( seen.wikiCluster() );
        assertNull( seen.autonomyCeiling() );
        assertEquals( full, service.listProducts( admin ).get( 0 ) );
    }
}

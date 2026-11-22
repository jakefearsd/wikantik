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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link WorkService} over a mocked {@link WorkStore}: visibility, policy and validation. */
class WorkServiceTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );
    /** People whose display name, in this fixture, is their login. */
    private static final Set< String > KNOWN = Set.of( "alice", "bob", "carol", "vera", "lena", "root", "outsider" );

    private WorkStore store;
    private WorkService service;
    private Project plat;
    private Project ops;
    private Item epic;
    private Item story;
    private Item task;
    private Item opsTask;

    private final Actor outsider = Actor.human( "outsider", Set.of(), false );
    private final Actor viewer = Actor.human( "vera", Set.of(), false );
    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private final Actor bob = Actor.human( "bob", Set.of(), false );
    private final Actor lead = Actor.human( "lena", Set.of(), false );
    private final Actor admin = Actor.human( "root", Set.of(), true );

    private static Item item( final long id, final long projectId, final String key, final WorkLevel level,
                              final Long parentId ) {
        return new Item( id, projectId, key, level, parentId, null, WorkKind.SOFTWARE, "title " + key, "body",
            WorkStatus.DRAFT, 3, 1024, null, null, "alice", null, null, null, 4, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
    }

    private void role( final long projectId, final String login, final WorkRole role ) throws SQLException {
        when( store.roleFor( eq( projectId ), argThat( a -> a != null && login.equals( a.login() ) ) ) )
            .thenReturn( Optional.of( role ) );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        org.mockito.Mockito.lenient().when( store.acks() ).thenReturn( mock( WorkAckStore.class ) );
        org.mockito.Mockito.lenient().when( store.policyChain( org.mockito.ArgumentMatchers.anyLong() ) )
            .thenReturn( PolicyItems.chain( PolicyItems.task( null ), List.of(), Autonomy.DELEGATED, null ) );
        org.mockito.Mockito.when( store.claims() ).thenReturn( org.mockito.Mockito.mock( WorkClaimStore.class ) );
        final WorkDirectory directory = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return KNOWN.contains( name ) ? Optional.of( name ) : Optional.empty();
            }
        };
        service = new WorkService( store, markdown -> "<p>" + markdown + "</p>", directory,
            Clock.fixed( NOW, ZoneOffset.UTC ) );
        plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null );
        ops = new Project( 2, "OPS", "Ops", "", Autonomy.SUPERVISED, NOW, null );
        epic = item( 10, 1, "PLAT-1", WorkLevel.EPIC, null );
        story = item( 11, 1, "PLAT-2", WorkLevel.STORY, 10L );
        task = item( 12, 1, "PLAT-3", WorkLevel.TASK, 11L );
        opsTask = item( 20, 2, "OPS-1", WorkLevel.TASK, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.project( "OPS" ) ).thenReturn( Optional.of( ops ) );
        when( store.item( "PLAT-1" ) ).thenReturn( Optional.of( epic ) );
        when( store.item( "PLAT-2" ) ).thenReturn( Optional.of( story ) );
        when( store.item( "PLAT-3" ) ).thenReturn( Optional.of( task ) );
        when( store.item( "OPS-1" ) ).thenReturn( Optional.of( opsTask ) );
        when( store.itemById( 20 ) ).thenReturn( Optional.of( opsTask ) );
        when( store.itemById( 12 ) ).thenReturn( Optional.of( task ) );
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenAnswer( inv -> new WorkStore.ItemWrite( inv.getArgument( 1 ), null ) );
        when( store.createItem( any(), any(), any( Actor.class ) ) ).thenAnswer( inv -> {
            final Item d = inv.getArgument( 1 );
            return new Item( 99, 1, "PLAT-9", d.level(), d.parentId(), d.productId(), d.kind(), d.title(), d.body(),
                d.status(), d.priority(), 1024, d.dueOn(), d.assignee(), "alice", null, null, null, 1, NOW, NOW,
                null, null, null, null, null, null, null, null, null, null );
        } );
        role( 1, "vera", WorkRole.VIEWER );
        role( 1, "alice", WorkRole.MEMBER );
        role( 1, "bob", WorkRole.MEMBER );
        role( 1, "lena", WorkRole.LEAD );
    }

    private static Map< String, Object > changes( final Object... kv ) {
        final java.util.LinkedHashMap< String, Object > m = new java.util.LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            m.put( (String) kv[ i ], kv[ i + 1 ] );
        }
        return m;
    }

    private static ItemDraft draft( final WorkLevel level, final String parentKey, final String productKey,
                                    final String title, final String assignee ) {
        return new ItemDraft( "PLAT", level, parentKey, productKey, WorkKind.SOFTWARE, title, "", null, null,
            assignee, null, null, null );
    }

    @FunctionalInterface
    private interface Call { void run() throws Exception; }

    private static WorkValidationException invalid( final Call call ) {
        return assertThrows( WorkValidationException.class, call::run );
    }

    // ---- visibility

    @Test
    void nonMemberItemLookupIsNotFound() throws SQLException {
        assertThrows( WorkNotFoundException.class, () -> service.item( outsider, "PLAT-3" ) );
        verify( store, never() ).item( anyString() );
    }

    @Test
    void nonMemberBoardIsNotFound() throws SQLException {
        assertThrows( WorkNotFoundException.class, () -> service.board( outsider, "PLAT", null ) );
        verify( store, never() ).board( anyLong(), any() );
    }

    @Test
    void nonMemberAndUnknownProjectLookAlike() {
        final WorkNotFoundException unknown = assertThrows( WorkNotFoundException.class,
            () -> service.item( outsider, "NOPE-1" ) );
        final WorkNotFoundException hidden = assertThrows( WorkNotFoundException.class,
            () -> service.item( outsider, "PLAT-3" ) );
        assertEquals( unknown.getMessage().replace( "NOPE-1", "X" ), hidden.getMessage().replace( "PLAT-3", "X" ) );
    }

    @Test
    void viewerEditIsDeniedNotNotFound() {
        assertThrows( WorkDeniedException.class,
            () -> service.updateItem( viewer, "PLAT-3", new ItemPatch( 4, changes( "title", "x" ), null ) ) );
    }

    @Test
    void adminNeedsNoMembership() throws SQLException {
        final WorkService.ItemView view = service.item( admin, "PLAT-3" );
        assertEquals( "PLAT-3", view.item().key() );
        assertEquals( WorkRole.LEAD, view.viewerRole() );
        assertEquals( WorkRole.LEAD, service.roleIn( admin, "PLAT" ) );
        assertEquals( "<p>body</p>", view.bodyHtml() );
    }

    @Test
    void blankBodyIsNotRendered() throws SQLException {
        when( store.item( "PLAT-3" ) ).thenReturn( Optional.of( new Item( 12, 1, "PLAT-3", WorkLevel.TASK, 11L,
            null, WorkKind.SOFTWARE, "t", null, WorkStatus.DRAFT, 3, 1, null, null, "a", null, null, null, 1, NOW,
            NOW, null, null, null, null, null, null, null, null, null, null ) ) );
        assertEquals( "", service.item( alice, "PLAT-3" ).bodyHtml() );
    }

    @Test
    void commentIdFromInvisibleProjectIsNotFound() throws SQLException {
        when( store.comment( 5 ) ).thenReturn( Optional.of( new Comment( 5, 20, "zoe", "secret", NOW, null, null, null ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.editComment( outsider, 5, "x" ) );
        assertThrows( WorkNotFoundException.class, () -> service.removeComment( outsider, 5 ) );
        verify( store, never() ).editComment( anyLong(), anyString(), any( Actor.class ), anyList() );
        verify( store, never() ).removeComment( anyLong(), any( Actor.class ) );
    }

    @Test
    void linkIdFromInvisibleProjectIsNotFound() throws SQLException {
        when( store.link( 7 ) ).thenReturn( Optional.of( new Link( 7, 20, "url", "https://x.example" ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.removeLink( outsider, 7 ) );
        verify( store, never() ).removeLink( anyLong(), any( Actor.class ) );
    }

    // ---- validation

    @Test
    void parentInOtherProject() {
        assertEquals( "parentKey", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "parentKey", "OPS-1" ), null ) ) ).field() );
    }

    @Test
    void taskDirectlyUnderEpic() {
        assertEquals( "parentKey", invalid( () -> service.createItem( alice,
            draft( WorkLevel.TASK, "PLAT-1", null, "t", null ) ) ).field() );
    }

    @Test
    void itemParentedToItself() {
        assertEquals( "parentKey", invalid( () -> service.updateItem( alice, "PLAT-2", new ItemPatch( 4,
            changes( "parentKey", "PLAT-2" ), null ) ) ).field() );
    }

    @Test
    void epicWithProduct() throws SQLException {
        when( store.product( "web" ) ).thenReturn( Optional.of( new Product( 3, "web", "Web", null, null, null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) ) );
        assertEquals( "productKey", invalid( () -> service.createItem( alice,
            draft( WorkLevel.EPIC, null, "web", "t", null ) ) ).field() );
    }

    @Test
    void unknownProductIsInvalid() {
        assertEquals( "productKey", invalid( () -> service.createItem( alice,
            draft( WorkLevel.TASK, null, "nope", "t", null ) ) ).field() );
    }

    @Test
    void epicWithStatusReady() {
        assertEquals( "status", invalid( () -> service.updateItem( alice, "PLAT-1", new ItemPatch( 4,
            changes( "status", "ready" ), null ) ) ).field() );
    }

    @Test
    void unknownChangeKey() {
        assertEquals( "bogus", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "bogus", 1 ), null ) ) ).field() );
    }

    @Test
    void handoffWithoutNote() {
        assertEquals( "note", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "status", "in_progress", "assignee", "bob" ), "  " ) ) ).field() );
    }

    @Test
    void blankTitle() {
        assertEquals( "title", invalid( () -> service.createItem( alice,
            draft( WorkLevel.TASK, null, null, "   ", null ) ) ).field() );
    }

    @Test
    void titleOver200Chars() {
        assertEquals( "title", invalid( () -> service.createItem( alice,
            draft( WorkLevel.TASK, null, null, "x".repeat( 201 ), null ) ) ).field() );
    }

    @Test
    void assigneeNotAProjectMember() {
        assertEquals( "assignee", invalid( () -> service.createItem( alice,
            draft( WorkLevel.TASK, null, null, "t", "zed" ) ) ).field() );
    }

    @Test
    void priorityOutOfRange() {
        assertEquals( "priority", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "priority", 9 ), null ) ) ).field() );
    }

    @Test
    void badValueTypeNamesField() {
        assertEquals( "title", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "title", 5 ), null ) ) ).field() );
        assertEquals( "dueOn", invalid( () -> service.updateItem( alice, "PLAT-3", new ItemPatch( 4,
            changes( "dueOn", "not-a-date" ), null ) ) ).field() );
    }

    // ---- behaviour

    @Test
    void archivedProjectRejectsWritesWithConflict() throws SQLException {
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( new Project( 1, "PLAT", "Platform", "",
            Autonomy.SUPERVISED, NOW, NOW ) ) );
        assertThrows( WorkConflictException.class,
            () -> service.createItem( alice, draft( WorkLevel.TASK, null, null, "t", null ) ) );
        assertThrows( WorkConflictException.class, () -> service.updateItem( alice, "PLAT-3",
            new ItemPatch( 4, changes( "title", "x" ), null ) ) );
        assertThrows( WorkConflictException.class, () -> service.addComment( alice, "PLAT-3", "hi" ) );
        assertThrows( WorkConflictException.class, () -> service.addLink( alice, "PLAT-3", "url", "https://a.b" ) );
        assertThrows( WorkConflictException.class,
            () -> service.putMember( lead, "PLAT", "bob", "user", WorkRole.MEMBER ) );
        assertThrows( WorkConflictException.class,
            () -> service.updateProject( lead, "PLAT", "n", "d", null ) );
        assertEquals( "PLAT-3", service.item( alice, "PLAT-3" ).item().key() );
    }

    @Test
    void aMemberCannotLoosenALevelOrSetTheLeadOnlyFields() throws SQLException {
        when( store.policyChain( org.mockito.ArgumentMatchers.anyLong() ) )
            .thenReturn( PolicyItems.chain( PolicyItems.task( null ), List.of(), Autonomy.SUPERVISED, null ) );
        assertEquals( "Making this looser than it inherits takes a project lead.",
            assertThrows( WorkDeniedException.class, () -> service.updateItem( alice, "PLAT-3",
                new ItemPatch( 4, changes( "autonomy", "delegated" ), null ) ) ).reason() );
        assertThrows( WorkDeniedException.class, () -> service.updateItem( alice, "PLAT-3",
            new ItemPatch( 4, changes( "allowDirect", true ), null ) ) );
        assertThrows( WorkDeniedException.class, () -> service.updateItem( alice, "PLAT-3",
            new ItemPatch( 4, changes( "doneMeans", "in_production" ), null ) ) );
    }

    @Test
    void leadMaySetPolicyFields() throws SQLException {
        service.updateItem( lead, "PLAT-3", new ItemPatch( 4, changes( "autonomy", "delegated" ), null ) );
        verify( store ).writeItem( any(), any(), any( Actor.class ), any(), eq( false ) );
    }

    @Test
    void viewerMayNotMoveButMemberMay() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> service.updateItem( viewer, "PLAT-3",
            new ItemPatch( 4, changes( "status", "ready" ), null ) ) );
        service.updateItem( alice, "PLAT-3", new ItemPatch( 4, changes( "status", "ready" ), null ) );
    }

    @Test
    void statusChangeWithoutRankGoesToEndOfNewColumn() throws SQLException {
        when( store.maxRank( 1, WorkStatus.READY ) ).thenReturn( 3072.0 );
        final WorkService.ItemView v = service.updateItem( alice, "PLAT-3",
            new ItemPatch( 4, changes( "status", "ready" ), null ) );
        assertEquals( 4096.0, v.item().rank() );
    }

    @Test
    void expectedVersionIsPassedToTheStore() throws SQLException {
        service.updateItem( alice, "PLAT-3", new ItemPatch( 2, changes( "title", "x" ), null ) );
        final ArgumentCaptor< Item > current = ArgumentCaptor.forClass( Item.class );
        verify( store ).writeItem( current.capture(), any(), any( Actor.class ), any(), eq( false ) );
        assertEquals( 2, current.getValue().version() );
    }

    @Test
    void staleVersionSurfacesAsConflict() throws SQLException {
        when( store.writeItem( any(), any(), any( Actor.class ), any(), org.mockito.ArgumentMatchers.anyBoolean() ) )
            .thenThrow( new WorkConflictException( "stale" ) );
        assertThrows( WorkConflictException.class, () -> service.updateItem( alice, "PLAT-3",
            new ItemPatch( 1, changes( "title", "x" ), null ) ) );
    }

    @Test
    void mentionsParsedFromBodyAreLimitedToMembers() throws SQLException {
        when( store.addComment( any(), any( Actor.class ), anyString(), anyList() ) )
            .thenReturn( new Comment( 1, 12, "alice", "x", NOW, null, null, null ) );
        role( 1, "carol", WorkRole.VIEWER );
        service.addComment( alice, "PLAT-3", "hi @bob, @outsider and @bob again, mail a@b.com; @carol." );
        final ArgumentCaptor< List< String > > mentioned = ArgumentCaptor.forClass( List.class );
        verify( store ).addComment( any(), any( Actor.class ), anyString(), mentioned.capture() );
        assertEquals( List.of( "bob", "carol" ), mentioned.getValue() );
    }

    @Test
    void onlyAuthorEditsComment() throws SQLException {
        when( store.comment( 5 ) ).thenReturn( Optional.of( new Comment( 5, 12, "alice", "x", NOW, null, null, null ) ) );
        when( store.editComment( eq( 5L ), eq( "y" ), eq( alice ), anyList() ) )
            .thenReturn( new Comment( 5, 12, "alice", "y", NOW, NOW, null, null ) );
        assertThrows( WorkDeniedException.class, () -> service.editComment( bob, 5, "y" ) );
        assertThrows( WorkDeniedException.class, () -> service.editComment( lead, 5, "y" ) );
        assertEquals( "y", service.editComment( alice, 5, "y" ).body() );
    }

    @Test
    void authorOrLeadRemovesComment() throws SQLException {
        when( store.comment( 5 ) ).thenReturn( Optional.of( new Comment( 5, 12, "alice", "x", NOW, null, null, null ) ) );
        assertThrows( WorkDeniedException.class, () -> service.removeComment( bob, 5 ) );
        when( store.comment( 6 ) ).thenReturn( Optional.of( new Comment( 6, 12, "vera", "x", NOW, null, null, null ) ) );
        assertThrows( WorkDeniedException.class, () -> service.removeComment( viewer, 6 ),
            "a demoted viewer may not remove even their own comment" );
        service.removeComment( alice, 5 );
        service.removeComment( lead, 5 );
        service.removeComment( admin, 5 );
        verify( store, org.mockito.Mockito.times( 3 ) ).removeComment( eq( 5L ), any( Actor.class ) );
    }

    @Test
    void waitingOnExcludesInvisibleProjects() throws SQLException {
        when( store.visibleProjects( alice ) ).thenReturn( List.of( plat ) );
        final Item older = new Item( 12, 1, "PLAT-3", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "",
            WorkStatus.DRAFT, 3, 1, null, "alice", "x", null, null, null, 1, NOW, NOW.minusSeconds( 60 ), null, null, null, null, null, null, null, null, null, null );
        final Item newer = new Item( 13, 1, "PLAT-4", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "",
            WorkStatus.DRAFT, 3, 1, null, null, "x", null, null, null, 1, NOW, NOW, null, null, null, null, null, null, null, null, null, null );
        when( store.assignedOpen( "alice", Set.of( 1L ) ) ).thenReturn( List.of( older ) );
        when( store.mentioning( "alice", Set.of( 1L ) ) ).thenReturn( List.of( newer, older ) );
        when( store.notifyStores() ).thenReturn( new WorkNotifyStores( null, null, null, null, null, null, mock( WorkCodeHostStore.class ), null, null ) );
        assertEquals( List.of( "PLAT-4", "PLAT-3" ),
            service.waitingOn( alice ).stream().map( w -> w.item().key() ).toList() );
        verify( store, never() ).assignedOpen( eq( "alice" ), eq( Set.of( 1L, 2L ) ) );
    }

    @Test
    void waitingOnWithNoVisibleProjectsIsEmpty() throws SQLException {
        assertTrue( service.waitingOn( outsider ).isEmpty() );
        verify( store, never() ).assignedOpen( anyString(), any() );
    }

    @Test
    void createProjectRequiresAdminAndAddsNoMembers() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> service.createProject( lead, "NEW", "New", "" ) );
        when( store.createProject( "NEW", "New", "" ) )
            .thenReturn( new Project( 3, "NEW", "New", "", Autonomy.SUPERVISED, NOW, null ) );
        assertEquals( "NEW", service.createProject( admin, "NEW", "New", "" ).key() );
        verify( store, never() ).putMember( anyLong(), anyString(), anyString(), any(), any( Actor.class ), any() );
    }

    @Test
    void createProjectRejectsBadKeyAndDuplicate() throws SQLException {
        assertEquals( "key", invalid( () -> service.createProject( admin, "bad key", "n", "" ) ).field() );
        assertThrows( WorkConflictException.class, () -> service.createProject( admin, "PLAT", "n", "" ) );
    }

    @Test
    void memberChangesNeedLead() throws SQLException {
        assertThrows( WorkDeniedException.class,
            () -> service.putMember( alice, "PLAT", "bob", "user", WorkRole.LEAD ) );
        service.putMember( lead, "PLAT", "bob", "user", WorkRole.LEAD );
        verify( store ).putMember( 1, "bob", "user", WorkRole.LEAD, Actor.human( "lena", java.util.Set.of(), false ), java.util.List.of() );
        assertThrows( WorkNotFoundException.class,
            () -> service.putMember( outsider, "PLAT", "bob", "user", WorkRole.LEAD ) );
    }

    @Test
    void listProductsIsEmptyWithoutAnyMembership() throws SQLException {
        when( store.products() ).thenReturn( List.of( new Product( 3, "web", "Web", null, null, null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) ) );
        when( store.visibleProjects( outsider ) ).thenReturn( List.of() );
        when( store.visibleProjects( alice ) ).thenReturn( List.of( plat ) );
        assertTrue( service.listProducts( outsider ).isEmpty() );
        assertEquals( 1, service.listProducts( alice ).size() );
    }

    @Test
    void productChangesNeedAdmin() {
        final Product p = new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED, DeployGate.HUMAN,
            null );
        assertThrows( WorkDeniedException.class, () -> service.createProduct( lead, p ) );
        assertThrows( WorkDeniedException.class, () -> service.archiveProduct( lead, "web" ) );
    }

    @Test
    void linkValidation() {
        assertEquals( "kind", invalid( () -> service.addLink( alice, "PLAT-3", "weird", "x" ) ).field() );
        assertEquals( "target", invalid( () -> service.addLink( alice, "PLAT-3", "url", "javascript:alert(1)" ) )
            .field() );
    }

    @Test
    void blockedByTargetInInvisibleProjectIsRejected() throws SQLException {
        final WorkValidationException hidden = invalid( () -> service.addLink( alice, "PLAT-3", "blocked_by",
            "OPS-1" ) );
        final WorkValidationException unknown = invalid( () -> service.addLink( alice, "PLAT-3", "blocked_by",
            "OPS-999" ) );
        assertEquals( "target", hidden.field() );
        assertEquals( hidden.getMessage(), unknown.getMessage() );
        verify( store, never() ).addLink( any(), anyString(), anyString(), any( Actor.class ) );
    }

    @Test
    void blockedByMustBeSameProjectItemNotSelf() throws SQLException {
        assertEquals( "target", invalid( () -> service.addLink( alice, "PLAT-3", "blocked_by", "plat-3" ) ).field() );
        assertEquals( "target", invalid( () -> service.addLink( alice, "PLAT-3", "blocked_by", "nonsense" ) ).field() );
        when( store.addLink( any(), anyString(), anyString(), any( Actor.class ) ) )
            .thenReturn( new Link( 1, 12, "blocked_by", "PLAT-2" ) );
        service.addLink( alice, "PLAT-3", "blocked_by", "plat-2" );
        verify( store ).addLink( any(), eq( "blocked_by" ), eq( "PLAT-2" ), eq( alice ) );
    }
}

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

import com.wikantik.work.WorkClaimsService.ClaimResult;
import com.wikantik.work.WorkClaimsService.Handoff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Claims at the service over a mocked store: policy first, then validation, then the store, then the listener. */
class WorkClaimsServiceTest {

    private static final Instant NOW = Instant.parse( "2026-11-16T12:00:00Z" );
    private static final Instant UNTIL = Instant.parse( "2026-11-16T16:00:00Z" );

    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor codex = Actor.agent( 7, "codex@docker2" );
    private final Actor other = Actor.agent( 8, "other@host" );
    private final Actor viewerAgent = Actor.agent( 9, "viewer@host" );

    private WorkStore store;
    private WorkClaimStore claims;
    private WorkService service;
    private Project plat;
    private final List< ClaimOutcome > outcomes = new ArrayList<>();

    private static Item item( final String key, final WorkStatus status, final String queue, final Long holder ) {
        return new Item( 12, 1, key, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "title", "body", status, 3, 1024,
            null, null, "lena", null, null, null, 4, NOW, NOW, null, queue, holder, holder == null ? null : NOW,
            holder == null ? null : UNTIL, null );
    }

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        roles( 1, "lena", WorkRole.LEAD );
        roles( 1, "codex@docker2", WorkRole.MEMBER );
        roles( 1, "other@host", WorkRole.MEMBER );
        roles( 1, "viewer@host", WorkRole.VIEWER );
        when( store.queue( "implementer" ) ).thenReturn( Optional.of( new Queue( "implementer", "I", "", null ) ) );
        when( store.queue( "old" ) ).thenReturn( Optional.of( new Queue( "old", "O", "", NOW ) ) );
        when( store.agent( 7 ) ).thenReturn( Optional.of( new Agent( 7, "codex@docker2", "", "", 1,
            List.of( "implementer" ), NOW, null ) ) );
        service = build( plat );
    }

    private WorkService build( final Project project ) throws SQLException {
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( project ) );
        return new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return Optional.of( name.toLowerCase( java.util.Locale.ROOT ) );
            }
        }, AgentKeys.NONE, Clock.fixed( NOW, ZoneOffset.UTC ), new WorkSettings( Duration.ofMinutes( 240 ) ),
            new WorkListener() {
                @Override public void claim( final ClaimOutcome o ) { outcomes.add( o ); }
            } );
    }

    private void roles( final long projectId, final String login, final WorkRole role ) throws SQLException {
        when( store.roleFor( eq( projectId ), argThat( a -> a != null && login.equals( a.login() ) ) ) )
            .thenReturn( Optional.of( role ) );
    }

    private Item stored( final Item i ) throws SQLException {
        when( store.item( i.key() ) ).thenReturn( Optional.of( i ) );
        when( store.itemById( i.id() ) ).thenReturn( Optional.of( i ) );
        return i;
    }

    @Test
    void humanClaimDenied() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        assertThrows( WorkDeniedException.class, () -> service.claims().claim( lena, "PLAT-1" ) );
        verify( claims, never() ).claim( any(), any(), any() );
    }

    @Test
    void viewerAgentClaimDenied() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        assertThrows( WorkDeniedException.class, () -> service.claims().claim( viewerAgent, "PLAT-1" ) );
        verify( claims, never() ).claim( any(), any(), any() );
    }

    @Test
    void refusedClaimNamesHolder() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", 8L ) );
        when( store.agent( 8 ) ).thenReturn( Optional.of( new Agent( 8, "other@host", "", "", 2, List.of(), NOW,
            null ) ) );
        when( claims.claim( any(), eq( codex ), any() ) ).thenReturn( Optional.empty() );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.claims().claim( codex, "PLAT-1" ) );
        assertEquals( WorkConflictException.NOT_CLAIMABLE, e.reason() );
        assertTrue( e.getMessage().contains( "other@host" ), e.getMessage() );
        assertTrue( e.getMessage().contains( UNTIL.toString() ), e.getMessage() );
        assertFalse( e.getMessage().contains( "lena" ), e.getMessage() );
    }

    @Test
    void refusalReasonsAreSpecific() throws SQLException {
        when( claims.claim( any(), eq( codex ), any() ) ).thenReturn( Optional.empty() );
        stored( item( "PLAT-1", WorkStatus.DRAFT, "implementer", null ) );
        assertTrue( refusal( "PLAT-1" ).contains( "ready" ) );
        stored( item( "PLAT-2", WorkStatus.READY, "implementer", null ) );
        when( store.isBlocked( 12 ) ).thenReturn( true );
        assertTrue( refusal( "PLAT-2" ).contains( "blocked" ) );
        when( store.isBlocked( 12 ) ).thenReturn( false );
        stored( item( "PLAT-3", WorkStatus.READY, "elsewhere", null ) );
        assertTrue( refusal( "PLAT-3" ).contains( "queue you serve" ) );
        stored( item( "PLAT-4", WorkStatus.READY, "implementer", null ) );
        assertEquals( "This item cannot be claimed right now.", refusal( "PLAT-4" ) );
    }

    private String refusal( final String key ) {
        return assertThrows( WorkConflictException.class, () -> service.claims().claim( codex, key ) ).getMessage();
    }

    @Test
    void refusedClaimCountsRefused() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        when( claims.claim( any(), any(), any() ) ).thenReturn( Optional.empty() );
        assertThrows( WorkConflictException.class, () -> service.claims().claim( codex, "PLAT-1" ) );
        assertEquals( List.of( ClaimOutcome.REFUSED ), outcomes );
    }

    @Test
    void freshClaimReportedAndUsesConfiguredExpiry() throws SQLException {
        final Item free = stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        when( claims.claim( any(), any(), any() ) ).thenReturn( Optional.of( item( "PLAT-1", WorkStatus.IN_PROGRESS,
            "implementer", 7L ) ) );
        final ClaimResult r = service.claims().claim( codex, "PLAT-1" );
        assertFalse( r.renewed() );
        assertEquals( List.of( ClaimOutcome.CLAIMED ), outcomes );
        verify( claims ).claim( eq( free ), eq( codex ), eq( Duration.ofMinutes( 240 ) ) );
    }

    @Test
    void renewalReported() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.IN_PROGRESS, "implementer", 7L ) );
        when( claims.claim( any(), any(), any() ) ).thenReturn( Optional.of( item( "PLAT-1", WorkStatus.IN_PROGRESS,
            "implementer", 7L ) ) );
        assertTrue( service.claims().claim( codex, "PLAT-1" ).renewed() );
        assertEquals( List.of( ClaimOutcome.RENEWED ), outcomes );
    }

    @Test
    void claimOnArchivedProjectIs409Archived() throws SQLException {
        final Project archived = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, NOW );
        service = build( archived );
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.claims().claim( codex, "PLAT-1" ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
    }

    @Test
    void otherAgentCannotReleaseOrHandOff() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.IN_PROGRESS, "implementer", 7L ) );
        assertThrows( WorkDeniedException.class, () -> service.claims().release( other, "PLAT-1", "mine now" ) );
        assertThrows( WorkDeniedException.class, () -> service.claims().handoff( other, "PLAT-1",
            new Handoff( 4, WorkStatus.REVIEW, null, "implementer", "take it" ) ) );
        verify( claims, never() ).release( any(), any(), any() );
        verify( store, never() ).handoff( any(), any(), any(), any() );
    }

    @Test
    void personMayReleaseAnyClaim() throws SQLException {
        final Item held = stored( item( "PLAT-1", WorkStatus.IN_PROGRESS, "implementer", 7L ) );
        when( claims.release( any(), any(), any() ) ).thenReturn( Optional.of( item( "PLAT-1", WorkStatus.READY,
            "implementer", null ) ) );
        final WorkService.ItemView v = service.claims().release( lena, "PLAT-1", "stuck" );
        assertEquals( WorkStatus.READY, v.item().status() );
        assertEquals( List.of( ClaimOutcome.RELEASED ), outcomes );
        verify( claims ).release( held, lena, "stuck" );
    }

    @Test
    void releaseWithoutClaimIsNotClaimed() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> service.claims().release( lena, "PLAT-1", null ) );
        assertEquals( WorkConflictException.NOT_CLAIMED, e.reason() );
        assertTrue( outcomes.isEmpty() );
    }

    private WorkValidationException rejected( final Handoff h ) {
        return assertThrows( WorkValidationException.class, () -> service.claims().handoff( lena, "PLAT-1", h ) );
    }

    @Test
    void handoffNeedsExactlyOneTarget() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        assertEquals( "assignee", rejected( new Handoff( 4, null, "Mo", "implementer", "n" ) ).field() );
        assertEquals( "assignee", rejected( new Handoff( 4, null, null, null, "n" ) ).field() );
        assertEquals( "assignee", rejected( new Handoff( 4, null, " ", " ", "n" ) ).field() );
    }

    @Test
    void handoffWithoutNoteRejected() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        assertEquals( "note", rejected( new Handoff( 4, null, "mo", null, null ) ).field() );
        assertEquals( "note", rejected( new Handoff( 4, null, "mo", null, "  " ) ).field() );
        assertEquals( "note", rejected( new Handoff( 4, null, "mo", null, "x".repeat( 2001 ) ) ).field() );
    }

    @Test
    void handoffToRetiredOrUnknownQueueRejected() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, null, null ) );
        final WorkValidationException retired = rejected( new Handoff( 4, null, null, "old", "n" ) );
        assertEquals( "queue", retired.field() );
        assertEquals( "No such queue.", retired.getMessage() );
        assertEquals( "No such queue.", rejected( new Handoff( 4, null, null, "ghost", "n" ) ).getMessage() );
    }

    @Test
    void handoffToNonMemberRejected() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        final WorkValidationException e = rejected( new Handoff( 4, null, "zed", null, "n" ) );
        assertEquals( "assignee", e.field() );
    }

    @Test
    void handoffToSameTargetRejected() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        assertEquals( "queue", rejected( new Handoff( 4, WorkStatus.REVIEW, null, "implementer", "n" ) ).field() );
        verify( store, never() ).handoff( any(), any(), any(), any() );
    }

    @Test
    void handoffMovesToThePersonAndKeepsTheNote() throws SQLException {
        final Item cur = stored( item( "PLAT-1", WorkStatus.READY, "implementer", null ) );
        when( store.handoff( any(), any(), any(), any() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );
        service.claims().handoff( lena, "PLAT-1", new Handoff( 4, WorkStatus.REVIEW, "Lena", null, "review it" ) );
        final ArgumentCaptor< Item > next = ArgumentCaptor.forClass( Item.class );
        verify( store ).handoff( any(), next.capture(), eq( lena ), eq( "review it" ) );
        assertEquals( "lena", next.getValue().assignee() );
        assertEquals( null, next.getValue().queue() );
        assertEquals( WorkStatus.REVIEW, next.getValue().status() );
        assertEquals( cur.id(), next.getValue().id() );
    }

    @Test
    void bothAssigneeAndQueueInPatchRejected() throws SQLException {
        stored( item( "PLAT-1", WorkStatus.READY, null, null ) );
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> service.updateItem( lena,
            "PLAT-1", new ItemPatch( 4, Map.of( "assignee", "lena", "queue", "implementer" ), null ) ) );
        assertEquals( "queue", e.field() );
        assertEquals( "Assign a person or a queue, not both.", e.getMessage() );
    }

    @Test
    void claimableLimitClampedTo50() throws SQLException {
        service.claims().claimable( lena, "PLAT", null, 500 );
        verify( claims ).claimable( isNull(), any(), isNull(), eq( 50 ) );
        service.claims().claimable( lena, "PLAT", null, 0 );
        verify( claims ).claimable( isNull(), any(), isNull(), eq( 20 ) );
        service.claims().claimable( lena, "PLAT", "implementer", 7 );
        verify( claims ).claimable( isNull(), any(), eq( "implementer" ), eq( 7 ) );
    }

    @Test
    void agentClaimableUsesItsIdAndOnlyProjectsWhereItIsAMember() throws SQLException {
        when( store.visibleProjects( codex ) ).thenReturn( List.of( plat ) );
        when( store.visibleProjects( viewerAgent ) ).thenReturn( List.of( plat ) );
        service.claims().claimable( codex, null, null, 10 );
        verify( claims ).claimable( eq( 7L ), eq( Set.of( 1L ) ), isNull(), anyInt() );
        assertTrue( service.claims().claimable( viewerAgent, null, null, 10 ).isEmpty() );
    }

    @Test
    void myClaimsIsEmptyForAPerson() throws SQLException {
        assertTrue( service.claims().myClaims( lena ).isEmpty() );
        verify( claims, never() ).claimsHeldBy( anyInt(), any() );
    }

    @Test
    void sweepReportsEachExpiry() throws SQLException {
        when( claims.sweepExpiredClaims( 500 ) ).thenReturn( List.of( item( "PLAT-1", WorkStatus.READY, "implementer",
            null ), item( "PLAT-2", WorkStatus.READY, "implementer", null ) ) );
        assertEquals( 2, service.claims().sweepExpiredClaims() );
        assertEquals( List.of( ClaimOutcome.EXPIRED, ClaimOutcome.EXPIRED ), outcomes );
    }

    @Test
    void waitingOnForAnAgentIsEmptyWithoutAnyLookup() throws SQLException {
        assertTrue( service.waitingOn( codex ).isEmpty() );
        verify( store, never() ).assignedOpen( any(), any() );
        verify( store, never() ).mentioning( any(), any() );
    }
}

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

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification service for callers that must not reach the store (an agent has no inbox), its input checks, and
 * the wording of a hand-off line at its edges.
 */
class NoticesUnitTest {

    private static final Instant NOW = Instant.parse( "2026-11-15T12:00:00Z" );
    private static final Function< String, String > NAMES = login -> "Name of " + login;

    private WorkStore store;
    private WorkNotificationStore notifications;
    private WorkWatchStore watches;
    private WorkNotificationsService service;
    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor agent = Actor.agent( 9, "bot", lena );

    @BeforeEach
    void setUp() throws SQLException {
        store = mock( WorkStore.class );
        final WorkNotifyStores holder = mock( WorkNotifyStores.class );
        notifications = mock( WorkNotificationStore.class );
        watches = mock( WorkWatchStore.class );
        when( store.notifyStores() ).thenReturn( holder );
        when( holder.notifications() ).thenReturn( notifications );
        when( holder.watches() ).thenReturn( watches );
        when( holder.intake() ).thenReturn( mock( WorkIntakeStore.class ) );
        final Project plat = new Project( 1, "PLAT", "Platform", "", Autonomy.SUPERVISED, NOW, null );
        final Item task = new Item( 12, 1, "PLAT-3", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "b",
            WorkStatus.READY, 3, 1, null, null, "alice", null, null, null, 1, NOW, NOW, null, null, null, null, null,
            null, null, null, null, null );
        when( store.project( "PLAT" ) ).thenReturn( Optional.of( plat ) );
        when( store.item( "PLAT-3" ) ).thenReturn( Optional.of( task ) );
        for ( final Actor a : new Actor[] { alice, lena, agent } ) {
            when( store.roleFor( eq( 1L ), argThat( x -> x != null && x.login().equals( a.login() ) ) ) )
                .thenReturn( Optional.of( WorkRole.MEMBER ) );
        }
        final WorkService svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.fixed( NOW, ZoneOffset.UTC ) );
        service = svc.features().notifications();
    }

    // ---- an agent has no inbox

    @Test
    void anAgentHasNoInboxNoUnreadNoWatchesAndNoMailPreference() throws SQLException {
        assertTrue( service.inbox( agent, null, 10 ).isEmpty() );
        assertEquals( 0, service.unread( agent ) );
        service.markAllRead( agent );
        service.markRead( agent, "PLAT-3" );
        assertEquals( WatchState.NONE, service.watchState( agent, "PLAT-3" ) );
        assertEquals( new NotifyPreference( EmailMode.OFF, false, false ), service.preference( agent ) );
        verify( notifications, never() ).inbox( any(), any(), any(), anyInt() );
        verify( notifications, never() ).unread( any(), any() );
        verify( notifications, never() ).markRead( any(), org.mockito.ArgumentMatchers.anyLong(), any() );
        verify( notifications, never() ).markAllRead( any(), any(), any() );
        verify( notifications, never() ).emailMode( any() );
        verify( watches, never() ).stateOf( org.mockito.ArgumentMatchers.anyLong(), any() );
    }

    @Test
    void aPersonsReadsAndMarksGoToTheirOwnRows() throws SQLException {
        when( notifications.unread( eq( "alice" ), any() ) ).thenReturn( 4 );
        assertEquals( 4, service.unread( alice ) );
        service.markRead( alice, "PLAT-3" );
        verify( notifications ).markRead( "alice", 12L, NOW );
        service.markAllRead( alice );
        verify( notifications ).markAllRead( eq( "alice" ), any(), eq( NOW ) );
        when( watches.stateOf( 12L, "alice" ) ).thenReturn( WatchState.MUTED );
        assertEquals( WatchState.MUTED, service.watchState( alice, "PLAT-3" ) );
        when( notifications.emailMode( "alice" ) ).thenReturn( EmailMode.DAILY );
        assertEquals( EmailMode.DAILY, service.preference( alice ).email() );
    }

    @Test
    void theHostsNoticePortsDefaultToNothingWired() throws SQLException {
        assertFalse( service.preference( alice ).emailAvailable() );
        assertFalse( service.preference( alice ).webhooksAvailable() );
        assertEquals( WorkNotifyPorts.NONE.settings(), service.settings() );
    }

    private NotifyPreference preference( final Actor a ) {
        try {
            return service.preference( a );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( e );
        }
    }

    @Test
    void aWatchStateAndAnEmailModeAreRequired() throws SQLException {
        assertEquals( "state", assertThrows( WorkValidationException.class,
            () -> service.setWatch( alice, "PLAT-3", null ) ).field() );
        assertEquals( "email", assertThrows( WorkValidationException.class,
            () -> service.setPreference( alice, null ) ).field() );
        assertThrows( WorkDeniedException.class, () -> service.setWatch( agent, "PLAT-3", WatchState.WATCHING ) );
        assertThrows( WorkDeniedException.class, () -> service.setPreference( agent, EmailMode.OFF ) );
        verify( watches, never() ).subscribe( org.mockito.ArgumentMatchers.anyLong(), any() );
        verify( notifications, never() ).setEmailMode( any(), any() );
        assertEquals( EmailMode.OFF, preference( agent ).email() );
    }

    @Test
    void dispatchReportsHowManyEventsWereRead() throws SQLException {
        when( notifications.dispatch( eq( 25 ), any() ) ).thenReturn( 7 );
        assertEquals( 7, service.dispatch( 25 ) );
    }

    // ---- the readable set

    @Test
    void aReadableSetWithNothingInItIsNone() {
        assertTrue( new WorkNotificationStore.Readable( null, null ).none() );
        assertTrue( new WorkNotificationStore.Readable( Set.of(), Set.of() ).none() );
        assertFalse( new WorkNotificationStore.Readable( Set.of( 1L ), null ).none() );
        assertFalse( new WorkNotificationStore.Readable( null, Set.of( 2L ) ).none() );
        assertFalse( WorkNotificationStore.Readable.visible( Set.of( 3L ) ).none() );
        assertEquals( Set.of(), new WorkNotificationStore.Readable( null, Set.of( 2L ) ).visible() );
        assertEquals( Set.of(), new WorkNotificationStore.Readable( Set.of( 1L ), null ).reportOnly() );
    }

    // ---- hand-off wording

    private static String line( final String type, final String to ) {
        return NoticeText.line( RecipientsTest.ev( type, "alice", Actor.Kind.HUMAN, null, to, null ), null, "bob", NAMES );
    }

    @Test
    void aHandoffLineSaysWhoGetsItAndTheStatusItWasHandedOnAs() {
        assertEquals( "Name of alice handed this on as in review", line( "handoff", "in_review" ) );
        assertEquals( "Name of alice handed this to you as in review", line( "handoff", "in_review|bob" ) );
        assertEquals( "Name of alice handed this to Name of carol as todo", line( "handoff", "todo|carol" ) );
        assertEquals( "Name of alice handed this to the ops queue as todo", line( "handoff", "todo|queue:ops" ) );
    }

    @Test
    void aHandoffWithNothingAfterTheBarOrNoStatusStillReadsPlainly() {
        assertEquals( "Name of alice handed this on as todo", line( "handoff", "todo|" ) );
        assertEquals( "Name of alice handed this on as todo", line( "handoff", "todo|  " ) );
        assertEquals( "Name of alice handed this to Name of carol as ", line( "handoff", "|carol" ),
            "a bar in first place leaves an empty status" );
        assertEquals( "Name of alice handed this on as ", line( "handoff", null ) );
    }

    @Test
    void aStatusMoveToNothingSaysSo() {
        assertEquals( "Name of alice moved this from nothing to nothing", line( "status", null ) );
        assertEquals( "Name of alice moved this from nothing to in progress", line( "status", "in_progress" ) );
    }
}

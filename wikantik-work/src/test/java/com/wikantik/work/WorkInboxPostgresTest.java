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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The inbox as a reader sees it: only projects they still see, only their own rows, 404 for strangers. */
@RequiresPostgres
class WorkInboxPostgresTest {

    private static final Actor LENA = NotifyServiceFixture.LENA;
    private static final Actor OTTO = NotifyServiceFixture.OTTO;
    private static final Actor MALLORY = NotifyServiceFixture.MALLORY;

    private NotifyServiceFixture fx;
    private Item first;
    private Item second;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyServiceFixture();
        fx.plant();
        first = fx.newItem( LENA );
        second = fx.newItem( LENA );
        fx.patch( LENA, first, "assignee", "otto" );
        fx.patch( LENA, second, "assignee", "otto" );
        fx.dispatchAll();
    }

    @Test
    void theInboxShowsNewestFirstWithTitles() throws SQLException {
        final List< NoticeView > inbox = fx.notices.inbox( OTTO, null, 10 );
        assertEquals( 2, inbox.size() );
        assertTrue( inbox.get( 0 ).id() > inbox.get( 1 ).id() );
        assertEquals( "t", inbox.get( 0 ).itemTitle() );
        assertEquals( "WIKI", inbox.get( 0 ).projectKey() );
        assertEquals( second.key(), inbox.get( 0 ).event().itemKey() );
        assertEquals( 1, fx.notices.inbox( OTTO, inbox.get( 0 ).id(), 10 ).size(), "paging by beforeId" );
        assertEquals( 2, fx.notices.inbox( OTTO, null, 0 ).size(), "0 means the default" );
        assertEquals( 1, fx.notices.inbox( OTTO, null, -5 ).size(), "clamped to at least 1" );
    }

    @Test
    void aMemberRemovedAfterDispatchNoLongerSeesOrCountsTheRow() throws SQLException {
        assertEquals( 2, fx.notices.unread( OTTO ) );
        fx.store.removeMember( fx.project.id(), "otto", "user", NotifyServiceFixture.ROOT );
        assertTrue( fx.notices.inbox( OTTO, null, 10 ).isEmpty() );
        assertEquals( 0, fx.notices.unread( OTTO ) );
        fx.notices.markAllRead( OTTO );
        assertEquals( 2, fx.rows( "recipient = 'otto' AND read_at IS NULL" ), "nothing marked outside their view" );
        fx.store.putMember( fx.project.id(), "otto", "user", WorkRole.MEMBER, NotifyServiceFixture.ROOT );
        assertEquals( 2, fx.notices.unread( OTTO ), "and the rows are back with their standing" );
    }

    @Test
    void aStrangersInboxIsEmpty() throws SQLException {
        assertTrue( fx.notices.inbox( MALLORY, null, 10 ).isEmpty() );
        assertEquals( 0, fx.notices.unread( MALLORY ) );
    }

    @Test
    void markReadNeedsAVisibleItem() throws SQLException {
        assertThrows( WorkNotFoundException.class, () -> fx.notices.markRead( MALLORY, first.key() ) );
        assertThrows( WorkNotFoundException.class, () -> fx.notices.markRead( OTTO, "NOPE-9" ) );
        fx.notices.markRead( OTTO, first.key() );
        assertEquals( 1, fx.notices.unread( OTTO ) );
        assertNotNull( fx.notices.inbox( OTTO, null, 10 ).stream().filter( n -> n.readAt() != null ).findFirst()
            .orElseThrow().readAt() );
    }

    @Test
    void markAllReadTouchesOnlyTheCallersRows() throws SQLException {
        fx.patch( LENA, first, "assignee", "vera" );
        fx.dispatchAll();
        assertEquals( 1, fx.notices.unread( NotifyServiceFixture.VERA ) );
        fx.notices.markAllRead( OTTO );
        assertEquals( 0, fx.notices.unread( OTTO ) );
        assertEquals( 1, fx.notices.unread( NotifyServiceFixture.VERA ) );
    }

    @Test
    void ownMethodsTouchOnlyTheCallersRows() throws SQLException {
        fx.notices.setPreference( LENA, EmailMode.DAILY );
        fx.notices.markAllRead( OTTO );
        fx.notices.setPreference( OTTO, EmailMode.OFF );
        assertEquals( EmailMode.DAILY, fx.notices.preference( LENA ).email() );
        assertEquals( EmailMode.OFF, fx.notices.preference( OTTO ).email() );
        assertEquals( EmailMode.EACH, fx.notices.preference( NotifyServiceFixture.VERA ).email() );
        fx.patch( LENA, second, "assignee", "lena" );
        fx.patch( OTTO, second, "assignee", "vera" );
        fx.dispatchAll();
        fx.notices.markAllRead( OTTO );
        assertEquals( 1, fx.notices.unread( NotifyServiceFixture.VERA ), "vera's rows were not read by otto" );
    }

    @Test
    void agentsCannotWatchOrSetEmail() throws SQLException {
        final long id = WorkAgentFixtures.agent( fx.ds, "codex" );
        fx.store.putMember( fx.project.id(), String.valueOf( id ), "agent", WorkRole.MEMBER, NotifyServiceFixture.ROOT );
        final Actor agent = WorkAgentFixtures.sponsored( id, "codex" );
        final WorkDeniedException watch = assertThrows( WorkDeniedException.class,
            () -> fx.notices.setWatch( agent, first.key(), WatchState.WATCHING ) );
        assertEquals( "Agents are not notified.", watch.reason() );
        assertThrows( WorkDeniedException.class, () -> fx.notices.setPreference( agent, EmailMode.OFF ) );
        assertEquals( WatchState.NONE, fx.notices.watchState( agent, first.key() ) );
        assertTrue( fx.notices.inbox( agent, null, 10 ).isEmpty() );
        assertEquals( 0, fx.notices.unread( agent ) );
        assertEquals( 0, fx.watches( agent.login() ) );
    }

    @Test
    void setWatchOnAnArchivedProjectIsArchived() throws SQLException {
        fx.service.archiveProject( NotifyServiceFixture.ROOT, "WIKI" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> fx.notices.setWatch( OTTO, first.key(), WatchState.MUTED ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
    }

    @Test
    void watchStateRoundTrips() throws SQLException {
        assertEquals( WatchState.NONE, fx.notices.watchState( NotifyServiceFixture.VERA, first.key() ) );
        assertEquals( WatchState.WATCHING, fx.notices.setWatch( NotifyServiceFixture.VERA, first.key(), WatchState.WATCHING ) );
        assertEquals( WatchState.MUTED, fx.notices.setWatch( NotifyServiceFixture.VERA, first.key(), WatchState.MUTED ) );
        assertEquals( WatchState.NONE, fx.notices.setWatch( NotifyServiceFixture.VERA, first.key(), WatchState.NONE ) );
        assertThrows( WorkNotFoundException.class, () -> fx.notices.setWatch( MALLORY, first.key(), WatchState.WATCHING ) );
        assertThrows( WorkNotFoundException.class, () -> fx.notices.watchState( MALLORY, first.key() ) );
    }
}

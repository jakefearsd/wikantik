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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class JdbcWorkNotificationsPostgresTest {

    private static final Instant AT = Instant.parse( "2026-03-02T10:00:00Z" );

    private NotifyFixtures fx;
    private WorkNotificationStore store;
    private final List< EventFacts > seenFacts = new ArrayList<>();
    private final List< WorkEvent > seenEvents = new ArrayList<>();

    /** Tells zed about everything and records what the planner was shown. */
    private final WorkNotificationStore.NoticePlanner zed = ( e, f ) -> {
        seenEvents.add( e );
        seenFacts.add( f );
        return Map.of( "zed", NoticeReason.WATCHING );
    };

    @BeforeEach
    void setUp() throws SQLException {
        fx = new NotifyFixtures();
        store = new JdbcWorkStore( fx.ds ).notifyStores().notifications();
    }

    private void plant() throws SQLException {
        assertEquals( 0, store.dispatch( 10, zed ) );
    }

    @Test
    void theFirstDispatchStartsAtTheCurrentEndOfTheLog() throws SQLException {
        fx.event( WorkEvent.EDITED, "bob", null );
        assertEquals( 0, store.dispatch( 10, zed ) );
        assertEquals( 0, fx.count( "work_notification" ) );
        assertEquals( 0, store.dispatch( 10, zed ) );
        assertEquals( 0, fx.count( "work_notification" ) );
    }

    @Test
    void historyBeforeTheFirstDispatchIsNeverReplayed() throws SQLException {
        fx.event( WorkEvent.EDITED, "bob", null );
        fx.event( WorkEvent.COMMENTED, "carol", null );
        fx.event( WorkEvent.STATUS, "bob", "done" );
        plant();
        assertEquals( 0, store.dispatch( 10, zed ) );
        final long fresh = fx.event( WorkEvent.EDITED, "bob", null );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( List.of( fresh ), seenEvents.stream().map( WorkEvent::id ).toList() );
        assertEquals( 1, fx.count( "work_notification" ) );
    }

    @Test
    void aDispatchConsumesEventsAndMovesTheCursor() throws SQLException {
        plant();
        final long a = fx.event( WorkEvent.EDITED, "bob", null );
        final long b = fx.event( WorkEvent.STATUS, "bob", "done" );
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 10, zed ), 2 ) );
        assertEquals( 2, fx.count( "work_notification" ) );
        assertEquals( List.of( a, b ), seenEvents.stream().map( WorkEvent::id ).toList() );
        assertEquals( b, WorkAgentFixtures.scalar( fx.ds, "SELECT event_id FROM work_notify_cursor" ) );
    }

    @Test
    void aSecondDispatchFindsNothingNew() throws SQLException {
        plant();
        fx.event( WorkEvent.EDITED, "bob", null );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( 0, store.dispatch( 10, zed ) );
        assertEquals( 1, seenEvents.size() );
    }

    @Test
    void theBatchSizeBoundsTheEventsRead() throws SQLException {
        plant();
        for ( int i = 0; i < 5; i++ ) {
            fx.event( WorkEvent.EDITED, "bob", null );
        }
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 2, zed ), 2 ) );
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 2, zed ), 2 ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 2, zed ), 1 ) );
        assertEquals( 5, seenEvents.size() );
        assertEquals( 5, fx.count( "work_notification" ) );
    }

    @Test
    void twoDispatchersProduceEachRowOnce() throws Exception {
        plant();
        long last = 0;
        for ( int i = 0; i < 50; i++ ) {
            last = fx.event( WorkEvent.EDITED, "bob", null );
        }
        DispatchAwait.windowPast( fx.ds, last ); // the loops below stop at the first empty call
        final AtomicInteger planned = new AtomicInteger();
        final WorkNotificationStore.NoticePlanner counting = ( e, f ) -> {
            planned.incrementAndGet();
            return Map.of( "zed", NoticeReason.WATCHING );
        };
        final CountDownLatch go = new CountDownLatch( 1 );
        final ExecutorService pool = Executors.newFixedThreadPool( 2 );
        try {
            final List< Future< Integer > > runs = new ArrayList<>();
            for ( int t = 0; t < 2; t++ ) {
                runs.add( pool.submit( () -> {
                    go.await();
                    int read = 0;
                    int got;
                    do {
                        got = store.dispatch( 7, counting );
                        read += got;
                    } while ( got > 0 );
                    return read;
                } ) );
            }
            go.countDown();
            int total = 0;
            for ( final Future< Integer > run : runs ) {
                total += run.get( 60, TimeUnit.SECONDS );
            }
            assertEquals( 50, total );
        } finally {
            pool.shutdownNow();
        }
        assertEquals( 50, planned.get() );
        assertEquals( 50, fx.count( "work_notification" ) );
    }

    @Test
    void autoWatchIsAppliedBeforeThePlannerSeesWatchers() throws SQLException {
        plant();
        fx.event( WorkEvent.COMMENTED, "carol", null );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( Set.of( "carol" ), seenFacts.get( 0 ).watchers() );
        assertEquals( "alice", seenFacts.get( 0 ).reporter() );
    }

    @Test
    void aCommentsMentionsReachThePlannerOnce() throws SQLException {
        plant();
        final WorkNotificationStore.NoticePlanner mentions = ( e, f ) -> {
            seenFacts.add( f );
            final Map< String, NoticeReason > out = new java.util.TreeMap<>();
            f.mentioned().forEach( m -> out.put( m, NoticeReason.MENTIONED ) );
            return out;
        };
        final long comment = fx.comment( "carol", false, "otto" );
        fx.event( WorkEvent.COMMENTED, "carol", String.valueOf( comment ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, mentions ), 1 ) );
        assertEquals( Set.of( "otto" ), seenFacts.get( 0 ).mentioned() );
        fx.mention( comment, "pia" );
        fx.event( WorkEvent.COMMENT_EDITED, "carol", String.valueOf( comment ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, mentions ), 1 ) );
        assertEquals( Set.of( "pia" ), seenFacts.get( 1 ).mentioned() );
        assertEquals( Set.of( "otto", "pia" ), seenFacts.get( 1 ).watchers().stream()
            .filter( w -> !"carol".equals( w ) ).collect( java.util.stream.Collectors.toSet() ) );
    }

    @Test
    void aRemovedCommentIsReportedRemoved() throws SQLException {
        plant();
        final long comment = fx.comment( "carol", true, "otto" );
        fx.event( WorkEvent.COMMENTED, "carol", String.valueOf( comment ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertTrue( seenFacts.get( 0 ).commentRemoved() );
        assertFalse( seenFacts.get( 0 ).watchers().contains( "otto" ) );
    }

    @Test
    void groupLeadsArriveAsGroups() throws SQLException {
        plant();
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES ("
            + fx.project + ",'user','lena','lead'), (" + fx.project + ",'group','ops','lead'), (" + fx.project
            + ",'user','mo','member'), (" + fx.project + ",'agent','7','lead')" );
        fx.event( WorkEvent.EDITED, "bob", null );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( Set.of( "lena" ), seenFacts.get( 0 ).leads() );
        assertEquals( Set.of( "ops" ), seenFacts.get( 0 ).leadGroups() );
    }

    @Test
    void anItemlessEventIsConsumedAndNotifiesNobodyExceptAWebhookFailure() throws SQLException {
        plant();
        WorkAgentFixtures.exec( fx.ds, "INSERT INTO work_member (project_id, principal_kind, principal, role) VALUES ("
            + fx.project + ",'user','lena','lead')" );
        WorkAgentFixtures.scalar( fx.ds, fx.eventSql( fx.project, null, WorkEvent.SETTINGS, "bob", "x", "webhook" ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( 0, seenEvents.size() );
        WorkAgentFixtures.scalar( fx.ds, fx.eventSql( fx.project, null, WorkEvent.SETTINGS, "bob", "failed", "webhook" ) );
        assertEquals( 1, DispatchAwait.events( () -> store.dispatch( 10, zed ), 1 ) );
        assertEquals( 1, seenEvents.size() );
        assertEquals( Set.of( "lena" ), seenFacts.get( 0 ).leads() );
        assertEquals( 1, fx.count( "work_notification WHERE item_id IS NULL" ) );
    }

    @Test
    void sprintEventsAreConsumedAndNotifyNobody() throws SQLException {
        plant();
        for ( final String type : List.of( WorkEvent.SPRINT_CREATED, WorkEvent.SPRINT_UPDATED, WorkEvent.SPRINT_STARTED,
                WorkEvent.SPRINT_CLOSED ) ) {
            WorkAgentFixtures.scalar( fx.ds, fx.eventSql( fx.project, null, type, "bob", "1", "Sprint 1" ) );
        }
        assertEquals( 4, DispatchAwait.events( () -> store.dispatch( 10, zed ), 4 ) );
        assertEquals( 0, seenEvents.size() );
        assertEquals( 0, fx.count( "work_notification" ) );
        assertEquals( 0, store.dispatch( 10, zed ) );
    }

    @Test
    void inboxReadsOnlyTheGivenProjects() throws SQLException {
        plant();
        final long other = fx.newProject( "OTHR" );
        final long otherItem = fx.newItem( other, 1 );
        final long mine = fx.event( WorkEvent.EDITED, "bob", null );
        WorkAgentFixtures.scalar( fx.ds, fx.eventSql( other, otherItem, WorkEvent.EDITED, "bob", null, null ) );
        assertEquals( 2, DispatchAwait.events( () -> store.dispatch( 10, zed ), 2 ) );
        final List< NoticeView > mineOnly = store.inbox( "zed", Set.of( fx.project ), null, 10 );
        assertEquals( 1, mineOnly.size() );
        assertEquals( mine, mineOnly.get( 0 ).event().id() );
        assertEquals( "WIKI", mineOnly.get( 0 ).projectKey() );
        assertEquals( "t1", mineOnly.get( 0 ).itemTitle() );
        assertEquals( "WIKI-1", mineOnly.get( 0 ).event().itemKey() );
        assertEquals( NoticeReason.WATCHING, mineOnly.get( 0 ).reason() );
        assertEquals( 2, store.inbox( "zed", Set.of( fx.project, other ), null, 10 ).size() );
        assertTrue( store.inbox( "zed", Set.of(), null, 10 ).isEmpty() );
        assertTrue( store.inbox( "nobody", Set.of( fx.project, other ), null, 10 ).isEmpty() );
        final List< NoticeView > both = store.inbox( "zed", Set.of( fx.project, other ), null, 10 );
        assertTrue( both.get( 0 ).id() > both.get( 1 ).id() );
        assertEquals( 1, store.inbox( "zed", Set.of( fx.project, other ), both.get( 0 ).id(), 10 ).size() );
    }

    @Test
    void unreadAndMarkReadCountOnlyTheGivenProjects() throws SQLException {
        plant();
        final long other = fx.newProject( "OTHR" );
        final long otherItem = fx.newItem( other, 1 );
        fx.event( WorkEvent.EDITED, "bob", null );
        fx.event( WorkEvent.EDITED, "bob", null );
        WorkAgentFixtures.scalar( fx.ds, fx.eventSql( other, otherItem, WorkEvent.EDITED, "bob", null, null ) );
        assertEquals( 3, DispatchAwait.events( () -> store.dispatch( 10, zed ), 3 ) );
        assertEquals( 2, store.unread( "zed", Set.of( fx.project ) ) );
        assertEquals( 3, store.unread( "zed", Set.of( fx.project, other ) ) );
        assertEquals( 0, store.unread( "zed", Set.of() ) );
        assertEquals( 0, store.markAllRead( "zed", Set.of(), AT ) );
        assertEquals( 2, store.markAllRead( "zed", Set.of( fx.project ), AT ) );
        assertEquals( 0, store.unread( "zed", Set.of( fx.project ) ) );
        assertEquals( 1, store.unread( "zed", Set.of( fx.project, other ) ) );
        assertEquals( AT, store.inbox( "zed", Set.of( fx.project ), null, 10 ).get( 0 ).readAt() );
        assertEquals( 1, store.markRead( "zed", otherItem, AT ) );
        assertEquals( 0, store.markRead( "zed", otherItem, AT ) );
    }

    @Test
    void emailModeDefaultsToEach() throws SQLException {
        assertEquals( EmailMode.EACH, store.emailMode( "zed" ) );
        store.setEmailMode( "zed", EmailMode.DAILY );
        assertEquals( EmailMode.DAILY, store.emailMode( "zed" ) );
        store.setEmailMode( "zed", EmailMode.OFF );
        assertEquals( EmailMode.OFF, store.emailMode( "zed" ) );
        assertEquals( EmailMode.EACH, store.emailMode( "other" ) );
    }
}

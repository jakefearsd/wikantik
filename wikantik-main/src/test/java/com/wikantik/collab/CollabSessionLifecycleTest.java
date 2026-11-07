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
package com.wikantik.collab;

import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Phase 2 Task 3: the session lifecycle (docs/MultiEdit.MD A1, A3, A5, A12, A13 and section 7.4's delete/rename):
 * participant events, eviction reasons and the eviction tombstone, selections following the head, rename, freeze on a
 * deleted page and its recreate, and the collab version on external-change events.
 */
class CollabSessionLifecycleTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final int PRESENCE_TIMEOUT = 45;
    private static final CollabIdentity IDENTITY = key -> key.substring( 0, 1 ).toUpperCase() + key.substring( 1 );

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSession session;
    private final Set< String > noEdit = new HashSet<>();
    private final Set< String > noCreate = new HashSet<>();

    /** Edit and create rights by wiki session (the author key here). */
    private final CollabPermissions permissions = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !noEdit.contains( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return !noCreate.contains( participantSession );
        }
    };

    /** Records completions; a second completion fails the test (F6: exactly once). */
    private static final class Waiter implements PullWaiter {
        final List< PullResponse > got = new ArrayList<>();

        @Override
        public void complete( final PullResponse r ) {
            if( !got.isEmpty() ) {
                fail( "waiter completed twice: " + got + " then " + r );
            }
            got.add( r );
        }

        PullResponse only() {
            assertEquals( 1, got.size(), "completions" );
            return got.get( 0 );
        }
    }

    private static CollabConfig config() {
        return new CollabConfig( true, 20, 65536, 25, PRESENCE_TIMEOUT, 30, 100, 500, 10, 0, 1000 );
    }

    private CollabPorts ports() {
        return new CollabPorts( store, writer, IDENTITY, permissions, clock );
    }

    private CollabSession create( final String page ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( page.getBytes() ), page,
            writer.latest( page ), "creator", clock );
        return CollabSession.create( rec, ports(), config(), 1 << 20 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        session = create( PAGE );
    }

    private JoinResponse join( final String author ) {
        return session.join( new JoinRequest( author, null, author, null, null, null ) );
    }

    private PushResponse type( final JoinResponse j, final String author, final int at, final String text ) {
        final CollabSession.View v = session.view();
        return session.push( j.participantId(), author, v.head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( v.doc().length(), at, at, text ) ) ) );
    }

    /** Every held event, as seen by {@code j}'s pull from event 0 at the head (answered at once). */
    private List< CollabEvent > events( final JoinResponse j, final String author ) {
        final PullResponse r = session.pullOrPark( j.participantId(), author,
            new PullCursor( session.view().head(), Long.MAX_VALUE, Long.MAX_VALUE, 0 ), null, new Waiter() );
        assertNotNull( r, "events are held: the pull answers at once" );
        assertEquals( 200, r.status() );
        return r.events();
    }

    private static List< String > types( final List< CollabEvent > events ) {
        return events.stream().map( CollabEvent::type ).toList();
    }

    private static CollabEvent last( final List< CollabEvent > events, final String type ) {
        CollabEvent found = null;
        for( final CollabEvent e : events ) {
            if( e.type().equals( type ) ) {
                found = e;
            }
        }
        assertNotNull( found, "an event of type " + type + " in " + events );
        return found;
    }

    /** Parks {@code j}'s pull at every cursor it has seen (nothing ahead), returning the waiter. */
    private Waiter park( final JoinResponse j, final String author ) {
        final PullResponse first = session.pullOrPark( j.participantId(), author,
            new PullCursor( session.view().head(), 0, 0, 0 ), null, new Waiter() );
        final Waiter w = new Waiter();
        final PullCursor at = first == null ? new PullCursor( session.view().head(), session.view().metaSeq(),
            session.view().presenceSeq(), 0 ) : new PullCursor( first.version(), first.metaSeq(), first.presenceSeq(),
            first.eventSeq() );
        assertNull( session.pullOrPark( j.participantId(), author, at, null, w ), "parked" );
        assertTrue( w.got.isEmpty() );
        return w;
    }

    private static void assertEvicted( final int status, final String reason, final String detail,
                                       final String expectedDetail ) {
        assertEquals( 403, status );
        assertEquals( CollabSession.EVICTED, reason );
        assertEquals( expectedDetail, detail );
    }

    // ---- participant events ----------------------------------------------------------------------------------------

    @Test
    void joinLeaveTimeoutAndEvictionEmitParticipantEvents() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse bob = join( "bob" );
        CollabEvent joined = last( events( ada, "ada" ), "participant-joined" );
        @SuppressWarnings( "unchecked" )
        final Map< String, Object > who = ( Map< String, Object > ) joined.fields().get( "participant" );
        assertEquals( bob.participantId(), who.get( "participantId" ) );
        assertEquals( "Bob", who.get( "displayName" ) );
        assertNotNull( who.get( "color" ) );
        assertEquals( bob.eventSeq(), joined.seq(), "the joiner's own join event is not news to it" );

        assertEquals( 200, session.leave( bob.participantId(), "bob" ).status() );
        CollabEvent left = last( events( ada, "ada" ), "participant-left" );
        assertEquals( Map.of( "participantId", bob.participantId(), "reason", "left" ), left.fields() );

        final JoinResponse cat = join( "cat" );
        clock.advance( Duration.ofSeconds( PRESENCE_TIMEOUT + 1 ) );
        assertEquals( 200, session.push( ada.participantId(), "ada", session.view().head(), List.of() ).status() );
        assertEquals( 1, CollabSession.Hooks.expirePresence( session, clock.instant().minusSeconds( PRESENCE_TIMEOUT ) ) );
        left = last( events( ada, "ada" ), "participant-left" );
        assertEquals( Map.of( "participantId", cat.participantId(), "reason", "timeout" ), left.fields() );
        assertEquals( 409, type( cat, "cat", 0, "x" ).status(), "a timed-out participant rejoins" );

        final JoinResponse dan = join( "dan" );
        session.evict( dan.participantId(), CollabSession.LOST_EDIT );
        final List< CollabEvent > all = events( ada, "ada" );
        final CollabEvent evicted = last( all, "evicted" );
        assertEquals( Map.of( "participantId", dan.participantId(), "reason", "lost-edit" ), evicted.fields() );
        left = last( all, "participant-left" );
        assertEquals( Map.of( "participantId", dan.participantId(), "reason", "evicted" ), left.fields() );
        assertEquals( evicted.seq() + 1, left.seq(), "evicted, then participant-left" );
    }

    @Test
    void aRejoinWithItsPriorClientIdReplacesItsOwnStaleEntry() {
        final JoinResponse ada = join( "ada" );
        final Waiter w = park( ada, "ada" );
        final JoinResponse again = session.join( new JoinRequest( "ada", ada.clientId(), "ada", null, null, null ) );
        assertEquals( ada.clientId(), again.clientId(), "the same client gets its clientID back" );
        assertEquals( 1, session.view().participants(), "the stale entry was replaced, not kept beside it" );
        assertEquals( 409, w.only().status(), "the stale entry's parked pull rejoins" );
        assertEquals( 409, type( ada, "ada", 0, "x" ).status() );
        // another author never replaces it
        final JoinResponse mal = session.join( new JoinRequest( "mal", again.clientId(), "mal", null, null, null ) );
        assertFalse( again.clientId().equals( mal.clientId() ) );
        assertEquals( 2, session.view().participants() );
    }

    // ---- eviction tombstone ----------------------------------------------------------------------------------------

    @Test
    void anEvictedParticipantGets403EvictedWithItsReason() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse bob = join( "bob" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        final Waiter parked = park( ada, "ada" );

        session.evict( ada.participantId(), CollabSession.SIGNED_OUT );

        final PullResponse woke = parked.only();
        assertEvicted( woke.status(), woke.reason(), woke.detail(), "signed-out" );
        final PushResponse push = type( ada, "ada", 0, "y" );
        assertEvicted( push.status(), push.reason(), push.detail(), "signed-out" );
        final PullResponse pull = session.pullOrPark( ada.participantId(), "ada", 0, null, new Waiter() );
        assertEvicted( pull.status(), pull.reason(), pull.detail(), "signed-out" );
        final PublishResponse pub = session.publish( ada.participantId(), "ada", 1, "" );
        assertEvicted( pub.status(), pub.reason(), pub.detail(), "signed-out" );
        final PushResponse discard = session.discard( ada.participantId(), "ada" );
        assertEvicted( discard.status(), discard.reason(), discard.detail(), "signed-out" );
        assertEquals( 403, session.leave( ada.participantId(), "ada" ).status() );
        assertEquals( CollabSession.EVICTED, session.leave( ada.participantId(), "ada" ).reason() );
        // the tombstone is the author's: anyone else is told it is not theirs
        assertEquals( "not-your-participant", session.push( ada.participantId(), "bob", 1, List.of() ).reason() );
        assertEquals( 1, session.view().head(), "nothing applied" );
        assertEquals( 200, type( bob, "bob", 0, "z" ).status() );

        // bounded: pruned after the presence-timeout retention, then a plain rejoin
        clock.advance( Duration.ofSeconds( PRESENCE_TIMEOUT + 1 ) );
        join( "cat" );
        assertEquals( 409, session.push( ada.participantId(), "ada", 2, List.of() ).status() );
        assertEquals( "rejoin", session.push( ada.participantId(), "ada", 2, List.of() ).reason() );
    }

    @Test
    void anAccountDeletedEvictionCarriesItsReason() {
        final JoinResponse ada = join( "ada" );
        session.evict( ada.participantId(), CollabSession.ACCOUNT_DELETED );
        final PushResponse r = type( ada, "ada", 0, "x" );
        assertEvicted( r.status(), r.reason(), r.detail(), "account-deleted" );
    }

    @Test
    void e29_lostEditEvictsWithReasonLostEdit() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse bob = join( "bob" );
        final Waiter parked = park( bob, "bob" );
        noEdit.add( "bob" );

        session.recheckEdits();

        assertEvicted( parked.only().status(), parked.only().reason(), parked.only().detail(), "lost-edit" );
        final PushResponse r = type( bob, "bob", 0, "x" );
        assertEvicted( r.status(), r.reason(), r.detail(), "lost-edit" );
        assertEquals( "lost-edit", last( events( ada, "ada" ), "evicted" ).fields().get( "reason" ) );
        // the one-argument evict is a lost-edit eviction
        final JoinResponse cat = join( "cat" );
        session.evict( cat.participantId() );
        assertEquals( "lost-edit", type( cat, "cat", 0, "x" ).detail() );
    }

    // ---- presence --------------------------------------------------------------------------------------------------

    @Test
    void keptSelectionsFollowTheHead() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse bob = join( "bob" );
        final JoinResponse cat = join( "cat" );
        assertEquals( 200, session.push( bob.participantId(), "bob", 0, List.of(), new Selection( 6, 8, 0, 0 ) ).status() );
        assertEquals( 200, session.push( cat.participantId(), "cat", 0, List.of(), new Selection( 1, 1, 0, 2 ) ).status() );

        assertEquals( 200, type( ada, "ada", 0, "XY" ).status() );   // a push: head 1
        assertEquals( new Selection( 8, 10, 1, 0 ), session.view().selections().get( bob.participantId() ) );
        assertEquals( new Selection( 1, 1, 0, 2 ), session.view().selections().get( cat.participantId() ),
            "a selection with unconfirmed updates is kept as sent" );

        writer.externalSave( PAGE, "top\n" + BODY, Map.of( "title", "T" ), "agent" );
        session.settle();   // a server update: head 2
        assertEquals( 2, session.view().head() );
        assertEquals( new Selection( 12, 14, 2, 0 ), session.view().selections().get( bob.participantId() ) );
        assertEquals( "XYtop\nhello\nworld\n".substring( 12, 14 ), "hello\nworld\n".substring( 6, 8 ) );
    }

    @Test
    void aPresenceChangeWakesParkedPullsOnce() {
        final JoinResponse ada = join( "ada" );
        final JoinResponse bob = join( "bob" );
        final Waiter w = park( ada, "ada" );
        assertEquals( 200, session.push( bob.participantId(), "bob", 0, List.of(), new Selection( 1, 1, 0, 0 ) ).status() );
        final PullResponse r = w.only();
        assertEquals( 200, r.status() );
        assertEquals( new Selection( 1, 1, 0, 0 ), r.presence().get( bob.participantId() ).selection() );
        assertEquals( 200, session.push( bob.participantId(), "bob", 0, List.of(), new Selection( 2, 2, 0, 0 ) ).status() );
        assertEquals( 1, w.got.size(), "answered once; the next change waits for the next pull" );
    }

    // ---- rename ----------------------------------------------------------------------------------------------------

    @Test
    void rekeyMovesTheSessionAndEmitsPageRenamed() {
        final JoinResponse ada = join( "ada" );
        final Waiter w = park( ada, "ada" );

        session.rekey( "Renamed" );

        assertEquals( "Renamed", session.pageName() );
        assertEquals( "Renamed", store.session( session.id() ).orElseThrow().pageName() );
        assertTrue( store.sessionForPage( PAGE ).isEmpty() );
        final CollabEvent renamed = last( w.only().events(), "page-renamed" );
        assertEquals( Map.of( "to", "Renamed" ), renamed.fields() );
        assertTrue( session.answersTo( "Renamed" ) );
        assertTrue( session.answersTo( PAGE ), "the old name still resolves for post-join calls" );
        assertFalse( session.answersTo( "Elsewhere" ) );
    }

    @Test
    void rekeyOntoAPageWithADraftIsRefused() {
        final JoinResponse ada = join( "ada" );
        writer.seed( "Other", "other\n", Map.of() );
        create( "Other" );
        final long events = events( ada, "ada" ).size();

        assertThrows( IllegalStateException.class, () -> session.rekey( "Other" ) );

        assertEquals( PAGE, session.pageName() );
        assertEquals( PAGE, store.session( session.id() ).orElseThrow().pageName() );
        assertFalse( session.answersTo( "Other" ) );
        assertEquals( events, events( ada, "ada" ).size(), "no page-renamed event" );
    }

    @Test
    void e27_renameDuringPublishLandsOnTheNewName() throws Exception {
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< PublishResponse > first = CompletableFuture.supplyAsync(
            () -> session.publish( ada.participantId(), "ada", 1, "" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        writer.rename( PAGE, "Renamed" );
        session.rekey( "Renamed" );
        pause.release();

        final PublishResponse r = first.get( 10, TimeUnit.SECONDS );
        assertEquals( 409, r.status() );
        assertEquals( "publish-merged-retry", r.reason() );
        assertFalse( session.frozen(), "a rename is not a delete" );

        final PublishResponse retry = session.publish( ada.participantId(), "ada", session.view().head(), "" );
        assertEquals( 200, retry.status() );
        assertEquals( "xhello\r\nworld\r\n", writer.latest( "Renamed" ).orElseThrow().body() );
        assertEquals( 2, writer.latest( "Renamed" ).orElseThrow().version() );
        assertTrue( writer.latest( PAGE ).isEmpty(), "the old name was never recreated" );
    }

    // ---- freeze and recreate ---------------------------------------------------------------------------------------

    @Test
    void e28_freezeMakesPushes409FrozenAndKeepsPulls() {
        final JoinResponse ada = join( "ada" );
        final Waiter w = park( ada, "ada" );
        writer.delete( PAGE );

        session.freeze();

        assertTrue( session.frozen() );
        assertEquals( SessionRecord.FROZEN, store.session( session.id() ).orElseThrow().status() );
        assertTrue( types( w.only().events() ).contains( "page-deleted" ) );
        final PushResponse push = type( ada, "ada", 0, "x" );
        assertEquals( 409, push.status() );
        assertEquals( "frozen", push.reason() );
        final PushResponse discard = session.discard( ada.participantId(), "ada" );
        assertEquals( 409, discard.status() );
        assertEquals( "frozen", discard.reason() );
        assertEquals( 200, session.pullOrPark( ada.participantId(), "ada", 0, 0, null, new Waiter() ).status(),
            "pulls are served" );
    }

    @Test
    void e28_publishOfAFrozenDraftRecreatesThePageOnlyWithCreatePages() {
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        writer.delete( PAGE );
        session.freeze();
        noCreate.add( "ada" );

        final PublishResponse refused = session.publish( ada.participantId(), "ada", 1, "" );
        assertEquals( 403, refused.status() );
        assertEquals( "no-create", refused.reason() );
        assertTrue( writer.latest( PAGE ).isEmpty() );
        assertTrue( session.frozen() );

        noCreate.clear();
        final PublishResponse ok = session.publish( ada.participantId(), "ada", 1, "" );
        assertEquals( 200, ok.status() );
        assertEquals( 1, ok.pageVersion(), "the version numbers restart" );
        assertEquals( "xhello\r\nworld\r\n", writer.latest( PAGE ).orElseThrow().body() );
        assertFalse( session.frozen() );
        assertEquals( SessionRecord.ACTIVE, store.session( session.id() ).orElseThrow().status() );
        assertEquals( 200, type( ada, "ada", 0, "y" ).status(), "editable again" );
    }

    @Test
    void aFrozenDraftWithoutEditsStillRecreatesThePage() {
        final JoinResponse ada = join( "ada" );
        writer.delete( PAGE );
        session.freeze();
        final PublishResponse ok = session.publish( ada.participantId(), "ada", 0, "" );
        assertEquals( 200, ok.status() );
        assertFalse( ok.unchanged() );
        assertEquals( "hello\r\nworld\r\n", writer.latest( PAGE ).orElseThrow().body() );
        assertFalse( session.frozen() );
    }

    @Test
    void aRecreatedPageUnfreezesTheDraftAsOneExternalChange() {
        writer.externalSave( PAGE, BODY + "two\n", Map.of( "title", "T" ), "agent" );
        writer.externalSave( PAGE, BODY + "three\n", Map.of( "title", "T" ), "agent" );
        session.settle();
        assertEquals( 3, session.view().basePageVersion() );
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        final int head = session.view().head();
        writer.delete( PAGE );
        session.freeze();
        writer.externalSave( PAGE, BODY + "three\nend\n", Map.of( "title", "T" ), "agent" );

        session.settle();

        assertFalse( session.frozen() );
        assertEquals( SessionRecord.ACTIVE, store.session( session.id() ).orElseThrow().status() );
        assertEquals( 1, session.view().basePageVersion(), "no version walk: the numbers restarted" );
        assertEquals( head + 1, session.view().head(), "one external change" );
        assertEquals( "x" + BODY + "three\nend\n", session.view().doc() );
        final CollabEvent e = last( events( ada, "ada" ), "external-change" );
        assertEquals( Boolean.TRUE, e.fields().get( "recreated" ) );
        assertEquals( 1, e.fields().get( "pageVersion" ) );
        assertEquals( head + 1, e.fields().get( "version" ) );
    }

    @Test
    void freezeIsDurableAcrossReload() {
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        writer.delete( PAGE );
        session.freeze();

        session = CollabSession.load( session.id(), ports(), config(), 1 << 20 );

        assertTrue( session.frozen() );
        final JoinResponse again = join( "ada" );
        assertEquals( "xhello\nworld\n", again.doc(), "a join into a frozen draft gets its text to copy" );
        assertEquals( "frozen", type( again, "ada", 1, "y" ).reason() );
        final PullResponse r = session.pullOrPark( again.participantId(), "ada",
            new PullCursor( 1, Long.MAX_VALUE, Long.MAX_VALUE, again.eventSeq() ), null, new Waiter() );
        assertNotNull( r, "the joiner is told at once that the page is gone" );
        assertTrue( types( r.events() ).contains( "page-deleted" ) );
    }

    @Test
    void aSaveThatFindsThePageGoneFreezesTheSession() throws Exception {
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final CompletableFuture< PublishResponse > first = CompletableFuture.supplyAsync(
            () -> session.publish( ada.participantId(), "ada", 1, "" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        writer.delete( PAGE );   // deleted under the save, its event not yet delivered
        pause.release();

        final PublishResponse r = first.get( 10, TimeUnit.SECONDS );
        assertEquals( 409, r.status() );
        assertEquals( "frozen", r.reason() );
        assertTrue( session.frozen(), "the client gets the recreate flow, not a retry into the same conflict" );
        assertTrue( types( events( ada, "ada" ) ).contains( "page-deleted" ) );
        assertTrue( writer.latest( PAGE ).isEmpty() );
        assertEquals( 200, session.publish( ada.participantId(), "ada", 1, "" ).status(), "recreate" );
    }

    @Test
    void externalChangeEventCarriesTheCollabVersion() {
        final JoinResponse ada = join( "ada" );
        assertEquals( 200, type( ada, "ada", 0, "x" ).status() );
        writer.externalSave( PAGE, BODY + "more\n", Map.of( "title", "T" ), "agent" );
        session.settle();
        final CollabEvent e = last( events( ada, "ada" ), "external-change" );
        assertEquals( 2, e.fields().get( "version" ) );
        assertEquals( 2, session.view().head() );
        assertFalse( e.fields().containsKey( "recreated" ) );
    }
}

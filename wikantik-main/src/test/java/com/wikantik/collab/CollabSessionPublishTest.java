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

import com.wikantik.api.content.ContentValidationException;
import com.wikantik.api.exceptions.AccessRuleShapeException;
import com.wikantik.api.exceptions.FrontmatterValidationException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.CollabInvariants;
import com.wikantik.collab.testing.CollabSessionSimServer;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.InvariantView;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.testing.SimClient;
import com.wikantik.collab.testing.SimServer;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Section 7.3 publish (pending-publish marker, unlocked save, normalisation mapping N/W), section 7.4 external merge
 * (A1 content-hash detection and the publish backstop, A3 queueing during a publish, meta LWW, the oversize rule),
 * E39 crash recovery (A2) and the Phase 0 final-review publish amendments. Every E16-E26 and E39 case is a named test.
 */
class CollabSessionPublishTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final CollabIdentity IDENTITY = key -> switch( key ) {
        case "alice" -> "Alice A";
        case "bob" -> "Bob B";
        case "agent" -> "Wiki Agent";
        default -> null;
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    /** Wiki sessions (here: the author key) that have lost {@code edit}. */
    private final Set< Object > noEdit = new HashSet<>();
    private final CollabPermissions permissions = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !noEdit.contains( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };
    private CollabSession session;

    /** Records the one completion of a parked pull. */
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

    static CollabConfig config( final int ring, final int snapshotEvery ) {
        return new CollabConfig( true, 20, 65536, 25, 45, 30, ring, snapshotEvery, 10, 0, 1000 );
    }

    private CollabPorts ports( final CollabStore st, final CollabPageWriter w ) {
        return new CollabPorts( st, w, IDENTITY, permissions, clock );
    }

    /** A new session on a fresh store (the page may hold only one session per store). */
    private CollabSession recreate( final CollabPageWriter w, final CollabConfig c, final long max ) {
        store = new InMemoryCollabStore();
        return create( w, c, max );
    }

    private CollabSession create( final CollabPageWriter w, final CollabConfig c, final long max ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( PAGE.getBytes() ), PAGE, w.latest( PAGE ),
            "creator", clock );
        return CollabSession.create( rec, ports( store, w ), c, max );
    }

    private CollabSession reload() {
        return CollabSession.load( session.id(), ports( store, writer ), config( 3, 500 ), 1000 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        session = create( writer, config( 3, 500 ), 1000 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, author, null ) );
    }

    /** Types {@code text} at {@code at} from the head (a client at head); asserts the push was accepted. */
    private void type( final JoinResponse j, final String author, final int at, final String text ) {
        final CollabSession.View v = session.view();
        final PushResponse r = session.push( j.participantId(), author, v.head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( v.doc().length(), at, at, text ) ) ) );
        assertEquals( 200, r.status(), "push: " + r );
    }

    private void replace( final JoinResponse j, final String author, final String from, final String to ) {
        final CollabSession.View v = session.view();
        final int at = v.doc().indexOf( from );
        assertTrue( at >= 0, from + " in " + v.doc() );
        final PushResponse r = session.push( j.participantId(), author, v.head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( v.doc().length(), at, at + from.length(), to ) ) ) );
        assertEquals( 200, r.status(), "push: " + r );
    }

    private PublishStart.Ticket start( final JoinResponse j, final String author ) {
        final PublishStart st = session.startPublish( j.participantId(), author, session.view().head(), "note" );
        assertNull( st.response(), "the publish was answered before the save: " + st.response() );
        return st.ticket();
    }

    private PublishResponse publish( final JoinResponse j, final String author ) {
        return session.publish( j.participantId(), author, session.view().head(), "note" );
    }

    /** An outside save through the normal pipeline, and the event the listener delivers. */
    private int external( final String body, final Map< String, Object > meta, final String author ) {
        final int v = writer.externalSave( PAGE, body, meta, author );
        session.reconcile( writer.version( PAGE, v ).orElseThrow() );
        return v;
    }

    private SessionRecord record() {
        return store.session( session.id() ).orElseThrow();
    }

    private String latestBody() {
        return CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() );
    }

    private PullResponse pullAll( final JoinResponse j, final String author, final int since ) {
        final Waiter w = new Waiter();
        final PullResponse r = session.pullOrPark( j.participantId(), author, new PullCursor( since, 0, Long.MAX_VALUE, 0 ),
            null, w );
        return r != null ? r : null;
    }

    private void checkInvariants( final CollabSession s ) {
        final CollabSession.View v = s.view();
        CollabInvariants.check( new InvariantView( v.id(), v.doc(), v.head(), v.baseBody(), v.local(), v.basePageVersion(),
            v.ring(), v.oversize(), v.maxPageBytes(), store, writer, v.pageName(), false, v.publishPending(),
            v.oversizeCeiling(), false ), true );
    }

    private void checkInvariants() {
        checkInvariants( session );
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + 1 ) ) {
            n++;
        }
        return n;
    }

    private static List< CollabEvent > events( final PullResponse r, final String type ) {
        return r.events().stream().filter( e -> e.type().equals( type ) ).toList();
    }

    // ---- section 7.3 ------------------------------------------------------------------------------------------------

    @Test
    void thePendingPublishMarkerIsDurableForTheWholeSave() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        assertEquals( 1, record().pendingPublishVersion() );
        assertEquals( 1, record().pendingPublishBaseVersion() );
        assertEquals( "alice", record().pendingPublishAuthor() );
        assertTrue( session.view().publishPending() );
        assertEquals( "X" + BODY, t.request().body() );
        assertEquals( 1, t.request().expectedVersion() );
        final PublishResponse r = assertDoesNotThrowSave( t );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.pageVersion() );
        assertNull( record().pendingPublishVersion(), "the marker is cleared with the base change" );
        assertEquals( 2, record().basePageVersion() );
        assertFalse( session.view().publishPending() );
        assertTrue( session.view().local().empty() );
        checkInvariants();
    }

    private PublishResponse assertDoesNotThrowSave( final PublishStart.Ticket t ) {
        try {
            return session.finishPublish( t, writer.publish( t.request() ) );
        } catch( final WikiException e ) {
            throw new AssertionError( "the save failed: " + e, e );
        }
    }

    @Test
    void e16_pushesAcceptedDuringTheSaveAreKeptByThePostPublishMapping() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        // editing continues during the save (the lock is not held): bob types at the end
        type( b, "bob", session.view().doc().length(), "Y" );
        writer.rewriteOnSave( s -> s.replace( "hello", "HELLO" ) );
        final PublishResponse r = assertDoesNotThrowSave( t );
        assertEquals( 200, r.status() );
        final CollabSession.View v = session.view();
        assertEquals( "XHELLO\nworld\nY", v.doc() );
        assertEquals( "XHELLO\nworld\n", v.baseBody() );
        assertEquals( "XHELLO\nworld\nY", v.local().apply( v.baseBody() ), "local = W.map(N), relative to the new base" );
        assertEquals( 3, v.head(), "one server update N.map(W)" );
        // the snapshot is of the live head after the server update, never of S_V
        final Snapshot snap = store.latestSnapshot( session.id() ).orElseThrow();
        assertEquals( 3, snap.version() );
        assertEquals( "XHELLO\nworld\nY", snap.body() );
        assertEquals( 2, snap.basePageVersion() );
        assertTrue( record().hasUnpublished(), "bob's Y is still unpublished" );
        final PullResponse caughtUp = pullAll( b, "bob", 2 );
        assertEquals( StoredUpdate.SERVER, caughtUp.updates().get( 0 ).clientId() );
        checkInvariants();
    }

    @Test
    void filterRewritesConvergeForEveryone() {   // E17, F4 rewriteOnSave, with real client rebasing
        final CollabSessionSimServer server = CollabSessionSimServer.create( "Filtered", new InMemoryCollabStore(), writer( "Filtered" ),
            clock, 1000, 3, 500 );
        final SimClient a = new SimClient( "A", server.join( "alice" ) );
        final SimClient b = new SimClient( "B", server.join( "bob" ) );
        // alice adds trailing spaces (the filter strips them); bob types right where they are, unsent during the save
        a.localEdit( ChangeSet.replace( a.doc().length(), 5, 5, "   " ) );
        assertEquals( 200, server.push( a.participantId(), "alice", a.version(), a.sendable() ).status() );
        pull( server, a, "alice" );
        final SimServer.PublishStart st = server.beginPublish( a.participantId(), "alice", a.version(), "n" );
        assertNotNull( st.pending() );
        pull( server, b, "bob" );
        b.localEdit( ChangeSet.replace( b.doc().length(), 7, 7, "typing" ) );
        assertEquals( 200, server.push( b.participantId(), "bob", b.version(), b.sendable() ).status() );
        assertEquals( 200, server.releasePublish( st.pending() ).status() );
        for( int round = 0; round < 3; round++ ) {
            pull( server, a, "alice" );
            pull( server, b, "bob" );
        }
        final String doc = server.view().doc();
        assertEquals( doc, a.doc() );
        assertEquals( doc, b.doc() );
        assertFalse( doc.contains( "   \n" ), "everyone converged on the filtered text: " + doc );
        assertEquals( 1, count( doc, "typing" ), doc );
        CollabInvariants.check( server.view(), true );
    }

    private ScriptedPageWriter writer( final String page ) {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( page, "hello\nworld\nmore\n", Map.of() );
        w.rewriteOnSave( s -> s.replaceAll( " +(?=\r?\n|$)", "" ) );
        return w;
    }

    private static void pull( final SimServer server, final SimClient c, final String author ) {
        final SimServer.PullResult r = server.pull( c.participantId(), author, c.version() );
        if( !r.parked() ) {
            assertEquals( 200, r.status() );
            c.receive( r.updates(), c.version() );
        }
        for( final SimServer.Wakeup w : server.drainWakeups() ) {
            if( w.participantId().equals( c.participantId() ) ) {
                c.receive( w.result().updates(), w.result().version() - w.result().updates().size() );
            }
        }
        if( !c.sendable().isEmpty() ) {
            final SimServer.PushResult p = server.push( c.participantId(), author, c.version(), c.sendable() );
            c.onPushResult( p );
        }
    }

    @Test
    void e18_aSecondPublishWhileOneIsInTheSaveIs423() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        final PublishResponse second = publish( b, "bob" );
        assertEquals( 423, second.status() );
        assertEquals( "publish-in-progress", second.reason() );
        assertEquals( 200, assertDoesNotThrowSave( t ).status() );
        assertEquals( 200, publish( b, "bob" ).status(), "after the first publish the flag is reset" );
        assertFalse( session.view().publishPending() );
    }

    @Test
    void e19_aRefusedPublishLeavesTheDraftAndTellsTheOthers() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        final CollabSession.View before = session.view();
        final List< WikiException > refusals = List.of( new FrontmatterValidationException( List.of() ),
            new ContentValidationException( List.of() ), new AccessRuleShapeException( List.of() ) );
        long eventSeq = 0;
        for( final WikiException e : refusals ) {
            final Waiter w = new Waiter();
            assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 1, 0, Long.MAX_VALUE, eventSeq ), null, w ) );
            writer.failNextPublish( e );
            final PublishResponse r = publish( a, "alice" );
            assertEquals( 422, r.status() );
            assertEquals( "writer-refused", r.reason() );
            assertSame( e, r.failure(), "the HTTP layer builds PageResource's 422 body from it" );
            assertEquals( 1, r.pageVersion() );
            final List< CollabEvent > failed = events( w.only(), "publish-failed" );
            assertEquals( 1, failed.size() );
            assertEquals( "Alice A", failed.get( 0 ).fields().get( "by" ) );
            eventSeq = w.only().eventSeq();
        }
        assertEquals( before.doc(), session.view().doc() );
        assertEquals( before.head(), session.view().head() );
        assertEquals( 1, session.view().basePageVersion() );
        assertNull( record().pendingPublishVersion() );
        assertEquals( 1, writer.history( PAGE ).size() );
        checkInvariants();
    }

    @Test
    void e20_anExternalSaveBetweenTheSnapshotAndTheSaveIsMergedAndThePublishRetried() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        external( BODY + "agent\n", Map.of( "title", "T" ), "agent" );
        assertEquals( 1, session.view().basePageVersion(), "queued while publishing (A3), not merged" );
        final WikiException conflict = assertThrows( WikiException.class, () -> writer.publish( t.request() ) );
        final PublishResponse r = session.failPublish( t, conflict );
        assertEquals( 409, r.status() );
        assertEquals( "publish-merged-retry", r.reason() );
        assertEquals( 2, session.view().basePageVersion() );
        assertEquals( "Xhello\nworld\nagent\n", session.view().doc() );
        assertNull( record().pendingPublishVersion() );
        final PublishResponse retry = publish( a, "alice" );
        assertEquals( 200, retry.status() );
        assertEquals( 3, retry.pageVersion() );
        assertEquals( "Xhello\nworld\nagent\n", latestBody() );
        checkInvariants();
    }

    @Test
    void e21_aPublishWithNoChangesIsUnchangedAndSavesNothing() {
        final JoinResponse a = join( "alice" );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status() );
        assertTrue( r.unchanged() );
        assertEquals( 1, r.pageVersion() );
        assertEquals( 1, writer.history( PAGE ).size() );
        assertNull( record().pendingPublishVersion() );
        assertFalse( session.view().publishPending() );
    }

    @Test
    void e22_anExternalSaveIdenticalToTheBaseAdvancesOnlyTheBase() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        external( BODY.replace( "\n", "\r\n" ), Map.of( "title", "T" ), "agent" );
        final CollabSession.View v = session.view();
        assertEquals( 2, v.basePageVersion() );
        assertEquals( 1, v.head(), "no text update" );
        assertEquals( "X" + BODY, v.doc() );
        assertEquals( 2, store.latestSnapshot( session.id() ).orElseThrow().basePageVersion() );
        checkInvariants();
    }

    @Test
    void e23_anExternalSaveOverlappingUnpublishedEditsMergesWithRanges() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        replace( a, "alice", "world", "there" );
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 1, 0, Long.MAX_VALUE, 0 ), null, w ) );
        external( "hello\nearth\nadded line\n", Map.of( "title", "T" ), "agent" );
        final CollabSession.View v = session.view();
        assertEquals( "hello\nearth\nadded line\n", v.baseBody() );
        assertEquals( 1, count( v.doc(), "there" ), v.doc() );
        assertEquals( 1, count( v.doc(), "earth" ), v.doc() );
        assertEquals( 1, count( v.doc(), "added" ), v.doc() );
        assertEquals( v.doc(), v.local().apply( v.baseBody() ) );
        final PullResponse got = w.only();
        assertEquals( StoredUpdate.SERVER, got.updates().get( 0 ).clientId() );
        final List< CollabEvent > ext = events( got, "external-change" );
        assertEquals( 1, ext.size() );
        assertEquals( "Wiki Agent", ext.get( 0 ).fields().get( "source" ) );
        @SuppressWarnings( "unchecked" )
        final List< List< Integer > > ranges = (List< List< Integer > >) ext.get( 0 ).fields().get( "ranges" );
        assertFalse( ranges.isEmpty() );
        final int addedAt = v.doc().indexOf( "added" );
        assertTrue( ranges.stream().anyMatch( r -> r.get( 0 ) <= addedAt && r.get( 1 ) >= addedAt + 5 ),
            "the ranges are in the merged document's coordinates: " + ranges + " in " + v.doc() );
        checkInvariants();
    }

    @Test
    void e24_anExternalMetaChangeKeepsOursOnTheSameFieldAndTakesTheOtherField() {
        final ScriptedPageWriter w = new ScriptedPageWriter();
        w.seed( PAGE, BODY, Map.of( "title", "T", "status", "active" ) );
        writer = w;
        session = recreate( w, config( 3, 500 ), 1000 );
        final JoinResponse a = session.join( new JoinRequest( "alice", null, "alice", null, null,
            Map.of( "title", "Ours", "status", "active" ) ) );
        assertEquals( "Ours", session.view().meta().get( "title" ) );
        final JoinResponse b = join( "bob" );
        final Waiter waiter = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 0, session.view().metaSeq(), Long.MAX_VALUE, 0 ),
            null, waiter ) );
        external( BODY, Map.of( "title", "Theirs", "status", "draft" ), "agent" );
        final CollabSession.View v = session.view();
        assertEquals( "Ours", v.meta().get( "title" ), "both changed: the session wins" );
        assertEquals( "draft", v.meta().get( "status" ), "only the outside writer changed it: taken" );
        assertEquals( List.of( new MetaOp( "status", "draft" ) ), waiter.only().metaOps() );
        final List< CollabEvent > ext = events( waiter.only(), "external-change" );
        assertEquals( List.of( "title" ), ext.get( 0 ).fields().get( "conflictFields" ) );
        assertTrue( a.participantId() != null );
        checkInvariants();
    }

    @Test
    void e25_anExternalMergeOverTheLimitIsAppliedAndEntersOversize() {
        session = recreate( writer, config( 3, 500 ), 40 );
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "0123456789" );
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 1, 0, Long.MAX_VALUE, 0 ), null, w ) );
        external( BODY + "abcdefghijklmnopqrstuvw\n", Map.of( "title", "T" ), "agent" );
        final CollabSession.View v = session.view();
        assertEquals( 46, v.doc().length(), "the merge is always applied" );
        assertTrue( v.oversize() );
        final List< CollabEvent > over = events( w.only(), "oversize" );
        assertEquals( 1, over.size() );
        assertEquals( 46L, ( (Number) over.get( 0 ).fields().get( "bytes" ) ).longValue() );
        assertEquals( 40L, ( (Number) over.get( 0 ).fields().get( "limit" ) ).longValue() );
        final CollabSession.View g = session.view();
        assertEquals( 413, session.push( a.participantId(), "alice", g.head(), List.of( new Update( a.clientId(),
            ChangeSet.replace( g.doc().length(), 0, 0, "grow" ) ) ) ).status(), "growth is refused" );
        assertEquals( 413, publish( a, "alice" ).status() );
        assertEquals( "oversize", publish( a, "alice" ).reason() );
        replace( a, "alice", "0123456789", "" );
        assertFalse( session.view().oversize() );
        assertEquals( 200, publish( a, "alice" ).status(), "trimmed under the limit: publishable again" );
        checkInvariants();
    }

    @Test
    void e26_anExternalSaveWhileEvictedIsMergedOnLoadInVersionOrder() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        // the session is evicted from memory; two outside saves land, the second building on the first
        writer.externalSave( PAGE, BODY + "first\n", Map.of( "title", "T" ), "agent" );
        writer.externalSave( PAGE, BODY + "second\n", Map.of( "title", "T" ), "agent" );
        session = reload();
        final CollabSession.View v = session.view();
        assertEquals( "Xhello\nworld\nsecond\n", v.doc() );
        assertEquals( 3, v.basePageVersion() );
        final List< StoredUpdate > log = store.updatesSince( session.id(), 1 );
        assertEquals( 2, log.size(), "one server update per version, in order" );
        assertTrue( log.stream().allMatch( u -> StoredUpdate.SERVER.equals( u.kind() ) ) );
        checkInvariants();
    }

    @Test
    void externalSaveDuringPublishIsMergedOnceAfterIt() {   // A3, review focus 2
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        final PublishOutcome saved;
        try {
            saved = writer.publish( t.request() );
        } catch( final WikiException e ) {
            throw new AssertionError( e );
        }
        // the outside save lands after ours, while the session is still inside the publish: queued
        external( "Xhello\nworld\nagent\n", Map.of( "title", "T" ), "agent" );
        assertEquals( 1, session.view().basePageVersion() );
        assertEquals( 200, session.finishPublish( t, saved ).status() );
        final CollabSession.View v = session.view();
        assertEquals( 3, v.basePageVersion() );
        assertEquals( "Xhello\nworld\nagent\n", v.doc(), "merged once, against its predecessor (our publish)" );
        assertTrue( v.local().empty() );
        checkInvariants();
    }

    // ---- E39 / A2 ---------------------------------------------------------------------------------------------------

    @Test
    void e39_recoveryFinishesThePublish() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        type( b, "bob", session.view().doc().length(), "Y" );   // typed during the save
        writer.persistThenFail( new WikiException( "the process died after the page store committed" ) );
        assertThrows( WikiException.class, () -> writer.publish( t.request() ) );
        // crash: the session never ran its post-save half
        session = reload();
        final CollabSession.View v = session.view();
        assertEquals( 2, v.basePageVersion() );
        assertEquals( "Xhello\nworld\n", v.baseBody() );
        assertEquals( "Xhello\nworld\nY", v.doc(), "no duplicated text" );
        assertEquals( "Xhello\nworld\nY", v.local().apply( v.baseBody() ) );
        assertNull( record().pendingPublishVersion() );
        checkInvariants();
    }

    @Test
    void e39_aSaveThatPersistsThenFailsIsFinishedInProcess() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        writer.persistThenFail( new WikiException( "post-save failure" ) );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status() );
        assertEquals( 2, r.pageVersion() );
        assertEquals( "Xhello\nworld\n", session.view().doc() );
        assertTrue( session.view().local().empty() );
        assertNull( record().pendingPublishVersion() );
        checkInvariants();
    }

    @Test
    void e39_aForeignVersionIsNotClaimed() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        start( a, "alice" );
        // crash before the save; meanwhile another writer creates version 2
        writer.externalSave( PAGE, BODY + "agent\n", Map.of( "title", "T" ), "agent" );
        session = reload();
        final CollabSession.View v = session.view();
        assertEquals( 2, v.basePageVersion() );
        assertEquals( BODY + "agent\n", v.baseBody() );
        assertEquals( "Xhello\nworld\nagent\n", v.doc(), "merged as an outside write, not taken for ours" );
        assertNull( record().pendingPublishVersion() );
        checkInvariants();
    }

    @Test
    void e39_aVersionByThePublisherWithOtherTextIsNotClaimed() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        start( a, "alice" );
        // the same author saved other text through another path: the hash differs, so it is not ours
        writer.externalSave( PAGE, BODY + "elsewhere\n", Map.of( "title", "T" ), "alice" );
        session = reload();
        assertEquals( "Xhello\nworld\nelsewhere\n", session.view().doc() );
        assertNull( record().pendingPublishVersion() );
        checkInvariants();
    }

    @Test
    void e39_aMarkerWithNoSavedVersionIsCleared() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        start( a, "alice" );
        session = reload();   // crashed before the save ran
        assertNull( record().pendingPublishVersion() );
        assertEquals( 1, session.view().basePageVersion() );
        assertEquals( "X" + BODY, session.view().doc() );
        checkInvariants();
    }

    @Test
    void aStoreFaultAfterTheSaveIsFinishedByThePendingRule() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 503, r.status() );
        assertEquals( "store", r.reason() );
        assertEquals( 2, writer.latest( PAGE ).orElseThrow().version(), "the page was saved" );
        assertTrue( session.view().publishPending(), "the marker explains the gap (invariant 4)" );
        checkInvariants();
        // the next publish (or reconcile) finishes it by the pending rule instead of merging our own text back in
        final PublishResponse again = publish( a, "alice" );
        assertEquals( 200, again.status() );
        assertTrue( again.unchanged() );
        assertEquals( 2, again.pageVersion() );
        assertEquals( "Xhello\nworld\n", session.view().doc() );
        assertFalse( session.view().publishPending() );
        checkInvariants();
    }

    @Test
    void aFailedBackstopMergeIsOwedAndSettledLater() {   // soak seed 2218 (PUBLISH_STORM)
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        writer.externalSave( PAGE, BODY + "missed\n", Map.of( "title", "T" ), "agent" );   // its event was missed
        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 503, r.status() );
        assertTrue( session.view().reconcilePending(), "the failed merge is owed, so the registry tick retries it" );
        session.settle();
        assertFalse( session.view().reconcilePending() );
        assertEquals( 2, session.view().basePageVersion() );
        assertEquals( "Xhello\nworld\nmissed\n", session.view().doc() );
        checkInvariants();
    }

    @Test
    void aMarkerThatCannotBeClearedIsOwed() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final PublishStart.Ticket t = start( a, "alice" );
        writer.failNextPublish( new WikiException( "refused before anything was stored" ) );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );   // clearing the marker fails
        final WikiException e = assertThrows( WikiException.class, () -> writer.publish( t.request() ) );
        assertEquals( 503, session.failPublish( t, e ).status() );
        assertTrue( session.view().publishPending() );
        assertTrue( session.view().reconcilePending(), "the marker is owed a retry" );
        session.settle();
        assertFalse( session.view().publishPending() );
        assertFalse( session.view().reconcilePending() );
        checkInvariants();
    }

    @Test
    void truncationIsClampedToThePendingPublishVersionWhileAPublishIsPending() {
        session = recreate( writer, config( 2, 2 ), 1000 );
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "a" );
        type( a, "alice", 0, "b" );
        type( a, "alice", 0, "c" );
        final PublishStart.Ticket t = start( a, "alice" );   // V = 3
        for( int i = 0; i < 4; i++ ) {
            type( a, "alice", 0, "d" );                       // snapshots at 4 and 6, each truncating
        }
        session.truncateLog();
        final List< StoredUpdate > log = store.updatesSince( session.id(), 0 );
        assertTrue( log.get( 0 ).version() <= 4, "S_V (version 3) must stay rebuildable: log starts at " + log.get( 0 ).version() );
        writer.persistThenFail( new WikiException( "crash after the save" ) );
        assertThrows( WikiException.class, () -> writer.publish( t.request() ) );
        session = CollabSession.load( session.id(), ports( store, writer ), config( 2, 2 ), 1000 );
        assertEquals( 2, session.view().basePageVersion() );
        assertEquals( "dddd" + "cba" + BODY, session.view().doc() );
        assertEquals( "cba" + BODY, session.view().baseBody() );
        checkInvariants();
    }

    // ---- A1 backstop ------------------------------------------------------------------------------------------------

    @Test
    void aWatcherOverwriteWithoutANewVersionIsReconciledAtPublish() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        writer.overwriteWithoutVersion( PAGE, BODY + "watcher\n" );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 409, r.status() );
        assertEquals( "publish-merged-retry", r.reason() );
        assertEquals( "Xhello\nworld\nwatcher\n", session.view().doc() );
        assertEquals( 1, session.view().basePageVersion() );
        final PublishResponse retry = publish( a, "alice" );
        assertEquals( 200, retry.status() );
        assertEquals( "Xhello\nworld\nwatcher\n", latestBody() );
        checkInvariants();
    }

    // ---- publish details (ledger) -----------------------------------------------------------------------------------

    @Test
    void aFieldUnchangedAgainstTheBaseIsPublishedWithItsOriginalTypedValue() {
        final Date date = Date.from( Instant.parse( "2026-01-15T00:00:00Z" ) );
        final ScriptedPageWriter w = new ScriptedPageWriter();
        final Map< String, Object > meta = new LinkedHashMap<>();
        meta.put( "title", "T" );
        meta.put( "date", date );
        meta.put( "count", 3 );
        w.seed( PAGE, BODY, meta );
        writer = w;
        session = recreate( w, config( 3, 500 ), 1000 );
        final JoinResponse a = session.join( new JoinRequest( "alice", null, "alice", null, null,
            Map.of( "title", "New", "date", "2026-01-15", "count", 3L ) ) );
        type( a, "alice", 0, "X" );
        assertEquals( 200, publish( a, "alice" ).status() );
        final Map< String, Object > saved = w.latest( PAGE ).orElseThrow().meta();
        assertSame( date, saved.get( "date" ), "not the normalised string: " + saved );
        assertEquals( 3, saved.get( "count" ) );
        assertEquals( "New", saved.get( "title" ) );
    }

    @Test
    void filterMadeMetaChangesAreFoldedAsMetaOpsAppendedBeforeTheBaseChange() {
        store = new InMemoryCollabStore();
        final List< String > calls = new ArrayList<>();
        final CollabStore recording = (CollabStore) Proxy.newProxyInstance( CollabStore.class.getClassLoader(),
            new Class< ? >[]{ CollabStore.class }, ( proxy, m, args ) -> {
                calls.add( m.getName() );
                try {
                    return m.invoke( store, args );
                } catch( final java.lang.reflect.InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
        final CollabPageWriter injecting = new CollabPageWriter() {
            @Override
            public Optional< PageState > latest( final String pageName ) {
                return writer.latest( pageName );
            }

            @Override
            public Optional< PageState > version( final String pageName, final int version ) {
                return writer.version( pageName, version );
            }

            @Override
            public PublishOutcome publish( final PublishRequest request ) throws WikiException {
                final Map< String, Object > meta = new LinkedHashMap<>( request.meta() );
                meta.put( "canonical_id", "01ABC" );
                final PublishOutcome o = writer.publish( new PublishRequest( request.pageName(), request.body(), meta,
                    request.expectedVersion(), request.authorKey(), request.changeNote() ) );
                return new PublishOutcome( o.version(), o.savedBody(), meta, o.unchanged() );
            }
        };
        final SessionRecord rec = CollabSession.newRecord( UUID.randomUUID(), PAGE, writer.latest( PAGE ), "c", clock );
        final CollabSession s = CollabSession.create( rec, ports( recording, injecting ), config( 3, 500 ), 1000 );
        final JoinResponse a = s.join( JoinRequest.of( "alice", "alice", null ) );
        assertEquals( 200, s.push( a.participantId(), "alice", 0, List.of( new Update( a.clientId(),
            ChangeSet.replace( BODY.length(), 0, 0, "X" ) ) ) ).status() );
        calls.clear();
        assertEquals( 200, s.publish( a.participantId(), "alice", 1, "n" ).status() );
        assertTrue( calls.indexOf( "appendMetaOps" ) >= 0, calls.toString() );
        assertTrue( calls.indexOf( "appendMetaOps" ) < calls.indexOf( "commitBaseChange" ), calls.toString() );
        assertEquals( "01ABC", s.view().meta().get( "canonical_id" ) );
        final List< StoredMetaOp > ops = store.metaOpsSince( s.id(), 0 );
        assertEquals( "canonical_id", ops.get( ops.size() - 1 ).field() );
        final Snapshot snap = store.latestSnapshot( s.id() ).orElseThrow();
        assertTrue( snap.metaSeq() <= store.session( s.id() ).orElseThrow().metaSeq(), "snapshot.metaSeq <= store metaSeq" );
        assertEquals( "01ABC", snap.meta().get( "canonical_id" ) );
        assertFalse( store.session( s.id() ).orElseThrow().hasUnpublished(), "the folded field is the base's value" );
    }

    @Test
    void aPublishFromAnOlderSyncedVersionPublishesTheHead() {   // section 7.3: the server publishes head
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        type( b, "bob", 1, "Y" );
        final PublishResponse r = session.publish( a.participantId(), "alice", 1, "n" );
        assertEquals( 200, r.status() );
        assertEquals( "XYhello\nworld\n", latestBody() );
    }

    @Test
    void aPublishFromAVersionTheSessionNeverIssuedIsNotSynced() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final PublishResponse r = session.publish( a.participantId(), "alice", 5, "n" );
        assertEquals( 409, r.status() );
        assertEquals( "not-synced", r.reason() );
        assertNull( record().pendingPublishVersion() );
    }

    @Test
    void aPublishTellsEveryoneWhoPublished() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "X" );
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 1, 0, Long.MAX_VALUE, 0 ), null, w ) );
        assertEquals( 200, publish( a, "alice" ).status() );
        final List< CollabEvent > published = events( w.only(), "published" );
        assertEquals( 1, published.size(), "woken by the event alone: the publish needed no server update" );
        assertEquals( "Alice A", published.get( 0 ).fields().get( "by" ) );
        assertEquals( 2, ( (Number) published.get( 0 ).fields().get( "pageVersion" ) ).intValue() );
        assertFalse( published.get( 0 ).fields().containsValue( "alice" ), "never the login" );
    }

    @Test
    void e31_aParticipantWhoLostEditByThePublishedBodyIsEvicted() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", 0, "[{ALLOW edit alice}]\n" );
        final Waiter w = new Waiter();
        assertNull( session.pullOrPark( b.participantId(), "bob", new PullCursor( 1, Long.MAX_VALUE, Long.MAX_VALUE,
            Long.MAX_VALUE ), null, w ) );
        noEdit.add( "bob" );   // the newly saved ACL excludes bob (A5: re-checked after every publish)
        assertEquals( 200, publish( a, "alice" ).status() );
        assertEquals( 1, session.view().participants() );
        assertEquals( 1, w.got.size(), "bob's parked pull is answered" );
        assertEquals( "rejoin", session.push( b.participantId(), "bob", session.view().head(), List.of() ).reason() );
    }

    @Test
    void aParticipantWhoLostEditByAnExternalSaveIsEvicted() {
        join( "alice" );
        join( "bob" );
        noEdit.add( "bob" );
        external( BODY + "[{ALLOW edit alice}]\n", Map.of( "title", "T" ), "agent" );
        assertEquals( 1, session.view().participants() );
    }

    @Test
    void reconcileByContentHashIgnoresAStaleOrRepeatedEvent() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", 0, "X" );
        final int v = external( BODY + "agent\n", Map.of( "title", "T" ), "agent" );
        final CollabSession.View once = session.view();
        session.reconcile( writer.version( PAGE, v ).orElseThrow() );
        session.reconcile( writer.version( PAGE, 1 ).orElseThrow() );
        assertEquals( once.head(), session.view().head(), "a repeated or older event merges nothing" );
        assertEquals( once.doc(), session.view().doc() );
        checkInvariants();
    }
}

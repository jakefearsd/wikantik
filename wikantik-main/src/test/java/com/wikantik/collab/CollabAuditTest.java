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

import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 Task 5: the audit entries of collaborative editing (section 9, plan decision 7) through a recording
 * {@link CollabAudit}: join, publish (with the credited contributors, once on the E39 path), discard, eviction, an
 * orphaning publish, session open, an administrator's purge and retention's expiry. Entries are recorded after the
 * session lock is released, and a failing audit never fails an edit.
 */
class CollabAuditTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final CollabIdentity IDENTITY = key -> null;

    /** Records each call as one line; fails a call made while the watched session's lock is held. */
    static final class RecordingAudit implements CollabAudit {
        final List< String > entries = Collections.synchronizedList( new ArrayList<>() );
        final Set< UUID > sessions = Collections.synchronizedSet( new HashSet<>() );
        volatile CollabSession watched;

        private void add( final UUID session, final String line ) {
            final CollabSession s = watched;
            if( s != null ) {
                assertFalse( CollabSession.Hooks.lockHeld( s ), "audited under the session lock: " + line );
            }
            sessions.add( session );
            entries.add( line );
        }

        @Override
        public void sessionOpened( final UUID session, final String page, final String authorKey ) {
            add( session, "open " + page + " " + authorKey );
        }

        @Override
        public void joined( final UUID session, final String page, final String authorKey, final String participantId ) {
            add( session, "join " + page + " " + authorKey + ( participantId == null ? " no-participant" : "" ) );
        }

        @Override
        public void published( final UUID session, final String page, final String publisherKey, final int pageVersion,
                               final int collabVersion, final Set< String > contributorKeys ) {
            add( session, "publish " + page + " " + publisherKey + " pageVersion=" + pageVersion + " collabVersion="
                + collabVersion + " " + new TreeSet<>( contributorKeys ) );
        }

        @Override
        public void discarded( final UUID session, final String page, final String authorKey ) {
            add( session, "discard " + page + " " + authorKey );
        }

        @Override
        public void evicted( final UUID session, final String page, final String authorKey, final String reason ) {
            add( session, "evict " + page + " " + authorKey + " " + reason );
        }

        @Override
        public void purged( final UUID session, final String page, final String adminKey, final boolean expired ) {
            add( session, ( expired ? "expire " : "purge " ) + page + " " + adminKey );
        }

        @Override
        public void orphaned( final UUID session, final String page ) {
            add( session, "orphaned " + page );
        }

        List< String > starting( final String prefix ) {
            synchronized( entries ) {
                return entries.stream().filter( e -> e.startsWith( prefix ) ).toList();
            }
        }
    }

    /** Throws on every call. */
    private static final CollabAudit THROWING = new CollabAudit() {
        @Override
        public void joined( final UUID session, final String page, final String authorKey, final String participantId ) {
            throw new IllegalStateException( "test: the audit log is down" );
        }

        @Override
        public void published( final UUID session, final String page, final String publisherKey, final int pageVersion,
                               final int collabVersion, final Set< String > contributorKeys ) {
            throw new IllegalStateException( "test: the audit log is down" );
        }

        @Override
        public void discarded( final UUID session, final String page, final String authorKey ) {
            throw new IllegalStateException( "test: the audit log is down" );
        }

        @Override
        public void evicted( final UUID session, final String page, final String authorKey, final String reason ) {
            throw new IllegalStateException( "test: the audit log is down" );
        }
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private RecordingAudit audit;
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

    private static CollabConfig config( final int retentionDays ) {
        return new CollabConfig( true, 20, 65536, 25, 3600, 30, 100, 500, 10, retentionDays, 1000 );
    }

    private CollabPorts ports( final CollabAudit a ) {
        return new CollabPorts( store, writer, IDENTITY, permissions, clock, a, CollabMetrics.NONE );
    }

    private CollabSession create( final CollabAudit a ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( PAGE.getBytes() ), PAGE,
            writer.latest( PAGE ), "creator", clock );
        return CollabSession.create( rec, ports( a ), config( 0 ), 1000 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        audit = new RecordingAudit();
        session = create( audit );
        audit.watched = session;
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, author, null ) );
    }

    private void type( final JoinResponse j, final String author, final String text ) {
        final CollabSession.View v = session.view();
        final PushResponse r = session.push( j.participantId(), author, v.head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
        assertEquals( 200, r.status(), "push: " + r );
    }

    private PublishResponse publish( final JoinResponse j, final String author ) {
        return session.publish( j.participantId(), author, session.view().head(), "note" );
    }

    // ---- session-level entries -----------------------------------------------------------------------------------

    @Test
    void aJoinPublishDiscardAndEvictionAreAudited() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "X" );
        type( b, "bob", "Y" );
        final int v = session.view().head();
        final PublishResponse published = publish( a, "alice" );
        assertEquals( 200, published.status() );
        type( b, "bob", "Z" );
        assertEquals( 200, session.discard( b.participantId(), "bob" ).status() );
        session.evict( b.participantId(), CollabSession.SIGNED_OUT );

        assertEquals( List.of(
            "join Page alice",
            "join Page bob",
            "publish Page alice pageVersion=" + published.pageVersion() + " collabVersion=" + v + " [alice, bob]",
            "discard Page bob",
            "evict Page bob signed-out" ), audit.entries );
        assertEquals( Set.of( session.id() ), audit.sessions );
    }

    @Test
    void theE39PathAuditsThePublishOnce() throws Exception {
        final JoinResponse a = join( "alice" );
        type( a, "alice", "X" );
        // in process: the save persisted, then failed; the failed save is claimed and finished
        writer.persistThenFail( new WikiException( "post-save failure" ) );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status() );
        assertEquals( List.of( "publish Page alice pageVersion=2 collabVersion=1 [alice]" ), audit.starting( "publish" ) );

        // after a crash: the reload's pending-publish rule finishes it, audited once
        type( a, "alice", "Q" );
        final int v = session.view().head();
        final PublishStart st = session.startPublish( a.participantId(), "alice", v, "note" );
        assertNull( st.response() );
        writer.persistThenFail( new WikiException( "the process died after the page store committed" ) );
        assertThrows( WikiException.class, () -> writer.publish( st.ticket().request() ) );
        final RecordingAudit after = new RecordingAudit();
        final CollabSession reloaded = CollabSession.load( session.id(), ports( after ), config( 0 ), 1000 );
        reloaded.settle();
        assertEquals( List.of( "publish Page alice pageVersion=3 collabVersion=" + v + " [alice]" ), after.entries );
    }

    @Test
    void anOrphaningPublishIsAudited() throws Exception {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "X" );
        final PublishStart st = session.startPublish( a.participantId(), "alice", session.view().head(), "note" );
        assertNull( st.response() );
        type( b, "bob", "Y" );   // typed during the save: unpublished after it
        final PublishOutcome saved = writer.publish( st.ticket().request() );
        noEdit.add( "alice" );
        noEdit.add( "bob" );   // the save made the page editor-only, say

        assertEquals( 200, session.finishPublish( st.ticket(), saved ).status() );

        final List< String > tail = audit.entries.subList( audit.entries.size() - 3, audit.entries.size() );
        assertEquals( List.of( "evict Page alice lost-edit", "evict Page bob lost-edit", "orphaned Page" ), tail );
    }

    @Test
    void aPublishThatLeavesAnEditorOrNothingUnpublishedOrphansNothing() throws Exception {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "X" );
        noEdit.add( "bob" );
        assertEquals( 200, publish( a, "alice" ).status() );   // alice keeps edit
        type( a, "alice", "Y" );
        final PublishStart st = session.startPublish( a.participantId(), "alice", session.view().head(), "note" );
        final PublishOutcome saved = writer.publish( st.ticket().request() );
        noEdit.add( "alice" );
        assertEquals( 200, session.finishPublish( st.ticket(), saved ).status() );   // nothing left unpublished

        assertEquals( List.of(), audit.starting( "orphaned" ) );
        assertEquals( List.of( "evict Page bob lost-edit", "evict Page alice lost-edit" ), audit.starting( "evict" ) );
        assertFalse( session.hasParticipant( b.participantId() ) );
    }

    @Test
    void aThrowingAuditDoesNotFailThePush() {
        store = new InMemoryCollabStore();   // one session per page per store
        session = create( THROWING );
        final JoinResponse[] a = new JoinResponse[ 1 ];
        final List< LogEvent > warnings = CollabLogCapture.warnings( () -> {
            a[ 0 ] = join( "alice" );
            final JoinResponse b = join( "bob" );
            final List< PullResponse > parked = new ArrayList<>();
            assertNull( session.pullOrPark( b.participantId(), "bob", session.view().head(),
                session.view().presenceSeq(), null, parked::add ) );
            join( "carol" );   // its audit throws; bob's parked pull is still answered
            assertEquals( 1, parked.size() );
            type( a[ 0 ], "alice", "X" );
            assertEquals( 200, publish( a[ 0 ], "alice" ).status() );
            type( b, "bob", "Y" );
            assertEquals( 200, session.discard( b.participantId(), "bob" ).status() );
            session.evict( b.participantId(), CollabSession.LOST_EDIT );
        } );
        assertEquals( "Xhello\nworld\n", writer.latest( PAGE ).orElseThrow().body().replace( "\r\n", "\n" ) );
        assertTrue( warnings.stream().anyMatch( e -> e.getLevel() == Level.WARN
            && e.getMessage().getFormattedMessage().contains( "audit entry was not recorded" ) ), warnings.toString() );
    }

    // ---- registry-level entries ----------------------------------------------------------------------------------

    private CollabSessionRegistry registry;

    private Joined join( final String page, final String author ) {
        return assertInstanceOf( Joined.class, registry.join( page,
            new JoinRequest( author, null, author, author.toUpperCase(), null, null ) ) );
    }

    private void leaveAndIdleOut( final Joined j, final String author ) {
        assertEquals( 200, j.session().leave( j.response().participantId(), author ).status() );
        registry.tick();
        clock.advance( Duration.ofMinutes( 10 ) );
        registry.tick();
        assertTrue( registry.liveForPage( j.session().pageName() ).isEmpty() );
    }

    @Test
    void purgeAndExpiryAreAuditedAsAdminAndSystem() {
        store = new InMemoryCollabStore( clock );
        registry = new CollabSessionRegistry( ports( audit ), config( 30 ), 1 << 20 );
        audit.watched = null;
        final Joined a = join( PAGE, "alice" );
        join( PAGE, "bob" );
        assertEquals( 204, registry.purge( a.response().sessionId(), "root" ).status() );
        assertEquals( List.of( "open Page alice", "join Page alice", "join Page bob", "evict Page alice purged",
            "evict Page bob purged", "purge Page root" ), audit.entries );

        audit.entries.clear();
        writer.seed( "Other", BODY, Map.of() );
        final Joined c = join( "Other", "carol" );
        final PushResponse typed = c.session().push( c.response().participantId(), "carol", 0,
            List.of( new Update( c.response().clientId(), ChangeSet.replace( BODY.length(), 0, 0, "c" ) ) ) );
        assertEquals( 200, typed.status() );
        leaveAndIdleOut( c, "carol" );
        final Joined d = join( "Other", "dave" );   // a reload from the store is not an open
        leaveAndIdleOut( d, "dave" );
        clock.advance( Duration.ofDays( 31 ) );
        registry.tick();

        assertEquals( List.of( "open Other carol", "join Other carol", "join Other dave", "expire Other null" ),
            audit.entries );
        assertEquals( Set.of( a.response().sessionId(), c.response().sessionId() ), audit.sessions );
    }
}

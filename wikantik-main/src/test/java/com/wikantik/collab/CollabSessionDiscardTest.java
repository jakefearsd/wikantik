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
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 6.2's discard (resets the draft to the latest page version, broadcast as a server update and an event), plus
 * the small read accessors the HTTP surface needs: {@code hasParticipant}, {@code metaSeq} and the join's
 * {@code eventSeq}.
 */
class CollabSessionDiscardTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSession session;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        final SessionRecord rec = CollabSession.newRecord( UUID.randomUUID(), PAGE, writer.latest( PAGE ), "creator",
            clock );
        session = CollabSession.create( rec, new CollabPorts( store, writer, key -> key.toUpperCase(),
            CollabSessionTest.ALLOW, clock ), CollabSessionTest.config( 100, 500, 1000 ), 1_000_000 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, null, null ) );
    }

    private PushResponse type( final JoinResponse j, final String author, final String text ) {
        final String doc = session.view().doc();
        return session.push( j.participantId(), author, session.view().head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( doc.length(), 0, 0, text ) ) ) );
    }

    @Test
    void discardResetsTheDraftToTheBaseAsOneServerUpdate() {
        final JoinResponse a = join( "ada" );
        type( a, "ada", "XYZ " );
        assertTrue( session.view().doc().startsWith( "XYZ " ) );
        final int before = session.view().head();

        final PushResponse r = session.discard( a.participantId(), "ada" );

        assertEquals( 200, r.status() );
        assertEquals( BODY, session.view().doc() );
        assertTrue( session.view().local().empty(), "nothing unpublished after a discard" );
        assertEquals( before + 1, session.view().head(), "one server update" );
        assertEquals( CollabSession.SERVER_CLIENT, session.view().ring().get( session.view().ring().size() - 1 ).clientId() );
        assertEquals( before + 1, r.version() );
    }

    @Test
    void discardIsBroadcastToAParkedPullWithTheUpdateAndAnEvent() {
        final JoinResponse a = join( "ada" );
        final JoinResponse b = join( "raj" );
        type( a, "ada", "XYZ " );
        final int head = session.view().head();
        final List< PullResponse > got = new ArrayList<>();
        // park raj at head, with an event cursor at the current event sequence
        final PullResponse now = session.pullOrPark( b.participantId(), "raj",
            new PullCursor( head, session.metaSeq(), Long.MAX_VALUE, b.eventSeq() ), null, got::add );
        assertEquals( null, now );

        session.discard( a.participantId(), "ada" );

        assertEquals( 1, got.size() );
        final PullResponse pulled = got.get( 0 );
        assertEquals( 200, pulled.status() );
        assertEquals( 1, pulled.updates().size() );
        final List< CollabEvent > events = pulled.events();
        assertTrue( events.stream().anyMatch( e -> "draft-discarded".equals( e.type() )
            && "ADA".equals( e.fields().get( "by" ) ) ), events.toString() );
    }

    @Test
    void discardRestoresTheBaseMetadataAsServerMetaOps() {
        final JoinResponse a = session.join( new JoinRequest( "ada", null, null, null, null,
            Map.of( "title", "Changed" ) ) );
        assertEquals( "Changed", session.view().meta().get( "title" ) );
        final long metaSeq = session.metaSeq();

        assertEquals( 200, session.discard( a.participantId(), "ada" ).status() );

        assertEquals( "T", session.view().meta().get( "title" ) );
        assertTrue( session.metaSeq() > metaSeq );
    }

    @Test
    void discardFirstMergesTheLatestPageVersion() {
        final JoinResponse a = join( "ada" );
        type( a, "ada", "XYZ " );
        writer.externalSave( PAGE, "fresh\n", Map.of( "title", "T" ), "agent" );

        assertEquals( 200, session.discard( a.participantId(), "ada" ).status() );

        assertEquals( "fresh\n", session.view().doc() );
        assertEquals( 2, session.view().basePageVersion() );
    }

    @Test
    void anUnchangedDraftDiscardsToNothing() {
        final JoinResponse a = join( "ada" );
        final int head = session.view().head();
        assertEquals( 200, session.discard( a.participantId(), "ada" ).status() );
        assertEquals( head, session.view().head(), "no update for an unchanged draft" );
    }

    @Test
    void discardChecksTheParticipant() {
        final JoinResponse a = join( "ada" );
        assertEquals( 403, session.discard( a.participantId(), "raj" ).status() );
        assertEquals( "not-your-participant", session.discard( a.participantId(), "raj" ).reason() );
        assertEquals( 409, session.discard( "nobody", "ada" ).status() );
        assertEquals( "rejoin", session.discard( "nobody", "ada" ).reason() );
    }

    @Test
    void aStoreFailureDiscardsNothing() {
        final JoinResponse a = join( "ada" );
        type( a, "ada", "XYZ " );
        final String doc = session.view().doc();
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 1 );
        store.failOn( InMemoryCollabStore.Op.APPEND_BATCH, 1 );

        final PushResponse r = session.discard( a.participantId(), "ada" );

        assertEquals( 503, r.status() );
        assertEquals( "store", r.reason() );
        assertEquals( doc, session.view().doc() );
    }

    /** Any one store write failing leaves a meta-carrying draft either fully discarded or untouched, never half. */
    @Test
    void aStoreFailureNeverHalfDiscardsAMetaCarryingDraft() {
        for( final InMemoryCollabStore.Op op : InMemoryCollabStore.Op.values() ) {
            setUp();
            final JoinResponse a = session.join( new JoinRequest( "ada", null, null, null, null,
                Map.of( "title", "Changed" ) ) );
            type( a, "ada", "XYZ " );
            final long metaSeq = session.metaSeq();
            store.failOn( op, 1 );

            final PushResponse r = session.discard( a.participantId(), "ada" );

            final boolean textReset = BODY.equals( session.view().doc() );
            final boolean metaReset = "T".equals( session.view().meta().get( "title" ) );
            assertEquals( textReset, metaReset, op + ": text and meta reset together" );
            if( r.status() == 503 ) {
                assertFalse( textReset, op + ": a refused discard changes nothing" );
                assertEquals( metaSeq, session.metaSeq(), op.toString() );
                assertEquals( 0, store.metaOpsSince( session.id(), metaSeq ).size(), op + ": nothing half-stored" );
            } else {
                assertEquals( 200, r.status(), op.toString() );
                assertTrue( textReset, op.toString() );
            }
        }
    }

    @Test
    void hasParticipantFollowsJoinAndLeave() {
        final JoinResponse a = join( "ada" );
        assertTrue( session.hasParticipant( a.participantId() ) );
        session.leave( a.participantId(), "ada" );
        assertFalse( session.hasParticipant( a.participantId() ) );
        assertFalse( session.hasParticipant( "nobody" ) );
    }

    @Test
    void joinCarriesTheEventSequence() {
        final JoinResponse a = join( "ada" );
        type( a, "ada", "XYZ " );
        session.discard( a.participantId(), "ada" );
        final JoinResponse b = join( "raj" );
        assertEquals( 1, a.eventSeq(), "its own participant-joined" );
        assertEquals( 3, b.eventSeq(), "a new joiner starts after the events it need not replay" );
    }
}

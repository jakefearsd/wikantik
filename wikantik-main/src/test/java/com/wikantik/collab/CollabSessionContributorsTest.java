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
import com.wikantik.collab.model.DraftSummary;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.PublishResponse;
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contributor tracking per draft and the credit at the publish point (docs/MultiEdit.MD sections 7.3 and 10, rulings
 * R2-R5): who is written to {@code page_version_contributor} for each published version, the {@code draft-state}
 * event, the draft summary, and a rejoining author's stable {@code "Editor N"}.
 */
class CollabSessionContributorsTest {

    private static final String PAGE = "Page";
    private static final String CID = "01HZCANONICAL0000000000001";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final CollabIdentity IDENTITY = key -> switch( key ) {
        case "alice" -> "Alice A";
        case "bob" -> "Bob B";
        case "carol" -> "Carol C";
        case "dave" -> "Dave D";
        case "agent" -> "Wiki Agent";
        default -> null;
    };
    private static final CollabPermissions ALLOW = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return !"revoked".equals( participantSession );
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private CollabSession session;
    /** How many times the session called {@link CollabStore#recordContributors}. */
    private final AtomicInteger ledgerWrites = new AtomicInteger();

    private static CollabConfig config( final int snapshotEvery ) {
        return new CollabConfig( true, 20, 65536, 25, 45, 30, 50, snapshotEvery, 10, 0, 1000 );
    }

    /** The store as the session sees it: every call delegated, {@code recordContributors} counted. */
    private CollabStore counting() {
        return (CollabStore) Proxy.newProxyInstance( CollabStore.class.getClassLoader(), new Class< ? >[] { CollabStore.class },
            ( proxy, m, args ) -> {
                if( m.getName().equals( "recordContributors" ) ) {
                    ledgerWrites.incrementAndGet();
                }
                try {
                    return m.invoke( store, args );
                } catch( final InvocationTargetException e ) {
                    throw e.getCause();
                }
            } );
    }

    private CollabPorts ports() {
        return new CollabPorts( counting(), writer, IDENTITY, ALLOW, clock );
    }

    private CollabSession createOn( final String page, final int snapshotEvery ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( page.getBytes() ), page,
            writer.latest( page ), "creator", clock );
        return CollabSession.create( rec, ports(), config( snapshotEvery ), 100_000 );
    }

    private CollabSession reload( final int snapshotEvery ) {
        return CollabSession.load( session.id(), ports(), config( snapshotEvery ), 100_000 );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T", "canonical_id", CID ) );
        clock = new MutableClock( T0 );
        session = createOn( PAGE, 500 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, author, null ) );
    }

    /** Appends {@code text} at the end of the head document; asserts the push was accepted. */
    private void type( final JoinResponse j, final String author, final String text ) {
        final CollabSession.View v = session.view();
        final int end = v.doc().length();
        final PushResponse r = session.push( j.participantId(), author, v.head(),
            List.of( new Update( j.clientId(), ChangeSet.replace( end, end, end, text ) ) ) );
        assertEquals( 200, r.status(), "push: " + r );
    }

    private void editMeta( final JoinResponse j, final String author, final MetaEdit e ) {
        final PushResponse r = session.push( j.participantId(), author, session.view().head(), List.of(), List.of( e ),
            null );
        assertEquals( 200, r.status(), "meta push: " + r );
    }

    private PublishResponse publish( final JoinResponse j, final String author ) {
        return session.publish( j.participantId(), author, session.view().head(), "note", "markdown" );
    }

    private Map< Integer, List< String > > ledger() {
        return store.contributorsByVersion( CID );
    }

    private PullResponse events( final JoinResponse j, final String author ) {
        final PullResponse r = session.pullOrPark( j.participantId(), author,
            new PullCursor( session.view().head(), Long.MAX_VALUE, Long.MAX_VALUE, 0 ), null, x -> { } );
        assertNotNull( r, "events are pending, so the pull answers at once" );
        return r;
    }

    private static List< CollabEvent > ofType( final PullResponse r, final String type ) {
        return r.events().stream().filter( e -> e.type().equals( type ) ).toList();
    }

    // ---- credit at publish ------------------------------------------------------------------------------------------

    @Test
    void twoAuthorsArePublishedAsContributors() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "A" );
        type( b, "bob", "B" );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 2, r.pageVersion() );
        assertEquals( Map.of( 2, List.of( "alice", "bob" ) ), ledger() );
        assertEquals( List.of(), session.view().contributorKeys(), "a publish with no edits during its save empties it" );
        assertFalse( session.view().unpublished() );
    }

    @Test
    void anEditDuringTheSaveIsCreditedToTheNextVersionOnly() throws Exception {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final JoinResponse c = join( "carol" );
        type( a, "alice", "A" );
        type( c, "carol", "C1" );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        final int v = session.view().head();
        final CompletableFuture< PublishResponse > saving = CompletableFuture.supplyAsync(
            () -> session.publish( a.participantId(), "alice", v, "note", "markdown" ) );
        pause.awaitEntered( Duration.ofSeconds( 10 ) );
        type( b, "bob", "B" );       // during the save: not in V
        type( c, "carol", "C2" );    // carol edited on both sides of V
        pause.release();
        final PublishResponse first = saving.get( 10, TimeUnit.SECONDS );
        assertEquals( 200, first.status(), first.toString() );
        assertEquals( Map.of( 2, List.of( "alice", "carol" ) ), ledger(), "bob's edit landed after V" );
        assertEquals( List.of( "bob", "carol" ), session.view().contributorKeys().stream().sorted().toList() );
        final PublishResponse second = publish( b, "bob" );
        assertEquals( 200, second.status(), second.toString() );
        assertEquals( Map.of( 2, List.of( "alice", "carol" ), 3, List.of( "bob", "carol" ) ), ledger() );
    }

    @Test
    void aMetaOnlyContributorIsCredited() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "A" );
        editMeta( b, "bob", MetaEdit.set( "summary", "Bob's summary" ) );
        assertEquals( 200, publish( a, "alice" ).status() );
        assertEquals( Map.of( 2, List.of( "alice", "bob" ) ), ledger() );
    }

    @Test
    void theCreatorsSeedIsCredited() {
        session = createOn( "NewPage", 500 );
        session.join( new JoinRequest( "alice", null, "alice", null, "From a template\n",
            Map.of( "title", "New", "canonical_id", CID ) ) );
        final JoinResponse b = join( "bob" );
        assertEquals( List.of( "alice" ), session.view().contributorKeys() );
        assertEquals( List.of( "Alice A" ), b.contributors() );
        final PublishResponse r = publish( b, "bob" );   // bob publishes without typing: he is not credited
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 1, r.pageVersion() );
        assertEquals( Map.of( 1, List.of( "alice" ) ), ledger() );
    }

    @Test
    void externalMergesCreditNobody() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", "A" );
        final int v = writer.externalSave( PAGE, "agent line\n" + BODY, Map.of( "title", "T", "canonical_id", CID ),
            "agent" );
        session.reconcile( writer.version( PAGE, v ).orElseThrow() );
        assertEquals( List.of( "alice" ), session.view().contributorKeys() );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( Map.of( 3, List.of( "alice" ) ), ledger() );
    }

    @Test
    void discardClearsTheContributors() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final JoinResponse c = join( "carol" );
        type( a, "alice", "A" );
        type( b, "bob", "B" );
        assertEquals( 200, session.discard( b.participantId(), "bob" ).status() );
        assertEquals( List.of(), session.view().contributorKeys() );
        assertEquals( Map.of(), store.latestSnapshot( session.id() ).orElseThrow().contributors() );
        type( c, "carol", "C" );
        assertEquals( 200, publish( c, "carol" ).status() );
        assertEquals( Map.of( 2, List.of( "carol" ) ), ledger() );
    }

    @Test
    void aRecreatePublishClearsOldRows() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        type( a, "alice", "A" );
        type( b, "bob", "B" );
        assertEquals( 200, publish( a, "alice" ).status() );
        assertEquals( Map.of( 2, List.of( "alice", "bob" ) ), ledger() );
        type( a, "alice", "again" );
        writer.delete( PAGE );
        session.freeze();
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 200, r.status(), r.toString() );
        assertEquals( 1, r.pageVersion(), "the page is created again" );
        assertEquals( Map.of( 1, List.of( "alice" ) ), ledger(), "the old page's rows are cleared" );
    }

    @Test
    void aMissingCanonicalIdSkipsTheLedgerWithAWarn() {
        writer.seed( "Bare", BODY, Map.of( "title", "T" ) );
        session = createOn( "Bare", 500 );
        final JoinResponse a = join( "alice" );
        type( a, "alice", "A" );
        try( LogCapture w = new LogCapture( PublishFlow.class ) ) {
            assertEquals( 200, publish( a, "alice" ).status() );
            assertTrue( w.lines.stream().anyMatch( l -> l.contains( "Bare" ) && l.contains( "canonical_id" ) ),
                w.lines.toString() );
        }
        assertEquals( 0, ledgerWrites.get() );
    }

    @Test
    void aLedgerFailureLeavesThePublishUnfinishedAndTheRetryRecords() {
        final JoinResponse a = join( "alice" );
        type( a, "alice", "A" );
        store.failOn( InMemoryCollabStore.Op.RECORD_CONTRIBUTORS, 1 );
        final PublishResponse r = publish( a, "alice" );
        assertEquals( 503, r.status(), r.toString() );
        assertEquals( 2, writer.latest( PAGE ).orElseThrow().version(), "the version was saved" );
        assertNotNull( store.session( session.id() ).orElseThrow().pendingPublishVersion(), "the marker stays" );
        assertEquals( Map.of(), ledger() );
        assertEquals( List.of( "alice" ), session.view().contributorKeys(), "not replaced before the commit" );
        session.settle();
        assertEquals( Map.of( 2, List.of( "alice" ) ), ledger() );
        assertNull( store.session( session.id() ).orElseThrow().pendingPublishVersion() );
        assertEquals( 2, session.view().basePageVersion() );
        assertEquals( List.of(), session.view().contributorKeys() );
    }

    // ---- persistence --------------------------------------------------------------------------------------------------

    @Test
    void contributorsSurviveTruncationAndReload() {
        writer.seed( "Trunc", BODY, Map.of( "canonical_id", CID ) );
        session = createOn( "Trunc", 2 );
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final JoinResponse c = join( "carol" );
        final JoinResponse d = join( "dave" );
        type( c, "carol", "c" );
        type( a, "alice", "a" );
        type( b, "bob", "b" );
        type( c, "carol", "c" );
        type( a, "alice", "a" );
        type( b, "bob", "b" );
        type( a, "alice", "a" );   // version 7: after the cadence snapshot at 6
        editMeta( d, "dave", MetaEdit.set( "summary", "s" ) );
        final List< String > before = session.view().contributorKeys();
        assertEquals( List.of( "carol", "alice", "bob", "dave" ), before );
        for( final JoinResponse j : List.of( a, b, c, d ) ) {   // everyone confirms the head, so the log may go
            final String who = j == a ? "alice" : j == b ? "bob" : j == c ? "carol" : "dave";
            assertEquals( 200, session.push( j.participantId(), who, session.view().head(), List.of() ).status() );
        }
        assertEquals( 7, session.truncateLog() );
        assertTrue( store.updatesSince( session.id(), 0 ).stream().allMatch( u -> u.version() > 6 ), "truncated" );
        session = reload( 2 );
        assertEquals( before, session.view().contributorKeys(), "equal set and order" );
    }

    @Test
    void joinCarriesContributorDisplayNames() {
        final JoinResponse a = join( "alice" );
        final JoinResponse anon = session.join( JoinRequest.of( "anon@example.com", "anon", "Named At Join" ) );
        type( a, "alice", "A" );
        type( anon, "anon@example.com", "N" );
        final JoinResponse c = join( "carol" );
        assertEquals( List.of( "Alice A", "Unknown contributor" ), c.contributors(),
            "names from the identity only: never the key, the join's name or Editor N" );
    }

    @Test
    void draftStateIsEmittedOnlyWhenTheSetOrUnpublishedChanges() {
        final JoinResponse a = join( "alice" );
        final JoinResponse b = join( "bob" );
        final JoinResponse c = join( "carol" );
        for( int i = 0; i < 10; i++ ) {
            type( a, "alice", "a" );
        }
        List< CollabEvent > states = ofType( events( c, "carol" ), "draft-state" );
        assertEquals( 1, states.size(), states.toString() );
        assertEquals( true, states.get( 0 ).fields().get( "unpublished" ) );
        assertEquals( List.of( "Alice A" ), states.get( 0 ).fields().get( "contributors" ) );
        assertEquals( T0.toString(), states.get( 0 ).fields().get( "lastEditAt" ) );
        type( b, "bob", "b" );
        type( b, "bob", "b" );
        states = ofType( events( c, "carol" ), "draft-state" );
        assertEquals( 2, states.size(), states.toString() );
        assertEquals( List.of( "Alice A", "Bob B" ), states.get( 1 ).fields().get( "contributors" ) );
        assertEquals( 200, publish( a, "alice" ).status() );
        final PullResponse after = events( c, "carol" );
        final List< String > types = after.events().stream().map( CollabEvent::type ).toList();
        assertEquals( List.of( "published", "draft-state" ), types.subList( types.size() - 2, types.size() ) );
        final CollabEvent emptied = after.events().get( after.events().size() - 1 );
        assertEquals( List.of(), emptied.fields().get( "contributors" ) );
        assertEquals( false, emptied.fields().get( "unpublished" ) );
    }

    @Test
    void draftSummaryIsIdentityCheckedAndCarriesBaseAndHead() {
        final JoinResponse a = join( "alice" );
        join( "bob" );
        type( a, "alice", "X" );
        editMeta( a, "alice", MetaEdit.set( "title", "New title" ) );
        final DraftSummary s = session.draftSummary( a.participantId(), "alice" );
        assertEquals( 200, s.status() );
        assertNull( s.reason() );
        assertEquals( 1, s.basePageVersion() );
        assertEquals( session.view().head(), s.version() );
        assertTrue( s.unpublished() );
        assertEquals( List.of( "Alice A" ), s.contributors() );
        assertEquals( T0, s.lastEditAt() );
        assertEquals( BODY, s.baseBody() );
        assertEquals( BODY + "X", s.doc() );
        assertEquals( Map.of( "title", "T", "canonical_id", CID ), s.baseMeta() );
        assertEquals( Map.of( "title", "New title", "canonical_id", CID ), s.meta() );

        final DraftSummary notYours = session.draftSummary( a.participantId(), "bob" );
        assertEquals( 403, notYours.status() );
        assertEquals( CollabSession.NOT_YOURS, notYours.reason() );
        assertEquals( List.of(), notYours.contributors() );
        assertEquals( 409, session.draftSummary( "nobody", "alice" ).status() );
        assertEquals( CollabSession.REJOIN, session.draftSummary( "nobody", "alice" ).reason() );
        session.evict( a.participantId(), CollabSession.LOST_EDIT );
        final DraftSummary evicted = session.draftSummary( a.participantId(), "alice" );
        assertEquals( 403, evicted.status() );
        assertEquals( CollabSession.EVICTED, evicted.reason() );
        assertEquals( CollabSession.LOST_EDIT, evicted.detail() );
    }

    @Test
    void aRejoinKeepsItsEditorNumber() {
        final JoinResponse first = session.join( JoinRequest.of( "anon1@example.com", "s1", null ) );
        final JoinResponse second = session.join( JoinRequest.of( "anon2@example.com", "s2", null ) );
        assertEquals( "Editor 1", first.participants().get( 0 ).displayName() );
        assertEquals( "Editor 2", second.participants().get( 1 ).displayName() );
        assertEquals( 200, session.leave( first.participantId(), "anon1@example.com" ).status() );
        final JoinResponse again = session.join( JoinRequest.of( "anon1@example.com", "s1", null ) );
        final String mine = again.participants().stream().filter( p -> p.participantId().equals( again.participantId() ) )
            .findFirst().orElseThrow().displayName();
        assertEquals( "Editor 1", mine, "the label does not change on rejoin" );
        final JoinResponse third = session.join( JoinRequest.of( "anon3@example.com", "s3", null ) );
        final String theirs = third.participants().stream().filter( p -> p.participantId().equals( third.participantId() ) )
            .findFirst().orElseThrow().displayName();
        assertEquals( "Editor 4", theirs, "a new author still gets the next number" );
    }

    /** Captures WARN and above from one class's logger, raising its level to WARN for the duration. */
    static final class LogCapture implements AutoCloseable {
        final List< String > lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final org.apache.logging.log4j.core.Logger logger;
        private final org.apache.logging.log4j.Level previous;
        private final org.apache.logging.log4j.core.appender.AbstractAppender appender;

        LogCapture( final Class< ? > c ) {
            logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger( c );
            previous = logger.getLevel();
            appender = new org.apache.logging.log4j.core.appender.AbstractAppender( "capture-" + c.getSimpleName(), null,
                    null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY ) {
                @Override
                public void append( final org.apache.logging.log4j.core.LogEvent e ) {
                    if( e.getLevel().isMoreSpecificThan( org.apache.logging.log4j.Level.WARN ) ) {
                        lines.add( e.getMessage().getFormattedMessage() );
                    }
                }
            };
            appender.start();
            logger.addAppender( appender );
            org.apache.logging.log4j.core.config.Configurator.setLevel( c.getName(), org.apache.logging.log4j.Level.WARN );
        }

        @Override
        public void close() {
            org.apache.logging.log4j.core.config.Configurator.setLevel( logger.getName(), previous );
            logger.removeAppender( appender );
            appender.stop();
        }
    }
}

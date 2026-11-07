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

import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.collab.CollabSessionRegistry.Joined;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 2 Task 6, the Task 5 re-review's adapter follow-ups 3 and 4: a publish whose save landed but whose text could
 * not be read back ({@link PublishReadBackException}) is the publisher's own write. It finishes (200), also when a save
 * filter rewrote the body (the hash no longer matches S_V, the author does), and the rewrite is adopted as the new
 * base the way {@code finishPublish} adopts it, never merged as an outside write: that would merge the publisher's
 * edits onto themselves and duplicate text.
 */
class CollabSessionReadBackTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";

    private static final class Permissions implements CollabPermissions {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean signedIn( final Object participantSession ) {
            return true;
        }
    }

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private CollabSessionRegistry registry;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        registry = new CollabSessionRegistry( store, writer, new Permissions(), key -> null,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            new CollabConfig( true, 20, 65536, 25, 600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
    }

    private Joined join( final String author ) {
        return assertInstanceOf( Joined.class, registry.join( PAGE,
            new JoinRequest( author, null, author, author.toUpperCase(), null, null ) ) );
    }

    private static PushResponse type( final Joined j, final String author, final String text ) {
        final CollabSession.View v = j.session().view();
        return j.session().push( j.response().participantId(), author, v.head(),
            List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, text ) ) ) );
    }

    private static PublishResponse publish( final Joined j, final String author ) {
        return j.session().publish( j.response().participantId(), author, j.session().view().head(), "" );
    }

    private static PublishReadBackException unread( final int version ) {
        return new PublishReadBackException( "test: page '" + PAGE + "' version " + version
            + " was saved but could not be read back", version, new ProviderException( "test: disk hiccup" ) );
    }

    private long occurrences( final String haystack, final String needle ) {
        long n = 0;
        for( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + 1 ) ) {
            n++;
        }
        return n;
    }

    @Test
    void aReadBackFailureAfterAFilterRewriteFinishesWithTheRewriteAndDuplicatesNothing() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        writer.rewriteOnSave( b -> b + "signed\n" );   // a save filter: the stored hash is no longer S_V's
        writer.persistThenFail( unread( 2 ) );

        final PublishResponse r = publish( a, "alice" );

        assertEquals( 200, r.status(), "the save landed: the publisher's own write, finished (never 422)" );
        assertEquals( 2, r.pageVersion() );
        final String stored = CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() );
        assertEquals( "mine hello\nworld\nsigned\n", stored );
        final CollabSession.View v = a.session().view();
        assertEquals( stored, v.doc(), "the filter's rewrite adopted once, the typing once" );
        assertEquals( 1, occurrences( v.doc(), "mine " ) );
        assertEquals( stored, v.baseBody(), "the saved body is the new base" );
        assertEquals( 2, v.basePageVersion() );
        assertTrue( v.local().empty(), "nothing left unpublished" );
        assertEquals( 1, store.updatesSince( a.response().sessionId(), 0 ).stream()
            .filter( u -> StoredUpdate.SERVER.equals( u.kind() ) ).count(), "the rewrite as one server update" );
    }

    @Test
    void aReadBackFailureWithoutARewriteFinishes() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        writer.persistThenFail( unread( 2 ) );

        final PublishResponse r = publish( a, "alice" );

        assertEquals( 200, r.status() );
        assertEquals( "mine hello\nworld\n", a.session().view().doc() );
        assertEquals( "mine hello\nworld\n", CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() ) );
        assertEquals( 2, a.session().view().basePageVersion() );
    }

    @Test
    void editsMadeDuringTheUnreadSaveSurviveOnTopOfTheRewrite() {
        final Joined a = join( "alice" );
        final Joined b = join( "bob" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        writer.rewriteOnSave( s -> s + "signed\n" );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        writer.persistThenFail( unread( 2 ) );
        final java.util.concurrent.CompletableFuture< PublishResponse > first =
            java.util.concurrent.CompletableFuture.supplyAsync( () -> publish( a, "alice" ) );
        pause.awaitEntered( java.time.Duration.ofSeconds( 10 ) );
        assertEquals( 200, type( b, "bob", "more " ).status() );   // typed during the save
        pause.release();

        final PublishResponse r = first.join();

        assertEquals( 200, r.status() );
        final CollabSession.View v = a.session().view();
        assertEquals( "more mine hello\nworld\nsigned\n", v.doc() );
        assertEquals( 1, occurrences( v.doc(), "mine " ) );
        assertEquals( 1, occurrences( v.doc(), "signed" ) );
        assertEquals( "mine hello\nworld\nsigned\n", v.baseBody() );
        assertEquals( v.doc(), v.local().apply( v.baseBody() ), "only bob's typing is unpublished" );
    }

    /**
     * The Phase 2 residual (ruling D): the read-back fails AND so does the read of the version afterwards, so the publish
     * is left unfinished. The version the save reported is stored on the marker at once, so the retry claims it by
     * (saved version, author) even though the filter's rewrite broke S_V's content hash: finished, never merged as an
     * outside write onto the publisher's own edits.
     */
    @Test
    void anUnreadSaveWhoseVersionCannotBeReadEitherIsClaimedByItsSavedVersionOnRetry() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        writer.rewriteOnSave( b -> b + "signed\n" );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        writer.persistThenFail( unread( 2 ) );
        final java.util.concurrent.CompletableFuture< PublishResponse > first =
            java.util.concurrent.CompletableFuture.supplyAsync( () -> publish( a, "alice" ) );
        pause.awaitEntered( java.time.Duration.ofSeconds( 10 ) );
        writer.failReads( true );   // the page store stops answering reads while the save runs
        pause.release();

        assertEquals( 503, first.join().status(), "unfinished: owed a retry" );
        assertEquals( 2, store.session( a.response().sessionId() ).orElseThrow().pendingPublishSavedVersion() );
        writer.failReads( false );
        a.session().settle();

        assertNull( store.session( a.response().sessionId() ).orElseThrow().pendingPublishVersion() );
        final String stored = CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() );
        final CollabSession.View v = a.session().view();
        assertEquals( stored, v.doc(), "the rewrite adopted once" );
        assertEquals( 1, occurrences( v.doc(), "mine " ), "the publisher's edit is not applied twice" );
        assertEquals( 2, v.basePageVersion() );
    }

    /** As above, but the process dies before the retry: the reload's pending-publish rule claims by the stored version. */
    @Test
    void anUnreadSaveIsClaimedByItsStoredSavedVersionAfterARestart() {
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        writer.rewriteOnSave( b -> b + "signed\n" );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        writer.persistThenFail( unread( 2 ) );
        final java.util.concurrent.CompletableFuture< PublishResponse > first =
            java.util.concurrent.CompletableFuture.supplyAsync( () -> publish( a, "alice" ) );
        pause.awaitEntered( java.time.Duration.ofSeconds( 10 ) );
        writer.failReads( true );
        pause.release();
        assertEquals( 503, first.join().status() );
        writer.failReads( false );

        final CollabSessionRegistry restarted = new CollabSessionRegistry( store, writer, new Permissions(), key -> null,
            new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) ),
            new CollabConfig( true, 20, 65536, 25, 600, 30, 100, 500, 10, 0, 1000 ), 1 << 20 );
        final Joined again = assertInstanceOf( Joined.class, restarted.join( PAGE,
            new JoinRequest( "alice", null, "alice", "ALICE", null, null ) ) );
        again.session().settle();

        assertNull( store.session( again.response().sessionId() ).orElseThrow().pendingPublishVersion() );
        final CollabSession.View v = again.session().view();
        assertEquals( CollabText.toLf( writer.latest( PAGE ).orElseThrow().body() ), v.doc() );
        assertEquals( 1, occurrences( v.doc(), "mine " ) );
        assertEquals( 2, v.basePageVersion() );
    }

    @Test
    void anotherAuthorsVersionIsStillAnOutsideWriteEvenWhenTheSaveSaysItLanded() {
        // the adapter names the version it saved; a B+1 by someone else is not the publisher's own, so it is merged as an
        // outside write (E20) rather than adopted
        final Joined a = join( "alice" );
        assertEquals( 200, type( a, "alice", "mine " ).status() );
        final ScriptedPageWriter.PauseHandle pause = writer.pauseNextPublish();
        writer.failNextPublish( unread( 2 ) );
        final java.util.concurrent.CompletableFuture< PublishResponse > first =
            java.util.concurrent.CompletableFuture.supplyAsync( () -> publish( a, "alice" ) );
        pause.awaitEntered( java.time.Duration.ofSeconds( 10 ) );
        writer.externalSave( PAGE, BODY + "theirs\n", Map.of( "title", "T" ), "bob" );   // version 2
        pause.release();

        final PublishResponse r = first.join();

        assertEquals( 409, r.status() );
        assertEquals( CollabSession.MERGED_RETRY, r.reason() );
        assertEquals( "mine hello\nworld\ntheirs\n", a.session().view().doc() );
    }
}

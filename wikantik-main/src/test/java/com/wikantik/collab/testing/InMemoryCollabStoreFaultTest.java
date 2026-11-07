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
package com.wikantik.collab.testing;

import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-memory store's own extras: fault injection (F8) and deep copy. */
class InMemoryCollabStoreFaultTest {

    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    private static SessionRecord session( final String page ) {
        return new SessionRecord( UUID.randomUUID(), page, SessionRecord.ACTIVE, 1, "b", Map.of(), "h", 0, 0L, false, null,
            null, null, "alice", T0, T0 );
    }

    private static StoredUpdate upd( final int v ) {
        return new StoredUpdate( v, StoredUpdate.TEXT, "c", "alice", com.wikantik.collab.text.ChangeSet.empty( 0 ) );
    }

    @Test
    void failOnInjectsBeforeMutating() {
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final SessionRecord s = session( "A" );
        store.createSession( s );
        store.failOn( InMemoryCollabStore.Op.APPEND_UPDATES, 2 );
        store.appendUpdates( s.id(), List.of( upd( 1 ) ) );
        final IllegalStateException e = assertThrows( IllegalStateException.class,
            () -> store.appendUpdates( s.id(), List.of( upd( 2 ) ) ) );
        assertTrue( e.getMessage().startsWith( "injected failure" ) );
        assertEquals( 1, store.session( s.id() ).orElseThrow().headVersion() );
        assertEquals( 1, store.updatesSince( s.id(), 0 ).size() );
        // one-shot: the next call succeeds
        store.appendUpdates( s.id(), List.of( upd( 2 ) ) );
        assertEquals( 2, store.session( s.id() ).orElseThrow().headVersion() );
    }

    @Test
    void everyOpHasAFaultPointThatFiresBeforeMutation() {
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final SessionRecord s = session( "A" );
        store.failOn( InMemoryCollabStore.Op.CREATE_SESSION, 1 );
        assertThrows( IllegalStateException.class, () -> store.createSession( s ) );
        assertTrue( store.session( s.id() ).isEmpty() );
        store.createSession( s );

        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertThrows( IllegalStateException.class, () -> store.updateSession( s.withBase( 9, "x", Map.of(), "h9" ) ) );
        assertEquals( 1, store.session( s.id() ).orElseThrow().basePageVersion() );

        store.failOn( InMemoryCollabStore.Op.APPEND_META, 1 );
        assertThrows( IllegalStateException.class,
            () -> store.appendMetaOps( s.id(), List.of( new StoredMetaOp( 1, "a", "f", "v" ) ) ) );
        assertEquals( 0L, store.session( s.id() ).orElseThrow().metaSeq() );

        store.failOn( InMemoryCollabStore.Op.WRITE_SNAPSHOT, 1 );
        assertThrows( IllegalStateException.class, () -> store.writeSnapshot( s.id(),
            new Snapshot( 0, 0L, 1, "b", Map.of(), com.wikantik.collab.text.ChangeSet.empty( 0 ) ) ) );
        assertTrue( store.latestSnapshot( s.id() ).isEmpty() );

        store.appendUpdates( s.id(), List.of( upd( 1 ) ) );
        store.writeSnapshot( s.id(), new Snapshot( 1, 0L, 1, "b", Map.of(), com.wikantik.collab.text.ChangeSet.empty( 0 ) ) );
        store.failOn( InMemoryCollabStore.Op.TRUNCATE, 1 );
        assertThrows( IllegalStateException.class, () -> store.truncateUpdatesBelow( s.id(), 5 ) );
        assertEquals( 1, store.updatesSince( s.id(), 0 ).size() );

        store.failOn( InMemoryCollabStore.Op.COMMIT_BASE_CHANGE, 1 );
        assertThrows( IllegalStateException.class, () -> store.commitBaseChange( s.id(),
            List.of( new StoredUpdate( 2, StoredUpdate.SERVER, "server", null, com.wikantik.collab.text.ChangeSet.empty( 0 ) ) ),
            new Snapshot( 2, 0L, 9, "b9", Map.of(), com.wikantik.collab.text.ChangeSet.empty( 0 ) ),
            s.withBase( 9, "b9", Map.of(), "h9" ) ) );
        assertEquals( 1, store.session( s.id() ).orElseThrow().headVersion() );
        assertEquals( 1, store.session( s.id() ).orElseThrow().basePageVersion() );
        assertEquals( 1, store.latestSnapshot( s.id() ).orElseThrow().version() );
        assertEquals( "b", store.latestSnapshot( s.id() ).orElseThrow().body() );

        store.failOn( InMemoryCollabStore.Op.APPEND_WITH_SNAPSHOT, 1 );
        assertThrows( IllegalStateException.class, () -> store.appendWithSnapshot( s.id(), List.of( upd( 2 ) ), List.of(),
            new Snapshot( 2, 0L, 1, "b2", Map.of(), com.wikantik.collab.text.ChangeSet.empty( 0 ) ) ) );
        assertEquals( 1, store.session( s.id() ).orElseThrow().headVersion() );
        assertEquals( 1, store.latestSnapshot( s.id() ).orElseThrow().version() );

        store.failOn( InMemoryCollabStore.Op.DELETE, 1 );
        assertThrows( IllegalStateException.class, () -> store.deleteSession( s.id() ) );
        assertTrue( store.session( s.id() ).isPresent() );

        store.recordContributors( "cid", 1, Set.of( "alice" ), true );
        store.failOn( InMemoryCollabStore.Op.RECORD_CONTRIBUTORS, 1 );
        assertThrows( IllegalStateException.class, () -> store.recordContributors( "cid", 1, Set.of( "bob" ), true ) );
        assertEquals( Map.of( 1, List.of( "alice" ) ), store.contributorsByVersion( "cid" ) );
    }

    @Test
    void copyCarriesTheContributorLedgerIndependently() {
        final InMemoryCollabStore store = new InMemoryCollabStore();
        store.recordContributors( "cid", 1, Set.of( "alice" ), true );
        final InMemoryCollabStore copy = store.copy();
        copy.recordContributors( "cid", 2, Set.of( "bob" ), false );
        store.recordContributors( "cid", 1, Set.of( "carol" ), true );
        assertEquals( Map.of( 1, List.of( "alice" ), 2, List.of( "bob" ) ), copy.contributorsByVersion( "cid" ) );
        assertEquals( Map.of( 1, List.of( "carol" ) ), store.contributorsByVersion( "cid" ) );
    }

    @Test
    void copyIsIndependentOfTheOriginal() {
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final SessionRecord s = session( "A" );
        store.createSession( s );
        store.appendUpdates( s.id(), List.of( upd( 1 ) ) );
        final InMemoryCollabStore copy = store.copy();
        copy.appendUpdates( s.id(), List.of( upd( 2 ) ) );
        store.deleteSession( s.id() );
        assertEquals( 2, copy.session( s.id() ).orElseThrow().headVersion() );
        assertEquals( 2, copy.updatesSince( s.id(), 0 ).size() );
        assertTrue( store.session( s.id() ).isEmpty() );
    }
}

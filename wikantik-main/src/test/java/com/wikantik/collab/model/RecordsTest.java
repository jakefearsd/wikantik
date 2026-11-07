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
package com.wikantik.collab.model;

import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecordsTest {

    private static final ChangeSet CS = ChangeSet.empty( 0 );

    private static SessionRecord session( final Integer pv, final Integer pbv ) {
        final Instant t = Instant.EPOCH;
        return session( pv, pbv, pv == null ? null : "alice" );
    }

    private static SessionRecord session( final Integer pv, final Integer pbv, final String author ) {
        final Instant t = Instant.EPOCH;
        return new SessionRecord( UUID.randomUUID(), "P", "active", 1, "b", Map.of(), "h", 0, 0, false, pv, pbv, author, null, null,
            "u", t, t );
    }

    @Test
    void serverUpdateMayHaveNullAuthor() {
        assertDoesNotThrow( () -> new StoredUpdate( 1, "server", "c", null, CS ) );
    }

    @Test
    void textUpdateRequiresAuthor() {
        assertThrows( NullPointerException.class, () -> new StoredUpdate( 1, "text", "c", null, CS ) );
        assertThrows( IllegalArgumentException.class, () -> new StoredUpdate( 1, "text", "c", " ", CS ) );
    }

    @Test
    void updateRejectsBadKindAndNegativeVersion() {
        assertThrows( IllegalArgumentException.class, () -> new StoredUpdate( 1, "other", "c", "a", CS ) );
        assertThrows( IllegalArgumentException.class, () -> new StoredUpdate( -1, "text", "c", "a", CS ) );
    }

    @Test
    void metaOpAllowsNullAuthorAndValue() {
        assertDoesNotThrow( () -> new StoredMetaOp( 1, null, "tags", null ) );
        assertThrows( IllegalArgumentException.class, () -> new StoredMetaOp( 1, "a", " ", "v" ) );
    }

    @Test
    void publishRequestRequiresAuthor() {
        assertThrows( NullPointerException.class, () -> new PublishRequest( "P", "b", Map.of(), 1, null, "n" ) );
        assertDoesNotThrow( () -> new PublishRequest( "P", "b", Map.of(), 1, "system", "n" ) );
    }

    @Test
    void pendingVersionsAreBothNullOrBothSet() {
        assertDoesNotThrow( () -> session( null, null ) );
        assertDoesNotThrow( () -> session( 3, 2 ) );
        assertThrows( IllegalArgumentException.class, () -> session( 3, null ) );
        assertThrows( IllegalArgumentException.class, () -> session( null, 2 ) );
        assertThrows( IllegalArgumentException.class, () -> session( -1, 2 ) );
    }

    @Test
    void metaIsCopiedDefensivelyAndMayHoldNulls() {
        final Map< String, Object > m = new HashMap<>();
        m.put( "k", null );
        final PageState ps = new PageState( 1, "b", m, "h", null );
        m.put( "x", 1 );
        assertEquals( 1, ps.meta().size() );
        assertThrows( UnsupportedOperationException.class, () -> ps.meta().put( "y", 2 ) );
    }

    @Test
    void pendingPublishTripleIsAllNullOrAllSet() {
        assertDoesNotThrow( () -> session( 3, 2, "alice" ) );
        assertThrows( IllegalArgumentException.class, () -> session( 3, 2, null ) );
        assertThrows( IllegalArgumentException.class, () -> session( null, null, "alice" ) );
        assertThrows( IllegalArgumentException.class, () -> session( 3, 2, " " ) );
    }

    @Test
    void theSavedVersionNeedsAPendingPublishAndTheCanonicalIdNeedsTheVersion() {
        final SessionRecord marked = session( 3, 2, "alice" );
        final SessionRecord saved = marked.withPublishSaved( 3, "cid" );
        assertEquals( 3, saved.pendingPublishSavedVersion() );
        assertEquals( "cid", saved.pendingPublishCanonicalId() );
        assertThrows( IllegalArgumentException.class, () -> session( null, null ).withPublishSaved( 3, "cid" ) );
        assertThrows( IllegalArgumentException.class, () -> marked.withPublishSaved( -1, null ) );
        final Instant t = Instant.EPOCH;
        assertThrows( IllegalArgumentException.class, () -> new SessionRecord( UUID.randomUUID(), "P", "active", 1, "b",
            Map.of(), "h", 0, 0, false, 3, 2, "alice", null, "cid", "u", t, t ) );
        assertEquals( null, saved.withPending( 4, 3, "bob" ).pendingPublishSavedVersion(), "a new marker starts unsaved" );
        assertEquals( null, saved.withPending( null, null, null ).pendingPublishCanonicalId() );
        assertEquals( 3, saved.withHead( 9 ).withBase( 2, "x", Map.of(), "h2" ).withStatus( "frozen" ).withPageName( "Q" )
            .withMetaSeq( 4 ).pendingPublishSavedVersion(), "every other with* keeps it" );
    }

    @Test
    void withPendingSetsAndClearsTheTriple() {
        final SessionRecord s = session( null, null ).withPending( 5, 4, "bob" );
        assertEquals( 5, s.pendingPublishVersion() );
        assertEquals( 4, s.pendingPublishBaseVersion() );
        assertEquals( "bob", s.pendingPublishAuthor() );
        final SessionRecord cleared = s.withPending( null, null, null );
        assertEquals( null, cleared.pendingPublishAuthor() );
        assertThrows( IllegalArgumentException.class, () -> s.withPending( 5, 4, null ) );
    }

    @Test
    void lastEditAtMayBeNullUntilTheFirstEdit() {
        final Instant t = Instant.EPOCH;
        final SessionRecord s = new SessionRecord( UUID.randomUUID(), "P", "active", 1, "b", Map.of(), "h", 0, 0, false,
            null, null, null, null, null, "u", t, null );
        assertEquals( null, s.lastEditAt() );
        assertEquals( null, s.withHead( 2 ).lastEditAt() );
    }

    @Test
    void baseContentHashIsRequired() {
        final Instant t = Instant.EPOCH;
        assertThrows( NullPointerException.class, () -> new SessionRecord( UUID.randomUUID(), "P", "active", 1, "b",
            Map.of(), null, 0, 0, false, null, null, null, null, null, "u", t, t ) );
    }

    @Test
    void snapshotBasePageVersionMustNotBeNegative() {
        assertDoesNotThrow( () -> new Snapshot( 0, 0, 0, "b", Map.of(), CS ) );
        assertThrows( IllegalArgumentException.class, () -> new Snapshot( 0, 0, -1, "b", Map.of(), CS ) );
    }

    @Test
    void pageStateAuthorMayBeNullForAnUnknownAuthor() {
        assertEquals( null, new PageState( 1, "b", Map.of(), "h", null ).author() );
        assertEquals( "alice", new PageState( 1, "b", Map.of(), "h", "alice" ).author() );
    }

    @Test
    void storedUpdateKindConstants() {
        assertEquals( "text", StoredUpdate.TEXT );
        assertEquals( "server", StoredUpdate.SERVER );
        assertDoesNotThrow( () -> new StoredUpdate( 1, StoredUpdate.SERVER, "c", null, CS ) );
    }
}

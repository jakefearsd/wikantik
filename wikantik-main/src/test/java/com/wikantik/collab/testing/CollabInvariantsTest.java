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
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollabInvariantsTest {

    private static final String PAGE = "Page";
    private static final String BASE = "hello\n";

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private UUID id;

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BASE, Map.of() );
        id = UUID.randomUUID();
        final Instant t = Instant.parse( "2026-01-01T00:00:00Z" );
        store.createSession( new SessionRecord( id, PAGE, SessionRecord.ACTIVE, 1, BASE, Map.of(), 0, 0, false, null, null,
            "stub", t, t ) );
        store.appendUpdates( id, List.of( new StoredUpdate( 1, "text", "c1", "alice", ChangeSet.replace( 6, 0, 0, "X" ) ) ) );
    }

    /** A consistent view: base "hello\n", one update inserting X, ring holding it. */
    private InvariantView valid() {
        final ChangeSet cs = ChangeSet.replace( 6, 0, 0, "X" );
        return new InvariantView( id, "Xhello\n", 1, BASE, cs, 1,
            store.updatesSince( id, 0 ), false, 1000, store, writer, PAGE, false, false );
    }

    private static InvariantView with( final InvariantView v, final UnaryOperator< Builder > f ) {
        return f.apply( new Builder( v ) ).build();
    }

    private static final class Builder {
        UUID sessionId; String doc; int head; String baseBody; ChangeSet local; int basePageVersion;
        List< StoredUpdate > ring; boolean oversize; long maxPageBytes; InvariantView v;
        boolean externalChangePending; boolean publishPending;

        Builder( final InvariantView v ) {
            this.v = v; sessionId = v.sessionId(); doc = v.doc(); head = v.head(); baseBody = v.baseBody(); local = v.local();
            basePageVersion = v.basePageVersion(); ring = v.ring(); oversize = v.oversize(); maxPageBytes = v.maxPageBytes();
            externalChangePending = v.externalChangePending(); publishPending = v.publishPending();
        }

        InvariantView build() {
            return new InvariantView( sessionId, doc, head, baseBody, local, basePageVersion, ring, oversize, maxPageBytes,
                v.store(), v.writer(), v.pageName(), externalChangePending, publishPending );
        }
    }

    private static void failsNaming( final InvariantView v, final boolean replay, final int n ) {
        final AssertionError e = assertThrows( AssertionError.class, () -> CollabInvariants.check( v, replay ) );
        assertTrue( e.getMessage().contains( "invariant " + n ), e.getMessage() );
    }

    @Test
    void validViewPasses() {
        assertDoesNotThrow( () -> CollabInvariants.check( valid() ) );
        assertDoesNotThrow( () -> CollabInvariants.check( valid(), false ) );
    }

    @Test
    void inv1_localDoesNotReproduceDoc() {
        failsNaming( with( valid(), b -> { b.doc = "Yhello\n"; return b; } ), false, 1 );
    }

    @Test
    void inv1_localLengthMismatchIsAViolationNotAnException() {
        failsNaming( with( valid(), b -> { b.local = ChangeSet.empty( 3 ); return b; } ), false, 1 );
    }

    @Test
    void inv2_headDisagreesWithStore() {
        failsNaming( with( valid(), b -> { b.head = 2; return b; } ), false, 2 );
    }

    @Test
    void inv2_ringNotASuffixOfTheStoreLog() {
        final StoredUpdate forged = new StoredUpdate( 1, "text", "c1", "alice", ChangeSet.replace( 6, 0, 0, "Z" ) );
        failsNaming( with( valid(), b -> { b.ring = List.of( forged ); return b; } ), false, 2 );
    }

    @Test
    void inv3_replayDisagreesWithDocOnlyWhenChecked() {
        // snapshot says the body at version 0 was different, so replay reproduces a different doc
        store.writeSnapshot( id, new com.wikantik.collab.model.Snapshot( 0, 0, "jello\n", Map.of(), ChangeSet.empty( 6 ) ) );
        assertDoesNotThrow( () -> CollabInvariants.check( valid(), false ) );
        failsNaming( valid(), true, 3 );
    }

    @Test
    void inv3_passesWithAMatchingSnapshot() {
        store.writeSnapshot( id, new com.wikantik.collab.model.Snapshot( 0, 0, BASE, Map.of(), ChangeSet.empty( 6 ) ) );
        assertDoesNotThrow( () -> CollabInvariants.check( valid(), true ) );
    }

    @Test
    void inv4_basePageVersionBehindWriter() {
        failsNaming( with( valid(), b -> { b.basePageVersion = 0; return b; } ), false, 4 );
    }

    @Test
    void inv4_skippedWhilePublishOrExternalChangePending() {
        assertDoesNotThrow( () -> CollabInvariants.check( with( valid(), b -> { b.basePageVersion = 0; b.publishPending = true; return b; } ) ) );
        assertDoesNotThrow( () -> CollabInvariants.check( with( valid(), b -> { b.basePageVersion = 0; b.externalChangePending = true; return b; } ) ) );
    }

    @Test
    void inv5_docOverLimitWhenNotOversize() {
        failsNaming( with( valid(), b -> { b.maxPageBytes = 3; return b; } ), false, 5 );
    }

    @Test
    void inv5_oversizeStateIsAllowedToExceedLimit() {
        assertDoesNotThrow( () -> CollabInvariants.check( with( valid(), b -> { b.maxPageBytes = 3; b.oversize = true; return b; } ) ) );
    }

    @Test
    void inv6_loneSurrogateInDoc() {
        final ChangeSet cs = ChangeSet.replace( 6, 0, 0, "\uD83D" );
        final InvariantView v = with( valid(), b -> { b.doc = "\uD83Dhello\n"; b.local = cs; return b; } );
        failsNaming( v, false, 6 );
    }
}

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

import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimClientTest {

    private static SimClient client( final String name, final String clientId, final int version, final String doc ) {
        return new SimClient( name, new Joined( "p-" + name, clientId, version, doc ) );
    }

    private static ChangeSet insert( final String doc, final int at, final String text ) {
        return ChangeSet.replace( doc.length(), at, at, text );
    }

    @Test
    void ownUpdateIsConfirmedByClientId() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        final ChangeSet cs = insert( "abc", 3, "X" );
        a.localEdit( cs );
        assertEquals( "abcX", a.doc() );
        assertEquals( List.of( new Update( "ca", cs ) ), a.sendable() );

        a.receive( List.of( new Update( "ca", cs ) ), 0 );

        assertEquals( 1, a.version() );
        assertTrue( a.sendable().isEmpty() );
        assertEquals( "abcX", a.doc() );
    }

    @Test
    void remoteUpdateIsRebasedOverUnconfirmedLocalEdit() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 0, "L" ) );
        a.receive( List.of( new Update( "cb", insert( "abc", 3, "R" ) ) ), 0 );

        assertEquals( "LabcR", a.doc() );
        assertEquals( 1, a.version() );
        assertEquals( 1, a.sendable().size() );
        // The unconfirmed update now applies on top of the remote one.
        assertEquals( "LabcR", a.sendable().get( 0 ).changes().apply( "abcR" ) );
    }

    @Test
    void concurrentInsertsAtSamePositionConvergeInSameOrder() {   // E4
        final String base = "ab";
        final SimClient a = client( "a", "ca", 0, base );
        final SimClient b = client( "b", "cb", 0, base );
        final ChangeSet ca = insert( base, 1, "A" );
        final ChangeSet cb = insert( base, 1, "B" );
        a.localEdit( ca );
        b.localEdit( cb );
        // The authority accepts a first, then b rebased over a (the same map the library's rebaseUpdates does).
        final ChangeSet cbRebased = cb.map( ca );
        final List< Update > accepted = List.of( new Update( "ca", ca ), new Update( "cb", cbRebased ) );

        a.receive( accepted, 0 );
        b.receive( accepted, 0 );

        assertEquals( a.doc(), b.doc() );
        assertEquals( "aABb", a.doc() );
        assertTrue( a.sendable().isEmpty() );
        assertTrue( b.sendable().isEmpty() );
    }

    @Test
    void lostAcknowledgementIsConfirmedByLaterPullWithoutDoubleApply() {   // E9
        final SimClient a = client( "a", "ca", 0, "abc" );
        final ChangeSet cs = insert( "abc", 0, "X" );
        a.localEdit( cs );
        a.onPushResult( new PushResult( 503, 0, null ) );          // the response was lost / failed; push was applied server-side
        assertEquals( 1, a.sendable().size() );

        a.receive( List.of( new Update( "ca", cs ) ), 0 );   // the pull brings the client's own update back

        assertEquals( "Xabc", a.doc() );
        assertEquals( 1, a.version() );
        assertTrue( a.sendable().isEmpty() );
    }

    @Test
    void staleOrDuplicatedBatchIsIgnored() {   // E11
        final SimClient a = client( "a", "ca", 0, "abc" );
        final List< Update > batch = List.of( new Update( "cb", insert( "abc", 3, "R" ) ) );
        a.receive( batch, 0 );
        assertEquals( "abcR", a.doc() );

        a.receive( batch, 0 );                                // duplicate
        assertEquals( "abcR", a.doc() );
        assertEquals( 1, a.version() );
    }

    @Test
    void partiallyOverlappingBatchAppliesOnlyTheNewTail() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        final Update u1 = new Update( "cb", insert( "abc", 3, "1" ) );
        final Update u2 = new Update( "cb", insert( "abc1", 4, "2" ) );
        a.receive( List.of( u1 ), 0 );
        a.receive( List.of( u1, u2 ), 0 );
        assertEquals( "abc12", a.doc() );
        assertEquals( 2, a.version() );
    }

    @Test
    void gapBatchIsNotAppliedAndIsTraced() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.receive( List.of( new Update( "cb", insert( "abc", 3, "R" ) ) ), 5 );
        assertEquals( "abc", a.doc() );
        assertEquals( 0, a.version() );
        assertTrue( a.trace().stream().anyMatch( t -> t.contains( "gap" ) ) );
    }

    @Test
    void resyncKeepsUnsyncedTextInRecoveryAndAdoptsFreshState() {   // A10
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 3, "MINE" ) );

        a.resync( new Joined( "p-a2", "ca2", 7, "server doc" ) );

        assertEquals( "abcMINE", a.recoveryText() );
        assertEquals( "server doc", a.doc() );
        assertEquals( 7, a.version() );
        assertTrue( a.sendable().isEmpty() );
    }

    @Test
    void resyncWithNothingUnsyncedLeavesRecoveryEmpty() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.resync( new Joined( "p-a2", "ca2", 3, "new" ) );
        assertEquals( "", a.recoveryText() );
        assertEquals( "new", a.doc() );
    }

    @Test
    void traceRecordsEventsAsJson() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 0, "X" ) );
        a.receive( List.of( new Update( "cb", insert( "abc", 3, "R" ) ) ), 0 );
        final List< String > trace = a.trace();
        assertTrue( trace.get( 1 ).contains( "\"type\":\"local\"" ), trace.get( 1 ) );
        assertTrue( trace.stream().anyMatch( t -> t.contains( "\"type\":\"receive\"" ) && t.contains( "\"fromVersion\":0" ) ) );
        assertTrue( trace.stream().anyMatch( t -> t.contains( "\"sendable\"" ) ) );
    }

    @Test
    void everySendableSnapshotCarriesTheDocumentAfterTheStep() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 0, "X" ) );
        a.receive( List.of( new Update( "cb", insert( "abc", 3, "R" ) ) ), 0 );
        final List< SimClient.TraceEntry > snaps = a.traceEntries().stream()
            .filter( t -> t.type().equals( "sendable" ) ).toList();
        assertEquals( 2, snaps.size() );
        assertEquals( "Xabc", snaps.get( 0 ).data().get( "doc" ).getAsString() );
        assertEquals( "XabcR", snaps.get( 1 ).data().get( "doc" ).getAsString() );
    }

    @Test
    void laterCleanResyncClearsStaleRecoveryText() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 3, "MINE" ) );
        a.resync( new Joined( "p-a2", "ca2", 7, "server doc" ) );
        assertEquals( "abcMINE", a.recoveryText() );
        a.resync( new Joined( "p-a3", "ca3", 8, "server doc 2" ) );
        assertEquals( "", a.recoveryText() );
    }

    @Test
    void tiePositionRebaseOrdersForeignBeforeLocal() {
        final SimClient a = client( "a", "ca", 0, "ab" );
        a.localEdit( insert( "ab", 1, "A" ) );
        a.receive( List.of( new Update( "cb", insert( "ab", 1, "B" ) ) ), 0 );
        assertEquals( "aBAb", a.doc() );
        assertEquals( "aBAb", a.sendable().get( 0 ).changes().apply( "aBb" ) );
    }

    @Test
    void batchWithOwnUpdateAfterForeignOne() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        final ChangeSet own = insert( "abc", 0, "L" );
        a.localEdit( own );
        final Update f1 = new Update( "cb", insert( "abc", 3, "x" ) );
        final Update ownAccepted = new Update( "ca", insert( "abcx", 0, "L" ) );
        final Update f2 = new Update( "cc", insert( "Labcx", 5, "y" ) );
        a.receive( List.of( f1, ownAccepted, f2 ), 0 );
        assertEquals( "Labcxy", a.doc() );
        assertEquals( 3, a.version() );
        assertTrue( a.sendable().isEmpty() );
    }

    @Test
    void traceHasJoinAndResyncCarriesNewIdentity() {
        final SimClient a = client( "a", "ca", 4, "abc" );
        assertTrue( a.trace().get( 0 ).contains( "\"type\":\"join\"" ) );
        assertTrue( a.trace().get( 0 ).contains( "\"clientId\":\"ca\"" ) );
        a.resync( new Joined( "p2", "ca2", 9, "new" ) );
        assertTrue( a.trace().stream().anyMatch( t -> t.contains( "\"type\":\"resync\"" )
            && t.contains( "\"clientId\":\"ca2\"" ) && t.contains( "\"doc\":\"new\"" ) ) );
    }

    @Test
    void rejoinRebasesUnsentEditsOntoTheFreshDocumentUnderTheNewClientId() {   // 7.2 expired participants
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 3, "X" ) );
        a.localEdit( insert( "abcX", 0, "Y" ) );
        // meanwhile the server moved on: someone inserted "R" at 0 and someone deleted "b"
        a.rejoin( new Joined( "p-new", "cn", 2, "Rac" ) );

        assertEquals( "YRacX", a.doc() );
        assertEquals( 2, a.version() );
        assertEquals( "cn", a.clientId() );
        assertEquals( "p-new", a.participantId() );
        assertEquals( "", a.recoveryText() );
        assertEquals( 2, a.sendable().size() );
        assertTrue( a.sendable().stream().allMatch( u -> "cn".equals( u.clientId() ) ) );
        assertEquals( "YRacX", a.sendable().get( 1 ).changes().apply( a.sendable().get( 0 ).changes().apply( "Rac" ) ) );
        // the replay sees a resync (re-seeding the library at the fresh state) followed by the rebased local edits
        final List< String > types = a.traceEntries().stream().map( SimClient.TraceEntry::type ).toList();
        final int at = types.lastIndexOf( "resync" );
        assertEquals( List.of( "resync", "sendable", "local", "sendable", "local", "sendable" ), types.subList( at, types.size() ) );
        assertTrue( a.trace().get( at ).contains( "\"unsynced\":0" ) && a.trace().get( at ).contains( "\"rebased\":2" ),
            a.trace().get( at ) );
    }

    @Test
    void rejoinRebasesOverTheSyncedDocumentNotTheLocalOne() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 0, "L" ) );                                  // "Labc", unconfirmed
        a.receive( List.of( new Update( "cb", insert( "abc", 3, "R" ) ) ), 0 );  // synced "abcR", doc "LabcR"
        assertEquals( "abcR", a.syncedDoc() );
        a.rejoin( new Joined( "p2", "c2", 3, "abcRST" ) );
        assertEquals( "LabcRST", a.doc() );
        assertEquals( "abcRST", a.syncedDoc() );
    }

    @Test
    void rejoinWithNothingUnconfirmedJustAdoptsTheFreshState() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.rejoin( new Joined( "p2", "c2", 4, "fresh" ) );
        assertEquals( "fresh", a.doc() );
        assertEquals( 4, a.version() );
        assertTrue( a.sendable().isEmpty() );
    }

    @Test
    void anEditDeletedByOthersRebasesToNothingAndIsDropped() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( ChangeSet.replace( 3, 1, 2, "" ) );     // delete "b"
        a.rejoin( new Joined( "p2", "c2", 1, "ac" ) );       // someone else already deleted it
        assertEquals( "ac", a.doc() );
        assertTrue( a.sendable().isEmpty() );
    }

    @Test
    void reconnectUnderTheSameClientIdKeepsTheWholeCollabState() {   // 7.2 "same clientID"
        final SimClient a = client( "a", "ca", 0, "abc" );
        a.localEdit( insert( "abc", 3, "X" ) );
        a.reconnect( new Joined( "p-new", "ca", 5, "whatever the head is" ) );
        assertEquals( "p-new", a.participantId() );
        assertEquals( "ca", a.clientId() );
        assertEquals( 0, a.version(), "it keeps pulling from its own version" );
        assertEquals( "abcX", a.doc() );
        assertEquals( 1, a.sendable().size() );
        final List< String > types = a.traceEntries().stream().map( SimClient.TraceEntry::type ).toList();
        assertEquals( "rejoin", types.get( types.size() - 2 ) );
        assertTrue( a.trace().get( types.size() - 2 ).contains( "\"participantId\":\"p-new\"" ) );
    }

    @Test
    void reconnectUnderAnotherClientIdIsRefused() {
        final SimClient a = client( "a", "ca", 0, "abc" );
        assertThrows( IllegalArgumentException.class, () -> a.reconnect( new Joined( "p2", "cb", 0, "abc" ) ) );
    }
}

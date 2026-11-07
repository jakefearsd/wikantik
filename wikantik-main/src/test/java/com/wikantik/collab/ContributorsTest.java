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

import com.wikantik.collab.model.Contribution;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-draft contributor record and its credit at the publish point (docs/MultiEdit.MD section 7.3, ruling R2). */
class ContributorsTest {

    private static final int NONE = Contribution.NONE;

    @Test
    void creditedAtIsBoundedByVersionAndMetaSeq() {
        final Contributors c = Contributors.of( Map.of() );
        c.text( "alice", 1 );
        c.text( "alice", 2 );
        c.meta( "bob", 1 );
        c.text( "carol", 3 );
        c.meta( "dave", 2 );
        assertEquals( List.of( "alice", "bob", "carol", "dave" ), c.authors() );
        assertEquals( List.of( "alice", "bob" ), List.copyOf( c.creditedAt( 2, 1 ) ) );
        assertEquals( List.of( "alice" ), List.copyOf( c.creditedAt( 2, 0 ) ) );
        assertEquals( List.of( "bob" ), List.copyOf( c.creditedAt( 0, 1 ) ) );
        assertEquals( List.of( "alice", "bob", "carol", "dave" ), List.copyOf( c.creditedAt( 3, 2 ) ) );
        assertEquals( Set.of(), c.creditedAt( 0, 0 ) );
    }

    @Test
    void afterPublishKeepsOnlyLaterContributions() {
        final Contributors c = Contributors.of( Map.of() );
        c.text( "alice", 1 );
        c.text( "alice", 4 );      // during the save
        c.text( "bob", 2 );
        c.meta( "carol", 1 );
        c.meta( "carol", 3 );      // during the save
        c.meta( "dave", 2 );
        c.text( "dave", 5 );       // during the save
        final Map< String, Contribution > before = c.snapshot();
        final Contributors after = c.afterPublish( 3, 2 );
        assertEquals( before, c.snapshot(), "the receiver is unchanged until the commit succeeds" );
        final Map< String, Contribution > expected = new LinkedHashMap<>();
        expected.put( "alice", new Contribution( 4, 4, NONE, NONE ) );
        expected.put( "carol", new Contribution( NONE, NONE, 3, 3 ) );
        expected.put( "dave", new Contribution( 4, 5, NONE, NONE ) );
        assertEquals( expected, after.snapshot() );
        assertEquals( List.of( "alice", "carol", "dave" ), after.authors() );
        assertEquals( Set.of(), after.creditedAt( 3, 2 ), "nothing at or below the published point is credited twice" );
        after.clear();
        assertTrue( after.isEmpty() );
        assertFalse( c.isEmpty() );
    }

    @Test
    void recordSkipsServerChangesWithoutAnAuthor() {
        final Contributors c = Contributors.of( Map.of() );
        final ChangeSet ins = ChangeSet.replace( 0, 0, 0, "x" );
        c.record( List.of( new StoredUpdate( 1, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, null, ins ),
                new StoredUpdate( 2, StoredUpdate.SERVER, CollabSession.SERVER_CLIENT, "creator", ins ),
                new StoredUpdate( 3, StoredUpdate.TEXT, "c1", "alice", ins ) ),
            List.of( new StoredMetaOp( 1, null, "title", "x" ), new StoredMetaOp( 2, "bob", "title", "y" ) ) );
        assertEquals( List.of( "creator", "alice", "bob" ), c.authors() );
    }

    @Test
    void ofCopiesAndKeepsOrder() {
        final Map< String, Contribution > m = new LinkedHashMap<>();
        m.put( "zed", new Contribution( 1, 1, NONE, NONE ) );
        m.put( "amy", new Contribution( NONE, NONE, 1, 2 ) );
        final Contributors c = Contributors.of( m );
        m.clear();
        assertEquals( List.of( "zed", "amy" ), c.authors() );
        assertThrows( UnsupportedOperationException.class, () -> c.snapshot().clear() );
        assertThrows( IllegalArgumentException.class, () -> c.text( " ", 1 ) );
        assertThrows( IllegalArgumentException.class, () -> c.text( "amy", 0 ) );
        assertThrows( IllegalArgumentException.class, () -> c.meta( "amy", 0 ) );
    }

    /** One op of the random model: a text update at {@code version} or a meta op at {@code seq} by {@code author}. */
    private record Op( String author, int version, long seq ) {
    }

    @Test
    void aSeededModelAgreesWithBruteForce() {
        final String[] authors = { "a", "b", "c", "d", "e" };
        int publishes = 0;
        for( long seed = 0; seed < 2_000; seed++ ) {
            final Random rnd = new Random( seed );
            Contributors c = Contributors.of( Map.of() );
            final List< Op > ops = new ArrayList<>();
            int head = 0;
            long metaSeq = 0;
            int fromV = 0;        // the last publish (or discard) point: ops at or below it are no longer counted
            long fromM = 0;
            int pendingV = -1;    // a publish in its save
            long pendingM = -1;
            for( int step = 0; step < 60; step++ ) {
                final int roll = rnd.nextInt( 100 );
                final String who = authors[ rnd.nextInt( authors.length ) ];
                if( roll < 45 ) {
                    head++;
                    c.text( who, head );
                    ops.add( new Op( who, head, NONE ) );
                } else if( roll < 70 ) {
                    metaSeq++;
                    c.meta( who, metaSeq );
                    ops.add( new Op( who, NONE, metaSeq ) );
                } else if( roll < 82 && pendingV < 0 ) {
                    pendingV = head;      // the publish starts: V and M_V are the head at that moment
                    pendingM = metaSeq;
                } else if( roll < 94 && pendingV >= 0 ) {
                    final Set< String > expected = new HashSet<>();
                    for( final Op op : ops ) {
                        if( ( op.version() > fromV && op.version() <= pendingV )
                                || ( op.seq() > fromM && op.seq() <= pendingM ) ) {
                            expected.add( op.author() );
                        }
                    }
                    assertEquals( expected, c.creditedAt( pendingV, pendingM ), "seed " + seed + " step " + step );
                    c = c.afterPublish( pendingV, pendingM );
                    fromV = pendingV;
                    fromM = pendingM;
                    pendingV = -1;
                    pendingM = -1;
                    publishes++;
                } else if( pendingV < 0 ) {
                    c.clear();            // a discard: nothing before it is ever credited
                    fromV = head;
                    fromM = metaSeq;
                }
            }
        }
        assertTrue( publishes > 5_000, "the model exercised publishes: " + publishes );
    }
}

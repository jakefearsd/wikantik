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
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import com.wikantik.collab.text.ChangeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 Task 5: the section 12.3 counters through a recording {@link CollabMetrics}: pushes by result (with their
 * latency), publishes by result, external merges and 410 resyncs.
 */
class CollabMetricsTest {

    private static final String PAGE = "Page";
    private static final String BODY = "hello\nworld\n";
    private static final Instant T0 = Instant.parse( "2026-01-01T00:00:00Z" );

    /** Counts each call by name and label. */
    static final class RecordingMetrics implements CollabMetrics {
        final Map< String, Integer > counts = Collections.synchronizedMap( new TreeMap<>() );
        final List< Long > latencies = Collections.synchronizedList( new ArrayList<>() );

        private void count( final String key ) {
            counts.merge( key, 1, Integer::sum );
        }

        @Override
        public void push( final String result ) {
            count( "push:" + result );
        }

        @Override
        public void pushLatency( final long nanos ) {
            latencies.add( nanos );
        }

        @Override
        public void publish( final String result ) {
            count( "publish:" + result );
        }

        @Override
        public void externalMerge() {
            count( "externalMerge" );
        }

        @Override
        public void resync() {
            count( "resync" );
        }
    }

    private InMemoryCollabStore store;
    private ScriptedPageWriter writer;
    private MutableClock clock;
    private RecordingMetrics metrics;
    private CollabSession session;

    private CollabSession create( final CollabConfig c, final long max ) {
        final SessionRecord rec = CollabSession.newRecord( UUID.nameUUIDFromBytes( PAGE.getBytes() ), PAGE,
            writer.latest( PAGE ), "creator", clock );
        return CollabSession.create( rec, new CollabPorts( store, writer, key -> null, CollabSessionTest.ALLOW, clock,
            CollabAudit.NONE, metrics ), c, max );
    }

    @BeforeEach
    void setUp() {
        store = new InMemoryCollabStore();
        writer = new ScriptedPageWriter();
        writer.seed( PAGE, BODY, Map.of( "title", "T" ) );
        clock = new MutableClock( T0 );
        metrics = new RecordingMetrics();
        session = create( CollabSessionTest.config( 3, 500, 1000 ), 1000 );
    }

    private JoinResponse join( final String author ) {
        return session.join( JoinRequest.of( author, author, null ) );
    }

    private PushResponse push( final JoinResponse j, final String author, final int version, final String text ) {
        final String doc = session.view().doc();
        return session.push( j.participantId(), author, version,
            List.of( new Update( j.clientId(), ChangeSet.replace( doc.length(), 0, 0, text ) ) ) );
    }

    private int publishStatus( final JoinResponse j, final String author ) {
        return session.publish( j.participantId(), author, session.view().head(), "note" ).status();
    }

    @Test
    void pushResultsAreCountedByKind() {
        final JoinResponse a = join( "alice" );
        assertEquals( 200, push( a, "alice", 0, "x" ).status() );
        assertEquals( 409, push( a, "alice", 0, "y" ).status(), "stale base" );
        assertEquals( 413, push( a, "alice", 1, "z".repeat( 2000 ) ).status() );

        assertEquals( Map.of( "push:accepted", 1, "push:rebase", 1, "push:rejected", 1 ), metrics.counts );
        assertEquals( 3, metrics.latencies.size() );
        assertTrue( metrics.latencies.stream().allMatch( n -> n >= 0 ), metrics.latencies.toString() );
    }

    @Test
    void publishResultsAreCounted() {
        final JoinResponse a = join( "alice" );
        assertEquals( 200, publishStatus( a, "alice" ), "unchanged" );
        assertEquals( 200, push( a, "alice", session.view().head(), "x" ).status() );
        assertEquals( 200, publishStatus( a, "alice" ), "published" );
        assertEquals( 200, push( a, "alice", session.view().head(), "y" ).status() );
        writer.externalSave( PAGE, "outside\n", Map.of( "title", "T" ), "agent" );   // unseen: the A1 backstop
        assertEquals( 409, publishStatus( a, "alice" ) );
        writer.failNextPublish( new WikiException( "a filter refused it" ) );
        assertEquals( 422, publishStatus( a, "alice" ) );
        final PublishStart st = session.startPublish( a.participantId(), "alice", session.view().head(), "note" );
        assertEquals( 500, session.failPublish( st.ticket(), new IllegalStateException( "the page store broke" ) )
            .status(), "the two-step publish is counted too" );
        store.failOn( InMemoryCollabStore.Op.UPDATE_SESSION, 1 );
        assertEquals( 503, publishStatus( a, "alice" ) );

        assertEquals( Map.of( "publish:unchanged", 1, "publish:published", 1, "publish:conflict", 1,
            "publish:refused", 1, "publish:failed", 1, "publish:store", 1 ), filtered( "publish:" ) );
    }

    @Test
    void externalMergesAndResyncsAreCounted() {
        final JoinResponse a = join( "alice" );
        final int v = writer.externalSave( PAGE, BODY + "agent\n", Map.of( "title", "T" ), "agent" );
        session.reconcile( writer.version( PAGE, v ).orElseThrow() );
        session.reconcile( writer.version( PAGE, v ).orElseThrow() );   // repeated: merges nothing
        assertEquals( 1, metrics.counts.getOrDefault( "externalMerge", 0 ) );

        String doc = session.view().doc();
        for( int i = 0; i < 5; i++ ) {
            assertEquals( 200, push( a, "alice", session.view().head(), "a" ).status() );
            doc = "a" + doc;
        }
        final int head = session.view().head();
        store.writeSnapshot( session.id(), new Snapshot( head - 2, session.view().metaSeq(),
            session.view().basePageVersion(), doc, session.view().meta(), session.view().local() ) );
        store.truncateUpdatesBelow( session.id(), head - 1 );
        final PullResponse gone = session.pullOrPark( a.participantId(), "alice",
            PullCursor.text( head - 4, Long.MAX_VALUE ), null, r -> { } );
        assertEquals( 410, gone.status() );
        assertEquals( 1, metrics.counts.getOrDefault( "resync", 0 ) );
    }

    private Map< String, Integer > filtered( final String prefix ) {
        final Map< String, Integer > out = new TreeMap<>();
        metrics.counts.forEach( ( k, n ) -> {
            if( k.startsWith( prefix ) ) {
                out.put( k, n );
            }
        } );
        return out;
    }
}

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

import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSet;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * E33 under F7: real threads join, edit and leave two pages through one {@link CollabSessionRegistry} while a ticker
 * advances the clock past the idle time and ticks, so idle evictions (deleting the unchanged page's draft, keeping the
 * edited one's) race the joins. Checked on every join: the session joined is the live one, and its draft is in the
 * store; at the end, every accepted edit is in the draft.
 */
public final class RegistryRace {

    /** What one run did. */
    public record Report( int joins, int accepted, int evictionsSeen, int finalEdits ) {
    }

    private static final String KEPT = "Kept";
    private static final String EMPTY = "Empty";
    private static final CollabPermissions ALLOW_ALL = new CollabPermissions() {
        @Override
        public boolean canEdit( final Object participantSession, final String pageName ) {
            return true;
        }

        @Override
        public boolean canCreate( final Object participantSession, final String pageName ) {
            return true;
        }
    };

    private final int joinersPerPage;
    private final int rounds;
    private final Duration timeout;
    private final ConcurrentLinkedQueue< String > failures = new ConcurrentLinkedQueue<>();

    public RegistryRace( final int joinersPerPage, final int rounds, final Duration timeout ) {
        this.joinersPerPage = joinersPerPage;
        this.rounds = rounds;
        this.timeout = timeout;
    }

    /** One run on fresh doubles; throws {@link AssertionError} on the first broken rule. */
    public Report run() {
        final InMemoryCollabStore store = new InMemoryCollabStore();
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( KEPT, "kept\n", Map.of() );
        writer.seed( EMPTY, "empty\n", Map.of() );
        final MutableClock clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        // presence and permission timeouts far beyond the run, so only idle eviction removes anything; 1-minute idle
        final CollabConfig config = new CollabConfig( true, 1000, 65536, 25, 1_000_000_000, 1_000_000_000, 100, 4, 1, 0,
            1_000_000 );
        final CollabSessionRegistry registry = new CollabSessionRegistry( store, writer, ALLOW_ALL, k -> null, clock,
            config, 1 << 20 );
        final AtomicInteger joins = new AtomicInteger();
        final AtomicInteger accepted = new AtomicInteger();
        final AtomicInteger evictions = new AtomicInteger();
        final AtomicBoolean done = new AtomicBoolean();
        final CountDownLatch start = new CountDownLatch( 1 );
        final ExecutorService pool = Executors.newFixedThreadPool( 2 * joinersPerPage + 1 );
        try {
            final List< Future< ? > > joiners = new ArrayList<>();
            for( int t = 0; t < joinersPerPage; t++ ) {
                final String author = "user" + t;
                joiners.add( pool.submit( () -> joinLoop( registry, store, KEPT, author, start, joins, accepted ) ) );
                joiners.add( pool.submit( () -> joinLoop( registry, store, EMPTY, author, start, joins, null ) ) );
            }
            final Future< ? > ticker = pool.submit( () -> tickLoop( registry, clock, start, done, evictions ) );
            start.countDown();
            for( final Future< ? > f : joiners ) {
                await( f );
            }
            done.set( true );
            await( ticker );
        } finally {
            pool.shutdownNow();
        }
        if( !failures.isEmpty() ) {
            throw new AssertionError( failures.size() + " registry race failures, first: " + failures.peek() );
        }
        final int finalEdits = countEdits( registry );
        if( finalEdits != accepted.get() ) {
            throw new AssertionError( "lost draft: " + accepted.get() + " edits accepted, " + finalEdits + " in the draft" );
        }
        return new Report( joins.get(), accepted.get(), evictions.get(), finalEdits );
    }

    private void await( final Future< ? > f ) {
        try {
            f.get( timeout.toMillis(), TimeUnit.MILLISECONDS );
        } catch( final Exception e ) {
            throw new AssertionError( "registry race timed out or failed: " + e, e );
        }
    }

    private void joinLoop( final CollabSessionRegistry registry, final InMemoryCollabStore store, final String page,
                           final String author, final CountDownLatch start, final AtomicInteger joins,
                           final AtomicInteger accepted ) {
        awaitStart( start );
        for( int i = 0; i < rounds && failures.isEmpty(); i++ ) {
            final CollabSessionRegistry.JoinOutcome o = registry.join( page, JoinRequest.of( author, author, author ) );
            if( !( o instanceof CollabSessionRegistry.Joined j ) ) {
                failures.add( "join of " + page + " by " + author + " answered " + o );
                return;
            }
            joins.incrementAndGet();
            final UUID id = j.response().sessionId();
            if( registry.live( id ).orElse( null ) != j.session() ) {
                failures.add( "joined session " + id + " of " + page + " is not the live one" );
            }
            if( store.session( id ).isEmpty() ) {
                failures.add( "joined session " + id + " of " + page + " has no draft in the store" );
            }
            if( accepted != null && edit( j, author ) ) {
                accepted.incrementAndGet();
            }
            if( j.session().leave( j.response().participantId(), author ).status() != 200 ) {
                failures.add( "leave of " + page + " by " + author + " refused" );
            }
        }
    }

    /** Inserts one "x" at the head, retrying stale pushes; false (with a failure) on anything else. */
    private boolean edit( final CollabSessionRegistry.Joined j, final String author ) {
        for( int attempt = 0; attempt < 10_000; attempt++ ) {
            final CollabSession.View v = j.session().view();
            final PushResponse r = j.session().push( j.response().participantId(), author, v.head(),
                List.of( new Update( j.response().clientId(), ChangeSet.replace( v.doc().length(), 0, 0, "x" ) ) ) );
            if( r.status() == 200 ) {
                return true;
            }
            if( r.status() != 409 || !"stale-base".equals( r.reason() ) ) {
                failures.add( "push by " + author + " answered " + r );
                return false;
            }
        }
        failures.add( "push by " + author + " starved" );
        return false;
    }

    private void tickLoop( final CollabSessionRegistry registry, final MutableClock clock, final CountDownLatch start,
                           final AtomicBoolean done, final AtomicInteger evictions ) {
        awaitStart( start );
        while( !done.get() && failures.isEmpty() ) {
            final int before = registry.liveCount();
            clock.advance( Duration.ofMinutes( 2 ) );
            registry.tick();
            if( registry.liveCount() < before ) {
                evictions.incrementAndGet();
            }
        }
    }

    private static void awaitStart( final CountDownLatch start ) {
        try {
            start.await();
        } catch( final InterruptedException e ) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException( "interrupted before the start", e );
        }
    }

    private static int countEdits( final CollabSessionRegistry registry ) {
        final CollabSessionRegistry.JoinOutcome o = registry.join( KEPT, JoinRequest.of( "auditor", "auditor", "A" ) );
        if( !( o instanceof CollabSessionRegistry.Joined j ) ) {
            throw new AssertionError( "final join answered " + o );
        }
        return (int) j.response().doc().chars().filter( c -> c == 'x' ).count();
    }
}

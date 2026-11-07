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

import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PublishResult;
import com.wikantik.collab.testing.SimServer.PullResult;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Update;
import com.wikantik.collab.testing.SimServer.Wakeup;
import com.wikantik.collab.text.ChangeSet;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * F7: real threads, for the narrow job of proving the locking is correct (E38, E40). Everything else about the
 * protocol is tested single-threaded by {@link SimScheduler}.
 *
 * <p>Each run builds a fresh server from the factory, joins {@code threads} participants and releases one worker per
 * participant through a {@link CyclicBarrier}. {@link #pushRace} has every worker push to the same version at once, round
 * after round (E38: exactly one is accepted per version). {@link #run} has every worker perform a seeded mix of local
 * edit, push, pull (parking when caught up), publish and leave-then-rejoin (E40; external saves wait for Phase 1, the
 * stub ignores them). Every run is bounded by {@code runTimeout}: on timeout the harness dumps every thread's stack
 * (deadlock diagnosis) and fails. After each run it asserts that the versions acknowledged to pushes and the server
 * updates in the store log are exactly {@code 1..head} with no gap or double claim, that every parked pull was answered
 * exactly once, that the clients converge, and {@link CollabInvariants} (with replay; invariant 2 is store log == ring
 * suffix).</p>
 *
 * <p>Wakeups (parked-pull answers) may be drained by any thread; draining and routing them to their participant's inbox
 * happens under one harness lock so a participant's answers keep their order. That lock never wraps another server
 * call, so it cannot hide a server deadlock.</p>
 */
public final class ConcurrencyHarness {

    /** The default-build run count; {@code -Dcollab.stress.runs} overrides (the weekly soak uses 20,000). */
    public static final int DEFAULT_RUNS = 50;
    static final int OPS_PER_THREAD = 80;
    static final int ROUNDS = 8;
    static final int MAX_QUIESCENCE_ROUNDS = 200;
    private static final int MAX_DOC = 400;
    private static final String SERVER_CLIENT = "server";

    /** What happened across all runs of one call (all counts are totals). */
    public record Report( int runs, long rounds, long accepted, long stale, long pulls, long parked, long wakeups,
                          long publishes, long leaves, long joins, int maxHead ) {
    }

    private final int threads;
    private final int runs;
    private final Duration runTimeout;
    private final Supplier< SimServer > factory;

    public ConcurrencyHarness( final int threads, final int runs, final Duration runTimeout,
                               final Supplier< SimServer > factory ) {
        if( threads < 2 ) {
            throw new IllegalArgumentException( "threads must be >= 2: " + threads );
        }
        if( runs < 1 ) {
            throw new IllegalArgumentException( "runs must be >= 1: " + runs );
        }
        this.threads = threads;
        this.runs = runs;
        this.runTimeout = Objects.requireNonNull( runTimeout );
        this.factory = Objects.requireNonNull( factory );
    }

    /** {@code -Dcollab.stress.runs}, default {@value #DEFAULT_RUNS}. */
    public static int runs() {
        return Integer.getInteger( "collab.stress.runs", DEFAULT_RUNS );
    }

    private static final class Tally {
        final AtomicLong rounds = new AtomicLong();
        final AtomicLong accepted = new AtomicLong();
        final AtomicLong stale = new AtomicLong();
        final AtomicLong pulls = new AtomicLong();
        final AtomicLong parked = new AtomicLong();
        final AtomicLong wakeups = new AtomicLong();
        final AtomicLong publishes = new AtomicLong();
        final AtomicLong leaves = new AtomicLong();
        final AtomicLong joins = new AtomicLong();
        int maxHead;

        Report report( final int runs ) {
            return new Report( runs, rounds.get(), accepted.get(), stale.get(), pulls.get(), parked.get(), wakeups.get(),
                publishes.get(), leaves.get(), joins.get(), maxHead );
        }
    }

    // ---- E38 ------------------------------------------------------------------------------------------------------

    /**
     * E38: in each of {@value #ROUNDS} rounds every worker pushes one update to the same version at the same moment;
     * exactly one push per round may be accepted, every other one must be a {@code stale-base} 409.
     */
    public Report pushRace( final long seed ) {
        final Tally tally = new Tally();
        for( int run = 0; run < runs; run++ ) {
            pushRaceOnce( seed, run, tally );
        }
        return tally.report( runs );
    }

    private void pushRaceOnce( final long seed, final int run, final Tally tally ) {
        final SimServer server = factory.get();
        final List< Joined > joined = new ArrayList<>();
        for( int t = 0; t < threads; t++ ) {
            joined.add( server.join( author( t ) ) );
            tally.joins.incrementAndGet();
        }
        final AtomicInteger version = new AtomicInteger( server.view().head() );
        final java.util.concurrent.atomic.AtomicReference< String > doc =
            new java.util.concurrent.atomic.AtomicReference<>( server.view().doc() );
        final AtomicInteger acceptedThisRound = new AtomicInteger();
        final AtomicInteger round = new AtomicInteger();
        final Queue< String > errors = new ConcurrentLinkedQueue<>();
        final CyclicBarrier start = new CyclicBarrier( threads );
        final CyclicBarrier end = new CyclicBarrier( threads, () -> {
            final int acc = acceptedThisRound.getAndSet( 0 );
            final InvariantView v = server.view();
            if( acc != 1 ) {
                errors.add( "round " + round.get() + " at version " + version.get() + ": " + acc
                    + " pushes accepted, expected exactly one (E38)" );
            } else if( v.head() != version.get() + 1 ) {
                errors.add( "round " + round.get() + ": one push accepted but head moved from " + version.get() + " to "
                    + v.head() );
            }
            tally.rounds.incrementAndGet();
            round.incrementAndGet();
            version.set( v.head() );
            doc.set( v.doc() );
        } );
        final long budget = runTimeout.toMillis();
        final List< Callable< Void > > workers = new ArrayList<>();
        for( int t = 0; t < threads; t++ ) {
            final Joined j = joined.get( t );
            final String author = author( t );
            final SplittableRandom rnd = new SplittableRandom( mix( seed, run, t ) );
            workers.add( () -> {
                for( int k = 0; k < ROUNDS; k++ ) {
                    start.await( budget, TimeUnit.MILLISECONDS );
                    final String d = doc.get();
                    final int at = rnd.nextInt( d.length() + 1 );
                    final PushResult r = server.push( j.participantId(), author, version.get(),
                        List.of( new Update( j.clientId(), ChangeSet.replace( d.length(), at, at, j.clientId() ) ) ) );
                    if( r.status() == 200 ) {
                        acceptedThisRound.incrementAndGet();
                        tally.accepted.incrementAndGet();
                    } else if( r.status() == 409 && "stale-base".equals( r.reason() ) ) {
                        tally.stale.incrementAndGet();
                    } else {
                        errors.add( author + ": push answered " + r.status() + " " + r.reason() );
                    }
                    end.await( budget, TimeUnit.MILLISECONDS );
                    if( !errors.isEmpty() ) {
                        return null;
                    }
                }
                return null;
            } );
        }
        execute( seed, run, workers );
        if( !errors.isEmpty() ) {
            throw new AssertionError( "seed " + seed + " run " + run + ": " + errors );
        }
        final InvariantView v = server.view();
        checkLog( seed, run, v, Map.of() );
        CollabInvariants.check( v, true );
        tally.maxHead = Math.max( tally.maxHead, v.head() );
    }

    // ---- E40 ------------------------------------------------------------------------------------------------------

    /** One worker's client state; touched only by its own thread until the run ends. */
    private static final class Worker {
        final int index;
        final String author;
        final SplittableRandom rnd;
        SimClient sim;
        /** The {@code since} of each pull the server is holding for the current participant, oldest first. */
        final Deque< Integer > parkedSince = new ArrayDeque<>();

        Worker( final int index, final String author, final SplittableRandom rnd, final Joined joined ) {
            this.index = index;
            this.author = author;
            this.rnd = rnd;
            this.sim = new SimClient( author, joined );
        }
    }

    /** Per-run shared state of {@link #run}. */
    private final class Mixed {
        final long seed;
        final int run;
        final SimServer server;
        final Tally tally;
        final Object routeLock = new Object();
        final Map< String, Queue< PullResult > > inbox = new ConcurrentHashMap<>();
        final Map< String, AtomicInteger > parks = new ConcurrentHashMap<>();
        final Map< String, AtomicInteger > answered = new ConcurrentHashMap<>();
        /** Every version range acknowledged to a push: {@code [first, last]} and the pushing client id. */
        final Queue< Map.Entry< int[], String > > claims = new ConcurrentLinkedQueue<>();
        final List< Worker > workers = new ArrayList<>();

        Mixed( final long seed, final int run, final SimServer server, final Tally tally ) {
            this.seed = seed;
            this.run = run;
            this.server = server;
            this.tally = tally;
        }

        AssertionError fail( final String what ) {
            return new AssertionError( "seed " + seed + " run " + run + ": " + what );
        }

        /** Drains every completed parked pull and queues it for its participant, in order. */
        void route() {
            synchronized( routeLock ) {
                for( final Wakeup w : server.drainWakeups() ) {
                    if( w.result().parked() ) {
                        throw fail( "a wakeup for " + w.participantId() + " is itself parked" );
                    }
                    answered.computeIfAbsent( w.participantId(), k -> new AtomicInteger() ).incrementAndGet();
                    tally.wakeups.incrementAndGet();
                    inbox.computeIfAbsent( w.participantId(), k -> new ConcurrentLinkedQueue<>() ).add( w.result() );
                }
            }
        }

        /** Feeds the worker's routed parked-pull answers to its client. */
        void consume( final Worker w ) {
            final Queue< PullResult > q = inbox.get( w.sim.participantId() );
            if( q == null ) {
                return;
            }
            for( PullResult r = q.poll(); r != null; r = q.poll() ) {
                final Integer since = w.parkedSince.pollFirst();
                if( since == null ) {
                    throw fail( w.author + " got a parked-pull answer with no parked pull (each is answered exactly once)" );
                }
                if( r.status() != 200 ) {
                    throw fail( w.author + "'s parked pull was answered " + r.status() + " " + r.reason() );
                }
                w.sim.receive( r.updates(), since );
            }
        }

        void push( final Worker w ) {
            final List< Update > out = w.sim.sendable();
            final int base = w.sim.version();
            final PushResult r = server.push( w.sim.participantId(), w.author, base, out );
            if( r.status() == 200 ) {
                tally.accepted.incrementAndGet();
                claims.add( Map.entry( new int[]{ r.version() - out.size() + 1, r.version() }, w.sim.clientId() ) );
            } else if( r.status() == 409 && "stale-base".equals( r.reason() ) ) {
                tally.stale.incrementAndGet();
            } else {
                throw fail( w.author + "'s push at " + base + " answered " + r.status() + " " + r.reason() );
            }
        }

        void pull( final Worker w ) {
            final int since = w.sim.version();
            final PullResult r = server.pull( w.sim.participantId(), w.author, since );
            tally.pulls.incrementAndGet();
            if( r.parked() ) {
                tally.parked.incrementAndGet();
                parks.computeIfAbsent( w.sim.participantId(), k -> new AtomicInteger() ).incrementAndGet();
                w.parkedSince.addLast( since );
            } else if( r.status() == 200 ) {
                w.sim.receive( r.updates(), since );
            } else {
                throw fail( w.author + "'s pull from " + since + " answered " + r.status() + " " + r.reason() );
            }
        }

        void publish( final Worker w ) {
            final PublishResult r = server.publish( w.sim.participantId(), w.author, w.sim.version(), "stress" );
            final boolean ok = r.status() == 200 || r.status() == 409 && "stale-base".equals( r.reason() )
                || r.status() == 400 && "empty".equals( r.reason() );
            if( !ok ) {
                throw fail( w.author + "'s publish answered " + r.status() + " " + r.reason() );
            }
            if( r.status() == 200 && !r.unchanged() ) {
                tally.publishes.incrementAndGet();
            }
        }

        /** Leaves, then rejoins; unconfirmed text goes to the recovery panel (A10), never re-applied. */
        void leaveAndRejoin( final Worker w ) {
            server.leave( w.sim.participantId() );
            tally.leaves.incrementAndGet();
            route();
            consume( w );
            if( !w.parkedSince.isEmpty() ) {
                throw fail( w.author + " left with " + w.parkedSince.size() + " parked pull(s) never answered" );
            }
            w.sim.resync( server.join( w.author ) );
            tally.joins.incrementAndGet();
        }

        void localEdit( final Worker w ) {
            final String d = w.sim.doc();
            final int from = w.rnd.nextInt( d.length() + 1 );
            if( d.length() > MAX_DOC ) {
                w.sim.localEdit( ChangeSet.replace( d.length(), from, Math.min( d.length(), from + 40 ), "" ) );
                return;
            }
            final int to = w.rnd.nextInt( 4 ) == 0 ? Math.min( d.length(), from + w.rnd.nextInt( 6 ) ) : from;
            final String[] words = { "a", "bc", " ", "  \n", "x\n", w.sim.clientId() };
            w.sim.localEdit( ChangeSet.replace( d.length(), from, to, words[ w.rnd.nextInt( words.length ) ] ) );
        }

        void step( final Worker w ) {
            consume( w );
            final int k = w.rnd.nextInt( 100 );
            if( k < 30 ) {
                localEdit( w );
            } else if( k < 55 ) {
                if( w.sim.sendable().isEmpty() ) {
                    pull( w );
                } else {
                    push( w );
                }
            } else if( k < 82 ) {
                pull( w );
            } else if( k < 91 ) {
                publish( w );
            } else if( k < 95 ) {
                leaveAndRejoin( w );
            }
            route();
        }
    }

    /**
     * E40: every worker performs {@value #OPS_PER_THREAD} seeded operations (local edit, push, pull, publish,
     * leave-then-rejoin) concurrently; then the run is quiesced single-threaded and checked.
     */
    public Report run( final long seed ) {
        final Tally tally = new Tally();
        for( int run = 0; run < runs; run++ ) {
            mixedOnce( seed, run, tally );
        }
        return tally.report( runs );
    }

    private void mixedOnce( final long seed, final int run, final Tally tally ) {
        final Mixed m = new Mixed( seed, run, factory.get(), tally );
        for( int t = 0; t < threads; t++ ) {
            m.workers.add( new Worker( t, author( t ), new SplittableRandom( mix( seed, run, t ) ), m.server.join( author( t ) ) ) );
            tally.joins.incrementAndGet();
        }
        final CyclicBarrier start = new CyclicBarrier( threads );
        final long budget = runTimeout.toMillis();
        final List< Callable< Void > > bodies = new ArrayList<>();
        for( final Worker w : m.workers ) {
            bodies.add( () -> {
                start.await( budget, TimeUnit.MILLISECONDS );
                for( int i = 0; i < OPS_PER_THREAD; i++ ) {
                    m.step( w );
                }
                return null;
            } );
        }
        execute( seed, run, bodies );
        checkLog( seed, run, m.server.view(), claimsOf( m ) );
        quiesce( m );
        final InvariantView v = m.server.view();
        checkLog( seed, run, v, claimsOf( m ) );
        for( final Worker w : m.workers ) {
            if( !w.sim.doc().equals( v.doc() ) || w.sim.version() != v.head() ) {
                throw m.fail( "convergence: " + w.author + " at version " + w.sim.version() + " with doc length "
                    + w.sim.doc().length() + ", server head " + v.head() + " doc length " + v.doc().length() );
            }
        }
        // every parked pull is answered exactly once: leaving completes the remaining waiters
        for( final Worker w : m.workers ) {
            m.server.leave( w.sim.participantId() );
        }
        m.route();
        for( final Map.Entry< String, AtomicInteger > e : m.parks.entrySet() ) {
            final int got = m.answered.getOrDefault( e.getKey(), new AtomicInteger() ).get();
            if( got != e.getValue().get() ) {
                throw m.fail( e.getKey() + " parked " + e.getValue().get() + " pull(s) but " + got + " were answered" );
            }
        }
        for( final String p : m.answered.keySet() ) {
            if( !m.parks.containsKey( p ) ) {
                throw m.fail( "wakeup for " + p + ", which never parked a pull" );
            }
        }
        CollabInvariants.check( m.server.view(), true );
        tally.maxHead = Math.max( tally.maxHead, v.head() );
    }

    private static Map< Integer, String > claimsOf( final Mixed m ) {
        final Map< Integer, String > owner = new HashMap<>();
        for( final Map.Entry< int[], String > c : m.claims ) {
            for( int version = c.getKey()[ 0 ]; version <= c.getKey()[ 1 ]; version++ ) {
                final String prior = owner.put( version, c.getValue() );
                if( prior != null ) {
                    throw m.fail( "version " + version + " acknowledged to two pushes (" + prior + " and " + c.getValue() + ")" );
                }
            }
        }
        return owner;
    }

    /** Single-threaded: every client pushes and pulls until nothing is unconfirmed and everyone is at head. */
    private void quiesce( final Mixed m ) {
        for( int round = 0; round < MAX_QUIESCENCE_ROUNDS; round++ ) {
            boolean busy = false;
            for( final Worker w : m.workers ) {
                m.consume( w );
                final int head = m.server.view().head();
                if( !w.sim.sendable().isEmpty() ) {
                    busy = true;
                    if( w.sim.version() == head ) {
                        m.push( w );
                    } else {
                        m.pull( w );
                    }
                } else if( w.sim.version() < head ) {
                    busy = true;
                    m.pull( w );
                }
                m.route();
            }
            if( !busy ) {
                return;
            }
        }
        throw m.fail( "convergence: no quiescence after " + MAX_QUIESCENCE_ROUNDS + " rounds" );
    }

    /**
     * Versions {@code 1..head} are each either a server update in the log or acknowledged to exactly one push from
     * the client whose update the log holds there; no push is acknowledged a version beyond head.
     */
    private static void checkLog( final long seed, final int run, final InvariantView v, final Map< Integer, String > claims ) {
        final List< StoredUpdate > log = v.store().updatesSince( v.sessionId(), 0 );
        final String where = "seed " + seed + " run " + run + ": ";
        if( log.size() != v.head() ) {
            throw new AssertionError( where + "the store log holds " + log.size() + " updates but head is version " + v.head() );
        }
        for( int i = 0; i < log.size(); i++ ) {
            final StoredUpdate u = log.get( i );
            if( u.version() != i + 1 ) {
                throw new AssertionError( where + "store log version " + u.version() + " at position " + ( i + 1 ) );
            }
            final String owner = claims.get( u.version() );
            if( SERVER_CLIENT.equals( u.clientId() ) ? owner != null : owner != null && !owner.equals( u.clientId() ) ) {
                throw new AssertionError( where + "version " + u.version() + " is logged for " + u.clientId()
                    + " but was acknowledged to " + owner );
            }
        }
        for( final int version : claims.keySet() ) {
            if( version < 1 || version > v.head() ) {
                throw new AssertionError( where + "version " + version + " acknowledged to " + claims.get( version )
                    + " is outside 1.." + v.head() );
            }
        }
    }

    // ---- execution ------------------------------------------------------------------------------------------------

    /** Runs the bodies on {@code threads} named threads, bounded by the run timeout; a timeout dumps every stack. */
    private void execute( final long seed, final int run, final List< Callable< Void > > bodies ) {
        final AtomicInteger n = new AtomicInteger();
        final ExecutorService pool = Executors.newFixedThreadPool( bodies.size(), r -> {
            final Thread t = new Thread( r, "collab-stress-" + seed + "-" + run + "-" + n.getAndIncrement() );
            t.setDaemon( true );
            return t;
        } );
        try {
            // completion order, so the first worker to fail is reported at once (not as a timeout of the others)
            final ExecutorCompletionService< Void > done = new ExecutorCompletionService<>( pool );
            bodies.forEach( done::submit );
            final long deadline = System.nanoTime() + runTimeout.toNanos();
            for( int i = 0; i < bodies.size(); i++ ) {
                final long left = Math.max( 0, deadline - System.nanoTime() );
                try {
                    final Future< Void > f = done.poll( left, TimeUnit.NANOSECONDS );
                    if( f == null ) {
                        throw new AssertionError( "seed " + seed + " run " + run + " timed out after " + runTimeout
                            + " (deadlock or livelock?); thread dump:\n" + threadDump() );
                    }
                    f.get();
                } catch( final ExecutionException e ) {
                    throw failure( seed, run, e.getCause() );
                } catch( final InterruptedException e ) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError( "seed " + seed + " run " + run + ": interrupted", e );
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static AssertionError failure( final long seed, final int run, final Throwable cause ) {
        if( cause instanceof AssertionError a ) {
            return a;
        }
        if( cause instanceof BrokenBarrierException || cause instanceof TimeoutException ) {
            return new AssertionError( "seed " + seed + " run " + run + ": a worker lost its barrier (another worker failed or hung); thread dump:\n" + threadDump(), cause );
        }
        return new AssertionError( "seed " + seed + " run " + run + ": a worker threw " + cause, cause );
    }

    static String threadDump() {
        final StringBuilder sb = new StringBuilder();
        Thread.getAllStackTraces().entrySet().stream()
            .sorted( java.util.Comparator.comparing( e -> e.getKey().getName() ) )
            .forEach( e -> {
                sb.append( '"' ).append( e.getKey().getName() ).append( "\" " ).append( e.getKey().getState() ).append( '\n' );
                final StackTraceElement[] st = e.getValue();
                for( int i = 0; i < Math.min( st.length, 40 ); i++ ) {
                    sb.append( "    at " ).append( st[ i ] ).append( '\n' );
                }
            } );
        return sb.toString();
    }

    private static String author( final int t ) {
        return "author-" + t;
    }

    private static long mix( final long seed, final int run, final int thread ) {
        return seed * 1_000_003L + run * 7_919L + thread;
    }
}

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

import com.wikantik.collab.model.PageState;
import com.wikantik.collab.testing.SimNetwork.Message;
import com.wikantik.collab.testing.SimNetwork.Type;
import com.wikantik.collab.testing.SimProfile.Kind;
import com.wikantik.collab.testing.SimServer.Joined;
import com.wikantik.collab.testing.SimServer.PublishResult;
import com.wikantik.collab.testing.SimServer.PullResult;
import com.wikantik.collab.testing.SimServer.PushResult;
import com.wikantik.collab.testing.SimServer.Wakeup;
import com.wikantik.collab.testing.SimTrace.Outcome;
import com.wikantik.collab.testing.SimTrace.Status;
import com.wikantik.collab.testing.SimTrace.Step;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The deterministic collaborative-editing simulator (F1): one seeded {@link SplittableRandom}, no threads, no sleeps,
 * no wall clock. Each step picks one action from the {@link SimProfile}'s weights; the step is recorded with every
 * parameter it needs, so a run is replayed from its {@link SimTrace} alone ({@link #replay}), not from its seed.
 *
 * <p><b>Clients</b> are {@link SimClient}s driven like {@code collabPeer} will drive the library: at most one in-flight
 * push, pull, publish and join each; a 409 {@code stale-base} pulls and then re-pushes; 409 {@code rejoin} and 410 rejoin
 * and resync (unsynced text to the recovery panel); a 413 also resyncs (the oversize edit cannot ever be accepted). A
 * request unanswered for the profile's step budget times out and is retried at once, as a recorded {@code TIMEOUT} step.
 * A pull answer that is not the in-flight one (late, duplicated) is still fed to {@link SimClient#receive}, which must
 * ignore what it already has (E11); any other stale answer is ignored.</p>
 *
 * <p><b>Network</b>: requests and answers are {@link SimNetwork} messages; a step may deliver, drop or duplicate any of
 * them. A parked pull's answer comes from {@link SimServer#drainWakeups()}, drained after every server call and queued as
 * an answer to the parked request. {@code ADVANCE_CLOCK} moves the {@link MutableClock}, ticks the server and drains.</p>
 *
 * <p><b>Checks</b>: {@link CollabInvariants} after every step (replay every {@value #REPLAY_EVERY}th); a 400 or 403
 * answer to a well-formed client request is a protocol failure. At the end, quiescence: faults stop, everything is
 * delivered in order, every client pushes and pulls until nothing is in flight and nothing is unconfirmed; then every
 * client document must equal the server's and the invariants (with replay) must hold. A server whose view reports
 * {@code poisoned} ends the run as {@link Status#UNSUPPORTED} (Task 10 ruling).</p>
 */
public final class SimScheduler {

    /** The Phase 0 server under test. */
    public static final Function< SimEnv, SimServer > STUB = env -> new StubCollabAuthority( env.pageName(), env.store(),
        env.writer(), env.clock(), env.maxPageBytes(), env.ringSize() );

    static final String PAGE = "SimPage";
    /** Small, so pulls regularly fall through the ring to the store (E12). */
    static final int RING_SIZE = 4;
    static final int REPLAY_EVERY = 25;
    static final int MAX_QUIESCENCE_ROUNDS = 400;
    private static final Instant EPOCH = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final String CJK = "中文字編集協作";
    private static final String[] ASTRAL = { "😀", "𝄞", "🎉", "𝕏" };

    private final long seed;
    private final SimProfile profile;
    private final Function< SimEnv, SimServer > serverFactory;
    private final int clients;

    public SimScheduler( final long seed, final SimProfile profile, final Function< SimEnv, SimServer > serverFactory,
                         final int clients ) {
        if( clients < 1 || clients > 26 ) {
            throw new IllegalArgumentException( "clients must be 1..26: " + clients );
        }
        this.seed = seed;
        this.profile = Objects.requireNonNull( profile );
        this.serverFactory = Objects.requireNonNull( serverFactory );
        this.clients = clients;
    }

    /** Generates and executes up to {@code steps} steps (fewer if one fails), then drives the run to quiescence. */
    public SimTrace run( final int steps ) {
        final SplittableRandom rnd = new SplittableRandom( seed );
        final String initialDoc = rnd.nextInt( 10 ) == 0 ? "" : text( rnd, rnd.nextInt( 120 ) );
        final SimTrace header = new SimTrace( seed, profile, PAGE, profile.maxPageBytes(), RING_SIZE, profile.rewrite(),
            clients, initialDoc, List.of(), Outcome.NOT_RUN, List.of() );
        final World w = new World( header, serverFactory );
        final List< Step > done = new ArrayList<>();
        Outcome o = w.start();
        for( int i = 0; i < steps && o == null; i++ ) {
            final Step s = w.generate( i, rnd );
            done.add( s );
            o = w.execute( s );
        }
        if( o == null ) {
            o = w.quiesce();
        }
        return header.withSteps( done ).withResult( o, w.clientTraces() );
    }

    /** Executes exactly the trace's steps against a fresh server (no randomness), then quiescence. */
    public static SimTrace replay( final SimTrace trace, final Function< SimEnv, SimServer > serverFactory ) {
        final World w = new World( trace, serverFactory );
        Outcome o = w.start();
        for( final Step s : trace.steps() ) {
            if( o != null ) {
                break;
            }
            o = w.execute( s );
        }
        if( o == null ) {
            o = w.quiesce();
        }
        return trace.withSteps( trace.steps() ).withResult( o, w.clientTraces() );
    }

    // ---- random generation (run only; never called during execution) ------------------------------------------

    static String text( final SplittableRandom rnd, final int codePoints ) {
        final StringBuilder sb = new StringBuilder();
        for( int i = 0; i < codePoints; i++ ) {
            final int k = rnd.nextInt( 100 );
            if( k < 55 ) {
                sb.append( (char) ( 'a' + rnd.nextInt( 26 ) ) );
            } else if( k < 65 ) {
                sb.append( ' ' );
            } else if( k < 75 ) {
                sb.append( '\n' );
            } else if( k < 88 ) {
                sb.append( CJK.charAt( rnd.nextInt( CJK.length() ) ) );
            } else {
                sb.append( ASTRAL[ rnd.nextInt( ASTRAL.length ) ] );
            }
        }
        return sb.toString();
    }

    /** Moves a position off the middle of a surrogate pair (down), so no edit ever splits one. */
    static int snap( final String doc, final int pos ) {
        final int p = Math.max( 0, Math.min( pos, doc.length() ) );
        if( p > 0 && p < doc.length() && Character.isLowSurrogate( doc.charAt( p ) )
                && Character.isHighSurrogate( doc.charAt( p - 1 ) ) ) {
            return p - 1;
        }
        return p;
    }

    private static long utf8( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 ).length;
    }

    // ---- one run ----------------------------------------------------------------------------------------------

    private static final class Peer {
        final String name;
        final String authorKey;
        SimClient sim;
        String push;
        String pull;
        String join;
        String publish;
        int pushAt;
        int pullAt;
        int joinAt;
        int publishAt;
        boolean repush;

        Peer( final String name, final String authorKey ) {
            this.name = name;
            this.authorKey = authorKey;
        }
    }

    private static final class World {
        private final SimTrace header;
        private final SimProfile profile;
        private final Function< SimEnv, SimServer > factory;
        private final SimNetwork net = new SimNetwork();
        private final List< Peer > peers = new ArrayList<>();
        private final Map< String, Integer > clientOf = new LinkedHashMap<>();
        /** Per participant, the pulls the server holds, oldest first: {@code requestId -> since}. */
        private final Map< String, Deque< Map.Entry< String, Integer > > > parked = new LinkedHashMap<>();
        private SimEnv env;
        private SimServer server;
        private String tag = "start";
        private int seq;
        private int ordinal;

        World( final SimTrace header, final Function< SimEnv, SimServer > factory ) {
            this.header = header;
            this.profile = header.profile();
            this.factory = factory;
        }

        List< SimTrace.ClientTrace > clientTraces() {
            return peers.stream().filter( p -> p.sim != null )
                .map( p -> new SimTrace.ClientTrace( p.name, p.authorKey, p.sim.trace() ) ).toList();
        }

        Outcome start() {
            return guarded( () -> {
                final ScriptedPageWriter writer = new ScriptedPageWriter();
                writer.seed( header.pageName(), header.initialDoc(), Map.of() );
                writer.rewriteOnSave( SimEnv.rewrite( header.rewrite() ) );
                env = new SimEnv( header.pageName(), new MutableClock( EPOCH ), new InMemoryCollabStore(), writer,
                    header.maxPageBytes(), header.ringSize() );
                server = factory.apply( env );
                for( int i = 0; i < header.clients(); i++ ) {
                    final String name = String.valueOf( (char) ( 'A' + i ) );
                    final Peer p = new Peer( name, "author-" + name );
                    final Joined j = server.join( p.authorKey );
                    clientOf.put( j.participantId(), i );
                    p.sim = new SimClient( name, j );
                    peers.add( p );
                }
                drain();
                return afterStep( true );
            } );
        }

        // -- generation --

        Step generate( final int i, final SplittableRandom rnd ) {
            ordinal = i;
            for( int c = 0; c < peers.size(); c++ ) {
                final Peer p = peers.get( c );
                final String expired = expired( p, i );
                if( expired != null ) {
                    return Step.of( i, Kind.TIMEOUT ).client( c ).op( expired );
                }
            }
            final Map< Kind, Integer > w = new EnumMap<>( Kind.class );
            for( final Kind k : Kind.values() ) {
                if( k != Kind.TIMEOUT && eligible( k ) ) {
                    w.put( k, profile.weight( k ) );
                }
            }
            final int total = w.values().stream().mapToInt( Integer::intValue ).sum();
            int r = rnd.nextInt( total );
            Kind pick = Kind.LOCAL_EDIT;
            for( final Map.Entry< Kind, Integer > e : w.entrySet() ) {
                if( r < e.getValue() ) {
                    pick = e.getKey();
                    break;
                }
                r -= e.getValue();
            }
            final Step s = Step.of( i, pick );
            return switch( pick ) {
                case LOCAL_EDIT -> {
                    final int c = rnd.nextInt( peers.size() );
                    yield edit( s.client( c ), peers.get( c ).sim.doc(), rnd );
                }
                case SEND_PUSH, SEND_PULL, PUBLISH -> s.client( pickClient( pick, rnd ) );
                case DELIVER, DROP, DUPLICATE -> {
                    // any message may go next (free reordering), biased towards the oldest so the backlog stays short
                    final List< String > ids = net.ids();
                    yield s.message( ids.get( Math.min( rnd.nextInt( ids.size() ), rnd.nextInt( ids.size() ) ) ) );
                }
                case ADVANCE_CLOCK -> s.millis( 1000L * ( 1 + rnd.nextInt( 30 ) ) );
                case EXTERNAL_SAVE -> edit( s, writerBody(), rnd );
                case CRASH_RECOVER, TIMEOUT -> s;
            };
        }

        /**
         * A request times out once it is lost (neither it, its answer nor a parked server-side wait is left) and older
         * than the profile's budget, or regardless after four budgets (a slow, congested network).
         */
        private String expired( final Peer p, final int i ) {
            if( overdue( p.push, p.pushAt, i, profile.pushTimeoutSteps() ) ) {
                return "push";
            }
            if( overdue( p.pull, p.pullAt, i, profile.pullTimeoutSteps() ) ) {
                return "pull";
            }
            if( overdue( p.join, p.joinAt, i, profile.pushTimeoutSteps() ) ) {
                return "join";
            }
            if( overdue( p.publish, p.publishAt, i, profile.pushTimeoutSteps() ) ) {
                return "publish";
            }
            return null;
        }

        private boolean overdue( final String request, final int sentAt, final int now, final int budget ) {
            if( request == null ) {
                return false;
            }
            final int age = now - sentAt;
            return age >= 4 * budget || age >= budget && !inFlight( request );
        }

        private boolean inFlight( final String request ) {
            return isParked( request ) || net.ids().stream().anyMatch( id -> request.equals( net.peek( id ).requestId() ) );
        }

        private boolean canSend( final Kind k, final Peer p ) {
            if( p.join != null ) {
                return false;
            }
            return switch( k ) {
                case SEND_PUSH -> p.push == null && !p.sim.sendable().isEmpty();
                case SEND_PULL -> p.pull == null;
                case PUBLISH -> p.publish == null;
                default -> false;
            };
        }

        private boolean eligible( final Kind k ) {
            return switch( k ) {
                case SEND_PUSH, SEND_PULL, PUBLISH -> peers.stream().anyMatch( p -> canSend( k, p ) );
                case DELIVER, DROP, DUPLICATE -> !net.isEmpty();
                default -> true;
            };
        }

        private int pickClient( final Kind k, final SplittableRandom rnd ) {
            final List< Integer > ok = new ArrayList<>();
            for( int c = 0; c < peers.size(); c++ ) {
                if( canSend( k, peers.get( c ) ) ) {
                    ok.add( c );
                }
            }
            return ok.get( rnd.nextInt( ok.size() ) );
        }

        private Step edit( final Step s, final String doc, final SplittableRandom rnd ) {
            final int len = doc.length();
            final boolean big = rnd.nextDouble() < profile.bigEditChance();
            final long softCap = Math.min( 2048, profile.maxPageBytes() * 3 / 4 );
            int from;
            int to;
            String ins;
            if( rnd.nextInt( 100 ) == 0 || big && rnd.nextInt( 8 ) == 0 ) {
                // E7: whole-document replace
                from = 0;
                to = len;
                ins = text( rnd, rnd.nextInt( big ? 60 : 20 ) );
            } else if( utf8( doc ) > softCap && rnd.nextInt( 4 ) != 0 ) {
                from = rnd.nextInt( len + 1 );
                to = Math.min( len, from + 1 + rnd.nextInt( 64 ) );
                ins = "";
            } else if( big ) {
                from = rnd.nextInt( len + 1 );
                to = Math.min( len, from + rnd.nextInt( 40 ) );
                ins = text( rnd, 10 + rnd.nextInt( 70 ) );
            } else {
                from = rnd.nextInt( len + 1 );
                to = rnd.nextInt( 3 ) == 0 ? Math.min( len, from + rnd.nextInt( 9 ) ) : from;
                ins = text( rnd, rnd.nextInt( 7 ) );
            }
            from = snap( doc, from );
            to = snap( doc, to );
            if( from == to && ins.isEmpty() ) {
                ins = "x";
            }
            return s.edit( from, to, ins );
        }

        private String writerBody() {
            return env.writer().latest( header.pageName() ).map( PageState::body ).map( CollabText::toLf ).orElse( "" );
        }

        // -- execution (shared by run and replay; no randomness) --

        Outcome execute( final Step s ) {
            tag = String.valueOf( s.id() );
            seq = 0;
            ordinal = s.id();
            return guarded( () -> {
                switch( s.action() ) {
                    case LOCAL_EDIT -> {
                        final Peer p = peer( s.client() );
                        if( p != null ) {
                            p.sim.localEdit( replaceIn( p.sim.doc(), s ) );
                        }
                    }
                    case SEND_PUSH -> withPeer( s, this::sendPush );
                    case SEND_PULL -> withPeer( s, this::sendPull );
                    case PUBLISH -> withPeer( s, this::sendPublish );
                    case DELIVER -> deliver( s.messageId() );
                    case DROP -> net.take( s.messageId() );
                    case DUPLICATE -> {
                        if( net.contains( s.messageId() ) ) {
                            final Message m = net.take( s.messageId() );
                            net.send( m );
                            net.send( m.withId( newId() ) );
                        }
                    }
                    case ADVANCE_CLOCK -> {
                        env.clock().advance( Duration.ofMillis( s.millis() ) );
                        server.tick();
                        drain();
                    }
                    case EXTERNAL_SAVE -> {
                        final String body = writerBody();
                        env.writer().externalSave( header.pageName(), replaceIn( body, s ).apply( body ), Map.of(), "agent" );
                    }
                    case CRASH_RECOVER -> throw new UnsupportedOperationException( "CRASH_RECOVER is wired by Task 14 (ruling R1)" );
                    case TIMEOUT -> withPeer( s, p -> timeout( p, s.op() ) );
                }
                return afterStep( s.id() % REPLAY_EVERY == 0 );
            } );
        }

        /** The step's edit against {@code doc}, clamped and snapped (a shrunk trace may have a shorter document). */
        private static ChangeSet replaceIn( final String doc, final Step s ) {
            int from = snap( doc, s.from() );
            int to = snap( doc, s.to() );
            if( to < from ) {
                final int t = from;
                from = to;
                to = t;
            }
            return ChangeSet.replace( doc.length(), from, to, s.insert() );
        }

        private Peer peer( final int c ) {
            return c >= 0 && c < peers.size() ? peers.get( c ) : null;
        }

        private void withPeer( final Step s, final java.util.function.Consumer< Peer > f ) {
            final Peer p = peer( s.client() );
            if( p != null ) {
                f.accept( p );
            }
        }

        private String newId() {
            return tag + "." + seq++;
        }

        private int indexOf( final Peer p ) {
            return peers.indexOf( p );
        }

        private void sendPush( final Peer p ) {
            if( !canSend( Kind.SEND_PUSH, p ) ) {
                return;
            }
            final String id = newId();
            net.send( new Message( id, Type.PUSH, indexOf( p ), id, p.sim.participantId(), p.sim.version(),
                p.sim.sendable(), null ) );
            p.push = id;
            p.pushAt = ordinal;
        }

        private void sendPull( final Peer p ) {
            if( !canSend( Kind.SEND_PULL, p ) ) {
                return;
            }
            final String id = newId();
            net.send( new Message( id, Type.PULL, indexOf( p ), id, p.sim.participantId(), p.sim.version(), null, null ) );
            p.pull = id;
            p.pullAt = ordinal;
        }

        private void sendPublish( final Peer p ) {
            if( !canSend( Kind.PUBLISH, p ) ) {
                return;
            }
            final String id = newId();
            net.send( new Message( id, Type.PUBLISH, indexOf( p ), id, p.sim.participantId(), p.sim.version(), null, null ) );
            p.publish = id;
            p.publishAt = ordinal;
        }

        private void startJoin( final Peer p ) {
            if( p.join != null ) {
                return;
            }
            final String id = newId();
            net.send( new Message( id, Type.JOIN, indexOf( p ), id, null, 0, null, null ) );
            p.join = id;
            p.joinAt = ordinal;
        }

        private void timeout( final Peer p, final String op ) {
            switch( op ) {
                case "push" -> {
                    if( p.push != null ) {
                        p.push = null;
                        sendPush( p );
                    }
                }
                case "pull" -> {
                    if( p.pull != null ) {
                        p.pull = null;
                        sendPull( p );
                    }
                }
                case "join" -> {
                    if( p.join != null ) {
                        p.join = null;
                        startJoin( p );
                    }
                }
                case "publish" -> p.publish = null;
                default -> throw new IllegalArgumentException( "unknown timeout op " + op );
            }
        }

        private void deliver( final String id ) {
            final Message m = net.take( id );
            if( m == null ) {
                return;
            }
            if( m.type().request() ) {
                serve( m );
            } else {
                answer( m );
            }
        }

        private void reply( final Message req, final Type type, final Object result ) {
            net.send( new Message( newId(), type, req.client(), req.requestId(), req.participantId(), req.version(), null,
                result ) );
        }

        private void serve( final Message m ) {
            final Peer p = peers.get( m.client() );
            switch( m.type() ) {
                case PUSH -> reply( m, Type.PUSH_RESULT, server.push( m.participantId(), p.authorKey, m.version(), m.updates() ) );
                case PULL -> {
                    final PullResult r = server.pull( m.participantId(), p.authorKey, m.version() );
                    if( r.parked() ) {
                        parked.computeIfAbsent( m.participantId(), k -> new ArrayDeque<>() )
                            .addLast( Map.entry( m.requestId(), m.version() ) );
                    } else {
                        reply( m, Type.PULL_RESULT, r );
                    }
                }
                case PUBLISH -> reply( m, Type.PUBLISH_RESULT, server.publish( m.participantId(), p.authorKey, m.version(), "sim" ) );
                case JOIN -> {
                    final Joined j = server.join( p.authorKey );
                    clientOf.put( j.participantId(), m.client() );
                    reply( m, Type.JOINED, j );
                }
                default -> throw new IllegalStateException( "not a request: " + m );
            }
            drain();
        }

        /** Queues every completed parked pull as the answer to the request that parked it. */
        private void drain() {
            for( final Wakeup w : server.drainWakeups() ) {
                final Deque< Map.Entry< String, Integer > > q = parked.get( w.participantId() );
                final Map.Entry< String, Integer > req = q == null ? null : q.pollFirst();
                if( req == null ) {
                    throw new AssertionError( "protocol: wakeup for " + w.participantId()
                        + " with no parked pull (F6: every pull is answered exactly once)" );
                }
                if( w.result().parked() ) {
                    throw new AssertionError( "protocol: a wakeup answer for " + w.participantId() + " is itself parked" );
                }
                final Integer c = clientOf.get( w.participantId() );
                net.send( new Message( newId(), Type.PULL_RESULT, c, req.getKey(), w.participantId(), req.getValue(), null,
                    w.result() ) );
            }
        }

        private boolean isParked( final String requestId ) {
            return parked.values().stream().flatMap( Deque::stream ).anyMatch( e -> e.getKey().equals( requestId ) );
        }

        private static AssertionError refused( final Peer p, final String what, final int status, final String reason ) {
            return new AssertionError( "protocol: client " + p.name + "'s well-formed " + what + " was answered " + status
                + " " + reason );
        }

        private void answer( final Message m ) {
            final Peer p = peers.get( m.client() );
            switch( m.type() ) {
                case PUSH_RESULT -> onPush( p, m.requestId(), (PushResult) m.result() );
                case PULL_RESULT -> onPull( p, m.requestId(), m.version(), (PullResult) m.result() );
                case PUBLISH_RESULT -> {
                    final PublishResult r = (PublishResult) m.result();
                    if( m.requestId().equals( p.publish ) ) {
                        p.publish = null;
                        if( r.status() == 403 ) {
                            throw refused( p, "publish", r.status(), r.reason() );
                        }
                    }
                }
                case JOINED -> {
                    if( m.requestId().equals( p.join ) ) {
                        p.join = null;
                        p.push = null;
                        p.pull = null;
                        p.publish = null;
                        p.repush = false;
                        p.sim.resync( (Joined) m.result() );
                    }
                }
                default -> throw new IllegalStateException( "not an answer: " + m );
            }
        }

        private void onPush( final Peer p, final String requestId, final PushResult r ) {
            p.sim.onPushResult( r );
            if( !requestId.equals( p.push ) ) {
                return; // a late or duplicated answer
            }
            p.push = null;
            switch( r.status() ) {
                case 200 -> { }
                case 409 -> {
                    if( "stale-base".equals( r.reason() ) ) {
                        p.repush = true;
                        sendPull( p );
                    } else if( "rejoin".equals( r.reason() ) ) {
                        startJoin( p );
                    } else {
                        throw refused( p, "push", r.status(), r.reason() );
                    }
                }
                case 413 -> startJoin( p );
                case 503 -> { } // store fault: retried by a later push; "poisoned" ends the run in afterStep
                default -> throw refused( p, "push", r.status(), r.reason() );
            }
        }

        /** {@code since} is the pulled-from version: the wire answer is {@code updates:[...from V]} with no version (6.2). */
        private void onPull( final Peer p, final String requestId, final int since, final PullResult r ) {
            final boolean current = requestId.equals( p.pull );
            if( current ) {
                p.pull = null;
            }
            if( r.status() == 200 ) {
                p.sim.receive( r.updates(), since );
                if( current && p.repush ) {
                    p.repush = false;
                    sendPush( p );
                }
                return;
            }
            if( !current ) {
                return;
            }
            switch( r.status() ) {
                case 409, 410 -> startJoin( p );
                case 503 -> { }
                default -> throw refused( p, "pull", r.status(), r.reason() );
            }
        }

        private Outcome afterStep( final boolean replay ) {
            final InvariantView v = server.view();
            if( v.poisoned() ) {
                return new Outcome( Status.UNSUPPORTED, "unsupported scenario: the server reports poisoned", tag );
            }
            CollabInvariants.check( v, replay );
            return null;
        }

        // -- quiescence --

        Outcome quiesce() {
            return guarded( () -> {
                for( int round = 1; ; round++ ) {
                    if( round > MAX_QUIESCENCE_ROUNDS ) {
                        throw new AssertionError( "convergence: no quiescence after " + MAX_QUIESCENCE_ROUNDS + " rounds" );
                    }
                    tag = "q" + round;
                    seq = 0;
                    while( !net.isEmpty() ) {
                        deliver( net.ids().get( 0 ) );
                        final Outcome u = afterStep( false );
                        if( u != null ) {
                            return u;
                        }
                        if( seq > 100_000 ) {
                            throw new AssertionError( "convergence: the network never drained" );
                        }
                    }
                    if( !retryAll() ) {
                        break;
                    }
                }
                final InvariantView v = server.view();
                for( final Peer p : peers ) {
                    if( !p.sim.doc().equals( v.doc() ) ) {
                        throw new AssertionError( "convergence: client " + p.name + " differs from the server "
                            + firstDifference( p.sim.doc(), v.doc() ) );
                    }
                    if( p.sim.version() != v.head() ) {
                        throw new AssertionError( "convergence: client " + p.name + " at version " + p.sim.version()
                            + " but head is " + v.head() );
                    }
                }
                CollabInvariants.check( v, true );
                tag = "end";
                return new Outcome( Status.PASS, "converged at head " + v.head() + " with doc length " + v.doc().length(), tag );
            } );
        }

        /** With the network empty, abandons lost requests and sends what each client still needs; false when nothing is left. */
        private boolean retryAll() {
            boolean progress = false;
            final int head = server.view().head();
            for( final Peer p : peers ) {
                if( p.join != null ) {
                    p.join = null;
                    startJoin( p );
                    progress = true;
                    continue;
                }
                p.push = null;
                p.publish = null;
                if( p.pull != null && !isParked( p.pull ) ) {
                    p.pull = null;
                }
                if( !p.sim.sendable().isEmpty() ) {
                    sendPush( p );
                    progress = true;
                } else if( p.sim.version() < head && p.pull == null ) {
                    sendPull( p );
                    progress = true;
                }
            }
            return progress;
        }

        // -- failure capture --

        private interface Body {
            Outcome run();
        }

        /**
         * Runs one step. An invariant or protocol {@link AssertionError} or an unexpected runtime exception becomes the
         * run's FAIL outcome (with its origin), which the caller asserts on; nothing is dropped.
         */
        private Outcome guarded( final Body b ) {
            try {
                return b.run();
            } catch( final AssertionError e ) {
                return new Outcome( Status.FAIL, String.valueOf( e.getMessage() ), tag );
            } catch( final RuntimeException e ) {
                return new Outcome( Status.FAIL, "exception: " + e + " at " + Arrays.stream( e.getStackTrace() ).limit( 4 )
                    .map( StackTraceElement::toString ).collect( Collectors.joining( " < " ) ), tag );
            }
        }

        private static String firstDifference( final String client, final String server ) {
            int i = 0;
            while( i < client.length() && i < server.length() && client.charAt( i ) == server.charAt( i ) ) {
                i++;
            }
            final int from = snap( client, Math.max( 0, i - 10 ) );
            return "at char " + i + ": client " + show( client.substring( from ) ) + " server "
                + show( server.substring( Math.min( from, server.length() ) ) ) + " (lengths " + client.length() + "/"
                + server.length() + ")";
        }

        private static String show( final String s ) {
            final String flat = s.replace( "\n", "\\n" );
            return '"' + ( flat.length() > 60 ? flat.substring( 0, snap( flat, 60 ) ) + "..." : flat ) + '"' + " ("
                + s.length() + " chars)";
        }
    }
}

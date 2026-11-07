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

import com.wikantik.api.exceptions.WikiException;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.testing.SimClient.MetaState;
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
import com.wikantik.util.TextUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The deterministic collaborative-editing simulator (F1): one seeded {@link SplittableRandom}, no threads, no sleeps,
 * no wall clock. Each step picks one action from the {@link SimProfile}'s weights; the step is recorded with every
 * parameter it needs, so a run is replayed from its {@link SimTrace} alone ({@link #replay}), not from its seed.
 *
 * <p><b>Clients</b> are {@link SimClient}s driven like {@code collabPeer} will drive the library: at most one in-flight
 * push, pull, publish, leave and join each; a 409 {@code stale-base} pulls and then re-pushes. A 409 {@code rejoin} (the
 * server dropped the participant: a {@code LEAVE} step or a crash) rejoins (section 7.2 "expired participants"),
 * offering its clientID back: when the server reissues it the client keeps its whole state and pulls on from its own
 * version ({@link SimClient#reconnect}); otherwise (a reloaded server does not know it) the unconfirmed edits are rebased
 * onto the fresh document and kept when none of them was ever sent, else the client resyncs (A10: a sent edit may
 * already be in the fresh document, so it goes to the recovery panel, never re-applied). {@code TRUNCATE} asks the
 * server for its A10 log truncation; a client the log no longer reaches gets 410 and resyncs.
 * A 410 or 413 resyncs, but first sends {@code leave} for the old participant and waits for its answer, so a straggler
 * push from the old participant cannot be accepted after its text moved to the recovery panel; the scheduler fails the
 * run if one ever is. A request unanswered for the profile's step budget times out and is retried at once, as a recorded
 * {@code TIMEOUT} step.
 * A pull answer that is not the in-flight one (late, duplicated) is still fed to {@link SimClient#receive}, which must
 * ignore what it already has (E11); any other stale answer is ignored.</p>
 *
 * <p><b>Network</b>: requests and answers are {@link SimNetwork} messages; a step may deliver, drop or duplicate any of
 * them. A parked pull's answer comes from {@link SimServer#drainWakeups()}, drained after every server call and queued as
 * an answer to the parked request. {@code ADVANCE_CLOCK} moves the {@link MutableClock}, ticks the server and drains.
 * {@code CRASH_RECOVER} (F8) discards the server and reloads one over the same store and page writer
 * ({@link Recovery}); the parked pulls die with it.</p>
 *
 * <p><b>Publish and external writes</b> (Task 4, weighted by the session's overlay only): a server whose publish
 * unlocks during the save ({@link SimServer#beginPublish}) leaves the publish pending inside the page save until a
 * {@code RELEASE_PUBLISH} step (or quiescence) completes it, so pushes, pulls and {@code EXTERNAL_SAVE}s interleave with
 * it; an {@code EXTERNAL_SAVE} writes a new page version and delivers the listener's event
 * ({@link SimServer#externalSaved}). {@code STORE_FAULT} arms a one-shot fault on a publish-time store operation and
 * {@code PERSIST_THEN_FAIL} makes the next save persist and then throw (E39). A crash with a publish inside its save
 * persists that save on even steps and drops it on odd ones. The scheduler owns {@code externalChangePending}: set by
 * {@code EXTERNAL_SAVE}, cleared when a publish, reload or reconcile completes and the server reports nothing queued or
 * owed ({@link SimServer#reconciled}); at quiescence the server settles what it owes ({@link SimServer#settle}).</p>
 *
 * <p><b>Phase 2</b> (weighted by the session's overlay only, executed only against a server with
 * {@link SimServer#lifecycle()}): {@code SELECT} moves a client's selection, which rides on its next push and pull
 * (A12); {@code META_EDIT} is one metadata edit in the client's {@link SimClient.MetaState}, sent on its next push
 * (A14); {@code RENAME} and {@code DELETE} rename or delete the page in the page writer and deliver the listener's event
 * ({@link SimServer#renamed}, {@link SimServer#deleted}); a {@code LEAVE} carries a reason ({@code op}: none is the
 * presence timeout, {@code signed-out} or {@code lost-edit} a tombstoned eviction). A client answered 403
 * {@code evicted} must have been evicted with that reason: signed out, it keeps every edit and rejoins with its clientID
 * (A13); for a lost grant its unsynced text goes to the recovery panel and its pending metadata is discarded. On 409
 * {@code frozen} (or a {@code page-deleted} event, or a frozen join) a client stops editing and pushing; once the page
 * is recreated (a {@code published} or a recreated {@code external-change} event, or at quiescence) it resyncs, as a
 * reload would. At quiescence a frozen draft is recreated by an outside save of its own text, every client keeps a pull
 * parked (carrying its last selection), and invariant 8's and 9's quiescence halves are checked; invariant 9's ADD
 * ledger is checked after every step.</p>
 *
 * <p><b>Phase 3, attribution</b> (a server with {@link SimServer#lifecycle()}): the page carries a {@code canonical_id}
 * ({@link #CANONICAL_ID}, seeded and on every outside save) so publishes are attributed. The scheduler keeps its own
 * {@link CollabInvariants.Attribution} model: an accepted push credits its author with the versions and meta seqs it
 * moved the head and the meta sequence by; a publish that takes a ticket records (V, M_V), B and S_V as the save
 * begins (cross-checked against the stored marker), and, when its save returned to the server, the version it wrote
 * (ruling D); it completes when its release answers 200, or, once its marker is gone (an unfinished publish retried,
 * the reload's pending-publish rule after a crash), when the version its save wrote is by its author, or, when the
 * server never learnt that version (the save threw, or a crash caught it inside the save or before the version was
 * stored), when version B+1 is by its author with S_V's stored text (A2's claim rule); otherwise it credits nobody. A
 * save that stored nothing new credits nobody and leaves its contributors pending (ruling R2). Invariants 10 and 11 are
 * checked after every step and at quiescence; while a publish is marked, the rows it may already have written before a
 * failed commit are accepted too.</p>
 *
 * <p><b>Coverage</b>: every run counts what it explored ({@link SimTrace#counters()}, keys below), so a test can assert
 * that a profile retune did not silently stop exercising a path.</p>
 *
 * <p><b>Checks</b>: {@link CollabInvariants} after every step (replay every {@value #REPLAY_EVERY}th); a 400 or 403
 * answer to a well-formed client request is a protocol failure. At the end, quiescence: faults stop, everything is
 * delivered in order, every client pushes and pulls until nothing is in flight and nothing is unconfirmed; then every
 * client document must equal the server's and the invariants (with replay) must hold. A server whose view reports
 * {@code poisoned} ends the run as {@link Status#UNSUPPORTED} (Task 10 ruling).</p>
 */
public final class SimScheduler {

    private static final Logger LOG = LogManager.getLogger( SimScheduler.class );

    /** The Phase 0 server under test. */
    public static final Function< SimEnv, SimServer > STUB = env -> new StubCollabAuthority( env.pageName(), env.store(),
        env.writer(), env.clock(), env.maxPageBytes(), env.ringSize() );
    /** The stub's crash recovery: a fresh stub reloaded from the environment's store and writer (F8). */
    public static final Function< SimEnv, SimServer > STUB_RELOAD = env -> StubCollabAuthority.reload( env.pageName(),
        env.store(), env.writer(), env.clock(), env.maxPageBytes(), env.ringSize() );
    /** For server factories with no recovery: a {@code CRASH_RECOVER} step fails the run. */
    static final Function< SimEnv, SimServer > NO_RELOAD = env -> {
        throw new UnsupportedOperationException( "this server factory has no reload; CRASH_RECOVER needs one" );
    };

    /** Counter: an answer delivered for a request that had already been answered (a duplicate). */
    public static final String DUPLICATE_ANSWERS = "duplicatedResponsesDelivered";
    /** Counter: a 200 pull answer that was not the client's current pull and still applied updates. */
    public static final String OUT_OF_ORDER_PULLS = "outOfOrderPullsApplied";
    /** Counter: a 200 pull answer starting below the client's version (skip &gt; 0, E11). */
    public static final String TRIMMED_PULLS = "stalePullsTrimmed";
    /** Counter: parked pulls answered empty by the long-poll timeout (a tick). */
    public static final String EXPIRED_PULLS = "parkedPullsExpired";
    /** Counter: participants dropped by a {@code LEAVE} step or a client's own leave. */
    public static final String LEAVES = "leaves";
    /** Counter: rejoins that kept (rebased) the client's edits. */
    public static final String REJOINS = "rejoins";
    /** Counter: resyncs (fresh state, unsynced text to the recovery panel). */
    public static final String RESYNCS = "resyncs";
    /** Counter: {@code CRASH_RECOVER} steps. */
    public static final String CRASHES = "crashRecovers";
    /** Counter: pushes from a participant its client had already abandoned, refused by the server. */
    public static final String RETIRED_REFUSED = "retiredPushesRefused";
    /** Counter: rejoins that got the client's own clientID back and kept its whole collab state (7.2). */
    public static final String RECONNECTS = "reconnects";
    /** Counter: {@code TRUNCATE} steps (A10). */
    public static final String TRUNCATIONS = "truncations";
    /** Counter: pulls answered 410 (below the ring and the truncated log, A10). */
    public static final String GONE_PULLS = "pulls410";
    /** Counter: publishes held inside the page save and then released ({@code RELEASE_PUBLISH}, section 7.3). */
    public static final String RELEASED_PUBLISHES = "publishesReleased";
    /** Counter: {@code EXTERNAL_SAVE} steps (section 7.4). */
    public static final String EXTERNAL_SAVES = "externalSaves";
    /** Counter: publish-time store faults armed ({@code STORE_FAULT}). */
    public static final String STORE_FAULTS = "storeFaults";
    /** Counter: saves armed to persist and then fail ({@code PERSIST_THEN_FAIL}, E39). */
    public static final String PERSIST_FAILS = "persistThenFails";
    /** Counter: publishes held inside the save when the server crashed (F8 + E39). */
    public static final String ABANDONED_PUBLISHES = "publishesAbandonedByCrash";
    /** Counter: {@code SELECT} steps a client took (A12). */
    public static final String SELECTS = "selections";
    /** Counter: {@code META_EDIT} steps a client took (A14). */
    public static final String META_EDITS = "metaEdits";
    /** Counter: pushes carrying meta edits that the server accepted. */
    public static final String META_PUSHES = "metaPushesAccepted";
    /** Counter: {@code RENAME} steps (the session rekeyed). */
    public static final String RENAMES = "renames";
    /** Counter: {@code DELETE} steps (the draft froze). */
    public static final String DELETES = "deletes";
    /** Counter: pushes answered 409 {@code frozen}. */
    public static final String FROZEN_PUSHES = "frozenPushesRefused";
    /** Counter: a frozen draft unfrozen by a recreate (a publish or an outside save). */
    public static final String UNFREEZES = "unfreezes";
    /** Counter: tombstoned evictions ({@code signed-out}, {@code lost-edit}). */
    public static final String EVICTIONS = "tombstoneEvictions";
    /** Counter: requests answered 403 {@code evicted} (the tombstone at work). */
    public static final String EVICTED_ANSWERS = "evictedAnswers";
    /** Counter: accepted ADDs entered in invariant 9's ledger. */
    public static final String LEDGER_ADDS = "addsLedgered";
    /** Counter: client selections compared with the server's at quiescence (invariant 8). */
    public static final String PRESENCE_CHECKED = "selectionsCheckedAtQuiescence";
    /** Counter: completed publishes entered in invariant 11's model (each credits its page version's rows). */
    public static final String CREDITED_PUBLISHES = "publishesCredited";
    /** Counter: completed publishes whose save was held inside the page save when the server crashed (F8). */
    public static final String CREDITED_AFTER_CRASH = "publishesCreditedAfterCrash";
    /** Counter: publishes whose marker was cleared without the session claiming the saved version (A2: no credit). */
    public static final String UNCLAIMED_PUBLISHES = "publishesUnclaimed";
    static final List< String > COUNTERS = List.of( DUPLICATE_ANSWERS, OUT_OF_ORDER_PULLS, TRIMMED_PULLS, EXPIRED_PULLS,
        LEAVES, REJOINS, RESYNCS, CRASHES, RETIRED_REFUSED, RECONNECTS, TRUNCATIONS, GONE_PULLS, RELEASED_PUBLISHES,
        EXTERNAL_SAVES, STORE_FAULTS, PERSIST_FAILS, ABANDONED_PUBLISHES, SELECTS, META_EDITS, META_PUSHES, RENAMES, DELETES,
        FROZEN_PUSHES, UNFREEZES, EVICTIONS, EVICTED_ANSWERS, LEDGER_ADDS, PRESENCE_CHECKED, CREDITED_PUBLISHES,
        CREDITED_AFTER_CRASH, UNCLAIMED_PUBLISHES );
    /** Counters only a server with Phase 2's lifecycle (the session) can move; Phase 3's attribution rides on it. */
    static final List< String > LIFECYCLE_COUNTERS = List.of( SELECTS, META_EDITS, META_PUSHES, RENAMES, DELETES,
        FROZEN_PUSHES, UNFREEZES, EVICTIONS, EVICTED_ANSWERS, LEDGER_ADDS, PRESENCE_CHECKED, CREDITED_PUBLISHES,
        CREDITED_AFTER_CRASH, UNCLAIMED_PUBLISHES );
    /**
     * The simulated page's {@code canonical_id}: seeded with the page and carried by every outside save, as the
     * structural spine keeps it on every real save, so a publish can be attributed (the ledger is keyed by it).
     */
    static final String CANONICAL_ID = "sim-page-id";
    /** The set fields and scalar fields a {@code META_EDIT} touches, and the small item vocabulary (so edits collide). */
    static final List< String > SET_FIELDS = List.of( "tags", "tags", "related", "aliases" );
    static final List< String > SCALAR_FIELDS = List.of( "status", "summary" );
    static final List< String > ITEMS = List.of( "a", "b", "c", "d", "e" );
    static final List< String > VALUES = List.of( "draft", "active", "archived", "x" );
    /** Counters only a server with external merge and pending publishes (the session's overlay) can move. */
    static final List< String > SESSION_ONLY_COUNTERS = List.of( RELEASED_PUBLISHES, EXTERNAL_SAVES, STORE_FAULTS,
        PERSIST_FAILS, ABANDONED_PUBLISHES );
    /** The store operations a publish (and an external merge) writes through: the targets of {@code STORE_FAULT}. */
    static final List< String > FAULT_OPS = List.of( InMemoryCollabStore.Op.UPDATE_SESSION.name(),
        InMemoryCollabStore.Op.APPEND_META.name(), InMemoryCollabStore.Op.COMMIT_BASE_CHANGE.name() );

    static final String PAGE = "SimPage";
    static final int REPLAY_EVERY = 25;
    static final int MAX_QUIESCENCE_ROUNDS = 400;
    private static final Instant EPOCH = Instant.parse( "2026-01-01T00:00:00Z" );
    private static final String CJK = "中文字編集協作";
    private static final String[] ASTRAL = { "😀", "𝄞", "🎉", "𝕏" };

    /**
     * A per-server-factory weight overlay: an action named here takes this weight instead of the profile's, so a server
     * can be fuzzed with actions the other server does not take without changing the other server's generated traces
     * (the stub's committed corpus). Replay ignores it: a trace carries its steps.
     */
    public record Overlay( Map< Kind, Integer > weights ) {
        /** The profile's weights unchanged (the stub, and the trace corpus). */
        public static final Overlay NONE = new Overlay( Map.of() );

        public Overlay {
            weights = Map.copyOf( weights );
        }

        int weight( final SimProfile p, final Kind k ) {
            return weights.getOrDefault( k, p.weight( k ) );
        }
    }

    private final long seed;
    private final SimProfile profile;
    private final Function< SimEnv, SimServer > serverFactory;
    private final Function< SimEnv, SimServer > reload;
    private final Overlay overlay;
    private final int clients;

    /** {@link #STUB} and {@link CollabSessionSimServer#SESSION} get their reloads for crash steps; any other factory none. */
    public SimScheduler( final long seed, final SimProfile profile, final Function< SimEnv, SimServer > serverFactory,
                         final int clients ) {
        this( seed, profile, serverFactory, defaultReload( serverFactory ), clients );
    }

    public SimScheduler( final long seed, final SimProfile profile, final Function< SimEnv, SimServer > serverFactory,
                         final Function< SimEnv, SimServer > reload, final int clients ) {
        this( seed, profile, serverFactory, reload, Overlay.NONE, clients );
    }

    public SimScheduler( final long seed, final SimProfile profile, final Function< SimEnv, SimServer > serverFactory,
                         final Function< SimEnv, SimServer > reload, final Overlay overlay, final int clients ) {
        if( clients < 1 || clients > 26 ) {
            throw new IllegalArgumentException( "clients must be 1..26: " + clients );
        }
        this.seed = seed;
        this.profile = Objects.requireNonNull( profile );
        this.serverFactory = Objects.requireNonNull( serverFactory );
        this.reload = Objects.requireNonNull( reload );
        this.overlay = Objects.requireNonNull( overlay );
        this.clients = clients;
    }

    static Function< SimEnv, SimServer > defaultReload( final Function< SimEnv, SimServer > factory ) {
        if( factory == STUB ) {
            return STUB_RELOAD;
        }
        return factory == CollabSessionSimServer.SESSION ? CollabSessionSimServer.SESSION_RELOAD : NO_RELOAD;
    }

    /** Generates and executes up to {@code steps} steps (fewer if one fails), then drives the run to quiescence. */
    public SimTrace run( final int steps ) {
        final SplittableRandom rnd = new SplittableRandom( seed );
        final String initialDoc = rnd.nextInt( 10 ) == 0 ? "" : text( rnd, rnd.nextInt( 120 ) );
        final SimTrace header = new SimTrace( seed, profile, PAGE, profile.maxPageBytes(), profile.ringSize(),
            profile.rewrite(), clients, initialDoc, List.of(), Outcome.NOT_RUN, List.of() );
        final World w = new World( header, serverFactory, reload );
        w.overlay = overlay;
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
        return header.withSteps( done ).withResult( o, w.clientTraces(), w.counters() );
    }

    /** Executes exactly the trace's steps against a fresh server (no randomness), then quiescence. */
    public static SimTrace replay( final SimTrace trace, final Function< SimEnv, SimServer > serverFactory ) {
        return replay( trace, serverFactory, defaultReload( serverFactory ) );
    }

    public static SimTrace replay( final SimTrace trace, final Function< SimEnv, SimServer > serverFactory,
                                   final Function< SimEnv, SimServer > reload ) {
        final World w = new World( trace, serverFactory, reload );
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
        return trace.withSteps( trace.steps() ).withResult( o, w.clientTraces(), w.counters() );
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

    /** A scheduler-detected failure with a precise {@link Outcome#kind} for the shrinker. */
    static final class SimFailure extends AssertionError {
        private static final long serialVersionUID = 1L;
        final String kind;

        SimFailure( final String kind, final String message ) {
            super( message );
            this.kind = kind;
        }
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
        String leave;
        int pushAt;
        int pullAt;
        int joinAt;
        int publishAt;
        int leaveAt;
        boolean repush;
        /** The pending join must resync (recovery panel) rather than rejoin with the edits rebased. */
        boolean resync;
        /** How many of the leading unconfirmed updates have been sent in some push (and so may have been accepted). */
        int sent;
        /** Participants this client abandoned by rejoining or resyncing: none may ever have a push accepted again. */
        final Set< String > retired = new HashSet<>();
        /** The meta edits on the current push (given back to the meta model on its answer). */
        List< MetaState.Entry > pushMeta = List.of();
        /** The selection the current push carries, and the last one a push delivered (null after a (re)join). */
        Selection pushSelection;
        Selection sentSelection;
        /** The meta and event cursors the current pull was sent with. */
        long pullMetaSince;
        long pullEventsSince;

        Peer( final String name, final String authorKey ) {
            this.name = name;
            this.authorKey = authorKey;
        }
    }

    /** A publish the server holds inside its page save, and the request it answers. */
    private record PendingPublish( Message request, Object handle ) {
    }

    /**
     * A publish the server took a ticket for, as the scheduler observed it when the save began: the publish point
     * (V, M_V), the base B it saves on (0 for a recreate), its author and S_V. {@code crashed} once a crash caught it
     * inside its save (F8). {@code saved}: the new version its save wrote, once the save returned it to the server
     * (null while it has not, after a save that threw, or when a crash lost the stored copy).
     */
    private static final class Ticket {
        final int version;
        final long metaSeq;
        final int base;
        final String author;
        final String doc;
        boolean crashed;
        Integer saved;

        Ticket( final int version, final long metaSeq, final int base, final String author, final String doc ) {
            this.version = version;
            this.metaSeq = metaSeq;
            this.base = base;
            this.author = author;
            this.doc = doc;
        }
    }

    /** The simulated page's metadata as seeded and as every outside save writes it: its {@code canonical_id} alone. */
    static Map< String, Object > pageMeta() {
        return Map.of( "canonical_id", CANONICAL_ID );
    }

    /** What a push carries besides its text (in {@link Message#result()} of the request): meta edits and the selection. */
    private record PushExtras( List< MetaState.Entry > meta, Selection selection ) {
    }

    /** What a pull carries besides its version: all four cursors and the selection. */
    private record PullExtras( PullCursor cursor, Selection selection ) {
    }

    private static final class World {
        private final SimTrace header;
        private final SimProfile profile;
        private final Function< SimEnv, SimServer > factory;
        private final Function< SimEnv, SimServer > reload;
        private final SimNetwork net = new SimNetwork();
        private final Map< String, Long > counters = new LinkedHashMap<>();
        /** Request ids whose answer has been delivered at least once. */
        private final Set< String > answered = new HashSet<>();
        private final List< Peer > peers = new ArrayList<>();
        private final Map< String, Integer > clientOf = new LinkedHashMap<>();
        /** Per participant, the pulls the server holds, oldest first: {@code requestId -> since}. */
        private final Map< String, Deque< Map.Entry< String, Integer > > > parked = new LinkedHashMap<>();
        private Overlay overlay = Overlay.NONE;
        /** Publishes inside the save, oldest first; released by {@code RELEASE_PUBLISH} or at quiescence. */
        private final Deque< PendingPublish > pendingPublishes = new ArrayDeque<>();
        /**
         * Owned by the scheduler (plan amendment, Task 4): set by {@code EXTERNAL_SAVE}, cleared when a publish, a reload
         * or a reconcile completes and the server reports nothing queued or owed. It explains invariant 4's gap.
         */
        private boolean externalPending;
        private SimEnv env;
        private SimServer server;
        private UUID sessionId;
        /** Participant id to the reason it was evicted with (a tombstone the server should answer 403 for). */
        private final Map< String, String > evictedAs = new LinkedHashMap<>();
        /** Invariant 9's ledger: field to the items an accepted ADD put there that nothing later took out. */
        private final Map< String, Set< String > > mustHold = new TreeMap<>();
        /** The meta sequence the ledger has accounted for. */
        private long metaCursor;
        private boolean wasFrozen;
        /**
         * The listener's rename or delete event the server refused (a store fault): the registry retries it on its tick
         * (Task 3/5: refused rekeys retry; a sweep freezes a draft whose page is gone), so the scheduler does on
         * {@code ADVANCE_CLOCK} and at quiescence. Until it lands it explains invariant 4's gap.
         */
        private String owedRename;
        private boolean owedFreeze;
        /**
         * Invariants 10 and 11's model (Phase 3), kept for a server with {@link SimServer#lifecycle()} only: every
         * accepted update and meta op with its pusher's author, and every completed publish with its publish point.
         */
        private CollabInvariants.Attribution attribution;
        /** The publish whose marker the server holds (inside its save, or unfinished and owed); null when none. */
        private Ticket ticket;
        private String tag = "start";
        private int seq;
        private int ordinal;

        World( final SimTrace header, final Function< SimEnv, SimServer > factory, final Function< SimEnv, SimServer > reload ) {
            this.header = header;
            this.profile = header.profile();
            this.factory = factory;
            this.reload = reload;
            COUNTERS.forEach( c -> counters.put( c, 0L ) );
        }

        Map< String, Long > counters() {
            return counters;
        }

        private void count( final String counter, final long n ) {
            counters.merge( counter, n, Long::sum );
        }

        List< SimTrace.ClientTrace > clientTraces() {
            return peers.stream().filter( p -> p.sim != null )
                .map( p -> new SimTrace.ClientTrace( p.name, p.authorKey, p.sim.trace() ) ).toList();
        }

        Outcome start() {
            return guarded( () -> {
                final ScriptedPageWriter writer = new ScriptedPageWriter();
                writer.seed( header.pageName(), header.initialDoc(), pageMeta() );
                writer.rewriteOnSave( SimEnv.rewrite( header.rewrite() ) );
                env = new SimEnv( header.pageName(), new MutableClock( EPOCH ), new InMemoryCollabStore(), writer,
                    header.maxPageBytes(), header.ringSize() );
                server = factory.apply( env );
                attribution = server.lifecycle() ? new CollabInvariants.Attribution() : null;
                for( int i = 0; i < header.clients(); i++ ) {
                    final String name = String.valueOf( (char) ( 'A' + i ) );
                    final Peer p = new Peer( name, "author-" + name );
                    final Joined j = server.join( p.authorKey );
                    clientOf.put( j.participantId(), i );
                    p.sim = new SimClient( name, j );
                    peers.add( p );
                }
                sessionId = server.view().sessionId();
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
                    w.put( k, overlay.weight( profile, k ) );
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
                case LEAVE -> {
                    final Step leave = s.client( pickClient( pick, rnd ) );
                    if( !server.lifecycle() ) {
                        yield leave;
                    }
                    // a timeout (no tombstone), a sign-out (A13) or a revoked grant (A5)
                    final int why = rnd.nextInt( 3 );
                    yield why == 0 ? leave : leave.op( why == 1 ? CollabSession.SIGNED_OUT : CollabSession.LOST_EDIT );
                }
                case SELECT -> {
                    final int c = pickClient( pick, rnd );
                    final String doc = peers.get( c ).sim.doc();
                    final int anchor = snap( doc, rnd.nextInt( doc.length() + 1 ) );
                    final int head = rnd.nextInt( 3 ) == 0 ? anchor : snap( doc, rnd.nextInt( doc.length() + 1 ) );
                    yield s.client( c ).edit( anchor, head, null );
                }
                case META_EDIT -> metaEdit( s.client( pickClient( pick, rnd ) ), rnd );
                case DELIVER, DROP, DUPLICATE -> {
                    // any message may go next (free reordering), biased towards the oldest so the backlog stays short
                    final List< String > ids = net.ids();
                    yield s.message( ids.get( Math.min( rnd.nextInt( ids.size() ), rnd.nextInt( ids.size() ) ) ) );
                }
                case ADVANCE_CLOCK -> s.millis( 1000L * ( 1 + rnd.nextInt( 30 ) ) );
                case EXTERNAL_SAVE -> edit( s, writerBody(), rnd );
                case STORE_FAULT -> s.op( FAULT_OPS.get( rnd.nextInt( FAULT_OPS.size() ) ) );
                case CRASH_RECOVER, TRUNCATE, RELEASE_PUBLISH, PERSIST_THEN_FAIL, RENAME, DELETE, TIMEOUT -> s;
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
            if( overdue( p.leave, p.leaveAt, i, profile.pushTimeoutSteps() ) ) {
                return "leave";
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
            return isParked( request ) || net.ids().stream().anyMatch( id -> request.equals( net.peek( id ).requestId() ) )
                || pendingPublishes.stream().anyMatch( pp -> request.equals( pp.request().requestId() ) );
        }

        private boolean canSend( final Kind k, final Peer p ) {
            if( p.join != null || p.leave != null ) {
                return false;
            }
            return switch( k ) {
                case SEND_PUSH -> p.push == null && hasSomethingToPush( p );
                case SEND_PULL -> p.pull == null;
                case PUBLISH -> p.publish == null;
                case LEAVE, SELECT -> true;
                case META_EDIT -> !p.sim.frozen();
                default -> false;
            };
        }

        private boolean eligible( final Kind k ) {
            return switch( k ) {
                case SEND_PUSH, SEND_PULL, PUBLISH, LEAVE, SELECT, META_EDIT -> peers.stream().anyMatch( p -> canSend( k, p ) );
                case DELIVER, DROP, DUPLICATE -> !net.isEmpty();
                case RELEASE_PUBLISH -> !pendingPublishes.isEmpty();
                case RENAME, DELETE -> renameOrDeletePossible();
                default -> true;
            };
        }

        /**
         * Unconfirmed text, unsent meta edits or a selection no push has delivered, and not frozen (a frozen draft refuses
         * every push). The selection clause is the real client's: it pushes a selection-only change
         * ({@code collabPeer.js} {@code pushNow}: anchor and head compared with the last selection a push delivered).
         */
        private static boolean hasSomethingToPush( final Peer p ) {
            return !p.sim.frozen() && ( !p.sim.sendable().isEmpty() || p.sim.meta().hasUnsent() || selectionUnsent( p ) );
        }

        private static boolean selectionUnsent( final Peer p ) {
            final Selection s = p.sim.selection();
            return s != null && ( p.sentSelection == null || s.anchor() != p.sentSelection.anchor()
                || s.head() != p.sentSelection.head() );
        }

        private boolean pageExists() {
            return env.writer().latest( env.pageName() ).isPresent();
        }

        /** The page exists, the draft is not frozen, and no earlier rename or delete event is still owed. */
        private boolean renameOrDeletePossible() {
            return owedRename == null && !owedFreeze && pageExists() && !server.frozen();
        }

        /** Delivers an owed rename or delete event; a refusal (logged) leaves it owed for the next tick. */
        private void retryListenerEvents() {
            try {
                if( owedRename != null ) {
                    server.renamed( owedRename );
                    owedRename = null;
                    server.settle();   // the listener reconciles the renamed session by content hash (ruling Q1)
                }
                if( owedFreeze ) {
                    // like the registry's sweep: only a draft whose page is (still) gone is frozen
                    if( !pageExists() ) {
                        server.deleted();
                    }
                    owedFreeze = false;
                }
            } catch( final RuntimeException e ) {
                LOG.warn( "sim: the {} event was refused; retried on the next tick: {}", owedRename != null ? "rename"
                    : "delete", e.toString() );
            }
            drain();
            settled();
        }

        /**
         * One meta edit: on a set field (biased to {@code tags}) an ADD, a REMOVE, a SET of a list or a SET null; on a
         * scalar field a SET or a SET null. Recorded as {@code op} {@code "<kind>:<field>"} and {@code insert} (the item,
         * the value, or the list's items joined by commas).
         */
        private Step metaEdit( final Step s, final SplittableRandom rnd ) {
            if( rnd.nextInt( 3 ) != 0 ) {
                final String field = SET_FIELDS.get( rnd.nextInt( SET_FIELDS.size() ) );
                final int k = rnd.nextInt( 10 );
                if( k < 5 ) {
                    return s.op( "add:" + field ).edit( -1, -1, ITEMS.get( rnd.nextInt( ITEMS.size() ) ) );
                }
                if( k < 8 ) {
                    return s.op( "remove:" + field ).edit( -1, -1, ITEMS.get( rnd.nextInt( ITEMS.size() ) ) );
                }
                if( k < 9 ) {
                    final List< String > items = new ArrayList<>();
                    for( final String item : ITEMS ) {
                        if( rnd.nextInt( 3 ) == 0 ) {
                            items.add( item );
                        }
                    }
                    return s.op( "set:" + field ).edit( -1, -1, String.join( ",", items ) );
                }
                return s.op( "unset:" + field );
            }
            final String field = SCALAR_FIELDS.get( rnd.nextInt( SCALAR_FIELDS.size() ) );
            return rnd.nextInt( 5 ) == 0 ? s.op( "unset:" + field )
                : s.op( "set:" + field ).edit( -1, -1, VALUES.get( rnd.nextInt( VALUES.size() ) ) );
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
            return env.writer().latest( env.pageName() ).map( PageState::body ).map( CollabText::toLf ).orElse( "" );
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
                        if( p != null && !p.sim.frozen() ) {
                            p.sim.localEdit( replaceIn( p.sim.doc(), s ) );
                        }
                    }
                    case SELECT -> withPeer( s, p -> {
                        if( p.join == null && p.leave == null ) {
                            p.sim.select( snap( p.sim.doc(), s.from() ), snap( p.sim.doc(), s.to() ) );
                            count( SELECTS, 1 );
                        }
                    } );
                    case META_EDIT -> withPeer( s, p -> {
                        if( p.join == null && p.leave == null && !p.sim.frozen() ) {
                            applyMetaStep( p.sim.meta(), s );
                            count( META_EDITS, 1 );
                        }
                    } );
                    case RENAME -> {
                        if( renameOrDeletePossible() ) {
                            final String to = header.pageName() + "-" + s.id();
                            env.writer().rename( env.pageName(), to );
                            env = new SimEnv( to, env.clock(), env.store(), env.writer(), env.maxPageBytes(), env.ringSize() );
                            owedRename = to;
                            externalPending = true;
                            count( RENAMES, 1 );
                            retryListenerEvents();
                        }
                    }
                    case DELETE -> {
                        if( renameOrDeletePossible() ) {
                            env.writer().delete( env.pageName() );
                            owedFreeze = true;
                            externalPending = true;
                            count( DELETES, 1 );
                            retryListenerEvents();
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
                        count( EXPIRED_PULLS, drain() );
                        retryListenerEvents();
                    }
                    case EXTERNAL_SAVE -> {
                        final String body = writerBody();
                        final int version = env.writer().externalSave( env.pageName(), replaceIn( body, s ).apply( body ),
                            pageMeta(), "agent" );
                        externalPending = true;
                        count( EXTERNAL_SAVES, 1 );
                        server.externalSaved( version );
                        drain();
                        settled();
                    }
                    case CRASH_RECOVER -> {
                        forgetUnstoredSavedVersion();
                        // F8: the server and everything it held in memory (participants, parked pulls, publishes inside
                        // their save) is gone; a held save committed before the crash on even steps, never ran on odd ones
                        for( final PendingPublish pp : pendingPublishes ) {
                            server.abandonPublish( pp.handle(), s.id() % 2 == 0 );
                            count( ABANDONED_PUBLISHES, 1 );
                            if( ticket != null ) {
                                ticket.crashed = true;   // the reload's pending-publish rule claims or clears it
                            }
                        }
                        pendingPublishes.clear();
                        // reloaded under the name the store holds (a refused rekey leaves the old one there)
                        final SimEnv at = new SimEnv( env.store().session( sessionId ).map( r -> r.pageName() )
                            .orElse( env.pageName() ), env.clock(), env.store(), env.writer(), env.maxPageBytes(),
                            env.ringSize() );
                        server = Recovery.recover( env.store(), env.writer(), ( st, wr ) -> reload.apply( at ) );
                        parked.clear();
                        count( CRASHES, 1 );
                        settled();
                    }
                    case RELEASE_PUBLISH -> releasePublish();
                    case STORE_FAULT -> {
                        env.store().failOn( InMemoryCollabStore.Op.valueOf( s.op() ), 1 );
                        count( STORE_FAULTS, 1 );
                    }
                    case PERSIST_THEN_FAIL -> {
                        env.writer().persistThenFail( new WikiException( "injected: the process died after the page store"
                            + " committed the save" ) );
                        count( PERSIST_FAILS, 1 );
                    }
                    case LEAVE -> withPeer( s, p -> {
                        // the server drops the participant (presence timeout, or a tombstoned eviction for a reason)
                        // without telling the client
                        if( p.join == null && p.leave == null ) {
                            if( s.op() == null ) {
                                server.evict( p.sim.participantId() );
                                count( LEAVES, 1 );
                            } else if( server.view().editGrants().containsKey( p.sim.participantId() ) ) {
                                // only a live participant is evicted (and tombstoned); an unknown one is ignored
                                evictedAs.put( p.sim.participantId(), s.op() );
                                server.evict( p.sim.participantId(), s.op() );
                                count( EVICTIONS, 1 );
                            }
                            drain();
                        }
                    } );
                    case TRUNCATE -> {
                        server.truncate();
                        count( TRUNCATIONS, 1 );
                        drain();
                    }
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

        /** A {@code META_EDIT} step applied to the client's meta model. */
        private static void applyMetaStep( final MetaState meta, final Step s ) {
            final int colon = s.op().indexOf( ':' );
            final String kind = s.op().substring( 0, colon );
            final String field = s.op().substring( colon + 1 );
            switch( kind ) {
                case "add" -> meta.add( field, s.insert() );
                case "remove" -> meta.remove( field, s.insert() );
                case "set" -> meta.edit( field, SET_FIELDS.contains( field )
                    ? ( s.insert().isEmpty() ? List.of() : List.of( s.insert().split( "," ) ) ) : s.insert() );
                case "unset" -> meta.edit( field, null );
                default -> throw new IllegalArgumentException( "unknown meta edit " + s.op() );
            }
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
            p.pushMeta = p.sim.meta().takeSendable();
            p.pushSelection = p.sim.selection();
            net.send( new Message( id, Type.PUSH, indexOf( p ), id, p.sim.participantId(), p.sim.version(),
                p.sim.sendable(), new PushExtras( p.pushMeta, p.pushSelection ) ) );
            p.sent = p.sim.sendable().size();
            p.push = id;
            p.pushAt = ordinal;
        }

        private void sendPull( final Peer p ) {
            if( !canSend( Kind.SEND_PULL, p ) ) {
                return;
            }
            final String id = newId();
            p.pullMetaSince = p.sim.meta().serverSeq();
            p.pullEventsSince = p.sim.eventSeq();
            final PullCursor cursor = new PullCursor( p.sim.version(), p.pullMetaSince, Long.MAX_VALUE, p.pullEventsSince );
            net.send( new Message( id, Type.PULL, indexOf( p ), id, p.sim.participantId(), p.sim.version(), null,
                new PullExtras( cursor, p.sim.selection() ) ) );
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

        /** 409 {@code rejoin}: the server already dropped the participant, so join at once (7.2 expired participants). */
        private void startRejoin( final Peer p ) {
            if( p.join != null || p.leave != null ) {
                return;
            }
            p.resync = false;
            startJoin( p );
        }

        /**
         * 410 / 413: resync. The old participant is still known to the server, so leave it first and join only once the
         * leave is answered: a straggler push from it can then never be accepted after the text moved to recovery (A10).
         */
        private void startResync( final Peer p ) {
            p.resync = true;
            if( p.join != null || p.leave != null ) {
                return; // a rejoin in progress (its participant is already gone) becomes a resync
            }
            sendLeave( p );
        }

        private void sendLeave( final Peer p ) {
            final String id = newId();
            net.send( new Message( id, Type.LEAVE, indexOf( p ), id, p.sim.participantId(), 0, null, null ) );
            p.leave = id;
            p.leaveAt = ordinal;
        }

        /**
         * A rejoin offers the client's clientID back (7.2 "same clientID"); a resync does not (its unsynced text went to
         * the recovery panel, so it starts afresh).
         */
        private void startJoin( final Peer p ) {
            if( p.join != null ) {
                return;
            }
            final String id = newId();
            net.send( new Message( id, Type.JOIN, indexOf( p ), id, null, 0, null, p.resync ? null : p.sim.clientId() ) );
            p.join = id;
            p.joinAt = ordinal;
        }

        private void timeout( final Peer p, final String op ) {
            switch( op ) {
                case "push" -> {
                    if( p.push != null ) {
                        abandonPush( p );
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
                case "leave" -> {
                    if( p.leave != null ) {
                        p.leave = null;
                        sendLeave( p );
                    }
                }
                default -> throw new IllegalArgumentException( "unknown timeout op " + op );
            }
        }

        /** The current push is given up on: its meta edits go back to unsent (they are idempotent, so resending is safe). */
        private static void abandonPush( final Peer p ) {
            p.push = null;
            p.sim.meta().failed( p.pushMeta );
            p.pushMeta = List.of();
        }

        private void deliver( final String id ) {
            final Message m = net.take( id );
            if( m == null ) {
                return;
            }
            if( m.type().request() ) {
                serve( m );
            } else {
                if( !answered.add( m.requestId() ) ) {
                    count( DUPLICATE_ANSWERS, 1 );
                }
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
                case PUSH -> {
                    final PushExtras x = (PushExtras) m.result();
                    final List< MetaEdit > edits = x.meta().stream().map( MetaState.Entry::edit ).toList();
                    accountServerOps();
                    final InvariantView before = attribution == null ? null : server.view();
                    final PushResult r = server.push( m.participantId(), p.authorKey, m.version(), m.updates(), edits,
                        x.selection() );
                    if( before != null && r.status() == 200 ) {
                        attribute( p.authorKey, before );
                    }
                    if( r.status() == 200 && !edits.isEmpty() ) {
                        ledger( x.meta() );
                        count( META_PUSHES, 1 );
                    }
                    if( p.retired.contains( m.participantId() ) ) {
                        if( r.status() == 200 && ( !m.updates().isEmpty() || !edits.isEmpty() ) ) {
                            throw new SimFailure( "protocol/straggler", "protocol: a straggler push from client " + p.name
                                + "'s abandoned participant " + m.participantId() + " was accepted after the client moved on"
                                + " (its text may be in the recovery panel: A10 never re-apply)" );
                        }
                        count( RETIRED_REFUSED, 1 );
                    }
                    reply( m, Type.PUSH_RESULT, r );
                }
                case PULL -> {
                    final PullExtras x = (PullExtras) m.result();
                    final PullResult r = server.pull( m.participantId(), p.authorKey, x.cursor(), x.selection() );
                    if( r.parked() ) {
                        parked.computeIfAbsent( m.participantId(), k -> new ArrayDeque<>() )
                            .addLast( Map.entry( m.requestId(), m.version() ) );
                    } else {
                        reply( m, Type.PULL_RESULT, r );
                    }
                }
                case PUBLISH -> {
                    final SimServer.PublishStart started = server.beginPublish( m.participantId(), p.authorKey, m.version(),
                        "sim" );
                    if( started.pending() != null ) {
                        pendingPublishes.addLast( new PendingPublish( m, started.pending() ) );
                        if( attribution != null ) {
                            // one ticket at a time: a new one means this call settled the previous marker first
                            if( ticket != null ) {
                                resolveByClaim();
                            }
                            ticket = newTicket( p.authorKey );
                        }
                    } else {
                        reply( m, Type.PUBLISH_RESULT, started.result() );
                        settled();
                    }
                }
                case JOIN -> {
                    final Joined j = server.join( p.authorKey, (String) m.result(), null, null );
                    clientOf.put( j.participantId(), m.client() );
                    reply( m, Type.JOINED, j );
                }
                case LEAVE -> {
                    server.leave( m.participantId(), p.authorKey );
                    count( LEAVES, 1 );
                    reply( m, Type.LEFT, null );
                }
                default -> throw new IllegalStateException( "not a request: " + m );
            }
            drain();
        }

        /**
         * Invariant 9's ledger, for a push the server accepted: in order, an ADD puts its item in the ledger, a REMOVE of
         * that item or a SET of the field without it takes it out. By intent, not by what went on the wire. The ops the
         * push produced are then accounted for (they are its own, not server ops).
         */
        private void ledger( final List< MetaState.Entry > entries ) {
            for( final MetaState.Entry e : entries ) {
                final MetaEdit i = e.intent();
                switch( i.kind() ) {
                    case ADD -> {
                        mustHold.computeIfAbsent( i.field(), f -> new TreeSet<>() ).add( (String) i.value() );
                        count( LEDGER_ADDS, 1 );
                    }
                    case REMOVE -> mustHold.getOrDefault( i.field(), new TreeSet<>() ).remove( (String) i.value() );
                    case SET -> mustHold.getOrDefault( i.field(), new TreeSet<>() )
                        .retainAll( CollabInvariants.items( i.value() ) );
                }
            }
            for( final StoredMetaOp op : env.store().metaOpsSince( sessionId, metaCursor ) ) {
                metaCursor = Math.max( metaCursor, op.seq() );
            }
        }

        /**
         * The meta ops the server wrote by itself since the ledger last looked (an external merge, a publish fold): each
         * one is a SET of its field, so it takes out the items its value lacks.
         */
        private void accountServerOps() {
            if( sessionId == null ) {
                return;
            }
            for( final StoredMetaOp op : env.store().metaOpsSince( sessionId, metaCursor ) ) {
                final Set< String > held = mustHold.get( op.field() );
                if( held != null ) {
                    held.retainAll( CollabInvariants.items( op.value() ) );
                }
                metaCursor = Math.max( metaCursor, op.seq() );
            }
        }

        /** Completes the oldest publish held inside its save and answers its request. */
        private void releasePublish() {
            final PendingPublish pp = pendingPublishes.pollFirst();
            if( pp == null ) {
                return;
            }
            env.writer().takeLastReturned();
            final PublishResult r = server.releasePublish( pp.handle() );
            count( RELEASED_PUBLISHES, 1 );
            final PublishOutcome returned = env.writer().takeLastReturned();
            if( attribution != null && ticket != null ) {
                learnSavedVersion( ticket, returned );
            }
            if( attribution != null && ticket != null && r.status() == 200 ) {
                // the save returned and the post-save half finished: the publish is complete at the answered version
                complete( ticket, r.pageVersion() );
            }
            reply( pp.request(), Type.PUBLISH_RESULT, r );
            drain();
            settled();
        }

        // -- attribution (Phase 3: invariants 10 and 11) --

        /**
         * A push the server accepted, against the view {@code before} it: the versions it moved the head by are its text
         * updates and the seqs it moved the meta sequence by its meta ops, all by its author (the simulator is single
         * threaded, so nothing else wrote in between).
         */
        private void attribute( final String author, final InvariantView before ) {
            final InvariantView after = server.view();
            for( int v = before.head() + 1; v <= after.head(); v++ ) {
                attribution.text( author, v );
            }
            for( long m = before.metaSeq() + 1; m <= after.metaSeq(); m++ ) {
                attribution.meta( author, m );
            }
        }

        /**
         * The ticket a publish just took: (V, M_V) and S_V are the head, meta sequence and document now (the save is
         * paused, so nothing has moved since the ticket), B is the base, or 0 for a frozen draft's recreate. The store's
         * marker must name the same V and B.
         */
        private Ticket newTicket( final String author ) {
            final InvariantView v = server.view();
            final Ticket t = new Ticket( v.head(), v.metaSeq(), v.frozen() ? 0 : v.basePageVersion(), author, v.doc() );
            final SessionRecord rec = env.store().session( sessionId ).orElseThrow();
            if( !Objects.equals( rec.pendingPublishVersion(), t.version )
                    || !Objects.equals( rec.pendingPublishBaseVersion(), t.base ) ) {
                throw new SimFailure( "attribution/marker", "attribution: the publish by " + author + " took its ticket at"
                    + " version " + t.version + " on base " + t.base + " but the stored marker names version "
                    + rec.pendingPublishVersion() + " on base " + rec.pendingPublishBaseVersion() );
            }
            return t;
        }

        /**
         * With no save of it still running, a ticket whose marker the store no longer holds was settled by the server
         * (an unfinished publish retried, the reload's pending-publish rule): resolved by the claim rule.
         */
        private void resolveIfSettled() {
            if( ticket == null || !pendingPublishes.isEmpty() ) {
                return;
            }
            final SessionRecord rec = env.store().session( sessionId ).orElseThrow();
            if( !Objects.equals( rec.pendingPublishVersion(), ticket.version )
                    || !Objects.equals( rec.pendingPublishBaseVersion(), ticket.base ) ) {
                resolveByClaim();
            }
        }

        /**
         * A2 / E39 as amended by ruling D: a settled marker was a completed publish when the version its save wrote (as
         * the server learnt it) is by the ticket's author, or, when the server never learnt that version, when version
         * B+1 is by the ticket's author with S_V's text as the save stores it; otherwise (the page moved on, or the save
         * never ran) the publish credits nobody, and any rows it wrote early must be gone.
         */
        private void resolveByClaim() {
            final Ticket t = ticket;
            if( t.saved != null ? byAuthor( t, t.saved ) : claimedByHash( t ) ) {
                complete( t, t.saved != null ? t.saved : t.base + 1 );
                return;
            }
            count( UNCLAIMED_PUBLISHES, 1 );
            ticket = null;
            if( t.saved != null ) {
                // the server knew the version the save wrote: rows it credited there (or held from an earlier page at
                // that number) are removed, and a recreate's save restarted the numbering, so every earlier row goes
                attribution.unclaimed( t.saved, t.base == 0 );
            }
        }

        /** Version {@code at} of the session's page is by the ticket's author. */
        private boolean byAuthor( final Ticket t, final int at ) {
            final Optional< PageState > v = env.writer().version( server.view().pageName(), at );
            return v.isPresent() && t.author.equals( v.get().author() );
        }

        /** A2: version B+1 of the session's page is by the ticket's author with S_V's text as the save stores it. */
        private boolean claimedByHash( final Ticket t ) {
            final Optional< PageState > next = env.writer().version( server.view().pageName(), t.base + 1 );
            return next.isPresent() && t.author.equals( next.get().author() )
                && next.get().contentHash().equals( CollabText.contentHash( TextUtil.normalizePostData( t.doc ) ) );
        }

        /**
         * What the server learnt, as its release finished, of the version its save wrote (ruling D): the version a save
         * returned (none for a save that stored nothing new); or, for a save that threw after persisting, version B+1 when
         * A2's rule claimed it at once (the server then finished it from that version and stored it on the marker).
         */
        private void learnSavedVersion( final Ticket t, final PublishOutcome returned ) {
            if( returned != null ) {
                if( !returned.unchanged() ) {
                    t.saved = returned.version();
                }
            } else if( claimedByHash( t ) ) {
                t.saved = t.base + 1;
            }
        }

        /**
         * A crash keeps only what the store holds: a saved version the server learnt but could not store on the marker
         * (a store fault) is lost with its memory, and the reload claims by A2's content-hash rule instead.
         */
        private void forgetUnstoredSavedVersion() {
            if( attribution == null || ticket == null || ticket.saved == null ) {
                return;
            }
            final Integer stored = env.store().session( sessionId ).map( SessionRecord::pendingPublishSavedVersion )
                .orElse( null );
            if( !Objects.equals( stored, ticket.saved ) ) {
                ticket.saved = null;
            }
        }

        /**
         * The model as it would be with {@code t}'s rows written but the publish not completed, at the version its save
         * wrote (B+1 when the scheduler has not seen it): a marked publish writes them before its commit.
         */
        private CollabInvariants.Attribution inFlight( final Ticket t ) {
            final CollabInvariants.Attribution alt = attribution.copy();
            alt.orphaned( t.saved != null ? t.saved : t.base + 1, t.version, t.metaSeq, t.base == 0 );
            return alt;
        }

        /**
         * The ticket's publish completed at {@code pageVersion}; a version at or below B means the save stored nothing new,
         * which credits nobody and leaves the contributors pending (ruling R2).
         */
        private void complete( final Ticket t, final int pageVersion ) {
            if( pageVersion <= t.base ) {
                attribution.publishedUnchanged( t.version, t.metaSeq );
            } else {
                attribution.published( pageVersion, t.version, t.metaSeq, t.base == 0 );
            }
            count( CREDITED_PUBLISHES, 1 );
            if( t.crashed ) {
                count( CREDITED_AFTER_CRASH, 1 );
            }
            ticket = null;
        }

        /** Invariants 10 and 11 against the model, after settling whatever ticket the server settled. */
        private void checkAttribution() {
            resolveIfSettled();
            CollabInvariants.contributors( server.contributors(), attribution );
            // a marked publish may have written its rows already, before the commit that failed (its retry rewrites them)
            CollabInvariants.ledger( server.ledger( CANONICAL_ID ), attribution,
                ticket == null ? List.of() : List.of( inFlight( ticket ) ) );
        }

        /** A clear point for {@link #externalPending}: cleared once the server has nothing queued or owed. */
        private void settled() {
            if( externalPending && server.reconciled() && owedRename == null && !owedFreeze ) {
                externalPending = false;
            }
        }

        /** The server's view with the scheduler's {@link #externalPending} folded in (invariant 4). */
        private InvariantView view() {
            final InvariantView v = server.view();
            if( !externalPending || v.externalChangePending() ) {
                return v;
            }
            return v.withExternalChangePending();
        }

        /** Queues every completed parked pull as the answer to the request that parked it; returns how many. */
        private int drain() {
            final List< Wakeup > wakeups = server.drainWakeups();
            for( final Wakeup w : wakeups ) {
                final Deque< Map.Entry< String, Integer > > q = parked.get( w.participantId() );
                final Map.Entry< String, Integer > req = q == null ? null : q.pollFirst();
                if( req == null ) {
                    throw new SimFailure( "protocol/orphan-wakeup", "protocol: wakeup for " + w.participantId()
                        + " with no parked pull (F6: every pull is answered exactly once)" );
                }
                if( w.result().parked() ) {
                    throw new SimFailure( "protocol/parked-wakeup", "protocol: a wakeup answer for " + w.participantId()
                        + " is itself parked" );
                }
                final Integer c = clientOf.get( w.participantId() );
                net.send( new Message( newId(), Type.PULL_RESULT, c, req.getKey(), w.participantId(), req.getValue(), null,
                    w.result() ) );
            }
            return wakeups.size();
        }

        private boolean isParked( final String requestId ) {
            return parked.values().stream().flatMap( Deque::stream ).anyMatch( e -> e.getKey().equals( requestId ) );
        }

        private static AssertionError refused( final Peer p, final String what, final int status, final String reason ) {
            return new SimFailure( "protocol/refused " + what + " " + status + " " + reason, "protocol: client " + p.name
                + "'s well-formed " + what + " was answered " + status + " " + reason );
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
                        if( r.status() == 403 && CollabSession.EVICTED.equals( r.reason() ) ) {
                            // the publish answer carries no detail: the push or pull answer names the reason
                            onEvicted( p, m.participantId(), "publish", evictedAs.get( m.participantId() ) );
                        } else if( r.status() == 403 ) {
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
                        final List< MetaState.Entry > inflight = p.pushMeta;
                        p.pushMeta = List.of();
                        p.retired.add( p.sim.participantId() );
                        final Joined j = (Joined) m.result();
                        if( !p.resync && j.clientId().equals( p.sim.clientId() ) ) {
                            // the same collab state goes on: the abandoned push's meta edits go out again (idempotent)
                            p.sim.meta().failed( inflight );
                            p.sim.reconnect( j );
                            count( RECONNECTS, 1 );
                        } else if( !p.resync && p.sent == 0 ) {
                            p.sim.rejoin( (Joined) m.result() );
                            count( REJOINS, 1 );
                        } else {
                            p.sim.resync( (Joined) m.result() );
                            count( RESYNCS, 1 );
                        }
                        p.resync = false;
                        p.sent = 0;
                        p.sentSelection = null;   // a new participant: the server holds no selection for it yet
                    }
                }
                case LEFT -> {
                    if( m.requestId().equals( p.leave ) ) {
                        p.leave = null;
                        startJoin( p );
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
            final List< MetaState.Entry > meta = p.pushMeta;
            p.pushMeta = List.of();
            if( r.status() == 200 ) {
                p.sim.meta().acked( meta, r.metaSeq() );
                if( p.pushSelection != null ) {
                    p.sentSelection = p.pushSelection;
                }
                return;
            }
            p.sim.meta().failed( meta );
            switch( r.status() ) {
                case 409 -> {
                    if( "stale-base".equals( r.reason() ) ) {
                        p.repush = true;
                        sendPull( p );
                    } else if( "rejoin".equals( r.reason() ) ) {
                        startRejoin( p );
                    } else if( "frozen".equals( r.reason() ) ) {
                        // the page was deleted under the draft: stop editing and pushing until it is recreated
                        count( FROZEN_PUSHES, 1 );
                        p.sim.freeze();
                    } else {
                        throw refused( p, "push", r.status(), r.reason() );
                    }
                }
                case 403 -> {
                    if( !CollabSession.EVICTED.equals( r.reason() ) ) {
                        throw refused( p, "push", r.status(), r.reason() );
                    }
                    onEvicted( p, p.sim.participantId(), "push", r.detail() );
                }
                case 413 -> startResync( p );
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
                final int before = p.sim.version();
                final int unconfirmed = p.sim.sendable().size();
                if( since < before && !r.updates().isEmpty() ) {
                    count( TRIMMED_PULLS, 1 );
                }
                p.sim.receive( r.updates(), since );
                if( !current && p.sim.version() > before ) {
                    count( OUT_OF_ORDER_PULLS, 1 );
                }
                // own updates confirmed come off the front of the unconfirmed list, which is where the sent ones are
                p.sent = Math.max( 0, p.sent - ( unconfirmed - p.sim.sendable().size() ) );
                if( current ) {
                    // meta ops and events only from the current pull, as HTTP delivers them (one answer per request)
                    pulledMetaAndEvents( p, r );
                }
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
                case 409 -> startRejoin( p );
                case 403 -> {
                    if( !CollabSession.EVICTED.equals( r.reason() ) ) {
                        throw refused( p, "pull", r.status(), r.reason() );
                    }
                    onEvicted( p, p.sim.participantId(), "pull", r.detail() );
                }
                case 410 -> {
                    count( GONE_PULLS, 1 );
                    startResync( p );
                }
                case 503 -> { }
                default -> throw refused( p, "pull", r.status(), r.reason() );
            }
        }

        /** The current pull's meta ops (when contiguous with what the client has) and its events. */
        private void pulledMetaAndEvents( final Peer p, final PullResult r ) {
            if( p.pullMetaSince == p.sim.meta().serverSeq() ) {
                p.sim.meta().applyRemote( r.metaOps(), r.metaSeq() );
            }
            if( p.pullEventsSince != p.sim.eventSeq() ) {
                return;
            }
            boolean recreated = false;
            for( final CollabEvent e : r.events() ) {
                switch( e.type() ) {
                    case "page-deleted" -> {
                        p.sim.freeze();
                        recreated = false;
                    }
                    case "published" -> recreated = p.sim.frozen();
                    case "external-change" -> recreated = recreated || Boolean.TRUE.equals( e.fields().get( "recreated" ) );
                    default -> { }
                }
            }
            p.sim.eventsSeen( r.eventSeq() );
            if( recreated && p.sim.frozen() ) {
                startResync( p );   // the page exists again: reload, as the editor does (its unsynced text to recovery)
            }
        }

        /**
         * 403 {@code evicted} for one of the client's requests: the scheduler must have evicted that participant with
         * that reason ({@code detail}; a publish answer carries none). Signed out, the client keeps every edit and rejoins
         * offering its clientID (A13, ruling Q11); for a lost grant its pending metadata is discarded and its unsynced
         * text goes to the recovery panel (a resync, A13).
         */
        private void onEvicted( final Peer p, final String participantId, final String what, final String detail ) {
            final String why = evictedAs.get( participantId );
            if( why == null ) {
                throw new SimFailure( "protocol/evicted-unknown", "protocol: client " + p.name + "'s " + what + " for "
                    + participantId + " was answered 403 evicted (" + detail + "), but that participant was never evicted" );
            }
            if( !why.equals( detail ) ) {
                throw new SimFailure( "protocol/evicted-reason", "protocol: client " + p.name + "'s " + what + " for "
                    + participantId + " was answered 403 evicted with detail " + detail + ", but it was evicted for " + why );
            }
            count( EVICTED_ANSWERS, 1 );
            if( CollabSession.LOST_EDIT.equals( why ) ) {
                p.sim.meta().discardPending();
                p.resync = true;
                if( p.join == null && p.leave == null ) {
                    startJoin( p );
                }
            } else {
                startRejoin( p );
            }
        }

        private Outcome afterStep( final boolean replay ) {
            final InvariantView v = view();
            if( v.poisoned() ) {
                return new Outcome( Status.UNSUPPORTED, "unsupported scenario: the server reports poisoned", tag );
            }
            CollabInvariants.check( v, replay );
            if( server.lifecycle() ) {
                accountServerOps();
                CollabInvariants.addsSurvive( v, mustHold );
                checkAttribution();
                if( wasFrozen && !v.frozen() ) {
                    count( UNFREEZES, 1 );
                }
                wasFrozen = v.frozen();
            }
            return null;
        }

        // -- quiescence --

        Outcome quiesce() {
            return guarded( () -> {
                for( int round = 1; ; round++ ) {
                    if( round > MAX_QUIESCENCE_ROUNDS ) {
                        throw new SimFailure( "convergence/no-quiescence", "convergence: no quiescence after "
                            + MAX_QUIESCENCE_ROUNDS + " rounds" );
                    }
                    tag = "q" + round;
                    seq = 0;
                    while( !net.isEmpty() || !pendingPublishes.isEmpty() ) {
                        if( net.isEmpty() ) {
                            releasePublish();
                        } else {
                            deliver( net.ids().get( 0 ) );
                        }
                        final Outcome u = afterStep( false );
                        if( u != null ) {
                            return u;
                        }
                        if( seq > 100_000 ) {
                            throw new SimFailure( "convergence/network", "convergence: the network never drained" );
                        }
                    }
                    if( !retryAll() && !settleServer() ) {
                        break;
                    }
                }
                final InvariantView v = view();
                // fault points are one-shot, so after settleServer() nothing may be left queued or owed: a merge or a
                // pending publish still outstanding here would hide invariant 4 for good
                if( v.externalChangePending() || v.publishPending() ) {
                    throw new SimFailure( "convergence/unsettled", "convergence: the server is not settled at quiescence"
                        + " (externalChangePending=" + v.externalChangePending() + ", publishPending=" + v.publishPending()
                        + ")" );
                }
                for( final Peer p : peers ) {
                    if( !p.sim.doc().equals( v.doc() ) ) {
                        throw new SimFailure( "convergence/doc", "convergence: client " + p.name + " differs from the server "
                            + firstDifference( p.sim.doc(), v.doc() ) );
                    }
                    if( p.sim.version() != v.head() ) {
                        throw new SimFailure( "convergence/version", "convergence: client " + p.name + " at version "
                            + p.sim.version() + " but head is " + v.head() );
                    }
                }
                CollabInvariants.check( v, true );
                if( server.lifecycle() ) {
                    lifecycleAtQuiescence( v );
                }
                tag = "end";
                return new Outcome( Status.PASS, "converged at head " + v.head() + " with doc length " + v.doc().length(), tag );
            } );
        }

        /** Phase 2's quiescence checks: nothing frozen, every client shows the server's metadata, invariants 8 and 9. */
        private void lifecycleAtQuiescence( final InvariantView v ) {
            if( v.frozen() ) {
                throw new SimFailure( "convergence/frozen", "convergence: the draft is still frozen at quiescence" );
            }
            final Map< String, Map< String, Object > > shown = new LinkedHashMap<>();
            final Map< String, Selection > selections = new LinkedHashMap<>();
            for( final Peer p : peers ) {
                if( p.sim.frozen() ) {
                    throw new SimFailure( "convergence/frozen-client", "convergence: client " + p.name
                        + " still thinks the page is deleted" );
                }
                if( !p.sim.meta().pending().isEmpty() ) {
                    throw new SimFailure( "convergence/meta-pending", "convergence: client " + p.name + " still has meta"
                        + " edits pending: " + p.sim.meta().pending() );
                }
                shown.put( p.name, p.sim.meta().view() );
                if( p.sim.selection() != null ) {
                    selections.put( p.sim.participantId(), p.sim.selection() );
                }
            }
            CollabInvariants.metaAtQuiescence( v, shown );
            CollabInvariants.presenceAtQuiescence( v, selections );
            count( PRESENCE_CHECKED, selections.size() );
            CollabInvariants.addsSurvive( v, mustHold );
            checkAttribution();
            if( ticket != null ) {
                throw new SimFailure( "attribution/unsettled", "attribution: the publish at version " + ticket.version
                    + " by " + ticket.author + " is still marked at quiescence" );
            }
        }

        /**
         * With everything delivered, lets the server retry what it owes the page store (a failed merge, an unfinished
         * publish); true when that moved the head or answered a parked pull, so the clients have more to do.
         */
        private boolean settleServer() {
            if( owedRename != null || owedFreeze ) {
                retryListenerEvents();   // the registry's tick: faults are one-shot, so this gets through
                return true;
            }
            if( server.lifecycle() && server.frozen() && pendingPublishes.isEmpty() ) {
                // a frozen draft at quiescence: someone recreates the page with the draft's text (as the editor's
                // recreate does, ruling Q5), through an outside save the listener reports
                final InvariantView v = server.view();
                final int version = env.writer().externalSave( env.pageName(), v.doc(), v.meta(), "recreator" );
                externalPending = true;
                count( EXTERNAL_SAVES, 1 );
                server.externalSaved( version );
                drain();
                settled();
                return true;
            }
            final int head = server.view().head();
            // like the registry tick, retry while something is owed: each armed store fault is one-shot and there is at
            // most one per operation, so a few rounds always get through (the convergence check then demands it)
            for( int round = 0; round <= FAULT_OPS.size(); round++ ) {
                server.settle();
                if( server.reconciled() && !server.view().publishPending() ) {
                    break;
                }
            }
            final int woke = drain();
            settled();
            return woke > 0 || server.view().head() != head || !net.isEmpty();
        }

        /** With the network empty, abandons lost requests and sends what each client still needs; false when nothing is left. */
        private boolean retryAll() {
            boolean progress = false;
            final int head = server.view().head();
            for( final Peer p : peers ) {
                if( p.leave != null ) {
                    p.leave = null;
                    sendLeave( p );
                    progress = true;
                    continue;
                }
                if( p.join != null ) {
                    p.join = null;
                    startJoin( p );
                    progress = true;
                    continue;
                }
                abandonPush( p );
                p.publish = null;
                if( p.pull != null && !isParked( p.pull ) ) {
                    p.pull = null;
                }
                if( server.lifecycle() && p.sim.frozen() && !server.frozen() ) {
                    startResync( p );   // the page exists again: reload
                    progress = true;
                } else if( hasSomethingToPush( p ) ) {
                    sendPush( p );
                    progress = true;
                } else if( p.pull == null && ( p.sim.version() < head || server.lifecycle() ) ) {
                    // with presence, every client keeps a pull parked (its last selection rides on it), as the editor does
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
            } catch( final SimFailure e ) {
                return new Outcome( Status.FAIL, String.valueOf( e.getMessage() ), tag, e.kind );
            } catch( final AssertionError e ) {
                return new Outcome( Status.FAIL, String.valueOf( e.getMessage() ), tag );
            } catch( final RuntimeException e ) {
                final StackTraceElement[] st = e.getStackTrace();
                return new Outcome( Status.FAIL, "exception: " + e + " at " + Arrays.stream( st ).limit( 4 )
                    .map( StackTraceElement::toString ).collect( Collectors.joining( " < " ) ), tag,
                    "exception " + e.getClass().getName() + " at " + firstOwnFrame( st ) );
            }
        }

        /**
         * The first {@code com.wikantik} frame (else the first frame): a failure inside a JDK call is identified by the
         * code that made it, so the shrinker keeps the same bug rather than any exception from that JDK method.
         */
        static String firstOwnFrame( final StackTraceElement[] st ) {
            return Arrays.stream( st ).filter( f -> f.getClassName().startsWith( "com.wikantik." ) ).findFirst()
                .map( StackTraceElement::toString ).orElse( st.length > 0 ? st[ 0 ].toString() : "?" );
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

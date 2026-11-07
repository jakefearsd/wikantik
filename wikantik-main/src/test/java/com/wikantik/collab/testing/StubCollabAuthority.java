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
import com.wikantik.api.pages.VersionConflictException;
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PublishOutcome;
import com.wikantik.collab.model.PublishRequest;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.CollabText;
import com.wikantik.collab.text.TextDiff;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The Phase 0 stand-in for the real {@code CollabSession}: one page, the section 7.2 push, section 7.5 pull with
 * parking and section 7.6 ring (with A10's fall-through to the store), and a deliberately naive publish. There are no
 * external merges, meta ops, presence, frozen state or permission checks; those land in Phase 1/2.
 *
 * <p>Phase 1 additions (final-review amendments): a join reissues the caller's prior clientID when a participant of the
 * same author held it and left (section 7.2 "same clientID"), and {@link #truncate} cuts the store's log below the lowest
 * version a live participant has confirmed (A10; the store clamps the cut to a snapshot, which the stub writes only at
 * creation and publish).</p>
 *
 * <p>Decisions: an unknown (never joined or left) participant gets 409 from push, pull and publish (stands for the
 * {@code rejoin} answer of 7.2). Publish is naive (requires {@code version == head}, no pending-publish
 * bookkeeping); if the writer's filters rewrote the text, {@code doc} and the saved base differ, so the stub appends a
 * server update {@code TextDiff.changeSet(doc, base)} to converge everyone; Phase 1 does the full 7.3 mapping. A
 * writer exception maps to 409 for a version conflict and 422 otherwise.
 *
 * <p><b>Poisoning.</b> The stub does not model publish crash recovery (the real session's pending-publish rule does). Any
 * failure after the page was saved (a store fault during publish, or a writer that persisted then failed) sets a
 * {@code poisoned} flag: the triggering publish and every later push, pull and publish answer 503 {@code poisoned}, and the view reports
 * {@code publishPending} so invariants 2 to 4 are skipped. Task 12/14 must keep publish-time store faults and
 * {@code persistThenFail} at weight 0 against the stub; a poisoned stub is an unsupported scenario, not a failure.</p>
 *
 * <p>Oversize is session state (see {@link #enterOrLeaveOversize}): a publish rewrite can push the doc over
 * {@code maxPageBytes}; pushes may then only shrink it, and the state clears when the doc is back within the limit.</p>
 *
 * <p>Every public call holds one {@link ReentrantLock} (the real session's lock, section 7.1), so the F7 harness can
 * drive it from real threads (E38, E40). {@link #reload} is the F8 crash recovery: a fresh stub rebuilt from the store
 * alone.</p>
 */
public final class StubCollabAuthority implements SimServer {

    private static final Logger LOG = LogManager.getLogger( StubCollabAuthority.class );
    /** The long-poll timeout of section 7.5. */
    static final Duration POLL_TIMEOUT = Duration.ofSeconds( 25 );
    static final String SERVER_CLIENT = "server";
    private static final String POISONED = "poisoned";
    private static final String NOT_YOURS = "not-your-participant";
    /** Reloads per store, so participant ids stay unique across recoveries and runs stay deterministic. */
    private static final Map< CollabStore, Integer > BOOTS = Collections.synchronizedMap( new WeakHashMap<>() );

    /** {@code confirmed}: the highest version the participant has shown it holds (A10's truncation never cuts above it). */
    private static final class Participant {
        final String authorKey;
        final String clientId;
        int confirmed;

        Participant( final String authorKey, final String clientId, final int confirmed ) {
            this.authorKey = authorKey;
            this.clientId = clientId;
            this.confirmed = confirmed;
        }

        String authorKey() {
            return authorKey;
        }

        String clientId() {
            return clientId;
        }

        void confirm( final int version, final int head ) {
            if( version >= 0 && version <= head ) {
                confirmed = Math.max( confirmed, version );
            }
        }
    }

    private record Waiter( int since, Instant deadline ) {
    }

    private final String pageName;
    private final CollabStore store;
    private final CollabPageWriter writer;
    private final Clock clock;
    private final long maxPageBytes;
    private final int ringSize;
    private final UUID id;
    /** Empty for a first boot, {@code r<k>} after the k-th reload over the same store: ids are never reused (A10). */
    private final String idPrefix;
    private final ReentrantLock lock = new ReentrantLock();

    private String doc;
    private int head;
    private String baseBody;
    private int basePageVersion;
    private Map< String, Object > baseMeta;
    private ChangeSet local;
    private final Deque< StoredUpdate > ring = new ArrayDeque<>();

    private final Map< String, Participant > participants = new LinkedHashMap<>();
    /** clientID to author key of participants that left (a rejoin by the same author gets the clientID back, 7.2). */
    private final Map< String, String > retiredClients = new LinkedHashMap<>();
    private final Map< String, Waiter > waiters = new LinkedHashMap<>();
    private final List< Wakeup > wakeups = new ArrayList<>();
    private int nextParticipant = 1;
    private boolean oversize;
    private long oversizeCeiling;
    private boolean poisoned;

    public StubCollabAuthority( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                final Clock clock, final long maxPageBytes, final int ringSize ) {
        this( pageName, store, writer, clock, maxPageBytes, ringSize,
            UUID.nameUUIDFromBytes( pageName.getBytes( StandardCharsets.UTF_8 ) ), "" );
        final PageState latest = writer.latest( pageName ).orElse( null );
        doc = latest == null ? "" : CollabText.toLf( latest.body() );
        baseBody = doc;
        basePageVersion = latest == null ? 0 : latest.version();
        baseMeta = latest == null ? Map.of() : latest.meta();
        local = ChangeSet.empty( doc.length() );
        enterOrLeaveOversize( true );
        final Instant now = clock.instant();
        store.createSession( new SessionRecord( id, pageName, SessionRecord.ACTIVE, basePageVersion, baseBody, baseMeta,
            CollabText.contentHash( baseBody ), 0, 0, false, null, null, null, null, null, "stub", now, now ) );
        store.writeSnapshot( id, new Snapshot( 0, 0, basePageVersion, doc, baseMeta, local ) );
    }

    private StubCollabAuthority( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                 final Clock clock, final long maxPageBytes, final int ringSize, final UUID id,
                                 final String idPrefix ) {
        this.pageName = Objects.requireNonNull( pageName );
        this.store = Objects.requireNonNull( store );
        this.writer = Objects.requireNonNull( writer );
        this.clock = Objects.requireNonNull( clock );
        this.maxPageBytes = maxPageBytes;
        if( ringSize < 1 ) {
            throw new IllegalArgumentException( "ringSize must be >= 1: " + ringSize );
        }
        this.ringSize = ringSize;
        this.id = id;
        this.idPrefix = idPrefix;
    }

    /**
     * F8 recovery: a fresh stub over the same store and page writer, rebuilt from the committed state only. {@code doc}
     * and {@code head} come from the latest snapshot plus the log after it, the base from the session record, the ring
     * from the log's tail, and {@code local} is recomputed as {@code TextDiff.changeSet(baseBody, doc)} (section 7.1).
     * Participants and parked pulls are not persisted, so every client must rejoin; participant and client ids carry a
     * per-store boot generation ({@code r<k>p<n>}) so a straggler request addressed to a pre-crash participant can never
     * be taken for a post-crash one.
     *
     * @throws IllegalStateException when the store has no session (or no snapshot) for the page, or its log is not
     *                               contiguous to the recorded head
     */
    public static StubCollabAuthority reload( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                              final Clock clock, final long maxPageBytes, final int ringSize ) {
        final SessionRecord rec = store.sessionForPage( pageName ).orElseThrow(
            () -> new IllegalStateException( "no collab session to reload for page " + pageName ) );
        final Snapshot snap = store.latestSnapshot( rec.id() ).orElseThrow(
            () -> new IllegalStateException( "collab session " + rec.id() + " has no snapshot" ) );
        final int boot = BOOTS.merge( store, 1, Integer::sum );
        final StubCollabAuthority s = new StubCollabAuthority( pageName, store, writer, clock, maxPageBytes, ringSize, rec.id(),
            "r" + boot );
        String doc = snap.body();
        int version = snap.version();
        for( final StoredUpdate u : store.updatesSince( rec.id(), snap.version() ) ) {
            if( u.version() != version + 1 ) {
                throw new IllegalStateException( "collab log of " + rec.id() + " has a gap before version " + u.version() );
            }
            doc = u.changes().apply( doc );
            version = u.version();
        }
        if( version != rec.headVersion() ) {
            throw new IllegalStateException( "collab log of " + rec.id() + " ends at " + version + " but the session head is "
                + rec.headVersion() );
        }
        s.doc = doc;
        s.head = version;
        s.baseBody = rec.baseBody();
        s.basePageVersion = rec.basePageVersion();
        s.baseMeta = rec.baseMeta();
        s.local = TextDiff.changeSet( s.baseBody, doc );
        store.updatesSince( rec.id(), Math.max( 0, version - ringSize ) ).forEach( s::remember );
        s.enterOrLeaveOversize( true );
        return s;
    }

    // ---- public surface: every call holds the session lock (E38/E40 run these on real threads) ----

    /** The stub creates its session in its constructor, never on a join, so {@code initialText}/{@code initialMeta} are ignored. */
    @Override
    public Joined join( final String authorKey, final String priorClientId, final String initialText,
                        final Map< String, Object > initialMeta ) {
        lock.lock();
        try {
            return joinLocked( authorKey, priorClientId );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void truncate() {
        lock.lock();
        try {
            int cut = head;
            for( final Participant p : participants.values() ) {
                cut = Math.min( cut, p.confirmed );
            }
            store.truncateUpdatesBelow( id, cut );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PushResult push( final String participantId, final String authorKey, final int version, final List< Update > updates ) {
        lock.lock();
        try {
            return pushLocked( participantId, authorKey, version, updates );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PullResult pull( final String participantId, final String authorKey, final int since ) {
        lock.lock();
        try {
            return pullLocked( participantId, authorKey, since );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void tick() {
        lock.lock();
        try {
            tickLocked();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Left leave( final String participantId, final String authorKey ) {
        lock.lock();
        try {
            final Participant p = participants.get( participantId );
            if( p == null ) {
                return new Left( 409, "rejoin" );
            }
            if( !p.authorKey().equals( authorKey ) ) {
                return new Left( 403, NOT_YOURS );
            }
            leaveLocked( participantId );
            return new Left( 200, null );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void evict( final String participantId ) {
        lock.lock();
        try {
            leaveLocked( participantId );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List< Wakeup > drainWakeups() {
        lock.lock();
        try {
            return drainWakeupsLocked();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public PublishResult publish( final String participantId, final String authorKey, final int version, final String note ) {
        lock.lock();
        try {
            return publishLocked( participantId, authorKey, version, note );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public InvariantView view() {
        lock.lock();
        try {
            return viewLocked();
        } finally {
            lock.unlock();
        }
    }

    private Joined joinLocked( final String authorKey, final String priorClientId ) {
        final int n = nextParticipant++;
        final boolean reissue = priorClientId != null && Objects.requireNonNull( authorKey ).equals( retiredClients.get( priorClientId ) )
            && participants.values().stream().noneMatch( x -> x.clientId().equals( priorClientId ) );
        if( reissue ) {
            retiredClients.remove( priorClientId );
        }
        final Participant p = new Participant( Objects.requireNonNull( authorKey ), reissue ? priorClientId : idPrefix + "c" + n,
            head );
        participants.put( idPrefix + "p" + n, p );
        return new Joined( idPrefix + "p" + n, p.clientId(), head, doc );
    }

    private PushResult pushLocked( final String participantId, final String authorKey, final int version, final List< Update > updates ) {
        if( poisoned ) {
            return new PushResult( 503, head, POISONED );
        }
        final Participant p = participants.get( participantId );
        if( p == null ) {
            return new PushResult( 409, head, "rejoin" );
        }
        if( !p.authorKey().equals( authorKey ) ) {
            return new PushResult( 403, head, NOT_YOURS );
        }
        p.confirm( version, head );
        if( version != head ) {
            return new PushResult( 409, head, "stale-base" );
        }
        // validate the whole chain before touching anything
        String cur = doc;
        final List< StoredUpdate > accepted = new ArrayList<>();
        for( final Update u : updates ) {
            if( !p.clientId().equals( u.clientId() ) ) {
                LOG.warn( "push from {} names clientId {} (bound to {}): refused", participantId, u.clientId(), p.clientId() );
                return new PushResult( 400, head, "rejected" );
            }
            final ChangeSet cs = u.changes();
            if( cs.length() != cur.length() ) {
                LOG.warn( "push from {}: change set length {} does not match document length {}", participantId, cs.length(),
                    cur.length() );
                return new PushResult( 400, head, "rejected" );
            }
            final String next = cs.apply( cur );
            if( CollabText.splitsSurrogatePair( cur, cs ) || CollabText.hasLoneSurrogate( next ) ) {
                LOG.warn( "push from {}: update would leave a lone surrogate", participantId );
                return new PushResult( 400, head, "rejected" );
            }
            if( utf8( next ) > Math.max( maxPageBytes, utf8( cur ) ) ) {
                return new PushResult( 413, head, "oversize" );
            }
            accepted.add( new StoredUpdate( head + accepted.size() + 1, StoredUpdate.TEXT, p.clientId(), p.authorKey(), cs ) );
            cur = next;
        }
        if( accepted.isEmpty() ) {
            return new PushResult( 200, head, null );
        }
        try {
            store.appendUpdates( id, accepted );
        } catch( final RuntimeException e ) {
            LOG.warn( "push from {}: store append failed, nothing applied", participantId, e );
            return new PushResult( 503, head, "store" );
        }
        ChangeSet l = local;
        for( final StoredUpdate u : accepted ) {
            l = l.compose( u.changes() );
            remember( u );
        }
        local = l;
        doc = cur;
        head += accepted.size();
        enterOrLeaveOversize( false );
        wakeWaiters();
        return new PushResult( 200, head, null );
    }

    private void remember( final StoredUpdate u ) {
        ring.addLast( u );
        while( ring.size() > ringSize ) {
            ring.removeFirst();
        }
    }

    private PullResult pullLocked( final String participantId, final String authorKey, final int since ) {
        if( poisoned ) {
            return new PullResult( 503, List.of(), head, false, POISONED );
        }
        final Participant p = participants.get( participantId );
        if( p == null ) {
            return new PullResult( 409, List.of(), head, false, "rejoin" );
        }
        if( !p.authorKey().equals( authorKey ) ) {
            return new PullResult( 403, List.of(), head, false, NOT_YOURS );
        }
        if( since < 0 || since > head ) {
            return new PullResult( 400, List.of(), head, false, "bad-since" );
        }
        p.confirm( since, head );
        if( since == head ) {
            if( waiters.put( participantId, new Waiter( since, clock.instant().plus( POLL_TIMEOUT ) ) ) != null ) {
                // F6: every request is answered exactly once, so the displaced pull gets an empty 200
                wakeups.add( new Wakeup( participantId, new PullResult( 200, List.of(), head, false, null ) ) );
            }
            return new PullResult( 200, List.of(), head, true, null );
        }
        return slice( since );
    }

    private PullResult slice( final int since ) {
        final List< StoredUpdate > source;
        if( !ring.isEmpty() && ring.peekFirst().version() <= since + 1 ) {
            source = ring.stream().filter( u -> u.version() > since ).toList();
        } else {
            source = store.updatesSince( id, since );
            if( source.isEmpty() || source.get( 0 ).version() != since + 1 || source.get( source.size() - 1 ).version() != head ) {
                return new PullResult( 410, List.of(), head, false, "resync" );
            }
        }
        final List< Update > out = new ArrayList<>();
        for( final StoredUpdate u : source ) {
            out.add( new Update( u.clientId(), u.changes() ) );
        }
        return new PullResult( 200, out, head, false, null );
    }

    private void wakeWaiters() {
        for( final Map.Entry< String, Waiter > e : new ArrayList<>( waiters.entrySet() ) ) {
            wakeups.add( new Wakeup( e.getKey(), slice( e.getValue().since() ) ) );
        }
        waiters.clear();
    }

    private void tickLocked() {
        final Instant now = clock.instant();
        for( final Map.Entry< String, Waiter > e : new ArrayList<>( waiters.entrySet() ) ) {
            if( !now.isBefore( e.getValue().deadline() ) ) {
                waiters.remove( e.getKey() );
                wakeups.add( new Wakeup( e.getKey(), new PullResult( 200, List.of(), head, false, null ) ) );
            }
        }
    }

    private void leaveLocked( final String participantId ) {
        final Participant gone = participants.remove( participantId );
        if( gone != null ) {
            retiredClients.put( gone.clientId(), gone.authorKey() );
        }
        if( waiters.remove( participantId ) != null ) {
            wakeups.add( new Wakeup( participantId, new PullResult( 200, List.of(), head, false, null ) ) );
        }
    }

    private List< Wakeup > drainWakeupsLocked() {
        final List< Wakeup > out = List.copyOf( wakeups );
        wakeups.clear();
        return out;
    }

    private PublishResult publishLocked( final String participantId, final String authorKey, final int version,
                                               final String note ) {
        if( poisoned ) {
            return new PublishResult( 503, basePageVersion, POISONED, false );
        }
        final Participant p = participants.get( participantId );
        if( p == null ) {
            return new PublishResult( 409, basePageVersion, "rejoin", false );
        }
        if( !p.authorKey().equals( authorKey ) ) {
            return new PublishResult( 403, basePageVersion, NOT_YOURS, false );
        }
        p.confirm( version, head );
        if( version != head ) {
            return new PublishResult( 409, basePageVersion, "stale-base", false );
        }
        if( local.empty() ) {
            return new PublishResult( 200, basePageVersion, null, true );
        }
        if( doc.isEmpty() ) {
            return new PublishResult( 400, basePageVersion, "empty", false );
        }
        final PublishOutcome out;
        try {
            out = writer.publish( new PublishRequest( pageName, doc, baseMeta, basePageVersion, p.authorKey(),
                note == null ? "" : note ) );
        } catch( final VersionConflictException e ) {
            LOG.warn( "publish of {} refused: {}", pageName, e.getMessage() );
            return failedPublish( 409, "stale-base" );
        } catch( final WikiException e ) {
            LOG.warn( "publish of {} refused: {}", pageName, e.getMessage() );
            return failedPublish( 422, "writer-refused" );
        }
        final String newBase = CollabText.toLf( out.savedBody() );
        final List< StoredUpdate > extra = new ArrayList<>();
        if( !newBase.equals( doc ) ) {
            extra.add( new StoredUpdate( head + 1, StoredUpdate.SERVER, SERVER_CLIENT, null, TextDiff.changeSet( doc, newBase ) ) );
        }
        final int newHead = head + extra.size();
        try {
            if( !extra.isEmpty() ) {
                store.appendUpdates( id, extra );
            }
            store.writeSnapshot( id, new Snapshot( newHead, 0, out.version(), newBase, out.savedMeta(),
                ChangeSet.empty( newBase.length() ) ) );
            final SessionRecord rec = store.session( id ).orElseThrow();
            store.updateSession( rec.withBase( out.version(), newBase, out.savedMeta(), CollabText.contentHash( newBase ) ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "publish of {} saved page version {} but the store failed; stub poisoned", pageName, out.version(), e );
            poisoned = true;
            return new PublishResult( 503, basePageVersion, POISONED, false );
        }
        extra.forEach( this::remember );
        head = newHead;
        doc = newBase;
        baseBody = newBase;
        basePageVersion = out.version();
        baseMeta = out.savedMeta();
        local = ChangeSet.empty( newBase.length() );
        enterOrLeaveOversize( true );
        if( !extra.isEmpty() ) {
            wakeWaiters();
        }
        return new PublishResult( 200, out.version(), null, false );
    }

    /** A writer failure: if the page version advanced anyway (persist-then-fail) the stub cannot recover, so poison it. */
    private PublishResult failedPublish( final int status, final String reason ) {
        final int latest = writer.latest( pageName ).map( PageState::version ).orElse( 0 );
        if( latest != basePageVersion ) {
            LOG.warn( "publish of {} failed after the page moved to version {}; stub poisoned", pageName, latest );
            poisoned = true;
            return new PublishResult( 503, basePageVersion, POISONED, false );
        }
        return new PublishResult( status, basePageVersion, reason, false );
    }

    /**
     * Oversize is session state: entered when a publish rewrite (or load) lands the doc over the limit, with the ceiling
     * reset to the new size by every such publish (even when already oversize), ratcheting down as pushes shrink the doc,
     * cleared once the doc is within the limit. {@code mayEnter} is false for pushes, which are
     * refused rather than allowed to cross the limit.
     */
    private void enterOrLeaveOversize( final boolean mayEnter ) {
        final long bytes = utf8( doc );
        if( bytes <= maxPageBytes ) {
            oversize = false;
            oversizeCeiling = 0;
        } else if( mayEnter ) {
            // a publish rewrite (or load) sets the ceiling afresh, even when already oversize; only pushes ratchet it
            oversize = true;
            oversizeCeiling = bytes;
        } else if( oversize ) {
            oversizeCeiling = Math.min( oversizeCeiling, bytes );
        }
    }

    private InvariantView viewLocked() {
        return new InvariantView( id, doc, head, baseBody, local, basePageVersion, List.copyOf( ring ),
            oversize, maxPageBytes, store, writer, pageName, false, poisoned, oversizeCeiling, poisoned );
    }

    private static long utf8( final String s ) {
        return s.getBytes( StandardCharsets.UTF_8 ).length;
    }
}

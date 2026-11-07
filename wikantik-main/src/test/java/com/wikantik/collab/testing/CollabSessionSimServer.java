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
import com.wikantik.collab.CollabPageWriter;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.CollabStore;
import com.wikantik.collab.PullWaiter;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PublishResponse;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.text.ChangeSet;
import com.wikantik.collab.text.ChangeSetJson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The real {@link CollabSession} behind the Phase 0 {@link SimServer} surface, so the simulator, the regression traces,
 * the concurrency harness and the recovery tests all run against it as well as the stub.
 *
 * <p><b>Wire.</b> Every update crosses {@link ChangeSetJson} in both directions (push and pull), exactly as it will
 * over HTTP, so the wire format is in the fuzz path; a change set the parser refuses is a 400, which the scheduler
 * reports as a protocol failure.</p>
 *
 * <p><b>Parked pulls.</b> The session completes waiters outside its lock, possibly on another thread and in any
 * order across participants. The harnesses pair answers with requests in park order per participant, so each pull gets
 * a slot queued before it is offered to the session, and completed slots are released strictly in queue order.</p>
 *
 * <p>The session has no poisoned state: the view always reports {@code poisoned=false}, so a session run can never end
 * as an UNSUPPORTED scenario (its tolerance is 0). Publish is the session's naive Task 3 publish (Task 4 replaces
 * it).</p>
 */
public final class CollabSessionSimServer implements SimServer {

    private static final Logger LOG = LogManager.getLogger( CollabSessionSimServer.class );
    /** Small, so cadence snapshots and the truncation behind them happen in short runs. */
    static final int SIM_SNAPSHOT_EVERY = 6;
    /** The A8 limiter is a session test of its own; the simulator's clock moves too rarely for a real rate. */
    static final int SIM_PUSHES_PER_SECOND = 1_000_000;

    public static final Function< SimEnv, SimServer > SESSION = env -> create( env.pageName(), env.store(), env.writer(),
        env.clock(), env.maxPageBytes(), env.ringSize(), SIM_SNAPSHOT_EVERY );
    public static final Function< SimEnv, SimServer > SESSION_RELOAD = env -> reload( env.pageName(), env.store(),
        env.writer(), env.clock(), env.maxPageBytes(), env.ringSize(), SIM_SNAPSHOT_EVERY );
    /** Session-only actions: explicit A10 truncation in every profile (the stub's corpus stays as it is). */
    public static final SimScheduler.Overlay OVERLAY = new SimScheduler.Overlay( Map.of( SimProfile.Kind.TRUNCATE, 2 ) );

    private final CollabSession session;
    private final CollabStore store;
    private final CollabPageWriter writer;
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Map< String, Deque< Slot > > slots = new HashMap<>();
    private final List< Wakeup > ready = new ArrayList<>();

    private CollabSessionSimServer( final CollabSession session, final CollabStore store, final CollabPageWriter writer ) {
        this.session = session;
        this.store = store;
        this.writer = writer;
    }

    static CollabConfig config( final int ringSize, final int snapshotEvery ) {
        return new CollabConfig( true, 1000, 65536, 25, 45, 30, ringSize, snapshotEvery, 10, 0, SIM_PUSHES_PER_SECOND );
    }

    static UUID idFor( final String pageName ) {
        return UUID.nameUUIDFromBytes( pageName.getBytes( StandardCharsets.UTF_8 ) );
    }

    /** A new session on the page's latest saved state. */
    public static CollabSessionSimServer create( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                                 final Clock clock, final long maxPageBytes, final int ringSize,
                                                 final int snapshotEvery ) {
        final SessionRecord rec = CollabSession.newRecord( idFor( pageName ), pageName, writer.latest( pageName ), "sim", clock );
        return new CollabSessionSimServer( CollabSession.create( rec, store, writer, clock, config( ringSize, snapshotEvery ),
            maxPageBytes ), store, writer );
    }

    /** F8 recovery: the session reloaded from the store alone. */
    public static CollabSessionSimServer reload( final String pageName, final CollabStore store, final CollabPageWriter writer,
                                                 final Clock clock, final long maxPageBytes, final int ringSize,
                                                 final int snapshotEvery ) {
        final UUID id = store.sessionForPage( pageName ).map( SessionRecord::id ).orElseThrow(
            () -> new IllegalStateException( "no collab session to reload for page " + pageName ) );
        return new CollabSessionSimServer( CollabSession.load( id, store, writer, clock, config( ringSize, snapshotEvery ),
            maxPageBytes ), store, writer );
    }

    public CollabSession session() {
        return session;
    }

    /** The JSON round trip a change set makes over the wire (section 6.1). */
    static ChangeSet wire( final ChangeSet cs ) {
        return ChangeSetJson.fromJson( ChangeSetJson.toJson( cs ) );
    }

    @Override
    public Joined join( final String authorKey, final String priorClientId, final String initialText,
                        final Map< String, Object > initialMeta ) {
        final JoinResponse j = session.join( new JoinRequest( authorKey, priorClientId, null, authorKey, initialText,
            initialMeta ) );
        return new Joined( j.participantId(), j.clientId(), j.version(), j.doc() );
    }

    @Override
    public PushResult push( final String participantId, final String authorKey, final int version, final List< Update > updates ) {
        final List< com.wikantik.collab.model.Update > sent = new ArrayList<>( updates.size() );
        for( final Update u : updates ) {
            try {
                sent.add( new com.wikantik.collab.model.Update( u.clientId(), wire( u.changes() ) ) );
            } catch( final IllegalArgumentException e ) {
                LOG.warn( "push from {}: change set refused by the wire parser: {}", participantId, e.getMessage() );
                return new PushResult( 400, version, "rejected" );
            }
        }
        final PushResponse r = session.push( participantId, authorKey, version, sent );
        return new PushResult( r.status(), r.version(), r.reason() );
    }

    @Override
    public PullResult pull( final String participantId, final String authorKey, final int since ) {
        final Slot slot = new Slot( participantId );
        wakeLock.lock();
        try {
            slots.computeIfAbsent( participantId, k -> new ArrayDeque<>() ).addLast( slot );
        } finally {
            wakeLock.unlock();
        }
        final PullResponse r = session.pullOrPark( participantId, authorKey, since, null, slot );
        if( r == null ) {
            return new PullResult( 200, List.of(), since, true, null );
        }
        wakeLock.lock();
        try {
            slots.get( participantId ).remove( slot );
            flush( participantId );
        } finally {
            wakeLock.unlock();
        }
        return result( r );
    }

    private static PullResult result( final PullResponse r ) {
        final List< Update > out = new ArrayList<>( r.updates().size() );
        for( final com.wikantik.collab.model.Update u : r.updates() ) {
            out.add( new Update( u.clientId(), wire( u.changes() ) ) );
        }
        return new PullResult( r.status(), out, r.version(), false, r.reason() );
    }

    /** Releases the participant's leading completed slots, in park order. Caller holds {@link #wakeLock}. */
    private void flush( final String participantId ) {
        final Deque< Slot > q = slots.get( participantId );
        while( q != null && !q.isEmpty() && q.peekFirst().result != null ) {
            ready.add( new Wakeup( participantId, q.pollFirst().result ) );
        }
    }

    /** One pull offered to the session; completed at most once. */
    private final class Slot implements PullWaiter {
        private final String participantId;
        private PullResult result;

        Slot( final String participantId ) {
            this.participantId = participantId;
        }

        @Override
        public void complete( final PullResponse response ) {
            Objects.requireNonNull( response );
            wakeLock.lock();
            try {
                if( result != null ) {
                    throw new IllegalStateException( "parked pull of " + participantId + " completed twice" );
                }
                result = result( response );
                flush( participantId );
            } finally {
                wakeLock.unlock();
            }
        }
    }

    @Override
    public PublishResult publish( final String participantId, final String authorKey, final int version, final String note ) {
        final PublishResponse r = session.publish( participantId, authorKey, version, note );
        return new PublishResult( r.status(), r.pageVersion(), r.reason(), r.unchanged() );
    }

    @Override
    public void leave( final String participantId ) {
        session.leave( participantId );
    }

    @Override
    public List< Wakeup > drainWakeups() {
        wakeLock.lock();
        try {
            final List< Wakeup > out = List.copyOf( ready );
            ready.clear();
            return out;
        } finally {
            wakeLock.unlock();
        }
    }

    @Override
    public InvariantView view() {
        final CollabSession.View v = session.view();
        return new InvariantView( v.id(), v.doc(), v.head(), v.baseBody(), v.local(), v.basePageVersion(), v.ring(),
            v.oversize(), v.maxPageBytes(), store, writer, v.pageName(), false, false, v.oversizeCeiling(), false );
    }

    @Override
    public void tick() {
        session.expireWaiters().forEach( Runnable::run );
    }

    @Override
    public void truncate() {
        session.truncateLog();
    }
}

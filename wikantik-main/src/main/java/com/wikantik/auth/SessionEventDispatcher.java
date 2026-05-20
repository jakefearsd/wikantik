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
package com.wikantik.auth;

import com.wikantik.WikiSession;
import com.wikantik.api.core.Engine;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.event.WikiEvent;
import com.wikantik.event.WikiEventListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-engine fan-out point for WikiSession event listeners.
 *
 * <p>Before this class, every {@link WikiSession} created by
 * {@link WikiSession#guestSession(Engine)} registered itself as a listener on
 * three managers ({@code GroupManager}, {@code AuthenticationManager},
 * {@code UserManager}) — under stress that produced thousands of per-session
 * registrations per second. Even after the
 * {@code WikiEventManager.WikiEventDelegate} mutex / O(1) rewrite, each
 * registration still cost three map ops and (more importantly) the listener
 * list grew monotonically with anonymous HTTP traffic.</p>
 *
 * <p>This dispatcher is registered <strong>once per engine</strong> on each of
 * the three managers. New {@link WikiSession}s call {@link #register} to join a
 * weak-keyed set; events broadcast from any manager fan out to every alive
 * session in that set. Dead sessions auto-clear via the {@link WeakHashMap}.</p>
 *
 * <p>The dispatcher implements {@link WikiEventListener} so it slots into the
 * existing manager-listener machinery without any manager-side changes. The
 * event-dispatch contract is identical to the previous per-session pattern:
 * every alive session receives every event from any of the three managers, and
 * each session's {@code actionPerformed} filters by {@link WikiEvent#getTarget()}
 * exactly as before.</p>
 */
public final class SessionEventDispatcher implements WikiEventListener {

    private static final Logger LOG = LogManager.getLogger( SessionEventDispatcher.class );

    /** One dispatcher per Engine, lazily created. Engine identity keys the map. */
    private static final ConcurrentHashMap< Engine, SessionEventDispatcher > PER_ENGINE =
        new ConcurrentHashMap<>();

    /**
     * Weak-keyed set of alive sessions. Dead sessions clear automatically via
     * the {@link WeakHashMap} backing. Wrapped in {@link Collections#synchronizedSet}
     * for thread-safe add/iterate; iteration is done via snapshot-then-release
     * (see {@link #actionPerformed}).
     */
    private final Set< WikiSession > sessions =
        Collections.synchronizedSet( Collections.newSetFromMap( new WeakHashMap<>() ) );

    private SessionEventDispatcher( final GroupManager g,
                                     final AuthenticationManager a,
                                     final UserManager u ) {
        g.addWikiEventListener( this );
        a.addWikiEventListener( this );
        u.addWikiEventListener( this );
        LOG.info( "SessionEventDispatcher initialised for engine — single per-engine listener registered on GroupManager + AuthenticationManager + UserManager (replaces per-session registration)." );
    }

    /**
     * Resolve (or create) the dispatcher for {@code engine}. Thread-safe and
     * idempotent — first caller constructs and registers; later callers receive
     * the same instance.
     */
    public static SessionEventDispatcher forEngine( final Engine engine,
                                                     final GroupManager groupMgr,
                                                     final AuthenticationManager authMgr,
                                                     final UserManager userMgr ) {
        if ( engine == null ) throw new IllegalArgumentException( "engine must not be null" );
        if ( groupMgr == null ) throw new IllegalArgumentException( "groupMgr must not be null" );
        if ( authMgr == null ) throw new IllegalArgumentException( "authMgr must not be null" );
        if ( userMgr == null ) throw new IllegalArgumentException( "userMgr must not be null" );
        return PER_ENGINE.computeIfAbsent( engine,
            e -> new SessionEventDispatcher( groupMgr, authMgr, userMgr ) );
    }

    /**
     * Add {@code session} to the alive-set. Sessions that become unreachable
     * are removed by GC automatically (weak-keyed backing map).
     */
    public void register( final WikiSession session ) {
        if ( session == null ) return;
        sessions.add( session );
    }

    /**
     * Dispatch the event to every alive session. Snapshot-then-release pattern
     * — listener callbacks fire outside the set's intrinsic lock so a slow
     * session callback doesn't block concurrent {@link #register} calls.
     */
    @Override
    public void actionPerformed( final WikiEvent event ) {
        final List< WikiSession > snapshot;
        synchronized ( sessions ) {
            if ( sessions.isEmpty() ) return;
            snapshot = new ArrayList<>( sessions );
        }
        for ( final WikiSession s : snapshot ) {
            try {
                s.actionPerformed( event );
            } catch ( final Throwable t ) { //NOPMD — session-callback safety; preserves previous broad-catch posture.
                LOG.warn( "Session {} threw on event {}: {}", s, event, t.toString(), t );
            }
        }
    }

    /** Current alive-session count. Test-only / introspection helper. */
    public int sessionCount() {
        synchronized ( sessions ) {
            return sessions.size();
        }
    }
}

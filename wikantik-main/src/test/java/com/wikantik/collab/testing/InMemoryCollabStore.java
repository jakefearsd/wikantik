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

import com.wikantik.collab.CollabStore;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.Snapshot;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lock-guarded in-memory {@link CollabStore} for the deterministic simulator (F9), with per-operation fault points
 * (F8). It must satisfy {@link AbstractCollabStoreContractTest} exactly as the Postgres store will.
 */
public final class InMemoryCollabStore implements CollabStore {

    /** The mutating operations that carry a fault point. */
    public enum Op {
        CREATE_SESSION, UPDATE_SESSION, APPEND_UPDATES, APPEND_META, WRITE_SNAPSHOT, COMMIT_BASE_CHANGE, TRUNCATE, DELETE
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final Map< UUID, SessionRecord > sessions = new HashMap<>();
    private final Map< UUID, TreeMap< Integer, StoredUpdate > > updates = new HashMap<>();
    private final Map< UUID, TreeMap< Long, StoredMetaOp > > metaOps = new HashMap<>();
    private final Map< UUID, TreeMap< Integer, Snapshot > > snapshots = new HashMap<>();
    private final Map< Op, Integer > calls = new EnumMap<>( Op.class );
    private final Map< Op, Integer > failAt = new EnumMap<>( Op.class );

    /** The {@code nth} (1-based, counted from now) call of {@code op} throws before mutating anything; one-shot. */
    public void failOn( final Op op, final int nth ) {
        if( nth < 1 ) {
            throw new IllegalArgumentException( "nth must be >= 1: " + nth );
        }
        lock.lock();
        try {
            calls.put( op, 0 );
            failAt.put( op, nth );
        } finally {
            lock.unlock();
        }
    }

    /** A deep copy of the stored state (records are immutable); fault points are not copied. */
    public InMemoryCollabStore copy() {
        lock.lock();
        try {
            final InMemoryCollabStore c = new InMemoryCollabStore();
            c.sessions.putAll( sessions );
            updates.forEach( ( k, v ) -> c.updates.put( k, new TreeMap<>( v ) ) );
            metaOps.forEach( ( k, v ) -> c.metaOps.put( k, new TreeMap<>( v ) ) );
            snapshots.forEach( ( k, v ) -> c.snapshots.put( k, new TreeMap<>( v ) ) );
            return c;
        } finally {
            lock.unlock();
        }
    }


    // ---- defensive copies: nested meta values (lists, maps) are mutable, so neither a caller's later mutation nor a
    // ---- reader's can reach the stored state

    private static Object deep( final Object v ) {
        if( v instanceof Map< ?, ? > m ) {
            final Map< String, Object > c = new LinkedHashMap<>();
            m.forEach( ( k, x ) -> c.put( String.valueOf( k ), deep( x ) ) );
            return c;
        }
        if( v instanceof List< ? > l ) {
            final List< Object > c = new ArrayList<>();
            l.forEach( x -> c.add( deep( x ) ) );
            return c;
        }
        return v;
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > deepMeta( final Map< String, Object > m ) {
        return ( Map< String, Object > ) deep( m );
    }

    private static SessionRecord detach( final SessionRecord s ) {
        return s.withBase( s.basePageVersion(), s.baseBody(), deepMeta( s.baseMeta() ), s.baseContentHash() );
    }

    private static Snapshot detach( final Snapshot s ) {
        return new Snapshot( s.version(), s.metaSeq(), s.basePageVersion(), s.body(), deepMeta( s.meta() ), s.local() );
    }

    private static StoredMetaOp detach( final StoredMetaOp o ) {
        return new StoredMetaOp( o.seq(), o.authorKey(), o.field(), deep( o.value() ) );
    }

    private void fault( final Op op ) {
        final Integer target = failAt.get( op );
        if( target == null ) {
            return;
        }
        final int n = calls.merge( op, 1, Integer::sum );
        if( n == target ) {
            failAt.remove( op );
            throw new InjectedFault( "injected failure: " + op + " call #" + n );
        }
    }

    private SessionRecord require( final UUID id ) {
        final SessionRecord s = sessions.get( id );
        if( s == null ) {
            throw new IllegalArgumentException( "no collab session " + id );
        }
        return s;
    }

    private Optional< UUID > idForPage( final String page ) {
        return sessions.values().stream().filter( s -> s.pageName().equals( page ) ).map( SessionRecord::id ).findFirst();
    }

    @Override
    public void createSession( final SessionRecord s ) {
        lock.lock();
        try {
            fault( Op.CREATE_SESSION );
            if( sessions.containsKey( s.id() ) || idForPage( s.pageName() ).isPresent() ) {
                throw new IllegalStateException( "page already has a collab session: " + s.pageName() );
            }
            sessions.put( s.id(), detach( s ) );
            updates.put( s.id(), new TreeMap<>() );
            metaOps.put( s.id(), new TreeMap<>() );
            snapshots.put( s.id(), new TreeMap<>() );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional< SessionRecord > session( final UUID id ) {
        lock.lock();
        try {
            return Optional.ofNullable( sessions.get( id ) ).map( InMemoryCollabStore::detach );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional< SessionRecord > sessionForPage( final String pageName ) {
        lock.lock();
        try {
            return idForPage( pageName ).map( sessions::get ).map( InMemoryCollabStore::detach );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void updateSession( final SessionRecord s ) {
        lock.lock();
        try {
            fault( Op.UPDATE_SESSION );
            final SessionRecord old = require( s.id() );
            if( !old.pageName().equals( s.pageName() ) && idForPage( s.pageName() ).isPresent() ) {
                throw new IllegalStateException( "page already has a collab session: " + s.pageName() );
            }
            sessions.put( s.id(), detach( merged( old, s ) ) );
        } finally {
            lock.unlock();
        }
    }

    /** {@code s}'s mutable fields over {@code old}'s immutable ones (the {@link #updateSession} field rules). */
    private static SessionRecord merged( final SessionRecord old, final SessionRecord s ) {
        return new SessionRecord( old.id(), s.pageName(), s.status(), s.basePageVersion(), s.baseBody(), s.baseMeta(),
            s.baseContentHash(), old.headVersion(), old.metaSeq(), s.hasUnpublished(), s.pendingPublishVersion(),
            s.pendingPublishBaseVersion(), s.pendingPublishAuthor(), old.createdBy(), old.createdAt(), s.lastEditAt() );
    }

    /**
     * Validates every step first and only then mutates, all under the lock, so a failure (including the fault point)
     * changes nothing.
     */
    @Override
    public void commitBaseChange( final UUID id, final List< StoredUpdate > serverUpdates, final Snapshot snapshot,
                                  final SessionRecord updated ) {
        lock.lock();
        try {
            fault( Op.COMMIT_BASE_CHANGE );
            final SessionRecord old = require( id );
            if( !updated.id().equals( id ) ) {
                throw new IllegalArgumentException( "session record " + updated.id() + " is not collab session " + id );
            }
            for( final StoredUpdate u : serverUpdates ) {
                if( !StoredUpdate.SERVER.equals( u.kind() ) ) {
                    throw new IllegalArgumentException( "commitBaseChange appends server updates only, got " + u );
                }
            }
            checkContiguous( old, serverUpdates );
            final int newHead = old.headVersion() + serverUpdates.size();
            if( snapshot.version() != newHead ) {
                throw new IllegalStateException( "snapshot version " + snapshot.version() + " is not the new head " + newHead );
            }
            if( snapshot.basePageVersion() != updated.basePageVersion() ) {
                throw new IllegalArgumentException( "snapshot base page version " + snapshot.basePageVersion()
                    + " is not the session's new base " + updated.basePageVersion() );
            }
            if( !old.pageName().equals( updated.pageName() ) && idForPage( updated.pageName() ).isPresent() ) {
                throw new IllegalStateException( "page already has a collab session: " + updated.pageName() );
            }
            final TreeMap< Integer, StoredUpdate > log = updates.get( id );
            serverUpdates.forEach( u -> log.put( u.version(), u ) );
            snapshots.get( id ).put( snapshot.version(), detach( snapshot ) );
            sessions.put( id, detach( merged( old, updated ).withHead( newHead ) ) );
        } finally {
            lock.unlock();
        }
    }

    private static void checkContiguous( final SessionRecord s, final List< StoredUpdate > list ) {
        int expected = s.headVersion();
        for( final StoredUpdate u : list ) {
            expected++;
            if( u.version() != expected ) {
                throw new IllegalStateException( "update version " + u.version() + " is not " + expected
                    + " (head " + s.headVersion() + ")" );
            }
        }
    }

    @Override
    public void appendUpdates( final UUID id, final List< StoredUpdate > list ) {
        lock.lock();
        try {
            fault( Op.APPEND_UPDATES );
            final SessionRecord s = require( id );
            if( list.isEmpty() ) {
                return;
            }
            checkContiguous( s, list );
            final TreeMap< Integer, StoredUpdate > log = updates.get( id );
            list.forEach( u -> log.put( u.version(), u ) );
            sessions.put( id, s.withHead( s.headVersion() + list.size() ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List< StoredUpdate > updatesSince( final UUID id, final int sinceVersion ) {
        lock.lock();
        try {
            final TreeMap< Integer, StoredUpdate > log = updates.get( id );
            return log == null ? List.of() : new ArrayList<>( log.tailMap( sinceVersion, false ).values() );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void appendMetaOps( final UUID id, final List< StoredMetaOp > ops ) {
        lock.lock();
        try {
            fault( Op.APPEND_META );
            final SessionRecord s = require( id );
            if( ops.isEmpty() ) {
                return;
            }
            long expected = s.metaSeq();
            for( final StoredMetaOp o : ops ) {
                expected++;
                if( o.seq() != expected ) {
                    throw new IllegalStateException( "meta seq " + o.seq() + " is not " + expected
                        + " (metaSeq " + s.metaSeq() + ")" );
                }
            }
            final TreeMap< Long, StoredMetaOp > log = metaOps.get( id );
            ops.forEach( o -> log.put( o.seq(), detach( o ) ) );
            sessions.put( id, s.withMetaSeq( s.metaSeq() + ops.size() ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List< StoredMetaOp > metaOpsSince( final UUID id, final long sinceSeq ) {
        lock.lock();
        try {
            final TreeMap< Long, StoredMetaOp > log = metaOps.get( id );
            return log == null ? List.of()
                : log.tailMap( sinceSeq, false ).values().stream().map( InMemoryCollabStore::detach ).toList();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void writeSnapshot( final UUID id, final Snapshot s ) {
        lock.lock();
        try {
            fault( Op.WRITE_SNAPSHOT );
            require( id );
            snapshots.get( id ).put( s.version(), detach( s ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional< Snapshot > latestSnapshot( final UUID id ) {
        lock.lock();
        try {
            final TreeMap< Integer, Snapshot > m = snapshots.get( id );
            return m == null || m.isEmpty() ? Optional.empty() : Optional.of( detach( m.lastEntry().getValue() ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional< Snapshot > snapshotAtOrBelow( final UUID id, final int version ) {
        lock.lock();
        try {
            final TreeMap< Integer, Snapshot > m = snapshots.get( id );
            final Map.Entry< Integer, Snapshot > e = m == null ? null : m.floorEntry( version );
            return e == null ? Optional.empty() : Optional.of( detach( e.getValue() ) );
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void truncateUpdatesBelow( final UUID id, final int cut ) {
        lock.lock();
        try {
            fault( Op.TRUNCATE );
            require( id );
            final TreeMap< Integer, Snapshot > snaps = snapshots.get( id );
            final Integer floor = snaps.floorKey( cut );
            if( floor != null ) {
                updates.get( id ).headMap( floor, true ).clear();
                snaps.headMap( floor, false ).clear();
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void deleteSession( final UUID id ) {
        lock.lock();
        try {
            fault( Op.DELETE );
            require( id );
            sessions.remove( id );
            updates.remove( id );
            metaOps.remove( id );
            snapshots.remove( id );
        } finally {
            lock.unlock();
        }
    }
}

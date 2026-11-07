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

import com.wikantik.collab.CollabSession.Hooks;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.SessionRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The live sessions of this node, one per page (section 7.7): join creates or loads a page's session, {@link #tick}
 * runs the timers, and idle sessions leave memory.
 *
 * <p><b>Join</b> refuses a derived page (409 {@code derived-page}, section 9), a caller without {@code edit}, or without
 * {@code createPages} on a page that does not exist yet (403 {@code forbidden}), and a full session (429
 * {@code session-full}); with the kill switch off it answers {@link Disabled}. A store failure while opening or
 * seeding is 503 {@code store}; a malformed initial text 400 {@code rejected}.</p>
 *
 * <p><b>Tick</b> (driven every second by the wiring): answers long-polls past their timeout, evicts participants not
 * seen within {@code presence.timeoutSeconds} (E13; their next request meets 409 {@code rejoin}), and every
 * {@code permission.recheckSeconds} re-checks each participant's {@code edit} with its wiki session (A5, E30) and
 * truncates the log (A10). A session without participants for {@code memory.idleEvictMinutes} leaves memory; its draft
 * is deleted when, by the session's memory, it has no unpublished changes (section 7.7, A10 Minor), and kept otherwise.
 * The stored {@code has_unpublished} flag is never consulted: it is written only at snapshot cadence. Each tick
 * first calls {@link CollabSession#settle}, the only retry of an owed external merge or an unfinished publish, and a
 * session that still owes one (a publish in its save, a stored pending-publish marker, a queued or failed merge) is
 * never evicted.</p>
 *
 * <p><b>Wedged sessions.</b> A commit the store reported as failed may still have landed (an ambiguous commit), after
 * which every append fails. The HTTP surface reports outcomes through {@link #observe}; after
 * {@value #STORE_FAILURE_LIMIT} consecutive 503 {@code store} answers the session leaves memory without touching its
 * draft, so the next join reloads it from the store.</p>
 *
 * <p><b>Concurrency.</b> Each page has a {@link SessionSlot} whose lock serialises opening, joining and evicting it, so
 * an idle eviction racing a join (E33) either sees the participant and stays, or finishes (draft deleted or kept)
 * before the join, which then takes a fresh slot and reloads or creates. Lock order: slot, then the page map, then the
 * session. Pushes and pulls do not come through here: {@link #live} is a lock-free lookup.</p>
 *
 * <p>The per-load id tag (CollabSession's {@code LOADS}) is a static map keyed by store instance, so two registries on
 * two stores never share a count.</p>
 */
public final class CollabSessionRegistry {

    private static final Logger LOG = LogManager.getLogger( CollabSessionRegistry.class );

    /** Consecutive 503 {@code store} answers after which a session is dropped from memory for a reload. */
    public static final int STORE_FAILURE_LIMIT = 3;
    /** {@code wikantik.api.maxPageBytes}'s default, for the six-argument constructor. */
    public static final long DEFAULT_MAX_PAGE_BYTES = 262_144L;
    static final String DERIVED = "derived-page";
    static final String FORBIDDEN = "forbidden";
    static final String FULL = "session-full";
    private static final String DERIVED_FROM = "derived_from";

    /** The answer to {@link #join}. */
    public sealed interface JoinOutcome permits Joined, Refused, Disabled {
    }

    /** Joined {@code session}; {@code response} is the section 6.2 join body. */
    public record Joined( CollabSession session, JoinResponse response ) implements JoinOutcome {
    }

    /** Refused with an HTTP status and a reason. */
    public record Refused( int status, String reason ) implements JoinOutcome {
    }

    /** The kill switch is off (the HTTP surface answers 503 {@code collab-disabled}). */
    public record Disabled() implements JoinOutcome {
    }

    private final CollabPorts ports;
    private final CollabStore store;
    private final CollabPageWriter writer;
    private final CollabPermissions permissions;
    private final Clock clock;
    private final CollabConfig config;
    private final long maxPageBytes;
    private final ReentrantLock mapLock = new ReentrantLock();
    /** Guarded by {@code mapLock}. */
    private final Map< String, SessionSlot > byPage = new HashMap<>();
    /** Opened slots only. */
    private final Map< UUID, SessionSlot > byId = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public CollabSessionRegistry( final CollabStore store, final CollabPageWriter writer,
                                  final CollabPermissions permissions, final CollabIdentity identity, final Clock clock,
                                  final CollabConfig config ) {
        this( store, writer, permissions, identity, clock, config, DEFAULT_MAX_PAGE_BYTES );
    }

    /** @param maxPageBytes {@code wikantik.api.maxPageBytes}, the page size limit behind section 7.2's 413 */
    public CollabSessionRegistry( final CollabStore store, final CollabPageWriter writer,
                                  final CollabPermissions permissions, final CollabIdentity identity, final Clock clock,
                                  final CollabConfig config, final long maxPageBytes ) {
        this.ports = new CollabPorts( store, writer, identity, permissions, clock );   // checks every port for null
        this.store = store;
        this.writer = writer;
        this.permissions = permissions;
        this.clock = clock;
        this.config = Objects.requireNonNull( config, "config" );
        this.maxPageBytes = maxPageBytes;
        this.enabled = config.enabled();
    }

    // ---- kill switch -----------------------------------------------------------------------------------------

    /** Off refuses new joins; live sessions and stored drafts are kept. */
    public void setEnabled( final boolean on ) {
        enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The settings this registry runs with (the HTTP surface reads the long-poll timeout and body limit). */
    public CollabConfig config() {
        return config;
    }

    // ---- join ------------------------------------------------------------------------------------------------

    /** Creates or loads {@code pageName}'s session and joins it (see the class comment for the refusals). */
    public JoinOutcome join( final String pageName, final JoinRequest r ) {
        if( !enabled ) {
            return new Disabled();
        }
        final PageState latest = writer.latest( pageName ).orElse( null );
        final Refused refused = refuse( pageName, r, latest );
        if( refused != null ) {
            return refused;
        }
        while( true ) {
            final SessionSlot slot = slotFor( pageName );
            slot.lock();
            try {
                if( !slot.closed() ) {
                    return joinLocked( slot, pageName, r, latest );
                }
            } finally {
                slot.unlock();
            }
            // evicted while we waited for its lock: the eviction unmapped it, so the next slot is a fresh one
        }
    }

    /** {@code latest} is null when the page does not exist. 403 comes first: a non-editor learns nothing more. */
    private Refused refuse( final String pageName, final JoinRequest r, final PageState latest ) {
        if( !mayJoin( r, pageName, latest == null ) ) {
            return new Refused( 403, FORBIDDEN );
        }
        if( latest != null && latest.meta().containsKey( DERIVED_FROM ) ) {
            return new Refused( 409, DERIVED );
        }
        return null;
    }

    /** {@code edit}, plus {@code createPages} for a new page; a check that throws fails closed (logged). */
    private boolean mayJoin( final JoinRequest r, final String pageName, final boolean create ) {
        final Object who = r.wikiSession();
        try {
            return permissions.canEdit( who, pageName ) && ( !create || permissions.canCreate( who, pageName ) );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: the permission check for {} joining {} failed; refusing: {}", r.authorKey(), pageName,
                e.toString() );
            return false;
        }
    }

    private SessionSlot slotFor( final String pageName ) {
        mapLock.lock();
        try {
            return byPage.computeIfAbsent( pageName, k -> new SessionSlot() );
        } finally {
            mapLock.unlock();
        }
    }

    private JoinOutcome joinLocked( final SessionSlot slot, final String pageName, final JoinRequest r,
                                    final PageState latest ) {
        if( slot.session() == null && !open( slot, pageName, r, latest ) ) {
            return new Refused( 503, CollabSession.STORE );
        }
        final CollabSession s = slot.session();
        if( Hooks.members( s ).size() >= config.maxParticipants() ) {
            return new Refused( 429, FULL );
        }
        final JoinResponse response;
        try {
            response = s.join( r );
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "collab: join of {} by {} refused: {}", pageName, r.authorKey(), e.toString() );
            return new Refused( 400, CollabSession.REJECTED );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: join of {} by {} failed in the store: {}", pageName, r.authorKey(), e.toString() );
            return new Refused( 503, CollabSession.STORE );
        }
        slot.joined();
        return new Joined( s, response );
    }

    /** Loads the page's stored draft or creates a session; false (slot discarded, logged) on a store failure. */
    private boolean open( final SessionSlot slot, final String pageName, final JoinRequest r,
                          final PageState latest ) {
        final CollabSession s;
        try {
            final UUID stored = store.sessionForPage( pageName ).map( SessionRecord::id ).orElse( null );
            s = stored != null
                ? CollabSession.load( stored, ports, config, maxPageBytes )
                : CollabSession.create( CollabSession.newRecord( UUID.randomUUID(), pageName,
                    Optional.ofNullable( latest ), r.authorKey(), clock ), ports, config, maxPageBytes );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: could not open the session of {}: {}", pageName, e.toString() );
            slot.close();
            unmap( slot, null );
            return false;
        }
        slot.opened( s, clock.instant() );
        byId.put( s.id(), slot );
        return true;
    }

    // ---- lookups ---------------------------------------------------------------------------------------------

    /** The live session with this id, for push, pull, publish and leave. */
    public Optional< CollabSession > live( final UUID id ) {
        final SessionSlot slot = byId.get( id );
        return slot == null ? Optional.empty() : Optional.ofNullable( slot.session() );
    }

    /** The live session of a page, if one is in memory. */
    public Optional< CollabSession > liveForPage( final String pageName ) {
        final SessionSlot slot = slot( pageName );
        return slot == null ? Optional.empty() : Optional.ofNullable( slot.session() );
    }

    /** The number of sessions in memory. */
    public int liveCount() {
        return byId.size();
    }

    private SessionSlot slot( final String pageName ) {
        mapLock.lock();
        try {
            return byPage.get( pageName );
        } finally {
            mapLock.unlock();
        }
    }

    // ---- tick ------------------------------------------------------------------------------------------------

    /**
     * The timers: long-poll timeouts, presence timeouts, the settle retry, permission re-checks and truncation, idle
     * eviction. Nothing escapes, not even an {@link Error}: a periodic task that throws is silently cancelled by its
     * executor, which would stop every session's timers.
     */
    @SuppressWarnings( "PMD.AvoidCatchingThrowable" )
    public void tick() {
        try {
            final Instant now = clock.instant();
            for( final SessionSlot slot : List.copyOf( byId.values() ) ) {
                if( !slot.closed() ) {
                    tickSession( slot, slot.session(), now );
                }
            }
        } catch( final Throwable t ) {
            LOG.error( "collab: the registry tick failed; the next tick retries: {}", t.toString(), t );
        }
    }

    /** Each step on its own, so one failing step neither skips the others nor the other sessions. */
    private void tickSession( final SessionSlot slot, final CollabSession s, final Instant now ) {
        slot.step( s, now, "waiter expiry", () -> s.expireWaiters().forEach( Runnable::run ) );
        slot.step( s, now, "presence", () -> SessionSweeps.expirePresence( s,
            now.minusSeconds( config.presenceTimeoutSeconds() ) ) );
        // the only retry of an owed external merge or an unfinished publish (Task 4); empty sessions owing nothing skip
        slot.step( s, now, "settle", () -> {
            if( slot.needsSettle() ) {
                s.settle();
            }
        } );
        slot.step( s, now, "permission re-check", () -> {
            if( slot.recheckDue( now, config.permissionRecheckSeconds() ) ) {
                SessionSweeps.recheckEdit( s, permissions );
                s.truncateLog();
            }
        } );
        slot.step( s, now, "idle eviction", () -> evictIfIdle( slot, s, now ) );
    }

    private void evictIfIdle( final SessionSlot slot, final CollabSession s, final Instant now ) {
        if( !slot.tryLock() ) {
            return;   // a join is opening or joining it: not idle
        }
        try {
            final Duration idle = Duration.ofMinutes( config.memoryIdleEvictMinutes() );
            final boolean idleLongEnough = !slot.closed() && slot.idle( now, idle );
            if( idleLongEnough && !Hooks.owesThePageStore( s ) ) {
                evictLocked( slot, s, true );
            }
        } finally {
            slot.unlock();
        }
    }

    /**
     * Takes the session out of memory, under its slot lock: drops any participants; then, for an idle eviction, deletes
     * the draft when the session has no unpublished changes and truncates its log otherwise; then unmaps the slot.
     */
    private void evictLocked( final SessionSlot slot, final CollabSession s, final boolean idle ) {
        slot.close();
        SessionSweeps.evictAll( s );
        if( idle ) {
            if( Hooks.hasUnpublishedChanges( s ) ) {
                s.truncateLog();
            } else {
                deleteDraft( s );
            }
        }
        unmap( slot, s );
        LOG.debug( "collab session {} ({}) left memory", s.id(), s.pageName() );
    }

    private void deleteDraft( final CollabSession s ) {
        try {
            store.deleteSession( s.id() );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): unchanged draft not deleted; the next join reloads it: {}", s.id(),
                s.pageName(), e.toString() );
        }
    }

    private void unmap( final SessionSlot slot, final CollabSession s ) {
        mapLock.lock();
        try {
            byPage.values().removeIf( x -> x == slot );
        } finally {
            mapLock.unlock();
        }
        if( s != null ) {
            byId.remove( s.id(), slot );
        }
    }

    // ---- outcomes, authors, renames --------------------------------------------------------------------------

    /**
     * An answer the HTTP surface gave for session {@code id}: a 503 {@code store} counts towards
     * {@value #STORE_FAILURE_LIMIT}, a 2xx resets the count, anything else leaves it. Unknown ids are ignored.
     */
    public void observe( final UUID id, final int status, final String reason ) {
        final SessionSlot slot = byId.get( id );
        if( slot == null ) {
            return;
        }
        if( status == 503 && CollabSession.STORE.equals( reason ) ) {
            if( slot.storeFailed( STORE_FAILURE_LIMIT ) ) {
                evictWedged( slot );
            }
        } else if( status >= 200 && status < 300 ) {
            slot.storeSucceeded();
        }
    }

    /**
     * Drops a wedged session, unless this object's publish is in its save: a reload then would settle the pending marker
     * while this object finishes the publish (two writers). The count stays at the limit, so the next store failure after
     * the publish ends evicts. An owed merge does not defer it: the reload settles that.
     */
    private void evictWedged( final SessionSlot slot ) {
        slot.lock();
        try {
            final CollabSession s = slot.session();
            if( slot.closed() || deferredByPublish( s ) ) {
                return;
            }
            // participants go first, so no new publish can start; then check again for one that started meanwhile
            SessionSweeps.evictAll( s );
            if( deferredByPublish( s ) ) {
                return;   // stays live; its participants rejoin this object
            }
            LOG.warn( "collab session {} ({}): {} consecutive store failures; dropped from memory for a reload",
                s.id(), s.pageName(), STORE_FAILURE_LIMIT );
            evictLocked( slot, s, false );
        } finally {
            slot.unlock();
        }
    }

    private static boolean deferredByPublish( final CollabSession s ) {
        if( !Hooks.publishing( s ) ) {
            return false;
        }
        LOG.warn( "collab session {} ({}): store failures, but a publish is in its save; reload deferred", s.id(),
            s.pageName() );
        return true;
    }

    /** Evicts every participant of {@code authorKey} in every live session (a deleted user, A10 Minor). */
    public void evictAuthor( final String authorKey ) {
        for( final SessionSlot slot : List.copyOf( byId.values() ) ) {
            SessionSweeps.evictAuthor( slot.session(), authorKey );
        }
    }

    /**
     * The page {@code from} was renamed to {@code to}: its live session or stored draft follows.
     *
     * @throws IllegalStateException when {@code to} already has a session, live or stored
     */
    public void rekey( final String from, final String to ) {
        final SessionSlot slot = slot( from );
        if( slot == null ) {
            renameStored( from, to );
            return;
        }
        slot.lock();
        try {
            if( slot.closed() || slot.session() == null ) {
                renameStored( from, to );
            } else {
                moveLive( slot, from, to );
            }
        } finally {
            slot.unlock();
        }
    }

    private void moveLive( final SessionSlot slot, final String from, final String to ) {
        mapLock.lock();
        try {
            if( byPage.containsKey( to ) ) {
                throw new IllegalStateException( "collab: cannot move the session of " + from + " to " + to
                    + ": it has a live session" );
            }
            Hooks.rename( slot.session(), to );
            byPage.remove( from, slot );
            byPage.put( to, slot );
        } finally {
            mapLock.unlock();
        }
    }

    private void renameStored( final String from, final String to ) {
        store.sessionForPage( from ).ifPresent( rec -> store.updateSession( rec.withPageName( to ) ) );
    }

    /** The page was deleted: its live session or stored draft is frozen for an administrator. */
    public void freeze( final String pageName ) {
        final SessionSlot slot = slot( pageName );
        if( slot != null && freezeLive( slot ) ) {
            return;
        }
        store.sessionForPage( pageName ).ifPresent( rec -> store.updateSession( rec.withStatus( SessionRecord.FROZEN ) ) );
    }

    private static boolean freezeLive( final SessionSlot slot ) {
        slot.lock();
        try {
            if( slot.closed() || slot.session() == null ) {
                return false;
            }
            Hooks.freeze( slot.session() );
            return true;
        } finally {
            slot.unlock();
        }
    }
}

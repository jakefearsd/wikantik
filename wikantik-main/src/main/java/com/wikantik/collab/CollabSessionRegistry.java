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
import com.wikantik.collab.model.DraftListing;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.PageState;
import com.wikantik.collab.model.PurgeOutcome;
import com.wikantik.collab.model.SessionRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;

/**
 * The live sessions of this node, one per page (section 7.7): join creates or loads a page's session, {@link #tick}
 * runs the timers, and idle sessions leave memory.
 *
 * <p><b>Join</b> refuses a derived page (409 {@code derived-page}, section 9), a caller without {@code edit}, or without
 * {@code createPages} on a page that does not exist yet (403 {@code forbidden}), and a full session (429
 * {@code session-full}); with the kill switch off it answers {@link Disabled}. A store failure while opening or
 * seeding is 503 {@code store}; a malformed initial text 400 {@code rejected}. A rejoin naming its prior clientID
 * replaces its own stale entry (A13) and takes no new seat. A join into a frozen draft (its page was deleted) is a join
 * on a page that does not exist, so it needs {@code createPages}; it is answered with the draft's text, its pushes are
 * 409 {@code frozen}, and its first pull is told {@code page-deleted} (the join's {@code eventSeq} stops just before
 * it).</p>
 *
 * <p><b>Tick</b> (driven every second by the wiring): answers long-polls past their timeout, evicts participants not
 * seen within {@code presence.timeoutSeconds} (E13; their next request meets 409 {@code rejoin}), and every
 * {@code permission.recheckSeconds} re-checks each participant's {@code edit} with its wiki session (A5, E30) and
 * truncates the log (A10). A session without participants for {@code memory.idleEvictMinutes} leaves memory; its draft
 * is deleted when, by the session's memory, it has no unpublished changes (section 7.7, A10 Minor), and kept otherwise.
 * The stored {@code has_unpublished} flag is never consulted: it is written only at snapshot cadence. The tick settles
 * a session ({@link OrphanSweep#settle}: one page read, then a reconcile) on every tick while it owes the page store
 * something (a publish in its save, a stored pending-publish marker, a queued or failed merge; such a session is never
 * evicted) and otherwise, when it has participants, every {@link SessionSlot#SETTLE_EVERY}: the page listener delivers
 * saves at once, and this is the safety net for a missed event. Each tick also retries owed renames (with backoff,
 * {@link Renames}), runs the orphan sweep on that same read ({@link OrphanSweep}: a draft whose page has been missing
 * for {@link #ORPHAN_GRACE} with no rename owed is frozen), reloads the draft index every
 * {@link DraftIndex#RELOAD_EVERY}, runs draft retention at most hourly ({@link DraftAdmin}), and, once after
 * {@link #requestEditRecheck} (a group changed), re-checks every participant's {@code edit}. Step failures are logged
 * by {@link TickLog}: a warning clock per step, a repeating {@link Error} once per window.</p>
 *
 * <p><b>Drafts</b> ({@link DraftAdmin}): the administrators' {@link #drafts} list, {@link #purge} (participants are
 * evicted with {@link #PURGED}; never publishes) and retention ({@code draft.retentionDays}). {@link #draftActive}
 * answers from memory only: a live session answers for itself, the {@link DraftIndex} for stored-only drafts with
 * unpublished changes. A session leaving memory stores its draft markers ({@code hasUnpublished}, {@code lastEditAt})
 * first.</p>
 *
 * <p><b>Engine events</b> arrive through {@code CollabPageEventListener} (adapter package): {@link #rekey} and
 * {@link #settleAll} on a rename, {@link #freeze} on a true delete (ruling Q10), {@link #evictSignedOut} and
 * {@link #evictSignedOutAll} on session expiry and logout (A13), {@link #requestEditRecheck} on a group change.</p>
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
    /** How long the orphan sweep must find a draft's page missing before it freezes the draft ({@link OrphanSweep}). */
    public static final Duration ORPHAN_GRACE = OrphanSweep.GRACE;
    /** {@code wikantik.api.maxPageBytes}'s default, for the six-argument constructor. */
    public static final long DEFAULT_MAX_PAGE_BYTES = 262_144L;
    static final String DERIVED = "derived-page";
    static final String FORBIDDEN = "forbidden";
    static final String FULL = "session-full";
    /** A participant's eviction reason when an administrator purges the draft. */
    public static final String PURGED = "purged";
    private static final String REGISTRY = "collab registry";

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
    private final PageSlots slots = new PageSlots();
    private final Renames renames;
    private final OrphanSweep orphans;
    /** Opened slots only. */
    private final Map< UUID, SessionSlot > byId = new ConcurrentHashMap<>();
    private final DraftIndex index = new DraftIndex();
    private final DraftAdmin admin;
    /** The registry-wide tick steps' failure log (each session slot has its own). */
    private final TickLog tickLog = new TickLog();
    /** Set by {@link #requestEditRecheck}; the next tick re-checks every live session's participants. */
    private final AtomicBoolean editRecheckRequested = new AtomicBoolean();
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
        this( new CollabPorts( store, writer, identity, permissions, clock ), config, maxPageBytes );
    }

    /**
     * Over every port, the audit and metrics sinks included.
     *
     * @param maxPageBytes {@code wikantik.api.maxPageBytes}, the page size limit behind section 7.2's 413
     */
    public CollabSessionRegistry( final CollabPorts ports, final CollabConfig config, final long maxPageBytes ) {
        this.ports = Objects.requireNonNull( ports, "ports" );
        this.store = ports.store();
        this.writer = ports.writer();
        this.permissions = ports.permissions();
        this.clock = ports.clock();
        this.config = Objects.requireNonNull( config, "config" );
        this.maxPageBytes = maxPageBytes;
        this.enabled = config.enabled();
        this.renames = new Renames( slots, store, clock, this::freezeAt, writer::exists );
        this.orphans = new OrphanSweep( writer, renames, this::freezeAt );
        this.admin = new DraftAdmin( store, slots, byId, index, this::unmap, ports.audit() );
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

    /** The page size limit ({@code wikantik.api.maxPageBytes}); the HTTP surface sizes the join body from it. */
    public long maxPageBytes() {
        return maxPageBytes;
    }

    /** The metrics sink (the wiring binds the gauges over this registry). */
    public CollabMetrics metrics() {
        return ports.metrics();
    }

    // ---- join ------------------------------------------------------------------------------------------------

    /** Creates or loads {@code pageName}'s session and joins it (see the class comment for the refusals). */
    public JoinOutcome join( final String pageName, final JoinRequest r ) {
        if( !enabled ) {
            return new Disabled();
        }
        if( !renames.joinable( pageName ) ) {
            // a rename into this page is still owed: opening a session now would leave the renamed draft no home
            LOG.warn( "collab: join of {} by {} refused: a rename into it is pending", pageName, r.authorKey() );
            return new Refused( 503, CollabSession.STORE );
        }
        while( true ) {
            // read (again, on a retry): a rename or delete may have moved the page meanwhile
            final PageState latest;
            try {
                latest = writer.latest( pageName ).orElse( null );
            } catch( final RuntimeException e ) {
                // unknown is not "missing": never open a new-page session over a page the store could not read
                LOG.warn( "collab: join of {} by {} refused: the page could not be read: {}", pageName, r.authorKey(),
                    e.toString() );
                return new Refused( 503, CollabSession.STORE );
            }
            final Refused refused = refuse( pageName, r, latest );
            if( refused != null ) {
                return refused;
            }
            final SessionSlot slot = slots.slotFor( pageName );
            slot.lock();
            try {
                final JoinOutcome out = slot.closed() ? null : joinLocked( slot, pageName, r, latest );
                if( out != null ) {
                    return out;
                }
            } finally {
                slot.unlock();
            }
            // retried: the slot was evicted or moved by a rekey while we waited for its lock (the next slot is a fresh
            // one), or the page changed between the read above and the open
        }
    }

    /** {@code latest} is null when the page does not exist. 403 comes first: a non-editor learns nothing more. */
    private Refused refuse( final String pageName, final JoinRequest r, final PageState latest ) {
        return JoinRefusals.refuse( permissions, pageName, r, latest );
    }

    /** Null to retry (the page moved under the join); else the outcome. */
    private JoinOutcome joinLocked( final SessionSlot slot, final String pageName, final JoinRequest r,
                                    final PageState latest ) {
        if( slot.session() == null ) {
            final Opened o = open( slot, pageName, r, latest );
            if( o != Opened.OK ) {
                return o == Opened.FAILED ? new Refused( 503, CollabSession.STORE ) : null;
            }
        }
        final CollabSession s = slot.session();
        if( !s.pageName().equals( pageName ) ) {
            return null;   // a rekey moved this slot to another name while we waited: take the page's own slot
        }
        // a rejoin replacing its own stale entry (A13) takes no new seat
        final int seats = Hooks.members( s ).size() - ( Hooks.replacesStale( s, r ) ? 1 : 0 );
        if( seats >= config.maxParticipants() ) {
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

    /** How opening a slot went. */
    private enum Opened { OK, FAILED, STALE }

    /**
     * Loads the page's stored draft or creates a session; FAILED (slot discarded, logged) on a store failure. A new
     * session is created only from the page as it is now: when it no longer matches the join's read (a rename or delete
     * landed in between, so the draft may have just moved away), STALE, and the join retries from a fresh read.
     */
    private Opened open( final SessionSlot slot, final String pageName, final JoinRequest r,
                         final PageState latest ) {
        final CollabSession s;
        final boolean created;
        try {
            final UUID stored = store.sessionForPage( pageName ).map( SessionRecord::id ).orElse( null );
            created = stored == null;
            if( stored == null && !PageSlots.samePage( latest, writer.latest( pageName ).orElse( null ) ) ) {
                LOG.debug( "collab: {} changed while {} was joining; retrying", pageName, r.authorKey() );
                return Opened.STALE;
            }
            s = stored != null
                ? CollabSession.load( stored, ports, config, maxPageBytes )
                : CollabSession.create( CollabSession.newRecord( UUID.randomUUID(), pageName,
                    Optional.ofNullable( latest ), r.authorKey(), clock ), ports, config, maxPageBytes );
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: could not open the session of {}: {}", pageName, e.toString() );
            slot.close();
            unmap( slot, null );
            return Opened.FAILED;
        }
        slot.opened( s, clock.instant() );
        byId.put( s.id(), slot );
        if( created ) {
            ports.audit().sessionOpened( s.id(), pageName, r.authorKey() );   // a reload is not an open
        }
        return Opened.OK;
    }

    // ---- lookups ---------------------------------------------------------------------------------------------

    /** The live session with this id, for push, pull, publish and leave. */
    public Optional< CollabSession > live( final UUID id ) {
        final SessionSlot slot = byId.get( id );
        return slot == null ? Optional.empty() : Optional.ofNullable( slot.session() );
    }

    /** The live session of a page, if one is in memory. */
    public Optional< CollabSession > liveForPage( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        return slot == null ? Optional.empty() : Optional.ofNullable( slot.session() );
    }

    /** The number of sessions in memory. */
    public int liveCount() {
        return byId.size();
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
            renames.retryPending();
            final Instant now = clock.instant();
            tickLog.run( REGISTRY, "draft index", now, () -> index.reloadIfDue( store, now ) );
            tickLog.run( REGISTRY, "draft retention", now,
                () -> admin.expireIfDue( config.draftRetentionDays(), now ) );
            final boolean recheck = editRecheckRequested.getAndSet( false );
            for( final SessionSlot slot : List.copyOf( byId.values() ) ) {
                if( !slot.closed() ) {
                    tickSession( slot, slot.session(), now, recheck );
                }
            }
        } catch( final Throwable t ) {
            // LOG.error justified: an escaped Throwable (often an Error) would cancel every session's timers if rethrown
            LOG.error( "collab: the registry tick failed; the next tick retries: {}", t.toString(), t );
        }
    }

    /** Each step on its own, so one failing step neither skips the others nor the other sessions. */
    private void tickSession( final SessionSlot slot, final CollabSession s, final Instant now, final boolean recheck ) {
        slot.step( s, now, "waiter expiry", () -> s.expireWaiters().forEach( Runnable::run ) );
        slot.step( s, now, "presence", () -> SessionSweeps.expirePresence( s,
            now.minusSeconds( config.presenceTimeoutSeconds() ) ) );
        // the safety net for a missed page event and the only retry of an owed merge or an unfinished publish; its one
        // page read is shared with the orphan sweep
        final AtomicReference< OrphanSweep.Seen > seen = new AtomicReference<>();
        slot.step( s, now, "settle", () -> seen.set( orphans.settle( slot, s, now ) ) );
        slot.step( s, now, "orphan sweep", () -> orphans.sweep( slot, s, now, seen.get() ) );
        slot.step( s, now, "requested re-check", () -> {
            if( recheck ) {
                s.recheckEdits();   // a group changed (E29): at most one tick later
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
     * Takes the session out of memory, under its slot lock: drops any participants; then the draft is deleted (an idle
     * eviction without unpublished changes) or its markers stored ({@link DraftAdmin#unloaded}); then unmaps the slot.
     */
    private void evictLocked( final SessionSlot slot, final CollabSession s, final boolean idle ) {
        slot.close();
        SessionSweeps.evictAll( s );
        admin.unloaded( s, idle );
        unmap( slot, s );
        LOG.debug( "collab session {} ({}) left memory", s.id(), s.pageName() );
    }

    private void unmap( final SessionSlot slot, final CollabSession s ) {
        slots.unmap( slot );
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
            if( slot.closed() || SessionSweeps.deferredByPublish( s ) ) {
                return;
            }
            // participants go first, so no new publish can start; then check again for one that started meanwhile
            SessionSweeps.evictAll( s );
            if( SessionSweeps.deferredByPublish( s ) ) {
                return;   // stays live; its participants rejoin this object
            }
            LOG.warn( "collab session {} ({}): {} consecutive store failures; dropped from memory for a reload",
                s.id(), s.pageName(), STORE_FAILURE_LIMIT );
            evictLocked( slot, s, false );
        } finally {
            slot.unlock();
        }
    }


    /**
     * Evicts every participant of {@code authorKey} in every live session, with {@code account-deleted} (a deleted user,
     * A10 Minor); returns how many.
     */
    public int evictAuthor( final String authorKey ) {
        return forEachLive( s -> SessionSweeps.evictAuthor( s, authorKey ) );
    }

    /**
     * Evicts the participants holding {@code wikiSession} in every live session, with {@code signed-out} (the HTTP
     * session expired, A13: the client keeps its edits and asks the user to log in); returns how many.
     */
    public int evictSignedOut( final Object wikiSession ) {
        return forEachLive( s -> SessionSweeps.evictSignedOut( s, wikiSession ) );
    }

    /**
     * Evicts every participant whose wiki session {@link CollabPermissions#signedIn} reports signed out, with
     * {@code signed-out} (a logout, whose event names no session, A13); returns how many.
     */
    public int evictSignedOutAll() {
        return forEachLive( s -> SessionSweeps.evictSignedOutAll( s, permissions ) );
    }

    /** The next {@link #tick} re-checks {@code edit} for every participant of every live session (a group changed). */
    public void requestEditRecheck() {
        editRecheckRequested.set( true );
    }

    /**
     * Reconciles every live session with its page by content hash (after a rename, whose referrer rewrites fire no save
     * event, A1). One session failing is logged and the others go on; the tick retries what is owed.
     */
    public void settleAll() {
        forEachLive( SessionSweeps::settleQuietly );
    }

    // ---- drafts: the admin list, purge, the draft-active index, the contributor ledger -------------------------

    /** Every stored draft (active and frozen), oldest edit first, overlaid with its live session's state. */
    public List< DraftListing > drafts() {
        return admin.drafts();
    }

    /**
     * Administrator {@code adminKey}'s purge of draft {@code id}: 204, 404 {@code unknown}, 423
     * {@code publish-in-progress} or 503 {@code store} (the draft is kept). Every participant is evicted with
     * {@link #PURGED} first. Never publishes. A 204 is audited ({@link CollabAudit#purged}).
     */
    public PurgeOutcome purge( final UUID id, final String adminKey ) {
        return admin.purge( id, adminKey );
    }

    /**
     * True when someone is editing {@code pageName} or its draft holds unpublished changes. A live session answers for
     * itself (participants, or unpublished changes); otherwise the draft index answers for a stored-only draft with
     * unpublished changes (reloaded every {@link DraftIndex#RELOAD_EVERY}, updated at once on this node's changes).
     * Never reads the store.
     */
    public boolean draftActive( final String pageName ) {
        return admin.draftActive( pageName );
    }

    /**
     * The node is shutting down (after the tick has stopped): stores the draft markers of every live session, so a
     * restart reads each draft's unpublished state and last edit. Best-effort and never throws: each session on its own,
     * a failure logged at WARN. Sessions stay in memory; nothing is evicted.
     */
    public void close() {
        admin.storeAllMarkers();
    }

    /**
     * The participants across the live sessions, read without any session lock (each roster keeps a volatile count),
     * so the participants gauge never waits for a busy session.
     */
    public int participantCount() {
        return forEachLive( Hooks::participantsLockFree );
    }

    /**
     * The contributors credited to each version of the page whose {@code canonical_id} is {@code canonicalId}; empty
     * (logged) when the store cannot tell.
     */
    public Map< Integer, List< String > > contributorsByVersion( final String canonicalId ) {
        return admin.contributorsByVersion( canonicalId );
    }

    /** True when {@code pageName} has a draft in the store, active or frozen. */
    public boolean hasDraft( final String pageName ) {
        return store.sessionForPage( pageName ).isPresent();
    }

    private int forEachLive( final ToIntFunction< CollabSession > f ) {
        return SessionSweeps.forEachLive( byId.values(), f );
    }

    /**
     * The page {@code from} was renamed to {@code to}: its live session or stored draft follows ({@link Renames}), and
     * so does a draft still owed a rename into {@code from} (a chain). A rename that fails is remembered: the tick
     * retries it with backoff, and a join of {@code to} retries it first and is refused (503 {@code store}) rather than
     * open a second session while it is owed. When {@code to} has a draft of its own the rename is refused and the
     * orphan is frozen under {@code from} with a WARN.
     *
     * @throws IllegalStateException when {@code to} already has a session, live or stored
     */
    public void rekey( final String from, final String to ) {
        try {
            renames.rekey( from, to );
        } finally {
            admin.reindex( from, to );
        }
    }

    /**
     * The page was deleted (ruling Q10: only a true delete freezes): its live session or stored draft is frozen for an
     * administrator. A draft still owed a rename into the deleted page never lands there: it is frozen where it is.
     */
    public void freeze( final String pageName ) {
        final String owedFrom = renames.dropInto( pageName );
        freezeAt( pageName );
        if( owedFrom != null ) {
            LOG.warn( "collab: {} was deleted while the draft of {} was owed a rename into it; frozen under {}", pageName,
                owedFrom, owedFrom );
            freezeAt( owedFrom );
        }
    }

    /** Freezes the live session or stored draft under {@code pageName}, if any. */
    private void freezeAt( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        final List< Runnable > done = slot == null ? null : slot.freezeIfLive();
        if( done != null ) {
            done.forEach( Runnable::run );   // after the slot lock is released
            return;
        }
        store.sessionForPage( pageName ).ifPresent( rec -> store.updateSession( rec.withStatus( SessionRecord.FROZEN ) ) );
    }

}

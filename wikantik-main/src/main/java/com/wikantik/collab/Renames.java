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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link CollabSessionRegistry}'s renames (A1): a page's live session or stored draft follows its page to the new
 * name, and a rename that could not be carried out is remembered and retried, so a draft never stays behind on the old
 * name and a joiner never opens a second session for the renamed page.
 *
 * <p><b>Failures.</b> A rename is <em>refused</em> when the new name already has a draft of its own (live or stored):
 * the orphan is frozen under its old name with a WARN, so its participants learn why their pushes stop, and the rename
 * stays owed in case the other draft goes away. Any other failure (the store) is retried. Retries back off: the first
 * on the next tick, then after 1 s, 2 s, 4 s and so on up to {@value #MAX_BACKOFF_SECONDS} s, so a refusal is not a
 * store unique violation (a database ERROR) every second. A join of the new name retries at once, unless the new name
 * has a draft of its own: that draft is the one it joins, so it never re-runs a refused rename.</p>
 *
 * <p><b>Chains and deletes.</b> A rename of a name that is itself owed a rename (A to B owed, then B to C) follows the
 * chain: the owed draft moves to C, never onto the vanished B. A retry never moves a draft onto a name whose page no
 * longer exists: the owed rename is dropped and the draft stays put, where the registry's orphan sweep freezes it. The
 * delete of an owed rename's target likewise drops it ({@link #dropInto}); the registry freezes the draft where it
 * is.</p>
 *
 * <p>Each rename runs under the old name's slot lock, so it cannot interleave with a join opening that slot: a stored
 * draft is renamed and the unopened slot retired (a waiting joiner retries from a fresh read); a live session is renamed
 * by its own rekey, which waits for the session's base lock and writes the store, with the page map free: the new name
 * is reserved for the slot first (a joiner of it waits on the slot, then joins the moved session), and the old name is
 * unmapped before the slot is released. Parked pulls are answered after every lock is released, and so are freezes.</p>
 */
final class Renames {

    private static final Logger LOG = LogManager.getLogger( Renames.class );
    /** The longest wait between two retries of an owed rename. */
    static final long MAX_BACKOFF_SECONDS = 300;

    /** A rename still owed: the draft's name, failures so far, when it is due again, and whether it was frozen. */
    private record Owed( String from, int failures, Instant due, boolean frozen ) {
    }

    private final PageSlots slots;
    private final CollabStore store;
    private final Clock clock;
    /** Freezes a page's draft (the registry's freeze); called with no lock held. */
    private final Consumer< String > freezer;
    /** Whether a page exists; may throw. */
    private final Predicate< String > pageExists;
    /** New name to the rename owed into it. */
    private final Map< String, Owed > pending = new ConcurrentHashMap<>();

    Renames( final PageSlots slots, final CollabStore store, final Clock clock, final Consumer< String > freezer,
             final Predicate< String > pageExists ) {
        this.slots = slots;
        this.store = store;
        this.clock = clock;
        this.freezer = freezer;
        this.pageExists = pageExists;
    }

    /**
     * Moves {@code from}'s session or draft to {@code to}, and a draft still owed a rename into {@code from} with it.
     * A failure is remembered for a retry and rethrown; a failure of the chained move is only remembered.
     *
     * @throws IllegalStateException when {@code to} already has a session, live or stored (the orphan is frozen)
     */
    void rekey( final String from, final String to ) {
        if( from.equals( to ) ) {
            return;
        }
        final Owed chained = pending.remove( from );
        RuntimeException failure = null;
        try {
            attempt( from, to, null );
        } catch( final RuntimeException e ) {
            failure = e;
        }
        if( chained != null ) {
            follow( chained, from, to );
        }
        if( failure != null ) {
            throw failure;
        }
    }

    /** The draft owed a rename into {@code via} goes on to {@code to}, where {@code via} went. */
    private void follow( final Owed chained, final String via, final String to ) {
        final String origin = chained.from();
        if( origin.equals( to ) ) {
            LOG.info( "collab: {} was renamed back to {}; its draft never left", via, to );
            return;
        }
        if( pending.containsKey( to ) ) {
            LOG.warn( "collab: the draft of {} cannot follow {} to {}: another draft is owed that name; frozen under {}",
                origin, via, to, origin );
            freeze( origin );
            return;
        }
        LOG.info( "collab: the draft of {} follows the rename chain {} -> {} -> {}", origin, origin, via, to );
        try {
            attempt( origin, to, chained );
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: the chained rename of {} to {} failed; retried: {}", origin, to, e.toString() );
        }
    }

    /** One try of the rename; on failure the owed entry is recorded (backed off) and the failure rethrown. */
    private void attempt( final String from, final String to, final Owed prior ) {
        try {
            move( from, to ).forEach( Runnable::run );
        } catch( final RuntimeException e ) {
            failed( from, to, prior, e );
            throw e;
        }
        final Owed now = pending.get( to );
        if( now != null && now.from().equals( from ) ) {
            pending.remove( to, now );
        }
    }

    private void failed( final String from, final String to, final Owed prior, final RuntimeException e ) {
        final int failures = prior == null ? 1 : prior.failures() + 1;
        final boolean wasFrozen = prior != null && prior.frozen();
        final boolean refusedNow = !wasFrozen && hasSession( to );
        if( refusedNow ) {
            LOG.warn( "collab: the draft of {} cannot follow its rename to {}: {} has a draft of its own; frozen under {}"
                + " and retried with backoff: {}", from, to, to, from, e.toString() );
        } else if( prior == null ) {
            LOG.warn( "collab: the draft of {} could not follow its rename to {}; retried: {}", from, to, e.toString() );
        } else {
            LOG.debug( "collab: the rename of {} to {} failed again ({} failures): {}", from, to, failures,
                e.toString() );
        }
        final boolean frozen = wasFrozen || refusedNow && freeze( from );
        pending.put( to, new Owed( from, failures, clock.instant().plus( backoff( failures ) ), frozen ) );
    }

    /** Freezes the draft under {@code pageName}; false (logged) on a failure, and the next refusal tries again. */
    private boolean freeze( final String pageName ) {
        try {
            freezer.accept( pageName );
            return true;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: could not freeze the draft of {}; tried again on the next refusal: {}", pageName,
                e.toString() );
            return false;
        }
    }

    /** After the first failure the next tick retries; then 1 s, 2 s, 4 s and so on, capped. */
    static Duration backoff( final int failures ) {
        if( failures <= 1 ) {
            return Duration.ZERO;
        }
        final int shift = Math.min( failures - 2, 20 );
        return Duration.ofSeconds( Math.min( MAX_BACKOFF_SECONDS, 1L << shift ) );
    }

    /**
     * Whether a join of {@code pageName} may open or join its session: no rename into it is owed; or the page has a
     * draft of its own (a refused rename leaves it in place, and the join joins it); or the owed rename lands (or is
     * dropped) now.
     */
    boolean joinable( final String pageName ) {
        final Owed owed = pending.get( pageName );
        return owed == null || hasSession( pageName ) || retry( pageName, owed );
    }

    /** Retries every owed rename that is due (the registry's tick). */
    void retryPending() {
        final Instant now = clock.instant();
        for( final Map.Entry< String, Owed > e : Map.copyOf( pending ).entrySet() ) {
            if( !now.isBefore( e.getValue().due() ) ) {
                retry( e.getKey(), e.getValue() );
            }
        }
    }

    /** True when a rename out of {@code pageName} is still owed (its draft is not an orphan yet). */
    boolean owedFrom( final String pageName ) {
        return pending.values().stream().anyMatch( o -> o.from().equals( pageName ) );
    }

    /**
     * Drops the rename owed into {@code pageName} (its page was deleted: the draft must never land there); returns the
     * name the draft is still under, or null when none was owed.
     */
    String dropInto( final String pageName ) {
        final Owed owed = pending.remove( pageName );
        return owed == null ? null : owed.from();
    }

    /** Retries the owed rename into {@code to}; true when none is owed any more (it landed, or was dropped). */
    private boolean retry( final String to, final Owed owed ) {
        final boolean exists;
        try {
            exists = pageExists.test( to );
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: could not tell whether {} exists; the rename into it waits: {}", to, e.toString() );
            return false;
        }
        if( !exists ) {
            if( pending.remove( to, owed ) ) {
                LOG.warn( "collab: {} no longer exists; the draft of {} stays where it is, never moved onto a vanished"
                    + " page", to, owed.from() );
            }
            return true;
        }
        try {
            attempt( owed.from(), to, owed );
            return true;
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: the retried rename of {} to {} failed (recorded): {}", owed.from(), to, e.toString() );
            return false;
        }
    }

    /** True when {@code pageName} has a session, live or stored; false (logged) when the store cannot tell. */
    private boolean hasSession( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        if( slot != null && slot.session() != null ) {
            return true;
        }
        try {
            return store.sessionForPage( pageName ).isPresent();
        } catch( final RuntimeException e ) {
            LOG.warn( "collab: could not tell whether {} has a draft: {}", pageName, e.toString() );
            return false;
        }
    }

    /** The rename under {@code from}'s slot lock; returns the parked-pull completions to run after it. */
    private List< Runnable > move( final String from, final String to ) {
        while( true ) {
            final SessionSlot slot = slots.slotFor( from );
            slot.lock();
            try {
                if( !slot.closed() ) {
                    return slot.session() == null ? renameStored( slot, from, to ) : moveLive( slot, from, to );
                }
            } finally {
                slot.unlock();
            }
            // evicted while we waited: the next slot is a fresh one
        }
    }

    /** Under the slot lock; the page map is held only to reserve and swap keys, never across the session's rename. */
    private List< Runnable > moveLive( final SessionSlot slot, final String from, final String to ) {
        slots.reserve( from, to, slot );
        boolean moved = false;
        try {
            final List< Runnable > done = CollabSession.Hooks.rekey( slot.session(), to );
            moved = true;
            return done;
        } finally {
            slots.release( moved ? from : to, slot );   // refused: the reservation of to goes; moved: from goes
        }
    }

    /** Under the unopened slot's lock: the stored draft (if any) is renamed and the slot retired. */
    private List< Runnable > renameStored( final SessionSlot slot, final String from, final String to ) {
        store.sessionForPage( from ).ifPresent( rec -> store.updateSession( rec.withPageName( to ) ) );
        slot.close();
        slots.unmap( slot );
        return List.of();
    }
}

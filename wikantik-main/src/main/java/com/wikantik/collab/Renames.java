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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link CollabSessionRegistry}'s renames (A1): a page's live session or stored draft follows its page to the new
 * name, and a rename that could not be carried out is remembered and retried, so a draft never stays behind on the old
 * name and a joiner never opens a second session for the renamed page.
 *
 * <p><b>Failures.</b> A rename is <em>refused</em> when the new name already has a draft of its own (live or stored).
 * That is checked before the store is touched, so a refusal never costs a store unique violation (a database ERROR).
 * The orphan is frozen under its old name with one WARN, so its participants learn why their pushes stop, and the rename
 * stays owed in case the other draft goes away. Any other failure (the store) is retried. Retries back off: the first
 * on the next tick, then after 1 s, 2 s, 4 s and so on up to {@value #MAX_BACKOFF_SECONDS} s. A join of the new name
 * retries at once, unless the new name has a draft of its own: that draft is the one it joins.</p>
 *
 * <p><b>Chains and deletes.</b> A rename of a name that is itself owed a rename (A to B owed, then B to C) follows the
 * chain: the owed draft moves to C, never onto the vanished B. A retry never moves a draft onto a name whose page is
 * known not to exist: the owed rename is dropped and the draft stays put, where the registry's orphan sweep freezes it.
 * A page store that cannot tell ({@link PageReadException}) means "try again later", never "drop". The delete of an owed
 * rename's target likewise drops it ({@link #dropInto}); the registry freezes the draft where it is.</p>
 *
 * <p><b>The rename window</b> (final review item 3). The engine's renamer records the rename as owed under its page
 * locks, before the page moves ({@link #starting}), and marks it landed or forgotten as it releases them
 * ({@link #ended}); its rename event, and so {@link #rekey}, comes only after the referrers are rewritten and the page
 * reindexed. A join of the new name in between follows the owed rename into the moved draft instead of opening a fresh
 * one there (which the event would then refuse to move onto, freezing the moved draft). While the rename is in flight a
 * missing target means "not moved yet", never "drop it": the join is refused (503) until the page is there. A rename of
 * a name that has no draft (live or stored) has nothing to move and is done at once: so the event of a rename whose
 * draft already moved is a no-op.</p>
 *
 * <p><b>Concurrency.</b> Every change to the owed renames, and every move it decides on, runs under one lock: the
 * listener's rename, the tick's retry, a join's retry and a delete's drop are serialised, and a retry re-checks under
 * the lock that the rename it read is still the one owed (a chained rename may have followed it meanwhile). Lock order:
 * this lock, then a slot, then the page map, then the session's base and session locks; nothing that holds a slot or a
 * session lock calls in here. Parked-pull completions and freezes run after this lock is released. A join of a page
 * with no rename owed never takes it.</p>
 *
 * <p>Each move runs under the old name's slot lock, so it cannot interleave with a join opening that slot: a stored
 * draft is renamed and the unopened slot retired (a waiting joiner retries from a fresh read); a live session is renamed
 * by its own rekey, which waits for the session's base lock and writes the store, with the page map free: the new name
 * is reserved for the slot first (a joiner of it waits on the slot, then joins the moved session), and the old name is
 * unmapped before the slot is released.</p>
 */
final class Renames {

    private static final Logger LOG = LogManager.getLogger( Renames.class );
    /** The longest wait between two retries of an owed rename. */
    static final long MAX_BACKOFF_SECONDS = 300;

    /**
     * A rename still owed: the draft's name, failures so far, when it is due again, whether it was refused (the WARN was
     * logged), whether its orphan was frozen, and whether the renamer still holds its page locks ({@code inFlight}: the
     * page may not have moved yet).
     */
    private record Owed( String from, int failures, Instant due, boolean refused, boolean frozen, boolean inFlight ) {
    }

    /** What a step decided under the lock and runs after it: parked-pull completions, then freezes. */
    private static final class After {
        final List< Runnable > completions = new ArrayList<>();
        final List< String > freezes = new ArrayList<>();
    }

    private final PageSlots slots;
    private final CollabStore store;
    private final Clock clock;
    /** Freezes a page's draft (the registry's freeze); called with no lock held. */
    private final Consumer< String > freezer;
    /** Whether a page exists; throws when the page store cannot tell. */
    private final Predicate< String > pageExists;
    /** Serialises every change to {@link #pending} and the moves decided from it. */
    private final ReentrantLock lock = new ReentrantLock();
    /** New name to the rename owed into it. Written under {@link #lock}; read without it only for a fast answer. */
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
        final After after = new After();
        RuntimeException failure = null;
        lock.lock();
        try {
            final Owed chained = pending.remove( from );
            try {
                attempt( from, to, null, after );
            } catch( final RuntimeException e ) {
                failure = e;
            }
            if( chained != null ) {
                follow( chained, from, to, after );
            }
        } finally {
            lock.unlock();
        }
        finish( after );
        if( failure != null ) {
            throw failure;
        }
    }

    /** Under the lock: the draft owed a rename into {@code via} goes on to {@code to}, where {@code via} went. */
    private void follow( final Owed chained, final String via, final String to, final After after ) {
        final String origin = chained.from();
        if( origin.equals( to ) ) {
            LOG.info( "collab: {} was renamed back to {}; its draft never left", via, to );
            return;
        }
        if( pending.containsKey( to ) ) {
            LOG.warn( "collab: the draft of {} cannot follow {} to {}: another draft is owed that name; frozen under {}",
                origin, via, to, origin );
            after.freezes.add( origin );
            return;
        }
        LOG.info( "collab: the draft of {} follows the rename chain {} -> {} -> {}", origin, origin, via, to );
        try {
            attempt( origin, to, chained, after );
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: the chained rename of {} to {} failed; retried: {}", origin, to, e.toString() );
        }
    }

    /**
     * Under the lock: one try of the rename. A target with a draft of its own is refused before the store is touched;
     * on any failure the owed entry is recorded (backed off) and the failure rethrown.
     */
    private void attempt( final String from, final String to, final Owed prior, final After after ) {
        if( Boolean.FALSE.equals( draftUnder( from ) ) ) {
            // nothing to move: no draft has that name (or it already followed this rename, in the rename window)
            settled( from, to );
            return;
        }
        boolean refused = hasSession( to );
        try {
            if( refused ) {
                throw new IllegalStateException( "collab: cannot move the draft of " + from + " to " + to
                    + ": it has a draft of its own" );
            }
            after.completions.addAll( move( from, to ) );
        } catch( final RuntimeException e ) {
            refused = refused || hasSession( to );   // a target that gained a session during the move refuses it too
            failed( from, to, prior, refused, e, after );
            throw e;
        }
        settled( from, to );
    }

    /** Under the lock: the rename of {@code from} into {@code to} is owed no more. */
    private void settled( final String from, final String to ) {
        final Owed now = pending.get( to );
        if( now != null && now.from().equals( from ) ) {
            pending.remove( to, now );
        }
    }

    private void failed( final String from, final String to, final Owed prior, final boolean refusedNow,
                         final RuntimeException e, final After after ) {
        final boolean inFlight = inFlight( prior );
        final int failures = prior == null ? 1 : prior.failures() + 1;
        final boolean warned = prior != null && prior.refused();
        logFailure( from, to, failures, refusedNow && !warned, prior == null && !refusedNow, e );
        boolean frozen = prior != null && prior.frozen();
        if( refusedNow && !frozen ) {
            after.freezes.add( from );
            frozen = true;   // cleared again by finish() if the freeze fails, so the next refusal retries it
        }
        pending.put( to, new Owed( from, failures, clock.instant().plus( backoff( failures ) ), refusedNow || warned,
            frozen, inFlight ) );
    }

    private static boolean inFlight( final Owed owed ) {
        return owed != null && owed.inFlight();
    }

    /** One WARN for a refusal, one for the first other failure; DEBUG after that. */
    private static void logFailure( final String from, final String to, final int failures, final boolean firstRefusal,
                                    final boolean firstFailure, final RuntimeException e ) {
        if( firstRefusal ) {
            LOG.warn( "collab: the draft of {} cannot follow its rename to {}: {} has a draft of its own; frozen under {}"
                + " and retried with backoff", from, to, to, from );
        } else if( firstFailure ) {
            LOG.warn( "collab: the draft of {} could not follow its rename to {}; retried: {}", from, to, e.toString() );
        } else {
            LOG.debug( "collab: the rename of {} to {} failed again ({} failures): {}", from, to, failures,
                e.toString() );
        }
    }

    /** After the lock: completions, then freezes; a freeze that fails is logged and retried on the next refusal. */
    private void finish( final After after ) {
        after.completions.forEach( Runnable::run );
        for( final String name : after.freezes ) {
            try {
                freezer.accept( name );
            } catch( final RuntimeException e ) {
                LOG.warn( "collab: could not freeze the draft of {}; tried again on the next refusal: {}", name,
                    e.toString() );
                unmarkFrozen( name );
            }
        }
    }

    private void unmarkFrozen( final String from ) {
        lock.lock();
        try {
            pending.replaceAll( ( to, o ) -> o.frozen() && o.from().equals( from )
                ? new Owed( o.from(), o.failures(), o.due(), o.refused(), false, o.inFlight() ) : o );
        } finally {
            lock.unlock();
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
        if( !pending.containsKey( pageName ) ) {
            return true;   // the common case, without the lock
        }
        final After after = new After();
        final boolean out;
        lock.lock();
        try {
            final Owed owed = pending.get( pageName );
            out = owed == null || hasSession( pageName ) || retry( pageName, owed, after );
        } finally {
            lock.unlock();
        }
        finish( after );
        return out;
    }

    /**
     * Retries every owed rename that is due (the registry's tick), each re-checked under the lock: a listener's chained
     * rename or a delete may have followed or dropped it since the snapshot.
     */
    void retryPending() {
        final Instant now = clock.instant();
        for( final Map.Entry< String, Owed > e : Map.copyOf( pending ).entrySet() ) {
            if( now.isBefore( e.getValue().due() ) ) {
                continue;
            }
            final After after = new After();
            lock.lock();
            try {
                if( Objects.equals( pending.get( e.getKey() ), e.getValue() ) ) {
                    retry( e.getKey(), e.getValue(), after );
                }
            } finally {
                lock.unlock();
            }
            finish( after );
        }
    }

    /** The renamer is about to move {@code from} to {@code to}, holding both page locks: the rename is owed, in flight. */
    void starting( final String from, final String to ) {
        if( from.equals( to ) ) {
            return;
        }
        lock.lock();
        try {
            final Owed replaced = pending.put( to, new Owed( from, 0, clock.instant(), false, false, true ) );
            if( replaced != null ) {
                LOG.debug( "collab: the rename of {} into {} replaces the one owed from {} (that page is gone)", from, to,
                    replaced.from() );
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The renamer releases its locks: a rename that {@code moved} the page stays owed (no longer in flight) until it
     * lands; one that failed before the move is forgotten.
     */
    void ended( final String from, final String to, final boolean moved ) {
        lock.lock();
        try {
            final Owed owed = pending.get( to );
            if( owed == null || !owed.inFlight() || !owed.from().equals( from ) ) {
                return;   // it landed already (a join, a retry), or a later rename took its place
            }
            if( moved ) {
                pending.put( to, new Owed( from, owed.failures(), owed.due(), owed.refused(), owed.frozen(), false ) );
            } else {
                pending.remove( to, owed );
            }
        } finally {
            lock.unlock();
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
        lock.lock();
        try {
            final Owed owed = pending.remove( pageName );
            return owed == null ? null : owed.from();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Under the lock: retries the owed rename into {@code to}; true when none is owed any more (it landed, or was
     * dropped because {@code to} is known not to exist). A page store that cannot tell keeps it owed.
     */
    private boolean retry( final String to, final Owed owed, final After after ) {
        final boolean exists;
        try {
            exists = pageExists.test( to );
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: could not tell whether {} exists; the rename into it stays owed: {}", to, e.toString() );
            return false;
        }
        if( !exists ) {
            if( owed.inFlight() ) {
                return false;   // the renamer has not moved the page yet: still owed, the join waits
            }
            if( pending.remove( to, owed ) ) {
                LOG.warn( "collab: {} no longer exists; the draft of {} stays where it is, never moved onto a vanished"
                    + " page", to, owed.from() );
            }
            return true;
        }
        try {
            attempt( owed.from(), to, owed, after );
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

    /**
     * Whether {@code pageName} has a draft, live or stored; null (logged at DEBUG; the caller goes on as if it might)
     * when the store cannot tell.
     */
    private Boolean draftUnder( final String pageName ) {
        final SessionSlot slot = slots.get( pageName );
        if( slot != null && slot.session() != null ) {
            return Boolean.TRUE;
        }
        try {
            return store.sessionForPage( pageName ).isPresent();
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: could not tell whether {} has a draft to move: {}", pageName, e.toString() );
            return null;
        }
    }

    /** The rename under {@code from}'s slot lock; returns the parked-pull completions to run after every lock. */
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

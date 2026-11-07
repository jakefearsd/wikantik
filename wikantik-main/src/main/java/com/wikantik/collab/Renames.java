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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link CollabSessionRegistry}'s renames (A1): a page's live session or stored draft follows its page to the new
 * name, and a rename that could not be carried out (the store failed or refused) is remembered and retried, by the
 * registry's tick and by a join of the new name, so a draft never stays behind on the old name and a joiner never opens
 * a second session for the renamed page.
 *
 * <p>Each rename runs under the old name's slot lock, so it cannot interleave with a join opening that slot: a stored
 * draft is renamed and the unopened slot retired (a waiting joiner retries from a fresh read); a live session is renamed
 * by its own rekey, which waits for the session's base lock and writes the store, with the page map free: the new name
 * is reserved for the slot first (a joiner of it waits on the slot, then joins the moved session), and the old name is
 * unmapped before the slot is released. Parked pulls are answered after every lock is released.</p>
 */
final class Renames {

    private static final Logger LOG = LogManager.getLogger( Renames.class );

    private final PageSlots slots;
    private final CollabStore store;
    /** New name to old name, for the renames still owed. */
    private final Map< String, String > pending = new ConcurrentHashMap<>();

    Renames( final PageSlots slots, final CollabStore store ) {
        this.slots = slots;
        this.store = store;
    }

    /**
     * Moves {@code from}'s session or draft to {@code to}; a failure is remembered for a retry and rethrown.
     *
     * @throws IllegalStateException when {@code to} already has a session, live or stored
     */
    void rekey( final String from, final String to ) {
        if( from.equals( to ) ) {
            return;
        }
        try {
            move( from, to ).forEach( Runnable::run );
        } catch( final RuntimeException e ) {
            if( !from.equals( pending.put( to, from ) ) ) {
                LOG.warn( "collab: the draft of {} could not follow its rename to {}; retried: {}", from, to,
                    e.toString() );
            }
            throw e;
        }
        pending.remove( to, from );
    }

    /**
     * Whether a join of {@code pageName} may open or join its session: no rename into it is owed, or the owed one lands
     * now, or the page already has a session (a refused rename leaves the target's own draft in place).
     */
    boolean joinable( final String pageName ) {
        return resolve( pageName ) || hasSession( pageName );
    }

    /** Retries every owed rename (the registry's tick). */
    void retryPending() {
        for( final String to : Map.copyOf( pending ).keySet() ) {
            resolve( to );
        }
    }

    /** Retries the owed rename into {@code to}, if any; true when none is owed any more. */
    private boolean resolve( final String to ) {
        final String from = pending.get( to );
        if( from == null ) {
            return true;
        }
        try {
            rekey( from, to );
            return true;
        } catch( final RuntimeException e ) {
            LOG.debug( "collab: the rename of {} to {} failed again; retried on the next tick: {}", from, to,
                e.toString() );
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

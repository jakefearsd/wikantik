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

import com.wikantik.collab.model.SessionRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The page names that have a stored draft, active or frozen, held in memory for
 * {@link CollabSessionRegistry#draftActive}, which must never read the store. The tick reloads it from
 * {@link CollabStore#sessions} every {@link #RELOAD_EVERY} (drafts made on another node, or changed behind the
 * registry's back, appear within that); the registry updates it at once on create, rename, purge and delete.
 *
 * <p>A change made while a reload is reading the store is replayed onto the reloaded set, so a reload never undoes it.
 * Reads are lock-free; changes and the reload's swap hold one monitor. A reload whose read fails throws and keeps the
 * index as it was.</p>
 */
final class DraftIndex {

    /** How often the tick reloads the index from the store. */
    static final Duration RELOAD_EVERY = Duration.ofSeconds( 30 );

    private final Object lock = new Object();
    private final Set< String > pages = ConcurrentHashMap.newKeySet();
    /** Guarded by {@code lock}: the changes made since the running reload started reading, or null when none runs. */
    private List< Consumer< Set< String > > > sinceRead;
    /** Tick thread only. */
    private Instant lastReload;

    /** True when {@code pageName} has a stored draft, as far as this node knows. Lock-free. */
    boolean contains( final String pageName ) {
        return pages.contains( pageName );
    }

    void add( final String pageName ) {
        change( set -> set.add( pageName ) );
    }

    void remove( final String pageName ) {
        change( set -> set.remove( pageName ) );
    }

    /** Sets whether {@code pageName} has a stored draft. */
    void set( final String pageName, final boolean present ) {
        if( present ) {
            add( pageName );
        } else {
            remove( pageName );
        }
    }

    private void change( final Consumer< Set< String > > op ) {
        synchronized( lock ) {
            op.accept( pages );
            if( sinceRead != null ) {
                sinceRead.add( op );
            }
        }
    }

    /**
     * Reloads from {@code store} when {@link #RELOAD_EVERY} has passed since the last reload (at once on the first call);
     * tick thread only.
     *
     * @throws RuntimeException when the store cannot list its drafts: the index is kept and the next reload is due after
     *                          the usual interval
     */
    void reloadIfDue( final CollabStore store, final Instant now ) {
        if( lastReload != null && now.isBefore( lastReload.plus( RELOAD_EVERY ) ) ) {
            return;
        }
        lastReload = now;   // a failed read is retried after the same interval, not on every tick
        synchronized( lock ) {
            sinceRead = new ArrayList<>();
        }
        final Set< String > fresh = new HashSet<>();
        try {
            for( final SessionRecord rec : store.sessions() ) {
                fresh.add( rec.pageName() );
            }
        } catch( final RuntimeException e ) {
            synchronized( lock ) {
                sinceRead = null;
            }
            throw e;
        }
        synchronized( lock ) {
            sinceRead.forEach( op -> op.accept( fresh ) );
            sinceRead = null;
            pages.retainAll( fresh );
            pages.addAll( fresh );
        }
    }
}

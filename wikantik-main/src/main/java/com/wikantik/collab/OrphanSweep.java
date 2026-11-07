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

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * {@link CollabSessionRegistry}'s orphan sweep (Phase 2 Task 3 review, binding a), run on each tick for every session
 * the tick settles (it has participants or owes the page store something): a draft based on a page that existed (base
 * version above 0, not frozen), whose page has been missing on every sweep for {@link #GRACE} with no rename out of it
 * owed ({@link Renames#owedFrom}), is frozen. That is the only way a delete whose event was missed, or a draft that a
 * dropped rename left behind, is ever frozen; without it a publish there is answered 409 {@code publish-merged-retry}
 * forever. A new page's draft (base 0) has no page by design and is never swept. The grace lets a rename, whose page
 * store move lands before its event, deliver the event: a rename is not a delete.
 */
final class OrphanSweep {

    private static final Logger LOG = LogManager.getLogger( OrphanSweep.class );

    /** How long a draft's page must be missing before the sweep freezes the draft. */
    static final Duration GRACE = Duration.ofMinutes( 1 );

    private final CollabPageWriter writer;
    private final Renames renames;
    /** The registry's freeze of one page's draft. */
    private final Consumer< String > freezer;

    OrphanSweep( final CollabPageWriter writer, final Renames renames, final Consumer< String > freezer ) {
        this.writer = writer;
        this.renames = renames;
        this.freezer = freezer;
    }

    /**
     * One sweep of {@code slot}'s session {@code s} (tick thread only). The existence check reads the page's metadata
     * only ({@link CollabPageWriter#exists}). When the page store cannot tell ({@link PageReadException} or any other
     * failure) the answer is unknown: nothing is frozen and the grace clock neither starts nor advances.
     */
    void sweep( final SessionSlot slot, final CollabSession s, final Instant now ) {
        if( !slot.needsSettle() ) {
            return;
        }
        final String name = s.pageName();
        if( !CollabSession.Hooks.basedOnAPage( s ) || renames.owedFrom( name ) ) {
            slot.present();
            return;
        }
        final boolean exists;
        try {
            exists = writer.exists( name );
        } catch( final RuntimeException e ) {
            LOG.debug( "collab session {}: could not tell whether {} exists; swept again next tick: {}", s.id(), name,
                e.toString() );
            return;
        }
        if( exists ) {
            slot.present();
            return;
        }
        if( slot.absentFor( now, GRACE ) ) {
            LOG.warn( "collab session {}: page {} has been missing for {} with no rename owed; its draft is frozen",
                s.id(), name, GRACE );
            slot.present();
            freezer.accept( name );
        }
    }
}

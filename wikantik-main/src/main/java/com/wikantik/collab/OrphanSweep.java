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

import com.wikantik.collab.model.PageState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * {@link CollabSessionRegistry}'s tick-time settle and orphan sweep, which share one page read.
 *
 * <p><b>Settle</b> ({@link #settle}): when the slot says one is due ({@link SessionSlot#needsSettle}: the session owes
 * the page store something, its page was found missing, or it has participants and {@link SessionSlot#SETTLE_EVERY}
 * has passed), the page is read once and the session reconciled with it ({@link CollabSession#reconcile}). The page
 * listener delivers saves at once; this is the safety net for a missed event and the only retry of an owed merge or an
 * unfinished publish. A missing page needs a reconcile only for a session that owes the page store something (a stored
 * pending-publish marker); that session settles itself ({@link CollabSession#settle}, a second read on that rare path
 * only). A read that throws propagates to the tick step, which logs it; no settle is recorded, so the next tick reads
 * again.</p>
 *
 * <p><b>Orphan sweep</b> ({@link #sweep}; Phase 2 Task 3 review, binding a): uses what the settle read on the same tick,
 * never a read of its own. A draft based on a page that existed (base version above 0, not frozen), whose page has been
 * missing on every sweep for {@link #GRACE} with no rename out of it owed ({@link Renames#owedFrom}), is frozen. That is
 * the only way a delete whose event was missed, or a draft that a dropped rename left behind, is ever frozen; without
 * it a publish there is answered 409 {@code publish-merged-retry} forever. A new page's draft (base 0) has no page by
 * design and is never swept. The grace lets a rename, whose page store move lands before its event, deliver the event:
 * a rename is not a delete. Once the page is found missing the slot settles (and so sweeps) on every tick until the
 * page is back or the draft is frozen.</p>
 */
final class OrphanSweep {

    private static final Logger LOG = LogManager.getLogger( OrphanSweep.class );

    /** How long a draft's page must be missing before the sweep freezes the draft. */
    static final Duration GRACE = Duration.ofMinutes( 1 );

    /** What one settle read: the page name it read and whether that page exists. */
    record Seen( String pageName, boolean exists ) {
    }

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
     * The settle of {@code slot}'s session {@code s} on this tick (tick thread only), when due; returns what it read,
     * or null when none was due.
     *
     * @throws RuntimeException when the page cannot be read ({@link PageReadException} or any other failure): unknown,
     *                          retried on the next tick
     */
    Seen settle( final SessionSlot slot, final CollabSession s, final Instant now ) {
        if( !slot.needsSettle( now ) ) {
            return null;
        }
        final String name = s.pageName();
        final Optional< PageState > latest = writer.latest( name );
        slot.settled( now );
        if( latest.isPresent() ) {
            s.reconcile( latest.get() );
        } else if( CollabSession.Hooks.owesThePageStore( s ) ) {
            s.settle();
        }
        return new Seen( name, latest.isPresent() );
    }

    /**
     * One sweep of {@code slot}'s session {@code s} (tick thread only) with what this tick's settle read. Nothing read
     * (no settle due, or its read failed) or a read of a name the session has since left (a rename) is unknown: nothing
     * is frozen and the grace clock neither starts nor advances.
     */
    void sweep( final SessionSlot slot, final CollabSession s, final Instant now, final Seen seen ) {
        final String name = s.pageName();
        if( seen == null || !seen.pageName().equals( name ) ) {
            return;
        }
        if( !CollabSession.Hooks.basedOnAPage( s ) || renames.owedFrom( name ) || seen.exists() ) {
            slot.present();
            return;
        }
        CollabSession.Hooks.deleteSeen( s );   // a recreate before the grace ends still guards the draft
        if( slot.absentFor( now, GRACE ) ) {
            LOG.warn( "collab session {}: page {} has been missing for {} with no rename owed; its draft is frozen",
                s.id(), name, GRACE );
            slot.present();
            freezer.accept( name );
        }
    }
}

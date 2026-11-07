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

import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.SessionRecord;
import com.wikantik.collab.model.StoredMetaOp;
import com.wikantik.collab.model.StoredUpdate;
import com.wikantik.collab.model.Update;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.List;

/**
 * Section 7.2's push with A12's piggybacked selection and A14's meta edits, under the session lock. The whole push is
 * validated first (identity, rate, freeze, the version check when it carries text, every update, every meta edit); a
 * refusal applies nothing, not even the selection. Meta edits are then resolved in request order against the live meta
 * ({@link MetaEdits}; an edit that changes nothing yields no op), the text and the ops are stored in one step (store
 * first, memory only on success: 503 {@code store}, E35) and applied, and parked pulls are woken. A push without text
 * skips the version check (presence and meta are placed by their own cursors). An accepted push records its author as
 * a contributor ({@link Contributors}) and emits {@code draft-state} when the contributor set or {@code unpublished}
 * changed.
 */
final class SessionPush {

    private static final Logger LOG = LogManager.getLogger( SessionPush.class );

    private final Draft d;
    private final Roster roster;
    private final Answers answers;
    private final CollabConfig config;

    SessionPush( final Draft d, final Roster roster, final Answers answers, final CollabConfig config ) {
        this.d = d;
        this.roster = roster;
        this.answers = answers;
        this.config = config;
    }

    /** One push; the runnables added to {@code completions} answer waiters and run after the lock is released. */
    PushResponse push( final String participantId, final String authorKey, final int version,
                       final List< Update > updates, final List< MetaEdit > metaEdits, final Selection selection,
                       final Instant now, final List< Runnable > completions ) {
        final Gate.Refusal refused = Gate.push( roster, participantId, authorKey, now, config.pushesPerSecond(),
            SessionRecord.FROZEN.equals( d.record.status() ) );
        if( refused != null ) {
            return PushResponse.refused( refused.status(), d.head, refused.reason(), refused.detail() );
        }
        final Participant p = roster.get( participantId );
        Gate.confirm( p, version, d.head );
        if( !updates.isEmpty() && version != d.head ) {
            return PushResponse.refused( 409, d.head, CollabSession.STALE );
        }
        final PushCheck check = PushCheck.of( p, updates, d.doc, d.head, d.oversize.maxPageBytes() );
        if( check.refusal() != null ) {
            return PushResponse.refused( check.refusal().status(), d.head, check.refusal().reason() );
        }
        final List< StoredMetaOp > ops = metaOps( p, metaEdits );
        if( ops == null ) {
            return PushResponse.refused( 400, d.head, CollabSession.REJECTED );
        }
        final List< StoredUpdate > accepted = check.accepted();
        if( accepted.isEmpty() && ops.isEmpty() ) {
            // presence only, or meta edits that change nothing (ruling P7: no version check)
            presenceOnly( p, selection, completions );
            return PushResponse.accepted( d.head, d.metaSeq );
        }
        final Draft.State before = d.state();
        if( !store( participantId, accepted, ops ) ) {
            return PushResponse.refused( 503, d.head, CollabSession.STORE );
        }
        d.lastEditAt = now;
        d.applyMetaOps( ops );
        d.contributors.record( accepted, ops );
        if( accepted.isEmpty() ) {
            presenceOnly( p, selection, completions );
        } else {
            applyText( p, version, accepted, check.next(), selection );
        }
        d.announce( before );
        answers.wakeAll( accepted, completions );
        return PushResponse.accepted( d.head, d.metaSeq );
    }

    /**
     * The meta ops of a push, or null (logged by field name and kind only: a value never reaches the log) when an edit
     * is invalid or a resolved value is over {@link MetaEdits#MAX_VALUE_BYTES}.
     */
    private List< StoredMetaOp > metaOps( final Participant p, final List< MetaEdit > metaEdits ) {
        for( final MetaEdit e : metaEdits ) {
            final String why = MetaEdits.invalid( e );
            if( why != null ) {
                LOG.warn( "collab session {}: push from {} refused, meta edit on field {} ({}): {}; nothing applied",
                    d.id, p.id, e == null ? null : clip( e.field() ), e == null ? null : e.kind(), why );
                return null;
            }
        }
        final List< StoredMetaOp > ops = MetaEdits.ops( d.meta, metaEdits, d.metaSeq, p.authorKey );
        final String oversized = MetaEdits.oversized( ops );
        if( oversized != null ) {
            LOG.warn( "collab session {}: push from {} refused, the value of meta field {} exceeds {} bytes; nothing"
                + " applied", d.id, p.id, clip( oversized ), MetaEdits.MAX_VALUE_BYTES );
            return null;
        }
        return ops;
    }

    private void presenceOnly( final Participant p, final Selection selection, final List< Runnable > completions ) {
        if( keepSelection( p, selection ) ) {
            answers.presenceChanged( p.id, completions );
        }
    }

    /** A field name as logged: at most 128 characters (a refused name may be longer). */
    private static String clip( final String field ) {
        return field == null || field.length() <= 128 ? field : field.substring( 0, 128 ) + "...";
    }

    /**
     * The text alone through {@code appendUpdates}; with meta ops, both through one {@code appendBatch}. False (logged)
     * when the store refused: nothing was stored and nothing is applied.
     */
    private boolean store( final String participantId, final List< StoredUpdate > accepted,
                           final List< StoredMetaOp > ops ) {
        try {
            if( ops.isEmpty() ) {
                d.store.appendUpdates( d.id, accepted );
            } else {
                d.store.appendBatch( d.id, accepted, ops );
            }
            return true;
        } catch( final RuntimeException e ) {
            LOG.warn( "collab session {} ({}): push from {} not stored, nothing applied: {}", d.id, d.pageName(),
                participantId, e.getMessage() );
            return false;
        }
    }

    private void applyText( final Participant p, final int version, final List< StoredUpdate > accepted,
                            final String next, final Selection selection ) {
        d.applyAccepted( accepted, next );
        d.oversize.update( d.doc, false );
        final Selection own = Presence.afterOwnPush( selection, version, accepted.size(), d.doc.length(), d.head );
        if( own != null ? Presence.set( p, own ) : keepSelection( p, selection ) ) {
            d.presenceSeq++;
        }
        if( d.snapshotDue( config.snapshotEveryUpdates() ) ) {
            d.snapshot( roster.lowestConfirmed( d.head ) );
        }
    }

    private boolean keepSelection( final Participant p, final Selection s ) {
        return Presence.keep( p, s, d.ring, d.doc.length(), d.head );
    }
}

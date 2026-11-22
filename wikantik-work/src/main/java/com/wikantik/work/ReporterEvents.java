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
package com.wikantik.work;

import java.util.Optional;
import java.util.Set;

/**
 * What a person who only reports may meet of an event on their own item: the one place that decides it, used by the
 * dispatcher, the inbox and unread statements, the email digest and the reporter's own timeline
 * ({@code intake().item}), so no caller can forget the reshaping.
 *
 * <p>Two lists, both allow-lists: a reporter is <em>told</em> (an inbox row, an email line) only of an event in
 * {@link #NOTIFIED} that {@link #reshape} keeps, and their timeline <em>shows</em> only those plus {@code created}
 * and {@code attached}. A new event type reaches members only until someone adds it here.
 *
 * <p>Every status move, however it happened, becomes one {@code status} event whose two values are status words and
 * nothing else: a plain status change, a hand-off that changes the status ({@code status|target} values are cut to
 * the status), a claim that moves the item, a lapsed claim, a release that sends the item back to {@code ready}. A
 * hand-off or claim that leaves the status where it was is not shown. Every reshaped event drops the note (an internal
 * reason), the agent id and any assignee, queue or holder; the actor (none for a lapsed claim) stays the stored login (the display-name path
 * turns it into the name members see) and its kind.
 */
final class ReporterEvents {

    /** The event types a reporter-only person may be told of: status moves by any route, and comments. */
    static final Set< String > NOTIFIED = Set.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED,
        WorkEvent.CLAIM_EXPIRED, WorkEvent.RELEASED, WorkEvent.COMMENTED );

    /** The event types a reporter's own timeline may show: what they are told of, and the filing and its files. */
    static final Set< String > SHOWN = Set.of( WorkEvent.STATUS, WorkEvent.HANDOFF, WorkEvent.CLAIMED,
        WorkEvent.CLAIM_EXPIRED, WorkEvent.RELEASED, WorkEvent.COMMENTED, WorkEvent.CREATED, WorkEvent.ATTACHED );

    private ReporterEvents() {
    }

    /** Whether a reporter-only person is told of this event (on their own item): allow-listed, and kept by {@link #reshape}. */
    static boolean notifies( final WorkEvent e ) {
        return NOTIFIED.contains( e.type() ) && reshape( e ).isPresent();
    }

    /**
     * The event as a reporter may see it, or empty when they may not: a type outside {@link #SHOWN}, an item-less
     * event, or a hand-off or claim that moves no status.
     */
    static Optional< WorkEvent > reshape( final WorkEvent e ) {
        if ( e.itemId() == null || !SHOWN.contains( e.type() ) ) {
            return Optional.empty();
        }
        return switch ( e.type() ) {
            case WorkEvent.CREATED -> Optional.of( copy( e, WorkEvent.CREATED, null, null ) );
            case WorkEvent.COMMENTED -> Optional.of( copy( e, WorkEvent.COMMENTED, null, e.toValue() ) );
            case WorkEvent.ATTACHED -> Optional.of( copy( e, WorkEvent.ATTACHED, e.fromValue(), e.toValue() ) );
            default -> statusMove( e );
        };
    }

    /** A reporter's notice: the event reshaped and the reason "watching" (any other reason tells of assignment or claims). */
    static Optional< NoticeView > reshape( final NoticeView v ) {
        if ( !NOTIFIED.contains( v.event().type() ) ) {
            return Optional.empty();
        }
        return reshape( v.event() ).map( e -> new NoticeView( v.id(), NoticeReason.WATCHING, v.createdAt(), v.readAt(), e,
            v.projectKey(), v.itemTitle() ) );
    }

    /**
     * The SQL condition, over the {@code work_event} row aliased {@code alias}, that holds exactly when
     * {@link #notifies} does for an event with an item. It is what the inbox, unread and mark-all-read statements use
     * for a project where the reader only reports, so the unread count and the list agree with the dispatcher.
     */
    static String notifiedSql( final String alias ) {
        return "( " + alias + ".type = '" + WorkEvent.COMMENTED + "' OR " + StatusMoves.movedSql( alias ) + " )";
    }

    /** A status move ({@link StatusMoves}, the one definition) as one {@code status} event of status words. */
    private static Optional< WorkEvent > statusMove( final WorkEvent e ) {
        return StatusMoves.of( e ).map( m -> {
            final WorkEvent moved = copy( e, WorkEvent.STATUS, m.from(), m.to() );
            return WorkEvent.CLAIM_EXPIRED.equals( e.type() ) ? withoutActor( moved ) : moved;
        } );
    }

    /**
     * A lapsed claim is the sweeper's doing, not the holding agent's: the stored actor is that agent, so a reporter's
     * copy carries no actor at all (a {@code SYSTEM} event with a null actor, worded "This moved to ready").
     */
    private static WorkEvent withoutActor( final WorkEvent e ) {
        return new WorkEvent( e.id(), e.projectId(), e.itemId(), e.itemKey(), e.at(), null, Actor.Kind.SYSTEM, null,
            e.type(), e.fromValue(), e.toValue(), null );
    }

    private static WorkEvent copy( final WorkEvent e, final String type, final String from, final String to ) {
        return new WorkEvent( e.id(), e.projectId(), e.itemId(), e.itemKey(), e.at(), e.actor(), e.actorKind(), null,
            type, from, to, null );
    }
}

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

import java.util.function.Function;

/** The one plain-text line that says what an event was, for the inbox, mail and Slack. Never prints a login or a note. */
final class NoticeText {

    private static final String QUEUE_PREFIX = "queue:";

    private NoticeText() {
    }

    static String line( final WorkEvent e, final NoticeReason reasonOrNull, final String recipientOrNull,
                        final Function< String, String > displayName ) {
        final String a = Actor.shown( e.actorKind(), e.actor(), displayName );
        return switch ( e.type() ) {
            case WorkEvent.ASSIGNED -> {
                final String target = target( e.toValue(), recipientOrNull, displayName );
                yield target == null ? a + " unassigned this" : a + " assigned this to " + target;
            }
            case WorkEvent.HANDOFF -> handoff( e, a, recipientOrNull, displayName );
            case WorkEvent.STATUS -> a + " moved this from " + words( e.fromValue() ) + " to " + words( e.toValue() );
            case WorkEvent.CREATED -> reasonOrNull == NoticeReason.REQUESTED
                ? a + " filed a request" : a + " created this";
            case WorkEvent.COMMENTED -> reasonOrNull == NoticeReason.MENTIONED
                ? a + " mentioned you in a comment" : a + " commented";
            case WorkEvent.COMMENT_EDITED -> a + " mentioned you in an edited comment";
            case WorkEvent.SETTINGS -> "webhook".equals( e.note() ) && "failed".equals( e.toValue() )
                ? "The webhook to " + e.fromValue() + " was switched off after failing for 24 hours."
                : a + " updated this";
            default -> changeLine( e.type(), a );
        };
    }

    /**
     * The line a reporter-only person reads for an event of their own item, given the event as
     * {@link ReporterEvents#reshape} made it: "moved this to <status>" for any status move (never the old value, an
     * assignee, a queue or a note), "commented" for a comment.
     */
    static String reporterLine( final WorkEvent reshaped, final Function< String, String > displayName ) {
        if ( reshaped.actor() == null && WorkEvent.STATUS.equals( reshaped.type() ) ) {
            return "This moved to " + words( reshaped.toValue() );   // a lapsed claim: nobody acted
        }
        final String a = Actor.shown( reshaped.actorKind(), reshaped.actor(), displayName );
        return switch ( reshaped.type() ) {
            case WorkEvent.STATUS -> a + " moved this to " + words( reshaped.toValue() );
            case WorkEvent.COMMENTED -> a + " commented";
            case WorkEvent.CREATED -> a + " filed this";
            case WorkEvent.ATTACHED -> a + " attached a file";
            default -> a + " updated this";
        };
    }

    /** The lines for change, deployment and claim events; anything else is the generic line. */
    private static String changeLine( final String type, final String a ) {
        return switch ( type ) {
            case WorkEvent.REVIEW_REQUESTED -> a + " asked for a review";
            case WorkEvent.CHANGE_MERGED -> a + " merged a change";
            case WorkEvent.CHANGE_FLAGGED -> a + " flagged a merge outside policy";
            case WorkEvent.SHIPPED -> a + " shipped this";
            case WorkEvent.ROLLED_BACK -> a + " rolled back a deployment that carried this";
            case WorkEvent.CLAIM_EXPIRED -> a + "'s claim lapsed and the item went back to its queue";
            default -> a + " updated this";
        };
    }

    private static String handoff( final WorkEvent e, final String a, final String recipient,
                                   final Function< String, String > displayName ) {
        final String to = e.toValue() == null ? "" : e.toValue();
        final int bar = to.indexOf( '|' );
        final String status = words( bar < 0 ? to : to.substring( 0, bar ) );
        final String target = target( bar < 0 ? null : to.substring( bar + 1 ), recipient, displayName );
        return a + " handed this " + ( target == null ? "on" : "to " + target ) + " as " + status;
    }

    /** "you", a display name, "the X queue", or null for no target. */
    private static String target( final String raw, final String recipient, final Function< String, String > name ) {
        if ( raw == null || raw.isBlank() ) {
            return null;
        }
        if ( raw.startsWith( QUEUE_PREFIX ) ) {
            return "the " + raw.substring( QUEUE_PREFIX.length() ) + " queue";
        }
        return raw.equals( recipient ) ? "you" : name.apply( raw );
    }

    private static String words( final String status ) {
        return status == null ? "nothing" : status.replace( '_', ' ' );
    }
}

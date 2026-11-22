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

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Who hears of an event, and why: a pure function of the event and the facts about it. */
final class Recipients {

    private static final String QUEUE_PREFIX = "queue:";

    private Recipients() {
    }

    /** Login to its strongest reason; the person who did it is left out unless nobody did (a webhook switched off). */
    static Map< String, NoticeReason > of( final WorkEvent e, final EventFacts f ) {
        final Map< String, NoticeReason > out = new TreeMap<>();
        if ( isWebhookOff( e ) ) {
            addAll( out, f.leads(), NoticeReason.WEBHOOK_DISABLED );
            return out;
        }
        switch ( e.type() ) {
            case WorkEvent.ASSIGNED -> add( out, personIn( e.toValue() ), NoticeReason.ASSIGNED );
            case WorkEvent.HANDOFF -> handoff( out, e, f );
            case WorkEvent.COMMENTED, WorkEvent.COMMENT_EDITED -> comment( out, e, f );
            case WorkEvent.STATUS -> {
                addAll( out, f.watchers(), NoticeReason.WATCHING );
                acknowledge( out, e, f, e.toValue() );
            }
            case WorkEvent.REVIEW_REQUESTED, WorkEvent.CHANGE_MERGED, WorkEvent.SHIPPED, WorkEvent.ROLLED_BACK ->
                addAll( out, f.watchers(), NoticeReason.WATCHING );
            case WorkEvent.CHANGE_FLAGGED -> addAll( out, f.leads(), NoticeReason.ACKNOWLEDGE );
            case WorkEvent.CLAIM_EXPIRED -> add( out, f.reporter(), NoticeReason.CLAIM_EXPIRED );
            default -> { }
        }
        if ( e.actorKind() == Actor.Kind.HUMAN ) {
            out.remove( e.actor() );
        }
        return out;
    }

    /**
     * Who may hear of the event as a person who only reports: the item's watchers (they watch the item they filed),
     * and only for an event {@link ReporterEvents#notifies} allows; the person who did it is left out. The planner
     * keeps a login from this set only for a person who only reports and filed the item; everyone else is decided by
     * {@link #of} alone, so this never adds a notice for a member.
     */
    static Set< String > forReporters( final WorkEvent e, final EventFacts f ) {
        if ( !ReporterEvents.notifies( e ) || f.commentRemoved() ) {
            return Set.of();
        }
        final Set< String > out = new TreeSet<>( f.watchers() );
        if ( e.actorKind() == Actor.Kind.HUMAN ) {
            out.remove( e.actor() );
        }
        return out;
    }

    private static void comment( final Map< String, NoticeReason > out, final WorkEvent e, final EventFacts f ) {
        if ( f.commentRemoved() ) {
            return;
        }
        addAll( out, f.mentioned(), NoticeReason.MENTIONED );
        if ( WorkEvent.COMMENTED.equals( e.type() ) ) {
            addAll( out, f.watchers(), NoticeReason.WATCHING );
        }
    }

    private static void handoff( final Map< String, NoticeReason > out, final WorkEvent e, final EventFacts f ) {
        final String to = e.toValue() == null ? "" : e.toValue();
        final int bar = to.indexOf( '|' );
        final String status = bar < 0 ? to : to.substring( 0, bar );
        add( out, personIn( bar < 0 ? null : to.substring( bar + 1 ) ), NoticeReason.ASSIGNED );
        addAll( out, f.watchers(), NoticeReason.WATCHING );
        acknowledge( out, e, f, status );
    }

    private static void acknowledge( final Map< String, NoticeReason > out, final WorkEvent e, final EventFacts f,
                                     final String status ) {
        if ( "done".equals( status ) && e.actorKind() == Actor.Kind.AGENT && f.ackPending() ) {
            addAll( out, f.leads(), NoticeReason.ACKNOWLEDGE );
        }
    }

    private static boolean isWebhookOff( final WorkEvent e ) {
        return WorkEvent.SETTINGS.equals( e.type() ) && "webhook".equals( e.note() ) && "failed".equals( e.toValue() );
    }

    /** The login in an assignment text, or null for no target or a queue. */
    static String personIn( final String target ) {
        if ( target == null || target.isBlank() || target.startsWith( QUEUE_PREFIX ) ) {
            return null;
        }
        return target;
    }

    private static void addAll( final Map< String, NoticeReason > out, final Set< String > logins,
                                final NoticeReason reason ) {
        for ( final String login : logins ) {
            add( out, login, reason );
        }
    }

    private static void add( final Map< String, NoticeReason > out, final String login, final NoticeReason reason ) {
        if ( login != null ) {
            out.merge( login, reason, ( a, b ) -> a.compareTo( b ) <= 0 ? a : b );
        }
    }
}

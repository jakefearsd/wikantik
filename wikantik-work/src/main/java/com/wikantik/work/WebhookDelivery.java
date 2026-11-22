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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Sends each webhook's pending events in order, at least once. A webhook speaks for its owner: it sends only while the
 * owner is a lead of the project (or a wiki admin), otherwise it is paused with its cursor kept. Each webhook is
 * handled on its own, so one failing never blocks another. A failure stops that webhook at the failed event (the next
 * attempt resumes there), backs off in memory (a restart forgets the pause, never the cursor), and after 24 hours of
 * failure switches it off. No log line or stored error carries a URL path.
 */
final class WebhookDelivery {

    private static final Logger LOG = LogManager.getLogger( WebhookDelivery.class );

    private static final int BATCH = 100;
    private static final Duration SWITCH_OFF_AFTER = Duration.ofHours( 24 );
    private static final Duration FIRST_BACKOFF = Duration.ofMinutes( 1 );
    private static final Duration MAX_BACKOFF = Duration.ofMinutes( 30 );
    /** The most time one cycle's webhook step may spend; it starts no further send once this has passed. */
    static final Duration CYCLE_BUDGET = Duration.ofSeconds( 30 );
    private static final int MAX_ERROR = 200;
    private static final String JSON = "application/json";

    private record Backoff( int failures, Instant notBefore ) {
    }

    /** What one attempt on a webhook did: events posted, and why it stopped early (null when it did not). */
    private record Attempt( int posted, String error, boolean lost ) {
    }

    private final WorkStore store;
    private final WorkScopes scopes;
    private final GuardedListener listener;
    private final WorkNotifyPorts ports;
    private final Clock clock;
    private final Map< Long, Backoff > backoff = new ConcurrentHashMap<>();
    /** Where the next cycle starts in the webhook list, so a cycle that ran out of time does not starve the ones after it. */
    private int startAt;

    /** The time one cycle may still spend, read from the work clock (a send is slow in real time, not in {@code now}). */
    private final class Budget {
        private final Instant end = clock.instant().plus( CYCLE_BUDGET );
        private boolean spent;

        boolean spent() {
            spent = spent || !clock.instant().isBefore( end );
            return spent;
        }
    }

    WebhookDelivery( final WorkStore store, final WorkScopes scopes, final GuardedListener listener,
                     final WorkNotifyPorts ports, final Clock clock ) {
        this.store = store;
        this.scopes = scopes;
        this.listener = listener;
        this.ports = ports;
        this.clock = clock;
    }

    /**
     * Whether the webhook's owner may send for the project now: an account that is a lead there, or a wiki admin.
     * A person's account is what counts; a name that is only an agent is not an owner.
     *
     * @throws WorkUnavailableException when the directory cannot answer
     */
    boolean ownerMaySend( final Webhook w ) {
        final Optional< WorkDirectory.Person > person = scopes.person( w.owner() );
        if ( person.isEmpty() ) {
            return false;
        }
        final WorkDirectory.Person p = person.get();
        final Actor owner = Actor.human( p.login(), p.groups(), p.admin() );
        if ( owner.instanceAdmin() ) {
            return true;
        }
        try {
            return scopes.standing().roleIn( w.projectId(), owner ).filter( r -> r.atLeast( WorkRole.LEAD ) ).isPresent();
        } catch ( final SQLException e ) {
            throw new WorkUnavailableException( "could not read the project's members" );
        }
    }

    /**
     * One cycle. While webhooks are off, or a webhook's owner may not send, the cursor is moved to the newest event
     * instead, so nothing from the quiet period is sent when delivery resumes. While on, the cycle stops starting
     * sends after {@link #CYCLE_BUDGET} and the next cycle begins at the webhook it reached.
     */
    int deliver( final Instant now ) throws SQLException {
        final List< Webhook > all = store.notifyStores().webhooks().enabled();
        if ( !ports.settings().webhooksEnabled() ) {
            for ( final Webhook w : all ) {
                skipBacklog( w );
            }
            return 0;
        }
        final Set< Long > live = all.stream().map( Webhook::id ).collect( Collectors.toSet() );
        backoff.keySet().retainAll( live );
        final Budget budget = new Budget();
        int posted = 0;
        for ( int n = 0; n < all.size(); n++ ) {
            final int index = ( startAt + n ) % all.size();
            final Webhook w = all.get( index );
            final Backoff wait = backoff.get( w.id() );
            if ( wait != null && now.isBefore( wait.notBefore() ) ) {
                continue;
            }
            if ( budget.spent() ) {
                startAt = index;
                return posted;
            }
            posted += deliverQuietly( w, now, budget );
        }
        startAt = 0;
        return posted;
    }

    private int deliverQuietly( final Webhook w, final Instant now, final Budget budget ) {
        try {
            return deliverOne( w, now, budget );
        } catch ( final SQLException | WorkUnavailableException e ) {
            // Our own store or directory, not the endpoint: no failure counted. Only the class: a message could carry an address.
            LOG.warn( "Webhook {} to {} could not be delivered this cycle ({}); it will be tried again", w.id(),
                w.url().getHost(), e.getClass().getSimpleName() );
            return 0;
        }
    }

    private void skipBacklog( final Webhook w ) {
        try {
            store.notifyStores().webhooks().skipToNewest( w.id() );
        } catch ( final SQLException e ) {
            LOG.warn( "Webhook {} to {}: could not skip the quiet period's events ({})", w.id(), w.url().getHost(),
                e.getClass().getSimpleName() );
        }
    }

    private int deliverOne( final Webhook w, final Instant now, final Budget budget ) throws SQLException {
        if ( !ownerMaySend( w ) ) {
            skipBacklog( w );
            return 0;
        }
        final WorkWebhookStore webhooks = store.notifyStores().webhooks();
        final List< WebhookEvent > pending = webhooks.pending( w, BATCH );
        if ( pending.isEmpty() ) {
            return 0;
        }
        String tx = w.cursorTx();
        long id = w.cursorEventId();
        int posted = 0;
        String error = null;
        for ( final WebhookEvent ev : pending ) {
            if ( sends( w, ev ) ) {
                if ( budget.spent() ) {
                    break;
                }
                error = post( w, ev );
                if ( error != null ) {
                    break;
                }
                posted++;
                listener.notified( "webhook", "delivered" );
            }
            tx = ev.tx();
            id = ev.event().id();
        }
        final boolean moved = id != w.cursorEventId() || !tx.equals( w.cursorTx() );
        if ( moved && !webhooks.advance( w.id(), w.cursorTx(), w.cursorEventId(), tx, id,
                posted > 0 && error == null ? now : null ) ) {
            return posted;
        }
        if ( error == null ) {
            backoff.remove( w.id() );
        } else {
            failed( w, now, error );
        }
        return posted;
    }

    private void failed( final Webhook w, final Instant now, final String error ) throws SQLException {
        final WorkWebhookStore webhooks = store.notifyStores().webhooks();
        webhooks.recordFailure( w.id(), now, error );
        listener.notified( "webhook", "failed" );
        final int failures = backoff.getOrDefault( w.id(), new Backoff( 0, now ) ).failures() + 1;
        final Duration delay = FIRST_BACKOFF.multipliedBy( 1L << Math.min( failures - 1, 5 ) );
        backoff.put( w.id(), new Backoff( failures, now.plus( delay.compareTo( MAX_BACKOFF ) > 0 ? MAX_BACKOFF : delay ) ) );
        LOG.warn( "Webhook {} to {} failed: {}", w.id(), w.url().getHost(), error );
        final Instant since = w.failingSince() == null ? now : w.failingSince();
        if ( !now.isBefore( since.plus( SWITCH_OFF_AFTER ) ) && webhooks.disableForFailure( w, now ) ) {
            backoff.remove( w.id() );
            LOG.warn( "Webhook {} to {} was switched off after failing for 24 hours", w.id(), w.url().getHost() );
        }
    }

    private static boolean sends( final Webhook w, final WebhookEvent ev ) {
        final String type = ev.event().type();
        return WebhookPayloads.SENDABLE.contains( type ) && ( w.eventTypes().isEmpty() || w.eventTypes().contains( type ) );
    }

    /**
     * Posts one event; null on a 2xx, else the short, address-free reason. Anything thrown while building or sending
     * is that webhook's failure (so it backs off and can be switched off), never an escape.
     */
    private String post( final Webhook w, final WebhookEvent ev ) {
        try {
            final String base = ports.settings().baseUrl();
            final boolean generic = w.format() == WebhookFormat.GENERIC;
            final String body = generic ? WebhookPayloads.generic( ev, base, scopes::displayName )
                : WebhookPayloads.slack( ev, base, scopes::displayName );
            final Map< String, String > headers = new LinkedHashMap<>();
            headers.put( "X-Wikantik-Event", ev.event().type() );
            headers.put( "X-Wikantik-Delivery", String.valueOf( ev.event().id() ) );
            if ( generic ) {
                headers.put( "X-Wikantik-Signature", WebhookPayloads.signature( w.secret(), body ) );
            }
            final int status = ports.outbound().post( w.url(), JSON, body, headers );
            return status >= 200 && status < 300 ? null : "HTTP " + status;
        } catch ( final WorkOutbound.Refused e ) {
            final String reason = scrub( e.getMessage(), w );
            LOG.warn( "Webhook {} to {}: delivery of event {} was refused ({})", w.id(), w.url().getHost(),
                ev.event().id(), reason );
            return reason;
        } catch ( final IOException e ) {
            LOG.warn( "Webhook {} to {}: delivery of event {} failed ({})", w.id(), w.url().getHost(),
                ev.event().id(), e.getClass().getSimpleName() );
            return e.getClass().getSimpleName();
        } catch ( final WorkUnavailableException e ) {
            // Our own directory or database, not the endpoint: the caller logs it and does not count a failure.
            throw e;
        } catch ( final RuntimeException e ) {
            LOG.warn( "Webhook {} to {}: delivery of event {} threw {}", w.id(), w.url().getHost(), ev.event().id(),
                e.getClass().getSimpleName(), e );
            return e.getClass().getSimpleName();
        }
    }

    /** Removes the address, or its path, from a message and bounds it. */
    private static String scrub( final String message, final Webhook w ) {
        String out = message == null ? "refused" : message.replace( w.url().toString(), w.target() );
        final String path = w.url().getRawPath();
        if ( path != null && path.length() > 1 ) {
            out = out.replace( path, "/…" );
        }
        return out.length() > MAX_ERROR ? out.substring( 0, MAX_ERROR ) : out;
    }
}

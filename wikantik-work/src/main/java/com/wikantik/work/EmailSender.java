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
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mails what is due, one message per person per run. A person is mailed only with an address, their choice on, and
 * standing in the item's project <em>now</em>; rows they can no longer see, or with nowhere to go, are stamped and
 * never sent. Sending happens outside any transaction and the stamp follows it (at least once: a crash between the
 * two repeats one message). A failure for one person is logged and leaves their rows for the next run.
 */
final class EmailSender {

    private static final Logger LOG = LogManager.getLogger( EmailSender.class );
    private static final Duration WINDOW = Duration.ofHours( 48 );
    private static final int LIMIT = 2000;
    private static final java.util.regex.Pattern SINGLE_ADDRESS =
        java.util.regex.Pattern.compile( "[^\\s,;<>()@\\[\\]\\\\\"]+@[^\\s,;<>()@\\[\\]\\\\\"]+" );

    private final WorkStore store;
    private final WorkScopes scopes;
    private final Clock clock;
    private final GuardedListener listener;
    private final WorkNotifyPorts ports;

    EmailSender( final WorkStore store, final WorkScopes scopes, final Clock clock,
                 final GuardedListener listener, final WorkNotifyPorts ports ) {
        this.store = store;
        this.scopes = scopes;
        this.clock = clock;
        this.listener = listener;
        this.ports = ports;
    }

    /** @return the number of messages sent */
    int send( final Instant now ) throws SQLException {
        if ( !ports.settings().emailEnabled() ) {
            return 0;
        }
        final Map< String, List< PendingEmail > > byRecipient = new LinkedHashMap<>();
        final ZonedDateTime local = now.atZone( clock.getZone() );
        final boolean dailyOpen = local.getHour() >= ports.settings().dailyHour();
        for ( final PendingEmail row : store.notifyStores().notifications().pendingEmail( now.minus( WINDOW ), LIMIT,
                dailyOpen, local.toLocalDate() ) ) {
            byRecipient.computeIfAbsent( row.recipient(), r -> new ArrayList<>() ).add( row );
        }
        int sent = 0;
        for ( final Map.Entry< String, List< PendingEmail > > one : byRecipient.entrySet() ) {
            try {
                sent += mailOne( one.getKey(), one.getValue(), now );
            } catch ( final WorkUnavailableException | SQLException e ) {
                LOG.warn( "Work email for {} was skipped this run and will be retried: {}", one.getKey(),
                    e.getClass().getSimpleName() );
            }
        }
        return sent;
    }

    private int mailOne( final String login, final List< PendingEmail > rows, final Instant now )
            throws SQLException {
        final EmailMode mode = rows.get( 0 ).mode();
        final LocalDate today = now.atZone( clock.getZone() ).toLocalDate();
        if ( mode == EmailMode.OFF || mode == EmailMode.DAILY && !dailyDue( rows.get( 0 ), now, today ) ) {
            return 0;
        }
        final Optional< WorkDirectory.Person > person = scopes.person( login );
        if ( person.isEmpty() ) {
            stamp( login, rows, now, null );
            return 0;
        }
        final List< PendingEmail > visible = visibleRows( person.get(), rows );
        if ( visible.size() < rows.size() ) {
            stamp( login, rows.stream().filter( r -> !visible.contains( r ) ).toList(), now, null );
        }
        if ( visible.isEmpty() ) {
            return 0;
        }
        final Optional< String > address = scopes.email( login );
        if ( address.isEmpty() || !singleAddress( address.get() ) ) {
            stamp( login, visible, now, null );
            return 0;
        }
        final EmailDigest.Email email = EmailDigest.render( visible, ports.settings().emailDetail(),
            ports.settings().baseUrl(), scopes::displayName );
        try {
            ports.mailer().send( address.get().strip(), email.subject(), email.text() );
        } catch ( final IOException | RuntimeException e ) {
            LOG.warn( "Work email to {} failed; it will be retried: {}", login, e.getClass().getSimpleName() );
            listener.notified( "email", "failed" );
            return 0;
        }
        stamp( login, visible, now, mode == EmailMode.DAILY ? today : null );
        listener.notified( "email", "delivered" );
        return 1;
    }

    /** One plain address: a list, a display-name form or a line break in a profile address is never mailed. */
    private static boolean singleAddress( final String address ) {
        return SINGLE_ADDRESS.matcher( address.strip() ).matches();
    }

    private boolean dailyDue( final PendingEmail first, final Instant now, final LocalDate today ) {
        final ZonedDateTime local = now.atZone( clock.getZone() );
        return local.getHour() >= ports.settings().dailyHour()
            && ( first.lastDigestOn() == null || first.lastDigestOn().isBefore( today ) );
    }

    private List< PendingEmail > visibleRows( final WorkDirectory.Person p, final List< PendingEmail > rows )
            throws SQLException {
        final Actor actor = Actor.human( p.login(), p.groups(), p.admin() );
        final Map< Long, Boolean > sees = new HashMap<>();
        final List< PendingEmail > visible = new ArrayList<>();
        for ( final PendingEmail r : rows ) {
            final long project = r.notice().event().projectId();
            final Boolean known = sees.get( project );
            final boolean can = known != null ? known : scopes.standing().seesProject( project, actor );
            sees.put( project, can );
            if ( can ) {
                visible.add( r );
            }
        }
        return visible;
    }

    private void stamp( final String login, final List< PendingEmail > rows, final Instant at, final LocalDate digestOn )
            throws SQLException {
        store.notifyStores().notifications().markEmailed( login, rows.stream().map( r -> r.notice().id() ).toList(), at, digestOn );
    }
}

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

import com.wikantik.work.WorkScopes.ItemScope;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Notifications, reached through {@link WorkFeatures#notifications()}: the inbox, watching, the email preference and
 * the dispatch the scheduler drives. A reader sees a notice only while they still see its project: the inbox, the
 * unread count and mark-all-read are filtered to the projects they can see now, and an item they cannot see is
 * "not found" to them. Agents are never notified: their inbox is empty and they cannot watch.
 */
public final class WorkNotificationsService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final String AGENTS_REFUSED = "Agents are not notified.";

    private final WorkStore store;
    private final WorkScopes scopes;
    private final WorkPeople people;
    private final Clock clock;
    private final GuardedListener listener;
    private final WorkNotifyPorts ports;
    private final EmailSender emailSender;

    WorkNotificationsService( final WorkStore store, final WorkScopes scopes, final WorkPeople people,
                              final Clock clock, final GuardedListener listener, final WorkNotifyPorts ports ) {
        this.store = store;
        this.scopes = scopes;
        this.people = people;
        this.clock = clock;
        this.listener = listener;
        this.ports = ports == null ? WorkNotifyPorts.NONE : ports;
        this.emailSender = new EmailSender( store, scopes, clock, listener, this.ports );
    }

    /** The caller's notices, newest first, only those in projects they see now; {@code limit} 1-100 (0 means 50). */
    public List< NoticeView > inbox( final Actor a, final Long beforeId, final int limit ) throws SQLException {
        if ( a.kind() == Actor.Kind.AGENT ) {
            return List.of();
        }
        final int bounded = limit == 0 ? DEFAULT_LIMIT : Math.max( 1, Math.min( MAX_LIMIT, limit ) );
        return store.notifyStores().notifications().inbox( a.login(), visibleIds( a ), beforeId, bounded );
    }

    /** The caller's unread notices in projects they see now. */
    public int unread( final Actor a ) throws SQLException {
        if ( a.kind() == Actor.Kind.AGENT ) {
            return 0;
        }
        return store.notifyStores().notifications().unread( a.login(), visibleIds( a ) );
    }

    /** Marks the caller's notices about the item read; an item the caller cannot see is not found. */
    public void markRead( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        if ( a.kind() == Actor.Kind.AGENT ) {
            return;
        }
        store.notifyStores().notifications().markRead( a.login(), is.item().id(), clock.instant() );
    }

    /** Marks every unread notice of the caller's, in projects they see now, read. */
    public void markAllRead( final Actor a ) throws SQLException {
        if ( a.kind() == Actor.Kind.AGENT ) {
            return;
        }
        store.notifyStores().notifications().markAllRead( a.login(), visibleIds( a ), clock.instant() );
    }

    /** The caller's watch state for the item; an agent's is always {@link WatchState#NONE}. */
    public WatchState watchState( final Actor a, final String itemKey ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        if ( a.kind() == Actor.Kind.AGENT ) {
            return WatchState.NONE;
        }
        return store.notifyStores().watches().stateOf( is.item().id(), a.login() );
    }

    /** Watch, mute or stop watching the item; returns the state now stored. Not recorded in any history. */
    public WatchState setWatch( final Actor a, final String itemKey, final WatchState state ) throws SQLException {
        final ItemScope is = scopes.itemScope( a, itemKey );
        refuseAgent( a );
        is.scope().requireWritable();
        if ( state == null ) {
            throw new WorkValidationException( "state", "Choose watching, muted or none." );
        }
        final WorkWatchStore watches = store.notifyStores().watches();
        final long item = is.item().id();
        switch ( state ) {
            case WATCHING -> watches.subscribe( item, a.login() );
            case MUTED -> watches.mute( item, a.login() );
            case NONE -> watches.unwatch( item, a.login() );
        }
        return watches.stateOf( item, a.login() );
    }

    /** The caller's email choice and what the wiki offers. An agent has no choice: off, and nothing available. */
    public NotifyPreference preference( final Actor a ) throws SQLException {
        if ( a.kind() == Actor.Kind.AGENT ) {
            return new NotifyPreference( EmailMode.OFF, false, false );
        }
        return new NotifyPreference( store.notifyStores().notifications().emailMode( a.login() ),
            ports.settings().emailEnabled(), ports.settings().webhooksEnabled() );
    }

    public NotifyPreference setPreference( final Actor a, final EmailMode mode ) throws SQLException {
        refuseAgent( a );
        if ( mode == null ) {
            throw new WorkValidationException( "email", "Choose each, daily or off." );
        }
        store.notifyStores().notifications().setEmailMode( a.login(), mode );
        return preference( a );
    }

    /**
     * Turns up to {@code maxEvents} new events into inbox rows, in one transaction; a directory failure rolls the
     * batch back (it is retried by the next call).
     *
     * @return the number of events read
     */
    public int dispatch( final int maxEvents ) throws SQLException {
        return store.notifyStores().notifications().dispatch( maxEvents, new NoticePlanning( scopes, people, listener ) );
    }

    /** The notification tunables the host supplied. */
    public NotifySettings settings() {
        return ports.settings();
    }

    /**
     * Mails what is due: one message per person holding everything new for them, at most once per email interval
     * (the host's schedule). Nothing is sent unless email is enabled.
     *
     * @return the number of messages sent
     */
    public int sendEmail( final Instant now ) throws SQLException {
        return emailSender.send( now );
    }

    private Set< Long > visibleIds( final Actor a ) throws SQLException {
        return scopes.standing().visibleProjects( a ).stream().map( Project::id ).collect( Collectors.toSet() );
    }

    private static void refuseAgent( final Actor a ) {
        if ( a.kind() == Actor.Kind.AGENT ) {
            throw new WorkDeniedException( AGENTS_REFUSED );
        }
    }
}

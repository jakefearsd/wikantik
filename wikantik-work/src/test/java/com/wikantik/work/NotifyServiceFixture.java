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

import com.wikantik.jdbc.testing.PostgresTestDb;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The real service over a real store with a scriptable directory, for the dispatch and inbox tests. Project WIKI:
 * lena leads, otto is a member, vera a viewer; mallory has an account and no standing. Display names are logins.
 */
final class NotifyServiceFixture {

    static final Actor ROOT = Actor.human( "root", Set.of(), true );
    static final Actor LENA = Actor.human( "lena", Set.of(), false );
    static final Actor OTTO = Actor.human( "otto", Set.of(), false );
    static final Actor VERA = Actor.human( "vera", Set.of(), false );
    static final Actor MALLORY = Actor.human( "mallory", Set.of(), false );

    final DataSource ds;
    final JdbcWorkStore store;
    final WorkService service;
    final WorkNotificationsService notices;
    final Set< String > accounts = new HashSet<>( Set.of( "lena", "otto", "vera", "mallory" ) );
    /** Logins that are wiki admins in the directory. */
    final Set< String > admins = new HashSet<>();
    final Map< String, Set< String > > groupMembers = new HashMap<>();
    final List< String > notified = new ArrayList<>();
    /** Every audit record as {@code actor action targetType target}. */
    final List< String > audited = new ArrayList<>();
    final Map< String, String > emails = new HashMap<>();
    final Map< String, Set< String > > accountGroups = new HashMap<>();
    boolean directoryDown;
    /** When set, the directory throws when asked for a display name (a payload cannot be built). */
    boolean displayNameFails;
    final Project project;
    /** The service clock; tests move it to make a slow send visible. */
    final AdjustableClock clock = new AdjustableClock( NOW );
    private final WorkDirectory directory;
    private final WorkListener listener;

    /** The instant the service clock is fixed at. */
    static final Instant NOW = Instant.parse( "2026-03-02T10:00:00Z" );

    NotifyServiceFixture() throws SQLException {
        this( WorkNotifyPorts.NONE );
    }

    NotifyServiceFixture( final WorkNotifyPorts ports ) throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        store = new JdbcWorkStore( ds );
        project = store.createProject( "WIKI", "Wiki", "" );
        store.putMember( project.id(), "lena", "user", WorkRole.LEAD, ROOT );
        store.putMember( project.id(), "otto", "user", WorkRole.MEMBER, ROOT );
        store.putMember( project.id(), "vera", "user", WorkRole.VIEWER, ROOT );
        directory = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) {
                return accountGroups.getOrDefault( login, Set.of() );
            }
            @Override public boolean exists( final String login ) { return accounts.contains( login ); }
            @Override public String displayName( final String login ) {
                if ( displayNameFails ) {
                    throw new IllegalStateException( "no display name for " + login );
                }
                return login;
            }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return accounts.contains( name ) ? Optional.of( name ) : Optional.empty();
            }
            @Override public Optional< String > loginIgnoringCase( final String name ) {
                return accounts.stream().filter( a -> a.equalsIgnoreCase( name ) ).findFirst();
            }
            @Override public Optional< Person > person( final String login ) {
                if ( directoryDown ) {
                    throw new WorkUnavailableException( "directory is down" );
                }
                return accounts.contains( login ) ? Optional.of( new Person( login, accountGroups.getOrDefault( login, Set.of() ),
                    admins.contains( login ) ) )
                    : Optional.empty();
            }
            @Override public Optional< String > email( final String login ) {
                if ( directoryDown ) {
                    throw new WorkUnavailableException( "directory is down" );
                }
                return Optional.ofNullable( emails.get( login ) );
            }
            @Override public Set< String > groupMembers( final String group ) {
                return groupMembers.getOrDefault( group, Set.of() );
            }
        };
        listener = new WorkListener() {
            @Override public void notified( final String channel, final String outcome ) {
                notified.add( channel + "/" + outcome );
            }
        };
        service = serviceWith( ports );
        notices = service.features().notifications();
    }

    /** Another service over the same store, directory and clock, with other ports (the flag a restart changes). */
    WorkService serviceWith( final WorkNotifyPorts ports ) {
        return new WorkService( store, md -> md, directory, clock, WorkSettings.DEFAULTS, listener,
            ( actor, action, type, target, detail ) -> audited.add( actor + " " + action + " " + type + " " + target ), ports );
    }

    /** Plants the dispatch cursor: what exists now is never notified. */
    void plant() throws SQLException {
        notices.dispatch( 10 );
    }

    /** Dispatches until every event written so far is consumed (see {@link DispatchAwait}). */
    void dispatchAll() throws SQLException {
        DispatchAwait.until( () -> notices.dispatch( 100 ), () -> WorkAgentFixtures.scalar( ds,
            "SELECT COUNT(*) FROM work_event e, work_notify_cursor c WHERE c.name = 'inbox' "
                + "AND (e.tx, e.id) > (c.tx, c.event_id)" ) == 0 );
    }

    /** Waits until a dispatch call would see every event written so far, without dispatching. */
    void awaitWindow() throws SQLException {
        DispatchAwait.windowPastAll( ds );
    }

    Item newItem( final Actor by ) throws SQLException {
        return service.createItem( by, new ItemDraft( "WIKI", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "",
            null, null, null, null, null, null ) ).item();
    }

    void patch( final Actor by, final Item item, final String field, final Object value ) throws SQLException {
        final Item now = store.item( item.key() ).orElseThrow();
        service.updateItem( by, item.key(), new ItemPatch( now.version(), Map.of( field, value ), null ) );
    }

    long rows( final String where ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_notification WHERE " + where );
    }

    long watches( final String login ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, "SELECT COUNT(*) FROM work_watch WHERE login = '" + login + "'" );
    }
}

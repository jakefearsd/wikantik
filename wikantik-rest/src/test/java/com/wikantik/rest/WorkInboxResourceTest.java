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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.EmailMode;
import com.wikantik.work.NoticeReason;
import com.wikantik.work.NoticeView;
import com.wikantik.work.NotifyPreference;
import com.wikantik.work.WatchState;
import com.wikantik.work.WorkConflictException;
import com.wikantik.work.WorkDeniedException;
import com.wikantik.work.WorkEvent;
import com.wikantik.work.WorkFeatures;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkNotificationsService;
import com.wikantik.work.WorkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.wikantik.rest.WorkHttp.ALICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;

/** The inbox, watch and preference routes: what they pass to the service and how its refusals go on the wire. */
class WorkInboxResourceTest {

    private WorkNotificationsService notifications;
    private WorkInboxResource servlet;

    @BeforeEach
    void setUp() {
        final WorkService svc = Mockito.mock( WorkService.class );
        final WorkFeatures features = Mockito.mock( WorkFeatures.class );
        notifications = Mockito.mock( WorkNotificationsService.class );
        Mockito.when( svc.features() ).thenReturn( features );
        Mockito.when( features.notifications() ).thenReturn( notifications );
        servlet = WorkHttp.wire( new WorkInboxResource(), svc, ALICE );
    }

    private WorkHttp.Result call( final String method, final String path, final String body ) throws Exception {
        return call( method, path, Map.of(), body );
    }

    private WorkHttp.Result call( final String method, final String path, final Map< String, String > query,
                                  final String body ) throws Exception {
        final String[] parts = path.split( "/", 3 );   // "", "inbox", "unread" ...
        final String servletPath = "/api/work/" + parts[ 1 ];
        return WorkHttp.call( servlet, method, servletPath, parts.length > 2 ? "/" + parts[ 2 ] : null, query, body );
    }

    private static NoticeView notice( final long id, final WorkEvent event ) {
        return new NoticeView( id, NoticeReason.ASSIGNED, Instant.parse( "2026-11-21T10:00:00Z" ), null, event,
            "PLAT", "Fix the build" );
    }

    private static WorkEvent assigned( final String actor, final String from, final String to ) {
        return new WorkEvent( 5, 1, 7L, "PLAT-7", Instant.parse( "2026-11-21T09:59:00Z" ), actor, Actor.Kind.HUMAN, null,
            "assigned", from, to, null );
    }

    @Test
    void inboxListsNoticesWithTheUnreadCount() throws Exception {
        Mockito.when( notifications.inbox( ALICE, 40L, 10 ) ).thenReturn(
            List.of( notice( 31, assigned( "carol", null, "alice" ) ) ) );
        Mockito.when( notifications.unread( ALICE ) ).thenReturn( 3 );
        final JsonObject o = call( "GET", "/inbox", Map.of( "before", "40", "limit", "10" ), null )
            .json().getAsJsonObject();
        assertEquals( 3, o.get( "unread" ).getAsInt() );
        final JsonObject n = o.getAsJsonArray( "notices" ).get( 0 ).getAsJsonObject();
        assertEquals( 31, n.get( "id" ).getAsInt() );
        assertEquals( "assigned", n.get( "reason" ).getAsString() );
        assertEquals( "2026-11-21T10:00:00Z", n.get( "createdAt" ).getAsString() );
        assertTrue( n.get( "readAt" ).isJsonNull() );
        assertEquals( "PLAT", n.get( "projectKey" ).getAsString() );
        assertEquals( "PLAT-7", n.get( "itemKey" ).getAsString() );
        assertEquals( "Fix the build", n.get( "itemTitle" ).getAsString() );
        assertEquals( "assigned", n.getAsJsonObject( "event" ).get( "type" ).getAsString() );
    }

    @Test
    void inboxWithNoParametersPassesNullAndZero() throws Exception {
        Mockito.when( notifications.inbox( ALICE, null, 0 ) ).thenReturn( List.of() );
        assertEquals( 200, call( "GET", "/inbox", null ).status() );
        Mockito.verify( notifications ).inbox( ALICE, null, 0 );
    }

    @Test
    void aBadBeforeIs400NamingBefore() throws Exception {
        final WorkHttp.Result r = call( "GET", "/inbox", Map.of( "before", "abc" ), null );
        assertEquals( 400, r.status() );
        assertEquals( "before", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void noticesNameDisplayNamesNotLogins() throws Exception {
        Mockito.when( notifications.inbox( any(), any(), anyInt() ) ).thenReturn( List.of(
            notice( 1, assigned( "carol-login", "dave-login", "erin-login" ) ),
            notice( 2, new WorkEvent( 6, 1, 7L, "PLAT-7", Instant.parse( "2026-11-21T09:59:00Z" ), "frank-login",
                Actor.Kind.HUMAN, null, "handoff", "in_progress|gina-login", "review|hal-login", "please look" ) ) ) );
        Mockito.when( notifications.unread( ALICE ) ).thenReturn( 2 );
        final String body = call( "GET", "/inbox", null ).body();
        for ( final String login : List.of( "carol-login", "dave-login", "erin-login", "frank-login", "gina-login",
                "hal-login" ) ) {
            assertFalse( body.contains( "\"" + login + "\"" ) || body.contains( login + "|" ) || body.contains( "|" + login ),
                login + " must not reach a non-admin: " + body );
        }
        final JsonObject e = call( "GET", "/inbox", null ).json().getAsJsonObject().getAsJsonArray( "notices" )
            .get( 0 ).getAsJsonObject().getAsJsonObject( "event" );
        assertEquals( "Name of carol-login", e.get( "actor" ).getAsString() );
        assertEquals( "Name of erin-login", e.get( "toValue" ).getAsString() );
    }

    @Test
    void anAdminStillSeesTheLoginInTheActorField() throws Exception {
        final Actor admin = Actor.human( "root", Set.of(), true );
        servlet = WorkHttp.wire( new WorkInboxResource(), servlet.workService(), admin );
        Mockito.when( notifications.inbox( admin, null, 0 ) ).thenReturn(
            List.of( notice( 1, assigned( "carol-login", null, "erin-login" ) ) ) );
        final JsonObject e = call( "GET", "/inbox", null ).json().getAsJsonObject().getAsJsonArray( "notices" )
            .get( 0 ).getAsJsonObject().getAsJsonObject( "event" );
        assertEquals( "carol-login", e.get( "actor" ).getAsString() );
        assertEquals( "Name of carol-login", e.get( "actorName" ).getAsString() );
    }

    @Test
    void unreadCount() throws Exception {
        Mockito.when( notifications.unread( ALICE ) ).thenReturn( 4 );
        final WorkHttp.Result r = call( "GET", "/inbox/unread", null );
        assertEquals( 200, r.status() );
        assertEquals( 4, r.json().getAsJsonObject().get( "unread" ).getAsInt() );
    }

    @Test
    void markReadIs204() throws Exception {
        assertEquals( 204, call( "POST", "/inbox/read/PLAT-7", null ).status() );
        Mockito.verify( notifications ).markRead( ALICE, "PLAT-7" );
    }

    @Test
    void markAllReadIs204() throws Exception {
        assertEquals( 204, call( "POST", "/inbox/read-all", null ).status() );
        Mockito.verify( notifications ).markAllRead( ALICE );
    }

    @Test
    void aStrangersReadOfAnItemIs404() throws Exception {
        final WorkNotFoundException gone = new WorkNotFoundException( "Not found." );
        Mockito.doThrow( gone ).when( notifications ).markRead( any(), eq( "SECRET-1" ) );
        Mockito.when( notifications.watchState( any(), eq( "SECRET-1" ) ) ).thenThrow( gone );
        Mockito.when( notifications.setWatch( any(), eq( "SECRET-1" ), any() ) ).thenThrow( gone );
        for ( final String[] route : new String[][] { { "POST", "/inbox/read/SECRET-1", null },
                { "GET", "/watch/SECRET-1", null }, { "PUT", "/watch/SECRET-1", "{\"state\":\"watching\"}" } } ) {
            final WorkHttp.Result r = call( route[ 0 ], route[ 1 ], route[ 2 ] );
            assertEquals( 404, r.status(), route[ 0 ] + route[ 1 ] );
            assertEquals( "Not found.", r.json().getAsJsonObject().get( "message" ).getAsString() );
            assertFalse( r.body().contains( "SECRET" ) );
        }
    }

    @Test
    void watchStateIsRead() throws Exception {
        Mockito.when( notifications.watchState( ALICE, "PLAT-7" ) ).thenReturn( WatchState.MUTED );
        final WorkHttp.Result r = call( "GET", "/watch/PLAT-7", null );
        assertEquals( 200, r.status() );
        assertEquals( "muted", r.json().getAsJsonObject().get( "state" ).getAsString() );
    }

    @Test
    void watchStateIsSet() throws Exception {
        Mockito.when( notifications.setWatch( ALICE, "PLAT-7", WatchState.WATCHING ) ).thenReturn( WatchState.WATCHING );
        final WorkHttp.Result r = call( "PUT", "/watch/PLAT-7", "{\"state\":\"watching\"}" );
        assertEquals( 200, r.status() );
        assertEquals( "watching", r.json().getAsJsonObject().get( "state" ).getAsString() );
    }

    @Test
    void aBadStateIs400NamingState() throws Exception {
        final WorkHttp.Result r = call( "PUT", "/watch/PLAT-7", "{\"state\":\"loud\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "state", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anUnknownWatchFieldIs400NamingIt() throws Exception {
        final WorkHttp.Result r = call( "PUT", "/watch/PLAT-7", "{\"state\":\"none\",\"user\":\"bob\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "user", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anAgentCannotWatchAndAnArchivedProjectRefuses() throws Exception {
        Mockito.when( notifications.setWatch( any(), eq( "PLAT-1" ), any() ) )
            .thenThrow( new WorkDeniedException( "Agents are not notified." ) );
        assertEquals( 403, call( "PUT", "/watch/PLAT-1", "{\"state\":\"watching\"}" ).status() );
        Mockito.when( notifications.setWatch( any(), eq( "OLD-1" ), any() ) )
            .thenThrow( new WorkConflictException( "This project is archived.", WorkConflictException.ARCHIVED ) );
        final WorkHttp.Result r = call( "PUT", "/watch/OLD-1", "{\"state\":\"watching\"}" );
        assertEquals( 409, r.status() );
        assertEquals( "archived", r.json().getAsJsonObject().get( "reason" ).getAsString() );
    }

    @Test
    void preferencesAreRead() throws Exception {
        Mockito.when( notifications.preference( ALICE ) ).thenReturn( new NotifyPreference( EmailMode.DAILY, true, false ) );
        final JsonObject o = call( "GET", "/notify-prefs", null ).json().getAsJsonObject();
        assertEquals( "daily", o.get( "email" ).getAsString() );
        assertTrue( o.get( "emailAvailable" ).getAsBoolean() );
        assertFalse( o.get( "webhooksAvailable" ).getAsBoolean() );
    }

    @Test
    void preferencesAreSet() throws Exception {
        Mockito.when( notifications.setPreference( ALICE, EmailMode.EACH ) ).thenReturn(
            new NotifyPreference( EmailMode.EACH, true, true ) );
        final WorkHttp.Result r = call( "PUT", "/notify-prefs", "{\"email\":\"each\"}" );
        assertEquals( 200, r.status() );
        assertEquals( "each", r.json().getAsJsonObject().get( "email" ).getAsString() );
    }

    @Test
    void aBadEmailChoiceIs400NamingEmail() throws Exception {
        final WorkHttp.Result r = call( "PUT", "/notify-prefs", "{\"email\":\"hourly\"}" );
        assertEquals( 400, r.status() );
        assertEquals( "email", r.json().getAsJsonObject().get( "field" ).getAsString() );
    }

    @Test
    void anAgentCannotSetAnEmailChoice() throws Exception {
        Mockito.when( notifications.setPreference( any(), any() ) ).thenThrow( new WorkDeniedException( "Agents are not notified." ) );
        assertEquals( 403, call( "PUT", "/notify-prefs", "{\"email\":\"off\"}" ).status() );
    }
}

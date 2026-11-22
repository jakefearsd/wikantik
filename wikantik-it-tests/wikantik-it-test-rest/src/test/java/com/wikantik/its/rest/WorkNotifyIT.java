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
package com.wikantik.its.rest;

import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Work notifications end to end against the real dispatcher thread (interval 2 s in this module, see
 * it-rest-cascade.properties): an assignee hears of the assignment, and stops hearing, and loses the project's
 * webhook routes, the moment access is removed. Email and webhooks are off in this wiki: the delivery paths are
 * covered by unit tests, and the IT wiki cannot reach a public URL.
 */
public class WorkNotifyIT {

    private static Caller admin;
    private static Caller lena;
    private static Caller otto;

    @BeforeAll
    static void setUp() throws Exception {
        admin = WorkIt.admin();
        lena = WorkIt.account( admin, "notelena", "Note Lena Lead" );
        otto = WorkIt.account( admin, "noteotto", "Note Otto Member" );
        assertEquals( 201, admin.send( "POST", "/api/work/projects",
            Map.of( "key", "NOTE", "name", "Notes", "description", "d" ) ).statusCode() );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", "notelena",
            "principalKind", "user", "role", "lead" ) ).statusCode() );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", "noteotto",
            "principalKind", "user", "role", "member" ) ).statusCode() );
    }

    @Test
    void anAssigneeHearsAndLosesItWithTheirAccess() throws Exception {
        final HttpResponse< String > made = lena.send( "POST", "/api/work/items", Map.of( "project", "NOTE",
            "level", "task", "title", "Hear this", "assignee", "Note Otto Member" ) );
        assertEquals( 201, made.statusCode(), made.body() );
        // Test methods run in no fixed order, so the key is whatever the project handed out.
        final String key = json( made ).getAsJsonObject().get( "item" ).getAsJsonObject().get( "key" ).getAsString();

        final String body = WorkIt.await( "inbox", otto, "/api/work/inbox", b -> b.contains( key ) );
        final JsonObject notice = json( otto.get( "/api/work/inbox" ) ).getAsJsonObject()
            .getAsJsonArray( "notices" ).get( 0 ).getAsJsonObject();
        assertTrue( body.contains( key ) );
        assertEquals( "assigned", notice.get( "reason" ).getAsString() );
        assertEquals( "Note Lena Lead", notice.getAsJsonObject( "event" ).get( "actorName" ).getAsString(),
            "the notice names the actor by display name" );
        assertEquals( 1, json( otto.get( "/api/work/inbox/unread" ) ).getAsJsonObject().get( "unread" ).getAsInt() );
        // the actor never hears of their own action
        assertEquals( 0, json( lena.get( "/api/work/inbox" ) ).getAsJsonObject().getAsJsonArray( "notices" ).size() );

        assertEquals( 204, admin.send( "DELETE", "/api/work/projects/NOTE/members",
            Map.of( "principal", "noteotto", "principalKind", "user" ) ).statusCode() );

        final JsonObject after = json( otto.get( "/api/work/inbox" ) ).getAsJsonObject();
        assertEquals( 0, after.getAsJsonArray( "notices" ).size(), "a removed member's inbox shows nothing of the project" );
        assertEquals( 0, after.get( "unread" ).getAsInt() );
        assertEquals( 0, json( otto.get( "/api/work/inbox/unread" ) ).getAsJsonObject().get( "unread" ).getAsInt() );
        assertEquals( 404, otto.get( "/api/work/projects/NOTE/webhooks" ).statusCode() );
    }

    @Test
    void aLeadManagesAWebhookAndNeverSeesTheSecretAgain() throws Exception {
        final JsonObject notify = json( lena.get( "/api/work/me" ) ).getAsJsonObject().getAsJsonObject( "notify" );
        assertFalse( notify.get( "emailAvailable" ).getAsBoolean(), "email is off in the IT wiki" );
        assertFalse( notify.get( "webhooksAvailable" ).getAsBoolean(), "webhooks are off in the IT wiki" );

        final HttpResponse< String > add = lena.send( "POST", "/api/work/projects/NOTE/webhooks",
            Map.of( "url", "https://example.com/hook", "format", "generic" ) );
        assertEquals( 409, add.statusCode(), add.body() );
        assertEquals( "webhooks_off", json( add ).getAsJsonObject().get( "reason" ).getAsString() );
        assertNull( json( add ).getAsJsonObject().get( "secret" ), "a refused add carries no secret" );
    }

    /** A bearer call: only the Authorization header, no cookie jar and no session. */
    private static HttpResponse< String > as( final String token, final String method, final String path, final Object body )
            throws Exception {
        return WorkIt.bearer( token, method, path, body == null ? null : WorkIt.GSON.toJson( body ) );
    }

    private static String mint( final Caller owner, final String scope ) throws Exception {
        final HttpResponse< String > r = owner.send( "POST", "/api/self/apikeys", Map.of( "label", "note-" + scope, "scope", scope ) );
        assertEquals( 201, r.statusCode(), "mint " + scope );
        return json( r ).getAsJsonObject().get( "token" ).getAsString();
    }

    @Test
    void theNewRoutesAnswerAWorkKeyAnAgentTokenAndAWrongScopedKeyLikeTheOtherWorkRoutes() throws Exception {
        final HttpResponse< String > made = lena.send( "POST", "/api/work/items", Map.of( "project", "NOTE",
            "level", "task", "title", "Credential check" ) );
        assertEquals( 201, made.statusCode(), made.body() );
        final String item = json( made ).getAsJsonObject().get( "item" ).getAsJsonObject().get( "key" ).getAsString();
        final String workKey = mint( lena, "work" );

        // a work key acts as its owner: every route family answers, and a write goes through
        assertEquals( 200, as( workKey, "GET", "/api/work/me", null ).statusCode(), "control: the key reaches work" );
        assertEquals( 200, as( workKey, "GET", "/api/work/inbox", null ).statusCode() );
        assertEquals( 200, as( workKey, "GET", "/api/work/inbox/unread", null ).statusCode() );
        assertEquals( 200, as( workKey, "GET", "/api/work/notify-prefs", null ).statusCode() );
        assertEquals( 200, as( workKey, "GET", "/api/work/watch/" + item, null ).statusCode() );
        final HttpResponse< String > muted = as( workKey, "PUT", "/api/work/watch/" + item, Map.of( "state", "muted" ) );
        assertEquals( 200, muted.statusCode(), muted.body() );
        assertEquals( "muted", json( lena.get( "/api/work/watch/" + item ) ).getAsJsonObject().get( "state" ).getAsString(),
            "the key's write is the owner's own watch state" );

        // an agent token: nobody to notify, so an empty inbox, and a write is refused
        final WorkIt.Agent bot = WorkIt.registerAgent( admin, "notebot@it" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", bot.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        final HttpResponse< String > inbox = as( bot.token(), "GET", "/api/work/inbox", null );
        assertEquals( 200, inbox.statusCode(), inbox.body() );
        assertEquals( 0, json( inbox ).getAsJsonObject().getAsJsonArray( "notices" ).size() );
        assertEquals( 403, as( bot.token(), "PUT", "/api/work/watch/" + item, Map.of( "state", "watching" ) ).statusCode() );
        assertEquals( 403, as( bot.token(), "PUT", "/api/work/notify-prefs", Map.of( "email", "off" ) ).statusCode() );

        // a key of another scope is refused as the existing work routes refuse it
        final String readKey = mint( lena, "mcp_read" );
        final HttpResponse< String > control = as( readKey, "GET", "/api/work/me", null );
        assertEquals( 403, control.statusCode() );
        for ( final String path : new String[] { "/api/work/inbox", "/api/work/inbox/unread", "/api/work/notify-prefs",
                "/api/work/watch/" + item, "/api/work/projects/NOTE/webhooks" } ) {
            final HttpResponse< String > r = as( readKey, "GET", path, null );
            assertEquals( 403, r.statusCode(), path );
            assertEquals( control.body(), r.body(), path );
        }
        assertEquals( 403, as( readKey, "PUT", "/api/work/watch/" + item, Map.of( "state", "watching" ) ).statusCode() );
    }

    @Test
    void aMemberListsNoWebhooksAndALeadListsThem() throws Exception {
        // WorkWebhooksService.list: a caller who may not manage the project gets an empty list, a lead gets the list,
        // and neither is gated by the global switch (only add and update are)
        final Caller member = WorkIt.account( admin, "notemia", "Note Mia Member" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", "notemia",
            "principalKind", "user", "role", "member" ) ).statusCode() );
        final HttpResponse< String > asMember = member.get( "/api/work/projects/NOTE/webhooks" );
        assertEquals( 200, asMember.statusCode(), asMember.body() );
        assertEquals( 0, json( asMember ).getAsJsonArray().size() );
        final HttpResponse< String > asLead = lena.get( "/api/work/projects/NOTE/webhooks" );
        assertEquals( 200, asLead.statusCode(), asLead.body() );
        assertEquals( 0, json( asLead ).getAsJsonArray().size(), "none were added: the switch is off in this wiki" );
    }
}

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
package com.wikantik.its.work;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.its.work.WorkIt.Caller;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.wikantik.its.work.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    void aLeadHearsOfAReportersRequestAndOfTheirCommentsUntilSomeoneHoldsIt() throws Exception {
        final Caller rita = WorkIt.account( admin, "noterita", "Note Rita Reporter" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", "noterita",
            "principalKind", "user", "role", "reporter" ) ).statusCode() );
        // Her own member: the other test of this class takes otto out of the project, and tests run in no fixed order.
        final Caller mia = WorkIt.account( admin, "notemia", "Note Mia Member" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/NOTE/members", Map.of( "principal", "notemia",
            "principalKind", "user", "role", "member" ) ).statusCode() );
        final HttpResponse< String > filed = rita.send( "POST", "/api/work/intake/items",
            Map.of( "projectKey", "NOTE", "title", "Please look at this", "body", "it broke" ) );
        assertEquals( 201, filed.statusCode(), filed.body() );
        final String key = json( filed ).getAsJsonObject().get( "key" ).getAsString();

        WorkIt.await( "the lead's inbox", lena, "/api/work/inbox", b -> b.contains( key ) );
        final JsonObject request = noticeFor( lena, key, "created" );
        assertEquals( "requested", request.get( "reason" ).getAsString() );
        assertEquals( "Note Rita Reporter", request.getAsJsonObject( "event" ).get( "actorName" ).getAsString() );
        assertNull( noticeFor( mia, key, "created" ), "a member who is not a lead is not told" );
        assertNull( noticeFor( rita, key, "created" ), "the reporter is not told of their own filing" );

        assertEquals( 201, rita.send( "POST", "/api/work/intake/items/" + key + "/comments",
            Map.of( "body", "any news?" ) ).statusCode() );
        WorkIt.await( "the lead's inbox", lena, "/api/work/inbox", b -> b.contains( "\"commented\"" ) );
        assertEquals( "requested", noticeFor( lena, key, "commented" ).get( "reason" ).getAsString() );

        // Once the lead assigns it (by editing the field), the member is named the lead as the one who assigned it,
        // and the reporter's next comment goes to the assignee, not to the lead.
        final JsonObject item = json( lena.get( "/api/work/items/" + key ) ).getAsJsonObject().getAsJsonObject( "item" );
        final HttpResponse< String > assigned = lena.send( "PATCH", "/api/work/items/" + key, Map.of(
            "expectedVersion", item.get( "version" ).getAsLong(), "changes", Map.of( "assignee", "Note Mia Member" ) ) );
        assertEquals( 200, assigned.statusCode(), assigned.body() );
        JsonObject waiting = null;
        for ( final JsonElement w : json( mia.get( "/api/work/me" ) ).getAsJsonObject().getAsJsonArray( "waitingOn" ) ) {
            if ( key.equals( w.getAsJsonObject().get( "key" ).getAsString() ) ) {
                waiting = w.getAsJsonObject().getAsJsonObject( "waiting" );
            }
        }
        assertNotNull( waiting, "the item waits on the member" );
        assertEquals( "assigned", waiting.get( "reason" ).getAsString() );
        assertEquals( "Note Lena Lead", waiting.get( "byName" ).getAsString(), "an assignment made by editing the field names who made it" );

        WorkIt.await( "the member's inbox", mia, "/api/work/inbox", b -> b.contains( key ) );
        assertEquals( 201, rita.send( "POST", "/api/work/intake/items/" + key + "/comments",
            Map.of( "body", "thank you" ) ).statusCode() );
        WorkIt.await( "the member's inbox", mia, "/api/work/inbox", b -> b.contains( "\"commented\"" ) );
        assertEquals( "watching", noticeFor( mia, key, "commented" ).get( "reason" ).getAsString() );
        int toLead = 0;
        for ( final JsonElement n : json( lena.get( "/api/work/inbox" ) ).getAsJsonObject().getAsJsonArray( "notices" ) ) {
            final JsonObject o = n.getAsJsonObject();
            if ( key.equals( o.get( "itemKey" ).getAsString() ) && "requested".equals( o.get( "reason" ).getAsString() ) ) {
                toLead++;
            }
        }
        assertEquals( 2, toLead, "the filing and the first comment, not the comment after the assignment" );
    }

    /** The newest notice in the caller's inbox for the item and event type, or null. */
    private static JsonObject noticeFor( final Caller who, final String key, final String type ) throws Exception {
        for ( final JsonElement n : json( who.get( "/api/work/inbox" ) ).getAsJsonObject().getAsJsonArray( "notices" ) ) {
            final JsonObject o = n.getAsJsonObject();
            if ( !o.get( "itemKey" ).isJsonNull() && key.equals( o.get( "itemKey" ).getAsString() )
                && type.equals( o.getAsJsonObject( "event" ).get( "type" ).getAsString() ) ) {
                return o;
            }
        }
        return null;
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

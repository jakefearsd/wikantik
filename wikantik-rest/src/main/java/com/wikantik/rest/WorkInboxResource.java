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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.work.EmailMode;
import com.wikantik.work.NoticeView;
import com.wikantik.work.NotifyPreference;
import com.wikantik.work.WatchState;
import com.wikantik.work.WorkNotificationsService;
import com.wikantik.work.WorkValidationException;

import java.util.List;
import java.util.Set;

/**
 * The caller's own notification surface: {@code /api/work/inbox} (notices, unread count, mark read),
 * {@code /api/work/watch/{itemKey}} and {@code /api/work/notify-prefs}. Every notice goes through the display-name
 * path, so a login reaches no one but a wiki admin; an item the caller cannot see is the same 404 as one that does
 * not exist.
 */
public class WorkInboxResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    private static final String STATE = "state";
    private static final String EMAIL = "email";

    @Override
    protected void routes( final SegmentRouteTable.Builder< Handler > r ) {
        route( r, "GET", "inbox", this::inbox );
        route( r, "GET", "inbox/unread", c -> c.tree( unreadOnly( notifications( c ).unread( c.actor ) ) ) );
        route( r, "POST", "inbox/read/{}", c -> {
            notifications( c ).markRead( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
        route( r, "POST", "inbox/read-all", c -> {
            notifications( c ).markAllRead( c.actor );
            c.noContent();
        } );
        route( r, "GET", "watch/{}", c -> c.tree( state( notifications( c ).watchState( c.actor, c.pathParam( 0 ) ) ) ) );
        route( r, "PUT", "watch/{}", this::setWatch );
        route( r, "GET", "notify-prefs", c -> c.tree( preference( notifications( c ).preference( c.actor ) ) ) );
        route( r, "PUT", "notify-prefs", this::setPreference );
    }

    private static WorkNotificationsService notifications( final Call c ) {
        return c.svc.features().notifications();
    }

    private void inbox( final Call c ) throws Exception {
        final WorkNotificationsService n = notifications( c );
        final List< NoticeView > notices = n.inbox( c.actor, before( c ), clampLimit( c ) );
        final WorkLookup lookup = lookupFor( c.actor );
        final JsonArray list = new JsonArray();
        for ( final NoticeView v : notices ) {
            list.add( notice( v, c, lookup ) );
        }
        final JsonObject out = new JsonObject();
        out.add( "notices", list );
        out.addProperty( "unread", n.unread( c.actor ) );
        c.tree( out );
    }

    /** {@code {id, reason, createdAt, readAt, projectKey, itemKey, itemTitle, event}}, the event enriched once. */
    private JsonObject notice( final NoticeView v, final Call c, final WorkLookup lookup ) {
        final JsonObject raw = toTree( v, c.actor, lookup ).getAsJsonObject();
        final JsonElement event = raw.get( "event" );
        final JsonObject out = new JsonObject();
        for ( final String field : List.of( "id", "reason", "createdAt", "readAt", "projectKey" ) ) {
            out.add( field, raw.get( field ) );
        }
        out.add( "itemKey", event.getAsJsonObject().get( "itemKey" ) );
        out.add( "itemTitle", raw.get( "itemTitle" ) );
        out.add( "event", event );
        return out;
    }

    private static Long before( final Call c ) {
        final String raw = c.query( "before" );
        if ( raw == null ) {
            return null;
        }
        try {
            return Long.valueOf( raw );
        } catch ( final NumberFormatException e ) {
            throw new WorkValidationException( "before", "before must be a notice id." );
        }
    }

    private void setWatch( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        WorkSprintResource.reject( b, Set.of( STATE ) );
        final WatchState wanted = WatchState.fromWire( requiredString( b, STATE ) );
        c.tree( state( notifications( c ).setWatch( c.actor, c.pathParam( 0 ), wanted ) ) );
    }

    private void setPreference( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        WorkSprintResource.reject( b, Set.of( EMAIL ) );
        final EmailMode mode = EmailMode.fromWire( requiredString( b, EMAIL ) );
        c.tree( preference( notifications( c ).setPreference( c.actor, mode ) ) );
    }

    private static JsonObject unreadOnly( final int unread ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "unread", unread );
        return o;
    }

    private static JsonObject state( final WatchState s ) {
        final JsonObject o = new JsonObject();
        o.addProperty( STATE, s.wire() );
        return o;
    }

    private static JsonObject preference( final NotifyPreference p ) {
        final JsonObject o = new JsonObject();
        o.addProperty( EMAIL, p.email().wire() );
        o.addProperty( "emailAvailable", p.emailAvailable() );
        o.addProperty( "webhooksAvailable", p.webhooksAvailable() );
        return o;
    }
}

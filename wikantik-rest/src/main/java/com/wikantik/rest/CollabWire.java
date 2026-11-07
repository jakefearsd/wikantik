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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.collab.model.CollabEvent;
import com.wikantik.collab.model.JoinResponse;
import com.wikantik.collab.model.MetaOp;
import com.wikantik.collab.model.PresenceEntry;
import com.wikantik.collab.model.PullCursor;
import com.wikantik.collab.model.PullResponse;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSetJson;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Date;
import java.util.Map;

/**
 * The {@code /api/collab/*} wire format (docs/MultiEdit.MD section 6.2 as amended; the shared fixture
 * {@code wikantik-frontend/src/collab/__fixtures__/collab-protocol.json} is the contract). Bodies are built as JSON trees
 * and written with explicit nulls (a presence entry without a selection carries four nulls; a meta op's {@code value:
 * null} removes the field). Every response is {@code Cache-Control: no-store}.
 */
final class CollabWire {

    /** Compact, null-keeping. */
    static final Gson GSON = new GsonBuilder().serializeNulls()
        .registerTypeAdapter( Date.class, RestServletBase.UTC_ISO_DATE_SERIALIZER ).create();

    static final String REASON = "reason";
    static final String MESSAGE = "message";

    /** One answer: a status and a body ({@code null} for 204). */
    record Answer( int status, JsonElement body ) {
    }

    private CollabWire() {
    }

    static void noStore( final HttpServletResponse response ) {
        response.setHeader( "Cache-Control", "no-store" );
    }

    static void write( final HttpServletResponse response, final Answer a ) throws IOException {
        noStore( response );
        response.setStatus( a.status() );
        if( a.body() != null ) {
            response.setContentType( "application/json" );
            response.setCharacterEncoding( "UTF-8" );
            response.getWriter().write( GSON.toJson( a.body() ) );
        }
    }

    static Answer noContent() {
        return new Answer( 204, null );
    }

    static Answer reason( final int status, final String reason ) {
        final JsonObject o = new JsonObject();
        o.addProperty( REASON, reason );
        return new Answer( status, o );
    }

    static Answer rejected( final String message ) {
        final JsonObject o = new JsonObject();
        o.addProperty( REASON, "rejected" );
        o.addProperty( MESSAGE, message );
        return new Answer( 400, o );
    }

    /** 403 for a caller without the needed permission, in the RestServletBase envelope's shape. */
    static Answer forbidden( final String pageName ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "error", "forbidden" );
        o.addProperty( MESSAGE, "No edit permission on " + pageName );
        return new Answer( 403, o );
    }

    /**
     * A refusal from the session as section 6.2 words it: {@code rejoin} keeps {@code rejoin: true}, {@code resync} keeps
     * {@code resync: true}, {@code stale-base} carries {@code accepted: false} and the head {@code version}, and 400/413
     * carry a message.
     */
    static Answer refusal( final int status, final String reason, final int head ) {
        return refusal( status, reason, null, head );
    }

    /**
     * As {@link #refusal(int, String, int)}, with the session's {@code detail} when it gave one: 403 {@code evicted}
     * carries why ({@code lost-edit}, {@code signed-out}, {@code account-deleted}).
     */
    static Answer refusal( final int status, final String reason, final String detail, final int head ) {
        final String r = reason == null ? "error" : reason;
        final JsonObject o = new JsonObject();
        switch( r ) {
            case "stale-base" -> {
                o.addProperty( "accepted", false );
                o.addProperty( REASON, r );
                o.addProperty( "version", head );
            }
            case "rejoin" -> {
                o.addProperty( "rejoin", true );
                o.addProperty( REASON, r );
            }
            case "resync" -> {
                o.addProperty( "resync", true );
                o.addProperty( REASON, r );
            }
            case "rejected" -> {
                o.addProperty( REASON, r );
                o.addProperty( MESSAGE, "the update was refused: malformed, a length mismatch, a lone surrogate or"
                    + " another participant's clientID" );
            }
            case "oversize" -> {
                o.addProperty( REASON, r );
                o.addProperty( MESSAGE, "document would exceed maxPageBytes" );
            }
            default -> o.addProperty( REASON, r );
        }
        if( detail != null ) {
            o.addProperty( "detail", detail );
        }
        return new Answer( status, o );
    }

    static Answer joined( final JoinResponse j ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "sessionId", j.sessionId().toString() );
        o.addProperty( "participantId", j.participantId() );
        o.addProperty( "clientID", j.clientId() );
        o.addProperty( "version", j.version() );
        o.addProperty( "doc", j.doc() );
        o.addProperty( "metaSeq", j.metaSeq() );
        o.add( "metadata", GSON.toJsonTree( j.metadata() ) );
        o.addProperty( "basePageVersion", j.basePageVersion() );
        final JsonArray peers = new JsonArray();
        for( final JoinResponse.Peer p : j.participants() ) {
            final JsonObject po = new JsonObject();
            po.addProperty( "participantId", p.participantId() );
            po.addProperty( "displayName", p.displayName() );
            po.addProperty( "color", p.color() );
            peers.add( po );
        }
        o.add( "participants", peers );
        final JsonObject draft = new JsonObject();
        draft.addProperty( "unpublished", j.unpublished() );
        draft.add( "contributors", new JsonArray() );
        draft.addProperty( "lastEditAt", j.lastEditAt() == null ? null : j.lastEditAt().toString() );
        o.add( "draft", draft );
        o.addProperty( "eventSeq", j.eventSeq() );
        return new Answer( 200, o );
    }

    static Answer pushed( final int version, final long metaSeq ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "accepted", true );
        o.addProperty( "version", version );
        o.addProperty( "metaSeq", metaSeq );
        return new Answer( 200, o );
    }

    /** A pull answer: 200 as section 6.2 amended, else the refusal. */
    static Answer pulled( final PullResponse r ) {
        if( r.status() != 200 ) {
            return refusal( r.status(), r.reason(), r.detail(), r.version() );
        }
        final JsonObject o = new JsonObject();
        final JsonArray updates = new JsonArray();
        for( final Update u : r.updates() ) {
            final JsonObject uo = new JsonObject();
            uo.addProperty( "clientID", u.clientId() );
            uo.add( "changes", ChangeSetJson.toJson( u.changes() ) );
            updates.add( uo );
        }
        o.add( "updates", updates );
        final JsonArray ops = new JsonArray();
        for( final MetaOp m : r.metaOps() ) {
            final JsonObject mo = new JsonObject();
            mo.addProperty( "field", m.field() );
            mo.add( "value", GSON.toJsonTree( m.value() ) );
            if( m.by() != null ) {
                mo.addProperty( "by", m.by() );   // the author's public name; server ops carry none
            }
            ops.add( mo );
        }
        o.add( "metaOps", ops );
        o.addProperty( "metaSeq", r.metaSeq() );
        final JsonObject presence = new JsonObject();
        for( final Map.Entry< String, PresenceEntry > e : r.presence().entrySet() ) {
            presence.add( e.getKey(), GSON.toJsonTree( e.getValue() ) );
        }
        o.add( "presence", presence );
        o.addProperty( "presenceSeq", r.presenceSeq() );
        final JsonArray events = new JsonArray();
        for( final CollabEvent ev : r.events() ) {
            final JsonObject eo = new JsonObject();
            eo.addProperty( "seq", ev.seq() );
            eo.addProperty( "type", ev.type() );
            ev.fields().forEach( ( k, v ) -> eo.add( k, GSON.toJsonTree( v ) ) );
            events.add( eo );
        }
        o.add( "events", events );
        o.addProperty( "eventSeq", r.eventSeq() );
        return new Answer( 200, o );
    }

    /**
     * The empty answer of a long-poll the container timed out before the session did: nothing new, every cursor echoed
     * back, so the client's next pull asks for exactly what it asked for now.
     */
    static Answer emptyPull( final PullCursor c ) {
        final JsonObject o = new JsonObject();
        o.add( "updates", new JsonArray() );
        o.add( "metaOps", new JsonArray() );
        o.addProperty( "metaSeq", c.metaSince() );
        o.add( "presence", new JsonObject() );
        o.addProperty( "presenceSeq", c.presenceSince() );
        o.add( "events", new JsonArray() );
        o.addProperty( "eventSeq", c.eventsSince() );
        return new Answer( 200, o );
    }

    /** The publish answer's 200 body. */
    static Answer published( final int pageVersion, final Object warnings, final Object mathWarnings ) {
        final JsonObject o = new JsonObject();
        o.addProperty( "pageVersion", pageVersion );
        o.add( "warnings", GSON.toJsonTree( warnings ) );
        o.add( "mathWarnings", GSON.toJsonTree( mathWarnings ) );
        return new Answer( 200, o );
    }

    static Answer of( final int status, final Map< String, Object > body ) {
        return new Answer( status, GSON.toJsonTree( body ) );
    }
}

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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.collab.CollabSession;
import com.wikantik.collab.model.MetaEdit;
import com.wikantik.collab.model.PushResponse;
import com.wikantik.collab.model.Selection;
import com.wikantik.collab.model.Update;
import com.wikantik.collab.text.ChangeSetJson;
import com.wikantik.rest.CollabWire.Answer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Parses a {@code POST /updates} body (section 6.2 as amended) from untrusted JSON, before the session sees any of it:
 * {@code version} an integer, {@code updates} a list of {@code {clientID, changes}} with CodeMirror change sets
 * ({@link ChangeSetJson#fromJson}, which refuses every malformed shape), an optional {@code selection} (A12) and
 * {@code metaOps} as {@link MetaEdit}s ({@link CollabMetaOps}, A14). Any wire defect is a refusal message for 400
 * {@code rejected}, answered before the session is called; nothing is applied. The lists are built without nulls.
 */
final class CollabPush {

    private static final Logger LOG = LogManager.getLogger( CollabPush.class );

    /** Either {@code refusal} (a message) or the parsed push. */
    record Parsed( String refusal, int version, List< Update > updates, List< MetaEdit > metaEdits,
                   Selection selection ) {
        static Parsed refused( final String why ) {
            return new Parsed( why, 0, List.of(), List.of(), null );
        }
    }

    private CollabPush() {
    }

    /**
     * {@code POST /updates} past the session lookup: wire defects are 400 {@code rejected} before the session is called;
     * otherwise the session's push (which refuses in the order identity, rate/freeze, stale base when the push carries
     * text, text checks, meta checks), reported to {@code observe}. A 429 carries {@code retryAfterMs}.
     */
    static Answer run( final CollabSession s, final String participantId, final String authorKey,
                       final JsonObject body, final int pushesPerSecond, final BiConsumer< Integer, String > observe ) {
        final Parsed p = parse( body );
        if( p.refusal() != null ) {
            LOG.warn( "collab session {}: push from {} refused before the session: {}", s.id(), participantId,
                p.refusal() );
            return CollabWire.rejected( p.refusal() );
        }
        final PushResponse r = s.push( participantId, authorKey, p.version(), p.updates(), p.metaEdits(),
            p.selection() );
        observe.accept( r.status(), r.reason() );
        if( r.ok() ) {
            return CollabWire.pushed( r.version(), r.metaSeq() );
        }
        final Answer a = CollabWire.refusal( r.status(), r.reason(), r.version() );
        if( r.status() == 429 ) {
            ( ( JsonObject ) a.body() ).addProperty( "retryAfterMs", Math.max( 1, 1000 / Math.max( 1, pushesPerSecond ) ) );
        }
        return a;
    }

    static Parsed parse( final JsonObject body ) {
        final Integer version = integer( body.get( "version" ) );
        if( version == null ) {
            return Parsed.refused( "version must be an integer" );
        }
        final List< MetaEdit > metaEdits = new ArrayList<>();
        String why = CollabMetaOps.parse( body.get( "metaOps" ), metaEdits );
        if( why != null ) {
            return Parsed.refused( why );
        }
        final List< Update > updates = new ArrayList<>();
        why = updates( body.get( "updates" ), updates );
        if( why != null ) {
            return Parsed.refused( why );
        }
        final Object selection = selection( body.get( "selection" ) );
        if( selection instanceof String refusal ) {
            return Parsed.refused( refusal );
        }
        return new Parsed( null, version, updates, metaEdits, ( Selection ) selection );
    }

    /** Null when every update was added (an absent list is empty), else why not. */
    private static String updates( final JsonElement list, final List< Update > into ) {
        if( list == null || list.isJsonNull() ) {
            return null;
        }
        if( !list.isJsonArray() ) {
            return "updates must be a list";
        }
        for( final JsonElement e : list.getAsJsonArray() ) {
            final String why = addUpdate( e, into );
            if( why != null ) {
                return why;
            }
        }
        return null;
    }

    /** The selection, null when absent, or a refusal message (a String). */
    private static Object selection( final JsonElement sel ) {
        if( sel == null || sel.isJsonNull() ) {
            return null;
        }
        if( !sel.isJsonObject() ) {
            return "selection must be an object";
        }
        final JsonObject s = sel.getAsJsonObject();
        final Integer anchor = integer( s.get( "anchor" ) );
        final Integer head = integer( s.get( "head" ) );
        final Integer v = integer( s.get( "version" ) );
        final Integer unconfirmed = integer( s.get( "unconfirmed" ) );
        if( anchor == null || head == null || v == null || unconfirmed == null ) {
            return "selection needs integer anchor, head, version and unconfirmed";
        }
        try {
            return new Selection( anchor, head, v, unconfirmed );
        } catch( final IllegalArgumentException e ) {
            return "selection: " + e.getMessage();
        }
    }

    /** Null when added, else why not. */
    private static String addUpdate( final JsonElement e, final List< Update > into ) {
        if( !e.isJsonObject() ) {
            return "an update must be an object";
        }
        final JsonObject u = e.getAsJsonObject();
        final JsonElement client = u.get( "clientID" );
        if( !isString( client ) ) {
            return "an update needs a clientID string";
        }
        final JsonElement changes = u.get( "changes" );
        if( changes == null || !changes.isJsonArray() ) {
            return "an update needs a changes list";
        }
        try {
            into.add( new Update( client.getAsString(), ChangeSetJson.fromJson( changes.getAsJsonArray() ) ) );
            return null;
        } catch( final RuntimeException ex ) {
            return "malformed changes: " + ex.getMessage();
        }
    }

    static boolean isString( final JsonElement e ) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    /** The value as an {@code int} when it is a JSON number with an exact int value, else null. */
    static Integer integer( final JsonElement e ) {
        if( e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber() ) {
            return null;
        }
        try {
            final BigDecimal d = e.getAsBigDecimal();
            return d.intValueExact();
        } catch( final ArithmeticException | NumberFormatException ex ) {
            return null;
        }
    }
}

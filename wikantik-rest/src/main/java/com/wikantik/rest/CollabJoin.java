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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.rest.CollabResource.Access;
import com.wikantik.rest.CollabResource.Caller;
import com.wikantik.rest.CollabWire.Answer;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * {@code POST /join} (section 6.2 as amended by A11): 503 {@code collab-disabled} with the kill switch off or no
 * registry wired; 403 unless the caller may {@code view} the page, before anything about it is revealed; then the
 * registry's join with {@code priorClientId}, {@code initialText} and {@code initialMeta} (its 403 {@code forbidden},
 * 409 {@code derived-page}, 429 {@code session-full}, 503 {@code store}, 400 {@code rejected}).
 */
final class CollabJoin {

    private static final Logger LOG = LogManager.getLogger( CollabJoin.class );
    /**
     * A join body may carry a whole template or restored page (A11): up to {@code maxPageBytes} of text, which JSON
     * escaping can grow to six bytes per byte (a backslash-u escape), plus room for the initial frontmatter.
     */
    private static final long JSON_ESCAPE_FACTOR = 6;
    private static final long META_ALLOWANCE_BYTES = 64L * 1024;
    private static final String DISABLED = "collab-disabled";
    private static final Gson GSON = new Gson();

    private CollabJoin() {
    }

    static Answer run( final Optional< CollabSessionRegistry > registry, final Access access,
                       final HttpServletRequest request, final Caller caller, final String page ) throws IOException {
        if( registry.isEmpty() || !registry.get().isEnabled() ) {
            return CollabWire.reason( 503, DISABLED );
        }
        if( !access.mayView( caller, page ) ) {
            return CollabWire.forbidden( page, "view" );
        }
        final long cap = registry.get().maxPageBytes() * JSON_ESCAPE_FACTOR + META_ALLOWANCE_BYTES;
        final Object parsed = CollabRequests.readJson( request, cap );
        if( parsed instanceof Answer refused ) {
            return refused;
        }
        final JsonObject body = ( JsonObject ) parsed;
        final JoinRequest jr;
        try {
            jr = new JoinRequest( caller.authorKey(), optionalString( body, "priorClientId" ), caller.wikiSession(),
                null, optionalString( body, "initialText" ), initialMeta( body ) );
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "collab: malformed join of {} by {}: {}", page, caller.authorKey(), e.getMessage() );
            return CollabWire.rejected( e.getMessage() );
        }
        final CollabSessionRegistry reg = registry.get();
        return switch( reg.join( page, jr ) ) {
            case CollabSessionRegistry.Joined j -> {
                reg.observe( j.session().id(), 200, null );
                yield CollabWire.joined( j.response() );
            }
            case CollabSessionRegistry.Refused r -> switch( r.status() ) {
                case 403 -> CollabWire.forbidden( page );
                case 400 -> CollabWire.rejected( "the initial text was refused" );
                default -> CollabWire.reason( r.status(), r.reason() );
            };
            case CollabSessionRegistry.Disabled d -> CollabWire.reason( 503, DISABLED );
        };
    }

    private static String optionalString( final JsonObject body, final String key ) {
        final JsonElement e = body.get( key );
        if( e == null || e.isJsonNull() ) {
            return null;
        }
        if( !CollabPush.isString( e ) ) {
            throw new IllegalArgumentException( key + " must be a string" );
        }
        return e.getAsString();
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Object > initialMeta( final JsonObject body ) {
        final JsonElement e = body.get( "initialMeta" );
        if( e == null || e.isJsonNull() ) {
            return null;
        }
        if( !e.isJsonObject() ) {
            throw new IllegalArgumentException( "initialMeta must be an object" );
        }
        return GSON.fromJson( e, Map.class );
    }
}

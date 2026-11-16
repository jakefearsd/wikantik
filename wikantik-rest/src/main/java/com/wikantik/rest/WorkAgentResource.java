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
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.WorkNotFoundException;
import com.wikantik.work.WorkService;
import com.wikantik.work.WorkValidationException;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /api/work/agents/*}: the agent registry. Anyone who belongs to a project may list agents (by name; the key id
 * is for wiki admins only); only a wiki admin writes. The service authorises before it looks an agent up, so a
 * non-admin gets the same 403 for an agent that exists, one that does not and an id that is not a number.
 *
 * <p>The plaintext token is in exactly two responses, the 201 of a registration and the rotate-key response, both
 * {@code Cache-Control: no-store}. It is never stored, logged or listed.
 */
public class WorkAgentResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;
    private static final org.apache.logging.log4j.Logger LOG =
        org.apache.logging.log4j.LogManager.getLogger( WorkAgentResource.class );

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "agents", c -> c.tree( agentsTree( c, c.svc.registry().listAgents( c.actor ) ) ) );
        route( r, "POST", "agents", this::register );
        route( r, "PATCH", "agents/{}", this::update );
        route( r, "POST", "agents/{}/rotate-key", this::rotate );
        route( r, "POST", "agents/{}/retire", c -> {
            c.svc.registry().retireAgent( c.actor, agentId( c ) );
            c.noContent();
        } );
    }

    /**
     * The id in the path. One that is not a number is passed on as an id no agent has, so the service answers it
     * exactly as it would an unknown agent: 403 for a non-admin, 404 for an admin.
     */
    private static long agentId( final Call c ) {
        try {
            return Long.parseLong( c.pathParam( 0 ) );
        } catch ( final NumberFormatException e ) {
            // Only that it was not a number: the segment is the caller's text and never goes into the log.
            LOG.warn( "Work API agent id in the path is not a number" );
            return -1L;
        }
    }

    private void register( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b != null ) {
            secret( c, c.svc.registry().registerAgent( c.actor, draft( b, optString( b, "owner" ) ) ), 201 );
        }
    }

    /** The body is optional: {@code {owner}} mints the new key under that person, a bare POST keeps the old owner. */
    private void rotate( final Call c ) throws Exception {
        String owner = null;
        if ( c.req.getContentLength() != 0 ) {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            owner = optString( b, "owner" );
        }
        secret( c, c.svc.registry().rotateAgentKey( c.actor, agentId( c ), owner ), 200 );
    }

    private void update( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final long id = agentId( c );
        c.svc.registry().updateAgent( c.actor, id, draft( b, null ) );
        final Agent updated = c.svc.registry().listAgents( c.actor ).stream().filter( a -> a.id() == id ).findFirst()
            .orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        c.tree( agentTree( c, updated ) );
    }

    private static AgentDraft draft( final JsonObject b, final String owner ) {
        return new AgentDraft( optString( b, "name" ), optString( b, "vendor" ), optString( b, "runtime" ), queues( b ),
            owner );
    }

    /** {@code queues} as a list of queue keys; anything but an array of text is a 400 on the field. */
    private static List< String > queues( final JsonObject b ) {
        final JsonElement raw = b.get( "queues" );
        if ( raw == null || raw.isJsonNull() ) {
            return List.of();
        }
        if ( !raw.isJsonArray() ) {
            throw new WorkValidationException( "queues", "queues must be a list of queue keys." );
        }
        final List< String > out = new ArrayList<>();
        for ( final JsonElement e : raw.getAsJsonArray() ) {
            if ( !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString() ) {
                throw new WorkValidationException( "queues", "queues must be a list of queue keys." );
            }
            out.add( e.getAsString() );
        }
        return out;
    }

    /** {@code {agent, token}}, never cached: the one time the token is shown. */
    private void secret( final Call c, final WorkService.AgentWithToken made, final int status ) throws Exception {
        final JsonObject out = new JsonObject();
        out.add( "agent", agentTree( c, made.agent() ) );
        out.addProperty( "token", made.token() );
        c.resp.setHeader( "Cache-Control", "no-store" );
        c.resp.setHeader( "Pragma", "no-cache" );
        WorkJson.write( c.resp, status, out );
    }

    private JsonArray agentsTree( final Call c, final List< Agent > agents ) {
        final JsonArray out = new JsonArray();
        agents.forEach( a -> out.add( agentTree( c, a ) ) );
        return out;
    }

    /** One agent; the key id only for a wiki admin, and not even a null in its place for anyone else. */
    private JsonObject agentTree( final Call c, final Agent agent ) {
        final JsonObject o = toTree( agent, c.actor ).getAsJsonObject();
        if ( !c.actor.instanceAdmin() ) {
            o.remove( "apiKeyId" );
        }
        return o;
    }
}

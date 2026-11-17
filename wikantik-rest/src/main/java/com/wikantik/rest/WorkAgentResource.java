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
import com.wikantik.work.WorkRegistryService;
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
        route( r, "GET", "agents", c -> c.tree( agentsTree( c, c.svc.registry().agentViews( c.actor ) ) ) );
        route( r, "POST", "agents", this::register );
        route( r, "PATCH", "agents/{}", this::update );
        route( r, "POST", "agents/{}/rotate-key", this::rotate );
        route( r, "POST", "agents/{}/retire", c -> {
            c.svc.registry().retireAgent( c.actor, agentId( c ) );
            c.noContent();
        } );
        // The caller takes the agent over (PUT) or gives it up (DELETE): nobody names another person as sponsor.
        route( r, "PUT", "agents/{}/sponsor", c -> {
            c.tree( agentTree( c, c.svc.registry().describe( c.svc.registry().takeOver( c.actor, agentId( c ) ) ) ) );
        } );
        route( r, "DELETE", "agents/{}/sponsor", c -> {
            c.svc.registry().releaseSponsorship( c.actor, agentId( c ) );
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
            secret( c, c.svc.registry().registerAgent( c.actor, draft( b ) ), 201 );
        }
    }

    /** The body is optional: {@code {owner}} mints the new key under that person, a bare POST keeps the old owner. */
    private void rotate( final Call c ) throws Exception {
        final JsonObject b = c.optionalBody();
        if ( b != null ) {
            secret( c, c.svc.registry().rotateCredential( c.actor, agentId( c ) ), 200 );
        }
    }

    /** A partial update: fields the body leaves out stay as they are. */
    private void update( final Call c ) throws Exception {
        final JsonObject b = c.body();
        if ( b == null ) {
            return;
        }
        final long id = agentId( c );
        c.svc.registry().updateAgent( c.actor, id, draft( b ) );
        // The update has committed: the reply must not fail on a directory lookup for some other agent's sponsor.
        final Agent updated = c.svc.registry().listAgents( c.actor ).stream().filter( a -> a.id() == id ).findFirst()
            .orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) );
        c.tree( agentTree( c, c.svc.registry().describe( updated ) ) );
    }

    private static AgentDraft draft( final JsonObject b ) {
        return new AgentDraft( optString( b, "name" ), optString( b, "vendor" ), optString( b, "runtime" ), queues( b ) );
    }

    /**
     * {@code queues} as a list of queue keys, or null when the body does not name them (a register serves none, a
     * PATCH leaves them as they are); anything but an array of text is a 400 on the field.
     */
    private static List< String > queues( final JsonObject b ) {
        final JsonElement raw = b.get( "queues" );
        if ( raw == null || raw.isJsonNull() ) {
            return null;
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
        // Built from the agent the change returned, never from a fresh listing: the token is shown once.
        out.add( "agent", agentTree( c, c.svc.registry().describe( made.agent() ) ) );
        out.addProperty( "token", made.token() );
        c.resp.setHeader( "Cache-Control", "no-store" );
        c.resp.setHeader( "Pragma", "no-cache" );
        WorkJson.write( c.resp, status, out );
    }

    private JsonArray agentsTree( final Call c, final List< WorkRegistryService.AgentView > agents ) {
        final JsonArray out = new JsonArray();
        agents.forEach( a -> out.add( agentTree( c, a ) ) );
        return out;
    }

    /**
     * An agent as the caller sees it: its sponsor by public name (the login only for a wiki admin, as people are
     * shown everywhere else), a plain status, and whether the caller is the sponsor. Never a credential.
     */
    private JsonObject agentTree( final Call c, final WorkRegistryService.AgentView view ) {
        final Agent agent = view.agent();
        final JsonObject o = toTree( agent, c.actor ).getAsJsonObject();
        o.remove( "apiKeyId" );
        o.remove( "sponsor" );
        if ( view.sponsorName() != null ) {
            o.addProperty( "sponsorName", view.sponsorName() );
        }
        if ( c.actor.instanceAdmin() && agent.sponsor() != null ) {
            o.addProperty( "sponsor", agent.sponsor() );
        }
        o.addProperty( "status", view.status().name().toLowerCase( java.util.Locale.ROOT ) );
        o.addProperty( "sponsoredByMe", c.actor.kind() == com.wikantik.work.Actor.Kind.HUMAN
            && c.actor.login().equals( agent.sponsor() ) );
        o.addProperty( "mayTakeOver", view.mayTakeOver() );
        return o;
    }
}

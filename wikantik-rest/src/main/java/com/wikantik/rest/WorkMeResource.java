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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.wikantik.work.Actor;
import com.wikantik.work.WaitingItem;
import com.wikantik.work.WorkEvent;


/**
 * {@code /api/work/me}: what is waiting on the caller, and the projects they can see. Each waiting item says why it is
 * there ({@code waiting.reason}: {@code assigned} or {@code mentioned}) and, for an assignment that came with a
 * hand-off, who handed it over and their note.
 */
public class WorkMeResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final SegmentRouteTable< Handler > r ) {
        route( r, "GET", "me", c -> {
            final JsonObject out = new JsonObject();
            out.add( "waitingOn", waitingOn( c ) );
            out.add( "projects", visibleProjects( c ) );
            out.add( "deploymentsAwaiting", WorkDeploymentJson.brief( c.svc.deployments().awaitingMyApproval( c.actor ) ) );
            c.json( out );
        } );
    }

    private JsonArray waitingOn( final Call c ) throws Exception {
        final WorkLookup lookup = lookupFor( c.actor );
        final JsonArray out = new JsonArray();
        for ( final WaitingItem w : c.svc.waitingOn( c.actor ) ) {
            final JsonObject item = toTree( w.item(), c.actor, lookup ).getAsJsonObject();
            item.add( "waiting", waiting( w, c.actor, lookup ) );
            out.add( item );
        }
        return out;
    }

    /**
     * {@code {reason, by, byName, byKind, note, at}}. {@code by} (a login, or an agent's name) is for a wiki admin
     * only; {@code byName} is the display name or the agent's current name. Null when no hand-off lies behind it.
     */
    private JsonObject waiting( final WaitingItem w, final Actor viewer, final WorkLookup lookup ) {
        final JsonObject out = new JsonObject();
        out.addProperty( "reason", w.reason() );
        final WorkEvent h = w.handoff();
        if ( viewer.admin() ) {
            out.addProperty( "by", h == null ? null : h.actor() );
        }
        final JsonObject event = h == null ? null : toTree( h, viewer, lookup ).getAsJsonObject();
        out.add( "byName", event == null ? JsonNull.INSTANCE : event.get( "actorName" ) );
        out.add( "byKind", event == null ? JsonNull.INSTANCE : event.get( "actorKind" ) );
        out.addProperty( "note", h == null ? null : h.note() );
        out.add( "at", event == null ? JsonNull.INSTANCE : event.get( "at" ) );
        return out;
    }
}

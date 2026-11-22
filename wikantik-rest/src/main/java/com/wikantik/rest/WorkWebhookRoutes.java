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
import com.wikantik.work.WebhookDraft;
import com.wikantik.work.WebhookPatch;
import com.wikantik.work.WorkValidationException;

import java.util.Set;

/**
 * A project's webhook routes, registered into {@link WorkProjectResource} (they live under
 * {@code /api/work/projects/{key}}). A webhook is shown as scheme and host only; its secret is in the response that
 * creates it and nowhere else.
 */
final class WorkWebhookRoutes {

    private static final Set< String > ADD_FIELDS = Set.of( "url", "format", "eventTypes" );
    private static final Set< String > PATCH_FIELDS = Set.of( "url", "eventTypes", "enabled" );

    private WorkWebhookRoutes() {
    }

    static void register( final SegmentRouteTable.Builder< WorkResourceBase.Handler > r ) {
        WorkResourceBase.route( r, "GET", "projects/{}/webhooks", c -> c.json(
            c.svc.features().webhooks().list( c.actor, c.pathParam( 0 ) ) ) );
        WorkResourceBase.route( r, "POST", "projects/{}/webhooks", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                WorkSprintResource.reject( b, ADD_FIELDS );
                c.created( c.svc.features().webhooks().add( c.actor, c.pathParam( 0 ), new WebhookDraft(
                    WorkRequests.optString( b, "url" ), WorkRequests.optString( b, "format" ),
                    WorkRequests.optStringList( b, "eventTypes" ) ) ) );
            }
        } );
        WorkResourceBase.route( r, "PATCH", "projects/{}/webhooks/{}", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                WorkSprintResource.reject( b, PATCH_FIELDS );
                c.json( c.svc.features().webhooks().update( c.actor, c.pathParam( 0 ), c.idParam( 1 ), new WebhookPatch(
                    WorkRequests.optString( b, "url" ), WorkRequests.optStringList( b, "eventTypes" ), enabled( b ) ) ) );
            }
        } );
        WorkResourceBase.route( r, "DELETE", "projects/{}/webhooks/{}", c -> {
            c.svc.features().webhooks().remove( c.actor, c.pathParam( 0 ), c.idParam( 1 ) );
            c.noContent();
        } );
    }

    /** {@code enabled}: null when absent, otherwise a JSON boolean or a 400 naming it. */
    private static Boolean enabled( final JsonObject b ) {
        final JsonElement v = b.get( "enabled" );
        if ( v == null || v.isJsonNull() ) {
            return null;
        }
        if ( !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean() ) {
            throw new WorkValidationException( "enabled", "enabled must be true or false." );
        }
        return v.getAsBoolean();
    }
}

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

import com.google.gson.JsonObject;
import com.wikantik.work.Queue;
import com.wikantik.work.WorkNotFoundException;

import java.util.List;

/**
 * {@code /api/work/queues/*}: the queues work can be handed to. Anyone who belongs to a project may list them (a
 * caller with no visible project gets an empty list); only a wiki admin writes, and the service authorises before it
 * looks a key up, so a non-admin gets the same 403 for a queue that exists and one that does not.
 */
public class WorkQueueResource extends WorkResourceBase {

    private static final long serialVersionUID = 1L;

    @Override
    protected void routes( final List< Object[] > r ) {
        route( r, "GET", "queues", c -> c.json( c.svc.registry().listQueues( c.actor ) ) );
        route( r, "POST", "queues", c -> {
            final JsonObject b = c.body();
            if ( b != null ) {
                c.created( c.svc.registry().createQueue( c.actor, requiredString( b, "key" ), optString( b, "name" ),
                    optString( b, "description" ) ) );
            }
        } );
        route( r, "PATCH", "queues/{}", c -> {
            final JsonObject b = c.body();
            if ( b == null ) {
                return;
            }
            final String key = c.pathParam( 0 );
            c.svc.registry().updateQueue( c.actor, key, optString( b, "name" ), optString( b, "description" ) );
            c.json( c.svc.registry().listQueues( c.actor ).stream().filter( q -> q.key().equals( key ) ).findFirst()
                .orElseThrow( () -> new WorkNotFoundException( NOT_FOUND ) ) );
        } );
        route( r, "POST", "queues/{}/retire", c -> {
            c.svc.registry().retireQueue( c.actor, c.pathParam( 0 ) );
            c.noContent();
        } );
    }
}

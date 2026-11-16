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
package com.wikantik.its.rest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wikantik.its.rest.WorkIt.Agent;
import com.wikantik.its.rest.WorkIt.Caller;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.Map;

import static com.wikantik.its.rest.WorkIt.json;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A claim nobody renews lapses by itself. The rest module's cascade sets {@code wikantik.work.claim.expiryMinutes = 1}
 * (the shortest the product allows) and the server sweeps once a minute, so the lapse shows within about two minutes;
 * the wait is bounded and the assertions run on the success path of the poll.
 */
public class WorkClaimExpiryIT {

    private static JsonObject itemOf( final Caller who, final String key ) throws Exception {
        final HttpResponse< String > r = who.get( "/api/work/items/" + key );
        assertEquals( 200, r.statusCode(), r.body() );
        return json( r ).getAsJsonObject().getAsJsonObject( "item" );
    }

    @Test
    void anUnrenewedClaimLapsesAndIsRecordedAgainstTheAgent() throws Exception {
        final Caller admin = WorkIt.admin();
        assertEquals( 201, admin.send( "POST", "/api/work/projects", Map.of( "key", "EXP", "name", "Expiry" ) ).statusCode() );
        assertEquals( 201, admin.send( "POST", "/api/work/queues", Map.of( "key", "expq", "name", "Expiry queue" ) ).statusCode() );
        final Agent agent = WorkIt.registerAgent( admin, "exp@it", "expq" );
        assertEquals( 204, admin.send( "PUT", "/api/work/projects/EXP/members", Map.of( "principal", agent.name(),
            "principalKind", "agent", "role", "member" ) ).statusCode() );
        final JsonObject made = json( admin.send( "POST", "/api/work/items", Map.of( "project", "EXP", "level", "task",
            "title", "Lapses" ) ) ).getAsJsonObject().getAsJsonObject( "item" );
        final String key = made.get( "key" ).getAsString();
        assertEquals( 200, admin.send( "POST", "/api/work/items/" + key + "/handoff", Map.of( "expectedVersion",
            made.get( "version" ).getAsInt(), "status", "ready", "queue", "expq", "note", "go" ) ).statusCode() );

        assertEquals( 200, WorkIt.bearer( agent.token(), "POST", "/api/work/items/" + key + "/claim", null ).statusCode() );
        // positive control: right after the claim the item is held
        final JsonObject held = itemOf( admin, key );
        assertEquals( "in_progress", held.get( "status" ).getAsString() );
        assertEquals( agent.id(), held.get( "claimedByAgent" ).getAsLong() );

        final long deadline = System.currentTimeMillis() + 180_000L;
        while ( System.currentTimeMillis() < deadline ) {
            final JsonObject now = itemOf( admin, key );
            if ( now.get( "claimedByAgent" ).isJsonNull() ) {
                assertEquals( "ready", now.get( "status" ).getAsString() );
                assertEquals( "expq", now.get( "queue" ).getAsString() );
                JsonObject expired = null;
                for ( final JsonElement e : json( admin.get( "/api/work/items/" + key + "/events" ) ).getAsJsonArray() ) {
                    if ( "claim_expired".equals( e.getAsJsonObject().get( "type" ).getAsString() ) ) {
                        expired = e.getAsJsonObject();
                    }
                }
                assertTrue( expired != null, "a claim_expired event" );
                assertEquals( "agent", expired.get( "actorKind" ).getAsString() );
                assertEquals( agent.name(), expired.get( "actorName" ).getAsString() );
                assertEquals( "ready", expired.get( "toValue" ).getAsString() );
                return;
            }
            Thread.sleep( 3_000 );
        }
        fail( "the claim never lapsed within 180 s" );
    }
}

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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkSprintJsonTest {

    @Test
    void aPlannedSprintHasNoCommittedOrCompleted() {
        final JsonObject o = WorkSprintJson.view( WorkSprintFixtures.planned( 9 ) );
        assertEquals( 9, o.get( "id" ).getAsLong() );
        assertEquals( "PLAT", o.get( "project" ).getAsString() );
        assertEquals( "planned", o.get( "state" ).getAsString() );
        assertEquals( "2026-11-23", o.get( "startsOn" ).getAsString() );
        assertTrue( o.get( "committed" ).isJsonNull() );
        assertTrue( o.get( "completed" ).isJsonNull() );
        assertTrue( o.get( "startedAt" ).isJsonNull() );
        final JsonObject current = o.getAsJsonObject( "current" );
        assertEquals( 4, current.get( "items" ).getAsInt() );
        assertEquals( 7.5, current.get( "points" ).getAsDouble() );
        assertEquals( 1, current.get( "doneItems" ).getAsInt() );
        assertEquals( 2.0, current.get( "donePoints" ).getAsDouble() );
        assertFalse( o.has( "projectId" ) );
    }

    @Test
    void aClosedSprintCarriesBoth() {
        final JsonObject o = WorkSprintJson.view( WorkSprintFixtures.closed( 9 ) );
        assertEquals( "closed", o.get( "state" ).getAsString() );
        assertEquals( 5, o.getAsJsonObject( "committed" ).get( "items" ).getAsInt() );
        assertEquals( 10.0, o.getAsJsonObject( "committed" ).get( "points" ).getAsDouble() );
        assertEquals( 4, o.getAsJsonObject( "completed" ).get( "items" ).getAsInt() );
        assertEquals( 8.5, o.getAsJsonObject( "completed" ).get( "points" ).getAsDouble() );
        assertEquals( "2026-12-04T17:00:00Z", o.get( "closedAt" ).getAsString() );
        assertEquals( 6, o.get( "version" ).getAsInt() );
    }
}

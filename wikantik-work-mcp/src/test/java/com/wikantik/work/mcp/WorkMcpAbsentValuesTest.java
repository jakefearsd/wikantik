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
package com.wikantik.work.mcp;

import com.google.gson.JsonObject;
import com.wikantik.work.CommentView;
import com.wikantik.work.WorkStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** What a tool answers when a value the store usually fills in is absent: a null in the reply, never a failure. */
class WorkMcpAbsentValuesTest {

    @Test
    void anAgentWhoseRecordWasJustRetiredStillGetsAnAnswerUnderItsOwnName() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.self( w.agent ) ).thenReturn( Optional.empty() );
        when( w.svc.listProjectsWithRoles( w.agent ) ).thenReturn( List.of() );
        when( w.claims.myClaims( w.agent ) ).thenReturn( List.of( MockWork.item( "PLAT-1", WorkStatus.IN_PROGRESS, 2, 7L, null ) ) );
        when( w.claims.claimExpiry() ).thenReturn( Duration.ofMinutes( 30 ) );
        final JsonObject out = WorkMcpFixture.json( new GetWorkContextTool( w.svc, w.directory ).call( w.agent, Map.of() ) );
        final JsonObject agent = out.getAsJsonObject( "agent" );
        assertEquals( "builder", agent.get( "name" ).getAsString() );
        assertTrue( agent.get( "vendor" ).isJsonNull() );
        assertTrue( agent.get( "runtime" ).isJsonNull() );
        assertEquals( 0, agent.getAsJsonArray( "queues" ).size() );
        assertTrue( out.getAsJsonArray( "claims" ).get( 0 ).getAsJsonObject().get( "expiresAt" ).isJsonNull() );
    }

    @Test
    void aCommentWithNoTimestampIsReportedWithoutOne() throws Exception {
        final MockWork w = new MockWork();
        when( w.svc.addComment( w.agent, "PLAT-1", "Looks good" ) ).thenReturn(
            new CommentView( 42, 1, "builder", "Looks good", null, null, null, "<p>x</p>", List.of(), 7L ) );
        final JsonObject out = WorkMcpFixture.json( new CommentOnItemTool( w.svc ).call( w.agent,
            Map.of( "key", "PLAT-1", "body", "Looks good" ) ) );
        assertEquals( 42, out.get( "id" ).getAsInt() );
        assertTrue( out.get( "at" ).isJsonNull() );
    }

    @Test
    void anAgentIsShownUnderItsRegisteredNameWhichNeedNotBeItsLogin() throws Exception {
        final MockWork w = new MockWork();
        when( w.registry.self( w.agent ) ).thenReturn( Optional.of( new com.wikantik.work.Agent( 7, "Builder Bot", "Acme",
            "claude-code", "x", List.of( "implementer" ), java.time.Instant.EPOCH, java.time.Instant.EPOCH ) ) );
        when( w.svc.listProjectsWithRoles( w.agent ) ).thenReturn( List.of() );
        when( w.claims.myClaims( w.agent ) ).thenReturn( List.of() );
        when( w.claims.claimExpiry() ).thenReturn( Duration.ofMinutes( 30 ) );
        final JsonObject out = WorkMcpFixture.json( new GetWorkContextTool( w.svc, w.directory ).call( w.agent, Map.of() ) );
        final JsonObject agent = out.getAsJsonObject( "agent" );
        assertEquals( "Builder Bot", agent.get( "name" ).getAsString() );
        assertEquals( "Acme", agent.get( "vendor" ).getAsString() );
        assertEquals( "claude-code", agent.get( "runtime" ).getAsString() );
        assertEquals( "implementer", agent.getAsJsonArray( "queues" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( 30, out.get( "claimExpiryMinutes" ).getAsInt() );
        assertTrue( out.get( "person" ).isJsonNull() );
    }
}

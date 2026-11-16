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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Item;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class GetWorkContextToolTest {

    private WorkMcpFixture f;
    private GetWorkContextTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tool = new GetWorkContextTool( f.svc, f.directory() );
    }

    @Test
    void agentSeesOnlyAdmittedProjects() throws SQLException {
        final JsonObject out = WorkMcpFixture.json( tool.call( f.builderActor, Map.of() ) );

        final JsonArray projects = out.getAsJsonArray( "projects" );
        assertEquals( 1, projects.size(), "SECRET is invisible even though the key's owner leads it" );
        assertEquals( "PLAT", projects.get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "member", projects.get( 0 ).getAsJsonObject().get( "role" ).getAsString() );
        final JsonObject agent = out.getAsJsonObject( "agent" );
        assertEquals( "builder", agent.get( "name" ).getAsString() );
        assertEquals( "Acme", agent.get( "vendor" ).getAsString() );
        assertEquals( "claude-code", agent.get( "runtime" ).getAsString() );
        assertEquals( "Implementer", agent.getAsJsonArray( "queues" ).get( 0 ).getAsJsonObject().get( "name" ).getAsString() );
        assertTrue( out.get( "person" ).isJsonNull() );
        assertEquals( 30, out.get( "claimExpiryMinutes" ).getAsInt() );
        assertFalse( WorkMcpFixture.text( tool.call( f.builderActor, Map.of() ) ).contains( "SECRET" ) );
    }

    @Test
    void anUnadmittedAgentStillGetsItsOwnRecord() throws SQLException {
        final com.wikantik.work.Agent loner = f.svc.registry().registerAgent( f.admin,
            new com.wikantik.work.AgentDraft( "loner", "Acme", "cli", java.util.List.of( "reviewer" ), "lena" ) ).agent();

        final JsonObject out = WorkMcpFixture.json( tool.call( com.wikantik.work.Actor.agent( loner.id(), "loner" ),
            Map.of() ) );

        assertEquals( 0, out.getAsJsonArray( "projects" ).size() );
        assertEquals( "loner", out.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( "Acme", out.getAsJsonObject( "agent" ).get( "vendor" ).getAsString() );
        // queue names are shown to those who belong to a project; a loner sees its own queues by key
        assertEquals( "reviewer", out.getAsJsonObject( "agent" ).getAsJsonArray( "queues" ).get( 0 )
            .getAsJsonObject().get( "key" ).getAsString() );
    }

    @Test
    void agentSeesItsClaims() throws SQLException {
        final Item item = f.ready( "PLAT", "Mine" );
        f.svc.claims().claim( f.builderActor, item.key() );

        final JsonArray claims = WorkMcpFixture.json( tool.call( f.builderActor, Map.of() ) ).getAsJsonArray( "claims" );

        assertEquals( 1, claims.size() );
        assertEquals( item.key(), claims.get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
        assertEquals( "Mine", claims.get( 0 ).getAsJsonObject().get( "title" ).getAsString() );
        assertEquals( "in_progress", claims.get( 0 ).getAsJsonObject().get( "status" ).getAsString() );
        assertFalse( claims.get( 0 ).getAsJsonObject().get( "expiresAt" ).isJsonNull() );
    }

    @Test
    void unboundKeyShowsPersonAndNoClaims() throws SQLException {
        final JsonObject out = WorkMcpFixture.json( tool.call( f.lena, Map.of() ) );

        assertEquals( "Lena Lead", out.get( "person" ).getAsString() );
        assertTrue( out.get( "agent" ).isJsonNull() );
        assertEquals( 0, out.getAsJsonArray( "claims" ).size() );
        assertEquals( 2, out.getAsJsonArray( "projects" ).size() );
        assertFalse( WorkMcpFixture.text( tool.call( f.lena, Map.of() ) ).contains( "\"lena\"" ), "never the login" );
    }

    @Test
    void noActorIsTheFixedError() {
        assertEquals( "This endpoint needs a work API key.", WorkMcpFixture.text( tool.call( null, Map.of() ) ) );
    }
}

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
            new com.wikantik.work.AgentDraft( "loner", "Acme", "cli", java.util.List.of( "reviewer" ) ) ).agent();

        final JsonObject out = WorkMcpFixture.json( tool.call( com.wikantik.work.Actor.agent( loner.id(), "loner", com.wikantik.work.Actor.human( "admin", java.util.Set.of(), true ) ),
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

    @Test
    void eachProjectNamesItsActiveSprintAndGoal() throws SQLException {
        final var sprint = f.activeSprint( "PLAT" );
        final JsonObject out = WorkMcpFixture.json( tool.call( f.lena, Map.of() ) );
        for ( final var e : out.getAsJsonArray( "projects" ) ) {
            final JsonObject p = e.getAsJsonObject();
            if ( "PLAT".equals( p.get( "key" ).getAsString() ) ) {
                assertEquals( sprint.sprint().name(), p.getAsJsonObject( "activeSprint" ).get( "name" ).getAsString() );
                assertEquals( "Ship the thing", p.getAsJsonObject( "activeSprint" ).get( "goal" ).getAsString() );
                assertEquals( sprint.sprint().startsOn().toString(),
                    p.getAsJsonObject( "activeSprint" ).get( "startsOn" ).getAsString() );
                assertEquals( sprint.sprint().endsOn().toString(),
                    p.getAsJsonObject( "activeSprint" ).get( "endsOn" ).getAsString() );
            } else {
                assertTrue( p.get( "activeSprint" ).isJsonNull(), "no sprint in " + p );
            }
        }
    }

    private static final String NO_PROJECT = "You are not a member of any project you can see. "
        + "Ask your sponsor to add you to a project (they must be a member of it too).";
    private static final String NO_QUEUE = "You serve no queue, so list_claimable is always empty. "
        + "Ask your sponsor to add a queue to you.";

    private java.util.List< String > setup( final com.wikantik.work.Actor actor ) throws SQLException {
        final JsonObject out = WorkMcpFixture.json( tool.call( actor, Map.of() ) );
        final java.util.List< String > hints = new java.util.ArrayList<>();
        for ( final var e : out.getAsJsonArray( "setup" ) ) {
            hints.add( e.getAsString() );
        }
        return hints;
    }

    private com.wikantik.work.Actor agentActor( final String name, final java.util.List< String > queues )
            throws SQLException {
        final com.wikantik.work.Agent a = f.svc.registry().registerAgent( f.admin,
            new com.wikantik.work.AgentDraft( name, "Acme", "cli", queues ) ).agent();
        return com.wikantik.work.Actor.agent( a.id(), a.name(),
            com.wikantik.work.Actor.human( "admin", java.util.Set.of(), true ) );
    }

    @Test
    void personGetsAnEmptySetupList() throws SQLException {
        assertEquals( java.util.List.of(), setup( f.lena ) );
    }

    @Test
    void wellSetUpAgentGetsAnEmptySetupList() throws SQLException {
        assertEquals( java.util.List.of(), setup( f.builderActor ) );
    }

    @Test
    void setupNeverCarriesASponsorHint() throws SQLException {
        // A sponsorless agent never reaches a tool (the transport refuses it), so setup has no sponsor hint.
        final com.wikantik.work.Actor loner = agentActor( "loner", java.util.List.of() );
        f.svc.registry().releaseSponsorship( f.admin, loner.agentId() );
        assertEquals( java.util.List.of( NO_PROJECT, NO_QUEUE ), setup( loner ) );
    }

    @Test
    void anAgentServingOnlyARetiredQueueIsToldItServesNone() throws SQLException {
        final com.wikantik.work.Actor a = agentActor( "second", java.util.List.of( "reviewer" ) );
        f.svc.putMember( f.admin, "PLAT", "second", "agent", com.wikantik.work.WorkRole.MEMBER );
        assertEquals( java.util.List.of(), setup( a ), "control: an active queue is served" );
        f.svc.registry().retireQueue( f.admin, "reviewer" );
        assertEquals( java.util.List.of( NO_QUEUE ), setup( a ) );
    }

    @Test
    void agentInNoProjectIsToldToAskItsSponsor() throws SQLException {
        final java.util.List< String > hints = setup( agentActor( "loner", java.util.List.of( "reviewer" ) ) );
        assertEquals( java.util.List.of( NO_PROJECT ), hints );
    }

    @Test
    void agentWithNoQueueIsToldListClaimableIsEmpty() throws SQLException {
        final com.wikantik.work.Actor a = agentActor( "second", java.util.List.of() );
        f.svc.putMember( f.admin, "PLAT", "second", "agent", com.wikantik.work.WorkRole.MEMBER );
        assertEquals( java.util.List.of( NO_QUEUE ), setup( a ) );
    }

    @Test
    void descriptionNamesTheSetupField() {
        assertTrue( tool.definition().description().contains( "setup:[" ), tool.definition().description() );
    }
}

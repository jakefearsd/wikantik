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
import com.wikantik.work.Autonomy;
import com.wikantik.work.DeployGate;
import com.wikantik.work.Item;
import com.wikantik.work.ItemDraft;
import com.wikantik.work.ItemPatch;
import com.wikantik.work.Product;
import com.wikantik.work.WorkClaimsService;
import com.wikantik.work.WorkKind;
import com.wikantik.work.WorkLevel;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkStatus;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresPostgres
class GetItemToolTest {

    private WorkMcpFixture f;
    private GetItemTool tool;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tool = new GetItemTool( f.svc, f.directory() );
    }

    private McpSchema.CallToolResult asAgent( final String key ) {
        return tool.call( f.builderActor, Map.of( "key", key ) );
    }

    @Test
    void includesTypeAndLabels() throws SQLException {
        final Item item = f.ready( "PLAT", "Typed" );
        f.svc.updateItem( f.lena, item.key(), new com.wikantik.work.ItemPatch( item.version(),
            Map.of( "type", "bug", "labels", java.util.List.of( "ui", "api" ) ), null ) );
        final JsonObject o = WorkMcpFixture.json( asAgent( item.key() ) );
        assertEquals( "bug", o.get( "type" ).getAsString() );
        assertEquals( java.util.List.of( "api", "ui" ),
            o.getAsJsonArray( "labels" ).asList().stream().map( e -> e.getAsString() ).toList() );
    }

    @Test
    void hiddenAndUnknownGiveIdenticalError() throws SQLException {
        final Item hidden = f.ready( "SECRET", "Not for the builder" );   // lena (the key owner) can see it; the agent cannot

        final McpSchema.CallToolResult forHidden = asAgent( hidden.key() );
        final McpSchema.CallToolResult forUnknown = asAgent( "PLAT-9999" );
        final McpSchema.CallToolResult forUnknownProject = asAgent( "NOPE-1" );

        assertEquals( Boolean.TRUE, forHidden.isError() );
        assertEquals( "Not found.", WorkMcpFixture.text( forHidden ) );
        assertEquals( forHidden, forUnknown );
        assertEquals( forHidden, forUnknownProject );
        assertEquals( forHidden, tool.call( f.builderActor, Map.of() ), "a missing key is the same answer, not a crash" );
    }

    @Test
    void aKeyOfTheWrongTypeIsRejectedByName() {
        assertEquals( "key: must be a string.", WorkMcpFixture.text( tool.call( f.builderActor, Map.of( "key", 12 ) ) ) );
    }

    @Test
    void anUnexpectedFailureShowsTheClientNothingOfIt() {
        final WorkMcpTool exploding = new WorkMcpTool() {
            @Override public String name() { return "explode"; }
            @Override public McpSchema.Tool definition() { return null; }
            @Override protected McpSchema.CallToolResult run( final com.wikantik.work.Actor a, final Map< String, Object > args ) {
                throw new IllegalStateException( "secret detail" );
            }
        };
        final McpSchema.CallToolResult r = exploding.call( f.builderActor, Map.of() );
        assertEquals( Boolean.TRUE, r.isError() );
        assertEquals( "Work is unavailable right now.", WorkMcpFixture.text( r ) );
        assertFalse( WorkMcpFixture.text( r ).contains( "secret detail" ) );
    }

    @Test
    void bodyIsMarkdownSource() throws SQLException {
        final Item draft = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "Doc", "# Heading\n\nSome **bold** text", null, null, null, "implementer", null, null ) ).item();
        f.svc.addComment( f.lena, draft.key(), "Please *check* this" );

        final JsonObject out = WorkMcpFixture.json( asAgent( draft.key() ) );

        assertEquals( "# Heading\n\nSome **bold** text", out.get( "body" ).getAsString() );
        assertFalse( WorkMcpFixture.text( asAgent( draft.key() ) ).contains( "<p>" ), "no rendered HTML anywhere" );
        assertEquals( "Please *check* this", out.getAsJsonArray( "comments" ).get( 0 ).getAsJsonObject().get( "body" ).getAsString() );
    }

    @Test
    void includesWikiCluster() throws SQLException {
        f.svc.createProduct( f.admin, new Product( 0, "web", "Web", null, null, "AgentHub", Autonomy.SUPERVISED,
            DeployGate.HUMAN, null ) );
        final Item item = f.svc.createItem( f.lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, "web", WorkKind.SOFTWARE,
            "In a product", "", null, null, null, "implementer", null, null ) ).item();

        final JsonObject out = WorkMcpFixture.json( asAgent( item.key() ) );

        assertEquals( "AgentHub", out.get( "wikiCluster" ).getAsString() );
        assertEquals( "web", out.get( "productKey" ).getAsString() );
    }

    @Test
    void noLoginInOutput() throws SQLException {
        f.svc.putMember( f.admin, "PLAT", "jdoe", "user", WorkRole.MEMBER );
        final Item item = f.ready( "PLAT", "Mentioned" );
        final com.wikantik.work.Actor jdoe = com.wikantik.work.Actor.human( "jdoe", java.util.Set.of(), false );
        f.svc.addComment( jdoe, item.key(), "I looked at this" );
        f.svc.updateItem( f.lena, item.key(), new ItemPatch( f.svc.item( f.lena, item.key() ).item().version(),
            Map.of( "assignee", "Jane Doe" ), null ) );

        final String text = WorkMcpFixture.text( asAgent( item.key() ) );

        assertTrue( text.contains( "Jane Doe" ), text );
        assertFalse( text.contains( "jdoe" ), "a login never reaches an agent: " + text );
        assertFalse( text.contains( "\"lena\"" ), text );
    }

    @Test
    void claimAndEventsAreNamedAsSentences() throws SQLException {
        final Item item = f.ready( "PLAT", "Claim me" );
        f.svc.claims().claim( f.builderActor, item.key() );
        final Item held = f.svc.item( f.builderActor, item.key() ).item();
        f.svc.claims().handoff( f.builderActor, item.key(), new WorkClaimsService.Handoff( held.version(),
            WorkStatus.REVIEW, null, "reviewer", "Ready for a second pair of eyes" ) );

        final JsonObject out = WorkMcpFixture.json( asAgent( item.key() ) );
        final JsonArray events = out.getAsJsonArray( "events" );

        JsonObject handoff = null;
        JsonObject claimed = null;
        for ( int i = 0; i < events.size(); i++ ) {
            final JsonObject e = events.get( i ).getAsJsonObject();
            if ( "handoff".equals( e.get( "type" ).getAsString() ) ) {
                handoff = e;
            }
            if ( "claimed".equals( e.get( "type" ).getAsString() ) ) {
                claimed = e;
            }
        }
        assertEquals( "builder", claimed.get( "actor" ).getAsString() );
        assertEquals( "agent", claimed.get( "actorKind" ).getAsString() );
        assertEquals( "builder", handoff.get( "actor" ).getAsString() );
        assertEquals( "in_progress|", handoff.get( "from" ).getAsString().replaceAll( "^(\\w+\\|).*", "$1" ) );
        assertEquals( "review|Reviewer", handoff.get( "to" ).getAsString(), "a queue is shown by its name" );
        assertEquals( "Ready for a second pair of eyes", handoff.get( "note" ).getAsString() );
    }

    @Test
    void aLiveClaimShowsItsHolderAndExpiry() throws SQLException {
        final Item item = f.ready( "PLAT", "Held" );
        f.svc.claims().claim( f.builderActor, item.key() );

        final JsonObject claim = WorkMcpFixture.json( asAgent( item.key() ) ).getAsJsonObject( "claim" );

        assertEquals( "builder", claim.get( "agent" ).getAsString() );
        assertTrue( java.time.Instant.parse( claim.get( "expiresAt" ).getAsString() )
            .isAfter( java.time.Instant.now().plus( Duration.ofMinutes( 20 ) ) ) );
    }

    @Test
    void anItemWithoutAClaimHasNull() throws SQLException {
        final Item item = f.ready( "PLAT", "Free" );
        assertTrue( WorkMcpFixture.json( asAgent( item.key() ) ).get( "claim" ).isJsonNull() );
    }

    @Test
    void onlyTheLast20EventsAreReturned() throws SQLException {
        final Item item = f.ready( "PLAT", "Busy" );
        for ( int i = 0; i < 25; i++ ) {
            f.svc.addComment( f.lena, item.key(), "note " + i );
        }
        final JsonArray events = WorkMcpFixture.json( asAgent( item.key() ) ).getAsJsonArray( "events" );
        assertEquals( 20, events.size() );
        assertEquals( "commented", events.get( 19 ).getAsJsonObject().get( "type" ).getAsString() );
    }
}

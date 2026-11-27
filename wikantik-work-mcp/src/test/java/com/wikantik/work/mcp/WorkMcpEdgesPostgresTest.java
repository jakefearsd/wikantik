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
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Actor;
import com.wikantik.work.Item;
import com.wikantik.work.ItemPatch;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The work tools at their edges: a person calling with a personal key, an item with every optional part filled in
 * or left out, and arguments that are missing, blank or of the wrong type.
 */
@RequiresPostgres
class WorkMcpEdgesPostgresTest {

    private WorkMcpFixture f;
    private List< WorkMcpTool > tools;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tools = WorkMcpInitializer.assembleTools( f.svc, f.directory() );
    }

    private McpSchema.CallToolResult call( final Actor who, final String tool, final Object... kv ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            args.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        return tools.stream().filter( x -> x.name().equals( tool ) ).findFirst().orElseThrow().call( who, args );
    }

    private JsonObject ok( final McpSchema.CallToolResult r ) {
        assertFalse( Boolean.TRUE.equals( r.isError() ), WorkMcpFixture.text( r ) );
        return WorkMcpFixture.json( r );
    }

    private static String refused( final McpSchema.CallToolResult r ) {
        assertTrue( Boolean.TRUE.equals( r.isError() ), "expected a refusal, got " + WorkMcpFixture.text( r ) );
        return WorkMcpFixture.text( r );
    }

    // ---- a person with a personal work key

    @Test
    void aPersonsContextNamesNoAgentAndTheyHoldNoClaims() {
        final JsonObject me = ok( call( f.lena, "get_work_context" ) );
        assertEquals( "Lena Lead", me.get( "person" ).getAsString(), "the display name, not the login" );
        assertTrue( me.get( "agent" ).isJsonNull(), "a person has no agent record" );
        assertFalse( me.toString().contains( "\"lena\"" ), me.toString() );
        assertEquals( 0, me.getAsJsonArray( "claims" ).size() );
        final JsonObject builder = ok( call( f.builderActor, "get_work_context" ) );
        assertTrue( builder.get( "person" ).isJsonNull() );
        assertEquals( "builder", builder.getAsJsonObject( "agent" ).get( "name" ).getAsString() );
        assertEquals( "Acme", builder.getAsJsonObject( "agent" ).get( "vendor" ).getAsString(), "an agent sees its own record" );
        assertEquals( "claude-code", builder.getAsJsonObject( "agent" ).get( "runtime" ).getAsString() );
        final var queue = builder.getAsJsonObject( "agent" ).getAsJsonArray( "queues" ).get( 0 ).getAsJsonObject();
        assertEquals( "implementer", queue.get( "key" ).getAsString() );
        assertEquals( "Implementer", queue.get( "name" ).getAsString() );
    }

    @Test
    void aHeldClaimShowsWhenItExpires() throws SQLException {
        final Item item = f.ready( "PLAT", "Held" );
        final JsonObject claimed = ok( call( f.builderActor, "claim_item", "key", item.key() ) );
        final java.time.Instant expires = java.time.Instant.parse(
            claimed.getAsJsonObject( "claim" ).get( "expiresAt" ).getAsString() );
        assertTrue( expires.isAfter( java.time.Instant.now() ) );
        final JsonObject me = ok( call( f.builderActor, "get_work_context" ) );
        assertEquals( 1, me.getAsJsonArray( "claims" ).size() );
        assertEquals( expires, java.time.Instant.parse(
            me.getAsJsonArray( "claims" ).get( 0 ).getAsJsonObject().get( "expiresAt" ).getAsString() ) );
        assertEquals( item.key(), me.getAsJsonArray( "claims" ).get( 0 ).getAsJsonObject().get( "key" ).getAsString() );
    }

    // ---- an item with everything, and with nothing

    @Test
    void anItemIsShownWithItsDateLinksCommentsAndWhoMovedIt() throws SQLException {
        final Item item = f.ready( "PLAT", "Full" );
        f.svc.updateItem( f.lena, item.key(), new ItemPatch( item.version(), Map.of( "dueOn", "2026-12-01" ), null ) );
        f.svc.addLink( f.lena, item.key(), "url", "https://example.com/spec" );
        final long gone = f.svc.addComment( f.lena, item.key(), "A thought I took back." ).id();
        f.svc.removeComment( f.lena, gone );
        ok( call( f.builderActor, "comment_on_item", "key", item.key(), "body", "Starting on this." ) );
        ok( call( f.builderActor, "claim_item", "key", item.key() ) );
        ok( call( f.builderActor, "release_item", "key", item.key(), "note", "blocked on the vendor" ) );
        final JsonObject shown = ok( call( f.lena, "get_item", "key", " " + item.key() + " " ) );
        assertEquals( "2026-12-01", shown.get( "dueOn" ).getAsString() );
        assertEquals( "https://example.com/spec", shown.getAsJsonArray( "links" ).get( 0 ).getAsJsonObject()
            .get( "target" ).getAsString() );
        assertEquals( 1, shown.getAsJsonArray( "comments" ).size(), "a removed comment is not shown" );
        assertTrue( shown.getAsJsonArray( "events" ).toString().contains( "builder" ), "the agent is named, not numbered" );
        final JsonObject bare = ok( call( f.lena, "get_item", "key", f.ready( "PLAT", "Bare" ).key() ) );
        assertTrue( bare.get( "dueOn" ).isJsonNull() );
        assertEquals( 0, bare.getAsJsonArray( "links" ).size() );
    }

    @Test
    void aMissingOrBlankKeyIsNotFound() {
        for ( final Object key : Arrays.asList( null, "", "  " ) ) {
            final McpSchema.CallToolResult r = key == null ? call( f.lena, "get_item" ) : call( f.lena, "get_item", "key", key );
            assertEquals( "Not found.", refused( r ) );
        }
        assertEquals( "Not found.", refused( call( f.lena, "claim_item", "key", " " ) ) );
    }

    // ---- arguments

    @Test
    void aNewItemsKindDefaultsAndIsChecked() {
        final JsonObject made = ok( call( f.lena, "create_item", "project", "PLAT", "level", "task", "title",
            "No kind given", "kind", " " ) );
        assertEquals( "ready", made.get( "status" ).getAsString(), "a person's new task starts ready" );
        ok( call( f.lena, "create_item", "project", "PLAT", "level", "task", "title", "Docs", "kind", "docs" ) );
        assertTrue( refused( call( f.lena, "create_item", "project", "PLAT", "level", "task", "title", "Bad kind",
            "kind", "poetry" ) ).startsWith( "kind:" ) );
    }

    @Test
    void aHandOffNamesExactlyOneTarget() throws SQLException {
        final Item item = f.ready( "PLAT", "Hand me on" );
        final String both = refused( call( f.lena, "handoff_item", "key", item.key(), "expectedVersion", item.version(),
            "status", "review", "assignee", "Jane Doe", "queue", "reviewer", "note", "n" ) );
        final String neither = refused( call( f.lena, "handoff_item", "key", item.key(), "expectedVersion",
            item.version(), "status", "review", "assignee", " ", "queue", "", "note", "n" ) );
        assertEquals( both, neither, "blank is the same as absent: one target, not two and not none" );
        ok( call( f.lena, "handoff_item", "key", item.key(), "expectedVersion", item.version(), "status", "review",
            "assignee", "", "queue", "reviewer", "note", "Ready for review." ) );
    }

    @Test
    void aDeploymentRequestNeedsAListOfKeysAndAWholeNumberId() {
        assertEquals( "items: is required.", refused( call( f.lena, "request_deployment", "product", "wikantik",
            "version", "2.6.0" ) ) );
        assertEquals( "items: must be a list of item keys.", refused( call( f.lena, "request_deployment", "product",
            "wikantik", "version", "2.6.0", "items", List.of( 7 ) ) ) );
        assertEquals( "deployment: must be a whole number.", refused( call( f.lena, "get_deployment", "deployment", 1.5 ) ) );
        assertEquals( "deployment: must be a whole number.", refused( call( f.lena, "get_deployment", "deployment", "one" ) ) );
        assertEquals( "Not found.", refused( call( f.lena, "get_deployment", "deployment", 999_999 ) ) );
    }

    // ---- what reaches the security log

    @Test
    void aLogFieldCarriesNoControlSeparatorOrInvisibleCharacter() {
        assertEquals( "-", WorkMcpLog.clean( null ) );
        assertEquals( "-", WorkMcpLog.clean( "  ​\n\t" ) );
        assertEquals( "PLAT-1forged", WorkMcpLog.clean( "PLAT-1\nfor ged​" ) );
        assertEquals( 64, WorkMcpLog.clean( "x".repeat( 500 ) ).length() );
        assertEquals( "-", WorkMcpLog.cleanKey( null ) );
        assertEquals( "PLAT-1agentroot", WorkMcpLog.cleanKey( "PLAT-1 agent=root" ) );
        assertEquals( "PLAT-1x", WorkMcpLog.cleanKey( "PLAT-1  x" ) );
    }

    @Test
    void anAgentEstimatesThroughTheRealService() throws SQLException {
        final com.wikantik.work.Item item = f.ready( "PLAT", "Sized by the agent" );
        final JsonObject o = ok( call( f.builderActor, "update_item", "key", item.key(),
            "expectedVersion", item.version(), "estimate", 3 ) );
        assertEquals( 3.0, o.get( "estimate" ).getAsDouble() );
        final var estimateEvents = f.svc.events( f.lena, item.key() ).stream()
            .filter( e -> "field".equals( e.type() ) && "estimate".equals( e.note() ) ).toList();
        assertEquals( 1, estimateEvents.size() );
        assertEquals( f.builder.id(), estimateEvents.get( 0 ).agentId() );
        assertEquals( com.wikantik.work.Actor.Kind.AGENT, estimateEvents.get( 0 ).actorKind() );
        assertEquals( "sprint: A person plans a sprint.", refused( call( f.builderActor, "update_item",
            "key", item.key(), "expectedVersion", o.get( "version" ).getAsInt(), "sprint", 1 ) ) );
    }
}

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
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.Agent;
import com.wikantik.work.AgentDraft;
import com.wikantik.work.Item;
import com.wikantik.work.WaitingItem;
import com.wikantik.work.WorkClaimsService.Handoff;
import com.wikantik.work.WorkRole;
import com.wikantik.work.WorkStatus;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole loop an agent runs, through the real tools, dispatch, service and store on Postgres: after the fixture
 * every step is a tool call carrying the calling agent's own key. After each step the event log is read back: every
 * change is there, attributed to the calling agent by id, and every ended claim has its {@code released} event.
 */
@RequiresPostgres
class WorkMcpFlowPostgresTest {

    private static final String NEEDS_KEY = "This endpoint needs a work API key.";

    private WorkMcpFixture f;
    private List< WorkMcpTool > tools;
    private Agent reviewer;

    @BeforeEach
    void setUp() throws SQLException {
        f = new WorkMcpFixture();
        tools = WorkMcpInitializer.assembleTools( f.svc, f.directory() );
        reviewer = f.svc.registry().registerAgent( f.admin, new AgentDraft( "reviewer-bot", "Acme", "claude-code",
            List.of( "reviewer" ), "lena" ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "reviewer-bot", "agent", WorkRole.MEMBER );
        f.svc.putMember( f.admin, "PLAT", "mo", "user", WorkRole.MEMBER );
        f.displayNames.put( "mo", "Mo Member" );
    }

    /** One tool call as the agent whose key it is: through the transport context and per-call actor resolution. */
    private McpSchema.CallToolResult call( final Agent who, final String tool, final Object... kv ) {
        final Map< String, Object > args = new LinkedHashMap<>();
        for ( int i = 0; i < kv.length; i += 2 ) {
            args.put( ( String ) kv[ i ], kv[ i + 1 ] );
        }
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ) )
            .thenReturn( WorkMcpFixture.record( who.apiKeyId(), "lena", ApiKeyService.Scope.WORK ) );
        final WorkMcpTool t = tools.stream().filter( x -> x.name().equals( tool ) ).findFirst().orElseThrow();
        return WorkMcpInitializer.dispatch( t, f.svc, WorkMcpActors.extract( request ), args );
    }

    private static void ok( final McpSchema.CallToolResult r ) {
        assertFalse( Boolean.TRUE.equals( r.isError() ), WorkMcpFixture.text( r ) );
    }

    private int version( final Agent who, final String key ) {
        final McpSchema.CallToolResult r = call( who, "get_item", "key", key );
        ok( r );
        return WorkMcpFixture.json( r ).get( "version" ).getAsInt();
    }

    /** The item's events as {@code type|actorKind|agentId|from|to|note}, oldest first, straight from the table. */
    private List< String > events( final String key ) throws SQLException {
        final List< String > rows = new ArrayList<>();
        try ( Connection c = f.ds.getConnection(); Statement s = c.createStatement();
              ResultSet rs = s.executeQuery( "SELECT e.type, e.actor_kind, e.agent_id, e.from_value, e.to_value, e.note "
                  + "FROM work_event e JOIN work_item i ON i.id = e.item_id JOIN work_project p ON p.id = i.project_id "
                  + "WHERE p.key || '-' || i.seq = '" + key + "' ORDER BY e.id" ) ) {
            while ( rs.next() ) {
                rows.add( rs.getString( 1 ) + "|" + rs.getString( 2 ) + "|" + rs.getObject( 3 ) + "|" + rs.getString( 4 )
                    + "|" + rs.getString( 5 ) + "|" + rs.getString( 6 ) );
            }
        }
        return rows;
    }

    private static String agentFields( final String row ) {
        final String[] p = row.split( "\\|", -1 );
        return p[ 1 ] + "/" + p[ 2 ];
    }

    private static void assertLastEvent( final List< String > log, final String type, final Agent by ) {
        final String last = log.get( log.size() - 1 );
        assertTrue( last.startsWith( type + "|" ), "expected a final " + type + " event, got " + last );
        assertEquals( "agent/" + by.id(), agentFields( last ), last );
    }

    @Test
    void fourHandoffDirectionsThroughTools() throws SQLException, IOException {
        // direction 1: a person puts it in a queue (fixture: lena, human)
        final Item item = f.ready( "PLAT", "Build the thing" );
        final String key = item.key();
        List< String > log = events( key );
        assertTrue( log.stream().allMatch( r -> agentFields( r ).equals( "user/null" ) ), log.toString() );

        // direction 2: queue -> agent A
        McpSchema.CallToolResult claimed = call( f.builder, "claim_item", "key", key );
        ok( claimed );
        final JsonObject claim = WorkMcpFixture.json( claimed ).getAsJsonObject( "claim" );
        assertEquals( false, claim.get( "renewed" ).getAsBoolean() );
        assertNotNull( claim.get( "expiresAt" ).getAsString() );
        assertEquals( "builder", WorkMcpFixture.json( claimed ).getAsJsonObject( "item" ).getAsJsonObject( "claim" ).get( "agent" ).getAsString() );
        assertLastEvent( events( key ), "claimed", f.builder );

        ok( call( f.builder, "comment_on_item", "key", key, "body", "Started; the retry loop is the hard part." ) );
        assertLastEvent( events( key ), "commented", f.builder );

        // direction 3: agent A -> queue reviewer (its claim ends)
        final McpSchema.CallToolResult toQueue = call( f.builder, "handoff_item", "key", key,
            "expectedVersion", version( f.builder, key ), "status", "review", "queue", "reviewer",
            "note", "Implemented with tests; please review the retry loop." );
        ok( toQueue );
        assertEquals( "Reviewer", WorkMcpFixture.json( toQueue ).get( "queue" ).getAsString() );
        log = events( key );
        final int n = log.size();
        assertTrue( log.get( n - 2 ).startsWith( "released|" ) || log.get( n - 1 ).startsWith( "released|" ), log.toString() );
        final List< String > lastTwo = log.subList( n - 2, n );
        assertTrue( lastTwo.stream().anyMatch( r -> r.startsWith( "handoff|agent|" + f.builder.id() + "|" )
            && r.endsWith( "|Implemented with tests; please review the retry loop." ) ), lastTwo.toString() );
        assertTrue( lastTwo.stream().anyMatch( r -> r.startsWith( "released|" ) && r.contains( "|agent:" + f.builder.id() + "|" )
            && agentFields( r ).equals( "agent/" + f.builder.id() ) ), lastTwo.toString() );

        // direction 4a: queue -> agent B, which hands it to a person
        ok( call( reviewer, "claim_item", "key", key ) );
        assertLastEvent( events( key ), "claimed", reviewer );
        final McpSchema.CallToolResult toPerson = call( reviewer, "handoff_item", "key", key,
            "expectedVersion", version( reviewer, key ), "status", "review", "assignee", "Lena Lead",
            "note", "Looks right; needs your decision on the retry limit." );
        ok( toPerson );
        assertEquals( "Lena Lead", WorkMcpFixture.json( toPerson ).get( "assignee" ).getAsString() );
        log = events( key );
        final List< String > lastTwoB = log.subList( log.size() - 2, log.size() );
        assertTrue( lastTwoB.stream().anyMatch( r -> r.startsWith( "handoff|agent|" + reviewer.id() + "|" ) ), lastTwoB.toString() );
        assertTrue( lastTwoB.stream().anyMatch( r -> r.startsWith( "released|" ) && r.contains( "|agent:" + reviewer.id() + "|" )
            && agentFields( r ).equals( "agent/" + reviewer.id() ) ), lastTwoB.toString() );

        // lena's waitingOn holds it, with B's note
        final List< WaitingItem > waiting = f.svc.waitingOn( f.lena );
        final WaitingItem w = waiting.stream().filter( x -> x.item().key().equals( key ) ).findFirst().orElseThrow();
        assertEquals( WaitingItem.ASSIGNED, w.reason() );
        assertEquals( "Looks right; needs your decision on the retry limit.", w.handoff().note() );
        assertEquals( reviewer.id(), w.handoff().agentId() );

        // direction 4b: person -> person, through the service
        f.svc.claims().handoff( f.lena, key, new Handoff( f.svc.item( f.lena, key ).item().version(), WorkStatus.IN_PROGRESS,
            "Mo Member", null, "Mo, please settle the retry limit." ) );
        log = events( key );
        assertTrue( log.get( log.size() - 1 ).startsWith( "handoff|" ), log.toString() );

        Files.createDirectories( Path.of( "target" ) );
        Files.write( Path.of( "target", "flow-event-log.txt" ), log );
    }

    @Test
    void agentCannotTouchOtherAgentsClaim() throws SQLException {
        final String key = f.ready( "PLAT", "Held by builder" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );
        final int eventsBefore = events( key ).size();
        final int v = version( f.builder, key );
        // the second agent serves implementer too, so only the holder's claim stands in its way
        final Agent other = f.svc.registry().registerAgent( f.admin, new AgentDraft( "second", "Acme", "claude-code",
            List.of( "implementer" ), "lena" ) ).agent();
        f.svc.putMember( f.admin, "PLAT", "second", "agent", WorkRole.MEMBER );

        final String claimText = WorkMcpFixture.text( call( other, "claim_item", "key", key ) );
        assertTrue( claimText.startsWith( "not_claimable: " ), claimText );
        assertTrue( claimText.contains( "builder" ), "names the holder: " + claimText );

        final String update = WorkMcpFixture.text( call( other, "update_item", "key", key, "expectedVersion", v, "title", "Mine now" ) );
        final String hand = WorkMcpFixture.text( call( other, "handoff_item", "key", key, "expectedVersion", v,
            "status", "review", "queue", "reviewer", "note", "Taking over." ) );
        final String release = WorkMcpFixture.text( call( other, "release_item", "key", key ) );
        assertTrue( update.startsWith( "denied: " ), update );
        assertTrue( hand.startsWith( "denied: " ), hand );
        assertTrue( release.startsWith( "denied: " ), release );
        assertEquals( eventsBefore, events( key ).size(), "refused calls write nothing" );
        assertEquals( f.builder.id(), f.svc.item( f.lena, key ).item().claimedByAgent() );
    }

    @Test
    void aHandOffToAQueueAsInProgressIsAStatusRefusal() throws SQLException {
        final String key = f.ready( "PLAT", "Still going" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );
        final int eventsBefore = events( key ).size();

        final String hand = WorkMcpFixture.text( call( f.builder, "handoff_item", "key", key, "expectedVersion",
            version( f.builder, key ), "status", "in_progress", "queue", "reviewer", "note", "Half done." ) );

        assertEquals( "status: Work in a queue is ready or in review until an agent claims it.", hand );
        assertEquals( eventsBefore, events( key ).size(), "the refused hand-off writes nothing" );
        assertEquals( f.builder.id(), f.svc.item( f.lena, key ).item().claimedByAgent(), "and keeps the claim" );
    }

    @Test
    void releaseReturnsItemToQueue() throws SQLException {
        final String key = f.ready( "PLAT", "Give it back" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );

        final McpSchema.CallToolResult released = call( f.builder, "release_item", "key", key, "note", "Missing a credential." );
        ok( released );

        final JsonObject out = WorkMcpFixture.json( released );
        assertEquals( key, out.get( "key" ).getAsString() );
        assertEquals( "ready", out.get( "status" ).getAsString() );
        final Item now = f.svc.item( f.lena, key ).item();
        assertEquals( "implementer", now.queue() );
        assertEquals( null, now.claimedByAgent() );
        final List< String > log = events( key );
        assertLastEvent( log, "released", f.builder );
        assertTrue( log.get( log.size() - 1 ).endsWith( "|Missing a credential." ), log.toString() );
        assertTrue( log.get( log.size() - 1 ).contains( "|agent:" + f.builder.id() + "|" ), log.toString() );
        // it can be claimed again
        assertTrue( WorkMcpFixture.json( call( f.builder, "list_claimable" ) ).getAsJsonArray( "items" ).toString().contains( key ) );
    }

    @Test
    void claimingAgainRenews() throws SQLException {
        final String key = f.ready( "PLAT", "Long job" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );
        final McpSchema.CallToolResult again = call( f.builder, "claim_item", "key", key );
        ok( again );
        assertEquals( true, WorkMcpFixture.json( again ).getAsJsonObject( "claim" ).get( "renewed" ).getAsBoolean() );
        assertLastEvent( events( key ), "claim_renewed", f.builder );
    }

    @Test
    void anAgentRetiredMidFlowLosesItsKeyAndItsClaim() throws SQLException {
        final String key = f.ready( "PLAT", "Doomed" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );

        f.svc.registry().retireAgent( f.admin, f.builder.id() );

        assertEquals( NEEDS_KEY, WorkMcpFixture.text( call( f.builder, "comment_on_item", "key", key, "body", "still here?" ) ) );
        assertEquals( NEEDS_KEY, WorkMcpFixture.text( call( f.builder, "get_item", "key", key ) ) );
        final List< String > log = events( key );
        final String last = log.get( log.size() - 1 );
        assertTrue( last.startsWith( "released|" ), last );
        assertTrue( last.contains( "|agent:" + f.builder.id() + "|" ), last );
        assertTrue( last.endsWith( "|agent retired" ), last );
        assertEquals( null, f.svc.item( f.lena, key ).item().claimedByAgent() );
    }

    @Test
    void createItemLandsInDraftReportedByTheAgent() throws SQLException {
        final McpSchema.CallToolResult created = call( f.builder, "create_item", "project", "PLAT", "level", "task",
            "title", "Found while working", "body", "Details", "queue", "implementer" );
        ok( created );

        final JsonObject out = WorkMcpFixture.json( created );
        assertEquals( "draft", out.get( "status" ).getAsString() );
        final String key = out.get( "key" ).getAsString();
        final Item item = f.svc.item( f.lena, key ).item();
        assertEquals( WorkStatus.DRAFT, item.status() );
        assertEquals( f.builder.id(), item.reporterAgentId() );
        assertEquals( null, item.assignee() );
        assertEquals( out.get( "version" ).getAsInt(), item.version() );
        // created into a queue: the creation is followed by the first assignment, both by the agent
        final List< String > log = events( key );
        assertEquals( 2, log.size(), log.toString() );
        assertTrue( log.get( 0 ).startsWith( "created|" ), log.toString() );
        assertEquals( "agent/" + f.builder.id(), agentFields( log.get( 0 ) ) );
        assertLastEvent( log, "assigned", f.builder );
        assertTrue( log.get( 1 ).contains( "|null|queue:implementer|" ), log.get( 1 ) );
        assertEquals( "status: New items always start as drafts.", WorkMcpFixture.text(
            call( f.builder, "create_item", "project", "PLAT", "level", "task", "title", "x", "status", "ready" ) ) );
    }

    @Test
    void anAgentCannotReachAProjectItWasNeverAdmittedTo() throws SQLException {
        final String key = f.ready( "SECRET", "Hidden" ).key();
        for ( final String tool : List.of( "claim_item", "release_item" ) ) {
            assertEquals( "Not found.", WorkMcpFixture.text( call( f.builder, tool, "key", key ) ) );
        }
        assertEquals( "Not found.", WorkMcpFixture.text( call( f.builder, "comment_on_item", "key", key, "body", "hi" ) ) );
        assertEquals( "Not found.", WorkMcpFixture.text( call( f.builder, "create_item", "project", "SECRET", "level", "task", "title", "x" ) ) );
    }


    private int rowCount( final String sql ) throws SQLException {
        return ( int ) f.scalar( sql );
    }

    private long itemId( final String key ) throws SQLException {
        return f.scalar( "SELECT i.id FROM work_item i JOIN work_project p ON p.id = i.project_id WHERE p.key || '-' || i.seq = '"
            + key + "'" );
    }

    @Test
    void updateItemIsRecordedAgainstTheAgentAndRefusalsWriteNothing() throws SQLException {
        final String key = f.ready( "PLAT", "Update me" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );

        // a status change with a note: one status event, attributed to the agent, carrying the note
        final McpSchema.CallToolResult moved = call( f.builder, "update_item", "key", key,
            "expectedVersion", version( f.builder, key ), "status", "review", "note", "Ready for a look" );
        ok( moved );
        final List< String > log = events( key );   // a status change by the holder also ends its claim: status, then released
        final String status = log.stream().filter( r -> r.startsWith( "status|" ) ).reduce( ( a, b ) -> b ).orElseThrow();
        assertEquals( "agent/" + f.builder.id(), agentFields( status ), status );
        assertTrue( status.endsWith( "|in_progress|review|Ready for a look" ), status );
        assertTrue( log.get( log.size() - 1 ).startsWith( "released|agent|" + f.builder.id() + "|agent:" + f.builder.id() ), log.toString() );

        // title and body: the revision row names the agent and the version moved
        final int before = version( f.builder, key );
        final McpSchema.CallToolResult edited = call( f.builder, "update_item", "key", key, "expectedVersion", before,
            "title", "Updated title", "body", "Updated body" );
        ok( edited );
        assertTrue( WorkMcpFixture.json( edited ).get( "version" ).getAsInt() > before );
        assertEquals( "Updated title", WorkMcpFixture.json( edited ).get( "title" ).getAsString() );
        assertEquals( f.builder.id(), f.scalar( "SELECT edited_by_agent_id FROM work_item_revision WHERE item_id = "
            + itemId( key ) + " ORDER BY version DESC LIMIT 1" ) );
        assertEquals( "Updated body", WorkMcpFixture.json( call( f.builder, "get_item", "key", key ) ).get( "body" ).getAsString() );

        // refusals by name: no event, no version change, no revision
        final int events = events( key ).size();
        final int v = version( f.builder, key );
        final long revisions = f.scalar( "SELECT count(*) FROM work_item_revision WHERE item_id = " + itemId( key ) );
        assertEquals( "assignee: Use handoff_item to change who has the item.", WorkMcpFixture.text(
            call( f.builder, "update_item", "key", key, "expectedVersion", v, "assignee", "Lena Lead" ) ) );
        assertEquals( "queue: Use handoff_item to change who has the item.", WorkMcpFixture.text(
            call( f.builder, "update_item", "key", key, "expectedVersion", v, "queue", "reviewer" ) ) );
        assertEquals( events, events( key ).size() );
        assertEquals( v, version( f.builder, key ) );
        assertEquals( revisions, f.scalar( "SELECT count(*) FROM work_item_revision WHERE item_id = " + itemId( key ) ) );
        assertEquals( 1, rowCount( "SELECT count(*) FROM work_event WHERE type = 'assigned' AND item_id = " + itemId( key ) ),
            "only the assignment made at creation; the refused updates wrote none" );
    }

    @Test
    void anAgentCannotPromoteItsOwnDraftButCanPassItToAPerson() throws SQLException {
        final McpSchema.CallToolResult created = call( f.builder, "create_item", "project", "PLAT", "level", "task",
            "title", "Idea", "queue", "implementer" );
        ok( created );
        final String key = WorkMcpFixture.json( created ).get( "key" ).getAsString();
        final int eventsBefore = events( key ).size();

        assertEquals( "denied: A person moves a draft forward.", WorkMcpFixture.text( call( f.builder, "update_item",
            "key", key, "expectedVersion", version( f.builder, key ), "status", "ready" ) ) );
        assertEquals( "denied: A person moves a draft forward.", WorkMcpFixture.text( call( f.builder, "handoff_item",
            "key", key, "expectedVersion", version( f.builder, key ), "status", "ready", "assignee", "Lena Lead",
            "note", "promote it" ) ) );
        assertEquals( eventsBefore, events( key ).size(), "the refusals wrote nothing" );

        // editing the draft is fine, and so is handing it to a person with the status left as draft
        ok( call( f.builder, "update_item", "key", key, "expectedVersion", version( f.builder, key ), "body", "Details" ) );
        // the person's LOGIN is not a name an agent may use; the display name is
        assertEquals( "assignee: The assignee is not a member of this project.", WorkMcpFixture.text( call( f.builder,
            "handoff_item", "key", key, "expectedVersion", version( f.builder, key ), "status", "draft",
            "assignee", "mo", "note", "for you" ) ) );
        ok( call( f.builder, "handoff_item", "key", key, "expectedVersion", version( f.builder, key ), "status", "draft",
            "assignee", "Lena Lead", "note", "Ready for review; please refine and promote." ) );
        final WaitingItem waiting = f.svc.waitingOn( f.lena ).stream().filter( x -> x.item().key().equals( key ) )
            .findFirst().orElseThrow();
        assertEquals( "Ready for review; please refine and promote.", waiting.handoff().note() );
        assertEquals( f.builder.id(), waiting.handoff().agentId() );

        // the person promotes it into the queue; now the agent can take it
        final Item held = f.svc.item( f.lena, key ).item();
        f.svc.claims().handoff( f.lena, key, new Handoff( held.version(), WorkStatus.READY, null, "implementer",
            "Refined; go ahead." ) );
        ok( call( f.builder, "claim_item", "key", key ) );
        assertLastEvent( events( key ), "claimed", f.builder );
    }

    @Test
    void handingOffToAPersonByDisplayNameWorks() throws SQLException {
        final String key = f.ready( "PLAT", "To Mo" ).key();
        ok( call( f.builder, "claim_item", "key", key ) );
        ok( call( f.builder, "handoff_item", "key", key, "expectedVersion", version( f.builder, key ), "status", "review",
            "assignee", "Mo Member", "note", "Over to you." ) );
        assertEquals( "mo", f.svc.item( f.lena, key ).item().assignee() );
    }
}

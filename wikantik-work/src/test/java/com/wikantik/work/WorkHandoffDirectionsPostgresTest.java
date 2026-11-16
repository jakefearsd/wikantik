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
package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import com.wikantik.work.WorkClaimsService.ClaimResult;
import com.wikantik.work.WorkClaimsService.Handoff;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The four hand-off directions through the service on a real database, and what the event log holds after each. */
@RequiresPostgres
class WorkHandoffDirectionsPostgresTest {

    private static final Map< String, String > LOGINS = Map.of( "Lena Lead", "lena", "Mo Member", "mo" );

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor mo = Actor.human( "mo", Set.of(), false );
    private final Actor jake = Actor.human( "jake", Set.of(), false );
    private DataSource ds;
    private WorkService svc;
    private Actor a;
    private Actor b;
    private Actor agentJake;
    private long ordinal;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final AgentKeys keys = new AgentKeys() {
            @Override
            public Minted mint( final String ownerLogin, final String label ) {
                try {
                    return new Minted( ( int ) WorkAgentFixtures.apiKey( ds, "work" ), "tok" );
                } catch ( final SQLException e ) {
                    throw new IllegalStateException( e );
                }
            }

            @Override
            public Minted remint( final int oldKeyId, final String label ) {
                return mint( null, label );
            }

            @Override
            public void revoke( final int keyId, final String revokedBy ) {
            }
        };
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return Optional.ofNullable( LOGINS.get( name ) );
            }
        }, keys, Clock.systemUTC(), WorkSettings.DEFAULTS, null );
        svc.createProject( admin, "PLAT", "Platform", "" );
        svc.putMember( admin, "PLAT", "lena", "user", WorkRole.LEAD );
        svc.putMember( admin, "PLAT", "mo", "user", WorkRole.MEMBER );
        svc.putMember( admin, "PLAT", "jake", "user", WorkRole.MEMBER );
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        svc.registry().createQueue( admin, "reviewer", "Reviewer", "" );
        a = admit( "agent-a", "implementer" );
        b = admit( "agent-b", "reviewer" );
        agentJake = admit( "jake", "implementer" );
    }

    private Actor admit( final String name, final String queue ) throws SQLException {
        final Agent agent = svc.registry().registerAgent( admin, new AgentDraft( name, "", "", List.of( queue ),
            "lena" ) ).agent();
        svc.putMember( admin, "PLAT", name, "agent", WorkRole.MEMBER );
        return Actor.agent( agent.id(), agent.name() );
    }

    private String create( final String assignee ) throws SQLException {
        return svc.createItem( lena, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "task " + ++ordinal, "", null, null, assignee, null ) ).item().key();
    }

    private List< WorkEvent > events( final String key ) throws SQLException {
        return svc.events( lena, key );
    }

    private static String line( final WorkEvent e ) {
        return e.type() + " " + e.actorKind() + " " + e.fromValue() + " -> " + e.toValue() + " [" + e.note() + "]";
    }

    private List< String > log( final String key ) throws SQLException {
        final List< String > out = new ArrayList<>();
        events( key ).forEach( e -> out.add( line( e ) ) );
        return out;
    }

    @Test
    void humanToAgent() throws SQLException {
        final String key = create( null );
        final Item v = svc.item( lena, key ).item();
        svc.claims().handoff( lena, key, new Handoff( v.version(), WorkStatus.READY, null, "implementer", "go" ) );
        assertTrue( svc.claims().claimable( a, "PLAT", null, 20 ).stream().anyMatch( s -> key.equals( s.item().key() ) ) );
        final ClaimResult r = svc.claims().claim( a, key );
        assertEquals( WorkStatus.IN_PROGRESS, r.view().item().status() );
        assertEquals( a.agentId(), r.view().item().claimedByAgent() );
        assertEquals( List.of( "created HUMAN null -> null [null]",
            "handoff HUMAN draft| -> ready|queue:implementer [go]",
            "claimed AGENT ready -> in_progress [" + r.view().item().claimExpiresAt() + "]" ), log( key ) );
        assertEquals( List.of( key ), svc.claims().myClaims( a ).stream().map( Item::key ).toList() );
    }

    @Test
    void agentToAgent() throws SQLException {
        final String key = create( null );
        svc.claims().handoff( lena, key, new Handoff( svc.item( lena, key ).item().version(), WorkStatus.READY, null,
            "implementer", "go" ) );
        final Item claimed = svc.claims().claim( a, key ).view().item();
        final WorkService.ItemView moved = svc.claims().handoff( a, key,
            new Handoff( claimed.version(), WorkStatus.REVIEW, null, "reviewer", "please review" ) );
        assertNull( moved.item().claimedByAgent() );
        assertEquals( "reviewer", moved.item().queue() );
        final ClaimResult r = svc.claims().claim( b, key );
        assertEquals( WorkStatus.REVIEW, r.view().item().status() );
        assertEquals( b.agentId(), r.view().item().claimedByAgent() );
        final List< String > log = log( key );
        assertEquals( "handoff AGENT in_progress|queue:implementer -> review|queue:reviewer [please review]", log.get( 3 ) );
        assertEquals( "released AGENT agent:" + a.agentId() + " -> review [reassigned]", log.get( 4 ) );
        assertTrue( log.get( 5 ).startsWith( "claimed AGENT review -> review [" ), log.get( 5 ) );
        assertEquals( 6, log.size() );
    }

    @Test
    void agentToHuman() throws SQLException {
        final String key = create( null );
        svc.claims().handoff( lena, key, new Handoff( svc.item( lena, key ).item().version(), WorkStatus.REVIEW, null,
            "reviewer", "go" ) );
        final Item claimed = svc.claims().claim( b, key ).view().item();
        svc.claims().handoff( b, key, new Handoff( claimed.version(), WorkStatus.REVIEW, "Lena Lead", null,
            "please approve" ) );
        final WaitingItem w = svc.waitingOn( lena ).stream().filter( x -> key.equals( x.item().key() ) )
            .findFirst().orElseThrow();
        assertEquals( "assigned", w.reason() );
        assertEquals( "please approve", w.handoff().note() );
        assertEquals( b.agentId(), w.handoff().agentId() );
        assertEquals( "lena", w.item().assignee() );
        assertNull( w.item().queue() );
        final List< String > log = log( key );
        assertEquals( "handoff AGENT review|queue:reviewer -> review|lena [please approve]", log.get( 3 ) );
        assertEquals( "released AGENT agent:" + b.agentId() + " -> review [reassigned]", log.get( 4 ) );
        assertEquals( 5, log.size() );
    }

    @Test
    void humanToHuman() throws SQLException {
        final String key = create( "Lena Lead" );
        svc.claims().handoff( lena, key, new Handoff( svc.item( lena, key ).item().version(), WorkStatus.READY,
            "Mo Member", null, "yours now" ) );
        final WaitingItem w = svc.waitingOn( mo ).stream().filter( x -> key.equals( x.item().key() ) )
            .findFirst().orElseThrow();
        assertEquals( "assigned", w.reason() );
        assertEquals( "yours now", w.handoff().note() );
        assertEquals( Actor.Kind.HUMAN, w.handoff().actorKind() );
        assertEquals( List.of( "created HUMAN null -> null [null]", "handoff HUMAN draft|lena -> ready|mo [yours now]" ),
            log( key ) );
        assertTrue( svc.waitingOn( lena ).stream().noneMatch( x -> key.equals( x.item().key() ) ) );
    }

    @Test
    void staleAgentWriteAfterHumanMoveIsRefused() throws SQLException {
        final String key = create( null );
        svc.claims().handoff( lena, key, new Handoff( svc.item( lena, key ).item().version(), WorkStatus.READY, null,
            "implementer", "go" ) );
        final Item held = svc.claims().claim( a, key ).view().item();
        svc.updateItem( lena, key, new ItemPatch( held.version(), Map.of( "status", "refining" ), null ) );
        final WorkConflictException stale = assertThrows( WorkConflictException.class, () -> svc.updateItem( a, key,
            new ItemPatch( held.version(), Map.of( "title", "late" ), null ) ) );
        assertEquals( WorkConflictException.STALE, stale.reason() );
        final WorkConflictException gone = assertThrows( WorkConflictException.class,
            () -> svc.claims().release( a, key, "done" ) );
        assertEquals( WorkConflictException.NOT_CLAIMED, gone.reason() );
        assertEquals( 1, events( key ).stream().filter( e -> WorkEvent.RELEASED.equals( e.type() ) ).count() );
    }

    @Test
    void humanCannotEditAgentCommentOfSameName() throws SQLException {
        final String key = create( null );
        final long id = svc.addComment( agentJake, key, "agent says" ).id();
        assertThrows( WorkDeniedException.class, () -> svc.editComment( jake, id, "tampered" ) );
        assertThrows( WorkDeniedException.class, () -> svc.removeComment( jake, id ) );
        assertEquals( "edited", svc.editComment( agentJake, id, "edited" ).body() );
    }

    @Test
    void agentCannotEditHumanCommentOfSameName() throws SQLException {
        final String key = create( null );
        final long id = svc.addComment( jake, key, "human says" ).id();
        assertThrows( WorkDeniedException.class, () -> svc.editComment( agentJake, id, "tampered" ) );
        assertThrows( WorkDeniedException.class, () -> svc.removeComment( agentJake, id ) );
        assertEquals( "edited", svc.editComment( jake, id, "edited" ).body() );
    }

    @Test
    void queueKeyOnUpdateClearsTheAssignee() throws SQLException {
        final String key = create( "Lena Lead" );
        final Item v = svc.item( lena, key ).item();
        final Item next = svc.updateItem( lena, key, new ItemPatch( v.version(), Map.of( "queue", "implementer" ),
            null ) ).item();
        assertEquals( "implementer", next.queue() );
        assertNull( next.assignee() );
        assertEquals( "No such queue.", assertThrows( WorkValidationException.class, () -> svc.updateItem( lena, key,
            new ItemPatch( next.version(), Map.of( "queue", "nowhere" ), null ) ) ).getMessage() );
    }
}

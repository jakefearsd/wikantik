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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The assignment log through {@link WorkService}: it covers exactly the projects the caller can see. */
@RequiresPostgres
class WorkServiceAssignmentLogTest {

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor bob = Actor.human( "bob", Set.of(), false );
    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private DataSource ds;
    private WorkService svc;
    private Actor codex;
    private int seq;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final AgentKeys keys = new AgentKeys() {
            @Override
            public Minted mint( final String ownerLogin, final String label, final String createdBy ) {
                try {
                    return new Minted( ( int ) WorkAgentFixtures.ownedKey( ds, ownerLogin ), "tok" );
                } catch ( final SQLException e ) {
                    throw new IllegalStateException( e );
                }
            }

            @Override
            public Minted remint( final int oldKeyId, final String label, final String createdBy ) {
                return mint( WorkAgentFixtures.ownerOf( ds, oldKeyId ), label, createdBy );
            }

            @Override
            public void revoke( final int keyId, final String revokedBy ) {
            }
        };
        svc = new WorkService( new JdbcWorkStore( ds ), md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) { return Optional.empty(); }
        }, keys, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() { } );
        svc.createProject( admin, "AAA", "Alpha", "" );
        svc.createProject( admin, "BBB", "Beta", "" );
        svc.putMember( admin, "AAA", "lena", "user", WorkRole.LEAD );
        svc.putMember( admin, "BBB", "bob", "user", WorkRole.LEAD );
        svc.putMember( admin, "BBB", "lena", "user", WorkRole.LEAD );   // lena leads both; removed below where needed
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        final Agent agent = svc.registry().registerAgent( admin, new AgentDraft( "codex@docker2", "", "",
            List.of( "implementer" ) ) ).agent();
        codex = Actor.agent( agent.id(), agent.name(), admin );
    }

    private String ready( final Actor by, final String project, final String title ) throws SQLException {
        final Item draft = svc.createItem( by, new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            title, "", null, null, null, "implementer" ) ).item();
        return svc.updateItem( by, draft.key(), new ItemPatch( draft.version(), Map.of( "status", "ready" ), null ) )
            .item().key();
    }

    private List< AssignmentLogEvent > log( final Actor who ) throws SQLException {
        return svc.assignmentLog( who, AssignmentLogQuery.all() );
    }

    @Test
    void logCoversOnlyVisibleProjects() throws SQLException {
        svc.removeMember( admin, "BBB", "lena", "user" );
        ready( lena, "AAA", "alpha work" );
        ready( bob, "BBB", "beta work, newer" );
        final List< AssignmentLogEvent > seen = log( lena );
        assertFalse( seen.isEmpty(), "positive control: lena sees her own project" );
        assertTrue( seen.stream().allMatch( e -> e.projectKey().equals( "AAA" ) ), seen.toString() );
        assertTrue( log( bob ).stream().allMatch( e -> e.projectKey().equals( "BBB" ) ) );
        assertEquals( Set.of( "AAA", "BBB" ), log( admin ).stream().map( AssignmentLogEvent::projectKey )
            .collect( java.util.stream.Collectors.toSet() ), "an instance admin sees every project" );
    }

    @Test
    void rowsCarryProjectAndItemNames() throws SQLException {
        final String key = ready( lena, "AAA", "alpha work" );
        final AssignmentLogEvent e = log( lena ).get( 0 );
        assertEquals( "Alpha", e.projectName() );
        assertEquals( key, e.itemKey() );
        assertEquals( "alpha work", e.itemTitle() );
        assertFalse( e.projectArchived() );
        svc.archiveProject( admin, "AAA" );
        assertTrue( log( lena ).get( 0 ).projectArchived(), "an archived project's history stays, marked" );
    }

    @Test
    void strangerGetsEmptyLog() throws SQLException {
        ready( lena, "AAA", "alpha work" );
        assertTrue( log( mallory ).isEmpty() );
        assertTrue( svc.assignmentLog( WorkAgentFixtures.sponsored( 999_999, "ghost" ), AssignmentLogQuery.all() ).isEmpty() );
    }

    @Test
    void agentLogCoversOnlyAdmittedProjects() throws SQLException {
        // codex's key owner (bob) leads BBB, but codex is admitted to AAA only
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        ready( lena, "AAA", "alpha work" );
        final String beta = ready( bob, "BBB", "beta work, newer" );
        final List< AssignmentLogEvent > seen = log( codex );
        assertFalse( seen.isEmpty() );
        assertTrue( seen.stream().allMatch( e -> e.projectKey().equals( "AAA" ) ), seen.toString() );
        assertTrue( seen.stream().noneMatch( e -> beta.equals( e.itemKey() ) ) );
        final Actor flagged = new Actor( "codex@docker2", Set.of(), true, Actor.Kind.AGENT, codex.agentId(), null );
        assertTrue( log( flagged ).stream().allMatch( e -> e.projectKey().equals( "AAA" ) ),
            "an agent that asks for the admin flag still sees only what it was admitted to" );
    }

    @Test
    void agentMembershipIsInTheLogAndPeoplesIsNot() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        svc.putMember( admin, "AAA", "bob", "user", WorkRole.VIEWER );
        svc.removeMember( admin, "AAA", "codex@docker2", "agent" );
        final List< AssignmentLogEvent > seen = log( lena );
        assertEquals( 2, seen.stream().filter( e -> e.type().equals( WorkEvent.MEMBER ) ).count() );
        assertTrue( seen.stream().noneMatch( e -> e.toValue() != null && e.toValue().startsWith( "user:" ) ) );
    }

    @Test
    void projectFilterNarrowsAndHiddenIsAsUnknown() throws SQLException {
        ready( lena, "AAA", "a" );
        ready( lena, "BBB", "b" );
        final AssignmentLogQuery onlyB = new AssignmentLogQuery( "BBB", null, null, null, null, null, 0 );
        assertTrue( svc.assignmentLog( lena, onlyB ).stream().allMatch( e -> e.projectKey().equals( "BBB" ) ) );
        final WorkNotFoundException hidden = assertThrows( WorkNotFoundException.class,
            () -> svc.assignmentLog( mallory, onlyB ) );
        final WorkNotFoundException unknown = assertThrows( WorkNotFoundException.class, () -> svc.assignmentLog( lena,
            new AssignmentLogQuery( "NOPE", null, null, null, null, null, 0 ) ) );
        assertEquals( unknown.getMessage(), hidden.getMessage() );
    }

    @Test
    void rejectsTypeOutsideTheLog() {
        for ( final String project : new String[] { null, "AAA", "GHOST" } ) {
            assertThrows( WorkValidationException.class, () -> svc.assignmentLog( lena,
                new AssignmentLogQuery( project, null, null, Set.of( "comment" ), null, null, 0 ) ) );
            assertThrows( WorkValidationException.class, () -> svc.assignmentLog( lena,
                new AssignmentLogQuery( project, null, null, Set.of( "commented" ), null, null, 0 ) ),
                "a real event type that is not in the log" );
        }
        assertThrows( WorkValidationException.class, () -> svc.activity( lena, "GHOST", null, null, null,
            Set.of( "nonsense" ), 10 ), "activity validates types before looking the project up" );
        assertThrows( WorkValidationException.class, () -> svc.activity( mallory, "AAA", null, null, null,
            Set.of( "nonsense" ), 10 ), "and the same for a project the caller cannot see" );
    }

    @Test
    void activityTypesNarrowTheProjectFeed() throws SQLException {
        final String key = ready( lena, "AAA", "a" );
        svc.addComment( lena, key, "hello" );
        final List< WorkEvent > comments = svc.activity( lena, "AAA", null, null, null, Set.of( "commented" ), 50 );
        assertEquals( List.of( "commented" ), comments.stream().map( WorkEvent::type ).toList() );
        assertTrue( svc.activity( lena, "AAA", null, null, null, null, 50 ).size() > 1 );
    }

    @Test
    void aClaimThenStatusThenExpiryReadInOrder() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        final String key = ready( lena, "AAA", "claimed work" );
        svc.claims().claim( codex, key );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claim_expires_at = now() - INTERVAL '1 minute'" );
        assertEquals( 1, svc.claims().sweepExpiredClaims() );

        final List< AssignmentLogEvent > forItem = log( lena ).stream().filter( e -> key.equals( e.itemKey() ) )
            .toList().reversed();   // oldest first
        final List< String > types = forItem.stream().map( AssignmentLogEvent::type ).toList();
        assertEquals( List.of( "assigned", "status", "claimed", "claim_expired" ), types,
            "an item created into a queue, made ready, claimed, then expired: " + types );
        final AssignmentLogEvent claimed = forItem.get( types.indexOf( "claimed" ) );
        final AssignmentLogEvent expired = forItem.get( types.indexOf( "claim_expired" ) );
        assertEquals( "codex@docker2", claimed.actor() );
        assertEquals( Actor.Kind.AGENT, claimed.actorKind() );
        assertEquals( "ready", claimed.fromValue() );
        assertEquals( "in_progress", claimed.toValue() );
        assertEquals( "codex@docker2", expired.actor() );
        assertEquals( "in_progress", expired.fromValue() );
        assertEquals( "ready", expired.toValue(), "the status is back at ready" );
        assertTrue( types.indexOf( "claimed" ) < types.indexOf( "claim_expired" ) );
    }

    @Test
    void agentAndActorKindFiltersNarrow() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        svc.claims().claim( codex, ready( lena, "AAA", "w" ) );
        final List< AssignmentLogEvent > agents = svc.assignmentLog( lena, new AssignmentLogQuery( null, null, null,
            null, Actor.Kind.AGENT, null, 0 ) );
        assertEquals( List.of( "claimed" ), agents.stream().map( AssignmentLogEvent::type ).toList() );
        final List< AssignmentLogEvent > byAgent = svc.assignmentLog( lena, new AssignmentLogQuery( null, null, null,
            null, null, codex.agentId(), 0 ) );
        assertEquals( List.of( "claimed", "member" ), byAgent.stream().map( AssignmentLogEvent::type ).toList(),
            "what the agent did, and its own admission to the project" );
        assertTrue( svc.assignmentLog( lena, new AssignmentLogQuery( null, null, null, null, null, 12345L, 0 ) )
            .isEmpty() );
    }

    @Test
    void anExpiryIsListedUnderPeopleAndAgentsAlike() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        svc.claims().claim( codex, ready( lena, "AAA", "lapses" ) );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET claim_expires_at = now() - INTERVAL '1 minute'" );
        assertEquals( 1, svc.claims().sweepExpiredClaims() );
        for ( final Actor.Kind who : Actor.Kind.values() ) {
            assertTrue( svc.assignmentLog( lena, new AssignmentLogQuery( null, null, null, null, who, null, 0 ) )
                .stream().anyMatch( e -> "claim_expired".equals( e.type() ) ), "who=" + who );
        }
        assertTrue( svc.assignmentLog( lena, new AssignmentLogQuery( null, null, null, null, Actor.Kind.HUMAN, null,
            0 ) ).stream().noneMatch( e -> "claimed".equals( e.type() ) ), "the agent's own claim stays under Agents" );
    }
}

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

import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The service at its edges: what a caller gets for a missing, blank, unknown or duplicate value, and the defaults
 * an item or a registry entry takes when a field is left out.
 */
@RequiresPostgres
class WorkServiceEdgesPostgresTest {

    private GateFixture f;

    @BeforeEach
    void setUp() throws SQLException {
        f = new GateFixture();
    }

    private static String field( final Executable call ) {
        return assertThrows( WorkValidationException.class, call ).field();
    }

    private static ItemDraft draft( final WorkLevel level, final String title, final Integer priority,
                                    final String parentKey, final String productKey ) {
        return new ItemDraft( "PLAT", level, parentKey, productKey, null, title, null, priority, null, null, null, null, null );
    }

    // ---- items

    @Test
    void aNewItemTakesTheDefaultsForWhatItLeavesOut() throws SQLException {
        final Item made = f.svc.createItem( f.mia, draft( WorkLevel.TASK, "Small thing", null, null, null ) ).item();
        assertEquals( WorkKind.SOFTWARE, made.kind() );
        assertEquals( "", made.body() );
        assertEquals( 3, made.priority() );
        assertEquals( WorkStatus.READY, made.status(), "a person's task starts ready" );
        assertEquals( WorkStatus.DRAFT, f.svc.createItem( f.mia, draft( WorkLevel.EPIC, "An epic", null, null, null ) )
            .item().status(), "an epic starts as a draft" );
        assertNull( made.productId() );
        assertNull( made.assignee() );
        final Item mine = f.svc.createItem( f.mia, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.DOCS,
            "Assigned", "text", 1, null, " mia ", null, null, null ) ).item();
        assertEquals( "mia", mine.assignee(), "a display name or login, trimmed" );
        assertEquals( WorkKind.DOCS, mine.kind() );
    }

    @Test
    void aNewItemIsCheckedFieldByField() throws SQLException {
        assertEquals( "level", field( () -> f.svc.createItem( f.mia, draft( null, "No level", null, null, null ) ) ) );
        assertEquals( "title", field( () -> f.svc.createItem( f.mia, draft( WorkLevel.TASK, " ", null, null, null ) ) ) );
        assertEquals( "title", field( () -> f.svc.createItem( f.mia, draft( WorkLevel.TASK, null, null, null, null ) ) ) );
        assertEquals( "title", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.TASK, "x".repeat( 5_000 ), null, null, null ) ) ) );
        assertEquals( "priority", field( () -> f.svc.createItem( f.mia, draft( WorkLevel.TASK, "P0", 0, null, null ) ) ) );
        assertEquals( "priority", field( () -> f.svc.createItem( f.mia, draft( WorkLevel.TASK, "P5", 5, null, null ) ) ) );
        assertEquals( "productKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.EPIC, "An epic", null, null, "wikantik" ) ) ), "an epic has no product of its own" );
        assertEquals( "productKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.TASK, "Unknown product", null, null, "nope" ) ) ) );
    }

    @Test
    void aParentIsAnItemOfTheSameProjectThatMayHoldTheChild() throws SQLException {
        f.svc.createProject( f.admin, "OPS", "Operations", "" );
        f.svc.putMember( f.admin, "OPS", "mia", "user", WorkRole.MEMBER );
        final Item elsewhere = f.svc.createItem( f.mia, new ItemDraft( "OPS", WorkLevel.EPIC, null, null, null,
            "Ops epic", null, null, null, null, null, null, null ) ).item();
        assertEquals( "parentKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.STORY, "Cross project", null, elsewhere.key(), null ) ) ) );
        assertEquals( "parentKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.STORY, "No such parent", null, "PLAT-9999", null ) ) ) );
        final Item task = f.task();
        assertEquals( "parentKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.STORY, "Under a task", null, task.key(), null ) ) ), "a task holds nothing" );
        assertEquals( "parentKey", field( () -> f.set( f.lena, task, "parentKey", task.key() ) ), "nor itself" );
    }

    @Test
    void anArchivedProductCannotBeTargeted() throws SQLException {
        f.svc.createProduct( f.admin, new Product( 0, "old", "Old", null, "main", null, null, null, null ) );
        f.svc.archiveProduct( f.admin, "old" );
        assertEquals( "productKey", field( () -> f.svc.createItem( f.mia,
            draft( WorkLevel.TASK, "Legacy work", null, null, "old" ) ) ) );
        assertFalse( f.svc.listProducts( f.mia ).stream().anyMatch( p -> "old".equals( p.key() ) && p.archivedAt() == null ) );
    }

    @Test
    void movingStatusAndAssigneeTogetherNeedsANote() throws SQLException {
        final Item t = f.status( f.lena, f.task(), "ready" );
        assertEquals( "note", field( () -> f.svc.updateItem( f.lena, t.key(), new ItemPatch( t.version(),
            Map.of( "status", "review", "assignee", "mia" ), null ) ) ) );
        assertEquals( "mia", f.svc.updateItem( f.lena, t.key(), new ItemPatch( t.version(),
            Map.of( "status", "review", "assignee", "mia" ), "Please look at the diff." ) ).item().assignee() );
    }

    // ---- what a key names

    @Test
    void aMissingMalformedOrForeignKeyIsNotFound() throws SQLException {
        final Item t = f.task();
        for ( final String key : Arrays.asList( null, "", "garbage", "PLAT", "PLAT-", "PLAT-9999", "NOPE-1" ) ) {
            assertThrows( WorkNotFoundException.class, () -> f.svc.item( f.lena, key ), "item " + key );
        }
        assertEquals( t.key(), f.svc.item( f.lena, " " + t.key() + " " ).item().key(), "surrounding space is ignored" );
        assertThrows( WorkNotFoundException.class, () -> f.svc.project( f.lena, null ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.project( f.lena, "NOPE" ) );
    }

    @Test
    void aRemovedCommentCanNoLongerBeEditedOrRemoved() throws SQLException {
        final Item t = f.task();
        final long id = f.svc.addComment( f.mia, t.key(), "First thought." ).id();
        assertThrows( WorkDeniedException.class, () -> f.svc.editComment( f.lena, id, "not mine" ) );
        f.svc.removeComment( f.mia, id );
        assertThrows( WorkNotFoundException.class, () -> f.svc.editComment( f.mia, id, "too late" ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.removeComment( f.mia, id ) );
        assertThrows( WorkNotFoundException.class, () -> f.svc.removeComment( f.mia, 999_999L ) );
    }

    // ---- projects and the registry

    @Test
    void aLeadRenamesTheProjectAndSetsItsDefaultLevel() throws SQLException {
        assertThrows( WorkDeniedException.class, () -> f.svc.updateProject( f.mia, "PLAT", "Mine now", "", null ) );
        f.svc.updateProject( f.lena, "PLAT", " Platform team ", null, null );
        assertEquals( "Platform team", f.svc.project( f.mia, "PLAT" ).name() );
        assertEquals( "", f.svc.project( f.mia, "PLAT" ).description() );
        f.svc.updateProject( f.lena, "PLAT", "Platform team", "What ships", Autonomy.SUPERVISED );
        assertEquals( Autonomy.SUPERVISED, f.svc.project( f.mia, "PLAT" ).defaultAutonomy() );
        assertEquals( "name", field( () -> f.svc.updateProject( f.lena, "PLAT", " ", "", null ) ) );
    }

    @Test
    void queuesAndAgentsAreNamedOnce() throws SQLException {
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> f.svc.registry().createQueue( f.admin, "reviewer", "Again", "" ) ).reason() );
        f.svc.registry().updateQueue( f.admin, "reviewer", " Reviewers ", null );
        assertEquals( "Reviewers", f.svc.registry().listQueues( f.lena ).stream()
            .filter( q -> "reviewer".equals( q.key() ) ).findFirst().orElseThrow().name() );
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> f.svc.registry().registerAgent( f.admin, new AgentDraft( "Author", "", "", List.of() ) ) ).reason(),
            "names are compared without case" );
        assertEquals( WorkConflictException.EXISTS, assertThrows( WorkConflictException.class,
            () -> f.svc.registry().updateAgent( f.admin, f.reviewer.agentId(),
                new AgentDraft( "author", null, null, List.of( "reviewer" ) ) ) ).reason() );
        f.svc.registry().updateAgent( f.admin, f.reviewer.agentId(),
            new AgentDraft( "reviewer-bot", "acme", null, List.of( "reviewer" ) ) );
        assertEquals( "acme", f.svc.registry().listAgents( f.admin ).stream()
            .filter( a -> a.id() == f.reviewer.agentId() ).findFirst().orElseThrow().vendor() );
    }

    @Test
    void anAgentServesQueuesThatExist() throws SQLException {
        assertEquals( "queues", field( () -> f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "ghost", "", "", List.of( "no-such-queue" ) ) ) ) );
        assertEquals( "queues", field( () -> f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "ghost", "", "", Arrays.asList( "reviewer", null ) ) ) ) );
        f.svc.registry().retireQueue( f.admin, "reviewer" );
        assertEquals( "queues", field( () -> f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "ghost", "", "", List.of( "reviewer" ) ) ) ), "a retired queue takes no new agent" );
        f.svc.registry().updateAgent( f.admin, f.reviewer.agentId(),
            new AgentDraft( "reviewer-bot", null, null, List.of( "reviewer", "implementer" ) ) );
        assertTrue( f.svc.registry().listAgents( f.admin ).stream().filter( a -> a.id() == f.reviewer.agentId() )
            .findFirst().orElseThrow().queues().contains( "reviewer" ), "one it already serves is kept" );
        final WorkService.AgentWithToken plain = f.svc.registry().registerAgent( f.admin,
            new AgentDraft( "plain", null, null, null ) );
        assertEquals( List.of(), plain.agent().queues() );
        assertFalse( plain.toString().contains( plain.token() ), "a token never reaches a log through toString" );
    }

    @Test
    void theRegistrySaysWhatStandsBehindEachAgent() throws SQLException {
        final long plain = f.svc.registry().registerAgent( f.lena, new AgentDraft( "plain", "", "", List.of() ) ).agent().id();
        final long retired = f.svc.registry().registerAgent( f.lena, new AgentDraft( "old", "", "", List.of() ) ).agent().id();
        f.svc.registry().retireAgent( f.lena, retired );
        f.svc.registry().releaseSponsorship( f.lena, plain );
        final Map< Long, WorkRegistryService.AgentStatus > status = new java.util.HashMap<>();
        f.svc.registry().agentViews( f.admin ).forEach( v -> status.put( v.agent().id(), v.status() ) );
        assertEquals( WorkRegistryService.AgentStatus.ACTIVE, status.get( f.author.agentId() ) );
        assertEquals( WorkRegistryService.AgentStatus.NO_SPONSOR, status.get( plain ) );
        assertEquals( WorkRegistryService.AgentStatus.RETIRED, status.get( retired ) );
        assertEquals( Optional.empty(), f.svc.registry().self( f.lena ), "a person is no agent" );
        assertEquals( f.author.agentId(), f.svc.registry().self( f.author ).orElseThrow().id() );
        final Set< Long > standing = f.svc.registry().agentStandings( f.lena, "PLAT" ).keySet();
        assertTrue( standing.contains( f.author.agentId() ) && standing.contains( f.reviewer.agentId() ) );
        f.svc.registry().retireAgent( f.admin, f.reviewer.agentId() );
        assertFalse( f.svc.registry().agentStandings( f.lena, "PLAT" ).containsKey( f.reviewer.agentId() ),
            "a retired agent has no standing to report" );
    }

    // ---- construction and the store's defaults

    @Test
    void aServiceBuiltWithNoListenerOrAuditStillWorks() throws SQLException {
        final WorkDirectory directory = new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return null; }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
        };
        final WorkService bare = new WorkService( new JdbcWorkStore( f.ds ), md -> md, directory, Clock.systemUTC(),
            ( java.util.function.Consumer< Action > ) null );
        assertThrows( WorkNotFoundException.class, () -> bare.project( Actor.human( "stranger", Set.of(), false ), "PLAT" ) );
        final WorkService unaudited = new WorkService( new JdbcWorkStore( f.ds ), md -> md, directory, Clock.systemUTC(),
            WorkSettings.DEFAULTS, new WorkListener() { }, null );
        unaudited.registry().createQueue( f.admin, "writer", "Writer", null );
        assertEquals( Set.of(), unaudited.registry().actorForApiKey( 1, "mia" ).groups(), "no groups is an empty set" );
    }

    @Test
    void aStoreThatCannotFlagOrCheckACloseSaysSo() throws SQLException {
        final WorkStore plain = Mockito.mock( WorkStore.class, Mockito.CALLS_REAL_METHODS );
        final Item t = f.task();
        Mockito.doReturn( new WorkStore.ItemWrite( t, null ) ).when( plain ).writeItem( t, t, f.mia, null, false );
        assertEquals( t, plain.writeItem( t, t, f.mia, null, false, false ).item() );
        assertEquals( t, plain.writeItem( t, t, f.mia, null, false, new CloseRules( false, false ) ).item() );
        assertThrows( UnsupportedOperationException.class, () -> plain.writeItem( t, t, f.mia, null, false, true ) );
        assertThrows( UnsupportedOperationException.class,
            () -> plain.writeItem( t, t, f.mia, null, false, new CloseRules( false, true ) ) );
    }

    @Test
    void thePolicyChainOfATaskReachesItsStoryAndEpicAndNoFurther() throws SQLException {
        final JdbcWorkStore store = new JdbcWorkStore( f.ds );
        final Item epic = f.draft( WorkLevel.EPIC, null );
        final Item story = f.draft( WorkLevel.STORY, epic.key() );
        final Item task = f.draft( WorkLevel.TASK, story.key() );
        assertEquals( List.of( story.id(), epic.id() ), store.policyChain( task.id() ).ancestors().stream()
            .map( Item::id ).toList(), "nearest first" );
        assertEquals( List.of(), store.policyChain( epic.id() ).ancestors() );
        assertThrows( SQLException.class, () -> store.policyChain( 999_999L ) );
        assertEquals( Set.of(), store.agentsWithMemberRowIn( List.of() ), "no projects, no query" );
    }

    @Test
    void smallValuesPrintWhatTheyShould() {
        assertEquals( "key", ItemFilter.Order.KEY.wire() );
        assertEquals( "due", ItemFilter.Order.DUE.wire() );
        final String issued = new WorkCredentialStore.Issued( 7, "wka_secret" ).toString();
        assertTrue( issued.contains( "7" ) && !issued.contains( "wka_secret" ), "the token is never printed" );
    }

    @Test
    void aParentLoopInDamagedDataCannotHangTheResolver() throws SQLException {
        final JdbcWorkStore store = new JdbcWorkStore( f.ds );
        final Item epic = f.draft( WorkLevel.EPIC, null );
        final Item story = f.draft( WorkLevel.STORY, epic.key() );
        final Item task = f.draft( WorkLevel.TASK, story.key() );
        // no service call can write this; a restore or a hand-run statement could
        try ( java.sql.Connection c = f.ds.getConnection(); java.sql.Statement st = c.createStatement() ) {
            st.executeUpdate( "UPDATE work_item SET parent_id = " + task.id() + " WHERE id = " + epic.id() );
        }
        assertEquals( 2, store.policyChain( task.id() ).ancestors().size(), "it stops at the depth the hierarchy has" );
    }
}

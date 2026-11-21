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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard that a new {@link WorkService} method cannot skip project visibility: every public method is
 * classified, every read is driven reflectively as a non-member against a real, populated project.
 */
@RequiresPostgres
class WorkServiceVisibilityPostgresTest {

    /** Methods that change state. A new public method fails the classification test until it is listed here or below. */
    private static final Set< String > WRITES = Set.of( "createProject", "updateProject", "archiveProject",
        "putMember", "removeMember", "createProduct", "updateProduct", "archiveProduct", "createItem", "updateItem",
        "addComment", "editComment", "removeComment", "addLink", "removeLink", "createQueue", "updateQueue",
        "retireQueue", "registerAgent", "updateAgent", "rotateCredential", "takeOver", "releaseSponsorship", "retireAgent", "claim", "release", "handoff", "acknowledge", "open", "update",
        "requestReview", "review", "merge", "abandon", "request", "approve", "start", "verify", "fail", "rollBack", "reject" );

    /** Methods that act for no caller: they derive an actor, so they take none. */
    private static final Set< String > SYSTEM = Set.of( "actorForApiKey",
        "agentForToken", "actorForAgent", "describe",
        "sweepExpiredClaims", "sweepStanding", "claimExpiry" );

    /**
     * Methods that return only the caller's own record, never project data: an agent's own agent row. They are not
     * reads in the hiding sense (an agent with no project still gets its own row), so each has its own test.
     */
    private static final Set< String > OWN = Set.of( "self" );

    /**
     * Reads that answer yes or no, so "returned real data" has no meaning for them: each is driven as a stranger
     * by its own test ({@code WorkDeploymentAccessPostgresTest.aNonMemberGetsNotFound} for {@code cleared}).
     */
    private static final Set< String > YES_OR_NO = Set.of( "cleared" );

    /**
     * Reads that are wiki-admin only ({@code MANAGE_INSTANCE}): they are not about a project, so a stranger is refused
     * rather than hidden from it. Each is driven as a person with no standing and must refuse.
     */
    private static final Set< String > INSTANCE_READS = Set.of( "productHistory" );

    /** Accessors that hand out a sub-facade; its public methods are classified and driven like the service's own. */
    private static final Set< String > FACADES = Set.of( "registry", "claims", "changes", "deployments", "features" );

    /** Methods that only read. */
    private static final Set< String > READS = Set.of( "listProjects", "project", "members", "roleIn", "listProducts",
        "item", "items", "board", "itemSummaries", "boardSummaries", "revisions", "events", "activity", "waitingOn",
        "listProjectsWithRoles", "listQueues", "listAgents", "agentViews", "agentStandings", "claimable", "myClaims", "assignmentLog", "policyOf", "list", "counts", "views", "view", "deployment",
        "forProduct", "history", "awaitingMyApproval", "shippedIn", "gate", "labelsOf" );

    private static final List< String > STRING_FIXTURES = List.of( "PLAT", "PLAT-1", "implementer", "web" );

    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private Actor rogueAgent;
    private Actor flaggedAgent;
    private Actor codexActor;
    /** The member agent itself (it holds a claim in PLAT) under a sponsor who is not a member, and under none. */
    private Actor codexUnderAStranger;
    private Actor codexWithNoSponsor;

    private WorkService service;
    private long commentId;
    private long linkId;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        final javax.sql.DataSource ds = PostgresTestDb.createDataSource();
        final JdbcWorkStore store = new JdbcWorkStore( ds );
        store.createQueue( "implementer", "Implementer", "" );
        final long codex = WorkAgentFixtures.agent( ds, "codex@docker2" );
        rogueAgent = WorkAgentFixtures.sponsored( WorkAgentFixtures.agent( ds, "rogue@x" ), "rogue@x" );
        flaggedAgent = new Actor( "flagged@x", Set.of(), true, Actor.Kind.AGENT,
            WorkAgentFixtures.agent( ds, "flagged@x" ), null );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_agent_queue (agent_id, queue_key) VALUES (" + codex
            + ", 'implementer')" );
        final Project plat = store.createProject( "PLAT", "Platform", "secret roadmap" );
        codexActor = WorkAgentFixtures.sponsored( codex, "codex@docker2" );
        codexUnderAStranger = Actor.agent( codex, "codex@docker2", Actor.human( "mallory", Set.of(), false ) );
        codexWithNoSponsor = Actor.agent( codex, "codex@docker2" );
        store.putMember( plat.id(), String.valueOf( codex ), "agent", WorkRole.MEMBER,
            Actor.human( "admin", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "lena", "user", WorkRole.LEAD, Actor.human( "admin", java.util.Set.of(), false ) );
        final Product web = store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final Item item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "secret item" ), Actor.human( "lena", java.util.Set.of(), false ) );
        // lena is also the assignee so that waitingOn is non-empty for her
        final Item assigned = new Item( item.id(), item.projectId(), item.key(), item.level(), null, web.id(),
            item.kind(), item.title(), item.body(), item.status(), item.priority(), item.rank(), null, "lena",
            item.reporter(), null, null, null, item.version(), item.createdAt(), item.updatedAt(), null, null, null, null, null, null,
            WorkType.FEATURE, List.of( "secret" ) );
        store.updateItem( item, assigned, Actor.human( "lena", java.util.Set.of(), false ), null );
        // PLAT-2 is claimable, PLAT-3 is held by codex: claimable and myClaims return real data to prove against
        store.createItem( plat, queued( "claimable item" ), lena );
        final Item held = store.createItem( plat, queued( "held item" ), lena );
        store.claims().claim( held, codexActor, java.time.Duration.ofHours( 1 ) );
        // a live change on PLAT-1, so the change reads return real data to prove against
        store.changes().open( item, web.id(), new ChangeDraft( "work/PLAT-1-secret", "main", "abc1234", null, null ),
            lena );
        // a deployment of "web" carrying PLAT-1, so the deployment reads return real data to prove against
        store.deployments().request( web, "production", "1.0", List.of( item ), lena );
        final long shipped = store.deployments().request( web, "production", "0.9", List.of( item ), lena ).id();
        store.deployments().move( shipped, DeploymentState.APPROVED, null, null, lena );
        store.deployments().move( shipped, DeploymentState.RUNNING, null, null, lena );
        store.deployments().move( shipped, DeploymentState.VERIFIED, "ok", null, lena );
        commentId = store.addComment( item, Actor.human( "lena", java.util.Set.of(), false ), "secret comment", List.of() ).id();
        linkId = store.addLink( item, "url", "https://example.com/secret", Actor.human( "lena", java.util.Set.of(), false ) ).id();
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return true; }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC() );
    }

    private static Item queued( final String title ) {
        return new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title, "body", WorkStatus.READY, 3,
            0, null, null, null, null, null, null, 0, null, null, null, "implementer", null, null, null, null, null, null );
    }

    private List< Actor > strangers() {
        return List.of( mallory, rogueAgent, flaggedAgent, codexUnderAStranger, codexWithNoSponsor );
    }

    /** A public method and the object to call it on: the service itself, or the facade an accessor returns. */
    private record Op( Method method, Object target ) {
        String name() {
            return method.getName();
        }
    }

    private static boolean isOwnPublic( final Method m ) {
        return Modifier.isPublic( m.getModifiers() ) && !m.isSynthetic();
    }

    private List< Op > ops() throws Exception {
        final List< Op > out = new ArrayList<>();
        for ( final Method m : WorkService.class.getDeclaredMethods() ) {
            if ( isOwnPublic( m ) && !FACADES.contains( m.getName() ) ) {
                out.add( new Op( m, service ) );
            }
        }
        for ( final String accessor : FACADES ) {
            final Object facade = WorkService.class.getMethod( accessor ).invoke( service );
            for ( final Method m : facade.getClass().getDeclaredMethods() ) {
                if ( isOwnPublic( m ) ) {
                    out.add( new Op( m, facade ) );
                }
            }
        }
        return out;
    }

    @Test
    void everySubFacadeIsListedSoItCannotEscapeClassification() {
        for ( final Method m : WorkService.class.getDeclaredMethods() ) {
            final boolean facade = isOwnPublic( m ) && m.getParameterCount() == 0
                && m.getReturnType().getName().matches( "com\\.wikantik\\.work\\.\\w*Service" );
            if ( facade ) {
                assertTrue( FACADES.contains( m.getName() ), m.getName() + " returns a service facade: add it to FACADES" );
            }
        }
        for ( final String accessor : FACADES ) {
            assertDoesNotThrow( () -> WorkService.class.getMethod( accessor ), accessor + " is not a WorkService method" );
        }
    }

    @Test
    void everyPublicMethodIsClassifiedAsReadOrWrite() throws Exception {
        final Set< String > all = new TreeSet<>();
        ops().forEach( op -> all.add( op.name() ) );
        assertTrue( Collections.disjoint( READS, WRITES ), "a method cannot be both a read and a write" );
        assertTrue( Collections.disjoint( SYSTEM, READS ) && Collections.disjoint( SYSTEM, WRITES ) );
        assertTrue( Collections.disjoint( OWN, READS ) && Collections.disjoint( OWN, WRITES )
            && Collections.disjoint( OWN, SYSTEM ) );
        final Set< String > classified = new TreeSet<>( READS );
        classified.addAll( OWN );
        classified.addAll( YES_OR_NO );
        classified.addAll( INSTANCE_READS );
        classified.addAll( WRITES );
        classified.addAll( SYSTEM );
        assertEquals( classified, all, "classify every public WorkService method as a read, a write or system" );
    }

    /** Argument lists for a read: Actor first, then every combination of the String fixtures. */
    private static List< Object[] > argumentSets( final Method m, final Actor actor ) {
        List< Object[] > sets = new ArrayList<>();
        sets.add( new Object[ m.getParameterCount() ] );
        for ( int i = 0; i < m.getParameterCount(); i++ ) {
            final Class< ? > type = m.getParameterTypes()[ i ];
            final List< Object > options = type == Actor.class ? List.of( actor )
                : type == String.class ? new ArrayList<>( STRING_FIXTURES )
                : type == int.class ? List.of( 10 )
                // a numeric id: the one change of the fixture (ids restart with each truncate) and one that is not there
                : type == long.class ? List.of( 1L, 999_999L )
                : type == AssignmentLogQuery.class ? List.of( AssignmentLogQuery.all() )
                : Arrays.asList( new Object[] { null } );
            final List< Object[] > next = new ArrayList<>();
            for ( final Object[] prefix : sets ) {
                for ( final Object option : options ) {
                    final Object[] copy = prefix.clone();
                    copy[ i ] = option;
                    next.add( copy );
                }
            }
            sets = next;
        }
        return sets;
    }

    private static Object invoke( final Method m, final Object target, final Object[] args ) throws Exception {
        try {
            return m.invoke( target, args );
        } catch ( final InvocationTargetException e ) {
            if ( e.getCause() instanceof Exception cause ) {
                throw cause;
            }
            throw e;
        }
    }

    private static boolean isEmptyResult( final Object result ) {
        return result instanceof Collection< ? > c && c.isEmpty();
    }

    @Test
    void everyReadMethodHidesInvisibleProject() throws Exception {
        for ( final Op op : ops() ) {
            final Method m = op.method();
            if ( !READS.contains( m.getName() ) ) {
                continue;
            }
            int provedAgainstRealData = 0;
            for ( final Actor proving : List.of( lena, codexActor ) ) {
                for ( final Object[] asLead : argumentSets( m, proving ) ) {
                    final Object seen;
                    try {
                        seen = invoke( m, op.target(), asLead );
                    } catch ( final WorkNotFoundException e ) {
                        continue;  // this fixture combination does not address the project; try the next
                    }
                    if ( seen == null || isEmptyResult( seen ) ) {
                        continue;
                    }
                    provedAgainstRealData++;
                    for ( final Actor stranger : strangers() ) {
                        final Object[] asStranger = asLead.clone();
                        for ( int i = 0; i < asStranger.length; i++ ) {
                            if ( asStranger[ i ] instanceof Actor ) {
                                asStranger[ i ] = stranger;
                            }
                        }
                        try {
                            final Object result = invoke( m, op.target(), asStranger );
                            assertTrue( isEmptyResult( result ), m.getName() + " leaked to " + stranger.login() + ": "
                                + result );
                        } catch ( final WorkNotFoundException expected ) {
                            // the required outcome
                        }
                    }
                }
            }
            assertTrue( provedAgainstRealData > 0,
                m.getName() + " never returned real data to the project lead, so it proved nothing" );
        }
    }

    @Test
    void anInstanceReadRefusesAnyoneWhoIsNotAWikiAdmin() throws Exception {
        for ( final Op op : ops() ) {
            if ( !INSTANCE_READS.contains( op.name() ) ) {
                continue;
            }
            final List< Actor > nonAdmins = new ArrayList<>( strangers() );
            nonAdmins.add( lena );
            for ( final Actor who : nonAdmins.stream().filter( a -> !a.admin() ).toList() ) {
                for ( final Object[] args : argumentSets( op.method(), who ) ) {
                    assertThrows( WorkDeniedException.class, () -> invoke( op.method(), op.target(), args ),
                        op.name() + " answered " + who.login() );
                }
            }
            assertDoesNotThrow( () -> invoke( op.method(), op.target(), new Object[] { admin, "web" } ) );
        }
    }

    @Test
    void everyProjectScopedWriteHidesInvisibleProject() {
        final ItemDraft draft = new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "", null,
            null, null, null, null, null );
        final ItemPatch patch = new ItemPatch( 1, java.util.Map.of( "title", "x" ), null );
        final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
            () -> service.updateProject( mallory, "PLAT", "n", "d", null ),
            () -> service.archiveProject( mallory, "PLAT" ),
            () -> service.putMember( mallory, "PLAT", "mallory", "user", WorkRole.LEAD ),
            () -> service.removeMember( mallory, "PLAT", "lena", "user" ),
            () -> service.createItem( mallory, draft ),
            () -> service.updateItem( mallory, "PLAT-1", patch ),
            () -> service.addComment( mallory, "PLAT-1", "hi" ),
            () -> service.editComment( mallory, commentId, "x" ),
            () -> service.removeComment( mallory, commentId ),
            () -> service.addLink( mallory, "PLAT-1", "url", "https://a.example" ),
            () -> service.removeLink( mallory, linkId ) );
        for ( final var attempt : attempts ) {
            assertThrows( WorkNotFoundException.class, attempt );
        }
        final WorkClaimsService.Handoff handoff = new WorkClaimsService.Handoff( 1, null, null, "implementer", "n" );
        for ( final Actor stranger : strangers() ) {
            assertThrows( WorkNotFoundException.class, () -> service.claims().claim( stranger, "PLAT-2" ) );
            assertThrows( WorkNotFoundException.class, () -> service.claims().release( stranger, "PLAT-3", "x" ) );
            assertThrows( WorkNotFoundException.class, () -> service.claims().handoff( stranger, "PLAT-2", handoff ) );
        }
    }

    @Test
    void systemMethodsTakeNoActor() throws Exception {
        for ( final Op op : ops() ) {
            final Method m = op.method();
            if ( SYSTEM.contains( m.getName() ) ) {
                assertTrue( Arrays.stream( m.getParameterTypes() ).noneMatch( t -> t == Actor.class ),
                    m.getName() + " must not accept a caller-supplied actor" );
            }
        }
    }

    /** Queues are instance-wide: only a wiki admin changes them. */
    @Test
    void everyQueueWriteRequiresAdmin() {
        final WorkRegistryService r = service.registry();
        for ( final Actor caller : strangers() ) {
            final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
                () -> r.createQueue( caller, "ops", "Ops", "" ),
                () -> r.updateQueue( caller, "implementer", "Impl", "" ),
                () -> r.retireQueue( caller, "implementer" ) );
            for ( final var attempt : attempts ) {
                assertThrows( WorkDeniedException.class, attempt, caller.login() );
            }
        }
    }

    /**
     * A stranger registers nothing, and an agent it cannot see does not exist for it: every write on one is the
     * same not-found as for an id that names nothing. (An agent caller is refused outright: agents never manage
     * agents.)
     */
    @Test
    void aStrangerCannotRegisterOrTouchAnAgent() throws SQLException {
        final AgentDraft agent = new AgentDraft( "new@x", "", "", List.of() );
        final WorkRegistryService r = service.registry();
        final long rogue = rogueAgent.agentId();
        for ( final Actor caller : List.of( mallory, flaggedAgent, codexUnderAStranger, codexWithNoSponsor ) ) {
            assertThrows( WorkDeniedException.class, () -> r.registerAgent( caller, agent ), caller.login() );
            final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
                () -> r.updateAgent( caller, rogue, agent ),
                () -> r.rotateCredential( caller, rogue ),
                () -> r.takeOver( caller, rogue ),
                () -> r.releaseSponsorship( caller, rogue ),
                () -> r.retireAgent( caller, rogue ) );
            for ( final var attempt : attempts ) {
                assertEquals( assertThrows( WorkNotFoundException.class, () -> r.retireAgent( caller, 999_999 ) )
                    .getMessage().replace( "999999", String.valueOf( rogue ) ),
                    assertThrows( WorkNotFoundException.class, attempt, caller.login() ).getMessage() );
            }
        }
        assertEquals( 0, service.registry().listAgents( mallory ).size() );
    }


    @Test
    void selfIsTheCallingAgentsOwnRecordOnly() throws Exception {
        final Agent own = service.registry().self( rogueAgent ).orElseThrow();   // admitted to no project at all
        assertEquals( "rogue@x", own.name() );
        assertEquals( rogueAgent.agentId(), own.id() );
        final Agent codex = service.registry().self( codexActor ).orElseThrow();
        assertEquals( List.of( "implementer" ), codex.queues(), "positive control: a real record with its queues" );
        assertTrue( service.registry().self( mallory ).isEmpty(), "a person has no agent record" );
        assertTrue( service.registry().self( lena ).isEmpty() );
        assertTrue( service.registry().self( admin ).isEmpty() );
        assertTrue( service.registry().self( WorkAgentFixtures.sponsored( 999_999, "ghost" ) ).isEmpty() );
    }

    @Test
    void claimExpiryIsTheConfiguredSettingAndTakesNoActor() {
        assertEquals( WorkSettings.DEFAULTS.claimExpiry(), service.claims().claimExpiry() );
    }

    @Test
    void theFixtureIsVisibleToItsLead() throws Exception {
        assertEquals( 1, service.listProjects( lena ).size() );
        assertFalse( service.waitingOn( lena ).isEmpty() );
        assertTrue( service.listProjects( mallory ).isEmpty() );
        assertTrue( service.listProducts( mallory ).isEmpty() );
        assertTrue( new HashSet<>( service.waitingOn( mallory ) ).isEmpty() );
    }
}

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
        "requestReview", "review", "merge", "abandon", "request", "approve", "start", "verify", "fail", "rollBack", "reject",
        "create", "close", "markRead", "setWatch", "add", "remove",
        "setDefaultProduct", "save", "rename", "delete", "upload", "enableHook", "disableHook", "acknowledgeFlag", "comment" );

    /** Methods that act for no caller: they derive an actor, so they take none. */
    private static final Set< String > SYSTEM = Set.of( "actorForApiKey",
        "agentForToken", "actorForAgent", "describe",
        "sweepExpiredClaims", "sweepStanding", "claimExpiry", "dispatch", "prune", "sendEmail", "settings", "deliver", "maxFileBytes",
        "authenticate", "apply", "rejected" );

    /**
     * Methods that touch only the caller's own record, or the caller's own notifications and settings, never project
     * data: an agent's own agent row, a person's inbox (filtered to the projects they see). They are not
     * reads in the hiding sense (an agent with no project still gets its own row), so each has its own test.
     */
    private static final Set< String > OWN = Set.of( "self", "inbox", "unread", "markAllRead", "preference", "setPreference" );

    /**
     * Where each {@link #OWN} method is driven, as {@code Class#method}: the claim "each has its own test" is checked
     * ({@code everyOwnMethodPointsAtATestThatExists}), so it cannot go stale. The notification ones are driven as a
     * person who only reports (Part 8, Task 8.2c).
     */
    private static final java.util.Map< String, String > OWN_TESTS = java.util.Map.of(
        "self", "WorkServiceVisibilityPostgresTest#selfIsTheCallingAgentsOwnRecordOnly",
        "inbox", "WorkReporterSurfacesPostgresTest#aDemotedMembersOldNoticesShowOnlyAsAReporterMay",
        "unread", "WorkReporterSurfacesPostgresTest#aDemotedMembersOldNoticesShowOnlyAsAReporterMay",
        "markAllRead", "WorkReporterSurfacesPostgresTest#aReporterReadsTheirWatchStateAndClearsTheirUnread",
        "preference", "WorkReporterSurfacesPostgresTest#aReportersOwnPreferenceIsHers",
        "setPreference", "WorkReporterSurfacesPostgresTest#aReportersOwnPreferenceIsHers" );

    /**
     * Surfaces a person who only reports may use on the item they filed (Task 8.2c, rulings 3 and 4): the reflective
     * reporter loop drives them with every argument set except the reporter's own item, and the own-item case is driven
     * by the named test.
     */
    private static final java.util.Map< String, String > OWN_ITEM_TESTS = java.util.Map.of(
        "WorkNotificationsService.markRead", "WorkReporterSurfacesPostgresTest#aReporterReadsTheirWatchStateAndClearsTheirUnread",
        "WorkNotificationsService.watchState", "WorkReporterSurfacesPostgresTest#aReporterReadsTheirWatchStateAndClearsTheirUnread",
        "WorkNotificationsService.setWatch", "WorkReporterSurfacesPostgresTest#aReporterWatchesAndMarksReadOnlyTheirOwnItem",
        "WorkAttachmentsService.upload", "WorkReporterSurfacesPostgresTest#aReporterAttachesToAndReadsTheirOwnItem",
        "WorkAttachmentsService.list", "WorkReporterSurfacesPostgresTest#aReporterAttachesToAndReadsTheirOwnItem",
        "WorkAttachmentsService.content", "WorkReporterSurfacesPostgresTest#aReporterAttachesToAndReadsTheirOwnItem",
        "WorkAttachmentsService.mayAttach", "WorkReporterSurfacesPostgresTest#aReporterAttachesToAndReadsTheirOwnItem",
        "WorkAttachmentsService.listing", "WorkReporterSurfacesPostgresTest#aReporterSeesTheListingWithMayAttachTrue",
        "WorkAttachmentsService.remove", "WorkReporterSurfacesPostgresTest#aReporterRemovesOnlyTheirOwnUpload" );

    /**
     * What intake lets a person do for themselves: file a draft into a project they hold any row in, list what they
     * can file into and what they filed. Strangers are not "refused" these: rita and gus legitimately file into PLAT, so
     * the reflective write loop cannot treat {@code submit} as a write (ruling 10). Each has its own test.
     */
    private static final Set< String > INTAKE_OWN = Set.of( "reportableProjects", "reported", "submit" );

    /**
     * Reads that answer yes or no, so "returned real data" has no meaning for them: each is driven as a stranger
     * by its own test ({@code WorkDeploymentAccessPostgresTest.aNonMemberGetsNotFound} for {@code cleared}).
     */
    private static final Set< String > YES_OR_NO = Set.of( "cleared" );

    /**
     * Reads that are wiki-admin only ({@code MANAGE_INSTANCE}): they are not about a project, so a stranger is refused
     * rather than hidden from it. Each is driven as a person with no standing and must refuse.
     */
    private static final Set< String > INSTANCE_READS = Set.of( "productHistory", "hooks", "doraForProduct" );

    /**
     * Accessors that hand out a sub-facade; its public methods are classified and driven like the service's own. A
     * facade's own accessors that return a service ({@code features().sprints()}) are walked the same way.
     */
    private static final Set< String > FACADES = Set.of( "registry", "claims", "changes", "deployments", "features" );

    /** Methods that only read. */
    private static final Set< String > READS = Set.of( "listProjects", "project", "members", "roleIn", "listProducts",
        "item", "items", "board", "itemSummaries", "boardSummaries", "revisions", "events", "activity", "waitingOn",
        "listProjectsWithRoles", "listQueues", "listAgents", "agentViews", "agentStandings", "claimable", "myClaims", "assignmentLog", "policyOf", "list", "counts", "views", "view", "deployment",
        "forProduct", "history", "awaitingMyApproval", "shippedIn", "gate", "labelsOf", "get", "velocity", "childPoints", "report", "watchState", "find", "saved", "content", "download", "mayAttach", "listing", "dora", "outsideReporters", "groupSuggestions" );

    /** A download sink that keeps nothing, for the reflective drivers. */
    private static final WorkAttachmentsService.Download DISCARD = new WorkAttachmentsService.Download() {
        @Override public void begin( final Attachment attachment ) { }
        @Override public void chunk( final byte[] buffer, final int length ) { }
    };

    private static final List< String > STRING_FIXTURES = List.of( "PLAT", "PLAT-1", "implementer", "web" );

    /** As above plus the two reporters' own items, so a reporter is driven against the very item she may follow. */
    private static final List< String > REPORTER_FIXTURES = List.of( "PLAT", "PLAT-1", "PLAT-4", "PLAT-5", "implementer", "web" );

    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor admin = Actor.human( "admin", Set.of(), true );
    /** A reporter by name, and one by the Customers group; each filed one item (PLAT-4 and PLAT-5). */
    private final Actor rita = Actor.human( "rita", Set.of(), false );
    private final Actor gus = Actor.human( "gus", Set.of( "Customers" ), false );
    private Actor rogueAgent;
    private Actor flaggedAgent;
    private Actor codexActor;
    /** The member agent itself (it holds a claim in PLAT) under a sponsor who is not a member, and under none. */
    private Actor codexUnderAStranger;
    private Actor codexWithNoSponsor;
    /** The member agent itself, under a sponsor whose only standing is the reporter row. */
    private Actor codexUnderAReporter;

    private WorkService service;
    private long commentId;
    private long linkId;
    private long filterId;

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
        codexUnderAReporter = Actor.agent( codex, "codex@docker2", rita );
        store.putMember( plat.id(), String.valueOf( codex ), "agent", WorkRole.MEMBER,
            Actor.human( "admin", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "lena", "user", WorkRole.LEAD, Actor.human( "admin", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "rita", "user", WorkRole.REPORTER, Actor.human( "admin", java.util.Set.of(), false ) );
        store.putMember( plat.id(), "Customers", "group", WorkRole.REPORTER, Actor.human( "admin", java.util.Set.of(), false ) );
        final Product web = store.createProduct( new Product( 0, "web", "Web", null, null, null, Autonomy.DELEGATED,
            DeployGate.HUMAN, null ) );
        final Item item = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "secret item" ), Actor.human( "lena", java.util.Set.of(), false ) );
        // a sprint holding PLAT-1, started and closed, so list, get and velocity return real data to prove against
        final java.time.LocalDate today = java.time.LocalDate.now();
        final Sprint sprint = store.sprints().create( plat, new SprintDraft( "S1", "", today, today.plusDays( 13 ) ), lena );
        // lena is also the assignee so that waitingOn is non-empty for her
        final Item assigned = new Item( item.id(), item.projectId(), item.key(), item.level(), null, web.id(),
            item.kind(), item.title(), item.body(), item.status(), item.priority(), item.rank(), null, "lena",
            item.reporter(), null, null, null, item.version(), item.createdAt(), item.updatedAt(), null, null, null, null, null, null,
            WorkType.FEATURE, List.of( "secret" ), sprint.id(), new java.math.BigDecimal( "3.0" ) );
        store.updateItem( item, assigned, Actor.human( "lena", java.util.Set.of(), false ), null );
        store.sprints().start( sprint.id(), sprint.version(), lena );
        store.sprints().close( sprint.id(), sprint.version() + 1, null, lena );
        // PLAT-2 is claimable, PLAT-3 is held by codex: claimable and myClaims return real data to prove against
        store.createItem( plat, queued( "claimable item" ), lena );
        final Item held = store.createItem( plat, queued( "held item" ), lena );
        store.claims().claim( held, codexActor, java.time.Duration.ofHours( 1 ) );
        // the two reporters' own items: PLAT-4 is rita's, PLAT-5 is gus's
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "rita's report" ), rita );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "gus's report" ), gus );
        // a live change on PLAT-1, so the change reads return real data to prove against
        store.changes().open( item, web.id(), new ChangeDraft( "work/PLAT-1-secret", "main", "abc1234", null, null ),
            lena );
        // a deployment of "web" carrying PLAT-1, so the deployment reads return real data to prove against
        store.deployments().request( web, "production", "1.0", List.of( item ), lena );
        final long shipped = store.deployments().request( web, "production", "0.9", List.of( item ), lena ).id();
        store.deployments().move( shipped, DeploymentState.APPROVED, null, null, lena );
        store.deployments().move( shipped, DeploymentState.RUNNING, null, null, lena );
        store.deployments().move( shipped, DeploymentState.VERIFIED, "ok", null, lena );
        filterId = store.notifyStores().filters().insert( "lena", "Team view", plat.id(), true,
            new java.util.TreeMap<>( java.util.Map.of( "projects", "PLAT" ) ) ).id();
        // an attachment on PLAT-1 (id 1), so list and content return real data to prove against
        store.notifyStores().attachments().add( item, null,
            new WorkAttachmentStore.NewAttachment( "secret.png", "image/png", PNG, "0000000000000000000000000000000000000000000000000000000000000000" ), Long.MAX_VALUE, lena );
        commentId = store.addComment( item, Actor.human( "lena", java.util.Set.of(), false ), "secret comment", List.of() ).id();
        linkId = store.addLink( item, "url", "https://example.com/secret", Actor.human( "lena", java.util.Set.of(), false ) ).id();
        // a webhook on PLAT, so the webhook list returns real data to prove against
        store.notifyStores().webhooks().add( plat, java.net.URI.create( "https://hooks.example.com/secret" ),
            WebhookFormat.GENERIC, "s3cret", List.of(), lena );
        service = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return "gus".equals( login ) ? Set.of( "Customers" ) : Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public Set< String > groupNames() { return Set.of( "web-team", "Customers" ); }
            @Override public String displayName( final String login ) { return login; }
        }, Clock.systemUTC() );
    }

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1 };

    private static Item queued( final String title ) {
        return new Item( 0, 0, null, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title, "body", WorkStatus.READY, 3,
            0, null, null, null, null, null, null, 0, null, null, null, "implementer", null, null, null, null, null, null, null, null );
    }

    private List< Actor > strangers() {
        return List.of( mallory, rogueAgent, flaggedAgent, codexUnderAStranger, codexWithNoSponsor, Actor.system( "github" ),
            rita, gus, codexUnderAReporter );
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
            addFacade( out, WorkService.class.getMethod( accessor ).invoke( service ) );
        }
        return out;
    }

    private static boolean isServiceAccessor( final Method m ) {
        return isOwnPublic( m ) && m.getParameterCount() == 0
            && m.getReturnType().getName().matches( "com\\.wikantik\\.work\\.\\w*Service" );
    }

    /** The facade's public methods; an accessor returning a service is walked into, not classified. */
    private static void addFacade( final List< Op > out, final Object facade ) throws Exception {
        for ( final Method m : facade.getClass().getDeclaredMethods() ) {
            if ( isServiceAccessor( m ) ) {
                addFacade( out, m.invoke( facade ) );
            } else if ( isOwnPublic( m ) ) {
                out.add( new Op( m, facade ) );
            }
        }
    }

    @Test
    void everySubFacadeIsListedSoItCannotEscapeClassification() throws Exception {
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
        assertTrue( ops().stream().anyMatch( op -> op.target() instanceof WorkSprintsService ),
            "the sprint service, reached through features(), is walked" );
        assertTrue( ops().stream().anyMatch( op -> op.target() instanceof WorkFlowService ),
            "the flow service, reached through features(), is walked" );
        assertTrue( ops().stream().anyMatch( op -> op.target() instanceof WorkIntakeService ),
            "the intake service, reached through features(), is walked" );
    }

    @Test
    void everyPublicMethodIsClassifiedAsReadOrWrite() throws Exception {
        final Set< String > all = new TreeSet<>();
        ops().forEach( op -> all.add( op.name() ) );
        assertTrue( Collections.disjoint( READS, WRITES ), "a method cannot be both a read and a write" );
        assertTrue( Collections.disjoint( SYSTEM, READS ) && Collections.disjoint( SYSTEM, WRITES ) );
        assertTrue( Collections.disjoint( OWN, READS ) && Collections.disjoint( OWN, WRITES )
            && Collections.disjoint( OWN, SYSTEM ) );
        assertTrue( Collections.disjoint( INTAKE_OWN, READS ) && Collections.disjoint( INTAKE_OWN, WRITES )
            && Collections.disjoint( INTAKE_OWN, SYSTEM ) && Collections.disjoint( INTAKE_OWN, OWN )
            && Collections.disjoint( INTAKE_OWN, INSTANCE_READS ) && Collections.disjoint( INTAKE_OWN, YES_OR_NO ),
            "submit is a filing, not a write strangers are refused: it must not be in WRITES" );
        final Set< String > classified = new TreeSet<>( READS );
        classified.addAll( OWN );
        classified.addAll( INTAKE_OWN );
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
                : type == FindFilter.class ? List.of( FindFilter.all() )
                : type == Collection.class ? List.of( List.of( "rita", "lena" ) )
                : type == WorkAttachmentsService.Download.class ? List.of( DISCARD )
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
        return result instanceof Collection< ? > c && c.isEmpty() || result instanceof FindPage p && p.items().isEmpty();
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
                    } catch ( final WorkDeniedException e ) {
                        // a management read (group suggestions) is refused to an agent even in its own project; the
                        // lead proves it against real data
                        continue;
                    }
                    // a void read (a streamed download) proves itself by not being refused
                    if ( seen == null && m.getReturnType() != void.class || isEmptyResult( seen ) ) {
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
            // the admin positive control: the admin and "web" first, null for any other parameter
            final Object[] control = new Object[ op.method().getParameterCount() ];
            control[ 0 ] = admin;
            if ( control.length > 1 ) {
                control[ 1 ] = "web";
            }
            assertDoesNotThrow( () -> invoke( op.method(), op.target(), control ) );
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
            () -> service.removeLink( mallory, linkId ),
            () -> service.features().setDefaultProduct( mallory, "PLAT", "web" ),
            () -> service.features().notifications().markRead( mallory, "PLAT-1" ),
            () -> service.features().notifications().setWatch( mallory, "PLAT-1", WatchState.WATCHING ),
            () -> service.features().webhooks().add( mallory, "PLAT", new WebhookDraft( "https://a.example.com/x", "generic", List.of() ) ),
            () -> service.features().webhooks().update( mallory, "PLAT", 1, new WebhookPatch( null, null, false ) ),
            () -> service.features().webhooks().remove( mallory, "PLAT", 1 ),
            () -> service.features().attachments().upload( mallory, "PLAT-1", null, "x.png", PNG ),
            () -> service.features().attachments().remove( mallory, "PLAT-1", 1L ) );
        for ( final var attempt : attempts ) {
            assertThrows( WorkNotFoundException.class, attempt );
        }
        final WorkIntakeService intake = service.features().intake();
        final long ritasComment = assertDoesNotThrow( () -> intake.comment( rita, "PLAT-4", "my own words" ).id() );
        for ( final Actor reporter : List.of( rita, gus ) ) {
            final List< org.junit.jupiter.api.function.Executable > asAReporter = List.of(
                () -> service.createItem( reporter, draft ),
                () -> service.updateItem( reporter, "PLAT-4", patch ),
                () -> service.updateItem( reporter, "PLAT-5", patch ),
                () -> service.addComment( reporter, "PLAT-4", "hi" ),
                () -> service.addComment( reporter, "PLAT-5", "hi" ),
                () -> service.addLink( reporter, "PLAT-4", "url", "https://a.example" ),
                () -> service.editComment( reporter, commentId, "x" ),
                () -> service.editComment( reporter, ritasComment, "x" ),
                () -> service.removeComment( reporter, commentId ),
                () -> service.removeComment( reporter, ritasComment ),
                () -> service.removeLink( reporter, linkId ),
                () -> intake.comment( reporter, "PLAT-1", "hi" ),
                () -> intake.comment( reporter, "PLAT-2", "hi" ) );
            for ( final var attempt : asAReporter ) {
                assertThrows( WorkNotFoundException.class, attempt, reporter.login() );
            }
        }
        assertThrows( WorkNotFoundException.class, () -> intake.comment( rita, "PLAT-5", "gus's item" ) );
        assertThrows( WorkNotFoundException.class, () -> intake.comment( gus, "PLAT-4", "rita's item" ) );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( mallory, new IntakeDraft( "PLAT", null, null, "t", "" ) ) );
        for ( final Actor stranger : strangers().stream().filter( a -> a != rita && a != gus ).toList() ) {
            assertThrows( WorkNotFoundException.class,
                () -> intake.submit( stranger, new IntakeDraft( "PLAT", null, null, "t", "" ) ), stranger.login() );
            assertThrows( WorkNotFoundException.class, () -> intake.comment( stranger, "PLAT-1", "hi" ), stranger.login() );
        }
        for ( final Actor stranger : strangers() ) {
            assertThrows( WorkNotFoundException.class,
                () -> service.features().attachments().upload( stranger, "PLAT-1", null, "x.png", PNG ) );
            assertThrows( WorkNotFoundException.class,
                () -> service.features().attachments().remove( stranger, "PLAT-1", 1L ) );
        }
        final WorkSprintsService sprints = service.features().sprints();
        final SprintDraft sprintDraft = new SprintDraft( "S2", "", java.time.LocalDate.now(),
            java.time.LocalDate.now().plusDays( 13 ) );
        final List< org.junit.jupiter.api.function.Executable > sprintAttempts = List.of(
            () -> sprints.create( mallory, "PLAT", sprintDraft ), () -> sprints.update( mallory, 1, 1, sprintDraft ),
            () -> sprints.start( mallory, 1, 1 ), () -> sprints.close( mallory, 1, 1, null ) );
        for ( final var attempt : sprintAttempts ) {
            assertThrows( WorkNotFoundException.class, attempt );
        }
        for ( final Actor stranger : strangers() ) {
            final var find = service.features().find();
            final List< org.junit.jupiter.api.function.Executable > filterWrites = List.of(
                () -> find.save( stranger, new SavedFilterDraft( "x", "PLAT", true, java.util.Map.of() ) ),
                () -> find.save( stranger, new SavedFilterDraft( "x", "PLAT", false, java.util.Map.of() ) ),
                () -> find.rename( stranger, filterId, "y" ), () -> find.delete( stranger, filterId ) );
            final Class< ? extends Exception > expected = stranger.kind() == Actor.Kind.AGENT
                ? WorkDeniedException.class : WorkNotFoundException.class;
            for ( final var attempt : filterWrites ) {
                assertThrows( expected, attempt, stranger.login() );
            }
        }
        final WorkClaimsService.Handoff handoff = new WorkClaimsService.Handoff( 1, null, null, "implementer", "n" );
        for ( final Actor stranger : strangers() ) {
            assertThrows( WorkNotFoundException.class, () -> service.claims().claim( stranger, "PLAT-2" ) );
            assertThrows( WorkNotFoundException.class, () -> service.claims().release( stranger, "PLAT-3", "x" ) );
            assertThrows( WorkNotFoundException.class, () -> service.claims().handoff( stranger, "PLAT-2", handoff ) );
        }
    }

    // ---- reporters: the audit as a test

    /** Prints the outcome list (the report's audit table) only when {@code -Dwork.reporterOutcomes=true}. */
    private static void say( final String line ) {
        if ( Boolean.getBoolean( "work.reporterOutcomes" ) ) {
            System.out.println( "REPORTER-OUTCOME " + line );
        }
    }

    @Test
    void everyOwnMethodPointsAtATestThatExists() throws Exception {
        assertEquals( OWN, OWN_TESTS.keySet(), "every OWN method names the test that drives it" );
        final java.util.Map< String, String > all = new java.util.HashMap<>( OWN_TESTS );
        OWN_ITEM_TESTS.forEach( all::put );
        for ( final java.util.Map.Entry< String, String > e : all.entrySet() ) {
            final String[] parts = e.getValue().split( "#" );
            final Method m = Class.forName( "com.wikantik.work." + parts[ 0 ] ).getDeclaredMethod( parts[ 1 ] );
            assertTrue( m.isAnnotationPresent( Test.class ), e.getKey() + " -> " + e.getValue() + " is not a test" );
        }
    }

    @Test
    void intakeOwnReadsAnswerOnlyTheCaller() throws SQLException {
        final WorkIntakeService intake = service.features().intake();
        assertEquals( List.of( "PLAT" ), intake.reportableProjects( rita ).stream().map( ReportableProject::key ).toList() );
        assertEquals( List.of( WorkRole.REPORTER ), intake.reportableProjects( rita ).stream().map( ReportableProject::role ).toList() );
        assertEquals( List.of( "PLAT-4" ), intake.reported( rita ).stream().map( ReportedItem::key ).toList() );
        assertEquals( List.of( "PLAT" ), intake.reportableProjects( gus ).stream().map( ReportableProject::key ).toList() );
        assertEquals( List.of( "PLAT-5" ), intake.reported( gus ).stream().map( ReportedItem::key ).toList() );
        assertEquals( List.of( "PLAT" ), intake.reportableProjects( lena ).stream().map( ReportableProject::key ).toList() );
        assertEquals( List.of(), intake.reported( lena ), "a lead follows items through the ordinary calls" );
        for ( final Actor stranger : List.of( mallory, rogueAgent, flaggedAgent, codexUnderAStranger, codexWithNoSponsor,
                codexUnderAReporter, Actor.system( "github" ) ) ) {
            assertEquals( List.of(), intake.reportableProjects( stranger ), stranger.login() );
            assertEquals( List.of(), intake.reported( stranger ), stranger.login() );
        }
        say( "rita intake.reportableProjects -> [PLAT]; intake.reported -> [PLAT-4]" );
        say( "gus intake.reportableProjects -> [PLAT]; intake.reported -> [PLAT-5]" );
        say( "mallory, agents, system intake.reportableProjects/reported -> empty" );
    }

    @Test
    void aReporterFollowsOnlyTheirOwnItemThroughIntake() throws SQLException {
        final WorkIntakeService intake = service.features().intake();
        assertEquals( "PLAT-4", intake.item( rita, "PLAT-4" ).key() );
        assertEquals( "PLAT-5", intake.item( gus, "PLAT-5" ).key() );
        for ( final String other : List.of( "PLAT-1", "PLAT-2", "PLAT-3", "PLAT-5", "PLAT-99", "PLAT", "web" ) ) {
            assertThrows( WorkNotFoundException.class, () -> intake.item( rita, other ), "rita " + other );
        }
        for ( final String other : List.of( "PLAT-1", "PLAT-2", "PLAT-3", "PLAT-4" ) ) {
            assertThrows( WorkNotFoundException.class, () -> intake.item( gus, other ), "gus " + other );
        }
        assertEquals( "my words", intake.comment( rita, "PLAT-4", "my words" ).body() );
        assertThrows( WorkNotFoundException.class, () -> intake.comment( rita, "PLAT-5", "x" ) );
        for ( final Actor stranger : List.of( mallory, rogueAgent, flaggedAgent, codexUnderAStranger, codexWithNoSponsor,
                codexUnderAReporter, Actor.system( "github" ) ) ) {
            for ( final String key : List.of( "PLAT-1", "PLAT-4", "PLAT-5" ) ) {
                assertThrows( WorkNotFoundException.class, () -> intake.item( stranger, key ), stranger.login() + " " + key );
            }
        }
        say( "rita intake.item(PLAT-4) -> allowed; other items -> not found; intake.comment(PLAT-4) -> allowed; others -> not found" );
        say( "gus intake.item(PLAT-5) -> allowed; other items -> not found" );
    }

    @Test
    void filingIsForPeopleWithAnyRowAndStartsADraft() throws SQLException {
        final WorkIntakeService intake = service.features().intake();
        for ( final Actor filer : List.of( rita, gus, lena ) ) {
            final ReportedItemView v = intake.submit( filer, new IntakeDraft( "PLAT", null, null, "from " + filer.login(), "" ) );
            assertEquals( WorkStatus.DRAFT, v.status(), filer.login() );
            assertEquals( filer.login(), v.reporter() );
        }
        final long before = service.items( lena, "PLAT", null ).size();
        for ( final Actor stranger : List.of( mallory, rogueAgent, flaggedAgent, codexUnderAStranger, codexWithNoSponsor,
                Actor.system( "github" ) ) ) {
            assertThrows( WorkNotFoundException.class,
                () -> intake.submit( stranger, new IntakeDraft( "PLAT", null, null, "no", "" ) ), stranger.login() );
        }
        assertThrows( WorkNotFoundException.class, () -> intake.submit( codexUnderAReporter,
            new IntakeDraft( "PLAT", null, null, "no", "" ) ), "an agent under a reporter has nothing" );
        assertEquals( before, service.items( lena, "PLAT", null ).size(), "none of those filed anything" );
        say( "rita, gus, lena intake.submit -> draft filed; mallory, agents, system, codexUnderAReporter -> not found" );
    }

    /** The parameter values a reflective call uses: enough for each method to reach its first check. */
    private static List< Object > samples( final Class< ? > type, final Actor actor ) {
        final java.time.LocalDate day = java.time.LocalDate.parse( "2026-01-05" );
        if ( type == Actor.class ) {
            return List.of( actor );
        }
        if ( type == String.class ) {
            return new ArrayList<>( REPORTER_FIXTURES );
        }
        final java.util.Map< Class< ? >, Object > fixed = java.util.Map.ofEntries(
            java.util.Map.entry( int.class, 1 ), java.util.Map.entry( boolean.class, true ),
            java.util.Map.entry( Collection.class, List.of( "rita", "gus", "lena" ) ),
            java.util.Map.entry( AssignmentLogQuery.class, AssignmentLogQuery.all() ),
            java.util.Map.entry( FindFilter.class, FindFilter.all() ),
            java.util.Map.entry( java.time.Instant.class, java.time.Instant.parse( "2026-01-05T00:00:00Z" ) ),
            java.util.Map.entry( java.time.LocalDate.class, day ),
            java.util.Map.entry( byte[].class, PNG ),
            java.util.Map.entry( WorkAttachmentsService.Download.class, DISCARD ),
            java.util.Map.entry( WatchState.class, WatchState.WATCHING ),
            java.util.Map.entry( WorkRole.class, WorkRole.LEAD ),
            java.util.Map.entry( ItemDraft.class, new ItemDraft( "PLAT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE, "t", "",
                null, null, null, null, null, null ) ),
            java.util.Map.entry( ItemPatch.class, new ItemPatch( 1, java.util.Map.of( "title", "x" ), null ) ),
            java.util.Map.entry( WorkClaimsService.Handoff.class, new WorkClaimsService.Handoff( 1, null, null, "implementer", "n" ) ),
            java.util.Map.entry( ChangeDraft.class, new ChangeDraft( "work/x", "main", "abc1234", null, null, null ) ),
            java.util.Map.entry( DeploymentDraft.class, new DeploymentDraft( "web", null, "1.0", List.of( "PLAT-1" ), null ) ),
            java.util.Map.entry( SprintDraft.class, new SprintDraft( "S9", "", day, day.plusDays( 13 ) ) ),
            java.util.Map.entry( WebhookDraft.class, new WebhookDraft( "https://a.example.com/x", "generic", List.of() ) ),
            java.util.Map.entry( WebhookPatch.class, new WebhookPatch( null, null, false ) ),
            java.util.Map.entry( SavedFilterDraft.class, new SavedFilterDraft( "x", "PLAT", true, java.util.Map.of() ) ),
            java.util.Map.entry( AgentDraft.class, new AgentDraft( "new@x", "", "", List.of() ) ),
            java.util.Map.entry( DoraQuery.class, new DoraQuery( null, null, null ) ),
            java.util.Map.entry( FlowQuery.class, new FlowQuery( null, null, null, null, null, null ) ),
            java.util.Map.entry( Product.class, new Product( 0, "web2", "Web", null, null, null, Autonomy.DELEGATED,
                DeployGate.HUMAN, null ) ) );
        if ( type == long.class ) {
            return List.of( 1L, 4L, 999_999L );
        }
        return Arrays.asList( fixed.get( type ) );
    }

    private static List< Object[] > reporterArguments( final Method m, final Actor actor ) {
        List< Object[] > sets = new ArrayList<>();
        sets.add( new Object[ m.getParameterCount() ] );
        for ( int i = 0; i < m.getParameterCount(); i++ ) {
            final List< Object > options = samples( m.getParameterTypes()[ i ], actor );
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

    /**
     * The audit as a test (Part 8, review focus 2). Every public method of the service and of every facade is driven
     * as a person who is only a reporter (by name, and by group) and as an agent under one, with the fixtures that
     * include the reporter's own item. The answer is "not found" or "denied", or an empty result for a list: never
     * data, and no write. The intake methods and the caller's own records have their own tests; a method added
     * later is classified (or the classification test fails) and then driven here.
     */
    @Test
    void everyMethodAnswersAReporterWithNothing() throws Exception {
        final Set< String > skipped = new TreeSet<>( SYSTEM );
        skipped.addAll( OWN );
        final java.util.Map< String, java.util.Set< String > > outcomes = new java.util.TreeMap<>();
        final List< String > failures = new ArrayList<>();
        for ( final Actor reporter : List.of( rita, gus, codexUnderAReporter ) ) {
            for ( final Op op : ops() ) {
                if ( skipped.contains( op.name() ) || op.target() instanceof WorkIntakeService ) {
                    continue;
                }
                final String label = reporter.login() + ( reporter.kind() == Actor.Kind.AGENT ? " (agent under a reporter)" : "" )
                    + " | " + op.target().getClass().getSimpleName() + "." + op.name();
                final java.util.Set< String > seen = outcomes.computeIfAbsent( label, k -> new TreeSet<>() );
                final boolean ownItemSurface = OWN_ITEM_TESTS.containsKey( op.target().getClass().getSimpleName() + "." + op.name() );
                final String ownKey = reporter == rita ? "PLAT-4" : reporter == gus ? "PLAT-5" : null;
                for ( final Object[] args : reporterArguments( op.method(), reporter ) ) {
                    if ( ownItemSurface && ownKey != null && Arrays.asList( args ).contains( ownKey ) ) {
                        continue;   // her own item: driven by the test OWN_ITEM_TESTS names
                    }
                    try {
                        final Object result = invoke( op.method(), op.target(), args );
                        if ( READS.contains( op.name() ) && isEmptyResult( result ) ) {
                            seen.add( "empty" );
                        } else {
                            seen.add( "RETURNED" );
                            failures.add( label + " answered " + java.util.Arrays.toString( args ) + " with " + result );
                        }
                    } catch ( final WorkNotFoundException e ) {
                        seen.add( "not found" );
                    } catch ( final WorkDeniedException e ) {
                        seen.add( "denied" );
                    } catch ( final Exception e ) {
                        seen.add( "UNEXPECTED " + e.getClass().getSimpleName() );
                        failures.add( label + " threw " + e + " for " + java.util.Arrays.toString( args ) );
                    }
                }
            }
        }
        assertEquals( List.of(), failures );
        final List< String > driven = new ArrayList<>();
        outcomes.forEach( ( label, seen ) -> driven.add( label + " -> " + String.join( " / ", seen ) ) );
        driven.forEach( WorkServiceVisibilityPostgresTest::say );
        final Set< String > drivenNames = new TreeSet<>();
        outcomes.keySet().forEach( l -> drivenNames.add( l.substring( l.lastIndexOf( '.' ) + 1 ) ) );
        final Set< String > expected = new TreeSet<>( READS );
        expected.addAll( WRITES );
        expected.addAll( INSTANCE_READS );
        expected.addAll( YES_OR_NO );
        expected.removeAll( Set.of( "comment", "item", "outsideReporters" ) );   // intake's own: driven by their tests
        expected.add( "item" );   // WorkService.item itself
        assertEquals( expected, drivenNames, "every classified method was driven as a reporter" );
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

    @Test
    void maxFileBytesIsTheConfiguredSettingAndTakesNoActor() throws Exception {
        assertEquals( WorkSettings.DEFAULTS.maxAttachmentBytes(), service.features().attachments().maxFileBytes() );
        assertEquals( 0, WorkAttachmentsService.class.getMethod( "maxFileBytes" ).getParameterCount() );
    }
}

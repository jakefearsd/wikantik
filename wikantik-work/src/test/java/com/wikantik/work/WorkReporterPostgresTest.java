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
import java.util.HashMap;
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
 * Intake through the service, over real PostgreSQL: project INT has lena (lead), alice and carol (members), rita (a
 * reporter by name) and the Customers group (reporters, gus is one of them). A reporter files and follows their own
 * items and nothing else.
 */
@RequiresPostgres
class WorkReporterPostgresTest {

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor alice = Actor.human( "alice", Set.of(), false );
    private final Actor rita = Actor.human( "rita", Set.of(), false );
    private final Actor gus = Actor.human( "gus", Set.of( "Customers" ), false );
    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private final Map< String, Set< String > > groups = new HashMap<>( Map.of( "gus", Set.of( "Customers" ) ) );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService service;
    private WorkIntakeService intake;

    @BeforeEach
    void setUp() throws SQLException {
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        ds = PostgresTestDb.createDataSource();
        store = new JdbcWorkStore( ds );
        service = new WorkService( store, renderer(), directory(), Clock.fixed( NOW, java.time.ZoneOffset.UTC ) );
        intake = service.features().intake();
        service.createProject( admin, "INT", "Intake", "" );
        service.putMember( admin, "INT", "lena", "user", WorkRole.LEAD );
        service.putMember( admin, "INT", "alice", "user", WorkRole.MEMBER );
        service.putMember( admin, "INT", "carol", "user", WorkRole.MEMBER );
        service.putMember( admin, "INT", "rita", "user", WorkRole.REPORTER );
        service.putMember( admin, "INT", "Customers", "group", WorkRole.REPORTER );
        service.registry().createQueue( admin, "implementer", "Implementer", "" );
    }

    /** The fixed service clock (house rule: no test reads the wall clock). */
    private static final java.time.Instant NOW = java.time.Instant.parse( "2026-03-02T10:00:00Z" );

    /** Marks every rendering with the item key it was asked for, so a test sees which item's attachments a token may name. */
    private static WorkRenderer renderer() {
        return new WorkRenderer() {
            @Override public String toHtml( final String markdown ) { return markdown; }
            @Override public String toHtml( final String markdown, final String itemKey ) { return "[" + itemKey + "] " + markdown; }
        };
    }

    private WorkDirectory directory() {
        return new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return groups.getOrDefault( login, Set.of() ); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            // a display name that differs from the login, so a login left in a reporter's view is caught
            @Override public String displayName( final String login ) { return "Person " + login.toUpperCase( java.util.Locale.ROOT ); }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                final String login = name.startsWith( "Person " ) ? name.substring( 7 ).toLowerCase( java.util.Locale.ROOT ) : name;
                return WorkAgentFixtures.isPerson( login ) ? Optional.of( login ) : Optional.empty();
            }
        };
    }

    private static IntakeDraft draft( final String title ) {
        return new IntakeDraft( "INT", null, null, title, "details of " + title );
    }

    private ReportedItemView file( final Actor who, final String title ) throws SQLException {
        return intake.submit( who, draft( title ) );
    }

    private Item stored( final String key ) throws SQLException {
        return store.item( key ).orElseThrow();
    }

    private Actor agentInIntake( final String name, final WorkRole role ) throws SQLException {
        final Agent a = service.registry().registerAgent( lena, new AgentDraft( name, "", "", List.of( "implementer" ) ) ).agent();
        if ( role != null ) {
            service.putMember( admin, "INT", name, "agent", role );
        }
        return Actor.agent( a.id(), a.name(), lena );
    }

    /** An agent named like a person: the registry refuses the name, the store does not (ruling 5). */
    private Actor namesake( final String name, final Actor sponsor ) throws SQLException {
        final long id = store.registerAgent( name, "", "", List.of(), sponsor.login() ).id();
        store.putMember( store.project( "INT" ).orElseThrow().id(), String.valueOf( id ), "agent", WorkRole.MEMBER, admin );
        return Actor.agent( id, name, sponsor );
    }

    // ---- filing

    @Test
    void aReporterFilesADraftUnassignedStory() throws SQLException {
        final ReportedItemView v = intake.submit( rita, new IntakeDraft( "int", null, WorkType.BUG, "Cannot log in", "I tried" ) );
        assertEquals( WorkStatus.DRAFT, v.status() );
        assertEquals( WorkLevel.STORY, v.level() );
        assertEquals( WorkType.BUG, v.type() );
        assertEquals( "Person RITA", v.reporter() );
        assertEquals( "INT", v.projectKey() );
        final Item item = stored( v.key() );
        assertNull( item.assignee() );
        assertNull( item.queue() );
        assertNull( item.reporterAgentId() );
        assertNull( item.parentId() );
        assertNull( item.productId() );
        assertEquals( "rita", item.reporter() );
        assertEquals( List.of( WorkEvent.CREATED ), store.itemEvents( item.id() ).stream().map( WorkEvent::type ).toList() );
        assertEquals( List.of( WorkEvent.CREATED ), v.events().stream().map( ReportedEvent::type ).toList() );
    }

    @Test
    void aGroupReporterFilesToo() throws SQLException {
        final ReportedItemView v = file( gus, "From a customer" );
        assertEquals( "Person GUS", v.reporter() );
        assertEquals( WorkStatus.DRAFT, v.status() );
    }

    @Test
    void aMemberFilingThroughIntakeAlsoGetsADraft() throws SQLException {
        assertEquals( WorkStatus.DRAFT, intake.submit( alice, new IntakeDraft( "INT", WorkLevel.TASK, null, "t", "" ) ).status() );
        assertEquals( WorkStatus.READY, service.createItem( alice, new ItemDraft( "INT", WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, "member's own task", "", null, null, null, null, null, null ) ).item().status(),
            "the ordinary create is unchanged" );
    }

    @Test
    void anEpicCannotBeFiled() {
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> intake.submit( rita, new IntakeDraft( "INT", WorkLevel.EPIC, null, "Everything", "" ) ) );
        assertEquals( "level", e.field() );
        assertEquals( 0, count( "SELECT count(*) FROM work_item" ), "nothing was stored" );
    }

    private long count( final String sql ) {
        try {
            return WorkAgentFixtures.scalar( ds, sql );
        } catch ( final SQLException e ) {
            throw new IllegalStateException( e );
        }
    }

    @Test
    void theSameRulesApplyAsToAMembersCreate() {
        assertEquals( "title", assertThrows( WorkValidationException.class,
            () -> intake.submit( rita, new IntakeDraft( "INT", null, null, "  ", "" ) ) ).field() );
    }

    @Test
    void anAgentCannotFile() throws SQLException {
        final Actor member = agentInIntake( "codex", WorkRole.MEMBER );
        final WorkDeniedException denied = assertThrows( WorkDeniedException.class, () -> intake.submit( member, draft( "x" ) ) );
        assertEquals( "A person files intake; an agent creates items in the projects it belongs to.", denied.reason() );
        final Actor stranger = agentInIntake( "ghost", null );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( stranger, draft( "x" ) ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_item" ) );
    }

    @Test
    void aStrangerAndAnUnknownProjectAreNotFound() {
        assertThrows( WorkNotFoundException.class, () -> intake.submit( mallory, draft( "x" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( rita, new IntakeDraft( "NOPE", null, null, "x", "" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( rita, new IntakeDraft( null, null, null, "x", "" ) ) );
    }

    @Test
    void anArchivedProjectRefusesIntake() throws SQLException {
        final ReportedItemView mine = file( rita, "before the archive" );
        service.archiveProject( admin, "INT" );
        assertEquals( WorkConflictException.ARCHIVED, assertThrows( WorkConflictException.class,
            () -> file( rita, "after" ) ).reason() );
        assertEquals( WorkConflictException.ARCHIVED, assertThrows( WorkConflictException.class,
            () -> intake.comment( rita, mine.key(), "still there?" ) ).reason() );
        assertTrue( intake.item( rita, mine.key() ).archived(), "she may still read it" );
    }

    // ---- reading

    @Test
    void aReporterSeesTheirOwnItemAndNothingElse() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final String alices = service.createItem( alice, new ItemDraft( "INT", WorkLevel.TASK, null, null,
            WorkKind.SOFTWARE, "alice's", "", null, null, null, null, null, null ) ).item().key();
        final String gusKey = file( gus, "gus's" ).key();
        assertEquals( own, intake.item( rita, own ).key() );
        assertThrows( WorkNotFoundException.class, () -> intake.item( rita, alices ) );
        assertThrows( WorkNotFoundException.class, () -> intake.item( rita, gusKey ) );
        assertThrows( WorkNotFoundException.class, () -> intake.item( rita, "INT-9999" ) );
        assertThrows( WorkNotFoundException.class, () -> intake.item( rita, "nonsense" ) );
        assertThrows( WorkNotFoundException.class, () -> service.item( rita, own ), "the full read is not hers" );
        assertThrows( WorkNotFoundException.class, () -> intake.item( mallory, own ) );
    }

    @Test
    void aMemberReadsAnyItemThroughIntakeAsAReportedView() throws SQLException {
        final String own = file( rita, "mine" ).key();
        assertEquals( own, intake.item( alice, own ).key(), "a person with standing may follow any item" );
        assertEquals( own, intake.item( admin, own ).key() );
    }

    @Test
    void theReportersViewCarriesOnlyTheirEvents() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final String other = file( gus, "other" ).key();
        int version = stored( own ).version();
        version = service.updateItem( alice, own, new ItemPatch( version, Map.of( "assignee", "carol" ), null ) ).item().version();
        version = service.updateItem( alice, own, new ItemPatch( version, Map.of( "priority", 1 ), null ) ).item().version();
        service.addLink( alice, own, "blocked_by", other );
        version = service.updateItem( alice, own, new ItemPatch( stored( own ).version(), Map.of( "status", "ready" ),
            "internal reason" ) ).item().version();
        service.addComment( alice, own, "we are on it" );
        intake.comment( rita, own, "thank you" );
        final ReportedItemView v = intake.item( rita, own );
        assertEquals( List.of( WorkEvent.CREATED, WorkEvent.STATUS, WorkEvent.COMMENTED, WorkEvent.COMMENTED ),
            v.events().stream().map( ReportedEvent::type ).toList() );
        assertEquals( WorkStatus.READY, v.status() );
        assertEquals( List.of( "we are on it", "thank you" ), v.comments().stream().map( ReportedComment::body ).toList(),
            "the whole discussion" );
        assertFalse( v.toString().contains( "internal reason" ) );
        assertTrue( version > 0 );
    }

    @Test
    void aReportersViewLeaksNothingAroundTheItem() throws SQLException {
        final Product product = store.createProduct( new Product( 0, "secretproduct", "Secret Product", null, "main", null,
            Autonomy.DELEGATED, DeployGate.HUMAN, null ) );
        service.createItem( lena, new ItemDraft( "INT", WorkLevel.EPIC, null, null, WorkKind.SOFTWARE,
            "PARENTSECRET", "", null, null, null, null, null, null ) );
        final String own = file( rita, "mine" ).key();
        final String linked = service.createItem( lena, new ItemDraft( "INT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "LINKEDSECRET", "", null, null, null, null, null, null ) ).item().key();
        final Map< String, Object > changes = new HashMap<>();
        changes.put( "parentKey", "INT-1" );
        changes.put( "productKey", product.key() );
        changes.put( "assignee", "carol" );
        changes.put( "labels", List.of( "secretlabel" ) );
        changes.put( "estimate", 21 );
        service.updateItem( lena, own, new ItemPatch( stored( own ).version(), changes, null ) );
        service.addLink( lena, own, "blocked_by", linked );
        service.createItem( lena, new ItemDraft( "INT", WorkLevel.TASK, own, null, WorkKind.SOFTWARE,
            "CHILDSECRET", "", null, null, null, null, null, null ) );
        final ReportedItemView v = intake.item( rita, own );
        final String shown = v.toString();
        for ( final String secret : List.of( "PARENTSECRET", "LINKEDSECRET", "CHILDSECRET", "secretproduct", "Secret Product",
                "carol", "secretlabel", "implementer", linked, "INT-1" ) ) {
            assertFalse( shown.contains( secret ), "the view shows " + secret + ": " + shown );
        }
    }

    @Test
    void aReporterCommentsAndMentionsNobody() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final ReportedComment c = intake.comment( rita, own, "@alice please look" );
        assertEquals( "@alice please look", c.body(), "stored as typed: nothing resolved" );
        assertEquals( List.of(), c.mentions() );
        assertEquals( 0, count( "SELECT count(*) FROM work_mention" ) );
        assertEquals( 1, count( "SELECT count(*) FROM work_comment" ) );
    }

    @Test
    void aReporterCannotEditTheirOwnItemOrCommentThroughTheFullRoutes() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final int version = stored( own ).version();
        assertThrows( WorkNotFoundException.class, () -> service.updateItem( rita, own,
            new ItemPatch( version, Map.of( "title", "mine now" ), null ) ) );
        assertThrows( WorkNotFoundException.class, () -> service.addComment( rita, own, "hi" ) );
        assertThrows( WorkNotFoundException.class, () -> service.addLink( rita, own, "blocked_by", "INT-2" ) );
        assertThrows( WorkNotFoundException.class, () -> service.events( rita, own ) );
        assertThrows( WorkNotFoundException.class, () -> service.revisions( rita, own ) );
        assertThrows( WorkNotFoundException.class, () -> service.policyOf( rita, own ) );
        assertThrows( WorkNotFoundException.class, () -> service.createItem( rita, new ItemDraft( "INT", WorkLevel.TASK, null,
            null, WorkKind.SOFTWARE, "t", "", null, null, null, null, null, null ) ) );
        assertEquals( "mine", stored( own ).title() );
    }

    @Test
    void aReporterIsNeitherAssignableNorMentionable() throws SQLException {
        final String key = service.createItem( alice, new ItemDraft( "INT", WorkLevel.TASK, null, null, WorkKind.SOFTWARE,
            "alice's", "", null, null, null, null, null, null ) ).item().key();
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> service.updateItem( lena, key,
            new ItemPatch( stored( key ).version(), Map.of( "assignee", "rita" ), null ) ) );
        assertEquals( "assignee", e.field() );
        service.addComment( alice, key, "@rita are you there?" );
        assertEquals( 0, count( "SELECT count(*) FROM work_mention WHERE mentioned = 'rita'" ) );
    }

    @Test
    void aRemovedReporterLosesTheirItems() throws SQLException {
        final String own = file( rita, "mine" ).key();
        assertEquals( 1, intake.reported( rita ).size() );
        service.removeMember( admin, "INT", "rita", "user" );
        assertThrows( WorkNotFoundException.class, () -> intake.item( rita, own ) );
        assertEquals( List.of(), intake.reported( rita ) );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( rita, draft( "again" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.comment( rita, own, "hello?" ) );
    }

    @Test
    void reportedListsOnlyReporterOnlyProjects() throws SQLException {
        final String own = file( rita, "mine" ).key();
        file( gus, "gus's" );
        file( alice, "alice filed through intake" );
        final List< ReportedItem > mine = intake.reported( rita );
        assertEquals( List.of( own ), mine.stream().map( ReportedItem::key ).toList() );
        assertEquals( "Intake", mine.get( 0 ).projectName() );
        assertEquals( WorkStatus.DRAFT, mine.get( 0 ).status() );
        assertEquals( List.of(), intake.reported( alice ), "a member follows items through the ordinary calls" );
        assertEquals( 1, intake.reported( gus ).size() );
        assertEquals( List.of(), intake.reported( mallory ) );
        assertEquals( List.of(), intake.reported( namesake( "rita", rita ) ) );
    }

    @Test
    void reportedCountsComments() throws SQLException {
        final String own = file( rita, "mine" ).key();
        intake.comment( rita, own, "one" );
        service.addComment( alice, own, "two" );
        assertEquals( 2, intake.reported( rita ).get( 0 ).commentCount() );
    }

    @Test
    void reportableProjectsNamesTheCallersRole() throws SQLException {
        assertEquals( List.of( WorkRole.REPORTER ), intake.reportableProjects( rita ).stream().map( ReportableProject::role ).toList() );
        assertEquals( List.of( WorkRole.REPORTER ), intake.reportableProjects( gus ).stream().map( ReportableProject::role ).toList() );
        assertEquals( List.of( WorkRole.MEMBER ), intake.reportableProjects( alice ).stream().map( ReportableProject::role ).toList() );
        assertEquals( List.of( WorkRole.LEAD ), intake.reportableProjects( admin ).stream().map( ReportableProject::role ).toList() );
        assertEquals( List.of(), intake.reportableProjects( mallory ) );
        assertEquals( List.of(), service.listProjects( rita ), "and she sees no project through the ordinary list" );
    }

    @Test
    void outsideReportersFeedsTheMembersNotice() throws SQLException {
        file( rita, "mine" );
        assertEquals( Set.of( "rita", "gus" ), intake.outsideReporters( alice, "INT", List.of( "rita", "alice", "gus" ) ) );
        assertEquals( Set.of(), intake.outsideReporters( alice, "INT", List.of( "alice" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.outsideReporters( rita, "INT", List.of( "rita" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.outsideReporters( gus, "INT", List.of( "rita" ) ) );
        assertThrows( WorkNotFoundException.class, () -> intake.outsideReporters( mallory, "INT", List.of( "rita" ) ) );
    }

    @Test
    void aPromotedReporterIsNoLongerOutside() throws SQLException {
        service.putMember( admin, "INT", "rita", "user", WorkRole.VIEWER );
        assertEquals( Set.of(), intake.outsideReporters( alice, "INT", List.of( "rita" ) ) );
    }

    @Test
    void aReporterScopeNeverReachesTheFullView() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final WorkScopes scopes = new WorkScopes( store, directory(), null );
        final WorkScopes.ItemScope is = scopes.ownItemScope( rita, own );
        assertTrue( is.scope().reporterOnly() );
        final ItemViews views = new ItemViews( store, md -> md, new WorkPeople( directory() ) );
        assertThrows( IllegalStateException.class, () -> views.view( is.scope(), is.item() ) );
        assertFalse( scopes.ownItemScope( alice, own ).scope().reporterOnly() );
        assertFalse( scopes.ownItemScope( admin, own ).scope().reporterOnly() );
        assertThrows( WorkNotFoundException.class, () -> scopes.itemScope( rita, own ), "the ordinary scope is still closed" );
    }

    @Test
    void aReportersItemIsNeverClaimable() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final Actor codex = agentInIntake( "codex", WorkRole.MEMBER );
        assertEquals( List.of(), service.claims().claimable( codex, "INT", null, 10 ) );
        assertEquals( WorkStatus.DRAFT, stored( own ).status() );
        assertNull( stored( own ).queue() );
    }

    @Test
    void anAgentNamedLikeTheReporterCannotSeeTheirItem() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final Actor namesake = namesake( "rita", rita );
        assertThrows( WorkNotFoundException.class, () -> intake.item( namesake, own ),
            "its sponsor is only a reporter, so its member row is capped to nothing" );
        assertThrows( WorkNotFoundException.class, () -> intake.comment( namesake, own, "hi" ) );
        assertThrows( WorkNotFoundException.class, () -> intake.submit( namesake, draft( "x" ) ) );
        final Actor outsider = agentInIntake( "ghost", null );
        assertThrows( WorkNotFoundException.class, () -> intake.item( outsider, own ) );
        assertThrows( WorkNotFoundException.class, () -> intake.comment( outsider, own, "hi" ) );
    }

    // ---- core review fixes (I1, I3, I4) and the render token (F)

    /** I1 / P8-16: intake names a project by key and name, never its description or anything else. */
    @Test
    void reportableProjectsCarryOnlyKeyNameAndRole() throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_project SET description = 'DESCRIPTIONMARKER' WHERE key = 'INT'" );
        final List< ReportableProject > mine = intake.reportableProjects( rita );
        assertEquals( List.of( new ReportableProject( "INT", "Intake", WorkRole.REPORTER ) ), mine );
        assertFalse( mine.toString().contains( "DESCRIPTIONMARKER" ) );
        assertEquals( List.of( "key", "name", "role" ), java.util.Arrays.stream( ReportableProject.class.getRecordComponents() )
            .map( java.lang.reflect.RecordComponent::getName ).toList() );
    }

    /** I3: the intake paths bound an item body and a comment at {@link WorkIntakeService#MAX_TEXT} characters. */
    @Test
    void intakeTextIsBounded() throws SQLException {
        final String most = "x".repeat( WorkIntakeService.MAX_TEXT );
        final String own = intake.submit( rita, new IntakeDraft( "INT", null, null, "long", most ) ).key();
        assertEquals( most, stored( own ).body() );
        final WorkValidationException body = assertThrows( WorkValidationException.class,
            () -> intake.submit( rita, new IntakeDraft( "INT", null, null, "too long", most + "x" ) ) );
        assertEquals( "body", body.field() );
        assertEquals( most, intake.comment( rita, own, most ).body() );
        final WorkValidationException comment = assertThrows( WorkValidationException.class,
            () -> intake.comment( rita, own, most + "x" ) );
        assertEquals( "body", comment.field() );
        assertEquals( 1, count( "SELECT count(*) FROM work_comment" ), "the refused comment stored nothing" );
        assertEquals( 1, count( "SELECT count(*) FROM work_item WHERE title = 'long'" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_item WHERE title = 'too long'" ) );
    }

    /**
     * I4: everything intake returns names people by display name: the reporter, every event actor, every comment
     * author (a person or an agent, which shows by its name with no id). No login of a person reaches the view.
     */
    @Test
    void aReportersViewNamesPeopleOnlyByDisplayName() throws SQLException {
        final String own = file( rita, "mine" ).key();
        service.updateItem( alice, own, new ItemPatch( stored( own ).version(), Map.of( "status", "ready" ), null ) );
        service.addComment( lena, own, "we are on it" );
        final Actor codex = agentInIntake( "codex@docker2", WorkRole.MEMBER );
        service.addComment( codex, own, "an agent replies" );
        final ReportedComment hers = intake.comment( rita, own, "thank you" );
        final ReportedItemView v = intake.item( rita, own );
        assertEquals( "Person RITA", v.reporter() );
        assertEquals( "Person RITA", hers.author() );
        assertEquals( List.of( "Person RITA", "Person ALICE" ), v.events().stream().filter( e -> !e.type().equals( "commented" ) )
            .map( ReportedEvent::actor ).toList() );
        assertEquals( List.of( "Person LENA", "codex@docker2", "Person RITA" ), v.comments().stream()
            .map( ReportedComment::author ).toList() );
        assertEquals( Actor.Kind.AGENT, v.comments().get( 1 ).authorKind() );
        final String shown = v.toString() + hers;
        for ( final String login : List.of( "rita", "alice", "lena" ) ) {
            assertFalse( shown.contains( login ), "the login " + login + " in " + shown );
        }
        assertFalse( shown.contains( "agentId" ) || shown.contains( "AgentId" ), shown );
    }

    /** F / G2: a reporter's text renders against her own item only, so an attachment token resolves to her item's files. */
    @Test
    void aReportersTextRendersAgainstTheirOwnItemOnly() throws SQLException {
        final String own = file( rita, "mine" ).key();
        service.addComment( lena, own, "see work-attachment:1" );
        final ReportedItemView v = intake.item( rita, own );
        assertTrue( v.bodyHtml().startsWith( "[" + own + "] " ), v.bodyHtml() );
        assertTrue( v.comments().stream().allMatch( c -> c.bodyHtml().startsWith( "[" + own + "] " ) ), v.comments().toString() );
    }

    /** M4: an attached event is shown only while its file is still attached; a removed file is not named. */
    @Test
    void aRemovedFileIsNotNamedInTheTimeline() throws SQLException {
        final String own = file( rita, "mine" ).key();
        final WorkAttachmentsService files = service.features().attachments();
        final byte[] png = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1 };
        final Attachment kept = files.upload( alice, own, null, "kept.png", png );
        final Attachment gone = files.upload( alice, own, null, "internal-roadmap.xlsx", png );
        files.remove( alice, own, gone.id() );
        final ReportedItemView v = intake.item( rita, own );
        assertEquals( List.of( "attachment:" + kept.id() ), v.events().stream().filter( e -> e.type().equals( "attached" ) )
            .map( ReportedEvent::fromValue ).toList() );
        assertFalse( v.toString().contains( "internal-roadmap" ) );
    }

    @Test
    void aWikiAdminWithNoRowInTheProjectIsShownAsLeadWhenFiling() throws SQLException {
        final Actor otherAdmin = Actor.human( "root2", Set.of(), true );
        assertEquals( List.of( "INT" ), intake.reportableProjects( otherAdmin ).stream().map( ReportableProject::key ).toList() );
        assertEquals( List.of( WorkRole.LEAD ), intake.reportableProjects( otherAdmin ).stream().map( ReportableProject::role ).toList(),
            "no member row, so the role comes from the admin flag" );
    }
}

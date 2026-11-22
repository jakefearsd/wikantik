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
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Saved filters through the facade: pinned to their project, personal otherwise, hidden from non-members. */
@RequiresPostgres
class WorkSavedFiltersPostgresTest {

    private final Actor admin = Actor.human( "admin", Set.of(), true );
    private final Actor lena = Actor.human( "lena", Set.of(), false );
    private final Actor bob = Actor.human( "bob", Set.of(), false );
    private final Actor vera = Actor.human( "vera", Set.of(), false );
    private final Actor mallory = Actor.human( "mallory", Set.of(), false );
    private DataSource ds;
    private JdbcWorkStore store;
    private WorkService svc;
    private WorkFindService find;
    private Actor codex;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        svc = new WorkService( store, md -> md, new WorkDirectory() {
            @Override public Set< String > groupsOf( final String login ) { return Set.of(); }
            @Override public boolean exists( final String login ) { return WorkAgentFixtures.isPerson( login ); }
            @Override public String displayName( final String login ) { return login; }
            @Override public Optional< String > loginForDisplayName( final String name ) {
                return "Lena Lead".equals( name ) ? Optional.of( "lena" ) : Optional.empty();
            }
        }, Clock.systemUTC(), WorkSettings.DEFAULTS, new WorkListener() { } );
        find = svc.features().find();
        svc.createProject( admin, "AAA", "Alpha", "" );
        svc.createProject( admin, "BBB", "Beta", "" );
        svc.putMember( admin, "AAA", "lena", "user", WorkRole.LEAD );
        svc.putMember( admin, "AAA", "bob", "user", WorkRole.MEMBER );
        svc.putMember( admin, "AAA", "vera", "user", WorkRole.VIEWER );
        svc.putMember( admin, "BBB", "bob", "user", WorkRole.LEAD );
        svc.putMember( admin, "BBB", "lena", "user", WorkRole.MEMBER );
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        final Agent agent = svc.registry().registerAgent( admin, new AgentDraft( "codex@docker2", "", "",
            List.of( "implementer" ) ) ).agent();
        codex = Actor.agent( agent.id(), agent.name(), admin );
    }

    private Item item( final Actor by, final String project, final String title ) throws SQLException {
        return svc.createItem( by, new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title, "",
            null, null, null, null, null, null ) ).item();
    }

    private SavedFilterView share( final Actor by, final String name, final String project, final Map< String, String > f )
            throws SQLException {
        return find.save( by, new SavedFilterDraft( name, project, true, f ) );
    }

    private SavedFilterView privately( final Actor by, final String name, final Map< String, String > f ) throws SQLException {
        return find.save( by, new SavedFilterDraft( name, null, false, f ) );
    }

    private List< String > names( final Actor who ) throws SQLException {
        return find.saved( who ).stream().map( v -> v.filter().name() ).toList();
    }

    private static String field( final org.junit.jupiter.api.function.Executable e ) {
        return assertThrows( WorkValidationException.class, e ).field();
    }

    @Test
    void aSharedFilterIsListedToMembersAndViewersAndNotToAStranger() throws SQLException {
        share( lena, "Ready work", "AAA", Map.of( "status", "ready" ) );
        assertEquals( List.of( "Ready work" ), names( lena ) );
        assertEquals( List.of( "Ready work" ), names( bob ) );
        assertEquals( List.of( "Ready work" ), names( vera ) );
        assertTrue( names( mallory ).isEmpty() );
        assertEquals( List.of( "Ready work" ), names( admin ) );
        assertTrue( find.saved( bob ).get( 0 ).filter().shared() );
        assertFalse( find.saved( bob ).get( 0 ).mine() );
        assertTrue( find.saved( lena ).get( 0 ).mine() );
    }

    @Test
    void aPrivateFilterIsListedToItsOwnerOnlyEvenAgainstAnAdmin() throws SQLException {
        privately( lena, "Mine", Map.of( "status", "ready" ) );
        assertEquals( List.of( "Mine" ), names( lena ) );
        assertTrue( names( admin ).isEmpty() );
        assertTrue( names( bob ).isEmpty() );
    }

    @Test
    void runningSomeonesSharedFilterGivesTheRunnersResults() throws SQLException {
        item( lena, "AAA", "alpha" );
        item( bob, "BBB", "beta" );
        final SavedFilterView v = share( lena, "Ready", "AAA", Map.of( "status", "ready" ) );
        assertEquals( "AAA", v.filter().filter().get( "projects" ), "the filter names only its project" );
        final SavedFilterView seenByBob = find.saved( bob ).get( 0 );
        final FindPage page = find.find( bob, FindParams.parse( seenByBob.filter().filter() ) );
        assertEquals( List.of( "alpha" ), page.items().stream().map( s -> s.item().title() ).toList() );
        final FindPage forVera = find.find( vera, FindParams.parse( seenByBob.filter().filter() ) );
        assertEquals( 1, forVera.items().size() );
    }

    @Test
    void aFilterNamingMeMeansTheRunner() throws SQLException {
        final Item l = item( lena, "AAA", "lenas" );
        final Item b = item( bob, "AAA", "bobs" );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET assignee = 'lena' WHERE id = " + l.id() );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET assignee = 'bob' WHERE id = " + b.id() );
        final SavedFilterView v = share( lena, "Mine", "AAA", Map.of( "assignee", "me" ) );
        assertEquals( "me", v.filter().filter().get( "assignee" ), "stored as me, not as lena" );
        final SortedMap< String, String > stored = find.saved( bob ).get( 0 ).filter().filter();
        assertEquals( List.of( "bobs" ), find.find( bob, FindParams.parse( stored ) ).items().stream()
            .map( s -> s.item().title() ).toList() );
    }

    @Test
    void peopleAreStoredAsLogins() throws SQLException {
        final SavedFilterView v = privately( bob, "Lenas", Map.of( "assignee", "Lena Lead", "reporter", "Lena Lead" ) );
        assertEquals( "lena", v.filter().filter().get( "assignee" ) );
        assertEquals( "lena", v.filter().filter().get( "reporter" ) );
    }

    @Test
    void anUnknownPersonIsRefusedByField() {
        assertEquals( "assignee", field( () -> privately( bob, "x", Map.of( "assignee", "Nobody" ) ) ) );
        assertEquals( "reporter", field( () -> privately( bob, "x", Map.of( "reporter", "Nobody" ) ) ) );
        assertEquals( "assignee", field( () -> privately( bob, "x", Map.of( "assignee", "lena" ) ) ),
            "a login from a non-admin names nobody" );
    }

    @Test
    void aViewerCannotShare() {
        assertThrows( WorkDeniedException.class, () -> share( vera, "x", "AAA", Map.of() ) );
        assertDoesNotThrowPrivate( vera );
    }

    private void assertDoesNotThrowPrivate( final Actor who ) {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow( () -> find.save( who,
            new SavedFilterDraft( "mine", "AAA", false, Map.of() ) ), "a viewer may keep a private filter" );
    }

    @Test
    void sharingIntoAnUnseenProjectIsNotFound() {
        assertThrows( WorkNotFoundException.class, () -> share( mallory, "x", "AAA", Map.of() ) );
        assertThrows( WorkNotFoundException.class, () -> share( lena, "x", "NOPE", Map.of() ) );
        assertThrows( WorkNotFoundException.class, () -> find.save( mallory,
            new SavedFilterDraft( "x", "AAA", false, Map.of() ) ), "a private one pinned to it too" );
        assertThrows( WorkNotFoundException.class, () -> share( mallory, "x", "AAA", Map.of( "cursor", "junk" ) ),
            "the stranger learns nothing from a refusal that depends on the filter" );
    }

    @Test
    void aSharedFilterSearchesItsOwnProjectOnly() throws SQLException {
        assertEquals( "AAA", share( lena, "absent", "AAA", Map.of() ).filter().filter().get( "projects" ) );
        assertEquals( "AAA", share( lena, "same", "AAA", Map.of( "projects", "aaa" ) ).filter().filter().get( "projects" ) );
        assertEquals( "projects", field( () -> share( lena, "other", "AAA", Map.of( "projects", "BBB" ) ) ) );
        assertEquals( "projects", field( () -> share( lena, "both", "AAA", Map.of( "projects", "AAA,BBB" ) ) ) );
    }

    @Test
    void aSharedFilterNeedsAProject() {
        assertEquals( "project", field( () -> find.save( lena, new SavedFilterDraft( "x", null, true, Map.of() ) ) ) );
        assertEquals( "project", field( () -> find.save( lena, new SavedFilterDraft( "x", " ", true, Map.of() ) ) ) );
    }

    @Test
    void aPrivateFilterMayStayCrossProject() throws SQLException {
        final SavedFilterView v = privately( bob, "everywhere", Map.of( "projects", "AAA,BBB" ) );
        assertEquals( "AAA,BBB", v.filter().filter().get( "projects" ) );
        assertEquals( null, v.filter().projectId() );
    }

    @Test
    void cursorAndLimitAreNotSaved() {
        assertEquals( "cursor", field( () -> privately( bob, "x", Map.of( "cursor", "abc" ) ) ) );
        assertEquals( "limit", field( () -> privately( bob, "x", Map.of( "limit", "10" ) ) ) );
    }

    @Test
    void aNameNeedsToBeReasonable() {
        assertEquals( "name", field( () -> privately( bob, " ", Map.of() ) ) );
        assertEquals( "name", field( () -> privately( bob, "x".repeat( 81 ), Map.of() ) ) );
        assertEquals( "name", field( () -> privately( bob, "bad\nname", Map.of() ) ) );
        assertEquals( "name", field( () -> privately( bob, null, Map.of() ) ) );
    }

    @Test
    void aDuplicateNameIsExists() throws SQLException {
        privately( bob, "Same", Map.of() );
        final WorkConflictException e = assertThrows( WorkConflictException.class, () -> privately( bob, " Same ", Map.of() ) );
        assertEquals( WorkConflictException.EXISTS, e.reason() );
        privately( lena, "Same", Map.of() );
    }

    @Test
    void onlyTheOwnerRenames() throws SQLException {
        final SavedFilterView v = share( lena, "Team view", "AAA", Map.of() );
        final long id = v.filter().id();
        assertThrows( WorkDeniedException.class, () -> find.rename( bob, id, "Mine now" ) );
        assertThrows( WorkNotFoundException.class, () -> find.rename( mallory, id, "Mine now" ) );
        assertThrows( WorkNotFoundException.class, () -> find.rename( lena, 999_999, "x" ) );
        assertEquals( "Renamed", find.rename( lena, id, "Renamed" ).filter().name() );
        final long priv = privately( lena, "Secret", Map.of() ).filter().id();
        assertThrows( WorkNotFoundException.class, () -> find.rename( bob, priv, "x" ), "someone else's private filter" );
    }

    @Test
    void aLeadDeletesASharedFilterAndAMemberMayNot() throws SQLException {
        svc.putMember( admin, "AAA", "bob", "user", WorkRole.LEAD );
        svc.putMember( admin, "AAA", "carol", "user", WorkRole.MEMBER );
        final Actor carol = Actor.human( "carol", Set.of(), false );
        final long id = share( bob, "Bobs team view", "AAA", Map.of() ).filter().id();
        svc.putMember( admin, "AAA", "bob", "user", WorkRole.MEMBER );
        assertThrows( WorkDeniedException.class, () -> find.delete( carol, id ) );
        assertThrows( WorkDeniedException.class, () -> find.delete( vera, id ) );
        assertFalse( find.saved( carol ).get( 0 ).mayDelete() );
        assertTrue( find.saved( lena ).get( 0 ).mayDelete(), "a lead" );
        assertTrue( find.saved( admin ).get( 0 ).mayDelete(), "a wiki admin" );
        assertTrue( find.saved( bob ).get( 0 ).mayDelete(), "its owner" );
        find.delete( lena, id );
        assertTrue( names( bob ).isEmpty() );
        assertThrows( WorkNotFoundException.class, () -> find.delete( lena, id ), "already gone" );
    }

    @Test
    void anOwnerDeletesTheirOwn() throws SQLException {
        final long id = privately( bob, "x", Map.of() ).filter().id();
        find.delete( bob, id );
        assertTrue( names( bob ).isEmpty() );
    }

    @Test
    void aStrangerDeletingGetsNotFound() throws SQLException {
        final long shared = share( lena, "Team", "AAA", Map.of() ).filter().id();
        final long priv = privately( lena, "Mine", Map.of() ).filter().id();
        assertThrows( WorkNotFoundException.class, () -> find.delete( mallory, shared ) );
        assertThrows( WorkNotFoundException.class, () -> find.delete( mallory, priv ) );
        assertThrows( WorkNotFoundException.class, () -> find.delete( bob, priv ), "a private one of someone else" );
        assertEquals( 2, find.saved( lena ).size(), "nothing was deleted" );
    }

    @Test
    void anAgentHasNoSavedFiltersAndCannotSave() throws SQLException {
        privately( lena, "Mine", Map.of() );
        final long id = share( lena, "Team", "AAA", Map.of() ).filter().id();
        assertTrue( find.saved( codex ).isEmpty() );
        final String text = "Saved filters belong to people.";
        assertEquals( text, assertThrows( WorkDeniedException.class, () -> privately( codex, "x", Map.of() ) ).reason() );
        assertEquals( text, assertThrows( WorkDeniedException.class, () -> share( codex, "x", "AAA", Map.of() ) ).reason() );
        assertEquals( text, assertThrows( WorkDeniedException.class, () -> share( codex, "x", "NOPE", Map.of() ) ).reason(),
            "asked of every agent alike, before any lookup" );
        assertEquals( text, assertThrows( WorkDeniedException.class, () -> find.rename( codex, id, "y" ) ).reason() );
        assertEquals( text, assertThrows( WorkDeniedException.class, () -> find.delete( codex, id ) ).reason() );
    }

    @Test
    void sharingIntoAnArchivedProjectIsArchivedButRenameAndDeleteWork() throws SQLException {
        final long id = share( lena, "Team", "AAA", Map.of() ).filter().id();
        svc.archiveProject( admin, "AAA" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> share( lena, "Another", "AAA", Map.of() ) );
        assertEquals( WorkConflictException.ARCHIVED, e.reason() );
        assertEquals( "Renamed", find.rename( lena, id, "Renamed" ).filter().name() );
        find.delete( lena, id );
        assertTrue( names( lena ).isEmpty() );
    }

    @Test
    void leavingTheProjectHidesItsSharedFilters() throws SQLException {
        share( lena, "Team", "AAA", Map.of() );
        assertEquals( List.of( "Team" ), names( bob ) );
        svc.removeMember( admin, "AAA", "bob", "user" );
        assertTrue( names( bob ).isEmpty() );
        final long id = find.saved( lena ).get( 0 ).filter().id();
        assertThrows( WorkNotFoundException.class, () -> find.delete( bob, id ) );
        assertThrows( WorkNotFoundException.class, () -> find.rename( bob, id, "x" ) );
    }

    @Test
    void aFilterOfAProjectIHaveLeftIsNotListedToMe() throws SQLException {
        share( lena, "Shared in AAA", "AAA", Map.of() );
        find.save( lena, new SavedFilterDraft( "Private in AAA", "AAA", false, Map.of() ) );
        privately( lena, "Everywhere", Map.of() );
        assertEquals( 3, names( lena ).size() );
        svc.removeMember( admin, "AAA", "lena", "user" );
        assertEquals( List.of( "Everywhere" ), names( lena ), "own rows of a project she left are dropped too" );
    }

    @Test
    void aStoredFilterIsNeverRunForItsSaverWhenAnotherRunsIt() throws SQLException {
        final Item l = item( lena, "AAA", "lenas" );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET reporter = 'lena' WHERE id = " + l.id() );
        share( lena, "Reported by me", "AAA", Map.of( "reporter", "me" ) );
        final SortedMap< String, String > stored = find.saved( bob ).get( 0 ).filter().filter();
        assertTrue( find.find( bob, FindParams.parse( stored ) ).items().isEmpty() );
        assertEquals( 1, find.find( lena, FindParams.parse( stored ) ).items().size() );
    }

    @Test
    void anUnrunnableFilterCannotBeSaved() throws SQLException {
        for ( final Map< String, String > bad : List.of( Map.of( "updatedSince", "+999999999-12-31T00:00:00Z" ),
            Map.of( "dueBefore", "+999999999-12-31" ), Map.of( "dueBefore", "1969-01-01" ) ) ) {
            final String name = bad.keySet().iterator().next();
            assertEquals( name, field( () -> privately( lena, "x", bad ) ) );
            assertEquals( name, field( () -> share( lena, "x", "AAA", bad ) ) );
        }
        assertEquals( "9999-12-31T23:59:59Z", privately( lena, "y", Map.of( "updatedSince", "9999-12-31T23:59:59Z" ) )
            .filter().filter().get( "updatedSince" ), "a far-future date in range still saves" );
    }

    @Test
    void anOwnerWhoLeftCannotDeleteTheirFilterThereButALeadCan() throws SQLException {
        final long shared = share( bob, "Bobs team", "AAA", Map.of() ).filter().id();
        final long priv = find.save( bob, new SavedFilterDraft( "Bobs own", "AAA", false, Map.of() ) ).filter().id();
        svc.removeMember( admin, "AAA", "bob", "user" );
        assertThrows( WorkNotFoundException.class, () -> find.delete( bob, shared ) );
        assertThrows( WorkNotFoundException.class, () -> find.delete( bob, priv ) );
        assertEquals( "Bobs team", find.saved( lena ).get( 0 ).filter().name() );
        find.delete( lena, shared );
        assertTrue( find.saved( lena ).isEmpty(), "the lead cleaned up the departed member's shared filter" );
        assertEquals( 1, countRows(), "the private one of a departed member is only theirs" );
    }

    private int countRows() throws SQLException {
        return store.notifyStores().filters().countOwned( "bob" );
    }

    @Test
    void anOwnerMayKeepAtMostOneHundredFilters() throws SQLException {
        for ( int i = 0; i < SavedFilters.MAX_PER_OWNER; i++ ) {
            privately( lena, "f" + i, Map.of() );
        }
        assertEquals( "name", field( () -> privately( lena, "one too many", Map.of() ) ) );
        assertEquals( "name", field( () -> share( lena, "one too many", "AAA", Map.of() ) ) );
        privately( bob, "bobs own", Map.of() );
        find.delete( lena, find.saved( lena ).get( 0 ).filter().id() );
        privately( lena, "now it fits", Map.of() );
        assertEquals( SavedFilters.MAX_PER_OWNER, store.notifyStores().filters().countOwned( "lena" ) );
    }

    @Test
    void filtersBeyondTheListLimitAreStillListedAfterTheOnesOfALeftProject() throws SQLException {
        WorkAgentFixtures.exec( ds, "INSERT INTO work_saved_filter (owner, name, project_id, shared, filter) "
            + "SELECT 'lena', 'a-gone-' || g, ( SELECT id FROM work_project WHERE key = 'BBB' ), false, '{}'::jsonb "
            + "FROM generate_series( 1, 210 ) g" );
        svc.removeMember( admin, "BBB", "lena", "user" );
        WorkAgentFixtures.exec( ds, "INSERT INTO work_saved_filter (owner, name, project_id, shared, filter) "
            + "VALUES ( 'lena', 'z-last', NULL, false, '{}'::jsonb )" );
        assertEquals( List.of( "z-last" ), names( lena ), "listed although 210 rows of a left project sort before it" );
    }
}

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The find facade: it covers exactly the projects the caller can see now, whatever the caller asks for. */
@RequiresPostgres
class WorkFindPostgresTest {

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
        svc.registry().createQueue( admin, "implementer", "Implementer", "" );
        final Agent agent = svc.registry().registerAgent( admin, new AgentDraft( "codex@docker2", "", "",
            List.of( "implementer" ) ) ).agent();
        codex = Actor.agent( agent.id(), agent.name(), bob );
    }

    private Item item( final Actor by, final String project, final String title ) throws SQLException {
        return svc.createItem( by, new ItemDraft( project, WorkLevel.TASK, null, null, WorkKind.SOFTWARE, title, "",
            null, null, null, null, null, null ) ).item();
    }

    private void set( final Item i, final String assignments ) throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET " + assignments + " WHERE id = " + i.id() );
    }

    private static List< String > titles( final FindPage p ) {
        return p.items().stream().map( s -> s.item().title() ).sorted().toList();
    }

    private static FindFilter inProjects( final String... keys ) {
        return new FindFilter( Set.of( keys ), null, null, null, null, null, null, null, null, null, null, false,
            null, null, null, null, 0, null );
    }

    private static FindFilter with( final String assignee, final String reporter, final boolean watching ) {
        return new FindFilter( null, null, null, null, null, null, assignee, null, reporter, null, null, watching,
            null, null, null, null, 0, null );
    }

    @Test
    void resultsNeverIncludeAProjectTheCallerCannotSee() throws SQLException {
        item( lena, "AAA", "alpha" );
        item( bob, "BBB", "beta" );
        assertEquals( List.of( "alpha" ), titles( find.find( lena, FindFilter.all() ) ) );
        assertEquals( new FindPage( List.of(), null ), find.find( lena, inProjects( "BBB" ) ), "a foreign project" );
        assertEquals( find.find( lena, inProjects( "NOPE" ) ), find.find( lena, inProjects( "BBB" ) ),
            "same answer as for one that does not exist" );
        assertEquals( List.of( "alpha" ), titles( find.find( lena, inProjects( "AAA", "BBB" ) ) ) );
        assertEquals( List.of( "alpha" ), titles( find.find( lena, inProjects( "aaa" ) ) ), "any case" );
    }

    @Test
    void membersViewersAndStrangersEachSeeTheirOwnProjects() throws SQLException {
        item( lena, "AAA", "alpha" );
        item( bob, "BBB", "beta" );
        assertEquals( List.of( "alpha", "beta" ), titles( find.find( bob, FindFilter.all() ) ), "a member of two" );
        assertEquals( List.of( "alpha" ), titles( find.find( vera, FindFilter.all() ) ), "a viewer" );
        assertTrue( titles( find.find( vera, inProjects( "BBB" ) ) ).isEmpty() );
    }

    @Test
    void aStrangerGetsAnEmptyPage() throws SQLException {
        item( lena, "AAA", "alpha" );
        assertEquals( FindPage.EMPTY, find.find( mallory, FindFilter.all() ) );
        assertEquals( FindPage.EMPTY, find.find( mallory, inProjects( "AAA" ) ) );
    }

    @Test
    void anAdminSearchesEveryProject() throws SQLException {
        item( lena, "AAA", "alpha" );
        item( bob, "BBB", "beta" );
        assertEquals( List.of( "alpha", "beta" ), titles( find.find( admin, FindFilter.all() ) ) );
    }

    @Test
    void anAgentSeesOnlyWhatItsStandingAllows() throws SQLException {
        item( lena, "AAA", "alpha" );
        item( bob, "BBB", "beta" );
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        svc.putMember( admin, "BBB", "codex@docker2", "agent", WorkRole.MEMBER );
        assertEquals( List.of( "alpha", "beta" ), titles( find.find( codex, FindFilter.all() ) ) );
        svc.removeMember( admin, "AAA", "bob", "user" );
        assertEquals( List.of( "beta" ), titles( find.find( codex, FindFilter.all() ) ), "the sponsor lost AAA" );
        assertTrue( titles( find.find( codex, inProjects( "AAA" ) ) ).isEmpty() );
        final Actor unsponsored = Actor.agent( codex.agentId(), codex.login() );
        assertEquals( FindPage.EMPTY, find.find( unsponsored, FindFilter.all() ), "no sponsor, nothing" );
    }

    @Test
    void meMeansTheCaller() throws SQLException {
        final Item l = item( lena, "AAA", "lenas" );
        final Item b = item( bob, "AAA", "bobs" );
        set( l, "assignee = 'lena'" );
        set( b, "assignee = 'bob'" );
        assertEquals( List.of( "lenas" ), titles( find.find( lena, with( "me", null, false ) ) ) );
        assertEquals( List.of( "bobs" ), titles( find.find( bob, with( "me", null, false ) ) ) );
        assertEquals( List.of( "lenas" ), titles( find.find( lena, with( null, "me", false ) ) ) );
        assertEquals( List.of( "bobs" ), titles( find.find( bob, with( null, "me", false ) ) ) );
    }

    @Test
    void meForAnAgentIsItsClaimsAndItsOwnItems() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        final Item claimed = item( lena, "AAA", "claimed" );
        set( claimed, "claimed_by_agent = " + codex.agentId() + ", claimed_at = NOW(), "
            + "claim_expires_at = NOW() + interval '1 day'" );
        item( lena, "AAA", "other" );
        final Item own = item( codex, "AAA", "created by codex" );
        assertEquals( List.of( "claimed" ), titles( find.find( codex, with( "me", null, false ) ) ) );
        assertEquals( List.of( own.title() ), titles( find.find( codex, with( null, "me", false ) ) ) );
    }

    @Test
    void watchingForAnAgentMatchesNothing() throws SQLException {
        svc.putMember( admin, "AAA", "codex@docker2", "agent", WorkRole.MEMBER );
        final Item i = item( lena, "AAA", "watched" );
        store.notifyStores().watches().watch( i.id(), "lena" );
        store.notifyStores().watches().watch( i.id(), codex.login() );
        assertEquals( List.of( "watched" ), titles( find.find( lena, with( null, null, true ) ) ), "positive control" );
        assertEquals( FindPage.EMPTY, find.find( codex, with( null, null, true ) ) );
    }

    @Test
    void aPersonIsNamedByDisplayName() throws SQLException {
        set( item( lena, "AAA", "lenas" ), "assignee = 'lena'" );
        assertEquals( List.of( "lenas" ), titles( find.find( bob, with( "Lena Lead", null, false ) ) ) );
        assertTrue( titles( find.find( bob, with( "lena", null, false ) ) ).isEmpty(), "a login from a non-admin" );
        assertEquals( List.of( "lenas" ), titles( find.find( admin, with( "lena", null, false ) ) ),
            "from an admin" );
    }

    @Test
    void anUnknownProductOrPersonMatchesNothing() throws SQLException {
        item( lena, "AAA", "alpha" );
        assertEquals( FindPage.EMPTY, find.find( lena, with( "Nobody Known", null, false ) ) );
        assertEquals( FindPage.EMPTY, find.find( lena, with( null, "Nobody Known", false ) ) );
        final FindFilter product = FindParams.parse( java.util.Map.of( "product", "ghost" ) );
        assertEquals( FindPage.EMPTY, find.find( lena, product ) );
    }

    @Test
    void limitDefaultsTo50AndIsCappedAt200() throws SQLException {
        for ( int i = 0; i < 60; i++ ) {
            item( lena, "AAA", "t" + i );
        }
        assertEquals( 50, find.find( lena, FindFilter.all() ).items().size() );
        assertEquals( 60, find.find( lena, FindFilter.all().page( null, 500 ) ).items().size() );
        assertEquals( 5, find.find( lena, FindFilter.all().page( null, 5 ) ).items().size() );
    }

    @Test
    void theLastPageHasNoNextCursor() throws SQLException {
        for ( int i = 0; i < 5; i++ ) {
            item( lena, "AAA", "t" + i );
        }
        final List< Long > seen = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            final FindPage p = find.find( lena, FindFilter.all().page( cursor, 2 ) );
            p.items().forEach( s -> seen.add( s.item().id() ) );
            cursor = p.next();
            pages++;
        } while ( cursor != null && pages < 10 );
        assertEquals( 3, pages );
        assertEquals( 5, new HashSet<>( seen ).size() );
        assertNull( cursor );
        assertNull( find.find( lena, FindFilter.all().page( null, 5 ) ).next(), "exactly one full page: no next" );
    }

    @Test
    void cardExtrasComeFromEachItemsProject() throws SQLException {
        final Item a = item( lena, "AAA", "alpha" );
        final Item b = item( bob, "BBB", "beta" );
        svc.addComment( lena, a.key(), "hello" );
        svc.createProduct( admin, new Product( 0, "web", "Web", null, "main", null, Autonomy.AUTONOMOUS, DeployGate.HUMAN, null ) );
        svc.updateItem( bob, b.key(), new ItemPatch( b.version(), java.util.Map.of( "productKey", "web" ), null ) );
        final List< WorkService.ItemSummary > rows = find.find( admin, FindFilter.all() ).items();
        final WorkService.ItemSummary alpha = rows.stream().filter( s -> s.item().key().equals( a.key() ) ).findFirst()
            .orElseThrow();
        final WorkService.ItemSummary beta = rows.stream().filter( s -> s.item().key().equals( b.key() ) ).findFirst()
            .orElseThrow();
        assertEquals( 1, alpha.commentCount() );
        assertNull( alpha.productKey() );
        assertEquals( "web", beta.productKey() );
        assertEquals( 0, beta.commentCount() );
    }

    @Test
    void archivedProjectsAreSearched() throws SQLException {
        item( lena, "AAA", "alpha" );
        svc.archiveProject( admin, "AAA" );
        assertEquals( List.of( "alpha" ), titles( find.find( lena, FindFilter.all() ) ) );
    }

    @Test
    void aBadCursorIsRefusedTheSameForAStranger() throws SQLException {
        item( lena, "AAA", "alpha" );
        final FindFilter bad = FindFilter.all().page( "not-a-cursor", 0 );
        final WorkValidationException forLena = assertThrows( WorkValidationException.class, () -> find.find( lena, bad ) );
        final WorkValidationException forStranger = assertThrows( WorkValidationException.class,
            () -> find.find( mallory, bad ) );
        assertEquals( forLena.getMessage(), forStranger.getMessage() );
        assertEquals( "cursor", forStranger.field() );
    }

    @Test
    void aCursorNeverWidensWhatACallerCanSee() throws SQLException {
        for ( int i = 0; i < 3; i++ ) {
            item( lena, "AAA", "a" + i );
            item( bob, "BBB", "b" + i );
        }
        final FindPage first = find.find( bob, FindFilter.all().page( null, 2 ) );
        assertEquals( 2, first.items().size() );
        svc.removeMember( admin, "AAA", "bob", "user" );
        final List< String > rest = new ArrayList<>();
        String cursor = first.next();
        int guard = 0;
        while ( cursor != null && guard++ < 10 ) {
            final FindPage p = find.find( bob, FindFilter.all().page( cursor, 2 ) );
            p.items().forEach( s -> rest.add( s.item().key() ) );
            cursor = p.next();
        }
        assertFalse( rest.isEmpty(), "the pages after the first must still come" );
        assertTrue( rest.stream().allMatch( k -> k.startsWith( "BBB-" ) ), rest.toString() );
        assertEquals( 2, rest.size(), "the two BBB items not on the first page" );
    }

    @Test
    void aForeignFilterValueLeaksNothing() throws SQLException {
        final Item foreign = item( bob, "BBB", "beta secret" );
        set( foreign, "labels = ARRAY['secret'], assignee = 'bob', priority = 1" );
        final FindFilter labelled = FindParams.parse( java.util.Map.of( "label", "secret" ) );
        final FindFilter byPerson = with( "Lena Lead", null, false );
        final FindFilter sprint = FindParams.parse( java.util.Map.of( "sprint", "active" ) );
        final FindFilter text = FindParams.parse( java.util.Map.of( "q", "secret" ) );
        for ( final Actor who : List.of( lena, vera, mallory ) ) {
            for ( final FindFilter f : List.of( labelled, byPerson, sprint, text, inProjects( "BBB" ) ) ) {
                assertEquals( FindPage.EMPTY, find.find( who, f ), who.login() + " " + f );
            }
        }
    }
}

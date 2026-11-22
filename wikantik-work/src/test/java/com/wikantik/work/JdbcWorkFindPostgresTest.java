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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cross-project find statement over real PostgreSQL, one filter at a time and then together. */
@RequiresPostgres
class JdbcWorkFindPostgresTest {

    private static final Instant T0 = Instant.parse( "2026-11-16T10:00:00Z" );

    private DataSource ds;
    private JdbcWorkStore store;
    private WorkFindStore finder;
    private Project plat;
    private Project ops;
    private int n;

    /** A spec under construction; every field is optional, as in {@link FindFilter}. */
    private static final class S {
        Set< Long > projects = Set.of();
        Set< WorkStatus > statuses = Set.of();
        Set< WorkLevel > levels = Set.of();
        WorkType type;
        Set< String > labels = Set.of();
        Set< Integer > priorities = Set.of();
        String assignee;
        Long assigneeAgent;
        String queue;
        String reporter;
        Long reporterAgent;
        Long product;
        SprintRef sprint;
        String watcher;
        String text;
        LocalDate dueBefore;
        Instant updatedSince;
        FindFilter.Sort sort = FindFilter.Sort.UPDATED;
        int fetch = 50;
        FindCursor after;

        FindSpec build() {
            return new FindSpec( projects, statuses, levels, type, labels, priorities, assignee, assigneeAgent, queue,
                reporter, reporterAgent, product, sprint, watcher, text, dueBefore, updatedSince, sort, fetch, after );
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        finder = store.notifyStores().finder();
        plat = store.createProject( "PLAT", "Platform", "" );
        ops = store.createProject( "OPS", "Operations", "" );
    }

    private S all() {
        final S s = new S();
        s.projects = Set.of( plat.id(), ops.id() );
        return s;
    }

    private Item item( final Project in, final String title ) throws SQLException {
        return store.createItem( in, JdbcWorkStoreItemsPostgresTest.draft( title ), Actor.human( "alice", Set.of(), false ) );
    }

    private void set( final Item i, final String assignments ) throws SQLException {
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET " + assignments + " WHERE id = " + i.id() );
    }

    private List< String > titles( final S s ) throws SQLException {
        return finder.find( s.build() ).stream().map( Item::title ).sorted().toList();
    }

    @Test
    void onlyTheGivenProjectsAreSearched() throws SQLException {
        item( plat, "p" );
        item( ops, "o" );
        final S s = all();
        s.projects = Set.of( ops.id() );
        assertEquals( List.of( "o" ), titles( s ) );
        assertEquals( List.of( "o", "p" ), titles( all() ) );
    }

    @Test
    void noProjectsRunsNoQueryAndFindsNothing() throws SQLException {
        item( plat, "p" );
        final S s = new S();
        assertTrue( finder.find( s.build() ).isEmpty() );
    }

    @Test
    void statusesNarrow() throws SQLException {
        final Item a = item( plat, "a" );
        item( plat, "b" );
        set( a, "status = 'ready'" );
        final S s = all();
        s.statuses = Set.of( WorkStatus.READY );
        assertEquals( List.of( "a" ), titles( s ) );
    }

    @Test
    void levelsNarrow() throws SQLException {
        item( plat, "t" );
        store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( WorkLevel.EPIC, "e" ),
            Actor.human( "alice", Set.of(), false ) );
        final S s = all();
        s.levels = Set.of( WorkLevel.EPIC );
        assertEquals( List.of( "e" ), titles( s ) );
    }

    @Test
    void typeNarrows() throws SQLException {
        item( plat, "x" );
        set( item( plat, "bug" ), "type = 'bug'" );
        final S s = all();
        s.type = WorkType.BUG;
        assertEquals( List.of( "bug" ), titles( s ) );
    }

    @Test
    void labelsMustAllBeCarried() throws SQLException {
        set( item( plat, "ui" ), "labels = ARRAY['ui']" );
        set( item( plat, "both" ), "labels = ARRAY['ui','billing']" );
        set( item( plat, "billing" ), "labels = ARRAY['billing']" );
        final S s = all();
        s.labels = Set.of( "ui", "billing" );
        assertEquals( List.of( "both" ), titles( s ) );
    }

    @Test
    void prioritiesNarrow() throws SQLException {
        set( item( plat, "one" ), "priority = 1" );
        set( item( plat, "two" ), "priority = 2" );
        item( plat, "three" );
        final S s = all();
        s.priorities = Set.of( 1, 2 );
        assertEquals( List.of( "one", "two" ), titles( s ) );
    }

    @Test
    void assigneeLoginNarrows() throws SQLException {
        set( item( plat, "mine" ), "assignee = 'lena'" );
        set( item( plat, "his" ), "assignee = 'bob'" );
        final S s = all();
        s.assignee = "lena";
        assertEquals( List.of( "mine" ), titles( s ) );
    }

    @Test
    void assigneeAgentMatchesTheClaimHolder() throws SQLException {
        final long agent = WorkAgentFixtures.agent( ds, "codex" );
        set( item( plat, "held" ), "claimed_by_agent = " + agent + ", claimed_at = NOW(), claim_expires_at = NOW() + interval '1 day'" );
        item( plat, "free" );
        final S s = all();
        s.assigneeAgent = agent;
        assertEquals( List.of( "held" ), titles( s ) );
    }

    @Test
    void queueNarrows() throws SQLException {
        WorkAgentFixtures.queue( ds, "implementer" );
        set( item( plat, "queued" ), "assignee_queue = 'implementer'" );
        item( plat, "other" );
        final S s = all();
        s.queue = "implementer";
        assertEquals( List.of( "queued" ), titles( s ) );
    }

    @Test
    void reporterLoginIgnoresAgentCreatedItems() throws SQLException {
        final long agent = WorkAgentFixtures.agent( ds, "codex" );
        set( item( plat, "person" ), "reporter = 'lena'" );
        set( item( plat, "agent" ), "reporter = 'lena', reporter_agent_id = " + agent );
        final S s = all();
        s.reporter = "lena";
        assertEquals( List.of( "person" ), titles( s ) );
    }

    @Test
    void reporterAgentMatchesById() throws SQLException {
        final long agent = WorkAgentFixtures.agent( ds, "codex" );
        final long other = WorkAgentFixtures.agent( ds, "other" );
        set( item( plat, "a" ), "reporter_agent_id = " + agent );
        set( item( plat, "b" ), "reporter_agent_id = " + other );
        final S s = all();
        s.reporterAgent = agent;
        assertEquals( List.of( "a" ), titles( s ) );
    }

    @Test
    void productNarrows() throws SQLException {
        final Product p = store.createProduct( new Product( 0, "web", "Web", null, "main", null, Autonomy.AUTONOMOUS,
            DeployGate.HUMAN, null ) );
        set( item( plat, "with" ), "product_id = " + p.id() );
        item( plat, "without" );
        final S s = all();
        s.product = p.id();
        assertEquals( List.of( "with" ), titles( s ) );
    }

    private long sprint( final Project in, final String state ) throws SQLException {
        final String started = "planned".equals( state ) ? "NULL, NULL" : "NOW(), 0";
        return WorkAgentFixtures.scalar( ds, "INSERT INTO work_sprint (project_id, name, starts_on, ends_on, state, "
            + "started_at, committed_items) VALUES (" + in.id() + ", 'S" + ( n++ ) + "', '2026-11-01', '2026-11-14', '"
            + state + "', " + started + ") RETURNING id" );
    }

    @Test
    void sprintIdActiveAndNone() throws SQLException {
        final long active = sprint( plat, "active" );
        final long planned = sprint( ops, "planned" );
        set( item( plat, "inActive" ), "sprint_id = " + active );
        set( item( ops, "inPlanned" ), "sprint_id = " + planned );
        item( plat, "loose" );
        final S s = all();
        s.sprint = new SprintRef( SprintRef.Kind.ID, planned );
        assertEquals( List.of( "inPlanned" ), titles( s ) );
        s.sprint = new SprintRef( SprintRef.Kind.ACTIVE, null );
        assertEquals( List.of( "inActive" ), titles( s ) );
        s.sprint = new SprintRef( SprintRef.Kind.NONE, null );
        assertEquals( List.of( "loose" ), titles( s ) );
    }

    @Test
    void watchingKeepsWatchedNotMuted() throws SQLException {
        final Item watched = item( plat, "watched" );
        final Item muted = item( plat, "muted" );
        item( plat, "ignored" );
        store.notifyStores().watches().watch( watched.id(), "lena" );
        store.notifyStores().watches().mute( muted.id(), "lena" );
        final S s = all();
        s.watcher = "lena";
        assertEquals( List.of( "watched" ), titles( s ) );
    }

    @Test
    void textMatchesTitleOrBodyAndEscapesWildcards() throws SQLException {
        item( plat, "50% done" );
        item( plat, "500 done" );
        set( item( plat, "plain" ), "body = 'mentions 50% here'" );
        final S s = all();
        s.text = "50%";
        assertEquals( List.of( "50% done", "plain" ), titles( s ) );
        s.text = "Mentions";
        assertEquals( List.of( "plain" ), titles( s ), "case-insensitive, body included" );
        s.text = "'; DROP TABLE work_item; --";
        assertTrue( titles( s ).isEmpty(), "text is a bound parameter" );
        assertEquals( 3, titles( all() ).size() );
    }

    @Test
    void dueBeforeLeavesOutUndated() throws SQLException {
        set( item( plat, "soon" ), "due_on = '2026-11-20'" );
        set( item( plat, "later" ), "due_on = '2026-12-20'" );
        item( plat, "undated" );
        final S s = all();
        s.dueBefore = LocalDate.parse( "2026-12-01" );
        assertEquals( List.of( "soon" ), titles( s ) );
    }

    @Test
    void updatedSinceIsInclusive() throws SQLException {
        set( item( plat, "old" ), "updated_at = '" + T0.minusSeconds( 1 ) + "'" );
        set( item( plat, "edge" ), "updated_at = '" + T0 + "'" );
        set( item( plat, "new" ), "updated_at = '" + T0.plusSeconds( 1 ) + "'" );
        final S s = all();
        s.updatedSince = T0;
        assertEquals( List.of( "edge", "new" ), titles( s ) );
    }

    @Test
    void twoFiltersCombine() throws SQLException {
        final Item both = item( plat, "both" );
        set( both, "status = 'ready', assignee = 'lena'" );
        set( item( plat, "statusOnly" ), "status = 'ready'" );
        set( item( plat, "assigneeOnly" ), "assignee = 'lena'" );
        final S s = all();
        s.statuses = Set.of( WorkStatus.READY );
        s.assignee = "lena";
        assertEquals( List.of( "both" ), titles( s ) );
    }

    @Test
    void eachSortOrdersAsDocumented() throws SQLException {
        final Item a = item( plat, "a" );
        final Item b = item( plat, "b" );
        final Item c = item( ops, "c" );
        set( a, "updated_at = '" + T0 + "', created_at = '" + T0.plusSeconds( 2 ) + "', priority = 3, due_on = '2026-12-02'" );
        set( b, "updated_at = '" + T0.plusSeconds( 2 ) + "', created_at = '" + T0 + "', priority = 1" );
        set( c, "updated_at = '" + T0.plusSeconds( 1 ) + "', created_at = '" + T0.plusSeconds( 1 ) + "', priority = 2, "
            + "due_on = '2026-12-01'" );
        assertEquals( List.of( "b", "c", "a" ), ordered( FindFilter.Sort.UPDATED ) );
        assertEquals( List.of( "a", "c", "b" ), ordered( FindFilter.Sort.CREATED ) );
        assertEquals( List.of( "b", "c", "a" ), ordered( FindFilter.Sort.PRIORITY ) );
        assertEquals( List.of( "c", "a", "b" ), ordered( FindFilter.Sort.DUE ), "undated last" );
    }

    private List< String > ordered( final FindFilter.Sort sort ) throws SQLException {
        final S s = all();
        s.sort = sort;
        return finder.find( s.build() ).stream().map( Item::title ).toList();
    }

    @Test
    void fetchBoundsTheRows() throws SQLException {
        item( plat, "a" );
        item( plat, "b" );
        final S s = all();
        s.fetch = 1;
        assertEquals( 1, finder.find( s.build() ).size() );
    }

    @Test
    void pagingIsStableWhenRowsTie() throws SQLException {
        for ( int i = 0; i < 7; i++ ) {
            set( item( i % 2 == 0 ? plat : ops, "t" + i ),
                "updated_at = '" + T0 + "', created_at = '" + T0 + "', priority = 3, due_on = NULL" );
        }
        for ( final FindFilter.Sort sort : FindFilter.Sort.values() ) {
            final List< Long > seen = new ArrayList<>();
            final List< Integer > sizes = new ArrayList<>();
            FindCursor after = null;
            for ( int guard = 0; guard < 5; guard++ ) {
                final S s = all();
                s.sort = sort;
                s.fetch = 3;
                s.after = after;
                final List< Item > page = finder.find( s.build() );
                if ( page.isEmpty() ) {
                    break;
                }
                sizes.add( page.size() );
                page.forEach( i -> seen.add( i.id() ) );
                after = FindCursor.decode( FindCursor.after( page.get( page.size() - 1 ), sort ).encode(), sort );
            }
            assertEquals( List.of( 3, 3, 1 ), sizes, sort.toString() );
            assertEquals( 7, new HashSet<>( seen ).size(), sort + " repeats or loses rows" );
        }
    }

    private List< Item > pageAfter( final FindFilter.Sort sort, final int fetch, final Item last ) throws SQLException {
        final S s = all();
        s.sort = sort;
        s.fetch = fetch;
        s.after = last == null ? null : FindCursor.decode( FindCursor.after( last, sort ).encode(), sort );
        return finder.find( s.build() );
    }

    @Test
    void pagingWalksTheSameOrderAsOnePageWhenSortValuesDiffer() throws SQLException {
        for ( int i = 0; i < 9; i++ ) {
            set( item( i % 2 == 0 ? plat : ops, "d" + i ), "updated_at = '" + T0.plusSeconds( i * 7L % 9 ) + "', "
                + "created_at = '" + T0.plusSeconds( i * 4L % 9 ) + "', priority = " + ( i % 4 + 1 )
                + ", due_on = " + ( i == 8 ? "NULL" : "'2026-12-" + String.format( "%02d", 1 + i * 5 % 9 ) + "'" ) );
        }
        for ( final FindFilter.Sort sort : FindFilter.Sort.values() ) {
            final List< Long > whole = pageAfter( sort, 50, null ).stream().map( Item::id ).toList();
            final List< Long > walked = new ArrayList<>();
            Item last = null;
            for ( int guard = 0; guard < 6; guard++ ) {
                final List< Item > page = pageAfter( sort, 2, last );
                if ( page.isEmpty() ) {
                    break;
                }
                page.forEach( i -> walked.add( i.id() ) );
                last = page.get( page.size() - 1 );
            }
            assertEquals( 9, whole.size(), sort.toString() );
            assertEquals( whole, walked, sort + " must walk the order of one big page" );
        }
    }

    @Test
    void anItemUpdatedBetweenPagesIsNeverRepeatedAndOnlyTheUpdatedOneIsMissed() throws SQLException {
        final List< Item > items = new ArrayList<>();
        for ( int i = 0; i < 7; i++ ) {
            final Item it = item( i % 2 == 0 ? plat : ops, "u" + i );
            set( it, "updated_at = '" + T0.plusSeconds( i ) + "'" );
            items.add( it );
        }
        final List< Item > first = pageAfter( FindFilter.Sort.UPDATED, 3, null );
        assertEquals( List.of( items.get( 6 ).id(), items.get( 5 ).id(), items.get( 4 ).id() ),
            first.stream().map( Item::id ).toList() );
        final long unseen = items.get( 1 ).id();
        final long seenAlready = items.get( 5 ).id();
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET updated_at = '" + T0.plusSeconds( 100 ) + "' WHERE id IN ("
            + unseen + "," + seenAlready + ")" );
        final List< Long > rest = new ArrayList<>();
        Item last = first.get( 2 );
        for ( int guard = 0; guard < 5; guard++ ) {
            final List< Item > page = pageAfter( FindFilter.Sort.UPDATED, 3, last );
            if ( page.isEmpty() ) {
                break;
            }
            page.forEach( i -> rest.add( i.id() ) );
            last = page.get( page.size() - 1 );
        }
        assertEquals( List.of( items.get( 3 ).id(), items.get( 2 ).id(), items.get( 0 ).id() ), rest,
            "newest-first: an updated item jumps ahead of the boundary, so it is not repeated (the unseen one is missed)" );
    }
}

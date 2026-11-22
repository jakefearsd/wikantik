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
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The sprint a patch may name ({@code ItemTargets.checkSprint}), driven through the real applier, plus the
 * statement counts of the sprint lock an item write takes.
 */
@RequiresPostgres
class ItemSprintTargetsPostgresTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final LocalDate D1 = LocalDate.of( 2026, 11, 2 );

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;
    private ItemPatchApplier applier;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
        applier = new ItemPatchApplier( store, ( projectId, login ) -> true );
    }

    private Sprint sprint( final Project p, final String name ) throws SQLException {
        return store.sprints().create( p, new SprintDraft( name, "", D1, D1.plusDays( 10 ) ), LENA );
    }

    private Sprint closedSprint( final String name ) throws SQLException {
        final Sprint s = sprint( plat, name );
        return store.sprints().close( s.id(), store.sprints().start( s.id(), 1, LENA ).version(), null, LENA );
    }

    private Item item( final String title ) throws SQLException {
        return store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( title ), LENA );
    }

    private Item patch( final Item i, final String key, final Object value ) throws SQLException {
        final Map< String, Object > changes = new HashMap<>();
        changes.put( key, value );
        return applier.apply( plat, i, changes, null );
    }

    @Test
    void anUnknownSprintIsASprintValidationError() throws SQLException {
        final Item i = item( "a" );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> patch( i, "sprint", 987654L ) );
        assertEquals( "sprint", e.field() );
    }

    @Test
    void anotherProjectsSprintIsASprintValidationError() throws SQLException {
        final Project other = store.createProject( "OTH", "Other", "" );
        final Sprint foreign = sprint( other, "F" );
        final Item i = item( "a" );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> patch( i, "sprint", foreign.id() ) );
        assertEquals( "sprint", e.field() );
    }

    @Test
    void joiningAClosedSprintIsRefused() throws SQLException {
        final Sprint closed = closedSprint( "Old" );
        final Item i = item( "a" );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> patch( i, "sprint", closed.id() ) );
        assertEquals( WorkConflictException.SPRINT_CLOSED, e.reason() );
    }

    @Test
    void anItemMayStayInAClosedSprintAndMayLeaveIt() throws SQLException {
        final Sprint s = sprint( plat, "S1" );
        final Item i = item( "a" );
        final Item in = store.updateItem( i, patch( i, "sprint", s.id() ), LENA, null );
        // done work stays in a closed sprint
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET status = 'done', closed_at = NOW() WHERE id = " + in.id() );
        final Sprint active = store.sprints().start( s.id(), 1, LENA );
        store.sprints().close( s.id(), active.version(), null, LENA );
        final Item stuck = store.itemById( in.id() ).orElseThrow();
        assertEquals( s.id(), stuck.sprintId() );
        final Item renamed = patch( stuck, "title", "renamed" );
        assertEquals( s.id(), renamed.sprintId() );
        assertEquals( s.id(), store.updateItem( stuck, renamed, LENA, null ).sprintId() );
        final Item current = store.itemById( in.id() ).orElseThrow();
        final Item left = store.updateItem( current, patch( current, "sprint", null ), LENA, null );
        assertNull( left.sprintId() );
    }

    private Item statusChanged( final Item i, final WorkStatus status ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), status, i.priority(), i.rank(), i.dueOn(), i.assignee(), i.reporter(), i.autonomy(),
            i.doneMeans(), i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(),
            i.claimedByAgent(), i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(),
            i.sprintId(), i.estimate() );
    }

    /** The statements one write issues, counted on a fresh store. */
    private int statementsOf( final Item cur, final Item next ) throws SQLException {
        final StatementProbe counter = new StatementProbe( ds );
        new JdbcWorkStore( counter ).updateItem( cur, next, LENA, null );
        return counter.statements();
    }

    @Test
    void aTitleOnlyEditOfASprintItemIssuesNoSprintStatement() throws SQLException {
        final Sprint s = sprint( plat, "S1" );
        final Item i = item( "a" );
        final Item in = store.updateItem( i, patch( i, "sprint", s.id() ), LENA, null );
        final Item plain = item( "b" );
        final int inSprint = statementsOf( in, patch( in, "title", "t1" ) );
        final int noSprint = statementsOf( plain, patch( plain, "title", "t1" ) );
        assertEquals( noSprint, inSprint );
    }

    @Test
    void aStatusChangeOfASprintItemIssuesExactlyOneSprintLockStatement() throws SQLException {
        final Sprint s = sprint( plat, "S1" );
        final Item i = item( "a" );
        final Item in = store.updateItem( i, patch( i, "sprint", s.id() ), LENA, null );
        final Item plain = item( "b" );
        final int inSprint = statementsOf( in, statusChanged( in, WorkStatus.READY ) );
        final int noSprint = statementsOf( plain, statusChanged( plain, WorkStatus.READY ) );
        assertEquals( noSprint + 1, inSprint );
    }

    @Test
    void anEstimateChangeOfASprintItemIssuesExactlyOneSprintLockStatement() throws SQLException {
        final Sprint s = sprint( plat, "S1" );
        final Item i = item( "a" );
        final Item in = store.updateItem( i, patch( i, "sprint", s.id() ), LENA, null );
        final Item plain = item( "b" );
        final int inSprint = statementsOf( in, patch( in, "estimate", 3.0 ) );
        final int noSprint = statementsOf( plain, patch( plain, "estimate", 3.0 ) );
        assertEquals( noSprint + 1, inSprint );
    }
}

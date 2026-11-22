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

import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Sprint writes are all-or-nothing: a failing statement leaves nothing behind. */
@RequiresPostgres
class JdbcWorkSprintsRollbackTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final SprintDraft DRAFT = new SprintDraft( "S1", "goal", LocalDate.of( 2026, 11, 2 ),
        LocalDate.of( 2026, 11, 13 ) );

    private DataSource ds;
    private JdbcWorkStore store;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        store = new JdbcWorkStore( ds );
        plat = store.createProject( "PLAT", "Platform", "" );
    }

    private FaultInjectingDataSource faultOn( final int statement ) {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( ds );
        faulty.failOn( statement, new RuntimeException( "boom: statement " + statement ) );
        return faulty;
    }

    private long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private Item itemIn( final Sprint s, final String title ) throws SQLException {
        final Item i = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( title ), LENA );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET status = 'ready', sprint_id = " + s.id()
            + " WHERE id = " + i.id() );
        return store.itemById( i.id() ).orElseThrow();
    }

    @Test
    void aFaultOnTheCreatedEventLeavesNoSprint() throws SQLException {
        final FaultInjectingDataSource faulty = faultOn( 2 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).sprints().create( plat, DRAFT, LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( 0, count( "SELECT count(*) FROM work_sprint" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'sprint_created'" ) );
    }

    @Test
    void aFaultOnTheUpdatedEventLeavesTheSprintUnchanged() throws SQLException {
        final Sprint s = store.sprints().create( plat, DRAFT, LENA );
        final FaultInjectingDataSource faulty = faultOn( 3 );
        final SprintDraft edit = new SprintDraft( "Renamed", "other", DRAFT.startsOn(), DRAFT.endsOn() );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).sprints().update( s.id(), 1, edit, LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( s, store.sprints().byId( s.id() ).orElseThrow() );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'sprint_updated'" ) );
    }

    @Test
    void aFaultOnTheStartedEventLeavesTheSprintPlannedWithNoCommitment() throws SQLException {
        final Sprint s = store.sprints().create( plat, DRAFT, LENA );
        itemIn( s, "a" );
        final FaultInjectingDataSource faulty = faultOn( 5 );
        assertThrows( RuntimeException.class, () -> new JdbcWorkStore( faulty ).sprints().start( s.id(), 1, LENA ) );
        assertEquals( 1, faulty.rollbacks() );
        final Sprint after = store.sprints().byId( s.id() ).orElseThrow();
        assertEquals( SprintState.PLANNED, after.state() );
        assertNull( after.committedItems() );
        assertNull( after.startedAt() );
        assertEquals( 1, after.version() );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'sprint_started'" ) );
    }

    @Test
    void aFaultOnTheThirdMovedItemLeavesTheSprintActiveAndNothingMoved() throws SQLException {
        final Sprint s = store.sprints().create( plat, DRAFT, LENA );
        final List< Item > items = new ArrayList<>();
        for ( final String t : List.of( "a", "b", "c", "d" ) ) {
            items.add( itemIn( s, t ) );
        }
        final Sprint active = store.sprints().start( s.id(), 1, LENA );
        // statements: 1 lock sprint, 2 lock items, 3 update sprint, 4 closed event, then update + event per item:
        // 5/6 first, 7/8 second, 9 the third item's update
        final FaultInjectingDataSource faulty = faultOn( 9 );
        final RuntimeException thrown = assertThrows( RuntimeException.class,
            () -> new JdbcWorkStore( faulty ).sprints().close( s.id(), active.version(), null, LENA ) );
        assertEquals( "boom: statement 9", thrown.getMessage(), "the third item's update failed, nothing else" );
        assertEquals( 1, faulty.rollbacks() );
        assertEquals( active, store.sprints().byId( s.id() ).orElseThrow() );
        assertNull( store.sprints().byId( s.id() ).orElseThrow().completedItems() );
        for ( final Item i : items ) {
            final Item after = store.itemById( i.id() ).orElseThrow();
            assertEquals( s.id(), after.sprintId() );
            assertEquals( i.version(), after.version() );
        }
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'sprint_closed'" ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'field' AND note = 'sprint'" ) );
    }
}

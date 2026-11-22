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
import com.wikantik.work.WorkSprintsService.SprintView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sprint service over real PostgreSQL: who may do what, where a sprint can be reached from, and the figures. */
@RequiresPostgres
class WorkSprintsServicePostgresTest {

    private static final LocalDate DAY = LocalDate.of( 2026, 12, 1 );
    private static final String LEAD_NEEDED = "This action needs the lead role; you hold member.";
    private static final String PERSON_PLANS = "A person plans a sprint.";

    private GateFixture fx;
    private WorkSprintsService sprints;

    @BeforeEach
    void setUp() throws SQLException {
        fx = new GateFixture();
        sprints = fx.svc.features().sprints();
    }

    private static SprintDraft draft( final String name ) {
        return new SprintDraft( name, "", DAY, DAY.plusDays( 13 ) );
    }

    private SprintView create( final String name ) throws SQLException {
        return sprints.create( fx.lena, "PLAT", draft( name ) );
    }

    private long sprintId( final String name ) throws SQLException {
        return create( name ).sprint().id();
    }

    private Item task() throws SQLException {
        return fx.task();
    }

    private Item plan( final Item i, final long sprintId ) throws SQLException {
        return fx.set( fx.mia, i, "sprint", sprintId );
    }

    private Item story( final String parent, final Object estimate ) throws SQLException {
        final Item s = fx.svc.createItem( fx.lena, new ItemDraft( "PLAT", WorkLevel.STORY, parent, "wikantik",
            WorkKind.SOFTWARE, "A story", "", null, null, null, null, null, null ) ).item();
        return estimate == null ? s : fx.set( fx.lena, s, "estimate", estimate );
    }

    private Item childTask( final String parent, final Object estimate ) throws SQLException {
        final Item t = fx.svc.createItem( fx.lena, new ItemDraft( "PLAT", WorkLevel.TASK, parent, "wikantik",
            WorkKind.SOFTWARE, "A task", "", null, null, null, null, null, null ) ).item();
        return estimate == null ? t : fx.set( fx.lena, t, "estimate", estimate );
    }

    @Test
    void aLeadCreatesStartsAndClosesASprint() throws SQLException {
        final SprintView made = sprints.create( fx.lena, "PLAT", new SprintDraft( " Sprint 1 ", " ship ", DAY,
            DAY.plusDays( 13 ) ) );
        assertEquals( "Sprint 1", made.sprint().name() );
        assertEquals( "ship", made.sprint().goal() );
        assertEquals( SprintState.PLANNED, made.sprint().state() );
        final Item t = fx.set( fx.lena, task(), "estimate", 3 );
        plan( t, made.sprint().id() );
        final SprintView started = sprints.start( fx.lena, made.sprint().id(), made.sprint().version() );
        assertEquals( SprintState.ACTIVE, started.sprint().state() );
        assertEquals( 1, started.items() );
        assertEquals( new BigDecimal( "3.0" ), started.points() );
        final SprintView closed = sprints.close( fx.lena, made.sprint().id(), started.sprint().version(), null );
        assertEquals( SprintState.CLOSED, closed.sprint().state() );
        assertEquals( 1, sprints.list( fx.lena, "PLAT" ).size() );
        assertEquals( closed.sprint().id(), sprints.get( fx.mia, made.sprint().id() ).sprint().id() );
    }

    @Test
    void aLeadEditsAPlannedSprintAndTheNameIsNormalised() throws SQLException {
        final SprintView made = create( "S1" );
        final SprintView edited = sprints.update( fx.lena, made.sprint().id(), made.sprint().version(),
            new SprintDraft( "S1b", "g", DAY, DAY.plusDays( 6 ) ) );
        assertEquals( "S1b", edited.sprint().name() );
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> sprints.create( fx.lena, "PLAT", new SprintDraft( "  ", "", DAY, DAY ) ) );
        assertEquals( "name", e.field() );
        assertEquals( "endsOn", assertThrows( WorkValidationException.class,
            () -> sprints.create( fx.lena, "PLAT", new SprintDraft( "x", "", DAY, DAY.minusDays( 1 ) ) ) ).field() );
        assertEquals( "goal", assertThrows( WorkValidationException.class,
            () -> sprints.create( fx.lena, "PLAT", new SprintDraft( "x", "g".repeat( 501 ), DAY, DAY ) ) ).field() );
        assertEquals( "A sprint needs a start and an end date.", assertThrows( WorkValidationException.class,
            () -> sprints.create( fx.lena, "PLAT", new SprintDraft( "x", "", null, DAY ) ) ).getMessage() );
    }

    @Test
    void aMemberCannotManageASprint() throws SQLException {
        final SprintView made = create( "S1" );
        final long id = made.sprint().id();
        final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
            () -> sprints.create( fx.mia, "PLAT", draft( "S2" ) ),
            () -> sprints.update( fx.mia, id, 1, draft( "S2" ) ),
            () -> sprints.start( fx.mia, id, 1 ),
            () -> sprints.close( fx.mia, id, 1, null ) );
        for ( final var attempt : attempts ) {
            assertEquals( LEAD_NEEDED, assertThrows( WorkDeniedException.class, attempt ).reason() );
        }
    }

    @Test
    void aMemberPlansAnItemIntoASprintAndOutAgain() throws SQLException {
        final long id = sprintId( "S1" );
        final Item in = plan( task(), id );
        assertEquals( id, in.sprintId() );
        assertEquals( 1, sprints.get( fx.mia, id ).items() );
        final Item out = fx.set( fx.mia, in, "sprint", null );
        assertEquals( null, out.sprintId() );
        assertEquals( 0, sprints.get( fx.mia, id ).items() );
    }

    @Test
    void aViewerCannotPlanAnItem() throws SQLException {
        final Actor viewer = Actor.human( "vic", Set.of(), false );
        fx.svc.putMember( fx.admin, "PLAT", "vic", "user", WorkRole.VIEWER );
        final Item t = task();
        final long id = sprintId( "S1" );
        assertThrows( WorkDeniedException.class, () -> fx.set( viewer, t, "sprint", id ) );
        assertEquals( null, fx.fresh( t ).sprintId() );
    }

    @Test
    void anAgentCannotPlanAnItemOrManageASprint() throws SQLException {
        final Item t = task();
        final long id = sprintId( "S1" );
        assertEquals( PERSON_PLANS, assertThrows( WorkDeniedException.class,
            () -> fx.set( fx.author, t, "sprint", id ) ).reason() );
        assertEquals( PERSON_PLANS, assertThrows( WorkDeniedException.class,
            () -> fx.set( fx.author, t, "sprint", null ) ).reason() );
        final List< org.junit.jupiter.api.function.Executable > attempts = List.of(
            () -> sprints.create( fx.author, "PLAT", draft( "S2" ) ),
            () -> sprints.update( fx.author, id, 1, draft( "S2" ) ),
            () -> sprints.start( fx.author, id, 1 ),
            () -> sprints.close( fx.author, id, 1, null ) );
        for ( final var attempt : attempts ) {
            assertEquals( PERSON_PLANS, assertThrows( WorkDeniedException.class, attempt ).reason() );
        }
    }

    @Test
    void anAgentMayEstimateAnItemItMayEdit() throws SQLException {
        final Item t = task();
        assertEquals( new BigDecimal( "2.5" ), fx.set( fx.author, t, "estimate", 2.5 ).estimate() );
    }

    @Test
    void anAgentFencedByAnotherAgentsClaimCannotEstimate() throws SQLException {
        final Item held = fx.claimedBy( fx.author, task() );
        final WorkDeniedException e = assertThrows( WorkDeniedException.class,
            () -> fx.set( fx.reviewer, held, "estimate", 3 ) );
        assertEquals( "This item is claimed by another agent.", e.reason() );
    }

    @Test
    void anEstimateOutsideHalvesIsRefusedNamingEstimate() throws SQLException {
        final Item t = task();
        for ( final Object bad : new Object[] { 2.3, -1, 1000, "x" } ) {
            assertEquals( "estimate", assertThrows( WorkValidationException.class,
                () -> fx.set( fx.lena, t, "estimate", bad ) ).field() );
        }
    }

    @Test
    void anEpicCannotBeEstimatedOrPlanned() throws SQLException {
        final Item epic = fx.draft( WorkLevel.EPIC, null );
        final long id = sprintId( "S1" );
        assertEquals( "estimate", assertThrows( WorkValidationException.class,
            () -> fx.set( fx.lena, epic, "estimate", 3 ) ).field() );
        assertEquals( "sprint", assertThrows( WorkValidationException.class,
            () -> fx.set( fx.lena, epic, "sprint", id ) ).field() );
    }

    @Test
    void aSprintOfAnotherProjectIsNoSuchSprint() throws SQLException {
        fx.svc.createProject( fx.admin, "OTHR", "Other", "" );
        final long foreign = sprints.create( fx.admin, "OTHR", draft( "F1" ) ).sprint().id();
        final Item t = task();
        final WorkValidationException e = assertThrows( WorkValidationException.class,
            () -> fx.set( fx.lena, t, "sprint", foreign ) );
        assertEquals( "sprint", e.field() );
        assertEquals( "sprint", assertThrows( WorkValidationException.class,
            () -> fx.set( fx.lena, t, "sprint", 999_999L ) ).field() );
    }

    @Test
    void aClosedSprintTakesNoItems() throws SQLException {
        final SprintView made = create( "S1" );
        final SprintView started = sprints.start( fx.lena, made.sprint().id(), made.sprint().version() );
        sprints.close( fx.lena, made.sprint().id(), started.sprint().version(), null );
        final Item t = task();
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> plan( t, made.sprint().id() ) );
        assertEquals( WorkConflictException.SPRINT_CLOSED, e.reason() );
    }

    @Test
    void closeToABadTargetIsBadTarget() throws SQLException {
        final SprintView a = create( "S1" );
        final SprintView started = sprints.start( fx.lena, a.sprint().id(), a.sprint().version() );
        final WorkConflictException e = assertThrows( WorkConflictException.class,
            () -> sprints.close( fx.lena, a.sprint().id(), started.sprint().version(), 999_999L ) );
        assertEquals( WorkConflictException.BAD_TARGET, e.reason() );
        assertEquals( SprintState.ACTIVE, sprints.get( fx.lena, a.sprint().id() ).sprint().state() );
    }

    @Test
    void everyReadAsAStrangerIsNotFound() throws SQLException {
        final long id = sprintId( "S1" );
        task();
        fx.svc.createProject( fx.admin, "OTHR", "Other", "" );
        final long foreign = sprints.create( fx.admin, "OTHR", draft( "F1" ) ).sprint().id();
        final Actor mallory = Actor.human( "mallory", Set.of(), false );
        final Actor stranger = Actor.agent( fx.author.agentId(), "author", Actor.human( "mallory", Set.of(), false ) );
        final String message = assertThrows( WorkNotFoundException.class,
            () -> sprints.get( fx.lena, 999_999L ) ).getMessage();
        for ( final Actor who : List.of( mallory, stranger ) ) {
            final List< org.junit.jupiter.api.function.Executable > reads = List.of(
                () -> sprints.list( who, "PLAT" ), () -> sprints.get( who, id ),
                () -> sprints.velocity( who, "PLAT" ), () -> sprints.childPoints( who, "PLAT-1" ),
                () -> sprints.get( who, 999_999L ) );
            for ( final var read : reads ) {
                assertEquals( message, assertThrows( WorkNotFoundException.class, read, who.login() ).getMessage() );
            }
        }
        assertEquals( message, assertThrows( WorkNotFoundException.class,
            () -> sprints.get( fx.lena, foreign ) ).getMessage() );
        assertEquals( id, sprints.get( fx.lena, id ).sprint().id() );
    }

    @Test
    void everyWriteAsAStrangerIsNotFound() throws SQLException {
        final long id = sprintId( "S1" );
        final Actor mallory = Actor.human( "mallory", Set.of(), false );
        final List< org.junit.jupiter.api.function.Executable > writes = List.of(
            () -> sprints.create( mallory, "PLAT", draft( "S2" ) ), () -> sprints.update( mallory, id, 1, draft( "S2" ) ),
            () -> sprints.start( mallory, id, 1 ), () -> sprints.close( mallory, id, 1, null ) );
        for ( final var write : writes ) {
            assertThrows( WorkNotFoundException.class, write );
        }
    }

    @Test
    void anArchivedProjectRefusesEveryWrite() throws SQLException {
        final SprintView made = create( "S1" );
        final long id = made.sprint().id();
        final Item t = task();
        fx.svc.archiveProject( fx.admin, "PLAT" );
        final List< org.junit.jupiter.api.function.Executable > writes = List.of(
            () -> sprints.create( fx.lena, "PLAT", draft( "S2" ) ),
            () -> sprints.update( fx.lena, id, 1, draft( "S2" ) ),
            () -> sprints.start( fx.lena, id, 1 ),
            () -> sprints.close( fx.lena, id, 1, null ),
            () -> plan( t, id ) );
        for ( final var write : writes ) {
            assertEquals( WorkConflictException.ARCHIVED,
                assertThrows( WorkConflictException.class, write ).reason() );
        }
    }

    @Test
    void childPointsIsTheEpicsSum() throws SQLException {
        final Item epic = fx.draft( WorkLevel.EPIC, null );
        final Item withOwn = story( epic.key(), 3 );
        final Item byTasks = story( epic.key(), null );
        childTask( byTasks.key(), 2 );
        childTask( byTasks.key(), 1.5 );
        final Item cancelled = story( epic.key(), 8 );
        fx.status( fx.lena, cancelled, "cancelled" );
        assertEquals( new BigDecimal( "6.5" ), sprints.childPoints( fx.lena, epic.key() ) );
        assertEquals( new BigDecimal( "3.5" ), sprints.childPoints( fx.lena, byTasks.key() ) );
        assertTrue( sprints.childPoints( fx.lena, withOwn.key() ).signum() == 0 );
    }

    @Test
    void velocityListsTheLastSixClosedSprintsOldestFirst() throws SQLException {
        for ( int n = 1; n <= 7; n++ ) {
            final SprintView made = create( "S" + n );
            final SprintView started = sprints.start( fx.lena, made.sprint().id(), made.sprint().version() );
            sprints.close( fx.lena, made.sprint().id(), started.sprint().version(), null );
        }
        final List< Velocity > v = sprints.velocity( fx.mia, "PLAT" );
        assertEquals( List.of( "S2", "S3", "S4", "S5", "S6", "S7" ), v.stream().map( Velocity::name ).toList() );
        assertEquals( DAY.plusDays( 13 ), v.get( 0 ).endsOn() );
    }
}

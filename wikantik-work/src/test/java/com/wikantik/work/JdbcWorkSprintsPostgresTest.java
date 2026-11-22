package com.wikantik.work;

import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sprint store against real PostgreSQL: create, update, start, close, totals, history and child points. */
@RequiresPostgres
class JdbcWorkSprintsPostgresTest {

    private static final Actor LENA = Actor.human( "lena", Set.of(), false );
    private static final LocalDate D1 = LocalDate.of( 2026, 11, 2 );
    private static final LocalDate D2 = LocalDate.of( 2026, 11, 13 );

    private DataSource ds;
    private WorkClaimFixtures fx;
    private JdbcWorkStore store;
    private WorkSprintStore sprints;
    private Project plat;

    @BeforeEach
    void setUp() throws SQLException {
        ds = PostgresTestDb.createDataSource();
        PostgresTestDb.truncate( WorkAgentFixtures.TABLES );
        fx = new WorkClaimFixtures( ds );
        store = fx.store;
        sprints = store.sprints();
        plat = fx.plat;
    }

    private static SprintDraft draft( final String name ) {
        return new SprintDraft( name, "goal", D1, D2 );
    }

    private Sprint sprint( final String name ) throws SQLException {
        return sprints.create( plat, draft( name ), LENA );
    }

    /** A planned sprint taken through start and close, so that it is closed; starts on the given day. */
    private Sprint closedSprint( final String name, final LocalDate startsOn ) throws SQLException {
        final Sprint s = sprints.create( plat, new SprintDraft( name, "", startsOn, startsOn.plusDays( 10 ) ), LENA );
        return sprints.close( s.id(), sprints.start( s.id(), 1, LENA ).version(), null, LENA );
    }

    /** A task in the sprint with the estimate and status, set with plain SQL so that no event is written. */
    private Item item( final Project p, final String title, final Sprint s, final String estimate,
                       final String status ) throws SQLException {
        final Item i = store.createItem( p, JdbcWorkStoreItemsPostgresTest.draft( title ), LENA );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET sprint_id = " + ( s == null ? "NULL" : s.id() )
            + ", estimate = " + ( estimate == null ? "NULL" : estimate ) + ", status = '" + status + "'"
            + ( "done".equals( status ) ? ", closed_at = NOW()" : "" ) + " WHERE id = " + i.id() );
        return store.itemById( i.id() ).orElseThrow();
    }

    private long count( final String sql ) throws SQLException {
        return WorkAgentFixtures.scalar( ds, sql );
    }

    private List< WorkEvent > projectEvents( final String type ) throws SQLException {
        return store.projectEvents( plat.id(), null, null, null, Set.of( type ), 100 );
    }

    private static void assertConflict( final String reason, final org.junit.jupiter.api.function.Executable call ) {
        assertEquals( reason, assertThrows( WorkConflictException.class, call ).reason() );
    }

    /** The item as the store holds it, moved to the sprint (null = backlog) with the estimate, through the store. */
    private Item planned( final Item i, final Sprint s, final String estimate, final WorkStatus status )
            throws SQLException {
        final Item cur = store.itemById( i.id() ).orElseThrow();
        return store.updateItem( cur, changed( cur, s, estimate, status ), LENA, null );
    }

    private static Item changed( final Item cur, final Sprint s, final String estimate, final WorkStatus status ) {
        return new Item( cur.id(), cur.projectId(), cur.key(), cur.level(), cur.parentId(), cur.productId(),
            cur.kind(), cur.title(), cur.body(), status, cur.priority(), cur.rank(), cur.dueOn(), cur.assignee(),
            cur.reporter(), cur.autonomy(), cur.doneMeans(), cur.allowDirect(), cur.version(), cur.createdAt(),
            cur.updatedAt(), cur.closedAt(), cur.queue(), cur.claimedByAgent(), cur.claimedAt(), cur.claimExpiresAt(),
            cur.reporterAgentId(), cur.type(), cur.labels(), s == null ? null : s.id(),
            estimate == null ? null : new BigDecimal( estimate ) );
    }

    @Test
    void createWritesTheSprintAndASprintCreatedEvent() throws SQLException {
        final Sprint s = sprint( "S1" );
        assertEquals( SprintState.PLANNED, s.state() );
        assertEquals( 1, s.version() );
        assertEquals( "PLAT", s.projectKey() );
        final List< WorkEvent > ev = projectEvents( WorkEvent.SPRINT_CREATED );
        assertEquals( 1, ev.size() );
        assertEquals( String.valueOf( s.id() ), ev.get( 0 ).toValue() );
        assertEquals( "S1", ev.get( 0 ).note() );
        assertNull( ev.get( 0 ).itemId() );
    }

    @Test
    void aDuplicateNameInTheProjectIsRefusedNamingName() throws SQLException {
        sprint( "S1" );
        final WorkValidationException e = assertThrows( WorkValidationException.class, () -> sprint( "S1" ) );
        assertEquals( "name", e.field() );
        assertEquals( 1, projectEvents( WorkEvent.SPRINT_CREATED ).size() );
    }

    @Test
    void updateChangesFieldsBumpsTheVersionAndWritesOneEvent() throws SQLException {
        final Sprint s = sprint( "S1" );
        final Sprint u = sprints.update( s.id(), 1, new SprintDraft( "Renamed", "new goal", D1, D2.plusDays( 1 ) ), LENA );
        assertEquals( "Renamed", u.name() );
        assertEquals( "new goal", u.goal() );
        assertEquals( D2.plusDays( 1 ), u.endsOn() );
        assertEquals( 2, u.version() );
        final List< WorkEvent > ev = projectEvents( WorkEvent.SPRINT_UPDATED );
        assertEquals( 1, ev.size() );
        assertEquals( "Renamed", ev.get( 0 ).note() );
        assertEquals( String.valueOf( s.id() ), ev.get( 0 ).toValue() );
    }

    @Test
    void anUnchangedUpdateWritesNothing() throws SQLException {
        final Sprint s = sprint( "S1" );
        assertEquals( s, sprints.update( s.id(), 1, draft( "S1" ), LENA ) );
        assertEquals( 0, projectEvents( WorkEvent.SPRINT_UPDATED ).size() );
    }

    @Test
    void aStaleUpdateIsStale() throws SQLException {
        final Sprint s = sprint( "S1" );
        assertConflict( WorkConflictException.STALE, () -> sprints.update( s.id(), 7, draft( "X" ), LENA ) );
        assertEquals( "S1", sprints.byId( s.id() ).orElseThrow().name() );
    }

    @Test
    void aClosedSprintCannotBeUpdated() throws SQLException {
        final Sprint closed = closedSprint( "S1", D1 );
        assertConflict( WorkConflictException.SPRINT_CLOSED,
            () -> sprints.update( closed.id(), closed.version(), draft( "X" ), LENA ) );
    }

    @Test
    void startRecordsTheCommitmentFromTheItemsInTheSprint() throws SQLException {
        final Sprint s = sprint( "S1" );
        item( plat, "a", s, "3", "ready" );
        item( plat, "b", s, null, "ready" );
        item( plat, "c", s, "8", "cancelled" );
        final Sprint started = sprints.start( s.id(), 1, LENA );
        assertEquals( SprintState.ACTIVE, started.state() );
        assertEquals( 2, started.committedItems() );
        assertEquals( new BigDecimal( "3.0" ), started.committedPoints() );
        assertEquals( 2, started.version() );
        assertNotNull( started.startedAt() );
        assertEquals( 1, projectEvents( WorkEvent.SPRINT_STARTED ).size() );
    }

    @Test
    void aSecondActiveSprintIsSprintActive() throws SQLException {
        final Sprint a = sprint( "A" );
        final Sprint b = sprint( "B" );
        sprints.start( a.id(), 1, LENA );
        assertConflict( WorkConflictException.SPRINT_ACTIVE, () -> sprints.start( b.id(), 1, LENA ) );
        assertEquals( SprintState.PLANNED, sprints.byId( b.id() ).orElseThrow().state() );
    }

    @Test
    void twoStartsRacingLeaveOneActive() throws Exception {
        final Sprint a = sprint( "A" );
        final Sprint b = sprint( "B" );
        final CommitGate gate = new CommitGate( ds );
        final JdbcWorkStore first = new JdbcWorkStore( gate );
        final CompletableFuture< Sprint > winner = CompletableFuture.supplyAsync( () -> {
            try {
                return first.sprints().start( a.id(), 1, LENA );
            } catch ( final SQLException e ) {
                throw new IllegalStateException( e );
            }
        } );
        assertTrue( gate.reached.await( 20, TimeUnit.SECONDS ), "the first start never reached its commit" );
        // The first start has written 'active' and holds its locks, uncommitted; the second runs on its own connection.
        final CompletableFuture< Throwable > loser = CompletableFuture.supplyAsync( () -> {
            try {
                sprints.start( b.id(), 1, LENA );
                return null;
            } catch ( final Exception e ) {
                return e;
            }
        } );
        WorkClaimFixtures.awaitBlockedBy( ds, gate.pid );
        gate.open();
        winner.get( 20, TimeUnit.SECONDS );
        final Throwable err = loser.get( 20, TimeUnit.SECONDS );
        assertTrue( err instanceof WorkConflictException, String.valueOf( err ) );
        assertEquals( WorkConflictException.SPRINT_ACTIVE, ( ( WorkConflictException ) err ).reason() );
        assertEquals( 1, count( "SELECT count(*) FROM work_sprint WHERE state = 'active'" ) );
        assertEquals( 1, projectEvents( WorkEvent.SPRINT_STARTED ).size() );
        assertEquals( SprintState.PLANNED, sprints.byId( b.id() ).orElseThrow().state() );
    }

    @Test
    void startingAnActiveOrClosedSprintIsBadTransition() throws SQLException {
        final Sprint s = sprint( "S1" );
        final Sprint active = sprints.start( s.id(), 1, LENA );
        assertConflict( WorkConflictException.BAD_TRANSITION, () -> sprints.start( s.id(), active.version(), LENA ) );
        final Sprint closed = sprints.close( s.id(), active.version(), null, LENA );
        assertConflict( WorkConflictException.BAD_TRANSITION, () -> sprints.start( s.id(), closed.version(), LENA ) );
    }

    @Test
    void closingAPlannedSprintIsBadTransition() throws SQLException {
        final Sprint s = sprint( "S1" );
        assertConflict( WorkConflictException.BAD_TRANSITION, () -> sprints.close( s.id(), 1, null, LENA ) );
    }

    /** An active sprint holding done 5, cancelled 2, ready 3 and a claimed in-progress item with no estimate. */
    private Sprint activeWithWork() throws SQLException {
        final Sprint s = sprint( "S1" );
        item( plat, "done", s, "5", "done" );
        item( plat, "cancelled", s, "2", "cancelled" );
        item( plat, "ready", s, "3", "ready" );
        final Item claimable = fx.ready( "progress" );
        fx.store.claims().claim( claimable, fx.actorA, Duration.ofHours( 1 ) ).orElseThrow();
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET sprint_id = " + s.id() + " WHERE id = " + claimable.id() );
        return sprints.start( s.id(), 1, LENA );
    }

    private Map< String, Item > byTitle() throws SQLException {
        final Map< String, Item > out = new HashMap<>();
        for ( final Item i : store.items( new ItemFilter( plat.id(), null, null, null, null, null, null, null, 0, null, null, null, null, null ) ) ) {
            out.put( i.title(), i );
        }
        return out;
    }

    private List< WorkEvent > sprintMoves( final Item i ) throws SQLException {
        return store.itemEvents( i.id() ).stream()
            .filter( e -> "field".equals( e.type() ) && "sprint".equals( e.note() ) ).toList();
    }

    @Test
    void closeRecordsCompletionAndMovesUnfinishedWorkToTheTarget() throws SQLException {
        final Sprint next = sprint( "Next" );
        final Sprint active = activeWithWork();
        final Map< String, Item > before = byTitle();
        final Sprint closed = sprints.close( active.id(), active.version(), next.id(), LENA );
        assertEquals( SprintState.CLOSED, closed.state() );
        assertEquals( 1, closed.completedItems() );
        assertEquals( new BigDecimal( "5.0" ), closed.completedPoints() );
        assertNotNull( closed.closedAt() );
        final Map< String, Item > after = byTitle();
        assertEquals( active.id(), after.get( "done" ).sprintId() );
        assertEquals( active.id(), after.get( "cancelled" ).sprintId() );
        assertEquals( before.get( "done" ).version(), after.get( "done" ).version() );
        for ( final String moved : List.of( "ready", "progress" ) ) {
            assertEquals( next.id(), after.get( moved ).sprintId() );
            assertEquals( before.get( moved ).version() + 1, after.get( moved ).version() );
            final List< WorkEvent > moves = sprintMoves( after.get( moved ) );
            assertEquals( 1, moves.size() );
            assertEquals( String.valueOf( active.id() ), moves.get( 0 ).fromValue() );
            assertEquals( String.valueOf( next.id() ), moves.get( 0 ).toValue() );
            assertEquals( "lena", moves.get( 0 ).actor() );
        }
        assertNotNull( after.get( "progress" ).claimedByAgent(), "the claim survives the move" );
        assertEquals( 1, projectEvents( WorkEvent.SPRINT_CLOSED ).size() );
    }

    @Test
    void closeWithNoTargetMovesUnfinishedWorkToTheBacklog() throws SQLException {
        final Sprint active = activeWithWork();
        sprints.close( active.id(), active.version(), null, LENA );
        final Map< String, Item > after = byTitle();
        assertNull( after.get( "ready" ).sprintId() );
        assertNull( after.get( "progress" ).sprintId() );
        assertEquals( active.id(), after.get( "done" ).sprintId() );
        final List< WorkEvent > moves = sprintMoves( after.get( "ready" ) );
        assertEquals( 1, moves.size() );
        assertNull( moves.get( 0 ).toValue() );
    }

    @Test
    void closeRefusesABadTarget() throws SQLException {
        final Sprint closedOne = closedSprint( "Old", D1.minusDays( 30 ) );
        final Project other = store.createProject( "OTH", "Other", "" );
        final Sprint foreign = sprints.create( other, draft( "F" ), LENA );
        final Sprint active = activeWithWork();
        final Map< String, Item > before = byTitle();
        final int closesBefore = projectEvents( WorkEvent.SPRINT_CLOSED ).size();
        for ( final long bad : new long[] { closedOne.id(), active.id(), foreign.id(), 987654 } ) {
            assertConflict( WorkConflictException.BAD_TARGET,
                () -> sprints.close( active.id(), active.version(), bad, LENA ) );
        }
        assertEquals( active, sprints.byId( active.id() ).orElseThrow() );
        assertEquals( before, byTitle() );
        assertEquals( closesBefore, projectEvents( WorkEvent.SPRINT_CLOSED ).size() );
    }

    @Test
    void closeWithAStaleVersionIsStale() throws SQLException {
        final Sprint active = activeWithWork();
        assertConflict( WorkConflictException.STALE, () -> sprints.close( active.id(), 1, null, LENA ) );
    }

    @Test
    void theCloseEventPrecedesItsMoves() throws SQLException {
        final Sprint next = sprint( "Next" );
        final Sprint active = activeWithWork();
        sprints.close( active.id(), active.version(), next.id(), LENA );
        final long closeId = projectEvents( WorkEvent.SPRINT_CLOSED ).get( 0 ).id();
        assertEquals( 2, count( "SELECT count(*) FROM work_event WHERE type = 'field' AND note = 'sprint' "
            + "AND from_value = '" + active.id() + "' AND id > " + closeId ) );
        assertEquals( 0, count( "SELECT count(*) FROM work_event WHERE type = 'field' AND note = 'sprint' "
            + "AND from_value = '" + active.id() + "' AND id < " + closeId ) );
    }

    @Test
    void totalsCountNonCancelledItemsAndDoneOnes() throws SQLException {
        final Sprint s = sprint( "S1" );
        sprint( "Empty" );
        item( plat, "a", s, "3", "ready" );
        item( plat, "b", s, "2", "done" );
        item( plat, "c", s, null, "done" );
        item( plat, "d", s, "8", "cancelled" );
        final Map< Long, SprintTotals > totals = sprints.totals( plat.id() );
        assertEquals( new SprintTotals( 3, new BigDecimal( "5.0" ), 2, new BigDecimal( "2.0" ) ), totals.get( s.id() ) );
        assertEquals( 1, totals.size() );
    }

    @Test
    void forProjectOrdersActivePlannedThenClosed() throws SQLException {
        final Sprint c1 = closedSprint( "C1", LocalDate.of( 2026, 10, 1 ) );
        final Sprint c2 = closedSprint( "C2", LocalDate.of( 2026, 10, 15 ) );
        final Sprint p1 = sprints.create( plat, new SprintDraft( "P1", "", LocalDate.of( 2026, 11, 20 ),
            LocalDate.of( 2026, 11, 30 ) ), LENA );
        final Sprint p2 = sprints.create( plat, new SprintDraft( "P2", "", LocalDate.of( 2026, 11, 10 ),
            LocalDate.of( 2026, 11, 19 ) ), LENA );
        final Sprint a = sprint( "A" );
        sprints.start( a.id(), 1, LENA );
        assertEquals( List.of( "A", "P2", "P1", "C2", "C1" ),
            sprints.forProject( plat.id() ).stream().map( Sprint::name ).toList() );
        assertTrue( c1.id() > 0 && c2.id() > 0 && p1.id() > 0 && p2.id() > 0 );
    }

    @Test
    void closedReturnsNewestFirstUpToTheLimit() throws SQLException {
        closedSprint( "C1", LocalDate.of( 2026, 10, 1 ) );
        closedSprint( "C2", LocalDate.of( 2026, 10, 15 ) );
        closedSprint( "C3", LocalDate.of( 2026, 11, 1 ) );
        sprint( "Planned" );
        assertEquals( List.of( "C3", "C2" ), sprints.closed( plat.id(), 2 ).stream().map( Sprint::name ).toList() );
        assertEquals( 3, sprints.closed( plat.id(), 10 ).size() );
    }

    @Test
    void eventsForCoversEveryItemEverInTheSprint() throws SQLException {
        final Sprint s = sprint( "S1" );
        final Item stays = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "stays" ), LENA );
        final Item leaves = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "leaves" ), LENA );
        final Item never = store.createItem( plat, JdbcWorkStoreItemsPostgresTest.draft( "never" ), LENA );
        planned( stays, s, "3", WorkStatus.DRAFT );
        planned( leaves, s, null, WorkStatus.DRAFT );
        final Sprint started = sprints.start( s.id(), 1, LENA );
        planned( leaves, null, null, WorkStatus.DRAFT );
        // a priority edit writes a `field` event noted priority: it must not come back
        final Item cur = store.itemById( stays.id() ).orElseThrow();
        store.updateItem( cur, edited( cur, WorkStatus.DRAFT, 1, null ), LENA, null );
        // a plain status change, then a hand-off to done (status and assignee together, with a note)
        final Item prog = planned( stays, s, "3", WorkStatus.IN_PROGRESS );
        store.handoff( prog, edited( prog, WorkStatus.DONE, prog.priority(), "carol" ), LENA, "over to you" );
        sprints.close( s.id(), started.version(), null, LENA );

        final SprintHistory h = sprints.eventsFor( plat.id(), s.id() );
        assertEquals( projectEvents( WorkEvent.SPRINT_STARTED ).get( 0 ).id(), h.startedEventId() );
        assertEquals( projectEvents( WorkEvent.SPRINT_CLOSED ).get( 0 ).id(), h.closedEventId() );
        final Set< Long > items = new java.util.HashSet<>();
        h.events().forEach( e -> items.add( e.itemId() ) );
        assertEquals( Set.of( stays.id(), leaves.id() ), items );
        assertTrue( h.events().stream().anyMatch( e -> e.kind() == SprintEvent.Kind.SPRINT
            && e.itemId() == leaves.id() && e.value() == null ), "the move out is there, with a null target" );
        assertTrue( h.events().stream().anyMatch( e -> e.kind() == SprintEvent.Kind.ESTIMATE && "3".equals( e.value() ) ) );

        final long handoffId = count( "SELECT id FROM work_event WHERE type = 'handoff' AND item_id = " + stays.id() );
        final long priorityId = count( "SELECT id FROM work_event WHERE type = 'field' AND note = 'priority' "
            + "AND item_id = " + stays.id() );
        assertTrue( h.events().stream().noneMatch( e -> e.id() == priorityId ), "no priority event leaks" );
        final SprintEvent handoff = h.events().stream().filter( e -> e.id() == handoffId ).findFirst().orElseThrow();
        assertEquals( SprintEvent.Kind.STATUS, handoff.kind() );
        assertEquals( "done", handoff.value(), "the hand-off arrives as its status part, without the target" );
        assertEquals( 0, h.events().stream().filter( e -> e.value() != null && e.value().contains( "|" ) ).count() );
        final List< Long > ids = h.events().stream().map( SprintEvent::id ).toList();
        assertEquals( ids.stream().sorted().toList(), ids );
    }

    private static Item edited( final Item i, final WorkStatus status, final int priority, final String assignee ) {
        return new Item( i.id(), i.projectId(), i.key(), i.level(), i.parentId(), i.productId(), i.kind(), i.title(),
            i.body(), status, priority, i.rank(), i.dueOn(), assignee, i.reporter(), i.autonomy(), i.doneMeans(),
            i.allowDirect(), i.version(), i.createdAt(), i.updatedAt(), i.closedAt(), i.queue(), i.claimedByAgent(),
            i.claimedAt(), i.claimExpiresAt(), i.reporterAgentId(), i.type(), i.labels(), i.sprintId(), i.estimate() );
    }

    @Test
    void eventsForOfASprintNeverStartedHasTheOpenMarkers() throws SQLException {
        final Sprint s = sprint( "S1" );
        final SprintHistory h = sprints.eventsFor( plat.id(), s.id() );
        assertEquals( 0, h.startedEventId() );
        assertEquals( Long.MAX_VALUE, h.closedEventId() );
        assertTrue( h.events().isEmpty() );
    }

    private Item child( final String title, final WorkLevel level, final Item parent, final String estimate,
                        final String status ) throws SQLException {
        final Item draft = JdbcWorkStoreItemsPostgresTest.draft( level, title );
        final Item i = store.createItem( plat, draft, LENA );
        WorkAgentFixtures.exec( ds, "UPDATE work_item SET " + ( parent == null ? "" : "parent_id = " + parent.id() + ", " )
            + "estimate = " + ( estimate == null ? "NULL" : estimate ) + ", status = '" + status + "' WHERE id = " + i.id() );
        return store.itemById( i.id() ).orElseThrow();
    }

    @Test
    void childPointsSumsStoriesOrTheirTasks() throws SQLException {
        final Item epic = child( "epic", WorkLevel.EPIC, null, null, "draft" );
        final Item five = child( "s5", WorkLevel.STORY, epic, "5", "draft" );
        child( "t1", WorkLevel.TASK, five, "2", "draft" );
        child( "t2", WorkLevel.TASK, five, "2", "draft" );
        final Item bare = child( "bare", WorkLevel.STORY, epic, null, "draft" );
        child( "t3", WorkLevel.TASK, bare, "1", "draft" );
        child( "t4", WorkLevel.TASK, bare, "0.5", "draft" );
        final Item gone = child( "gone", WorkLevel.STORY, epic, "9", "cancelled" );
        assertEquals( new BigDecimal( "6.5" ), sprints.childPoints( epic.id() ) );
        assertEquals( new BigDecimal( "4.0" ), sprints.childPoints( five.id() ) );
        assertEquals( new BigDecimal( "0.0" ), sprints.childPoints( gone.id() ) );
        assertEquals( new BigDecimal( "0.0" ), sprints.childPoints( bare.id() ).subtract( new BigDecimal( "1.5" ) ) );
    }

    private static Throwable outcome( final java.util.concurrent.Callable< ? > call ) {
        try {
            call.call();
            return null;
        } catch ( final Exception e ) {
            return e;
        }
    }

    @Test
    void anItemEditWaitingOnACloseFindsTheItemMovedAndRefusesCleanlyNeverDeadlocks() throws Exception {
        final Sprint s = sprint( "S1" );
        final Item in = item( plat, "a", s, "3", "ready" );
        final Sprint active = sprints.start( s.id(), 1, LENA );
        final CommitGate gate = new CommitGate( ds );
        final JdbcWorkStore closing = new JdbcWorkStore( gate );
        final CompletableFuture< Throwable > close = CompletableFuture.supplyAsync( () ->
            outcome( () -> closing.sprints().close( s.id(), active.version(), null, LENA ) ) );
        assertTrue( gate.reached.await( 20, TimeUnit.SECONDS ), "the close never reached its commit" );
        // The close holds the sprint row and the item row, uncommitted. The edit queues on the sprint (shared lock
        // first). Taking the item row first would be a lock cycle: PostgreSQL would abort one side with 40P01.
        final Item cur = store.itemById( in.id() ).orElseThrow();
        final CompletableFuture< Throwable > edit = CompletableFuture.supplyAsync( () ->
            outcome( () -> store.updateItem( cur, changed( cur, s, "3", WorkStatus.IN_PROGRESS ), LENA, null ) ) );
        WorkClaimFixtures.awaitBlockedBy( ds, gate.pid );
        gate.open();
        assertNull( close.get( 20, TimeUnit.SECONDS ) );
        final Throwable refused = edit.get( 20, TimeUnit.SECONDS );
        assertTrue( refused instanceof WorkConflictException, String.valueOf( refused ) );
        assertEquals( WorkConflictException.STALE, ( ( WorkConflictException ) refused ).reason() );
        assertNull( store.itemById( in.id() ).orElseThrow().sprintId(), "the close's move stands" );
    }

    @Test
    void aCloseWaitingOnAnItemEditCountsTheEditedItem() throws Exception {
        final Sprint s = sprint( "S1" );
        final Item in = item( plat, "a", s, "3", "ready" );
        final Sprint active = sprints.start( s.id(), 1, LENA );
        final CommitGate gate = new CommitGate( ds );
        final JdbcWorkStore editing = new JdbcWorkStore( gate );
        final Item cur = store.itemById( in.id() ).orElseThrow();
        final CompletableFuture< Throwable > edit = CompletableFuture.supplyAsync( () ->
            outcome( () -> editing.updateItem( cur, changed( cur, s, "3", WorkStatus.DONE ), LENA, null ) ) );
        assertTrue( gate.reached.await( 20, TimeUnit.SECONDS ), "the edit never reached its commit" );
        // The edit holds a shared lock on the sprint and the item row, uncommitted; the close must wait for it.
        final CompletableFuture< Throwable > close = CompletableFuture.supplyAsync( () ->
            outcome( () -> sprints.close( s.id(), active.version(), null, LENA ) ) );
        WorkClaimFixtures.awaitBlockedBy( ds, gate.pid );
        gate.open();
        assertNull( edit.get( 20, TimeUnit.SECONDS ) );
        assertNull( close.get( 20, TimeUnit.SECONDS ) );
        final Sprint closed = sprints.byId( s.id() ).orElseThrow();
        assertEquals( 1, closed.completedItems(), "the close saw the finished item" );
        assertEquals( new BigDecimal( "3.0" ), closed.completedPoints() );
        assertEquals( s.id(), store.itemById( in.id() ).orElseThrow().sprintId(), "a done item stays" );
    }

    @Test
    void anItemEditParkedBeforeItsSprintLockCannotDeadlockAClose() throws Exception {
        final Sprint s = sprint( "S1" );
        final Item in = item( plat, "a", s, "3", "ready" );
        final Sprint active = sprints.start( s.id(), 1, LENA );
        final StatementProbe probe = new StatementProbe( ds, "FOR SHARE" );
        final JdbcWorkStore editing = new JdbcWorkStore( probe );
        final Item cur = store.itemById( in.id() ).orElseThrow();
        final CompletableFuture< Throwable > edit = CompletableFuture.supplyAsync( () ->
            outcome( () -> editing.updateItem( cur, changed( cur, s, "3", WorkStatus.IN_PROGRESS ), LENA, null ) ) );
        assertTrue( probe.reached.await( 20, TimeUnit.SECONDS ), "the edit never reached its sprint lock" );
        // The edit is parked just before taking the sprint's shared lock. With the item row locked first it would
        // now hold the item and the close below would wait on it, then the edit would wait on the close's sprint
        // lock: a cycle. Sprint first, the edit holds nothing and the close runs to its end.
        final CompletableFuture< Throwable > close = CompletableFuture.supplyAsync( () ->
            outcome( () -> sprints.close( s.id(), active.version(), null, LENA ) ) );
        try {
            close.get( 3, TimeUnit.SECONDS );
        } catch ( final java.util.concurrent.TimeoutException e ) {
            // Blocked behind the parked edit: the lock order is wrong. Let the edit go and report what happened.
            probe.open();
        }
        probe.open();
        assertNull( close.get( 20, TimeUnit.SECONDS ), "the close fails (a deadlock victim?)" );
        final Throwable refused = edit.get( 20, TimeUnit.SECONDS );
        assertTrue( refused instanceof WorkConflictException, String.valueOf( refused ) );
        assertEquals( WorkConflictException.STALE, ( ( WorkConflictException ) refused ).reason() );
    }

    @Test
    void aStartWaitsForAnInFlightItemEditAndCountsIt() throws Exception {
        final Sprint s = sprint( "S1" );
        item( plat, "a", s, "3", "ready" );
        final Item joining = item( plat, "b", null, null, "ready" );
        final CommitGate gate = new CommitGate( ds );
        final JdbcWorkStore editing = new JdbcWorkStore( gate );
        final Item cur = store.itemById( joining.id() ).orElseThrow();
        final CompletableFuture< Throwable > edit = CompletableFuture.supplyAsync( () ->
            outcome( () -> editing.updateItem( cur, changed( cur, s, "5", WorkStatus.READY ), LENA, null ) ) );
        assertTrue( gate.reached.await( 20, TimeUnit.SECONDS ), "the edit never reached its commit" );
        // The edit joined the sprint with 5 points and holds a shared lock on it, uncommitted.
        final CompletableFuture< Throwable > start = CompletableFuture.supplyAsync( () ->
            outcome( () -> sprints.start( s.id(), 1, LENA ) ) );
        WorkClaimFixtures.awaitBlockedBy( ds, gate.pid );
        gate.open();
        assertNull( edit.get( 20, TimeUnit.SECONDS ) );
        assertNull( start.get( 20, TimeUnit.SECONDS ) );
        final Sprint started = sprints.byId( s.id() ).orElseThrow();
        assertEquals( 2, started.committedItems(), "the start counted the edit that was in flight" );
        assertEquals( new BigDecimal( "8.0" ), started.committedPoints() );
    }
}

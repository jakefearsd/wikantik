package com.wikantik.agentreports;

import com.wikantik.jdbc.Jdbc;
import com.wikantik.jdbc.testing.FaultInjectingDataSource;
import com.wikantik.jdbc.testing.PostgresTestDb;
import com.wikantik.jdbc.testing.RequiresPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@RequiresPostgres
class JdbcAgentReportStoreTest {

    private static final Instant T0 = Instant.parse( "2026-11-26T10:00:00Z" );
    private static final ReportQuery ALL = new ReportQuery( null, null, null, null, null, null, null, 100 );

    private JdbcAgentReportStore store;

    @BeforeEach
    void setUp() {
        PostgresTestDb.truncate( "agent_report_context", "agent_report_friction", "agent_report" );
        store = new JdbcAgentReportStore( new Jdbc( PostgresTestDb.createDataSource() ) );
    }

    private static AgentReport report( final Instant filedAt, final String kind, final String outcome,
                                       final List< String > products, final List< String > skills ) {
        return new AgentReport( null, filedAt, null, null, null, "ws", kind, products, skills,
            T0.minusSeconds( 60 ), T0, "the task é", outcome,
            List.of( new ContextUse( "wiki", "PageA", "yes" ), new ContextUse( "repo", "a/b.java", "no" ),
                     new ContextUse( "web", "http://x", "partly" ) ),
            "missing stuff",
            List.of( new Friction( "wikantik", "write_pages", "slow", true ),
                     new Friction( "jakemon", null, "odd", false ) ),
            "some notes" );
    }

    private static final Filer F = new Filer( "claude-code", "hash1", "alice" );

    @Test
    void insertAndListRoundTripsEveryField() {
        final AgentReport in = report( T0, "build", "completed", List.of( "wikantik", "jakemon" ), List.of( "s1", "s2" ) );
        final long id = store.insert( in, F );
        final List< AgentReport > out = store.list( ALL );
        assertEquals( 1, out.size() );
        final AgentReport r = out.get( 0 );
        assertEquals( id, r.id() );
        assertEquals( T0, r.filedAt() );
        assertEquals( "claude-code", r.client() );
        assertEquals( "hash1", r.sessionHash() );
        assertEquals( "alice", r.filedBy() );
        assertEquals( "ws", r.workspace() );
        assertEquals( "build", r.kindOfWork() );
        assertEquals( List.of( "wikantik", "jakemon" ), r.products() );
        assertEquals( List.of( "s1", "s2" ), r.skills() );
        assertEquals( in.windowStart(), r.windowStart() );
        assertEquals( in.windowEnd(), r.windowEnd() );
        assertEquals( "the task é", r.task() );
        assertEquals( "completed", r.outcome() );
        assertEquals( in.contextUsed(), r.contextUsed() );
        assertEquals( "missing stuff", r.contextMissing() );
        assertEquals( in.friction(), r.friction() );
        assertEquals( "some notes", r.notes() );
    }

    @Test
    void nullableFieldsRoundTripAsNull() {
        final AgentReport in = new AgentReport( null, T0, null, null, null, null, "other", List.of(), List.of(),
            null, null, "t", "abandoned", List.of(), null, List.of(), null );
        store.insert( in, F );
        final AgentReport r = store.list( ALL ).get( 0 );
        assertEquals( null, r.workspace() );
        assertEquals( null, r.windowStart() );
        assertEquals( null, r.notes() );
        assertEquals( List.of(), r.products() );
    }

    @Test
    void listIsNewestFirstAndLimited() {
        store.insert( report( T0, "build", "completed", List.of(), List.of() ), F );
        store.insert( report( T0.plusSeconds( 10 ), "content", "partial", List.of(), List.of() ), F );
        store.insert( report( T0.plusSeconds( 5 ), "research", "abandoned", List.of(), List.of() ), F );
        assertEquals( List.of( "content", "research", "build" ),
            store.list( ALL ).stream().map( AgentReport::kindOfWork ).toList() );
        assertEquals( 2, store.list( new ReportQuery( null, null, null, null, null, null, null, 2 ) ).size() );
    }

    @Test
    void filtersNarrow() {
        store.insert( report( T0, "build", "completed", List.of( "wikantik" ), List.of( "s1" ) ), F );
        store.insert( report( T0.plusSeconds( 100 ), "content", "partial", List.of( "jakemon" ), List.of( "s2" ) ),
            new Filer( "codex", "hash2", "bob" ) );
        assertEquals( 1, store.list( q( T0.plusSeconds( 50 ), null, null, null, null, null, null ) ).size() );
        assertEquals( "build", store.list( q( null, T0.plusSeconds( 50 ), null, null, null, null, null ) ).get( 0 ).kindOfWork() );
        assertEquals( "content", store.list( q( null, null, "content", null, null, null, null ) ).get( 0 ).kindOfWork() );
        assertEquals( "content", store.list( q( null, null, null, "jakemon", null, null, null ) ).get( 0 ).kindOfWork() );
        assertEquals( "build", store.list( q( null, null, null, null, "claude-code", null, null ) ).get( 0 ).kindOfWork() );
        assertEquals( "content", store.list( q( null, null, null, null, null, "s2", null ) ).get( 0 ).kindOfWork() );
        assertEquals( "build", store.list( q( null, null, null, null, null, null, "completed" ) ).get( 0 ).kindOfWork() );
        assertEquals( 0, store.list( q( null, null, "content", "wikantik", null, null, null ) ).size() );
    }

    private static ReportQuery q( final Instant from, final Instant to, final String kind, final String product,
                                  final String client, final String skill, final String outcome ) {
        return new ReportQuery( from, to, kind, product, client, skill, outcome, 100 );
    }

    @Test
    void limitIsCappedAt500() {
        assertEquals( 500, new ReportQuery( null, null, null, null, null, null, null, 9999 ).limit() );
        assertEquals( 100, new ReportQuery( null, null, null, null, null, null, null, 0 ).limit() );
    }

    @Test
    void failureOnFrictionInsertRollsEverythingBack() {
        final FaultInjectingDataSource faulty = new FaultInjectingDataSource( PostgresTestDb.createDataSource() );
        final JdbcAgentReportStore s = new JdbcAgentReportStore( new Jdbc( faulty ) );
        // statements: 1 report insert, 2 context batch, 3 friction batch
        faulty.failOn( 3, new IllegalStateException( "boom" ) );
        assertThrows( RuntimeException.class,
            () -> s.insert( report( T0, "build", "completed", List.of(), List.of() ), F ) );
        assertEquals( 0, store.list( ALL ).size() );
        assertEquals( 1, faulty.rollbacks() );
    }
}

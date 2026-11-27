package com.wikantik.agentreports;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentReportServiceTest {

    private static final Map< String, Object > ARGS =
        Map.of( "kind_of_work", "other", "task", "t", "outcome", "partial" );

    /** Mutable clock. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse( "2026-11-26T10:00:00Z" );
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone( final java.time.ZoneId z ) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static final class MemStore implements AgentReportStore {
        final List< AgentReport > rows = new ArrayList<>();
        final List< Filer > filers = new ArrayList<>();
        @Override public long insert( final AgentReport r, final Filer f ) {
            rows.add( r );
            filers.add( f );
            return rows.size();
        }
        @Override public List< AgentReport > list( final ReportQuery q ) { return rows; }
    }

    @Test
    void filesAndFillsServerFields() {
        final MemStore store = new MemStore();
        final TestClock clock = new TestClock();
        final AgentReportService svc = new AgentReportService( store, 60, clock );
        final FileResult r = svc.file( ARGS, new Filer( "claude-code", "h", "alice" ) );
        assertEquals( new FileResult.Filed( 1 ), r );
        assertEquals( "alice", store.filers.get( 0 ).filedBy() );
        assertEquals( clock.now, store.rows.get( 0 ).filedAt() );
    }

    @Test
    void invalidArgsAreRefusedAndNotStored() {
        final MemStore store = new MemStore();
        final AgentReportService svc = new AgentReportService( store, 60, new TestClock() );
        final FileResult r = svc.file( Map.of( "task", "t" ), new Filer( "c", "h", "alice" ) );
        final FileResult.Refused ref = assertInstanceOf( FileResult.Refused.class, r );
        assertTrue( ref.reasons().stream().anyMatch( s -> s.contains( "kind_of_work" ) ) );
        assertTrue( store.rows.isEmpty() );
    }

    @Test
    void sixtyFirstCallInAnHourIsRateLimitedPerFiler() {
        final MemStore store = new MemStore();
        final TestClock clock = new TestClock();
        final AgentReportService svc = new AgentReportService( store, 60, clock );
        final Filer alice = new Filer( "c", "h", "alice" );
        for ( int i = 0; i < 60; i++ ) {
            assertInstanceOf( FileResult.Filed.class, svc.file( ARGS, alice ) );
            clock.now = clock.now.plusSeconds( 1 );
        }
        final FileResult.RateLimited rl = assertInstanceOf( FileResult.RateLimited.class, svc.file( ARGS, alice ) );
        assertTrue( rl.retryAfterSeconds() > 0 && rl.retryAfterSeconds() <= 3600 );
        assertEquals( 60, store.rows.size() );
        assertInstanceOf( FileResult.Filed.class, svc.file( ARGS, new Filer( "c", "h", "bob" ) ) );

        clock.now = clock.now.plus( Duration.ofHours( 1 ) );
        assertInstanceOf( FileResult.Filed.class, svc.file( ARGS, alice ) );
    }

    @Test
    void listDelegates() {
        final MemStore store = new MemStore();
        final AgentReportService svc = new AgentReportService( store, 60, new TestClock() );
        svc.file( ARGS, new Filer( "c", "h", "a" ) );
        assertEquals( 1, svc.list( new ReportQuery( null, null, null, null, null, null, null, 10 ) ).size() );
    }

    @Test
    void aLimitOfZeroOrLessDisablesRateLimiting() {
        for ( final int limit : new int[] { 0, -1 } ) {
            final AgentReportService svc = new AgentReportService( new MemStore(), limit, new TestClock() );
            final Filer alice = new Filer( "c", "h", "alice" );
            for ( int i = 0; i < 5; i++ ) {
                assertInstanceOf( FileResult.Filed.class, svc.file( ARGS, alice ), "limit " + limit );
            }
        }
    }
}

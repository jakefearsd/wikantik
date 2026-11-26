package com.wikantik.agentreports;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportQueryParserTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-26T12:00:00Z" ), ZoneOffset.UTC );

    @Test
    void defaultWindowIsSevenDaysEndingNow() {
        final ReportQuery q = ReportQueryParser.parse( Map.<String, String>of()::get, CLOCK );
        assertEquals( Instant.parse( "2026-11-19T12:00:00Z" ), q.from() );
        assertNull( q.to() );
        assertEquals( ReportQuery.DEFAULT_LIMIT, q.limit() );
    }

    @Test
    void filtersPassThrough() {
        final ReportQuery q = ReportQueryParser.parse( Map.of( "since", "2026-11-01", "until", "2026-11-10T00:00:00Z",
                "kind_of_work", "build", "product", "wikantik", "client", "claude-code", "skill", "tdd",
                "outcome", "done", "limit", "5" )::get, CLOCK );
        assertEquals( Instant.parse( "2026-11-01T00:00:00Z" ), q.from() );
        assertEquals( Instant.parse( "2026-11-10T00:00:00Z" ), q.to() );
        assertEquals( "build", q.kindOfWork() );
        assertEquals( "wikantik", q.product() );
        assertEquals( "claude-code", q.client() );
        assertEquals( "tdd", q.skill() );
        assertEquals( "done", q.outcome() );
        assertEquals( 5, q.limit() );
    }

    @Test
    void blankFiltersAreAny() {
        final ReportQuery q = ReportQueryParser.parse( Map.of( "product", "  " )::get, CLOCK );
        assertNull( q.product() );
    }

    @Test
    void badSinceNamesTheParameter() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> ReportQueryParser.parse( Map.of( "since", "yesterday" )::get, CLOCK ) );
        assertTrue( e.getMessage().contains( "since" ) );
    }

    @Test
    void integralLimitInAnyNumberFormIsAccepted() {
        assertEquals( 50, ReportQueryParser.parse( Map.of( "limit", "50.0" )::get, CLOCK ).limit() );
    }

    @Test
    void fractionalLimitIsRefusedNamingTheParameter() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> ReportQueryParser.parse( Map.of( "limit", "50.5" )::get, CLOCK ) );
        assertTrue( e.getMessage().contains( "limit" ) );
    }

    @Test
    void badLimitNamesTheParameter() {
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> ReportQueryParser.parse( Map.of( "limit", "lots" )::get, CLOCK ) );
        assertTrue( e.getMessage().contains( "limit" ) );
    }
}

package com.wikantik.agentreports;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.function.Function;

/**
 * Parses the listing parameters shared by {@code list_agent_reports} and {@code /admin/agent-reports}:
 * {@code since} (ISO date or instant, default seven days before now), {@code until}, {@code kind_of_work},
 * {@code product}, {@code client}, {@code skill}, {@code outcome} and {@code limit}.
 */
public final class ReportQueryParser {

    /** Days before now that {@code since} defaults to. */
    public static final int DEFAULT_WINDOW_DAYS = 7;

    private ReportQueryParser() {}

    /**
     * @param param looks a parameter up by name; null or blank means absent
     * @throws IllegalArgumentException naming the parameter that is malformed
     */
    public static ReportQuery parse( final Function< String, String > param, final Clock clock ) {
        final Instant since = instant( "since", param.apply( "since" ) );
        final Instant from = since != null ? since : clock.instant().minus( DEFAULT_WINDOW_DAYS, ChronoUnit.DAYS );
        return new ReportQuery( from, instant( "until", param.apply( "until" ) ),
                text( param.apply( "kind_of_work" ) ), text( param.apply( "product" ) ),
                text( param.apply( "client" ) ), text( param.apply( "skill" ) ),
                text( param.apply( "outcome" ) ), limit( param.apply( "limit" ) ) );
    }

    private static String text( final String v ) {
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static int limit( final String v ) {
        final String t = text( v );
        if ( t == null ) {
            return ReportQuery.DEFAULT_LIMIT;
        }
        try {
            return Integer.parseInt( t );
        } catch ( final NumberFormatException e ) {
            throw new IllegalArgumentException( "limit must be an integer, got '" + t + "'", e );
        }
    }

    private static Instant instant( final String name, final String v ) {
        final String t = text( v );
        if ( t == null ) {
            return null;
        }
        try {
            return t.length() <= 10 ? LocalDate.parse( t ).atStartOfDay( ZoneOffset.UTC ).toInstant()
                    : Instant.parse( t );
        } catch ( final DateTimeParseException e ) {
            throw new IllegalArgumentException( name + " must be an ISO date or instant, got '" + t + "'", e );
        }
    }
}

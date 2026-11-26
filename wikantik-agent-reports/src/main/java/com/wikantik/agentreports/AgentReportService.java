package com.wikantik.agentreports;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Files and lists agent reports: validates, enforces a per-filer hourly allowance, then stores.
 * The allowance is a sliding window kept in memory (per process); every attempt counts, valid or not.
 */
public final class AgentReportService {

    private static final Duration WINDOW = Duration.ofHours( 1 );

    private final AgentReportStore store;
    private final int maxPerHourPerKey;
    private final Clock clock;
    private final Map< String, Deque< Instant > > attempts = new HashMap<>();

    /** @param maxPerHourPerKey attempts allowed per filer per hour; zero or less disables the limit */
    public AgentReportService( final AgentReportStore store, final int maxPerHourPerKey, final Clock clock ) {
        this.store = store;
        this.maxPerHourPerKey = maxPerHourPerKey;
        this.clock = clock;
    }

    public FileResult file( final Map< String, Object > args, final Filer filer ) {
        final Instant now = clock.instant();
        final long retry = admit( filer.filedBy(), now );
        if ( retry > 0 ) {
            return new FileResult.RateLimited( retry );
        }
        final Validation v = AgentReportValidator.validate( args );
        if ( !v.isOk() ) {
            return new FileResult.Refused( v.reasons() );
        }
        return new FileResult.Filed( store.insert( v.draft().filedAt( now ), filer ) );
    }

    public List< AgentReport > list( final ReportQuery query ) {
        return store.list( query );
    }

    /** Records an attempt; returns 0 if admitted, else the seconds until the oldest attempt leaves the window. */
    private synchronized long admit( final String key, final Instant now ) {
        if ( maxPerHourPerKey <= 0 ) {
            return 0;
        }
        final Deque< Instant > q = attempts.computeIfAbsent( key, k -> new ArrayDeque<>() );
        while ( !q.isEmpty() && !q.peekFirst().plus( WINDOW ).isAfter( now ) ) {
            q.removeFirst();
        }
        if ( q.size() >= maxPerHourPerKey ) {
            return Math.max( 1, Duration.between( now, q.peekFirst().plus( WINDOW ) ).toSeconds() );
        }
        q.addLast( now );
        return 0;
    }
}

package com.wikantik.agentreports;

import java.time.Instant;

/** Filters for listing reports; a null filter means "any". {@code limit} defaults to 100 and is capped at 500. */
public record ReportQuery( Instant from, Instant to, String kindOfWork, String product, String client,
                           String skill, String outcome, int limit ) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    public ReportQuery {
        if ( limit <= 0 ) {
            limit = DEFAULT_LIMIT;
        } else if ( limit > MAX_LIMIT ) {
            limit = MAX_LIMIT;
        }
    }
}

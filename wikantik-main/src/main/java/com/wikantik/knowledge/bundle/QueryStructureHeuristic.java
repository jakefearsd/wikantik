package com.wikantik.knowledge.bundle;

import java.util.List;

/**
 * Cheap lexical gate: does a query look multi-part enough to be worth an LLM
 * decomposition planner call? Liberal by design — the {@link QueryPlanner} is
 * the precision filter (it returns passthrough for single-intent). Over-firing
 * only costs an extra planner call that returns no sub-queries; under-firing
 * silently loses a decomposition opportunity, so we bias toward firing.
 *
 * <p>Markers chosen from the 2026-07-11 RELATIONAL measurement: the addressable
 * failure class is cross-page comparative/conjunctive questions.
 */
final class QueryStructureHeuristic {

    private QueryStructureHeuristic() {}

    /** Substring markers (matched on a lowercased, space-padded query). */
    private static final List< String > MARKERS = List.of(
        " vs ", " vs.", " versus ", " differ", " difference between ",
        " compare ", " compared to ", " comparison ", " between ",
        " and what ", " and when ", " and how ", " and why ", " and which ",
        " as well as ", " both " );

    static boolean looksMultiPart( final String query ) {
        if ( query == null ) return false;
        final String q = " " + query.trim().toLowerCase() + " ";
        if ( q.isBlank() ) return false;
        for ( final String m : MARKERS ) {
            if ( q.contains( m ) ) return true;
        }
        return false;
    }
}

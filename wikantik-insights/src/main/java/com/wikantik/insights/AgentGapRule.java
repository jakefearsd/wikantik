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
package com.wikantik.insights;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rule 1 -- {@code AGENT_GAP}: retrieval queries that consistently come back empty or weak on agent surfaces. Never traffic-gated, since its denominator is MCP retrieval traffic rather than search impressions.
 *
 * <p>Package-private: callers reach it through {@link OpportunityEngine}, which delegates its
 * public {@code evaluate*} method here and runs it as part of the rule list.</p>
 */
final class AgentGapRule implements OpportunityRule {

    private static final Set<String> WEAK_COVERAGE = Set.of( "weak", "unknown" );

    @Override
    public String type() {
        return OpportunityEngine.AGENT_GAP;
    }

    @Override
    public boolean gated() {
        return false;
    }

    @Override
    public List<Opportunity> evaluate( final EvaluationInput in ) {
        return detect( in.demandRows(), in.today(), in.config() );
    }

    /**
     * Rule 1 -- {@code OpportunityEngine.AGENT_GAP}: a retrieval query that consistently comes back empty or weak
     * on agent surfaces. See DemandRow's javadoc for the input shape and grouping contract.
     *
     * <p>Rows are grouped by {@link DemandRow#queryText()}. A query only fires when
     * <strong>every</strong> row in its group is weak (zero results, or
     * {@code coverage ∈ {weak, unknown}}) -- a single non-weak row for that query means the gap
     * is not consistent and the query is left silent, matching §7.3 rule 1's "every occurrence"
     * wording.</p>
     *
     * @param rows   demand rows for the evaluation window; not queried here, supplied by the caller
     * @param today  the date this evaluation runs, used as {@link Opportunity#firstSeen()}
     * @param config thresholds; the global occurrence floor always applies even if
     *               {@link OpportunityEngineConfig#agentGapMinOccurrences()} is configured lower
     * @return opportunities for queries meeting the minimum support, in no particular order
     */
    static List<Opportunity> detect( final List<DemandRow> rows, final LocalDate today,
                                     final OpportunityEngineConfig config ) {
        final Map<String, List<DemandRow>> byQuery = new LinkedHashMap<>();
        for ( final DemandRow row : rows ) {
            byQuery.computeIfAbsent( row.queryText(), key -> new ArrayList<>() ).add( row );
        }

        final int minOccurrences = config.effectiveAgentGapMinOccurrences();
        final List<Opportunity> out = new ArrayList<>();

        for ( final Map.Entry<String, List<DemandRow>> entry : byQuery.entrySet() ) {
            final List<DemandRow> group = entry.getValue();

            final boolean everyRowWeak = group.stream().allMatch( AgentGapRule::isWeak );
            if ( !everyRowWeak ) {
                continue;
            }

            final int occurrences = group.stream().mapToInt( DemandRow::occurrences ).sum();
            final int distinctSessions = group.stream().mapToInt( DemandRow::distinctSessions ).sum();
            if ( occurrences < minOccurrences || distinctSessions < config.agentGapMinDistinctSessions() ) {
                continue;
            }

            final long zeroResultOccurrences = group.stream()
                    .filter( r -> r.resultCount() <= 0 ).mapToInt( DemandRow::occurrences ).sum();
            final long weakCoverageOccurrences = group.stream()
                    .filter( r -> r.resultCount() > 0 && isWeakCoverage( r.coverage() ) )
                    .mapToInt( DemandRow::occurrences ).sum();

            final Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put( "queryText", entry.getKey() );
            evidence.put( "occurrences", occurrences );
            evidence.put( "distinctSessions", distinctSessions );
            evidence.put( "zeroResultOccurrences", zeroResultOccurrences );
            evidence.put( "weakCoverageOccurrences", weakCoverageOccurrences );

            out.add( new Opportunity( OpportunityEngine.AGENT_GAP, entry.getKey(), occurrences * config.weightAgentGap(),
                    evidence, "Write or extend the section that answers this query, or sharpen the summary and headings of the page that should.",
                    today, false ) );
        }
        return out;
    }

    private static boolean isWeak( final DemandRow row ) {
        return row.resultCount() <= 0 || isWeakCoverage( row.coverage() );
    }

    private static boolean isWeakCoverage( final String coverage ) {
        return coverage != null && WEAK_COVERAGE.contains( coverage.toLowerCase( Locale.ROOT ) );
    }
}

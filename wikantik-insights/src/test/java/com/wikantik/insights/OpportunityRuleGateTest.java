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

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterises how the §7.3.0 traffic gate treats each rule of {@link OpportunityEngine#evaluateGated}:
 * the three gated rules each surface once in {@link Backlog#suppressed()} with their measured and
 * required values, {@code AGENT_GAP} always runs, and imported opportunities pass through
 * un-suppressed when the gate is closed.
 */
class OpportunityRuleGateTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 8, 17 );
    private static final String SITE = "wiki.wikantik.com";
    private static final String PAGE = "/wiki/DivergencePage";

    private final OpportunityEngine engine = new OpportunityEngine();
    private final OpportunityEngineConfig defaults = OpportunityEngineConfig.defaults();

    private static List<VisibilityRow> divergentRows() {
        return List.of(
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "google", SITE, PAGE, "", 20, 5, 2.0 ),
                new VisibilityRow( LocalDate.of( 2026, 8, 1 ), 28, "bing", SITE, PAGE, "", 5, 1, 12.0 ) );
    }

    private static Opportunity importedCtrGap( final String type ) {
        return new Opportunity( type, PAGE, 9.0, Map.of( "engine", "bing" ), "imported", TODAY, false );
    }

    private Backlog run( final int siteImpressions28d, final List<Opportunity> imported ) {
        return engine.evaluateGated( List.of( new DemandRow( "some query", null, 0, 5, 5 ) ), divergentRows(),
                imported, new MapPageFacts( Map.of() ), List.of(), Map.of(), siteImpressions28d, Set.of(),
                ExpectedCtrCurve.defaultCurve(), TODAY, defaults );
    }

    /** The merge in evaluateGated names these four types; a fifth rule would be evaluated, then silently dropped. */
    @Test
    void theRulesAreExactlyTheFourTypesTheMergeCombines() {
        assertEquals( List.of( OpportunityEngine.AGENT_GAP, OpportunityEngine.ENGINE_DIVERGENCE,
                        OpportunityEngine.VOCABULARY_GAP, OpportunityEngine.STALE_HIGH_TRAFFIC ),
                OpportunityEngine.RULES.stream().map( OpportunityRule::type ).toList() );
    }

    @Test
    void closedGateReportsEachGatedRuleOnceWithMeasuredAndRequired() {
        final Backlog backlog = run( 1234, List.of() );

        assertEquals( List.of( OpportunityEngine.ENGINE_DIVERGENCE, OpportunityEngine.VOCABULARY_GAP,
                        OpportunityEngine.STALE_HIGH_TRAFFIC ),
                backlog.suppressed().stream().map( SuppressedRule::type ).toList() );
        for ( final SuppressedRule rule : backlog.suppressed() ) {
            assertEquals( "traffic_gate", rule.reason() );
            assertEquals( 1234.0, rule.measured(), 0.0001 );
            assertEquals( defaults.gateImpressions28d(), rule.required(), 0.0001 );
        }
    }

    @Test
    void closedGateStillRunsAgentGap() {
        final Backlog backlog = run( 0, List.of() );

        assertEquals( List.of( OpportunityEngine.AGENT_GAP ),
                backlog.opportunities().stream().map( Opportunity::type ).toList() );
    }

    @Test
    void closedGateLetsImportedOpportunitiesThroughDivergenceSuppression() {
        final List<Opportunity> imported = List.of( importedCtrGap( "ctr_gap" ), importedCtrGap( "striking_distance" ) );

        final Backlog closed = run( 0, imported );
        assertTrue( closed.opportunities().stream().map( Opportunity::type ).toList()
                .containsAll( List.of( "ctr_gap", "striking_distance" ) ) );

        final Backlog open = run( Integer.MAX_VALUE, imported );
        final List<String> openTypes = open.opportunities().stream().map( Opportunity::type ).toList();
        assertTrue( openTypes.contains( OpportunityEngine.ENGINE_DIVERGENCE ) );
        assertTrue( !openTypes.contains( "ctr_gap" ) && !openTypes.contains( "striking_distance" ),
                "an open gate lets divergence suppress the imported pair" );
        assertTrue( open.suppressed().isEmpty() );
    }
}

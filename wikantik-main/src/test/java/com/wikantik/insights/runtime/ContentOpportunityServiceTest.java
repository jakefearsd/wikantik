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
package com.wikantik.insights.runtime;

import com.wikantik.insights.DemandRow;
import com.wikantik.insights.InsightsStore;
import com.wikantik.insights.Opportunity;
import com.wikantik.insights.OpportunityEngine;
import com.wikantik.insights.OpportunitySnooze;
import com.wikantik.insights.PageFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ContentOpportunityService} (content-intelligence design §7.5.1).
 */
class ContentOpportunityServiceTest {

    /** Trivial stub -- AGENT_GAP (the rule these tests exercise) never consults PageFacts. */
    private static final class StubPageFacts implements PageFacts {
        @Override
        public Optional<PageFact> lookup( final String pagePath ) {
            return Optional.empty();
        }
    }

    private InsightsStore store;
    private PageFacts pageFacts;
    private OpportunityEngine engine;

    @BeforeEach
    void setUp() {
        store = mock( InsightsStore.class );
        pageFacts = new StubPageFacts();
        engine = new OpportunityEngine();
    }

    private ContentOpportunityService serviceWith( final InsightsSettings settings ) {
        return new ContentOpportunityService( store, pageFacts, engine, settings );
    }

    private static InsightsSettings enabledSettings() {
        return InsightsSettings.from( new Properties() );
    }

    /** Three distinct AGENT_GAP-triggering demand rows with different priorities (occurrences x 2.0). */
    private static List<DemandRow> threeAgentGapRows() {
        return List.of(
                new DemandRow( "query-a", "weak", 0, 5, 2 ),  // priority 10.0
                new DemandRow( "query-b", "weak", 0, 3, 2 ),  // priority 6.0
                new DemandRow( "query-c", "weak", 0, 2, 2 ) ); // priority 4.0
    }

    // -- Fail-open ------------------------------------------------------------------------------

    @Test
    void storeThrowingDuringGatherFailsOpenToAnEmptyView() {
        when( store.siteImpressions28d( anyString() ) )
                .thenThrow( new IllegalStateException( "site impressions query failed" ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView view = assertDoesNotThrow(
                () -> service.backlog( "wiki.wikantik.com", null, null, 20, false ) );

        assertTrue( view.opportunities().isEmpty() );
        assertTrue( view.suppressed().isEmpty() );
        assertTrue( view.uncalibratedTypes().isEmpty() );
    }

    @Test
    void storeThrowingDuringDemandRowsFailsOpenToAnEmptyView() {
        when( store.demandRows( anyInt() ) )
                .thenThrow( new IllegalStateException( "demand rows query failed" ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView view = assertDoesNotThrow(
                () -> service.backlog( "wiki.wikantik.com", null, null, 20, false ) );

        assertTrue( view.opportunities().isEmpty() );
    }

    // -- Kill switch ------------------------------------------------------------------------------

    @Test
    void disabledSettingsShortCircuitsBeforeTouchingTheStore() {
        final Properties props = new Properties();
        props.setProperty( InsightsSettings.KEY_ENABLED, "false" );
        final ContentOpportunityService service = serviceWith( InsightsSettings.from( props ) );

        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, false );

        assertTrue( view.opportunities().isEmpty() );
        verifyNoInteractions( store );
    }

    // -- firstSeen ledger substitution ------------------------------------------------------------

    @Test
    void firstSeenIsReplacedByTheLedgerValueWhenPresent() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final LocalDate earlierDate = LocalDate.now().minusDays( 30 );
        when( store.upsertSeen( any(), any() ) )
                .thenReturn( Map.of( "agent_gap query-a", earlierDate ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, false );

        final Opportunity queryA = view.opportunities().stream()
                .filter( o -> "query-a".equals( o.target() ) ).findFirst().orElseThrow();
        assertEquals( earlierDate, queryA.firstSeen() );
    }

    @Test
    void firstSeenFallsBackToTodayWhenTheLedgerHasNoEntryForThePair() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        when( store.upsertSeen( any(), any() ) ).thenReturn( Map.of() ); // ledger write "failed"

        final ContentOpportunityService service = serviceWith( enabledSettings() );
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, false );

        assertFalse( view.opportunities().isEmpty() );
        for ( final Opportunity o : view.opportunities() ) {
            assertEquals( LocalDate.now(), o.firstSeen() );
        }
    }

    // -- Filters --------------------------------------------------------------------------------

    @Test
    void typeFilterExactMatchKeepsOnlyMatchingType() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView matching =
                service.backlog( "wiki.wikantik.com", OpportunityEngine.AGENT_GAP, null, 20, false );
        assertEquals( 3, matching.opportunities().size() );

        final ContentOpportunityService.BacklogView nonMatching =
                service.backlog( "wiki.wikantik.com", "stale_high_traffic", null, 20, false );
        assertTrue( nonMatching.opportunities().isEmpty() );
    }

    @Test
    void blankTypeFilterIsIgnored() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", "  ", null, 20, false );

        assertEquals( 3, view.opportunities().size() );
    }

    @Test
    void minPriorityDropsLowerPriorityOpportunities() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        // query-a=10.0, query-b=6.0, query-c=4.0 -- a floor of 5.0 keeps only a and b.
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, 5.0, 20, false );

        assertEquals( 2, view.opportunities().size() );
        assertTrue( view.opportunities().stream().allMatch( o -> o.priority() >= 5.0 ) );
    }

    @Test
    void limitTruncatesToTheHighestPriorityEntries() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 1, false );

        assertEquals( 1, view.opportunities().size() );
        assertEquals( "query-a", view.opportunities().get( 0 ).target() ); // highest priority (10.0)
    }

    @Test
    void nonPositiveLimitUsesTheDefaultOfTwenty() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 0, false );

        assertEquals( 3, view.opportunities().size() ); // all three fit under the default of 20
    }

    // -- Snooze filtering / includeSnoozed --------------------------------------------------------

    @Test
    void activeSnoozeExcludesItsOpportunityByDefault() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        when( store.activeSnoozes( any() ) ).thenReturn( List.of(
                new OpportunitySnooze( OpportunityEngine.AGENT_GAP, "query-a",
                        LocalDate.now().plusDays( 10 ), "not actionable right now", "tester" ) ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, false );

        assertTrue( view.opportunities().stream().noneMatch( o -> "query-a".equals( o.target() ) ) );
        assertEquals( 2, view.opportunities().size() );
    }

    @Test
    void includeSnoozedTrueBypassesSnoozeFiltering() {
        when( store.demandRows( anyInt() ) ).thenReturn( threeAgentGapRows() );
        when( store.activeSnoozes( any() ) ).thenReturn( List.of(
                new OpportunitySnooze( OpportunityEngine.AGENT_GAP, "query-a",
                        LocalDate.now().plusDays( 10 ), "not actionable right now", "tester" ) ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, true );

        assertTrue( view.opportunities().stream().anyMatch( o -> "query-a".equals( o.target() ) ) );
        assertEquals( 3, view.opportunities().size() );
    }

    // -- snooze() ---------------------------------------------------------------------------------

    @Test
    void snoozeRequiresAReason() {
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        assertThrows( IllegalArgumentException.class,
                () -> service.snooze( OpportunityEngine.AGENT_GAP, "query-a", 30, null, "tester" ) );
        assertThrows( IllegalArgumentException.class,
                () -> service.snooze( OpportunityEngine.AGENT_GAP, "query-a", 30, "   ", "tester" ) );
    }

    @Test
    void snoozeReportsNotPreviouslySnoozedWhenNoActiveSnoozeExists() {
        when( store.activeSnoozes( any() ) ).thenReturn( List.of() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.SnoozeResult result =
                service.snooze( OpportunityEngine.AGENT_GAP, "query-a", 30, "not actionable", "tester" );

        assertFalse( result.previouslySnoozed() );
        assertEquals( LocalDate.now().plusDays( 30 ), result.snoozedUntil() );
    }

    @Test
    void snoozeReportsPreviouslySnoozedWhenAnUnexpiredSnoozeAlreadyExists() {
        when( store.activeSnoozes( any() ) ).thenReturn( List.of(
                new OpportunitySnooze( OpportunityEngine.AGENT_GAP, "query-a",
                        LocalDate.now().plusDays( 5 ), "earlier reason", "someone-else" ) ) );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.SnoozeResult result =
                service.snooze( OpportunityEngine.AGENT_GAP, "query-a", 30, "still not actionable", "tester" );

        assertTrue( result.previouslySnoozed() );
    }

    @Test
    void snoozeDaysAreClampedToOneAndThreeSixtyFive() {
        when( store.activeSnoozes( any() ) ).thenReturn( List.of() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        final ContentOpportunityService.SnoozeResult tooLow =
                service.snooze( OpportunityEngine.AGENT_GAP, "query-a", 0, "reason", "tester" );
        assertEquals( LocalDate.now().plusDays( 1 ), tooLow.snoozedUntil() );

        final ContentOpportunityService.SnoozeResult tooHigh =
                service.snooze( OpportunityEngine.AGENT_GAP, "query-b", 10_000, "reason", "tester" );
        assertEquals( LocalDate.now().plusDays( 365 ), tooHigh.snoozedUntil() );
    }

    // -- Imported opportunities (content-intelligence design §7.3 "Imported from jakemon", §12.1 J3) --

    @Test
    void importedOpportunitiesAppearInTheBacklogEvenWhenTheTrafficGateIsClosed() {
        // siteImpressions28d is unstubbed -- Mockito's default int return is 0, well below the
        // 5000 default gate, so the three visibility-driven native rules do not run. Imported
        // opportunities are never gated (design §7.3.0) and must still appear.
        final Opportunity imported = new Opportunity( "ctr_gap", "low latency queue", 12.3,
                Map.of( "engine", "google", "confidence", 0.6 ), "Rewrite the title/meta snippet.",
                LocalDate.now(), false );
        when( store.latestImported( anyString(), anyInt() ) ).thenReturn( List.of( imported ) );

        final ContentOpportunityService service = serviceWith( enabledSettings() );
        final ContentOpportunityService.BacklogView view =
                service.backlog( "wiki.wikantik.com", null, null, 20, false );

        assertTrue( view.opportunities().stream().anyMatch(
                o -> "ctr_gap".equals( o.type() ) && "low latency queue".equals( o.target() ) ) );
        assertFalse( view.suppressed().isEmpty(), "the native visibility rules are still gated" );
        assertTrue( view.suppressed().stream().noneMatch( s -> "ctr_gap".equals( s.type() ) ),
                "ctr_gap is imported, not one of the three gated native rules -- it must never be "
                + "reported as suppressed" );
    }

    @Test
    void importedOpportunitiesArePassedTheConfiguredMaxAgeDays() {
        when( store.latestImported( anyString(), anyInt() ) ).thenReturn( List.of() );
        final ContentOpportunityService service = serviceWith( enabledSettings() );

        service.backlog( "wiki.wikantik.com", null, null, 20, false );

        verify( store ).latestImported( "wiki.wikantik.com",
                InsightsSettings.from( new Properties() ).importedMaxAgeDays() );
    }

    // -- Constructor guard ------------------------------------------------------------------------

    @Test
    void constructorRejectsNullCollaborators() {
        final InsightsSettings settings = enabledSettings();
        assertThrows( IllegalArgumentException.class,
                () -> new ContentOpportunityService( null, pageFacts, engine, settings ) );
        assertThrows( IllegalArgumentException.class,
                () -> new ContentOpportunityService( store, null, engine, settings ) );
        assertThrows( IllegalArgumentException.class,
                () -> new ContentOpportunityService( store, pageFacts, null, settings ) );
        assertThrows( IllegalArgumentException.class,
                () -> new ContentOpportunityService( store, pageFacts, engine, null ) );
    }
}

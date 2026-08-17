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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verdict logic for {@link EffectEvaluator} (design section 7.4). */
class EffectEvaluatorTest {

    private static final String SITE = "wiki.wikantik.com";
    private static final String PAGE = "/wiki/DeployGuide";
    private static final LocalDate TODAY = LocalDate.parse( "2026-08-17" );
    private static final LocalDate APPLIED = LocalDate.parse( "2026-07-01" );
    private static final int MIN_BASELINE = 100;

    private InsightsStore store;
    private EffectEvaluator evaluator;

    @BeforeEach
    void setUp() {
        store = mock( InsightsStore.class );
        evaluator = new EffectEvaluator( store, SITE, 28, MIN_BASELINE, 5 );
        when( store.recordEffect( anyLong(), anyString(), any(), any(), any(), anyString(), anyString() ) )
                .thenReturn( true );
    }

    /** Baseline impressions, baseline clicks -> a change row applied on {@link #APPLIED}. */
    private static PendingChange change( final int baselineImpressions, final int baselineClicks,
                                         final Double baselineCtr ) {
        return new PendingChange( 42L, PAGE, "title", "engine_divergence",
                APPLIED.atStartOfDay( ZoneOffset.UTC ).toInstant(), "agent", "note",
                APPLIED.minusDays( 28 ), APPLIED,
                baselineImpressions, baselineClicks, baselineCtr, 20.0 );
    }

    private void givenPending( final PendingChange... changes ) {
        when( store.unevaluatedChanges( any( LocalDate.class ) ) ).thenReturn( List.of( changes ) );
    }

    private void givenAfterWindow( final int impressions, final int clicks ) {
        when( store.pageWindowNear( eq( SITE ), eq( PAGE ), any( LocalDate.class ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow(
                        APPLIED.plusDays( 28 ), impressions, clicks, 18.0 ) ) );
    }

    /** Site totals that do not move, so the difference-in-differences term is zero. */
    private void givenFlatSiteTrend() {
        when( store.siteWindowNear( eq( SITE ), any( LocalDate.class ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow( APPLIED, 10_000, 1_000, 25.0 ) ) );
    }

    private String capturedVerdict() {
        final ArgumentCaptor< String > verdict = ArgumentCaptor.forClass( String.class );
        verify( store ).recordEffect( eq( 42L ), verdict.capture(), any(), any(), any(),
                anyString(), anyString() );
        return verdict.getValue();
    }

    private String capturedDetail() {
        final ArgumentCaptor< String > detail = ArgumentCaptor.forClass( String.class );
        verify( store ).recordEffect( anyLong(), anyString(), any(), any(), any(),
                anyString(), detail.capture() );
        return detail.getValue();
    }

    @Test
    void baselineBelowTheSupportFloorIsInsufficientData() {
        givenPending( change( MIN_BASELINE - 1, 5, 0.05 ) );

        assertEquals( 1, evaluator.evaluatePending( TODAY ) );

        assertEquals( EffectEvaluator.INSUFFICIENT_DATA, capturedVerdict() );
        // Cheap enough to short-circuit before touching the snapshot store at all.
        verify( store, never() ).pageWindowNear( anyString(), anyString(), any(), anyInt() );
    }

    @Test
    void noSnapshotNearTheAfterWindowIsInsufficientData() {
        givenPending( change( 1_000, 100, 0.10 ) );
        when( store.pageWindowNear( eq( SITE ), eq( PAGE ), any( LocalDate.class ), anyInt() ) )
                .thenReturn( Optional.empty() );

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.INSUFFICIENT_DATA, capturedVerdict() );
    }

    @Test
    void ctrGainBeyondTheThresholdIsImproved() {
        givenPending( change( 1_000, 100, 0.10 ) );   // baseline CTR 0.10
        givenAfterWindow( 1_000, 120 );               // after CTR 0.12 -> +20% relative
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.IMPROVED, capturedVerdict() );
    }

    @Test
    void ctrLossBeyondTheThresholdIsRegressed() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 80 );                // after CTR 0.08 -> -20% relative
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.REGRESSED, capturedVerdict() );
    }

    @Test
    void movementInsideTheThresholdIsNoEffect() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 105 );               // +5% relative, under the 15% bar
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.NO_EFFECT, capturedVerdict() );
    }

    /**
     * The control that makes the whole thing more than a before/after anecdote: a page that rose
     * exactly as much as the entire site rose has demonstrated nothing, and must not be credited
     * with an improvement. Without the difference-in-differences term this case reads as +100%.
     */
    @Test
    void aPageThatOnlyTrackedTheSiteTrendIsNotCreditedWithAnImprovement() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 200 );               // page CTR 0.10 -> 0.20, +0.10
        when( store.siteWindowNear( eq( SITE ), eq( APPLIED ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow( APPLIED, 10_000, 1_000, 25.0 ) ) );   // 0.10
        when( store.siteWindowNear( eq( SITE ), eq( APPLIED.plusDays( 28 ) ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow( APPLIED.plusDays( 28 ), 10_000, 2_000, 25.0 ) ) ); // 0.20

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.NO_EFFECT, capturedVerdict() );
        assertTrue( capturedDetail().contains( "\"differenceInDifferences\":true" ) );
    }

    @Test
    void aMissingSiteWindowSkipsTheAdjustmentAndSaysSo() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 120 );
        when( store.siteWindowNear( eq( SITE ), any( LocalDate.class ), anyInt() ) )
                .thenReturn( Optional.empty() );

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.IMPROVED, capturedVerdict() );
        assertTrue( capturedDetail().contains( "\"differenceInDifferences\":false" ),
                "an unadjusted verdict must not masquerade as an adjusted one" );
    }

    /**
     * The common case on this corpus: a page with impressions but no clicks. A relative threshold
     * against a zero rate is a division by zero, so the verdict is resolved explicitly.
     */
    @Test
    void zeroBaselineCtrWithClicksAfterIsImproved() {
        givenPending( change( 1_000, 0, 0.0 ) );
        givenAfterWindow( 1_000, 3 );
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.IMPROVED, capturedVerdict() );
        assertTrue( capturedDetail().contains( "\"zeroBaselineCtr\":true" ) );
    }

    @Test
    void zeroBaselineCtrWithNoClicksAfterIsNoEffect() {
        givenPending( change( 1_000, 0, 0.0 ) );
        givenAfterWindow( 1_000, 0 );
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        assertEquals( EffectEvaluator.NO_EFFECT, capturedVerdict() );
    }

    /** Realised click delta is what calibration divides by predicted priority, so it is asserted exactly. */
    @Test
    void realisedClickDeltaSubtractsTheSiteTrendInClickUnits() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 150 );
        when( store.siteWindowNear( eq( SITE ), eq( APPLIED ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow( APPLIED, 10_000, 1_000, 25.0 ) ) );   // 0.10
        when( store.siteWindowNear( eq( SITE ), eq( APPLIED.plusDays( 28 ) ), anyInt() ) )
                .thenReturn( Optional.of( new PageWindow( APPLIED.plusDays( 28 ), 10_000, 1_200, 25.0 ) ) ); // 0.12

        evaluator.evaluatePending( TODAY );

        // raw delta 50, minus the site trend applied to the baseline's own volume
        // (1000 impressions * (0.12 - 0.10) = 20) -> 30.
        final ArgumentCaptor< Double > clickDelta = ArgumentCaptor.forClass( Double.class );
        verify( store ).recordEffect( anyLong(), anyString(), any(), any(), clickDelta.capture(),
                anyString(), anyString() );
        assertEquals( 30.0, clickDelta.getValue(), 1e-9 );
    }

    @Test
    void everyVerdictRecordsTheComparisonModeItCouldActuallyRun() {
        givenPending( change( 1_000, 100, 0.10 ) );
        givenAfterWindow( 1_000, 120 );
        givenFlatSiteTrend();

        evaluator.evaluatePending( TODAY );

        verify( store ).recordEffect( anyLong(), anyString(), any(), any(), any(),
                eq( EffectEvaluator.METHOD_PAGE_ROLLUP ), anyString() );
    }

    /** One unevaluatable row must not cost every other row its verdict. */
    @Test
    void aFailingChangeDoesNotAbortTheBatch() {
        final PendingChange bad = new PendingChange( 7L, "/wiki/Bad", "title", null,
                APPLIED.atStartOfDay( ZoneOffset.UTC ).toInstant(), "agent", null,
                APPLIED.minusDays( 28 ), APPLIED, 1_000, 100, 0.10, 20.0 );
        when( store.unevaluatedChanges( any( LocalDate.class ) ) )
                .thenReturn( List.of( bad, change( 1_000, 100, 0.10 ) ) );
        when( store.pageWindowNear( eq( SITE ), eq( "/wiki/Bad" ), any( LocalDate.class ), anyInt() ) )
                .thenThrow( new IllegalStateException( "boom" ) );
        givenAfterWindow( 1_000, 120 );
        givenFlatSiteTrend();

        assertEquals( 1, evaluator.evaluatePending( TODAY ), "the healthy change is still evaluated" );
        verify( store, times( 1 ) ).recordEffect( eq( 42L ), anyString(), any(), any(), any(),
                anyString(), anyString() );
    }

    @Test
    void anUnreadableChangeListIsLoggedAndYieldsZeroRatherThanThrowing() {
        when( store.unevaluatedChanges( any( LocalDate.class ) ) )
                .thenThrow( new IllegalStateException( "database down" ) );

        assertEquals( 0, evaluator.evaluatePending( TODAY ) );
        verify( store, never() ).recordEffect( anyLong(), anyString(), any(), any(), any(),
                anyString(), anyString() );
    }
}

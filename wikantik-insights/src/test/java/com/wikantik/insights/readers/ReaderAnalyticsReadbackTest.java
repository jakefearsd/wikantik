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
package com.wikantik.insights.readers;

import com.wikantik.insights.readers.UmamiViews.AcquisitionRow;
import com.wikantik.insights.readers.UmamiViews.EngagementRow;
import com.wikantik.insights.readers.UmamiViews.TrafficRow;
import com.wikantik.insights.readers.UmamiViews.VitalsRow;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderAnalyticsReadbackTest {

    private static final LocalDate TODAY = LocalDate.of( 2026, 11, 24 );
    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-24T23:30:00Z" ), ZoneOffset.UTC );

    private UmamiViews views;
    private ReaderRollupStore store;
    private SimpleMeterRegistry meters;
    private final PathResolver resolver = path -> switch ( path ) {
        case "/wiki/A", "/wiki/A?x=1" -> Optional.of( "01A" );
        case "/wiki/B" -> Optional.of( "01B" );
        default -> Optional.empty();
    };

    @BeforeEach
    void setUp() {
        views = mock( UmamiViews.class );
        store = mock( ReaderRollupStore.class );
        meters = new SimpleMeterRegistry();
        when( views.traffic( any(), any() ) ).thenReturn( List.of() );
        when( views.engagement( any(), any() ) ).thenReturn( List.of() );
        when( views.vitals( any(), any() ) ).thenReturn( List.of() );
        when( views.acquisition( any(), any() ) ).thenReturn( List.of() );
    }

    private ReaderAnalyticsReadback readback() {
        return new ReaderAnalyticsReadback( views, store, () -> resolver, "wiki.example.com", 7, CLOCK, meters );
    }

    @SuppressWarnings( "unchecked" )
    private List< EngagementDay > capturedEngagement() {
        final ArgumentCaptor< List< EngagementDay > > c = ArgumentCaptor.forClass( List.class );
        verify( store ).replace( anyString(), any(), any(), c.capture(), anyList() );
        return c.getValue();
    }

    @Test
    void twoPathsForOneIdMergeAndVitalsComeFromTheBusiestPath() {
        when( views.traffic( any(), any() ) ).thenReturn( List.of(
            new TrafficRow( TODAY, "/wiki/A", 3, 5 ), new TrafficRow( TODAY, "/wiki/A?x=1", 2, 4 ) ) );
        when( views.vitals( any(), any() ) ).thenReturn( List.of(
            new VitalsRow( TODAY, "/wiki/A", 5, 2000.0, 100.0, 0.05 ),
            new VitalsRow( TODAY, "/wiki/A?x=1", 20, 3000.0, null, 0.2 ) ) );
        when( views.engagement( any(), any() ) ).thenReturn( List.of(
            new EngagementRow( TODAY, "01A", 2, 10.0, 30.0, 1, 1, 1, 1, 1, 1, 1 ) ) );

        final ReaderAnalyticsReadback.ReadbackResult result = readback().runOnce();

        assertTrue( result.ok() );
        final List< EngagementDay > rows = capturedEngagement();
        assertEquals( 1, rows.size() );
        final EngagementDay a = rows.get( 0 );
        assertEquals( "01A", a.canonicalId() );
        assertEquals( 9, a.views() );
        assertEquals( 5, a.visitors() );
        assertEquals( 2, a.exits() );
        assertEquals( 10.0, a.activeMedianS() );
        assertEquals( 3000.0, a.lcpP75Ms() );
        assertNull( a.inpP75Ms() );
        assertEquals( 0.2, a.clsP75() );
    }

    @Test
    void anUnresolvedPathGoesToTheSiteRow() {
        when( views.traffic( any(), any() ) ).thenReturn( List.of(
            new TrafficRow( TODAY, "/search", 1, 2 ), new TrafficRow( TODAY, "/login", 1, 1 ) ) );
        when( views.acquisition( any(), any() ) ).thenReturn( List.of(
            new AcquisitionRow( TODAY, "/search", "organic", "google", 2, 1, 0 ),
            new AcquisitionRow( TODAY, "/wiki/B", "organic", "google", 3, 2, 1 ),
            new AcquisitionRow( TODAY, "/login", "organic", "google", 4, 0, 1 ) ) );

        readback().runOnce();

        final List< EngagementDay > rows = capturedEngagement();
        assertEquals( 1, rows.size() );
        assertEquals( "", rows.get( 0 ).canonicalId() );
        assertEquals( 3, rows.get( 0 ).views() );
        @SuppressWarnings( "unchecked" )
        final ArgumentCaptor< List< AcquisitionDay > > acq = ArgumentCaptor.forClass( List.class );
        verify( store ).replace( anyString(), any(), any(), anyList(), acq.capture() );
        assertEquals( 2, acq.getValue().size() );
        final AcquisitionDay site = acq.getValue().stream().filter( r -> r.canonicalId().isEmpty() ).findFirst().orElseThrow();
        assertEquals( 6, site.landings() );
        assertEquals( 1, site.continued() );
        assertEquals( 1, site.returningLandings() );
    }

    @Test
    void aViewFailureIsReportedAndNeverWritesTheStore() {
        when( views.engagement( any(), any() ) ).thenThrow( new RuntimeException( "view gone" ) );

        final ReaderAnalyticsReadback.ReadbackResult result = readback().runOnce();

        assertFalse( result.ok() );
        assertEquals( "view gone", result.error() );
        verify( store, never() ).replace( anyString(), any(), any(), anyList(), anyList() );
        assertEquals( 1.0, meters.get( "wikantik_reader_readback_runs_total" ).tag( "outcome", "failed" ).counter().count() );
    }

    @Test
    void successCountsAndStampsTheGauge() {
        assertTrue( readback().runOnce().ok() );

        assertEquals( 1.0, meters.get( "wikantik_reader_readback_runs_total" ).tag( "outcome", "ok" ).counter().count() );
        assertEquals( CLOCK.instant().getEpochSecond(),
                      meters.get( "wikantik_reader_readback_last_success_seconds" ).gauge().value() );
    }

    @Test
    void theWindowIsTheLastNDaysEndingTodayInUtc() {
        readback().runOnce();

        final LocalDate from = TODAY.minusDays( 6 );
        verify( views ).traffic( from, TODAY );
        verify( views ).engagement( from, TODAY );
        verify( views ).vitals( from, TODAY );
        verify( views ).acquisition( from, TODAY );
        verify( store ).replace( "wiki.example.com", from, TODAY, List.of(), List.of() );
    }
}

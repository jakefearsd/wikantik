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
package com.wikantik.work;

import com.wikantik.work.WorkFlowStore.ClosedItem;
import com.wikantik.work.WorkFlowStore.OpenItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowTest {

    private static final Clock CLOCK = Clock.fixed( Instant.parse( "2026-11-21T10:00:00Z" ), ZoneOffset.UTC );
    private static final MetricWindow WINDOW =
        MetricWindow.of( LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-15" ), CLOCK );
    private static final Instant NOW = Instant.parse( "2026-11-21T10:00:00Z" );

    private static Instant t( final String s ) {
        return Instant.parse( "2026-" + s + ":00Z" );
    }

    private static ClosedItem closed( final String key, final String created, final String started, final String done,
                                      final String estimate ) {
        return new ClosedItem( key.hashCode(), key, t( created ), started == null ? null : t( started ), t( done ),
            estimate == null ? null : new BigDecimal( estimate ), Duration.ZERO );
    }

    @Test
    void cycleTimeExcludesTheTimeAReopenedItemSatDone() {
        final ClosedItem c = new ClosedItem( 1, "A-1", t( "11-01T09:00" ), t( "11-02T09:00" ), t( "11-10T09:00" ), null,
            Duration.ofDays( 1 ) );
        assertEquals( Duration.ofDays( 7 ), Flow.cycleTime( List.of( c ) ).p50() );
    }

    @Test
    void throughputHasAWeekForEveryWeekEvenEmpty() {
        final List< FlowReport.Week > weeks = Flow.throughput(
            List.of( closed( "A-1", "11-01T09:00", null, "11-10T09:00", null ) ), WINDOW );
        assertEquals( List.of( LocalDate.parse( "2026-11-02" ), LocalDate.parse( "2026-11-09" ) ),
            weeks.stream().map( FlowReport.Week::weekStart ).toList() );
        assertEquals( List.of( 0, 1 ), weeks.stream().map( FlowReport.Week::items ).toList() );
    }

    @Test
    void pointsTreatAMissingEstimateAsZero() {
        final List< FlowReport.Week > weeks = Flow.throughput( List.of(
            closed( "A-1", "11-01T09:00", null, "11-03T09:00", "3" ), closed( "A-2", "11-01T09:00", null, "11-04T09:00", null ),
            closed( "A-3", "11-01T09:00", null, "11-05T09:00", "1.5" ) ), WINDOW );
        assertEquals( 3, weeks.get( 0 ).items() );
        assertEquals( 0, new BigDecimal( "4.5" ).compareTo( weeks.get( 0 ).points() ) );
        assertEquals( 0, BigDecimal.ZERO.compareTo( weeks.get( 1 ).points() ), "an empty week writes zero points" );
    }

    @Test
    void anItemNeverInProgressCountsInLeadTimeOnly() {
        final List< ClosedItem > items = List.of( closed( "A-1", "11-01T09:00", null, "11-10T09:00", null ) );
        assertEquals( 0, Flow.cycleTime( items ).n() );
        assertEquals( 1, Flow.leadTime( items ).n() );
        assertEquals( Duration.ofDays( 9 ), Flow.leadTime( items ).p50() );
    }

    @Test
    void cycleTimeRunsFromTheFirstStart() {
        final Durations d = Flow.cycleTime( List.of( closed( "A-1", "11-01T09:00", "11-02T09:00", "11-10T09:00", null ) ) );
        assertEquals( Duration.ofDays( 8 ), d.p50() );
    }

    @Test
    void wipListsBothStatusesOldestFirstWithTheirAge() {
        final List< FlowReport.Wip > wip = Flow.wip( List.of(
            new OpenItem( "A-1", "young", WorkStatus.IN_PROGRESS, t( "11-19T10:00" ), t( "11-20T10:00" ) ),
            new OpenItem( "A-2", "old", WorkStatus.IN_PROGRESS, t( "11-01T10:00" ), t( "11-11T10:00" ) ),
            new OpenItem( "A-3", "never started", WorkStatus.REVIEW, t( "11-16T10:00" ), null ) ), NOW );
        assertEquals( List.of( WorkStatus.IN_PROGRESS, WorkStatus.REVIEW ), wip.stream().map( FlowReport.Wip::status ).toList() );
        assertEquals( List.of( "A-2", "A-1" ), wip.get( 0 ).items().stream().map( FlowReport.WipItem::key ).toList() );
        assertEquals( Duration.ofDays( 10 ), wip.get( 0 ).items().get( 0 ).age() );
        assertEquals( Duration.ofDays( 5 ), wip.get( 1 ).items().get( 0 ).age(), "an item without a start ages from creation" );
    }

    @Test
    void bothWipStatusesArePresentWhenNothingIsOpen() {
        final List< FlowReport.Wip > wip = Flow.wip( List.of(), NOW );
        assertEquals( 2, wip.size() );
        assertEquals( 0, wip.get( 0 ).count() );
        assertEquals( List.of(), wip.get( 1 ).items() );
    }

    @Test
    void wipCapsTheListAt50ButCountsAll() {
        final List< OpenItem > open = new ArrayList<>();
        for ( int i = 0; i < 60; i++ ) {
            open.add( new OpenItem( "A-" + i, "t", WorkStatus.IN_PROGRESS, t( "11-01T10:00" ), t( "11-01T10:00" ).plusSeconds( i ) ) );
        }
        final FlowReport.Wip wip = Flow.wip( open, NOW ).get( 0 );
        assertEquals( 60, wip.count() );
        assertEquals( 50, wip.items().size() );
        assertEquals( "A-0", wip.items().get( 0 ).key(), "oldest first" );
    }
}

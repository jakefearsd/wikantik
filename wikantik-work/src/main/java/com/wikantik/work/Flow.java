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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The flow figures as pure functions over item facts: no database, no clock. */
final class Flow {

    /** How many items one work-in-progress list names; the count is always the total. */
    static final int WIP_LIST = 50;

    private Flow() {
    }

    /** One week for every week of the window, empty ones included; an item counts in the week of its close. */
    static List< FlowReport.Week > throughput( final List< ClosedItem > closed, final MetricWindow w ) {
        final Map< LocalDate, int[] > items = new LinkedHashMap<>();
        final Map< LocalDate, BigDecimal > points = new LinkedHashMap<>();
        for ( final LocalDate week : w.weekStarts() ) {
            items.put( week, new int[ 1 ] );
            points.put( week, BigDecimal.ZERO );
        }
        for ( final ClosedItem c : closed ) {
            final LocalDate week = w.weekOf( c.closedAt() );
            final int[] count = items.get( week );
            if ( count != null ) {
                count[ 0 ]++;
                points.merge( week, c.estimate() == null ? BigDecimal.ZERO : c.estimate(), BigDecimal::add );
            }
        }
        final List< FlowReport.Week > out = new ArrayList<>();
        items.forEach( ( week, count ) -> out.add( new FlowReport.Week( week, count[ 0 ], points.get( week ) ) ) );
        return out;
    }

    /** Close minus first start, for the items that were ever started. */
    static Durations cycleTime( final List< ClosedItem > closed ) {
        return Percentiles.of( closed.stream().filter( c -> c.firstInProgressAt() != null )
            .map( c -> Duration.between( c.firstInProgressAt(), c.closedAt() ) ).toList() );
    }

    /** Close minus creation, for every closed item. */
    static Durations leadTime( final List< ClosedItem > closed ) {
        return Percentiles.of( closed.stream().map( c -> Duration.between( c.createdAt(), c.closedAt() ) ).toList() );
    }

    /** One entry for in progress and one for review, both always present; oldest items first, at most 50 each. */
    static List< FlowReport.Wip > wip( final List< OpenItem > open, final Instant now ) {
        final List< FlowReport.Wip > out = new ArrayList<>();
        for ( final WorkStatus status : List.of( WorkStatus.IN_PROGRESS, WorkStatus.REVIEW ) ) {
            final List< FlowReport.WipItem > all = open.stream().filter( o -> o.status() == status )
                .map( o -> new FlowReport.WipItem( o.key(), o.title(), age( o, now ) ) )
                .sorted( Comparator.comparing( FlowReport.WipItem::age ).reversed()
                    .thenComparing( FlowReport.WipItem::key ) ).toList();
            out.add( new FlowReport.Wip( status, all.size(), all.subList( 0, Math.min( WIP_LIST, all.size() ) ) ) );
        }
        return out;
    }

    private static Duration age( final OpenItem o, final Instant now ) {
        final Instant since = o.firstInProgressAt() == null ? o.createdAt() : o.firstInProgressAt();
        final Duration age = Duration.between( since, now );
        return age.isNegative() ? Duration.ZERO : age;
    }
}

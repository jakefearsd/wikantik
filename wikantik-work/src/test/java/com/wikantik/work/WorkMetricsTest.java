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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WorkStore store = mock( WorkStore.class );
    private final AtomicLong nanos = new AtomicLong();

    private WorkMetrics metrics() {
        return new WorkMetrics( registry, store, nanos::get, TTL_NANOS );
    }

    private static final long TTL_NANOS = 5_000_000_000L;

    private static WorkStore.ItemCount count( final WorkStatus s, final long n ) {
        return new WorkStore.ItemCount( s, WorkLevel.TASK, WorkKind.SOFTWARE, n );
    }

    private Gauge gauge( final String status ) {
        return registry.find( "wikantik_work_items" ).tag( "status", status ).tag( "level", "task" ).tag( "kind", "software" ).gauge();
    }

    @Test
    void gaugeHasNoProjectLabel() throws SQLException {
        when( store.itemCounts() ).thenReturn( List.of( count( WorkStatus.DRAFT, 3 ) ) );
        metrics();
        final Set< String > keys = new TreeSet<>();
        for ( final Meter m : registry.find( "wikantik_work_items" ).meters() ) {
            keys.addAll( m.getId().getTags().stream().map( Tag::getKey ).collect( Collectors.toSet() ) );
        }
        assertEquals( Set.of( "kind", "level", "status" ), keys );
    }

    @Test
    void gaugeReportsTheStoredCount() throws SQLException {
        when( store.itemCounts() ).thenReturn( List.of( count( WorkStatus.DRAFT, 3 ) ) );
        metrics();
        assertEquals( 3.0, gauge( "draft" ).value() );
    }

    @Test
    void oneScrapeQueriesTheStoreOnce() throws SQLException {
        final AtomicInteger calls = new AtomicInteger();
        when( store.itemCounts() ).thenAnswer( inv -> {
            calls.incrementAndGet();
            return List.of( count( WorkStatus.DRAFT, 1 ), count( WorkStatus.DONE, 2 ) );
        } );
        metrics();
        assertEquals( 1.0, gauge( "draft" ).value() );
        assertEquals( 2.0, gauge( "done" ).value() );
        assertEquals( 1, calls.get(), "both gauges of one scrape share one query" );
    }

    @Test
    void aFailingQueryReportsZeroInsteadOfThrowing() throws SQLException {
        when( store.itemCounts() ).thenReturn( List.of( count( WorkStatus.DRAFT, 3 ) ) );
        metrics();
        assertEquals( 3.0, gauge( "draft" ).value() );
        when( store.itemCounts() ).thenThrow( new SQLException( "down" ) );
        nanos.addAndGet( TTL_NANOS + 1 );
        assertEquals( 0.0, gauge( "draft" ).value() );
    }

    @Test
    void denialsAreCountedByAction() {
        final WorkMetrics metrics = metrics();
        metrics.denied( Action.MOVE );
        metrics.denied( Action.MOVE );
        assertEquals( 2.0, registry.find( "wikantik_work_denied_total" ).tag( "action", "move" ).counter().count() );
    }
}

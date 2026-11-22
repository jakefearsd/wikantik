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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final WorkStore store = mock( WorkStore.class );
    private final WorkClaimStore claimStore = mock( WorkClaimStore.class );
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

    @Test
    void claimCounterHasOnlyOutcomeTag() {
        final WorkMetrics metrics = metrics();
        for ( final ClaimOutcome o : ClaimOutcome.values() ) {
            metrics.claim( o );
        }
        final Set< String > keys = new TreeSet<>();
        final Set< String > values = new TreeSet<>();
        for ( final Meter m : registry.find( "wikantik_work_claims_total" ).meters() ) {
            for ( final Tag t : m.getId().getTags() ) {
                keys.add( t.getKey() );
                values.add( t.getValue() );
            }
        }
        assertEquals( Set.of( "outcome" ), keys );
        assertEquals( Stream.of( ClaimOutcome.values() ).map( ClaimOutcome::wire )
            .collect( Collectors.toCollection( TreeSet::new ) ), values );
    }

    @Test
    void notificationsAreCountedByChannelAndOutcome() {
        final WorkMetrics metrics = metrics();
        metrics.notified( "inbox", "delivered" );
        metrics.notified( "inbox", "delivered" );
        metrics.notified( "email", "failed" );
        assertEquals( 2.0, registry.find( "wikantik_work_notifications_total" ).tag( "channel", "inbox" )
            .tag( "outcome", "delivered" ).counter().count() );
        assertEquals( 1.0, registry.find( "wikantik_work_notifications_total" ).tag( "channel", "email" )
            .tag( "outcome", "failed" ).counter().count() );
        registry.find( "wikantik_work_notifications_total" ).counters().forEach( c ->
            assertEquals( java.util.Set.of( "channel", "outcome" ), c.getId().getTags().stream()
                .map( io.micrometer.core.instrument.Tag::getKey ).collect( java.util.stream.Collectors.toSet() ),
                "no project or item label" ) );
    }

    @Test
    void deploymentTransitionsAreCountedWithNoProductLabel() {
        final WorkMetrics metrics = metrics();
        metrics.deployment( "requested" );
        metrics.deployment( "verified" );
        metrics.deployment( "verified" );
        assertEquals( 2.0, registry.find( "wikantik_work_deployments_total" ).tag( "state", "verified" ).counter().count() );
        assertEquals( 1.0, registry.find( "wikantik_work_deployments_total" ).tag( "state", "requested" ).counter().count() );
        final Set< String > keys = new TreeSet<>();
        for ( final Meter m : registry.find( "wikantik_work_deployments_total" ).meters() ) {
            for ( final Tag t : m.getId().getTags() ) {
                keys.add( t.getKey() );
            }
        }
        assertEquals( Set.of( "state" ), keys, "no product, project or item label" );
    }

    @Test
    void gateOutcomesAreCountedWithNoProjectLabel() {
        final WorkMetrics metrics = metrics();
        metrics.gate( "merge", true );
        metrics.gate( "merge", true );
        metrics.gate( "merge", false );
        metrics.gate( "close", false );
        assertEquals( 2.0, registry.find( "wikantik_work_gates_total" ).tag( "gate", "merge" ).tag( "outcome", "allowed" )
            .counter().count() );
        assertEquals( 1.0, registry.find( "wikantik_work_gates_total" ).tag( "gate", "merge" ).tag( "outcome", "refused" )
            .counter().count() );
        assertEquals( 1.0, registry.find( "wikantik_work_gates_total" ).tag( "gate", "close" ).tag( "outcome", "refused" )
            .counter().count() );
        final Set< String > keys = new TreeSet<>();
        for ( final Meter m : registry.find( "wikantik_work_gates_total" ).meters() ) {
            for ( final Tag t : m.getId().getTags() ) {
                keys.add( t.getKey() );
            }
        }
        assertEquals( Set.of( "gate", "outcome" ), keys, "no project, product or item label" );
    }

    @Test
    void claimCounterCountsEachOutcomeSeparately() {
        final WorkMetrics metrics = metrics();
        metrics.claim( ClaimOutcome.CLAIMED );
        metrics.claim( ClaimOutcome.CLAIMED );
        metrics.claim( ClaimOutcome.EXPIRED );
        assertEquals( 2.0, registry.find( "wikantik_work_claims_total" ).tag( "outcome", "claimed" ).counter().count() );
        assertEquals( 1.0, registry.find( "wikantik_work_claims_total" ).tag( "outcome", "expired" ).counter().count() );
    }

    @Test
    void activeClaimsGaugeHasNoTags() throws SQLException {
        final WorkClaimStore claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        when( claims.activeClaimCount() ).thenReturn( 4L );
        metrics();
        final Gauge g = registry.find( "wikantik_work_claims_active" ).gauge();
        assertEquals( 4.0, g.value() );
        assertEquals( List.of(), g.getId().getTags() );
    }

    @Test
    void activeClaimsGaugeReadsZeroWhenTheQueryFails() throws SQLException {
        final WorkClaimStore claims = mock( WorkClaimStore.class );
        when( store.claims() ).thenReturn( claims );
        when( claims.activeClaimCount() ).thenThrow( new SQLException( "down" ) );
        metrics();
        assertEquals( 0.0, registry.find( "wikantik_work_claims_active" ).gauge().value() );
    }

    private double active() {
        return registry.find( "wikantik_work_claims_active" ).gauge().value();
    }

    /** WARN events of {@link WorkMetrics} while {@code action} runs. */
    private static List< org.apache.logging.log4j.core.LogEvent > warnings( final Runnable action ) {
        final List< org.apache.logging.log4j.core.LogEvent > out = new java.util.concurrent.CopyOnWriteArrayList<>();
        final String logger = WorkMetrics.class.getName();
        final var core = ( org.apache.logging.log4j.core.Logger ) org.apache.logging.log4j.LogManager.getLogger( logger );
        final var capture = new org.apache.logging.log4j.core.appender.AbstractAppender( "metrics-capture", null, null,
            true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY ) {
            @Override
            public void append( final org.apache.logging.log4j.core.LogEvent e ) {
                if ( e.getLevel() == org.apache.logging.log4j.Level.WARN ) {
                    out.add( e.toImmutable() );
                }
            }
        };
        capture.start();
        final org.apache.logging.log4j.Level before = core.getLevel();
        org.apache.logging.log4j.core.config.Configurator.setLevel( logger, org.apache.logging.log4j.Level.WARN );
        core.addAppender( capture );
        try {
            action.run();
        } finally {
            core.removeAppender( capture );
            org.apache.logging.log4j.core.config.Configurator.setLevel( logger, before );
            capture.stop();
        }
        return out;
    }

    @Test
    void theActiveClaimGaugeSharesTheFiveSecondSnapshot() throws SQLException {
        final AtomicInteger calls = new AtomicInteger();
        when( store.claims() ).thenReturn( claimStore );
        when( claimStore.activeClaimCount() ).thenAnswer( inv -> ( long ) calls.incrementAndGet() );
        metrics();
        assertEquals( 1.0, active() );
        assertEquals( 1.0, active(), "a second scrape within the TTL reads the snapshot" );
        nanos.addAndGet( TTL_NANOS + 1 );
        assertEquals( 2.0, active() );
        assertEquals( 2, calls.get() );
    }

    @Test
    void anOutageIsWarnedOnceWithItsStackNotOnEveryScrape() throws SQLException {
        when( store.claims() ).thenReturn( claimStore );
        when( claimStore.activeClaimCount() ).thenThrow( new SQLException( "down" ) );
        when( store.itemCounts() ).thenThrow( new SQLException( "down" ) );
        metrics();
        final List< org.apache.logging.log4j.core.LogEvent > warned = warnings( () -> {
            for ( int i = 0; i < 4; i++ ) {
                assertEquals( 0.0, active() );
                assertEquals( 0.0, gauge( "draft" ).value() );
                nanos.addAndGet( TTL_NANOS + 1 );
            }
        } );
        assertEquals( 2, warned.size(), "one WARN per failing read for the whole outage: " + warned );
        assertTrue( warned.stream().allMatch( e -> e.getThrown() != null ), "the first carries the stack" );
        // Recovery, then a new outage: warned again.
        org.mockito.Mockito.reset( claimStore );
        when( claimStore.activeClaimCount() ).thenReturn( 3L );
        assertEquals( 3.0, active() );
        nanos.addAndGet( TTL_NANOS + 1 );
        when( claimStore.activeClaimCount() ).thenThrow( new SQLException( "down again" ) );
        final List< org.apache.logging.log4j.core.LogEvent > again = warnings( () -> {
            nanos.addAndGet( TTL_NANOS + 1 );
            active();
            nanos.addAndGet( TTL_NANOS + 1 );
            active();
        } );
        assertEquals( 1, again.stream().filter( e -> e.getMessage().getFormattedMessage().contains( "claim" ) ).count(),
            again.toString() );
    }
}

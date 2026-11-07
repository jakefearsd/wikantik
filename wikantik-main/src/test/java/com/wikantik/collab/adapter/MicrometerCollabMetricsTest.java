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
package com.wikantik.collab.adapter;

import com.wikantik.collab.CollabConfig;
import com.wikantik.collab.CollabMetrics;
import com.wikantik.collab.CollabPermissions;
import com.wikantik.collab.CollabSessionRegistry;
import com.wikantik.collab.model.JoinRequest;
import com.wikantik.collab.testing.InMemoryCollabStore;
import com.wikantik.collab.testing.MutableClock;
import com.wikantik.collab.testing.ScriptedPageWriter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Phase 3 Task 5: {@link MicrometerCollabMetrics} registers the section 12.3 meters under their Prometheus names. */
class MicrometerCollabMetricsTest {

    @Test
    void metersScrapeUnderTheSpecNames() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final MicrometerCollabMetrics m = new MicrometerCollabMetrics( meters );
        m.push( CollabMetrics.ACCEPTED );
        m.push( CollabMetrics.ACCEPTED );
        m.push( CollabMetrics.REBASE );
        m.push( CollabMetrics.REJECTED );
        m.pushLatency( TimeUnit.MILLISECONDS.toNanos( 5 ) );
        m.publish( CollabMetrics.PUBLISHED );
        m.publish( CollabMetrics.STORE );
        m.externalMerge();
        m.resync();
        m.resync();

        assertEquals( 2.0, meters.get( "wikantik_collab_push_total" ).tag( "result", "accepted" ).counter().count() );
        assertEquals( 1.0, meters.get( "wikantik_collab_push_total" ).tag( "result", "rebase" ).counter().count() );
        assertEquals( 1.0, meters.get( "wikantik_collab_push_total" ).tag( "result", "rejected" ).counter().count() );
        final Timer latency = meters.get( "wikantik_collab_push_latency_seconds" ).timer();
        assertEquals( 1, latency.count() );
        assertEquals( 5.0, latency.totalTime( TimeUnit.MILLISECONDS ), 0.001 );
        assertEquals( "seconds", latency.getId().getBaseUnit() );
        assertEquals( 1.0, meters.get( "wikantik_collab_publish_total" ).tag( "result", "published" ).counter().count() );
        assertEquals( 1.0, meters.get( "wikantik_collab_publish_total" ).tag( "result", "store" ).counter().count() );
        assertEquals( 1.0, meters.get( "wikantik_collab_external_merges_total" ).counter().count() );
        assertEquals( 2.0, meters.get( "wikantik_collab_resync_total" ).counter().count() );

        final Set< String > names = meters.getMeters().stream().map( Meter::getId ).map( Meter.Id::getName )
            .collect( Collectors.toCollection( TreeSet::new ) );
        assertEquals( new TreeSet<>( Set.of( "wikantik_collab_push_total", "wikantik_collab_push_latency_seconds",
            "wikantik_collab_publish_total", "wikantik_collab_external_merges_total", "wikantik_collab_resync_total" ) ),
            names );
    }

    @Test
    void gaugesReadTheRegistry() {
        final MutableClock clock = new MutableClock( Instant.parse( "2026-01-01T00:00:00Z" ) );
        final ScriptedPageWriter writer = new ScriptedPageWriter();
        writer.seed( "A", "a\n", Map.of() );
        writer.seed( "B", "b\n", Map.of() );
        final CollabPermissions everyone = new CollabPermissions() {
            @Override
            public boolean canEdit( final Object participantSession, final String pageName ) {
                return true;
            }

            @Override
            public boolean canCreate( final Object participantSession, final String pageName ) {
                return true;
            }
        };
        final CollabSessionRegistry registry = new CollabSessionRegistry( new InMemoryCollabStore( clock ), writer,
            everyone, key -> null, clock, new CollabConfig( true, 20, 65536, 25, 45, 30, 100, 500, 10, 0, 1000 ) );
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        new MicrometerCollabMetrics( meters ).bindGauges( registry );

        assertEquals( 0.0, meters.get( "wikantik_collab_sessions_active" ).gauge().value() );
        assertEquals( 0.0, meters.get( "wikantik_collab_participants_active" ).gauge().value() );
        assertInstanceOf( CollabSessionRegistry.Joined.class,
            registry.join( "A", JoinRequest.of( "alice", "alice", null ) ) );
        assertInstanceOf( CollabSessionRegistry.Joined.class, registry.join( "A", JoinRequest.of( "bob", "bob", null ) ) );
        assertInstanceOf( CollabSessionRegistry.Joined.class,
            registry.join( "B", JoinRequest.of( "carol", "carol", null ) ) );

        assertEquals( 2.0, meters.get( "wikantik_collab_sessions_active" ).gauge().value() );
        assertEquals( 3.0, meters.get( "wikantik_collab_participants_active" ).gauge().value() );
    }
}

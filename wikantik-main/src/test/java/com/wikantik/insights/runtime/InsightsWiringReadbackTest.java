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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InsightsWiringReadbackTest {

    private static boolean threadAlive() {
        return Thread.getAllStackTraces().keySet().stream()
            .anyMatch( t -> t.getName().equals( ReaderAnalyticsScheduler.THREAD_NAME ) && t.isAlive() );
    }

    private static InsightsWiring.ReadbackEnv env() {
        return new InsightsWiring.ReadbackEnv( null, "Main", null, new SimpleMeterRegistry() );
    }

    @Test
    void notStartedWhenReadbackIsNotConfigured() {
        final InsightsBackground bg = new InsightsBackground();
        InsightsWiring.startReaderReadback( null, new Properties(), env(), bg );
        assertFalse( threadAlive() );
        assertTrue( bg.isEmpty() );
    }

    @Test
    void notStartedWhenWebsiteIdIsNotAUuid() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.analytics.website.id", "not-a-uuid" );
        p.setProperty( "wikantik.analytics.upstream", "http://umami" );
        p.setProperty( "wikantik.analytics.readback.url", "jdbc:postgresql://h/db" );
        final InsightsBackground bg = new InsightsBackground();
        InsightsWiring.startReaderReadback( null, p, env(), bg );
        assertFalse( threadAlive() );
        assertTrue( bg.isEmpty() );
    }

    @Test
    void startsOnceConfiguredAndCloseStopsIt() throws Exception {
        final Properties p = new Properties();
        p.setProperty( "wikantik.analytics.website.id", "6f1c2b1e-0000-4000-8000-000000000001" );
        p.setProperty( "wikantik.analytics.upstream", "http://umami" );
        p.setProperty( "wikantik.analytics.readback.url", "jdbc:postgresql://h/db" );
        final InsightsBackground bg = new InsightsBackground();
        InsightsWiring.startReaderReadback( new org.postgresql.ds.PGSimpleDataSource(), p, env(), bg );
        assertTrue( threadAlive() );
        assertDoesNotThrow( bg::close );
        final long deadline = System.currentTimeMillis() + 5000;
        while ( threadAlive() && System.currentTimeMillis() < deadline ) {
            Thread.sleep( 20 );
        }
        assertFalse( threadAlive() );
    }
}

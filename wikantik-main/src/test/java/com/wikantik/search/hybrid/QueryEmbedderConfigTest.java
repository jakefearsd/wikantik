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
package com.wikantik.search.hybrid;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryEmbedderConfigTest {

    @Test
    void defaultsProduceSaneValues() {
        final QueryEmbedderConfig c = QueryEmbedderConfig.defaults();
        assertEquals( 2000L, c.timeoutMs() );
        assertEquals( 600L, c.cacheTtlSeconds() );
        assertEquals( 1000L, c.cacheMaxEntries() );
        assertEquals( 20, c.breakerWindowSize() );
        assertEquals( 10, c.breakerMinCalls() );
        assertEquals( 0.5, c.breakerFailureRate() );
        assertEquals( 30_000L, c.breakerCooldownMs() );
    }

    @Test
    void fromPropertiesWithNullReturnsDefaults() {
        assertEquals( QueryEmbedderConfig.defaults(), QueryEmbedderConfig.fromProperties( null ) );
    }

    @Test
    void fromPropertiesOverridesEachKey() {
        final Properties p = new Properties();
        p.setProperty( QueryEmbedderConfig.PREFIX + "timeout-ms", "500" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "cache.ttl-seconds", "60" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "cache.max-entries", "42" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "breaker.window-size", "5" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "breaker.min-calls", "3" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "breaker.failure-rate", "0.8" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "breaker.cooldown-ms", "100" );

        final QueryEmbedderConfig c = QueryEmbedderConfig.fromProperties( p );
        assertEquals( 500L, c.timeoutMs() );
        assertEquals( 60L, c.cacheTtlSeconds() );
        assertEquals( 42L, c.cacheMaxEntries() );
        assertEquals( 5, c.breakerWindowSize() );
        assertEquals( 3, c.breakerMinCalls() );
        assertEquals( 0.8, c.breakerFailureRate() );
        assertEquals( 100L, c.breakerCooldownMs() );
    }

    @Test
    void fromPropertiesIgnoresBlankValues() {
        final Properties p = new Properties();
        p.setProperty( QueryEmbedderConfig.PREFIX + "timeout-ms", "" );
        p.setProperty( QueryEmbedderConfig.PREFIX + "cache.ttl-seconds", "   " );
        assertEquals( QueryEmbedderConfig.DEFAULT_TIMEOUT_MS, QueryEmbedderConfig.fromProperties( p ).timeoutMs() );
        assertEquals( QueryEmbedderConfig.DEFAULT_CACHE_TTL_SECONDS,
                QueryEmbedderConfig.fromProperties( p ).cacheTtlSeconds() );
    }

    @Test
    void fromPropertiesRejectsMalformedLong() {
        final Properties p = new Properties();
        p.setProperty( QueryEmbedderConfig.PREFIX + "timeout-ms", "fast" );
        assertThrows( IllegalArgumentException.class, () -> QueryEmbedderConfig.fromProperties( p ) );
    }

    @Test
    void fromPropertiesRejectsMalformedDouble() {
        final Properties p = new Properties();
        p.setProperty( QueryEmbedderConfig.PREFIX + "breaker.failure-rate", "half" );
        assertThrows( IllegalArgumentException.class, () -> QueryEmbedderConfig.fromProperties( p ) );
    }

    @Test
    void constructorValidatesEachField() {
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 0, 1, 1, 1, 1, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 0, 1, 1, 1, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 0, 1, 1, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 0, 1, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 5, 0, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 5, 6, 0.5, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 1, 1, 0.0, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 1, 1, 1.1, 0 ) );
        assertThrows( IllegalArgumentException.class,
                () -> new QueryEmbedderConfig( 1, 1, 1, 1, 1, 0.5, -1 ) );
    }
}

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
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbedConcurrencyConfigTest {

    @Test
    void defaultsAreAdaptiveFromFourBetweenOneAndThirtyTwo() {
        final EmbedConcurrencyConfig d = EmbedConcurrencyConfig.defaults();
        assertEquals( EmbedConcurrencyConfig.Mode.ADAPTIVE, d.mode() );
        assertEquals( 4, d.initial() );
        assertEquals( 1, d.min() );
        assertEquals( 32, d.max() );
        assertEquals( 4, d.fixedLimit() );
        assertEquals( EmbedConcurrencyConfig.DEFAULT_MIN_GAIN, d.minGain() );
    }

    @Test
    void rejectsInconsistentBounds() {
        final var m = EmbedConcurrencyConfig.Mode.ADAPTIVE;
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( null, 4, 4, 1, 32, 0.1 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 0, 4, 1, 32, 0.1 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 4, 0, 32, 0.1 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 4, 5, 32, 0.1 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 40, 1, 32, 0.1 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 4, 1, 32, 0.0 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 4, 1, 32, 1.5 ) );
        assertThrows( IllegalArgumentException.class, () -> new EmbedConcurrencyConfig( m, 4, 4, 1, 32, Double.NaN ) );
    }

    @Test
    void anUnknownModeIsRefusedWithTheKeyName() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.search.hybrid.embedder.concurrency.mode", "turbo" );
        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
                () -> EmbedConcurrencyConfig.fromProperties( p ) );
        assertTrue( e.getMessage().contains( "concurrency.mode" ), e.getMessage() );
    }

    @Test
    void eachModeBuildsItsOwnLimiter() {
        final EmbedConcurrencyLimiter adaptive = EmbedConcurrencyConfig.defaults().newLimiter( System::nanoTime );
        assertEquals( "adaptive", adaptive.mode() );
        assertEquals( 4, adaptive.limit() );
        final EmbedConcurrencyLimiter fixed = EmbedConcurrencyConfig.fixed( 7 ).newLimiter( System::nanoTime );
        assertEquals( "fixed", fixed.mode() );
        assertEquals( 7, fixed.limit() );
    }
}

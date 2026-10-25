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
package com.wikantik.plantuml;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeCacheTest {

    private static OutcomeCache.Entry drawn( final int chars ) {
        final String svg = "x".repeat( chars );
        return new OutcomeCache.Entry( RenderOutcome.drawn( new PlantUmlDrawing( svg, svg ) ), 0L );
    }

    @Test
    void weightCountsBothVariantsMessageAndOverhead() {
        assertEquals( OutcomeCache.ENTRY_OVERHEAD_BYTES + 20, OutcomeCache.weight( drawn( 10 ).outcome() ) );
        assertEquals( OutcomeCache.ENTRY_OVERHEAD_BYTES + 3, OutcomeCache.weight( RenderOutcome.refused( "syntax-error", "bad", 1 ) ) );
    }

    @Test
    void evictsLeastRecentlyUsedUntilUnderTheByteBound() {
        final long one = OutcomeCache.weight( drawn( 1_000 ).outcome() );
        final OutcomeCache c = new OutcomeCache( 100, 2 * one );
        c.put( "a", drawn( 1_000 ) );
        c.put( "b", drawn( 1_000 ) );
        assertNotNull( c.get( "a" ) ); // a is now the most recently used
        c.put( "c", drawn( 1_000 ) );
        assertTrue( c.get( "b" ) == null, "b is cached" );
        assertNotNull( c.get( "a" ) );
        assertNotNull( c.get( "c" ) );
        assertEquals( 2 * one, c.bytes() );
    }

    @Test
    void replacingAnEntryReleasesItsBytes() {
        final OutcomeCache c = new OutcomeCache( 100, 1_000_000L );
        c.put( "a", drawn( 1_000 ) );
        c.put( "a", drawn( 10 ) );
        assertEquals( 1, c.size() );
        assertEquals( OutcomeCache.weight( drawn( 10 ).outcome() ), c.bytes() );
    }

    @Test
    void anOutcomeLargerThanTheBoundIsNotCachedAndEvictsNothing() {
        final OutcomeCache c = new OutcomeCache( 100, 5_000L );
        c.put( "small", drawn( 10 ) );
        c.put( "huge", drawn( 10_000 ) );
        assertTrue( c.get( "huge" ) == null, "huge is cached" );
        assertNotNull( c.get( "small" ) );
    }

    @Test
    void entryCountBoundStillApplies() {
        final OutcomeCache c = new OutcomeCache( 1, 1_000_000L );
        c.put( "a", drawn( 1 ) );
        c.put( "b", drawn( 1 ) );
        assertTrue( c.get( "a" ) == null, "a is cached" );
        assertEquals( 1, c.size() );
        assertEquals( OutcomeCache.weight( drawn( 1 ).outcome() ), c.bytes() );
    }

    @Test
    void expiry() {
        assertFalse( new OutcomeCache.Entry( RenderOutcome.refused( "too-slow", null, 0 ), 0L ).expired( Long.MAX_VALUE ) );
        final OutcomeCache.Entry e = new OutcomeCache.Entry( RenderOutcome.refused( "too-slow", null, 0 ), 100L );
        assertFalse( e.expired( 99L ) );
        assertTrue( e.expired( 100L ) );
    }
}

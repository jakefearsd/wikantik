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
package com.wikantik.api.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class RecentChangeTest {

    @Test
    void retainsAllFields() {
        final Instant when = Instant.parse( "2026-01-02T03:04:05Z" );
        final RecentChange c = new RecentChange( 7, when, "alice", "tidy up" );
        assertEquals( 7, c.version() );
        assertEquals( when, c.at() );
        assertEquals( "alice", c.author() );
        assertEquals( "tidy up", c.summary() );
    }

    @Test
    void allowsNullSummary() {
        final RecentChange c = new RecentChange( 1, Instant.EPOCH, "bob", null );
        assertNull( c.summary() );
    }

    @ParameterizedTest
    @ValueSource( ints = { 1, 2, 1000 } )
    void acceptsVersionAtOrAboveOne( final int version ) {
        assertEquals( version, new RecentChange( version, Instant.EPOCH, "a", null ).version() );
    }

    @ParameterizedTest
    @ValueSource( ints = { 0, -1, Integer.MIN_VALUE } )
    void rejectsVersionBelowOne( final int version ) {
        assertThrows( IllegalArgumentException.class,
            () -> new RecentChange( version, Instant.EPOCH, "a", null ) );
    }
}

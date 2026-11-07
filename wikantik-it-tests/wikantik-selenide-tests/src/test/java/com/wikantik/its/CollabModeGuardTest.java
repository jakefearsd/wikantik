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
package com.wikantik.its;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollabModeGuardTest {

    @Test
    void aFallbackToTheSingleAuthorEditorFailsTheTest() {
        final AssertionError e = assertThrows( AssertionError.class,
            () -> CollabMode.awaitReady( true, () -> null, Duration.ofMillis( 300 ) ) );
        assertTrue( e.getMessage().contains( "editor fell back to single-author mode" ), e.getMessage() );
    }

    @Test
    void anEditorStuckBeforeLiveFailsTheTest() {
        final AssertionError e = assertThrows( AssertionError.class,
            () -> CollabMode.awaitReady( true, () -> "connecting", Duration.ofMillis( 300 ) ) );
        assertTrue( e.getMessage().contains( "connecting" ), e.getMessage() );
    }

    @Test
    void aLateLiveStatusPasses() {
        final AtomicInteger polls = new AtomicInteger();
        assertDoesNotThrow( () -> CollabMode.awaitReady( true,
            () -> polls.incrementAndGet() < 3 ? "connecting" : "live", Duration.ofSeconds( 5 ) ) );
    }

    @Test
    void singleAuthorModeDoesNotWait() {
        final AtomicInteger polls = new AtomicInteger();
        assertDoesNotThrow( () -> CollabMode.awaitReady( false, () -> { polls.incrementAndGet(); return null; },
            Duration.ofMillis( 300 ) ) );
        assertEquals( 0, polls.get() );
    }
}

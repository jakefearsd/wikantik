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
package com.wikantik.knowledge.structure;

import com.wikantik.api.structure.Confidence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ConfidenceComputerTest {

    private static final Instant NOW = Instant.parse( "2026-04-25T12:00:00Z" );
    private static final Set< String > TRUSTED = Set.of( "alice" );
    private static final ConfidenceComputer COMPUTER =
            new ConfidenceComputer( TRUSTED::contains );

    @Test
    void explicit_override_wins() {
        final Confidence result = COMPUTER.compute(
                NOW.minus( Duration.ofDays( 1 ) ), "alice",
                Optional.of( Confidence.STALE ), NOW );
        assertEquals( Confidence.STALE, result );
    }

    @Test
    void null_verified_at_yields_stale() {
        assertEquals( Confidence.STALE,
                COMPUTER.compute( null, "alice", Optional.empty(), NOW ) );
    }

    @Test
    void over_ninety_days_yields_stale() {
        assertEquals( Confidence.STALE,
                COMPUTER.compute( NOW.minus( Duration.ofDays( 91 ) ), "alice",
                        Optional.empty(), NOW ) );
    }

    @Test
    void recent_trusted_author_yields_authoritative() {
        assertEquals( Confidence.AUTHORITATIVE,
                COMPUTER.compute( NOW.minus( Duration.ofDays( 5 ) ), "alice",
                        Optional.empty(), NOW ) );
    }

    @Test
    void recent_unknown_author_yields_provisional() {
        assertEquals( Confidence.PROVISIONAL,
                COMPUTER.compute( NOW.minus( Duration.ofDays( 5 ) ), "bob",
                        Optional.empty(), NOW ) );
    }

    @Test
    void exactly_at_threshold_is_not_stale() {
        // Boundary: exactly staleDays old → not stale.
        assertEquals( Confidence.AUTHORITATIVE,
                COMPUTER.compute( NOW.minus( Duration.ofDays( 90 ) ), "alice",
                        Optional.empty(), NOW ) );
    }

    @Test
    void custom_stale_window_is_respected() {
        final var tight = new ConfidenceComputer( TRUSTED::contains, 7 );
        assertEquals( Confidence.STALE,
                tight.compute( NOW.minus( Duration.ofDays( 8 ) ), "alice",
                        Optional.empty(), NOW ) );
        assertEquals( Confidence.AUTHORITATIVE,
                tight.compute( NOW.minus( Duration.ofDays( 6 ) ), "alice",
                        Optional.empty(), NOW ) );
    }

    @Test
    void null_verifier_is_provisional_at_recent_timestamp() {
        assertEquals( Confidence.PROVISIONAL,
                COMPUTER.compute( NOW.minus( Duration.ofDays( 1 ) ), null,
                        Optional.empty(), NOW ) );
    }
}

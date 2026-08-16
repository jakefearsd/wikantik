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
package com.wikantik.api.querylog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryLogContractTest {

    /** Minimal implementation that only overrides the legacy 4-arg method, to prove the new
     *  6-arg {@code log} default delegates to it (dropping coverage/sessionHash) rather than
     *  requiring every existing implementer to add an override. */
    private static final class LegacyOnlyService implements QueryLogService {
        final List< Object > calls = new ArrayList<>();

        @Override
        public void log( final String query, final ActorType actor, final SourceSurface surface,
                         final Integer resultCount ) {
            calls.add( List.of( query, actor, surface, resultCount == null ? "null" : resultCount ) );
        }
    }

    @Test
    void sixArgLogDefault_delegatesToFourArgForm_droppingCoverageAndSessionHash() {
        final LegacyOnlyService svc = new LegacyOnlyService();

        svc.log( "deploy", ActorType.HUMAN, SourceSurface.API_BUNDLE, 3, "weak", "abc123" );

        assertEquals( 1, svc.calls.size(), "the default must delegate exactly once, not drop the call" );
        assertEquals( List.of( "deploy", ActorType.HUMAN, SourceSurface.API_BUNDLE, 3 ), svc.calls.get( 0 ) );
    }

    @Test
    void recordClickDefault_isNoOp_neverThrows() {
        final LegacyOnlyService svc = new LegacyOnlyService();
        assertDoesNotThrow( () -> svc.recordClick( "abc123", "deploy", 2 ) );
        assertTrue( svc.calls.isEmpty(), "the no-op default must not also log a row" );
    }

    @Test
    void actorType_wireRoundTrips() {
        for ( final ActorType a : ActorType.values() ) {
            assertEquals( a, ActorType.fromWire( a.wire() ), a.name() );
        }
        assertEquals( "human", ActorType.HUMAN.wire() );
        assertEquals( "agent", ActorType.AGENT.wire() );
    }

    @Test
    void actorType_unknownWireFallsBackToUnknown() {
        assertEquals( ActorType.UNKNOWN, ActorType.fromWire( "garbage" ) );
        assertEquals( ActorType.UNKNOWN, ActorType.fromWire( null ) );
    }

    @Test
    void sourceSurface_wireRoundTrips() {
        for ( final SourceSurface s : SourceSurface.values() ) {
            assertEquals( s, SourceSurface.fromWire( s.wire() ), s.name() );
        }
        assertEquals( "api_bundle", SourceSurface.API_BUNDLE.wire() );
        assertEquals( "mcp_assemble_bundle", SourceSurface.MCP_ASSEMBLE_BUNDLE.wire() );
    }
}

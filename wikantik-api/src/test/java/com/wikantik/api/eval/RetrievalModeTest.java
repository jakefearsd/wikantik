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
package com.wikantik.api.eval;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalModeTest {

    @Test
    void wireNamesAreLowercaseUnderscore() {
        assertEquals( "bm25",         RetrievalMode.BM25.wireName() );
        assertEquals( "hybrid",       RetrievalMode.HYBRID.wireName() );
        assertEquals( "hybrid_graph", RetrievalMode.HYBRID_GRAPH.wireName() );
    }

    @Test
    void fromWireRoundTripsEveryEnumValue() {
        for ( final RetrievalMode m : RetrievalMode.values() ) {
            assertEquals( m, RetrievalMode.fromWire( m.wireName() ).orElseThrow() );
        }
    }

    @Test
    void fromWireIsCaseInsensitiveAndTrimmed() {
        assertEquals( RetrievalMode.HYBRID, RetrievalMode.fromWire( "  Hybrid  " ).orElseThrow() );
    }

    @Test
    void fromWireReturnsEmptyOnUnknown() {
        assertTrue( RetrievalMode.fromWire( "dense" ).isEmpty() );
        assertTrue( RetrievalMode.fromWire( "" ).isEmpty() );
        assertTrue( RetrievalMode.fromWire( null ).isEmpty() );
    }

    @Test
    void retrievalRunResultRequiresQuerySetMode() {
        final Instant now = Instant.now();
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalRunResult( 0L, " ", RetrievalMode.BM25,
                null, null, null, null, now, null, 0, 0, false ) );
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalRunResult( 0L, "set", null,
                null, null, null, null, now, null, 0, 0, false ) );
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalRunResult( 0L, "set", RetrievalMode.BM25,
                null, null, null, null, null, null, 0, 0, false ) );
    }

    @Test
    void retrievalRunResultAcceptsNullMetricsAndFinishedAt() {
        final Instant now = Instant.now();
        final RetrievalRunResult r = new RetrievalRunResult(
            42L, "core", RetrievalMode.HYBRID,
            null, null, null, null, now, null, 0, 4, true );
        assertEquals( 42L, r.runId() );
        assertEquals( "core", r.querySetId() );
        assertEquals( RetrievalMode.HYBRID, r.mode() );
        assertTrue( r.degraded() );
        assertEquals( 0, r.queriesEvaluated() );
        assertEquals( 4, r.queriesSkipped() );
    }

    @Test
    void retrievalRunResultRejectsNegativeCounts() {
        final Instant now = Instant.now();
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalRunResult( 0L, "set", RetrievalMode.BM25,
                0.5, 0.5, 0.5, 0.5, now, now, -1, 0, false ) );
        assertThrows( IllegalArgumentException.class,
            () -> new RetrievalRunResult( 0L, "set", RetrievalMode.BM25,
                0.5, 0.5, 0.5, 0.5, now, now, 0, -1, false ) );
    }

    @Test
    void retrievalRunResultPopulated() {
        final Instant start = Instant.parse( "2026-04-25T03:00:00Z" );
        final Instant end   = Instant.parse( "2026-04-25T03:01:00Z" );
        final RetrievalRunResult r = new RetrievalRunResult(
            7L, "core-agent-queries", RetrievalMode.HYBRID_GRAPH,
            0.85, 0.83, 0.92, 0.74, start, end, 16, 0, false );
        assertEquals( 0.85, r.ndcgAt5() );
        assertEquals( 0.83, r.ndcgAt10() );
        assertEquals( 0.92, r.recallAt20() );
        assertEquals( 0.74, r.mrr() );
        assertEquals( end, r.finishedAt() );
        assertFalse( r.degraded() );
    }
}

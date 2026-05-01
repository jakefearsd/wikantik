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
package com.wikantik.extractcli;

import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.State;
import com.wikantik.knowledge.extraction.BootstrapEntityExtractionIndexer.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the {@link BootstrapExtractionCli#reportJson} hot
 * path. Without a custom adapter, Gson's reflective serializer cannot reach
 * {@link Instant}'s private fields under JDK17+ ("module java.base does not
 * 'opens java.time' to unnamed module") and crashes the whole report write —
 * which is exactly how the 2026-05-01 smoke run lost its quality artifact.
 * The adapter renders {@link Instant} as ISO-8601.
 */
class BootstrapExtractionCliReportJsonTest {

    @Test
    void serializesInstantsAsIso8601() {
        final Instant started  = Instant.parse( "2026-05-01T11:00:00Z" );
        final Instant finished = Instant.parse( "2026-05-01T11:19:49Z" );
        final Status s = new Status(
            State.COMPLETED, 50, 50, 0, 600, 600, 0, 142, 0,
            started, finished, 1189200L, null, false, 2, 0, Map.of(), 0,
            403, 403, 0, 0, 0, 0, Map.of()
        );

        final String json = BootstrapExtractionCli.reportJson( s );

        assertTrue( json.contains( "\"startedAt\": \"2026-05-01T11:00:00Z\"" ),
            "startedAt should serialize as ISO-8601: " + json );
        assertTrue( json.contains( "\"finishedAt\": \"2026-05-01T11:19:49Z\"" ),
            "finishedAt should serialize as ISO-8601: " + json );
        // Sanity-check a counter survives intact.
        assertTrue( json.contains( "\"consolidatedCandidates\": 403" ),
            "consolidatedCandidates field missing: " + json );
        // Make sure we didn't accidentally end up with the reflective {seconds:..,nanos:..}
        // shape that crashed the original code path.
        assertFalse( json.contains( "\"seconds\":" ), "Instant should not be reflected: " + json );
    }

    @Test
    void handlesNullInstants() {
        // Status objects mid-run have null finishedAt — must not NPE.
        final Status s = new Status(
            State.RUNNING, 10, 5, 0, 100, 50, 0, 7, 0,
            Instant.parse( "2026-05-01T11:00:00Z" ), null, 60_000L, null, false, 2, 0, Map.of(), 0,
            12, 12, 0, 0, 0, 0, Map.of()
        );

        final String json = BootstrapExtractionCli.reportJson( s );

        assertTrue( json.contains( "\"finishedAt\": null" ),
            "null Instant should round-trip as JSON null: " + json );
    }

    @Test
    void writeReportSucceedsWithInstants( @org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp ) throws Exception {
        final java.nio.file.Path out = tmp.resolve( "report.json" );
        final Status s = new Status(
            State.COMPLETED, 1, 1, 0, 1, 1, 0, 0, 0,
            Instant.now(), Instant.now(), 100L, null, false, 2, 0, Map.of(), 0,
            0, 0, 0, 0, 0, 0, Map.of()
        );

        BootstrapExtractionCli.writeReport( out.toString(), s );

        final String json = java.nio.file.Files.readString( out );
        assertTrue( json.contains( "\"state\": \"COMPLETED\"" ), json );
    }
}

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
package com.wikantik.insights;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ExpectedCtrCurveParser} (content-intelligence design §7.3 rule 2). Like
 * {@link SnapshotPayloadParserTest} and {@link ImportedOpportunityParserTest}, every case here
 * exists because this is a public-adjacent write surface's first line of defense -- it must never
 * throw.
 */
class ExpectedCtrCurveParserTest {

    private static String payload( final String expectedCtrJson ) {
        return """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "expected_ctr": %s }""".formatted( expectedCtrJson );
    }

    private static final String FULL_TABLE = """
        {"1":0.28,"2":0.15,"3":0.11,"4":0.08,"5":0.06,
         "6":0.05,"7":0.04,"8":0.032,"9":0.028,"10":0.025}""";

    // -- Happy path -----------------------------------------------------------------------------

    @Test
    void parsesTheFullTenPointTable() {
        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( payload( FULL_TABLE ) );

        assertTrue( r.present() );
        assertEquals( 0, r.rejected() );
        assertEquals( LocalDate.parse( "2026-08-14" ), r.asOf() );
        assertEquals( 10, r.points().size() );
        assertEquals( 0.28, r.points().get( 1 ), 0.0001 );
        assertEquals( 0.11, r.points().get( 3 ), 0.0001 );
        assertEquals( 0.025, r.points().get( 10 ), 0.0001 );
    }

    @Test
    void asOfIsTakenFromTheTopLevelSnapshotDate() {
        final ExpectedCtrCurveParser.ParseResult r =
                ExpectedCtrCurveParser.parse( payload( "{\"1\":0.3}" ) );

        assertEquals( LocalDate.parse( "2026-08-14" ), r.asOf() );
    }

    // -- Absent key = no-op ------------------------------------------------------------------------

    @Test
    void missingExpectedCtrKeyIsAbsentNotRejected() {
        final String json = """
            { "engine": "google", "site": "wiki.wikantik.com", "snapshot_date": "2026-08-14" }""";

        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( json );

        assertFalse( r.present() );
        assertEquals( 0, r.rejected() );
        assertTrue( r.points().isEmpty() );
        assertNull( r.asOf() );
    }

    @Test
    void nullExpectedCtrKeyIsAbsentNotRejected() {
        final String json = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "expected_ctr": null }""";

        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( json );

        assertFalse( r.present() );
        assertEquals( 0, r.rejected() );
    }

    // -- Individual-entry rejection, not whole-payload rejection --------------------------------

    @Test
    void nonIntegerPositionKeyIsRejectedIndividually() {
        final ExpectedCtrCurveParser.ParseResult r =
                ExpectedCtrCurveParser.parse( payload( "{\"1\":0.28,\"not-a-position\":0.5}" ) );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertEquals( 1, r.points().size() );
        assertEquals( 0.28, r.points().get( 1 ), 0.0001 );
    }

    @Test
    void nonNumericValueIsRejectedIndividually() {
        final ExpectedCtrCurveParser.ParseResult r =
                ExpectedCtrCurveParser.parse( payload( "{\"1\":0.28,\"2\":\"high\"}" ) );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertEquals( 1, r.points().size() );
        assertEquals( 0.28, r.points().get( 1 ), 0.0001 );
    }

    @Test
    void booleanValueIsRejectedAsNonNumeric() {
        final ExpectedCtrCurveParser.ParseResult r =
                ExpectedCtrCurveParser.parse( payload( "{\"1\":true}" ) );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.points().isEmpty() );
    }

    @Test
    void multipleBadEntriesEachCountTowardRejectedButGoodOnesSurvive() {
        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse(
                payload( "{\"1\":0.28,\"bad-key\":0.5,\"3\":\"nope\",\"4\":0.08}" ) );

        assertEquals( 2, r.rejected() );
        assertEquals( 2, r.points().size() );
        assertEquals( 0.28, r.points().get( 1 ), 0.0001 );
        assertEquals( 0.08, r.points().get( 4 ), 0.0001 );
    }

    // -- Whole-key rejection (not an object / no snapshot_date) ----------------------------------

    @Test
    void expectedCtrAsAJsonArrayIsRejectedAsAWhole() {
        final ExpectedCtrCurveParser.ParseResult r =
                ExpectedCtrCurveParser.parse( payload( "[1,2,3]" ) );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.points().isEmpty() );
        assertNull( r.asOf() );
    }

    @Test
    void missingSnapshotDateIsRejectedAsAWhole() {
        final String json = """
            { "engine": "google", "site": "wiki.wikantik.com", "expected_ctr": %s }"""
                .formatted( FULL_TABLE );

        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( json );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.points().isEmpty() );
        assertNull( r.asOf() );
    }

    @Test
    void unparseableSnapshotDateIsRejectedAsAWhole() {
        final String json = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "not-a-date", "expected_ctr": %s }""".formatted( FULL_TABLE );

        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( json );

        assertTrue( r.present() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.points().isEmpty() );
    }

    // -- Malformed payload -------------------------------------------------------------------------

    @Test
    void malformedJsonNeverThrows() {
        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( "not json at all" );

        assertFalse( r.present() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.points().isEmpty() );
    }

    @Test
    void emptyExpectedCtrObjectYieldsNoPointsAndNoRejections() {
        final ExpectedCtrCurveParser.ParseResult r = ExpectedCtrCurveParser.parse( payload( "{}" ) );

        assertTrue( r.present() );
        assertEquals( 0, r.rejected() );
        assertTrue( r.points().isEmpty() );
        assertEquals( LocalDate.parse( "2026-08-14" ), r.asOf() );
    }
}

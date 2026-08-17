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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ImportedOpportunityParser} (content-intelligence design §7.3 "Imported
 * from jakemon", §12.1 item J3). Like {@link SnapshotPayloadParserTest}, every case here exists
 * because this is a public-adjacent write surface's first line of defense -- it must never throw.
 */
class ImportedOpportunityParserTest {

    private static final Set< String > ENGINES = Set.of( "google", "bing", "yandex" );
    private static final Set< String > SITES   = Set.of( "wiki.wikantik.com" );

    private static String payload( final String opportunitiesJson ) {
        return """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "opportunities": [ %s ] }""".formatted( opportunitiesJson );
    }

    private static final String STRIKING_DISTANCE = """
        { "type": "striking_distance", "query": "low latency queue", "target_page": "",
          "target_pages": [], "expected_uplift": 12.3, "confidence": 0.6,
          "engine": "google", "site": "wiki.wikantik.com",
          "impressions": 240, "position": 14.2 }""";

    // -- Happy path -----------------------------------------------------------------------------

    @Test
    void parsesAValidRowForEachOfTheFiveDetectorTypes() {
        final String opportunities = String.join( ",",
                row( "striking_distance", "q1", "", 1.0 ),
                row( "ctr_gap", "q2", "", 2.0 ),
                row( "content_gap", "q3", "", 3.0 ),
                row( "cannibalization", "q4", "", 4.0 ),
                row( "decay", "q5", "", 5.0 ) );

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunities ), ENGINES, SITES );

        assertEquals( 0, r.rejected() );
        assertEquals( 5, r.rows().size() );
        assertTrue( r.present() );
        assertEquals( Set.of( "striking_distance", "ctr_gap", "content_gap", "cannibalization", "decay" ),
                r.rows().stream().map( ImportedOpportunityRow::opportunityType )
                        .collect( java.util.stream.Collectors.toSet() ) );
    }

    @Test
    void parsedRowCarriesAsOfEngineSiteAndUplift() {
        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( STRIKING_DISTANCE ), ENGINES, SITES );

        assertEquals( 1, r.rows().size() );
        final ImportedOpportunityRow row = r.rows().get( 0 );
        assertEquals( LocalDate.parse( "2026-08-14" ), row.asOf() );
        assertEquals( "google", row.engine() );
        assertEquals( "wiki.wikantik.com", row.siteHost() );
        assertEquals( "striking_distance", row.opportunityType() );
        assertEquals( "low latency queue", row.target() );
        assertEquals( 12.3, row.expectedUplift(), 0.0001 );
        assertEquals( 0.6, row.confidence(), 0.0001 );
    }

    @Test
    void confidenceIsNullWhenAbsent() {
        final String opportunity = """
            { "type": "content_gap", "query": "graph databases", "target_page": "",
              "expected_uplift": 5.0, "engine": "google", "site": "wiki.wikantik.com" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunity ), ENGINES, SITES );

        assertEquals( 1, r.rows().size() );
        assertNull( r.rows().get( 0 ).confidence() );
    }

    // -- query-then-target_page fallback ----------------------------------------------------------

    @Test
    void targetIsQueryWhenQueryIsNonBlank() {
        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( STRIKING_DISTANCE ), ENGINES, SITES );
        assertEquals( "low latency queue", r.rows().get( 0 ).target() );
    }

    @Test
    void targetFallsBackToTargetPageWhenQueryIsBlank() {
        final String opportunity = """
            { "type": "cannibalization", "query": "", "target_page": "/wiki/PhilosophyHub",
              "expected_uplift": 3.0, "engine": "google", "site": "wiki.wikantik.com" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunity ), ENGINES, SITES );

        assertEquals( 1, r.rows().size() );
        assertEquals( "/wiki/PhilosophyHub", r.rows().get( 0 ).target() );
    }

    @Test
    void targetFallsBackToTargetPageWhenQueryIsAbsent() {
        final String opportunity = """
            { "type": "decay", "target_page": "/wiki/A",
              "expected_uplift": 3.0, "engine": "google", "site": "wiki.wikantik.com" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunity ), ENGINES, SITES );

        assertEquals( 1, r.rows().size() );
        assertEquals( "/wiki/A", r.rows().get( 0 ).target() );
    }

    // -- Rejections, each with its own reason ------------------------------------------------------

    @Test
    void blankTypeIsRejected() {
        final String opportunity = """
            { "type": "", "query": "q", "expected_uplift": 1.0,
              "engine": "google", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void unknownTypeIsRejected() {
        final String opportunity = """
            { "type": "made_up_detector", "query": "q", "expected_uplift": 1.0,
              "engine": "google", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void blankEngineIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": 1.0,
              "engine": "", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void blankSiteIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": 1.0,
              "engine": "google", "site": "" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void disallowedEngineIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": 1.0,
              "engine": "altavista", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void disallowedSiteIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": 1.0,
              "engine": "google", "site": "not-allowed.example.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void missingExpectedUpliftIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q",
              "engine": "google", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void nonNumericExpectedUpliftIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": "a lot",
              "engine": "google", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    @Test
    void neitherQueryNorTargetPageIsRejected() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "", "target_page": "", "expected_uplift": 1.0,
              "engine": "google", "site": "wiki.wikantik.com" }""";
        assertRejectsExactlyOne( opportunity );
    }

    private static void assertRejectsExactlyOne( final String opportunityJson ) {
        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunityJson ), ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.present() );
    }

    @Test
    void oneBadRowAmongGoodRowsIsRejectedWithoutLosingTheOthers() {
        final String opportunities = String.join( ",",
                row( "ctr_gap", "good query one", "", 1.0 ),
                """
                { "type": "not_a_real_type", "query": "bad", "expected_uplift": 1.0,
                  "engine": "google", "site": "wiki.wikantik.com" }""",
                row( "decay", "good query two", "", 2.0 ) );

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunities ), ENGINES, SITES );

        assertEquals( 2, r.rows().size() );
        assertEquals( 1, r.rejected() );
    }

    // -- Evidence capture -----------------------------------------------------------------------

    @Test
    void evidenceCapturesUnknownKeysButNotConsumedFields() {
        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( STRIKING_DISTANCE ), ENGINES, SITES );

        final String evidenceJson = r.rows().get( 0 ).evidenceJson();
        final JsonObject evidence = JsonParser.parseString( evidenceJson ).getAsJsonObject();

        assertTrue( evidence.has( "target_pages" ), "detector-specific extras must be captured" );
        assertTrue( evidence.has( "impressions" ) );
        assertTrue( evidence.has( "position" ) );

        assertFalse( evidence.has( "type" ), "type is a typed field, not evidence" );
        assertFalse( evidence.has( "query" ), "query became `target`, not evidence" );
        assertFalse( evidence.has( "target_page" ), "target_page became `target`, not evidence" );
        assertFalse( evidence.has( "engine" ), "engine is a typed column, re-added on read" );
        assertFalse( evidence.has( "site" ), "site is a typed column (site_host)" );
        assertFalse( evidence.has( "expected_uplift" ), "expected_uplift is a typed column" );
        assertFalse( evidence.has( "confidence" ), "confidence is a typed column, re-added on read" );
    }

    @Test
    void evidenceIsAnEmptyObjectWhenNoExtraKeysArePresent() {
        final String opportunity = """
            { "type": "content_gap", "query": "graph databases",
              "expected_uplift": 5.0, "engine": "google", "site": "wiki.wikantik.com" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunity ), ENGINES, SITES );

        final JsonObject evidence =
                JsonParser.parseString( r.rows().get( 0 ).evidenceJson() ).getAsJsonObject();
        assertEquals( 0, evidence.size() );
    }

    // -- present flag / absent key -----------------------------------------------------------------

    @Test
    void absentOpportunitiesKeyYieldsNotPresentAndNoRejections() {
        final String noOpportunities = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "by_page": [], "by_query": [] }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( noOpportunities, ENGINES, SITES );

        assertFalse( r.present() );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 0, r.rejected() );
    }

    @Test
    void emptyOpportunitiesArrayIsPresentWithNoRows() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "opportunities": [] }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload, ENGINES, SITES );

        assertTrue( r.present() );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 0, r.rejected() );
    }

    // -- Malformed input never throws ----------------------------------------------------------

    @Test
    void malformedJsonYieldsRejectionNotException() {
        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( "{ not json", ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
        assertFalse( r.present() );
    }

    @Test
    void opportunitiesKeyNotAnArrayYieldsRejectionNotException() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "opportunities": "not an array" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload, ENGINES, SITES );

        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.present() );
    }

    @Test
    void aNonObjectRowInTheArrayIsRejectedWithoutThrowing() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "opportunities": [ "just a string", %s ] }""".formatted( STRIKING_DISTANCE );

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload, ENGINES, SITES );

        assertEquals( 1, r.rows().size() );
        assertEquals( 1, r.rejected() );
    }

    @Test
    void missingSnapshotDateRejectsTheWholeArray() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com", "window_days": 28,
              "opportunities": [ %s ] }""".formatted( STRIKING_DISTANCE );

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload, ENGINES, SITES );

        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
        assertTrue( r.present() );
    }

    // -- Wildcard allowlist ----------------------------------------------------------------------

    @Test
    void wildcardSiteAllowlistAcceptsAnySite() {
        final String opportunity = """
            { "type": "ctr_gap", "query": "q", "expected_uplift": 1.0,
              "engine": "google", "site": "some-other-site.example.com" }""";

        final ImportedOpportunityParser.ParseResult r =
                ImportedOpportunityParser.parse( payload( opportunity ), ENGINES, Set.of( "*" ) );

        assertEquals( 1, r.rows().size() );
        assertEquals( 0, r.rejected() );
    }

    private static String row( final String type, final String query, final String targetPage,
                               final double expectedUplift ) {
        return """
            { "type": "%s", "query": "%s", "target_page": "%s", "expected_uplift": %s,
              "engine": "google", "site": "wiki.wikantik.com" }"""
                .formatted( type, query, targetPage, expectedUplift );
    }
}

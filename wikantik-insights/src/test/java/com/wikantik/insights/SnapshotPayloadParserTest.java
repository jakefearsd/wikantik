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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SnapshotPayloadParser}. The parser is a public write surface's first
 * line of defense — every case here exists because a malformed or engine-specific payload shape
 * proved it could otherwise crash or silently corrupt the ingest.
 */
class SnapshotPayloadParserTest {

    private static final Set< String > ENGINES = Set.of( "google", "bing", "yandex" );
    private static final Set< String > SITES   = Set.of( "wiki.wikantik.com" );

    private static final String PAYLOAD = """
        { "engine": "bing", "site": "wiki.wikantik.com",
          "snapshot_date": "2026-08-14", "window_days": 28,
          "by_page":  [ {"key":"https://wiki.wikantik.com/wiki/PhilosophyHub",
                         "clicks":0,"impressions":1,"position":9.0} ],
          "by_query": [ {"key":"philosophy hub","clicks":1,"impressions":3,"position":4.5} ] }""";

    @Test
    void parsesPageAndQueryRowsAndNormalisesPagePath() {
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( PAYLOAD, ENGINES, SITES );

        assertEquals( 2, r.rows().size() );
        assertEquals( 0, r.rejected() );

        final VisibilityRow page = r.rows().stream()
                .filter( x -> x.queryText().isEmpty() ).findFirst().orElseThrow();
        assertEquals( "/wiki/PhilosophyHub", page.pagePath(),
                      "scheme+host must be stripped so page_path is a stable key" );

        final VisibilityRow query = r.rows().stream()
                .filter( x -> !x.queryText().isEmpty() ).findFirst().orElseThrow();
        assertEquals( "philosophy hub", query.queryText() );
        assertEquals( "", query.pagePath(),
                      "query rows carry no page dimension in this payload shape" );
    }

    @Test
    void rejectsUnknownEngineAndUnknownSiteWithoutThrowing() {
        final String bad = PAYLOAD.replace( "\"bing\"", "\"altavista\"" );
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( bad, ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
    }

    @Test
    void malformedJsonYieldsRejectionNotException() {
        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( "{ not json", ENGINES, SITES );
        assertTrue( r.rows().isEmpty() );
        assertEquals( 1, r.rejected() );
    }

    // -- Cases added from real docker2 data (see plan Task A3) -----------------------------

    @Test
    void yandexByPageEmptyYieldsOnlyQueryRows() {
        // Yandex's collector never populates by_page for this site; a real snapshot ships
        // an empty array (not an absent key). This must parse cleanly and yield only the
        // by_query rows -- not fail, and not silently produce zero rows.
        final String yandexPayload = """
            { "engine": "yandex", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "by_page": [],
              "by_query": [ {"key":"philosophy hub","clicks":2,"impressions":9,"position":3.1},
                            {"key":"stoicism","clicks":0,"impressions":5,"position":11.0} ] }""";

        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( yandexPayload, ENGINES, SITES );

        assertEquals( 0, r.rejected() );
        assertEquals( 2, r.rows().size() );
        assertTrue( r.rows().stream().allMatch( x -> !x.queryText().isEmpty() ),
                    "every row must be a query row when by_page is empty" );
        assertTrue( r.rows().stream().allMatch( x -> x.pagePath().isEmpty() ) );
    }

    @Test
    void rowMissingPositionYieldsNullPositionNotZero() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "by_page": [],
              "by_query": [ {"key":"philosophy hub","clicks":1,"impressions":2} ] }""";

        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( payload, ENGINES, SITES );

        assertEquals( 0, r.rejected() );
        assertEquals( 1, r.rows().size() );
        assertNull( r.rows().get( 0 ).position(),
                    "a row with no position key must yield a null position, never 0.0 -- "
                    + "the store treats null and zero differently" );
    }

    @Test
    void barePathByPageKeyNormalisesToItself() {
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "by_page": [ {"key":"/wiki/A","clicks":1,"impressions":4,"position":2.0} ],
              "by_query": [] }""";

        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( payload, ENGINES, SITES );

        assertEquals( 0, r.rejected() );
        assertEquals( 1, r.rows().size() );
        assertEquals( "/wiki/A", r.rows().get( 0 ).pagePath(),
                      "a bare path with no scheme/host must normalise to itself" );
    }

    @Test
    void absentByQueryAndByPageKeysDoNotNpe() {
        // Neither array key is present at all (not even as an empty array). The parser must
        // treat this as "no rows this snapshot", not throw.
        final String payload = """
            { "engine": "google", "site": "wiki.wikantik.com",
              "snapshot_date": "2026-08-14", "window_days": 28 }""";

        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( payload, ENGINES, SITES );

        assertEquals( 0, r.rejected() );
        assertTrue( r.rows().isEmpty() );
    }

    @Test
    void wildcardSiteAllowlistAcceptsAnySite() {
        final String payload = """
            { "engine": "google", "site": "some-other-site.example.com",
              "snapshot_date": "2026-08-14", "window_days": 28,
              "by_page": [],
              "by_query": [ {"key":"anything","clicks":1,"impressions":1,"position":1.0} ] }""";

        final SnapshotPayloadParser.ParseResult r =
                SnapshotPayloadParser.parse( payload, ENGINES, Set.of( "*" ) );

        assertEquals( 0, r.rejected() );
        assertEquals( 1, r.rows().size() );
    }

    /**
     * The cross-product grain (work item J1). by_page and by_query are disjoint projections --
     * neither attributes a query to a page -- so until these rows arrive, the shared-query
     * restriction on ENGINE_DIVERGENCE, VOCABULARY_GAP case (a), and effect measurement's
     * query_intersection mode all have nothing to run on.
     */
    @Test
    void queryPageRowsCarryBothAPageAndAQuery() {
        final String json = """
                { "engine": "google", "site": "wiki.wikantik.com",
                  "snapshot_date": "2026-08-14", "window_days": 28,
                  "query_page": [ { "query": "deploy locally", "page": "https://wiki.wikantik.com/wiki/DeployGuide",
                                    "impressions": 40, "clicks": 3, "position": 8.5 } ] }
                """;

        final SnapshotPayloadParser.ParseResult result =
                SnapshotPayloadParser.parse( json, Set.of( "*" ), Set.of( "*" ) );

        assertEquals( 0, result.rejected() );
        assertEquals( 1, result.rows().size() );
        final VisibilityRow row = result.rows().get( 0 );
        assertEquals( "/wiki/DeployGuide", row.pagePath(), "host and scheme are stripped" );
        assertEquals( "deploy locally", row.queryText() );
        assertEquals( 40, row.impressions() );
        assertEquals( 3, row.clicks() );
    }

    @Test
    void queryPageRowMissingEitherIdentityIsRejected() {
        final String json = """
                { "engine": "google", "site": "wiki.wikantik.com",
                  "snapshot_date": "2026-08-14", "window_days": 28,
                  "query_page": [ { "query": "no page", "impressions": 5, "clicks": 0 },
                                  { "page": "/wiki/NoQuery", "impressions": 5, "clicks": 0 },
                                  { "query": "", "page": "/wiki/Blank", "impressions": 5, "clicks": 0 } ] }
                """;

        final SnapshotPayloadParser.ParseResult result =
                SnapshotPayloadParser.parse( json, Set.of( "*" ), Set.of( "*" ) );

        assertEquals( 3, result.rejected(), "a row that identifies nothing joinable is not storable" );
        assertEquals( 0, result.rows().size() );
    }

    @Test
    void anAbsentQueryPageArrayIsNotAnError() {
        final String json = """
                { "engine": "google", "site": "wiki.wikantik.com",
                  "snapshot_date": "2026-08-14", "window_days": 28,
                  "by_page": [ { "key": "/wiki/A", "impressions": 10, "clicks": 1, "position": 4.0 } ] }
                """;

        final SnapshotPayloadParser.ParseResult result =
                SnapshotPayloadParser.parse( json, Set.of( "*" ), Set.of( "*" ) );

        assertEquals( 0, result.rejected() );
        assertEquals( 1, result.rows().size(), "existing payloads keep parsing unchanged" );
    }
}

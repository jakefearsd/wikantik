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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses unmodified snapshots captured from the live jakemon exporter on 2026-08-16
 * (window ending 2026-08-14, site {@code wiki.wikantik.com}).
 *
 * <p>Hand-written fixtures only prove the parser handles the shapes we imagined. These prove it
 * handles the shapes the upstream collector actually emits — including per-engine differences
 * that no amount of guessing would have produced. If jakemon's payload shape ever drifts, these
 * fail before a backfill silently writes garbage.</p>
 */
class SnapshotPayloadParserRealDataTest {

    private static final Set< String > ENGINES = Set.of( "google", "bing", "yandex" );
    private static final Set< String > SITES = Set.of( "*" );

    private static String fixture( final String name ) throws IOException {
        try ( InputStream in = SnapshotPayloadParserRealDataTest.class
                .getResourceAsStream( "/snapshots/" + name ) ) {
            assertNotNull( in, "missing fixture /snapshots/" + name );
            return new String( in.readAllBytes(), StandardCharsets.UTF_8 );
        }
    }

    /** Real payloads carry no engine/site/date keys of their own — the shipper supplies them —
     *  so re-attach them exactly the way {@code ship_visibility.ship_all} does. */
    private static SnapshotPayloadParser.ParseResult parseFixture( final String engine )
            throws IOException {
        final String raw = fixture( engine + "-wiki-2026-08-14.json" );
        final String body = "{\"engine\":\"" + engine + "\",\"site\":\"wiki.wikantik.com\","
                + "\"snapshot_date\":\"2026-08-14\",\"window_days\":28,"
                + raw.substring( raw.indexOf( '{' ) + 1 );
        return SnapshotPayloadParser.parse( body, ENGINES, SITES );
    }

    @Test
    void parsesGoogleSnapshotWithBothDimensions() throws IOException {
        final SnapshotPayloadParser.ParseResult r = parseFixture( "google" );
        assertEquals( 0, r.rejected() );
        assertEquals( 826, r.rows().size(), "457 by_page + 369 by_query in the captured snapshot" );
    }

    @Test
    void parsesBingSnapshot() throws IOException {
        final SnapshotPayloadParser.ParseResult r = parseFixture( "bing" );
        assertEquals( 0, r.rejected() );
        assertEquals( 230, r.rows().size(), "88 by_page + 142 by_query in the captured snapshot" );
    }

    @Test
    void parsesYandexSnapshotWhichHasNoPageDimensionAtAll() throws IOException {
        // Yandex reports queries only — by_page is []. A parser that assumed page rows always
        // exist would drop or fail a third of the corpus, and this is the case hand-written
        // fixtures were least likely to cover.
        final SnapshotPayloadParser.ParseResult r = parseFixture( "yandex" );
        assertEquals( 0, r.rejected() );
        assertEquals( 21, r.rows().size() );
        assertTrue( r.rows().stream().allMatch( row -> row.pagePath().isEmpty() ),
                "every yandex row is a query row" );
        assertFalse( r.rows().stream().anyMatch( row -> row.queryText().isEmpty() ) );
    }

    @Test
    void everyRealPagePathIsNormalisedToASitePath() throws IOException {
        // The raw keys are absolute URLs. Left unnormalised, page_path would embed the scheme and
        // host, so the same page under a different host would key as a different row.
        final SnapshotPayloadParser.ParseResult r = parseFixture( "google" );
        assertTrue( r.rows().stream()
                        .filter( row -> row.queryText().isEmpty() )
                        .allMatch( row -> row.pagePath().startsWith( "/" ) ),
                "page rows must carry a leading-slash path" );
        assertFalse( r.rows().stream().anyMatch( row -> row.pagePath().contains( "://" ) ) );
    }

    @Test
    void noRealRowIsDegenerateOnBothKeyDimensions() throws IOException {
        // pagePath and queryText are both part of the primary key. A row empty in both would
        // collide with any other such row and silently overwrite it.
        for ( final String engine : new String[] { "google", "bing", "yandex" } ) {
            final SnapshotPayloadParser.ParseResult r = parseFixture( engine );
            assertFalse( r.rows().stream()
                            .anyMatch( row -> row.pagePath().isEmpty() && row.queryText().isEmpty() ),
                    engine + " produced a row with neither a page nor a query" );
        }
    }

    @Test
    void realRowsCarryNonNegativeCountsAndUsablePositions() throws IOException {
        for ( final String engine : new String[] { "google", "bing", "yandex" } ) {
            final SnapshotPayloadParser.ParseResult r = parseFixture( engine );
            assertTrue( r.rows().stream().allMatch( row -> row.impressions() >= 0 && row.clicks() >= 0 ),
                    engine + " produced a negative count" );
            assertTrue( r.rows().stream()
                            .allMatch( row -> row.position() == null || row.position() >= 0.0 ),
                    engine + " produced a negative position" );
        }
    }
}

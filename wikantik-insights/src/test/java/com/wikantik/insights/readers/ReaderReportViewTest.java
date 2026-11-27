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
package com.wikantik.insights.readers;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReaderReportViewTest {

    @Test
    void pagesAreRenderedWithSnakeCaseKeysInOrderAndNullsKept() {
        final PageReaderStats stats = new PageReaderStats( "01A", "Alpha", 10, 7, 30.0, 0.25, 0.5,
                Map.of( "direct", 4 ), 0.75, null, 2000.0, null, 0.05 );

        final List< Map< String, Object > > out = ReaderReportView.pages( List.of( stats ) );

        assertEquals( 1, out.size() );
        final Map< String, Object > m = out.get( 0 );
        final Map< String, Object > expected = new LinkedHashMap<>();
        expected.put( "canonical_id", "01A" );
        expected.put( "slug", "Alpha" );
        expected.put( "views", 10 );
        expected.put( "visitors", 7 );
        expected.put( "typical_active_s", 30.0 );
        expected.put( "scroll75_rate", 0.25 );
        expected.put( "actions_per_view", 0.5 );
        expected.put( "landings", Map.of( "direct", 4 ) );
        expected.put( "continuation_rate", 0.75 );
        expected.put( "returning_this_month_rate", null );
        expected.put( "lcp_p75_ms", 2000.0 );
        expected.put( "inp_p75_ms", null );
        expected.put( "cls_p75", 0.05 );
        assertEquals( expected, m );
        assertEquals( List.copyOf( expected.keySet() ), List.copyOf( m.keySet() ) );
    }

    @Test
    void noPagesIsAnEmptyList() {
        assertTrue( ReaderReportView.pages( List.of() ).isEmpty() );
    }

    @Test
    void coverageIsRenderedAsSiteRatiosAndPageRows() {
        final ReaderReport.Coverage coverage = new ReaderReport.Coverage( Map.of( "google", 0.5 ),
                List.of( new ReaderReport.CoverageRow( "google", "01A", 20, 10 ) ) );

        final Map< String, Object > out = ReaderReportView.coverage( coverage );

        assertEquals( Map.of( "google", 0.5 ), out.get( "site" ) );
        assertEquals( List.of( Map.of( "engine", "google", "canonical_id", "01A",
                "search_clicks", 20, "organic_landings", 10 ) ), out.get( "pages" ) );
    }
}

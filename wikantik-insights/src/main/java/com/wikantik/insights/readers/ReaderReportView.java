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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON-shaped (snake_case) view of a reader report, shared by the REST endpoint and the MCP tool. */
public final class ReaderReportView {

    private ReaderReportView() {
    }

    public static List< Map< String, Object > > pages( final List< PageReaderStats > stats ) {
        final List< Map< String, Object > > out = new ArrayList<>( stats.size() );
        for ( final PageReaderStats p : stats ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "canonical_id", p.canonicalId() );
            m.put( "slug", p.slug() );
            m.put( "views", p.views() );
            m.put( "visitors", p.visitors() );
            m.put( "typical_active_s", p.typicalActiveS() );
            m.put( "scroll75_rate", p.scroll75Rate() );
            m.put( "actions_per_view", p.actionsPerView() );
            m.put( "landings", p.landings() );
            m.put( "continuation_rate", p.continuationRate() );
            m.put( "returning_this_month_rate", p.returningThisMonthRate() );
            m.put( "lcp_p75_ms", p.lcpP75Ms() );
            m.put( "inp_p75_ms", p.inpP75Ms() );
            m.put( "cls_p75", p.clsP75() );
            out.add( m );
        }
        return out;
    }

    public static Map< String, Object > coverage( final ReaderReport.Coverage coverage ) {
        final List< Map< String, Object > > rows = new ArrayList<>();
        for ( final ReaderReport.CoverageRow r : coverage.pages() ) {
            final Map< String, Object > m = new LinkedHashMap<>();
            m.put( "engine", r.engine() );
            m.put( "canonical_id", r.canonicalId() );
            m.put( "search_clicks", r.searchClicks() );
            m.put( "organic_landings", r.organicLandings() );
            rows.add( m );
        }
        final Map< String, Object > out = new LinkedHashMap<>();
        out.put( "site", coverage.site() );
        out.put( "pages", rows );
        return out;
    }
}

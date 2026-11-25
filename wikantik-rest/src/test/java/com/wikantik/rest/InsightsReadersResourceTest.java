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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.insights.readers.PageReaderStats;
import com.wikantik.insights.readers.ReaderReport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InsightsReadersResource}. The admin check is {@code AdminAuthFilter}'s job
 * ({@code /admin/*}), as for the sibling insights resources, so it is not repeated here.
 */
class InsightsReadersResourceTest {

    private ReaderReport report;
    private HttpServletRequest req;
    private HttpServletResponse resp;
    private StringWriter buf;

    private InsightsReadersResource resource( final ReaderReport r ) {
        return new InsightsReadersResource() {
            @Override ReaderReport buildReport() { return r; }
            @Override String siteHost() { return "wiki.example.com"; }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        report = mock( ReaderReport.class );
        req = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        buf = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( buf ) );
        when( report.pages( anyString(), anyInt(), anyInt() ) ).thenReturn( List.of() );
        when( report.coverage( anyString(), anyInt() ) )
                .thenReturn( new ReaderReport.Coverage( Map.of(), List.of() ) );
    }

    @Test
    void daysAboveTheMaximumIsClampedToNinety() throws Exception {
        when( req.getParameter( "days" ) ).thenReturn( "500" );
        resource( report ).doGet( req, resp );
        verify( report ).pages( eq( "wiki.example.com" ), eq( 90 ), anyInt() );
        verify( report ).coverage( "wiki.example.com", 90 );
    }

    @Test
    void daysDefaultsToTwentyEight() throws Exception {
        resource( report ).doGet( req, resp );
        verify( report ).pages( eq( "wiki.example.com" ), eq( 28 ), anyInt() );
    }

    @Test
    void nonNumericDaysIsABadRequest() throws Exception {
        when( req.getParameter( "days" ) ).thenReturn( "abc" );
        resource( report ).doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_BAD_REQUEST );
    }

    @Test
    void servesPagesCoverageAndRefreshedAt() throws Exception {
        when( report.pages( anyString(), anyInt(), anyInt() ) ).thenReturn( List.of(
                new PageReaderStats( "ID1", "Alpha", 10, 7, 30.0, null, 0.5, Map.of( "direct", 4 ),
                        0.25, null, 2100.0, null, null ) ) );
        when( report.refreshedAt( "wiki.example.com" ) ).thenReturn( Instant.parse( "2026-11-20T05:00:00Z" ) );
        resource( report ).doGet( req, resp );
        final JsonObject b = JsonParser.parseString( buf.toString() ).getAsJsonObject();
        final JsonObject p = b.getAsJsonArray( "pages" ).get( 0 ).getAsJsonObject();
        assertEquals( "Alpha", p.get( "slug" ).getAsString() );
        assertEquals( 30.0, p.get( "typical_active_s" ).getAsDouble() );
        assertTrue( p.get( "returning_this_month_rate" ).isJsonNull() );
        assertEquals( 4, p.getAsJsonObject( "landings" ).get( "direct" ).getAsInt() );
        assertTrue( b.getAsJsonObject( "coverage" ).has( "site" ) );
        assertEquals( "2026-11-20T05:00:00Z", b.get( "refreshedAt" ).getAsString() );
    }

    @Test
    void noDatasourceIs503() throws Exception {
        resource( null ).doGet( req, resp );
        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }
}

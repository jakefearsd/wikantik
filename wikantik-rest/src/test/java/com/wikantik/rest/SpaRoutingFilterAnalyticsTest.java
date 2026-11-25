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

import com.wikantik.TestEngine;
import com.wikantik.analytics.AnalyticsSettings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The Umami tracker tag reaches reader pages only, only when analytics is configured. */
class SpaRoutingFilterAnalyticsTest {

    private static final String FAKE_INDEX_HTML = "<!DOCTYPE html>\n<html><head>\n<title>Wikantik</title>\n"
            + "</head><body><div id=\"root\"></div>\n</body></html>";
    private static final String TAG = "<script defer src=\"/analytics/script.js\" data-website-id=\"abc-123\""
            + " data-host-url=\"/analytics\" data-auto-track=\"false\" data-performance=\"true\"></script>";

    private TestEngine engine;
    private SpaRoutingFilter filter;
    private ServletContext servletContext;
    private final FilterChain chain = mock( FilterChain.class );

    private static AnalyticsSettings enabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.analytics.website.id", "abc-123" );
        p.setProperty( "wikantik.analytics.upstream", "http://umami.invalid:3000" );
        return AnalyticsSettings.from( p );
    }

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "AnalyticsPage", "Some body text for the analytics page." );
        servletContext = mock( ServletContext.class );
        when( servletContext.getResourceAsStream( "/index.html" ) )
                .thenAnswer( inv -> new ByteArrayInputStream( FAKE_INDEX_HTML.getBytes( StandardCharsets.UTF_8 ) ) );
        filter = new SpaRoutingFilter();
        filter.init( null );
        filter.setEngineForTest( engine );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    /** Runs the filter for {@code uri} and returns the response body (empty when the chain took it). */
    private String get( final String uri, final String ctxPath ) throws Exception {
        return fetch( uri, ctxPath, null ).body;
    }

    private record Result( String body, HttpServletResponse response ) { }

    private Result fetch( final String uri, final String ctxPath, final String ifNoneMatch ) throws Exception {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( ctxPath );
        when( request.getRequestURI() ).thenReturn( ctxPath + uri );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html" );
        when( request.getHeader( "If-None-Match" ) ).thenReturn( ifNoneMatch );
        when( request.getServletContext() ).thenReturn( servletContext );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when( response.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { buffer.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener l ) { }
        } );
        filter.doFilter( request, response, chain );
        return new Result( buffer.toString( StandardCharsets.UTF_8 ), response );
    }

    private static int count( final String haystack, final String needle ) {
        int n = 0;
        for( int i = haystack.indexOf( needle ); i >= 0; i = haystack.indexOf( needle, i + 1 ) ) {
            n++;
        }
        return n;
    }

    @Test
    void enabledWikiPageCarriesTheTagOnceInsideHead() throws Exception {
        filter.setAnalyticsForTest( enabled() );
        final String html = get( "/wiki/AnalyticsPage", "" );
        assertEquals( 1, count( html, TAG ), html );
        assertTrue( html.indexOf( TAG ) < html.indexOf( "</head>" ), "tag must be inside <head>: " + html );
    }

    @Test
    void siteRootAndOtherReaderRoutesCarryTheTag() throws Exception {
        filter.setAnalyticsForTest( enabled() );
        assertEquals( 1, count( get( "/", "" ), TAG ) );
        assertEquals( 1, count( get( "/search", "" ), TAG ) );
        assertEquals( 1, count( get( "/page-graph", "" ), TAG ) );
    }

    @Test
    void contextPathPrefixesTheScriptAndHostUrl() throws Exception {
        filter.setAnalyticsForTest( enabled() );
        final String html = get( "/wiki/AnalyticsPage", "/w" );
        assertTrue( html.contains( "src=\"/w/analytics/script.js\"" ), html );
        assertTrue( html.contains( "data-host-url=\"/w/analytics\"" ), html );
    }

    @Test
    void disabledResponseHasNoAnalyticsReference() throws Exception {
        filter.setAnalyticsForTest( AnalyticsSettings.disabled() );
        assertFalse( get( "/wiki/AnalyticsPage", "" ).contains( "/analytics/" ) );
        assertFalse( get( "/", "" ).contains( "/analytics/" ) );
    }

    @Test
    void upstreamBlankMeansNoTagEvenWithWebsiteId() throws Exception {
        final Properties p = new Properties();
        p.setProperty( "wikantik.analytics.website.id", "abc-123" );
        filter.setAnalyticsForTest( AnalyticsSettings.from( p ) );
        assertFalse( get( "/wiki/AnalyticsPage", "" ).contains( "/analytics/" ) );
    }

    @Test
    void adminAndEditorRoutesNeverCarryTheTag() throws Exception {
        filter.setAnalyticsForTest( enabled() );
        for( final String route : new String[] { "/admin/insights", "/admin", "/admin/security", "/edit/Main",
                "/diff/Main", "/login", "/preferences", "/work" } ) {
            final String html = get( route, "" );
            assertFalse( html.isEmpty(), route + " should be served from the shell" );
            assertFalse( html.contains( "/analytics/" ), route + " must not carry the tracker: " + html );
        }
    }

    @Test
    void etagDistinguishesEnabledFromDisabled() throws Exception {
        filter.setAnalyticsForTest( enabled() );
        final Result on = fetch( "/wiki/AnalyticsPage", "", null );
        final ArgumentCaptor< String > onEtag = ArgumentCaptor.forClass( String.class );
        verify( on.response ).setHeader( org.mockito.ArgumentMatchers.eq( "ETag" ), onEtag.capture() );

        filter.setAnalyticsForTest( AnalyticsSettings.disabled() );
        final Result off = fetch( "/wiki/AnalyticsPage", "", onEtag.getValue() );
        final ArgumentCaptor< String > offEtag = ArgumentCaptor.forClass( String.class );
        verify( off.response ).setHeader( org.mockito.ArgumentMatchers.eq( "ETag" ), offEtag.capture() );

        assertNotEquals( onEtag.getValue(), offEtag.getValue() );
        assertFalse( off.body.isEmpty(), "a cached copy with the tag must not 304 once analytics is off" );
        assertFalse( off.body.contains( "/analytics/" ) );
    }
}

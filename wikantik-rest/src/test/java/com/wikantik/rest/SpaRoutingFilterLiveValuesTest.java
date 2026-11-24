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
import com.wikantik.api.livevalues.LiveValueSource;
import com.wikantik.api.livevalues.LiveValues;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A summary holding a live-value token reaches the head, JSON-LD and data island expanded, never as a token. */
class SpaRoutingFilterLiveValuesTest {

    private static final String FAKE_INDEX_HTML = "<!DOCTYPE html>\n<html><head>\n<title>Wikantik</title>\n"
            + "</head><body><div id=\"root\"></div>\n</body></html>";
    private static final String FENCED = "{{wiki:pages}}";
    private static final String TOKEN = "{{wiki:articles|round=100}}";

    /** Replaces the one token with a fixed value; the real service is covered by its own tests. */
    private static final class StubLiveValues implements LiveValues {
        @Override public String expandText( final String text ) { return text.replace( TOKEN, "1,100+" ); }
        /** Code-aware enough for the test: only text outside ``` fences expands. */
        @Override public String expandBody( final String markdown ) {
            final String[] parts = markdown.split( "```", -1 );
            for( int i = 0; i < parts.length; i += 2 ) {
                parts[ i ] = parts[ i ].replace( TOKEN, "1,100+" );
            }
            return String.join( "```", parts );
        }
        @Override public String generation() { return "g"; }
        @Override public Map< String, String > renderedValues() { return Map.of(); }
        @Override public void register( final LiveValueSource source ) { }
    }

    private TestEngine engine;
    private SpaRoutingFilter filter;
    private ServletContext servletContext;
    private final FilterChain chain = mock( FilterChain.class );

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.serviceRegistry().put( LiveValues.class, new StubLiveValues() );
        engine.saveText( "LiveSummaryPage", "---\nsummary: 'Over " + TOKEN
                + " articles on software and maths for people and agents'\n---\nBody has " + TOKEN
                + " here.\n\n```\n" + FENCED + "\n```\n" );
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

    @Test
    void summaryIsExpandedInHeadJsonLdAndIsland() throws Exception {
        final HttpServletRequest request = mock( HttpServletRequest.class );
        when( request.getContextPath() ).thenReturn( "" );
        when( request.getRequestURI() ).thenReturn( "/wiki/LiveSummaryPage" );
        when( request.getHeader( "Accept" ) ).thenReturn( "text/html" );
        when( request.getServletContext() ).thenReturn( servletContext );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        when( response.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { buffer.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener l ) { }
        } );

        filter.doFilter( request, response, chain );

        final String html = buffer.toString( StandardCharsets.UTF_8 );
        final String expected = "Over 1,100+ articles on software and maths for people and agents";
        assertTrue( html.contains( "<meta name=\"description\" content=\"" + expected + "\"" ), html );
        assertTrue( html.contains( "property=\"og:description\" content=\"" + expected + "\"" ), html );
        assertTrue( html.contains( "\"description\":\"" + expected + "\"" )
                || html.contains( "\"description\": \"" + expected + "\"" ), "JSON-LD / island description: " + html );
        assertTrue( html.contains( FENCED ), "a token inside a code fence must stay verbatim: " + html );
        assertFalse( html.replace( FENCED, "" ).contains( "{{wiki:" ), "a raw token reached the response: " + html );
        assertTrue( html.contains( "Body has 1,100+ here." ), "island content / body shows the expanded body: " + html );
    }
}

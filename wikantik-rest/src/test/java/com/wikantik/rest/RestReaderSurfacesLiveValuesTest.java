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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.livevalues.StubLiveValues;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live-value tokens are expanded on the REST reader surfaces: {@code ?format=md|json} and the link preview card. */
class RestReaderSurfacesLiveValuesTest {

    private static TestEngine engine;
    private static WikiPageFormatFilter filter;
    private static PageResource servlet;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        engine.serviceRegistry().put( LiveValues.class, new StubLiveValues() );
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
        engine.saveText( "LvRest", "---\nsummary: We hold " + TOKEN + " articles.\n---\n"
                + "Body has " + TOKEN + " pages.\n" );
        engine.saveText( "LvBlock", "Block says " + TOKEN + " here ^blk1\n\nOther ^blk2\n" );
        filter = new WikiPageFormatFilter();
        final FilterConfig config = Mockito.mock( FilterConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        filter.init( config );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private static String format( final String fmt ) throws Exception {
        final HttpServletRequest req = HttpMockFactory.createHttpRequest( "/wiki/LvRest" );
        Mockito.when( req.getParameter( "format" ) ).thenReturn( fmt );
        Mockito.when( req.getRequestURI() ).thenReturn( "/wiki/LvRest" );
        Mockito.when( req.getContextPath() ).thenReturn( "" );
        final HttpServletResponse resp = HttpMockFactory.createHttpResponse();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final StringWriter sw = new StringWriter();
        Mockito.when( resp.getOutputStream() ).thenReturn( new ServletOutputStream() {
            @Override public void write( final int b ) { baos.write( b ); }
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener( final WriteListener l ) { }
        } );
        Mockito.when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
        filter.doFilter( req, resp, Mockito.mock( FilterChain.class ) );
        return "md".equals( fmt ) ? baos.toString( StandardCharsets.UTF_8 ) : sw.toString();
    }

    private static JsonObject preview( final String page, final String section ) throws Exception {
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( true ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "view" ) );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + page );
        if ( section != null ) {
            Mockito.doReturn( section ).when( request ).getParameter( "section" );
        }
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        new PagePreviewHandler( spy ).handlePreview( request, response, page );
        return new Gson().fromJson( sw.toString(), JsonObject.class );
    }

    @Test
    void markdownFormatExpandsProse() throws Exception {
        final String md = format( "md" );
        assertTrue( md.contains( "Body has " + VALUE + " pages." ), md );
        assertFalse( md.contains( "{{wiki:" ), md );
    }

    @Test
    void jsonFormatExpandsSummaryAndContent() throws Exception {
        final JsonObject o = new Gson().fromJson( format( "json" ), JsonObject.class );
        assertEquals( "We hold " + VALUE + " articles.", o.get( "summary" ).getAsString() );
        assertTrue( o.get( "content" ).getAsString().contains( "Body has " + VALUE + " pages." ) );
    }

    @Test
    void previewCardExpandsSummaryAndExcerpt() throws Exception {
        final JsonObject o = preview( "LvRest", null );
        assertEquals( "We hold " + VALUE + " articles.", o.get( "summary" ).getAsString() );
        assertTrue( o.get( "excerpt" ).getAsString().contains( VALUE ), o.toString() );
        assertFalse( o.get( "excerpt" ).getAsString().contains( "{{wiki:" ) || o.toString().contains( "articles|" ) );
    }

    @Test
    void previewBlockExcerptExpandsTokens() throws Exception {
        final JsonObject o = preview( "LvBlock", "^blk1" );
        assertTrue( o.get( "excerpt" ).getAsString().contains( "Block says " + VALUE + " here" ), o.toString() );
    }
}

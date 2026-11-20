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
import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.filters.PageFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Direct tests of {@link PagePreviewHandler}: the {@code /preview} card and the {@code /embed} body. */
class PagePreviewHandlerTest {

    private static TestEngine engine;
    private static PageResource servlet;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
        engine.getManager( com.wikantik.filters.FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String postTranslate( final com.wikantik.api.core.Context context, final String html )
                    throws FilterException {
                if ( html.contains( "ppht-boom" ) ) {
                    throw new FilterException( "boom" );
                }
                return html;
            }
        }, 0 );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @AfterEach
    void clean() {
        engine.deleteQuietly( "PphtPage", "PphtBare", "PphtBoom", "PphtSecret" );
    }

    private record Reply( int status, String body, HttpServletResponse response ) {
        JsonObject json() {
            return new com.google.gson.Gson().fromJson( body, JsonObject.class );
        }
    }

    private static PageResource spy( final boolean view ) {
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( view ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.eq( "view" ) );
        try {
            Mockito.doAnswer( inv -> {
                if ( !view ) {
                    RestJson.sendError( inv.getArgument( 1 ), HttpServletResponse.SC_FORBIDDEN, "Forbidden" );
                }
                return view;
            } ).when( spy ).checkPagePermission( Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.eq( "view" ) );
        } catch ( final java.io.IOException e ) {
            throw new IllegalStateException( e );
        }
        return spy;
    }

    private static Reply call( final boolean embed, final PageResource s, final String name,
                               final Map< String, String > params ) throws Exception {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + name );
        params.forEach( ( k, v ) -> Mockito.doReturn( v ).when( request ).getParameter( k ) );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        final PagePreviewHandler h = new PagePreviewHandler( s );
        if ( embed ) {
            h.handleEmbed( request, response, name );
        } else {
            h.handlePreview( request, response, name );
        }
        final ArgumentCaptor< Integer > c = ArgumentCaptor.forClass( Integer.class );
        Mockito.verify( response, Mockito.atLeast( 0 ) ).setStatus( c.capture() );
        return new Reply( c.getAllValues().isEmpty() ? 200 : c.getValue(), sw.toString(), response );
    }

    @Test
    void previewCardCarriesTitleTypeFirstClusterSummaryAndExcerpt() throws Exception {
        engine.saveText( "PphtPage", "---\ntitle: ' A Title '\ntype: article\ncluster: [alpha, beta]\nsummary: Short.\n---\nBody text here.\n" );
        final Reply r = call( false, spy( true ), "PphtPage", Map.of() );
        final JsonObject o = r.json();
        assertEquals( "PphtPage", o.get( "name" ).getAsString() );
        assertEquals( "A Title", o.get( "title" ).getAsString() );
        assertEquals( "article", o.get( "type" ).getAsString() );
        assertEquals( "alpha", o.get( "cluster" ).getAsString() );
        assertEquals( "Short.", o.get( "summary" ).getAsString() );
        assertTrue( o.get( "excerpt" ).getAsString().contains( "Body text here." ) );
        assertFalse( o.has( "blockMissing" ) );
        Mockito.verify( r.response() ).setHeader( "Cache-Control", "private, no-cache" );
    }

    @Test
    void previewOmitsBlankFieldsAndBeautifiesTheName() throws Exception {
        engine.saveText( "PphtBare", "---\nsummary: '  '\ncluster: single\n---\nJust text.\n" );
        final JsonObject o = call( false, spy( true ), "PphtBare", Map.of() ).json();
        assertEquals( "Ppht Bare", o.get( "title" ).getAsString() );
        assertFalse( o.has( "summary" ) );
        assertFalse( o.has( "type" ) );
        assertEquals( "single", o.get( "cluster" ).getAsString() );
    }

    @Test
    void previewSectionExcerptsThatHeadingAndBlockRefs() throws Exception {
        engine.saveText( "PphtPage", "Intro words.\n\n## Usage\n\nUsage words.\n\nA block. ^blk1\n" );
        final String usage = call( false, spy( true ), "PphtPage", Map.of( "section", "usage" ) ).json().get( "excerpt" ).getAsString();
        assertTrue( usage.contains( "Usage words" ), usage );
        assertFalse( usage.contains( "Intro words" ), usage );
        final JsonObject block = call( false, spy( true ), "PphtPage", Map.of( "section", "^blk1" ) ).json();
        assertTrue( block.get( "excerpt" ).getAsString().contains( "A block." ) );
        assertFalse( block.has( "blockMissing" ) );
        final JsonObject gone = call( false, spy( true ), "PphtPage", Map.of( "section", "^nope" ) ).json();
        assertTrue( gone.get( "blockMissing" ).getAsBoolean() );
        assertTrue( gone.get( "excerpt" ).getAsString().contains( "Intro words" ) );
        final JsonObject unknownHeading = call( false, spy( true ), "PphtPage", Map.of( "section", "no-such-heading" ) ).json();
        assertTrue( unknownHeading.get( "excerpt" ).getAsString().contains( "Intro words" ) );
    }

    @Test
    void missingAndUnviewablePagesAreTheSame404() throws Exception {
        engine.saveText( "PphtSecret", "hidden" );
        final Reply hidden = call( false, spy( false ), "PphtSecret", Map.of() );
        final Reply missing = call( false, spy( true ), "PphtNoSuchPage", Map.of() );
        assertEquals( 404, hidden.status() );
        assertEquals( 404, missing.status() );
        assertEquals( hidden.body(), missing.body() );
    }

    @Test
    void embedRendersBodyAndFlags() throws Exception {
        engine.saveText( "PphtPage", "Embedded words.\n" );
        final Reply r = call( true, spy( true ), "PphtPage", Map.of() );
        final JsonObject o = r.json();
        assertTrue( o.get( "html" ).getAsString().contains( "Embedded words." ), r.body() );
        assertFalse( o.get( "missing" ).getAsBoolean() );
        Mockito.verify( r.response() ).setHeader( "Cache-Control", "private, no-cache" );
    }

    @Test
    void embedOfAMissingPageIsDataNotA404() throws Exception {
        final Reply r = call( true, spy( true ), "PphtNoSuchEmbed", Map.of() );
        assertEquals( 200, r.status() );
        assertTrue( r.json().get( "missing" ).getAsBoolean() );
    }

    @Test
    void embedWithoutViewPermissionIs403() throws Exception {
        engine.saveText( "PphtSecret", "hidden" );
        final Reply r = call( true, spy( false ), "PphtSecret", Map.of() );
        assertEquals( 403, r.status() );
    }

    @Test
    void embedServesTheBodyUnfilteredWhenAPostTranslateFilterFails() throws Exception {
        engine.saveText( "PphtBoom", "Words ppht-boom end.\n" );
        final Reply r = call( true, spy( true ), "PphtBoom", Map.of() );
        assertEquals( 200, r.status() );
        assertTrue( r.json().get( "html" ).getAsString().contains( "ppht-boom" ), r.body() );
    }
}

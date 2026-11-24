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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;

import static com.wikantik.livevalues.StubLiveValues.TOKEN;
import static com.wikantik.livevalues.StubLiveValues.VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /api/pages/{name}} keeps {@code content} and {@code metadata} raw (the editor round-trips them) and
 * carries the expanded summary in a separate {@code resolved} block.
 */
class PageReadHandlerLiveValuesTest {

    private static TestEngine engine;
    private static PageResource servlet;

    @BeforeAll
    static void start() throws Exception {
        engine = TestEngine.build();
        engine.serviceRegistry().put( LiveValues.class, new StubLiveValues() );
        servlet = RestTestSupport.initServlet( PageResource::new, engine );
        engine.saveText( "LvRead", "---\nsummary: We hold " + TOKEN + " articles.\n---\nBody has " + TOKEN + " pages.\n" );
        engine.saveText( "LvNoSummary", "---\ntype: article\n---\nBody only.\n" );
    }

    @AfterAll
    static void stop() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    private static JsonObject get( final String page ) throws Exception {
        final PageResource spy = Mockito.spy( servlet );
        Mockito.doReturn( true ).when( spy ).hasPagePermission( Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        Mockito.doReturn( true ).when( spy ).checkPagePermission( Mockito.any(), Mockito.any(), Mockito.anyString(), Mockito.anyString() );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/pages/" + page );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        new PageReadHandler( spy ).handleGet( request, response, page );
        return new Gson().fromJson( sw.toString(), JsonObject.class );
    }

    @Test
    void contentAndMetadataStayRawWhileResolvedCarriesTheExpandedSummary() throws Exception {
        final JsonObject o = get( "LvRead" );
        assertTrue( o.get( "content" ).getAsString().contains( TOKEN ), o.toString() );
        assertEquals( "We hold " + TOKEN + " articles.", o.getAsJsonObject( "metadata" ).get( "summary" ).getAsString() );
        assertEquals( "We hold " + VALUE + " articles.", o.getAsJsonObject( "resolved" ).get( "summary" ).getAsString() );
    }

    @Test
    void resolvedIsOmittedWhenThereIsNoSummary() throws Exception {
        assertFalse( get( "LvNoSummary" ).has( "resolved" ) );
    }
}

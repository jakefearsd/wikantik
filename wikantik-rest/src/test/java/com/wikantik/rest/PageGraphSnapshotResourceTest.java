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
import com.wikantik.api.pagegraph.PageGraphNode;
import com.wikantik.api.pagegraph.PageGraphService;
import com.wikantik.api.pagegraph.PageGraphSnapshot;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link PageGraphSnapshotResource} — GET snapshot consumed by the /page-graph React route. */
class PageGraphSnapshotResourceTest {

    private TestEngine engine;
    private PageGraphSnapshotResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new PageGraphSnapshotResource();
        servlet.setEngine( engine );
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void doGet_serviceUnavailable_returns503() throws Exception {
        // No PageGraphService registered on this TestEngine — service() lookup returns null.
        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/page-graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        servlet.doGet( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( sw.toString().contains( "Page Graph service is not available" ), sw.toString() );
    }

    @Test
    void doGet_returnsSnapshotFromTheService() throws Exception {
        final PageGraphService svc = mock( PageGraphService.class );
        when( svc.snapshot( any() ) ).thenReturn( new PageGraphSnapshot(
                "2026-01-01T00:00:00Z", 1, 0, 4,
                List.of( new PageGraphNode( "Alpha", "Alpha", "page", "normal", null,
                        0, 0, false, "index-funds", List.of() ) ),
                List.of() ) );
        engine.setManager( PageGraphService.class, svc );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/page-graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        servlet.doGet( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertFalse( obj.has( "error" ), "Should not be an error: " + sw );
        assertEquals( 1, obj.get( "nodeCount" ).getAsInt() );
    }

    @Test
    void doGet_serviceThrows_returns500() throws Exception {
        final PageGraphService svc = mock( PageGraphService.class );
        when( svc.snapshot( any() ) ).thenThrow( new RuntimeException( "boom" ) );
        engine.setManager( PageGraphService.class, svc );

        final StringWriter sw = new StringWriter();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( "/api/page-graph" );
        Mockito.doReturn( null ).when( request ).getPathInfo();

        servlet.doGet( request, response );

        Mockito.verify( response ).setStatus( HttpServletResponse.SC_INTERNAL_SERVER_ERROR );
        assertTrue( sw.toString().contains( "Failed to build page graph snapshot" ), sw.toString() );
    }
}

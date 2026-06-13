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
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.BundleSection;
import com.wikantik.api.bundle.CitationHandle;
import com.wikantik.api.bundle.ContextBundle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class BundleResourceTest {

    @Test
    void missing_q_returns_400() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( null );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 400 );
    }

    @Test
    void unavailable_service_returns_503() throws Exception {
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return null; }
        };
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "something" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( new StringWriter() ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 503 );
    }

    @Test
    void happy_path_serialises_bundle() throws Exception {
        final BundleAssemblyService svc = mock( BundleAssemblyService.class );
        final BundleResource resource = new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return svc; }
        };
        final ContextBundle bundle = new ContextBundle( "deploy", List.of(
                new BundleSection( "01DEP", "DeployGuide", List.of( "Setup" ), "do x", 0.9,
                        new CitationHandle( "01DEP", 7, List.of( "Setup" ), "do x", "abc123" ) ) ) );
        when( svc.assemble( "deploy" ) ).thenReturn( bundle );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        when( req.getParameter( "q" ) ).thenReturn( "deploy" );
        final StringWriter sw = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        resource.doGet( req, resp );

        verify( resp ).setStatus( 200 );
        final JsonObject body = JsonParser.parseString( sw.toString() ).getAsJsonObject();
        assertEquals( "deploy", body.get( "query" ).getAsString() );
        assertEquals( 1, body.getAsJsonArray( "sections" ).size() );
        final JsonObject section = body.getAsJsonArray( "sections" ).get( 0 ).getAsJsonObject();
        assertEquals( "01DEP", section.get( "canonicalId" ).getAsString() );
        assertEquals( "Setup", section.getAsJsonArray( "headingPath" ).get( 0 ).getAsString() );
        final JsonObject citation = section.getAsJsonObject( "citation" );
        assertEquals( 7,       citation.get( "version" ).getAsInt() );
        assertEquals( "abc123", citation.get( "spanSha256" ).getAsString() );
    }
}

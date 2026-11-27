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
import com.wikantik.WikiEngine;
import com.wikantik.api.bundle.BundleAssemblyService;
import com.wikantik.api.bundle.RetrievalMode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The error envelopes of {@code /api/bundle}: status, content type and body of each refusal. */
class BundleResourceWireTest {

    private final StringWriter body = new StringWriter();
    private final HttpServletResponse resp = mock( HttpServletResponse.class );
    private final BundleAssemblyService svc = mock( BundleAssemblyService.class );

    private HttpServletRequest req( final String q, final String... more ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( "q" ) ).thenReturn( q );
        for ( int i = 0; i < more.length; i += 2 ) {
            when( req.getParameter( more[ i ] ) ).thenReturn( more[ i + 1 ] );
        }
        return req;
    }

    private BundleResource resource( final BundleAssemblyService service, final boolean admin ) {
        return new BundleResource() {
            @Override protected BundleAssemblyService bundleService() { return service; }
            @Override protected boolean isAdministrator( final HttpServletRequest r ) { return admin; }
        };
    }

    private JsonObject call( final BundleResource r, final HttpServletRequest req ) throws Exception {
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
        r.doGet( req, resp );
        return JsonParser.parseString( body.toString() ).getAsJsonObject();
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "   " } )
    void aBlankQueryIs400WithAJsonBody( final String q ) throws Exception {
        final JsonObject json = call( resource( svc, false ), req( q ) );

        verify( resp ).setStatus( 400 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "q (query) parameter required", json.get( "error" ).getAsString() );
        verify( svc, never() ).assemble( any( String.class ), any( RetrievalMode.class ) );
    }

    @Test
    void anUnknownModeIs400WithAJsonBodyAndNothingAssembles() throws Exception {
        final JsonObject json = call( resource( svc, false ), req( "x", "mode", "bogus" ) );

        verify( resp ).setStatus( 400 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertFalse( json.get( "error" ).getAsString().isBlank() );
        verify( svc, never() ).assemble( any( String.class ), any( RetrievalMode.class ) );
    }

    @Test
    void anUnwiredServiceIs503WithAJsonBody() throws Exception {
        final JsonObject json = call( resource( null, false ), req( "x" ) );

        verify( resp ).setStatus( 503 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "bundle assembly service unavailable", json.get( "error" ).getAsString() );
    }

    @Test
    void anAssemblyFailureIs500AndDoesNotEchoTheInternalMessage() throws Exception {
        when( svc.assemble( any(), any( RetrievalMode.class ) ) ).thenThrow( new IllegalStateException( "pg://internal-host" ) );

        final JsonObject json = call( resource( svc, false ), req( "x" ) );

        verify( resp ).setStatus( 500 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "bundle assembly failed", json.get( "error" ).getAsString() );
        assertFalse( body.toString().contains( "internal-host" ) );
    }

    @Test
    void debugRankingsWithoutAHybridSourceIs409WithAJsonBody() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        when( engine.bundleSectionSources() ).thenReturn( Map.of() );
        final BundleResource r = new BundleResource() {
            @Override protected com.wikantik.api.core.Engine getEngine() { return engine; }
            @Override protected boolean isAdministrator( final HttpServletRequest rq ) { return true; }
        };

        final JsonObject json = call( r, req( "x", "debug", "rankings" ) );

        verify( resp ).setStatus( 409 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
        assertEquals( "chunk-hybrid source not active (set wikantik.bundle.bm25.enabled=true)",
                json.get( "error" ).getAsString() );
    }

    @Test
    void debugRankingsIsRefusedToANonAdministratorBeforeTheSourceIsConsulted() throws Exception {
        final WikiEngine engine = mock( WikiEngine.class );
        final BundleResource r = new BundleResource() {
            @Override protected com.wikantik.api.core.Engine getEngine() { return engine; }
            @Override protected boolean isAdministrator( final HttpServletRequest rq ) { return false; }
        };

        call( r, req( "x", "debug", "rankings" ) );

        verify( resp ).setStatus( 403 );
        verify( engine, never() ).bundleSectionSources();
    }
}

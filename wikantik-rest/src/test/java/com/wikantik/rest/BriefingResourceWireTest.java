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
import com.wikantik.api.briefing.BriefingAssemblyService;
import com.wikantik.api.briefing.BriefingRequest;
import com.wikantik.api.briefing.ContextBriefing;
import com.wikantik.api.bundle.BundleCoverage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The request parsing and the wire envelope of {@code /api/briefing}: what a caller sends and the status it gets back. */
class BriefingResourceWireTest {

    private final BriefingAssemblyService svc = mock( BriefingAssemblyService.class );
    private final StringWriter body = new StringWriter();
    private final HttpServletResponse resp = mock( HttpServletResponse.class );

    private final BriefingResource resource = new BriefingResource() {
        @Override protected BriefingAssemblyService briefingService() { return svc; }
        @Override protected java.util.Set< String > filterViewable( final HttpServletRequest r,
                                                                    final java.util.Collection< String > names ) {
            return new java.util.HashSet<>( names );
        }
    };

    private HttpServletRequest request( final String key, final String value, final String... more ) {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getParameter( key ) ).thenReturn( value );
        for ( int i = 0; i < more.length; i += 2 ) {
            when( req.getParameter( more[ i ] ) ).thenReturn( more[ i + 1 ] );
        }
        return req;
    }

    private void call( final HttpServletRequest req ) throws Exception {
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );
        when( svc.assemble( any() ) ).thenReturn(
                new ContextBriefing( null, List.of(), BundleCoverage.empty(), List.of(), List.of(), 4000, 0 ) );
        resource.doGet( req, resp );
    }

    private BriefingRequest assembled() {
        final ArgumentCaptor< BriefingRequest > c = ArgumentCaptor.forClass( BriefingRequest.class );
        verify( svc ).assemble( c.capture() );
        return c.getValue();
    }

    private void assertRefused( final int status, final String message ) {
        verify( resp ).setStatus( status );
        final JsonObject json = JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( message, json.get( "message" ).getAsString() );
        verify( svc, never() ).assemble( any() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "abc", "1.5", "12x", "99999999999" } )
    void aBudgetThatIsNotAnIntIs400AndNothingAssembles( final String budget ) throws Exception {
        call( request( "pins", "A", "budget", budget ) );

        assertRefused( 400, "invalid budget" );
    }

    @Test
    void aPaddedBudgetIsTrimmedAndPassedThrough() throws Exception {
        call( request( "pins", "A", "budget", " 500 " ) );

        assertEquals( 500, assembled().budgetTokens() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "   " } )
    void aBlankBudgetMeansNoBudget( final String budget ) throws Exception {
        call( request( "pins", "A", "budget", budget ) );

        assertNull( assembled().budgetTokens() );
    }

    @ParameterizedTest
    @ValueSource( strings = { "xml", "JSON", "markdown", "html" } )
    void anUnknownFormatIs400AndNothingAssembles( final String format ) throws Exception {
        call( request( "pins", "A", "format", format ) );

        assertRefused( 400, "format must be 'json' or 'md'" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "", "  ", "json" } )
    void aBlankOrJsonFormatIsJson( final String format ) throws Exception {
        call( request( "pins", "A", "format", format ) );

        verify( resp ).setStatus( 200 );
        verify( resp ).setContentType( "application/json; charset=UTF-8" );
    }

    @Test
    void mdFormatIsMarkdownNotJson() throws Exception {
        call( request( "pins", "A", "format", "md" ) );

        verify( resp ).setContentType( "text/markdown; charset=UTF-8" );
        verify( resp, never() ).setContentType( "application/json; charset=UTF-8" );
    }

    @Test
    void csvParametersAreTrimmedAndEmptyTokensDropped() throws Exception {
        call( request( "pins", " A , ,B,,", "clusters", ",c1 , c2" ) );

        final BriefingRequest r = assembled();
        assertEquals( List.of( "A", "B" ), r.pins() );
        assertEquals( List.of( "c1", "c2" ), r.clusters() );
    }

    @ParameterizedTest
    @ValueSource( strings = { ",", " , ,", "", "  " } )
    void pinsThatSplitToNothingCountAsNoSource( final String pins ) throws Exception {
        call( request( "pins", pins ) );

        assertRefused( 400, "at least one of pins, clusters, prompt is required" );
    }

    @Test
    void aBlankPromptAloneIsNoSource() throws Exception {
        call( request( "prompt", "   " ) );

        assertRefused( 400, "at least one of pins, clusters, prompt is required" );
    }

    @Test
    void anAssemblyFailureIs500WithoutTheInternalMessage() throws Exception {
        when( svc.assemble( any() ) ).thenThrow( new IllegalStateException( "jdbc://secret-host down" ) );
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );

        resource.doGet( request( "pins", "A" ), resp );

        verify( resp ).setStatus( 500 );
        assertTrue( body.toString().contains( "briefing assembly failed" ) );
        assertTrue( !body.toString().contains( "secret-host" ), "the internal failure text leaked: " + body );
    }

    @Test
    void anUnwiredServiceIs503() throws Exception {
        final BriefingResource unwired = new BriefingResource() {
            @Override protected BriefingAssemblyService briefingService() { return null; }
        };
        when( resp.getWriter() ).thenReturn( new PrintWriter( body, true ) );

        unwired.doGet( request( "pins", "A" ), resp );

        verify( resp ).setStatus( 503 );
        assertTrue( body.toString().contains( "briefing assembly service unavailable" ) );
    }
}

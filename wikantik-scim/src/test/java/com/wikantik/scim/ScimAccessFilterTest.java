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
package com.wikantik.scim;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScimAccessFilterTest {

    private static final String PROP = "wikantik.scim.token";
    private String previousSystemToken;

    @BeforeEach
    void saveSystemToken() {
        previousSystemToken = System.getProperty( PROP );
        System.clearProperty( PROP );
    }

    @AfterEach
    void restoreSystemToken() {
        if ( previousSystemToken == null ) {
            System.clearProperty( PROP );
        } else {
            System.setProperty( PROP, previousSystemToken );
        }
    }

    @Test
    void validBearerPasses() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        HttpServletRequest req = mock( HttpServletRequest.class );
        HttpServletResponse resp = mock( HttpServletResponse.class );
        FilterChain chain = mock( FilterChain.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer secret-token" );
        f.doFilter( req, resp, chain );
        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( 401 );
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        ScimAccessFilter f = new ScimAccessFilter( "secret-token" );
        for ( String h : new String[]{ null, "Bearer nope", "secret-token" } ) {
            HttpServletRequest req = mock( HttpServletRequest.class );
            HttpServletResponse resp = mock( HttpServletResponse.class );
            FilterChain chain = mock( FilterChain.class );
            StringWriter sw = new StringWriter();
            when( req.getHeader( "Authorization" ) ).thenReturn( h );
            when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );
            f.doFilter( req, resp, chain );
            verify( chain, never() ).doFilter( any(), any() );
            verify( resp ).setStatus( 401 );
        }
    }

    @Test
    void initReadsTokenFromSystemProperty() throws Exception {
        System.setProperty( PROP, "sys-prop-token" );
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( null );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer sys-prop-token" );

        f.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
        verify( resp, never() ).setStatus( 401 );
    }

    @Test
    void initFallsBackToFilterConfigWhenNoSystemProperty() throws Exception {
        final FilterConfig cfg = mock( FilterConfig.class );
        when( cfg.getInitParameter( PROP ) ).thenReturn( "cfg-token" );
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( cfg );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer cfg-token" );

        f.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
    }

    @Test
    void initWithNoTokenAnywhereLeavesFilterClosed() throws Exception {
        final FilterConfig cfg = mock( FilterConfig.class );
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( cfg );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        final StringWriter sw = new StringWriter();
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer anything" );
        when( resp.getWriter() ).thenReturn( new PrintWriter( sw ) );

        f.doFilter( req, resp, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( 401 );
    }

    private int statusFor( final ScimAccessFilter f, final String header, final StringWriter body,
                           final HttpServletResponse resp ) throws Exception {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final FilterChain chain = mock( FilterChain.class );
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        when( req.getHeader( "Authorization" ) ).thenReturn( header );
        f.doFilter( req, resp, chain );
        try {
            verify( chain ).doFilter( req, resp );
            return 200;
        } catch ( final AssertionError notPassed ) {
            // the chain was not invoked: the request was refused
            org.mockito.ArgumentCaptor<Integer> c = org.mockito.ArgumentCaptor.forClass( Integer.class );
            verify( resp ).setStatus( c.capture() );
            return c.getValue();
        }
    }

    @Test
    void refusalIsAScimErrorDocumentWithTheScimMediaType() throws Exception {
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        assertEquals( 401, statusFor( new ScimAccessFilter( "tok" ), "Bearer wrong", body, resp ) );
        verify( resp ).setContentType( "application/scim+json" );
        final com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString( body.toString() ).getAsJsonObject();
        assertEquals( "401", json.get( "status" ).getAsString() );
        assertTrue( json.getAsJsonArray( "schemas" ).toString().contains( "scim:api:messages:2.0:Error" ) );
        assertEquals( "invalid or missing bearer token", json.get( "detail" ).getAsString() );
    }

    @Test
    void tokenComparisonIsExactNotPrefixOrCaseFolded() throws Exception {
        final ScimAccessFilter f = new ScimAccessFilter( "Secret" );
        for ( final String h : new String[]{ "Bearer secret", "Bearer Secre", "Bearer SecretX", "Bearer ", "bearer Secret",
                                             "Bearer  Secret", "Basic Secret" } ) {
            assertEquals( 401, statusFor( f, h, new StringWriter(), mock( HttpServletResponse.class ) ), h );
        }
        assertEquals( 200, statusFor( f, "Bearer Secret", new StringWriter(), mock( HttpServletResponse.class ) ) );
    }

    @Test
    void aBlankConfiguredTokenAdmitsNobodyNotEvenAnEmptyBearer() throws Exception {
        for ( final String blank : new String[]{ "", "   " } ) {
            final ScimAccessFilter f = new ScimAccessFilter( blank );
            assertEquals( 401, statusFor( f, "Bearer " + blank, new StringWriter(), mock( HttpServletResponse.class ) ), "[" + blank + "]" );
        }
    }

    @Test
    void aBlankSystemPropertyFallsThroughToTheFilterConfig() throws Exception {
        System.setProperty( PROP, "   " );
        final FilterConfig cfg = mock( FilterConfig.class );
        when( cfg.getInitParameter( PROP ) ).thenReturn( "from-config" );
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( cfg );
        assertEquals( 200, statusFor( f, "Bearer from-config", new StringWriter(), mock( HttpServletResponse.class ) ) );
        assertEquals( 401, statusFor( f, "Bearer    ", new StringWriter(), mock( HttpServletResponse.class ) ) );
    }

    @Test
    void aSystemPropertyWinsOverTheFilterConfig() throws Exception {
        System.setProperty( PROP, "from-property" );
        final FilterConfig cfg = mock( FilterConfig.class );
        when( cfg.getInitParameter( PROP ) ).thenReturn( "from-config" );
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( cfg );
        assertEquals( 200, statusFor( f, "Bearer from-property", new StringWriter(), mock( HttpServletResponse.class ) ) );
        assertEquals( 401, statusFor( f, "Bearer from-config", new StringWriter(), mock( HttpServletResponse.class ) ) );
        verify( cfg, never() ).getInitParameter( PROP );
    }

    @Test
    void initWithoutAFilterConfigOrPropertyStaysClosedWithoutThrowing() throws Exception {
        final ScimAccessFilter f = new ScimAccessFilter();
        f.init( null );
        assertEquals( 401, statusFor( f, "Bearer anything", new StringWriter(), mock( HttpServletResponse.class ) ) );
    }

    @Test
    void initKeepsATokenGivenToTheConstructor() throws Exception {
        System.setProperty( PROP, "other" );
        final ScimAccessFilter f = new ScimAccessFilter( "ctor" );
        f.init( null );
        assertEquals( 200, statusFor( f, "Bearer ctor", new StringWriter(), mock( HttpServletResponse.class ) ) );
        assertEquals( 401, statusFor( f, "Bearer other", new StringWriter(), mock( HttpServletResponse.class ) ) );
    }
}

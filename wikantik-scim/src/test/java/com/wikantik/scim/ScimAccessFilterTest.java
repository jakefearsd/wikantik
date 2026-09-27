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
}

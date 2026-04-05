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

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link BuildVersionFilter} — verifies the X-Build-Version header is
 * set from build-version.txt, and gracefully handles missing version files.
 */
class BuildVersionFilterTest {

    @Test
    void testAddsVersionHeaderWhenFileExists() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( "1712345678901" );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Build-Version", "1712345678901" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testNoHeaderWhenVersionFileMissing() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( null );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response, never() ).setHeader( eq( "X-Build-Version" ), anyString() );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testVersionIsTrimmed() throws Exception {
        final BuildVersionFilter filter = new BuildVersionFilter();
        final FilterConfig config = mockConfigWithVersion( "  1712345678901\n" );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Build-Version", "1712345678901" );
    }

    private FilterConfig mockConfigWithVersion( final String version ) {
        final ServletContext context = mock( ServletContext.class );
        if ( version != null ) {
            final InputStream stream = new ByteArrayInputStream( version.getBytes( StandardCharsets.UTF_8 ) );
            when( context.getResourceAsStream( "/build-version.txt" ) ).thenReturn( stream );
        } else {
            when( context.getResourceAsStream( "/build-version.txt" ) ).thenReturn( null );
        }
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getServletContext() ).thenReturn( context );
        return config;
    }
}

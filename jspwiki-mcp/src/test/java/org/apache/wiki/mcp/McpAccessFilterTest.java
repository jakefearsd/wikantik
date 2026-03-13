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
package org.apache.wiki.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class McpAccessFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private McpAccessFilter createFilter( final String key, final String cidrs ) {
        final Properties props = new Properties();
        if ( key != null ) {
            props.setProperty( "mcp.access.key", key );
        }
        if ( cidrs != null ) {
            props.setProperty( "mcp.access.allowedCidrs", cidrs );
        }
        return new McpAccessFilter( new McpConfig( props ) );
    }

    @Test
    void testCorrectApiKeyPasses() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer secret123" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testWrongApiKeyBlocked() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong-key" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    @Test
    void testMissingBearerPrefixBlocked() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "secret123" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    @Test
    void testIpInCidrPasses() throws Exception {
        final McpAccessFilter filter = createFilter( null, "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIpOutsideCidrBlocked() throws Exception {
        final McpAccessFilter filter = createFilter( null, "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    @Test
    void testWrongKeyButAllowedIpPasses() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", "10.0.0.0/8" );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong-key" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testRightKeyButBlockedIpPasses() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", "10.0.0.0/8" );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer secret123" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testBothUnconfiguredAllowsAllTraffic() throws Exception {
        final McpAccessFilter filter = createFilter( null, null );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIpv6CidrMatching() throws Exception {
        final McpAccessFilter filter = createFilter( null, "::1/128" );
        when( request.getRemoteAddr() ).thenReturn( "::1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testMalformedCidrSkippedValidStillWorks() throws Exception {
        final McpAccessFilter filter = createFilter( null, "not-a-cidr, 10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testDenyResponseIsJsonWithCorrectContentType() throws Exception {
        final McpAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( response ).setContentType( "application/json" );
        assertEquals( "{\"error\":\"Access denied\"}", body.toString() );
    }
}

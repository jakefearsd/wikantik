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
package com.wikantik.http.filter;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsrfProtectionFilterTest {

    @Test
    void testIsPostReturnsTrueForPost() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "POST" ).when( request ).getMethod();
        assertTrue( CsrfProtectionFilter.isPost( request ) );
    }

    @Test
    void testIsPostReturnsFalseForGet() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "GET" ).when( request ).getMethod();
        assertFalse( CsrfProtectionFilter.isPost( request ) );
    }

    @Test
    void testIsPostReturnsFalseForPut() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "PUT" ).when( request ).getMethod();
        assertFalse( CsrfProtectionFilter.isPost( request ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "POST", "PUT", "DELETE", "PATCH", "post", "put", "delete", "patch" } )
    void testIsStateChangingRecognizesAllMutatingMethods( final String method ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( method ).when( request ).getMethod();
        assertTrue( CsrfProtectionFilter.isStateChanging( request ),
                    method + " must be treated as a state-changing method" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "GET", "HEAD", "OPTIONS", "TRACE" } )
    void testIsStateChangingRejectsSafeMethods( final String method ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( method ).when( request ).getMethod();
        assertFalse( CsrfProtectionFilter.isStateChanging( request ),
                     method + " must not be treated as state-changing" );
    }

    @Test
    void testIsOriginAllowedWhenHeaderAbsent() {
        // Server-to-server / curl has no Origin header — allow through.
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( request ).getHeader( "Origin" );
        assertTrue( CsrfProtectionFilter.isOriginAllowed( request, "https://wiki.example.com" ) );
    }

    @Test
    void testIsOriginAllowedWhenHeaderMatches() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "https://wiki.example.com" ).when( request ).getHeader( "Origin" );
        assertTrue( CsrfProtectionFilter.isOriginAllowed( request, "https://wiki.example.com,https://other.example.com" ) );
    }

    @Test
    void testIsOriginAllowedWhenHeaderMismatches() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "https://evil.example.com" ).when( request ).getHeader( "Origin" );
        assertFalse( CsrfProtectionFilter.isOriginAllowed( request, "https://wiki.example.com" ) );
    }

    @Test
    void testIsOriginAllowedWithEmptyWhitelistRejectsCrossOrigin() {
        // No whitelist configured — any browser-originating request is suspicious.
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "https://evil.example.com" ).when( request ).getHeader( "Origin" );
        Mockito.doReturn( "https" ).when( request ).getScheme();
        Mockito.doReturn( "wiki.example.com" ).when( request ).getHeader( "Host" );
        assertFalse( CsrfProtectionFilter.isOriginAllowed( request, "" ) );
    }

    @Test
    void testIsOriginAllowedForSameOriginLocalhost() {
        // Regression for "Rejected state-changing request from Origin 'http://localhost:8080'":
        // browsers send Origin on same-origin state-changing requests, and same-origin must
        // always be allowed regardless of the whitelist (even when the whitelist is empty).
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "http://localhost:8080" ).when( request ).getHeader( "Origin" );
        Mockito.doReturn( "http" ).when( request ).getScheme();
        Mockito.doReturn( "localhost:8080" ).when( request ).getHeader( "Host" );

        assertTrue( CsrfProtectionFilter.isOriginAllowed( request, "" ),
                "Same-origin request must be allowed even with an empty whitelist" );
    }

    @Test
    void testIsOriginAllowedForSameOriginProductionHost() {
        // Same-origin over HTTPS against the public host with a non-empty whitelist
        // that does NOT mention the public host: must still be allowed.
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "https://wiki.example.com" ).when( request ).getHeader( "Origin" );
        Mockito.doReturn( "https" ).when( request ).getScheme();
        Mockito.doReturn( "wiki.example.com" ).when( request ).getHeader( "Host" );

        assertTrue( CsrfProtectionFilter.isOriginAllowed( request, "https://other.example.com" ) );
    }

    @Test
    void testIsOriginAllowedRejectsCrossOriginMatchingHostPrefix() {
        // Naive substring / prefix matching would allow https://localhost:8080.evil.com —
        // verify the same-origin path uses exact scheme+host comparison.
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "http://localhost:8080.evil.com" ).when( request ).getHeader( "Origin" );
        Mockito.doReturn( "http" ).when( request ).getScheme();
        Mockito.doReturn( "localhost:8080" ).when( request ).getHeader( "Host" );

        assertFalse( CsrfProtectionFilter.isOriginAllowed( request, "" ),
                "Host prefix that happens to start with the server host must not be treated as same-origin" );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/wikantik-admin-mcp", "/knowledge-mcp" } )
    void testIsMcpEndpointReturnsTrue( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isMcpEndpoint( request ) );
    }

    @Test
    void testIsMcpEndpointReturnsFalseForOther() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "/api/auth" ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isMcpEndpoint( request ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/api/pages", "/api/auth/login", "/api/search", "/api/history/Main", "/api/backlinks/Main" } )
    void testIsRestApiEndpointReturnsTrue( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    @ParameterizedTest
    @ValueSource( strings = { "/wikantik-admin-mcp", "/wiki/Main", "/login", "/edit/Main" } )
    void testIsRestApiEndpointReturnsFalseForOther( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    @Test
    void testIsRestApiEndpointReturnsFalseForNull() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( request ).getServletPath();
        assertFalse( CsrfProtectionFilter.isRestApiEndpoint( request ) );
    }

    /**
     * Admin endpoints (under /admin/) are exempt from CSRF token checks because they
     * use JSON Content-Type which provides natural CSRF protection via CORS preflight.
     */
    @ParameterizedTest
    @ValueSource( strings = { "/admin/groups", "/admin/policy", "/admin/users", "/admin/content" } )
    void testIsRestApiEndpointReturnsTrueForAdminPaths( final String servletPath ) {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( servletPath ).when( request ).getServletPath();
        assertTrue( CsrfProtectionFilter.isRestApiEndpoint( request ),
                "Admin path '" + servletPath + "' should be recognized as a REST API endpoint" );
    }

    // D3: Bearer-token requests are CSRF-exempt — Authorization headers cannot be forged
    // by browsers cross-origin, so the token itself is the proof of intent. Without this
    // exemption, /tools/* POSTs from API-key clients hit the synchronizer-token branch
    // and get 302-redirected to /error/Forbidden.html.
    @Test
    void testHasBearerAuthRecognizesBearerToken() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "Bearer abc123" ).when( request ).getHeader( "Authorization" );
        assertTrue( CsrfProtectionFilter.hasBearerAuth( request ) );
    }

    @Test
    void testHasBearerAuthRecognizesLowercaseBearer() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "bearer abc123" ).when( request ).getHeader( "Authorization" );
        assertTrue( CsrfProtectionFilter.hasBearerAuth( request ) );
    }

    @Test
    void testHasBearerAuthIgnoresBasicAuth() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "Basic Zm9vOmJhcg==" ).when( request ).getHeader( "Authorization" );
        assertFalse( CsrfProtectionFilter.hasBearerAuth( request ) );
    }

    @Test
    void testHasBearerAuthFalseWhenAbsent() {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( request ).getHeader( "Authorization" );
        assertFalse( CsrfProtectionFilter.hasBearerAuth( request ) );
    }

    /**
     * D3 end-to-end: a POST to /tools/search_wiki carrying a Bearer token must pass through
     * the filter without a CSRF token and without being redirected.
     */
    @Test
    void testBearerAuthPostToolsEndpointPassesThrough() throws Exception {
        final HttpServletRequest request = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "POST" ).when( request ).getMethod();
        Mockito.doReturn( "/tools/search_wiki" ).when( request ).getServletPath();
        Mockito.doReturn( "Bearer abc123" ).when( request ).getHeader( "Authorization" );

        final jakarta.servlet.http.HttpServletResponse response = Mockito.mock( jakarta.servlet.http.HttpServletResponse.class );
        final jakarta.servlet.FilterChain chain = Mockito.mock( jakarta.servlet.FilterChain.class );

        new CsrfProtectionFilter().doFilter( request, response, chain );

        Mockito.verify( chain ).doFilter( request, response );
        Mockito.verify( response, Mockito.never() ).sendRedirect( Mockito.anyString() );
    }
}

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
package com.wikantik.mcp;

import com.wikantik.http.ratelimit.SlidingWindowRateLimiter;
import com.wikantik.auth.apikeys.ApiKeyPrincipalRequest;
import com.wikantik.auth.apikeys.ApiKeyService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers all three {@link McpAccessFilter} constructor overloads (each delegates to
 * {@code surfaceFor(scope)} then the shared {@code AbstractApiAccessFilter} super
 * constructor) plus the fail-closed / CIDR / DB-key authorization paths that exercise
 * them end to end.
 */
class McpAccessFilterTest {

    private final HttpServletRequest request = mock( HttpServletRequest.class );
    private final HttpServletResponse response = mock( HttpServletResponse.class );
    private final FilterChain chain = mock( FilterChain.class );

    private McpAccessFilter createCidrFilter( final String cidrs ) {
        final Properties props = new Properties();
        if ( cidrs != null ) {
            props.setProperty( "mcp.access.allowedCidrs", cidrs );
        }
        // 2-arg constructor overload.
        return new McpAccessFilter( new McpConfig( props ), new SlidingWindowRateLimiter( 0, 0 ) );
    }

    @Test
    void ipInCidrPasses() throws Exception {
        final McpAccessFilter filter = createCidrFilter( "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void bothUnconfiguredFailsClosedWith503() throws Exception {
        final McpAccessFilter filter = createCidrFilter( null );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( response ).setHeader( "Retry-After", "86400" );
        assertTrue( body.toString().contains( "mcp_access_unconfigured" ),
                "Expected error body to mention mcp_access_unconfigured, got: " + body );
    }

    // ----- DB-backed API key path (3-arg and 4-arg constructor overloads) -----

    private ApiKeyService.Record dbRecord( final int id, final String principal,
                                           final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record(
                id, "hash-" + id, principal, "label", scope,
                Instant.now(), "admin", null, null, null );
    }

    @Test
    void dbKeyVerifiedAndInstallsPrincipalOnRequest() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final ApiKeyService.Record record = dbRecord( 1, "alice", ApiKeyService.Scope.MCP );
        when( svc.verify( "wkk_good" ) ).thenReturn( Optional.of( record ) );
        // 3-arg constructor overload (defaults requiredScope to MCP).
        final McpAccessFilter filter = new McpAccessFilter(
                new McpConfig( new Properties() ), new SlidingWindowRateLimiter( 0, 0 ), svc );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_good" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( any( HttpServletRequest.class ), eq( response ) );
        verify( request ).setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, record );
    }

    @Test
    void dbKeyOutsideRequiredScopeGets403() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_read_only" ) )
                .thenReturn( Optional.of( dbRecord( 3, "carol", ApiKeyService.Scope.MCP_READ ) ) );
        // 4-arg constructor overload with an explicit non-default requiredScope.
        final McpAccessFilter filter = new McpAccessFilter(
                new McpConfig( new Properties() ), new SlidingWindowRateLimiter( 0, 0 ), svc,
                ApiKeyService.Scope.MCP );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_read_only" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        assertTrue( body.toString().contains( "Key not authorized for MCP" ),
                "403 body should explain the scope mismatch: " + body );
    }

    // ----- authorize() direct path (sealed Outcome) -----

    @Test
    void authorizeReturnsDeniedOutcomeDirectly() {
        final McpAccessFilter filter = createCidrFilter( null );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );

        final McpAccessFilter.Outcome outcome = filter.authorize( request );

        assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome );
        assertEquals( HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                ( ( McpAccessFilter.Outcome.Denied ) outcome ).status() );
    }
}

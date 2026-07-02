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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class McpAccessFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private McpAccessFilter createCidrFilter( final String cidrs ) {
        final Properties props = new Properties();
        if ( cidrs != null ) {
            props.setProperty( "mcp.access.allowedCidrs", cidrs );
        }
        return new McpAccessFilter( new McpConfig( props ), new SlidingWindowRateLimiter( 0, 0 ) );
    }

    @Test
    void testIpInCidrPasses() throws Exception {
        final McpAccessFilter filter = createCidrFilter( "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIpOutsideCidrBlocked() throws Exception {
        final McpAccessFilter filter = createCidrFilter( "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    @Test
    void testBothUnconfiguredFailsClosed() throws Exception {
        final McpAccessFilter filter = createCidrFilter( null );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( response ).setContentType( "application/json" );
        assertTrue( body.toString().contains( "mcp_access_unconfigured" ),
                "Expected error body to mention mcp_access_unconfigured, got: " + body );
    }

    @Test
    void testUnrestrictedModeAllowedWithExplicitOptIn() throws Exception {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        final McpAccessFilter filter = new McpAccessFilter(
                new McpConfig( props ), new SlidingWindowRateLimiter( 0, 0 ) );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testIpv6CidrMatching() throws Exception {
        final McpAccessFilter filter = createCidrFilter( "::1/128" );
        when( request.getRemoteAddr() ).thenReturn( "::1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testMalformedCidrSkippedValidStillWorks() throws Exception {
        final McpAccessFilter filter = createCidrFilter( "not-a-cidr, 10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    // --- Rate limiting tests ---

    @Test
    void testRateLimitExceededReturns429() throws Exception {
        final SlidingWindowRateLimiter mockLimiter = mock( SlidingWindowRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        // Use CIDR allowlist so the filter is not fail-closed
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowedCidrs", "10.0.0.0/8" );
        final McpAccessFilter filter = new McpAccessFilter( new McpConfig( props ), mockLimiter );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( 429 );
        verify( response ).setHeader( "Retry-After", "1" );
        verify( response ).setContentType( "application/json" );
        assertEquals( "{\"error\":\"Rate limit exceeded\"}", body.toString() );
    }

    @Test
    void testRateLimitExceededIncludesRetryAfterHeader() throws Exception {
        final SlidingWindowRateLimiter mockLimiter = mock( SlidingWindowRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        // Unrestricted mode (explicitly opted-in) — no auth needed, just rate limit
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        final McpAccessFilter filter = new McpAccessFilter( new McpConfig( props ), mockLimiter );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "Retry-After", "1" );
    }

    @Test
    void testRateLimitCheckedInUnrestrictedMode() throws Exception {
        final SlidingWindowRateLimiter mockLimiter = mock( SlidingWindowRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        // No CIDR, explicit unrestricted opt-in → allowed, but rate limiter still applies
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        final McpAccessFilter filter = new McpAccessFilter( new McpConfig( props ), mockLimiter );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( 429 );
        verify( response ).setHeader( "Retry-After", "1" );
        verify( chain, never() ).doFilter( request, response );
    }

    // ----- DB-backed API key path -----

    private ApiKeyService.Record dbRecord( final int id, final String principal,
                                           final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record(
                id, "hash-" + id, principal, "label", scope,
                Instant.now(), "admin", null, null, null );
    }

    private McpAccessFilter createFilterWithDbService( final ApiKeyService svc,
                                                       final Properties extraProps ) {
        final Properties props = extraProps != null ? extraProps : new Properties();
        return new McpAccessFilter( new McpConfig( props ), new SlidingWindowRateLimiter( 0, 0 ), svc );
    }

    @Test
    void dbKeyVerifiedAndInstallsPrincipalOnRequest() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final ApiKeyService.Record record = dbRecord( 1, "alice", ApiKeyService.Scope.MCP );
        when( svc.verify( "wkk_good" ) ).thenReturn( Optional.of( record ) );
        final McpAccessFilter filter = createFilterWithDbService( svc, null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_good" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        final ArgumentCaptor< HttpServletRequest > captor = ArgumentCaptor.forClass( HttpServletRequest.class );
        verify( chain ).doFilter( captor.capture(), eq( response ) );
        assertInstanceOf( ApiKeyPrincipalRequest.class, captor.getValue(),
                "Filter must wrap the request so downstream JAAS/ACL checks run as the key's principal" );
        assertEquals( "alice", captor.getValue().getUserPrincipal().getName() );
        verify( request ).setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, record );
    }

    @Test
    void dbKeyWithAllScopeAlsoMatchesMcp() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_all" ) )
                .thenReturn( Optional.of( dbRecord( 2, "bob", ApiKeyService.Scope.ALL ) ) );
        final McpAccessFilter filter = createFilterWithDbService( svc, null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_all" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( any( HttpServletRequest.class ), eq( response ) );
    }

    @Test
    void dbKeyWithToolsScopeRejectedForMcpWith403() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_tools" ) )
                .thenReturn( Optional.of( dbRecord( 3, "carol", ApiKeyService.Scope.TOOLS ) ) );
        final McpAccessFilter filter = createFilterWithDbService( svc, null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_tools" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        assertTrue( body.toString().toLowerCase().contains( "not authorized" )
                        || body.toString().toLowerCase().contains( "mcp" ),
                "403 body should explain the scope mismatch: " + body );
    }

    @Test
    void dbKeyServiceAloneSatisfiesFailClosedGate() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( anyString() ) ).thenReturn( Optional.empty() );
        final McpAccessFilter filter = createFilterWithDbService( svc, null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_wrong" );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( response, never() ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
    }

    @Test
    void dbServicePresentWithAllowUnrestrictedDoesNotBlockUnauthenticatedRequests() throws Exception {
        // Regression: when an operator wires up a datasource (so ApiKeyService
        // is non-null) but has not configured CIDRs and has explicitly set
        // mcp.access.allowUnrestricted=true, the filter must run open. Otherwise
        // IT environments that boot with a datasource but no minted keys fail
        // every MCP call with 403.
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        final McpAccessFilter filter = createFilterWithDbService( svc, props );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( svc, never() ).verify( anyString() );
    }

    // ----- authorize() direct path (sealed Outcome) -----

    // Covers McpAccessFilter — DB key whose Scope does not match MCP
    // yields Outcome.Denied(403, "Key not authorized for MCP").
    @Test
    void dbKeyOutsideMcpScopeGets403() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_tools_only" ) )
                .thenReturn( Optional.of( dbRecord( 7, "carol", ApiKeyService.Scope.TOOLS ) ) );
        final McpAccessFilter filter = createFilterWithDbService( svc, null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_tools_only" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final McpAccessFilter.Outcome outcome = filter.authorize( request );

        assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome );
        final McpAccessFilter.Outcome.Denied d = ( McpAccessFilter.Outcome.Denied ) outcome;
        assertEquals( HttpServletResponse.SC_FORBIDDEN, d.status() );
        assertTrue( d.body().toLowerCase().contains( "not authorized for mcp" ),
                "deny body should explain the scope mismatch: " + d.body() );
    }

    // Covers McpAccessFilter — when InetAddress.getByName throws
    // UnknownHostException, checkIp must log+deny rather than propagate.
    @Test
    void unresolvableRemoteAddrLogsAndDenies() {
        final McpAccessFilter filter = createCidrFilter( "10.0.0.0/8" );
        // A string with whitespace cannot be parsed as an IP literal nor
        // resolved as a hostname → InetAddress.getByName throws.
        when( request.getRemoteAddr() ).thenReturn( "not a host" );

        final McpAccessFilter.Outcome outcome = filter.authorize( request );

        assertInstanceOf( McpAccessFilter.Outcome.Denied.class, outcome );
        assertEquals( HttpServletResponse.SC_FORBIDDEN,
                ( ( McpAccessFilter.Outcome.Denied ) outcome ).status() );
    }

    @Test
    void failClosedReturns503WithJsonBodyAndRetryAfter() throws Exception {
        final Properties p = new Properties();
        // No CIDRs, no DB service, no allowUnrestricted → fail-closed
        final McpConfig config = new McpConfig( p );
        final SlidingWindowRateLimiter rl = new SlidingWindowRateLimiter( 0, 0 );
        final McpAccessFilter filter = new McpAccessFilter( config, rl );

        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
        final FilterChain chain = Mockito.mock( FilterChain.class );

        filter.doFilter( req, resp, chain );

        Mockito.verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        Mockito.verify( resp ).setContentType( "application/json" );
        Mockito.verify( resp ).setHeader( "Retry-After", "86400" );
        Mockito.verifyNoInteractions( chain );

        final String text = body.toString();
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains( "\"error\":\"mcp_access_unconfigured\"" ), text );
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains( "/admin/apikeys" ),
                "Body should name the DB key minting path: " + text );
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains( "mcp.access.allowUnrestricted" ), text );
    }
}

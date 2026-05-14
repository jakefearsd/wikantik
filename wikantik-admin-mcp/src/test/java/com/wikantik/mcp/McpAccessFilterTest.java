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

    private McpAccessFilter createFilter( final String key, final String cidrs ) {
        final Properties props = new Properties();
        if ( key != null ) {
            props.setProperty( "mcp.access.keys", key );
        }
        if ( cidrs != null ) {
            props.setProperty( "mcp.access.allowedCidrs", cidrs );
        }
        return new McpAccessFilter( new McpConfig( props ), new McpRateLimiter( 0, 0 ) );
    }

    private McpAccessFilter createFilterMultiKey( final String keys, final String cidrs ) {
        final Properties props = new Properties();
        if ( keys != null ) {
            props.setProperty( "mcp.access.keys", keys );
        }
        if ( cidrs != null ) {
            props.setProperty( "mcp.access.allowedCidrs", cidrs );
        }
        return new McpAccessFilter( new McpConfig( props ), new McpRateLimiter( 0, 0 ) );
    }

    private McpAccessFilter createFilterWithRateLimiter( final String key, final McpRateLimiter rateLimiter ) {
        final Properties props = new Properties();
        if ( key != null ) {
            props.setProperty( "mcp.access.keys", key );
        }
        return new McpAccessFilter( new McpConfig( props ), rateLimiter );
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
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
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
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
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
    void testBothUnconfiguredFailsClosed() throws Exception {
        final McpAccessFilter filter = createFilter( null, null );
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
                new McpConfig( props ), new McpRateLimiter( 0, 0 ) );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );

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
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( response ).setContentType( "application/json" );
        assertEquals( "{\"error\":\"Access denied\"}", body.toString() );
    }

    // --- Multi-key tests ---

    @Test
    void testMultipleKeysFirstKeyPasses() throws Exception {
        final McpAccessFilter filter = createFilterMultiKey( "key-alpha, key-beta, key-gamma", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer key-alpha" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testMultipleKeysSecondKeyPasses() throws Exception {
        final McpAccessFilter filter = createFilterMultiKey( "key-alpha, key-beta, key-gamma", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer key-beta" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testMultipleKeysThirdKeyPasses() throws Exception {
        final McpAccessFilter filter = createFilterMultiKey( "key-alpha, key-beta, key-gamma", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer key-gamma" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void testMultipleKeysWrongKeyBlocked() throws Exception {
        final McpAccessFilter filter = createFilterMultiKey( "key-alpha, key-beta", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong-key" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    // --- Rate limiting tests ---

    @Test
    void testRateLimitExceededReturns429() throws Exception {
        final McpRateLimiter mockLimiter = mock( McpRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        final McpAccessFilter filter = createFilterWithRateLimiter( "secret123", mockLimiter );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer secret123" );
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
        final McpRateLimiter mockLimiter = mock( McpRateLimiter.class );
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
    void testRateLimitNotCheckedWhenAuthFails() throws Exception {
        final McpRateLimiter mockLimiter = mock( McpRateLimiter.class );
        final McpAccessFilter filter = createFilterWithRateLimiter( "secret123", mockLimiter );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( mockLimiter, never() ).tryAcquire( anyString() );
    }

    @Test
    void testRateLimitCheckedInUnrestrictedMode() throws Exception {
        final McpRateLimiter mockLimiter = mock( McpRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        // No key, no CIDR, explicit unrestricted opt-in → allowed, but rate limiter still applies
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
        return new McpAccessFilter( new McpConfig( props ), new McpRateLimiter( 0, 0 ), svc );
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
    void unknownDbKeyFallsThroughToLegacyKeyList() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( anyString() ) ).thenReturn( Optional.empty() );

        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "legacy-key" );
        final McpAccessFilter filter = createFilterWithDbService( svc, props );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer legacy-key" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
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
        // is non-null) but has not configured legacy keys or CIDRs and has
        // explicitly set mcp.access.allowUnrestricted=true, the filter must
        // run open. Otherwise IT environments that boot with a datasource but
        // no minted keys fail every MCP call with 403.
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowUnrestricted", "true" );
        final McpAccessFilter filter = createFilterWithDbService( svc, props );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( svc, never() ).verify( anyString() );
    }

    @Test
    void missingBearerHeaderIsNotVerifiedAgainstDbService() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "legacy-key" );
        final McpAccessFilter filter = createFilterWithDbService( svc, props );
        when( request.getHeader( "Authorization" ) ).thenReturn( null );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( svc, never() ).verify( anyString() );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    // ----- authorize() direct path (sealed Outcome) -----

    // Covers McpAccessFilter.java:188-194 — DB key whose Scope does not match
    // MCP yields Outcome.Denied(403, "Key not authorized for MCP").
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

    // Covers McpAccessFilter.java:186-203 — when apiKeyService.verify returns
    // Optional.empty(), control falls through to the legacy-key code path.
    @Test
    void dbKeyRejectionFallsThroughToLegacyKey() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "legacy-key" ) ).thenReturn( Optional.empty() );

        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "legacy-key" );
        final McpAccessFilter filter = createFilterWithDbService( svc, props );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer legacy-key" );

        final McpAccessFilter.Outcome outcome = filter.authorize( request );

        assertInstanceOf( McpAccessFilter.Outcome.Allowed.class, outcome,
                "fall-through to legacy key list must produce Allowed" );
        final McpAccessFilter.Outcome.Allowed a = ( McpAccessFilter.Outcome.Allowed ) outcome;
        assertTrue( a.clientId().startsWith( "legacy:" ),
                "clientId should mark the legacy match path: " + a.clientId() );
        // The DB service was still consulted before fall-through.
        verify( svc ).verify( "legacy-key" );
    }

    // Covers McpAccessFilter.java:262-268 — when InetAddress.getByName throws
    // UnknownHostException, checkIp must log+deny rather than propagate.
    @Test
    void unresolvableRemoteAddrLogsAndDenies() {
        final McpAccessFilter filter = createFilter( null, "10.0.0.0/8" );
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
        // No keys, no CIDRs, no allowUnrestricted → fail-closed
        final McpConfig config = new McpConfig( p );
        final McpRateLimiter rl = new McpRateLimiter( 0, 0 );
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
                text.contains( "mcp.access.keys" ), "Body should name the config keys: " + text );
        org.junit.jupiter.api.Assertions.assertTrue(
                text.contains( "mcp.access.allowUnrestricted" ), text );
    }
}

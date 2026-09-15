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
package com.wikantik.auth.apikeys;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Direct unit coverage for {@link AbstractApiAccessFilter} itself.
 *
 * <p>The class's only prior exercise came from thin subclass test suites living in
 * wikantik-admin-mcp ({@code McpAccessFilterTest}, {@code McpCidrIPv6Test}) and
 * wikantik-tools ({@code ToolsAccessFilterTest}) — since JaCoCo reports per-module,
 * none of that attributed coverage back to wikantik-main, where the class actually
 * lives. This test subclasses {@link AbstractApiAccessFilter} directly with a minimal
 * test {@link AbstractApiAccessFilter.Surface} so the module's own report sees it.</p>
 */
class AbstractApiAccessFilterTest {

    /** Minimal concrete filter — mirrors how McpAccessFilter/ToolsAccessFilter bind Surface. */
    private static final class TestAccessFilter extends AbstractApiAccessFilter {
        static Surface surface( final ApiKeyService.Scope requiredScope ) {
            return new Surface(
                    "Test",
                    "test.access",
                    requiredScope,
                    Outcome.Denied.of( HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "{\"error\":\"test_access_unconfigured\"}",
                            new Outcome.Header( "Retry-After", "42" ) ),
                    "{\"error\":\"Key not authorized for Test\"}" );
        }

        TestAccessFilter( final String allowedCidrs, final boolean allowUnrestricted,
                          final Predicate< String > rateLimiter, final ApiKeyService apiKeyService ) {
            this( allowedCidrs, allowUnrestricted, rateLimiter, apiKeyService, ApiKeyService.Scope.MCP );
        }

        TestAccessFilter( final String allowedCidrs, final boolean allowUnrestricted,
                          final Predicate< String > rateLimiter, final ApiKeyService apiKeyService,
                          final ApiKeyService.Scope requiredScope ) {
            super( surface( requiredScope ), allowedCidrs, allowUnrestricted, rateLimiter, apiKeyService );
        }
    }

    private static final Predicate< String > ALWAYS_ALLOW = s -> true;

    private ApiKeyService.Record record( final int id, final String principal, final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record( id, "hash-" + id, principal, "label", scope,
                Instant.now(), "admin", null, null, null );
    }

    // --- bearer-token accept / reject -----------------------------------------------

    @Test
    void validBearerTokenAllowsAndWrapsPrincipal() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_good" ) ).thenReturn( Optional.of( record( 1, "alice", ApiKeyService.Scope.MCP ) ) );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_good" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, outcome );
        final var allowed = ( AbstractApiAccessFilter.Outcome.Allowed ) outcome;
        assertEquals( "key:1", allowed.clientId() );
        assertInstanceOf( ApiKeyPrincipalRequest.class, allowed.effectiveReq() );
        assertEquals( "alice", allowed.effectiveReq().getUserPrincipal().getName() );
        verify( req ).setAttribute( eq( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD ), any() );
    }

    @Test
    void missingAuthorizationHeaderFallsThroughToIpCheckAndDenies() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( null );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, ( ( AbstractApiAccessFilter.Outcome.Denied ) outcome ).status() );
        verify( svc, never() ).verify( anyString() );
    }

    @Test
    void nonBearerAuthorizationHeaderIsIgnored() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Basic dXNlcjpwYXNz" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
        verify( svc, never() ).verify( anyString() );
    }

    @Test
    void revokedOrUnknownKeyIsDenied() {
        // ApiKeyService.verify() is the sole authority on "is this key currently valid" —
        // a revoked key simply verifies to empty, which the filter must treat as no match.
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_revoked" ) ).thenReturn( Optional.empty() );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_revoked" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, ( ( AbstractApiAccessFilter.Outcome.Denied ) outcome ).status() );
    }

    // --- rank-based scope check -------------------------------------------------------

    @Test
    void lowerRankKeyDeniedForHigherRankRequiredScope() {
        // mcp_read (rank 1) must not satisfy a surface requiring mcp (rank 3).
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_read" ) ).thenReturn( Optional.of( record( 2, "reader", ApiKeyService.Scope.MCP_READ ) ) );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc, ApiKeyService.Scope.MCP );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_read" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
        final var denied = ( AbstractApiAccessFilter.Outcome.Denied ) outcome;
        assertEquals( HttpServletResponse.SC_FORBIDDEN, denied.status() );
        assertTrue( denied.body().contains( "not authorized for Test" ), denied.body() );
    }

    @Test
    void higherRankKeySatisfiesLowerRankRequiredScope() {
        // mcp (rank 3) satisfies a surface requiring mcp_read (rank 1).
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_full" ) ).thenReturn( Optional.of( record( 3, "admin-key", ApiKeyService.Scope.MCP ) ) );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc, ApiKeyService.Scope.MCP_READ );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_full" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, outcome );
    }

    @Test
    void allScopeKeyMatchesAnyRequiredScope() {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_all" ) ).thenReturn( Optional.of( record( 4, "super", ApiKeyService.Scope.ALL ) ) );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc, ApiKeyService.Scope.MCP );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_all" );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, filter.authorize( req ) );
    }

    // --- CIDR allowlist: IPv4 + IPv6 ---------------------------------------------------

    @Test
    void ipv4WithinCidrAllowlistIsAllowed() {
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "10.5.5.5" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, filter.authorize( req ) );
    }

    @Test
    void ipv4OutsideCidrAllowlistIsDenied() {
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( req ) );
    }

    @Test
    void ipv6WithinCidrAllowlistIsAllowed() {
        final TestAccessFilter filter = new TestAccessFilter( "2001:db8::/32", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "2001:db8::1" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, filter.authorize( req ) );
    }

    @Test
    void ipv6OutsideCidrAllowlistIsDenied() {
        final TestAccessFilter filter = new TestAccessFilter( "2001:db8::/32", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "fe80::1" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( req ) );
    }

    @Test
    void unresolvableRemoteAddrLogsAndDeniesRatherThanThrowing() {
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        // A string with whitespace cannot be parsed as an IP literal nor resolved as a
        // hostname → InetAddress.getByName throws UnknownHostException inside checkIp().
        when( req.getRemoteAddr() ).thenReturn( "not a host" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
    }

    // --- rate limiting (through doFilter) ----------------------------------------------

    @Test
    void rateLimitExceededReturns429AndSkipsChain() throws Exception {
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", false, s -> false, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( req, resp, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( resp ).setStatus( 429 );
        verify( resp ).setHeader( "Retry-After", "1" );
        assertEquals( "{\"error\":\"Rate limit exceeded\"}", body.toString() );
    }

    @Test
    void rateLimitPassAllowsChainToProceed() throws Exception {
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( req, resp, chain );

        verify( chain ).doFilter( req, resp );
    }

    // --- allowUnrestricted bypass -------------------------------------------------------

    @Test
    void allowUnrestrictedBypassesAuthWhenNoCidrConfigured() {
        final TestAccessFilter filter = new TestAccessFilter( null, true, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "1.2.3.4" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Allowed.class, outcome );
        assertEquals( "ip:1.2.3.4", ( ( AbstractApiAccessFilter.Outcome.Allowed ) outcome ).clientId() );
    }

    @Test
    void allowUnrestrictedIsIgnoredWhenCidrIsConfigured() {
        // unrestricted = !hasCidr && allowUnrestricted — a configured CIDR always wins,
        // so an out-of-range caller is still denied even with allowUnrestricted=true.
        final TestAccessFilter filter = new TestAccessFilter( "10.0.0.0/8", true, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getRemoteAddr() ).thenReturn( "192.168.1.1" );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, filter.authorize( req ) );
    }

    // --- fail-closed ----------------------------------------------------------------------

    @Test
    void failsClosedWhenNothingIsConfigured() throws Exception {
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, null );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpServletResponse resp = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        when( req.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( req, resp, chain );

        verifyNoInteractions( chain );
        verify( resp ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        verify( resp ).setContentType( "application/json" );
        verify( resp ).setHeader( "Retry-After", "42" );
        assertTrue( body.toString().contains( "test_access_unconfigured" ), body.toString() );
    }

    @Test
    void dbKeyServiceAloneSatisfiesFailClosedGateEvenWhenKeyInvalid() {
        // Presence of an ApiKeyService (e.g. datasource configured) is enough to avoid the
        // fail-closed 503 gate, independent of whether any particular key verifies.
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( anyString() ) ).thenReturn( Optional.empty() );
        final TestAccessFilter filter = new TestAccessFilter( null, false, ALWAYS_ALLOW, svc );
        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_wrong" );
        when( req.getRemoteAddr() ).thenReturn( "1.2.3.4" );

        final AbstractApiAccessFilter.Outcome outcome = filter.authorize( req );

        assertInstanceOf( AbstractApiAccessFilter.Outcome.Denied.class, outcome );
        assertEquals( HttpServletResponse.SC_FORBIDDEN, ( ( AbstractApiAccessFilter.Outcome.Denied ) outcome ).status() );
    }

    // --- Outcome / CidrEntry statics ------------------------------------------------------

    @Test
    void deniedOfWithoutHeadersProducesEmptyHeaderList() {
        final var denied = AbstractApiAccessFilter.Outcome.Denied.of( 403, "{}" );
        assertEquals( 403, denied.status() );
        assertEquals( "{}", denied.body() );
        assertTrue( denied.headers().isEmpty() );
    }

    @Test
    void deniedOfWithHeadersPreservesThem() {
        final var denied = AbstractApiAccessFilter.Outcome.Denied.of( 503, "{}",
                new AbstractApiAccessFilter.Outcome.Header( "Retry-After", "10" ) );
        assertEquals( 1, denied.headers().size() );
        assertEquals( "Retry-After", denied.headers().get( 0 ).name() );
        assertEquals( "10", denied.headers().get( 0 ).value() );
    }

    @Test
    void oneArgParseCidrsMatchesTwoArgBehavior() {
        final List< AbstractApiAccessFilter.CidrEntry > entries = AbstractApiAccessFilter.parseCidrs( "10.0.0.0/8" );
        assertEquals( 1, entries.size() );
        assertEquals( 8, entries.get( 0 ).prefixLen() );
    }

    @Test
    void parseCidrsReturnsEmptyForNullOrBlank() {
        assertTrue( AbstractApiAccessFilter.parseCidrs( null ).isEmpty() );
        assertTrue( AbstractApiAccessFilter.parseCidrs( "   " ).isEmpty() );
    }

    @Test
    void parseCidrsSkipsMalformedEntriesButKeepsValidOnes() {
        final List< AbstractApiAccessFilter.CidrEntry > entries =
                AbstractApiAccessFilter.parseCidrs( "not-a-cidr, 10.0.0.0/8, 10.0.0.0/abc, 10.0.0.0/999, , 10.0.0.0" );
        assertEquals( 1, entries.size() );
        assertEquals( 8, entries.get( 0 ).prefixLen() );
    }

    @Test
    void matchesStaticRejectsMismatchedAddressLength() throws Exception {
        final byte[] v4 = InetAddress.getByName( "10.0.0.1" ).getAddress();
        final AbstractApiAccessFilter.CidrEntry v6Cidr =
                AbstractApiAccessFilter.parseCidrs( "::/0" ).get( 0 );
        assertFalse( AbstractApiAccessFilter.matches( v4, v6Cidr ) );
    }

    @Test
    void matchesStaticHandlesExactPrefixBoundary() throws Exception {
        final AbstractApiAccessFilter.CidrEntry cidr = AbstractApiAccessFilter.parseCidrs( "10.0.0.0/24" ).get( 0 );
        assertTrue( AbstractApiAccessFilter.matches(
                InetAddress.getByName( "10.0.0.255" ).getAddress(), cidr ) );
        assertFalse( AbstractApiAccessFilter.matches(
                InetAddress.getByName( "10.0.1.0" ).getAddress(), cidr ) );
    }
}

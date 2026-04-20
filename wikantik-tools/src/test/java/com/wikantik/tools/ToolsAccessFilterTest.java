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
package com.wikantik.tools;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class ToolsAccessFilterTest {

    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock FilterChain chain;

    private ToolsAccessFilter createFilter( final String keys, final String cidrs ) {
        final Properties props = new Properties();
        if ( keys != null ) {
            props.setProperty( "tools.access.keys", keys );
        }
        if ( cidrs != null ) {
            props.setProperty( "tools.access.allowedCidrs", cidrs );
        }
        return new ToolsAccessFilter( new ToolsConfig( props ), new ToolsRateLimiter( 0, 0 ) );
    }

    @Test
    void correctApiKeyPasses() throws Exception {
        final ToolsAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer secret123" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void wrongApiKeyBlocked() throws Exception {
        final ToolsAccessFilter filter = createFilter( "secret123", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong-key" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
    }

    @Test
    void ipInCidrPasses() throws Exception {
        final ToolsAccessFilter filter = createFilter( null, "10.0.0.0/8" );
        when( request.getRemoteAddr() ).thenReturn( "10.1.2.3" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void bothUnconfiguredFailsClosed() throws Exception {
        final ToolsAccessFilter filter = createFilter( null, null );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( HttpServletResponse.SC_SERVICE_UNAVAILABLE );
        assertTrue( body.toString().contains( "Tool server not configured" ),
                "Expected error body to mention Tool server, got: " + body );
    }

    @Test
    void unrestrictedModeAllowedWithExplicitOptIn() throws Exception {
        final Properties props = new Properties();
        props.setProperty( "tools.access.allowUnrestricted", "true" );
        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( props ), new ToolsRateLimiter( 0, 0 ) );
        when( request.getRemoteAddr() ).thenReturn( "1.2.3.4" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void multipleKeysAllAccepted() throws Exception {
        final ToolsAccessFilter filter = createFilter( "alpha, beta, gamma", null );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer beta" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void rateLimitExceededReturns429() throws Exception {
        final ToolsRateLimiter mockLimiter = mock( ToolsRateLimiter.class );
        when( mockLimiter.tryAcquire( anyString() ) ).thenReturn( false );

        final Properties props = new Properties();
        props.setProperty( "tools.access.keys", "secret123" );
        final ToolsAccessFilter filter = new ToolsAccessFilter( new ToolsConfig( props ), mockLimiter );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer secret123" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( request, response );
        verify( response ).setStatus( 429 );
        verify( response ).setHeader( "Retry-After", "1" );
        assertEquals( "{\"error\":\"Rate limit exceeded\"}", body.toString() );
    }

    // ----- DB-backed key path -----

    private ApiKeyService.Record dbRecord( final int id, final String principal,
                                           final ApiKeyService.Scope scope ) {
        return new ApiKeyService.Record(
                id, "hash-" + id, principal, "label", scope,
                Instant.now(), "admin", null, null, null );
    }

    @Test
    void dbKeyVerifiedAndPrincipalInstalled() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        final ApiKeyService.Record record = dbRecord( 1, "alice", ApiKeyService.Scope.TOOLS );
        when( svc.verify( "wkk_good" ) ).thenReturn( Optional.of( record ) );

        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( new Properties() ), new ToolsRateLimiter( 0, 0 ), svc );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_good" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        final ArgumentCaptor< HttpServletRequest > captor = ArgumentCaptor.forClass( HttpServletRequest.class );
        verify( chain ).doFilter( captor.capture(), eq( response ) );
        assertInstanceOf( ApiKeyPrincipalRequest.class, captor.getValue(),
                "Filter must install an ApiKeyPrincipalRequest wrapper so downstream sees the key principal" );
        assertEquals( "alice", captor.getValue().getUserPrincipal().getName() );
        verify( request ).setAttribute( ApiKeyPrincipalRequest.ATTR_API_KEY_RECORD, record );
    }

    @Test
    void dbKeyWithAllScopeAlsoMatchesTools() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_all" ) )
                .thenReturn( Optional.of( dbRecord( 2, "bob", ApiKeyService.Scope.ALL ) ) );

        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( new Properties() ), new ToolsRateLimiter( 0, 0 ), svc );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_all" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( any( HttpServletRequest.class ), eq( response ) );
    }

    @Test
    void dbKeyWithMcpScopeRejectedForToolsWith403() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( "wkk_mcp" ) )
                .thenReturn( Optional.of( dbRecord( 3, "carol", ApiKeyService.Scope.MCP ) ) );

        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( new Properties() ), new ToolsRateLimiter( 0, 0 ), svc );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wkk_mcp" );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( chain, never() ).doFilter( any(), any() );
        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        assertTrue( body.toString().contains( "not authorized" ),
                "Response body should explain the scope mismatch: " + body );
    }

    @Test
    void unknownDbKeyFallsThroughToLegacyKeyList() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( anyString() ) ).thenReturn( Optional.empty() );

        final Properties props = new Properties();
        props.setProperty( "tools.access.keys", "legacy-key" );
        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( props ), new ToolsRateLimiter( 0, 0 ), svc );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer legacy-key" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
    }

    @Test
    void dbKeyServiceAloneSatisfiesFailClosedGate() throws Exception {
        final ApiKeyService svc = mock( ApiKeyService.class );
        when( svc.verify( anyString() ) ).thenReturn( Optional.empty() );

        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( new Properties() ), new ToolsRateLimiter( 0, 0 ), svc );
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
        // Regression: in an IT environment wikantik.datasource is configured
        // (so ApiKeyService is non-null) but no legacy keys/CIDRs are set and
        // tools.access.allowUnrestricted=true is explicit. Filter must run open.
        final ApiKeyService svc = mock( ApiKeyService.class );
        final Properties props = new Properties();
        props.setProperty( "tools.access.allowUnrestricted", "true" );
        final ToolsAccessFilter filter = new ToolsAccessFilter(
                new ToolsConfig( props ), new ToolsRateLimiter( 0, 0 ), svc );
        when( request.getRemoteAddr() ).thenReturn( "10.0.0.1" );

        filter.doFilter( request, response, chain );

        verify( chain ).doFilter( request, response );
        verify( svc, never() ).verify( anyString() );
    }

    @Test
    void rateLimitNotCheckedWhenAuthFails() throws Exception {
        final ToolsRateLimiter mockLimiter = mock( ToolsRateLimiter.class );
        final Properties props = new Properties();
        props.setProperty( "tools.access.keys", "secret123" );
        final ToolsAccessFilter filter = new ToolsAccessFilter( new ToolsConfig( props ), mockLimiter );
        when( request.getHeader( "Authorization" ) ).thenReturn( "Bearer wrong" );
        when( request.getRemoteAddr() ).thenReturn( "192.168.1.1" );
        final StringWriter body = new StringWriter();
        when( response.getWriter() ).thenReturn( new PrintWriter( body ) );

        filter.doFilter( request, response, chain );

        verify( response ).setStatus( HttpServletResponse.SC_FORBIDDEN );
        verify( mockLimiter, never() ).tryAcquire( anyString() );
    }
}

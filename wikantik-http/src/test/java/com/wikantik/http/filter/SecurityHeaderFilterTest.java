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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests all OWASP security header filters. Each filter follows the same pattern:
 * default header value, configurable via init parameter, added to every response.
 */
class SecurityHeaderFilterTest {

    /**
     * Provides (Filter class, header name, default value, init parameter name) for each filter.
     */
    static Stream<Arguments> filterSpecs() {
        return Stream.of(
                Arguments.of( CSPFilter.class,
                        "Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self';",
                        "CSPValue" ),
                Arguments.of( ClickJackFilter.class,
                        "X-FRAME-OPTIONS",
                        "DENY",
                        "mode" ),
                Arguments.of( ContentTypeOptionsFilter.class,
                        "X-Content-Type-Options",
                        "nosniff",
                        "ContentTypeOptionsValue" ),
                Arguments.of( CORPFilter.class,
                        "Cross-Origin-Resource-Policy",
                        "same-origin",
                        "CORPValue" ),
                Arguments.of( COEPFilter.class,
                        "Cross-Origin-Embedder-Policy",
                        "require-corp",
                        "CORPValue" ),
                Arguments.of( CrossDomainFilter.class,
                        "X-Permitted-Cross-Domain-Policies",
                        "none",
                        "XDomainValue" ),
                Arguments.of( ReferrerPolicyFilter.class,
                        "Referrer-Policy",
                        "no-referrer-when-downgrade",
                        "ReferrerPolicyPValue" ),
                Arguments.of( STSFilter.class,
                        "Strict-Transport-Security",
                        "max-age=63072000; includeSubDomains; preload",
                        "STSValue" ),
                Arguments.of( ClearSiteDataFilter.class,
                        "Clear-Site-Data",
                        "\"cookies\", \"storage\"",
                        "CSDValue" )
        );
    }

    @ParameterizedTest( name = "{0} sets {1} header with default value" )
    @MethodSource( "filterSpecs" )
    void testDefaultHeaderValue( final Class<? extends Filter> filterClass,
                                  final String headerName,
                                  final String defaultValue,
                                  final String initParam ) throws Exception {
        final Filter filter = filterClass.getDeclaredConstructor().newInstance();

        // Init with no custom parameter
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( initParam ) ).thenReturn( null );
        filter.init( config );

        // Execute filter
        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        // Verify header set with default value
        verify( response ).addHeader( headerName, defaultValue );
        // Verify chain continues
        verify( chain ).doFilter( request, response );
    }

    @ParameterizedTest( name = "{0} uses custom value from init parameter" )
    @MethodSource( "filterSpecs" )
    void testCustomHeaderValue( final Class<? extends Filter> filterClass,
                                 final String headerName,
                                 final String defaultValue,
                                 final String initParam ) throws Exception {
        // ClickJackFilter validates values — only DENY or SAMEORIGIN are accepted
        if ( filterClass == ClickJackFilter.class ) {
            testClickJackCustomValue();
            return;
        }

        final Filter filter = filterClass.getDeclaredConstructor().newInstance();

        // Init with custom parameter
        final String customValue = "custom-test-value-" + headerName;
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( initParam ) ).thenReturn( customValue );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        // Verify custom value used instead of default
        verify( response ).addHeader( headerName, customValue );
        verify( chain ).doFilter( request, response );
    }

    private void testClickJackCustomValue() throws Exception {
        final ClickJackFilter filter = new ClickJackFilter();
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( "mode" ) ).thenReturn( "SAMEORIGIN" );
        filter.init( config );

        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( mock( HttpServletRequest.class ), response, chain );

        verify( response ).addHeader( "X-FRAME-OPTIONS", "SAMEORIGIN" );
    }

    @ParameterizedTest( name = "{0} always continues the filter chain" )
    @MethodSource( "filterSpecs" )
    void testFilterChainContinues( final Class<? extends Filter> filterClass,
                                    final String headerName,
                                    final String defaultValue,
                                    final String initParam ) throws Exception {
        final Filter filter = filterClass.getDeclaredConstructor().newInstance();
        final FilterConfig config = mock( FilterConfig.class );
        filter.init( config );

        final HttpServletRequest request = mock( HttpServletRequest.class );
        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        filter.doFilter( request, response, chain );

        // The filter must NEVER block the chain — it only adds headers
        verify( chain, times( 1 ) ).doFilter( request, response );
    }

    // ===== CSP-specific tests =====

    @Test
    void testCspDefaultDoesNotAllowUnsafeInlineScripts() {
        final CSPFilter filter = new CSPFilter();
        final FilterConfig config = mock( FilterConfig.class );
        filter.init( config );

        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        try {
            filter.doFilter( mock( HttpServletRequest.class ), response, chain );
        } catch ( final Exception e ) {
            fail( "Filter should not throw: " + e.getMessage() );
        }

        // Capture the actual header value
        final var captor = org.mockito.ArgumentCaptor.forClass( String.class );
        verify( response ).addHeader( eq( "Content-Security-Policy" ), captor.capture() );
        final String csp = captor.getValue();

        // script-src must NOT contain 'unsafe-inline'
        assertTrue( csp.contains( "script-src" ), "CSP must define script-src" );
        assertFalse( csp.contains( "script-src 'self' 'unsafe-inline'" ),
                "CSP script-src must NOT allow 'unsafe-inline' — XSS defense" );

        // style-src MAY contain 'unsafe-inline' (React inline styles, not a security risk)
        assertTrue( csp.contains( "style-src 'self' 'unsafe-inline'" ),
                "CSP style-src should allow 'unsafe-inline' for React inline styles" );
    }

    @Test
    void testStsDefaultIncludesPreload() {
        final STSFilter filter = new STSFilter();
        final FilterConfig config = mock( FilterConfig.class );
        filter.init( config );

        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        try {
            filter.doFilter( mock( HttpServletRequest.class ), response, chain );
        } catch ( final Exception e ) {
            fail( "Filter should not throw: " + e.getMessage() );
        }

        final var captor = org.mockito.ArgumentCaptor.forClass( String.class );
        verify( response ).addHeader( eq( "Strict-Transport-Security" ), captor.capture() );
        final String sts = captor.getValue();

        assertTrue( sts.contains( "max-age=" ), "STS must include max-age" );
        assertTrue( sts.contains( "includeSubDomains" ), "STS should include subdomains" );
        assertTrue( sts.contains( "preload" ), "STS should include preload" );
    }

    @Test
    void testClickjackRejectsInvalidValues() throws Exception {
        final ClickJackFilter filter = new ClickJackFilter();
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( "mode" ) ).thenReturn( "ALLOW-FROM http://evil.com" );
        filter.init( config );

        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );
        filter.doFilter( mock( HttpServletRequest.class ), response, chain );

        // Invalid value should be rejected — default DENY used instead
        verify( response ).addHeader( "X-FRAME-OPTIONS", "DENY" );
    }

    @Test
    void testClickjackDefaultIsDeny() {
        final ClickJackFilter filter = new ClickJackFilter();
        final FilterConfig config = mock( FilterConfig.class );
        filter.init( config );

        final HttpServletResponse response = mock( HttpServletResponse.class );
        final FilterChain chain = mock( FilterChain.class );

        try {
            filter.doFilter( mock( HttpServletRequest.class ), response, chain );
        } catch ( final Exception e ) {
            fail( "Filter should not throw: " + e.getMessage() );
        }

        verify( response ).addHeader( "X-FRAME-OPTIONS", "DENY" );
    }
}

/*
 * Copyright 2025 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wikantik.http.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * Tests for {@link COEPFilter} — emits {@code Cross-Origin-Embedder-Policy: require-corp}
 * with an init-param override under the filter's own name, {@code COEPValue}.
 */
class COEPFilterTest {

    private COEPFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new COEPFilter();
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
    }

    @Test
    void testSetsRequireCorpHeaderAndContinuesChain() throws Exception {
        filter.init( mock( FilterConfig.class ) );

        filter.doFilter( request, response, chain );

        verify( response ).addHeader( "Cross-Origin-Embedder-Policy", "require-corp" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testValueIsOverridableViaCoepInitParam() throws Exception {
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( "COEPValue" ) ).thenReturn( "credentialless" );
        filter.init( config );

        filter.doFilter( request, response, chain );

        verify( response ).addHeader( "Cross-Origin-Embedder-Policy", "credentialless" );
        verify( chain ).doFilter( request, response );
    }
}

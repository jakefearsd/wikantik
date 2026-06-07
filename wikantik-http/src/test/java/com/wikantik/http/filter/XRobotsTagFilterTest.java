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
 * Tests for {@link XRobotsTagFilter} — emits {@code X-Robots-Tag: noindex} so the
 * crawlable-but-not-indexable {@code /api/pages/} content endpoint is rendered by
 * crawlers but never indexed as a standalone URL.
 */
class XRobotsTagFilterTest {

    private XRobotsTagFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new XRobotsTagFilter();
        request = mock( HttpServletRequest.class );
        response = mock( HttpServletResponse.class );
        chain = mock( FilterChain.class );
    }

    @Test
    void testSetsNoindexHeaderAndContinuesChain() throws Exception {
        filter.init( mock( FilterConfig.class ) );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Robots-Tag", "noindex" );
        verify( chain ).doFilter( request, response );
    }

    @Test
    void testValueIsOverridableViaInitParam() throws Exception {
        final FilterConfig config = mock( FilterConfig.class );
        when( config.getInitParameter( "XRobotsTagValue" ) ).thenReturn( "noindex, nofollow" );
        filter.init( config );

        filter.doFilter( request, response, chain );

        verify( response ).setHeader( "X-Robots-Tag", "noindex, nofollow" );
        verify( chain ).doFilter( request, response );
    }
}

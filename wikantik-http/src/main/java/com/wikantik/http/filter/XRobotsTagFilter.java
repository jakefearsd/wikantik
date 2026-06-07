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

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Emits {@code X-Robots-Tag: noindex} on API responses.
 *
 * <p>The read-only page-content API ({@code /api/pages/...}) must stay crawlable
 * so Google's renderer can fetch page content while rendering the SPA (see
 * robots.txt {@code Allow: /api/pages/}). But the JSON those endpoints return
 * should never be indexed as a standalone search result, so this filter — mapped
 * to {@code /api/*} — tells crawlers to render-but-not-index the resource. It
 * applies to the page that is *served* the header, not to pages that fetch it as
 * a subresource, so the canonical {@code /wiki/...} HTML pages stay indexable.
 */
public class XRobotsTagFilter implements Filter {

    private String value = "noindex";

    @Override
    public void init( final FilterConfig filterConfig ) {
        final String configured = filterConfig.getInitParameter( "XRobotsTagValue" );
        if ( configured != null ) {
            value = configured;
        }
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                          final FilterChain chain ) throws IOException, ServletException {
        ( (HttpServletResponse) response ).setHeader( "X-Robots-Tag", value );
        chain.doFilter( request, response );
    }

}

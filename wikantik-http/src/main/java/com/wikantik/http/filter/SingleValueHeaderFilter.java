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
 * Template for the one-header security filters: an optional web.xml init-param overrides the
 * built-in default, and {@code doFilter} stamps that single header on every response passing
 * through the filter's mapping. Subclasses supply header name, init-param name, and default
 * via the constructor; each previously hand-copied this whole class (which is how COEPFilter
 * shipped reading CORPFilter's init-param name).
 */
public abstract class SingleValueHeaderFilter implements Filter {

    private final String headerName;
    private final String initParamName;
    private String value;

    protected SingleValueHeaderFilter( final String headerName, final String initParamName, final String defaultValue ) {
        this.headerName = headerName;
        this.initParamName = initParamName;
        this.value = defaultValue;
    }

    @Override
    public void init( final FilterConfig filterConfig ) {
        final String configured = filterConfig.getInitParameter( initParamName );
        if ( configured != null && isAcceptable( configured ) ) {
            value = configured;
        }
    }

    /**
     * Hook: whether a configured override is a legal value for this header. Defaults to
     * accepting anything; a subclass with a closed value set (e.g. X-Frame-Options)
     * overrides to keep the built-in default on an invalid override.
     */
    protected boolean isAcceptable( final String configured ) {
        return true;
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException {
        applyHeader( ( HttpServletResponse ) response, headerName, value );
        chain.doFilter( request, response );
    }

    /**
     * Hook: how the header lands on the response. Defaults to {@code addHeader}; a subclass
     * that must replace rather than accumulate (e.g. X-Robots-Tag) overrides with {@code setHeader}.
     */
    protected void applyHeader( final HttpServletResponse response, final String name, final String headerValue ) {
        response.addHeader( name, headerValue );
    }
}

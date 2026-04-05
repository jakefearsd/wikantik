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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Sets {@code Cache-Control} headers for static assets and the SPA entry point.
 *
 * <ul>
 *   <li>{@code /assets/*} with a Vite content hash (6+ alphanumeric chars before the
 *       extension) receives {@code public, max-age=31536000, immutable} — safe because
 *       the hash changes whenever the file content changes.</li>
 *   <li>{@code /index.html} receives {@code no-cache} — forces the browser to revalidate
 *       on every request so it always gets the latest asset references.</li>
 *   <li>All other paths pass through without a {@code Cache-Control} header.</li>
 * </ul>
 */
public class CacheHeaderFilter implements Filter {

    /** Matches Vite-style content-hashed filenames, e.g. {@code index-BCNdZRMf.js}. */
    private static final Pattern HASHED_ASSET = Pattern.compile( "-[A-Za-z0-9]{6,}\\." );

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response,
                           final FilterChain chain ) throws IOException, ServletException {
        final HttpServletRequest req = ( HttpServletRequest ) request;
        final HttpServletResponse resp = ( HttpServletResponse ) response;
        final String path = req.getRequestURI();

        // setHeader (not addHeader) — for Cache-Control we must replace, not append,
        // to avoid conflicting directives from other components.
        if ( path.startsWith( "/assets/" ) && HASHED_ASSET.matcher( path ).find() ) {
            resp.setHeader( "Cache-Control", "public, max-age=31536000, immutable" );
        } else if ( "/index.html".equals( path ) ) {
            resp.setHeader( "Cache-Control", "no-cache" );
        }

        chain.doFilter( request, response );
    }

    @Override
    public void destroy() {
    }
}

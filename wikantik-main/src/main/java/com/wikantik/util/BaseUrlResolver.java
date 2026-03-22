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
package com.wikantik.util;

import com.wikantik.api.core.Engine;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the base URL for the wiki using a 3-tier fallback strategy:
 * <ol>
 *   <li>Explicit configured base URL (e.g., for proxy/HTTPS scenarios)</li>
 *   <li>Engine's configured base URL ({@link Engine#getBaseURL()})</li>
 *   <li>URL derived from the current HTTP request (scheme + host + port + context path)</li>
 * </ol>
 *
 * <p>The returned URL never ends with a trailing slash, ensuring consistent concatenation.
 */
public final class BaseUrlResolver {

    private BaseUrlResolver() {
        // utility class
    }

    /**
     * Resolves the base URL using the 3-tier fallback strategy.
     *
     * @param engine            the wiki engine (used for {@link Engine#getBaseURL()} fallback)
     * @param request           the current HTTP request (used for request-derived fallback)
     * @param configuredBaseUrl an explicitly configured base URL, or {@code null}
     * @return a fully-qualified base URL without a trailing slash
     */
    public static String resolve( final Engine engine, final HttpServletRequest request, final String configuredBaseUrl ) {
        // Tier 1: explicit configured base URL
        if ( configuredBaseUrl != null && !configuredBaseUrl.isBlank() ) {
            return stripTrailingSlash( configuredBaseUrl );
        }

        // Tier 2: engine's base URL
        if ( engine != null ) {
            final String engineBase = engine.getBaseURL();
            if ( engineBase != null && engineBase.startsWith( "http" ) ) {
                return stripTrailingSlash( engineBase );
            }
        }

        // Tier 3: derive from request
        final String scheme = request.getScheme();
        final String serverName = request.getServerName();
        final int serverPort = request.getServerPort();
        final String contextPath = request.getContextPath();

        if ( scheme != null && serverName != null ) {
            final StringBuilder url = new StringBuilder( scheme ).append( "://" ).append( serverName );
            if ( ( "http".equals( scheme ) && serverPort != 80 ) ||
                 ( "https".equals( scheme ) && serverPort != 443 ) ) {
                url.append( ':' ).append( serverPort );
            }
            if ( contextPath != null ) {
                url.append( contextPath );
            }
            return stripTrailingSlash( url.toString() );
        }

        // Fallback to a safe default if request info is not available
        String baseUrl = "http://localhost";
        if ( contextPath != null ) {
            baseUrl += contextPath;
        }
        return stripTrailingSlash( baseUrl );
    }

    private static String stripTrailingSlash( final String url ) {
        if ( url != null && url.endsWith( "/" ) ) {
            return url.substring( 0, url.length() - 1 );
        }
        return url;
    }
}

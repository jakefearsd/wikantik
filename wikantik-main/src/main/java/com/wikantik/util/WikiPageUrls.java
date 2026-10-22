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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Reader links to wiki pages ({@code <base>/wiki/<page>}) for surfaces that have no HTTP request to
 * derive an origin from — retrieval results and context bundles handed to agents. Request-bound
 * surfaces use {@link BaseUrlResolver} instead.
 */
public final class WikiPageUrls {

    private WikiPageUrls() {}

    /**
     * The configured public origin ({@code wikantik.baseURL}) without a trailing slash, or
     * {@code fallback} (e.g. the servlet context path, for site-relative links) when it is unset.
     */
    public static String base( final Properties props, final String fallback ) {
        final String configured = props == null ? "" : props.getProperty( "wikantik.baseURL", "" ).strip();
        final String base = configured.isEmpty() ? ( fallback == null ? "" : fallback ) : configured;
        return base.endsWith( "/" ) ? base.substring( 0, base.length() - 1 ) : base;
    }

    /** {@code base + "/wiki/" + page}, the page name encoded as a single path segment. */
    public static String page( final String base, final String page ) {
        return base + "/wiki/" + URLEncoder.encode( page, StandardCharsets.UTF_8 ).replace( "+", "%20" );
    }
}

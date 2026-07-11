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
package com.wikantik.connectors.web;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.net.URI;

/** In-scope predicate: http/https only, host match (when sameHostOnly), optional path-prefix. */
final class CrawlScope {
    private static final Logger LOG = LogManager.getLogger( CrawlScope.class );
    private final String seedHost;
    private final boolean sameHostOnly;
    private final String pathPrefix;

    CrawlScope( final String seedHost, final boolean sameHostOnly, final String pathPrefix ) {
        this.seedHost = seedHost;
        this.sameHostOnly = sameHostOnly;
        this.pathPrefix = pathPrefix == null || pathPrefix.isBlank() ? null : pathPrefix;
    }

    boolean inScope( final String url ) {
        try {
            final URI u = URI.create( url );
            final String scheme = u.getScheme();
            if ( scheme == null || !( scheme.equals( "http" ) || scheme.equals( "https" ) ) ) return false;
            if ( sameHostOnly && ( u.getHost() == null || !u.getHost().equalsIgnoreCase( seedHost ) ) ) return false;
            if ( pathPrefix != null ) {
                final String path = u.getPath() == null ? "" : u.getPath();
                if ( !path.startsWith( pathPrefix ) ) return false;
            }
            return true;
        } catch ( final RuntimeException e ) {
            LOG.debug( "URL not in scope (unparseable): {}", url );
            return false;
        }
    }
}

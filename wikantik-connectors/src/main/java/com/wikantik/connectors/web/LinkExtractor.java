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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** jsoup-backed link + title extraction. Never throws — a parse failure yields an empty result. */
final class LinkExtractor {
    private static final Logger LOG = LogManager.getLogger( LinkExtractor.class );
    private LinkExtractor() {}

    static List< String > links( final String html, final String baseUrl ) {
        try {
            final Document doc = Jsoup.parse( html, baseUrl );
            final Set< String > out = new LinkedHashSet<>();
            for ( final Element a : doc.select( "a[href]" ) ) {
                final String abs = a.absUrl( "href" );   // resolves relative against baseUrl
                if ( abs != null && !abs.isBlank() ) out.add( abs );
            }
            return new ArrayList<>( out );
        } catch ( final RuntimeException e ) {
            LOG.warn( "link extraction failed for {}: {}", baseUrl, e.getMessage() );
            return List.of();
        }
    }

    static String title( final String html ) {
        try {
            return Jsoup.parse( html == null ? "" : html ).title();
        } catch ( final RuntimeException e ) {
            LOG.warn( "title extraction failed: {}", e.getMessage() );
            return "";
        }
    }
}

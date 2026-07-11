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
import org.jsoup.parser.Parser;

import java.util.ArrayList;
import java.util.List;

/** Parses sitemap.xml (urlset) and sitemap-index XML using jsoup's XML parser. Never throws. */
final class SitemapParser {
    private static final Logger LOG = LogManager.getLogger( SitemapParser.class );
    private SitemapParser() {}

    static ParsedSitemap parse( final String xml ) {
        if ( xml == null || xml.isBlank() ) return new ParsedSitemap( List.of(), false );
        try {
            final Document doc = Jsoup.parse( xml, "", Parser.xmlParser() );
            final boolean isIndex = !doc.select( "sitemapindex" ).isEmpty();
            final List< String > locs = new ArrayList<>();
            for ( final Element loc : doc.select( "loc" ) ) {
                final String text = loc.text().trim();
                if ( !text.isBlank() ) locs.add( text );
            }
            return new ParsedSitemap( locs, isIndex );
        } catch ( final RuntimeException e ) {
            LOG.warn( "sitemap parse failed: {}", e.getMessage() );
            return new ParsedSitemap( List.of(), false );
        }
    }
}

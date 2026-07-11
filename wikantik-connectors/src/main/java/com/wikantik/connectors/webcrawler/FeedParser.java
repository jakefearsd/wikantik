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
package com.wikantik.connectors.webcrawler;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/** Parses RSS 0.9-2.0 and Atom feeds via Rome. Never throws - a malformed feed yields an empty list. */
final class FeedParser {
    private static final Logger LOG = LogManager.getLogger( FeedParser.class );
    private FeedParser() {}

    static List< FeedEntry > parse( final byte[] xml, final String baseUrl ) {
        if ( xml == null || xml.length == 0 ) return List.of();
        try ( XmlReader reader = new XmlReader( new ByteArrayInputStream( xml ) ) ) {
            final SyndFeed feed = new SyndFeedInput().build( reader );
            final List< FeedEntry > out = new ArrayList<>();
            for ( final SyndEntry e : feed.getEntries() ) {
                final String link = e.getLink() == null ? "" : e.getLink().trim();
                if ( link.isBlank() ) continue;   // no stable URI -> skip
                final String title = e.getTitle() == null ? "" : e.getTitle();
                out.add( new FeedEntry( title, link, contentOf( e ) ) );
            }
            return out;
        } catch ( final Exception ex ) {   // FeedException, IOException, IllegalArgumentException, ...
            LOG.warn( "feed parse failed for {}: {}", baseUrl, ex.getMessage() );
            return List.of();
        }
    }

    private static String contentOf( final SyndEntry e ) {
        if ( e.getContents() != null && !e.getContents().isEmpty() ) {
            final StringBuilder sb = new StringBuilder();
            for ( final SyndContent c : e.getContents() ) {
                if ( c.getValue() != null ) sb.append( c.getValue() );
            }
            if ( !sb.isEmpty() ) return sb.toString();
        }
        if ( e.getDescription() != null && e.getDescription().getValue() != null ) {
            return e.getDescription().getValue();
        }
        return "";
    }
}

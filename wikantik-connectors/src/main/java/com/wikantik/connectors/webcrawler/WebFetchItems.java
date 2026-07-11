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

import com.wikantik.api.connectors.SourceItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared builders for web-fetched connectors (crawler + sitemap): the text/html {@link SourceItem}
 *  shape and content hashing. Keeps the item shape identical across both connectors. */
final class WebFetchItems {

    private WebFetchItems() {}

    static SourceItem toItem( final String url, final FetchResult r ) {
        final String html = new String( r.body(), StandardCharsets.UTF_8 );
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", LinkExtractor.title( html ) );
        md.put( "fetchedAt", Instant.now().toString() );   // metadata only; not asserted on exact value
        md.put( "httpStatus", r.status() );
        return new SourceItem( url, r.body(), "text/html", md, List.of(), sha256Hex( r.body() ) );
    }

    static SourceItem toItemFromContent( final String url, final byte[] htmlBytes, final String title ) {
        final Map< String, Object > md = new LinkedHashMap<>();
        md.put( "url", url );
        md.put( "title", title == null ? "" : title );
        md.put( "fetchedAt", Instant.now().toString() );
        return new SourceItem( url, htmlBytes, "text/html", md, List.of(), sha256Hex( htmlBytes ) );
    }

    static String sha256Hex( final byte[] bytes ) {
        try {
            final byte[] d = MessageDigest.getInstance( "SHA-256" ).digest( bytes );
            final StringBuilder sb = new StringBuilder( d.length * 2 );
            for ( final byte b : d ) sb.append( Character.forDigit( ( b >> 4 ) & 0xF, 16 ) ).append( Character.forDigit( b & 0xF, 16 ) );
            return sb.toString();
        } catch ( final NoSuchAlgorithmException e ) {
            throw new IllegalStateException( "SHA-256 unavailable", e );   // JVM-guaranteed; never happens
        }
    }
}

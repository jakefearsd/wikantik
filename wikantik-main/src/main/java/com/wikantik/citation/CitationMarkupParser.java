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
package com.wikantik.citation;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts {@code [claim](cite://target/heading "span")} links from a page body, in document order. */
public final class CitationMarkupParser {

    // group1 = claim, group2 = target+heading path, group3 = optional title (span)
    private static final Pattern CITE = Pattern.compile(
        "\\[([^\\]]*)]\\(\\s*cite://([^\\s)\"]+)\\s*(?:\"([^\"]*)\")?\\s*\\)" );

    public List< ParsedCitation > parse( final String body ) {
        final List< ParsedCitation > out = new ArrayList<>();
        if ( body == null || body.isEmpty() ) { return out; }
        final Map< String, Integer > ordinals = new HashMap<>();
        final Matcher m = CITE.matcher( body );
        while ( m.find() ) {
            final String claim = m.group( 1 ) == null ? "" : m.group( 1 ).trim();
            final String path = m.group( 2 );
            final String span = m.group( 3 ) == null ? "" : m.group( 3 );
            final int slash = path.indexOf( '/' );
            final String target = slash < 0 ? path : path.substring( 0, slash );
            final String headingPath = slash < 0 ? "" : decodeHeadingPath( path.substring( slash + 1 ) );
            final String spanHash = Spans.hash( Spans.normalize( span ) );
            final String key = target + " " + headingPath + " " + spanHash;
            final int ordinal = ordinals.merge( key, 0, ( a, b ) -> a + 1 );
            out.add( new ParsedCitation( target, headingPath, span, spanHash, claim, ordinal ) );
        }
        return out;
    }

    private static String decodeHeadingPath( final String raw ) {
        final String[] segs = raw.split( "/" );
        final StringBuilder sb = new StringBuilder();
        for ( final String seg : segs ) {
            if ( seg.isEmpty() ) { continue; }
            if ( sb.length() > 0 ) { sb.append( " > " ); }
            sb.append( URLDecoder.decode( seg, StandardCharsets.UTF_8 ).trim() );
        }
        return sb.toString();
    }
}

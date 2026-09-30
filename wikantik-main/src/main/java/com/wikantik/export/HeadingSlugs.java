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
package com.wikantik.export;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;

/**
 * Heading slugs identical to the page view's anchors ({@code wikantik-frontend/src/utils/headings.js}
 * {@code slugify}), which is what {@code Page#slug} links in the corpus target.
 */
public final class HeadingSlugs {
    private static final Parser PARSER = Parser.builder().build();

    /** JavaScript's {@code \s}: ASCII whitespace plus the Unicode space separators, as in headings.js. */
    private static final Pattern WHITESPACE =
            Pattern.compile( "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+" );
    private static final Pattern NON_SLUG = Pattern.compile( "[^a-z0-9-]" );
    private static final Pattern HYPHEN_RUN = Pattern.compile( "-+" );
    private static final Pattern EDGE_HYPHEN = Pattern.compile( "^-|-$" );

    private HeadingSlugs() {}

    public static String slug( final String headingText ) {
        String s = headingText.toLowerCase( Locale.ROOT );
        s = WHITESPACE.matcher( s ).replaceAll( "-" );
        s = NON_SLUG.matcher( s ).replaceAll( "" );
        s = HYPHEN_RUN.matcher( s ).replaceAll( "-" );
        return EDGE_HYPHEN.matcher( s ).replaceAll( "" );
    }

    /**
     * Slug to heading text for every heading. h2/h3 get the page view's ids (duplicates numbered
     * {@code -2}, {@code -3}, ...); other levels have no anchor in the view and are recorded under
     * their base slug only when it is free.
     */
    public static Map< String, String > headingsBySlug( final String markdownBody ) {
        final Map< String, String > out = new LinkedHashMap<>();
        final Map< String, Integer > seen = new HashMap<>();
        final Document doc = PARSER.parse( markdownBody );
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                final String text = new TextCollectingVisitor().collectAndGetText( h ).trim();
                final String base = slug( text );
                if ( h.getLevel() == 2 || h.getLevel() == 3 ) {
                    final int count = seen.merge( base, 1, Integer::sum ) - 1;
                    out.putIfAbsent( count == 0 ? base : base + "-" + ( count + 1 ), text );
                } else {
                    out.putIfAbsent( base, text );
                }
            }
        }
        return out;
    }
}

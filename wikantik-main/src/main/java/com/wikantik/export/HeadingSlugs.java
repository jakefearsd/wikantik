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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Document;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;

/** GitHub-compatible heading slugs, matching the anchors wiki pages link with ({@code Page#slug}). */
public final class HeadingSlugs {
    private static final Parser PARSER = Parser.builder().build();

    private HeadingSlugs() {}

    public static String slug( final String headingText ) {
        final String lower = headingText.trim().toLowerCase( Locale.ROOT );
        final StringBuilder sb = new StringBuilder( lower.length() );
        for ( int i = 0; i < lower.length(); i++ ) {
            final char c = lower.charAt( i );
            if ( Character.isLetterOrDigit( c ) || c == '-' || c == '_' ) {
                sb.append( c );
            } else if ( c == ' ' ) {
                sb.append( '-' );
            }
        }
        return sb.toString();
    }

    public static Map< String, String > headingsBySlug( final String markdownBody ) {
        final Map< String, String > out = new LinkedHashMap<>();
        final Document doc = PARSER.parse( markdownBody );
        for ( final Node n : doc.getDescendants() ) {
            if ( n instanceof Heading h ) {
                final String text = new TextCollectingVisitor().collectAndGetText( h ).trim();
                out.putIfAbsent( slug( text ), text );
            }
        }
        return out;
    }
}

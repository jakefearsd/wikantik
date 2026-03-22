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
package com.wikantik.mcp.tools;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utility for scanning Markdown-style links from wiki page text.
 * Extracted from {@link ScanMarkdownLinksTool} to enable reuse across audit tools.
 */
public final class MarkdownLinkScanner {

    static final Pattern LINK_PATTERN = Pattern.compile( "\\[([^\\]]*)\\]\\(([^)]+)\\)" );

    private MarkdownLinkScanner() {
    }

    /**
     * Finds all local wiki page targets in the given body text.
     * External URLs (http/https/ftp) and anchor links (#fragment) are excluded.
     *
     * @param bodyText the Markdown body text to scan
     * @return an ordered set of local page names referenced in the text
     */
    public static Set< String > findLocalLinks( final String bodyText ) {
        if ( bodyText == null || bodyText.isEmpty() ) {
            return Set.of();
        }
        final Set< String > locals = new LinkedHashSet<>();
        final Matcher matcher = LINK_PATTERN.matcher( bodyText );
        while ( matcher.find() ) {
            final String target = matcher.group( 2 );
            if ( "local".equals( classifyLink( target ) ) ) {
                locals.add( target );
            }
        }
        return locals;
    }

    /**
     * Scans the given text and returns all links with their classification.
     *
     * @param text the Markdown text to scan
     * @return list of link entries, each with keys: target, text, type
     */
    public static List< Map< String, String > > scanAll( final String text ) {
        final List< Map< String, String > > links = new ArrayList<>();
        if ( text == null || text.isEmpty() ) {
            return links;
        }
        final Matcher matcher = LINK_PATTERN.matcher( text );
        while ( matcher.find() ) {
            final String linkText = matcher.group( 1 );
            final String target = matcher.group( 2 );
            final Map< String, String > link = new LinkedHashMap<>();
            link.put( "target", target );
            link.put( "text", linkText );
            link.put( "type", classifyLink( target ) );
            links.add( link );
        }
        return links;
    }

    /**
     * Classifies a link target as external, anchor, or local.
     */
    static String classifyLink( final String target ) {
        if ( target.startsWith( "http://" ) || target.startsWith( "https://" )
                || target.startsWith( "ftp://" ) || target.startsWith( "mailto:" ) ) {
            return "external";
        }
        if ( target.startsWith( "#" ) ) {
            return "anchor";
        }
        return "local";
    }
}

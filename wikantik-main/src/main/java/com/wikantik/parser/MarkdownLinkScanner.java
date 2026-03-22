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
package com.wikantik.parser;


import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight utility for extracting Markdown-style links from raw wiki page text
 * without invoking the full rendering pipeline.
 *
 * <p>Used by the {@link com.wikantik.references.DefaultReferenceManager} for fast
 * link extraction during reference graph updates, and by MCP audit/verification
 * tools for structural analysis.
 *
 * <p>This class operates on raw Markdown text via regex — no AST construction,
 * no Flexmark, no rendering.  Typical execution time is microseconds per page.
 */
public final class MarkdownLinkScanner {

    /** Matches Markdown links: {@code [text](target)} where target may be empty (wikantik page link convention). */
    static final Pattern LINK_PATTERN = Pattern.compile( "\\[([^\\]]*)\\]\\(([^)]*)\\)" );

    private MarkdownLinkScanner() {
    }

    /**
     * Finds all local wiki page targets in the given body text.
     * External URLs (http/https/ftp/mailto) and anchor links (#fragment) are excluded.
     * Anchor suffixes ({@code PageName#section}) are stripped — only the page name is returned.
     *
     * <p>Handles both link formats used in this wiki:
     * <ul>
     *   <li>{@code [text](PageName)} — standard Markdown, page name is in the target</li>
     *   <li>{@code [PageName]()} — wikantik convention, page name is in the text (target is empty)</li>
     * </ul>
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
            final String linkText = matcher.group( 1 );
            String target = matcher.group( 2 );

            // Wikantik convention: [PageName]() — empty target means the text IS the page name
            if ( target.isEmpty() ) {
                // Skip plugin syntax [{...}]() and variable syntax [{$...}]()
                if ( !linkText.isEmpty() && !linkText.startsWith( "{" ) ) {
                    locals.add( linkText );
                }
                continue;
            }

            if ( "local".equals( classifyLink( target ) ) ) {
                // Strip anchor: [text](PageName#section) → PageName
                final int hash = target.indexOf( '#' );
                if ( hash > 0 ) {
                    target = target.substring( 0, hash );
                }
                if ( !target.isEmpty() ) {
                    locals.add( target );
                }
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

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
package com.wikantik.seo;

import com.wikantik.api.frontmatter.FrontmatterParser;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether an edit is a substantial content change, so the sitemap {@code lastmod} only moves on
 * real edits. Pure function of the two full page texts.
 *
 * @param minPercent percentage of the old word count that must change
 * @param minWords   cap on the required changed-word count (large pages need no more than this)
 */
public record ContentChangeRule( int minPercent, int minWords ) {

    private static final String FOOTER_PREFIX = "**Cluster Hub:**";
    private static final Pattern HEADING = Pattern.compile( "^#{1,6}\\s+(.*)$" );
    private static final Pattern WHITESPACE = Pattern.compile( "\\s+" );

    /**
     * Body normalised for comparison: frontmatter stripped, CRLF to LF, whitespace runs collapsed, a trailing
     * line starting {@code **Cluster Hub:**} dropped.
     *
     * @param fullPageText the full page text, may be null
     * @return the normalised body, never null
     */
    public static String normalize( final String fullPageText ) {
        return WHITESPACE.matcher( dropFooter( body( fullPageText ) ) ).replaceAll( " " ).trim();
    }

    /**
     * @param baselineText the full page text before the edit, may be null
     * @param newText      the full page text after the edit, may be null
     * @return true when {@code newText} is a substantial change from {@code baselineText}
     */
    public boolean isSubstantial( final String baselineText, final String newText ) {
        final String oldNorm = normalize( baselineText );
        final String newNorm = normalize( newText );
        if( oldNorm.isEmpty() ) {
            return !newNorm.isEmpty();
        }
        final Map< String, Integer > counts = new HashMap<>();
        for( final String w : oldNorm.split( " " ) ) {
            counts.merge( w, -1, Integer::sum );
        }
        int oldWordCount = 0;
        for( final int c : counts.values() ) {
            oldWordCount -= c;
        }
        if( !newNorm.isEmpty() ) {
            for( final String w : newNorm.split( " " ) ) {
                counts.merge( w, 1, Integer::sum );
            }
        }
        long changed = 0;
        for( final int c : counts.values() ) {
            changed += Math.abs( c );
        }
        final long threshold = Math.max( 1, Math.min( (long) Math.ceil( oldWordCount * minPercent / 100.0 ), minWords ) );
        if( changed >= threshold ) {
            return true;
        }
        final Set< String > oldHeadings = headings( body( baselineText ) );
        for( final String h : headings( body( newText ) ) ) {
            if( !oldHeadings.contains( h ) ) {
                return true;
            }
        }
        return false;
    }

    private static String body( final String fullPageText ) {
        if( fullPageText == null ) {
            return "";
        }
        final String text = fullPageText.replace( "\r\n", "\n" ).replace( '\r', '\n' );
        return text.substring( FrontmatterParser.bodyOffset( text ) );
    }

    private static String dropFooter( final String body ) {
        final String stripped = body.stripTrailing();
        final int nl = stripped.lastIndexOf( '\n' );
        final String lastLine = stripped.substring( nl + 1 );
        return lastLine.stripLeading().startsWith( FOOTER_PREFIX ) ? stripped.substring( 0, nl + 1 ) : body;
    }

    private static Set< String > headings( final String body ) {
        final Set< String > result = new HashSet<>();
        boolean inFence = false;
        for( final String line : body.split( "\n" ) ) {
            final String trimmed = line.strip();
            if( trimmed.startsWith( "```" ) || trimmed.startsWith( "~~~" ) ) {
                inFence = !inFence;
            } else if( !inFence ) {
                final Matcher m = HEADING.matcher( line );
                if( m.matches() ) {
                    result.add( m.group( 1 ).trim() );
                }
            }
        }
        return result;
    }
}

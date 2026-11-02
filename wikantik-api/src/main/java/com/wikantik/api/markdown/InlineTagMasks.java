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
package com.wikantik.api.markdown;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The raw-markup half of {@link InlineTagSyntax}'s masking: spans the renderer never makes a tag of (raw HTML including
 * attributes, HTML blocks, URLs, wikilinks, link text) are blanked in place, and a table row's pipes are opened up so a
 * cell start reads as a boundary. Everything is same-length so offsets survive.
 */
final class InlineTagMasks {

    private static final Pattern WIKILINK = Pattern.compile( "\\[\\[[^\\[\\]\\n]*\\]\\]" );
    /** An inline link or image, text and destination together: the renderer never makes a tag inside link text. */
    private static final Pattern INLINE_LINK = Pattern.compile(
            "!?\\[(?:[^\\[\\]\\n\\\\]|\\\\.)*\\]\\((?:[^()\\n]|\\([^()\\n]*\\))*\\)" );
    private static final Pattern REF_LINK = Pattern.compile( "!?\\[((?:[^\\[\\]\\n\\\\]|\\\\.)*)\\](?:\\[([^\\[\\]\\n]*)\\])?" );
    private static final Pattern REF_DEFINITION = Pattern.compile( "(?m)^ {0,3}\\[([^\\[\\]\\n]+)\\]:" );
    /** Raw HTML markup, attributes included: {@code <div style="color: #fff">} carries no tag. */
    private static final Pattern HTML_TAG = Pattern.compile( "</?[A-Za-z][A-Za-z0-9-]*"
            + "(?:\\s+[A-Za-z_:][\\w.:-]*(?:\\s*=\\s*(?:[^\\s\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*\\s*/?>" );
    private static final Pattern HTML_COMMENT = Pattern.compile( "(?s)<!--.*?-->" );
    private static final Pattern HTML_RAW_START = Pattern.compile( "(?i)^ {0,3}<(script|pre|style|textarea)(?:[\\s>]|$).*" );
    private static final Pattern HTML_RAW_END = Pattern.compile( "(?i).*</(script|pre|style|textarea)>.*" );
    private static final Pattern HTML_BLOCK_START = Pattern.compile( "(?i)^ {0,3}</?(?:address|article|aside|base|basefont|blockquote|body|caption|"
            + "center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|"
            + "h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|nav|noframes|ol|optgroup|option|p|param|"
            + "section|source|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?:[\\s>]|/>|$).*" );
    /** Anchored at the start of a non-space run: same matches as {@code \S+://\S+}, one attempt per run. */
    private static final Pattern URL = Pattern.compile( "(?<!\\S)\\S+://\\S+" );
    private InlineTagMasks() {
    }

    /** Blanks HTML (blocks, comments, tags), links (text included), wikilinks and URLs. */
    static void blankMarkup( final StringBuilder sb ) {
        blankHtmlBlocks( sb );
        blankMatches( sb, HTML_COMMENT );
        blankMatches( sb, HTML_TAG );
        blankLinks( sb );
        blankMatches( sb, WIKILINK );
        blankMatches( sb, URL );
    }

    /** CommonMark HTML blocks (raw, comment and block-level element starts): their text is never inline-parsed. */
    static void blankHtmlBlocks( final StringBuilder sb ) {
        final String s = sb.toString();
        int pos = 0;
        while ( pos < s.length() ) {
            final int eol = lineEnd( s, pos );
            final String line = s.substring( pos, eol );
            int end = -1;
            if ( HTML_RAW_START.matcher( line ).matches() ) {
                end = endOfBlock( s, pos, HTML_RAW_END, false );
            } else if ( line.stripLeading().startsWith( "<!--" ) && line.length() - line.stripLeading().length() < 4 ) {
                end = endOfBlock( s, pos, Pattern.compile( ".*-->.*" ), false );
            } else if ( HTML_BLOCK_START.matcher( line ).matches() ) {
                end = endOfBlock( s, pos, null, true );
            }
            if ( end < 0 ) {
                pos = eol + 1;
                continue;
            }
            for ( int i = pos; i < end; i++ ) {
                if ( sb.charAt( i ) != '\n' ) {
                    sb.setCharAt( i, ' ' );
                }
            }
            pos = end + 1;
        }
    }

    private static int lineEnd( final String s, final int from ) {
        final int eol = s.indexOf( '\n', from );
        return eol < 0 ? s.length() : eol;
    }

    /** End offset (exclusive) of the block starting at {@code start}: the end-condition line, or the line before a blank one. */
    private static int endOfBlock( final String s, final int start, final Pattern endLine, final boolean untilBlank ) {
        int pos = start;
        while ( pos < s.length() ) {
            final int eol = lineEnd( s, pos );
            final String line = s.substring( pos, eol );
            if ( untilBlank && line.isBlank() ) {
                return Math.max( start, pos - 1 );
            }
            if ( !untilBlank && endLine.matcher( line ).matches() ) {
                return eol;
            }
            pos = eol + 1;
        }
        return s.length();
    }

    /** Link text and destination together, for inline links, defined reference links and defined shortcut references. */
    private static void blankLinks( final StringBuilder sb ) {
        final java.util.Set< String > defined = new java.util.HashSet<>();
        final Matcher defs = REF_DEFINITION.matcher( sb );
        while ( defs.find() ) {
            defined.add( defs.group( 1 ).strip().toLowerCase( Locale.ROOT ) );
        }
        blankMatches( sb, INLINE_LINK );
        if ( defined.isEmpty() ) {
            return;
        }
        final Matcher m = REF_LINK.matcher( sb );
        final List< int[] > spans = new ArrayList<>();
        while ( m.find() ) {
            final String ref = m.group( 2 ) == null || m.group( 2 ).isBlank() ? m.group( 1 ) : m.group( 2 );
            if ( defined.contains( ref.strip().toLowerCase( Locale.ROOT ) ) ) {
                spans.add( new int[] { m.start(), m.end() } );
            }
        }
        for ( final int[] span : spans ) {
            for ( int i = span[ 0 ]; i < span[ 1 ]; i++ ) {
                sb.setCharAt( i, ' ' );
            }
        }
    }

    static void blankMatches( final StringBuilder sb, final Pattern p ) {
        final Matcher m = p.matcher( sb );
        while ( m.find() ) {
            for ( int i = m.start(); i < m.end(); i++ ) {
                sb.setCharAt( i, ' ' );
            }
        }
    }
}

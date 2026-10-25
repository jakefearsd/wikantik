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
package com.wikantik.markdown.extensions.fences;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level rules of the Mermaid safety policy (see {@link MermaidFenceRenderer}): whitespace normalisation,
 * front-matter detection, script-scheme backstop, entity decoding and statement splitting. Mirrors the
 * equivalent functions in {@code wikantik-frontend/src/utils/fences/mermaidPolicy.js}.
 */
final class MermaidTextRules {

    private static final Logger LOG = LogManager.getLogger( MermaidTextRules.class );

    private static final Pattern ENTITY = Pattern.compile( "&#[xX]([0-9a-fA-F]++);?+|&#([0-9]++);?+|&(colon|Tab|NewLine|sol);|#([0-9]++);" );
    private static final Map< String, String > NAMED_ENTITIES = Map.of( "colon", ":", "Tab", "\t", "NewLine", "\n", "sol", "/" );

    private MermaidTextRules() {
    }

    /** Leading ASCII letters of the statement, lower-cased, when followed by a space or the end; else "". */
    static String firstWord( final String s ) {
        int i = 0;
        while ( i < s.length() && ( s.charAt( i ) | 0x20 ) >= 'a' && ( s.charAt( i ) | 0x20 ) <= 'z' ) {
            i++;
        }
        return i == 0 || ( i < s.length() && s.charAt( i ) != ' ' ) ? "" : s.substring( 0, i ).toLowerCase( Locale.ROOT );
    }

    /**
     * Normalises before any analysis: only CRLF/CR become LF; every other (including U+2028, U+2029, U+0085, VT, FF)
     * whitespace, space-separator or format character (NBSP, U+2000-U+200B, U+3000, BOM, ...) becomes a space.
     */
    static String normalise( final String s ) {
        final StringBuilder sb = new StringBuilder( s.length() );
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            if ( c == '\r' ) {
                sb.append( '\n' );
                if ( i + 1 < s.length() && s.charAt( i + 1 ) == '\n' ) {
                    i++;
                }
            } else if ( c == '\n' ) {
                sb.append( '\n' );
            } else if ( Character.isWhitespace( c ) || Character.isSpaceChar( c ) || c == '\u0085' || Character.getType( c ) == Character.FORMAT ) {
                sb.append( ' ' );
            } else {
                sb.append( c );
            }
        }
        return sb.toString();
    }

    /**
     * Front matter is refused outright (no YAML interpretation): true when, after skipping leading
     * whitespace, control and format characters (Java or JS definitions), the text begins with {@code ---}.
     */
    static boolean startsFrontMatter( final String text ) {
        int i = 0;
        while ( i < text.length() ) {
            final char c = text.charAt( i );
            if ( Character.isWhitespace( c ) || Character.isSpaceChar( c ) || Character.isISOControl( c )
                    || Character.getType( c ) == Character.FORMAT || c == '﻿' ) {
                i++;
            } else {
                break;
            }
        }
        return text.startsWith( "---", i );
    }

    /** Backstop: after entity decoding and dropping whitespace/control chars, a script scheme anywhere refuses. */
    static boolean hasScriptScheme( final String source ) {
        final String decoded = decodeEntities( source );
        final StringBuilder sb = new StringBuilder( decoded.length() );
        for ( int i = 0; i < decoded.length(); i++ ) {
            final char c = decoded.charAt( i );
            if ( c > 0x20 && !( c >= 0x7f && c <= 0x9f ) ) {
                sb.append( c );
            }
        }
        final String u = sb.toString().toLowerCase( Locale.ROOT );
        return u.contains( "javascript:" ) || u.contains( "vbscript:" );
    }

    /** Splits a line on {@code ;} outside double quotes (Mermaid allows several statements per line). */
    static List< String > splitStatements( final String line ) {
        final List< String > out = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for ( int i = 0; i < line.length(); i++ ) {
            final char c = line.charAt( i );
            if ( c == '"' ) {
                quoted = !quoted;
            } else if ( c == ';' && !quoted ) {
                out.add( line.substring( start, i ) );
                start = i + 1;
            }
        }
        out.add( line.substring( start ) );
        return out;
    }

    static String decodeEntities( final String s ) {
        final Matcher m = ENTITY.matcher( s );
        final StringBuilder sb = new StringBuilder();
        while ( m.find() ) {
            String rep = m.group();
            try {
                if ( m.group( 1 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 1 ), 16 ), rep );
                } else if ( m.group( 2 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 2 ) ), rep );
                } else if ( m.group( 3 ) != null ) {
                    rep = NAMED_ENTITIES.get( m.group( 3 ) );
                } else if ( m.group( 4 ) != null ) {
                    rep = codePoint( Integer.parseInt( m.group( 4 ) ), rep );
                }
            } catch ( final NumberFormatException e ) {
                LOG.warn( "Unparseable entity '{}' in Mermaid link target; leaving it undecoded: {}", m.group(), e.getMessage() );
            }
            m.appendReplacement( sb, Matcher.quoteReplacement( rep ) );
        }
        m.appendTail( sb );
        return sb.toString();
    }

    private static String codePoint( final int cp, final String fallback ) {
        return Character.isValidCodePoint( cp ) ? new String( Character.toChars( cp ) ) : fallback;
    }
}

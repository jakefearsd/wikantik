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
package com.wikantik.api.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Obsidian-style wikilinks and embeds: {@code [[Page]]}, {@code [[Page|Alias]]},
 * {@code [[Page#Heading]]}, {@code [[#Heading]]}, {@code ![[Page]]} and {@code ![[Owner/file.png|300]]}.
 *
 * <p>Pure regex, no Flexmark. Every consumer (scanner, renamer, mentions, export) shares these rules;
 * the frontend mirrors them in {@code wikiLinkSyntax.js}. Tokens inside fenced or inline code, and
 * tokens whose opening bracket is backslash-escaped, are not wikilinks.
 */
public final class WikiLinkSyntax {

    /** Locates a wikilink or embed token. Group 1 is the embed bang, group 2 the inner text. */
    public static final Pattern TOKEN = Pattern.compile( "(!?)\\[\\[([^\\[\\]\\n]+?)]]" );
    private static final Pattern SIZE = Pattern.compile( "(\\d{1,5})(?:x(\\d{1,5}))?" );

    private WikiLinkSyntax() {
    }

    /**
     * A parsed wikilink. Offsets are relative to the string that was scanned.
     *
     * @param start    offset of the first character (the {@code !} of an embed, else the first bracket)
     * @param end      offset one past the closing brackets
     * @param embed    true for {@code ![[...]]}
     * @param target   page name or {@code Owner/file} (empty for a same-page heading link)
     * @param heading  heading after {@code #}, or null
     * @param alias    text after {@code |}, or null
     * @param nameFrom offset where the page-name part of the target begins
     * @param nameTo   offset one past the page-name part (the owner for {@code Owner/file})
     */
    public record WikiLinkRef( int start, int end, boolean embed, String target, String heading,
                               String alias, int nameFrom, int nameTo ) {

        /** True for {@code [[#Heading]]}. */
        public boolean isSamePage() {
            return target.isEmpty();
        }

        /** True when the target is {@code Owner/file}. */
        public boolean isAttachment() {
            return target.indexOf( '/' ) > 0;
        }

        /** The owning page for an attachment target, else the target. */
        public String pageName() {
            final int slash = target.indexOf( '/' );
            return slash > 0 ? target.substring( 0, slash ) : target;
        }

        /** The part after the first slash for an attachment target, else the target. */
        public String fileName() {
            final int slash = target.indexOf( '/' );
            return slash > 0 ? target.substring( slash + 1 ) : target;
        }

        /** Alias, else heading-only text, else {@code "Target > Heading"}, else the target. */
        public String displayText() {
            if ( alias != null ) {
                return alias;
            }
            if ( heading == null ) {
                return target;
            }
            return target.isEmpty() ? heading : target + " > " + heading;
        }

        /** Image size from a numeric alias: {@code 300} gives {300,-1}, {@code 300x200} gives {300,200}; else null. */
        public int[] size() {
            if ( alias == null ) {
                return null;
            }
            final Matcher m = SIZE.matcher( alias );
            if ( !m.matches() ) {
                return null;
            }
            return new int[]{ Integer.parseInt( m.group( 1 ) ), m.group( 2 ) == null ? -1 : Integer.parseInt( m.group( 2 ) ) };
        }
    }

    /** Parses a single token such as {@code [[Page|Alias]]}; offsets are relative to the token. */
    public static Optional< WikiLinkRef > parse( final String token ) {
        final Matcher m = TOKEN.matcher( token == null ? "" : token );
        return m.matches() ? build( m ) : Optional.empty();
    }

    private static Optional< WikiLinkRef > build( final Matcher m ) {
        final String inner = m.group( 2 );
        if ( inner.isEmpty() || Character.isWhitespace( inner.charAt( 0 ) ) ) {
            return Optional.empty();
        }
        final int pipe = inner.indexOf( '|' );
        final boolean escaped = pipe > 0 && inner.charAt( pipe - 1 ) == '\\';
        final String targetPart = pipe < 0 ? inner : inner.substring( 0, escaped ? pipe - 1 : pipe );
        final String alias = pipe < 0 ? null : blankToNull( inner.substring( pipe + 1 ).trim() );
        return buildFrom( m, targetPart, alias );
    }

    private static Optional< WikiLinkRef > buildFrom( final Matcher m, final String targetPart, final String alias ) {
        final int hash = targetPart.indexOf( '#' );
        final String target = ( hash < 0 ? targetPart : targetPart.substring( 0, hash ) ).stripTrailing();
        final String heading = hash < 0 ? null : blankToNull( targetPart.substring( hash + 1 ).trim() );
        if ( target.isEmpty() && heading == null ) {
            return Optional.empty();
        }
        final int slash = target.indexOf( '/' );
        final int nameFrom = m.start( 2 );
        return Optional.of( new WikiLinkRef( m.start(), m.end(), !m.group( 1 ).isEmpty(), target, heading, alias,
                nameFrom, nameFrom + ( slash > 0 ? slash : target.length() ) ) );
    }

    private static String blankToNull( final String s ) {
        return s.isEmpty() ? null : s;
    }

    /** Finds every wikilink outside fenced and inline code and not backslash-escaped, in document order. */
    public static List< WikiLinkRef > findAll( final String markdown ) {
        final List< WikiLinkRef > out = new ArrayList<>();
        if ( markdown == null || markdown.isEmpty() ) {
            return out;
        }
        final boolean[] code = CodeMask.of( markdown );
        final Matcher m = TOKEN.matcher( markdown );
        while ( m.find() ) {
            final int s = m.start();
            if ( !code[ s ] && !( s > 0 && markdown.charAt( s - 1 ) == '\\' ) ) {
                build( m ).ifPresent( out::add );
            }
        }
        return out;
    }

    /**
     * Rewrites wikilinks: {@code fn} returns the replacement token text, or null to keep the original.
     */
    public static String replaceAll( final String markdown, final Function< WikiLinkRef, String > fn ) {
        if ( markdown == null || markdown.isEmpty() ) {
            return markdown;
        }
        final StringBuilder sb = new StringBuilder( markdown.length() );
        int pos = 0;
        for ( final WikiLinkRef ref : findAll( markdown ) ) {
            final String replacement = fn.apply( ref );
            sb.append( markdown, pos, ref.start() );
            sb.append( replacement == null ? markdown.substring( ref.start(), ref.end() ) : replacement );
            pos = ref.end();
        }
        return sb.append( markdown, pos, markdown.length() ).toString();
    }

    /** Replaces links with their display text and removes embeds. */
    public static String toPlainText( final String markdown ) {
        return replaceAll( markdown, r -> r.embed() ? "" : r.displayText() );
    }
}

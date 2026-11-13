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
package com.wikantik.knowledge;

import com.wikantik.parser.markdown.ReaderText;
import com.wikantik.api.parser.InlineLinkMatcher;
import com.wikantik.api.parser.WikiLinkSyntax;

import java.util.regex.Pattern;

/**
 * Reduces wiki markdown to plain prose: frontmatter, code fences, link/image URLs,
 * headings, emphasis and HTML are removed, display text is kept. Used by the
 * frontmatter-defaults extractors ({@link SummaryExtractor}, {@link TagExtractor}).
 */
public final class MarkdownStripper {

    private MarkdownStripper() {}

    // Markdown stripping patterns
    private static final Pattern FRONTMATTER = Pattern.compile( "\\A---\\s*\\n.*?\\n---\\s*\\n", Pattern.DOTALL );
    private static final Pattern CODE_FENCE = Pattern.compile( "```[\\s\\S]*?```" );
    private static final Pattern HEADING = Pattern.compile( "^#{1,6}\\s+", Pattern.MULTILINE );
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile( "__([^_]+)__" );
    private static final Pattern BOLD_STAR = Pattern.compile( "\\*\\*([^*]+)\\*\\*" );
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile( "_([^_]+)_" );
    private static final Pattern ITALIC_STAR = Pattern.compile( "\\*([^*]+)\\*" );
    /** {@code ==highlight==}: exactly two {@code =} each side, flanked by non-space (the reader's rule); the text is kept. */
    private static final Pattern HIGHLIGHT = Pattern.compile( "(?<![=\\\\])==(?=[^\\s=])([^\\n]+?)(?<=[^\\s=])==(?!=)" );
    private static final Pattern INLINE_CODE = Pattern.compile( "`([^`]+)`" );
    /** One left-to-right pass, so a code span keeps its text verbatim ({@code `==x==`} keeps its {@code ==}). */
    private static final Pattern CODE_OR_HIGHLIGHT = Pattern.compile( INLINE_CODE.pattern() + "|" + HIGHLIGHT.pattern() );
    private static final Pattern MULTI_SPACE = Pattern.compile( "\\s+" );

    /**
     * Strips markdown formatting from text, keeping only prose content.
     * Code fences are discarded entirely; link/image URLs are discarded
     * but their display text is kept.
     */
    public static String stripMarkdown( final String markdown ) {
        if( markdown == null || markdown.isEmpty() ) return "";
        String s = markdown;
        s = FRONTMATTER.matcher( s ).replaceAll( "" );
        s = ReaderText.derivedBody( s );
        s = CODE_FENCE.matcher( s ).replaceAll( " " );
        s = WikiLinkSyntax.toPlainText( s );
        s = keepLinkText( s, InlineLinkMatcher.images( s ) );
        s = keepLinkText( s, new InlineLinkMatcher( s ) );
        s = HEADING.matcher( s ).replaceAll( "" );
        s = BOLD_UNDERSCORE.matcher( s ).replaceAll( "$1" );
        s = BOLD_STAR.matcher( s ).replaceAll( "$1" );
        s = ITALIC_UNDERSCORE.matcher( s ).replaceAll( "$1" );
        s = ITALIC_STAR.matcher( s ).replaceAll( "$1" );
        s = CODE_OR_HIGHLIGHT.matcher( s ).replaceAll( m -> java.util.regex.Matcher.quoteReplacement(
                m.group( 1 ) != null ? m.group( 1 ) : m.group( 2 ) ) );
        s = blankHtmlTags( s );
        s = MULTI_SPACE.matcher( s ).replaceAll( " " );
        return s.trim();
    }

    /** Replaces each link (or image) the matcher finds with its display text. */
    private static String keepLinkText( final String s, final InlineLinkMatcher links ) {
        StringBuilder out = null;
        int copied = 0;
        while( links.find() ) {
            if( out == null ) out = new StringBuilder( s.length() );
            out.append( s, copied, links.start() ).append( links.text() );
            copied = links.end();
        }
        return out == null ? s : out.append( s, copied, s.length() ).toString();
    }

    /**
     * Replaces each {@code <...>} run (at least one character between the brackets) with a space: what the regex
     * {@code <[^>]+>} did, without rescanning to the next {@code >} from every {@code <}.
     */
    static String blankHtmlTags( final String s ) {
        StringBuilder out = null;
        int copied = 0;
        int pos = 0;
        while( true ) {
            final int open = s.indexOf( '<', pos );
            final int close = open < 0 ? -1 : s.indexOf( '>', open + 1 );
            if( close < 0 ) break;
            pos = close + 1;
            if( close == open + 1 ) continue; // "<>" is not a tag
            if( out == null ) out = new StringBuilder( s.length() );
            out.append( s, copied, open ).append( ' ' );
            copied = pos;
        }
        return out == null ? s : out.append( s, copied, s.length() ).toString();
    }
}

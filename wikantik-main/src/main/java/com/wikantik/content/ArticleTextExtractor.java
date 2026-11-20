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
package com.wikantik.content;

import java.util.regex.Pattern;

/**
 * Engine-independent text extraction for recent-article summaries: the display title from page text or rendered
 * HTML, and a plain-text excerpt from rendered HTML.
 */
final class ArticleTextExtractor {

    /** Regex to match Markdown H1 headings ({@code # Title}). */
    private static final Pattern H1_WIKI_PATTERN = Pattern.compile( "^\\s*#\\s+(.+?)\\s*$", Pattern.MULTILINE );

    /** Regex to match H1 in rendered HTML. */
    private static final Pattern H1_HTML_PATTERN = Pattern.compile( "<h1[^>]*>([^<]+)</h1>", Pattern.CASE_INSENSITIVE );

    /** Regex for stripping HTML tags. */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile( "<[^>]+>" );

    /** Regex for collapsing whitespace. */
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile( "\\s+" );

    /** Regex pattern for numeric HTML entities (&#123; or &#x1F4A1;). */
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile( "&#(x?)([0-9a-fA-F]+);" );

    private ArticleTextExtractor() {
    }

    /**
     * Extracts a display title from the page text (H1 heading in markup) or, failing that, the rendered HTML.
     *
     * @return the H1 text, or {@code null} when neither source holds a non-empty H1 (the caller then falls back to
     *         the page name)
     */
    static String title( final String pageText, final String renderedHtml ) {
        // Try to find H1 in wiki markup first
        if ( pageText != null ) {
            final var matcher = H1_WIKI_PATTERN.matcher( pageText );
            if ( matcher.find() ) {
                final String h1 = matcher.group( 1 ).trim();
                if ( !h1.isEmpty() ) {
                    return h1;
                }
            }
        }

        // Try to find H1 in rendered HTML
        if ( renderedHtml != null ) {
            final var matcher = H1_HTML_PATTERN.matcher( renderedHtml );
            if ( matcher.find() ) {
                final String h1 = stripHtml( matcher.group( 1 ) ).trim();
                if ( !h1.isEmpty() ) {
                    return h1;
                }
            }
        }
        return null;
    }

    /**
     * Extracts an excerpt from rendered HTML content.
     */
    static String excerpt( final String html, final int maxLength ) {
        if ( html == null || html.isEmpty() ) {
            return null;
        }

        // Strip HTML tags
        String text = stripHtml( html );

        // Collapse whitespace
        text = WHITESPACE_PATTERN.matcher( text ).replaceAll( " " ).trim();

        // Skip if too short
        if ( text.length() < 20 ) {
            return null;
        }

        // Truncate to max length
        if ( text.length() > maxLength ) {
            // Find a good break point (word boundary)
            int breakPoint = text.lastIndexOf( ' ', maxLength );
            if ( breakPoint < maxLength / 2 ) {
                breakPoint = maxLength;
            }
            text = text.substring( 0, breakPoint ).trim() + "...";
        }

        return text;
    }

    /**
     * Strips HTML tags from a string and decodes HTML entities.
     */
    static String stripHtml( final String html ) {
        if ( html == null ) {
            return "";
        }

        // Remove HTML tags first
        String text = HTML_TAG_PATTERN.matcher( html ).replaceAll( "" );

        // Decode numeric HTML entities (&#123; or &#x1F4A1;)
        text = decodeNumericEntities( text );

        // Decode named HTML entities (most common ones)
        text = text
            // Whitespace and special characters
            .replace( "&nbsp;", " " )
            .replace( "&ensp;", " " )
            .replace( "&emsp;", " " )
            .replace( "&thinsp;", " " )
            // Basic entities (process &amp; last to avoid double-decoding)
            .replace( "&lt;", "<" )
            .replace( "&gt;", ">" )
            .replace( "&quot;", "\"" )
            .replace( "&apos;", "'" )
            // Punctuation and symbols
            .replace( "&ndash;", "–" )
            .replace( "&mdash;", "—" )
            .replace( "&lsquo;", "'" )
            .replace( "&rsquo;", "'" )
            .replace( "&ldquo;", "\u201C" )  // left double quotation mark
            .replace( "&rdquo;", "\u201D" )  // right double quotation mark
            .replace( "&hellip;", "…" )
            .replace( "&bull;", "•" )
            .replace( "&middot;", "·" )
            // Copyright and trademark
            .replace( "&copy;", "©" )
            .replace( "&reg;", "®" )
            .replace( "&trade;", "™" )
            // Currency
            .replace( "&cent;", "¢" )
            .replace( "&pound;", "£" )
            .replace( "&euro;", "€" )
            .replace( "&yen;", "¥" )
            // Math symbols
            .replace( "&times;", "×" )
            .replace( "&divide;", "÷" )
            .replace( "&plusmn;", "±" )
            .replace( "&deg;", "°" )
            // Process &amp; last to avoid double-decoding issues
            .replace( "&amp;", "&" );

        return text;
    }

    /**
     * Decodes numeric HTML entities (both decimal and hexadecimal).
     * Examples: &#65; -> A, &#x41; -> A
     */
    private static String decodeNumericEntities( final String text ) {
        if ( text == null || !text.contains( "&#" ) ) {
            return text;
        }

        final var matcher = NUMERIC_ENTITY_PATTERN.matcher( text );
        final var result = new StringBuilder();

        while ( matcher.find() ) {
            final boolean isHex = !matcher.group( 1 ).isEmpty();
            final String numStr = matcher.group( 2 );
            try {
                final int codePoint = Integer.parseInt( numStr, isHex ? 16 : 10 );
                if ( Character.isValidCodePoint( codePoint ) ) {
                    matcher.appendReplacement( result, new String( Character.toChars( codePoint ) ) );
                } else {
                    // Invalid code point, keep original
                    matcher.appendReplacement( result, matcher.group( 0 ) );
                }
            } catch ( final NumberFormatException e ) {
                // Keep original if parsing fails
                matcher.appendReplacement( result, matcher.group( 0 ) );
            }
        }
        matcher.appendTail( result );

        return result.toString();
    }
}

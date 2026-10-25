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

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** CommonMark HTML-block start/end detection for {@link RenderedFenceScanner}. */
final class HtmlBlockRules {

    private static final Pattern TAG_ONLY = Pattern.compile(
            "(?:<[A-Za-z][A-Za-z0-9-]*+(?:\\s++[A-Za-z_:][A-Za-z0-9_.:-]*+(?:\\s*+=\\s*+(?:\"[^\"]*+\"|'[^']*+'|[^\\s\"'=<>`]++))?+)*+\\s*+/?>"
                    + "|</[A-Za-z][A-Za-z0-9-]*+\\s*+>)\\s*+" );

    private static final Set< String > RAW_TEXT_TAGS = Set.of( "script", "pre", "style", "textarea" );

    private static final Set< String > BLOCK_TAGS = Set.of( "address", "article", "aside", "base", "basefont",
            "blockquote", "body", "caption", "center", "col", "colgroup", "dd", "details", "dialog", "dir", "div",
            "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset", "h1", "h2", "h3",
            "h4", "h5", "h6", "head", "header", "hr", "html", "iframe", "legend", "li", "link", "main", "menu",
            "menuitem", "nav", "noframes", "ol", "optgroup", "option", "p", "param", "search", "section",
            "summary", "table", "tbody", "td", "tfoot", "th", "thead", "title", "tr", "track", "ul" );

    private HtmlBlockRules() {
    }

    /** CommonMark HTML block start type (1-7) for a line whose first non-indent char {@code <} is at {@code p}; 0 if none. */
    static int htmlStartType( final String line, final int p, final boolean prevParagraph ) {
        final int marker = markerStartType( line, p );
        return marker != 0 ? marker : tagStartType( line, p, prevParagraph );
    }

    /** Start types 2-5, which are recognised by a literal marker rather than a tag name; 0 if none. */
    private static int markerStartType( final String line, final int p ) {
        if ( line.startsWith( "<!--", p ) ) {
            return 2;
        }
        if ( line.startsWith( "<?", p ) ) {
            return 3;
        }
        if ( line.startsWith( "<![CDATA[", p ) ) {
            return 5;
        }
        return isDeclarationStart( line, p ) ? 4 : 0;
    }

    private static boolean isDeclarationStart( final String line, final int p ) {
        return p + 2 < line.length() && line.charAt( p + 1 ) == '!' && Character.isLetter( line.charAt( p + 2 ) )
                && line.charAt( p + 2 ) < 128;
    }

    /** Start types 1, 6 and 7, which depend on the tag name; 0 if none. */
    private static int tagStartType( final String line, final int p, final boolean prevParagraph ) {
        final int n = line.length();
        final boolean closing = p + 1 < n && line.charAt( p + 1 ) == '/';
        final int nameStart = closing ? p + 2 : p + 1;
        final int e = asciiAlnumEnd( line, nameStart );
        if ( e == nameStart ) {
            return 0;
        }
        final String name = line.substring( nameStart, e ).toLowerCase( Locale.ROOT );
        final boolean delimited = isDelimited( line, e );
        if ( !closing && delimited && RAW_TEXT_TAGS.contains( name ) && ( e >= n || line.charAt( e ) != '/' ) ) {
            return 1;
        }
        if ( delimited && BLOCK_TAGS.contains( name ) ) {
            return 6;
        }
        return !prevParagraph && TAG_ONLY.matcher( line.substring( p ) ).matches() ? 7 : 0;
    }

    private static int asciiAlnumEnd( final String line, final int from ) {
        int e = from;
        while ( e < line.length() && Character.isLetterOrDigit( line.charAt( e ) ) && line.charAt( e ) < 128 ) {
            e++;
        }
        return e;
    }

    /** A tag name is delimited when followed by end of line, space, tab, {@code >} or {@code />}. */
    private static boolean isDelimited( final String line, final int e ) {
        return e >= line.length() || line.charAt( e ) == ' ' || line.charAt( e ) == '\t'
                || line.charAt( e ) == '>' || line.startsWith( "/>", e );
    }

    /** True when {@code line} (from {@code p}) contains the end marker of an HTML block of {@code type} 1-5. */
    static boolean htmlEnds( final int type, final String line, final int p ) {
        return switch ( type ) {
            case 1 -> containsIgnoreCase( line, p, "</script>" ) || containsIgnoreCase( line, p, "</pre>" )
                    || containsIgnoreCase( line, p, "</style>" ) || containsIgnoreCase( line, p, "</textarea>" );
            case 2 -> line.indexOf( "-->", p ) >= 0;
            case 3 -> line.indexOf( "?>", p ) >= 0;
            case 4 -> line.indexOf( '>', p ) >= 0;
            default -> line.indexOf( "]]>", p ) >= 0;
        };
    }

    private static boolean containsIgnoreCase( final String line, final int from, final String needle ) {
        final int last = line.length() - needle.length();
        for ( int i = from; i <= last; i++ ) {
            if ( line.regionMatches( true, i, needle, 0, needle.length() ) ) {
                return true;
            }
        }
        return false;
    }
}

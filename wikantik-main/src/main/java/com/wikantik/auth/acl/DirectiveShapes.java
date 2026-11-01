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
package com.wikantik.auth.acl;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.util.AclDirectiveScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * The fail-closed half of {@link AclDirectiveText}: what counts as directive-shaped when the render path's own reading
 * cannot be had, decided by plain scans with no Markdown parse — every span either reading could take for a rule, and
 * whether any such text is left.
 */
final class DirectiveShapes {

    private DirectiveShapes() {
    }

    /**
     * Every directive-shaped span, code or not, each with an empty {@code ()} after it: one-line {@code ALLOW}/{@code DENY}
     * spans anywhere (a superset of the text scan) and, in the body, wrapped ones and every <code>[{</code> whose keyword
     * starts {@code allow} or {@code deny} in any case, up to the first <code>}]</code> with no blank line between (a
     * superset of the render path's link-text rules, whatever the permission's spelling).
     */
    static List< int[] > directiveShaped( final String text ) {
        final List< int[] > ranges = new ArrayList<>();
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( text, true, false ) ) {
            ranges.add( new int[]{ span.start(), AclDirectiveScanner.endWithEmptyParentheses( text, span ) } );
        }
        final int bodyStart = FrontmatterParser.bodyOffset( text );
        final String body = text.substring( bodyStart );
        for ( final AclDirectiveScanner.Span span : AclDirectiveScanner.findAll( body, true, true ) ) {
            ranges.add( new int[]{ bodyStart + span.start(), bodyStart + AclDirectiveScanner.endWithEmptyParentheses( body, span ) } );
        }
        ruleShaped( body, bodyStart, ranges );
        return AclDirectiveText.merged( text, ranges );
    }

    /**
     * For each <code>}]</code> in {@code body}, the nearest <code>[{</code> before it (after the previous one paired, with
     * no blank line between) whose keyword — after any spaces — starts {@code allow} or {@code deny} in any case: the
     * renderer's own pairing (a closing {@code ]} takes the nearest open {@code [}), less its knowledge of code, HTML and
     * math. One linear pass.
     */
    private static void ruleShaped( final String body, final int offset, final List< int[] > out ) {
        int opener = -1;
        int i = 0;
        while ( i < body.length() ) {
            if ( body.startsWith( "[{", i ) && keywordShaped( body, i + 2 ) ) {
                opener = i;
                i += 2;
            } else if ( body.startsWith( "}]", i ) && opener >= 0 ) {
                final int end = body.startsWith( "()", i + 2 ) ? i + 4 : i + 2;
                out.add( new int[]{ offset + opener, offset + end } );
                opener = -1;
                i = end;
            } else {
                if ( blankLineStartsAt( body, i ) ) {
                    opener = -1;
                }
                i++;
            }
        }
    }

    private static boolean keywordShaped( final String text, final int from ) {
        int i = from;
        while ( i < text.length() && Character.isWhitespace( text.charAt( i ) ) ) {
            i++;
        }
        return text.regionMatches( true, i, "ALLOW", 0, 5 ) || text.regionMatches( true, i, "DENY", 0, 4 );
    }

    /** Whether a line ending at {@code i} begins a blank line (only spaces and tabs before the next line ending). */
    private static boolean blankLineStartsAt( final String text, final int i ) {
        final char c = text.charAt( i );
        if ( c != '\n' && c != '\r' ) {
            return false;
        }
        int j = c == '\r' && i + 1 < text.length() && text.charAt( i + 1 ) == '\n' ? i + 2 : i + 1;
        while ( j < text.length() && ( text.charAt( j ) == ' ' || text.charAt( j ) == '\t' ) ) {
            j++;
        }
        return j < text.length() && ( text.charAt( j ) == '\n' || text.charAt( j ) == '\r' );
    }

    /**
     * Whether either reading could still find a rule, decided without the render path's parse: a one-line
     * {@code ALLOW}/{@code DENY} span anywhere, or a <code>{ALLOW</code> / <code>{DENY</code> in the body with a {@code ]},
     * {@code )} or {@code >} somewhere after it. Every rule the render path reads is a link whose target starts so, and a
     * link's text or destination is closed by one of those.
     */
    static boolean stillReadable( final String text ) {
        if ( !AclDirectiveScanner.findAll( text, true, false ).isEmpty() ) {
            return true;
        }
        final String body = text.substring( FrontmatterParser.bodyOffset( text ) );
        final int first = firstOf( body, body.indexOf( "{ALLOW" ), body.indexOf( "{DENY" ) );
        return first >= 0 && Math.max( body.lastIndexOf( ']' ), Math.max( body.lastIndexOf( ')' ), body.lastIndexOf( '>' ) ) ) > first;
    }

    private static int firstOf( final String body, final int a, final int b ) {
        if ( a < 0 ) {
            return b;
        }
        return b < 0 ? a : Math.min( a, b );
    }
}

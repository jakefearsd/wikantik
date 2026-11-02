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

import com.wikantik.auth.acl.AccessRules.Shape;
import com.wikantik.auth.permissions.PagePermission;

import java.util.Locale;
import java.util.Set;

/** How a directive-shaped span is spelled, for {@link AccessRules}: what is wrong with it, worst first. */
final class DirectiveSpelling {

    private static final Set< String > PERMISSIONS = Set.of( PagePermission.COMMENT_ACTION, PagePermission.DELETE_ACTION,
            PagePermission.EDIT_ACTION, PagePermission.MODIFY_ACTION, PagePermission.RENAME_ACTION, PagePermission.UPLOAD_ACTION,
            PagePermission.VIEW_ACTION );

    private static final String OPENER = "[{";
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private DirectiveSpelling() {
    }

    /**
     * What is wrong with how the span {@code text[start, end)} is written, worst first; {@code null} when it is written
     * canonically.
     *
     * @param lineEnds where {@code text}'s line ends are; ask for spans in ascending order of {@code start}
     */
    static Shape of( final String text, final int start, final int end, final LineEnds lineEnds ) {
        int i = start + OPENER.length();
        final boolean leadingSpace = i < end && Character.isWhitespace( text.charAt( i ) );
        i = DirectiveCandidates.skipWhitespace( text, i, end );
        final int keywordEnd = wordEnd( text, i, end );
        final String keyword = text.substring( i, keywordEnd );
        if ( keyword.regionMatches( true, 0, DENY, 0, DENY.length() ) ) {
            return Shape.DENY;
        }
        if ( !keyword.regionMatches( true, 0, ALLOW, 0, ALLOW.length() ) ) {
            return Shape.UNREADABLE;     // never a candidate, whichever reading found it
        }
        if ( keyword.length() > ALLOW.length() ) {
            return Shape.ALLOW_PREFIXED_WORD;
        }
        boolean caseVariant = !keyword.equals( ALLOW );
        final int permissionStart = DirectiveCandidates.skipWhitespace( text, keywordEnd, end );
        final String permission = text.substring( permissionStart, wordEnd( text, permissionStart, end ) );
        if ( permission.isEmpty() ) {
            return Shape.BAD_PERMISSION;
        }
        for ( final String action : permission.split( ",", -1 ) ) {
            if ( !PERMISSIONS.contains( action.toLowerCase( Locale.ROOT ) ) ) {
                return Shape.BAD_PERMISSION;
            }
        }
        caseVariant |= !permission.equals( permission.toLowerCase( Locale.ROOT ) );
        if ( lineEnds.within( start, end ) ) {
            return Shape.WRAPPED;
        }
        if ( leadingSpace ) {
            return Shape.LEADING_SPACE;
        }
        return caseVariant ? Shape.CASE_VARIANT : null;
    }

    /**
     * The canonical form of a rule line read from a rule-shaped span ({@code allow VIEW Bob}, {@code ALLOW edit a,\n b}):
     * {@code ALLOW}, one space, the permission in lower case, one space, then the principals with every run of whitespace
     * that holds a line end (a wrapped rule's line break and indent) joined to one space. Other whitespace in the
     * principals stays as written: the ACL parser trims each principal, but keeps what is inside one.
     *
     * @param ruleLine a rule line without its braces, its keyword {@code allow} in any case
     * @return the canonical rule line, e.g. {@code ALLOW edit a, b}
     */
    static String canonical( final String ruleLine ) {
        final String line = ruleLine.strip();
        final int keywordEnd = nonSpaceEnd( line, 0 );
        final int permissionStart = DirectiveCandidates.skipWhitespace( line, keywordEnd, line.length() );
        final int permissionEnd = nonSpaceEnd( line, permissionStart );
        final int principalsStart = DirectiveCandidates.skipWhitespace( line, permissionEnd, line.length() );
        final StringBuilder out = new StringBuilder( line.length() ).append( ALLOW ).append( ' ' )
                .append( line.substring( permissionStart, permissionEnd ).toLowerCase( Locale.ROOT ) );
        if ( principalsStart < line.length() ) {
            out.append( ' ' );
            appendJoined( line, principalsStart, out );
        }
        return out.toString();
    }

    /** Appends {@code text} from {@code from}, each whitespace run that holds a line end replaced by one space. */
    private static void appendJoined( final String text, final int from, final StringBuilder out ) {
        int i = from;
        while ( i < text.length() ) {
            if ( !Character.isWhitespace( text.charAt( i ) ) ) {
                out.append( text.charAt( i++ ) );
                continue;
            }
            final int runEnd = DirectiveCandidates.skipWhitespace( text, i, text.length() );
            if ( hasLineTerminator( text, i, runEnd ) ) {
                out.append( ' ' );
            } else {
                out.append( text, i, runEnd );
            }
            i = runEnd;
        }
    }

    private static int nonSpaceEnd( final String text, final int from ) {
        int i = from;
        while ( i < text.length() && !Character.isWhitespace( text.charAt( i ) ) ) {
            i++;
        }
        return i;
    }

    /** Whether the character at {@code at} follows an odd run of backslashes. */
    static boolean escaped( final String text, final int at ) {
        int backslashes = 0;
        for ( int i = at - 1; i >= 0 && text.charAt( i ) == '\\'; i-- ) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    /** End of the word at {@code from}: up to whitespace, a closing brace, or {@code to}. */
    private static int wordEnd( final String text, final int from, final int to ) {
        int i = from;
        while ( i < to && !Character.isWhitespace( text.charAt( i ) ) && text.charAt( i ) != '}' ) {
            i++;
        }
        return i;
    }

    private static boolean hasLineTerminator( final String text, final int from, final int to ) {
        for ( int i = from; i < to; i++ ) {
            if ( isLineTerminator( text.charAt( i ) ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLineTerminator( final char c ) {
        return c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029';
    }

    /**
     * Whether a span holds a line end, answered in linear time over a whole text: candidate spans that share one closer
     * overlap (<code>[{ALLOW edit a [{ALLOW edit a … }]</code>), so scanning each one would be quadratic. Ask in
     * ascending order of span start.
     */
    static final class LineEnds {
        private final String text;
        /** The first line end at or after the last start asked about; {@code text.length()} when there is none. */
        private int next = -1;
        private int askedFrom = -1;

        LineEnds( final String text ) {
            this.text = text;
        }

        /** Whether {@code text[from, to)} holds a line end; {@code from} may not go down between calls. */
        boolean within( final int from, final int to ) {
            if ( from < askedFrom ) {
                throw new IllegalArgumentException( "spans must be asked about in ascending order: " + from + " < " + askedFrom );
            }
            askedFrom = from;
            if ( next < from ) {
                next = from;
                while ( next < text.length() && !isLineTerminator( text.charAt( next ) ) ) {
                    next++;
                }
            }
            return next < to;
        }
    }
}

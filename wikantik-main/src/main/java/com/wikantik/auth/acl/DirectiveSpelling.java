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
     */
    static Shape of( final String text, final int start, final int end ) {
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
        if ( hasLineTerminator( text, start, end ) ) {
            return Shape.WRAPPED;
        }
        if ( leadingSpace ) {
            return Shape.LEADING_SPACE;
        }
        return caseVariant ? Shape.CASE_VARIANT : null;
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
            final char c = text.charAt( i );
            if ( c == '\n' || c == '\r' || c == '\u0085' || c == '\u2028' || c == '\u2029' ) {
                return true;
            }
        }
        return false;
    }
}

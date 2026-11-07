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
package com.wikantik.collab.text;

import com.wikantik.api.pages.PageSaveHelper;

/**
 * Text rules of the collaboration layer: documents are LF-only (CodeMirror's internal form) and lengths are UTF-16
 * code units, so a change must never cut a surrogate pair in two.
 */
public final class CollabText {

    private CollabText() {
    }

    /**
     * The collaboration layer's content hash of a page body: SHA-256 hex of {@link #toLf}{@code (body)}, so a stored
     * CRLF body and its LF form hash alike. Meta is not part of the hash; it is compared separately.
     */
    public static String contentHash( final String body ) {
        return PageSaveHelper.computeContentHash( toLf( body ) );
    }

    /** {@code text} with every {@code \r\n} and lone {@code \r} turned into {@code \n}; LF-only text is returned as is. */
    public static String toLf( final String text ) {
        if ( text.indexOf( '\r' ) < 0 ) {
            return text;
        }
        final StringBuilder out = new StringBuilder( text.length() );
        for ( int i = 0; i < text.length(); i++ ) {
            final char c = text.charAt( i );
            if ( c == '\r' ) {
                out.append( '\n' );
                if ( i + 1 < text.length() && text.charAt( i + 1 ) == '\n' ) {
                    i++;
                }
            } else {
                out.append( c );
            }
        }
        return out.toString();
    }

    /** Whether {@code s} holds a high surrogate not followed by a low one, or a low surrogate not preceded by a high one. */
    public static boolean hasLoneSurrogate( final CharSequence s ) {
        for ( int i = 0; i < s.length(); i++ ) {
            final char c = s.charAt( i );
            if ( Character.isHighSurrogate( c ) ) {
                if ( i + 1 < s.length() && Character.isLowSurrogate( s.charAt( i + 1 ) ) ) {
                    i++;
                } else {
                    return true;
                }
            } else if ( Character.isLowSurrogate( c ) ) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any change in {@code cs} starts or ends between the high and the low surrogate of a pair in {@code doc}.
     *
     * @throws IllegalArgumentException when {@code cs} does not apply to a document of {@code doc}'s length
     */
    public static boolean splitsSurrogatePair( final String doc, final ChangeSet cs ) {
        if ( doc.length() != cs.length() ) {
            throw new IllegalArgumentException( "Change set of length " + cs.length() + " does not apply to a document of length "
                    + doc.length() );
        }
        // Adjacent changes are merged first: two changes that each take one half of a pair replace it whole.
        for ( final int[] range : cs.touchedRanges() ) {
            if ( insidePair( doc, range[ 0 ] ) || insidePair( doc, range[ 1 ] ) ) {
                return true;
            }
        }
        return false;
    }

    private static boolean insidePair( final String doc, final int pos ) {
        return pos > 0 && pos < doc.length()
                && Character.isHighSurrogate( doc.charAt( pos - 1 ) ) && Character.isLowSurrogate( doc.charAt( pos ) );
    }
}

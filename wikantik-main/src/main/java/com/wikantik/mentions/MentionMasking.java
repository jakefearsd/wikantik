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
package com.wikantik.mentions;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks regions of a draft that must never match (frontmatter, plugin spans, bare URLs) without moving offsets. */
final class MentionMasking {

    private static final Pattern PLUGIN = Pattern.compile( "\\[\\{.*?}]", Pattern.DOTALL );
    private static final Pattern BARE_URL = Pattern.compile( "\\b(?:https?://|www\\.)[^\\s)>\\]\"]+" );

    private MentionMasking() {}

    /**
     * Replaces frontmatter, plugin spans and bare URLs with spaces (newlines kept) so they can't match, while
     * every offset and line number stays identical to {@code text}.
     */
    static String mask( final String text ) {
        final char[] chars = text.toCharArray();
        final int fmEnd = frontmatterEnd( text );
        for ( int i = 0; i < fmEnd; i++ ) {
            blank( chars, i );
        }
        for ( final Pattern p : List.of( PLUGIN, BARE_URL ) ) {
            final Matcher m = p.matcher( text );
            while ( m.find() ) {
                for ( int i = m.start(); i < m.end(); i++ ) {
                    blank( chars, i );
                }
            }
        }
        return new String( chars );
    }

    private static void blank( final char[] chars, final int i ) {
        if ( chars[ i ] != '\n' && chars[ i ] != '\r' ) {
            chars[ i ] = ' ';
        }
    }

    /** Offset just past a leading {@code ---} … {@code ---} frontmatter block, or 0 when there is none. */
    static int frontmatterEnd( final String text ) {
        if ( !text.startsWith( "---" ) ) {
            return 0;
        }
        final Matcher close = Pattern.compile( "\\r?\\n---[ \\t]*(\\r?\\n|$)" ).matcher( text );
        return close.find( 3 ) ? close.end() : 0;
    }
}

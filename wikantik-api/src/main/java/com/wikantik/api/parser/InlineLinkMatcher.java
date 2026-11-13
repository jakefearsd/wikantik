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

/**
 * Finds {@code [text](target)} runs in one forward pass, matching exactly what the regex
 * {@code \[([^\]]*)\]\(([^)]*)\)} matches (leftmost opener, text up to the first {@code ]}, target up to the
 * first {@code )}) but in linear time. The regex rescans to the next {@code ]} from every {@code [}, so a body of
 * unclosed openers costs quadratic time; this skips every opener that shares a closer already known to fail.
 *
 * <p>Used like a {@link java.util.regex.Matcher}: call {@link #find()} until it returns false. Not thread-safe.
 */
public final class InlineLinkMatcher {

    private final String s;
    private final String opener;
    private int cursor;
    private int start = -1;
    private int textEnd;
    private int end;
    /** Set once a search for {@code )} ran off the end: no later match can exist. */
    private boolean exhausted;

    /** Matches {@code [text](target)}. */
    public InlineLinkMatcher( final String s ) {
        this( s, "[" );
    }

    private InlineLinkMatcher( final String s, final String opener ) {
        this.s = s;
        this.opener = opener;
    }

    /** Matches {@code ![text](target)} only; {@link #start()} is the {@code !}. */
    public static InlineLinkMatcher images( final String s ) {
        return new InlineLinkMatcher( s, "![" );
    }

    /** Advances to the next match after the previous one; false when there is none. */
    public boolean find() {
        return find( cursor );
    }

    /** Advances to the first match starting at or after {@code from}; false when there is none. */
    public boolean find( final int from ) {
        int pos = from;
        while ( !exhausted ) {
            final int open = s.indexOf( opener, pos );
            if ( open < 0 ) {
                break;
            }
            final int close = s.indexOf( ']', open + opener.length() );
            if ( close < 0 || close + 1 >= s.length() ) {
                break;
            }
            if ( s.charAt( close + 1 ) != '(' ) {
                pos = close + 1; // every opener before this ] shares it, and fails the same way
                continue;
            }
            final int paren = s.indexOf( ')', close + 2 );
            if ( paren < 0 ) {
                break; // no ) anywhere to the right: nothing later can match either
            }
            start = open;
            textEnd = close;
            end = paren + 1;
            cursor = end;
            return true;
        }
        exhausted = true;
        start = -1;
        return false;
    }

    /** Offset of the match's first character. */
    public int start() {
        requireMatch();
        return start;
    }

    /** Offset just past the match's closing {@code )}. */
    public int end() {
        requireMatch();
        return end;
    }

    /** The text between the brackets. */
    public String text() {
        requireMatch();
        return s.substring( start + opener.length(), textEnd );
    }

    /** The text between the parentheses. */
    public String target() {
        requireMatch();
        return s.substring( textEnd + 2, end - 1 );
    }

    private void requireMatch() {
        if ( start < 0 ) {
            throw new IllegalStateException( "No match found" );
        }
    }
}

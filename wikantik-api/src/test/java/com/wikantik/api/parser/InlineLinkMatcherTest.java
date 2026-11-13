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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InlineLinkMatcherTest {

    /** The regexes this class replaced; it must match exactly what they match. */
    private static final Pattern LINK = Pattern.compile( "\\[([^\\]]*)\\]\\(([^)]*)\\)" );
    private static final Pattern IMAGE = Pattern.compile( "!\\[([^\\]]*)\\]\\(([^)]*)\\)" );

    private static List< String > viaRegex( final Pattern p, final String s ) {
        final List< String > out = new ArrayList<>();
        final Matcher m = p.matcher( s );
        while ( m.find() ) {
            out.add( m.start() + ":" + m.end() + ":" + m.group( 1 ) + ":" + m.group( 2 ) );
        }
        return out;
    }

    private static List< String > viaMatcher( final InlineLinkMatcher m ) {
        final List< String > out = new ArrayList<>();
        while ( m.find() ) {
            out.add( m.start() + ":" + m.end() + ":" + m.text() + ":" + m.target() );
        }
        return out;
    }

    @Test
    void findsTextAndTarget() {
        final InlineLinkMatcher m = new InlineLinkMatcher( "see [the page](Target#sec) and []() end" );
        assertTrue( m.find() );
        assertEquals( "the page", m.text() );
        assertEquals( "Target#sec", m.target() );
        assertEquals( 4, m.start() );
        assertEquals( 26, m.end() );
        assertTrue( m.find() );
        assertEquals( "", m.text() );
        assertEquals( "", m.target() );
        assertFalse( m.find() );
    }

    @Test
    void imagesMatchOnlyBangPrefixedLinksEvenInsideALink() {
        final InlineLinkMatcher m = InlineLinkMatcher.images( "[![alt](img.png)](Page) [plain](P)" );
        assertTrue( m.find() );
        assertEquals( 1, m.start() );
        assertEquals( "alt", m.text() );
        assertEquals( "img.png", m.target() );
        assertFalse( m.find() );
    }

    @Test
    void groupsBeforeAMatchAreRefused() {
        final InlineLinkMatcher m = new InlineLinkMatcher( "none" );
        assertThrows( IllegalStateException.class, m::text );
        assertFalse( m.find() );
        assertThrows( IllegalStateException.class, m::start );
    }

    @Test
    void findFromAnOffsetSkipsEarlierMatches() {
        final InlineLinkMatcher m = new InlineLinkMatcher( "[a](b) [c](d)" );
        assertTrue( m.find( 1 ) );
        assertEquals( "c", m.text() );
    }

    @Test
    void matchesExactlyWhatTheRegexMatchesOnRandomLinkShapedText() {
        final char[] alphabet = { '[', ']', '(', ')', '!', 'a', ' ', '\n', '[', ']' };
        final Random random = new Random( 20261112L );
        for ( int round = 0; round < 20_000; round++ ) {
            final StringBuilder sb = new StringBuilder();
            final int len = random.nextInt( 40 );
            for ( int i = 0; i < len; i++ ) {
                sb.append( alphabet[ random.nextInt( alphabet.length ) ] );
            }
            final String s = sb.toString();
            assertEquals( viaRegex( LINK, s ), viaMatcher( new InlineLinkMatcher( s ) ), () -> "link: " + s );
            assertEquals( viaRegex( IMAGE, s ), viaMatcher( InlineLinkMatcher.images( s ) ), () -> "image: " + s );
        }
    }
}

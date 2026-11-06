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
package com.wikantik.api.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** What is a tag in a page is decided by the reader's parse (ReaderTagsTest, wikantik-main); this covers the identity rules. */
class InlineTagSyntaxTest {

    @Test
    void normaliseStripsHashesFlattensSlashesAndLowercases() {
        assertEquals( "team-core", InlineTagSyntax.normalise( "#Team/Core" ) );
        assertEquals( "foo", InlineTagSyntax.normalise( " Foo " ) );
    }

    @Test
    void findableTagsUnionFrontmatterAndInlineNormalisedOnce() {
        assertEquals( List.of( "foo", "x", "bar-baz" ),
            InlineTagSyntax.findableTags( java.util.Arrays.asList( "Foo", "#X", " ", null ), List.of( "foo", "#Bar/Baz", "FOO" ) ) );
        assertEquals( List.of( "a" ), InlineTagSyntax.findableTags( null, List.of( "a" ) ) );
        assertEquals( List.of(), InlineTagSyntax.findableTags( List.of(), null ) );
    }

    @Test
    void tagTextNeedsALetterUnderscoreOrDashAndTagCharsAreLettersDigitsAndSeparators() {
        assertFalse( InlineTagSyntax.isTagText( "123" ) );
        assertFalse( InlineTagSyntax.isTagText( "1/2" ) );
        assertTrue( InlineTagSyntax.isTagText( "fff" ) );
        assertTrue( InlineTagSyntax.isTagText( "2024-q1" ) );
        assertTrue( InlineTagSyntax.isTagChar( 'a' ) && InlineTagSyntax.isTagChar( '7' ) && InlineTagSyntax.isTagChar( '_' )
                && InlineTagSyntax.isTagChar( '/' ) && InlineTagSyntax.isTagChar( '-' ) );
        assertFalse( InlineTagSyntax.isTagChar( '.' ) );
    }
}

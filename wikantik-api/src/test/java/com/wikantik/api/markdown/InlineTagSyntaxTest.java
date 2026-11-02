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

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class InlineTagSyntaxTest {

    private static Set< String > tags( final String body ) {
        return InlineTagSyntax.normalisedTags( body );
    }

    @Test
    void inlineTagRules() {
        assertEquals( Set.of( "work", "team-core" ),
            tags( "Hi #Work and #team/core\n# Heading #nope\n`#code` [[#anchor]] http://x/#frag #123" ) );
    }

    @Test
    void insertionOrderAndDuplicates() {
        assertEquals( List.of( "b", "a" ), List.copyOf( tags( "#b #a #B" ) ) );
    }

    @Test
    void fencedCodeAndLinkDestinationsIgnored() {
        assertEquals( Set.of(), tags( "```\n#x\n```\n[t](a#b) word#nope" ) );
    }

    @Test
    void segmentAfterInlineCodeIsNotALineStart() {
        assertEquals( Set.of(), tags( "`c`#x" ) );
        assertEquals( Set.of(), tags( "# Title with `code` #real" ) );
        assertEquals( Set.of( "ok" ), tags( "see `c` #ok" ) );
    }

    @Test
    void tagNeedsLetterUnderscoreOrDash() {
        assertEquals( Set.of(), tags( "#2024/ #123 #1/2" ) );
        assertEquals( Set.of( "2024-q1", "y2024" ), tags( "#2024-q1 #y2024" ) );
        assertEquals( Set.of( "123a" ), tags( "#123a" ) );
    }

    @Test
    void cssColourInProseIsATag() {
        assertEquals( Set.of( "fff" ), tags( "use #fff here" ) );
    }

    @Test
    void writtenFormAndNormalisedFormAreBothReported() {
        final List< InlineTagSyntax.Tag > found = InlineTagSyntax.scan( "a #Foo and #a/b" );
        assertEquals( 2, found.size() );
        assertEquals( new InlineTagSyntax.Tag( 2, 6, "Foo", "foo" ), found.get( 0 ) );
        assertEquals( new InlineTagSyntax.Tag( 11, 15, "a/b", "a-b" ), found.get( 1 ) );
    }

    @Test
    void offsetsSurviveBlankedLinksAndCode() {
        final String body = "[[P]] `c` http://x/#f #t";
        final List< InlineTagSyntax.Tag > found = InlineTagSyntax.scan( body );
        assertEquals( 1, found.size() );
        assertEquals( "#t", body.substring( found.get( 0 ).start(), found.get( 0 ).end() ) );
    }

    @Test
    void mustFollowWhitespaceOrLineStart() {
        assertEquals( Set.of(), tags( "x#foo" ) );
        assertEquals( Set.of( "foo" ), tags( "x\n#foo" ) );
    }

    @Test
    void headingLinesAreSkipped() {
        assertEquals( Set.of(), tags( "# Heading\n## H #x" ) );
    }

    @Test
    void markdownLinkAndWikilinkAnchorsAreNotTags() {
        assertEquals( Set.of(), tags( "[[Page#Heading]] [t](#frag) https://e.com/#frag" ) );
    }

    @Test
    void mathIsNeverTagged() {
        assertEquals( Set.of(), tags( "$#x$" ) );
        assertEquals( Set.of(), tags( "$$\n#x\n$$" ) );
        assertEquals( Set.of( "y" ), tags( "$a$ #y" ) );
    }

    @Test
    void tagInsideACommentDoesNotCount() {
        assertEquals( Set.of(), tags( "%% #secret %%" ) );
        assertEquals( Set.of(), tags( "a %% line one\n#secret\nend %% b" ) );
        assertEquals( Set.of( "shown" ), tags( "%% #hidden %% #shown" ) );
    }

    @Test
    void normaliseStripsHashesFlattensSlashesAndLowercases() {
        assertEquals( "team-core", InlineTagSyntax.normalise( "#Team/Core" ) );
        assertEquals( "foo", InlineTagSyntax.normalise( " Foo " ) );
    }

    @Test
    void findableTagsUnionFrontmatterAndInlineNormalisedOnce() {
        assertEquals( List.of( "foo", "x", "bar-baz" ),
            InlineTagSyntax.findableTags( java.util.Arrays.asList( "Foo", "#X", " ", null ), "#foo #Bar/Baz" ) );
        assertEquals( List.of( "a" ), InlineTagSyntax.findableTags( null, "#a" ) );
        assertEquals( List.of(), InlineTagSyntax.findableTags( List.of(), null ) );
    }

    @Test
    void nullAndEmptyBodiesHaveNoTags() {
        assertEquals( Set.of(), tags( null ) );
        assertEquals( Set.of(), tags( "" ) );
        assertEquals( List.of(), InlineTagSyntax.scan( null ) );
    }

    @Test
    void longRunsScanInLinearTime() {
        final String run = "a".repeat( 200_000 );
        assertEquals( Set.of(), tags( run ) );
        assertEquals( Set.of(), tags( "://" + run ) );
        assertEquals( Set.of(), tags( "[[a ".repeat( 50_000 ) ) );
        assertEquals( Set.of(), tags( "](a".repeat( 50_000 ) ) );
    }
}

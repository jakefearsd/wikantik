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
package com.wikantik.tools;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith( MockitoExtension.class )
class ResultShaperTest {

    @Mock HttpServletRequest request;

    @Test
    void citationUrlUsesPublicBaseUrlWhenProvided() {
        final String url = ResultShaper.citationUrl( "Some Page", request, "https://wiki.example.com/" );
        assertEquals( "https://wiki.example.com/wiki/Some%20Page", url );
    }

    @Test
    void citationUrlFallsBackToRequestHost() {
        when( request.getScheme() ).thenReturn( "http" );
        when( request.getServerName() ).thenReturn( "localhost" );
        when( request.getServerPort() ).thenReturn( 8080 );
        final String url = ResultShaper.citationUrl( "Main", request, null );
        assertEquals( "http://localhost:8080/wiki/Main", url );
    }

    @Test
    void citationUrlOmitsDefaultPort() {
        when( request.getScheme() ).thenReturn( "https" );
        when( request.getServerName() ).thenReturn( "wiki.example.com" );
        when( request.getServerPort() ).thenReturn( 443 );
        assertEquals( "https://wiki.example.com/wiki/Foo",
                ResultShaper.citationUrl( "Foo", request, null ) );
    }

    @Test
    void snippetPrefersFirstContextFragment() {
        final String[] contexts = { "match in <b>context</b>" };
        assertEquals( "match in <b>context</b>", ResultShaper.snippet( contexts, "body text" ) );
    }

    @Test
    void snippetFallsBackToBodyWhenContextsEmpty() {
        final String out = ResultShaper.snippet( new String[ 0 ], "hello   world\n\nsecond line" );
        assertEquals( "hello world second line", out );
    }

    @Test
    void snippetEllipsizesLongBody() {
        final String body = "a".repeat( 1000 );
        final String out = ResultShaper.snippet( null, body );
        assertTrue( out.endsWith( "…" ) );
        assertTrue( out.length() < body.length() );
    }

    @Test
    void bodyOnlyStripsYamlFrontmatter() {
        final String raw = "---\nsummary: Hello\ntags: [a, b]\n---\nActual body content here.";
        assertEquals( "Actual body content here.", ResultShaper.bodyOnly( raw ).trim() );
    }

    @Test
    void truncateBodyAppendsEllipsis() {
        final String body = "a".repeat( 100 );
        final String out = ResultShaper.truncateBody( body, 10 );
        assertEquals( "aaaaaaaaaa…", out );
        assertTrue( ResultShaper.wasTruncated( body, 10 ) );
    }

    @Test
    void truncateBodyReturnsOriginalWhenUnderLimit() {
        assertEquals( "hello", ResultShaper.truncateBody( "hello", 10 ) );
        assertFalse( ResultShaper.wasTruncated( "hello", 10 ) );
    }

    @Test
    void applyFrontmatterCopiesSummaryAndTags() {
        final Map< String, Object > out = new LinkedHashMap<>();
        ResultShaper.applyFrontmatter( out, Map.of( "summary", "A summary", "tags", java.util.List.of( "x" ) ) );
        assertEquals( "A summary", out.get( "summary" ) );
        assertEquals( java.util.List.of( "x" ), out.get( "tags" ) );
    }

    @Test
    void frontmatterReturnsEmptyMapOnBadInput() {
        assertTrue( ResultShaper.frontmatter( null ).isEmpty() );
        assertTrue( ResultShaper.frontmatter( "" ).isEmpty() );
    }

    @Test
    void citationUrlFallsBackToBarePathWhenNoBaseUrlOrRequest() {
        assertEquals( "/wiki/Some%20Page", ResultShaper.citationUrl( "Some Page", null, null ) );
        assertEquals( "/wiki/Blank", ResultShaper.citationUrl( "Blank", null, "   " ) );
    }

    @Test
    void bodyOnlyReturnsEmptyStringForNullOrEmptyInput() {
        assertEquals( "", ResultShaper.bodyOnly( null ) );
        assertEquals( "", ResultShaper.bodyOnly( "" ) );
    }

    @Test
    void snippetReturnsEmptyStringWhenNoContextsAndNoBody() {
        assertEquals( "", ResultShaper.snippet( null, null ) );
        assertEquals( "", ResultShaper.snippet( new String[ 0 ], "" ) );
    }

    @Test
    void truncateBodyReturnsEmptyStringForNullBody() {
        assertEquals( "", ResultShaper.truncateBody( null, 10 ) );
        assertFalse( ResultShaper.wasTruncated( null, 10 ) );
    }

    // ---- mutation-driven additions ----

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource( { "http,80,h", "https,443,h", "http,443,h:443", "https,80,h:80", "http,8080,h:8080", "ftp,80,h:80" } )
    void citationUrlOmitsThePortOnlyWhenItIsTheDefaultForTheScheme( final String scheme, final int port, final String authority ) {
        when( request.getScheme() ).thenReturn( scheme );
        when( request.getServerName() ).thenReturn( "h" );
        when( request.getServerPort() ).thenReturn( port );
        assertEquals( scheme + "://" + authority + "/wiki/P", ResultShaper.citationUrl( "P", request, null ) );
    }

    @Test
    void citationUrlEncodesReservedCharactersInThePageName() {
        assertEquals( "/wiki/A%2FB%3F%26%C3%A9", ResultShaper.citationUrl( "A/B?&\u00e9", null, null ) );
    }

    @Test
    void frontmatterReadsMetadataFromRawText() {
        final Map< String, Object > meta = ResultShaper.frontmatter( "---\nsummary: Hello\ntags: [a, b]\n---\nbody" );
        assertEquals( "Hello", meta.get( "summary" ) );
        assertEquals( java.util.List.of( "a", "b" ), meta.get( "tags" ) );
        assertTrue( ResultShaper.frontmatter( "just a body, no frontmatter" ).isEmpty() );
    }

    @Test
    void frontmatterOfMalformedYamlIsEmptyNotAnException() {
        assertTrue( ResultShaper.frontmatter( "---\nkey: [unclosed\n---\nbody" ).isEmpty() );
    }

    @Test
    void bodyOnlyOfNothingIsEmptyAndOfPlainTextIsTheText() {
        assertEquals( "", ResultShaper.bodyOnly( null ) );
        assertEquals( "", ResultShaper.bodyOnly( "" ) );
        assertEquals( "plain text", ResultShaper.bodyOnly( "plain text" ) );
    }

    @Test
    void bodyOnlyOfTolerablyMalformedFrontmatterStillDropsTheFence() {
        assertEquals( "body", ResultShaper.bodyOnly( "---\nkey: [unclosed\n---\nbody" ) );
    }

    @Test
    void snippetUsesBodyWhenTheFirstContextIsBlankOrNullAndStripsAContext() {
        assertEquals( "from body", ResultShaper.snippet( new String[] { "   " }, "from body" ) );
        assertEquals( "from body", ResultShaper.snippet( new String[] { null }, "from body" ) );
        assertEquals( "ctx", ResultShaper.snippet( new String[] { "  ctx \n", "second" }, "from body" ) );
    }

    @Test
    void snippetOfNoContextAndNoBodyIsEmpty() {
        assertEquals( "", ResultShaper.snippet( null, null ) );
        assertEquals( "", ResultShaper.snippet( new String[ 0 ], "" ) );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource( { "319,319", "320,320", "321,321", "1000,321" } )
    void bodySnippetIsCutAfterThreeHundredTwentyCharactersOnly( final int bodyChars, final int expectedLength ) {
        final String out = ResultShaper.snippet( null, "y".repeat( bodyChars ) );
        assertEquals( expectedLength, out.length() );
        assertEquals( bodyChars > 320, out.endsWith( "\u2026" ) );
    }

    @org.junit.jupiter.params.ParameterizedTest( name = "body of 10, maxChars {0}: truncated={1}" )
    @org.junit.jupiter.params.provider.CsvSource( { "-1,false", "0,false", "1,true", "9,true", "10,false", "11,false" } )
    void truncationHappensOnlyWhenTheBodyIsLongerThanAPositiveLimit( final int maxChars, final boolean truncated ) {
        final String body = "0123456789";
        assertEquals( truncated, ResultShaper.wasTruncated( body, maxChars ) );
        final String shown = ResultShaper.truncateBody( body, maxChars );
        if( truncated ) {
            assertEquals( body.substring( 0, maxChars ) + "\u2026", shown );
        } else {
            assertEquals( body, shown );
        }
    }

    @Test
    void nullBodyIsNeverTruncatedAndShowsAsEmpty() {
        assertFalse( ResultShaper.wasTruncated( null, 5 ) );
        assertEquals( "", ResultShaper.truncateBody( null, 5 ) );
    }

    @Test
    void defaultBodyTruncationIsSixThousandCharacters() {
        assertEquals( 6000, ResultShaper.defaultBodyTruncation() );
    }

    @Test
    void applyFrontmatterIsANoOpForNullAndEmptyAndCopiesEachFieldIndependently() {
        final Map< String, Object > out = new LinkedHashMap<>();
        ResultShaper.applyFrontmatter( out, null );
        ResultShaper.applyFrontmatter( out, Map.of() );
        assertTrue( out.isEmpty() );
        ResultShaper.applyFrontmatter( out, Map.of( "summary", 42 ) );
        assertEquals( Map.of( "summary", "42" ), out );
        final Map< String, Object > tagsOnly = new LinkedHashMap<>();
        ResultShaper.applyFrontmatter( tagsOnly, Map.of( "tags", java.util.List.of( "t" ), "other", "ignored" ) );
        assertEquals( Map.of( "tags", java.util.List.of( "t" ) ), tagsOnly );
    }
}

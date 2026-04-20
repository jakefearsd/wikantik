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
}

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
package com.wikantik.rest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wikantik.WikiEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminFrontmatterIssuesResource} — the frontmatter audit
 * that lists pages whose YAML fails strict parsing.
 */
class AdminFrontmatterIssuesResourceTest {

    private PageManager                     pm;
    private WikiEngine                      engine;
    private AdminFrontmatterIssuesResource  resource;
    private HttpServletRequest              req;
    private HttpServletResponse             resp;
    private StringWriter                    body;

    @BeforeEach
    void setUp() throws Exception {
        pm     = mock( PageManager.class );
        engine = mock( WikiEngine.class );
        when( engine.getManager( PageManager.class ) ).thenReturn( pm );

        resource = new AdminFrontmatterIssuesResource();
        resource.setEngineForTesting( engine );

        req  = mock( HttpServletRequest.class );
        resp = mock( HttpServletResponse.class );
        body = new StringWriter();
        when( resp.getWriter() ).thenReturn( new PrintWriter( body ) );
    }

    private static Page page( final String name ) {
        final Page p = mock( Page.class );
        when( p.getName() ).thenReturn( name );
        return p;
    }

    private JsonObject callAndParseData() throws Exception {
        resource.doGet( req, resp );
        return JsonParser.parseString( body.toString() ).getAsJsonObject().getAsJsonObject( "data" );
    }

    // ------------------------------------------------------------------ 503

    @Test
    void pageManagerUnavailable_returns503() throws Exception {
        when( engine.getManager( PageManager.class ) ).thenReturn( null );

        resource.doGet( req, resp );

        org.mockito.Mockito.verify( resp ).setStatus( 503 );
        assertTrue( body.toString().contains( "page manager unavailable" ) );
    }

    // ------------------------------------------------------------------ 500

    @Test
    void getAllPagesFailure_returns500AndSanitizesQuotes() throws Exception {
        when( pm.getAllPages() ).thenThrow( new ProviderException( "boom \"quoted\"" ) );

        resource.doGet( req, resp );

        org.mockito.Mockito.verify( resp ).setStatus( 500 );
        final String out = body.toString();
        assertTrue( out.contains( "page enumeration failed" ), out );
        // Inner double quotes must be down-converted so the hand-built JSON stays valid.
        assertEquals( "boom 'quoted'",
                JsonParser.parseString( out ).getAsJsonObject().get( "error" ).getAsString()
                        .replace( "page enumeration failed: ", "" ) );
    }

    // ------------------------------------------------------------------ 200

    @Test
    void cleanCorpus_reportsZeroIssuesAndCountsScanned() throws Exception {
        final Page a = page( "Alpha" );
        final Page b = page( "Beta" );
        when( pm.getAllPages() ).thenReturn( List.of( a, b ) );
        when( pm.getPureText( a ) ).thenReturn( "---\ntitle: Alpha\n---\nbody\n" );
        when( pm.getPureText( b ) ).thenReturn( "no frontmatter at all\n" );

        final JsonObject data = callAndParseData();

        assertEquals( 0, data.get( "issue_count" ).getAsInt() );
        assertEquals( 0, data.get( "error_count" ).getAsInt() );
        assertEquals( 2, data.get( "scanned" ).getAsInt() );
        assertEquals( 0, data.getAsJsonArray( "issues" ).size() );
    }

    @Test
    void emptyAndNullBodiesAreSkippedButStillCountedAsScanned() throws Exception {
        final Page empty = page( "Empty" );
        final Page nul   = page( "Null" );
        when( pm.getAllPages() ).thenReturn( List.of( empty, nul ) );
        when( pm.getPureText( empty ) ).thenReturn( "" );
        when( pm.getPureText( nul ) ).thenReturn( null );

        final JsonObject data = callAndParseData();

        assertEquals( 2, data.get( "scanned" ).getAsInt() );
        assertEquals( 0, data.get( "issue_count" ).getAsInt() );
    }

    @Test
    void crlfFrontmatterIsRecognisedAndValidated() throws Exception {
        final Page p = page( "Crlf" );
        when( pm.getAllPages() ).thenReturn( List.of( p ) );
        // Deliberately broken YAML behind a CRLF fence — must still be parsed and flagged.
        when( pm.getPureText( p ) ).thenReturn( "---\r\ntitle: [unclosed\r\n---\r\nbody\r\n" );

        final JsonObject data = callAndParseData();

        assertEquals( 1, data.get( "issue_count" ).getAsInt() );
        assertEquals( "Crlf",
                data.getAsJsonArray( "issues" ).get( 0 ).getAsJsonObject().get( "pageName" ).getAsString() );
    }

    @Test
    void brokenYaml_reportsPageNameErrorAndPosition() throws Exception {
        final Page good   = page( "Good" );
        final Page broken = page( "Broken" );
        when( pm.getAllPages() ).thenReturn( List.of( good, broken ) );
        when( pm.getPureText( good ) ).thenReturn( "---\ntype: note\n---\nok\n" );
        when( pm.getPureText( broken ) ).thenReturn( "---\ntags: [a, b\nsummary: x\n---\nbody\n" );

        final JsonObject data = callAndParseData();

        assertEquals( 1, data.get( "issue_count" ).getAsInt() );
        assertEquals( 1, data.get( "error_count" ).getAsInt() );
        assertEquals( 2, data.get( "scanned" ).getAsInt() );

        final JsonObject issue = data.getAsJsonArray( "issues" ).get( 0 ).getAsJsonObject();
        assertEquals( "Broken", issue.get( "pageName" ).getAsString() );
        assertTrue( issue.has( "error" ) );
        // SnakeYAML reports a position for this class of error; both keys are
        // emitted only when positive, so assert on the contract, not the value.
        if ( issue.has( "line" ) ) {
            assertTrue( issue.get( "line" ).getAsInt() > 0 );
        }
        if ( issue.has( "column" ) ) {
            assertTrue( issue.get( "column" ).getAsInt() > 0 );
        }
    }

    @Test
    void unreadablePage_isSurfacedAsAnIssueAndScanContinues() throws Exception {
        final Page bad  = page( "Unreadable" );
        final Page next = page( "Next" );
        when( pm.getAllPages() ).thenReturn( List.of( bad, next ) );
        when( pm.getPureText( bad ) ).thenThrow( new IllegalStateException( "disk gone" ) );
        when( pm.getPureText( next ) ).thenReturn( "---\ntype: note\n---\nfine\n" );

        final JsonObject data = callAndParseData();

        assertEquals( 1, data.get( "issue_count" ).getAsInt() );
        assertEquals( 1, data.get( "error_count" ).getAsInt() );
        assertEquals( 2, data.get( "scanned" ).getAsInt(), "scan must not abort on one bad page" );

        final JsonObject issue = data.getAsJsonArray( "issues" ).get( 0 ).getAsJsonObject();
        assertEquals( "Unreadable", issue.get( "pageName" ).getAsString() );
        assertTrue( issue.get( "error" ).getAsString().startsWith( "page read failed: " ) );
    }
}

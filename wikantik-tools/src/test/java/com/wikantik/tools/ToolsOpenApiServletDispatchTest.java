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
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The servlet's request handling around the two tools: body parsing, status codes, metrics and the JSON it writes.
 * The tools are replaced by stubs; their own behaviour is pinned in SearchWikiToolTest / GetPageToolTest.
 */
class ToolsOpenApiServletDispatchTest {

    private static final ToolsConfig CONFIG = new ToolsConfig( new Properties() );

    /** Captures status, content type and body written to a response. */
    private static final class Out {
        final HttpServletResponse resp = Mockito.mock( HttpServletResponse.class );
        final StringWriter body = new StringWriter();
        int status = -1;
        String contentType;

        Out() throws IOException {
            Mockito.doAnswer( i -> { status = i.getArgument( 0 ); return null; } ).when( resp ).setStatus( Mockito.anyInt() );
            Mockito.doAnswer( i -> { contentType = i.getArgument( 0 ); return null; } ).when( resp ).setContentType( Mockito.anyString() );
            Mockito.doReturn( new PrintWriter( body, true ) ).when( resp ).getWriter();
        }

        String text() { return body.toString(); }
    }

    private static HttpServletRequest post( final String path, final String json ) throws IOException {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( path ).when( req ).getPathInfo();
        Mockito.doReturn( new BufferedReader( new StringReader( json ) ) ).when( req ).getReader();
        return req;
    }

    private static HttpServletRequest get( final String path, final String maxChars ) {
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( path ).when( req ).getPathInfo();
        Mockito.doReturn( maxChars ).when( req ).getParameter( "maxChars" );
        return req;
    }

    private static final class RecordingSearch extends SearchWikiTool {
        String query;
        int maxResults = Integer.MIN_VALUE;
        Map< String, Object > result = Map.of( "query", "q", "results", List.of( "a", "b", "c" ), "total", 3 );
        RuntimeException failure;

        RecordingSearch() { super( null, CONFIG ); }

        @Override
        Map< String, Object > execute( final String q, final int max, final HttpServletRequest request ) {
            query = q;
            maxResults = max;
            if( failure != null ) {
                throw failure;
            }
            return result;
        }
    }

    private static final class RecordingGetPage extends GetPageTool {
        String name;
        int maxChars = Integer.MIN_VALUE;
        Map< String, Object > result;
        RuntimeException failure;

        RecordingGetPage() { super( null, CONFIG ); }

        @Override
        Map< String, Object > execute( final String pageName, final int max, final HttpServletRequest request ) {
            name = pageName;
            maxChars = max;
            if( failure != null ) {
                throw failure;
            }
            return result;
        }
    }

    /** Compares parsed JSON: Gson escapes quotes as \\u0027, which is the same document. */
    private static void assertJson( final String expected, final String actual ) {
        assertEquals( com.google.gson.JsonParser.parseString( expected ), com.google.gson.JsonParser.parseString( actual ), actual );
    }

    // ---- search_wiki ----

    @Test
    void searchPassesQueryAndLimitToTheToolAndWritesItsResultAsJson() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        final ToolsMetrics metrics = new ToolsMetrics();
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( CONFIG, metrics, tool, null );
        final Out out = new Out();

        servlet.doPost( post( "/search_wiki", "{\"query\":\"jena sparql\",\"maxResults\":7}" ), out.resp );

        assertEquals( "jena sparql", tool.query );
        assertEquals( 7, tool.maxResults );
        assertEquals( 200, out.status );
        assertEquals( "application/json", out.contentType );
        assertJson( "{\"query\":\"q\",\"results\":[\"a\",\"b\",\"c\"],\"total\":3}", out.text() );
        assertEquals( 1, metrics.searchSuccess() );
        assertEquals( 3, metrics.resultsReturned(), "the count of results returned is recorded" );
        assertEquals( 0, metrics.searchError() );
    }

    @Test
    void searchWithoutMaxResultsPassesZeroSoTheToolAppliesItsDefault() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), tool, null );
        servlet.doPost( post( "/search_wiki", "{\"query\":\"x\"}" ), new Out().resp );
        assertEquals( 0, tool.maxResults );
        servlet.doPost( post( "/search_wiki", "{\"query\":\"x\",\"maxResults\":null}" ), new Out().resp );
        assertEquals( 0, tool.maxResults );
    }

    @Test
    void searchResultWithoutAResultsListCountsZero() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        tool.result = Map.of( "query", "q", "error", "ContextRetrievalService not configured" );
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();
        new ToolsOpenApiServlet( CONFIG, metrics, tool, null ).doPost( post( "/search_wiki", "{\"query\":\"q\"}" ), out.resp );
        assertEquals( 200, out.status );
        assertEquals( 1, metrics.searchSuccess() );
        assertEquals( 0, metrics.resultsReturned() );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource( strings = { "", "not json", "[1]", "\"q\"", "{}", "{\"query\":null}",
            "{\"query\":\"\"}", "{\"query\":\"   \"}", "{\"maxResults\":5}" } )
    void searchWithoutAUsableQueryIs400AndNeverReachesTheTool( final String json ) throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, tool, null ).doPost( post( "/search_wiki", json ), out.resp );

        assertEquals( 400, out.status, json );
        assertJson( "{\"error\":\"Field 'query' is required\"}", out.text() );
        assertEquals( "application/json", out.contentType );
        assertEquals( null, tool.query );
        assertEquals( 0, metrics.searchSuccess() + metrics.searchError() );
    }

    @Test
    void searchToolFailureIs500WithTheMessageAndIsCountedAsAnError() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        tool.failure = new IllegalStateException( "permission check failed" );
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, tool, null ).doPost( post( "/search_wiki", "{\"query\":\"q\"}" ), out.resp );

        assertEquals( 500, out.status );
        assertJson( "{\"error\":\"search failed: permission check failed\"}", out.text() );
        assertEquals( 1, metrics.searchError() );
        assertEquals( 0, metrics.searchSuccess() );
    }

    @Test
    void postWithoutAPathIsNotImplemented() throws Exception {
        final Out out = new Out();
        final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( null ).when( req ).getPathInfo();
        new ToolsOpenApiServlet().doPost( req, out.resp );
        assertEquals( 501, out.status );
        assertEquals( "application/json", out.contentType );
        assertJson( "{\"error\":\"Tool endpoint not yet implemented\"}", out.text() );
    }

    @Test
    void searchIsExactPathAndPostOnly() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), tool, null );
        final Out other = new Out();
        servlet.doPost( post( "/search_wiki/", "{\"query\":\"q\"}" ), other.resp );
        assertEquals( 501, other.status );
        final Out viaGet = new Out();
        servlet.doGet( get( "/search_wiki", null ), viaGet.resp );
        assertEquals( 501, viaGet.status );
        assertEquals( null, tool.query );
    }

    // ---- get_page ----

    @Test
    void getPageDecodesTheNamePassesMaxCharsAndWritesTheResult() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        tool.result = Map.of( "name", "My Page", "truncated", true );
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, null, tool ).doGet( get( "/page/My%20Page", " 250 " ), out.resp );

        assertEquals( "My Page", tool.name );
        assertEquals( 250, tool.maxChars );
        assertEquals( 200, out.status );
        assertEquals( "application/json", out.contentType );
        assertJson( "{\"name\":\"My Page\",\"truncated\":true}", out.text() );
        assertEquals( 1, metrics.getPageSuccess() );
        assertEquals( 1, metrics.getPageTruncated() );
    }

    @Test
    void getPageThatWasNotTruncatedIsNotCountedAsTruncated() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        tool.result = Map.of( "name", "P", "truncated", false );
        final ToolsMetrics metrics = new ToolsMetrics();
        new ToolsOpenApiServlet( CONFIG, metrics, null, tool ).doGet( get( "/page/P", null ), new Out().resp );
        assertEquals( 1, metrics.getPageSuccess() );
        assertEquals( 0, metrics.getPageTruncated() );
        assertEquals( 0, tool.maxChars, "no maxChars parameter means 0, i.e. the tool default" );
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource( strings = { "abc", "12x", "", "  ", "1.5" } )
    void unparsableMaxCharsIsTreatedAsNoLimit( final String raw ) throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        tool.result = Map.of( "name", "P" );
        new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), null, tool ).doGet( get( "/page/P", raw ), new Out().resp );
        assertEquals( 0, tool.maxChars, raw );
    }

    @Test
    void getPageForAMissingPageIs404AndCounted() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, null, tool ).doGet( get( "/page/No%20Such", null ), out.resp );

        assertEquals( 404, out.status );
        assertJson( "{\"error\":\"Page not found: No Such\"}", out.text() );
        assertEquals( 1, metrics.getPageNotFound() );
        assertEquals( 0, metrics.getPageSuccess() );
    }

    @Test
    void getPageDeniedIs403WithTheDeniedNameAndNoPageContent() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        tool.failure = new PageAccessDeniedException( "Secret" );
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, null, tool ).doGet( get( "/page/Secret", null ), out.resp );

        assertEquals( 403, out.status );
        assertTrue( out.text().startsWith( "{\"error\":\"Access denied: " ), out.text() );
        assertEquals( 1, metrics.getPageForbidden() );
        assertEquals( 0, metrics.getPageError() );
    }

    @Test
    void getPageUnexpectedFailureIs500AndCounted() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        tool.failure = new IllegalStateException( "db down" );
        final ToolsMetrics metrics = new ToolsMetrics();
        final Out out = new Out();

        new ToolsOpenApiServlet( CONFIG, metrics, null, tool ).doGet( get( "/page/P", null ), out.resp );

        assertEquals( 500, out.status );
        assertJson( "{\"error\":\"get_page failed: db down\"}", out.text() );
        assertEquals( 1, metrics.getPageError() );
        assertEquals( 0, metrics.getPageForbidden() );
    }

    @Test
    void getPageWithABlankNameIs400() throws Exception {
        final RecordingGetPage tool = new RecordingGetPage();
        for( final String path : new String[] { "/page/", "/page/   " } ) {
            final Out out = new Out();
            new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), null, tool ).doGet( get( path, null ), out.resp );
            assertEquals( 400, out.status, path );
            assertJson( "{\"error\":\"Page name is required\"}", out.text() );
        }
        assertEquals( null, tool.name );
    }

    @Test
    void getPageIsServiceUnavailableWithoutATool() throws Exception {
        final Out out = new Out();
        new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), null, null ).doGet( get( "/page/P", null ), out.resp );
        assertEquals( 503, out.status );
        assertJson( "{\"error\":\"Tool server not initialized (engine unavailable)\"}", out.text() );
    }

    @Test
    void pagePathNeedsTheTrailingSlashPrefix() throws Exception {
        final Out out = new Out();
        new ToolsOpenApiServlet( CONFIG, new ToolsMetrics(), null, new RecordingGetPage() )
                .doGet( get( "/page", null ), out.resp );
        assertEquals( 501, out.status );
    }

    // ---- OpenAPI document and tool count ----

    @Test
    void openApiDocumentIsServedAtRootSlashAndOpenapiJsonAndCounted() throws Exception {
        final ToolsMetrics metrics = new ToolsMetrics();
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet( CONFIG, metrics, null, null );
        for( final String path : new String[] { null, "/", "/openapi.json" } ) {
            final HttpServletRequest req = Mockito.mock( HttpServletRequest.class );
            Mockito.doReturn( path ).when( req ).getPathInfo();
            Mockito.doReturn( "https" ).when( req ).getScheme();
            Mockito.doReturn( "w.example" ).when( req ).getServerName();
            Mockito.doReturn( 443 ).when( req ).getServerPort();
            final Out out = new Out();
            servlet.doGet( req, out.resp );
            assertEquals( 200, out.status, String.valueOf( path ) );
            assertEquals( "application/json", out.contentType );
            assertTrue( out.text().contains( "\"openapi\": \"3.1.0\"" ) || out.text().contains( "\"openapi\":\"3.1.0\"" ), out.text() );
        }
        assertEquals( 3, metrics.openapiServed() );
    }

    @Test
    void placeholderSpecIsNotCountedAsServedOpenApi() throws Exception {
        final ToolsOpenApiServlet servlet = new ToolsOpenApiServlet();
        final Out out = new Out();
        servlet.doGet( get( "/openapi.json", null ), out.resp );
        assertEquals( 200, out.status );
        assertTrue( out.text().contains( "0.1.0-skeleton" ) );
    }

    @Test
    void toolCountReflectsWhichToolsAreWired() {
        assertEquals( 0, new ToolsOpenApiServlet().toolCount() );
        assertEquals( 1, new ToolsOpenApiServlet( CONFIG, null, new RecordingSearch(), null ).toolCount() );
        assertEquals( 1, new ToolsOpenApiServlet( CONFIG, null, null, new RecordingGetPage() ).toolCount() );
        assertEquals( 2, new ToolsOpenApiServlet( CONFIG, null, new RecordingSearch(), new RecordingGetPage() ).toolCount() );
    }

    @Test
    void anEngineAndConfigWireBothToolsButEitherAloneWiresNone() {
        final com.wikantik.api.core.Engine engine = Mockito.mock( com.wikantik.api.core.Engine.class );
        assertEquals( 2, new ToolsOpenApiServlet( engine, CONFIG, null ).toolCount() );
        assertEquals( 0, new ToolsOpenApiServlet( engine, null, null ).toolCount() );
        assertEquals( 0, new ToolsOpenApiServlet( null, CONFIG, null ).toolCount() );
    }

    @Test
    void aNullMetricsArgumentStillGetsAWorkingCounter() throws Exception {
        final RecordingSearch tool = new RecordingSearch();
        final Out out = new Out();
        new ToolsOpenApiServlet( CONFIG, null, tool, null ).doPost( post( "/search_wiki", "{\"query\":\"q\"}" ), out.resp );
        assertEquals( 200, out.status );
    }
}

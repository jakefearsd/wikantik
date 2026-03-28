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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AdminContentResourceTest {

    private TestEngine engine;
    private AdminContentResource servlet;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );

        servlet = new AdminContentResource();
        final ServletConfig config = Mockito.mock( ServletConfig.class );
        Mockito.doReturn( engine.getServletContext() ).when( config ).getServletContext();
        servlet.init( config );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testGetStats() throws Exception {
        final String json = doGet( "/stats" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pageCount" ), "Response should contain 'pageCount'" );
        assertTrue( obj.has( "caches" ), "Response should contain 'caches'" );
        assertTrue( obj.get( "pageCount" ).getAsInt() >= 0, "Page count should be non-negative" );
    }

    @Test
    void testGetOrphanedPages() throws Exception {
        final String json = doGet( "/orphaned-pages" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pages" ), "Response should contain 'pages' key" );
        final JsonArray pages = obj.getAsJsonArray( "pages" );
        assertNotNull( pages, "Pages array should not be null" );
    }

    @Test
    void testGetBrokenLinks() throws Exception {
        final String json = doGet( "/broken-links" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "links" ), "Response should contain 'links' key" );
        final JsonArray links = obj.getAsJsonArray( "links" );
        assertNotNull( links, "Links array should not be null" );
    }

    @Test
    void testBulkDeleteWithEmptyList() throws Exception {
        final JsonObject body = new JsonObject();
        body.add( "pages", new JsonArray() );

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testReindex() throws Exception {
        // Create pages so the reindex loop has something to iterate over
        engine.saveText( "ReindexPage1", "Reindex test content 1." );
        engine.saveText( "ReindexPage2", "Reindex test content 2." );

        final JsonObject body = new JsonObject();

        final String json = doPost( "/reindex", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "started" ), "Response should contain 'started' key" );
        assertTrue( obj.get( "started" ).getAsBoolean() );
        assertTrue( obj.has( "pagesQueued" ), "Response should contain 'pagesQueued' key" );
        assertTrue( obj.get( "pagesQueued" ).getAsInt() >= 2,
                "Should have queued at least 2 pages for reindexing" );

        // Cleanup
        final com.wikantik.pages.PageManager pm = engine.getManager( com.wikantik.pages.PageManager.class );
        try { pm.deletePage( "ReindexPage1" ); } catch ( final Exception e ) { /* ignore */ }
        try { pm.deletePage( "ReindexPage2" ); } catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testCacheFlush() throws Exception {
        // Populate caches by loading pages
        engine.saveText( "CacheTestPage", "Content to cache." );
        engine.getManager( com.wikantik.pages.PageManager.class ).getPureText( "CacheTestPage", -1 );

        final JsonObject body = new JsonObject();

        final String json = doPost( "/cache/flush", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "flushed" ), "Response should contain 'flushed' key" );
        assertTrue( obj.get( "flushed" ).getAsBoolean() );
        assertTrue( obj.has( "entriesRemoved" ), "Response should contain 'entriesRemoved' key" );
        assertTrue( obj.get( "entriesRemoved" ).getAsInt() >= 0,
                "Should report entries removed count" );

        // Cleanup
        try { engine.getManager( com.wikantik.pages.PageManager.class ).deletePage( "CacheTestPage" ); }
        catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testUnknownGetEndpoint() throws Exception {
        final String json = doGet( "/unknown" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testUnknownPostEndpoint() throws Exception {
        final JsonObject body = new JsonObject();

        final String json = doPost( "/unknown", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 404, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testIsCrossOriginAllowedReturnsFalse() {
        assertFalse( servlet.isCrossOriginAllowed(),
                "Admin content endpoint should not allow cross-origin requests" );
    }

    @Test
    void testPurgeVersionsMissingPageReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        // No "page" field

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "page" ),
                "Error should mention missing page name" );
    }

    @Test
    void testPurgeVersionsKeepLatestLessThanOne() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "page", "SomePage" );
        body.addProperty( "keepLatest", 0 );

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
        assertTrue( obj.get( "message" ).getAsString().contains( "keepLatest" ) );
    }

    @Test
    void testPurgeVersionsPageWithNoHistory() throws Exception {
        // Create a page (will have just 1 version with FileSystemProvider)
        engine.saveText( "PurgeTestPage", "Single version content." );

        final JsonObject body = new JsonObject();
        body.addProperty( "page", "PurgeTestPage" );
        body.addProperty( "keepLatest", 1 );

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Purge should succeed, got: " + json );
        assertTrue( obj.has( "purged" ), "Response should contain 'purged' count" );
        assertTrue( obj.has( "remaining" ), "Response should contain 'remaining' count" );
        assertEquals( 0, obj.get( "purged" ).getAsInt(),
                "No versions should be purged when only 1 exists" );

        // Cleanup
        try { engine.getManager( com.wikantik.pages.PageManager.class ).deletePage( "PurgeTestPage" ); }
        catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testBulkDeleteWithPages() throws Exception {
        // Create pages to delete
        engine.saveText( "BulkDelPage1", "Content 1." );
        engine.saveText( "BulkDelPage2", "Content 2." );

        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "BulkDelPage1" );
        pages.add( "BulkDelPage2" );
        body.add( "pages", pages );

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Bulk delete should succeed, got: " + json );
        assertTrue( obj.has( "deleted" ), "Response should contain 'deleted' list" );
        assertTrue( obj.has( "failed" ), "Response should contain 'failed' list" );

        final JsonArray deleted = obj.getAsJsonArray( "deleted" );
        assertEquals( 2, deleted.size(), "Both pages should be deleted" );
    }

    @Test
    void testBulkDeleteWithMissingPagesReturns400() throws Exception {
        final JsonObject body = new JsonObject();
        // No "pages" field at all

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testCacheFlushWithSpecificCache() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "cache", "wikantik.pages" );

        final String json = doPost( "/cache/flush", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Cache flush should succeed, got: " + json );
        assertTrue( obj.get( "flushed" ).getAsBoolean() );
        assertTrue( obj.has( "entriesRemoved" ), "Response should contain entries count" );
    }

    @Test
    void testGetBrokenLinksStructure() throws Exception {
        // Create a page with a broken link
        engine.saveText( "BrokenLinkSource", "[NonExistentBrokenTarget](NonExistentBrokenTarget)" );

        final String json = doGet( "/broken-links" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "links" ), "Response should contain 'links' key" );
        final JsonArray links = obj.getAsJsonArray( "links" );

        // Look for our broken link entry
        for ( int i = 0; i < links.size(); i++ ) {
            final JsonObject entry = links.get( i ).getAsJsonObject();
            assertTrue( entry.has( "target" ), "Each link should have 'target'" );
            assertTrue( entry.has( "referencedBy" ), "Each link should have 'referencedBy'" );
            assertTrue( entry.has( "referrerCount" ), "Each link should have 'referrerCount'" );
        }

        // Cleanup
        try { engine.getManager( com.wikantik.pages.PageManager.class ).deletePage( "BrokenLinkSource" ); }
        catch ( final Exception e ) { /* ignore */ }
    }

    @Test
    void testGetStatsHasCacheDetails() throws Exception {
        final String json = doGet( "/stats" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pageCount" ) );
        assertTrue( obj.has( "caches" ) );
        final JsonArray caches = obj.getAsJsonArray( "caches" );
        // Verify cache entries have expected structure
        if ( caches.size() > 0 ) {
            final JsonObject cache = caches.get( 0 ).getAsJsonObject();
            assertTrue( cache.has( "name" ), "Cache entry should have 'name'" );
            assertTrue( cache.has( "fullName" ), "Cache entry should have 'fullName'" );
            assertTrue( cache.has( "size" ), "Cache entry should have 'size'" );
            assertTrue( cache.has( "maxSize" ), "Cache entry should have 'maxSize'" );
            assertTrue( cache.has( "hits" ), "Cache entry should have 'hits'" );
            assertTrue( cache.has( "misses" ), "Cache entry should have 'misses'" );
            assertTrue( cache.has( "hitRatio" ), "Cache entry should have 'hitRatio'" );
        }
    }

    @Test
    void testPostWithInvalidJsonReturns400() throws Exception {
        final HttpServletRequest request = createRequest( "/bulk-delete" );
        Mockito.doReturn( new BufferedReader( new StringReader( "not valid json" ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );

        final JsonObject obj = gson.fromJson( sw.toString(), JsonObject.class );
        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testPurgeVersionsBlankPageName() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "page", "   " );

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.get( "error" ).getAsBoolean() );
        assertEquals( 400, obj.get( "status" ).getAsInt() );
    }

    @Test
    void testGetOrphanedPagesReturnsArray() throws Exception {
        final String json = doGet( "/orphaned-pages" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pages" ) );
        assertTrue( obj.get( "pages" ).isJsonArray(),
                "orphaned-pages should return an array" );
    }

    @Test
    void testPurgeVersionsNonexistentPage() throws Exception {
        final JsonObject body = new JsonObject();
        body.addProperty( "page", "NonExistentPurgePage999" );
        body.addProperty( "keepLatest", 1 );

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        // Non-existent page should have null/empty history
        assertFalse( obj.has( "error" ), "Should handle non-existent page gracefully, got: " + json );
        assertEquals( 0, obj.get( "purged" ).getAsInt() );
        assertEquals( 0, obj.get( "remaining" ).getAsInt() );
    }

    @Test
    void testBulkDeleteWithNonexistentPages() throws Exception {
        final JsonObject body = new JsonObject();
        final JsonArray pages = new JsonArray();
        pages.add( "NonExistentBulkPage1" );
        pages.add( "NonExistentBulkPage2" );
        body.add( "pages", pages );

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Should complete without error, got: " + json );
        assertTrue( obj.has( "deleted" ) );
        assertTrue( obj.has( "failed" ) );
        // Non-existent pages may either succeed silently or fail gracefully
    }

    @Test
    void testGetStatsHasOrphanedAndBrokenCounts() throws Exception {
        final String json = doGet( "/stats" );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "pageCount" ) );
        assertTrue( obj.has( "orphanedCount" ), "Stats should include orphanedCount" );
        assertTrue( obj.has( "brokenLinkCount" ), "Stats should include brokenLinkCount" );
    }

    @Test
    void testPurgeVersionsWithHistory() throws Exception {
        // Create a page with multiple versions by saving it twice
        engine.saveText( "PurgeTestPage", "Version 1 content" );
        engine.saveText( "PurgeTestPage", "Version 2 content" );

        final JsonObject body = new JsonObject();
        body.addProperty( "page", "PurgeTestPage" );
        body.addProperty( "keepLatest", 1 );

        final String json = doPost( "/purge-versions", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertFalse( obj.has( "error" ), "Purge should succeed, got: " + json );
        assertTrue( obj.has( "purged" ), "Response should have 'purged' count" );
        assertTrue( obj.get( "purged" ).getAsInt() >= 0, "Purged count should be non-negative" );
    }

    @Test
    void testBulkDeleteActuallyDeletesPages() throws Exception {
        engine.saveText( "BulkDeleteMe1", "Delete me." );
        engine.saveText( "BulkDeleteMe2", "Delete me too." );

        final JsonObject body = new JsonObject();
        final com.google.gson.JsonArray pages = new com.google.gson.JsonArray();
        pages.add( "BulkDeleteMe1" );
        pages.add( "BulkDeleteMe2" );
        body.add( "pages", pages );

        final String json = doPost( "/bulk-delete", body );
        final JsonObject obj = gson.fromJson( json, JsonObject.class );

        assertTrue( obj.has( "deleted" ), "Response should have 'deleted' array" );
        final com.google.gson.JsonArray deleted = obj.getAsJsonArray( "deleted" );
        assertEquals( 2, deleted.size(), "Should have deleted 2 pages" );
    }

    // ----- Helper methods -----

    private String doGet( final String pathInfo ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doGet( request, response );
        return sw.toString();
    }

    private String doPost( final String pathInfo, final JsonObject body ) throws Exception {
        final HttpServletRequest request = createRequest( pathInfo );
        Mockito.doReturn( new BufferedReader( new StringReader( body.toString() ) ) ).when( request ).getReader();

        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final StringWriter sw = new StringWriter();
        Mockito.doReturn( new PrintWriter( sw ) ).when( response ).getWriter();

        servlet.doPost( request, response );
        return sw.toString();
    }

    private HttpServletRequest createRequest( final String pathInfo ) {
        final String path = "/admin/content" + ( pathInfo != null ? pathInfo : "" );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest( path );
        Mockito.doReturn( pathInfo ).when( request ).getPathInfo();
        return request;
    }
}

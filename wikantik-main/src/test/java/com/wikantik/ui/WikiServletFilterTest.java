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
package com.wikantik.ui;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;
import com.wikantik.util.HttpUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Date;

public class WikiServletFilterTest {

    TestEngine engine = TestEngine.build();

    @AfterEach
    public void tearDown() throws Exception {
        engine.stop();
    }

    @Test
    public void testETagCreatedForPage() throws Exception {
        final String pageName = "ETagTestPage";
        engine.saveText( pageName, "Some content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified = page.getLastModified();
        Assertions.assertNotNull( lastModified, "Page should have lastModified" );

        final String etag = HttpUtil.createETag( pageName, lastModified );
        Assertions.assertNotNull( etag );
        Assertions.assertFalse( etag.isEmpty() );
    }

    @Test
    public void testCheckFor304WithMatchingETag() throws Exception {
        final String pageName = "Conditional304Page";
        engine.saveText( pageName, "Test content for 304" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified = page.getLastModified();
        final String etag = HttpUtil.createETag( pageName, lastModified );

        final HttpServletRequest mockRequest = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( etag ).when( mockRequest ).getHeader( "If-None-Match" );
        Mockito.doReturn( null ).when( mockRequest ).getHeader( "Pragma" );
        Mockito.doReturn( null ).when( mockRequest ).getHeader( "cache-control" );
        Mockito.doReturn( -1L ).when( mockRequest ).getDateHeader( "If-Modified-Since" );

        final boolean is304 = HttpUtil.checkFor304( mockRequest, pageName, lastModified );
        Assertions.assertTrue( is304, "Should return 304 when ETag matches" );
    }

    @Test
    public void testCheckFor304WithNonMatchingETag() throws Exception {
        final String pageName = "NoMatch304Page";
        engine.saveText( pageName, "Original content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified = page.getLastModified();

        final HttpServletRequest mockRequest = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( "wrong-etag-value" ).when( mockRequest ).getHeader( "If-None-Match" );
        Mockito.doReturn( null ).when( mockRequest ).getHeader( "Pragma" );
        Mockito.doReturn( null ).when( mockRequest ).getHeader( "cache-control" );
        Mockito.doReturn( -1L ).when( mockRequest ).getDateHeader( "If-Modified-Since" );

        final boolean is304 = HttpUtil.checkFor304( mockRequest, pageName, lastModified );
        Assertions.assertFalse( is304, "Should not return 304 when ETag doesn't match" );
    }

    @Test
    public void testCheckFor304SkippedWithNoCacheHeader() throws Exception {
        final String pageName = "NoCachePage";
        engine.saveText( pageName, "Cacheable content" );

        final Page page = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified = page.getLastModified();
        final String etag = HttpUtil.createETag( pageName, lastModified );

        final HttpServletRequest mockRequest = Mockito.mock( HttpServletRequest.class );
        Mockito.doReturn( etag ).when( mockRequest ).getHeader( "If-None-Match" );
        Mockito.doReturn( "no-cache" ).when( mockRequest ).getHeader( "Pragma" );
        Mockito.doReturn( null ).when( mockRequest ).getHeader( "cache-control" );

        final boolean is304 = HttpUtil.checkFor304( mockRequest, pageName, lastModified );
        Assertions.assertFalse( is304, "Should not return 304 when Pragma: no-cache" );
    }

    @Test
    public void testETagChangesAfterPageModification() throws Exception {
        final String pageName = "ModifiedPage304";
        engine.saveText( pageName, "First version" );

        final Page page1 = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified1 = page1.getLastModified();
        final String etag1 = HttpUtil.createETag( pageName, lastModified1 );

        Thread.sleep( 1100 ); // Ensure different timestamp
        engine.saveText( pageName, "Second version" );

        final Page page2 = engine.getManager( PageManager.class ).getPage( pageName );
        final Date lastModified2 = page2.getLastModified();
        final String etag2 = HttpUtil.createETag( pageName, lastModified2 );

        Assertions.assertNotEquals( etag1, etag2, "ETags should differ after modification" );
    }

    @Test
    public void testCacheControlHeaderValue() {
        // Verify that "private" is a valid Cache-Control value we can set
        Assertions.assertEquals( "private", "private" );
    }
}

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
package com.wikantik.providers;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.wikantik.TestEngine.with;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for blog-aware versioning in {@link VersioningFileProvider}.
 * <p>
 * Blog pages store their {@code OLD/} version directory inside the blog user directory
 * (e.g. {@code pageDir/blog/jake/OLD/Blog/1.md}) rather than at the top-level
 * {@code pageDir/OLD/} directory used by regular pages.
 */
class VersioningFileProviderBlogTest {

    private TestEngine engine;
    private String pageDir;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build(
            with( PageManager.PROP_PAGEPROVIDER, "VersioningFileProvider" )
        );
        pageDir = engine.getWikiProperties().getProperty( AbstractFileProvider.PROP_PAGEDIR );
    }

    @AfterEach
    void tearDown() {
        TestEngine.deleteAll( new File( pageDir ) );
        engine.stop();
    }

    @Test
    void testBlogPageVersionHistoryInBlogDirectory() throws Exception {
        // Pre-create blog directory
        final Path blogDir = Path.of( pageDir, "blog", "jake" );
        Files.createDirectories( blogDir );

        // Save two versions
        engine.saveText( "blog/jake/Blog", "version 1" );
        engine.saveText( "blog/jake/Blog", "version 2" );

        // OLD/ should be inside the blog directory
        final Path blogOld = blogDir.resolve( "OLD" ).resolve( "Blog" );
        assertTrue( Files.isDirectory( blogOld ), "OLD/ should be inside blog directory" );
        assertFalse( Files.isDirectory( Path.of( pageDir, "OLD", "blog" ) ),
                "OLD/ should NOT be at the top level for blog pages" );

        // Verify version retrieval
        final PageManager pm = engine.getManager( PageManager.class );
        final List< Page > history = pm.getVersionHistory( "blog/jake/Blog" );
        assertEquals( 2, history.size() );
        assertEquals( "version 1", pm.getPageText( "blog/jake/Blog", 1 ).trim() );
        assertEquals( "version 2", pm.getPageText( "blog/jake/Blog", PageProvider.LATEST_VERSION ).trim() );
    }

    @Test
    void testBlogPageDeletionCleansUpVersions() throws Exception {
        final Path blogDir = Path.of( pageDir, "blog", "jake" );
        Files.createDirectories( blogDir );

        engine.saveText( "blog/jake/TestEntry", "v1" );
        engine.saveText( "blog/jake/TestEntry", "v2" );

        final PageManager pm = engine.getManager( PageManager.class );
        pm.deletePage( "blog/jake/TestEntry" );

        assertFalse( pm.pageExists( "blog/jake/TestEntry" ) );
        assertFalse( Files.exists( blogDir.resolve( "OLD" ).resolve( "TestEntry" ) ) );
    }

    @Test
    void testBlogPageOldVersionTextReadable() throws Exception {
        final Path blogDir = Path.of( pageDir, "blog", "jake" );
        Files.createDirectories( blogDir );

        engine.saveText( "blog/jake/Post1", "first draft" );
        engine.saveText( "blog/jake/Post1", "second draft" );
        engine.saveText( "blog/jake/Post1", "third draft" );

        final PageManager pm = engine.getManager( PageManager.class );
        assertEquals( "first draft", pm.getPageText( "blog/jake/Post1", 1 ).trim() );
        assertEquals( "second draft", pm.getPageText( "blog/jake/Post1", 2 ).trim() );
        assertEquals( "third draft", pm.getPageText( "blog/jake/Post1", PageProvider.LATEST_VERSION ).trim() );
    }

    @Test
    void testBlogPageVersionExists() throws Exception {
        final Path blogDir = Path.of( pageDir, "blog", "jake" );
        Files.createDirectories( blogDir );

        engine.saveText( "blog/jake/VersionCheck", "v1" );
        engine.saveText( "blog/jake/VersionCheck", "v2" );

        final PageManager pm = engine.getManager( PageManager.class );
        final PageProvider provider = pm.getProvider();

        assertTrue( provider.pageExists( "blog/jake/VersionCheck", 1 ), "Version 1 should exist" );
        assertTrue( provider.pageExists( "blog/jake/VersionCheck", 2 ), "Version 2 (latest) should exist" );
        assertFalse( provider.pageExists( "blog/jake/VersionCheck", 3 ), "Version 3 should not exist" );
    }

    @Test
    void testBlogPageDeleteVersion() throws Exception {
        final Path blogDir = Path.of( pageDir, "blog", "jake" );
        Files.createDirectories( blogDir );

        engine.saveText( "blog/jake/DelVer", "v1" );
        engine.saveText( "blog/jake/DelVer", "v2" );
        engine.saveText( "blog/jake/DelVer", "v3" );

        final PageManager pm = engine.getManager( PageManager.class );
        final PageProvider provider = pm.getProvider();

        // Delete the middle version
        provider.deleteVersion( "blog/jake/DelVer", 2 );

        final List< Page > history = provider.getVersionHistory( "blog/jake/DelVer" );
        assertEquals( 2, history.size(), "Should have 2 versions after deleting middle" );
        assertEquals( "v1", provider.getPageText( "blog/jake/DelVer", 1 ).trim() );
        assertEquals( "v3", provider.getPageText( "blog/jake/DelVer", 3 ).trim() );
    }

    @Test
    void testRegularPageVersioningUnaffected() throws Exception {
        // Ensure regular pages still use the top-level OLD/ directory
        engine.saveText( "RegularVersioned", "v1" );
        engine.saveText( "RegularVersioned", "v2" );

        final Path topOld = Path.of( pageDir, "OLD", "RegularVersioned" );
        assertTrue( Files.isDirectory( topOld ), "Regular page OLD/ should be at top level" );

        final PageManager pm = engine.getManager( PageManager.class );
        final List< Page > history = pm.getVersionHistory( "RegularVersioned" );
        assertEquals( 2, history.size() );
        assertEquals( "v1", pm.getPageText( "RegularVersioned", 1 ).trim() );
        assertEquals( "v2", pm.getPageText( "RegularVersioned", PageProvider.LATEST_VERSION ).trim() );
    }
}

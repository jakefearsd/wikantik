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
package com.wikantik.frontmatter;

import com.wikantik.api.frontmatter.FrontmatterParser;
import com.wikantik.api.frontmatter.FrontmatterWriter;
import com.wikantik.api.frontmatter.ParsedPage;

import com.wikantik.TestEngine;
import com.wikantik.WikiContext;
import com.wikantik.api.core.Page;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class FrontmatterPreloaderTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        engine = new TestEngine( props );
    }

    @AfterEach
    void tearDown() throws Exception {
        if ( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            pm.deletePage( "PreloaderTest" );
            pm.deletePage( "PreloaderNoFrontmatter" );
            pm.deletePage( "PreloaderListFields" );
            engine.stop();
        }
    }

    @Test
    void testPreloadSetsAttributes() throws Exception {
        final String content = "---\ntype: concept\nsummary: A test summary\ncluster: technology\n---\n# Test Page\nBody text.";
        engine.saveText( "PreloaderTest", content );

        final Page page = engine.getManager( PageManager.class ).getPage( "PreloaderTest" );
        final WikiContext context = new WikiContext( engine, page );

        FrontmatterPreloader.preloadMetadata( context );

        assertEquals( "concept", page.getAttribute( "type" ) );
        assertEquals( "A test summary", page.getAttribute( "summary" ) );
        assertEquals( "technology", page.getAttribute( "cluster" ) );
        assertEquals( Boolean.TRUE, page.getAttribute( FrontmatterPreloader.ATTR_PRELOADED ) );
    }

    @Test
    void testPreloadListFieldsAsCommaSeparated() throws Exception {
        final String content = "---\ntags: [ai, machine-learning, neural-nets]\nrelated: [PageOne, PageTwo]\n---\nBody.";
        engine.saveText( "PreloaderListFields", content );

        final Page page = engine.getManager( PageManager.class ).getPage( "PreloaderListFields" );
        final WikiContext context = new WikiContext( engine, page );

        FrontmatterPreloader.preloadMetadata( context );

        assertEquals( "ai, machine-learning, neural-nets", page.getAttribute( "tags" ) );
        assertEquals( "PageOne, PageTwo", page.getAttribute( "related" ) );
    }

    @Test
    void testPreloadIdempotent() throws Exception {
        final String content = "---\ntype: concept\nsummary: Original summary\n---\nBody.";
        engine.saveText( "PreloaderTest", content );

        final Page page = engine.getManager( PageManager.class ).getPage( "PreloaderTest" );
        final WikiContext context = new WikiContext( engine, page );

        FrontmatterPreloader.preloadMetadata( context );
        assertEquals( "Original summary", page.getAttribute( "summary" ) );

        // Manually override the attribute
        page.setAttribute( "summary", "Modified" );

        // Second call should be a no-op because guard is set
        FrontmatterPreloader.preloadMetadata( context );
        assertEquals( "Modified", page.getAttribute( "summary" ) );
    }

    @Test
    void testPreloadNoFrontmatter() throws Exception {
        engine.saveText( "PreloaderNoFrontmatter", "Just a regular page with no frontmatter." );

        final Page page = engine.getManager( PageManager.class ).getPage( "PreloaderNoFrontmatter" );
        final WikiContext context = new WikiContext( engine, page );

        FrontmatterPreloader.preloadMetadata( context );

        // Guard should still be set
        assertEquals( Boolean.TRUE, page.getAttribute( FrontmatterPreloader.ATTR_PRELOADED ) );
        // No frontmatter attributes should exist
        assertNull( page.getAttribute( "type" ) );
        assertNull( page.getAttribute( "summary" ) );
    }

    @Test
    void testPreloadDateFieldFormattedAsIso() throws Exception {
        final String content = "---\ndate: 2026-03-20\nsummary: Dated page\n---\nBody.";
        engine.saveText( "PreloaderTest", content );

        final Page page = engine.getManager( PageManager.class ).getPage( "PreloaderTest" );
        final WikiContext context = new WikiContext( engine, page );

        FrontmatterPreloader.preloadMetadata( context );

        assertEquals( "2026-03-20", page.getAttribute( "date" ),
                "Date fields should be stored in ISO format, not Date.toString()" );
    }

    @Test
    void testPreloadNullContext() {
        // Should not throw
        assertDoesNotThrow( () -> FrontmatterPreloader.preloadMetadata( null ) );
    }

    @Test
    void testPreloadWithNullPage() {
        // WikiContext with null page should be handled gracefully
        final WikiContext context = new WikiContext( engine, ( Page ) null );
        assertDoesNotThrow( () -> FrontmatterPreloader.preloadMetadata( context ) );
    }
}

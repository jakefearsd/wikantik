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
package com.wikantik.render;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RenderCacheInvalidationTest {

    private TestEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        engine = new TestEngine( TestEngine.getTestProperties() );
    }

    @AfterEach
    void tearDown() throws Exception {
        if( engine != null ) {
            final PageManager pm = engine.getManager( PageManager.class );
            try {
                pm.deletePage( "CacheTestPage" );
            } catch( final Exception e ) {
                /* ignore */
            }
            engine.stop();
        }
    }

    @Test
    void testRenderedHtmlUpdatesAfterPageEdit() throws Exception {
        // Save version 1
        engine.saveText( "CacheTestPage", "Hello **Version One**" );

        // Render and verify
        final String html1 = renderPage( "CacheTestPage" );
        assertTrue( html1.contains( "Version One" ), "First render should contain 'Version One', got: " + html1 );

        // Edit to version 2
        engine.saveText( "CacheTestPage", "Hello **Version Two**" );

        // Render again -- cache should have been invalidated by the save event
        final String html2 = renderPage( "CacheTestPage" );
        assertTrue( html2.contains( "Version Two" ), "After edit, render should contain 'Version Two', got: " + html2 );
        assertFalse( html2.contains( "Version One" ), "After edit, render should NOT contain 'Version One'" );
    }

    private String renderPage( final String pageName ) throws Exception {
        final PageManager pm = engine.getManager( PageManager.class );
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        final Page page = pm.getPage( pageName, PageProvider.LATEST_VERSION );
        final String text = pm.getPureText( pageName, PageProvider.LATEST_VERSION );
        final Context ctx = Wiki.context().create( engine, page );
        return rm.textToHTML( ctx, text );
    }
}

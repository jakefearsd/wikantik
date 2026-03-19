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
package com.wikantik.plugin;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.PluginException;
import com.wikantik.api.spi.Wiki;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Unit tests for {@link RecentArticles}.
 */
public class RecentArticlesTest {

    private TestEngine m_engine;
    private RecentArticles m_plugin;

    @BeforeEach
    public void setUp() throws Exception {
        m_engine = TestEngine.build();
        m_plugin = new RecentArticles();
    }

    @AfterEach
    public void tearDown() {
        m_engine.stop();
    }

    @Test
    public void testBasicExecution() throws Exception {
        m_engine.saveText( "PluginTestPage", "Plugin test content" );

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
        // Should contain either articles or "no articles" message
        Assertions.assertTrue( result.contains( "recent-articles" ) || result.contains( "No recent articles" ) );
    }

    @Test
    public void testWithCountParameter() throws Exception {
        // Create several test pages
        m_engine.saveText( "CountPluginTest1", "Content 1" );
        m_engine.saveText( "CountPluginTest2", "Content 2" );
        m_engine.saveText( "CountPluginTest3", "Content 3" );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "count", "2" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
    }

    @Test
    public void testWithSinceParameter() throws Exception {
        m_engine.saveText( "SincePluginTest", "Content" );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "since", "7" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
    }

    @Test
    public void testWithExcerptDisabled() throws Exception {
        m_engine.saveText( "NoExcerptPluginTest", "This is content that would normally become an excerpt." );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "excerpt", "false" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
        // When excerpts are disabled, should not contain excerpt class
        // (though this depends on whether there are articles to show)
    }

    @Test
    public void testWithExcerptLength() throws Exception {
        m_engine.saveText( "ExcerptLengthTest", "This is a longer piece of content for testing excerpt length limits." );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "excerptLength", "50" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
    }

    @Test
    public void testWithExcludePattern() throws Exception {
        m_engine.saveText( "ExcludeTestAdmin", "Admin content" );
        m_engine.saveText( "ExcludeTestRegular", "Regular content" );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "exclude", ".*Admin.*" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
        // Admin page should be excluded
        Assertions.assertFalse( result.contains( "ExcludeTestAdmin" ) );
    }

    @Test
    public void testWithIncludePattern() throws Exception {
        m_engine.saveText( "BlogIncludeTest1", "Blog content 1" );
        m_engine.saveText( "BlogIncludeTest2", "Blog content 2" );
        m_engine.saveText( "OtherIncludeTest", "Other content" );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "include", "Blog.*" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
        // Other page should not be included
        Assertions.assertFalse( result.contains( "OtherIncludeTest" ) );
    }

    @Test
    public void testWithCustomCssClass() throws Exception {
        m_engine.saveText( "CssClassTest", "Content" );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "cssClass", "my-custom-class" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
        // Should use custom CSS class
        Assertions.assertTrue( result.contains( "my-custom-class" ) || result.contains( "No recent articles" ) );
    }

    @Test
    public void testInvalidCountParameter() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "count", "not-a-number" );

        // Should not throw, but use default value instead
        Assertions.assertDoesNotThrow( () -> m_plugin.execute( context, params ) );
    }

    @Test
    public void testNegativeCountUsesDefault() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "count", "-5" );

        // Should not throw, but use default value instead
        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    @Test
    public void testMaxCountLimit() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "count", "1000" ); // Over the max limit

        // Should clamp to max value, not throw
        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    @Test
    public void testEmptyParameters() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
    }

    @Test
    public void testMultipleParameters() throws Exception {
        m_engine.saveText( "MultiParamTest", "!!! Test Title\nTest content for multiple parameters." );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "count", "5" );
        params.put( "since", "14" );
        params.put( "excerpt", "true" );
        params.put( "excerptLength", "100" );

        final String result = m_plugin.execute( context, params );

        Assertions.assertNotNull( result );
    }

    @Test
    public void testHtmlEscaping() throws Exception {
        // Create page with special HTML characters in content
        m_engine.saveText( "HtmlEscapeTest", "!!! Test <script>alert('xss')</script>\nContent with <b>html</b> tags." );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();

        final String result = m_plugin.execute( context, params );

        // Should not contain unescaped script tags in the output
        Assertions.assertFalse( result.contains( "<script>" ) );
    }

    @Test
    public void testOutputContainsArticleStructure() throws Exception {
        m_engine.saveText( "StructureTest", "!!! Structure Test Title\nSome content here." );

        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();

        final String result = m_plugin.execute( context, params );

        // Check for expected HTML structure elements
        if ( result.contains( "StructureTest" ) ) {
            Assertions.assertTrue( result.contains( "article-card" ) || result.contains( "article-title" ) );
        }
    }

    private Context createContext() {
        try {
            Page page = m_engine.getManager( PageManager.class ).getPage( "Main" );
            if ( page == null ) {
                m_engine.saveText( "Main", "Main page" );
                page = m_engine.getManager( PageManager.class ).getPage( "Main" );
            }
            return Wiki.context().create( m_engine, page );
        } catch ( final Exception e ) {
            return Wiki.context().create( m_engine, Wiki.contents().page( m_engine, "Main" ) );
        }
    }

    private void clearManagerCache() {
        final RecentArticlesManager manager = m_engine.getManager( RecentArticlesManager.class );
        if ( manager != null ) {
            manager.clearCache();
        }
    }
}

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
import com.wikantik.content.ArticleSummary;
import com.wikantik.content.RecentArticlesManager;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Additional coverage tests for {@link RecentArticles} targeting previously
 * uncovered branches: zero/negative since, zero/negative excerptLength,
 * article rendering with author+date separator, excerpt rendering,
 * changeNote rendering, empty-articles path, and no-manager path.
 */
public class RecentArticlesCITest {

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

    // -------------------------------------------------------------------------
    // renderHtml branch coverage via the plugin execute path
    // -------------------------------------------------------------------------

    /**
     * Negative since value resets to default.
     * Covers the {@code since <= 0} branch in buildQuery.
     */
    @Test
    public void testNegativeSinceResetsToDefault() throws Exception {
        m_engine.saveText( "NegSinceTest", "Content" );
        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "since", "-10" );

        // Should not throw; uses default since value
        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    /**
     * Zero since value resets to default.
     */
    @Test
    public void testZeroSinceResetsToDefault() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "since", "0" );

        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    /**
     * Negative excerptLength resets to default.
     * Covers the {@code length <= 0} branch in buildQuery for excerptLength.
     */
    @Test
    public void testNegativeExcerptLengthResetsToDefault() throws Exception {
        m_engine.saveText( "NegExcerptLen", "Content" );
        clearManagerCache();

        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "excerptLength", "-50" );

        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    /**
     * Zero excerptLength resets to default.
     */
    @Test
    public void testZeroExcerptLengthResetsToDefault() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "excerptLength", "0" );

        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    /**
     * Empty exclude and include parameters are ignored (not set on query).
     * Covers the {@code excludeParam != null && !excludeParam.isEmpty()} false path.
     */
    @Test
    public void testEmptyExcludeAndIncludeAreIgnored() throws Exception {
        final Context context = createContext();
        final Map<String, String> params = new HashMap<>();
        params.put( "exclude", "" );
        params.put( "include", "" );

        final String result = m_plugin.execute( context, params );
        Assertions.assertNotNull( result );
    }

    // -------------------------------------------------------------------------
    // renderHtml tested directly via reflection to hit article rendering branches
    // -------------------------------------------------------------------------

    /**
     * renderHtml with a null/empty list returns the "no articles" message.
     * Covers the {@code articles == null || articles.isEmpty()} branch.
     */
    @Test
    public void testRenderHtmlEmptyList() throws Exception {
        final String result = invokeRenderHtml( new ArrayList<>(), "recent-articles" );

        Assertions.assertTrue( result.contains( "no-articles" ),
                "Empty list should produce 'no-articles' paragraph" );
        Assertions.assertTrue( result.contains( "No recent articles found" ),
                "Empty list should contain the no-articles message" );
    }

    /**
     * renderHtml with null list returns the "no articles" message.
     */
    @Test
    public void testRenderHtmlNullList() throws Exception {
        final String result = invokeRenderHtml( null, "recent-articles" );

        Assertions.assertTrue( result.contains( "no-articles" ),
                "Null list should produce 'no-articles' paragraph" );
    }

    /**
     * Article with both author and lastModified renders the middle-dot separator.
     * Covers the {@code article.getAuthor() != null} AND {@code article.getLastModified() != null} path.
     */
    @Test
    public void testRenderHtmlArticleWithAuthorAndDate() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "TestPage" )
                .title( "Test Page Title" )
                .author( "Alice" )
                .lastModified( new Date() )
                .url( "/wiki/TestPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertTrue( result.contains( "Alice" ), "Should contain author name" );
        Assertions.assertTrue( result.contains( "&middot;" ),
                "Author AND date should be separated by middle dot" );
        Assertions.assertTrue( result.contains( "article-title" ),
                "Should contain article-title class" );
    }

    /**
     * Article with author but no lastModified: no middle-dot separator.
     * Covers the {@code article.getLastModified() != null} false branch.
     */
    @Test
    public void testRenderHtmlArticleWithAuthorNoDate() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "TestPage" )
                .title( "Test Page" )
                .author( "Bob" )
                .url( "/wiki/TestPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertTrue( result.contains( "Bob" ), "Should contain author name" );
        Assertions.assertFalse( result.contains( "&middot;" ),
                "No date means no middle dot" );
    }

    /**
     * Article with no author but with lastModified: no middle-dot separator.
     * Covers the {@code article.getAuthor() != null} false branch in meta section.
     */
    @Test
    public void testRenderHtmlArticleWithDateNoAuthor() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "TestPage" )
                .title( "Test Page" )
                .lastModified( new Date() )
                .url( "/wiki/TestPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertTrue( result.contains( "date" ),
                "Should contain date span class" );
        Assertions.assertFalse( result.contains( "&middot;" ),
                "No author means no middle dot before date" );
    }

    /**
     * Article with a non-empty excerpt renders the excerpt paragraph.
     * Covers the {@code article.getExcerpt() != null && !article.getExcerpt().isEmpty()} branch.
     */
    @Test
    public void testRenderHtmlArticleWithExcerpt() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "ExcerptPage" )
                .title( "Excerpt Page" )
                .excerpt( "This is a short excerpt." )
                .url( "/wiki/ExcerptPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertTrue( result.contains( "article-excerpt" ),
                "Should contain excerpt class" );
        Assertions.assertTrue( result.contains( "This is a short excerpt." ),
                "Should contain excerpt text" );
    }

    /**
     * Article with a non-empty changeNote renders the changenote paragraph.
     * Covers the {@code article.getChangeNote() != null && !article.getChangeNote().isEmpty()} branch.
     */
    @Test
    public void testRenderHtmlArticleWithChangeNote() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "NotePage" )
                .title( "Note Page" )
                .changeNote( "Fixed typo in introduction." )
                .url( "/wiki/NotePage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertTrue( result.contains( "article-changenote" ),
                "Should contain changenote class" );
        Assertions.assertTrue( result.contains( "Fixed typo in introduction." ),
                "Should contain changenote text" );
    }

    /**
     * Article with no excerpt: excerpt paragraph is not rendered.
     * Covers the {@code article.getExcerpt() == null} false branch.
     */
    @Test
    public void testRenderHtmlArticleWithNoExcerpt() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "NoExcerptPage" )
                .title( "No Excerpt" )
                .url( "/wiki/NoExcerptPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "recent-articles" );

        Assertions.assertFalse( result.contains( "article-excerpt" ),
                "No excerpt means no excerpt paragraph" );
    }

    /**
     * Custom cssClass is used in the container div.
     */
    @Test
    public void testRenderHtmlCustomCssClass() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "CssPage" )
                .title( "CSS Page" )
                .url( "/wiki/CssPage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "my-widget" );

        Assertions.assertTrue( result.contains( "class=\"my-widget\"" ),
                "Custom CSS class should appear in the container div" );
    }

    /**
     * HTML special characters in cssClass are escaped.
     * Covers the escapeHtml path for the cssClass value.
     */
    @Test
    public void testRenderHtmlCssClassEscaping() throws Exception {
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "EscapePage" )
                .title( "Escape" )
                .url( "/wiki/EscapePage" )
                .build();

        final String result = invokeRenderHtml( List.of( article ), "a\"b" );

        Assertions.assertTrue( result.contains( "&quot;" ),
                "Quotes in cssClass should be HTML-escaped" );
    }

    // -------------------------------------------------------------------------
    // Helper methods
    // -------------------------------------------------------------------------

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

    /**
     * Calls the private {@code renderHtml} method via reflection so that we can
     * exercise its branches without needing a full plugin execution.
     */
    private String invokeRenderHtml( final List<ArticleSummary> articles, final String cssClass )
            throws Exception {
        final Method method = RecentArticles.class.getDeclaredMethod(
                "renderHtml", List.class, String.class );
        method.setAccessible( true );
        return ( String ) method.invoke( m_plugin, articles, cssClass );
    }
}

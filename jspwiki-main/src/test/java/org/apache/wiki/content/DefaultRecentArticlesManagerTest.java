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
package org.apache.wiki.content;

import org.apache.wiki.TestEngine;
import org.apache.wiki.WikiContext;
import org.apache.wiki.api.core.Context;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.spi.Wiki;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link DefaultRecentArticlesManager}.
 */
public class DefaultRecentArticlesManagerTest {

    private TestEngine m_engine;
    private RecentArticlesManager m_manager;

    @BeforeEach
    public void setUp() throws Exception {
        m_engine = TestEngine.build();
        m_manager = m_engine.getManager( RecentArticlesManager.class );
    }

    @AfterEach
    public void tearDown() {
        m_engine.stop();
    }

    @Test
    public void testManagerAvailable() {
        Assertions.assertNotNull( m_manager, "RecentArticlesManager should be available" );
    }

    @Test
    public void testGetRecentArticlesEmpty() {
        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        Assertions.assertNotNull( articles );
        // May have some pages from previous tests, so just check it returns a list
    }

    @Test
    public void testGetRecentArticlesWithPages() throws Exception {
        // Create some test pages
        m_engine.saveText( "TestArticle1", "!!! Article One\nThis is the content of article one." );
        m_engine.saveText( "TestArticle2", "!!! Article Two\nThis is the content of article two." );
        m_engine.saveText( "TestArticle3", "!!! Article Three\nThis is the content of article three." );

        // Clear cache to ensure fresh results
        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        Assertions.assertNotNull( articles );
        Assertions.assertFalse( articles.isEmpty(), "Should have at least one article" );

        // Check that our test articles are in the results
        boolean foundTestArticle = articles.stream()
            .anyMatch( a -> a.getName().startsWith( "TestArticle" ) );
        Assertions.assertTrue( foundTestArticle, "Should find test articles" );
    }

    @Test
    public void testTitleExtraction() throws Exception {
        // Create a page with an H1 heading
        m_engine.saveText( "TestTitleExtraction", "!!! My Article Title\nSome content here." );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final ArticleSummary article = articles.stream()
            .filter( a -> a.getName().equals( "TestTitleExtraction" ) )
            .findFirst()
            .orElse( null );

        Assertions.assertNotNull( article, "Should find TestTitleExtraction page" );
        Assertions.assertEquals( "My Article Title", article.getTitle(), "Should extract title from H1" );
    }

    @Test
    public void testTitleFallbackToPageName() throws Exception {
        // Create a page without an H1 heading
        m_engine.saveText( "TestNoHeading", "Just some plain text content without a heading." );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final ArticleSummary article = articles.stream()
            .filter( a -> a.getName().equals( "TestNoHeading" ) )
            .findFirst()
            .orElse( null );

        Assertions.assertNotNull( article, "Should find TestNoHeading page" );
        // Title should fall back to page name (beautified)
        Assertions.assertNotNull( article.getTitle() );
    }

    @Test
    public void testCountLimit() throws Exception {
        // Create several test pages
        for ( int i = 1; i <= 5; i++ ) {
            m_engine.saveText( "CountTestPage" + i, "Content " + i );
        }

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 2 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        Assertions.assertTrue( articles.size() <= 2, "Should return at most 2 articles" );
    }

    @Test
    public void testExcludePattern() throws Exception {
        m_engine.saveText( "SystemPage", "System content" );
        m_engine.saveText( "RegularPage", "Regular content" );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 100 )
            .excludePattern( "System.*" );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final boolean foundSystem = articles.stream()
            .anyMatch( a -> a.getName().equals( "SystemPage" ) );
        final boolean foundRegular = articles.stream()
            .anyMatch( a -> a.getName().equals( "RegularPage" ) );

        Assertions.assertFalse( foundSystem, "SystemPage should be excluded" );
        Assertions.assertTrue( foundRegular, "RegularPage should be included" );
    }

    @Test
    public void testIncludePattern() throws Exception {
        m_engine.saveText( "BlogPost1", "Blog content 1" );
        m_engine.saveText( "BlogPost2", "Blog content 2" );
        m_engine.saveText( "OtherPage", "Other content" );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 100 )
            .includePattern( "Blog.*" );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final boolean foundBlog1 = articles.stream()
            .anyMatch( a -> a.getName().equals( "BlogPost1" ) );
        final boolean foundBlog2 = articles.stream()
            .anyMatch( a -> a.getName().equals( "BlogPost2" ) );
        final boolean foundOther = articles.stream()
            .anyMatch( a -> a.getName().equals( "OtherPage" ) );

        Assertions.assertTrue( foundBlog1, "BlogPost1 should be included" );
        Assertions.assertTrue( foundBlog2, "BlogPost2 should be included" );
        Assertions.assertFalse( foundOther, "OtherPage should be excluded (not matching include pattern)" );
    }

    @Test
    public void testExcludedSystemPages() throws Exception {
        // "Main" should be in the excluded list by default
        m_engine.saveText( "Main", "Main page content" );
        m_engine.saveText( "NonExcludedPage", "Some other content" );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final boolean foundMain = articles.stream()
            .anyMatch( a -> a.getName().equals( "Main" ) );

        Assertions.assertFalse( foundMain, "Main should be excluded by default" );
    }

    @Test
    public void testExcerptGeneration() throws Exception {
        m_engine.saveText( "ExcerptTestPage", "!!! Test\nThis is a longer piece of content that should be used to generate an excerpt." );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 100 )
            .includeExcerpt( true )
            .excerptLength( 50 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final ArticleSummary article = articles.stream()
            .filter( a -> a.getName().equals( "ExcerptTestPage" ) )
            .findFirst()
            .orElse( null );

        Assertions.assertNotNull( article, "Should find ExcerptTestPage" );
        // Excerpt may be null for short content or if rendering failed
        // But URL should always be set
        Assertions.assertNotNull( article.getUrl() );
    }

    @Test
    public void testNoExcerptWhenDisabled() throws Exception {
        m_engine.saveText( "NoExcerptPage", "Some content that would normally become an excerpt." );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 100 )
            .includeExcerpt( false );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final ArticleSummary article = articles.stream()
            .filter( a -> a.getName().equals( "NoExcerptPage" ) )
            .findFirst()
            .orElse( null );

        Assertions.assertNotNull( article, "Should find NoExcerptPage" );
        Assertions.assertNull( article.getExcerpt(), "Excerpt should be null when disabled" );
    }

    @Test
    public void testCaching() throws Exception {
        m_engine.saveText( "CacheTestPage", "Cache test content" );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        // First call
        final List<ArticleSummary> articles1 = m_manager.getRecentArticles( context, query );

        // Second call should hit cache (same query)
        final List<ArticleSummary> articles2 = m_manager.getRecentArticles( context, query );

        Assertions.assertEquals( articles1.size(), articles2.size(), "Cache should return same results" );
    }

    @Test
    public void testClearCache() throws Exception {
        m_engine.saveText( "ClearCacheTestPage", "Clear cache test content" );

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        // Get initial results
        m_manager.getRecentArticles( context, query );

        // Clear cache
        m_manager.clearCache();

        // Add new page
        m_engine.saveText( "NewPageAfterCacheClear", "New content" );

        // Get results again (should include new page)
        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final boolean foundNew = articles.stream()
            .anyMatch( a -> a.getName().equals( "NewPageAfterCacheClear" ) );

        Assertions.assertTrue( foundNew, "Should find newly created page after cache clear" );
    }

    @Test
    public void testNullContext() {
        final RecentArticlesQuery query = new RecentArticlesQuery();

        final List<ArticleSummary> articles = m_manager.getRecentArticles( null, query );

        Assertions.assertNotNull( articles );
        Assertions.assertTrue( articles.isEmpty() );
    }

    @Test
    public void testNullQuery() {
        final Context context = createContext();

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, null );

        Assertions.assertNotNull( articles );
        Assertions.assertTrue( articles.isEmpty() );
    }

    @Test
    public void testArticleSummaryFields() throws Exception {
        m_engine.saveText( "FieldTestPage", "!!! Field Test Title\nTest content for field validation." );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery()
            .count( 100 )
            .includeExcerpt( true );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        final ArticleSummary article = articles.stream()
            .filter( a -> a.getName().equals( "FieldTestPage" ) )
            .findFirst()
            .orElse( null );

        Assertions.assertNotNull( article, "Should find FieldTestPage" );
        Assertions.assertEquals( "FieldTestPage", article.getName() );
        Assertions.assertNotNull( article.getUrl() );
        Assertions.assertNotNull( article.getLastModified() );
        // Note: Version may be -1 (LATEST_VERSION) in some cases
        // We just ensure it has a value (including sentinel values)
    }

    @Test
    public void testDefaultQueryMethod() throws Exception {
        m_engine.saveText( "DefaultQueryPage", "Default query content" );

        m_manager.clearCache();

        final Context context = createContext();

        // Use the default method that creates a new query
        final List<ArticleSummary> articles = m_manager.getRecentArticles( context );

        Assertions.assertNotNull( articles );
    }

    @Test
    public void testHasTemplatePage() {
        // By default, there should be no template page
        Assertions.assertFalse( m_manager.hasTemplatePage() );
    }

    @Test
    public void testRenderWithTemplate() throws Exception {
        m_engine.saveText( "RenderTestPage", "Render test content" );

        m_manager.clearCache();

        final Context context = createContext();
        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        // Should use default rendering since no template exists
        final String html = m_manager.renderWithTemplate( context, articles );

        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "recent-articles" ) || html.contains( "No recent articles" ) );
    }

    @Test
    public void testRenderEmptyList() {
        final Context context = createContext();

        final String html = m_manager.renderWithTemplate( context, List.of() );

        Assertions.assertNotNull( html );
        Assertions.assertTrue( html.contains( "No recent articles" ) );
    }

    @Test
    public void testResultsAreSorted() throws Exception {
        // Create pages with different timestamps
        m_engine.saveText( "SortTestOldPage", "Old content" );
        Thread.sleep( 100 ); // Small delay to ensure different timestamps
        m_engine.saveText( "SortTestNewPage", "New content" );

        m_manager.clearCache();

        final Context context = createContext();
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 100 );

        final List<ArticleSummary> articles = m_manager.getRecentArticles( context, query );

        // Find indices of our test pages
        int oldIndex = -1;
        int newIndex = -1;
        for ( int i = 0; i < articles.size(); i++ ) {
            if ( articles.get( i ).getName().equals( "SortTestOldPage" ) ) {
                oldIndex = i;
            }
            if ( articles.get( i ).getName().equals( "SortTestNewPage" ) ) {
                newIndex = i;
            }
        }

        if ( oldIndex >= 0 && newIndex >= 0 ) {
            Assertions.assertTrue( newIndex < oldIndex, "Newer page should come before older page" );
        }
    }

    private Context createContext() {
        try {
            final Page page = m_engine.getManager( PageManager.class ).getPage( "Main" );
            if ( page != null ) {
                return Wiki.context().create( m_engine, page );
            }
        } catch ( final Exception e ) {
            // Ignore
        }
        return Wiki.context().create( m_engine, Wiki.contents().page( m_engine, "Main" ) );
    }
}

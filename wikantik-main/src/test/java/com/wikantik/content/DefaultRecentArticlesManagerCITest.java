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
package com.wikantik.content;

import com.wikantik.MockEngineBuilder;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.content.SystemPageRegistry;
import com.wikantik.attachment.Attachment;
import com.wikantik.pages.PageManager;
import com.wikantik.render.RenderingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Constructor-injection unit tests for {@link DefaultRecentArticlesManager}.
 * Focuses on behaviors NOT covered by the integration-style {@link DefaultRecentArticlesManagerTest},
 * particularly: article list population and ordering, system-page and pattern-based filtering,
 * cache invalidation, pagination/limit, excerpt generation edge cases, template rendering paths,
 * HTML entity decoding, and error handling.
 */
class DefaultRecentArticlesManagerCITest {

    private Engine engine;
    private PageManager pageManager;
    private RenderingManager renderingManager;
    private SystemPageRegistry systemPageRegistry;
    private Context context;

    private DefaultRecentArticlesManager mgr;

    @BeforeEach
    void setUp() {
        pageManager = mock( PageManager.class );
        renderingManager = mock( RenderingManager.class );
        systemPageRegistry = mock( SystemPageRegistry.class );

        engine = MockEngineBuilder.engine()
                .with( PageManager.class, pageManager )
                .with( RenderingManager.class, renderingManager )
                .with( com.wikantik.content.SystemPageRegistry.class, systemPageRegistry )
                .build();

        context = mock( Context.class );
        when( context.getURL( eq( ContextEnum.PAGE_VIEW.getRequestContext() ), anyString() ) )
                .thenAnswer( inv -> "/wiki/" + inv.getArgument( 1 ) );

        mgr = new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry );
        mgr.initialize( engine, new Properties() );
    }

    // --- Helper methods ---

    private Page mockPage( final String name, final Date lastModified ) {
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( name );
        when( page.getLastModified() ).thenReturn( lastModified );
        when( page.getVersion() ).thenReturn( 1 );
        when( page.getSize() ).thenReturn( 100L );
        return page;
    }

    private Page mockPage( final String name ) {
        return mockPage( name, new Date() );
    }

    private void setupRecentChanges( final Page... pages ) {
        final Set<Page> recentChanges = new LinkedHashSet<>();
        for ( final Page p : pages ) {
            recentChanges.add( p );
        }
        when( pageManager.getRecentChanges( any( Date.class ) ) ).thenReturn( recentChanges );
    }

    // ==================== Null / empty input ====================

    @Test
    void testNullContextReturnsEmptyList() {
        final List<ArticleSummary> result = mgr.getRecentArticles( null, new RecentArticlesQuery() );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    @Test
    void testNullQueryReturnsEmptyList() {
        final List<ArticleSummary> result = mgr.getRecentArticles( context, null );
        assertNotNull( result );
        assertTrue( result.isEmpty() );
    }

    // ==================== Article list population ====================

    @Test
    void testBasicArticlePopulation() {
        final Page page = mockPage( "MyArticle" );
        when( pageManager.getPureText( page ) ).thenReturn( "# My Title\nSome content." );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "MyArticle", articles.get( 0 ).getName() );
        assertEquals( "My Title", articles.get( 0 ).getTitle() );
        assertEquals( "/wiki/MyArticle", articles.get( 0 ).getUrl() );
    }

    @Test
    void testMultipleArticlesPreserveOrder() {
        final Page p1 = mockPage( "First" );
        final Page p2 = mockPage( "Second" );
        final Page p3 = mockPage( "Third" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "Some content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2, p3 );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 3, articles.size() );
        assertEquals( "First", articles.get( 0 ).getName() );
        assertEquals( "Second", articles.get( 1 ).getName() );
        assertEquals( "Third", articles.get( 2 ).getName() );
    }

    @Test
    void testPageManagerNullReturnsEmptyList() {
        final DefaultRecentArticlesManager noPageMgr =
                new DefaultRecentArticlesManager( engine, null, renderingManager, systemPageRegistry );
        noPageMgr.initialize( engine, new Properties() );

        final List<ArticleSummary> articles = noPageMgr.getRecentArticles( context, new RecentArticlesQuery() );

        assertNotNull( articles );
        assertTrue( articles.isEmpty() );
    }

    // ==================== Count / limit ====================

    @Test
    void testCountLimitsResults() {
        final Page p1 = mockPage( "A" );
        final Page p2 = mockPage( "B" );
        final Page p3 = mockPage( "C" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2, p3 );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 2 ) );

        assertEquals( 2, articles.size() );
        assertEquals( "A", articles.get( 0 ).getName() );
        assertEquals( "B", articles.get( 1 ).getName() );
    }

    @Test
    void testCountOfOneReturnsOne() {
        final Page p1 = mockPage( "Only" );
        final Page p2 = mockPage( "Skipped" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2 );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 1 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "Only", articles.get( 0 ).getName() );
    }

    // ==================== Attachment filtering ====================

    @Test
    void testAttachmentsAreSkipped() {
        final Page normalPage = mockPage( "NormalPage" );
        when( pageManager.getPureText( normalPage ) ).thenReturn( "Normal content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );

        final Attachment attachment = mock( Attachment.class );
        when( attachment.getName() ).thenReturn( "NormalPage/attachment.pdf" );
        when( attachment.getLastModified() ).thenReturn( new Date() );
        when( attachment.getVersion() ).thenReturn( 1 );

        setupRecentChanges( attachment, normalPage );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "NormalPage", articles.get( 0 ).getName() );
    }

    // ==================== System page exclusion ====================

    @Test
    void testSystemPagesAreExcluded() {
        final Page sysPage = mockPage( "LeftMenu" );
        final Page normalPage = mockPage( "Article" );
        when( systemPageRegistry.isSystemPage( "LeftMenu" ) ).thenReturn( true );
        when( systemPageRegistry.isSystemPage( "Article" ) ).thenReturn( false );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( sysPage, normalPage );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "Article", articles.get( 0 ).getName() );
    }

    @Test
    void testNullSystemPageRegistryDoesNotExclude() {
        final DefaultRecentArticlesManager noRegistryMgr =
                new DefaultRecentArticlesManager( engine, pageManager, renderingManager, null );
        noRegistryMgr.initialize( engine, new Properties() );

        final Page page = mockPage( "LeftMenu" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = noRegistryMgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
    }

    // ==================== Pattern-based filtering ====================

    @Test
    void testExcludePatternFiltersPages() {
        final Page p1 = mockPage( "BlogPost" );
        final Page p2 = mockPage( "SystemInfo" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2 );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).excludePattern( "System.*" );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertEquals( 1, articles.size() );
        assertEquals( "BlogPost", articles.get( 0 ).getName() );
    }

    @Test
    void testIncludePatternOnlyMatchingPages() {
        final Page p1 = mockPage( "BlogPost" );
        final Page p2 = mockPage( "Article" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2 );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includePattern( "Blog.*" );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertEquals( 1, articles.size() );
        assertEquals( "BlogPost", articles.get( 0 ).getName() );
    }

    @Test
    void testInvalidQueryPatternIsIgnored() {
        final Page page = mockPage( "Page" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        // Invalid regex should be treated as null (ignored)
        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).excludePattern( "[invalid" );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        // Page should still be returned since the invalid pattern is ignored
        assertEquals( 1, articles.size() );
    }

    @Test
    void testConfiguredExcludePatternsApplied() {
        final Properties props = new Properties();
        props.setProperty( RecentArticlesManager.PROP_EXCLUDE_PATTERNS, "Admin.*,Config.*" );

        final DefaultRecentArticlesManager customMgr =
                new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry );
        customMgr.initialize( engine, props );

        final Page p1 = mockPage( "AdminPage" );
        final Page p2 = mockPage( "ConfigSettings" );
        final Page p3 = mockPage( "NormalArticle" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2, p3 );

        final List<ArticleSummary> articles = customMgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "NormalArticle", articles.get( 0 ).getName() );
    }

    @Test
    void testInvalidConfiguredExcludePatternIsSkipped() {
        final Properties props = new Properties();
        props.setProperty( RecentArticlesManager.PROP_EXCLUDE_PATTERNS, "[invalid,Valid.*" );

        final DefaultRecentArticlesManager customMgr =
                new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry );
        customMgr.initialize( engine, props );

        final Page p1 = mockPage( "ValidMatch" );
        final Page p2 = mockPage( "Other" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2 );

        final List<ArticleSummary> articles = customMgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        // "Valid.*" should still apply even though first pattern was invalid
        assertEquals( 1, articles.size() );
        assertEquals( "Other", articles.get( 0 ).getName() );
    }

    // ==================== Title extraction ====================

    @Test
    void testTitleExtractedFromH1() {
        final Page page = mockPage( "MyPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# My Great Title\nContent here." );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( "My Great Title", articles.get( 0 ).getTitle() );
    }

    @Test
    void testTitleExtractedFromH1WithLeadingWhitespace() {
        final Page page = mockPage( "MyPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "  # Indented Title  \nContent." );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( "Indented Title", articles.get( 0 ).getTitle() );
    }

    @Test
    void testTitleFallsBackToBeautifiedName() {
        final Page page = mockPage( "MyPageName" );
        when( pageManager.getPureText( page ) ).thenReturn( "No heading here, just content." );
        when( renderingManager.beautifyTitle( "MyPageName" ) ).thenReturn( "My Page Name" );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context,
                new RecentArticlesQuery().count( 10 ).includeExcerpt( false ) );

        assertEquals( "My Page Name", articles.get( 0 ).getTitle() );
    }

    @Test
    void testTitleFallsBackToPageNameWhenNoRenderingManager() {
        final DefaultRecentArticlesManager noRenderMgr =
                new DefaultRecentArticlesManager( engine, pageManager, null, systemPageRegistry );
        noRenderMgr.initialize( engine, new Properties() );

        final Page page = mockPage( "RawPageName" );
        when( pageManager.getPureText( page ) ).thenReturn( "No heading in this content." );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = noRenderMgr.getRecentArticles( context,
                new RecentArticlesQuery().count( 10 ).includeExcerpt( false ) );

        assertEquals( "RawPageName", articles.get( 0 ).getTitle() );
    }

    @Test
    void testTitleFromHtmlH1WhenNoWikiH1() {
        final Page page = mockPage( "MyPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "No markdown heading." );
        when( renderingManager.getHTML( "MyPage", 1 ) ).thenReturn( "<h1>HTML Title</h1><p>Some text</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertEquals( "HTML Title", articles.get( 0 ).getTitle() );
    }

    @Test
    void testTitleWithNullPageText() {
        final Page page = mockPage( "NullTextPage" );
        when( pageManager.getPureText( page ) ).thenReturn( null );
        when( renderingManager.beautifyTitle( "NullTextPage" ) ).thenReturn( "Null Text Page" );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context,
                new RecentArticlesQuery().count( 10 ).includeExcerpt( false ) );

        assertEquals( "Null Text Page", articles.get( 0 ).getTitle() );
    }

    // ==================== Excerpt generation ====================

    @Test
    void testExcerptGeneratedWhenEnabled() {
        final Page page = mockPage( "ExcerptPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        final String longHtml = "<h1>Title</h1><p>This is a longer piece of content that should definitely be long enough to generate an excerpt from the page.</p>";
        when( renderingManager.getHTML( "ExcerptPage", 1 ) ).thenReturn( longHtml );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true ).excerptLength( 200 );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNotNull( articles.get( 0 ).getExcerpt() );
        assertFalse( articles.get( 0 ).getExcerpt().isEmpty() );
    }

    @Test
    void testExcerptNullWhenDisabled() {
        final Page page = mockPage( "NoExcerptPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nLong content here." );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( false );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNull( articles.get( 0 ).getExcerpt() );
    }

    @Test
    void testExcerptNullForShortContent() {
        final Page page = mockPage( "ShortPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nShort" );
        when( renderingManager.getHTML( "ShortPage", 1 ) ).thenReturn( "<h1>Title</h1><p>Short</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        // Content less than 20 chars after stripping HTML should return null
        assertNull( articles.get( 0 ).getExcerpt() );
    }

    @Test
    void testExcerptTruncatedWithEllipsis() {
        final Page page = mockPage( "LongPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        // Create content that's longer than the excerpt length
        final String longContent = "<p>" + "word ".repeat( 100 ) + "</p>";
        when( renderingManager.getHTML( "LongPage", 1 ) ).thenReturn( longContent );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true ).excerptLength( 50 );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNotNull( articles.get( 0 ).getExcerpt() );
        assertTrue( articles.get( 0 ).getExcerpt().endsWith( "..." ) );
        assertTrue( articles.get( 0 ).getExcerpt().length() <= 60 ); // 50 + "..." + some margin for word boundary
    }

    @Test
    void testExcerptNullForEmptyHtml() {
        final Page page = mockPage( "EmptyHtml" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        when( renderingManager.getHTML( "EmptyHtml", 1 ) ).thenReturn( "" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNull( articles.get( 0 ).getExcerpt() );
    }

    @Test
    void testExcerptNullWhenRenderingReturnsNull() {
        final Page page = mockPage( "NullHtml" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        when( renderingManager.getHTML( "NullHtml", 1 ) ).thenReturn( null );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNull( articles.get( 0 ).getExcerpt() );
    }

    @Test
    void testExcerptStripsHtmlEntities() {
        final Page page = mockPage( "EntityPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        final String htmlWithEntities = "<p>This is content with &amp; ampersand and &lt;angle brackets&gt; and &quot;quotes&quot; making it longer.</p>";
        when( renderingManager.getHTML( "EntityPage", 1 ) ).thenReturn( htmlWithEntities );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        final String excerpt = articles.get( 0 ).getExcerpt();
        assertNotNull( excerpt );
        assertFalse( excerpt.contains( "&amp;" ) );
        assertFalse( excerpt.contains( "&lt;" ) );
        assertTrue( excerpt.contains( "&" ) );
        assertTrue( excerpt.contains( "<" ) );
    }

    @Test
    void testExcerptHandlesRenderingException() {
        final Page page = mockPage( "ErrorPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        when( renderingManager.getHTML( "ErrorPage", 1 ) ).thenThrow( new RuntimeException( "render error" ) );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        // Should still produce a summary, just without excerpt
        assertEquals( 1, articles.size() );
        assertNull( articles.get( 0 ).getExcerpt() );
    }

    // ==================== Cache behavior ====================

    @Test
    void testCacheHitReturnsSameResults() {
        final Page page = mockPage( "CachedPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( false );

        final List<ArticleSummary> first = mgr.getRecentArticles( context, query );
        final List<ArticleSummary> second = mgr.getRecentArticles( context, query );

        assertSame( first, second, "Cache hit should return exact same list instance" );
    }

    @Test
    void testClearCacheForcesRefresh() {
        final Page page = mockPage( "Page1" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( false );

        final List<ArticleSummary> first = mgr.getRecentArticles( context, query );
        mgr.clearCache();

        // After clearing cache, we should get a new list object
        final List<ArticleSummary> second = mgr.getRecentArticles( context, query );
        assertNotSame( first, second, "After cache clear, should build fresh results" );
    }

    @Test
    void testDifferentQueriesGetDifferentCacheEntries() {
        final Page p1 = mockPage( "A" );
        final Page p2 = mockPage( "B" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( p1, p2 );

        final RecentArticlesQuery q1 = new RecentArticlesQuery().count( 1 ).includeExcerpt( false );
        final RecentArticlesQuery q2 = new RecentArticlesQuery().count( 2 ).includeExcerpt( false );

        final List<ArticleSummary> result1 = mgr.getRecentArticles( context, q1 );
        final List<ArticleSummary> result2 = mgr.getRecentArticles( context, q2 );

        assertEquals( 1, result1.size() );
        assertEquals( 2, result2.size() );
    }

    // ==================== ArticleSummary fields ====================

    @Test
    void testArticleSummaryFieldsPopulated() {
        final Date now = new Date();
        final Page page = mockPage( "FullFields", now );
        when( page.getAuthor() ).thenReturn( "testuser" );
        when( page.getAttribute( Page.CHANGENOTE ) ).thenReturn( "Updated content" );
        when( page.getSize() ).thenReturn( 512L );
        when( pageManager.getPureText( page ) ).thenReturn( "# The Title\nContent." );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context,
                new RecentArticlesQuery().count( 10 ).includeExcerpt( false ) );

        final ArticleSummary summary = articles.get( 0 );
        assertEquals( "FullFields", summary.getName() );
        assertEquals( "The Title", summary.getTitle() );
        assertEquals( "testuser", summary.getAuthor() );
        assertEquals( now, summary.getLastModified() );
        assertEquals( "Updated content", summary.getChangeNote() );
        assertEquals( 1, summary.getVersion() );
        assertEquals( "/wiki/FullFields", summary.getUrl() );
        assertEquals( 512L, summary.getSize() );
    }

    @Test
    void testArticleWithNullAuthor() {
        final Page page = mockPage( "NoAuthor" );
        when( page.getAuthor() ).thenReturn( null );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertNull( articles.get( 0 ).getAuthor() );
    }

    // ==================== hasTemplatePage ====================

    @Test
    void testHasTemplatePageFalseWhenNotExists() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );
        assertFalse( mgr.hasTemplatePage() );
    }

    @Test
    void testHasTemplatePageTrueWhenExists() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        assertTrue( mgr.hasTemplatePage() );
    }

    @Test
    void testHasTemplatePageFalseWhenPageManagerNull() {
        final DefaultRecentArticlesManager noPageMgr =
                new DefaultRecentArticlesManager( engine, null, renderingManager, systemPageRegistry );
        noPageMgr.initialize( engine, new Properties() );
        assertFalse( noPageMgr.hasTemplatePage() );
    }

    // ==================== renderWithTemplate ====================

    @Test
    void testRenderWithTemplateNullArticlesReturnsDefault() {
        final String html = mgr.renderWithTemplate( context, null );
        assertTrue( html.contains( "No recent articles found." ) );
    }

    @Test
    void testRenderWithTemplateEmptyListReturnsDefault() {
        final String html = mgr.renderWithTemplate( context, List.of() );
        assertTrue( html.contains( "No recent articles found." ) );
    }

    @Test
    void testRenderDefaultHtmlWithArticles() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "TestPage" )
                .title( "Test Title" )
                .author( "author1" )
                .lastModified( new Date() )
                .url( "/wiki/TestPage" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "recent-articles" ) );
        assertTrue( html.contains( "article-card" ) );
        assertTrue( html.contains( "Test Title" ) );
        assertTrue( html.contains( "/wiki/TestPage" ) );
        assertTrue( html.contains( "author1" ) );
    }

    @Test
    void testRenderDefaultHtmlEscapesSpecialChars() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "<script>alert('xss')</script>" )
                .url( "/wiki/Test&param=val" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "&lt;script&gt;" ) );
        assertFalse( html.contains( "<script>" ) );
        assertTrue( html.contains( "&amp;param" ) );
    }

    @Test
    void testRenderDefaultHtmlWithExcerpt() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "Title" )
                .excerpt( "This is the excerpt text." )
                .url( "/wiki/Test" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "article-excerpt" ) );
        assertTrue( html.contains( "This is the excerpt text." ) );
    }

    @Test
    void testRenderDefaultHtmlWithNullAuthor() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "Title" )
                .url( "/wiki/Test" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Should not throw and should contain article-meta
        assertTrue( html.contains( "article-meta" ) );
    }

    // ==================== Template rendering (iterative) ====================

    @Test
    void testRenderIterativeTemplate() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "Header %%ARTICLE_COUNT%%\n%%ARTICLE_START%%* %%TITLE%% by %%AUTHOR%%\n%%ARTICLE_END%%Footer" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "My Post" )
                .author( "writer" )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "Header 1" ) );
        assertTrue( html.contains( "My Post" ) );
        assertTrue( html.contains( "writer" ) );
        assertTrue( html.contains( "Footer" ) );
    }

    @Test
    void testRenderIterativeTemplateWithDatePlaceholders() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_START%%%%DATE%% | %%DATE_FULL%% | %%INDEX%%\n%%ARTICLE_END%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "Title" )
                .lastModified( new Date() )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Should contain a date in yyyy-MM-dd format and index "1"
        assertTrue( html.contains( "| 1" ) );
        // Should not contain unresolved placeholders
        assertFalse( html.contains( "%%DATE%%" ) );
        assertFalse( html.contains( "%%DATE_FULL%%" ) );
    }

    @Test
    void testRenderIterativeTemplateWithNullDate() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_START%%Date:%%DATE%% Full:%%DATE_FULL%%\n%%ARTICLE_END%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "Title" )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "Date:" ) );
        assertTrue( html.contains( "Full:" ) );
        assertFalse( html.contains( "%%DATE%%" ) );
    }

    @Test
    void testRenderIterativeTemplateNullFieldDefaults() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_START%%Author:%%AUTHOR%% Note:%%CHANGENOTE%% Excerpt:%%EXCERPT%%\n%%ARTICLE_END%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        // Build article with null optional fields
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "Title" )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Author should default to "Anonymous", others to empty
        assertTrue( html.contains( "Author:Anonymous" ) );
        assertTrue( html.contains( "Note:" ) );
        assertTrue( html.contains( "Excerpt:" ) );
    }

    @Test
    void testRenderIterativeTemplateInvalidMarkerOrderFallsBackToDefault() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        // End marker before start marker
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_END%%stuff%%ARTICLE_START%%" );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "Title" )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Falls back to default HTML rendering
        assertTrue( html.contains( "recent-articles" ) );
    }

    @Test
    void testRenderIterativeTemplateRenderingExceptionFallsBack() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_START%%%%TITLE%%\n%%ARTICLE_END%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenThrow( new RuntimeException( "render fail" ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Post" )
                .title( "Title" )
                .url( "/wiki/Post" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Should not throw; may return raw text or fall back
        assertNotNull( html );
    }

    // ==================== Template rendering (simple) ====================

    @Test
    void testRenderSimpleTemplate() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "Total: %%ARTICLE_COUNT%%\n%%ARTICLE_LIST%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final ArticleSummary a1 = new ArticleSummary.Builder().name( "Page1" ).title( "Title1" ).url( "/1" ).build();
        final ArticleSummary a2 = new ArticleSummary.Builder().name( "Page2" ).title( "Title2" ).url( "/2" ).build();

        final String html = mgr.renderWithTemplate( context, List.of( a1, a2 ) );

        assertTrue( html.contains( "Total: 2" ) );
        assertTrue( html.contains( "Title1" ) );
        assertTrue( html.contains( "Title2" ) );
    }

    @Test
    void testRenderSimpleTemplateRenderingExceptionFallsBack() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "Simple: %%ARTICLE_COUNT%%" );
        when( renderingManager.textToHTML( eq( context ), anyString() ) ).thenThrow( new RuntimeException( "fail" ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "P" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Should return raw text fallback
        assertTrue( html.contains( "Simple: 1" ) );
    }

    @Test
    void testRenderSimpleTemplateNullRenderingManagerReturnsRawText() {
        // Construct with null renderingManager; do NOT call initialize() since that would
        // re-wire renderingManager from the engine mock (overwriting the intentional null).
        final DefaultRecentArticlesManager noRenderMgr =
                new DefaultRecentArticlesManager( engine, pageManager, null, systemPageRegistry );

        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "Count: %%ARTICLE_COUNT%%" );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "P" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = noRenderMgr.renderWithTemplate( context, List.of( article ) );

        assertEquals( "Count: 1", html );
    }

    @Test
    void testRenderIterativeTemplateNullRenderingManagerReturnsRawText() {
        // Construct with null renderingManager; do NOT call initialize() since that would
        // re-wire renderingManager from the engine mock (overwriting the intentional null).
        final DefaultRecentArticlesManager noRenderMgr =
                new DefaultRecentArticlesManager( engine, pageManager, null, systemPageRegistry );

        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenReturn( "%%ARTICLE_START%%%%NAME%%\n%%ARTICLE_END%%" );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "MyPage" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = noRenderMgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "MyPage" ) );
    }

    // ==================== Template fallback for empty/null template text ====================

    @Test
    void testRenderWithEmptyTemplateTextFallsBackToDefault() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) ).thenReturn( "" );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "P" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "recent-articles" ) );
    }

    @Test
    void testRenderWithNullTemplateTextFallsBackToDefault() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) ).thenReturn( null );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "P" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "recent-articles" ) );
    }

    // ==================== renderWithTemplate exception path ====================

    @Test
    void testRenderWithTemplateExceptionFallsBackToDefault() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( true );
        when( pageManager.getPureText( RecentArticlesManager.TEMPLATE_PAGE_NAME, -1 ) )
                .thenThrow( new RuntimeException( "page read error" ) );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "P" )
                .title( "T" )
                .url( "/p" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        // Exception in renderWithWikiTemplate should be caught and fall back to default
        assertTrue( html.contains( "recent-articles" ) );
    }

    // ==================== Numeric HTML entity decoding ====================

    @Test
    void testDecimalEntityDecoding() {
        final Page page = mockPage( "EntityPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        // &#65; = A, &#97; = a
        when( renderingManager.getHTML( "EntityPage", 1 ) )
                .thenReturn( "<p>Characters: &#65;&#97; and more text to meet twenty char minimum for excerpt</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNotNull( articles.get( 0 ).getExcerpt() );
        assertTrue( articles.get( 0 ).getExcerpt().contains( "Aa" ) );
    }

    @Test
    void testHexEntityDecoding() {
        final Page page = mockPage( "HexPage" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        // &#x41; = A
        when( renderingManager.getHTML( "HexPage", 1 ) )
                .thenReturn( "<p>Hex entity: &#x41; with enough content to generate an excerpt for testing purposes</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        assertNotNull( articles.get( 0 ).getExcerpt() );
        assertTrue( articles.get( 0 ).getExcerpt().contains( "A" ) );
        assertFalse( articles.get( 0 ).getExcerpt().contains( "&#x41;" ) );
    }

    // ==================== Error handling in buildArticleSummary ====================

    @Test
    void testBuildSummaryExceptionSkipsPage() {
        final Page goodPage = mockPage( "Good" );
        final Page badPage = mockPage( "Bad" );
        when( pageManager.getPureText( goodPage ) ).thenReturn( "# Good\nContent" );
        when( pageManager.getPureText( badPage ) ).thenThrow( new RuntimeException( "read error" ) );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( badPage, goodPage );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertEquals( 1, articles.size() );
        assertEquals( "Good", articles.get( 0 ).getName() );
    }

    // ==================== Initialize with custom properties ====================

    @Test
    void testCustomCacheTtlFromProperties() {
        // TTL=-1 guarantees the cache entry is always considered expired because
        // isExpired checks elapsed > ttl*1000L, and elapsed >= 0 > -1000 is always true.
        // TTL=0 would use strict > 0, which is false within the same millisecond.
        final Properties props = new Properties();
        props.setProperty( RecentArticlesManager.PROP_CACHE_TTL, "-1" );

        final DefaultRecentArticlesManager customMgr =
                new DefaultRecentArticlesManager( engine, pageManager, renderingManager, systemPageRegistry );
        customMgr.initialize( engine, props );

        final Page page = mockPage( "P" );
        when( pageManager.getPureText( any( Page.class ) ) ).thenReturn( "content" );
        when( renderingManager.beautifyTitle( anyString() ) ).thenAnswer( inv -> inv.getArgument( 0 ) );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( false );

        // With TTL=-1, every call should be a cache miss (new list returned each time)
        final List<ArticleSummary> first = customMgr.getRecentArticles( context, query );
        final List<ArticleSummary> second = customMgr.getRecentArticles( context, query );

        assertNotSame( first, second, "With TTL=-1, cache should always be expired" );
    }

    // ==================== Results immutability ====================

    @Test
    void testResultsAreUnmodifiable() {
        final Page page = mockPage( "Immutable" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title\nContent" );
        setupRecentChanges( page );

        final List<ArticleSummary> articles = mgr.getRecentArticles( context, new RecentArticlesQuery().count( 10 ) );

        assertThrows( UnsupportedOperationException.class, () -> articles.add(
                new ArticleSummary.Builder().name( "X" ).title( "X" ).url( "X" ).build() ) );
    }

    // ==================== Delegating constructor ====================

    @Test
    void testNoArgConstructorAndInitializeResolvesFromEngine() {
        final DefaultRecentArticlesManager noArgMgr = new DefaultRecentArticlesManager();
        when( engine.getManager( PageManager.class ) ).thenReturn( pageManager );
        when( engine.getManager( RenderingManager.class ) ).thenReturn( renderingManager );
        when( engine.getManager( com.wikantik.content.SystemPageRegistry.class ) ).thenReturn(
                mock( com.wikantik.content.SystemPageRegistry.class ) );

        noArgMgr.initialize( engine, new Properties() );

        // Should not throw — managers resolved from engine in initialize()
        assertFalse( noArgMgr.hasTemplatePage() );
    }

    // ==================== escapeHtml edge cases ====================

    @Test
    void testRenderDefaultHtmlWithNullUrl() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        // ArticleSummary.Builder sets url to "" if null, so this tests the escapeHtml("") path
        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "Title" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertNotNull( html );
        assertTrue( html.contains( "article-card" ) );
    }

    @Test
    void testRenderDefaultHtmlWithLastModified() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "Title" )
                .lastModified( new Date() )
                .url( "/wiki/Test" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertTrue( html.contains( "&middot;" ) );
    }

    @Test
    void testRenderDefaultHtmlWithoutLastModified() {
        when( pageManager.wikiPageExists( RecentArticlesManager.TEMPLATE_PAGE_NAME ) ).thenReturn( false );

        final ArticleSummary article = new ArticleSummary.Builder()
                .name( "Test" )
                .title( "Title" )
                .url( "/wiki/Test" )
                .build();

        final String html = mgr.renderWithTemplate( context, List.of( article ) );

        assertFalse( html.contains( "&middot;" ) );
    }

    // ==================== Named HTML entity decoding ====================

    @Test
    void testNamedHtmlEntitiesDecoded() {
        final Page page = mockPage( "Entities" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        when( renderingManager.getHTML( "Entities", 1 ) )
                .thenReturn( "<p>&copy; 2024 &ndash; &mdash; &euro; &times; &deg; &plusmn; &divide; and enough text to be 20+ chars</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        final String excerpt = articles.get( 0 ).getExcerpt();
        assertNotNull( excerpt );
        assertTrue( excerpt.contains( "\u00A9" ) ); // copyright
        assertTrue( excerpt.contains( "\u2013" ) ); // ndash
        assertTrue( excerpt.contains( "\u2014" ) ); // mdash
        assertTrue( excerpt.contains( "\u20AC" ) ); // euro
    }

    // ==================== Excerpt word-boundary truncation ====================

    @Test
    void testExcerptTruncatesAtWordBoundary() {
        final Page page = mockPage( "WordBoundary" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        // Create content where the word boundary is well past the halfway point
        final String content = "<p>Word1 Word2 Word3 Word4 Word5 Word6 Word7 Word8 Word9 Word10 End</p>";
        when( renderingManager.getHTML( "WordBoundary", 1 ) ).thenReturn( content );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true ).excerptLength( 30 );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        final String excerpt = articles.get( 0 ).getExcerpt();
        assertNotNull( excerpt );
        assertTrue( excerpt.endsWith( "..." ) );
        // Should not break in the middle of a word
        assertFalse( excerpt.contains( "Word" ) && excerpt.endsWith( "Wor..." ) );
    }

    @Test
    void testExcerptFallsBackToMaxLengthWhenNoGoodBreakPoint() {
        final Page page = mockPage( "NoBreak" );
        when( pageManager.getPureText( page ) ).thenReturn( "# Title" );
        // Long word with no spaces
        final String longWord = "A".repeat( 100 );
        when( renderingManager.getHTML( "NoBreak", 1 ) ).thenReturn( "<p>" + longWord + "</p>" );
        setupRecentChanges( page );

        final RecentArticlesQuery query = new RecentArticlesQuery().count( 10 ).includeExcerpt( true ).excerptLength( 50 );
        final List<ArticleSummary> articles = mgr.getRecentArticles( context, query );

        final String excerpt = articles.get( 0 ).getExcerpt();
        assertNotNull( excerpt );
        assertTrue( excerpt.endsWith( "..." ) );
    }
}

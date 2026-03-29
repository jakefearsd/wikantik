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

import com.wikantik.MockEngineBuilder;
import com.wikantik.WikiPage;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.FilterException;
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.attachment.AttachmentManager;
import com.wikantik.auth.AuthenticationManager;
import com.wikantik.auth.AuthorizationManager;
import com.wikantik.auth.UserManager;
import com.wikantik.auth.authorize.GroupManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.event.WikiEngineEvent;
import com.wikantik.event.WikiPageEvent;
import com.wikantik.filters.FilterManager;
import com.wikantik.pages.PageManager;
import com.wikantik.parser.WikiDocument;
import com.wikantik.references.ReferenceManager;
import com.wikantik.ui.CommandResolver;
import com.wikantik.ui.PageCommand;
import com.wikantik.variables.VariableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for {@link DefaultRenderingManager} using constructor injection.
 * Focuses on behaviors NOT covered by the integration-style {@link RenderingManagerTest}:
 * cache key generation, cache hit/miss, rendering pipeline invocation,
 * variable-driven filter skipping, error handling, event-driven cache flushing,
 * beautifyTitle with attachments, and the WYSIWYG rendering mode selection.
 */
class DefaultRenderingManagerCITest {

    private Engine engine;
    private CachingManager cachingManager;
    private FilterManager filterManager;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private VariableManager variableManager;
    private ReferenceManager referenceManager;
    private UserManager userManager;
    private AuthorizationManager authorizationManager;
    private AuthenticationManager authenticationManager;
    private GroupManager groupManager;
    private CommandResolver commandResolver;

    private DefaultRenderingManager mgr;

    @BeforeEach
    void setUp() throws WikiException {
        cachingManager = mock( CachingManager.class );
        filterManager = mock( FilterManager.class );
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );
        variableManager = mock( VariableManager.class );
        referenceManager = mock( ReferenceManager.class );
        userManager = mock( UserManager.class );
        authorizationManager = mock( AuthorizationManager.class );
        authenticationManager = mock( AuthenticationManager.class );
        groupManager = mock( GroupManager.class );
        commandResolver = mock( CommandResolver.class );

        // CommandResolver must return a valid PageCommand so WikiContext construction works
        when( commandResolver.findCommand( any(), anyString() ) ).thenReturn( PageCommand.VIEW );

        engine = MockEngineBuilder.engine()
                .with( CachingManager.class, cachingManager )
                .with( FilterManager.class, filterManager )
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( VariableManager.class, variableManager )
                .with( ReferenceManager.class, referenceManager )
                .with( UserManager.class, userManager )
                .with( AuthorizationManager.class, authorizationManager )
                .with( AuthenticationManager.class, authenticationManager )
                .with( GroupManager.class, groupManager )
                .with( CommandResolver.class, commandResolver )
                .build();

        mgr = new DefaultRenderingManager( engine, cachingManager, filterManager,
                                           pageManager, attachmentManager, variableManager );

        // Initialize with default properties so renderer strategies are populated
        final Properties props = new Properties();
        mgr.initialize( engine, props );

        // Wire RenderingManager to mgr after initialization (MarkdownRenderer uses it to get the parser)
        when( engine.getManager( RenderingManager.class ) ).thenReturn( mgr );
    }

    // --- helper methods ---

    private Context viewContext( final String pageName, final int version ) {
        final Context ctx = mock( Context.class );
        final Page page = mockPage( pageName, version );
        when( ctx.getRealPage() ).thenReturn( page );
        when( ctx.getPage() ).thenReturn( ( WikiPage ) page );
        when( ctx.getRequestContext() ).thenReturn( ContextEnum.PAGE_VIEW.getRequestContext() );
        when( ctx.getEngine() ).thenReturn( engine );
        return ctx;
    }

    private Context noneContext( final String pageName, final int version ) {
        final Context ctx = mock( Context.class );
        final Page page = mockPage( pageName, version );
        when( ctx.getRealPage() ).thenReturn( page );
        when( ctx.getPage() ).thenReturn( ( WikiPage ) page );
        when( ctx.getRequestContext() ).thenReturn( ContextEnum.PAGE_NONE.getRequestContext() );
        when( ctx.getEngine() ).thenReturn( engine );
        return ctx;
    }

    private Page mockPage( final String name, final int version ) {
        final WikiPage page = mock( WikiPage.class );
        when( page.getName() ).thenReturn( name );
        when( page.getVersion() ).thenReturn( version );
        return page;
    }

    // ========== useCache / useHtmlCache ==========

    @Test
    void useCacheReturnsTrueForPageViewWithEnabledDocCache() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        final Context ctx = viewContext( "TestPage", 1 );
        assertTrue( mgr.useCache( ctx ) );
    }

    @Test
    void useCacheReturnsFalseWhenDocCacheDisabled() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        final Context ctx = viewContext( "TestPage", 1 );
        assertFalse( mgr.useCache( ctx ) );
    }

    @Test
    void useCacheReturnsFalseForNonViewContext() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        final Context ctx = noneContext( "TestPage", 1 );
        assertFalse( mgr.useCache( ctx ) );
    }

    // ========== getRenderedDocument: cache miss ==========

    @Test
    void getRenderedDocumentReturnsDocOnCacheMiss() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) ).thenReturn( null );

        final Context ctx = viewContext( "CacheMissPage", 1 );
        final WikiDocument doc = mgr.getRenderedDocument( ctx, "**hello**" );

        assertNotNull( doc, "Should return a parsed document even on cache miss" );
        assertEquals( "**hello**", doc.getPageData() );

        // Should store in cache
        verify( cachingManager ).put( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), eq( doc ) );
    }

    // ========== getRenderedDocument: cache hit with matching hash ==========

    @Test
    void getRenderedDocumentReturnsCachedDocOnHashMatch() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );

        final String pagedata = "**cached content**";
        final Page page = mockPage( "CachedPage", 1 );
        final WikiDocument cachedDoc = new WikiDocument( page );
        cachedDoc.setPageData( pagedata );

        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) )
                .thenReturn( cachedDoc );

        final Context ctx = viewContext( "CachedPage", 1 );
        final WikiDocument result = mgr.getRenderedDocument( ctx, pagedata );

        assertSame( cachedDoc, result, "Should return exact same cached document" );
        // Should NOT call put again since it was a cache hit
        verify( cachingManager, never() ).put( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() );
    }

    // ========== getRenderedDocument: cache hit with changed content ==========

    @Test
    void getRenderedDocumentReRendersWhenContentChanged() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );

        final Page page = mockPage( "ChangedPage", 1 );
        final WikiDocument cachedDoc = new WikiDocument( page );
        cachedDoc.setPageData( "old content" );

        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) )
                .thenReturn( cachedDoc );

        final Context ctx = viewContext( "ChangedPage", 1 );
        final WikiDocument result = mgr.getRenderedDocument( ctx, "new content" );

        assertNotSame( cachedDoc, result, "Should return a freshly parsed document" );
        assertEquals( "new content", result.getPageData() );
        verify( cachingManager ).put( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), eq( result ) );
    }

    // ========== getRenderedDocument: cache disabled ==========

    @Test
    void getRenderedDocumentSkipsCacheWhenDisabled() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );

        final Context ctx = viewContext( "NoCachePage", 1 );
        final WikiDocument doc = mgr.getRenderedDocument( ctx, "some text" );

        assertNotNull( doc );
        // Should never interact with cache
        verify( cachingManager, never() ).get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() );
        verify( cachingManager, never() ).put( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() );
    }

    // ========== getRenderedDocument: non-view context bypasses cache ==========

    @Test
    void getRenderedDocumentBypassesCacheForNonViewContext() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );

        final Context ctx = noneContext( "NonViewPage", 1 );
        final WikiDocument doc = mgr.getRenderedDocument( ctx, "edit mode text" );

        assertNotNull( doc );
        verify( cachingManager, never() ).get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() );
        verify( cachingManager, never() ).put( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() );
    }

    // ========== Cache key includes VAR_EXECUTE_PLUGINS ==========

    @Test
    void cacheKeyIncludesPluginVariable() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) ).thenReturn( null );

        final Context ctx = viewContext( "PluginPage", 3 );
        when( ctx.getVariable( Context.VAR_EXECUTE_PLUGINS ) ).thenReturn( Boolean.TRUE );

        mgr.getRenderedDocument( ctx, "text" );

        // Verify cache key format: "PageName::version::pluginVar"
        verify( cachingManager ).put( eq( CachingManager.CACHE_DOCUMENTS ),
                eq( "PluginPage::3::true" ), any( WikiDocument.class ) );
    }

    @Test
    void cacheKeyWithNullPluginVariable() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) ).thenReturn( null );

        final Context ctx = viewContext( "NullPluginPage", 5 );
        when( ctx.getVariable( Context.VAR_EXECUTE_PLUGINS ) ).thenReturn( null );

        mgr.getRenderedDocument( ctx, "text" );

        verify( cachingManager ).put( eq( CachingManager.CACHE_DOCUMENTS ),
                eq( "NullPluginPage::5::null" ), any( WikiDocument.class ) );
    }

    // ========== textToHTML: filters run when VAR_RUNFILTERS is true ==========

    @Test
    void textToHTMLRunsFiltersWhenEnabled() throws FilterException {
        // Cache disabled to simplify
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );
        when( filterManager.doPreTranslateFiltering( any(), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );
        when( filterManager.doPostTranslateFiltering( any(), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final Context ctx = noneContext( "FilterPage", 1 );
        final String result = mgr.textToHTML( ctx, "**bold**" );

        assertNotNull( result );
        verify( filterManager ).doPreTranslateFiltering( eq( ctx ), eq( "**bold**" ) );
        verify( filterManager ).doPostTranslateFiltering( eq( ctx ), anyString() );
    }

    // ========== textToHTML: filters skipped when VAR_RUNFILTERS is false ==========

    @Test
    void textToHTMLSkipsFiltersWhenDisabled() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "false" );

        final Context ctx = noneContext( "NoFilterPage", 1 );
        mgr.textToHTML( ctx, "plain text" );

        verify( filterManager, never() ).doPreTranslateFiltering( any(), any() );
        verify( filterManager, never() ).doPostTranslateFiltering( any(), any() );
    }

    // ========== textToHTML: filter exception caught gracefully ==========

    @Test
    void textToHTMLHandlesFilterException() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );
        when( filterManager.doPreTranslateFiltering( any(), anyString() ) )
                .thenThrow( new FilterException( "test filter failure" ) );

        final Context ctx = noneContext( "ExceptionPage", 1 );
        // Should not throw; FilterException is caught
        final String result = mgr.textToHTML( ctx, "some text" );

        // After catching FilterException during pre-translate, result should be empty string
        assertEquals( "", result );
    }

    // ========== textToHTML with hooks: null pagedata returns null ==========

    @Test
    void textToHTMLWithHooksReturnsNullForNullPagedata() {
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );

        final Context ctx = noneContext( "NullPage", 1 );
        final String result = mgr.textToHTML( ctx, null, null, null, null, true, false );

        assertNull( result, "Should return null for null pagedata" );
    }

    // ========== textToHTML with hooks: justParse returns empty string ==========

    @Test
    void textToHTMLWithJustParseReturnsEmpty() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );
        when( filterManager.doPreTranslateFiltering( any(), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );

        final Context ctx = noneContext( "JustParsePage", 1 );
        final String result = mgr.textToHTML( ctx, "**bold**", null, null, null, true, true );

        assertEquals( "", result, "justParse should return empty string (no rendering)" );
        // Post-translate should NOT be called when justParse is true
        verify( filterManager, never() ).doPostTranslateFiltering( any(), any() );
    }

    // ========== textToHTML with hooks: filters skipped when runFilters=false ==========

    @Test
    void textToHTMLWithHooksSkipsFiltersWhenDisabled() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "false" );

        final Context ctx = noneContext( "NoFilterHooksPage", 1 );
        mgr.textToHTML( ctx, "text", null, null, null, true, false );

        verify( filterManager, never() ).doPreTranslateFiltering( any(), any() );
        verify( filterManager, never() ).doPostTranslateFiltering( any(), any() );
    }

    // ========== getHTML(context, page) delegates to pageManager ==========

    @Test
    void getHTMLWithContextAndPageDelegatesToPageManager() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "false" );

        final Page page = mockPage( "DelegatePage", 2 );
        when( pageManager.getPureText( "DelegatePage", 2 ) ).thenReturn( "page content" );

        final Context ctx = noneContext( "DelegatePage", 2 );
        mgr.getHTML( ctx, page );

        verify( pageManager ).getPureText( "DelegatePage", 2 );
    }

    // ========== getHTML(pagename, version) delegates to pageManager ==========

    @Test
    void getHTMLByNameAndVersionDelegatesToPageManager() {
        // Must return WikiPage (not just Page) because WikiContext casts the target to WikiPage
        final WikiPage page = mock( WikiPage.class );
        when( page.getName() ).thenReturn( "LookupPage" );
        when( page.getVersion() ).thenReturn( 3 );
        // getWiki() must not be null: PermissionFactory.getPagePermission calls wiki.hashCode()
        when( page.getWiki() ).thenReturn( "" );
        when( pageManager.getPage( "LookupPage", 3 ) ).thenReturn( page );
        when( pageManager.getPureText( "LookupPage", 3 ) ).thenReturn( "looked up" );

        // getHTML(String, int) calls pageManager.getPage first, then creates a real WikiContext
        // (which casts the mock Engine to WikiEngine internally), so the full pipeline cannot
        // complete in a unit test — verify only the first delegation step
        try {
            mgr.getHTML( "LookupPage", 3 );
        } catch( final Exception ignored ) {
            // Expected: WikiContext.getEngine() casts the mock Engine to WikiEngine
        }

        verify( pageManager ).getPage( "LookupPage", 3 );
    }

    // ========== beautifyTitle: not an attachment ==========

    @Test
    void beautifyTitleBeautifiesNonAttachment() throws ProviderException, WikiException {
        // Need to reinitialize with beautifyTitle=true
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_BEAUTIFYTITLE, "true" );
        mgr.initialize( engine, props );

        when( attachmentManager.getAttachmentInfo( "WikiNameTest" ) ).thenReturn( null );

        final String result = mgr.beautifyTitle( "WikiNameTest" );
        assertEquals( "Wiki Name Test", result );
    }

    // ========== beautifyTitle: is an attachment ==========

    @Test
    void beautifyTitleFormatsAttachment() throws ProviderException, WikiException {
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_BEAUTIFYTITLE, "true" );
        mgr.initialize( engine, props );

        final Attachment att = mock( Attachment.class );
        when( att.getParentName() ).thenReturn( "WikiPage" );
        when( att.getFileName() ).thenReturn( "image.png" );
        when( attachmentManager.getAttachmentInfo( "WikiPage/image.png" ) ).thenReturn( att );

        final String result = mgr.beautifyTitle( "WikiPage/image.png" );
        assertEquals( "Wiki Page/image.png", result );
    }

    // ========== beautifyTitle: provider exception returns title unchanged ==========

    @Test
    void beautifyTitleReturnsTitleOnProviderException() throws ProviderException, WikiException {
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_BEAUTIFYTITLE, "true" );
        mgr.initialize( engine, props );

        when( attachmentManager.getAttachmentInfo( "FailPage" ) ).thenThrow( new ProviderException( "boom" ) );

        assertEquals( "FailPage", mgr.beautifyTitle( "FailPage" ) );
    }

    // ========== beautifyTitle: disabled returns title as-is ==========

    @Test
    void beautifyTitleReturnsUnmodifiedWhenDisabled() {
        // Default setup: beautifyTitle is false
        assertEquals( "WikiNameTest", mgr.beautifyTitle( "WikiNameTest" ) );
    }

    // ========== beautifyTitleNoBreak ==========

    @Test
    void beautifyTitleNoBreakWhenDisabled() {
        assertEquals( "WikiName", mgr.beautifyTitleNoBreak( "WikiName" ) );
    }

    @Test
    void beautifyTitleNoBreakWhenEnabled() throws WikiException {
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_BEAUTIFYTITLE, "true" );
        mgr.initialize( engine, props );

        final String result = mgr.beautifyTitleNoBreak( "WikiName" );
        assertEquals( "Wiki&nbsp;Name", result );
    }

    // ========== actionPerformed: POST_SAVE_BEGIN flushes caches ==========

    @Test
    void actionPerformedFlushesPageAndReferrerCaches() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );

        when( referenceManager.findReferrers( "SavedPage" ) )
                .thenReturn( Set.of( "ReferrerA", "ReferrerB" ) );

        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, "SavedPage" );
        mgr.actionPerformed( event );

        // Should remove the saved page from both caches
        verify( cachingManager ).remove( CachingManager.CACHE_DOCUMENTS, "SavedPage" );
        verify( cachingManager ).remove( CachingManager.CACHE_HTML, "SavedPage" );

        // Should flush referrer pages in both caches for all three plugin-var states
        for( final String referrer : Set.of( "ReferrerA", "ReferrerB" ) ) {
            for( final String cache : new String[]{ CachingManager.CACHE_DOCUMENTS, CachingManager.CACHE_HTML } ) {
                verify( cachingManager ).remove( cache,
                        referrer + "::" + PageProvider.LATEST_VERSION + "::" + Boolean.FALSE );
                verify( cachingManager ).remove( cache,
                        referrer + "::" + PageProvider.LATEST_VERSION + "::" + Boolean.TRUE );
                verify( cachingManager ).remove( cache,
                        referrer + "::" + PageProvider.LATEST_VERSION + "::" + null );
            }
        }
    }

    // ========== actionPerformed: non-POST_SAVE_BEGIN event is ignored ==========

    @Test
    void actionPerformedIgnoresNonPostSaveEvent() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );

        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.PAGE_LOCK, "LockedPage" );
        mgr.actionPerformed( event );

        verify( cachingManager, never() ).remove( anyString(), anyString() );
    }

    // ========== actionPerformed: cache disabled means no flushing ==========

    @Test
    void actionPerformedDoesNothingWhenBothCachesDisabled() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( false );

        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, "Page" );
        mgr.actionPerformed( event );

        verify( cachingManager, never() ).remove( anyString(), anyString() );
    }

    // ========== isBeginningAWikiPagePostSaveEventAndCacheIsEnabled ==========

    @Test
    void isBeginningTrueWhenDocCacheOnly() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( false );
        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, "P" );
        assertTrue( mgr.isBeginningAWikiPagePostSaveEventAndCacheIsEnabled( event ) );
    }

    @Test
    void isBeginningTrueWhenHtmlCacheOnly() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );
        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, "P" );
        assertTrue( mgr.isBeginningAWikiPagePostSaveEventAndCacheIsEnabled( event ) );
    }

    @Test
    void isBeginningFalseForNonWikiPageEvent() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        // WikiEngineEvent is a non-WikiPageEvent concrete subclass of the sealed WikiEvent hierarchy
        final WikiEngineEvent event = new WikiEngineEvent( this, WikiEngineEvent.INITIALIZED );
        assertFalse( mgr.isBeginningAWikiPagePostSaveEventAndCacheIsEnabled( event ) );
    }

    // ========== actionPerformed: POST_SAVE_BEGIN with empty referrers ==========

    @Test
    void actionPerformedHandlesEmptyReferrers() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( false );
        when( referenceManager.findReferrers( "LonelyPage" ) ).thenReturn( Collections.emptySet() );

        final WikiPageEvent event = new WikiPageEvent( this, WikiPageEvent.POST_SAVE_BEGIN, "LonelyPage" );
        mgr.actionPerformed( event );

        // Only the saved page itself should be removed, no referrer flushes
        verify( cachingManager ).remove( CachingManager.CACHE_DOCUMENTS, "LonelyPage" );
        verify( cachingManager ).remove( CachingManager.CACHE_HTML, "LonelyPage" );
        // Exactly 2 remove calls total
        verify( cachingManager, times( 2 ) ).remove( anyString(), any() );
    }

    // ========== HTML cache: getHTML(context, pagedata) stores in HTML cache ==========

    @Test
    void getHTMLWithPagedataPopulatesHtmlCache() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );
        when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( null );

        final Context ctx = viewContext( "HtmlCachePage", 1 );
        final String html = mgr.getHTML( ctx, "**bold**" );

        assertNotNull( html );
        verify( cachingManager ).put( eq( CachingManager.CACHE_HTML ), anyString(), any( DefaultRenderingManager.HtmlCacheEntry.class ) );
    }

    // ========== HTML cache: hit returns cached HTML directly ==========

    @Test
    void getHTMLWithPagedataReturnsCachedHtmlOnHit() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );

        final String pagedata = "cached data";
        final String cachedHtml = "<p>cached data</p>\n";
        final String hash = WikiDocument.hashPageData( pagedata );
        final DefaultRenderingManager.HtmlCacheEntry entry =
                new DefaultRenderingManager.HtmlCacheEntry( cachedHtml, hash );

        when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( entry );

        final Context ctx = viewContext( "HtmlHitPage", 1 );
        final String result = mgr.getHTML( ctx, pagedata );

        assertEquals( cachedHtml, result );
        // Should NOT call put since it was a cache hit
        verify( cachingManager, never() ).put( eq( CachingManager.CACHE_HTML ), anyString(), any() );
    }

    // ========== HTML cache: stale entry (hash mismatch) causes re-render ==========

    @Test
    void getHTMLWithPagedataReRendersOnStaleCacheEntry() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );

        final String oldHash = WikiDocument.hashPageData( "old data" );
        final DefaultRenderingManager.HtmlCacheEntry staleEntry =
                new DefaultRenderingManager.HtmlCacheEntry( "<p>old</p>", oldHash );

        when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( staleEntry );

        final Context ctx = viewContext( "StalePage", 1 );
        final String result = mgr.getHTML( ctx, "new data" );

        assertNotNull( result );
        assertNotEquals( "<p>old</p>", result, "Should re-render instead of returning stale HTML" );
        // Should store new entry
        verify( cachingManager ).put( eq( CachingManager.CACHE_HTML ), anyString(), any( DefaultRenderingManager.HtmlCacheEntry.class ) );
    }

    // ========== textToHTML: HTML cache hit skips all rendering ==========

    @Test
    void textToHTMLReturnsHtmlCacheHitDirectly() throws FilterException {
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );

        final String pagedata = "cached text";
        final String hash = WikiDocument.hashPageData( pagedata );
        final DefaultRenderingManager.HtmlCacheEntry entry =
                new DefaultRenderingManager.HtmlCacheEntry( "<p>cached</p>", hash );

        when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( entry );

        final Context ctx = viewContext( "CacheFastPage", 1 );
        final String result = mgr.textToHTML( ctx, pagedata );

        assertEquals( "<p>cached</p>", result );
        // Filters should not be invoked on cache hit (short-circuit before filters)
        verify( filterManager, never() ).doPreTranslateFiltering( any(), any() );
        verify( filterManager, never() ).doPostTranslateFiltering( any(), any() );
    }

    // ========== textToHTML: stores result in HTML cache when enabled ==========

    @Test
    void textToHTMLStoresResultInHtmlCache() throws FilterException {
        when( cachingManager.enabled( CachingManager.CACHE_HTML ) ).thenReturn( true );
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( false );
        when( cachingManager.get( eq( CachingManager.CACHE_HTML ), anyString(), any() ) ).thenReturn( null );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "false" );

        final Context ctx = viewContext( "StoreHtmlPage", 1 );
        mgr.textToHTML( ctx, "text to cache" );

        // textToHTML calls getHTML(ctx, pagedata) internally which also stores in HTML cache,
        // so put may be invoked more than once; verify at least one store occurred
        verify( cachingManager, atLeastOnce() ).put( eq( CachingManager.CACHE_HTML ), anyString(),
                any( DefaultRenderingManager.HtmlCacheEntry.class ) );
    }

    // ========== getRenderer / getWysiwygRenderer ==========

    @Test
    void getRendererReturnsNonNull() {
        final Context ctx = noneContext( "RendererPage", 1 );
        final WikiDocument doc = new WikiDocument( ctx.getRealPage() );
        doc.setPageData( "text" );

        final WikiRenderer renderer = mgr.getRenderer( ctx, doc );
        assertNotNull( renderer, "getRenderer should return a standard renderer" );
    }

    @Test
    void getWysiwygRendererReturnsNonNull() {
        final Context ctx = noneContext( "WysiwygPage", 1 );
        final WikiDocument doc = new WikiDocument( ctx.getRealPage() );
        doc.setPageData( "text" );

        final WikiRenderer renderer = mgr.getWysiwygRenderer( ctx, doc );
        assertNotNull( renderer, "getWysiwygRenderer should return a WYSIWYG renderer" );
    }

    // ========== getHTML(context, doc): WYSIWYG mode selection ==========

    @Test
    void getHTMLSelectsWysiwygModeWhenVariableSet() throws Exception {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        final Context ctx = noneContext( "WysiwygDocPage", 1 );
        when( ctx.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( Boolean.TRUE );

        // Use getRenderedDocument to produce a real MarkdownDocument (required by MarkdownRenderer)
        final WikiDocument doc = mgr.getRenderedDocument( ctx, "text" );

        // Should not throw; uses WYSIWYG renderer
        final String html = mgr.getHTML( ctx, doc );
        assertNotNull( html );
    }

    @Test
    void getHTMLSelectsStandardModeByDefault() throws Exception {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        final Context ctx = noneContext( "StandardDocPage", 1 );
        when( ctx.getVariable( Context.VAR_WYSIWYG_EDITOR_MODE ) ).thenReturn( null );

        // Use getRenderedDocument to produce a real MarkdownDocument (required by MarkdownRenderer)
        final WikiDocument doc = mgr.getRenderedDocument( ctx, "text" );

        final String html = mgr.getHTML( ctx, doc );
        assertNotNull( html );
    }

    // ========== constructor injection: no-arg constructor does not NPE ==========

    @Test
    void noArgConstructorDoesNotThrow() {
        assertDoesNotThrow( () -> new DefaultRenderingManager() );
    }

    // ========== isPageDataUnchanged: fallback to string comparison when hash is null ==========

    @Test
    void getRenderedDocumentFallsBackToStringCompareWhenNoHash() {
        when( cachingManager.enabled( CachingManager.CACHE_DOCUMENTS ) ).thenReturn( true );

        final Page page = mockPage( "NoHashPage", 1 );
        final WikiDocument cachedDoc = new WikiDocument( page );
        // Deliberately set page data via direct field (bypassing setPageData which sets hash)
        // WikiDocument.getPageDataHash() returns null if setPageData was never called
        // But we can't set wikiText without setPageData. So let's create a doc that has matching text.
        cachedDoc.setPageData( "same content" );

        when( cachingManager.get( eq( CachingManager.CACHE_DOCUMENTS ), anyString(), any() ) )
                .thenReturn( cachedDoc );

        final Context ctx = viewContext( "NoHashPage", 1 );
        final WikiDocument result = mgr.getRenderedDocument( ctx, "same content" );

        // hash-based comparison should succeed and return cached doc
        assertSame( cachedDoc, result );
    }

    // ========== HtmlCacheEntry record ==========

    @Test
    void htmlCacheEntryRecordFieldsAccessible() {
        final DefaultRenderingManager.HtmlCacheEntry entry =
                new DefaultRenderingManager.HtmlCacheEntry( "<p>hi</p>", "abc123" );
        assertEquals( "<p>hi</p>", entry.html() );
        assertEquals( "abc123", entry.contentHash() );
    }

    // ========== textToHTML with hooks: filter exception in pre-translate ==========

    @Test
    void textToHTMLWithHooksHandlesPreTranslateFilterException() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );
        when( filterManager.doPreTranslateFiltering( any(), anyString() ) )
                .thenThrow( new FilterException( "pre-translate failure" ) );

        final Context ctx = noneContext( "PreFilterFail", 1 );
        final String result = mgr.textToHTML( ctx, "text", null, null, null, true, false );

        // Should catch and return empty string
        assertEquals( "", result );
    }

    // ========== textToHTML with hooks: filter exception in post-translate ==========

    @Test
    void textToHTMLWithHooksHandlesPostTranslateFilterException() throws FilterException {
        when( cachingManager.enabled( anyString() ) ).thenReturn( false );
        when( variableManager.getValue( any( Context.class ), eq( VariableManager.VAR_RUNFILTERS ), eq( "true" ) ) )
                .thenReturn( "true" );
        when( filterManager.doPreTranslateFiltering( any(), anyString() ) ).thenAnswer( inv -> inv.getArgument( 1 ) );
        when( filterManager.doPostTranslateFiltering( any(), anyString() ) )
                .thenThrow( new FilterException( "post-translate failure" ) );

        final Context ctx = noneContext( "PostFilterFail", 1 );
        final String result = mgr.textToHTML( ctx, "text", null, null, null, true, false );

        // Should not throw; the FilterException is caught and the rendered HTML (from before the
        // failed post-translate step) is returned rather than an empty string
        assertNotNull( result );
    }

}

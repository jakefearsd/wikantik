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

import com.wikantik.HttpMockFactory;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Attachment;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.ContextEnum;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.filters.PageFilter;
import com.wikantik.api.livevalues.LiveValues;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.spi.Wiki;
import com.wikantik.cache.CachingManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.parser.RenderDepth;
import com.wikantik.parser.WikiDocument;
import com.wikantik.markdown.extensions.wikilinks.postprocessor.AccessRuleLinkNodePostProcessorState;
import com.wikantik.parser.markdown.BudgetedText;
import com.wikantik.preferences.Preferences;
import com.wikantik.variables.VariableManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the cache-key and cache-skip decisions of the render package: which renders may be stored, what an eviction
 * removes, and the two cache helpers ({@link EmbedBodyCache}, {@link HtmlViewCache}) at their edges. Found by mutation
 * testing: each of these decisions survived with every other render test green.
 */
class RenderCacheDecisionsTest {

    private TestEngine engine;
    private CachingManager caches;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        caches = engine.getManager( CachingManager.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    private Context viewContext( final String pageName ) {
        final Context context = Wiki.context().create( engine, HttpMockFactory.createHttpRequest(),
                engine.getManager( PageManager.class ).getPage( pageName ) );
        context.setRequestContext( ContextEnum.PAGE_VIEW.getRequestContext() );
        return context;
    }

    private Context noneContext( final String pageName ) {
        final Context ctx = viewContext( pageName );
        ctx.setRequestContext( ContextEnum.PAGE_NONE.getRequestContext() );
        return ctx;
    }

    // ---- EmbedBodyCache ---------------------------------------------------------------------------------------------

    private static final String SLOT_PAGE = "SlotPage::1::null";
    private static final String SLOT = SLOT_PAGE + EmbedBodyCache.SLOT_SUFFIX;

    private String embed( final Context ctx, final boolean enabled, final String markdown, final EmbedBodyCache.BodyRender r )
            throws IOException {
        return EmbedBodyCache.render( caches, enabled, SLOT_PAGE, ctx, markdown, r );
    }

    private Context slotContext() throws Exception {
        engine.saveText( "SlotPage", "x\n" );
        return viewContext( "SlotPage" );
    }

    @Test
    void aRenderThatMayNotUseTheHtmlCacheIsRenderedEveryTimeAndNothingIsStored() throws Exception {
        final Context ctx = slotContext();
        final AtomicInteger renders = new AtomicInteger();
        final EmbedBodyCache.BodyRender render = () -> "<p>r" + renders.incrementAndGet() + "</p>";

        assertEquals( "<p>r1</p>", embed( ctx, false, "same", render ) );
        assertEquals( "<p>r2</p>", embed( ctx, false, "same", render ) );

        assertNull( caches.get( CachingManager.CACHE_HTML, SLOT, () -> null ), "no slot was written" );
    }

    @Test
    void aBodyThatOnlyConsultedTheViewerIsNeverStored() throws Exception {
        final Context ctx = slotContext();
        final AtomicInteger renders = new AtomicInteger();

        for( int i = 0; i < 2; i++ ) {
            embed( ctx, true, "who am i", () -> {
                renders.incrementAndGet();
                ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, Boolean.TRUE );
                return "<p>for one viewer</p>";
            } );
            ctx.setVariable( Context.VAR_VIEWER_SENSITIVE, null );
        }

        assertEquals( 2, renders.get(), "a viewer-sensitive body must be rendered for each viewer" );
        assertNull( caches.get( CachingManager.CACHE_HTML, SLOT, () -> null ), "nothing may be stored for it" );
    }

    @Test
    void aBodyThatOnlyFlaggedItselfUncacheableIsNeverStored() throws Exception {
        final Context ctx = slotContext();
        final AtomicInteger renders = new AtomicInteger();

        for( int i = 0; i < 2; i++ ) {
            embed( ctx, true, "plugin", () -> {
                renders.incrementAndGet();
                ctx.setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
                return "<p>uncacheable</p>";
            } );
            ctx.setVariable( Context.VAR_RENDER_UNCACHEABLE, null );
        }

        assertEquals( 2, renders.get() );
        assertNull( caches.get( CachingManager.CACHE_HTML, SLOT, () -> null ) );
    }

    @Test
    void aViewerIndependentBodyIsStoredAndLeavesTheHostFlagsUnset() throws Exception {
        final Context ctx = slotContext();
        final AtomicInteger renders = new AtomicInteger();
        final EmbedBodyCache.BodyRender plain = () -> "<p>plain" + renders.incrementAndGet() + "</p>";

        assertEquals( "<p>plain1</p>", embed( ctx, true, "plain", plain ) );
        assertEquals( "<p>plain1</p>", embed( ctx, true, "plain", plain ), "second call is the cached body" );

        assertEquals( 1, renders.get() );
        assertNull( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ), "the body raised nothing, so nothing is raised" );
        assertNull( ctx.getVariable( Context.VAR_RENDER_UNCACHEABLE ) );
    }

    @Test
    void aSlotHoldsAtMostEightBodiesAndKeepsTheNewest() throws Exception {
        final Context ctx = slotContext();
        for( int i = 1; i <= 8; i++ ) {
            final int n = i;
            embed( ctx, true, "body " + n, () -> "<p>" + n + "</p>" );
        }
        assertEquals( 8, slotSize() );

        final AtomicInteger renders = new AtomicInteger();
        embed( ctx, true, "body 9", () -> "<p>9</p>" );

        assertEquals( 8, slotSize(), "the ninth body displaces one; the slot never grows past eight" );
        assertEquals( "<p>9</p>", embed( ctx, true, "body 9", () -> {
            renders.incrementAndGet();
            return "<p>fresh</p>";
        } ), "the newest body is the one that was kept" );
        assertEquals( 0, renders.get() );
    }

    private int slotSize() {
        final EmbedBodyCache.Bodies bodies = caches.get( CachingManager.CACHE_HTML, SLOT, () -> null );
        return bodies.byKey().size();
    }

    @Test
    void aBodyIsKeyedByItsSourceSoAnotherSourceIsNotServedTheCachedBody() throws Exception {
        final Context ctx = slotContext();
        embed( ctx, true, "source A", () -> "<p>A</p>" );

        assertEquals( "<p>B</p>", embed( ctx, true, "source B", () -> "<p>B</p>" ) );
        assertEquals( "<p>A</p>", embed( ctx, true, "source A", () -> "<p>wrong</p>" ) );
    }

    // ---- HtmlViewCache ----------------------------------------------------------------------------------------------

    @Test
    void lookupNeverServesAnEntryToAViewerWhoseLocaleIsUnknown() {
        // an entry without a locale matches every viewer, so the null-locale guard is the only thing stopping a hit
        caches.put( CachingManager.CACHE_HTML, "LookupPage::1::null",
                new DefaultRenderingManager.HtmlCacheEntry( "<p>x</p>", "h" ) );

        assertNull( HtmlViewCache.lookup( caches, "LookupPage::1::null", "h", null ) );
        assertEquals( "<p>x</p>", HtmlViewCache.lookup( caches, "LookupPage::1::null", "h", "en" ) );
        assertNull( HtmlViewCache.lookup( caches, "LookupPage::1::null", "other-hash", "en" ) );
        assertNull( HtmlViewCache.lookup( caches, "NoSuchEntry::1::null", "h", "en" ) );
    }

    @Test
    void anEntryIsServedOnlyToViewersOfTheLocaleItWasRenderedFor() {
        HtmlViewCache.store( caches, "LocalePage::1::null",
                new DefaultRenderingManager.HtmlCacheEntry( "<p>fr</p>", "h", "fr" ) );

        assertEquals( "<p>fr</p>", HtmlViewCache.lookup( caches, "LocalePage::1::null", "h", "fr" ) );
        assertNull( HtmlViewCache.lookup( caches, "LocalePage::1::null", "h", "en" ), "another locale is a miss" );
    }

    @Test
    void anEntryWithoutALocaleIsNeverStored() {
        HtmlViewCache.store( caches, "NoLocalePage::1::null",
                new DefaultRenderingManager.HtmlCacheEntry( "<p>x</p>", "h", null ) );

        assertNull( caches.get( CachingManager.CACHE_HTML, "NoLocalePage::1::null", () -> null ) );
    }

    @Test
    void viewerLocaleIsTheLocalePreferencesResolveAndNullWhenThatFails() throws Exception {
        engine.saveText( "LocaleCtx", "x\n" );
        final Context ctx = viewContext( "LocaleCtx" );
        assertEquals( Preferences.getLocale( ctx ).toLanguageTag(), HtmlViewCache.viewerLocale( ctx ) );

        final Context broken = mock( Context.class );
        when( broken.getHttpRequest() ).thenThrow( new IllegalStateException( "no session" ) );
        assertNull( HtmlViewCache.viewerLocale( broken ), "an unknown locale must bypass the cache, not fail the render" );
    }

    @Test
    void isViewerSensitiveIsTrueForEitherFlagAndFalseForNeitherOrAFalseValue() {
        final Context ctx = mock( Context.class );
        assertFalse( HtmlViewCache.isViewerSensitive( ctx ) );

        when( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) ).thenReturn( Boolean.FALSE );
        assertFalse( HtmlViewCache.isViewerSensitive( ctx ) );

        when( ctx.getVariable( Context.VAR_VIEWER_SENSITIVE ) ).thenReturn( Boolean.TRUE );
        assertTrue( HtmlViewCache.isViewerSensitive( ctx ) );

        final Context uncacheable = mock( Context.class );
        when( uncacheable.getVariable( Context.VAR_RENDER_UNCACHEABLE ) ).thenReturn( Boolean.TRUE );
        assertTrue( HtmlViewCache.isViewerSensitive( uncacheable ) );
    }

    private static Engine engineWithRunFilters( final String value ) {
        final Engine e = mock( Engine.class );
        final Properties props = new Properties();
        if( value != null ) {
            props.setProperty( VariableManager.VAR_RUNFILTERS, value );
        }
        when( e.getWikiProperties() ).thenReturn( props );
        return e;
    }

    @Test
    void withoutAnEngineOrPropertiesTheFiltersRun() {
        final Engine noProps = mock( Engine.class );
        when( noProps.getWikiProperties() ).thenReturn( null );

        assertTrue( HtmlViewCache.runFilters( mock( Context.class ), null ) );
        assertTrue( HtmlViewCache.runFilters( mock( Context.class ), noProps ) );
        assertTrue( HtmlViewCache.runFilters( mock( Context.class ), engineWithRunFilters( null ) ), "unset property: run" );
        assertTrue( HtmlViewCache.runFilters( mock( Context.class ), engineWithRunFilters( "true" ) ) );
    }

    @Test
    void thePropertyCanSwitchTheFiltersOffAndThatRenderIsFlaggedUncacheable() {
        final Context ctx = mock( Context.class );

        assertFalse( HtmlViewCache.runFilters( ctx, engineWithRunFilters( "false" ) ) );

        verify( ctx ).setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
    }

    @Test
    void aCodeVariableWinsOverTheWikiPropertyInBothDirections() {
        final Context forcedOn = mock( Context.class );
        when( forcedOn.getVariable( VariableManager.VAR_RUNFILTERS ) ).thenReturn( "true" );
        assertTrue( HtmlViewCache.runFilters( forcedOn, engineWithRunFilters( "false" ) ) );
        verify( forcedOn, never() ).setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );

        final Context forcedOff = mock( Context.class );
        when( forcedOff.getVariable( VariableManager.VAR_RUNFILTERS ) ).thenReturn( Boolean.FALSE );
        assertFalse( HtmlViewCache.runFilters( forcedOff, engineWithRunFilters( "true" ) ) );
        verify( forcedOff ).setVariable( Context.VAR_RENDER_UNCACHEABLE, Boolean.TRUE );
    }

    // ---- RenderBudgetGuard ------------------------------------------------------------------------------------------

    private static BudgetedText.Exhausted exhausted() {
        final BudgetedText text = BudgetedText.of( "abcdefgh", 2 );
        return assertThrows( BudgetedText.Exhausted.class, () -> {
            for( int i = 0; i < 8; i++ ) {
                text.charAt( i );
            }
        } );
    }

    private static Context budgetContext() {
        final Context ctx = mock( Context.class );
        final Page page = mock( Page.class );
        when( page.getName() ).thenReturn( "BudgetPage" );
        when( ctx.getRealPage() ).thenReturn( page );
        return ctx;
    }

    @Test
    void aRenderWithinBudgetIsReturnedAsIsAndNothingIsEvicted() throws Exception {
        final AtomicInteger evictions = new AtomicInteger();
        final WikiDocument doc = mock( WikiDocument.class );

        final String html = RenderBudgetGuard.render( budgetContext(), doc, d -> "<p>ok</p>", evictions::incrementAndGet,
                text -> {
                    throw new AssertionError( "must not parse again" );
                } );

        assertEquals( "<p>ok</p>", html );
        assertEquals( 0, evictions.get() );
    }

    @Test
    void aBudgetThatRunsOutEvictsTheSharedDocumentAndRendersAFreshParseOfItsText() throws Exception {
        final AtomicInteger evictions = new AtomicInteger();
        final WikiDocument shared = mock( WikiDocument.class );
        when( shared.getPageData() ).thenReturn( "the text" );
        final WikiDocument fresh = mock( WikiDocument.class );
        final List< String > reparsed = new ArrayList<>();

        final String html = RenderBudgetGuard.render( budgetContext(), shared, d -> {
            if( d == shared ) {
                throw exhausted();
            }
            return "<p>fresh render</p>";
        }, evictions::incrementAndGet, text -> {
            reparsed.add( text );
            return fresh;
        } );

        assertEquals( "<p>fresh render</p>", html );
        assertEquals( 1, evictions.get(), "the page's render caches are evicted exactly once" );
        assertEquals( List.of( "the text" ), reparsed );
    }

    @Test
    void aDocumentWithNoTextAfterABudgetFailureRendersEmptyButStillEvicts() throws Exception {
        final AtomicInteger evictions = new AtomicInteger();
        final WikiDocument shared = mock( WikiDocument.class );
        when( shared.getPageData() ).thenReturn( null );
        final AtomicInteger renders = new AtomicInteger();

        final String html = RenderBudgetGuard.render( budgetContext(), shared, d -> {
            renders.incrementAndGet();
            throw exhausted();
        }, evictions::incrementAndGet, text -> {
            throw new AssertionError( "nothing to parse" );
        } );

        assertEquals( "", html );
        assertEquals( 1, evictions.get() );
        assertEquals( 1, renders.get(), "no second render attempt without text" );
    }

    // ---- DisplayText.forParse ---------------------------------------------------------------------------------------

    @Test
    void aBareParseReadsTheRawTextWhileARenderParsesTheDisplayText() {
        final Context ctx = mock( Context.class );
        final String raw = "[{ALLOW view Admin}]\n\nvisible body\n";

        final String parsed = DisplayText.forParse( ctx, raw, true, null );
        final String rendered = DisplayText.forParse( ctx, raw, false, null );

        assertEquals( raw, parsed, "a bare parse keeps the access rule so the caller can read it" );
        assertFalse( rendered.contains( "ALLOW" ), "a render never shows an access rule: " + rendered );
        assertTrue( rendered.contains( "visible body" ), rendered );
    }

    @Test
    void aBareParseDoesNotExpandLiveValuesButARenderDoes() {
        final Context ctx = mock( Context.class );
        final LiveValues values = mock( LiveValues.class );
        when( values.expandBody( anyString() ) ).thenReturn( "1142 articles" );
        final String raw = "{{wiki:articles}} articles";

        assertEquals( raw, DisplayText.forParse( ctx, raw, true, values ) );
        assertEquals( "1142 articles", DisplayText.forParse( ctx, raw, false, values ) );
    }

    // ---- DefaultRenderingManager: eviction and the explicit textToHTML ---------------------------------------------

    @Test
    void evictRenderCacheRemovesTheCurrentVersionAndTheLatestSentinel() throws Exception {
        engine.saveText( "EvictMe", "text\n" );
        final int version = engine.getManager( PageManager.class ).getPage( "EvictMe" ).getVersion();
        caches.put( CachingManager.CACHE_HTML, "EvictMe::" + version + "::null", "current" );
        caches.put( CachingManager.CACHE_HTML, "EvictMe::-1::null", "latest sentinel" );
        caches.put( CachingManager.CACHE_HTML, "Bystander::" + version + "::null", "other page" );

        engine.getManager( RenderingManager.class ).evictRenderCache( "EvictMe" );

        assertNull( caches.get( CachingManager.CACHE_HTML, "EvictMe::" + version + "::null", () -> null ) );
        assertNull( caches.get( CachingManager.CACHE_HTML, "EvictMe::-1::null", () -> null ) );
        assertEquals( "other page",
                caches.get( CachingManager.CACHE_HTML, "Bystander::" + version + "::null", () -> null ) );
    }

    @Test
    void evictingAPageThatDoesNotExistStillClearsItsLatestSentinel() {
        caches.put( CachingManager.CACHE_HTML, "NoSuchPageEver::-1::null", "stale" );

        engine.getManager( RenderingManager.class ).evictRenderCache( "NoSuchPageEver" );

        assertNull( caches.get( CachingManager.CACHE_HTML, "NoSuchPageEver::-1::null", () -> null ) );
    }

    @Test
    void renderingAVersionByNameIsNotAPageViewSoItNeverTouchesTheHtmlCache() throws Exception {
        engine.saveText( "ByName", "by **name**\n" );
        final int version = engine.getManager( PageManager.class ).getPage( "ByName" ).getVersion();
        final String id = "ByName::" + version + "::null";
        final RenderingManager rm = engine.getManager( RenderingManager.class );
        rm.textToHTML( viewContext( "ByName" ), "by **name**\n" );
        assertNotNull( caches.get( CachingManager.CACHE_HTML, id, () -> null ), "a real page view is cached (control)" );
        caches.remove( CachingManager.CACHE_HTML, id );

        final String html = rm.getHTML( "ByName", version );

        assertTrue( html.contains( "<strong>name</strong>" ), html );
        assertNull( caches.get( CachingManager.CACHE_HTML, id, () -> null ), "a PAGE_NONE render must not be stored" );
    }

    private final List< String > pre = new ArrayList<>();
    private final List< String > post = new ArrayList<>();

    private void addMarkingFilter() {
        engine.getManager( FilterManager.class ).addPageFilter( new PageFilter() {
            @Override
            public String preTranslate( final Context context, final String content ) {
                pre.add( content );
                return content;
            }

            @Override
            public String postTranslate( final Context context, final String html ) {
                post.add( html );
                return html + "<!--post-->";
            }
        }, 0 );
    }

    @Test
    void theExplicitTextToHtmlRendersTheTextAndRunsBothFilterPhases() throws Exception {
        engine.saveText( "ExplicitPage", "x\n" );
        addMarkingFilter();

        final String html = engine.getManager( RenderingManager.class ).textToHTML(
                noneContext( "ExplicitPage" ), "some **bold**", null, null, null, true, false );

        assertTrue( html.contains( "<strong>bold</strong>" ), html );
        assertTrue( html.endsWith( "<!--post-->" ), "the post-translate filter's output is what is returned: " + html );
        assertEquals( List.of( "some **bold**" ), pre );
        assertEquals( 1, post.size() );
    }

    @Test
    void aBareParseReturnsEmptyAndOnlyRunsThePreTranslateFilter() throws Exception {
        engine.saveText( "BareParse", "x\n" );
        addMarkingFilter();

        final String html = engine.getManager( RenderingManager.class ).textToHTML(
                noneContext( "BareParse" ), "some **bold**", null, null, null, true, true );

        assertEquals( "", html );
        assertEquals( 1, pre.size(), "the pre-translate filter still runs on a bare parse" );
        assertTrue( post.isEmpty() );
    }

    @Test
    void theExplicitTextToHtmlLeavesTheRenderDepthAsItFoundIt() throws Exception {
        engine.saveText( "DepthPage", "x\n" );

        engine.getManager( RenderingManager.class ).textToHTML(
                noneContext( "DepthPage" ), "text", null, null, null, true, false );

        assertTrue( RenderDepth.enter(), "nothing may still be counted as rendering on this thread" );
        RenderDepth.leave();
    }

    @Test
    void theLinkHooksAreFedTheLocalExternalAndAttachmentLinksOfTheText() throws Exception {
        engine.saveText( "HookPage", "x\n" );
        final Attachment att = Wiki.contents().attachment( engine, "HookPage", "pic.txt" );
        att.setAuthor( "tester" );
        engine.getManager( AttachmentManager.class ).storeAttachment( att, engine.makeAttachmentFile() );
        final List< String > local = new ArrayList<>();
        final List< String > external = new ArrayList<>();
        final List< String > attachment = new ArrayList<>();

        engine.getManager( RenderingManager.class ).textToHTML( noneContext( "HookPage" ),
                "[Target]() and [out](http://example.com/x) and [file](pic.txt)",
                ( ctx, s ) -> {
                    local.add( s );
                    return s;
                },
                ( ctx, s ) -> {
                    external.add( s );
                    return s;
                },
                ( ctx, s ) -> {
                    attachment.add( s );
                    return s;
                },
                true, false );

        assertEquals( List.of( "Target" ), local );
        assertEquals( List.of( "http://example.com/x" ), external );
        assertEquals( 1, attachment.size(), "the attachment link reaches the attachment hook: " + attachment );
        assertTrue( attachment.get( 0 ).endsWith( "pic.txt" ), attachment.get( 0 ) );
    }

    @Test
    void accessRulesAreReadFromABareParseOnlyWhenTheCallerAsksForThem() throws Exception {
        engine.saveText( "AclParse", "x\n" );
        final String text = "[{ALLOW view Bob}]\n\nbody\n";
        final RenderingManager rm = engine.getManager( RenderingManager.class );

        final List< String > readOff = new ArrayList<>();
        final Context off = noneContext( "AclParse" );
        off.setVariable( AccessRuleLinkNodePostProcessorState.VAR_READ_ACCESS_RULES, readOff );
        rm.textToHTML( off, text, null, null, null, false, true );
        assertEquals( List.of(), readOff, "parseAccessRules=false: no rule is read" );

        final List< String > readOn = new ArrayList<>();
        final Context on = noneContext( "AclParse" );
        on.setVariable( AccessRuleLinkNodePostProcessorState.VAR_READ_ACCESS_RULES, readOn );
        rm.textToHTML( on, text, null, null, null, true, true );
        assertEquals( List.of( "ALLOW view Bob" ), readOn, "parseAccessRules=true: the rule is read" );
    }
}

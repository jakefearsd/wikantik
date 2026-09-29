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
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Engine;
import com.wikantik.api.core.Page;
import com.wikantik.api.exceptions.WikiException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import com.wikantik.cache.CachingManager;
import com.wikantik.filters.FilterManager;
import com.wikantik.parser.MarkupParser;
import com.wikantik.parser.WikiDocument;
import com.wikantik.variables.VariableManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultRenderingManager}'s defensive branches: the invalid-configuration
 * paths in {@code initialize()}, the reflective-failure wrapping in {@code getParser()}, the
 * per-call exception isolation in {@code getRenderedDocument()}/{@code getHTML()}/
 * {@code textToHTML()}, and {@code createRenderer()}'s missing-strategy / constructor-failure
 * paths. Uses the class's test-friendly DI constructor plus reflection for the handful of
 * private fields (markupParserClass, rendererStrategies) and the private pure-logic method
 * {@code isPageDataUnchanged} that have no other seam.
 */
class DefaultRenderingManagerTest {

    private CachingManager cachingManager;
    private FilterManager filterManager;
    private PageManager pageManager;
    private AttachmentManager attachmentManager;
    private VariableManager variableManager;
    private Context context;
    private Page realPage;

    @BeforeEach
    void setUp() {
        cachingManager = mock( CachingManager.class );
        filterManager = mock( FilterManager.class );
        pageManager = mock( PageManager.class );
        attachmentManager = mock( AttachmentManager.class );
        variableManager = mock( VariableManager.class );

        realPage = mock( Page.class );
        when( realPage.getName() ).thenReturn( "TestPage" );
        when( realPage.getVersion() ).thenReturn( 1 );

        context = mock( Context.class );
        when( context.getRealPage() ).thenReturn( realPage );
    }

    private DefaultRenderingManager manager() {
        return new DefaultRenderingManager( null, cachingManager, filterManager, pageManager, attachmentManager, variableManager );
    }

    private static void setMarkupParserClass( final DefaultRenderingManager mgr, final String className ) throws Exception {
        final Field f = DefaultRenderingManager.class.getDeclaredField( "markupParserClass" );
        f.setAccessible( true );
        f.set( mgr, className );
    }

    @SuppressWarnings( "unchecked" )
    private static Map< String, Constructor< ? > > rendererStrategies( final DefaultRenderingManager mgr ) throws Exception {
        final Field f = DefaultRenderingManager.class.getDeclaredField( "rendererStrategies" );
        f.setAccessible( true );
        return ( Map< String, Constructor< ? > > ) f.get( mgr );
    }

    private static Object invokePrivate( final Object target, final String name,
            final Class< ? >[] paramTypes, final Object... args ) throws Exception {
        final Method m = DefaultRenderingManager.class.getDeclaredMethod( name, paramTypes );
        m.setAccessible( true );
        return m.invoke( target, args );
    }

    // ---- initialize(): invalid parser / renderer configuration ---------------------------------

    @Test
    void initializeRevertsToDefaultParserWhenConfiguredParserIsNotAMarkupParser() throws Exception {
        final Engine engine = MockEngineBuilder.engine()
                .with( CachingManager.class, cachingManager )
                .with( FilterManager.class, filterManager )
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( VariableManager.class, variableManager )
                .build();
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_PARSER, "java.lang.String" ); // not a MarkupParser

        final DefaultRenderingManager mgr = manager();
        mgr.initialize( engine, props );

        final Field f = DefaultRenderingManager.class.getDeclaredField( "markupParserClass" );
        f.setAccessible( true );
        assertEquals( "com.wikantik.parser.markdown.MarkdownParser", f.get( mgr ) );
    }

    @Test
    void initializeThrowsWikiExceptionWhenRendererClassNotFound() {
        final Engine engine = MockEngineBuilder.engine()
                .with( CachingManager.class, cachingManager )
                .with( FilterManager.class, filterManager )
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( VariableManager.class, variableManager )
                .build();
        final Properties props = new Properties();
        props.setProperty( RenderingManager.PROP_RENDERER, "com.wikantik.render.NoSuchRendererXyz" );

        final DefaultRenderingManager mgr = manager();
        assertThrows( WikiException.class, () -> mgr.initialize( engine, props ) );
    }

    @Test
    void initializeThrowsWikiExceptionWhenRendererMissingExpectedConstructor() {
        final Engine engine = MockEngineBuilder.engine()
                .with( CachingManager.class, cachingManager )
                .with( FilterManager.class, filterManager )
                .with( PageManager.class, pageManager )
                .with( AttachmentManager.class, attachmentManager )
                .with( VariableManager.class, variableManager )
                .build();
        final Properties props = new Properties();
        // A real, loadable class, but it has no (Context, WikiDocument) constructor.
        props.setProperty( RenderingManager.PROP_RENDERER, "java.lang.String" );

        final DefaultRenderingManager mgr = manager();
        assertThrows( WikiException.class, () -> mgr.initialize( engine, props ) );
    }

    // ---- getParser(): reflective failure wrapping ----------------------------------------------

    @Test
    void getParserWrapsReflectiveFailureInRuntimeException() throws Exception {
        final DefaultRenderingManager mgr = manager();
        // A nonexistent class makes ClassUtil.getMappedObject's Class.forName() throw
        // ClassNotFoundException (a ReflectiveOperationException), landing in getParser()'s
        // catch clause — unlike e.g. "java.lang.Object", which ClassUtil happily constructs
        // via its no-arg constructor (ignoring the mismatched initargs) and would only fail
        // with an uncaught ClassCastException on return, bypassing the catch block entirely.
        setMarkupParserClass( mgr, "com.wikantik.render.NoSuchMarkupParserXyz" );

        assertThrows( RuntimeException.class, () -> mgr.getParser( context, "some text" ) );
    }

    // ---- getRenderedDocument(): per-call exception isolation -----------------------------------

    @Test
    void getRenderedDocumentReturnsNullWhenParserThrowsIOException() throws Exception {
        final DefaultRenderingManager mgr = manager();
        setMarkupParserClass( mgr, IOExceptionParser.class.getName() );

        assertNull( mgr.getRenderedDocument( context, "text" ) );
    }

    @Test
    void getRenderedDocumentReturnsNullWhenParserThrowsRuntimeException() throws Exception {
        final DefaultRenderingManager mgr = manager();
        setMarkupParserClass( mgr, RuntimeExceptionParser.class.getName() );

        assertNull( mgr.getRenderedDocument( context, "text" ) );
    }

    // ---- isPageDataUnchanged(): hash-absent fallback -------------------------------------------

    @Test
    void isPageDataUnchangedFallsBackToStringComparisonWhenNoHash() throws Exception {
        final DefaultRenderingManager mgr = manager();
        final WikiDocument doc = new WikiDocument( realPage ); // pageDataHash is null until setPageData() is called

        final Object result = invokePrivate( mgr, "isPageDataUnchanged",
                new Class<?>[] { String.class, WikiDocument.class }, "hello", doc );

        assertEquals( Boolean.FALSE, result, "doc.getPageData() is null, so \"hello\".equals(null) must be false" );
    }

    // ---- getHTML(Context, String): per-call exception isolation --------------------------------

    @Test
    void getHTMLStringOverloadReturnsNullWhenRendererThrowsIOException() throws Exception {
        final DefaultRenderingManager mgr = manager();
        // The real default MarkdownParser needs a live engine (AuthSubsystem wiring) to
        // construct; SimpleParser produces a minimal valid WikiDocument without that
        // dependency, letting this test isolate the getHTML(context, doc) failure.
        setMarkupParserClass( mgr, SimpleParser.class.getName() );
        rendererStrategies( mgr ).put( "standard",
                ThrowingGetStringRenderer.class.getConstructor( Context.class, WikiDocument.class ) );

        assertNull( mgr.getHTML( context, "some text" ) );
    }

    // ---- textToHTML(): disableAccessRules + parser IOException isolation -----------------------

    @Test
    void textToHTMLDisablesAccessRulesWhenConfiguredAndJustParses() throws Exception {
        final DefaultRenderingManager mgr = manager();
        setMarkupParserClass( mgr, SimpleParser.class.getName() );

        final String result = mgr.textToHTML( context, "plain text", null, null, null, false, true );

        assertEquals( "", result, "justParse=true must skip rendering and return the initial empty result" );
    }

    @Test
    void textToHTMLReturnsEmptyResultWhenParserThrowsIOException() throws Exception {
        final DefaultRenderingManager mgr = manager();
        setMarkupParserClass( mgr, IOExceptionParser.class.getName() );

        final String result = mgr.textToHTML( context, "plain text", null, null, null, true, true );

        assertEquals( "", result );
    }

    // ---- createRenderer(): missing strategy / constructor failure ------------------------------

    @Test
    void getRendererReturnsNullWhenNoStrategyIsRegisteredForMode() throws Exception {
        final DefaultRenderingManager mgr = manager(); // rendererStrategies starts empty
        final WikiDocument doc = new WikiDocument( realPage );
        doc.setPageData( "text" );

        assertNull( mgr.getRenderer( context, doc ) );
    }

    @Test
    void getRendererReturnsNullWhenTheRegisteredConstructorThrows() throws Exception {
        final DefaultRenderingManager mgr = manager();
        rendererStrategies( mgr ).put( "standard",
                ThrowingConstructorRenderer.class.getConstructor( Context.class, WikiDocument.class ) );
        final WikiDocument doc = new WikiDocument( realPage );
        doc.setPageData( "text" );

        assertNull( mgr.getRenderer( context, doc ) );
    }

    // ---- test doubles ---------------------------------------------------------------------------

    /**
     * Minimal working MarkupParser that produces a valid (empty) WikiDocument without any of
     * the real {@code MarkdownParser}'s engine/AuthSubsystem dependencies — used wherever a
     * test needs {@code parse()} to succeed but doesn't care about actual markdown conversion.
     */
    public static final class SimpleParser extends MarkupParser {
        public SimpleParser( final Context context, final Reader in ) {
            super( context, in );
        }
        @Override
        public WikiDocument parse() {
            final WikiDocument doc = new WikiDocument( context.getRealPage() );
            doc.setPageData( "text" );
            return doc;
        }
    }

    /** MarkupParser whose {@code parse()} always fails with a checked IOException. */
    public static final class IOExceptionParser extends MarkupParser {
        public IOExceptionParser( final Context context, final Reader in ) {
            super( context, in );
        }
        @Override
        public WikiDocument parse() throws IOException {
            throw new IOException( "simulated scan failure" );
        }
    }

    /** MarkupParser whose {@code parse()} always fails with an unchecked exception. */
    public static final class RuntimeExceptionParser extends MarkupParser {
        public RuntimeExceptionParser( final Context context, final Reader in ) {
            super( context, in );
        }
        @Override
        public WikiDocument parse() {
            throw new RuntimeException( "simulated unexpected failure" );
        }
    }

    /** WikiRenderer whose {@code getString()} always fails with a checked IOException. */
    public static final class ThrowingGetStringRenderer extends WikiRenderer {
        public ThrowingGetStringRenderer( final Context context, final WikiDocument doc ) {
            super( context, doc );
        }
        @Override
        public String getString() throws IOException {
            throw new IOException( "simulated render failure" );
        }
    }

    /** WikiRenderer whose constructor always fails, simulating an uninstantiable renderer. */
    public static final class ThrowingConstructorRenderer extends WikiRenderer {
        public ThrowingConstructorRenderer( final Context context, final WikiDocument doc ) {
            super( context, doc );
            throw new IllegalStateException( "simulated construction failure" );
        }
        @Override
        public String getString() {
            return "";
        }
    }
}

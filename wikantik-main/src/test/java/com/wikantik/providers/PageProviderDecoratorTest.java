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
import com.wikantik.api.exceptions.ProviderException;
import com.wikantik.api.providers.PageProvider;
import com.wikantik.cache.CachingManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Unit tests for PageProviderDecorator and its subclasses.
 */
class PageProviderDecoratorTest {

    /**
     * Minimal no-op decorator used to exercise the {@link PageProviderDecorator}
     * delegation contract (chaining, {@code getRealProvider()} unwrapping) without
     * depending on a concrete production subclass.
     */
    private static final class NoOpPageProviderDecorator extends PageProviderDecorator {
        NoOpPageProviderDecorator( final PageProvider delegate ) {
            super( delegate );
        }
    }

    private TestEngine engine;

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            engine.stop();
        }
    }

    @Test
    void testDecoratorDelegatesAllMethods() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();

        // Create a simple decorator that just delegates
        final PageProviderDecorator decorator = new PageProviderDecorator( provider ) {};

        // Test various methods
        Assertions.assertEquals( provider.getProviderInfo(), decorator.getProviderInfo() );
        Assertions.assertEquals( provider.getPageCount(), decorator.getPageCount() );

        // Save a page through the decorator
        engine.saveText( "DecoratorTest", "Test content" );

        // Read through decorator
        Assertions.assertTrue( decorator.pageExists( "DecoratorTest" ) );
        Assertions.assertNotNull( decorator.getPageInfo( "DecoratorTest", -1 ) );
        Assertions.assertTrue( decorator.getPageText( "DecoratorTest", -1 ).startsWith( "Test content" ) );
    }

    @Test
    void testDecoratorChaining() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider baseProvider = engine.getManager( PageManager.class ).getProvider();

        // Chain decorators: outer no-op -> Logging -> Base
        final LoggingPageProviderDecorator loggingDecorator =
                new LoggingPageProviderDecorator( baseProvider );
        final NoOpPageProviderDecorator outerDecorator =
                new NoOpPageProviderDecorator( loggingDecorator );

        // Verify chain
        Assertions.assertEquals( loggingDecorator, outerDecorator.getDelegate() );
        Assertions.assertEquals( baseProvider, loggingDecorator.getDelegate() );
        Assertions.assertEquals( baseProvider, outerDecorator.getRealProvider() );
    }

    @Test
    void testLoggingDecoratorDelegates() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final LoggingPageProviderDecorator loggingProvider = new LoggingPageProviderDecorator( provider );

        engine.saveText( "LoggingTest", "Content" );

        // Verify delegate calls work
        Assertions.assertTrue( loggingProvider.pageExists( "LoggingTest" ) );
        Assertions.assertFalse( loggingProvider.pageExists( "NonExistent" ) );

        final Page page = loggingProvider.getPageInfo( "LoggingTest", -1 );
        Assertions.assertNotNull( page );
        Assertions.assertEquals( "LoggingTest", page.getName() );

        final String text = loggingProvider.getPageText( "LoggingTest", -1 );
        Assertions.assertTrue( text.startsWith( "Content" ) );
    }

    @Test
    void testDecoratorNullDelegateThrows() {
        Assertions.assertThrows( NullPointerException.class, () ->
                new LoggingPageProviderDecorator( null )
        );
    }

    @Test
    void testGetRealProviderUnwrapsChain() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider baseProvider = engine.getManager( PageManager.class ).getProvider();

        // Create a 3-level chain
        final LoggingPageProviderDecorator level1 = new LoggingPageProviderDecorator( baseProvider );
        final NoOpPageProviderDecorator level2 = new NoOpPageProviderDecorator( level1 );
        final LoggingPageProviderDecorator level3 = new LoggingPageProviderDecorator( level2 );

        // getRealProvider should unwrap all levels
        Assertions.assertEquals( baseProvider, level3.getRealProvider() );
        Assertions.assertEquals( baseProvider, level2.getRealProvider() );
        Assertions.assertEquals( baseProvider, level1.getRealProvider() );
    }

    @Test
    void testDecoratorVersionHistory() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        props.setProperty( "wikantik.pageProvider", "com.wikantik.providers.VersioningFileProvider" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final PageProviderDecorator decorator = new NoOpPageProviderDecorator( provider );

        // Create multiple versions
        engine.saveText( "VersionTest", "v1" );
        engine.saveText( "VersionTest", "v2" );
        engine.saveText( "VersionTest", "v3" );

        // Get version history through decorator
        final var history = decorator.getVersionHistory( "VersionTest" );
        Assertions.assertEquals( 3, history.size() );
    }
}

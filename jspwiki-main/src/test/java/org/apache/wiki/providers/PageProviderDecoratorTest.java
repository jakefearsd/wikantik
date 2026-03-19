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
package org.apache.wiki.providers;

import org.apache.wiki.TestEngine;
import org.apache.wiki.api.core.Page;
import org.apache.wiki.api.exceptions.ProviderException;
import org.apache.wiki.api.providers.PageProvider;
import org.apache.wiki.cache.CachingManager;
import org.apache.wiki.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Properties;

/**
 * Unit tests for PageProviderDecorator and its subclasses.
 */
class PageProviderDecoratorTest {

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

        // Chain decorators: Metrics -> Logging -> Base
        final LoggingPageProviderDecorator loggingDecorator =
                new LoggingPageProviderDecorator( baseProvider );
        final MetricsPageProviderDecorator metricsDecorator =
                new MetricsPageProviderDecorator( loggingDecorator );

        // Verify chain
        Assertions.assertEquals( loggingDecorator, metricsDecorator.getDelegate() );
        Assertions.assertEquals( baseProvider, loggingDecorator.getDelegate() );
        Assertions.assertEquals( baseProvider, metricsDecorator.getRealProvider() );
    }

    @Test
    void testMetricsDecoratorTracksCalls() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator( provider );

        // Initial state
        Assertions.assertEquals( 0, metricsProvider.getMetrics().pageExistsCalls.get() );
        Assertions.assertEquals( 0, metricsProvider.getMetrics().getPageInfoCalls.get() );

        // Save through engine (doesn't go through decorator)
        engine.saveText( "MetricsTest", "Content" );

        // Call through decorator
        metricsProvider.pageExists( "MetricsTest" );
        metricsProvider.pageExists( "MetricsTest" );
        metricsProvider.pageExists( "NonExistent" );

        Assertions.assertEquals( 3, metricsProvider.getMetrics().pageExistsCalls.get() );
        Assertions.assertTrue( metricsProvider.getMetrics().pageExistsTimeNanos.get() > 0 );

        metricsProvider.getPageInfo( "MetricsTest", -1 );
        Assertions.assertEquals( 1, metricsProvider.getMetrics().getPageInfoCalls.get() );
    }

    @Test
    void testMetricsDecoratorReset() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator( provider );

        engine.saveText( "ResetTest", "Content" );

        // Make some calls
        metricsProvider.pageExists( "ResetTest" );
        metricsProvider.getPageInfo( "ResetTest", -1 );

        Assertions.assertTrue( metricsProvider.getMetrics().pageExistsCalls.get() > 0 );
        Assertions.assertTrue( metricsProvider.getMetrics().getPageInfoCalls.get() > 0 );

        // Reset
        metricsProvider.resetMetrics();

        Assertions.assertEquals( 0, metricsProvider.getMetrics().pageExistsCalls.get() );
        Assertions.assertEquals( 0, metricsProvider.getMetrics().getPageInfoCalls.get() );
        Assertions.assertEquals( 0, metricsProvider.getMetrics().pageExistsTimeNanos.get() );
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
        final MetricsPageProviderDecorator level2 = new MetricsPageProviderDecorator( level1 );
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
        props.setProperty( "wikantik.pageProvider", "org.apache.wiki.providers.VersioningFileProvider" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator( provider );

        // Create multiple versions
        engine.saveText( "VersionTest", "v1" );
        engine.saveText( "VersionTest", "v2" );
        engine.saveText( "VersionTest", "v3" );

        // Get version history through decorator
        final var history = metricsProvider.getVersionHistory( "VersionTest" );
        Assertions.assertEquals( 3, history.size() );
        Assertions.assertEquals( 1, metricsProvider.getMetrics().getVersionHistoryCalls.get() );
    }

    @Test
    void testMetricsAverageCalculation() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator( provider );

        engine.saveText( "AvgTest", "Content" );

        // Make multiple calls
        for ( int i = 0; i < 10; i++ ) {
            metricsProvider.getPageText( "AvgTest", -1 );
        }

        Assertions.assertEquals( 10, metricsProvider.getMetrics().getPageTextCalls.get() );
        Assertions.assertTrue( metricsProvider.getMetrics().getAverageGetPageTextMs() > 0 );

        // Empty metrics should return 0 average
        metricsProvider.resetMetrics();
        Assertions.assertEquals( 0.0, metricsProvider.getMetrics().getAverageGetPageTextMs() );
    }

    @Test
    void testMetricsToString() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CachingManager.PROP_CACHE_ENABLE, "false" );
        engine = TestEngine.build( props );

        final PageProvider provider = engine.getManager( PageManager.class ).getProvider();
        final MetricsPageProviderDecorator metricsProvider = new MetricsPageProviderDecorator( provider );

        engine.saveText( "ToStringTest", "Content" );
        metricsProvider.pageExists( "ToStringTest" );
        metricsProvider.getPageInfo( "ToStringTest", -1 );

        final String metricsString = metricsProvider.getMetrics().toString();
        Assertions.assertTrue( metricsString.contains( "pageExists=1" ) );
        Assertions.assertTrue( metricsString.contains( "getPageInfo=1" ) );
    }
}

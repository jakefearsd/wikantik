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
import com.wikantik.api.core.Engine;
import com.wikantik.api.exceptions.NoRequiredPropertyException;
import com.wikantik.api.managers.AttachmentManager;
import com.wikantik.api.managers.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for how the two caching decorators locate, instantiate and
 * initialise the real provider they wrap.
 *
 * <p>{@link CachingProvider} and {@link CachingAttachmentProvider} carried the same
 * ~16-line block for this, differing only in which property names the class comes from.
 * These tests were written BEFORE that block was shared, to pin the observable behaviour
 * of both paths — a missing property and an unusable class name — so the extraction could
 * be shown to change nothing. They are equally useful afterwards as the only coverage
 * these error branches have.
 *
 * <p>Note this is a consistency refactor, not a bug fix: both copies already handled both
 * error paths correctly and identically.
 */
class ProviderBootstrapTest {

    private Engine engine;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
    }

    @AfterEach
    void tearDown() {
        if ( engine != null ) {
            ( (TestEngine) engine ).stop();
        }
    }

    /** Engine properties with the page-provider key removed. */
    private Properties withoutPageProvider() {
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.remove( PageManager.PROP_PAGEPROVIDER );
        return props;
    }

    /** Engine properties with both the current and deprecated attachment-provider keys removed. */
    private Properties withoutAttachmentProvider() {
        final Properties props = new Properties();
        props.putAll( engine.getWikiProperties() );
        props.remove( AttachmentManager.PROP_PROVIDER );
        props.remove( "wikantik.attachmentProvider" );
        return props;
    }

    // ------------------------------------------------------------------
    // Missing property
    // ------------------------------------------------------------------

    @Test
    void cachingProvider_missingPageProviderProperty_reportsWhichPropertyIsMissing() {
        final NoRequiredPropertyException e = assertThrows( NoRequiredPropertyException.class,
            () -> new CachingProvider().initialize( engine, withoutPageProvider() ),
            "A missing page-provider property must name the property, not fail obscurely." );

        assertTrue( e.getMessage() != null && !e.getMessage().isBlank(),
            "The exception must carry a message identifying the missing property." );
    }

    @Test
    void cachingAttachmentProvider_missingAttachmentProviderProperty_reportsWhichPropertyIsMissing() {
        final NoRequiredPropertyException e = assertThrows( NoRequiredPropertyException.class,
            () -> new CachingAttachmentProvider().initialize( engine, withoutAttachmentProvider() ),
            "A missing attachment-provider property must name the property, not fail obscurely." );

        assertTrue( e.getMessage() != null && !e.getMessage().isBlank(),
            "The exception must carry a message identifying the missing property." );
    }

    /**
     * The attachment provider accepts a deprecated key as a fallback. Pinned because the
     * shared lookup must keep passing it through — dropping it would silently break any
     * deployment still using the old key.
     */
    @Test
    void cachingAttachmentProvider_acceptsTheDeprecatedPropertyKey() throws Exception {
        final Properties props = withoutAttachmentProvider();
        props.setProperty( "wikantik.attachmentProvider", "BasicAttachmentProvider" );

        final CachingAttachmentProvider provider = new CachingAttachmentProvider();
        provider.initialize( engine, props );

        assertNotNull( provider.getRealProvider(),
            "The deprecated attachment-provider key must still resolve a real provider." );
    }

    // ------------------------------------------------------------------
    // Unusable class name
    // ------------------------------------------------------------------

    @Test
    void cachingProvider_unknownProviderClass_failsWithAnActionableMessage() {
        final Properties props = withoutPageProvider();
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "NoSuchProviderClassAnywhere" );

        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> new CachingProvider().initialize( engine, props ) );

        assertEquals( "illegal provider class", e.getMessage() );
        assertInstanceOf( ReflectiveOperationException.class, e.getCause(),
            "The reflective failure must be preserved as the cause for diagnosis." );
    }

    @Test
    void cachingAttachmentProvider_unknownProviderClass_failsWithAnActionableMessage() {
        final Properties props = withoutAttachmentProvider();
        props.setProperty( AttachmentManager.PROP_PROVIDER, "NoSuchProviderClassAnywhere" );

        final IllegalArgumentException e = assertThrows( IllegalArgumentException.class,
            () -> new CachingAttachmentProvider().initialize( engine, props ) );

        assertEquals( "illegal provider class", e.getMessage() );
        assertInstanceOf( ReflectiveOperationException.class, e.getCause(),
            "The reflective failure must be preserved as the cause for diagnosis." );
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------

    @Test
    void cachingProvider_resolvesAndInitialisesTheConfiguredProvider() throws Exception {
        final Properties props = withoutPageProvider();
        props.setProperty( PageManager.PROP_PAGEPROVIDER, "com.wikantik.providers.CounterProvider" );

        final CachingProvider provider = new CachingProvider();
        provider.initialize( engine, props );

        assertInstanceOf( CounterProvider.class, provider.getRealProvider(),
            "The configured class must be the one wrapped." );
    }
}

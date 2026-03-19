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

import com.wikantik.TestEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultSystemPageRegistry}.
 */
class SystemPageRegistryTest {

    private TestEngine engine;
    private SystemPageRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        registry = engine.getManager( SystemPageRegistry.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    @Test
    void testRegistryIsAvailable() {
        assertNotNull( registry, "SystemPageRegistry should be registered as a manager" );
    }

    @Test
    void testDiscoveryFindsKnownPages() {
        final Set<String> names = registry.getSystemPageNames();
        assertFalse( names.isEmpty(), "Should discover at least some system pages" );

        // About.txt is the anchor resource and must always be discovered
        assertTrue( names.contains( "About" ), "Should discover About" );
        // TextFormattingRules.txt is in test resources alongside About.txt
        assertTrue( names.contains( "TextFormattingRules" ), "Should discover TextFormattingRules" );
    }

    @Test
    void testIsSystemPageForDiscoveredPages() {
        assertTrue( registry.isSystemPage( "About" ) );
        assertTrue( registry.isSystemPage( "TextFormattingRules" ) );
    }

    @Test
    void testIsSystemPageFalseForArbitraryNames() {
        assertFalse( registry.isSystemPage( "MyCustomPage" ) );
        assertFalse( registry.isSystemPage( "SomeRandomArticle" ) );
        assertFalse( registry.isSystemPage( "BlogPost2026" ) );
    }

    @Test
    void testIsSystemPageNullSafe() {
        assertFalse( registry.isSystemPage( null ) );
    }

    @Test
    void testSystemPageNamesAreUnmodifiable() {
        final Set<String> names = registry.getSystemPageNames();
        assertThrows( UnsupportedOperationException.class, () -> names.add( "Hacked" ) );
    }

    @Test
    void testExtraPatterns() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SystemPageRegistry.PROP_EXTRA_PATTERNS, "MyCustom.*,Internal_.+" );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();
        customRegistry.initialize( engine, props );

        assertTrue( customRegistry.isSystemPage( "MyCustomPage" ) );
        assertTrue( customRegistry.isSystemPage( "MyCustomWidget" ) );
        assertTrue( customRegistry.isSystemPage( "Internal_Config" ) );
        assertFalse( customRegistry.isSystemPage( "Internal_" ) );  // .+ requires at least one char
        assertFalse( customRegistry.isSystemPage( "RegularPage" ) );
    }

    @Test
    void testExtraPatternsMatchedViaIsSystemPage() throws Exception {
        // Extra patterns should also be checked by isSystemPage, not just the discovered set
        final Properties props = new Properties();
        props.setProperty( SystemPageRegistry.PROP_EXTRA_PATTERNS, "CSS.*" );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();
        customRegistry.initialize( engine, props );

        assertTrue( customRegistry.isSystemPage( "CSSRibbon" ) );
        assertTrue( customRegistry.isSystemPage( "CSSThemeDark" ) );
        assertFalse( customRegistry.isSystemPage( "RegularPage" ) );
    }

    @Test
    void testDiscoveryFromTestResources() {
        // In the test environment, About.txt is placed in src/test/resources
        // alongside other .txt files. Discovery should enumerate all of them.
        final Set<String> names = registry.getSystemPageNames();
        assertTrue( names.size() >= 2, "Should discover at least About and TextFormattingRules" );
    }
}

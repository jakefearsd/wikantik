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
import com.wikantik.api.managers.SystemPageRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional tests for {@link DefaultSystemPageRegistry} covering uncovered branches:
 * isSystemPage with null pageName, extra pattern matching via PROP_EXTRA_PATTERNS,
 * invalid pattern in PROP_EXTRA_PATTERNS (logged and skipped), getSystemPageNames.
 */
class DefaultSystemPageRegistryAdditionalTest {

    private TestEngine engine;
    private SystemPageRegistry registry;

    @BeforeEach
    void setUp() {
        engine = TestEngine.build();
        registry = engine.getManager( SystemPageRegistry.class );
    }

    @AfterEach
    void tearDown() {
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // isSystemPage(null) — should return false without throwing
    // -----------------------------------------------------------------------

    @Test
    void isSystemPageWithNullReturnsFalse() {
        assertFalse( registry.isSystemPage( null ),
                "isSystemPage(null) should return false" );
    }

    // -----------------------------------------------------------------------
    // getSystemPageNames — returns non-null set
    // -----------------------------------------------------------------------

    @Test
    void getSystemPageNamesReturnsNonNullSet() {
        final Set<String> names = registry.getSystemPageNames();
        assertNotNull( names );
        // The test classpath contains About.md, so there should be at least one system page
        assertFalse( names.isEmpty(), "At least one system page should be discovered from classpath" );
    }

    // -----------------------------------------------------------------------
    // Known system pages discovered from classpath
    // -----------------------------------------------------------------------

    @Test
    void aboutIsASystemPage() {
        // About.md is the anchor resource, so About should be discovered
        assertTrue( registry.isSystemPage( "About" ),
                "About should be recognized as a system page" );
    }

    // -----------------------------------------------------------------------
    // Non-system page is not in registry
    // -----------------------------------------------------------------------

    @Test
    void randomPageIsNotSystemPage() {
        assertFalse( registry.isSystemPage( "MyCustomNonSystemPage12345" ) );
    }

    // -----------------------------------------------------------------------
    // Extra patterns via PROP_EXTRA_PATTERNS
    // -----------------------------------------------------------------------

    @Test
    void extraPatternMatchesPages() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SystemPageRegistry.PROP_EXTRA_PATTERNS, "Admin.*,System.*" );
        final TestEngine engineWithPatterns = new TestEngine( props );

        final SystemPageRegistry reg = engineWithPatterns.getManager( SystemPageRegistry.class );
        assertTrue( reg.isSystemPage( "AdminPanel" ),
                "AdminPanel should match Admin.* extra pattern" );
        assertTrue( reg.isSystemPage( "SystemSettings" ),
                "SystemSettings should match System.* extra pattern" );
        assertFalse( reg.isSystemPage( "UserPage" ),
                "UserPage should not match any pattern" );

        engineWithPatterns.stop();
    }

    @Test
    void invalidExtraPatternIsSkippedGracefully() throws Exception {
        // An invalid regex should not prevent startup
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( SystemPageRegistry.PROP_EXTRA_PATTERNS, "[invalid((regex, Valid.*" );
        final TestEngine engineWithBadPattern = new TestEngine( props );

        final SystemPageRegistry reg = engineWithBadPattern.getManager( SystemPageRegistry.class );
        assertNotNull( reg );
        // The valid part ("Valid.*") might or might not compile depending on where the error is
        // The important thing is that initialization did not throw
        assertFalse( reg.isSystemPage( null ) );

        engineWithBadPattern.stop();
    }
}

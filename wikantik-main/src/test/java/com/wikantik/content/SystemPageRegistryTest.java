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
import com.wikantik.WikiEngine;
import com.wikantik.api.managers.PageManager;
import com.wikantik.api.managers.SystemPageRegistry;
import com.wikantik.page.subsystem.PageSubsystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        // About.md is the anchor resource and must always be discovered
        assertTrue( names.contains( "About" ), "Should discover About" );
        // TextFormattingRules.md is in test resources alongside About.md
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
    void testAboutIsMcpEditableByDefault() {
        // About ships as editorial default content (and is the discovery anchor),
        // so it stays a system page but must be editable via MCP update_page.
        assertTrue( registry.isSystemPage( "About" ), "About is still a system page" );
        assertTrue( registry.isMcpEditable( "About" ), "About must be MCP-editable by default" );
    }

    @Test
    void testStructuralSystemPagesAreNotMcpEditable() {
        assertTrue( registry.isSystemPage( "TextFormattingRules" ) );
        assertFalse( registry.isMcpEditable( "TextFormattingRules" ),
            "help/structural system pages stay MCP-write-protected" );
    }

    @Test
    void testIsMcpEditableNullSafe() {
        assertFalse( registry.isMcpEditable( null ) );
    }

    @Test
    void testMcpEditableIsConfigurable() throws Exception {
        final Properties props = new Properties();
        props.setProperty( SystemPageRegistry.PROP_MCP_EDITABLE, "About, SandBox" );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();
        customRegistry.initialize( engine, props );

        assertTrue( customRegistry.isMcpEditable( "About" ) );
        assertTrue( customRegistry.isMcpEditable( "SandBox" ) );
        assertFalse( customRegistry.isMcpEditable( "TextFormattingRules" ) );
    }

    @Test
    void testEmptyMcpEditablePropertyRestoresFullProtection() throws Exception {
        // An explicit empty value opts About back into write-protection.
        final Properties props = new Properties();
        props.setProperty( SystemPageRegistry.PROP_MCP_EDITABLE, "" );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();
        customRegistry.initialize( engine, props );

        assertFalse( customRegistry.isMcpEditable( "About" ),
            "empty property removes the default About exemption" );
    }

    @Test
    void testDiscoveryFromTestResources() {
        // In the test environment, About.md is placed in src/test/resources
        // alongside other .md files. Discovery should enumerate all of them.
        final Set<String> names = registry.getSystemPageNames();
        assertTrue( names.size() >= 2, "Should discover at least About and TextFormattingRules" );
    }

    // -----------------------------------------------------------------------
    // warnOnUnreachableSystemPages — reached only through initialize(); these
    // exercise the null-engine short-circuit, the null-PageManager short-circuit,
    // the per-page defensive catch, and the outer defensive catch. All of these
    // are best-effort logging paths: initialize() must never throw because of them.
    // -----------------------------------------------------------------------

    @Test
    void testInitializeWithNullEngineDoesNotThrow() {
        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

        assertDoesNotThrow( () -> customRegistry.initialize( null, new Properties() ) );
        // Discovery itself does not depend on the engine, so it still runs.
        assertFalse( customRegistry.getSystemPageNames().isEmpty() );
    }

    @Test
    void testInitializeWithNullPageManagerDoesNotThrow() {
        final WikiEngine mockEngine = mock( WikiEngine.class );
        when( mockEngine.getPageSubsystem() ).thenReturn(
                new PageSubsystem.Services( null, null, null, null, null, null, null, null, null ) );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

        assertDoesNotThrow( () -> customRegistry.initialize( mockEngine, new Properties() ) );
    }

    @Test
    void testInitializeSwallowsPerPagePageExistsException() throws Exception {
        final PageManager pageManager = mock( PageManager.class );
        when( pageManager.pageExists( anyString() ) ).thenThrow( new RuntimeException( "provider unavailable" ) );

        final WikiEngine mockEngine = mock( WikiEngine.class );
        when( mockEngine.getPageSubsystem() ).thenReturn(
                new PageSubsystem.Services( pageManager, null, null, null, null, null, null, null, null ) );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

        assertDoesNotThrow( () -> customRegistry.initialize( mockEngine, new Properties() ) );
    }

    @Test
    void testInitializeSwallowsUnexpectedReachabilityCheckException() {
        final WikiEngine mockEngine = mock( WikiEngine.class );
        when( mockEngine.getPageSubsystem() ).thenThrow( new RuntimeException( "subsystem not ready" ) );

        final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

        assertDoesNotThrow( () -> customRegistry.initialize( mockEngine, new Properties() ) );
    }

    // -----------------------------------------------------------------------
    // discoverSystemPages() dispatch branches — these swap the thread's context
    // classloader out from under discovery, then restore it, since that is the
    // only seam the production code reads (Thread.currentThread().getContextClassLoader()).
    // -----------------------------------------------------------------------

    @Test
    void testDiscoverySkippedWhenNoContextClassLoader() {
        final Thread currentThread = Thread.currentThread();
        final ClassLoader original = currentThread.getContextClassLoader();
        try {
            currentThread.setContextClassLoader( null );
            final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

            customRegistry.initialize( null, new Properties() );

            assertTrue( customRegistry.getSystemPageNames().isEmpty(),
                    "No context classloader means discovery cannot run" );
        } finally {
            currentThread.setContextClassLoader( original );
        }
    }

    @Test
    void testDiscoverySkippedWhenAnchorResourceNotFound() {
        final Thread currentThread = Thread.currentThread();
        final ClassLoader original = currentThread.getContextClassLoader();
        try {
            currentThread.setContextClassLoader( new ClassLoader( null ) {
                @Override
                public URL getResource( final String name ) {
                    return null;
                }
            } );
            final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

            customRegistry.initialize( null, new Properties() );

            assertTrue( customRegistry.getSystemPageNames().isEmpty(),
                    "A classloader with no About.md must yield no discovered system pages" );
        } finally {
            currentThread.setContextClassLoader( original );
        }
    }

    @Test
    void testDiscoverySkippedForUnsupportedProtocol() throws Exception {
        final Thread currentThread = Thread.currentThread();
        final ClassLoader original = currentThread.getContextClassLoader();
        try {
            final URL httpUrl = new URL( "http://example.invalid/About.md" );
            currentThread.setContextClassLoader( new ClassLoader( null ) {
                @Override
                public URL getResource( final String name ) {
                    return "About.md".equals( name ) ? httpUrl : null;
                }
            } );
            final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();

            customRegistry.initialize( null, new Properties() );

            assertTrue( customRegistry.getSystemPageNames().isEmpty(),
                    "An anchor resource served over an unsupported protocol yields no system pages" );
        } finally {
            currentThread.setContextClassLoader( original );
        }
    }

    // -----------------------------------------------------------------------
    // discoverFromJar() — the anchor resource resolves to a jar: URL when
    // running from a packaged JAR (the production deployment shape).
    // -----------------------------------------------------------------------

    private static void addJarEntry( final JarOutputStream jos, final String name, final String content ) throws Exception {
        jos.putNextEntry( new JarEntry( name ) );
        if ( content != null ) {
            jos.write( content.getBytes( java.nio.charset.StandardCharsets.UTF_8 ) );
        }
        jos.closeEntry();
    }

    @Test
    void testDiscoveryFromJarEnumeratesSiblingMarkdownFiles() throws Exception {
        final File jarFile = File.createTempFile( "systemPagesTest", ".jar" );
        jarFile.deleteOnExit();
        try ( JarOutputStream jos = new JarOutputStream( new FileOutputStream( jarFile ) ) ) {
            addJarEntry( jos, "About.md", "# About" );
            addJarEntry( jos, "SandBox.md", "# SandBox" );
            addJarEntry( jos, "notes.txt", "not markdown" );
            addJarEntry( jos, "sub/", null );
            addJarEntry( jos, "sub/Nested.md", "# nested — must be excluded, contains '/'" );
        }

        final Thread currentThread = Thread.currentThread();
        final ClassLoader original = currentThread.getContextClassLoader();
        URLClassLoader jarLoader = null;
        try {
            jarLoader = new URLClassLoader( new URL[] { jarFile.toURI().toURL() }, null );
            currentThread.setContextClassLoader( jarLoader );

            final DefaultSystemPageRegistry customRegistry = new DefaultSystemPageRegistry();
            customRegistry.initialize( null, new Properties() );

            final Set<String> names = customRegistry.getSystemPageNames();
            assertTrue( names.contains( "About" ), "About.md must be discovered from the jar" );
            assertTrue( names.contains( "SandBox" ), "SandBox.md must be discovered from the jar" );
            assertFalse( names.contains( "notes" ), "Non-.md entries must be excluded" );
            assertTrue( names.stream().noneMatch( n -> n.contains( "/" ) ),
                    "Nested entries under a subdirectory must be excluded" );
        } finally {
            currentThread.setContextClassLoader( original );
            if ( jarLoader != null ) {
                jarLoader.close();
            }
            jarFile.delete();
        }
    }
}

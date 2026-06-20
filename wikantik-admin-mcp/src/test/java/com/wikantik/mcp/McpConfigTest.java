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
package com.wikantik.mcp;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class McpConfigTest {

    @Test
    void testDefaultProperties() {
        // The no-arg constructor loads from classpath — both main and test resources
        final McpConfig config = new McpConfig();
        assertEquals( "wikantik-mcp", config.serverName() );
        assertEquals( "Wikantik Knowledge Base", config.serverTitle() );
        assertEquals( "2.0.0", config.serverVersion() );
    }

    @Test
    void testInstructionsLoadedFromFile() {
        // The default config points mcp.instructions.file at wikantik-mcp-instructions.txt
        // which is bundled in src/main/resources
        final McpConfig config = new McpConfig();
        final String instructions = config.instructions();
        assertNotNull( instructions, "Instructions should be loaded from file" );
        assertTrue( instructions.contains( "Wikantik" ), "Instructions should mention Wikantik" );
        assertTrue( instructions.contains( "read_page" ), "Instructions should reference tools" );
    }

    @Test
    void testInlineInstructionsFallback() {
        // With the two-stage lookup, an unreadable mcp.instructions.file falls back
        // to the bundled classpath resource — not the mcp.instructions inline property.
        // The inline property is no longer consulted; the bundled resource is used instead.
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "/nonexistent/file/that/does/not/exist.txt" );
        props.setProperty( "mcp.instructions", "Inline instructions here" );

        final McpConfig config = new McpConfig( props );
        final String result = config.instructions();
        assertNotNull( result );
        assertFalse( result.isBlank(), "Should fall back to bundled resource, not inline property" );
    }

    @Test
    void testNonAbsoluteInstructionsFilePathFallsBackToBundledQuietly() {
        // A non-absolute mcp.instructions.file (e.g. a bare filename) is a misconfiguration that
        // resolves against the JVM working dir and always fails. It must be ignored quietly (no
        // startup ERROR — see the isAbsolute() guard) and the bundled instructions served instead.
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "wikantik-mcp-instructions.txt" );

        final McpConfig config = new McpConfig( props );
        final String result = config.instructions();
        assertNotNull( result );
        assertFalse( result.isBlank(), "Should serve the bundled instructions" );
    }

    @Test
    void testCustomServerIdentity() {
        final Properties props = new Properties();
        props.setProperty( "mcp.server.name", "custom-name" );
        props.setProperty( "mcp.server.title", "Custom Title" );
        props.setProperty( "mcp.server.version", "2.0.0" );

        final McpConfig config = new McpConfig( props );
        assertEquals( "custom-name", config.serverName() );
        assertEquals( "Custom Title", config.serverTitle() );
        assertEquals( "2.0.0", config.serverVersion() );
    }

    @Test
    void testNoInstructionsFileReturnsFromBundled() {
        // With the two-stage lookup, empty properties still returns the bundled resource.
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        final String result = config.instructions();
        assertNotNull( result );
        assertFalse( result.isBlank(), "Should return bundled resource when no file is configured" );
    }

    @Test
    void testBlankInstructionsFileFallsBackToBundled() {
        // A blank mcp.instructions.file is treated as absent; bundled resource is loaded.
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "  " );

        final McpConfig config = new McpConfig( props );
        final String result = config.instructions();
        assertNotNull( result );
        assertFalse( result.isBlank(), "Should return bundled resource when file path is blank" );
    }

    @Test
    void testDefaultsWhenPropertiesEmpty() {
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertEquals( "wikantik-mcp", config.serverName() );
        assertNull( config.serverTitle() );
        assertEquals( "1.0.0", config.serverVersion() );
    }

    @Test
    void testAllowedCidrsConfigured() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowedCidrs", "10.0.0.0/8, 192.168.1.0/24" );
        final McpConfig config = new McpConfig( props );
        assertEquals( "10.0.0.0/8, 192.168.1.0/24", config.allowedCidrs() );
    }

    @Test
    void testAllowedCidrsBlankReturnsNull() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.allowedCidrs", "  " );
        final McpConfig config = new McpConfig( props );
        assertNull( config.allowedCidrs() );
    }

    @Test
    void testAllowedCidrsAbsentReturnsNull() {
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertNull( config.allowedCidrs() );
    }

    /**
     * Verifies that constructing McpConfig via the no-arg constructor does not throw
     * a NullPointerException from classloader handling. The class loader of McpConfig
     * should always be non-null in a standard JVM, so the constructor must handle it
     * safely and return a functional config.
     */
    @Test
    void testConstructorHandlesClassLoaderSafely() {
        // The no-arg constructor loads from classpath, exercising the classloader null-check path
        final McpConfig config = new McpConfig();
        assertNotNull( config );
        // Should return defaults without error
        assertNotNull( config.serverName() );
        assertNotNull( config.serverVersion() );
    }

    // --- Rate limit tests ---

    @Test
    void testRateLimitDefaults() {
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertEquals( 0, config.rateLimitGlobal() );
        assertEquals( 0, config.rateLimitPerClient() );
    }

    @Test
    void testRateLimitConfigured() {
        final Properties props = new Properties();
        props.setProperty( "mcp.ratelimit.global", "3" );
        props.setProperty( "mcp.ratelimit.perClient", "1" );
        final McpConfig config = new McpConfig( props );
        assertEquals( 3, config.rateLimitGlobal() );
        assertEquals( 1, config.rateLimitPerClient() );
    }

    @Test
    void testRateLimitInvalidFallsBackToDefault() {
        final Properties props = new Properties();
        props.setProperty( "mcp.ratelimit.global", "not-a-number" );
        props.setProperty( "mcp.ratelimit.perClient", "" );
        final McpConfig config = new McpConfig( props );
        assertEquals( 0, config.rateLimitGlobal() );
        assertEquals( 0, config.rateLimitPerClient() );
    }

    // --- Two-stage lookup tests (Task 5) ---

    @Test
    void instructionsPrefersFileOverrideWhenSpecified( @org.junit.jupiter.api.io.TempDir final java.nio.file.Path tmp ) throws Exception {
        final java.nio.file.Path override = tmp.resolve( "custom-instructions.txt" );
        java.nio.file.Files.writeString( override, "OVERRIDE INSTRUCTIONS" );
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "mcp.instructions.file", override.toString() );

        org.junit.jupiter.api.Assertions.assertEquals(
                "OVERRIDE INSTRUCTIONS", new McpConfig( p ).instructions() );
    }

    @Test
    void instructionsFallsBackToBundledWhenOverrideMissing() {
        final java.util.Properties p = new java.util.Properties();
        p.setProperty( "mcp.instructions.file", "/nonexistent/path/that/does/not/exist.txt" );
        final String result = new McpConfig( p ).instructions();
        org.junit.jupiter.api.Assertions.assertNotNull( result );
        org.junit.jupiter.api.Assertions.assertFalse( result.isBlank(),
                "Should fall back to the bundled classpath resource when override is unreadable" );
    }

    @Test
    void instructionsReturnsBundledResourceByDefault() {
        final String result = new McpConfig( new java.util.Properties() ).instructions();
        org.junit.jupiter.api.Assertions.assertNotNull( result );
        org.junit.jupiter.api.Assertions.assertFalse( result.isBlank(),
                "Default instructions should load from the bundled classpath resource" );
    }
}

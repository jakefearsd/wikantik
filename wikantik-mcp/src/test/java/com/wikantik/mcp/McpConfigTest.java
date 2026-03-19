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

import java.util.List;
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
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "nonexistent-file.txt" );
        props.setProperty( "mcp.instructions", "Inline instructions here" );

        final McpConfig config = new McpConfig( props );
        assertEquals( "Inline instructions here", config.instructions() );
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
    void testNoInstructionsReturnsNull() {
        // Empty properties — no file, no inline
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertNull( config.instructions() );
    }

    @Test
    void testBlankInstructionsFileAndInlineReturnsNull() {
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "  " );
        props.setProperty( "mcp.instructions", "  " );

        final McpConfig config = new McpConfig( props );
        assertNull( config.instructions() );
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
    void testAccessKeyConfigured() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.key", "my-secret-key" );
        final McpConfig config = new McpConfig( props );
        assertEquals( "my-secret-key", config.accessKey() );
    }

    @Test
    void testAccessKeyBlankReturnsNull() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.key", "   " );
        final McpConfig config = new McpConfig( props );
        assertNull( config.accessKey() );
    }

    @Test
    void testAccessKeyAbsentReturnsNull() {
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertNull( config.accessKey() );
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

    // --- Multi-key tests ---

    @Test
    void testAccessKeysMultiple() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "key1, key2, key3" );
        final McpConfig config = new McpConfig( props );
        assertEquals( List.of( "key1", "key2", "key3" ), config.accessKeys() );
    }

    @Test
    void testAccessKeysLegacySingleKey() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.key", "legacy-key" );
        final McpConfig config = new McpConfig( props );
        assertEquals( List.of( "legacy-key" ), config.accessKeys() );
    }

    @Test
    void testAccessKeysBothConfiguredPrefersPlural() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "new1, new2" );
        props.setProperty( "mcp.access.key", "old-key" );
        final McpConfig config = new McpConfig( props );
        assertEquals( List.of( "new1", "new2" ), config.accessKeys() );
    }

    @Test
    void testAccessKeysEmptyReturnsEmptyList() {
        final Properties props = new Properties();
        final McpConfig config = new McpConfig( props );
        assertTrue( config.accessKeys().isEmpty() );
    }

    @Test
    void testAccessKeysBlankReturnsEmptyList() {
        final Properties props = new Properties();
        props.setProperty( "mcp.access.keys", "   " );
        final McpConfig config = new McpConfig( props );
        assertTrue( config.accessKeys().isEmpty() );
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
}

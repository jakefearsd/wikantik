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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigTest {

    // --- defaults from an empty Properties instance ------------------------------

    @Test
    void defaultsApplyWhenPropertiesAreEmpty() {
        final McpConfig config = new McpConfig( new Properties() );

        assertEquals( "wikantik-mcp", config.serverName() );
        assertNull( config.serverTitle() );
        assertEquals( "2.0.0", config.serverVersion() );
        assertEquals( 0, config.rateLimitGlobal() );
        assertEquals( 0, config.rateLimitPerClient() );
        assertNull( config.allowedCidrs() );
        assertFalse( config.allowUnrestricted() );
        assertEquals( 10000, config.rateLimiterMaxClients() );
        assertEquals( 50, config.kgCurationBulkLimit() );
    }

    // --- overrides via explicit Properties ----------------------------------------

    @Test
    void explicitPropertiesOverrideDefaults() {
        final Properties props = new Properties();
        props.setProperty( "mcp.server.name", "custom-name" );
        props.setProperty( "mcp.server.title", "Custom Title" );
        props.setProperty( "mcp.server.version", "9.9.9" );
        final McpConfig config = new McpConfig( props );

        assertEquals( "custom-name", config.serverName() );
        assertEquals( "Custom Title", config.serverTitle() );
        assertEquals( "9.9.9", config.serverVersion() );
    }

    @Test
    void allowedCidrsTrimsWhitespaceAndTreatsBlankAsUnset() {
        final Properties blank = new Properties();
        blank.setProperty( "mcp.access.allowedCidrs", "   " );
        assertNull( new McpConfig( blank ).allowedCidrs() );

        final Properties trimmed = new Properties();
        trimmed.setProperty( "mcp.access.allowedCidrs", "  10.0.0.0/8  " );
        assertEquals( "10.0.0.0/8", new McpConfig( trimmed ).allowedCidrs() );
    }

    @Test
    void allowUnrestrictedRequiresExplicitTrue() {
        final Properties unset = new Properties();
        assertFalse( new McpConfig( unset ).allowUnrestricted() );

        final Properties falseValue = new Properties();
        falseValue.setProperty( "mcp.access.allowUnrestricted", "false" );
        assertFalse( new McpConfig( falseValue ).allowUnrestricted() );

        final Properties trueValue = new Properties();
        trueValue.setProperty( "mcp.access.allowUnrestricted", "true" );
        assertTrue( new McpConfig( trueValue ).allowUnrestricted() );
    }

    // --- integer property parsing: valid, blank, invalid --------------------------

    @Test
    void rateLimitGlobalParsesValidInteger() {
        final Properties props = new Properties();
        props.setProperty( "mcp.ratelimit.global", "25" );
        assertEquals( 25, new McpConfig( props ).rateLimitGlobal() );
    }

    @Test
    void rateLimitGlobalFallsBackToDefaultOnInvalidValue() {
        final Properties props = new Properties();
        props.setProperty( "mcp.ratelimit.global", "not-a-number" );
        assertEquals( 0, new McpConfig( props ).rateLimitGlobal() );
    }

    @Test
    void rateLimitPerClientFallsBackToDefaultOnBlankValue() {
        final Properties props = new Properties();
        props.setProperty( "mcp.ratelimit.perClient", "" );
        assertEquals( 0, new McpConfig( props ).rateLimitPerClient() );
    }

    @Test
    void rateLimiterMaxClientsParsesValidPositiveInteger() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.mcp.rate_limit.max_clients", "500" );
        assertEquals( 500, new McpConfig( props ).rateLimiterMaxClients() );
    }

    @Test
    void rateLimiterMaxClientsFallsBackOnNonPositiveValue() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.mcp.rate_limit.max_clients", "0" );
        assertEquals( 10000, new McpConfig( props ).rateLimiterMaxClients() );

        final Properties negative = new Properties();
        negative.setProperty( "wikantik.mcp.rate_limit.max_clients", "-5" );
        assertEquals( 10000, new McpConfig( negative ).rateLimiterMaxClients() );
    }

    @Test
    void rateLimiterMaxClientsFallsBackOnNonNumericValue() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.mcp.rate_limit.max_clients", "abc" );
        assertEquals( 10000, new McpConfig( props ).rateLimiterMaxClients() );
    }

    @Test
    void kgCurationBulkLimitParsesValidPositiveInteger() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "12" );
        assertEquals( 12, new McpConfig( props ).kgCurationBulkLimit() );
    }

    @Test
    void kgCurationBulkLimitFallsBackOnBlankValue() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.mcp.kg_curation.bulk_limit", "" );
        assertEquals( 50, new McpConfig( props ).kgCurationBulkLimit() );
    }

    // --- instructions() ------------------------------------------------------------

    @Test
    void instructionsLoadsBundledClasspathResourceByDefault() {
        final McpConfig config = new McpConfig( new Properties() );
        final String instructions = config.instructions();
        assertTrue( instructions.contains( "Test-fixture MCP instructions" ), instructions );
    }

    @Test
    void instructionsIsMemoizedAcrossCalls() {
        final McpConfig config = new McpConfig( new Properties() );
        final String first = config.instructions();
        final String second = config.instructions();
        assertEquals( first, second );
    }

    @Test
    void instructionsIgnoresRelativeOverridePathAndUsesBundledResource() {
        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", "relative/path.txt" );
        final McpConfig config = new McpConfig( props );
        assertTrue( config.instructions().contains( "Test-fixture MCP instructions" ) );
    }

    @Test
    void instructionsLoadsAbsoluteOverrideFileWhenReadable( @TempDir final Path tempDir ) throws IOException {
        final Path overrideFile = tempDir.resolve( "custom-instructions.txt" );
        Files.writeString( overrideFile, "Custom override instructions.", StandardCharsets.UTF_8 );

        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", overrideFile.toAbsolutePath().toString() );
        final McpConfig config = new McpConfig( props );

        assertEquals( "Custom override instructions.", config.instructions() );
    }

    @Test
    void instructionsFallsBackToBundledResourceWhenOverrideFileUnreadable( @TempDir final Path tempDir ) {
        final Path missingFile = tempDir.resolve( "does-not-exist.txt" );

        final Properties props = new Properties();
        props.setProperty( "mcp.instructions.file", missingFile.toAbsolutePath().toString() );
        final McpConfig config = new McpConfig( props );

        assertTrue( config.instructions().contains( "Test-fixture MCP instructions" ) );
    }

    // --- no-arg constructor: classpath-based loading -------------------------------

    @Test
    void noArgConstructorLoadsOverrideFromTestClasspathResource() {
        // wikantik-mcp-core/src/test/resources/wikantik-mcp.properties sets this.
        final McpConfig config = new McpConfig();
        assertEquals( "test-mcp-core", config.serverName() );
    }
}

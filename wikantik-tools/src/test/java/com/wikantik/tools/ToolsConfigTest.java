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
package com.wikantik.tools;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ToolsConfigTest {

    @Test
    void accessKeysMultiple() {
        final Properties props = new Properties();
        props.setProperty( "tools.access.keys", "key1, key2, key3" );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( List.of( "key1", "key2", "key3" ), config.accessKeys() );
    }

    @Test
    void accessKeysBlankReturnsEmpty() {
        final Properties props = new Properties();
        props.setProperty( "tools.access.keys", "   " );
        final ToolsConfig config = new ToolsConfig( props );
        assertTrue( config.accessKeys().isEmpty() );
    }

    @Test
    void accessKeysAbsentReturnsEmpty() {
        final ToolsConfig config = new ToolsConfig( new Properties() );
        assertTrue( config.accessKeys().isEmpty() );
    }

    @Test
    void allowedCidrsConfigured() {
        final Properties props = new Properties();
        props.setProperty( "tools.access.allowedCidrs", "10.0.0.0/8, 192.168.1.0/24" );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( "10.0.0.0/8, 192.168.1.0/24", config.allowedCidrs() );
    }

    @Test
    void allowedCidrsAbsentReturnsNull() {
        final ToolsConfig config = new ToolsConfig( new Properties() );
        assertNull( config.allowedCidrs() );
    }

    @Test
    void allowUnrestrictedDefaultsFalse() {
        final ToolsConfig config = new ToolsConfig( new Properties() );
        assertFalse( config.allowUnrestricted() );
    }

    @Test
    void allowUnrestrictedRespectsExplicitTrue() {
        final Properties props = new Properties();
        props.setProperty( "tools.access.allowUnrestricted", "true" );
        final ToolsConfig config = new ToolsConfig( props );
        assertTrue( config.allowUnrestricted() );
    }

    @Test
    void rateLimitDefaults() {
        final ToolsConfig config = new ToolsConfig( new Properties() );
        assertEquals( 0, config.rateLimitGlobal() );
        assertEquals( 0, config.rateLimitPerClient() );
    }

    @Test
    void rateLimitConfigured() {
        final Properties props = new Properties();
        props.setProperty( "tools.ratelimit.global", "3" );
        props.setProperty( "tools.ratelimit.perClient", "1" );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( 3, config.rateLimitGlobal() );
        assertEquals( 1, config.rateLimitPerClient() );
    }

    @Test
    void rateLimitInvalidFallsBackToDefault() {
        final Properties props = new Properties();
        props.setProperty( "tools.ratelimit.global", "not-a-number" );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( 0, config.rateLimitGlobal() );
    }

    @Test
    void publicBaseUrlAbsentReturnsNull() {
        final ToolsConfig config = new ToolsConfig( new Properties() );
        assertNull( config.publicBaseUrl() );
    }

    @Test
    void publicBaseUrlRead() {
        final Properties props = new Properties();
        props.setProperty( "wikantik.public.baseURL", "https://wiki.example.com" );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( "https://wiki.example.com", config.publicBaseUrl() );
    }

}

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

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ToolsConfigTest {

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


    // ---- mutation-driven additions ----

    @Test
    void defaultConstructorLoadsTheShippedDefaultsFromTheClasspath() {
        final ToolsConfig config = new ToolsConfig();
        assertEquals( 100, config.rateLimitGlobal() );
        assertEquals( 10, config.rateLimitPerClient() );
        assertFalse( config.allowUnrestricted() );
        assertNull( config.allowedCidrs() );
        assertNull( config.publicBaseUrl() );
    }

    @Test
    void anOverrideOnTheContextClassLoaderIsOverlaidOnTheDefaults(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir ) throws Exception {
        java.nio.file.Files.writeString( dir.resolve( "wikantik-tools.properties" ),
                "tools.ratelimit.global = 7\ntools.access.allowedCidrs = 10.1.0.0/16\n" );
        final Thread thread = Thread.currentThread();
        final ClassLoader original = thread.getContextClassLoader();
        // null parent: the override loader sees only the override file, so a loader the config skips would not matter
        try( java.net.URLClassLoader override = new java.net.URLClassLoader( new java.net.URL[] { dir.toUri().toURL() }, null ) ) {
            thread.setContextClassLoader( override );
            final ToolsConfig config = new ToolsConfig();
            assertEquals( 7, config.rateLimitGlobal(), "the overlay wins over the shipped default" );
            assertEquals( 10, config.rateLimitPerClient(), "keys the overlay lacks keep the shipped default" );
            assertEquals( "10.1.0.0/16", config.allowedCidrs() );
        } finally {
            thread.setContextClassLoader( original );
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource( { "5,5", "' 12 ',12", "-3,-3", "0,0" } )
    void integerPropertiesAreStrippedAndParsed( final String raw, final int expected ) {
        final Properties props = new Properties();
        props.setProperty( "tools.ratelimit.perClient", raw );
        assertEquals( expected, new ToolsConfig( props ).rateLimitPerClient() );
    }

    @Test
    void blankAndMissingIntegerPropertiesUseTheDefault() {
        final Properties props = new Properties();
        props.setProperty( "tools.ratelimit.global", "   " );
        assertEquals( 0, new ToolsConfig( props ).rateLimitGlobal() );
    }

    @Test
    void stringPropertiesAreStrippedAndBlankMeansUnset() {
        final Properties props = new Properties();
        props.setProperty( "tools.access.allowedCidrs", "  10.0.0.0/8  " );
        props.setProperty( "wikantik.public.baseURL", "  https://w.example  " );
        final ToolsConfig config = new ToolsConfig( props );
        assertEquals( "10.0.0.0/8", config.allowedCidrs() );
        assertEquals( "https://w.example", config.publicBaseUrl() );
        props.setProperty( "tools.access.allowedCidrs", "   " );
        props.setProperty( "wikantik.public.baseURL", "" );
        assertNull( config.allowedCidrs() );
        assertNull( config.publicBaseUrl() );
    }

    @Test
    void allowUnrestrictedIsStrictlyTrueAndNothingElse() {
        for( final String v : new String[] { "false", "yes", "1", "", "TRUE ", " true" } ) {
            final Properties props = new Properties();
            props.setProperty( "tools.access.allowUnrestricted", v );
            final boolean expected = v.strip().equalsIgnoreCase( "true" );
            assertEquals( expected, new ToolsConfig( props ).allowUnrestricted(), "'" + v + "'" );
        }
    }
}

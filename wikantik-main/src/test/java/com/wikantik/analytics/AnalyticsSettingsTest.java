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
package com.wikantik.analytics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsSettingsTest {

    @Test
    void defaultsMatchTheDeclaredFile() {
        final AnalyticsSettings s = AnalyticsSettings.disabled();
        assertEquals( "script.js", s.scriptName() );
        assertEquals( Duration.ofMillis( 5000 ), s.proxyTimeout() );
        assertEquals( 16, s.proxyMaxInflight() );
        assertEquals( "wikantik_ro", s.readbackUser() );
        assertEquals( 3, s.readbackDays() );
        assertEquals( 24, s.readbackHours() );
        assertFalse( s.trackerEnabled() );
        assertFalse( s.readbackEnabled() );
        assertEquals( "", s.trackerTag( "" ) );
    }

    @Test
    void trackerNeedsWebsiteIdAndUpstream() {
        final Properties p = new Properties();
        p.setProperty( AnalyticsSettings.PROP_WEBSITE_ID, "id" );
        assertFalse( AnalyticsSettings.from( p ).trackerEnabled() );
        p.setProperty( AnalyticsSettings.PROP_UPSTREAM, "http://u" );
        assertTrue( AnalyticsSettings.from( p ).trackerEnabled() );
        assertFalse( AnalyticsSettings.from( p ).readbackEnabled() );
        p.setProperty( AnalyticsSettings.PROP_READBACK_URL, "jdbc:postgresql://x/y" );
        assertTrue( AnalyticsSettings.from( p ).readbackEnabled() );
    }

    @Test
    void badIntegerFallsBackToDefault() {
        final Properties p = new Properties();
        p.setProperty( AnalyticsSettings.PROP_READBACK_DAYS, "many" );
        assertEquals( 3, AnalyticsSettings.from( p ).readbackDays() );
    }

    @Test
    void siteHostFallsBackToBaseUrlHost() {
        final Properties p = new Properties();
        assertEquals( "wiki.example.com", AnalyticsSettings.from( p ).resolveSiteHost( "https://wiki.example.com/" ) );
        assertEquals( "", AnalyticsSettings.from( p ).resolveSiteHost( "" ) );
        assertEquals( "", AnalyticsSettings.from( p ).resolveSiteHost( null ) );
        assertEquals( "", AnalyticsSettings.from( p ).resolveSiteHost( "ht tp://bad host" ) );
        p.setProperty( AnalyticsSettings.PROP_SITE_HOST, "h.example" );
        assertEquals( "h.example", AnalyticsSettings.from( p ).resolveSiteHost( "https://wiki.example.com/" ) );
    }

    @Test
    void nullPropertiesReadAsDefaults() {
        assertFalse( AnalyticsSettings.from( null ).trackerEnabled() );
    }

    @Test
    void tagEscapesAttributeValues() {
        final Properties p = new Properties();
        p.setProperty( AnalyticsSettings.PROP_WEBSITE_ID, "a\"b" );
        p.setProperty( AnalyticsSettings.PROP_UPSTREAM, "http://u" );
        assertTrue( AnalyticsSettings.from( p ).trackerTag( "/w" ).contains( "data-website-id=\"a&quot;b\"" ) );
    }
}

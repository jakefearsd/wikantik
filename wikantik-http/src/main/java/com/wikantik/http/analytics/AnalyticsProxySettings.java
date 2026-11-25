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
package com.wikantik.http.analytics;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Properties;

/** Reads the proxy's {@code wikantik.analytics.*} keys into an {@link AnalyticsProxyServlet.AnalyticsProxyConfig}. */
final class AnalyticsProxySettings {

    private static final Logger LOG = LogManager.getLogger( AnalyticsProxySettings.class );

    static final String PROP_WEBSITE_ID = "wikantik.analytics.website.id";
    static final String PROP_UPSTREAM = "wikantik.analytics.upstream";
    static final String PROP_SCRIPT_NAME = "wikantik.analytics.script.name";
    static final String PROP_TIMEOUT_MS = "wikantik.analytics.proxy.timeout.ms";
    static final String PROP_MAX_INFLIGHT = "wikantik.analytics.proxy.max.inflight";
    static final int DEFAULT_TIMEOUT_MS = 5000;
    static final int DEFAULT_MAX_INFLIGHT = 16;

    private AnalyticsProxySettings() { }

    static AnalyticsProxyServlet.AnalyticsProxyConfig parse( final Properties p ) {
        return new AnalyticsProxyServlet.AnalyticsProxyConfig(
                p.getProperty( PROP_UPSTREAM, "" ).trim(),
                scriptName( p ),
                Duration.ofMillis( intProp( p, PROP_TIMEOUT_MS, DEFAULT_TIMEOUT_MS ) ),
                intProp( p, PROP_MAX_INFLIGHT, DEFAULT_MAX_INFLIGHT ),
                p.getProperty( PROP_WEBSITE_ID, "" ).trim() );
    }

    /** A blank value means the default, matching AnalyticsSettings. */
    private static String scriptName( final Properties p ) {
        final String v = p.getProperty( PROP_SCRIPT_NAME, "script.js" ).trim();
        return v.isEmpty() ? "script.js" : v;
    }

    private static int intProp( final Properties p, final String key, final int dflt ) {
        final String v = p.getProperty( key );
        if( v == null || v.isBlank() ) {
            return dflt;
        }
        try {
            return Integer.parseInt( v.trim() );
        } catch( final NumberFormatException e ) {
            LOG.warn( "Ignoring non-numeric value '{}' for {}; using {}: {}", v, key, dflt, e.toString() );
            return dflt;
        }
    }
}

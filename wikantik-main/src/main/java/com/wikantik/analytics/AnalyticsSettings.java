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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.time.Duration;
import java.util.Properties;

/**
 * Reader-analytics (Umami) settings parsed once from the wiki properties. Defaults match
 * {@code ini/wikantik.properties}.
 *
 * @param websiteId        Umami website id; blank turns all analytics off
 * @param upstream         Umami base URL the same-origin proxy forwards to
 * @param scriptName       file name the tracker script is served under
 * @param proxyTimeout     timeout for one proxied request
 * @param proxyMaxInflight cap on proxied requests in flight
 * @param readbackUrl      JDBC URL of Umami's read-only store
 * @param readbackUser     read-back database user
 * @param readbackPassword read-back database password
 * @param readbackDays     trailing days recomputed per read-back run
 * @param readbackHours    hours between read-back runs
 * @param siteHost         explicit site host for rollup rows; blank means derive from the base URL
 */
public record AnalyticsSettings( String websiteId, String upstream, String scriptName, Duration proxyTimeout,
                                 int proxyMaxInflight, String readbackUrl, String readbackUser,
                                 String readbackPassword, int readbackDays, int readbackHours, String siteHost ) {

    private static final Logger LOG = LogManager.getLogger( AnalyticsSettings.class );

    /** The record's default form with the read-back password redacted, so settings can be logged safely. */
    @Override
    public String toString() {
        return "AnalyticsSettings[websiteId=" + websiteId + ", upstream=" + upstream + ", scriptName=" + scriptName
                + ", proxyTimeout=" + proxyTimeout + ", proxyMaxInflight=" + proxyMaxInflight
                + ", readbackUrl=" + readbackUrl + ", readbackUser=" + readbackUser
                + ", readbackPassword=" + ( readbackPassword == null || readbackPassword.isEmpty() ? "" : "***" )
                + ", readbackDays=" + readbackDays + ", readbackHours=" + readbackHours
                + ", siteHost=" + siteHost + "]";
    }

    public static final String PROP_WEBSITE_ID = "wikantik.analytics.website.id";
    public static final String PROP_UPSTREAM = "wikantik.analytics.upstream";
    public static final String PROP_SCRIPT_NAME = "wikantik.analytics.script.name";
    public static final String PROP_PROXY_TIMEOUT_MS = "wikantik.analytics.proxy.timeout.ms";
    public static final String PROP_PROXY_MAX_INFLIGHT = "wikantik.analytics.proxy.max.inflight";
    public static final String PROP_READBACK_URL = "wikantik.analytics.readback.url";
    public static final String PROP_READBACK_USER = "wikantik.analytics.readback.user";
    public static final String PROP_READBACK_PASSWORD = "wikantik.analytics.readback.password";
    public static final String PROP_READBACK_DAYS = "wikantik.analytics.readback.days";
    public static final String PROP_READBACK_HOURS = "wikantik.analytics.readback.hours";
    public static final String PROP_SITE_HOST = "wikantik.analytics.site.host";

    /** Everything off. */
    public static AnalyticsSettings disabled() {
        return from( new Properties() );
    }

    /** A {@code null} property set (an engine that exposes none) reads as all defaults. */
    public static AnalyticsSettings from( final Properties properties ) {
        final Properties p = properties == null ? new Properties() : properties;
        return new AnalyticsSettings(
                str( p, PROP_WEBSITE_ID, "" ),
                str( p, PROP_UPSTREAM, "" ),
                str( p, PROP_SCRIPT_NAME, "script.js" ),
                Duration.ofMillis( intProp( p, PROP_PROXY_TIMEOUT_MS, 5000 ) ),
                intProp( p, PROP_PROXY_MAX_INFLIGHT, 16 ),
                str( p, PROP_READBACK_URL, "" ),
                str( p, PROP_READBACK_USER, "wikantik_ro" ),
                str( p, PROP_READBACK_PASSWORD, "" ),
                intProp( p, PROP_READBACK_DAYS, 3 ),
                intProp( p, PROP_READBACK_HOURS, 24 ),
                str( p, PROP_SITE_HOST, "" ) );
    }

    /** Same rule as the proxy servlet, which answers 404 otherwise: the tag is only useful if the proxy serves it. */
    public boolean trackerEnabled() {
        return !websiteId.isBlank() && !upstream.isBlank();
    }

    public boolean readbackEnabled() {
        return trackerEnabled() && !readbackUrl.isBlank();
    }

    /**
     * The analytics site host for a wiki's properties: the configured host, else the host of
     * {@code wikantik.public.baseURL} (falling back to {@code wikantik.baseURL}).
     */
    public static String siteHostOf( final Properties properties ) {
        if( properties == null ) {
            return "";
        }
        return from( properties ).resolveSiteHost(
                properties.getProperty( "wikantik.public.baseURL", properties.getProperty( "wikantik.baseURL", "" ) ) );
    }

    /** The configured site host, or the host of {@code baseUrl} when blank; {@code ""} when neither is known. */
    public String resolveSiteHost( final String baseUrl ) {
        if( !siteHost.isBlank() ) {
            return siteHost;
        }
        if( baseUrl == null || baseUrl.isBlank() ) {
            return "";
        }
        try {
            final String host = URI.create( baseUrl.trim() ).getHost();
            return host == null ? "" : host;
        } catch( final IllegalArgumentException e ) {
            LOG.warn( "Cannot derive the analytics site host from base URL '{}': {}", baseUrl, e.toString() );
            return "";
        }
    }

    /**
     * The Umami tracker {@code <script>} tag, served through the same-origin proxy under {@code contextPath};
     * {@code ""} when the tracker is not enabled.
     */
    public String trackerTag( final String contextPath ) {
        if( !trackerEnabled() ) {
            return "";
        }
        final String ctx = contextPath == null ? "" : contextPath;
        return "<script defer src=\"" + attr( ctx + "/analytics/" + scriptName ) + "\" data-website-id=\""
                + attr( websiteId ) + "\" data-host-url=\"" + attr( ctx + "/analytics" )
                + "\" data-auto-track=\"false\" data-performance=\"true\"></script>";
    }

    private static String attr( final String s ) {
        return s.replace( "&", "&amp;" ).replace( "\"", "&quot;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
    }

    private static String str( final Properties p, final String key, final String dflt ) {
        final String v = p.getProperty( key );
        return v == null || v.isBlank() ? dflt : v.trim();
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

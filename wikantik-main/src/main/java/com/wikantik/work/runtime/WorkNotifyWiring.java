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
package com.wikantik.work.runtime;

import com.wikantik.api.core.Engine;
import com.wikantik.util.TextUtil;
import com.wikantik.work.EmailDetail;
import com.wikantik.work.NotifySettings;
import com.wikantik.work.WorkMailer;
import com.wikantik.work.WorkNotifyPorts;
import com.wikantik.work.WorkValidationException;
import com.wikantik.work.WorkOutbound;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.Properties;

/**
 * Reads the {@code wikantik.work.notify.*} keys into {@link NotifySettings} and builds the ports the work service
 * is handed. Mail is wired only when email is enabled and outbound HTTP only when webhooks are; otherwise they are the "not wired" ports.
 */
final class WorkNotifyWiring {

    private static final Logger LOG = LogManager.getLogger( WorkNotifyWiring.class );

    /** Seconds between dispatcher runs; 1 to 3600, default 60. */
    static final String PROP_INTERVAL = "wikantik.work.notify.intervalSeconds";

    /** The absolute base URL for links; blank falls back to {@code wikantik.baseURL}. */
    static final String PROP_BASE_URL = "wikantik.work.notify.baseUrl";

    static final String PROP_EMAIL_ENABLED = "wikantik.work.notify.email.enabled";
    static final String PROP_EMAIL_DETAIL = "wikantik.work.notify.email.detail";
    static final String PROP_EMAIL_INTERVAL = "wikantik.work.notify.email.intervalMinutes";
    static final String PROP_EMAIL_DAILY_HOUR = "wikantik.work.notify.email.dailyHour";

    /** Whether webhooks may be added and delivered; default false. */
    static final String PROP_WEBHOOKS_ENABLED = "wikantik.work.notify.webhooks.enabled";

    private static final int DEFAULT_EMAIL_INTERVAL_MINUTES = 5;
    private static final int MAX_EMAIL_INTERVAL_MINUTES = 60;
    private static final int DEFAULT_DAILY_HOUR = 8;
    private static final int LAST_HOUR = 23;
    private static final String PROP_WIKI_BASE_URL = "wikantik.baseURL";
    private static final int DEFAULT_INTERVAL_SECONDS = 60;
    private static final int MAX_INTERVAL_SECONDS = 3600;

    private static final java.util.regex.Pattern HTTP_URL =
        java.util.regex.Pattern.compile( "https?://[^\\s/?#]+[^\\s]*", java.util.regex.Pattern.CASE_INSENSITIVE );

    private WorkNotifyWiring() {
    }

    static NotifySettings settings( final Properties props ) {
        final String baseUrl = baseUrl( props );
        return new NotifySettings( interval( props ), emailEnabled( props, baseUrl ), emailDetail( props ),
            Duration.ofMinutes( boundedInt( props, PROP_EMAIL_INTERVAL, DEFAULT_EMAIL_INTERVAL_MINUTES, 1,
                MAX_EMAIL_INTERVAL_MINUTES ) ),
            boundedInt( props, PROP_EMAIL_DAILY_HOUR, DEFAULT_DAILY_HOUR, 0, LAST_HOUR ), baseUrl,
            TextUtil.getBooleanProperty( props, PROP_WEBHOOKS_ENABLED, false ) );
    }

    static WorkNotifyPorts ports( final Properties props, final Engine engine ) {
        final NotifySettings settings = settings( props );
        return new WorkNotifyPorts( settings.emailEnabled() ? new WikiWorkMailer( engine ) : WorkMailer.NONE,
            settings.webhooksEnabled() ? new WikiWorkOutbound() : WorkOutbound.NONE, settings );
    }

    /** On only when asked for and links can be absolute. */
    private static boolean emailEnabled( final Properties props, final String baseUrl ) {
        if ( !TextUtil.getBooleanProperty( props, PROP_EMAIL_ENABLED, false ) ) {
            return false;
        }
        if ( baseUrl.isEmpty() ) {
            LOG.warn( "{} is true but neither {} nor {} is set, so email links could not be absolute; email stays off",
                PROP_EMAIL_ENABLED, PROP_BASE_URL, PROP_WIKI_BASE_URL );
            return false;
        }
        return true;
    }

    private static EmailDetail emailDetail( final Properties props ) {
        final String value = TextUtil.getStringProperty( props, PROP_EMAIL_DETAIL, "summary" ).strip();
        try {
            return EmailDetail.fromWire( value.toLowerCase( java.util.Locale.ROOT ) );
        } catch ( final WorkValidationException e ) {
            LOG.warn( "{}={} is not summary or full; using summary: {}", PROP_EMAIL_DETAIL, value, e.getMessage() );
            return EmailDetail.SUMMARY;
        }
    }

    private static int boundedInt( final Properties props, final String key, final int fallback, final int min,
                                   final int max ) {
        final int value = TextUtil.getIntegerProperty( props, key, fallback );
        if ( value < min || value > max ) {
            LOG.warn( "{}={} is outside {}..{}; using {}", key, value, min, max, fallback );
            return fallback;
        }
        return value;
    }

    private static Duration interval( final Properties props ) {
        final int seconds = TextUtil.getIntegerProperty( props, PROP_INTERVAL, DEFAULT_INTERVAL_SECONDS );
        if ( seconds < 1 || seconds > MAX_INTERVAL_SECONDS ) {
            LOG.warn( "{}={} is outside 1..{}; using {} seconds", PROP_INTERVAL, seconds, MAX_INTERVAL_SECONDS,
                DEFAULT_INTERVAL_SECONDS );
            return Duration.ofSeconds( DEFAULT_INTERVAL_SECONDS );
        }
        return Duration.ofSeconds( seconds );
    }

    /**
     * The configured base URL, else the wiki's, without a trailing slash; blank when neither is set. A value that is not
     * an http or https address is logged and ignored (so no links, and email stays off), never put into a message.
     */
    private static String baseUrl( final Properties props ) {
        final String own = usableUrl( props, PROP_BASE_URL );
        return own.isEmpty() ? usableUrl( props, PROP_WIKI_BASE_URL ) : own;
    }

    private static String usableUrl( final Properties props, final String key ) {
        String url = TextUtil.getStringProperty( props, key, "" ).strip();
        while ( url.endsWith( "/" ) ) {
            url = url.substring( 0, url.length() - 1 );
        }
        if ( !url.isEmpty() && !HTTP_URL.matcher( url ).matches() ) {
            LOG.warn( "{} is not an http or https address, so it is ignored for work notification links", key );
            return "";
        }
        return url;
    }
}

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

import com.wikantik.work.NotifySettings;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkNotifyWiringTest {

    @Test
    void defaultsAreOff() {
        final NotifySettings s = WorkNotifyWiring.settings( new Properties() );
        assertEquals( Duration.ofSeconds( 60 ), s.dispatchInterval() );
        assertFalse( s.emailEnabled() );
        assertFalse( s.webhooksEnabled() );
        assertEquals( "", s.baseUrl() );
    }

    @Test
    void aBaseUrlThatIsNotHttpIsIgnored() {
        final Properties p = new Properties();
        for ( final String bad : new String[] { "javascript:alert(1)", "wiki.example.com", "ftp://wiki.example.com", "//wiki.example.com", "https://" } ) {
            p.setProperty( WorkNotifyWiring.PROP_BASE_URL, bad );
            assertEquals( "", WorkNotifyWiring.settings( p ).baseUrl(), bad );
            p.setProperty( WorkNotifyWiring.PROP_EMAIL_ENABLED, "true" );
            assertFalse( WorkNotifyWiring.settings( p ).emailEnabled(), "no usable base URL: email stays off" );
        }
        p.setProperty( "wikantik.baseURL", "https://wiki.example.com/" );
        assertEquals( "https://wiki.example.com", WorkNotifyWiring.settings( p ).baseUrl(), "falls back to a good wiki URL" );
        p.setProperty( WorkNotifyWiring.PROP_BASE_URL, "HTTP://work.example.com:8080/x/" );
        assertEquals( "HTTP://work.example.com:8080/x", WorkNotifyWiring.settings( p ).baseUrl() );
    }

    @Test
    void theMailerIsWiredOnlyWhenEmailIsEnabled() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.baseURL", "https://wiki.example.com" );
        assertEquals( com.wikantik.work.WorkMailer.NONE, WorkNotifyWiring.ports( p, null ).mailer() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_ENABLED, "true" );
        assertEquals( WikiWorkMailer.class, WorkNotifyWiring.ports( p, null ).mailer().getClass() );
    }

    @Test
    void webhooksDefaultOff() {
        assertFalse( WorkNotifyWiring.settings( new Properties() ).webhooksEnabled() );
        final Properties p = new Properties();
        p.setProperty( WorkNotifyWiring.PROP_WEBHOOKS_ENABLED, "true" );
        assertEquals( true, WorkNotifyWiring.settings( p ).webhooksEnabled() );
    }

    @Test
    void theOutboundPortIsWiredOnlyWhenWebhooksAreOn() {
        final Properties p = new Properties();
        assertEquals( com.wikantik.work.WorkOutbound.NONE, WorkNotifyWiring.ports( p, null ).outbound() );
        p.setProperty( WorkNotifyWiring.PROP_WEBHOOKS_ENABLED, "true" );
        assertEquals( WikiWorkOutbound.class, WorkNotifyWiring.ports( p, null ).outbound().getClass() );
    }

    @Test
    void anIntervalOutOfRangeFallsBackTo60() {
        for ( final String bad : new String[] { "0", "-5", "3601", "100000" } ) {
            final Properties p = new Properties();
            p.setProperty( WorkNotifyWiring.PROP_INTERVAL, bad );
            assertEquals( Duration.ofSeconds( 60 ), WorkNotifyWiring.settings( p ).dispatchInterval(), bad );
        }
        final Properties ok = new Properties();
        ok.setProperty( WorkNotifyWiring.PROP_INTERVAL, "2" );
        assertEquals( Duration.ofSeconds( 2 ), WorkNotifyWiring.settings( ok ).dispatchInterval() );
        ok.setProperty( WorkNotifyWiring.PROP_INTERVAL, "3600" );
        assertEquals( Duration.ofSeconds( 3600 ), WorkNotifyWiring.settings( ok ).dispatchInterval() );
    }

    @Test
    void theBaseUrlFallsBackToTheWikisAndLosesItsTrailingSlash() {
        final Properties p = new Properties();
        p.setProperty( "wikantik.baseURL", "https://wiki.example.com/" );
        assertEquals( "https://wiki.example.com", WorkNotifyWiring.settings( p ).baseUrl() );
        p.setProperty( WorkNotifyWiring.PROP_BASE_URL, "https://work.example.com//" );
        assertEquals( "https://work.example.com", WorkNotifyWiring.settings( p ).baseUrl(), "its own key wins" );
        p.setProperty( WorkNotifyWiring.PROP_BASE_URL, "  " );
        assertEquals( "https://wiki.example.com", WorkNotifyWiring.settings( p ).baseUrl(), "blank is unset" );
    }

    @Test
    void emailNeedsABaseUrl() {
        final Properties p = new Properties();
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_ENABLED, "true" );
        assertFalse( WorkNotifyWiring.settings( p ).emailEnabled(), "no base URL: off" );
        p.setProperty( "wikantik.baseURL", "https://wiki.example.com/" );
        assertEquals( true, WorkNotifyWiring.settings( p ).emailEnabled() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_ENABLED, "false" );
        assertFalse( WorkNotifyWiring.settings( p ).emailEnabled() );
    }

    @Test
    void detailFallsBackToSummary() {
        final Properties p = new Properties();
        assertEquals( com.wikantik.work.EmailDetail.SUMMARY, WorkNotifyWiring.settings( p ).emailDetail() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_DETAIL, "full" );
        assertEquals( com.wikantik.work.EmailDetail.FULL, WorkNotifyWiring.settings( p ).emailDetail() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_DETAIL, "everything" );
        assertEquals( com.wikantik.work.EmailDetail.SUMMARY, WorkNotifyWiring.settings( p ).emailDetail() );
    }

    @Test
    void theDailyHourIsBounded() {
        final Properties p = new Properties();
        for ( final String bad : new String[] { "-1", "24", "99" } ) {
            p.setProperty( WorkNotifyWiring.PROP_EMAIL_DAILY_HOUR, bad );
            assertEquals( 8, WorkNotifyWiring.settings( p ).dailyHour(), bad );
        }
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_DAILY_HOUR, "0" );
        assertEquals( 0, WorkNotifyWiring.settings( p ).dailyHour() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_DAILY_HOUR, "23" );
        assertEquals( 23, WorkNotifyWiring.settings( p ).dailyHour() );
    }

    @Test
    void theEmailIntervalIsBounded() {
        final Properties p = new Properties();
        assertEquals( Duration.ofMinutes( 5 ), WorkNotifyWiring.settings( p ).emailInterval() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_INTERVAL, "61" );
        assertEquals( Duration.ofMinutes( 5 ), WorkNotifyWiring.settings( p ).emailInterval() );
        p.setProperty( WorkNotifyWiring.PROP_EMAIL_INTERVAL, "10" );
        assertEquals( Duration.ofMinutes( 10 ), WorkNotifyWiring.settings( p ).emailInterval() );
    }

    @Test
    void retentionDefaultsTo180DaysAndRefusesNonsense() {
        assertEquals( 180, WorkNotifyWiring.settings( new Properties() ).retentionDays() );
        final Properties p = new Properties();
        p.setProperty( WorkNotifyWiring.PROP_RETENTION_DAYS, "0" );
        assertEquals( 0, WorkNotifyWiring.settings( p ).retentionDays(), "0 keeps for ever" );
        p.setProperty( WorkNotifyWiring.PROP_RETENTION_DAYS, "30" );
        assertEquals( 30, WorkNotifyWiring.settings( p ).retentionDays() );
        p.setProperty( WorkNotifyWiring.PROP_RETENTION_DAYS, "-5" );
        assertEquals( 180, WorkNotifyWiring.settings( p ).retentionDays() );
    }
}

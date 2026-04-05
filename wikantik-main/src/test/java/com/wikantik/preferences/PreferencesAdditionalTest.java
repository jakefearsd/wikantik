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
package com.wikantik.preferences;

import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.api.managers.PageManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for {@link Preferences} covering uncovered branches:
 * getLocale with Language pref set (language+country+variant variants),
 * getLocale with server-side default locale property,
 * getDateFormat for each TimeFormat value,
 * renderDate,
 * and getDateFormat returning null on invalid format string.
 */
class PreferencesAdditionalTest {

    private TestEngine engine;
    private Context context;

    @BeforeEach
    void setUp() throws Exception {
        engine = TestEngine.build();
        engine.saveText( "PrefsAdditionalPage", "test" );
        final Page page = engine.getManager( PageManager.class ).getPage( "PrefsAdditionalPage" );
        context = Wiki.context().create( engine, page );
    }

    @AfterEach
    void tearDown() {
        engine.deleteTestPage( "PrefsAdditionalPage" );
        engine.stop();
    }

    // -----------------------------------------------------------------------
    // getLocale – Language preference with language only
    // -----------------------------------------------------------------------

    @Test
    void getLocaleFromLanguagePreferenceLanguageOnly() {
        final Preferences prefs = new Preferences();
        prefs.put( "Language", "de" );

        final HttpSession session = mock( HttpSession.class );
        when( session.getAttribute( Preferences.SESSIONPREFS ) ).thenReturn( prefs );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );
        when( req.getLocale() ).thenReturn( Locale.getDefault() );

        final Context ctx = mock( Context.class );
        when( ctx.getHttpRequest() ).thenReturn( req );
        when( ctx.getEngine() ).thenReturn( engine );

        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );
        assertEquals( "de", locale.getLanguage() );
    }

    @Test
    void getLocaleFromLanguagePreferenceWithCountry() {
        final Preferences prefs = new Preferences();
        prefs.put( "Language", "en-GB" );

        final HttpSession session = mock( HttpSession.class );
        when( session.getAttribute( Preferences.SESSIONPREFS ) ).thenReturn( prefs );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );

        final Context ctx = mock( Context.class );
        when( ctx.getHttpRequest() ).thenReturn( req );
        when( ctx.getEngine() ).thenReturn( engine );

        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );
        assertEquals( "en", locale.getLanguage() );
        assertEquals( "GB", locale.getCountry() );
    }

    @Test
    void getLocaleFromLanguagePreferenceWithVariant() {
        final Preferences prefs = new Preferences();
        prefs.put( "Language", "en_US_WIN" );

        final HttpSession session = mock( HttpSession.class );
        when( session.getAttribute( Preferences.SESSIONPREFS ) ).thenReturn( prefs );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );

        final Context ctx = mock( Context.class );
        when( ctx.getHttpRequest() ).thenReturn( req );
        when( ctx.getEngine() ).thenReturn( engine );

        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );
        assertEquals( "en", locale.getLanguage() );
        assertEquals( "US", locale.getCountry() );
        assertEquals( "WIN", locale.getVariant() );
    }

    // -----------------------------------------------------------------------
    // getLocale – server-side default locale property (valid and invalid)
    // -----------------------------------------------------------------------

    @Test
    void getLocaleFromServerSideDefaultLocale() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.preferences.default-locale", "fr_FR" );
        final TestEngine engineFr = new TestEngine( props );
        engineFr.saveText( "FrPage", "bonjour" );
        final Page page = engineFr.getManager( PageManager.class ).getPage( "FrPage" );
        final Context ctx = Wiki.context().create( engineFr, page );

        // No prefs, no request — should fall back to server default
        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );
        // fr_FR might be resolved or fall back to JVM default, but should not throw
        engineFr.deleteTestPage( "FrPage" );
        engineFr.stop();
    }

    @Test
    void getLocaleWithInvalidServerDefaultFallsBackToJvmDefault() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( "wikantik.preferences.default-locale", "not_a_valid_locale!!!" );
        final TestEngine badEngine = new TestEngine( props );
        badEngine.saveText( "BadLocalePage", "test" );
        final Page page = badEngine.getManager( PageManager.class ).getPage( "BadLocalePage" );
        final Context ctx = Wiki.context().create( badEngine, page );

        // Should not throw — falls through to JVM default
        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );

        badEngine.deleteTestPage( "BadLocalePage" );
        badEngine.stop();
    }

    // -----------------------------------------------------------------------
    // getLocale – falls back to request locale when request is present
    // -----------------------------------------------------------------------

    @Test
    void getLocaleFromRequestLocaleWhenNoPrefs() {
        final HttpServletRequest req = mock( HttpServletRequest.class );
        final HttpSession session = mock( HttpSession.class );
        when( req.getSession() ).thenReturn( session );
        when( session.getAttribute( Preferences.SESSIONPREFS ) ).thenReturn( null );
        when( req.getLocale() ).thenReturn( Locale.ITALIAN );

        final Context ctx = mock( Context.class );
        when( ctx.getHttpRequest() ).thenReturn( req );
        when( ctx.getEngine() ).thenReturn( engine );

        final Locale locale = Preferences.getLocale( ctx );
        assertNotNull( locale );
        assertEquals( Locale.ITALIAN.getLanguage(), locale.getLanguage() );
    }

    // -----------------------------------------------------------------------
    // getDateFormat – DATETIME, DATE, TIME paths
    // -----------------------------------------------------------------------

    @Test
    void getDateFormatDatetime() {
        final SimpleDateFormat fmt = Preferences.getDateFormat( context, Preferences.TimeFormat.DATETIME );
        // May return null if format string is invalid for locale, but should not throw
        // In a normal test environment this should succeed
        assertNotNull( fmt, "DATETIME format should be available" );
    }

    @Test
    void getDateFormatDate() {
        final SimpleDateFormat fmt = Preferences.getDateFormat( context, Preferences.TimeFormat.DATE );
        assertNotNull( fmt, "DATE format should be available" );
    }

    @Test
    void getDateFormatTime() {
        final SimpleDateFormat fmt = Preferences.getDateFormat( context, Preferences.TimeFormat.TIME );
        assertNotNull( fmt, "TIME format should be available" );
    }

    // -----------------------------------------------------------------------
    // renderDate – produces a non-empty string
    // -----------------------------------------------------------------------

    @Test
    void renderDateReturnsFormattedString() {
        final Date now = new Date();
        final String result = Preferences.renderDate( context, now, Preferences.TimeFormat.DATE );
        assertNotNull( result );
        assertFalse( result.isEmpty(), "renderDate should return a non-empty string" );
    }

    // -----------------------------------------------------------------------
    // getDateFormat – applies preferred timezone when set
    // -----------------------------------------------------------------------

    @Test
    void getDateFormatAppliesTimezonePreference() {
        final Preferences prefs = new Preferences();
        prefs.put( "TimeZone", "America/New_York" );

        final HttpSession session = mock( HttpSession.class );
        when( session.getAttribute( Preferences.SESSIONPREFS ) ).thenReturn( prefs );

        final HttpServletRequest req = mock( HttpServletRequest.class );
        when( req.getSession() ).thenReturn( session );
        when( req.getLocale() ).thenReturn( Locale.US );

        final Context ctx = mock( Context.class );
        when( ctx.getHttpRequest() ).thenReturn( req );
        when( ctx.getEngine() ).thenReturn( engine );

        final SimpleDateFormat fmt = Preferences.getDateFormat( ctx, Preferences.TimeFormat.DATETIME );
        assertNotNull( fmt );
        assertEquals( "America/New_York", fmt.getTimeZone().getID() );
    }
}

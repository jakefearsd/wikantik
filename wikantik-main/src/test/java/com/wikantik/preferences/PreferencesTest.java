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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for Preferences class.
 */
class PreferencesTest {

    private TestEngine testEngine;
    private Context wikiContext;
    private Page testPage;

    @BeforeEach
    void setUp() throws Exception {
        testEngine = TestEngine.build();
        testEngine.saveText( "PreferencesTestPage", "Test content for preferences" );
        testPage = testEngine.getManager( PageManager.class ).getPage( "PreferencesTestPage" );
        wikiContext = Wiki.context().create( testEngine, testPage );
    }

    @AfterEach
    void tearDown() {
        testEngine.deleteTestPage( "PreferencesTestPage" );
        testEngine.stop();
    }

    @Test
    void testGetPreferenceReturnsNullWhenNoRequest() {
        final Context ctx = mock( Context.class );
        doReturn( null ).when( ctx ).getHttpRequest();

        final String result = Preferences.getPreference( ctx, "SkinName" );
        assertNull( result, "Should return null when no request available" );
    }

    @Test
    void testGetPreferenceReturnsNullWhenNoPreferencesInSession() {
        final HttpServletRequest mockRequest = mock( HttpServletRequest.class );
        final HttpSession mockSession = mock( HttpSession.class );
        doReturn( mockSession ).when( mockRequest ).getSession();
        doReturn( null ).when( mockSession ).getAttribute( Preferences.SESSIONPREFS );

        final Context ctx = mock( Context.class );
        doReturn( mockRequest ).when( ctx ).getHttpRequest();

        final String result = Preferences.getPreference( ctx, "SkinName" );
        assertNull( result, "Should return null when preferences not loaded" );
    }

    @Test
    void testGetPreferenceReturnsValueWhenPresent() {
        // Manually populate session with a Preferences object
        final Preferences prefs = new Preferences();
        prefs.put( "SkinName", "TestSkin" );

        final HttpSession mockSession = mock( HttpSession.class );
        doReturn( prefs ).when( mockSession ).getAttribute( Preferences.SESSIONPREFS );

        final HttpServletRequest mockRequest = mock( HttpServletRequest.class );
        doReturn( mockSession ).when( mockRequest ).getSession();

        final Context ctx = mock( Context.class );
        doReturn( mockRequest ).when( ctx ).getHttpRequest();

        assertEquals( "TestSkin", Preferences.getPreference( ctx, "SkinName" ) );
    }

    @Test
    void testGetLocaleReturnsDefaultWhenNoPreferences() {
        // getLocale should fall back to JVM default when no prefs/request are available
        final Locale locale = Preferences.getLocale( wikiContext );
        assertNotNull( locale, "getLocale should always return a non-null Locale" );
    }

}

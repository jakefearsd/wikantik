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

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.jsp.PageContext;
import com.wikantik.TestEngine;
import com.wikantik.api.core.Context;
import com.wikantik.api.core.Page;
import com.wikantik.api.spi.Wiki;
import com.wikantik.pages.PageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


/**
 * Unit tests for Preferences class, particularly focused on session caching behavior.
 */
class PreferencesTest {

    private TestEngine testEngine;
    private PageContext mockPageContext;
    private HttpSession mockSession;
    private HttpServletRequest mockRequest;
    private ServletContext mockServletContext;
    private Context wikiContext;
    private Page testPage;

    // Storage maps for mock session, servlet context, and request attributes
    private Map<String, Object> sessionAttributes;
    private Map<String, Object> servletContextAttributes;
    private Map<String, Object> requestAttributes;

    @BeforeEach
    void setUp() throws Exception {
        testEngine = TestEngine.build();

        // Create a test page
        testEngine.saveText("PreferencesTestPage", "Test content for preferences");
        testPage = testEngine.getManager(PageManager.class).getPage("PreferencesTestPage");

        // Create wiki context
        wikiContext = Wiki.context().create(testEngine, testPage);

        // Initialize attribute storage maps
        sessionAttributes = new ConcurrentHashMap<>();
        servletContextAttributes = new ConcurrentHashMap<>();
        requestAttributes = new ConcurrentHashMap<>();

        // Set up mock ServletContext with attribute storage
        mockServletContext = mock(ServletContext.class);
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            if (value == null) {
                servletContextAttributes.remove(name);
            } else {
                servletContextAttributes.put(name, value);
            }
            return null;
        }).when(mockServletContext).setAttribute(anyString(), any());

        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return servletContextAttributes.get(name);
        }).when(mockServletContext).getAttribute(anyString());

        // Set up mock HttpSession with attribute storage
        mockSession = mock(HttpSession.class);
        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            if (value == null) {
                sessionAttributes.remove(name);
            } else {
                sessionAttributes.put(name, value);
            }
            return null;
        }).when(mockSession).setAttribute(anyString(), any());

        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return sessionAttributes.get(name);
        }).when(mockSession).getAttribute(anyString());

        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            sessionAttributes.remove(name);
            return null;
        }).when(mockSession).removeAttribute(anyString());

        doReturn("test-session-id").when(mockSession).getId();
        doReturn(mockServletContext).when(mockSession).getServletContext();

        // Set up mock HttpServletRequest with attribute storage
        mockRequest = mock(HttpServletRequest.class);
        doReturn(mockSession).when(mockRequest).getSession();
        doReturn(mockSession).when(mockRequest).getSession(anyBoolean());
        doReturn(mockServletContext).when(mockRequest).getServletContext();
        doReturn(null).when(mockRequest).getCookies(); // No cookies

        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Object value = invocation.getArgument(1);
            if (value == null) {
                requestAttributes.remove(name);
            } else {
                requestAttributes.put(name, value);
            }
            return null;
        }).when(mockRequest).setAttribute(anyString(), any());

        doAnswer(invocation -> {
            String name = invocation.getArgument(0);
            return requestAttributes.get(name);
        }).when(mockRequest).getAttribute(anyString());

        // Store the wikiContext in request attributes (used by Context.findContext)
        requestAttributes.put(Context.ATTR_CONTEXT, wikiContext);

        // Set up mock PageContext
        mockPageContext = mock(PageContext.class);
        doReturn(mockServletContext).when(mockPageContext).getServletContext();
        doReturn(mockSession).when(mockPageContext).getSession();
        doReturn(mockRequest).when(mockPageContext).getRequest();
        doReturn(wikiContext).when(mockPageContext).getAttribute(Context.ATTR_CONTEXT, PageContext.REQUEST_SCOPE);
    }

    @AfterEach
    void tearDown() {
        testEngine.deleteTestPage("PreferencesTestPage");
        testEngine.stop();
        sessionAttributes.clear();
        servletContextAttributes.clear();
        requestAttributes.clear();
    }

    // =========================================================================
    // Tests for session caching behavior
    // =========================================================================

    /**
     * This test verifies that setupPreferences does NOT reload preferences when
     * they are already present in the session. This is the key performance
     * optimization - we should not call PropertyReader.loadWebAppProps() on
     * every single request.
     *
     * With the current buggy code (session check commented out), this test FAILS
     * because each call to setupPreferences creates a new Preferences object.
     *
     * After the fix, this test should PASS because the second call will see
     * that SESSIONPREFS already exists and skip reloading.
     */
    @Test
    void testSetupPreferencesDoesNotReloadWhenAlreadyInSession() {
        // First call - should load preferences into session
        Preferences.setupPreferences(mockPageContext);

        Preferences firstPrefs = (Preferences) sessionAttributes.get(Preferences.SESSIONPREFS);
        assertNotNull(firstPrefs, "First call should create preferences in session");

        // Add a marker to the preferences to verify identity
        firstPrefs.put("TestMarker", "OriginalValue");

        // Second call - should NOT replace the existing preferences
        Preferences.setupPreferences(mockPageContext);

        Preferences secondPrefs = (Preferences) sessionAttributes.get(Preferences.SESSIONPREFS);
        assertNotNull(secondPrefs, "Second call should still have preferences in session");

        // KEY ASSERTION: The preferences instance should be the SAME object
        // With the bug: This fails because reloadPreferences creates a new instance
        // With the fix: This passes because we skip reloading when prefs exist
        assertSame(firstPrefs, secondPrefs,
            "setupPreferences should NOT replace existing preferences in session - " +
            "this is a performance bug causing properties to be reloaded on every request");

        // Also verify our marker is still there (proves same instance)
        assertEquals("OriginalValue", secondPrefs.get("TestMarker"),
            "Original preference values should be preserved");
    }

    /**
     * Verifies that setupPreferences DOES load preferences when the session
     * is empty (first request). This should pass with both buggy and fixed code.
     */
    @Test
    void testSetupPreferencesLoadsWhenNotInSession() {
        // Verify session is initially empty
        assertNull(sessionAttributes.get(Preferences.SESSIONPREFS),
            "Session should initially have no preferences");

        // Call setupPreferences
        Preferences.setupPreferences(mockPageContext);

        // Verify preferences were loaded
        Preferences prefs = (Preferences) sessionAttributes.get(Preferences.SESSIONPREFS);
        assertNotNull(prefs, "Preferences should be loaded into session on first call");

        // Verify some default preferences are set
        assertNotNull(prefs.get("SkinName"), "SkinName preference should be set");
        assertNotNull(prefs.get("DateFormat"), "DateFormat preference should be set");
        assertNotNull(prefs.get("TimeZone"), "TimeZone preference should be set");
    }

    /**
     * Verifies that multiple calls don't cause excessive object creation.
     * We call setupPreferences 10 times and verify the same instance is used.
     */
    @Test
    void testSetupPreferencesMultipleCallsUseSameInstance() {
        // First call
        Preferences.setupPreferences(mockPageContext);
        Preferences firstPrefs = (Preferences) sessionAttributes.get(Preferences.SESSIONPREFS);

        // Multiple subsequent calls
        for (int i = 0; i < 10; i++) {
            Preferences.setupPreferences(mockPageContext);
        }

        Preferences finalPrefs = (Preferences) sessionAttributes.get(Preferences.SESSIONPREFS);

        // Should still be the same instance
        assertSame(firstPrefs, finalPrefs,
            "Multiple calls to setupPreferences should not create new Preferences instances");
    }

    // =========================================================================
    // Tests for getPreference methods
    // =========================================================================

    @Test
    void testGetPreferenceFromPageContext() {
        // Setup preferences
        Preferences.setupPreferences(mockPageContext);

        // Should be able to retrieve preferences
        String skinName = Preferences.getPreference(mockPageContext, "SkinName");
        assertNotNull(skinName, "Should be able to retrieve SkinName preference");
    }

    @Test
    void testGetPreferenceFromWikiContext() {
        // Setup preferences first
        Preferences.setupPreferences(mockPageContext);

        // Create a wiki context with the mock request
        Context ctx = mock(Context.class);
        doReturn(mockRequest).when(ctx).getHttpRequest();

        String skinName = Preferences.getPreference(ctx, "SkinName");
        assertNotNull(skinName, "Should be able to retrieve SkinName preference from WikiContext");
    }

    @Test
    void testGetPreferenceReturnsNullWhenNoSession() {
        Context ctx = mock(Context.class);
        doReturn(null).when(ctx).getHttpRequest();

        String result = Preferences.getPreference(ctx, "SkinName");
        assertNull(result, "Should return null when no request available");
    }

    @Test
    void testGetPreferenceReturnsNullWhenNoPreferences() {
        // Don't call setupPreferences - session has no preferences
        String result = Preferences.getPreference(mockPageContext, "SkinName");
        assertNull(result, "Should return null when preferences not loaded");
    }

}

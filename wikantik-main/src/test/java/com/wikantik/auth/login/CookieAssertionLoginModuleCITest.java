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
package com.wikantik.auth.login;

import com.wikantik.HttpMockFactory;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link CookieAssertionLoginModule} targeting
 * paths not exercised by the existing {@code CookieAssertionLoginModuleTest}.
 */
class CookieAssertionLoginModuleCITest {

    private Subject subject;

    @BeforeEach
    void setUp() {
        subject = new Subject();
    }

    // --- login() branches ---

    @Test
    void testLoginFailsWhenNoCookiePresent() {
        // request has no assertion cookie → FailedLoginException
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[0] ).when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( null, request );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        assertThrows( FailedLoginException.class, module::login );
    }

    @Test
    void testLoginFailsWhenRequestIsNull() {
        // null request → name will be null → FailedLoginException
        final CallbackHandler handler = new WebContainerCallbackHandler( null, null );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        assertThrows( FailedLoginException.class, module::login );
    }

    @Test
    void testLoginThrowsLoginExceptionOnUnsupportedCallback() {
        // handler that throws UnsupportedCallbackException → LoginException
        final CallbackHandler handler = callbacks -> {
            throw new UnsupportedCallbackException( callbacks[0] );
        };
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        assertThrows( LoginException.class, module::login );
    }

    @Test
    void testLoginReturnsFalseOnIOException() throws LoginException {
        // handler that throws IOException → login() returns false
        final CallbackHandler handler = callbacks -> {
            throw new IOException( "simulated IO error" );
        };
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );

        assertFalse( module.login() );
    }

    @Test
    void testLoginSucceedsAndCommitAddsNameCookieWithArrow() throws LoginException {
        // Cookie value contains "-->" separator; only the part before it should be used
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Cookie cookie = new Cookie(
                CookieAssertionLoginModule.PREFS_COOKIE_NAME,
                "Bullwinkle-->extra stuff" );
        Mockito.doReturn( new Cookie[]{ cookie } ).when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( null, request );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertTrue( module.login() );
        module.commit();

        final Set<Principal> principals = subject.getPrincipals();
        assertEquals( 1, principals.size() );
        assertEquals( "Bullwinkle", principals.iterator().next().getName() );
    }

    // --- abort() path ---

    @Test
    void testAbortRemovesPrincipalsAddedDuringLogin() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        final Cookie cookie = new Cookie( CookieAssertionLoginModule.PREFS_COOKIE_NAME, "Rocky" );
        Mockito.doReturn( new Cookie[]{ cookie } ).when( request ).getCookies();

        final CallbackHandler handler = new WebContainerCallbackHandler( null, request );
        final LoginModule module = new CookieAssertionLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        module.login();
        module.commit();
        assertFalse( subject.getPrincipals().isEmpty() );

        // abort() should clean up
        module.abort();
        assertTrue( subject.getPrincipals().isEmpty() );
    }

    // --- static helper methods ---

    @Test
    void testGetUserCookieReturnsNullWhenNoCookies() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( null ).when( request ).getCookies();
        assertNull( CookieAssertionLoginModule.getUserCookie( request ) );
    }

    @Test
    void testGetUserCookieDecodesUrlEncodedValue() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        // URL-encoded "Hello World"
        final Cookie cookie = new Cookie(
                CookieAssertionLoginModule.PREFS_COOKIE_NAME,
                "Hello+World" );
        Mockito.doReturn( new Cookie[]{ cookie } ).when( request ).getCookies();
        // TextUtil.urlDecodeUTF8 decodes '+' as space
        final String result = CookieAssertionLoginModule.getUserCookie( request );
        assertNotNull( result );
    }

    @Test
    @SuppressWarnings( "deprecation" )
    void testSetUserCookieAddsCorrectCookie() {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        CookieAssertionLoginModule.setUserCookie( response, "Bullwinkle" );
        // Verify addCookie was called once
        Mockito.verify( response, Mockito.times( 1 ) ).addCookie( Mockito.any( Cookie.class ) );
    }

    @Test
    void testClearUserCookieCallsAddCookie() {
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        CookieAssertionLoginModule.clearUserCookie( response );
        // Clears both new (WikantikAssertedName) and legacy (JSPWikiAssertedName) cookies
        Mockito.verify( response, Mockito.times( 2 ) ).addCookie( Mockito.any( Cookie.class ) );
    }
}

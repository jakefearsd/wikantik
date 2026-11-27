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
import com.wikantik.TestEngine;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remember-me cookie as a credential: where its token may point, how long it lives, and what logout expires.
 */
class CookieAuthenticationLoginModuleCookieSecurityTest {

    private static List< Cookie > capture( final HttpServletResponse response ) {
        final List< Cookie > cookies = new ArrayList<>();
        Mockito.doAnswer( inv -> {
            cookies.add( inv.getArgument( 0 ) );
            return null;
        } ).when( response ).addCookie( Mockito.any( Cookie.class ) );
        return cookies;
    }

    private static boolean loginWith( final TestEngine engine, final String token ) throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[] { new Cookie( "WikantikUID", token ) } ).when( request ).getCookies();
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new CookieAuthenticationLoginModule();
        module.initialize( new Subject(), handler, new HashMap<>(), new HashMap<>() );
        return module.login();
    }

    @Test
    void aTokenPointingIntoASiblingDirectoryThatSharesTheCookieDirectoryPrefixIsRefused() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final File work = new File( engine.getWorkDir() );
        final File cookieDir = new File( work, CookieAuthenticationLoginModule.COOKIE_DIR );
        assertTrue( cookieDir.exists() || cookieDir.mkdirs() );
        final File sibling = new File( work, CookieAuthenticationLoginModule.COOKIE_DIR + "-sibling" );
        assertTrue( sibling.exists() || sibling.mkdirs() );
        final File siblingFile = new File( sibling, "token" );
        Files.writeString( siblingFile.toPath(), "admin", StandardCharsets.UTF_8 );
        try {
            assertFalse( loginWith( engine, "../" + sibling.getName() + "/token" ),
                    "a token that escapes the cookie directory must never authenticate" );
        } finally {
            Files.deleteIfExists( siblingFile.toPath() );
            Files.deleteIfExists( sibling.toPath() );
        }
    }

    @Test
    void theRememberMeCookieLivesForTheConfiguredNumberOfDays() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.setProperty( CookieAuthenticationLoginModule.PROP_LOGIN_EXPIRY_DAYS, "3" );
        final TestEngine engine = new TestEngine( props );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final List< Cookie > cookies = capture( response );

        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne", true );

        assertEquals( 1, cookies.size() );
        assertEquals( 3 * 24 * 60 * 60, cookies.get( 0 ).getMaxAge() );
    }

    @Test
    void theRememberMeCookieDefaultsToFourteenDays() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final List< Cookie > cookies = capture( response );

        CookieAuthenticationLoginModule.setLoginCookie( engine, response, "janne", true );

        assertEquals( 14 * 24 * 60 * 60, cookies.get( 0 ).getMaxAge() );
    }

    @Test
    void logoutExpiresBothTheCurrentAndTheLegacyCookieWithMatchingAttributes() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( true ).when( request ).isSecure();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final List< Cookie > cookies = capture( response );

        CookieAuthenticationLoginModule.clearLoginCookie( engine, request, response );

        assertEquals( 2, cookies.size() );
        assertEquals( "WikantikUID", cookies.get( 0 ).getName() );
        for( final Cookie c : cookies ) {
            assertEquals( 0, c.getMaxAge(), c.getName() );
            assertEquals( "", c.getValue(), c.getName() );
            assertTrue( c.isHttpOnly(), c.getName() );
            assertTrue( c.getSecure(), c.getName() + " must carry the request's Secure flag so the browser overwrites it" );
            assertEquals( "/", c.getPath(), c.getName() );
        }
    }

    @Test
    void logoutOverAPlainHttpRequestExpiresNonSecureCookies() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final HttpServletRequest http = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( false ).when( http ).isSecure();
        final HttpServletResponse response = HttpMockFactory.createHttpResponse();
        final List< Cookie > cookies = capture( response );

        CookieAuthenticationLoginModule.clearLoginCookie( engine, http, response );

        assertEquals( 2, cookies.size() );
        for( final Cookie c : cookies ) {
            assertFalse( c.getSecure(), c.getName() );
            assertEquals( 0, c.getMaxAge() );
        }
    }

    @Test
    void logoutDeletesTheServerSideTokenSoItCanNeverAuthenticateAgain() throws Exception {
        final TestEngine engine = new TestEngine( TestEngine.getTestProperties() );
        final HttpServletResponse issued = HttpMockFactory.createHttpResponse();
        final List< Cookie > issuedCookies = capture( issued );
        CookieAuthenticationLoginModule.setLoginCookie( engine, issued, "janne", true );
        final String token = issuedCookies.get( 0 ).getValue();
        assertTrue( loginWith( engine, token ), "precondition: the freshly issued token works" );

        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( new Cookie[] { new Cookie( "WikantikUID", token ) } ).when( request ).getCookies();
        CookieAuthenticationLoginModule.clearLoginCookie( engine, request, HttpMockFactory.createHttpResponse() );

        final File file = new File( new File( engine.getWorkDir(), CookieAuthenticationLoginModule.COOKIE_DIR ), token );
        assertFalse( file.exists() );
        assertFalse( loginWith( engine, token ) );
    }
}

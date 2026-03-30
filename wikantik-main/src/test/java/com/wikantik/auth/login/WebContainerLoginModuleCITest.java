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
import com.wikantik.auth.WikiPrincipal;
import com.wikantik.auth.user.XMLUserDatabase;
import jakarta.servlet.http.HttpServletRequest;
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
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for {@link WebContainerLoginModule} targeting
 * branches not exercised by the existing {@code WebContainerLoginModuleTest}:
 * the remoteUser path, null-request path, IOException path, and
 * UnsupportedCallbackException path.
 */
class WebContainerLoginModuleCITest {

    private TestEngine engine;
    private Subject subject;

    @BeforeEach
    void setUp() throws Exception {
        final Properties props = TestEngine.getTestProperties();
        props.put( XMLUserDatabase.PROP_USERDATABASE, "target/test-classes/userdatabase.xml" );
        engine = new TestEngine( props );
        subject = new Subject();
    }

    // --- remoteUser path: no UserPrincipal but a remoteUser exists ---

    @Test
    void testLoginSucceedsViaRemoteUser() throws LoginException {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        // No user principal
        Mockito.doReturn( null ).when( request ).getUserPrincipal();
        Mockito.doReturn( "janne" ).when( request ).getRemoteUser();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new WebContainerLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertTrue( module.login() );
        module.commit();

        final Set<Principal> principals = subject.getPrincipals();
        assertEquals( 1, principals.size() );
        assertEquals( "janne", principals.iterator().next().getName() );
    }

    // --- FailedLoginException when neither userPrincipal nor remoteUser is present ---

    @Test
    void testLoginFailsWhenNoPrincipalAndNoRemoteUser() {
        final HttpServletRequest request = HttpMockFactory.createHttpRequest();
        Mockito.doReturn( null ).when( request ).getUserPrincipal();
        Mockito.doReturn( null ).when( request ).getRemoteUser();

        final CallbackHandler handler = new WebContainerCallbackHandler( engine, request );
        final LoginModule module = new WebContainerLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( FailedLoginException.class, module::login );
    }

    // --- null request → LoginException ---

    @Test
    void testLoginThrowsWhenRequestIsNull() {
        final CallbackHandler handler = new WebContainerCallbackHandler( engine, null );
        final LoginModule module = new WebContainerLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertThrows( LoginException.class, module::login );
    }

    // --- IOException from handler → login() returns false ---

    @Test
    void testLoginReturnsFalseOnIOException() throws LoginException {
        final CallbackHandler handler = callbacks -> {
            throw new IOException( "simulated IO error" );
        };
        final LoginModule module = new WebContainerLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
    }

    // --- UnsupportedCallbackException from handler → login() returns false ---

    @Test
    void testLoginReturnsFalseOnUnsupportedCallback() throws LoginException {
        final CallbackHandler handler = callbacks -> {
            throw new UnsupportedCallbackException( callbacks[0] );
        };
        final LoginModule module = new WebContainerLoginModule();
        module.initialize( subject, handler, new HashMap<>(), new HashMap<>() );
        assertFalse( module.login() );
    }
}
